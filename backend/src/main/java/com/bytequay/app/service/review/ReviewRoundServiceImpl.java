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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
class ReviewRoundServiceImpl
        implements ReviewRoundService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewRoundServiceImpl.class);

    /** How long a batch of freshly-arrived remote comments waits before a
     *  round opens, in case more trickle in from the same reviewer pass.
     *  Dogfooding may retune this (plan-rail-runs.md open question). */
    static final Duration DEBOUNCE = Duration.ofMinutes(10);

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final ReviewRoundStore roundStore;
    private final AgentRunService agentRuns;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final ThreadTurnStore turnStore;
    private final TaskPhaseMachine phaseMachine;
    private final PullRequestService pullRequests;
    private final GitRunner git;
    private final BrainReviewService brainReview;
    private final RemoteDevelopmentStageService remoteStages;
    private final Clock clock;

    @Autowired
    ReviewRoundServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            TaskPhaseMachine phaseMachine,
            PullRequestService pullRequests,
            GitRunner git,
            BrainReviewService brainReview,
            RemoteDevelopmentStageService remoteStages)
    {
        this(taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore,
                phaseMachine, pullRequests, git, brainReview, remoteStages, Clock.systemUTC());
    }

    ReviewRoundServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            TaskPhaseMachine phaseMachine,
            PullRequestService pullRequests,
            GitRunner git,
            BrainReviewService brainReview,
            RemoteDevelopmentStageService remoteStages,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.git = requireNonNull(git, "git is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.remoteStages = requireNonNull(remoteStages, "remoteStages is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    @Transactional
    public void reconcile(Task task)
    {
        Optional<ReviewRound> live = roundStore.findLiveByTask(task.id());
        if (live.isPresent()) {
            // A round is already collecting/addressing/gated — freshly
            // ingested comments wait for the next round once this one closes,
            // rather than growing mid-flight (R11: one batch per round).
            // Refresh its stats on every sweep too, so a round whose stats
            // fell behind (e.g. opened before recomputeStats existed) heals
            // on its own rather than staying stuck until its next resolve.
            recomputeStats(live.get().id());
            enqueueRoundKickoffIfDeferred(task, live.get());
            return;
        }
        List<ReviewComment> unrounded = stageStore.findUnroundedRemoteComments(task.id());
        if (unrounded.isEmpty()) {
            return;
        }
        Instant oldest = unrounded.stream()
                .map(ReviewComment::createdAt)
                .min(Instant::compareTo)
                .orElseThrow();
        if (Duration.between(oldest, now()).compareTo(DEBOUNCE) < 0) {
            return; // still within the debounce window — wait for more to arrive.
        }
        openRound(task, unrounded);
    }

    private void openRound(Task task, List<ReviewComment> comments)
    {
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return;
        }
        String roundId = UUID.randomUUID().toString();
        List<UUID> commentIds = comments.stream().map(ReviewComment::id).toList();

        StageInstance remoteStage = remoteStages.ensureOpen(task.id());
        AgentRun run = agentRuns.openInStage(
                task.id(), AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                remoteStage.id().toString(), /* budget */ null);

        ReviewRound round = new ReviewRound(
                roundId, task.id(), roundStore.nextIndex(task.id()), List.of(),
                ReviewRound.STATUS_ADDRESSING, new ReviewRound.ReviewRoundStats(0, 0, 0, comments.size()),
                run.id(), now(), /* gatedAt */ null, /* postedAt */ null,
                ReviewRound.ORIGIN_EXTERNAL, /* brainVerdict */ null, /* iteration */ 0,
                ReviewRound.DEFAULT_BRAIN_BUDGET);
        roundStore.save(round);
        // Only now does the round row exist for review_comment.round_id's FK
        // to reference — assigning comments any earlier throws
        // SQLITE_CONSTRAINT_FOREIGNKEY against the real schema (invisible to
        // a mocked StageStore, which is why this shipped broken).
        stageStore.assignCommentsToRound(commentIds, UUID.fromString(roundId));

        enqueueRoundKickoffIfReady(task, round, run, comments);
    }

    /** A round's kickoff turn finishing means the agent's triage + fixes +
     *  drafted replies are done. Matched by stage id (the run's own backing
     *  stage), the same mechanism {@code CiFixRunExecutor} uses to find its
     *  own turns. Only handles the round's FIRST addressing-turn completion
     *  (iteration 0, no brain verdict yet) — the brain verification pass
     *  this hands off to (R21b) reuses the same ADDRESSING status for its
     *  own fix turns, and {@link BrainReviewServiceImpl} owns those. */
    @EventListener
    @Transactional
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null) {
            return;
        }
        Optional<ReviewRound> live = roundStore.findLiveByTask(event.taskId())
                .filter(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status()))
                .filter(r -> r.iteration() == 0 && r.brainVerdict() == null)
                .filter(r -> r.runId() != null);
        if (live.isEmpty()) {
            return;
        }
        ReviewRound round = live.get();
        if (!matchesRunTurn(round, turn)) {
            return;
        }
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        brainReview.reviewBeforeRoundGate(round, task);
    }

    private boolean matchesRunTurn(ReviewRound round, ThreadTurn turn)
    {
        if (round.runId() == null || turn == null) {
            return false;
        }
        String agentRunId = turn.agentRunId();
        if (agentRunId != null && !agentRunId.isBlank()) {
            return round.runId().equals(agentRunId);
        }
        if (!"review-round".equals(turn.initiator().source())) {
            return false;
        }
        return turn.stageId() != null
                && agentRuns.findById(round.runId())
                        .map(run -> turn.stageId().equals(run.stageId()))
                        .orElse(false);
    }

    private void enqueueRoundKickoffIfDeferred(Task task, ReviewRound round)
    {
        if (!ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())
                || !ReviewRound.STATUS_ADDRESSING.equals(round.status())
                || round.iteration() != 0
                || round.brainVerdict() != null
                || round.runId() == null) {
            return;
        }
        AgentRun run = agentRuns.findById(round.runId()).orElse(null);
        if (run == null || hasQueuedOrRunningTurn(task.threadId(), run)) {
            return;
        }
        enqueueRoundKickoffIfReady(task, round, run, stageStore.findCommentsByRound(UUID.fromString(round.id())));
    }

    private void enqueueRoundKickoffIfReady(Task task, ReviewRound round, AgentRun run, List<ReviewComment> comments)
    {
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty() || threadOpt.get().status() != ThreadStatus.IDLE) {
            log.info("review-round: thread not idle for task {}; round {} turn deferred",
                    task.id(), round.id());
            return;
        }
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return;
        }
        String prompt = buildRoundPrompt(ref.get(), comments);
        try {
            scheduler.enqueueTaskTurn(
                    threadOpt.get(), prompt, task.id(), run.stageId(),
                    TurnInitiator.unattended("review-round"), run.id());
            log.info("review-round: round {} ({}) queued for task {}",
                    round.idx(), round.id(), task.id());
        }
        catch (RuntimeException e) {
            log.warn("review-round: enqueue failed for task {} round {}: {}",
                    task.id(), round.id(), e.getMessage());
        }
    }

    private boolean hasQueuedOrRunningTurn(String threadId, AgentRun run)
    {
        return turnStore.listTurnsByTaskId(threadId, 100).stream()
                .filter(t -> matchesRunTurn(run, t))
                .anyMatch(t -> t.status() == ThreadTurnStatus.QUEUED
                        || t.status() == ThreadTurnStatus.RUNNING);
    }

    private boolean matchesRunTurn(AgentRun run, ThreadTurn turn)
    {
        if (run == null || turn == null) {
            return false;
        }
        String agentRunId = turn.agentRunId();
        if (agentRunId != null && !agentRunId.isBlank()) {
            return run.id().equals(agentRunId);
        }
        return "review-round".equals(turn.initiator().source())
                && turn.stageId() != null
                && turn.stageId().equals(run.stageId());
    }

    @Override
    public Optional<ReviewRound> findById(String roundId)
    {
        return roundStore.findById(roundId);
    }

    @Override
    public List<ReviewRound> findByTask(String taskId)
    {
        return roundStore.findByTask(taskId);
    }

    @Override
    @Transactional
    public void closeOpenRounds(String taskId, String reason)
    {
        roundStore.findLiveByTask(taskId).ifPresent(round -> {
            if (round.runId() != null) {
                agentRuns.transition(round.runId(), AgentRun.STATUS_CANCELLED, reason);
            }
            roundStore.save(round.withStatus(ReviewRound.STATUS_CLOSED));
        });
    }

    @Override
    @Transactional
    public ReviewRound approve(String roundId)
    {
        ReviewRound round = roundStore.findById(roundId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no round: " + roundId));
        if (!ReviewRound.STATUS_AWAITING_GATE.equals(round.status())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "round " + roundId + " is not awaiting its gate");
        }
        Task task = taskStore.findTaskById(round.taskId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + round.taskId()));
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isPresent()) {
            for (ReviewComment comment : stageStore.findCommentsByRound(UUID.fromString(roundId))) {
                if (comment.draftReplyBody() == null || comment.draftReplyBody().isBlank()
                        || comment.remoteCommentId() == null) {
                    continue;
                }
                pullRequests.replyToReviewThread(ref.get().repoRef().fullName(), ref.get().number(),
                        comment.remoteCommentId(), comment.draftReplyBody());
            }
        }
        if (task.worktreePath() != null && !task.worktreePath().isBlank()) {
            try {
                git.push(Path.of(task.worktreePath()));
            }
            catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "push interrupted for round " + roundId);
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "push failed for round " + roundId + ": " + e.getMessage());
            }
        }
        phaseMachine.transition(task.id(), TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);
        if (round.runId() != null) {
            agentRuns.transition(round.runId(), AgentRun.STATUS_SUCCEEDED, "round_approved");
        }
        ReviewRound posted = round.withStatus(ReviewRound.STATUS_POSTED).withPostedAt(now());
        return roundStore.save(posted);
    }

    @Override
    @Transactional
    public void recomputeStats(String roundId)
    {
        UUID id;
        try {
            id = UUID.fromString(roundId);
        }
        catch (IllegalArgumentException e) {
            return;
        }
        roundStore.findById(roundId).ifPresent(round -> {
            int fixed = 0;
            int replied = 0;
            int open = 0;
            for (ReviewComment comment : stageStore.findCommentsByRound(id)) {
                if (!comment.resolved()) {
                    open++;
                }
                else if (comment.draftReplyBody() != null) {
                    replied++;
                }
                else {
                    fixed++;
                }
            }
            roundStore.save(round.withStats(new ReviewRound.ReviewRoundStats(fixed, replied, 0, open)));
        });
    }

    private String buildRoundPrompt(PullRequestRef ref, List<ReviewComment> comments)
    {
        StringBuilder out = new StringBuilder();
        out.append("A new batch of review comments arrived on ")
                .append(ref.repoRef().fullName()).append(" #").append(ref.number())
                .append(".\n\n")
                .append("For EACH comment below: if it needs a code change, make the fix and "
                        + "commit it (plain git commit — do not push). If it's a question or "
                        + "needs no code change, draft a reply with "
                        + "record_round_reply(comment_id, body). Either way, once you've handled "
                        + "a comment, call resolve_review_comment(comment_id). Do NOT push, and do "
                        + "NOT call any tool that posts directly to GitHub — everything you draft "
                        + "here is held for the user's review before anything goes out.\n\n")
                .append("Comments in this batch:\n");
        for (ReviewComment c : comments) {
            String location = c.file() == null ? "(general PR comment)" : c.file() + ':' + c.line();
            out.append('\n').append("[id: ").append(c.id()).append("] ")
                    .append(location).append('\n')
                    .append("   ").append(c.body() == null ? "" : c.body().strip()).append('\n');
        }
        return out.toString();
    }

    private Instant now()
    {
        return Instant.now(clock);
    }
}
