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
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestRepository.MergeQueueInfo;
import com.bytequay.app.repository.PullRequestRepository.ReviewThreadMeta;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.CollaboratorPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        MergeQueueInfo queue = requireNonNull(
                pullRequests.fetchMergeQueueInfo(pat, pullRequest),
                "GitHub returned no merge queue observation");

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
        long observedAt = clock.instant().toEpochMilli();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("repository", repository.fullName());
        raw.put("pullRequest", request.pullRequestNumber());
        raw.put("detail", detail);
        raw.put("reviews", reviews);
        raw.put("checks", checks);
        raw.put("reviewThreads", threads);
        raw.put("reviewComments", comments);
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
                rawEvidence,
                observedAt);
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

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record IndexedReview(PrReviewState review, int index) {}
}
