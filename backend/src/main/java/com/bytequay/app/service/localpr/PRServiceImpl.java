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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
class PRServiceImpl
        implements PRService
{
    /** Check statuses that count as finished — a {@code ci} timeline event is
     *  written only when a check lands in one of these. */
    private static final Set<String> TERMINAL_CHECK_STATUSES =
            Set.of(PRCheck.STATUS_PASSED, PRCheck.STATUS_FAILED, PRCheck.STATUS_NEUTRAL);

    private final PRStore store;
    private final DevReportService devReports;
    private final ObjectMapper mapper;
    private final StageStore stageStore;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    PRServiceImpl(
            PRStore store, DevReportService devReports, ObjectMapper mapper, StageStore stageStore,
            ApplicationEventPublisher events)
    {
        this(store, devReports, mapper, stageStore, events, Clock.systemUTC());
    }

    PRServiceImpl(
            PRStore store, DevReportService devReports, ObjectMapper mapper, StageStore stageStore,
            ApplicationEventPublisher events, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.devReports = requireNonNull(devReports, "devReports is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.events = requireNonNull(events, "events is null");
        this.clock = requireNonNull(clock, "clock is null");
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
        requireText(taskId, "taskId");
        requireText(branchName, "branchName");
        requireText(baseBranch, "baseBranch");
        requireText(title, "title");
        // Idempotent — Dev calls this on its first commit and may retry.
        Optional<PR> existing = store.findByTaskId(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PR pr = PR.create(
                UUID.randomUUID().toString(), taskId, branchName, baseBranch, title, description, now());
        PR saved = store.save(pr);
        backfillPlanSelfReview(taskId, saved.id());
        notifyUpdated(saved.id());
        return saved;
    }

    /** The plan self-review (R20) predates the local PR — its `review` event
     *  is backfilled onto the timeline, at its original timestamp, the first
     *  time a local PR row is created for this task. */
    private void backfillPlanSelfReview(String taskId, String prId)
    {
        stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .findFirst()
                .flatMap(plan -> stageStore.findEventsByStage(plan.id()).stream()
                        .filter(e -> e.eventType() == StageEventType.PLAN_SELF_REVIEWED)
                        .findFirst())
                .ifPresent(reviewed -> appendEvent(
                        prId, PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                        /* localOnly */ true, reviewed.eventAt(),
                        payload("scope", "plan", "verdict", verdictOf(reviewed), "iteration", 1)));
    }

    private String verdictOf(StageEvent event)
    {
        try {
            return event.payloadJson() == null ? null
                    : mapper.readTree(event.payloadJson()).path("verdict").asText(null);
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public void recordBrainReview(String taskId, String scope, String verdict, int iteration)
    {
        store.findByTaskId(taskId).ifPresent(pr -> {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                    /* localOnly */ true, now(),
                    payload("scope", scope, "verdict", verdict, "iteration", iteration));
            notifyUpdated(pr.id());
        });
    }

    @Override
    public PR updateDetails(String prId, String title, String description)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withDetails(title, description));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public PRCommit recordCommit(
            String prId, String sha, String message, int additions, int deletions, String actor)
    {
        PR pr = require(prId);
        requireText(sha, "sha");
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
    public PR requestUserReview(String prId, String actor)
    {
        PR pr = require(prId);
        PR flipped = transition(prId, PR.STATUS_LOCAL_OPEN, actor);
        // Guarantee a DevReport exists once local-open fires, even via the
        // PRSyncService fallback path that bypasses record_dev_report —
        // a placeholder here is a no-op if the agent already recorded one.
        devReports.ensurePlaceholder(pr.taskId());
        return flipped;
    }

    @Override
    public PR transition(String prId, String newStatus, String actor)
    {
        PR pr = require(prId);
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
        return flipped;
    }

    @Override
    public PR recordPushed(String prId, int remotePrNumber, String remotePrUrl)
    {
        PR pr = require(prId);
        PR saved = store.save(pr.withRemote(remotePrNumber, remotePrUrl, now()));
        notifyUpdated(prId);
        return saved;
    }

    @Override
    public PR recordPush(String prId, int remotePrNumber, String remotePrUrl)
    {
        require(prId);
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
        recordPushed(prId, remotePrNumber, remotePrUrl);
        return transition(prId, PR.STATUS_REMOTE_DRAFTED, PRTimelineEntry.ACTOR_USER);
    }

    @Override
    public PR recordMerged(String prId)
    {
        return transition(prId, PR.STATUS_MERGED, PRTimelineEntry.ACTOR_USER);
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
            String author,
            String body,
            String parentCommentId)
    {
        PR pr = require(prId);
        requireText(origin, "origin");
        requireText(author, "author");
        requireText(body, "body");
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
        Instant when = now();
        PRComment comment = store.saveComment(new PRComment(
                UUID.randomUUID().toString(), pr.id(), origin, scope, filePath, lineNumber,
                author, body, when, /* resolvedAt */ null, /* dismissedAt */ null,
                /* strippedOnPushAt */ null, parentCommentId));
        // PR-level comments show on the timeline; inline comments live on the
        // diff, so only a pr-scoped comment writes a timeline event.
        if (PRComment.SCOPE_PR.equals(scope)) {
            appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMENT, author,
                    PRComment.ORIGIN_LOCAL.equals(origin), when, payload("commentId", comment.id()));
        }
        notifyUpdated(pr.id());
        return comment;
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
                /* parentCommentId */ null));
        appendEvent(pr.id(), PRTimelineEntry.TYPE_COMMENT, author, /* localOnly */ false, createdAt,
                payload("commentId", comment.id()), remoteCommentId);
        notifyUpdated(pr.id());
        return comment;
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
    public PRComment resolveComment(String commentId)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PRComment saved = store.saveComment(comment.withResolved(now()));
        notifyUpdated(comment.prId());
        return saved;
    }

    @Override
    public PRComment dismissComment(String commentId)
    {
        PRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        PRComment saved = store.saveComment(comment.withDismissed(now()));
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

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
