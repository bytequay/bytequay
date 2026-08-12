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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.UpdatePullRequestCommand;

/** GitHub pull-request creation, review, comment, and metadata mutations. */
public interface GitHubPullRequestWriteRepository {
    default String commitFileText(
            String pat,
            RepoRef repo,
            String path,
            String branch,
            String blobSha,
            String text,
            String message) {
        throw unsupported();
    }

    default PullRequest createPullRequest(
            String pat, RepoRef repo, CreatePullRequestCommand command) {
        throw unsupported();
    }

    default PullRequest updatePullRequest(
            String pat, PullRequestRef pr, UpdatePullRequestCommand command) {
        throw unsupported();
    }

    default PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body) {
        throw unsupported();
    }

    default void createInlineReviewComment(
            String pat,
            PullRequestRef pr,
            String body,
            String path,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide) {
        throw unsupported();
    }

    default PrReviewThreadMessage replyToReviewComment(
            String pat, PullRequestRef pr, long rootCommentId, String body) {
        throw unsupported();
    }

    default void editIssueComment(
            String pat, String owner, String repo, long commentId, String body) {
        throw unsupported();
    }

    default void editReviewComment(
            String pat, String owner, String repo, long commentId, String body) {
        throw unsupported();
    }

    default void deleteIssueComment(String pat, String owner, String repo, long commentId) {
        throw unsupported();
    }

    default void deleteReviewComment(String pat, String owner, String repo, long commentId) {
        throw unsupported();
    }

    default void addPullRequestReaction(String pat, PullRequestRef ref, String content) {
        throw unsupported();
    }

    default void addReviewCommentReaction(
            String pat, String owner, String repo, long commentId, String content) {
        throw unsupported();
    }

    default void addIssueCommentReaction(
            String pat, String owner, String repo, long commentId, String content) {
        throw unsupported();
    }

    default void resolveReviewThread(String pat, String threadNodeId) {
        throw unsupported();
    }

    default void unresolveReviewThread(String pat, String threadNodeId) {
        throw unsupported();
    }

    default PullRequestReview createReview(
            String pat, PullRequestRef pr, CreateReviewCommand command) {
        throw unsupported();
    }

    default PullRequestReview dismissReview(
            String pat, PullRequestRef pr, long reviewId, String message) {
        throw unsupported();
    }

    default PullRequest requestReviewers(
            String pat, PullRequestRef pr, RequestReviewersCommand command) {
        throw unsupported();
    }

    default void removeRequestedReviewers(
            String pat, PullRequestRef pr, RequestReviewersCommand command) {
        throw unsupported();
    }

    default void setPullRequestAssignee(
            String pat, PullRequestRef pr, String login, boolean selected) {
        throw unsupported();
    }

    default void setPullRequestLabel(
            String pat, PullRequestRef pr, String label, boolean selected) {
        throw unsupported();
    }

    default void setPullRequestDraft(String pat, PullRequestRef pr, boolean draft) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("GitHub pull-request write not implemented");
    }
}
