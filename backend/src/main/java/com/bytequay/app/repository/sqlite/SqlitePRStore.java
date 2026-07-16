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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.DiffSide;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PR.PRSyncSnapshot;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PRTriageState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.repository.PRStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqlitePRStore
        implements PRStore
{
    private final PrJpaRepository prs;
    private final PrCommitJpaRepository commits;
    private final PrTimelineEventJpaRepository events;
    private final PrCheckJpaRepository checks;
    private final PrCommentJpaRepository comments;
    private final PrTriageJpaRepository triage;
    private final JdbcTemplate jdbc;

    SqlitePRStore(
            PrJpaRepository prs,
            PrCommitJpaRepository commits,
            PrTimelineEventJpaRepository events,
            PrCheckJpaRepository checks,
            PrCommentJpaRepository comments,
            PrTriageJpaRepository triage,
            JdbcTemplate jdbc)
    {
        this.prs = prs;
        this.commits = commits;
        this.events = events;
        this.checks = checks;
        this.comments = comments;
        this.triage = triage;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PR save(PR pr)
    {
        PrEntity e = new PrEntity();
        e.setId(pr.id());
        e.setTaskId(pr.taskId());
        e.setBranchName(pr.branchName());
        e.setBaseBranch(pr.baseBranch());
        e.setTitle(pr.title());
        e.setDescription(pr.description());
        e.setStatus(pr.status());
        e.setCreatedAtMs(pr.createdAt().toEpochMilli());
        e.setPushedAtMs(epochOrNull(pr.pushedAt()));
        e.setRemotePrNumber(pr.remotePrNumber());
        e.setRemotePrUrl(pr.remotePrUrl());
        e.setMergedAtMs(epochOrNull(pr.mergedAt()));
        e.setClosedAtMs(epochOrNull(pr.closedAt()));
        e.setLocalAddressedThroughMs(epochOrNull(pr.localAddressedThroughAt()));
        e.setOrigin(pr.origin());
        e.setRepo(pr.repo());
        e.setAuthor(pr.author());
        e.setSyncedAtMs(epochOrNull(pr.syncedAt()));
        e.setBranchDeletedAtMs(epochOrNull(pr.branchDeletedAt()));
        applySyncSnapshot(e, pr.githubSync());
        return toDomain(prs.save(e));
    }

    private static void applySyncSnapshot(PrEntity e, PRSyncSnapshot snap)
    {
        if (snap == null) {
            return;
        }
        e.setWatchReason(snap.watchReason() == null ? null : snap.watchReason().name());
        e.setGhUpdatedAtMs(epochOrNull(snap.ghUpdatedAt()));
        e.setLabels(snap.labels());
        e.setLabelColors(snap.labelColors());
        e.setDraft(snap.draft());
        e.setCiStatus(snap.ciStatus() == null ? null : snap.ciStatus().name());
        e.setAdditions(snap.additions());
        e.setDeletions(snap.deletions());
        e.setCommentCount(snap.commentCount());
        e.setAttentionReason(snap.attentionReason() == null ? null : snap.attentionReason().name());
        e.setMergeable(snap.mergeable());
        e.setMergeableState(snap.mergeableState());
        e.setHeadPushedAtMs(epochOrNull(snap.headPushedAt()));
        e.setReviewerVerdicts(snap.reviewerVerdicts());
        e.setRequestedReviewers(snap.requestedReviewers());
        e.setMergeQueueEnabled(snap.mergeQueueEnabled());
        e.setMergeQueueState(snap.mergeQueueState());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PR> findById(String id)
    {
        return prs.findById(id).map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PR> findByTaskId(String taskId)
    {
        return prs.findByTaskId(taskId).map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PR> findByRepoAndRemotePrNumber(String repo, int remotePrNumber)
    {
        return prs.findByRepoAndRemotePrNumber(repo, remotePrNumber).map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PR> findTaskByRepoAndRemotePrNumber(String repo, int remotePrNumber)
    {
        return prs.findFirstByRepoAndRemotePrNumberAndOrigin(repo, remotePrNumber, PR.ORIGIN_TASK)
                .map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRunningAgentReview(String prId)
    {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM review_round r
                JOIN review_session s ON s.id = r.session_id
                WHERE s.pr_id = ?
                  AND (r.status IN ('QUEUED', 'RUNNING') OR r.lifecycle_finalized = 0)
                """, Long.class, prId);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void reparentChildren(String fromPrId, String toPrId)
    {
        // Bulk moves via JDBC — one dedup rule per child table. Rows that would
        // duplicate one the survivor already holds are left on the source row
        // and cascade away when the caller deletes it.
        // Commits: the same commit synced under both rows shares a sha.
        jdbc.update("UPDATE pr_commit SET pr_id = ? WHERE pr_id = ? "
                + "AND sha NOT IN (SELECT sha FROM pr_commit WHERE pr_id = ?)",
                toPrId, fromPrId, toPrId);
        // Checks: a synced GitHub check carries its run id; local checks (null
        // run id) have no twin and always move.
        jdbc.update("UPDATE pr_check SET pr_id = ? WHERE pr_id = ? "
                + "AND (run_id IS NULL OR run_id NOT IN "
                + "(SELECT run_id FROM pr_check WHERE pr_id = ? AND run_id IS NOT NULL))",
                toPrId, fromPrId, toPrId);
        reparentAgentReviews(fromPrId, toPrId);
        // Comments: ids are unique UUIDs, so no collision — move them all.
        jdbc.update("UPDATE pr_comment SET pr_id = ? WHERE pr_id = ?", toPrId, fromPrId);
        // Timeline: move genuine remote events the survivor lacks, plus local
        // AgentReview events whose history was just reparented. Other local-only
        // history belongs to the task survivor and remains authoritative.
        jdbc.update("UPDATE pr_timeline_event SET pr_id = ? WHERE pr_id = ? "
                + "AND ((remote_event_id IS NOT NULL AND remote_event_id NOT IN "
                + "(SELECT remote_event_id FROM pr_timeline_event WHERE pr_id = ? AND remote_event_id IS NOT NULL)) "
                + "OR (json_valid(payload_json) AND json_extract(payload_json, '$.reviewEvent') IS NOT NULL))",
                toPrId, fromPrId, toPrId);
        // Triage (pr_id is the PK): the source twin is the dashboard row, so
        // its triage holds the snooze / handled state the user set there — the
        // intentional one. Let it win: drop the survivor's (if any) to free the
        // PK, then move the twin's over. When the twin has no triage both
        // statements no-op and the survivor keeps its own.
        jdbc.update("DELETE FROM pr_triage WHERE pr_id = ? "
                + "AND EXISTS (SELECT 1 FROM pr_triage WHERE pr_id = ?)",
                toPrId, fromPrId);
        jdbc.update("UPDATE pr_triage SET pr_id = ? WHERE pr_id = ?", toPrId, fromPrId);
    }

    private void reparentAgentReviews(String fromPrId, String toPrId)
    {
        List<String> reviewThreadIds = jdbc.queryForList("""
                SELECT DISTINCT owner_thread_id
                FROM review_session
                WHERE pr_id = ? AND owner_task_id IS NULL AND owner_thread_id IS NOT NULL
                """, String.class, fromPrId);
        if (reviewThreadIds.isEmpty() && jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_session WHERE pr_id = ?", Long.class, fromPrId) == 0) {
            return;
        }

        Optional<TaskReviewOwner> owner = jdbc.query("""
                SELECT p.task_id, t.thread_id, th.workspace_id
                FROM pr p
                JOIN tasks t ON t.id = p.task_id
                JOIN threads th ON th.id = t.thread_id
                WHERE p.id = ?
                """, (rs, row) -> new TaskReviewOwner(
                rs.getString("task_id"), rs.getString("thread_id"), rs.getString("workspace_id")),
                toPrId).stream().findFirst();

        owner.ifPresent(taskOwner -> {
            // Runs are accounted to the surviving development task once the
            // formerly standalone review becomes part of that task's history.
            jdbc.update("""
                    UPDATE agent_run SET task_id = ?
                    WHERE id IN (
                        SELECT r.agent_run_id
                        FROM review_round r
                        JOIN review_session s ON s.id = r.session_id
                        WHERE s.pr_id = ?)
                    """, taskOwner.taskId(), fromPrId);
            jdbc.update("""
                    UPDATE agent_run SET task_id = ?
                    WHERE id IN (
                        SELECT v.verifier_run_id
                        FROM finding_verification v
                        JOIN finding f ON f.id = v.finding_id
                        JOIN review_session s ON s.id = f.session_id
                        WHERE s.pr_id = ?)
                    """, taskOwner.taskId(), fromPrId);
        });

        Optional<String> targetReviewId = jdbc.queryForList("""
                SELECT id FROM review_session
                WHERE pr_id = ? AND status IN ('ACTIVE', 'STALE')
                ORDER BY created_at_ms DESC LIMIT 1
                """, String.class, toPrId).stream().findFirst();
        if (targetReviewId.isPresent()) {
            // The partial unique index permits only one active review per PR.
            // Merge the twin's rounds/findings into the task-owned review before
            // removing its now-empty session rows.
            jdbc.update("""
                    UPDATE review_round SET session_id = ?
                    WHERE session_id IN (SELECT id FROM review_session WHERE pr_id = ?)
                    """, targetReviewId.get(), fromPrId);
            jdbc.update("""
                    UPDATE finding SET session_id = ?
                    WHERE session_id IN (SELECT id FROM review_session WHERE pr_id = ?)
                    """, targetReviewId.get(), fromPrId);
            jdbc.update("DELETE FROM review_session WHERE pr_id = ?", fromPrId);
        }
        else if (owner.isPresent()) {
            TaskReviewOwner taskOwner = owner.get();
            jdbc.update("""
                    UPDATE review_session
                    SET pr_id = ?, workspace_id = ?, owner_thread_id = ?, owner_task_id = ?
                    WHERE pr_id = ?
                    """, toPrId, taskOwner.workspaceId(), taskOwner.threadId(), taskOwner.taskId(), fromPrId);
        }
        else {
            jdbc.update("UPDATE review_session SET pr_id = ? WHERE pr_id = ?", toPrId, fromPrId);
        }

        for (String threadId : reviewThreadIds) {
            jdbc.update("""
                    DELETE FROM threads
                    WHERE id = ? AND flow = 'review'
                      AND NOT EXISTS (SELECT 1 FROM review_session WHERE owner_thread_id = ?)
                      AND NOT EXISTS (SELECT 1 FROM tasks WHERE thread_id = ?)
                    """, threadId, threadId, threadId);
        }
    }

    private record TaskReviewOwner(String taskId, String threadId, String workspaceId) {}

    @Override
    @Transactional
    public void deletePr(String prId)
    {
        jdbc.update("DELETE FROM pr WHERE id = ?", prId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PR> findPushedTaskPrsMissingRepo()
    {
        return prs.findPushedTaskPrsMissingRepo().stream().map(SqlitePRStore::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findTaskPrIdsWithExternalTwin()
    {
        return prs.findTaskPrIdsWithExternalTwin();
    }

    @Override
    @Transactional
    public void setRepo(String prId, String repo)
    {
        jdbc.update("UPDATE pr SET repo = ? WHERE id = ?", repo, prId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRDashboardEntry> findDashboardEntries()
    {
        return prs.findByWatchReasonIsNotNull().stream()
                .map(e -> new PRDashboardEntry(
                        toDomain(e),
                        triage.findById(e.getId()).map(SqlitePRStore::toDomain)
                                .orElseGet(() -> PRTriageState.empty(e.getId()))))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PRTriageState> findTriage(String prId)
    {
        return triage.findById(prId).map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional
    public PRTriageState saveTriage(PRTriageState state)
    {
        PrTriageEntity e = new PrTriageEntity();
        e.setPrId(state.prId());
        e.setViewedAtMs(epochOrNull(state.viewedAt()));
        e.setReviewedAtMs(epochOrNull(state.reviewedAt()));
        e.setHandledAction(state.handledAction() == null ? null : state.handledAction().name());
        e.setSnoozedUntilMs(epochOrNull(state.snoozedUntil()));
        e.setSnoozedAtMs(epochOrNull(state.snoozedAt()));
        e.setSnoozeWakeReason(state.snoozeWakeReason());
        return toDomain(triage.save(e));
    }

    @Override
    @Transactional
    public PRCommit addCommit(PRCommit commit)
    {
        PrCommitEntity e = new PrCommitEntity();
        e.setId(commit.id());
        e.setPrId(commit.prId());
        e.setSha(commit.sha());
        e.setMessage(commit.message());
        e.setAdditions(commit.additions());
        e.setDeletions(commit.deletions());
        e.setAuthoredAtMs(commit.authoredAt().toEpochMilli());
        e.setPushedAtMs(epochOrNull(commit.pushedAt()));
        return toDomain(commits.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRCommit> commitsFor(String prId)
    {
        return commits.findByPrIdOrderByAuthoredAtMsAsc(prId).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public PRTimelineEntry addEvent(PRTimelineEntry event)
    {
        return toDomain(events.save(toEntity(event)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRTimelineEntry> timelineFor(String prId)
    {
        return events.findByPrIdOrderByCreatedAtMsAsc(prId).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRTimelineEntry> unstrippedLocalOnlyEvents(String prId)
    {
        return events.findByPrIdAndLocalOnlyTrueAndStrippedOnPushAtMsIsNull(prId).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean timelineEventExistsByRemoteId(String prId, long remoteEventId)
    {
        return events.existsByPrIdAndRemoteEventId(prId, remoteEventId);
    }

    @Override
    @Transactional
    public PRCheck addCheck(PRCheck check)
    {
        PrCheckEntity e = new PrCheckEntity();
        e.setId(check.id());
        e.setPrId(check.prId());
        e.setKind(check.kind());
        e.setName(check.name());
        e.setStatus(check.status());
        e.setDurationMs(check.durationMs());
        e.setStartedAtMs(check.startedAt().toEpochMilli());
        e.setFinishedAtMs(epochOrNull(check.finishedAt()));
        e.setRunId(check.runId());
        return toDomain(checks.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRCheck> checksFor(String prId)
    {
        return checks.findByPrIdOrderByStartedAtMsAsc(prId).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public PRComment saveComment(PRComment comment)
    {
        PrCommentEntity e = new PrCommentEntity();
        e.setId(comment.id());
        e.setPrId(comment.prId());
        e.setOrigin(comment.origin());
        e.setScope(comment.scope());
        e.setFilePath(comment.filePath());
        e.setLineNumber(comment.lineNumber());
        e.setAuthor(comment.author());
        e.setBody(comment.body());
        e.setCreatedAtMs(comment.createdAt().toEpochMilli());
        e.setResolvedAtMs(epochOrNull(comment.resolvedAt()));
        e.setDismissedAtMs(epochOrNull(comment.dismissedAt()));
        e.setStrippedOnPushAtMs(epochOrNull(comment.strippedOnPushAt()));
        e.setParentCommentId(comment.parentCommentId());
        e.setPublishedAtMs(epochOrNull(comment.publishedAt()));
        e.setFindingId(comment.findingId());
        e.setSide(DiffSide.normalize(comment.side()));
        e.setStartLine(comment.startLine());
        e.setStartSide(comment.startSide());
        return toDomain(comments.save(e));
    }

    @Override
    @Transactional
    public void deleteComment(String id)
    {
        comments.deleteByParentCommentId(id);
        comments.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PRComment> findCommentById(String id)
    {
        return comments.findById(id).map(SqlitePRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRComment> commentsFor(String prId)
    {
        return comments.findByPrIdOrderByCreatedAtMsAsc(prId).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PRComment> unstrippedLocalComments(String prId)
    {
        return comments.findByPrIdAndOriginAndStrippedOnPushAtMsIsNull(
                        prId, PRComment.ORIGIN_LOCAL).stream()
                .map(SqlitePRStore::toDomain)
                .toList();
    }

    private static PrTimelineEventEntity toEntity(PRTimelineEntry event)
    {
        PrTimelineEventEntity e = new PrTimelineEventEntity();
        e.setId(event.id());
        e.setPrId(event.prId());
        e.setEventType(event.eventType());
        e.setActor(event.actor());
        e.setLocalOnly(event.localOnly());
        e.setStrippedOnPushAtMs(epochOrNull(event.strippedOnPushAt()));
        e.setCreatedAtMs(event.createdAt().toEpochMilli());
        e.setPayloadJson(event.payloadJson());
        e.setRemoteEventId(event.remoteEventId());
        return e;
    }

    private static PR toDomain(PrEntity e)
    {
        return new PR(
                e.getId(),
                e.getTaskId(),
                e.getBranchName(),
                e.getBaseBranch(),
                e.getTitle(),
                e.getDescription(),
                e.getStatus(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                instantOrNull(e.getPushedAtMs()),
                e.getRemotePrNumber(),
                e.getRemotePrUrl(),
                instantOrNull(e.getMergedAtMs()),
                instantOrNull(e.getClosedAtMs()),
                instantOrNull(e.getLocalAddressedThroughMs()),
                e.getOrigin(),
                e.getRepo(),
                e.getAuthor(),
                instantOrNull(e.getSyncedAtMs()),
                toSyncSnapshot(e),
                instantOrNull(e.getBranchDeletedAtMs()));
    }

    /** Absent unless the row has been touched by {@code syncList} at least
     *  once ({@code gh_updated_at_ms} is the reliable signal — {@code
     *  watch_reason} alone can't be, since it's cleared to null once a PR
     *  falls out of the dashboard while its sync fields stay put). */
    private static PRSyncSnapshot toSyncSnapshot(PrEntity e)
    {
        if (e.getGhUpdatedAtMs() == null) {
            return null;
        }
        return new PRSyncSnapshot(
                e.getWatchReason() == null ? null : PullRequest.Origin.valueOf(e.getWatchReason()),
                instantOrNull(e.getGhUpdatedAtMs()),
                e.getLabels(),
                e.getLabelColors(),
                e.isDraft(),
                e.getCiStatus() == null ? null : PullRequestDetail.CiStatus.valueOf(e.getCiStatus()),
                e.getAdditions(),
                e.getDeletions(),
                e.getCommentCount(),
                e.getAttentionReason() == null ? null : AttentionReason.valueOf(e.getAttentionReason()),
                e.getMergeable(),
                e.getMergeableState(),
                instantOrNull(e.getHeadPushedAtMs()),
                e.getReviewerVerdicts(),
                e.getRequestedReviewers(),
                e.isMergeQueueEnabled(),
                e.getMergeQueueState());
    }

    private static PRTriageState toDomain(PrTriageEntity e)
    {
        return new PRTriageState(
                e.getPrId(),
                instantOrNull(e.getViewedAtMs()),
                instantOrNull(e.getReviewedAtMs()),
                e.getHandledAction() == null ? null : HandledAction.valueOf(e.getHandledAction()),
                instantOrNull(e.getSnoozedUntilMs()),
                instantOrNull(e.getSnoozedAtMs()),
                e.getSnoozeWakeReason());
    }

    private static PRCommit toDomain(PrCommitEntity e)
    {
        return new PRCommit(
                e.getId(),
                e.getPrId(),
                e.getSha(),
                e.getMessage(),
                e.getAdditions(),
                e.getDeletions(),
                Instant.ofEpochMilli(e.getAuthoredAtMs()),
                instantOrNull(e.getPushedAtMs()));
    }

    private static PRTimelineEntry toDomain(PrTimelineEventEntity e)
    {
        return new PRTimelineEntry(
                e.getId(),
                e.getPrId(),
                e.getEventType(),
                e.getActor(),
                e.isLocalOnly(),
                instantOrNull(e.getStrippedOnPushAtMs()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getPayloadJson(),
                e.getRemoteEventId());
    }

    private static PRCheck toDomain(PrCheckEntity e)
    {
        return new PRCheck(
                e.getId(),
                e.getPrId(),
                e.getKind(),
                e.getName(),
                e.getStatus(),
                e.getDurationMs(),
                Instant.ofEpochMilli(e.getStartedAtMs()),
                instantOrNull(e.getFinishedAtMs()),
                e.getRunId());
    }

    private static PRComment toDomain(PrCommentEntity e)
    {
        return new PRComment(
                e.getId(),
                e.getPrId(),
                e.getOrigin(),
                e.getScope(),
                e.getFilePath(),
                e.getLineNumber(),
                e.getAuthor(),
                e.getBody(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                instantOrNull(e.getResolvedAtMs()),
                instantOrNull(e.getDismissedAtMs()),
                instantOrNull(e.getStrippedOnPushAtMs()),
                e.getParentCommentId(),
                instantOrNull(e.getPublishedAtMs()),
                e.getSide(),
                e.getStartLine(),
                e.getStartSide(),
                e.getFindingId());
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}
