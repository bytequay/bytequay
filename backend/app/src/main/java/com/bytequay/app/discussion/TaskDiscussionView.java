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

import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionMessage.Author;
import com.bytequay.core.discussion.TaskDiscussion;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Display projection of one discussion, and the only discussion type the controller serializes.
 *
 * <p>The seed and the provider session reference are deliberately absent: the seed is bounded only
 * by the context budget and the session reference is a provider handle, and neither is drawn. The
 * anchor is flattened to the fields the renderer uses; the quote hash is not projected, because the
 * client locates a footer by searching the rendered blocks for the quote and taking the
 * occurrence-th match, and never recomputes the hash.
 *
 * @param occurrence which appearance of the quote in that answer, 1-based; the footer places
 *         itself with it, and the panel tells apart two threads whose labels are otherwise
 *         byte-identical
 * @param pending a question is in flight. It is the only way to tell a running question from one
 *         orphaned by a restart, which must render as unanswered: both are a trailing question
 *         with no answer and no error
 * @param providerLane the lane fixed at creation, for the answer byline; it can differ from the
 *         task's current default, so it is not read from the task
 */
public record TaskDiscussionView(
        String id,
        String taskId,
        String turnId,
        String quote,
        int occurrence,
        boolean resolved,
        boolean pending,
        String providerLane,
        List<Message> messages,
        long version,
        Instant createdAt,
        Instant updatedAt)
{
    public TaskDiscussionView
    {
        messages = ImmutableList.copyOf(messages);
    }

    /**
     * One question or one answer, oldest first.
     *
     * @param error present only on a question whose answer failed, carrying the reader-facing
     *         reason the card prints beside Retry; an answer never has one
     */
    public record Message(String id, Author author, String body, Instant createdAt, Optional<String> error) {}

    static TaskDiscussionView of(TaskDiscussion discussion, boolean pending)
    {
        return new TaskDiscussionView(
                discussion.id(),
                discussion.taskId(),
                discussion.anchor().turnId(),
                discussion.anchor().quote(),
                discussion.anchor().occurrence(),
                discussion.resolved(),
                pending,
                discussion.providerLane(),
                discussion.messages().stream().map(TaskDiscussionView::message).collect(toImmutableList()),
                discussion.version(),
                discussion.createdAt(),
                discussion.updatedAt());
    }

    private static Message message(DiscussionMessage message)
    {
        return new Message(message.id(), message.author(), message.body(), message.createdAt(), message.error());
    }
}
