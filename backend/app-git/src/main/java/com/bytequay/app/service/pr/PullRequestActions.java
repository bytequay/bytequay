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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.pr.PullRequestService.Posted;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Explicit GitHub writes and the local bookkeeping that must not turn an accepted write into a failure. */
final class PullRequestActions
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestActions.class);
    private static final Set<String> REACTION_CONTENTS = ImmutableSet.of("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes");

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubPullRequestWriteClient pullRequestWrites;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PrDetailStore details;
    private final PrViewStateStore viewStateStore;
    private final RecentTouchRecorder recent;
    private final CredentialService credentialService;
    private final BiFunction<String, Integer, PullRequestDetail> readDetail;

    PullRequestActions(
            GitHubPullRequestReadClient gitHub,
            GitHubPullRequestWriteClient pullRequestWrites,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PrDetailStore details,
            PrViewStateStore viewStateStore,
            RecentTouchRecorder recent,
            CredentialService credentialService,
            BiFunction<String, Integer, PullRequestDetail> readDetail)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.details = requireNonNull(details, "details is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.readDetail = requireNonNull(readDetail, "readDetail is null");
    }

    boolean hasMergeQueue(String repo, String baseRef)
    {
        checkText(baseRef, "baseRef");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return pullRequestWrites.hasMergeQueue(pat, RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    Posted createReviewComment(String repo, int number, InlineReviewComment comment)
    {
        validateReviewComment(comment, true);
        String pat = credentialService.resolveGitHubToken();
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        PullRequestDetail before = readDetail.apply(repo, number);
        if (comment.replyToCommentId().isPresent()) {
            replyToReviewComment(repo, number, comment.replyToCommentId().getAsLong(), comment.body());
            return new Posted(afterWrite(repo, number, () -> readDetail.apply(repo, number), before), OptionalLong.empty(), ImmutableList.of());
        }
        PrTimelineEvent created = pullRequestWrites.createReviewComment(pat, parseRef(repo, number), comment);
        PullRequestDetail detail = afterWrite(repo, number, () -> {
            PullRequestDetail patched = patchCachedTimeline(repo, number, prId, created);
            prId.ifPresent(id -> markReviewed(id, "COMMENT", comment.commitId()));
            recordComment(repo, number, "review-comment", created, comment.body());
            return patched;
        }, before);
        return new Posted(detail, OptionalLong.empty(), ImmutableList.of(created));
    }

    PullRequestDetail commentOnPullRequest(String repo, int number, String body)
    {
        checkText(body, "comment body");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PullRequestDetail before = readDetail.apply(repo, number);
        PrTimelineEvent created = pullRequestWrites.createIssueComment(pat, ref, body);
        return afterWrite(repo, number, () -> {
            PullRequestDetail detail = patchCachedTimeline(repo, number, store.findIdByRepoAndNumber(repo, number), created);
            recordComment(repo, number, "comment", created, body);
            return detail;
        }, before);
    }

    /**
     * Runs a step after a GitHub write landed. Its failure must not fail the request: the caller would
     * take the write for failed and send it again. The next refresh repairs the local copy.
     *
     * <p>Writers read the detail they fall back to before writing, so a failed read sends nothing.
     */
    private static <T> T afterWrite(String repo, int number, Supplier<T> step, T fallback)
    {
        try {
            return step.get();
        }
        catch (RuntimeException failure) {
            log.warn("Wrote to {}#{} on GitHub but could not update the local copy: {}", repo, number, failure.toString());
            return fallback;
        }
    }

    private static void afterWrite(String repo, int number, Runnable step)
    {
        afterWrite(repo, number, () -> {
            step.run();
            return true;
        }, false);
    }

    boolean toggleReaction(String repo, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
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

        PullRequestRef ref = parseRef(repo, number);
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        try {
            return switch (normalizedTarget) {
                case "pull_request" -> pullRequestWrites.togglePullRequestReaction(credential.token(), ref, normalizedContent, viewerHasReacted);
                case "issue_comment" -> pullRequestWrites.toggleIssueCommentReaction(credential.token(), ref, commentId, normalizedContent, viewerHasReacted);
                case "review_comment" -> pullRequestWrites.toggleReviewCommentReaction(credential.token(), ref, commentId, normalizedContent, viewerHasReacted);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reaction target");
            };
        }
        finally {
            afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        }
    }

    void updateTitle(String repo, int number, String title)
    {
        checkText(title, "pull request title");
        String normalizedTitle = title.trim();
        pullRequestWrites.updatePullRequestTitle(credentialService.resolveGitHubToken(), parseRef(repo, number), normalizedTitle);
        afterWrite(repo, number, () -> {
            store.findIdByRepoAndNumber(repo, number).ifPresent(prId -> {
                snapshots.patchDetail(prId, cached -> new StoredPrDetail(cached.raw().withTitle(normalizedTitle), cached.reviews(), cached.timeline(),
                        cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite()));
                store.updateTitle(prId, normalizedTitle);
            });
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    void updateDescription(String repo, int number, String description)
    {
        pullRequestWrites.updatePullRequestDescription(credentialService.resolveGitHubToken(), parseRef(repo, number), requireNonNull(description, "description is null"));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    void closePullRequest(String repo, int number)
    {
        pullRequestWrites.closePullRequest(credentialService.resolveGitHubToken(), parseRef(repo, number));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestCaches(repo, number);
        });
    }

    void markReadyForReview(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, parseRef(repo, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        try {
            pullRequestWrites.markReadyForReview(pat, nodeId);
        }
        finally {
            // A lost response may still have promoted the draft; refresh without repeating the write.
            afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        }
        afterWrite(repo, number, () -> recent.pullRequestAction(repo, number, RecentReason.EDITED, 0));
    }

    void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        pullRequestWrites.updateIssueMetadata(
                credentialService.resolveGitHubToken(),
                parseRef(repo, number),
                normalizeNames(assignees, "assignees"),
                normalizeNames(labels, "labels"));
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        checkText(login, "reviewer login");
        pullRequestWrites.updateReviewerRequest(credentialService.resolveGitHubToken(), parseRef(repo, number), login.trim(), requested);
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            invalidatePullRequestDetail(repo, number);
        });
    }

    void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        checkText(body, "reply body");
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.replyToReviewComment(pat, parseRef(repo, number), commentId, body);
        afterWrite(repo, number, () -> {
            invalidatePullRequestCaches(repo, number);
            recent.pullRequestAction(repo, number, RecentReason.COMMENTED, 0);
            recent.wroteOnPullRequest(repo, number, RecentTextKind.COMMENT, "reply:" + commentId, body);
        });
    }

    /** Edits a posted comment or review body on GitHub, then patches the cached copies of that body. */
    PullRequestDetail editComment(String repo, int number, String target, long commentId, String body)
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

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PullRequestDetail before = readDetail.apply(repo, number);
        switch (normalizedTarget) {
            case "issue_comment" -> pullRequestWrites.updateIssueComment(pat, ref, commentId, body);
            case "review_comment" -> pullRequestWrites.updateReviewComment(pat, ref, commentId, body);
            case "review" -> pullRequestWrites.updateReviewBody(pat, ref, commentId, body);
            default -> throw new IllegalStateException("unsupported comment target: " + normalizedTarget);
        }
        Optional<PullRequestDetail> patched = afterWrite(repo, number, () -> {
            Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
            return prId.flatMap(id -> snapshots.patchDetail(id, cached -> withEditedBody(cached, normalizedTarget, commentId, body)))
                    .map(stored -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), repo, number, stored, stored.viewerCanWrite()));
        }, Optional.empty());
        afterWrite(repo, number, () -> {
            recent.pullRequestAction(repo, number, RecentReason.EDITED, 0);
            recent.wroteOnPullRequest(repo, number, "review".equals(normalizedTarget) ? RecentTextKind.REVIEW_BODY : RecentTextKind.COMMENT, textKey, body);
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

    void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        String threadId = gitHub.findReviewThreadId(pat, ref, rootCommentId);
        pullRequestWrites.setReviewThreadResolved(pat, threadId, resolved);
        afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
    }

    /**
     * Submits a review, with any inline comments, for the given pull request on GitHub and
     * records a local reviewed state.
     */
    Posted submitReview(String repo, int number, long prId, String event, String body, List<InlineReviewComment> comments, String commitId)
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

        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        Map<Long, String> replyThreads = new LinkedHashMap<>();
        reviewComments.stream().map(InlineReviewComment::replyToCommentId).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).distinct()
                .forEach(commentId -> replyThreads.put(commentId, gitHub.findReviewThreadId(pat, ref, commentId)));
        PullRequestDetail before = readDetail.apply(repo, number);
        PrReviewState created = replyThreads.isEmpty()
                ? pullRequestWrites.submitReview(pat, ref, normalizedEvent, reviewBody, reviewComments, commitId)
                : pullRequestWrites.submitReviewWithReplies(pat, ref, normalizedEvent, reviewBody, reviewComments, replyThreads, commitId);
        // The review response carries the review alone, so the inline comments it
        // was submitted with have to be read back or they vanish from the diff
        // until the next poll.
        List<PrTimelineEvent> threads = reviewComments.isEmpty() || created.githubId() == null
                ? ImmutableList.of()
                : afterWrite(repo, number, () -> replyThreads.isEmpty() ? gitHub.fetchReviewComments(pat, ref, created.githubId()) : gitHub.fetchPrReviewThreads(pat, ref),
                        ImmutableList.of());
        PullRequestDetail detail = afterWrite(repo, number, () -> {
            PullRequestDetail patched = patchCachedReview(repo, number, prId, created, threads);
            markReviewed(prId, normalizedEvent, commitId);
            recordReview(repo, number, normalizedEvent, reviewBody, created);
            reviewComments.forEach(inline -> recent.wroteOnPullRequest(
                    repo,
                    number,
                    RecentTextKind.COMMENT,
                    "review-comment:" + created.githubId() + ":" + inline.path() + ":" + inline.line(),
                    inline.body()));
            return patched;
        }, before);
        return new Posted(detail, created.githubId() == null ? OptionalLong.empty() : OptionalLong.of(created.githubId()), threads);
    }

    /**
     * Splices a submitted review, and any inline comments it carried, into the
     * cached detail. Approving cannot change the diff or the checks, so nothing
     * else is invalidated and the poll picks up whatever else moved.
     */
    private PullRequestDetail patchCachedReview(String repo, int number, long prId, PrReviewState created, List<PrTimelineEvent> threads)
    {
        requireNonNull(created, "created is null");
        Optional<StoredPrDetail> patched = snapshots.patchDetail(prId, cached -> {
            List<PrReviewState> reviews = new ArrayList<>(cached.reviews());
            reviews.removeIf(review -> Objects.equals(review.githubId(), created.githubId()));
            reviews.add(created);
            List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
            PrTimelineEvent reviewed = reviewedTimelineEvent(created);
            timeline.removeIf(event -> Objects.equals(event.githubId(), reviewed.githubId())
                    && reviewed.event().equals(event.event()));
            timeline.add(reviewed);
            for (PrTimelineEvent thread : threads) {
                timeline.removeIf(event -> Objects.equals(event.githubId(), thread.githubId())
                        && thread.event().equals(event.event()));
                timeline.add(thread);
            }
            return new StoredPrDetail(cached.raw(), reviews, timeline, cached.checkRuns(), cached.unavailableSections(), cached.viewerCanWrite());
        });
        return patched
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId, repo, number, detail, detail.viewerCanWrite()))
                .orElseGet(() -> readDetail.apply(repo, number));
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

    private void recordComment(String repo, int number, String kind, PrTimelineEvent created, String body)
    {
        recent.pullRequestAction(repo, number, RecentReason.COMMENTED, 0);
        recent.wroteOnPullRequest(repo, number, RecentTextKind.COMMENT, textKey(kind, created), body);
    }

    private void recordReview(String repo, int number, String event, String body, PrReviewState created)
    {
        RecentReason reason = switch (event) {
            case "APPROVE" -> RecentReason.APPROVED;
            case "REQUEST_CHANGES" -> RecentReason.CHANGES_REQUESTED;
            default -> RecentReason.COMMENTED;
        };
        recent.pullRequestAction(repo, number, reason, 0);
        recent.wroteOnPullRequest(repo, number, RecentTextKind.REVIEW_BODY, "review:" + Objects.toString(created == null ? null : created.githubId(), "latest"), body);
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

    /** Merges directly, falling back to GitHub's merge queue when required. */
    MergeResult mergePullRequest(String repo, int number, long prId, String strategy, String headSha)
    {
        checkText(headSha, "pull request head commit");
        PullRequestRef ref = parseRef(repo, number);
        if (store.findIdByRepoAndNumber(ref.repoFullName(), number).filter(id -> id == prId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pull request identity does not match this GitHub request; reopen it before merging");
        }
        String pat = credentialService.resolveGitHubToken();
        if ("when-ready".equalsIgnoreCase(strategy)) {
            String nodeId = pullRequestWrites.pullRequestNodeId(pat, ref)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
            try {
                return pullRequestWrites.enablePullRequestAutoMerge(pat, nodeId, headSha);
            }
            finally {
                // A lost response may still have enabled auto-merge; let refresh reconcile it.
                afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
            }
        }
        if ("queue".equalsIgnoreCase(strategy)) {
            return enqueuePullRequest(pat, ref, repo, number, headSha);
        }
        MergePullRequestCommand command = strategyCommand(strategy, headSha);
        MergeResult result;
        try {
            result = pullRequestWrites.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                return enqueuePullRequest(pat, ref, repo, number, headSha);
            }
            throw e;
        }
        afterWrite(repo, number, () -> {
            if (result.merged()) {
                store.setMonitored(prId, false);
            }
            viewStateStore.markReviewed(prId, HandledAction.MERGED, headSha, Instant.now());
            recent.pullRequestAction(repo, number, RecentReason.MERGED, 0);
            invalidatePullRequestDetail(repo, number);
        });
        return result;
    }

    void removeFromMergeQueue(String repo, int number, boolean autoMerge)
    {
        String pat = credentialService.resolveGitHubToken();
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, parseRef(repo, number))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        try {
            if (autoMerge) {
                pullRequestWrites.disablePullRequestAutoMerge(pat, nodeId);
            }
            else {
                pullRequestWrites.dequeuePullRequest(pat, nodeId);
            }
        }
        finally {
            // A lost response may still have stopped the merge; let refresh reconcile it.
            afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        }
        afterWrite(repo, number, () -> recent.pullRequestAction(repo, number, RecentReason.EDITED, 0));
    }

    private MergeResult enqueuePullRequest(String pat, PullRequestRef ref, String repo, int number, String headSha)
    {
        String nodeId = pullRequestWrites.pullRequestNodeId(pat, ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        MergeResult result = pullRequestWrites.enqueuePullRequest(pat, nodeId, headSha);
        // Queueing does not change the diff; retain it while the timeline refreshes.
        afterWrite(repo, number, () -> invalidatePullRequestDetail(repo, number));
        return result;
    }

    /** True when a direct-merge rejection is GitHub requiring the change to
     *  go through the merge queue (HTTP 405 with a queue message). */
    private static boolean requiresMergeQueue(ResponseStatusException e)
    {
        return e.getStatusCode().value() == 405
                && e.getReason() != null
                && e.getReason().toLowerCase(Locale.ROOT).contains("merge queue");
    }

    private static MergePullRequestCommand strategyCommand(String strategy, String headSha)
    {
        String method = switch (strategy == null ? "rebase" : strategy.toLowerCase(Locale.ROOT)) {
            case "squash" -> "squash";
            case "merge" -> "merge";
            default -> "rebase";
        };
        return new MergePullRequestCommand(method, Optional.empty(), Optional.empty(), Optional.of(headSha));
    }

    private void invalidatePullRequestDetail(String repo, int number)
    {
        // Keep locally patched data readable while requiring the next refresh to revalidate it.
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(prId -> details.updateEtag(prId, Optional.empty()));
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(snapshots::clearRemote);
    }

    private PullRequestDetail patchCachedTimeline(String repo, int number, Optional<Long> prId, PrTimelineEvent created)
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
                .map(detail -> PullRequestDetailMapper.toPullRequestDetail(prId.orElseThrow(), repo, number, detail, detail.viewerCanWrite()))
                .orElseGet(() -> readDetail.apply(repo, number));
    }
}
