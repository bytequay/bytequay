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

import java.util.List;

/**
 * Private, read-only discussions about a quoted passage of a completed assistant answer.
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
     * A task holds at most this many discussions. Each owns one persisted provider conversation, so
     * the bound covers provider-side transcripts on disk as well as rows.
     */
    int MAXIMUM_DISCUSSIONS_PER_TASK = 50;
}
