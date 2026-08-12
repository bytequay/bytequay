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
package com.bytequay.app.repository;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestedReviewers;
import com.bytequay.app.domain.SuggestedReviewer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only GitHub pull-request operations. */
public interface GitHubPullRequestReadRepository {
    default List<PullRequest> searchPullRequests(String pat, String query) {
        throw unsupported();
    }

    default PullRequestHistoryPage searchPullRequestsPaged(
            String pat, String query, int page, int perPage) {
        return searchPullRequestsPaged(pat, query, page, perPage, null, null);
    }

    default PullRequestHistoryPage searchPullRequestsPaged(
            String pat, String query, int page, int perPage, String sort, String order) {
        throw unsupported();
    }

    default PrRawDetail fetchPrDetail(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default String fetchPrTitle(String pat, PullRequestRef pr) {
        return null;
    }

    default ProbeResult probeChangedSinceEtag(String pat, PullRequestRef pr, String etag) {
        throw unsupported();
    }

    record ProbeResult(boolean changed, String newEtag) {}

    default List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default String fetchPrDiff(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<PullRequestDetail.ChangedFile> fetchPrFiles(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<PullRequestCommit> fetchPrCommits(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<DiffFile> fetchCommitDiffFiles(String pat, PullRequestRef pr, String sha) {
        throw unsupported();
    }

    default List<String> fetchFileBlobLines(String pat, RepoRef repo, String path, String sha) {
        throw unsupported();
    }

    record FileBlob(String sha, String text) {
        public FileBlob {
            requireNonNull(sha, "sha is null");
            requireNonNull(text, "text is null");
        }
    }

    default Optional<FileBlob> fetchFileBlob(String pat, RepoRef repo, String path, String ref) {
        throw unsupported();
    }

    default List<PrTimelineEvent> fetchPrTimeline(String pat, PullRequestRef pr, Instant since) {
        throw unsupported();
    }

    default List<PrReviewThreadMessage> fetchPrReviewComments(
            String pat, PullRequestRef pr, Instant since) {
        throw unsupported();
    }

    default List<PrTimelineEvent> fetchPrIssueComments(
            String pat, PullRequestRef pr, Instant since) {
        throw unsupported();
    }

    default Optional<PullRequestDetail.LinkedIssue> fetchIssue(
            String pat, RepoRef repo, int number) {
        throw unsupported();
    }

    record Paged<T>(List<T> items, boolean complete) {
        public static <T> Paged<T> complete(List<T> items) {
            return new Paged<>(items, true);
        }

        public static <T> Paged<T> partial(List<T> items) {
            return new Paged<>(items, false);
        }
    }

    default Paged<PrReviewState> fetchAllPrReviews(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Paged<PullRequestDetail.ChangedFile> fetchAllPrFiles(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Paged<PullRequestCommit> fetchAllPrCommits(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Paged<PrReviewThreadMessage> fetchAllPrReviewComments(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default Paged<PrTimelineEvent> fetchAllPrTimeline(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<PullRequest> listPullRequests(
            String pat, RepoRef repo, ListPullRequestsQuery query) {
        throw unsupported();
    }

    default PullRequest getPullRequest(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<PullRequestRef> fetchAttentionPrRefs(String pat) {
        throw unsupported();
    }

    record ReviewThreadMeta(
            long rootCommentDatabaseId,
            String graphqlNodeId,
            boolean resolved,
            String resolvedBy) {}

    default List<ReviewThreadMeta> fetchReviewThreadResolution(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<SuggestedReviewer> fetchSuggestedReviewers(String pat, PullRequestRef pr) {
        return List.of();
    }

    default List<PullRequestReview> listReviews(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default RequestedReviewers getRequestedReviewers(String pat, PullRequestRef pr) {
        throw unsupported();
    }

    default List<GitHubUserMatch> fetchAssignableUsers(String pat, RepoRef repo) {
        throw unsupported();
    }

    default List<IssueDetail.Label> fetchRepoLabels(String pat, RepoRef repo) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("GitHub pull-request read not implemented");
    }
}
