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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.CommitDetailEntry;
import com.bytequay.app.service.local.GitRunner.CommitEntry;
import com.bytequay.app.service.local.HistoryRewriter;
import com.bytequay.app.service.local.HistoryRewriter.RewriteEntry;
import com.bytequay.app.service.local.HistoryRewriter.RewritePlan;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Reorders a proven base-owned CI repair below a Task's frozen commit series.
 * The agent only appends ordinary commits; this owner performs and verifies
 * the one deterministic history mutation without publishing it.
 */
@Component
public final class BaseCiHistoryRewriter
{
    private final GitRunner git;
    private final HistoryRewriter history;

    public BaseCiHistoryRewriter(GitRunner git)
    {
        this.git = requireNonNull(git, "git is null");
        this.history = new HistoryRewriter(git);
    }

    public Result rewrite(Request request)
            throws IOException, InterruptedException
    {
        requireNonNull(request, "request is null");
        validateIdentity(request, true);
        Prepared prepared = prepare(request);

        List<RewriteEntry> entries = new ArrayList<>();
        entries.add(new RewriteEntry(prepared.repairInputs(), null));
        prepared.activeOriginals().forEach(commit ->
                entries.add(new RewriteEntry(List.of(commit), null)));

        try {
            HistoryRewriter.RewriteResult rewritten = history.rewrite(
                    request.worktree(),
                    new RewritePlan(
                            request.branch(), request.baseSha(),
                            List.copyOf(entries), false));
            if (rewritten.pushed()) {
                throw new IllegalStateException(
                        "base CI history rewrite unexpectedly published");
            }
            return verify(
                    request, prepared.repeatedRepair(),
                    prepared.repairInputs(), prepared.activeOriginals(),
                    prepared.originals(), prepared.inputTreeSha(),
                    rewritten.headSha());
        }
        catch (IOException | InterruptedException | RuntimeException failure) {
            restoreChangedHead(request, failure);
            throw failure;
        }
    }

    /**
     * Reconstructs the exact proof after the deterministic rewrite completed
     * but its result was not durably accepted. This is a read-only probe: the
     * current branch must already be the verified rewritten subject.
     */
    public Result recover(Request request)
            throws IOException, InterruptedException
    {
        requireNonNull(request, "request is null");
        validateIdentity(request, false);
        String currentHead = git.headSha(request.worktree());
        if (currentHead.equals(request.stageTurnOutputHeadSha())) {
            throw rejected("history rewrite is not observable");
        }
        Prepared prepared = prepare(request);
        return verify(
                request, prepared.repeatedRepair(), prepared.repairInputs(),
                prepared.activeOriginals(), prepared.originals(),
                prepared.inputTreeSha(), currentHead);
    }

    private Prepared prepare(Request request)
            throws IOException, InterruptedException
    {
        List<String> frozenRange = git.commitShasInRange(
                request.worktree(), request.baseSha(),
                request.frozenOriginalHeadSha());
        validateLinear(
                request.worktree(), request.baseSha(), frozenRange,
                "frozen Task history");

        List<String> frozenExistingRepair;
        if (frozenRange.equals(request.originalCommitShas())) {
            frozenExistingRepair = List.of();
        }
        else if (frozenRange.size() == request.originalCommitShas().size() + 1
                && frozenRange.subList(1, frozenRange.size())
                        .equals(request.originalCommitShas())) {
            frozenExistingRepair = List.of(frozenRange.getFirst());
        }
        else {
            throw rejected(
                    "frozen history is not the exact original Task series"
                            + " with at most one existing base repair");
        }

        List<String> inputRange = git.commitShasInRange(
                request.worktree(), request.baseSha(),
                request.stageTurnOutputHeadSha());
        validateLinear(
                request.worktree(), request.baseSha(), inputRange,
                "StageTurn output history");
        List<CommitSnapshot> originals = snapshots(
                request.worktree(), request.originalCommitShas());
        boolean repeatedRepair;
        List<String> existingRepair;
        List<String> activeOriginals;
        List<String> appendedRepair;
        if (inputRange.size() > frozenRange.size()
                && inputRange.subList(0, frozenRange.size())
                        .equals(frozenRange)) {
            repeatedRepair = !frozenExistingRepair.isEmpty();
            existingRepair = frozenExistingRepair;
            activeOriginals = request.originalCommitShas();
            appendedRepair = List.copyOf(
                    inputRange.subList(frozenRange.size(), inputRange.size()));
        }
        else if (frozenExistingRepair.isEmpty()
                && inputRange.size() > originals.size() + 1) {
            repeatedRepair = true;
            existingRepair = List.of(inputRange.getFirst());
            activeOriginals = List.copyOf(
                    inputRange.subList(1, originals.size() + 1));
            assertSameOriginalSeries(
                    originals, snapshots(request.worktree(), activeOriginals));
            appendedRepair = List.copyOf(
                    inputRange.subList(originals.size() + 1, inputRange.size()));
        }
        else {
            throw rejected(
                    "StageTurn output must append one or more repair commits"
                            + " to the exact current Task history");
        }

        List<String> repairInputs = new ArrayList<>(existingRepair);
        repairInputs.addAll(appendedRepair);
        String inputTreeSha = git.commitTreeSha(
                request.worktree(), request.stageTurnOutputHeadSha());
        return new Prepared(
                repeatedRepair, List.copyOf(repairInputs), activeOriginals,
                originals, inputTreeSha);
    }

