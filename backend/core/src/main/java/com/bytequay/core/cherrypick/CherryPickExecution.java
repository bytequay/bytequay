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

import com.bytequay.core.cherrypick.CherryPickItem.Result;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.bytequay.core.cherrypick.CherryPickChecks.checkOptionalText;
import static com.bytequay.core.cherrypick.CherryPickChecks.checkSha;
import static com.bytequay.core.cherrypick.CherryPickChecks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Complete durable projection of one confirmed cherry-pick range.
 *
 * <p>Git's sequencer owns what runs next; this aggregate owns what the result
 * was. The cursor is therefore derived from item results rather than stored,
 * so a crash cannot leave two disagreeing positions.
 */
public record CherryPickExecution(
        CherryPickExecutionId id,
        String startIdempotencyKey,
        Workspace workspace,
        Optional<PullRequestDraft> pullRequestDraft,
        Optional<Delivery> delivery,
        Range range,
        String currentTargetSha,
        Optional<String> agentTaskId,
        Optional<String> currentTurnId,
        Status status,
        Optional<Step> currentStep,
        Optional<PauseReason> pauseReason,
        Optional<FinishReason> finishReason,
        Optional<String> summary,
        Optional<ActionJournal> journal,
        Optional<RangeReview> rangeReview,
        List<CherryPickItem> items,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> terminalAt)
{
    private static final int MAX_SUMMARY_CHARACTERS = 8 * 1024;

    public CherryPickExecution
    {
        requireNonNull(id, "id is null");
        checkText(startIdempotencyKey, "startIdempotencyKey", 200);
        requireNonNull(workspace, "workspace is null");
        requireNonNull(pullRequestDraft, "pullRequestDraft is null");
        requireNonNull(delivery, "delivery is null");
        requireNonNull(range, "range is null");
        checkSha(currentTargetSha, "currentTargetSha");
        checkOptionalText(agentTaskId, "agentTaskId", 128);
        checkOptionalText(currentTurnId, "currentTurnId", 128);
        requireNonNull(status, "status is null");
        requireNonNull(currentStep, "currentStep is null");
        requireNonNull(pauseReason, "pauseReason is null");
        requireNonNull(finishReason, "finishReason is null");
        checkOptionalText(summary, "summary", MAX_SUMMARY_CHARACTERS);
        requireNonNull(journal, "journal is null");
        requireNonNull(rangeReview, "rangeReview is null");
        requireNonNull(items, "items is null");
        items = ImmutableList.copyOf(items);
        validateItems(items);
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(terminalAt, "terminalAt is null");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        terminalAt.ifPresent(value -> checkArgument(!value.isBefore(createdAt), "terminalAt is before createdAt"));
        validateStatus(status, currentStep, pauseReason, finishReason, summary, currentTurnId, terminalAt, items);
        delivery.ifPresent(value -> {
            checkArgument(status == Status.FINISHED && finishReason.orElseThrow() == FinishReason.COMPLETED,
                    "delivery belongs to an incomplete execution");
            checkArgument(pullRequestDraft.isPresent(), "delivery has no pull-request draft");
        });
    }

    public static CherryPickExecution create(
            CherryPickExecutionId id,
            String startIdempotencyKey,
            Workspace workspace,
            Optional<PullRequestDraft> pullRequestDraft,
            Range range,
            List<CherryPickItem> items,
            Instant now)
    {
        requireNonNull(range, "range is null");
        return new CherryPickExecution(
                id,
                startIdempotencyKey,
                workspace,
                pullRequestDraft,
                Optional.empty(),
                range,
                range.initialTargetSha(),
                Optional.empty(),
                Optional.empty(),
                Status.RUNNING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                items,
                0,
                now,
                now,
                Optional.empty());
    }

    /**
     * Returns the item the sequence would work on next.
     *
     * <p>Derived, never stored: it is the lowest-position item still
     * {@link Result#PENDING}.
     *
     * @return next pending item, or empty when every item has a result
     */
    public Optional<CherryPickItem> cursor()
    {
        return items.stream().filter(CherryPickItem::pending).findFirst();
    }

    /** Returns every item the sequence still has to apply, in plan order. */
    public List<CherryPickItem> pendingItems()
    {
        return items.stream().filter(CherryPickItem::pending).collect(toImmutableList());
    }

    public CherryPickExecution withAgentTask(String taskId, Instant now)
    {
        requireRunning(now);
        checkText(taskId, "taskId", 128);
        checkState(agentTaskId.isEmpty(), "execution already owns an agent task");
        return edit(now, draft -> draft.agentTaskId = Optional.of(taskId));
    }

    public CherryPickExecution withStep(Step step, Instant now)
    {
        requireRunning(now);
        requireNonNull(step, "step is null");
        return edit(now, draft -> draft.currentStep = Optional.of(step));
    }

    public CherryPickExecution withTurn(String turnId, Instant now)
    {
        requireRunning(now);
        checkText(turnId, "turnId", 128);
        return edit(now, draft -> draft.currentTurnId = Optional.of(turnId));
    }

    public CherryPickExecution withoutTurn(Instant now)
    {
        requireRunning(now);
        return edit(now, draft -> draft.currentTurnId = Optional.empty());
    }

    public CherryPickExecution withJournal(ActionJournal entry, Instant now)
    {
        requireRunning(now);
        requireNonNull(entry, "entry is null");
        return edit(now, draft -> draft.journal = Optional.of(entry));
    }

    public CherryPickExecution withoutJournal(Instant now)
    {
        requireMutable(now);
        return edit(now, draft -> draft.journal = Optional.empty());
    }

    /** Records the target commit created for one source item. */
    public CherryPickExecution recordApplied(String sourceSha, String targetSha, Instant now)
    {
        return recordApplied(sourceSha, targetSha, targetSha, now);
    }

    /**
     * Records a conflicted source as its target commit followed by the agent's
     * fixup, while advancing the execution tip to the fixup.
     */
    public CherryPickExecution recordAppliedWithFixup(
            String sourceSha,
            String targetSha,
            String fixupSha,
            Instant now)
    {
        return recordApplied(sourceSha, targetSha, fixupSha, now);
    }

    private CherryPickExecution recordApplied(
            String sourceSha,
            String targetSha,
            String resultingHeadSha,
            Instant now)
    {
        requireRunning(now);
        checkSha(sourceSha, "sourceSha");
        checkSha(targetSha, "targetSha");
        checkSha(resultingHeadSha, "resultingHeadSha");
        return edit(now, draft -> {
            draft.items = replace(sourceSha, item -> item.applied(targetSha));
            draft.currentTargetSha = resultingHeadSha;
        });
    }

    public CherryPickExecution recordEmptySkipped(String sourceSha, Instant now)
    {
        requireRunning(now);
        checkSha(sourceSha, "sourceSha");
        return edit(now, draft -> draft.items = replace(sourceSha, CherryPickItem::emptySkipped));
    }

    public CherryPickExecution recordConflict(String sourceSha, List<CherryPickItem.ConflictPath> paths, Instant now)
    {
        requireRunning(now);
        checkSha(sourceSha, "sourceSha");
        requireNonNull(paths, "paths is null");
        return edit(now, draft -> draft.items = replace(sourceSha, item -> item.withConflict(paths)));
    }

    public CherryPickExecution recordAgentTurnStarted(String sourceSha, Instant now)
    {
        requireRunning(now);
        checkSha(sourceSha, "sourceSha");
        return edit(now, draft -> draft.items = replace(sourceSha, CherryPickItem::withAgentTurnStarted));
    }

    public CherryPickExecution pause(PauseReason reason, String pausedSummary, Instant now)
    {
        requireRunning(now);
        requireNonNull(reason, "reason is null");
        checkText(pausedSummary, "pausedSummary", MAX_SUMMARY_CHARACTERS);
        return edit(now, draft -> {
            draft.status = Status.PAUSED;
            draft.currentStep = Optional.empty();
            draft.currentTurnId = Optional.empty();
            draft.pauseReason = Optional.of(reason);
            draft.summary = Optional.of(pausedSummary);
        });
    }

    public CherryPickExecution resume(Instant now)
    {
        requireMutable(now);
        checkState(status == Status.PAUSED, "execution is not paused");
        return edit(now, draft -> {
            draft.status = Status.RUNNING;
            draft.pauseReason = Optional.empty();
            draft.summary = Optional.empty();
        });
    }

    public CherryPickExecution finish(FinishReason reason, String finishedSummary, Instant now)
    {
        requireMutable(now);
        requireNonNull(reason, "reason is null");
        checkText(finishedSummary, "finishedSummary", MAX_SUMMARY_CHARACTERS);
        return edit(now, draft -> {
            draft.status = Status.FINISHED;
            draft.currentStep = Optional.empty();
            draft.currentTurnId = Optional.empty();
            draft.journal = Optional.empty();
            draft.pauseReason = Optional.empty();
            draft.finishReason = Optional.of(reason);
            draft.summary = Optional.of(finishedSummary);
            draft.terminalAt = Optional.of(now);
        });
    }

    /**
     * Stores the human-readable range review.
     *
     * <p>Read by a person, never by the program: {@code range-diff} is
     * heuristic, so parsing it to decide completion would make a guess load
     * bearing.
     *
     * @param review bounded range-diff output
     * @param now update time
     * @return execution carrying the review
     */
    public CherryPickExecution withRangeReview(RangeReview review, Instant now)
    {
        requireMutable(now);
        requireNonNull(review, "review is null");
        return edit(now, draft -> draft.rangeReview = Optional.of(review));
    }

    public CherryPickExecution withVersion(long updatedVersion)
    {
        checkArgument(updatedVersion >= version, "version moved backwards");
        return new CherryPickExecution(
                id, startIdempotencyKey, workspace, pullRequestDraft, delivery, range, currentTargetSha, agentTaskId, currentTurnId,
                status, currentStep, pauseReason, finishReason, summary, journal, rangeReview, items,
                updatedVersion, createdAt, updatedAt, terminalAt);
    }

    /** Records the deliberate, non-force push of this execution's generated branch. */
    public CherryPickExecution recordPush(String remote, String head, String sha, Instant now)
    {
        requireDeliverable(now);
        checkState(delivery.isEmpty(), "execution branch is already pushed");
        return edit(now, draft -> draft.delivery = Optional.of(Delivery.pushed(remote, head, sha)));
    }

    /** Records the provider pull request created from the previously pushed branch. */
    public CherryPickExecution recordPullRequest(long providerId, int number, String url, Instant now)
    {
        requireDeliverable(now);
        Delivery pushed = delivery.orElseThrow(() -> new IllegalStateException("execution branch is not pushed"));
        checkState(pushed.pullRequestId().isEmpty(), "execution already has a pull request");
        return edit(now, draft -> draft.delivery = Optional.of(pushed.withPullRequest(providerId, number, url)));
    }

    private List<CherryPickItem> replace(String sourceSha, java.util.function.UnaryOperator<CherryPickItem> edit)
    {
        ImmutableList.Builder<CherryPickItem> updated = ImmutableList.builderWithExpectedSize(items.size());
        boolean found = false;
        for (CherryPickItem item : items) {
            if (item.sourceSha().equals(sourceSha)) {
                updated.add(edit.apply(item));
                found = true;
            }
            else {
                updated.add(item);
            }
        }
        checkArgument(found, "source commit is not in the plan: %s", sourceSha);
        return updated.build();
    }

    private void requireMutable(Instant now)
    {
        requireNonNull(now, "now is null");
        checkState(status != Status.FINISHED, "execution is finished");
        checkArgument(!now.isBefore(updatedAt), "update time is before the previous update");
    }

    private void requireRunning(Instant now)
    {
        requireMutable(now);
        checkState(status == Status.RUNNING, "execution is not running");
    }

    private void requireDeliverable(Instant now)
    {
        requireNonNull(now, "now is null");
        checkArgument(!now.isBefore(updatedAt), "update time is before the previous update");
        checkState(status == Status.FINISHED && finishReason.orElseThrow() == FinishReason.COMPLETED,
                "execution did not complete successfully");
        checkState(pullRequestDraft.isPresent(), "execution has no pull-request draft");
    }

    private CherryPickExecution edit(Instant now, Consumer<Draft> edits)
    {
        Draft draft = new Draft(this);
        draft.updatedAt = now;
        edits.accept(draft);
        return draft.build();
    }

    private static void validateItems(List<CherryPickItem> items)
    {
        checkArgument(!items.isEmpty(), "items is empty");
        Set<String> sourceShas = new HashSet<>();
        int previousPosition = 0;
        for (CherryPickItem item : items) {
            checkArgument(item.position() > previousPosition, "item positions are not increasing");
            previousPosition = item.position();
            checkArgument(sourceShas.add(item.sourceSha()), "duplicate source commit: %s", item.sourceSha());
        }
        checkArgument(items.getFirst().position() == 1, "item positions are not one-based");
    }

    private static void validateStatus(
            Status status,
            Optional<Step> currentStep,
            Optional<PauseReason> pauseReason,
            Optional<FinishReason> finishReason,
            Optional<String> summary,
            Optional<String> currentTurnId,
            Optional<Instant> terminalAt,
            List<CherryPickItem> items)
    {
        switch (status) {
            case RUNNING -> checkArgument(
                    pauseReason.isEmpty() && finishReason.isEmpty() && summary.isEmpty() && terminalAt.isEmpty(),
                    "running execution carries terminal or pause facts");
            case PAUSED -> checkArgument(
                    currentStep.isEmpty() && pauseReason.isPresent() && finishReason.isEmpty()
                            && summary.isPresent() && currentTurnId.isEmpty() && terminalAt.isEmpty(),
                    "paused execution facts are inconsistent");
            case FINISHED -> {
                checkArgument(
                        currentStep.isEmpty() && pauseReason.isEmpty() && finishReason.isPresent()
                                && summary.isPresent() && currentTurnId.isEmpty() && terminalAt.isPresent(),
                        "finished execution facts are inconsistent");
                checkArgument(
                        finishReason.orElseThrow() != FinishReason.COMPLETED
                                || items.stream().noneMatch(CherryPickItem::pending),
                        "completed execution still has pending items");
            }
        }
    }

    public enum Status
    {
        RUNNING,
        PAUSED,
        FINISHED
    }

    /** Applies only while the status is {@link Status#RUNNING}. */
    public enum Step
    {
        APPLYING,
        RESOLVING
    }

    public enum PauseReason
    {
        UNSAFE_GIT_STATE,
        RECONCILIATION_MISMATCH,
        AGENT_TURN_FAILED,
        RETRY_LIMIT_REACHED
    }

    public enum FinishReason
    {
        COMPLETED,
        CANCELLED
    }

    /**
     * Where the commits go.
     *
     * <p>The local target branch is synchronized from the remote and is never
     * checked out, because Git refuses to fetch into a checked-out branch. The
     * work branch is generated by ByteQuay and is what the worktree holds.
     */
    public record Workspace(
            Optional<String> workspaceId,
            RepoRef repository,
            String sourceRemote,
            String targetRemote,
            String targetBranch,
            String localTargetBranch,
            String workBranch,
            Path worktree,
            String laneId)
    {
        public Workspace
        {
            checkOptionalText(workspaceId, "workspaceId", 100);
            requireNonNull(repository, "repository is null");
            checkText(repository.owner(), "repository owner", 100);
            checkText(repository.repo(), "repository name", 100);
            checkText(sourceRemote, "sourceRemote", 100);
            checkText(targetRemote, "targetRemote", 100);
            checkText(targetBranch, "targetBranch", 255);
            checkText(localTargetBranch, "localTargetBranch", 255);
            checkText(workBranch, "workBranch", 255);
            checkArgument(!localTargetBranch.equals(workBranch), "work branch equals the local target branch");
            requireNonNull(worktree, "worktree is null");
            checkArgument(worktree.isAbsolute(), "worktree is not absolute");
            worktree = worktree.normalize();
            checkText(laneId, "laneId", 100);
        }
    }

    /** Pull-request copy saved with the local execution for later explicit delivery. */
    public record PullRequestDraft(String title, String body)
    {
        private static final int MAX_TITLE_CHARACTERS = 1024;
        private static final int MAX_BODY_CHARACTERS = 64 * 1024;

        public PullRequestDraft
        {
            checkText(title, "pullRequestDraft title", MAX_TITLE_CHARACTERS);
            requireNonNull(body, "pullRequestDraft body is null");
            checkArgument(body.length() <= MAX_BODY_CHARACTERS, "pullRequestDraft body is too long");
        }
    }

    /** Durable remote-delivery facts for a successfully completed execution. */
    public record Delivery(
            String pushRemote,
            String head,
            String sha,
            Optional<Long> pullRequestId,
            Optional<Integer> pullRequestNumber,
            Optional<String> pullRequestUrl)
    {
        public Delivery
        {
            checkText(pushRemote, "delivery pushRemote", 100);
            checkText(head, "delivery head", 255);
            checkSha(sha, "delivery sha");
            requireNonNull(pullRequestId, "pullRequestId is null");
            requireNonNull(pullRequestNumber, "pullRequestNumber is null");
            requireNonNull(pullRequestUrl, "pullRequestUrl is null");
            checkArgument(pullRequestId.isPresent() == pullRequestNumber.isPresent()
                            && pullRequestId.isPresent() == pullRequestUrl.isPresent(),
                    "pull-request delivery facts are incomplete");
            pullRequestId.ifPresent(value -> checkArgument(value > 0, "pullRequestId is not positive"));
            pullRequestNumber.ifPresent(value -> checkArgument(value > 0, "pullRequestNumber is not positive"));
            pullRequestUrl.ifPresent(value -> checkText(value, "pullRequestUrl", 2048));
        }

        private static Delivery pushed(String remote, String head, String sha)
        {
            return new Delivery(remote, head, sha, Optional.empty(), Optional.empty(), Optional.empty());
        }

        private Delivery withPullRequest(long providerId, int number, String url)
        {
            return new Delivery(
                    pushRemote, head, sha, Optional.of(providerId), Optional.of(number), Optional.of(url));
        }
    }

    /** The frozen boundaries the plan was confirmed against. */
    public record Range(String sourceBaseSha, String sourceTipSha, String initialTargetSha)
    {
        public Range
        {
            checkSha(sourceBaseSha, "sourceBaseSha");
            checkSha(sourceTipSha, "sourceTipSha");
            checkSha(initialTargetSha, "initialTargetSha");
            checkArgument(!sourceBaseSha.equals(sourceTipSha), "sourceBaseSha equals sourceTipSha");
        }
    }

    /**
     * What the program was about to do, stored before it touches Git.
     *
     * <p>After a restart this says where to look; Git inspection says what
     * actually happened.
     */
    public record ActionJournal(
            ActionType type,
            Optional<String> sourceSha,
            String expectedHead,
            int attempt,
            String idempotencyKey,
            Instant startedAt)
    {
        public ActionJournal
        {
            requireNonNull(type, "type is null");
            CherryPickChecks.checkOptionalSha(sourceSha, "sourceSha");
            checkSha(expectedHead, "expectedHead");
            checkArgument(attempt >= 1, "attempt is not positive");
            checkText(idempotencyKey, "idempotencyKey", 200);
            requireNonNull(startedAt, "startedAt is null");
        }
    }

    public enum ActionType
    {
        BEGIN_SEQUENCE,
        SKIP_EMPTY,
        COMMIT_CONFLICT_SNAPSHOT,
        AGENT_TURN,
        AGENT_REPAIR,
        FINALIZE_CONFLICT_PAIR,
        /** Read compatibility for executions journaled by the squashing implementation. */
        NORMALIZE_CONFLICT_PAIR
    }

    /** Human-readable {@code range-diff} output, and whether it was cut short. */
    public record RangeReview(String output, boolean truncated)
    {
        public static final int MAX_OUTPUT_CHARACTERS = 256 * 1024;

        public RangeReview
        {
            requireNonNull(output, "output is null");
            checkArgument(
                    output.length() <= MAX_OUTPUT_CHARACTERS,
                    "output exceeds " + MAX_OUTPUT_CHARACTERS + " characters");
        }
    }

    private static final class Draft
    {
        private final CherryPickExecutionId id;
        private final String startIdempotencyKey;
        private final Workspace workspace;
        private final Optional<PullRequestDraft> pullRequestDraft;
        private final Range range;
        private final long version;
        private final Instant createdAt;

        private String currentTargetSha;
        private Optional<String> agentTaskId;
        private Optional<String> currentTurnId;
        private Status status;
        private Optional<Step> currentStep;
        private Optional<PauseReason> pauseReason;
        private Optional<FinishReason> finishReason;
        private Optional<String> summary;
        private Optional<ActionJournal> journal;
        private Optional<RangeReview> rangeReview;
        private Optional<Delivery> delivery;
        private List<CherryPickItem> items;
        private Instant updatedAt;
        private Optional<Instant> terminalAt;

        private Draft(CherryPickExecution execution)
        {
            this.id = execution.id;
            this.startIdempotencyKey = execution.startIdempotencyKey;
            this.workspace = execution.workspace;
            this.pullRequestDraft = execution.pullRequestDraft;
            this.delivery = execution.delivery;
            this.range = execution.range;
            this.version = execution.version;
            this.createdAt = execution.createdAt;
            this.currentTargetSha = execution.currentTargetSha;
            this.agentTaskId = execution.agentTaskId;
            this.currentTurnId = execution.currentTurnId;
            this.status = execution.status;
            this.currentStep = execution.currentStep;
            this.pauseReason = execution.pauseReason;
            this.finishReason = execution.finishReason;
            this.summary = execution.summary;
            this.journal = execution.journal;
            this.rangeReview = execution.rangeReview;
            this.items = execution.items;
            this.updatedAt = execution.updatedAt;
            this.terminalAt = execution.terminalAt;
        }

        private CherryPickExecution build()
        {
            return new CherryPickExecution(
                    id, startIdempotencyKey, workspace, pullRequestDraft, delivery, range, currentTargetSha, agentTaskId, currentTurnId,
                    status, currentStep, pauseReason, finishReason, summary, journal, rangeReview, items,
                    version, createdAt, updatedAt, terminalAt);
        }
    }
}
