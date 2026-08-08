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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
public class ReviewRoundServiceImpl
{
    /** How long a batch of freshly-arrived remote comments waits before a
     *  round opens, in case more trickle in from the same reviewer pass.
     *  Dogfooding may retune this (plan-rail-runs.md open question). */
    static final Duration DEBOUNCE = Duration.ofMinutes(10);

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final ReviewRoundStore roundStore;
    private final BrainReviewServiceImpl brainReview;
    private final PRService prService;
    private final TaskCommandExecutor commands;
    private final ReviewRoundStateMachine roundMachine;
    private final RoundGateSaga gateSaga;
    private final Clock clock;

    @Autowired
    ReviewRoundServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            BrainReviewServiceImpl brainReview,
            PRService prService,
            TaskCommandExecutor commands,
            ReviewRoundStateMachine roundMachine,
            RoundGateSaga gateSaga)
    {
        this(taskStore, stageStore, roundStore, brainReview, prService,
                commands, roundMachine, gateSaga, Clock.systemUTC());
    }

    ReviewRoundServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            BrainReviewServiceImpl brainReview,
            PRService prService,
            TaskCommandExecutor commands,
            ReviewRoundStateMachine roundMachine,
            RoundGateSaga gateSaga,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.roundMachine = requireNonNull(roundMachine, "roundMachine is null");
        this.gateSaga = requireNonNull(gateSaga, "gateSaga is null");
        this.clock = requireNonNull(clock, "clock is null");
    }
    public void reconcile(Task task)
    {
        requireNonNull(task, "task is null");
        // V2 remote feedback is owned by RemoteFeedbackRuntimeCoordinator.
        // Never let the legacy ReviewRound path create a second owner for it.
        if (taskStore.isV2Task(task.id())) {
            return;
        }
        rejectLegacyMutation();
        ReviewRound handoff = commands.execute(task.id(), () -> reconcileInCommand(task.id()));
        if (handoff != null) {
            taskStore.findTaskById(task.id()).ifPresent(current ->
                    brainReview.reviewBeforeRoundGate(handoff, current));
        }
    }

    private ReviewRound reconcileInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        if (taskStore.isV2Task(task.id())) {
            return null;
        }
        if (!reviewTaskRunnable(task)) {
            return null;
        }
        Optional<ReviewRound> live = roundStore.findLiveByTask(task.id());
        if (live.isPresent()) {
            // A round is already collecting/addressing/gated — freshly
            // ingested comments wait for the next round once this one closes,
            // rather than growing mid-flight (R11: one batch per round).
            // Refresh its stats on every sweep too, so a round whose stats
            // fell behind (e.g. opened before recomputeStats existed) heals
            // on its own rather than staying stuck until its next resolve.
            recomputeStats(live.get().id());
            return live.get();
        }
        List<ReviewComment> unrounded = stageStore.findUnroundedRemoteComments(task.id());
        if (unrounded.isEmpty()) {
            return null;
        }
        Instant oldest = unrounded.stream()
                .map(ReviewComment::createdAt)
                .min(Instant::compareTo)
                .orElseThrow();
        if (Duration.between(oldest, now()).compareTo(DEBOUNCE) < 0) {
            return null; // still within the debounce window — wait for more to arrive.
        }
        return openRoundInCommand(task, unrounded);
    }

    private ReviewRound openRoundInCommand(Task task, List<ReviewComment> comments)
    {
        TaskCommandExecutor.requireCurrent(task.id());
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return null;
        }
        List<UUID> commentIds = comments.stream().map(ReviewComment::id).toList();
        String prId = prService.findByTask(task.id())
                .map(PR::id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(409),
                        "task has no durable pull request for its review round"));
        return roundMachine.openExternalInCommand(task.id(), prId, commentIds);
    }

    private static boolean reviewTaskRunnable(Task task)
    {
        if (task == null || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            return false;
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> false;
            default -> true;
        };
    }
    public Optional<ReviewRound> findById(String roundId)
    {
        return roundStore.findById(roundId);
    }
    public List<ReviewRound> findByTask(String taskId)
    {
        List<ReviewRound> rounds = roundStore.findByTask(taskId);
        boolean taskParked = taskStore.findTaskById(taskId)
                .map(task -> task.status() == TaskStatus.PAUSED
                        || task.status() == TaskStatus.NEEDS_ATTENTION
                        || task.phase() == TaskPhase.NEEDS_ATTENTION)
                .orElse(false);
        int openBrainFindings = prService.findByTask(taskId)
                .map(pr -> (int) prService.comments(pr.id()).stream()
                        .filter(comment -> PRTimelineEntry.ACTOR_BRAIN.equals(comment.author()))
                        .filter(comment -> comment.parentCommentId() == null)
                        .filter(comment -> comment.resolvedAt() == null && comment.dismissedAt() == null)
                        .count())
                .orElse(0);
        return rounds.stream().map(round -> {
            ReviewRound projected = taskParked && round.isLive()
                    ? round.withStatus(ReviewRound.STATUS_PAUSED)
                    : round;
            if (!ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
                return projected;
            }
            ReviewRound.ReviewRoundStats stats = round.stats() == null
                    ? ReviewRound.ReviewRoundStats.empty()
                    : round.stats();
            return projected.withStats(new ReviewRound.ReviewRoundStats(
                    stats.fixed(), stats.replied(), stats.pushedBack(), openBrainFindings));
        }).toList();
    }
    public void closeOpenRounds(String taskId, String reason)
    {
        rejectLegacyMutation();
        commands.executeVoid(taskId, () -> closeOpenRoundsInCommand(taskId, reason));
    }
    public void closeOpenRoundsInCommand(String taskId, String reason)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.requireCurrent(taskId);
        for (ReviewRound round : roundStore.findByTask(taskId)) {
            if (ReviewRound.STATUS_CLOSED.equals(round.status())) {
                continue;
            }
            roundMachine.sealInCommand(taskId, round.id(), reason);
        }
    }
    public ReviewRound approve(String roundId)
    {
        rejectLegacyMutation();
        return gateSaga.approve(roundId);
    }
    public void recomputeStats(String roundId)
    {
        rejectLegacyMutation();
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
            roundStore.updateStats(
                    round.id(), new ReviewRound.ReviewRoundStats(fixed, replied, 0, open));
        });
    }

    private static void rejectLegacyMutation()
    {
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "LEGACY review rounds are read-only; use typed V2 review controls");
    }

    private Instant now()
    {
        return Instant.now(clock);
    }
}
