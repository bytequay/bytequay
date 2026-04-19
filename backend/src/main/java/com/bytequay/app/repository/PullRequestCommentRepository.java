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

import com.bytequay.app.domain.CommentListQuery;
import com.bytequay.app.domain.CreateCommentCommand;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReviewComment;
import com.bytequay.app.domain.RepoRef;

import java.util.List;

/**
 * Repository abstraction over the GitHub Pull Request Review Comments API.
 *
 * All methods take an explicit {@code pat} so the caller controls which
 * GitHub identity is used; this keeps the repository stateless.
 *
 * API reference: <a href="https://docs.github.com/en/rest/pulls/comments">https://docs.github.com/en/rest/pulls/comments</a>
 */
public interface PullRequestCommentRepository
{
    /**
     * Lists all review comments on a single pull request.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{pull_number}/comments
     */
    List<PullRequestReviewComment> listComments(
            String pat,
            PullRequestRef pr,
            CommentListQuery query);

    /**
     * Lists review comments across all pull requests in a repository.
     * Maps to: GET /repos/{owner}/{repo}/pulls/comments
     */
    List<PullRequestReviewComment> listRepoComments(
            String pat,
            RepoRef repo,
            CommentListQuery query);

    /**
     * Retrieves a single review comment by its ID.
     * Maps to: GET /repos/{owner}/{repo}/pulls/comments/{comment_id}
     */
    PullRequestReviewComment getComment(
            String pat,
            RepoRef repo,
            long commentId);

    /**
     * Creates a new review comment on the diff of a pull request.
     * Maps to: POST /repos/{owner}/{repo}/pulls/{pull_number}/comments
     */
    PullRequestReviewComment createComment(
            String pat,
            PullRequestRef pr,
            CreateCommentCommand command);

    /**
     * Creates a reply to an existing top-level review comment.
     * Maps to: POST /repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies
     */
    PullRequestReviewComment replyToComment(
            String pat,
            PullRequestRef pr,
            long commentId,
            String body);

    /**
     * Updates the body text of an existing review comment.
     * Maps to: PATCH /repos/{owner}/{repo}/pulls/comments/{comment_id}
     */
    PullRequestReviewComment updateComment(
            String pat,
            RepoRef repo,
            long commentId,
            String body);

    /**
     * Deletes a review comment.
     * Maps to: DELETE /repos/{owner}/{repo}/pulls/comments/{comment_id}
     */
    void deleteComment(
            String pat,
            RepoRef repo,
            long commentId);
}