    private Result verify(
            Request request,
            boolean repeatedRepair,
            List<String> repairInputs,
            List<String> activeOriginals,
            List<CommitSnapshot> originals,
            String inputTreeSha,
            String rewrittenHeadSha)
            throws IOException, InterruptedException
    {
        String actualHead = git.headSha(request.worktree());
        if (!actualHead.equals(rewrittenHeadSha)) {
            throw new IllegalStateException(
                    "rewriter result does not identify the checked-out head");
        }
        List<String> outputRange = git.commitShasInRange(
                request.worktree(), request.baseSha(), actualHead);
        validateLinear(
                request.worktree(), request.baseSha(), outputRange,
                "rewritten history");
        if (outputRange.size() != originals.size() + 1) {
            throw new IllegalStateException(
                    "rewritten history must contain one repair and the exact"
                            + " original Task commit count");
        }
        String repairSha = outputRange.getFirst();
        List<String> replayedShas = List.copyOf(
                outputRange.subList(1, outputRange.size()));
        if (!git.commitTreeSha(request.worktree(), actualHead)
                .equals(inputTreeSha)) {
            throw new IllegalStateException(
                    "rewritten history changed the StageTurn output tree");
        }

        List<CommitProof> commits = new ArrayList<>();
        for (int index = 0; index < originals.size(); index++) {
            CommitSnapshot before = originals.get(index);
            CommitSnapshot after = snapshot(
                    request.worktree(), replayedShas.get(index));
            if (!before.sameMessageAndAuthor(after)
                    || !before.patchId().equals(after.patchId())) {
                throw new IllegalStateException(
                        "rewritten Task commit changed message, author, or patch at index "
                                + index);
            }
            commits.add(new CommitProof(
                    before.sha(), activeOriginals.get(index), after.sha(),
                    before.patchId(), after.patchId(), before.messageDigest(),
                    before.authorName(), before.authorEmail(),
                    before.authoredAt()));
        }

        String originalPatchDigest = patchSeriesDigest(
                originals.stream().map(CommitSnapshot::patchId).toList());
        String rewrittenPatchDigest = patchSeriesDigest(
                commits.stream().map(CommitProof::outputPatchId).toList());
        if (!originalPatchDigest.equals(rewrittenPatchDigest)) {
            throw new IllegalStateException(
                    "rewritten Task patch-series digest changed");
        }
        Proof proof = new Proof(
                request.baseSha(), request.frozenOriginalHeadSha(),
                request.stageTurnOutputHeadSha(),
                request.originalCommitShas(), List.copyOf(repairInputs),
                repairSha, actualHead, git.stablePatchId(
                        request.worktree(), repairSha),
                List.copyOf(commits), originalPatchDigest,
                rewrittenPatchDigest, inputTreeSha,
                git.commitTreeSha(request.worktree(), actualHead),
                repeatedRepair);
        return new Result(actualHead, proof);
    }

    private void validateIdentity(Request request, boolean requireStageTurnHead)
            throws IOException, InterruptedException
    {
        if (request.branch().isBlank()) {
            throw rejected("branch is blank");
        }
        if (!git.currentBranch(request.worktree()).equals(request.branch())) {
            throw rejected("requested branch is not checked out");
        }
        exactCommit(request.worktree(), request.baseSha(), "base");
        exactCommit(
                request.worktree(), request.frozenOriginalHeadSha(),
                "frozen original head");
        exactCommit(
                request.worktree(), request.stageTurnOutputHeadSha(),
                "StageTurn output head");
        if (requireStageTurnHead && !git.headSha(request.worktree())
                .equals(request.stageTurnOutputHeadSha())) {
            throw rejected("StageTurn output is not the checked-out head");
        }
        if (request.originalCommitShas().isEmpty()) {
            throw rejected("original Task commit series is empty");
        }
        if (new HashSet<>(request.originalCommitShas()).size()
                != request.originalCommitShas().size()) {
            throw rejected("original Task commit series contains duplicates");
        }
        for (String commit : request.originalCommitShas()) {
            exactCommit(request.worktree(), commit, "original Task commit");
        }
        if (!request.originalCommitShas().getLast()
                .equals(request.frozenOriginalHeadSha())) {
            throw rejected(
                    "frozen original head is not the last original Task commit");
        }
    }

    private void exactCommit(Path worktree, String sha, String label)
            throws IOException, InterruptedException
    {
        if (!sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || git.resolveCommitSha(worktree, sha)
                        .filter(sha::equals).isEmpty()) {
            throw rejected(label + " is not an exact commit SHA");
        }
    }

    private void validateLinear(
            Path worktree,
            String baseSha,
            List<String> commits,
            String label)
            throws IOException, InterruptedException
    {
        String expectedParent = baseSha;
        for (String commit : commits) {
            List<String> parents = git.commitParentShas(worktree, commit);
            if (parents.size() != 1 || !parents.getFirst().equals(expectedParent)) {
                throw rejected(label + " is not a complete linear history");
            }
            expectedParent = commit;
        }
    }

