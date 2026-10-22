/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.core.cherrypick;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Where an execution stands after its commits apply, and the work branch as it was last seen.
 *
 * <p>Stored whole, so the task can be drawn without reading Git. A delivering execution carries
 * its branch through build, publication, CI, squash and merge; one started by a backport chain
 * stops after {@link Stage#APPLY} and leaves delivery to the chain.
 */
public record CherryPickLifecycle(
        Stage stage,
        boolean delivers,
        String baseSha,
        Optional<Hold> hold,
        List<BranchCommit> commits,
        List<Rebase> rebases,
        List<String> ciRunIds,
        Optional<Squash> squash,
        boolean readyForReview,
        List<Note> notes)
{
    public static final int MAX_NOTES = 20;

    public CherryPickLifecycle
    {
        requireNonNull(stage, "stage is null");
        checkSha(baseSha, "baseSha");
        requireNonNull(hold, "hold is null");
        commits = ImmutableList.copyOf(commits);
        rebases = ImmutableList.copyOf(rebases);
        ciRunIds = ImmutableList.copyOf(ciRunIds);
        requireNonNull(squash, "squash is null");
        notes = ImmutableList.copyOf(notes);
        checkArgument(delivers || stage == Stage.APPLY, "only a delivering execution moves past applying");
        checkArgument(notes.size() <= MAX_NOTES, "notes exceeds " + MAX_NOTES + " values");
    }

    public static CherryPickLifecycle start(boolean delivers, String baseSha)
    {
        return new CherryPickLifecycle(Stage.APPLY, delivers, baseSha, Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), false,
                ImmutableList.of());
    }

    public CherryPickLifecycle inStage(Stage next)
    {
        return new CherryPickLifecycle(next, delivers, baseSha, hold, commits, rebases, ciRunIds, squash, readyForReview, notes);
    }

    public CherryPickLifecycle held(Hold.Reason reason, String summary, Instant at)
    {
        return new CherryPickLifecycle(stage, delivers, baseSha, Optional.of(new Hold(reason, summary, at)), commits, rebases, ciRunIds, squash, readyForReview, notes);
    }

    public CherryPickLifecycle released()
    {
        return new CherryPickLifecycle(stage, delivers, baseSha, Optional.empty(), commits, rebases, ciRunIds, squash, readyForReview, notes);
    }

    public CherryPickLifecycle withBranch(String base, List<BranchCommit> branch)
    {
        return new CherryPickLifecycle(stage, delivers, base, hold, branch, rebases, ciRunIds, squash, readyForReview, notes);
    }

    /** Replaces the rebase with the same number, or adds the next one. */
    public CherryPickLifecycle withRebase(Rebase rebase)
    {
        List<Rebase> updated = rebases.stream().anyMatch(value -> value.number() == rebase.number())
                ? rebases.stream().map(value -> value.number() == rebase.number() ? rebase : value).collect(toImmutableList())
                : ImmutableList.<Rebase>builder().addAll(rebases).add(rebase).build();
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, updated, ciRunIds, squash, readyForReview, notes);
    }

    public CherryPickLifecycle withCiRun(String runId)
    {
        checkText(runId, "runId", 128);
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, rebases,
                ImmutableList.<String>builder().addAll(ciRunIds).add(runId).build(), squash, readyForReview, notes);
    }

    public CherryPickLifecycle withSquash(Optional<Squash> value)
    {
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, rebases, ciRunIds, value, readyForReview, notes);
    }

    public CherryPickLifecycle markedReady()
    {
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, rebases, ciRunIds, squash, true, notes);
    }

    public CherryPickLifecycle withNote(Note note)
    {
        checkArgument(notes.size() < MAX_NOTES, "Too many messages are waiting; wait for the agent's next step");
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, rebases, ciRunIds, squash, readyForReview,
                ImmutableList.<Note>builder().addAll(notes).add(note).build());
    }

    public CherryPickLifecycle withoutNotes()
    {
        return new CherryPickLifecycle(stage, delivers, baseSha, hold, commits, rebases, ciRunIds, squash, readyForReview, ImmutableList.of());
    }

    public List<BranchCommit> fixups()
    {
        return commits.stream().filter(commit -> commit.fixupOf().isPresent()).collect(toImmutableList());
    }

    public Optional<String> currentCiRunId()
    {
        return ciRunIds.isEmpty() ? Optional.empty() : Optional.of(ciRunIds.getLast());
    }

    public Optional<Rebase> liveRebase()
    {
        return rebases.stream().filter(rebase -> rebase.state() == Rebase.State.RUNNING).findFirst();
    }

    /** In order: the program moves forward, and back to CI only when new fixups or a rebase need checking. */
    public enum Stage
    {
        APPLY,
        BUILD,
        PUBLISH,
        CI_FIX,
        SQUASH,
        MERGE
    }

    /** Stops advancing until the user resumes; the worktree and remote state are kept. */
    public record Hold(Reason reason, String summary, Instant at)
    {
        public Hold
        {
            requireNonNull(reason, "reason is null");
            checkText(summary, "summary", 8 * 1024);
            requireNonNull(at, "at is null");
        }

        public enum Reason
        {
            USER,
            PUBLISH_FAILED,
            CI_FAILED,
            REBASE_FAILED,
            SQUASH_FAILED,
            PULL_REQUEST_CLOSED
        }
    }

    /**
     * One commit on the work branch, oldest first: a copied source commit, or the single
     * {@code fixup!} directly after the commit it belongs to.
     */
    public record BranchCommit(String sha, String subject, Optional<String> sourceSha, Optional<String> fixupOf, Optional<Origin> origin)
    {
        public BranchCommit
        {
            checkSha(sha, "sha");
            checkText(subject, "subject", 1000);
            checkOptionalSha(sourceSha, "sourceSha");
            checkOptionalSha(fixupOf, "fixupOf");
            requireNonNull(origin, "origin is null");
            checkArgument(sourceSha.isPresent() != fixupOf.isPresent(), "a branch commit is either a copy or a fixup");
            checkArgument(fixupOf.isPresent() == origin.isPresent(), "only a fixup has an origin");
        }

        /** The stage that first added the fixup; later stages amend the same commit. */
        public enum Origin
        {
            CONFLICT,
            BUILD,
            CI
        }
    }

    /** One replay of the work branch onto a moved target. */
    public record Rebase(
            int number,
            String baseSha,
            int targetMoved,
            Optional<String> rebaseId,
            State state,
            Optional<String> summary,
            Instant startedAt,
            Optional<Instant> endedAt)
    {
        public Rebase
        {
            checkArgument(number >= 1, "number is not positive");
            checkSha(baseSha, "baseSha");
            checkArgument(targetMoved >= 0, "targetMoved is negative");
            checkOptionalText(rebaseId, "rebaseId", 128);
            requireNonNull(state, "state is null");
            checkOptionalText(summary, "summary", 8 * 1024);
            requireNonNull(startedAt, "startedAt is null");
            requireNonNull(endedAt, "endedAt is null");
        }

        public Rebase ended(State result, String text, Instant at)
        {
            return new Rebase(number, baseSha, targetMoved, rebaseId, result, Optional.of(text), startedAt, Optional.of(at));
        }

        public enum State
        {
            RUNNING,
            DONE,
            FAILED
        }
    }

    /** The user's approval to fold the fixups, and what folding produced. */
    public record Squash(String approvedHead, Instant approvedAt, Optional<String> resultHead, List<BranchCommit> fixups)
    {
        public Squash
        {
            checkSha(approvedHead, "approvedHead");
            requireNonNull(approvedAt, "approvedAt is null");
            checkOptionalSha(resultHead, "resultHead");
            fixups = ImmutableList.copyOf(fixups);
        }
    }

    /** A message kept for the agent's next step. */
    public record Note(String text, Instant at)
    {
        public Note
        {
            checkText(text, "text", 16 * 1024);
            requireNonNull(at, "at is null");
        }
    }
}
