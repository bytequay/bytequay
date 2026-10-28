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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingPullRequestWrites;
import com.bytequay.app.service.pr.PullRequestService.Posted;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.SubmittedReview;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Explicit hosting writes and the local bookkeeping that must not turn an accepted write into a failure. */
final class PullRequestActions
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestActions.class);
    private static final Set<String> REACTION_CONTENTS = ImmutableSet.of("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes");

    private final HostingPullRequestWrites writes;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PrDetailStore details;
    private final PrViewStateStore viewStateStore;
    private final RecentTouchRecorder recent;
    private final HostingPullRequestService requests;
    private final Function<PullRequestRead, PullRequestDetail> readDetail;
    private final Function<PullRequestRead, Optional<Long>> rowId;

    PullRequestActions(
            HostingPullRequestWrites writes,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PrDetailStore details,
            PrViewStateStore viewStateStore,
            RecentTouchRecorder recent,
            HostingPullRequestService requests,
            Function<PullRequestRead, PullRequestDetail> readDetail, Function<PullRequestRead, Optional<Long>> rowId)
    {
        this.writes = requireNonNull(writes, "writes is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.details = requireNonNull(details, "details is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.readDetail = requireNonNull(readDetail, "readDetail is null");
        this.rowId = requireNonNull(rowId, "rowId is null");
    }

    boolean hasMergeQueue(PullRequestRead route, String baseRef)
    {
        checkText(baseRef, "baseRef");
        return writes.forSession(route.session()).hasMergeQueue(route.session().access(), route.repository(), baseRef);
    }

    Posted createReviewComment(PullRequestRead route, InlineReviewComment comment)
    {
        validateReviewComment(comment, true);
        Optional<Long> prId = rowId.apply(route);
        PullRequestDetail before = readDetail.apply(route);
        if (comment.replyToCommentId().isPresent()) {
            replyToReviewComment(route, comment.replyToCommentId().getAsLong(), comment.body());
            return new Posted(afterWrite(route, () -> readDetail.apply(route), before), OptionalLong.empty(), ImmutableList.of());
        }
        PrTimelineEvent created = writes.forSession(route.session()).createReviewComment(route.session().access(), route.repository(), route.number(), comment);
        PullRequestDetail detail = afterWrite(route, () -> {
            PullRequestDetail patched = patchCachedTimeline(route, prId, created);
            prId.ifPresent(id -> markReviewed(id, "COMMENT", comment.commitId()));
            recordComment(route, "review-comment", created, comment.body());
            return patched;
        }, before);
        return new Posted(detail, OptionalLong.empty(), ImmutableList.of(created));
    }

    PullRequestDetail commentOnPullRequest(PullRequestRead route, String body)
    {
        checkText(body, "comment body");
        PullRequestDetail before = readDetail.apply(route);
        PrTimelineEvent created = writes.forSession(route.session()).comment(route.session().access(), route.repository(), route.number(), body);
        return afterWrite(route, () -> {
            PullRequestDetail detail = patchCachedTimeline(route, rowId.apply(route), created);
            recordComment(route, "comment", created, body);
            return detail;
        }, before);
    }

    /**
     * Runs a step after a remote write landed. Its failure must not fail the request: the caller would
     * take the write for failed and send it again. The next refresh repairs the local copy.
     *
     * <p>Writers read the detail they fall back to before writing, so a failed read sends nothing.
     */
    private <T> T afterWrite(PullRequestRead route, Supplier<T> step, T fallback)
    {
        try {
            requests.invalidateRepository(route.repository().id(), route.number());
            return step.get();
        }
        catch (RuntimeException failure) {
            log.warn("Remote write to {}#{} succeeded but its local update failed: {}", route.repository().fullPath(), route.number(), failure.toString());
            return fallback;
        }
    }

    private void afterWrite(PullRequestRead route, Runnable step)
    {
        afterWrite(route, () -> {
            step.run();
            return true;
        }, false);
    }

    boolean toggleReaction(PullRequestRead route, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        checkText(target, "reaction target");
        checkText(content, "reaction content");
        String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
        String normalizedContent = content.trim().toLowerCase(Locale.ROOT);
        if (!REACTION_CONTENTS.contains(normalizedContent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction content");
        }
        switch (normalizedTarget) {
            case "pull_request" -> {
                if (commentId != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pull request reaction must not include a comment id");
                }
            }
            case "issue_comment", "review_comment" -> {
                if (commentId == null || commentId <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment reaction requires a positive comment id");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction target");
        }

        try {
            return writes.forSession(route.session()).toggleReaction(route.session().access(), route.repository(), route.number(),
                    normalizedTarget, commentId, normalizedContent, viewerHasReacted);
        }
        finally {
            afterWrite(route, () -> invalidatePullRequestDetail(route));
        }
    }

    void updateTitle(PullRequestRead route, String title)
    {
        checkText(title, "pull request title");
        String normalizedTitle = title.trim();
        writes.forSession(route.session()).updateTitle(route.session().access(), route.repository(), route.number(), normalizedTitle);
        afterWrite(route, () -> {
            rowId.apply(route).ifPresent(prId -> {
                snapshots.patchDetail(prId, cached -> new StoredPrDetail(cached.raw().withTitle(normalizedTitle), cached.reviews(), cached.timeline(),
                        cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite()));
                store.updateTitle(prId, normalizedTitle);
            });
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            invalidatePullRequestDetail(route);
        });
    }

    void updateDescription(PullRequestRead route, String description)
    {
        writes.forSession(route.session()).updateDescription(route.session().access(), route.repository(), route.number(), requireNonNull(description, "description is null"));
        afterWrite(route, () -> {
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            invalidatePullRequestDetail(route);
        });
    }

    void closePullRequest(PullRequestRead route)
    {
        writes.forSession(route.session()).close(route.session().access(), route.repository(), route.number());
        afterWrite(route, () -> {
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            invalidatePullRequestCaches(route);
        });
    }

    void markReadyForReview(PullRequestRead route)
    {
        try {
            writes.forSession(route.session()).markReady(route.session().access(), route.repository(), route.number());
        }
        finally {
            // A lost response may still have promoted the draft; refresh without repeating the write.
            afterWrite(route, () -> invalidatePullRequestDetail(route));
        }
        afterWrite(route, () -> recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0));
    }

    void updateMetadata(PullRequestRead route, List<String> assignees, List<String> labels)
    {
        writes.forSession(route.session()).updateMetadata(
                route.session().access(), route.repository(), route.number(),
                normalizeNames(assignees, "assignees"),
                normalizeNames(labels, "labels"));
        afterWrite(route, () -> {
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            invalidatePullRequestDetail(route);
        });
    }

    void updateReviewerRequest(PullRequestRead route, String login, boolean requested)
    {
        checkText(login, "reviewer login");
        writes.forSession(route.session()).updateReviewer(route.session().access(), route.repository(), route.number(), login.trim(), requested);
        afterWrite(route, () -> {
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            invalidatePullRequestDetail(route);
        });
    }

    void replyToReviewComment(PullRequestRead route, long commentId, String body)
    {
        checkText(body, "reply body");
        writes.forSession(route.session()).replyToReviewComment(route.session().access(), route.repository(), route.number(), commentId, body);
        afterWrite(route, () -> {
            invalidatePullRequestCaches(route);
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.COMMENTED, 0);
            recent.wroteOnPullRequest(rowId.apply(route).orElseThrow(), RecentTextKind.COMMENT, "reply:" + commentId, body);
        });
    }

    /** Edits a posted comment or review body remotely, then patches the cached copies of that body. */
    PullRequestDetail editComment(PullRequestRead route, String target, long commentId, String body)
    {
        checkText(target, "comment target");
        String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
        // The keys the create paths index, so an edit replaces the written text instead of adding a copy.
        String textKey = switch (normalizedTarget) {
            case "issue_comment" -> "comment:" + commentId;
            case "review_comment" -> "review-comment:" + commentId;
            case "review" -> "review:" + commentId;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported comment target");
        };
        if (commentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment edit requires a positive comment id");
        }
        checkText(body, "comment body");

        PullRequestDetail before = readDetail.apply(route);
        writes.forSession(route.session()).editComment(route.session().access(), route.repository(), route.number(), normalizedTarget, commentId, body);
        Optional<PullRequestDetail> patched = afterWrite(route, () -> {
            Optional<Long> prId = rowId.apply(route);
            return prId.flatMap(id -> snapshots.patchDetail(id, cached -> withEditedBody(cached, normalizedTarget, commentId, body)))
                    .map(stored -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), route.repository().fullPath(), route.number(), stored, stored.viewerCanWrite()));
        }, Optional.empty());
        afterWrite(route, () -> {
            recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0);
            recent.wroteOnPullRequest(rowId.apply(route).orElseThrow(), "review".equals(normalizedTarget) ? RecentTextKind.REVIEW_BODY : RecentTextKind.COMMENT, textKey, body);
        });
        return patched.orElse(before);
    }

    /** GitHub ids are unique only within one resource type, so each target patches only its own kind of copy. */
    private static StoredPrDetail withEditedBody(StoredPrDetail cached, String target, Long commentId, String body)
    {
        List<PrReviewState> reviews = cached.reviews().stream()
                .map(review -> "review".equals(target) && commentId.equals(review.githubId()) ? withBody(review, body) : review)
                .collect(toImmutableList());
        List<PrTimelineEvent> timeline = cached.timeline().stream()
                .map(event -> switch (target) {
                    case "issue_comment" -> "commented".equals(event.event()) && commentId.equals(event.githubId()) ? withBody(event, body) : event;
                    case "review" -> "reviewed".equals(event.event()) && commentId.equals(event.githubId()) ? withBody(event, body) : event;
                    case "review_comment" -> "review_thread".equals(event.event()) ? withThreadCommentBody(event, commentId, body) : event;
                    default -> throw new IllegalStateException("unsupported comment target: " + target);
                })
                .collect(toImmutableList());
        return new StoredPrDetail(cached.raw(), reviews, timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
    }

    private static PrReviewState withBody(PrReviewState review, String body)
    {
        return new PrReviewState(
                review.githubId(), review.login(), review.avatarUrl(), review.bot(), review.state(), review.submittedAt(), body, review.htmlUrl(), review.commitId());
    }

    private static PrTimelineEvent withBody(PrTimelineEvent event, String body)
    {
        return new PrTimelineEvent(event.githubId(), event.event(), event.actor(), event.timestamp(), body, event.payload(), event.localEventKey());
    }

    private static PrTimelineEvent withThreadCommentBody(PrTimelineEvent thread, Long commentId, String body)
    {
        List<PrTimelineComment> comments = thread.payload().comments().stream()
                .map(comment -> commentId.equals(comment.githubId())
                        ? new PrTimelineComment(
                                comment.githubId(), comment.actor(), comment.actorAvatarUrl(), comment.actorBot(), comment.timestamp(),
                                body, comment.htmlUrl(), comment.reviewId(), comment.inReplyToId(), comment.reactions())
                        : comment)
                .collect(toImmutableList());
        return new PrTimelineEvent(
                thread.githubId(),
                thread.event(),
                thread.actor(),
                thread.timestamp(),
                thread.body(),
                PrTimelinePayload.builder(thread.payload()).comments(comments).build(),
                thread.localEventKey());
    }

    void setReviewThreadResolved(PullRequestRead route, long rootCommentId, boolean resolved)
    {
        writes.forSession(route.session()).resolveThread(route.session().access(), route.repository(), route.number(), rootCommentId, resolved);
        afterWrite(route, () -> invalidatePullRequestDetail(route));
    }

    /**
     * Submits a review, with any inline comments, for the given pull request and
     * records a local reviewed state.
     */
    Posted submitReview(PullRequestRead route, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        checkText(commitId, "review commit");
        checkText(event, "review event");
        String normalizedEvent = event.toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("COMMENT", "APPROVE", "REQUEST_CHANGES").contains(normalizedEvent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review event");
        }
        List<InlineReviewComment> reviewComments = comments == null ? ImmutableList.of() : comments;
        reviewComments.forEach(comment -> validateReviewComment(comment, false));
        // GitHub anchors a whole review at one commit and resolves every comment's path and line
        // against it, so a mixed batch would land on the wrong lines — or, for a file that commit
        // does not have yet, be rejected outright. Callers split such a batch into one review each.
        if (reviewComments.stream().map(InlineReviewComment::commitId).filter(commit -> commit != null && !commit.isBlank()).distinct().count() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "one review carries comments written on one commit");
        }
        if (reviewComments.stream().filter(comment -> comment.replyToCommentId().isEmpty())
                .anyMatch(comment -> !commitId.equals(comment.commitId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comments must match the review commit");
        }
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent) && reviewBody.isBlank() && reviewComments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review body or inline comment must not be blank");
        }

        PullRequestDetail before = readDetail.apply(route);
        SubmittedReview submitted = writes.forSession(route.session()).submitReview(route.session().access(), route.repository(), route.number(),
                normalizedEvent, reviewBody, reviewComments, commitId);
        Optional<PrReviewState> created = submitted.review();
        List<PrTimelineEvent> events = submitted.events();
        PullRequestDetail detail = afterWrite(route, () -> {
            PullRequestDetail patched = patchCachedReview(route, prId, created, events);
            markReviewed(prId, normalizedEvent, commitId);
            recordReview(route, normalizedEvent, reviewBody, created.orElse(null));
            reviewComments.forEach(inline -> recent.wroteOnPullRequest(
                    rowId.apply(route).orElseThrow(),
                    RecentTextKind.COMMENT,
                    "review-comment:" + created.map(PrReviewState::githubId).map(Object::toString).orElse("latest") + ":" + inline.path() + ":" + inline.line(),
                    inline.body()));
            return patched;
        }, before);
        OptionalLong reviewId = created.map(PrReviewState::githubId).map(OptionalLong::of).orElse(OptionalLong.empty());
        return new Posted(detail, reviewId, events.stream().filter(item -> "review_thread".equals(item.event())).collect(toImmutableList()));
    }

    /**
     * Splices a submitted review, and any inline comments it carried, into the
     * cached detail. Approving cannot change the diff or the checks, so nothing
     * else is invalidated and the poll picks up whatever else moved.
     */
    private PullRequestDetail patchCachedReview(PullRequestRead route, long prId, Optional<PrReviewState> created, List<PrTimelineEvent> events)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = snapshots.patchDetail(prId, cached -> {
            List<PrReviewState> reviews = new ArrayList<>(cached.reviews());
            created.ifPresent(value -> {
                reviews.removeIf(review -> Objects.equals(review.githubId(), value.githubId())
                        && (value.githubId() != null || review.login().equalsIgnoreCase(value.login())));
                reviews.add(value);
            });
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            created.filter(review -> review.githubId() != null && review.submittedAt() != null).ifPresent(review -> {
                PrTimelineEvent reviewed = reviewedTimelineEvent(review);
                timeline.removeIf(event -> Objects.equals(event.githubId(), reviewed.githubId()) && reviewed.event().equals(event.event()));
                timeline.add(reviewed);
            });
            for (PrTimelineEvent thread : events) {
                timeline.removeIf(event -> Objects.equals(event.githubId(), thread.githubId())
                        && thread.event().equals(event.event()));
                timeline.add(thread);
            }
            return new StoredPrDetail(cached.raw(), reviews, timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        });
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId, route.repository().fullPath(), route.number(), detail, detail.viewerCanWrite()))
                .orElseGet(() -> readDetail.apply(route));
    }

    private static PrTimelineEvent reviewedTimelineEvent(PrReviewState review)
    {
        return new PrTimelineEvent(
                review.githubId(),
                "reviewed",
                review.login(),
                review.submittedAt(),
                review.body(),
                PrTimelinePayload.builder()
                        .actorAvatarUrl(review.avatarUrl())
                        .actorBot(review.bot())
                        .state(review.state())
                        .htmlUrl(review.htmlUrl())
                        .build(),
                Optional.empty());
    }

    private void markReviewed(long prId, String event, String commitId)
    {
        HandledAction action = switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        };
        viewStateStore.markReviewed(prId, action, commitId, Instant.now());
    }

    private void recordComment(PullRequestRead route, String kind, PrTimelineEvent created, String body)
    {
        recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.COMMENTED, 0);
        recent.wroteOnPullRequest(rowId.apply(route).orElseThrow(), RecentTextKind.COMMENT, textKey(kind, created), body);
    }

    private void recordReview(PullRequestRead route, String event, String body, PrReviewState created)
    {
        RecentReason reason = switch (event) {
            case "APPROVE" -> RecentReason.APPROVED;
            case "REQUEST_CHANGES" -> RecentReason.CHANGES_REQUESTED;
            default -> RecentReason.COMMENTED;
        };
        recent.pullRequestAction(rowId.apply(route).orElseThrow(), reason, 0);
        recent.wroteOnPullRequest(rowId.apply(route).orElseThrow(), RecentTextKind.REVIEW_BODY,
                "review:" + Objects.toString(created == null ? null : created.githubId(), "latest"), body);
    }

    /** Stable id for one written body, so re-posting cannot pile up duplicates. */
    private static String textKey(String kind, PrTimelineEvent created)
    {
        Long githubId = created == null ? null : created.githubId();
        return kind + ":" + (githubId != null ? githubId : Objects.toString(created == null ? null : created.timestamp(), "local"));
    }

    private static void validateReviewComment(InlineReviewComment comment, boolean requireCommit)
    {
        if (comment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment must not be null");
        }
        checkText(comment.body(), "review comment body");
        if (comment.replyToCommentId().isPresent()) {
            if (comment.replyToCommentId().getAsLong() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reply requires a positive comment id");
            }
            return;
        }
        checkText(comment.path(), "review comment path");
        checkText(comment.side(), "review comment side");
        String side = comment.side().toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("LEFT", "RIGHT").contains(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment side must be LEFT or RIGHT");
        }
        if (comment.line() < 1 || comment.startLine() != null && (comment.startLine() < 1 || comment.startLine() > comment.line())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid review comment line range");
        }
        if (comment.startLine() != null && !side.equalsIgnoreCase(comment.startSide())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment range must stay on one side");
        }
        if (requireCommit) {
            checkText(comment.commitId(), "review comment commit");
        }
    }

    MergeResult mergePullRequest(PullRequestRead route, long prId, String strategy, String headSha)
    {
        checkText(headSha, "pull request head commit");
        if (rowId.apply(route).filter(id -> id == prId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pull request identity does not match; reopen it before merging");
        }
        MergeResult result;
        try {
            result = writes.forSession(route.session()).merge(route.session().access(), route.repository(), route.number(), strategy, headSha);
        }
        finally {
            afterWrite(route, () -> invalidatePullRequestDetail(route));
        }
        afterWrite(route, () -> {
            if (result.merged()) {
                store.setMonitored(prId, false);
                viewStateStore.markReviewed(prId, HandledAction.MERGED, headSha, Instant.now());
                recent.pullRequestAction(prId, RecentReason.MERGED, 0);
            }
        });
        return result;
    }

    void removeFromMergeQueue(PullRequestRead route, boolean autoMerge)
    {
        try {
            writes.forSession(route.session()).removeFromMergeQueue(route.session().access(), route.repository(), route.number(), autoMerge);
        }
        finally {
            afterWrite(route, () -> invalidatePullRequestDetail(route));
        }
        afterWrite(route, () -> recent.pullRequestAction(rowId.apply(route).orElseThrow(), RecentReason.EDITED, 0));
    }

    private void invalidatePullRequestDetail(PullRequestRead route)
    {
        // Keep locally patched data readable while requiring the next refresh to revalidate it.
        rowId.apply(route)
                .ifPresent(prId -> details.updateEtag(prId, Optional.empty()));
    }

    private void invalidatePullRequestCaches(PullRequestRead route)
    {
        rowId.apply(route)
                .ifPresent(snapshots::clearRemote);
    }

    private PullRequestDetail patchCachedTimeline(PullRequestRead route, Optional<Long> prId, PrTimelineEvent created)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = prId.flatMap(id -> snapshots.patchDetail(id, cached -> {
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            timeline.removeIf(event -> created.githubId() != null
                    && created.githubId().equals(event.githubId())
                    && created.event().equals(event.event()));
            timeline.add(created);
            return new StoredPrDetail(cached.raw(), cached.reviews(), timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        }));
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), route.repository().fullPath(), route.number(), detail, detail.viewerCanWrite()))
                .orElseGet(() -> readDetail.apply(route));
    }
}