    private List<CommitSnapshot> snapshots(
            Path worktree, List<String> commits)
            throws IOException, InterruptedException
    {
        List<CommitSnapshot> result = new ArrayList<>();
        for (String commit : commits) {
            result.add(snapshot(worktree, commit));
        }
        return List.copyOf(result);
    }

    private CommitSnapshot snapshot(Path worktree, String sha)
            throws IOException, InterruptedException
    {
        CommitEntry entry = git.listCommits(worktree, sha, 1).stream()
                .filter(commit -> commit.sha().equals(sha))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "commit metadata is unavailable: " + sha));
        CommitDetailEntry detail = git.commitDetail(worktree, sha)
                .orElseThrow(() -> new IllegalStateException(
                        "commit message is unavailable: " + sha));
        return new CommitSnapshot(
                sha, git.stablePatchId(worktree, sha),
                detail.subject(), detail.body(),
                entry.authorName(), entry.authorEmail(), entry.authoredAt());
    }

    private void assertSameOriginalSeries(
            List<CommitSnapshot> frozen,
            List<CommitSnapshot> active)
    {
        if (frozen.size() != active.size()) {
            throw rejected("replayed Task commit count changed");
        }
        for (int index = 0; index < frozen.size(); index++) {
            CommitSnapshot expected = frozen.get(index);
            CommitSnapshot actual = active.get(index);
            if (!expected.sameMessageAndAuthor(actual)
                    || !expected.patchId().equals(actual.patchId())) {
                throw rejected(
                        "replayed Task commit changed message, author, or patch"
                                + " at index " + index);
            }
        }
    }

    private void restoreChangedHead(Request request, Exception failure)
    {
        boolean interrupted = failure instanceof InterruptedException;
        if (interrupted) {
            Thread.interrupted();
        }
        try {
            if (git.currentBranch(request.worktree()).equals(request.branch())
                    && !git.headSha(request.worktree())
                            .equals(request.stageTurnOutputHeadSha())) {
                git.resetHard(
                        request.worktree(), request.stageTurnOutputHeadSha());
            }
        }
        catch (IOException | InterruptedException | RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static IllegalArgumentException rejected(String reason)
    {
        return new IllegalArgumentException(
                "base CI history rewrite rejected: " + reason);
    }

    private static String patchSeriesDigest(List<String> patchIds)
    {
        return digest(String.join("\n", patchIds));
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Request(
            Path worktree,
            String branch,
            String baseSha,
            String frozenOriginalHeadSha,
            List<String> originalCommitShas,
            String stageTurnOutputHeadSha)
    {
        public Request
        {
            requireNonNull(worktree, "worktree is null");
            requireNonNull(branch, "branch is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(
                    frozenOriginalHeadSha, "frozenOriginalHeadSha is null");
            originalCommitShas = List.copyOf(requireNonNull(
                    originalCommitShas, "originalCommitShas is null"));
            requireNonNull(
                    stageTurnOutputHeadSha, "stageTurnOutputHeadSha is null");
        }
    }

    public record Result(String headSha, Proof proof)
    {
        public Result
        {
            requireNonNull(headSha, "headSha is null");
            requireNonNull(proof, "proof is null");
        }
    }

    public record Proof(
            String baseSha,
            String frozenOriginalHeadSha,
            String stageTurnOutputHeadSha,
            List<String> originalCommitShas,
            List<String> foldedRepairInputShas,
            String repairCommitSha,
            String rewrittenHeadSha,
            String repairPatchId,
            List<CommitProof> originalCommitProofs,
            String originalPatchSeriesDigest,
            String rewrittenPatchSeriesDigest,
            String inputTreeSha,
            String outputTreeSha,
            boolean repeatedRepair)
    {
        public Proof
        {
            originalCommitShas = List.copyOf(originalCommitShas);
            foldedRepairInputShas = List.copyOf(foldedRepairInputShas);
            originalCommitProofs = List.copyOf(originalCommitProofs);
        }
    }

    public record CommitProof(
            String frozenSha,
            String inputSha,
            String outputSha,
            String inputPatchId,
            String outputPatchId,
            String messageDigest,
            String authorName,
            String authorEmail,
            String authoredAt) {}

    private record Prepared(
            boolean repeatedRepair,
            List<String> repairInputs,
            List<String> activeOriginals,
            List<CommitSnapshot> originals,
            String inputTreeSha) {}

    private record CommitSnapshot(
            String sha,
            String patchId,
            String subject,
            String body,
            String authorName,
            String authorEmail,
            String authoredAt)
    {
        private boolean sameMessageAndAuthor(CommitSnapshot other)
        {
            return subject.equals(other.subject)
                    && body.equals(other.body)
                    && authorName.equals(other.authorName)
                    && authorEmail.equals(other.authorEmail)
                    && authoredAt.equals(other.authoredAt);
        }

        private String messageDigest()
        {
            return digest(subject + "\n\n" + body);
        }
    }
}
