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
package com.bytequay.app.service.localpr;

import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.DiffSide;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PRTriageState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.sqlite.TaskPushStore;
import com.bytequay.app.service.review.DevReportServiceImpl;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskExternalEffectGate;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

@Service
class PRServiceImpl
        implements PRService
{
    /** Check statuses that count as finished — a {@code ci} timeline event is
     *  written only when a check lands in one of these. */
    private static final Set<String> TERMINAL_CHECK_STATUSES =
            Set.of(PRCheck.STATUS_PASSED, PRCheck.STATUS_FAILED, PRCheck.STATUS_NEUTRAL);
    private static final Set<String> PR_PROGRESS_PHASES =
            Set.of(PRTimelineEntry.PHASE_STARTING, PRTimelineEntry.PHASE_CREATING_DRAFT);
    private static final int PUSH_FAILURE_REASON_LIMIT = 2_000;
    private static final String SOURCE_BRAIN_REVIEW_FIX = "brain-review-fix";
    private static final int TURN_SCAN_LIMIT = 100;

    private final PRStore store;
    private final DevReportServiceImpl devReports;
    private final ObjectMapper mapper;
    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadTurnStore turnStore;
    private final LocalReviewSubmissionStore submissionStore;
    private final TaskPushStore pushStore;
    private final TaskCommandExecutor commands;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private V2LocalReviewControl v2LocalReview;

    @Autowired
    PRServiceImpl(
            PRStore store, DevReportServiceImpl devReports, ObjectMapper mapper, StageStore stageStore,
            TaskStore taskStore, ThreadTurnStore turnStore,
            LocalReviewSubmissionStore submissionStore, TaskPushStore pushStore,
            TaskCommandExecutor commands, ApplicationEventPublisher events)
    {
        this(store, devReports, mapper, stageStore, taskStore, turnStore,
                submissionStore, pushStore, commands, events, Clock.systemUTC());
    }

    PRServiceImpl(
            PRStore store, DevReportServiceImpl devReports, ObjectMapper mapper, StageStore stageStore,
            TaskStore taskStore, ThreadTurnStore turnStore,
            LocalReviewSubmissionStore submissionStore, TaskPushStore pushStore,
            TaskCommandExecutor commands, ApplicationEventPublisher events, Clock clock)
    {
        this.submissionStore = requireNonNull(submissionStore, "submissionStore is null");
        this.pushStore = requireNonNull(pushStore, "pushStore is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.devReports = requireNonNull(devReports, "devReports is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.events = requireNonNull(events, "events is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Autowired(required = false)
    void setV2LocalReview(V2LocalReviewControl v2LocalReview)
    {
        this.v2LocalReview = requireNonNull(v2LocalReview, "v2LocalReview is null");
    }

    @Override
    public Optional<PR> findByTask(String taskId)
    {
        return store.findByTaskId(taskId);
    }

    @Override
    public Optional<PR> findById(String prId)
    {
        return store.findById(prId);
    }

    @Override
    public Optional<PR> findByRepoAndNumber(String repo, int remotePrNumber)
    {
        return store.findByRepoAndRemotePrNumber(repo, remotePrNumber);
    }

    @Override
    public Optional<PR> findTaskByRepoAndNumber(String repo, int remotePrNumber)
    {
        return store.findTaskByRepoAndRemotePrNumber(repo, remotePrNumber);
    }

    @Override
    public List<PRCommit> commits(String prId)
    {
        return store.commitsFor(prId);
    }

    @Override
    public List<PRTimelineEntry> timeline(String prId)
    {
        return store.timelineFor(prId);
    }

    @Override
    public List<LocalReviewSubmission> localReviewSubmissions(String prId)
    {
        List<PRTimelineEntry> events = store.timelineFor(prId).stream()
                .filter(event -> PRTimelineEntry.TYPE_REVIEW.equals(event.eventType()))
                .filter(event -> PRTimelineEntry.ACTOR_USER.equals(event.actor()))
                .toList();
        Map<String, LocalReviewSubmission> submissions = new LinkedHashMap<>();
        Map<String, String> activeSubmissionByComment = new LinkedHashMap<>();
        for (PRTimelineEntry event : events) {
            try {
                var payload = mapper.readTree(event.payloadJson());
                String reviewEvent = payload.path("reviewEvent").asText();
                if ("submitted".equals(reviewEvent)) {
                    List<String> commentIds = payload.path("commentIds").isArray()
                            ? StreamSupport.stream(payload.path("commentIds").spliterator(), false)
                                    .map(node -> node.asText(""))
                                    .filter(id -> !id.isBlank())
                                    .toList()
                            : List.of();
                    String body = payload.path("body").isNull() ? null : payload.path("body").asText(null);
                    String verdict = payload.path("verdict").isNull()
                            ? null : payload.path("verdict").asText(null);
                    String bodyCommentId = payload.path("bodyCommentId").isNull()
                            ? null : payload.path("bodyCommentId").asText(null);
                    submissions.put(event.id(), new LocalReviewSubmission(
                            event.createdAt(), commentIds, body, verdict, bodyCommentId));
                    commentIds.forEach(commentId -> activeSubmissionByComment.put(commentId, event.id()));
                }
                else if ("updated".equals(reviewEvent) || "reopened".equals(reviewEvent)) {
                    activeSubmissionByComment.remove(payload.path("commentId").asText());
                }
            }
            catch (JsonProcessingException | RuntimeException ignored) {
                // One malformed timeline event must not hide the valid batches around it.
            }
        }
        return submissions.entrySet().stream()
                .map(entry -> {
                    LocalReviewSubmission submission = entry.getValue();
                    List<String> activeIds = submission.commentIds().stream()
                            .filter(id -> entry.getKey().equals(activeSubmissionByComment.get(id)))
                            .toList();
                    return new LocalReviewSubmission(
                            submission.submittedAt(), activeIds, submission.body(),
                            submission.verdict(), submission.bodyCommentId());
                })
                .toList();
    }

    @Override
    public List<PRCheck> checks(String prId)
    {
        return store.checksFor(prId);
    }

    @Override
    public List<PRComment> comments(String prId)
    {
        return store.commentsFor(prId);
    }

    @Override
    public PR createForTask(
            String taskId, String branchName, String baseBranch, String title, String description)
    {
        throw conflict("Task-owned PR creation is retired outside the exact V2 Local Development owner");
    }

    @Override
    public PR createForTaskInCommand(
            String taskId, String branchName, String baseBranch,
            String title, String description)
    {
        requireText(taskId, "taskId");
        requireText(branchName, "branchName");
        requireText(baseBranch, "baseBranch");
        requireText(title, "title");
        String workflow = taskStore.findWorkflowVersion(taskId)
                .orElseThrow(() -> conflict(
                        "Task " + taskId + " has no immutable workflow route"));
        if (!"V2".equals(workflow)) {
            throw conflict("Historical LEGACY Task " + taskId
                    + " is read-only; create a V2 Task");
        }
        TaskCommandExecutor.requireCurrent(taskId);
        // Idempotent owner projection: duplicate result delivery reuses it.
        Optional<PR> existing = store.findByTaskId(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PR pr = PR.create(
                UUID.randomUUID().toString(), taskId, branchName, baseBranch, title, description, now());
        PR saved = store.save(pr);
        notifyUpdated(saved.id());
        return saved;
    }

    @Override
    public PR createExternal(
            String repo, int remotePrNumber, String remotePrUrl, String author,
            String branchName, String baseBranch, String title, String description,
            String status, Instant createdAt, Instant mergedAt, Instant closedAt)
    {
        requireText(repo, "repo");
        requireText(title, "title");
        // Idempotent — a repeat resolver call (dashboard refresh, revisit) must
        // never duplicate the row.
        Optional<PR> existing = store.findByRepoAndRemotePrNumber(repo, remotePrNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        PR pr = PR.createExternal(
                UUID.randomUUID().toString(), repo, remotePrNumber, remotePrUrl, author,
                branchName, baseBranch, title, description, status, createdAt, mergedAt, closedAt);
        PR saved = store.save(pr);
        notifyUpdated(saved.id());
        return saved;
    }

    @Override
    public PR markSynced(String prId, Instant when)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withSynced(when));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public List<PRDashboardEntry> dashboardEntries()
    {
        return store.findDashboardEntries();
    }

    @Override
    public PRTriageState triage(String prId)
    {
        return triageOrEmpty(prId);
    }

    @Override
    public PR setWatchReason(String prId, PullRequest.Origin watchReason)
    {
        PR pr = require(prId);
        PR.PRSyncSnapshot current = pr.githubSync();
        PR.PRSyncSnapshot updated = current == null
                ? new PR.PRSyncSnapshot(watchReason, null, List.of(), Map.of(), false, null, 0, 0, 0, null,
                        null, null, null, Map.of(), List.of(), false, null)
                : new PR.PRSyncSnapshot(watchReason, current.ghUpdatedAt(), current.labels(), current.labelColors(),
                        current.draft(), current.ciStatus(), current.additions(), current.deletions(),
                        current.commentCount(), current.attentionReason(), current.mergeable(),
                        current.mergeableState(), current.headPushedAt(), current.reviewerVerdicts(),
                        current.requestedReviewers(), current.mergeQueueEnabled(), current.mergeQueueState());
        PR saved = store.save(pr.withGithubSync(updated));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public PR updateSyncSnapshot(String prId, PR.PRSyncSnapshot snapshot)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withGithubSync(snapshot));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public void markViewed(String prId)
    {
        PRTriageState state = triageOrEmpty(prId);
        if (state.viewedAt() == null) {
            saveTriage(withViewedAt(state, clock.instant()));
        }
    }

    @Override
    public void markHandled(String prId, HandledAction action)
    {
        requireNonNull(action, "action is null");
        PRTriageState state = triageOrEmpty(prId);
        Instant now = clock.instant();
        saveTriage(new PRTriageState(
                prId, state.viewedAt() == null ? now : state.viewedAt(), now, action,
                state.snoozedUntil(), state.snoozedAt(), state.snoozeWakeReason()));
    }

    @Override
    public void reopen(String prId)
    {
        PRTriageState state = triageOrEmpty(prId);
        saveTriage(new PRTriageState(
                prId, state.viewedAt(), null, null,
                state.snoozedUntil(), state.snoozedAt(), state.snoozeWakeReason()));
    }

    @Override
    public void snooze(String prId, Instant until)
    {
        requireNonNull(until, "until is null");
        PRTriageState state = triageOrEmpty(prId);
        // A fresh snooze clears any prior auto-wake reason — the user is
        // parking the PR again on purpose.
        saveTriage(new PRTriageState(
                prId, state.viewedAt(), state.reviewedAt(), state.handledAction(),
                until, clock.instant(), null));
    }

    @Override
    public void unsnooze(String prId)
    {
        autoWake(prId, null);
    }

    @Override
    public void autoWake(String prId, String wakeReason)
    {
        PRTriageState state = triageOrEmpty(prId);
        saveTriage(new PRTriageState(
                prId, state.viewedAt(), state.reviewedAt(), state.handledAction(),
                null, null, wakeReason));
    }

    @Override
    public void clearSnoozeWakeReason(String prId)
    {
        PRTriageState state = triageOrEmpty(prId);
        if (state.snoozeWakeReason() != null) {
            saveTriage(new PRTriageState(
                    prId, state.viewedAt(), state.reviewedAt(), state.handledAction(),
                    state.snoozedUntil(), state.snoozedAt(), null));
        }
    }

    private PRTriageState triageOrEmpty(String prId)
    {
        return store.findTriage(prId).orElseGet(() -> PRTriageState.empty(prId));
    }

    private void saveTriage(PRTriageState state)
    {
        store.saveTriage(state);
        notifyUpdated(state.prId());
    }

    private static PRTriageState withViewedAt(PRTriageState state, Instant viewedAt)
    {
        return new PRTriageState(
                state.prId(), viewedAt, state.reviewedAt(), state.handledAction(),
                state.snoozedUntil(), state.snoozedAt(), state.snoozeWakeReason());
    }

    @Override
    public void recordBrainReview(
            String taskId, String scope, String verdict, int iteration, String roundId)
    {
        recordBrainReview(taskId, scope, verdict, iteration, roundId, null);
    }

    @Override
    public void recordBrainReview(
            String taskId,
            String scope,
            String verdict,
            int iteration,
            String roundId,
            String attemptId)
    {
        store.findByTaskId(taskId).ifPresent(pr -> {
            if (hasReviewActivity(
                    pr.id(), "finished", scope, iteration, roundId, attemptId)) {
                return;
            }
            Instant when = now();
            Instant startedAt = reviewStartedAt(
                    pr.id(), scope, iteration, roundId, attemptId).orElse(pr.createdAt());
            List<String> commentIds = store.commentsFor(pr.id()).stream()
                    .filter(c -> PRTimelineEntry.ACTOR_BRAIN.equals(c.author()))
                    .filter(c -> c.parentCommentId() == null)
                    .filter(c -> !c.createdAt().isBefore(startedAt) && !c.createdAt().isAfter(when))
                    .map(PRComment::id)
                    .toList();
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                    /* localOnly */ true, when,
                    payload("reviewEvent", "finished", "scope", scope, "verdict", verdict,
                            "iteration", iteration, "roundId", roundId,
                            "attemptId", attemptId,
                            "findingCount", commentIds.size(), "commentIds", commentIds));
            notifyUpdated(pr.id());
        });
    }

    @Override
    public void recordBrainReviewStarted(String taskId, String scope, int iteration, String roundId)
    {
        recordBrainReviewStarted(taskId, scope, iteration, roundId, null);
    }

    @Override
    public void recordBrainReviewStarted(
            String taskId, String scope, int iteration, String roundId, String attemptId)
    {
        recordBrainReviewActivity(
                taskId, PRTimelineEntry.ACTOR_BRAIN, "started",
                scope, iteration, roundId, attemptId);
    }

    @Override
    public void recordBrainReviewAddressing(String taskId, String scope, int iteration, String roundId)
    {
        recordBrainReviewAddressing(taskId, scope, iteration, roundId, null);
    }

    @Override
    public void recordBrainReviewAddressing(
            String taskId, String scope, int iteration, String roundId, String attemptId)
    {
        recordBrainReviewActivity(
                taskId, PRTimelineEntry.ACTOR_AGENT, "addressing-started",
                scope, iteration, roundId, attemptId);
    }

    @Override
    public void recordBrainReviewFailed(
            String taskId, String scope, int iteration, String roundId, String reason)
    {
        recordBrainReviewFailed(taskId, scope, iteration, roundId, reason, null);
    }

    @Override
    public void recordBrainReviewFailed(
            String taskId, String scope, int iteration, String roundId, String reason, String attemptId)
    {
        store.findByTaskId(taskId).ifPresent(pr -> {
            if (hasReviewActivity(pr.id(), "failed", scope, iteration, roundId, attemptId)) {
                return;
            }
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                    /* localOnly */ true, now(),
                    payload("reviewEvent", "failed", "scope", scope, "iteration", iteration,
                            "roundId", roundId, "reason", reason, "attemptId", attemptId));
            notifyUpdated(pr.id());
        });
    }

    private void recordBrainReviewActivity(
            String taskId, String actor, String activity, String scope, int iteration, String roundId)
    {
        recordBrainReviewActivity(taskId, actor, activity, scope, iteration, roundId, null);
    }

    private void recordBrainReviewActivity(
            String taskId, String actor, String activity, String scope, int iteration,
            String roundId, String attemptId)
    {
        store.findByTaskId(taskId).ifPresent(pr -> {
            if (hasReviewActivity(pr.id(), activity, scope, iteration, roundId, attemptId)) {
                return;
            }
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, actor, /* localOnly */ true, now(),
                    payload("reviewEvent", activity, "scope", scope, "iteration", iteration,
                            "roundId", roundId, "attemptId", attemptId));
            notifyUpdated(pr.id());
        });
    }

    private Optional<Instant> reviewStartedAt(
            String prId, String scope, int iteration, String roundId, String attemptId)
    {
        return store.timelineFor(prId).stream()
                .filter(e -> e.eventType().equals(PRTimelineEntry.TYPE_REVIEW))
                .filter(e -> reviewPayloadMatches(e, "started", scope, iteration, roundId))
                .filter(e -> attemptId == null || reviewAttemptMatches(e, attemptId))
                .map(PRTimelineEntry::createdAt)
                .max(Instant::compareTo);
    }

    private boolean hasReviewActivity(String prId, String activity, String scope, int iteration, String roundId)
    {
        return hasReviewActivity(prId, activity, scope, iteration, roundId, null);
    }

    private boolean hasReviewActivity(
            String prId, String activity, String scope, int iteration, String roundId, String attemptId)
    {
        return store.timelineFor(prId).stream()
                .anyMatch(e -> e.eventType().equals(PRTimelineEntry.TYPE_REVIEW)
                        && reviewPayloadMatches(e, activity, scope, iteration, roundId)
                        && (attemptId == null || reviewAttemptMatches(e, attemptId)));
    }

    private boolean reviewAttemptMatches(PRTimelineEntry event, String attemptId)
    {
        try {
            return attemptId.equals(mapper.readTree(event.payloadJson()).path("attemptId").asText(null));
        }
        catch (JsonProcessingException | RuntimeException e) {
            return false;
        }
    }

    private boolean reviewPayloadMatches(
            PRTimelineEntry event, String activity, String scope, int iteration, String roundId)
    {
        try {
            var payload = mapper.readTree(event.payloadJson());
            return activity.equals(payload.path("reviewEvent").asText())
                    && scope.equals(payload.path("scope").asText())
                    && iteration == payload.path("iteration").asInt()
                    && Objects.equals(roundId, payload.path("roundId").isNull()
                            ? null : payload.path("roundId").asText(null));
        }
        catch (JsonProcessingException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void recordGateApproval(String prId, String gate, String reason)
    {
        PR pr = require(prId);
        boolean exists = store.timelineFor(prId).stream().anyMatch(event -> {
            if (!PRTimelineEntry.TYPE_STATUS.equals(event.eventType())) {
                return false;
            }
            try {
                var value = mapper.readTree(event.payloadJson());
                return gate.equals(value.path("gate").asText())
                        && "approved".equals(value.path("decision").asText())
                        && reason.equals(value.path("reason").asText());
            }
            catch (JsonProcessingException | RuntimeException e) {
                return false;
            }
        });
        if (!exists) {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_STATUS, PRTimelineEntry.ACTOR_USER,
                    /* localOnly */ false, now(),
                    payload("gate", gate, "decision", "approved", "automatic", true, "reason", reason));
            notifyUpdated(pr.id());
        }
    }

    @Override
    public void recordPlanApproved(String taskId, String planStageId)
    {
        store.findByTaskId(taskId).ifPresent(pr -> {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_PLAN_FINALIZED, PRTimelineEntry.ACTOR_USER,
                    /* localOnly */ true, now(), payload("planStageId", planStageId));
            notifyUpdated(pr.id());
        });
    }

    @Override
    public PR updateDetails(String prId, String title, String description)
    {
        PR pr = require(prId);
        if (v2LocalReview != null && v2LocalReview.handles(pr)) {
            v2LocalReview.updateDetails(pr, title, description);
            return require(prId);
        }
        rejectTaskOwnedFallback(pr, "title/body editing");
        PR saved = store.save(pr.withDetails(title, description));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    @Transactional
    public void recordProgress(String prId, String phase)
    {
        PR pr = require(prId);
        if (!PR_PROGRESS_PHASES.contains(phase)) {
            throw new IllegalArgumentException("phase must be starting or creating-draft");
        }
        boolean exists = store.timelineFor(prId).stream()
                .filter(event -> PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS.equals(event.eventType()))
                .anyMatch(event -> progressPhase(event).map(phase::equals).orElse(false));
        if (exists) {
            return;
        }

        Instant when = now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("branch", pr.branchName());
        data.put("baseBranch", pr.baseBranch());
        appendEvent(prId, PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS, PRTimelineEntry.ACTOR_AGENT,
                /* localOnly */ true, when,
                payload("phase", phase, "branch", pr.branchName(), "baseBranch", pr.baseBranch()));
        if (pr.taskId() != null) {
            stageStore.findStageByType(pr.taskId(), StageType.DEVELOPMENT_STAGE)
                    .ifPresent(stage -> stageStore.recordEvent(
                            stage.id(), pr.taskId(), StageEventType.PULL_REQUEST_PROGRESS, data));
        }
        notifyUpdated(prId);
    }

    private Optional<String> progressPhase(PRTimelineEntry event)
    {
        try {
            return Optional.ofNullable(mapper.readTree(event.payloadJson()).path("phase").asText(null));
        }
        catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void recordPushFailureInCommand(String prId, String failedStep, String reason)
    {
        PR pr = require(prId);
        if (pr.taskId() == null) {
            throw conflict("PR " + prId + " is not task-owned");
        }
        TaskCommandExecutor.requireCurrent(pr.taskId());
        requireNonNull(failedStep, "failedStep is null");
        requireNonNull(reason, "reason is null");
        String boundedReason = reason.length() <= PUSH_FAILURE_REASON_LIMIT
                ? reason
                : reason.substring(0, PUSH_FAILURE_REASON_LIMIT);

        Instant when = now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", PRTimelineEntry.PHASE_FAILED);
        data.put("branch", pr.branchName());
        data.put("baseBranch", pr.baseBranch());
        data.put("failedStep", failedStep);
        data.put("reason", boundedReason);
        appendEvent(prId, PRTimelineEntry.TYPE_PULL_REQUEST_PROGRESS, PRTimelineEntry.ACTOR_AGENT,
                /* localOnly */ true, when, payload(
                        "phase", PRTimelineEntry.PHASE_FAILED,
                        "branch", pr.branchName(),
                        "baseBranch", pr.baseBranch(),
                        "failedStep", failedStep,
                        "reason", boundedReason));
        stageStore.findStageByType(pr.taskId(), StageType.DEVELOPMENT_STAGE)
                .ifPresent(stage -> stageStore.recordEvent(
                        stage.id(), pr.taskId(), StageEventType.PULL_REQUEST_PROGRESS, data));
        notifyUpdated(prId);
    }

    @Override
    public PR updateBranches(String prId, String branchName, String baseBranch)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withBranches(branchName, baseBranch));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public PR updateAuthor(String prId, String author)
    {
        PR pr = require(prId);
        if (author == null || author.isBlank() || author.equals(pr.author())) {
            return pr;
        }
        PR saved = store.save(pr.withAuthor(author));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public PRCommit recordCommit(
            String prId, String sha, String message, int additions, int deletions, String actor)
    {
        PR pr = require(prId);
        requireText(sha, "sha");
        PRCommit existing = store.commitsFor(pr.id()).stream()
                .filter(commit -> sameSha(commit.sha(), sha))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        Instant when = now();
        PRCommit commit = store.addCommit(new PRCommit(
                UUID.randomUUID().toString(), pr.id(), sha, message == null ? "" : message,
                additions, deletions, when, /* pushedAt */ null));
        // A commit is real git history that migrates on push, so its event is
        // never local-only.
        appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMIT, actor, /* localOnly */ false, when,
                payload("sha", sha, "message", commit.message(),
                        "additions", additions, "deletions", deletions));
        notifyUpdated(pr.id());
        return commit;
    }

    @Override
    public PRCheck recordCheck(String prId, String kind, String name, String status, Long durationMs)
    {
        PR pr = require(prId);
        requireText(kind, "kind");
        requireText(name, "name");
        requireText(status, "status");
        Instant when = now();
        boolean finished = TERMINAL_CHECK_STATUSES.contains(status);
        PRCheck check = store.addCheck(new PRCheck(
                UUID.randomUUID().toString(), pr.id(), kind, name, status, durationMs,
                when, finished ? when : null, /* runId */ null));
        if (finished) {
            // Local checks never migrate; remote checks are GitHub's own.
            appendEvent(pr.id(), PRTimelineEntry.TYPE_CI, PRTimelineEntry.ACTOR_AGENT,
                    PRCheck.KIND_LOCAL.equals(kind), when,
                    payload("kind", kind, "name", name, "status", status, "durationMs", durationMs));
        }
        notifyUpdated(pr.id());
        return check;
    }

    @Override
    public PRCommit recordSyncedCommit(String prId, String sha, String message, Instant authoredAt, String actor)
    {
        PR pr = require(prId);
        requireText(sha, "sha");
        PRCommit existing = store.commitsFor(pr.id()).stream()
                .filter(commit -> sameSha(commit.sha(), sha))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        Instant when = authoredAt == null ? now() : authoredAt;
        PRCommit commit = store.addCommit(new PRCommit(
                UUID.randomUUID().toString(), pr.id(), sha, message == null ? "" : message,
                0, 0, when, /* pushedAt */ null));
        appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMIT, actor, /* localOnly */ false, when,
                payload("sha", sha, "message", commit.message(), "additions", 0, "deletions", 0));
        notifyUpdated(pr.id());
        return commit;
    }

    @Override
    public PRCheck recordSyncedCheck(
            String prId, String runId, String name, String status, Instant startedAt, Instant finishedAt)
    {
        PR pr = require(prId);
        requireText(runId, "runId");
        requireText(name, "name");
        requireText(status, "status");
        Instant when = now();
        PRCheck existing = store.checksFor(pr.id()).stream()
                .filter(c -> runId.equals(c.runId()))
                .findFirst().orElse(null);
        boolean nowTerminal = TERMINAL_CHECK_STATUSES.contains(status);
        Instant effectiveStart = existing != null ? existing.startedAt() : startedAt != null ? startedAt : when;
        Instant effectiveFinish = !nowTerminal ? null
                : existing != null && existing.finishedAt() != null ? existing.finishedAt()
                : finishedAt != null ? finishedAt : when;
        // No timeline event: unlike a local test run, a synced remote check is
        // one of potentially dozens landing in the same sync pass (Trino
        // #29099 has 60) — GitHub's own Conversation tab never shows one row
        // per check either, only a compact status summary. The Checks tab
        // already renders the full list straight from this table.
        PRCheck check = store.addCheck(new PRCheck(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                pr.id(), PRCheck.KIND_REMOTE, name, status, /* durationMs */ null,
                effectiveStart, effectiveFinish, runId));
        notifyUpdated(pr.id());
        return check;
    }

    @Override
    public synchronized void recordRemoteCiState(
            String prId, String status, String previousStatus, String headSha, int checkCount)
    {
        PR pr = require(prId);
        requireText(status, "status");
        if (repeatsLastRemoteCiState(pr.id(), status, headSha, checkCount)) {
            return;
        }
        Instant when = now();
        appendEvent(pr.id(), PRTimelineEntry.TYPE_CI, PRTimelineEntry.ACTOR_AGENT,
                /* localOnly */ true, when,
                payload("kind", PRCheck.KIND_REMOTE, "status", status,
                        "previousStatus", previousStatus, "headSha", headSha,
                        "checkCount", checkCount));
        notifyUpdated(pr.id());
    }

    /** The PR detail can be refreshed concurrently by polling, dashboard sync,
     *  and a manual refresh. Serialise the read+append and compare against the
     *  durable last aggregate so those callers still produce one transition. */
    private boolean repeatsLastRemoteCiState(String prId, String status, String headSha, int checkCount)
    {
        List<PRTimelineEntry> timeline = store.timelineFor(prId);
        for (int i = timeline.size() - 1; i >= 0; i--) {
            PRTimelineEntry event = timeline.get(i);
            if (!PRTimelineEntry.TYPE_CI.equals(event.eventType()) || event.payloadJson() == null) {
                continue;
            }
            try {
                JsonNode payload = mapper.readTree(event.payloadJson());
                if (!PRCheck.KIND_REMOTE.equals(payload.path("kind").asText())) {
                    continue;
                }
                String previousHead = payload.path("headSha").isTextual()
                        ? payload.path("headSha").textValue()
                        : null;
                return status.equals(payload.path("status").asText())
                        && Objects.equals(headSha, previousHead)
                        && checkCount == payload.path("checkCount").asInt(-1);
            }
            catch (JsonProcessingException ignored) {
                // A malformed historical row cannot establish aggregate state.
            }
        }
        return false;
    }

    @Override
    public void recordRemoteCiRerun(
            String prId, String trigger, String headSha, int workflowCount)
    {
        PR pr = require(prId);
        requireText(trigger, "trigger");
        Instant when = now();
        appendEvent(pr.id(), PRTimelineEntry.TYPE_CI,
                "user".equals(trigger) ? PRTimelineEntry.ACTOR_USER : PRTimelineEntry.ACTOR_AGENT,
                /* localOnly */ true, when,
                payload("kind", PRCheck.KIND_REMOTE, "status", "rerun_requested",
                        "previousStatus", PRCheck.STATUS_FAILED, "headSha", headSha,
                        "checkCount", workflowCount, "trigger", trigger));
        notifyUpdated(pr.id());
    }

    @Override
    public void retainSyncedChecks(String prId, Set<String> runIds)
    {
        PR pr = require(prId);
        store.retainChecks(pr.id(), PRCheck.KIND_REMOTE, Set.copyOf(runIds));
        notifyUpdated(pr.id());
    }

    @Override
    public PR requestUserReview(String prId, String actor)
    {
        PR pr = require(prId);
        PR flipped = transition(pr, PR.STATUS_LOCAL_OPEN, actor);
        // Guarantee a DevReport exists once local-open fires, even via the
        // PRSyncService fallback path that bypasses record_dev_report —
        // a placeholder here is a no-op if the agent already recorded one.
        devReports.ensurePlaceholder(pr.taskId());
        return flipped;
    }

    @Override
    @Transactional
    public PR requestUserReviewInCommand(String taskId, String actor)
    {
        requireText(taskId, "taskId");
        String workflow = taskStore.findWorkflowVersion(taskId)
                .orElseThrow(() -> conflict(
                        "Task " + taskId + " has no immutable workflow route"));
        if (!"V2".equals(workflow)) {
            throw conflict("Historical " + workflow + " Task " + taskId
                    + " cannot enter V2 Local Review");
        }
        TaskCommandExecutor.requireCurrent(taskId);
        PR pr = store.findByTaskId(taskId)
                .orElseThrow(() -> conflict(
                        "Task " + taskId + " has no stable local PR"));
        if (!PR.ORIGIN_TASK.equals(pr.origin())) {
            throw conflict("Task " + taskId
                    + " local PR is not awaiting exact Brain approval");
        }
        if (PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return pr;
        }
        if (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status())) {
            throw conflict("Task " + taskId
                    + " local PR is not awaiting exact Brain approval");
        }
        // Exact Brain acceptance already proves a typed, revisioned V2
        // DevReport. The legacy one-row placeholder API is not valid here.
        return transition(pr, PR.STATUS_LOCAL_OPEN, actor);
    }

    @Override
    public PR transition(String prId, String newStatus, String actor)
    {
        return transition(require(prId), newStatus, actor);
    }

    private PR transition(PR pr, String newStatus, String actor)
    {
        requireText(newStatus, "newStatus");
        if (!pr.canTransitionTo(newStatus)) {
            throw new IllegalArgumentException(
                    "illegal local-PR transition: " + pr.status() + " -> " + newStatus);
        }
        String from = pr.status();
        Instant when = now();
        PR flipped = store.save(pr.withStatus(newStatus, when));
        appendEvent(pr.id(), PRTimelineEntry.TYPE_STATUS, actor, /* localOnly */ false, when,
                payload("from", from, "to", newStatus));
        notifyUpdated(pr.id());
        if (PR.STATUS_MERGED.equals(newStatus)
                && flipped.repo() != null && flipped.remotePrNumber() != null) {
            events.publishEvent(new PrMergedEvent(
                    flipped.repo(), flipped.remotePrNumber(),
                    flipped.title(), flipped.author()));
        }
        return flipped;
    }

    @Override
    public PR recordPushed(String prId, String repo, int remotePrNumber, String remotePrUrl)
    {
        PR pr = require(prId);
        store.save(pr.withRemote(repo, remotePrNumber, remotePrUrl, now()));
        // Reconcile: if the dashboard sync already minted an external twin for
        // this (repo, number) before we stamped it here, fold it into this task
        // row so one GitHub PR maps to one aggregate row.
        PR result = foldExternalTwinIntoTask(prId);
        notifyUpdated(prId);
        return result;
    }

    @Override
    @Transactional
    public PR foldExternalTwinIntoTask(String taskPrId)
    {
        PR task = require(taskPrId);
        if (task.repo() == null || task.remotePrNumber() == null) {
            return task;
        }
        // The finder is scoped to origin='external', so this is the dashboard
        // twin (if any) for the same GitHub PR — never the task row itself.
        Optional<PR> twin = store.findByRepoAndRemotePrNumber(task.repo(), task.remotePrNumber());
        if (twin.isEmpty() || twin.get().id().equals(taskPrId)) {
            return task;
        }
        PR external = twin.get();
        // A starting review or running round keeps the source PR id in its
        // in-memory work item and may still append findings, comments, and
        // timeline events. Reconcile on the next sweep rather than deleting
        // that aggregate mid-run.
        if (store.hasRunningAgentReview(external.id())) {
            return task;
        }
        PR survivor = task;
        // Carry the dashboard's watch/enrichment state onto the survivor so the
        // PR stays on the dashboard. A freshly-pushed task row usually has no
        // snapshot, but even one that does typically lacks watch_reason (only
        // the dashboard sweep sets it) — so adopt the twin's snapshot whenever
        // the survivor has no watch_reason of its own, not just when its whole
        // snapshot is null. Caller (recordPushed) or the reconciler notifies.
        boolean survivorLacksWatch = task.githubSync() == null || task.githubSync().watchReason() == null;
        if (survivorLacksWatch && external.githubSync() != null && external.githubSync().watchReason() != null) {
            survivor = store.save(task.withGithubSync(external.githubSync()));
        }
        store.reparentChildren(external.id(), taskPrId);
        store.deletePr(external.id());
        return survivor;
    }

    @Override
    public PR recordPush(String prId, String repo, int remotePrNumber, String remotePrUrl)
    {
        PR pr = require(prId);
        if (pr.taskId() == null) {
            return recordPush(pr, repo, remotePrNumber, remotePrUrl);
        }
        return commands.execute(pr.taskId(), () ->
                recordPushInCommand(prId, repo, remotePrNumber, remotePrUrl));
    }

    @Override
    @Transactional
    public PR recordPushInCommand(
            String prId, String repo, int remotePrNumber, String remotePrUrl)
    {
        PR pr = require(prId);
        if (pr.taskId() == null) {
            throw conflict("PR " + prId + " is not task-owned");
        }
        TaskCommandExecutor.requireCurrent(pr.taskId());
        return recordPush(pr, repo, remotePrNumber, remotePrUrl, true);
    }

    @Override
    @Transactional
    public PR recordPublishedInCommand(
            String prId, String repo, int remotePrNumber, String remotePrUrl)
    {
        PR pr = require(prId);
        if (pr.taskId() == null) {
            throw conflict("PR " + prId + " is not task-owned");
        }
        TaskCommandExecutor.requireCurrent(pr.taskId());
        return recordPush(pr, repo, remotePrNumber, remotePrUrl, false);
    }

    private PR recordPush(PR pr, String repo, int remotePrNumber, String remotePrUrl)
    {
        return recordPush(pr, repo, remotePrNumber, remotePrUrl, true);
    }

    private PR recordPush(
            PR pr,
            String repo,
            int remotePrNumber,
            String remotePrUrl,
            boolean recordLegacyStageEvent)
    {
        String prId = pr.id();
        Instant when = now();
        // Strip the private local record before it can be confused with what
        // migrated — local-only events + local-origin comments never leave
        // ByteQuay (design #47, non-negotiable).
        for (PRTimelineEntry event : store.unstrippedLocalOnlyEvents(prId)) {
            store.addEvent(event.withStripped(when));
        }
        for (PRComment comment : store.unstrippedLocalComments(prId)) {
            store.saveComment(comment.withStripped(when));
        }
        recordPushed(prId, repo, remotePrNumber, remotePrUrl);
        List<PRCommit> commits = store.commitsFor(prId);
        int additions = commits.stream().mapToInt(PRCommit::additions).sum();
        int deletions = commits.stream().mapToInt(PRCommit::deletions).sum();
        String payload = payload(
                "phase", PRTimelineEntry.PHASE_CREATED,
                "branch", pr.branchName(),
                "baseBranch", pr.baseBranch(),
                "number", remotePrNumber,
                "url", remotePrUrl,
                "additions", additions,
                "deletions", deletions);
        appendEvent(prId, PRTimelineEntry.TYPE_PULL_REQUEST_CREATED, PRTimelineEntry.ACTOR_USER,
                /* localOnly */ false, when, payload);
        if (recordLegacyStageEvent) {
            recordPullRequestCreated(
                    pr, remotePrNumber, remotePrUrl, additions, deletions);
        }
        return transition(prId, PR.STATUS_REMOTE_DRAFTED, PRTimelineEntry.ACTOR_USER);
    }

    /** Records the one remote-PR milestone on the Development stage so the
     * stage transcript and task brain receive the exact same publish event as
     * the PR timeline. A local PR only calls {@link #recordPush} on its first
     * remote promotion, so subsequent remote branch pushes do not duplicate it. */
    private void recordPullRequestCreated(
            PR pr, int remotePrNumber, String remotePrUrl, int additions, int deletions)
    {
        stageStore.findStageByType(pr.taskId(), StageType.DEVELOPMENT_STAGE)
                .ifPresent(stage -> stageStore.recordEvent(
                        stage.id(), pr.taskId(), StageEventType.PULL_REQUEST_CREATED,
                        Map.of(
                                "phase", PRTimelineEntry.PHASE_CREATED,
                                "branch", pr.branchName(),
                                "baseBranch", pr.baseBranch(),
                                "number", remotePrNumber,
                                "url", remotePrUrl,
                                "additions", additions,
                                "deletions", deletions)));
    }

    @Override
    public PR recordMerged(String prId)
    {
        return transition(prId, PR.STATUS_MERGED, PRTimelineEntry.ACTOR_USER);
    }

    @Override
    public PR recordBranchDeleted(String prId)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withBranchDeleted(now()));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public int pendingStripCount(String prId)
    {
        return store.unstrippedLocalOnlyEvents(prId).size() + store.unstrippedLocalComments(prId).size();
    }

    @Override
    public PRComment addComment(
            String prId,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String author,
            String body,
            String parentCommentId)
    {
        PR pr = require(prId);
        if (v2LocalReview != null
                && PRComment.ORIGIN_LOCAL.equals(origin)
                && v2LocalReview.handles(pr)) {
            return v2LocalReview.addComment(
                    pr, origin, scope, filePath, lineNumber, side,
                    startLine, startSide, author, body, parentCommentId);
        }
        rejectTaskOwnedFallback(pr, "comment creation");
        if (pr.taskId() == null) {
            return addComment(pr, origin, scope, filePath, lineNumber, side,
                    startLine, startSide, author, body, parentCommentId);
        }
        throw new IllegalStateException("unreachable Task-owned PR fallback");
    }

    private PRComment addComment(
            PR pr,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String author,
            String body,
            String parentCommentId)
    {
        if (PR.ORIGIN_TASK.equals(pr.origin())
                && PRTimelineEntry.ACTOR_USER.equals(author)
                && PRComment.ORIGIN_LOCAL.equals(origin)) {
            requireTaskReviewDraftOpen(pr);
        }
        requireText(origin, "origin");
        requireText(author, "author");
        requireText(body, "body");
        PRComment parent = resolveParentComment(pr, parentCommentId);
        if (parent != null
                && PR.ORIGIN_TASK.equals(pr.origin())
                && parent.strippedOnPushAt() != null) {
            throw notCurrentRemoteReviewDraft();
        }
        if (parent != null
                && PR.ORIGIN_TASK.equals(pr.origin())
                && PRComment.ORIGIN_LOCAL.equals(origin)
                && PRTimelineEntry.ACTOR_USER.equals(author)
                && PRTimelineEntry.ACTOR_BRAIN.equals(parent.author())
                && (parent.resolvedAt() != null || parent.dismissedAt() != null)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "This Brain finding is already resolved; add a new review comment for new feedback");
        }
        if (parent != null) {
            scope = parent.scope();
            filePath = parent.filePath();
            lineNumber = parent.lineNumber();
            side = parent.side();
            startLine = parent.startLine();
            startSide = parent.startSide();
            parentCommentId = parent.id();
        }
        if (PRComment.SCOPE_FILE_LINE.equals(scope)) {
            if (filePath == null || filePath.isBlank() || lineNumber == null) {
                throw new IllegalArgumentException("file-line comment requires filePath + lineNumber");
            }
        }
        else if (PRComment.SCOPE_PR.equals(scope)) {
            filePath = null;
            lineNumber = null;
        }
        else {
            throw new IllegalArgumentException("scope must be 'pr' or 'file-line'");
        }
        String resolvedSide = DiffSide.normalize(side);
        // Multi-line range: null unless a distinct startLine was given.
        Integer resolvedStartLine = null;
        String resolvedStartSide = null;
        if (startLine != null && !startLine.equals(lineNumber)) {
            resolvedStartLine = startLine;
            resolvedStartSide = DiffSide.normalizeOptional(startSide, resolvedSide);
        }
        Instant when = now();
        PRComment comment = store.saveComment(new PRComment(
                UUID.randomUUID().toString(), pr.id(), origin, scope, filePath, lineNumber,
                author, body, when, /* resolvedAt */ null, /* dismissedAt */ null,
                /* strippedOnPushAt */ null, parentCommentId, /* publishedAt */ null,
                resolvedSide, resolvedStartLine, resolvedStartSide));
        // PR-level comments show on the timeline; inline comments live on the
        // diff, so only a pr-scoped comment writes a timeline event.
        if (PRComment.SCOPE_PR.equals(scope)) {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMENT, author,
                    PRComment.ORIGIN_LOCAL.equals(origin), when, payload("commentId", comment.id()));
        }
        boolean userReplyToTaskLocalRoot = PR.ORIGIN_TASK.equals(pr.origin())
                && PRComment.ORIGIN_LOCAL.equals(origin)
                && PRTimelineEntry.ACTOR_USER.equals(author)
                && parent != null
                && parent.parentCommentId() == null
                && PRComment.ORIGIN_LOCAL.equals(parent.origin());
        if (userReplyToTaskLocalRoot
                && (parent.resolvedAt() != null || parent.dismissedAt() != null)) {
            store.saveComment(parent.withReopened());
        }
        if (userReplyToTaskLocalRoot
                && localReviewSubmissions(pr.id()).stream()
                        .anyMatch(submission -> submission.commentIds().contains(parent.id()))) {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                    /* localOnly */ true, when,
                    payload("reviewEvent", "updated", "commentId", parent.id()));
        }
        notifyUpdated(pr.id());
        return comment;
    }

    private PRComment resolveParentComment(PR pr, String parentCommentId)
    {
        if (parentCommentId == null || parentCommentId.isBlank()) {
            return null;
        }
        String id = parentCommentId.strip();
        PRComment parent = store.findCommentById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown parent comment: " + id));
        if (!pr.id().equals(parent.prId())) {
            throw new IllegalArgumentException("parent comment " + id + " belongs to another PR");
        }
        return parent;
    }

    @Override
    public boolean hasRemoteEvent(String prId, long remoteEventId)
    {
        return store.timelineEventExistsByRemoteId(prId, remoteEventId);
    }

    @Override
    public PRComment addRemoteComment(String prId, String author, String body, Instant createdAt,
            long remoteCommentId)
    {
        PR pr = require(prId);
        PRComment comment = store.saveComment(new PRComment(
                UUID.randomUUID().toString(), pr.id(), PRComment.ORIGIN_REMOTE, PRComment.SCOPE_PR,
                /* filePath */ null, /* lineNumber */ null, author, body, createdAt,
                /* resolvedAt */ null, /* dismissedAt */ null, /* strippedOnPushAt */ null,
                /* parentCommentId */ null, /* publishedAt */ null,
                DiffSide.RIGHT, /* startLine */ null, /* startSide */ null));
        appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMENT, author, /* localOnly */ false, createdAt,
                payload("commentId", comment.id()), remoteCommentId);
        notifyUpdated(pr.id());
        return comment;
    }

    @Override
    public void deleteDraftComment(String commentId)
    {
        if (v2LocalReview != null && v2LocalReview.ownsComment(commentId)) {
            v2LocalReview.deleteDraftComment(commentId);
            return;
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        rejectTaskOwnedFallback(require(comment.prId()), "comment deletion");
        if (!PRComment.ORIGIN_LOCAL.equals(comment.origin()) ||
                comment.publishedAt() != null ||
                comment.resolvedAt() != null ||
                comment.dismissedAt() != null ||
                comment.strippedOnPushAt() != null) {
            throw new IllegalArgumentException("comment is not an open local draft: " + commentId);
        }
        store.deleteComment(comment.id());
        notifyUpdated(comment.prId());
    }

    @Override
    public void recordRemoteReview(
            String prId, String reviewer, String verdict, String body, Instant when, long remoteReviewId)
    {
        PR pr = require(prId);
        appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, reviewer, /* localOnly */ false, when,
                payload("verdict", verdict, "body", body), remoteReviewId);
        notifyUpdated(pr.id());
    }

    @Override
    public void recordLocalReviewSubmission(
            String prId, List<String> commentIds, String body, String verdict, String bodyCommentId)
    {
        PR pr = require(prId);
        List<String> ids = commentIds == null ? List.of() : commentIds.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
        if (pr.taskId() == null) {
            recordSubmissionRows(pr, ids, body, verdict, bodyCommentId);
            return;
        }
        // Effect gate before the task stripe: the durable submission row,
        // its timeline event, and the epoch bump commit as one admission
        // that a concurrent ship/terminal command must serialize against.
        TaskExternalEffectGate.withEffectGate(pr.taskId(), () -> {
            commands.executeVoid(pr.taskId(), () -> {
                TaskCommandExecutor.requireCurrent(pr.taskId());
                PR current = require(pr.id());
                Task task = taskStore.findTaskById(pr.taskId())
                        .orElseThrow(() -> conflict("no task for local PR " + pr.id()));
                if (!acceptsLocalSubmission(task)) {
                    throw conflict("task " + task.id()
                            + " is not accepting Local Review comments");
                }
                var activePush = pushStore.findActiveByTask(task.id());
                if (activePush.isPresent() && !pushStore.revokeIfUnclaimed(
                        activePush.orElseThrow().token(),
                        "review_submission_superseded", now())) {
                    throw conflict("task " + task.id()
                            + " has a Push already in progress; finish or recover it first");
                }
                recordSubmissionRows(current, ids, body, verdict, bodyCommentId);
            });
            return null;
        });
    }

    private static boolean acceptsLocalSubmission(Task task)
    {
        if (task.status() == TaskStatus.PAUSED
                || task.status() == TaskStatus.NEEDS_ATTENTION
                || task.status() == TaskStatus.ERRORED
                || task.status() == TaskStatus.ARCHIVED
                || task.status().isDone()) {
            return false;
        }
        return task.phase() == TaskPhase.AWAITING_PUSH
                || task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS
                || task.phase() == TaskPhase.INTERNAL_REVIEW;
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(409), message);
    }

    private void recordSubmissionRows(
            PR pr, List<String> ids, String body, String verdict, String bodyCommentId)
    {
        Instant when = now();
        String eventId = UUID.randomUUID().toString();
        store.addEvent(new PRTimelineEntry(
                eventId, pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                /* localOnly */ true, /* strippedOnPushAt */ null, when,
                payload("reviewEvent", "submitted", "verdict", verdict,
                        "commentIds", ids, "findingCount", ids.size(), "body", body,
                        "bodyCommentId", bodyCommentId),
                /* remoteEventId */ null));
        if (pr.taskId() != null) {
            store.incrementLocalReviewEpoch(pr.id());
            submissionStore.insert(new com.bytequay.app.domain.LocalReviewSubmission(
                    UUID.randomUUID().toString(), eventId, pr.taskId(), pr.id(),
                    /* agentRunId */ null, submissionStore.nextSeq(pr.taskId()),
                    toJson(ids), rootSnapshotJson(pr.id(), ids), when,
                    /* addressedThroughAt */ null, /* attempt */ 0, /* failures */ 0,
                    when, /* activatedAt */ null, /* completedAt */ null,
                    /* canceledAt */ null, /* cancelReason */ null));
        }
        notifyUpdated(pr.id());
        events.publishEvent(new LocalReviewSubmittedEvent(pr.taskId(), pr.id()));
    }

    /** Freeze each submitted root's revision (body, anchor, order) so
     *  later edits create a new submission instead of mutating what the
     *  agent was asked to address. */
    private String rootSnapshotJson(String prId, List<String> ids)
    {
        Map<String, PRComment> byId = new LinkedHashMap<>();
        for (PRComment comment : comments(prId)) {
            byId.put(comment.id(), comment);
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        int order = 0;
        for (String id : ids) {
            PRComment comment = byId.get(id);
            Map<String, Object> frozen = new LinkedHashMap<>();
            frozen.put("id", id);
            frozen.put("order", order++);
            if (comment != null) {
                frozen.put("author", comment.author());
                frozen.put("body", comment.body());
                frozen.put("filePath", comment.filePath());
                frozen.put("createdAtMs", comment.createdAt().toEpochMilli());
            }
            snapshot.add(frozen);
        }
        return toJson(snapshot);
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise submission snapshot", e);
        }
    }

    @Override
    public void recordReviewEvent(String prId, String actor, String payloadJson)
    {
        PR pr = require(prId);
        appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, actor, true, now(), payloadJson);
        notifyUpdated(pr.id());
    }

    @Override
    public PRComment resolveComment(String commentId)
    {
        if (v2LocalReview != null && v2LocalReview.ownsComment(commentId)) {
            return v2LocalReview.resolveComment(commentId);
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        rejectTaskOwnedFallback(require(comment.prId()), "comment resolution");
        PRComment saved = store.saveComment(comment.withResolved(now(), PRTimelineEntry.ACTOR_USER));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment resolveCommentForAgent(String commentId)
    {
        return closeCommentForAgent(commentId, false);
    }

    @Override
    public PRComment reopenComment(String commentId)
    {
        if (v2LocalReview != null && v2LocalReview.ownsComment(commentId)) {
            return v2LocalReview.reopenComment(commentId);
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PR pr = require(comment.prId());
        rejectTaskOwnedFallback(pr, "comment reopening");
        return reopenComment(pr, commentId);
    }

    private PRComment reopenComment(PR pr, String commentId)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        if (!pr.id().equals(comment.prId())) {
            throw new IllegalArgumentException("comment " + commentId + " belongs to another PR");
        }
        if (PR.ORIGIN_TASK.equals(pr.origin())) {
            requireTaskReviewDraftOpen(pr);
            if (isRemoteTaskReview(pr)) {
                requireCurrentRemoteTaskReviewDraft(comment);
            }
        }
        PRComment saved = store.saveComment(comment.withReopened());
        if (PR.ORIGIN_TASK.equals(pr.origin())
                && PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && comment.parentCommentId() == null
                && (comment.resolvedAt() != null || comment.dismissedAt() != null)) {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_USER,
                    /* localOnly */ true, now(),
                    payload("reviewEvent", "reopened", "commentId", comment.id()));
        }
        notifyUpdated(comment.prId());
        return saved;
    }

    private void requireTaskReviewDraftOpen(PR pr)
    {
        if (isRemoteTaskReview(pr)) {
            return;
        }
        if (PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
            boolean activeLocalReview = task != null
                    && task.status() != TaskStatus.NEEDS_ATTENTION
                    && (task.phase() == TaskPhase.AWAITING_PUSH
                            || task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS
                            || task.phase() == TaskPhase.INTERNAL_REVIEW);
            if (activeLocalReview) {
                return;
            }
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "Review is not open for comments; refresh and use the current review surface");
    }

    private static boolean isRemoteTaskReview(PR pr)
    {
        return PR.STATUS_REMOTE_DRAFTED.equals(pr.status()) || PR.STATUS_REMOTE_OPEN.equals(pr.status());
    }

    private static void requireCurrentRemoteTaskReviewDraft(PRComment comment)
    {
        if (!PRComment.ORIGIN_LOCAL.equals(comment.origin())
                || comment.parentCommentId() != null
                || comment.publishedAt() != null
                || comment.strippedOnPushAt() != null) {
            throw notCurrentRemoteReviewDraft();
        }
    }

    private static ResponseStatusException notCurrentRemoteReviewDraft()
    {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "This comment is not a current remote review draft; add a new review comment");
    }

    @Override
    public PRComment dismissComment(String commentId)
    {
        if (v2LocalReview != null && v2LocalReview.ownsComment(commentId)) {
            return v2LocalReview.dismissComment(commentId);
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        rejectTaskOwnedFallback(require(comment.prId()), "comment dismissal");
        PRComment saved = store.saveComment(comment.withDismissed(now()));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment dismissCommentForAgent(String commentId)
    {
        return closeCommentForAgent(commentId, true);
    }

    private PRComment closeCommentForAgent(String commentId, boolean dismissed)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PR pr = require(comment.prId());
        if (pr.taskId() != null) {
            return TaskPhaseMachine.withTaskLock(pr.taskId(), () ->
                    closeCommentForAgent(require(pr.id()), commentId, dismissed));
        }
        return closeCommentForAgent(pr, commentId, dismissed);
    }

    private PRComment closeCommentForAgent(PR pr, String commentId, boolean dismissed)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        if (!pr.id().equals(comment.prId())) {
            throw new IllegalArgumentException("comment " + commentId + " belongs to another PR");
        }
        boolean submittedDevelopmentRoot = PR.ORIGIN_TASK.equals(pr.origin())
                && PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && comment.parentCommentId() == null
                && (PRTimelineEntry.ACTOR_USER.equals(comment.author())
                        || PRTimelineEntry.ACTOR_AGENT.equals(comment.author()) && comment.findingId() != null);
        if (submittedDevelopmentRoot) {
            List<LocalReviewSubmission> submissions = localReviewSubmissions(pr.id());
            Optional<Instant> activeSubmission = submissions.stream()
                    .filter(submission -> submission.commentIds().contains(comment.id()))
                    .map(LocalReviewSubmission::submittedAt)
                    .max(Instant::compareTo);
            Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
            boolean legacyAddressing = task != null
                    && task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS
                    && submissions.isEmpty();
            Instant dispatchedThrough = pr.localAddressedThroughAt();
            if (!legacyAddressing && (activeSubmission.isEmpty() || dispatchedThrough == null
                    || activeSubmission.get().isAfter(dispatchedThrough))) {
                throw new IllegalArgumentException(
                        "comment " + commentId
                                + " changed after this Development turn began; leave it open for the next submitted review");
            }
        }
        boolean brainFindingRoot = PR.ORIGIN_TASK.equals(pr.origin())
                && PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && comment.parentCommentId() == null
                && PRTimelineEntry.ACTOR_BRAIN.equals(comment.author());
        if (brainFindingRoot) {
            Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
            ThreadTurn fixTurn = task == null ? null : turnStore
                    .listTurnsByTaskIdAndStatus(task.threadId(), ThreadTurnStatus.RUNNING, TURN_SCAN_LIMIT)
                    .stream()
                    .filter(turn -> pr.taskId().equals(turn.taskId()))
                    .filter(turn -> turn.initiator() != null
                            && SOURCE_BRAIN_REVIEW_FIX.equals(turn.initiator().source()))
                    .max((left, right) -> left.createdAt().compareTo(right.createdAt()))
                    .orElse(null);
            boolean newerUserReply = fixTurn != null && store.commentsFor(pr.id()).stream()
                    .filter(reply -> comment.id().equals(reply.parentCommentId()))
                    .filter(reply -> PRTimelineEntry.ACTOR_USER.equals(reply.author()))
                    .anyMatch(reply -> !reply.createdAt().isBefore(fixTurn.createdAt()));
            if (fixTurn == null || newerUserReply) {
                throw new IllegalArgumentException(
                        "comment " + commentId
                                + " changed after this Development turn began; leave it open for the next Brain fix turn");
            }
        }
        PRComment saved = store.saveComment(dismissed
                ? comment.withDismissed(now())
                : comment.withResolved(now(), PRTimelineEntry.ACTOR_AGENT));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment markPublished(String commentId, Instant when)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PRComment saved = store.saveComment(comment.withPublished(when));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment attachFinding(String commentId, String findingId)
    {
        if (findingId == null || findingId.isBlank()) {
            throw new IllegalArgumentException("findingId is required");
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PRComment saved = store.saveComment(comment.withFinding(findingId));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment editCommentBody(String commentId, String body)
    {
        if (v2LocalReview != null && v2LocalReview.ownsComment(commentId)) {
            return v2LocalReview.editComment(commentId, body);
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("comment body is required");
        }
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        rejectTaskOwnedFallback(require(comment.prId()), "comment editing");
        if (!PRComment.ORIGIN_LOCAL.equals(comment.origin()) || comment.publishedAt() != null) {
            throw new IllegalArgumentException("only pending local comments can be edited");
        }
        PRComment saved = store.saveComment(comment.withBody(body.strip()));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PR markLocalAddressed(String prId, Instant through)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withLocalAddressedThrough(through));
        notifyUpdated(prId);
        return saved;
    }

    private PR require(String prId)
    {
        return store.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown local PR: " + prId));
    }

    private void rejectTaskOwnedFallback(PR pr, String action)
    {
        if (pr.taskId() == null) {
            return;
        }
        String workflow = taskStore.findWorkflowVersion(pr.taskId())
                .orElseThrow(() -> conflict(
                        "Task " + pr.taskId() + " has no immutable workflow route"));
        if ("LEGACY".equals(workflow)) {
            throw conflict("Historical LEGACY Task " + pr.taskId()
                    + " is read-only; " + action + " is retired");
        }
        if (!"V2".equals(workflow)) {
            throw conflict("unsupported Task workflow version " + workflow);
        }
        throw conflict("V2 Task-owned PR " + action
                + " must use its exact Local Development owner");
    }

    private void notifyUpdated(String prId)
    {
        events.publishEvent(new PrUpdatedEvent(prId));
    }

    private void appendEvent(
            String prId, String type, String actor, boolean localOnly, Instant when, String payloadJson)
    {
        appendEvent(prId, type, actor, localOnly, when, payloadJson, /* remoteEventId */ null);
    }

    private void appendEvent(
            String prId, String type, String actor, boolean localOnly, Instant when, String payloadJson,
            Long remoteEventId)
    {
        store.addEvent(new PRTimelineEntry(
                UUID.randomUUID().toString(), prId, type, actor == null ? "" : actor,
                localOnly, /* strippedOnPushAt */ null, when, payloadJson, remoteEventId));
    }

    private String payload(Object... keyValues)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        try {
            return mapper.writeValueAsString(map);
        }
        catch (JsonProcessingException e) {
            // Simple string/number maps don't fail to serialise; treat any as a bug.
            throw new IllegalStateException("failed to serialise timeline payload", e);
        }
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private static boolean sameSha(String left, String right)
    {
        return left != null && right != null
                && (left.equals(right) || left.startsWith(right) || right.startsWith(left));
    }

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
