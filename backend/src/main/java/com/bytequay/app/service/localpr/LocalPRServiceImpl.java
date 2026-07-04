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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRCommit;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.repository.LocalPRStore;
import com.bytequay.app.service.review.DevReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
class LocalPRServiceImpl
        implements LocalPRService
{
    /** Check statuses that count as finished — a {@code ci} timeline event is
     *  written only when a check lands in one of these. */
    private static final Set<String> TERMINAL_CHECK_STATUSES =
            Set.of(LocalPRCheck.STATUS_PASSED, LocalPRCheck.STATUS_FAILED, LocalPRCheck.STATUS_NEUTRAL);

    private final LocalPRStore store;
    private final DevReportService devReports;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    LocalPRServiceImpl(LocalPRStore store, DevReportService devReports, ObjectMapper mapper)
    {
        this(store, devReports, mapper, Clock.systemUTC());
    }

    LocalPRServiceImpl(LocalPRStore store, DevReportService devReports, ObjectMapper mapper, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.devReports = requireNonNull(devReports, "devReports is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public Optional<LocalPR> findByTask(String taskId)
    {
        return store.findByTaskId(taskId);
    }

    @Override
    public Optional<LocalPR> findById(String prId)
    {
        return store.findById(prId);
    }

    @Override
    public List<LocalPRCommit> commits(String prId)
    {
        return store.commitsFor(prId);
    }

    @Override
    public List<LocalPRTimelineEvent> timeline(String prId)
    {
        return store.timelineFor(prId);
    }

    @Override
    public List<LocalPRCheck> checks(String prId)
    {
        return store.checksFor(prId);
    }

    @Override
    public List<LocalPRComment> comments(String prId)
    {
        return store.commentsFor(prId);
    }

    @Override
    public LocalPR createForTask(
            String taskId, String branchName, String baseBranch, String title, String description)
    {
        requireText(taskId, "taskId");
        requireText(branchName, "branchName");
        requireText(baseBranch, "baseBranch");
        requireText(title, "title");
        // Idempotent — Dev calls this on its first commit and may retry.
        Optional<LocalPR> existing = store.findByTaskId(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalPR pr = LocalPR.create(
                UUID.randomUUID().toString(), taskId, branchName, baseBranch, title, description, now());
        return store.save(pr);
    }

    @Override
    public LocalPR updateDetails(String prId, String title, String description)
    {
        LocalPR pr = require(prId);
        return store.save(pr.withDetails(title, description));
    }

    @Override
    public LocalPRCommit recordCommit(
            String prId, String sha, String message, int additions, int deletions, String actor)
    {
        LocalPR pr = require(prId);
        requireText(sha, "sha");
        Instant when = now();
        LocalPRCommit commit = store.addCommit(new LocalPRCommit(
                UUID.randomUUID().toString(), pr.id(), sha, message == null ? "" : message,
                additions, deletions, when, /* pushedAt */ null));
        // A commit is real git history that migrates on push, so its event is
        // never local-only.
        appendEvent(pr.id(), LocalPRTimelineEvent.TYPE_COMMIT, actor, /* localOnly */ false, when,
                payload("sha", sha, "message", commit.message(),
                        "additions", additions, "deletions", deletions));
        return commit;
    }

    @Override
    public LocalPRCheck recordCheck(String prId, String kind, String name, String status, Long durationMs)
    {
        LocalPR pr = require(prId);
        requireText(kind, "kind");
        requireText(name, "name");
        requireText(status, "status");
        Instant when = now();
        boolean finished = TERMINAL_CHECK_STATUSES.contains(status);
        LocalPRCheck check = store.addCheck(new LocalPRCheck(
                UUID.randomUUID().toString(), pr.id(), kind, name, status, durationMs,
                when, finished ? when : null, /* runId */ null));
        if (finished) {
            // Local checks never migrate; remote checks are GitHub's own.
            appendEvent(pr.id(), LocalPRTimelineEvent.TYPE_CI, LocalPRTimelineEvent.ACTOR_AGENT,
                    LocalPRCheck.KIND_LOCAL.equals(kind), when,
                    payload("kind", kind, "name", name, "status", status, "durationMs", durationMs));
        }
        return check;
    }

    @Override
    public LocalPR requestUserReview(String prId, String actor)
    {
        LocalPR pr = require(prId);
        LocalPR flipped = transition(prId, LocalPR.STATUS_LOCAL_OPEN, actor);
        // Guarantee a DevReport exists once local-open fires, even via the
        // LocalPRSyncService fallback path that bypasses record_dev_report —
        // a placeholder here is a no-op if the agent already recorded one.
        devReports.ensurePlaceholder(pr.taskId());
        return flipped;
    }

    @Override
    public LocalPR transition(String prId, String newStatus, String actor)
    {
        LocalPR pr = require(prId);
        requireText(newStatus, "newStatus");
        if (!pr.canTransitionTo(newStatus)) {
            throw new IllegalArgumentException(
                    "illegal local-PR transition: " + pr.status() + " -> " + newStatus);
        }
        String from = pr.status();
        Instant when = now();
        LocalPR flipped = store.save(pr.withStatus(newStatus, when));
        appendEvent(pr.id(), LocalPRTimelineEvent.TYPE_STATUS, actor, /* localOnly */ false, when,
                payload("from", from, "to", newStatus));
        return flipped;
    }

    @Override
    public LocalPR recordPushed(String prId, int remotePrNumber, String remotePrUrl)
    {
        LocalPR pr = require(prId);
        return store.save(pr.withRemote(remotePrNumber, remotePrUrl, now()));
    }

    @Override
    public LocalPR recordPush(String prId, int remotePrNumber, String remotePrUrl)
    {
        require(prId);
        Instant when = now();
        // Strip the private local record before it can be confused with what
        // migrated — local-only events + local-origin comments never leave
        // ByteQuay (design #47, non-negotiable).
        for (LocalPRTimelineEvent event : store.unstrippedLocalOnlyEvents(prId)) {
            store.addEvent(event.withStripped(when));
        }
        for (LocalPRComment comment : store.unstrippedLocalComments(prId)) {
            store.saveComment(comment.withStripped(when));
        }
        recordPushed(prId, remotePrNumber, remotePrUrl);
        return transition(prId, LocalPR.STATUS_REMOTE_DRAFTED, LocalPRTimelineEvent.ACTOR_USER);
    }

    @Override
    public LocalPR recordMerged(String prId)
    {
        return transition(prId, LocalPR.STATUS_MERGED, LocalPRTimelineEvent.ACTOR_USER);
    }

    @Override
    public int pendingStripCount(String prId)
    {
        return store.unstrippedLocalOnlyEvents(prId).size() + store.unstrippedLocalComments(prId).size();
    }

    @Override
    public LocalPRComment addComment(
            String prId,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String author,
            String body,
            String parentCommentId)
    {
        LocalPR pr = require(prId);
        requireText(origin, "origin");
        requireText(author, "author");
        requireText(body, "body");
        if (LocalPRComment.SCOPE_FILE_LINE.equals(scope)) {
            if (filePath == null || filePath.isBlank() || lineNumber == null) {
                throw new IllegalArgumentException("file-line comment requires filePath + lineNumber");
            }
        }
        else if (LocalPRComment.SCOPE_PR.equals(scope)) {
            filePath = null;
            lineNumber = null;
        }
        else {
            throw new IllegalArgumentException("scope must be 'pr' or 'file-line'");
        }
        Instant when = now();
        LocalPRComment comment = store.saveComment(new LocalPRComment(
                UUID.randomUUID().toString(), pr.id(), origin, scope, filePath, lineNumber,
                author, body, when, /* resolvedAt */ null, /* dismissedAt */ null,
                /* strippedOnPushAt */ null, parentCommentId));
        // PR-level comments show on the timeline; inline comments live on the
        // diff, so only a pr-scoped comment writes a timeline event.
        if (LocalPRComment.SCOPE_PR.equals(scope)) {
            appendEvent(pr.id(), LocalPRTimelineEvent.TYPE_COMMENT, author,
                    LocalPRComment.ORIGIN_LOCAL.equals(origin), when, payload("commentId", comment.id()));
        }
        return comment;
    }

    @Override
    public LocalPRComment resolveComment(String commentId)
    {
        LocalPRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        return store.saveComment(comment.withResolved(now()));
    }

    @Override
    public LocalPRComment dismissComment(String commentId)
    {
        LocalPRComment comment = store.findCommentById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown comment: " + commentId));
        return store.saveComment(comment.withDismissed(now()));
    }

    @Override
    public LocalPR markLocalAddressed(String prId, Instant through)
    {
        LocalPR pr = require(prId);
        return store.save(pr.withLocalAddressedThrough(through));
    }

    private LocalPR require(String prId)
    {
        return store.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown local PR: " + prId));
    }

    private void appendEvent(
            String prId, String type, String actor, boolean localOnly, Instant when, String payloadJson)
    {
        store.addEvent(new LocalPRTimelineEvent(
                UUID.randomUUID().toString(), prId, type, actor == null ? "" : actor,
                localOnly, /* strippedOnPushAt */ null, when, payloadJson));
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
