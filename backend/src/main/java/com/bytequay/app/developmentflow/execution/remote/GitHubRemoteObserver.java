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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.MergeQueueInfo;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.CollaboratorPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.CANCELED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.FAILED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.NEUTRAL;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PASSED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.PENDING;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.QUEUED;
import static com.bytequay.app.developmentflow.stage.RemoteCiPolicy.CheckState.SKIPPED;
import static java.util.Objects.requireNonNull;

/** Exact, fail-closed GitHub snapshot used by the V2 Remote owner. */
@Component
public final class GitHubRemoteObserver
        implements RemoteObservationOperationHandler.Observer
{
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;
    private final CollaboratorPermissionService collaborators;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public GitHubRemoteObserver(
            PullRequestRepository pullRequests,
            PatResolver pats,
            CollaboratorPermissionService collaborators,
            ObjectMapper json)
    {
        this(pullRequests, pats, collaborators, json, Clock.systemUTC());
    }

    GitHubRemoteObserver(
            PullRequestRepository pullRequests,
            PatResolver pats,
            CollaboratorPermissionService collaborators,
            ObjectMapper json,
            Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.collaborators = requireNonNull(collaborators, "collaborators is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public RemoteObservationOperationHandler.Observation observe(
            RemoteObservationOperationHandler.Request request,
            ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        requireNonNull(request, "request is null");
        requireNonNull(execution, "execution is null");
        RepoRef repository = RepoRef.parse(request.repositoryId());
        PullRequestRef pullRequest = PullRequestRef.of(
                repository.owner(), repository.repo(), request.pullRequestNumber());
        String pat = pats.resolve(repository.fullName());

        requireActive(execution);
        String viewer = requireText(
                pullRequests.fetchUserProfile(pat).login(), "GitHub viewer login");
        requireActive(execution);
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(pat, pullRequest),
                "GitHub returned no pull request detail");
        requireText(detail.headSha(), "observed headSha");
        requireText(detail.baseSha(), "observed baseSha");
        requireActive(execution);
        List<PrReviewState> reviews = List.copyOf(
                pullRequests.fetchPrReviews(pat, pullRequest));
        requireActive(execution);
        List<PrCheckRunState> checks = List.copyOf(
                pullRequests.fetchPrCheckRunsStrict(
                        pat, repository.owner(), repository.repo(), detail.headSha()));
        requireActive(execution);
        List<ReviewThreadMeta> threads = List.copyOf(
                pullRequests.fetchReviewThreadResolution(pat, pullRequest));
        requireActive(execution);
        List<PrReviewThreadMessage> comments = List.copyOf(
                pullRequests.fetchPrReviewComments(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        List<PrTimelineEvent> timeline = List.copyOf(
                pullRequests.fetchPrTimeline(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        List<PrTimelineEvent> issueComments = List.copyOf(
                pullRequests.fetchPrIssueComments(pat, pullRequest, Instant.EPOCH));
        requireActive(execution);
        MergeQueueInfo queue = requireNonNull(
                pullRequests.fetchMergeQueueInfo(pat, pullRequest),
                "GitHub returned no merge queue observation");
        requireActive(execution);
        PrRawDetail stableDetail = requireNonNull(
                pullRequests.fetchPrDetail(pat, pullRequest),
                "GitHub returned no pull request stability detail");
        if (!Objects.equals(detail.headSha(), stableDetail.headSha())
                || !Objects.equals(detail.baseSha(), stableDetail.baseSha())) {
            throw new IllegalStateException(
                    "GitHub pull request head moved during exact observation");
        }

        List<PrReviewState> effective = effectiveReviews(reviews);
        int approvals = (int) effective.stream()
                .filter(review -> "APPROVED".equals(normalize(review.state())))
                .count();
        int changesRequested = (int) effective.stream()
                .filter(review -> "CHANGES_REQUESTED".equals(normalize(review.state())))
                .count();
        int writeApprovals = collaborators.countWriteApprovals(
                pat, repository, effective);
        Set<Long> unresolvedRoots = new HashSet<>();
        threads.stream()
                .filter(thread -> !thread.resolved())
                .map(ReviewThreadMeta::rootCommentDatabaseId)
                .forEach(unresolvedRoots::add);
        int unresolvedComments = (int) comments.stream()
                .filter(comment -> unresolvedRoots.contains(
                        comment.inReplyTo() == null
                                ? comment.githubId() : comment.inReplyTo()))
                .count();
        List<RemoteObservationOperationHandler.FeedbackFact> feedback =
                feedbackFacts(
                        comments, threads, timeline, issueComments, viewer, json);
        long observedAt = clock.instant().toEpochMilli();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("repository", repository.fullName());
        raw.put("pullRequest", request.pullRequestNumber());
        raw.put("detail", detail);
        raw.put("reviews", reviews);
        raw.put("checks", checks);
        raw.put("reviewThreads", threads);
        raw.put("reviewComments", comments);
        raw.put("timeline", timeline);
        raw.put("issueComments", issueComments);
        raw.put("viewer", viewer);
        raw.put("mergeQueue", queue);
        raw.put("observedAtMs", observedAt);
        String rawEvidence = write(raw);
        String key = digest(repository.fullName() + "#"
                + request.pullRequestNumber() + ":" + detail.headSha() + ":"
                + detail.baseSha() + ":" + observedAt + ":" + digest(rawEvidence));
        return new RemoteObservationOperationHandler.Observation(
                1,
                key,
                detail.headSha(),
                detail.baseSha(),
                prState(detail),
                mergeability(detail),
                mergeQueueState(detail, queue),
                approvals,
                writeApprovals,
                changesRequested,
                detail.requestedReviewerCount(),
                unresolvedRoots.size(),
                unresolvedComments,
                normalizeChecks(checks, json),
                feedback,
                rawEvidence,
                observedAt);
    }

    static List<RemoteObservationOperationHandler.FeedbackFact> feedbackFacts(
            List<PrReviewThreadMessage> comments,
            List<ReviewThreadMeta> threads,
            List<PrTimelineEvent> timeline,
            List<PrTimelineEvent> issueComments,
            String viewer,
            ObjectMapper json)
    {
        requireNonNull(comments, "comments is null");
        requireNonNull(threads, "threads is null");
        requireNonNull(timeline, "timeline is null");
        requireNonNull(issueComments, "issueComments is null");
        requireText(viewer, "viewer");
        requireNonNull(json, "json is null");

        Map<Long, ReviewThreadMeta> threadByRoot = new HashMap<>();
        for (ReviewThreadMeta thread : threads) {
            threadByRoot.put(thread.rootCommentDatabaseId(), thread);
        }
        List<RemoteObservationOperationHandler.FeedbackFact> facts =
                new ArrayList<>();
        for (PrReviewThreadMessage comment : comments) {
            long root = comment.inReplyTo() == null
                    ? comment.githubId() : comment.inReplyTo();
            ReviewThreadMeta thread = threadByRoot.get(root);
            if (thread != null && thread.resolved()) {
                continue;
            }
            String threadId = thread == null
                    ? firstText(comment.graphqlNodeId(), "review-thread:" + root)
                    : thread.graphqlNodeId();
            if (comment.body() == null || comment.body().isBlank()) {
                continue;
            }
            facts.add(fact(
                    RemoteObservationOperationHandler.FeedbackKind.INLINE_COMMENT,
                    "inline-comment:" + comment.githubId(), comment.author(),
                    sameLogin(comment.author(), viewer), threadId,
                    Long.toString(root),
                    comment.reviewId() == null
                            ? null : Long.toString(comment.reviewId()),
                    null, comment.body(), null, write(json, comment)));
        }
        for (ReviewThreadMeta thread : threads) {
            facts.add(fact(
                    thread.resolved()
                            ? RemoteObservationOperationHandler.FeedbackKind.THREAD_RESOLVED
                            : RemoteObservationOperationHandler.FeedbackKind.THREAD_REOPENED,
                    "thread-state:" + thread.graphqlNodeId(), thread.resolvedBy(),
                    false, thread.graphqlNodeId(), null, null, null, null, null,
                    write(json, thread)));
        }
        for (PrTimelineEvent event : issueComments) {
            if (!"COMMENTED".equals(normalize(event.event()))
                    || event.githubId() == null
                    || event.body() == null || event.body().isBlank()) {
                continue;
            }
            facts.add(fact(
                    RemoteObservationOperationHandler.FeedbackKind.TOP_LEVEL_COMMENT,
                    "top-level-comment:" + event.githubId(), event.actor(),
                    sameLogin(event.actor(), viewer), null,
                    Long.toString(event.githubId()), null, null, event.body(), null,
                    write(json, event)));
        }
        for (PrTimelineEvent event : timeline) {
            String kind = normalize(event.event());
            if ("REVIEWED".equals(kind)) {
                String reviewId = event.reviewId() == null
                        ? event.githubId() == null ? null
                                : Long.toString(event.githubId())
                        : Long.toString(event.reviewId());
                RemoteObservationOperationHandler.FeedbackVerdict verdict =
                        feedbackVerdict(event.state());
                if (reviewId == null || verdict == null) {
                    continue;
                }
                boolean own = sameLogin(event.actor(), viewer);
                if (event.body() != null && !event.body().isBlank()) {
                    facts.add(fact(
                            RemoteObservationOperationHandler.FeedbackKind.REVIEW_BODY,
                            "review-body:" + reviewId, event.actor(), own,
                            null, null, reviewId, null, event.body(), null,
                            write(json, event)));
                }
                facts.add(fact(
                        RemoteObservationOperationHandler.FeedbackKind.REVIEW_VERDICT,
                        "review-verdict:" + reviewId, event.actor(), own,
                        null, null, reviewId, null, null, verdict,
                        write(json, event)));
            }
            else if ("REVIEW_REQUESTED".equals(kind)
                    && event.requestedReviewer() != null
                    && !event.requestedReviewer().isBlank()) {
                String eventId = event.githubId() == null
                        ? digest(write(json, event))
                        : Long.toString(event.githubId());
                facts.add(fact(
                        RemoteObservationOperationHandler.FeedbackKind.REQUESTED_REVIEW,
                        "requested-review:" + eventId, event.actor(),
                        sameLogin(event.actor(), viewer), null, null, null,
                        event.requestedReviewer(), null, null, write(json, event)));
            }
        }
        return facts.stream()
                .sorted(Comparator.comparing(
                                RemoteObservationOperationHandler.FeedbackFact::externalKey)
                        .thenComparing(fact -> fact.kind().name()))
                .toList();
    }

    private static RemoteObservationOperationHandler.FeedbackFact fact(
            RemoteObservationOperationHandler.FeedbackKind kind,
            String externalKey,
            String actor,
            boolean ownAction,
            String threadId,
            String commentId,
            String reviewId,
            String requestedReviewer,
            String body,
            RemoteObservationOperationHandler.FeedbackVerdict verdict,
            String rawEvidence)
    {
        return new RemoteObservationOperationHandler.FeedbackFact(
                kind, externalKey, actor, ownAction, threadId, commentId,
                reviewId, requestedReviewer, body, verdict, rawEvidence);
    }

    private static RemoteObservationOperationHandler.FeedbackVerdict
            feedbackVerdict(String state)
    {
        return switch (normalize(state)) {
            case "APPROVED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.APPROVED;
            case "CHANGES_REQUESTED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.CHANGES_REQUESTED;
            case "COMMENTED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.COMMENTED;
            case "DISMISSED" ->
                    RemoteObservationOperationHandler.FeedbackVerdict.DISMISSED;
            default -> null;
        };
    }

    private static boolean sameLogin(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String firstText(String first, String fallback)
    {
        return first == null || first.isBlank() ? fallback : first;
    }

    static List<PrReviewState> effectiveReviews(List<PrReviewState> reviews)
    {
        requireNonNull(reviews, "reviews is null");
        Map<String, IndexedReview> latest = new HashMap<>();
        for (int index = 0; index < reviews.size(); index++) {
            PrReviewState review = requireNonNull(reviews.get(index), "review is null");
            requireText(review.login(), "review login");
            String login = review.login().toLowerCase(Locale.ROOT);
            IndexedReview candidate = new IndexedReview(review, index);
            latest.merge(login, candidate, GitHubRemoteObserver::later);
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(value ->
                        value.review().login().toLowerCase(Locale.ROOT)))
                .map(IndexedReview::review)
                .toList();
    }

    static List<RemoteCiPolicy.Check> normalizeChecks(
            List<PrCheckRunState> checks, ObjectMapper json)
    {
        requireNonNull(checks, "checks is null");
        requireNonNull(json, "json is null");
        List<RemoteCiPolicy.Check> normalized = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            PrCheckRunState check = requireNonNull(checks.get(index), "check is null");
            requireText(check.name(), "check name");
            normalized.add(new RemoteCiPolicy.Check(
                    "GITHUB_CHECK_RUN",
                    check.githubId() == null
                            ? "github-check:" + digest(check.name() + ":" + index)
                            : "github-check:" + check.githubId(),
                    check.name(),
                    checkState(check.status(), check.conclusion()),
                    check.status(),
                    check.conclusion(),
                    null,
                    null,
                    write(json, check)));
        }
        return List.copyOf(normalized);
    }

    static RemoteCiPolicy.CheckState checkState(String status, String conclusion)
    {
        String normalizedStatus = normalize(status);
        if ("QUEUED".equals(normalizedStatus)
                || "WAITING".equals(normalizedStatus)
                || "REQUESTED".equals(normalizedStatus)) {
            return QUEUED;
        }
        if ("IN_PROGRESS".equals(normalizedStatus)
                || "PENDING".equals(normalizedStatus)) {
            return PENDING;
        }
        if (!"COMPLETED".equals(normalizedStatus)) {
            return FAILED;
        }
        return switch (normalize(conclusion)) {
            case "SUCCESS" -> PASSED;
            case "NEUTRAL" -> NEUTRAL;
            case "SKIPPED" -> SKIPPED;
            case "CANCELLED", "CANCELED" -> CANCELED;
            default -> FAILED;
        };
    }

    static RemoteObservationOperationHandler.PrState prState(PrRawDetail detail)
    {
        if (detail.merged()) {
            return RemoteObservationOperationHandler.PrState.MERGED;
        }
        if ("closed".equalsIgnoreCase(detail.state())) {
            return RemoteObservationOperationHandler.PrState.CLOSED;
        }
        return detail.draft()
                ? RemoteObservationOperationHandler.PrState.DRAFT
                : RemoteObservationOperationHandler.PrState.OPEN;
    }

    static RemoteObservationOperationHandler.Mergeability mergeability(
            PrRawDetail detail)
    {
        String state = normalize(detail.mergeableState());
        if (detail.mergeable() == null || "UNKNOWN".equals(state)) {
            return RemoteObservationOperationHandler.Mergeability.UNKNOWN;
        }
        if ("DIRTY".equals(state)) {
            return RemoteObservationOperationHandler.Mergeability.CONFLICTING;
        }
        if (Boolean.TRUE.equals(detail.mergeable())
                && Set.of("CLEAN", "UNSTABLE", "HAS_HOOKS").contains(state)) {
            return RemoteObservationOperationHandler.Mergeability.MERGEABLE;
        }
        return RemoteObservationOperationHandler.Mergeability.BLOCKED;
    }

    static RemoteObservationOperationHandler.MergeQueueState mergeQueueState(
            PrRawDetail detail, MergeQueueInfo queue)
    {
        if (detail.merged()) {
            return RemoteObservationOperationHandler.MergeQueueState.MERGED;
        }
        String state = normalize(queue.entryState());
        if (state.isEmpty()) {
            return RemoteObservationOperationHandler.MergeQueueState.NONE;
        }
        if ("MERGED".equals(state)) {
            return RemoteObservationOperationHandler.MergeQueueState.MERGED;
        }
        if (Set.of("DEQUEUED", "REMOVED", "CANCELLED", "CANCELED")
                .contains(state)) {
            return RemoteObservationOperationHandler.MergeQueueState.DEQUEUED;
        }
        return RemoteObservationOperationHandler.MergeQueueState.QUEUED;
    }

    private static IndexedReview later(IndexedReview left, IndexedReview right)
    {
        Instant leftAt = left.review().submittedAt();
        Instant rightAt = right.review().submittedAt();
        if (leftAt == null && rightAt == null) {
            return left.index() < right.index() ? right : left;
        }
        if (leftAt == null) {
            return right;
        }
        if (rightAt == null) {
            return left;
        }
        int compared = leftAt.compareTo(rightAt);
        return compared < 0 || (compared == 0 && left.index() < right.index())
                ? right : left;
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote observation was canceled");
        }
    }

    private String write(Object value)
    {
        return write(json, value);
    }

    private static String write(ObjectMapper json, Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Serializing GitHub evidence failed", e);
        }
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record IndexedReview(PrReviewState review, int index) {}
}
