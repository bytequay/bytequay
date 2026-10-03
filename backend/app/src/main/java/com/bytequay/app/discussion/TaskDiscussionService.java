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
package com.bytequay.app.discussion;

import com.bytequay.core.discussion.ReviewCommentAnchor;

import java.util.List;

/**
 * Private, read-only discussions about a quoted passage of a completed assistant answer, or, as a
 * private thread, about one review comment on the task's pull request.
 *
 * <p>A discussion is not an agent task and not a turn on one. It runs as its own provider
 * conversation, so its questions never enter the task's conversation, event log, outbox or stream.
 */
public interface TaskDiscussionService
{
    /**
     * Opens a discussion on a quoted passage and asks its first question, or asks a further
     * question on the discussion that passage already has.
     *
     * <p>Returns as soon as the question is recorded; the answer is produced on a virtual thread
     * and observed through {@link #list}. Repeating the same passage never creates a second
     * discussion.
     *
     * @throws IllegalArgumentException when the quote does not occur in the stored final answer of
     *         the named turn, or is too short to anchor
     * @throws com.bytequay.agent.core.AgentConflictException when the task has no live worktree,
     *         when a question is already in flight for this task, or when the task already
     *         holds {@link #MAXIMUM_DISCUSSIONS_PER_TASK} discussions
     */
    TaskDiscussionView open(OpenDiscussionCommand command);

    /**
     * Asks a follow-up in an existing discussion, resuming its provider conversation. A resume that
     * fails falls back to a re-seeded conversation carrying every prior message, so a lost provider
     * session costs context rather than the thread.
     */
    TaskDiscussionView ask(AskDiscussionCommand command);

    /**
     * Re-runs a question whose answer failed, in place: the recorded question keeps its identity
     * and its error is cleared, so a retry never appends a second copy of it.
     */
    TaskDiscussionView retry(String workspaceId, String taskId, String discussionId, String messageId);

    /** Marks a discussion done, or reopens it. */
    TaskDiscussionView resolve(String workspaceId, String taskId, String discussionId, boolean resolved, long expectedVersion);

    /** Every discussion on one task, for the transcript footers and the panel. */
    List<TaskDiscussionView> list(String workspaceId, String taskId);

    /**
     * Asks about one comment of a pull request the task owns. A blank question asks for the summary;
     * a blank question on a thread that already has messages returns it unchanged. Creates the
     * comment's thread on first use. Returns once the question is recorded.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the task's files were released, the
     *         task does not own the pull request, the task is already answering, its history is too long
     *         to ask about, or it holds {@link #MAXIMUM_COMMENT_THREADS_PER_TASK} threads
     * @throws IllegalArgumentException when the key is not in that pull request's cached detail
     * @throws com.bytequay.agent.spi.AgentUnavailableException when the task has no answered turn yet
     */
    TaskDiscussionView askComment(AskCommentCommand command);

    /**
     * Starts one read-only session summarizing the given comments. Each comment without a thread gets
     * one holding the hidden summary request, then its summary; a comment that already has a thread is
     * skipped. Returns once the threads are stored.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the task is already answering, which
     *         is the watcher's busy check, when its history is too long to ask about, or when it holds
     *         {@link #MAXIMUM_COMMENT_THREADS_PER_TASK} threads
     * @throws com.bytequay.agent.spi.AgentUnavailableException when the task has no answered turn yet
     */
    void summarize(String workspaceId, String taskId, List<ReviewCommentAnchor> comments);

    /** Drafts a GitHub reply from one comment thread; stores nothing and posts nothing. */
    String draftReply(String workspaceId, String taskId, String discussionId);

    /** The private threads a review's task holds on its pull request's comments; an ended review's stay readable. */
    List<TaskDiscussionView> listReviewThreads(String reviewId);

    /**
     * {@link #askComment} on the review's own pull request, answered by the review's task from what the review saved
     * rather than from the task's history.
     *
     * @throws com.bytequay.core.review.ReviewNotFoundException when there is no such review
     * @throws com.bytequay.agent.core.AgentConflictException when the review is a replay, names no pull request, has ended or
     *         released its working copy, is already answering, or holds {@link #MAXIMUM_COMMENT_THREADS_PER_TASK} threads
     * @throws IllegalArgumentException when the key is not in that pull request's cached detail
     */
    TaskDiscussionView askReviewComment(String reviewId, String key, String question);

    /** {@link #retry} on a thread the review's task holds, while the review can still answer. */
    TaskDiscussionView retryReviewThread(String reviewId, String discussionId, String messageId);

    /** {@link #resolve} on a thread the review's task holds, which works on an ended review too. */
    TaskDiscussionView resolveReviewThread(String reviewId, String discussionId, boolean resolved, long expectedVersion);

    /** {@link #draftReply} in the reviewer's voice, from a thread the review's task holds. */
    String draftReviewReply(String reviewId, String discussionId);

    /**
     * A task holds at most this many passage discussions. Each owns one persisted provider conversation, so
     * the bound covers provider-side transcripts on disk as well as rows.
     */
    int MAXIMUM_DISCUSSIONS_PER_TASK = 50;

    /** Counted apart from passage discussions, so neither kind uses up the other's cap. */
    int MAXIMUM_COMMENT_THREADS_PER_TASK = 100;
}
