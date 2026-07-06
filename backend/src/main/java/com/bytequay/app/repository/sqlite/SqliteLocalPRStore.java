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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRCommit;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.repository.LocalPRStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqliteLocalPRStore
        implements LocalPRStore
{
    private final LocalPrJpaRepository prs;
    private final LocalPrCommitJpaRepository commits;
    private final LocalPrTimelineEventJpaRepository events;
    private final LocalPrCheckJpaRepository checks;
    private final LocalPrCommentJpaRepository comments;

    SqliteLocalPRStore(
            LocalPrJpaRepository prs,
            LocalPrCommitJpaRepository commits,
            LocalPrTimelineEventJpaRepository events,
            LocalPrCheckJpaRepository checks,
            LocalPrCommentJpaRepository comments)
    {
        this.prs = prs;
        this.commits = commits;
        this.events = events;
        this.checks = checks;
        this.comments = comments;
    }

    @Override
    @Transactional
    public LocalPR save(LocalPR pr)
    {
        LocalPrEntity e = new LocalPrEntity();
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
        return toDomain(prs.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalPR> findById(String id)
    {
        return prs.findById(id).map(SqliteLocalPRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalPR> findByTaskId(String taskId)
    {
        return prs.findByTaskId(taskId).map(SqliteLocalPRStore::toDomain);
    }

    @Override
    @Transactional
    public LocalPRCommit addCommit(LocalPRCommit commit)
    {
        LocalPrCommitEntity e = new LocalPrCommitEntity();
        e.setId(commit.id());
        e.setLocalPrId(commit.localPrId());
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
    public List<LocalPRCommit> commitsFor(String localPrId)
    {
        return commits.findByLocalPrIdOrderByAuthoredAtMsAsc(localPrId).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public LocalPRTimelineEvent addEvent(LocalPRTimelineEvent event)
    {
        return toDomain(events.save(toEntity(event)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalPRTimelineEvent> timelineFor(String localPrId)
    {
        return events.findByLocalPrIdOrderByCreatedAtMsAsc(localPrId).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalPRTimelineEvent> unstrippedLocalOnlyEvents(String localPrId)
    {
        return events.findByLocalPrIdAndLocalOnlyTrueAndStrippedOnPushAtMsIsNull(localPrId).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean timelineEventExistsByRemoteId(String localPrId, long remoteEventId)
    {
        return events.existsByLocalPrIdAndRemoteEventId(localPrId, remoteEventId);
    }

    @Override
    @Transactional
    public LocalPRCheck addCheck(LocalPRCheck check)
    {
        LocalPrCheckEntity e = new LocalPrCheckEntity();
        e.setId(check.id());
        e.setLocalPrId(check.localPrId());
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
    public List<LocalPRCheck> checksFor(String localPrId)
    {
        return checks.findByLocalPrIdOrderByStartedAtMsAsc(localPrId).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public LocalPRComment saveComment(LocalPRComment comment)
    {
        LocalPrCommentEntity e = new LocalPrCommentEntity();
        e.setId(comment.id());
        e.setLocalPrId(comment.localPrId());
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
        return toDomain(comments.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalPRComment> findCommentById(String id)
    {
        return comments.findById(id).map(SqliteLocalPRStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalPRComment> commentsFor(String localPrId)
    {
        return comments.findByLocalPrIdOrderByCreatedAtMsAsc(localPrId).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalPRComment> unstrippedLocalComments(String localPrId)
    {
        return comments.findByLocalPrIdAndOriginAndStrippedOnPushAtMsIsNull(
                        localPrId, LocalPRComment.ORIGIN_LOCAL).stream()
                .map(SqliteLocalPRStore::toDomain)
                .toList();
    }

    private static LocalPrTimelineEventEntity toEntity(LocalPRTimelineEvent event)
    {
        LocalPrTimelineEventEntity e = new LocalPrTimelineEventEntity();
        e.setId(event.id());
        e.setLocalPrId(event.localPrId());
        e.setEventType(event.eventType());
        e.setActor(event.actor());
        e.setLocalOnly(event.localOnly());
        e.setStrippedOnPushAtMs(epochOrNull(event.strippedOnPushAt()));
        e.setCreatedAtMs(event.createdAt().toEpochMilli());
        e.setPayloadJson(event.payloadJson());
        e.setRemoteEventId(event.remoteEventId());
        return e;
    }

    private static LocalPR toDomain(LocalPrEntity e)
    {
        return new LocalPR(
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
                instantOrNull(e.getLocalAddressedThroughMs()));
    }

    private static LocalPRCommit toDomain(LocalPrCommitEntity e)
    {
        return new LocalPRCommit(
                e.getId(),
                e.getLocalPrId(),
                e.getSha(),
                e.getMessage(),
                e.getAdditions(),
                e.getDeletions(),
                Instant.ofEpochMilli(e.getAuthoredAtMs()),
                instantOrNull(e.getPushedAtMs()));
    }

    private static LocalPRTimelineEvent toDomain(LocalPrTimelineEventEntity e)
    {
        return new LocalPRTimelineEvent(
                e.getId(),
                e.getLocalPrId(),
                e.getEventType(),
                e.getActor(),
                e.isLocalOnly(),
                instantOrNull(e.getStrippedOnPushAtMs()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getPayloadJson(),
                e.getRemoteEventId());
    }

    private static LocalPRCheck toDomain(LocalPrCheckEntity e)
    {
        return new LocalPRCheck(
                e.getId(),
                e.getLocalPrId(),
                e.getKind(),
                e.getName(),
                e.getStatus(),
                e.getDurationMs(),
                Instant.ofEpochMilli(e.getStartedAtMs()),
                instantOrNull(e.getFinishedAtMs()),
                e.getRunId());
    }

    private static LocalPRComment toDomain(LocalPrCommentEntity e)
    {
        return new LocalPRComment(
                e.getId(),
                e.getLocalPrId(),
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
                e.getParentCommentId());
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
