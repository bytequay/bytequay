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
package com.bytequay.core.discussion;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One private, read-only discussion about a quoted passage of a completed assistant answer, or, as a
 * private thread, about one review comment on the task's pull request.
 *
 * <p>The whole aggregate is persisted as a single JSON payload, so adding a field needs no schema
 * change. A passage discussion's seed is captured when it is created and never recomputed: the
 * re-seed after a lost provider session must reproduce the original premise, and the thread stays
 * readable after the task's runtime history is deleted. A comment thread stores no seed; a run
 * without a provider session renders the task history from the log, which lives as long as the
 * worktree that asking requires.
 *
 * @param id               opaque assigned identifier
 * @param taskId           the task whose answer is being discussed
 * @param anchor           the quoted passage this discussion belongs to; a comment thread's is
 *         derived from its comment
 * @param comment          the review comment a private thread is about; absent on a passage discussion
 * @param seed             the rendered task history through the anchored turn; present on passage
 *         discussions only
 * @param providerLane     the lane the conversation runs on, fixed at creation
 * @param providerModel    the model the conversation runs on, read from the task's defaults and
 *         fixed at creation so a later default change does not switch the thread mid-conversation
 * @param sessionReference the provider conversation to resume, recorded after a completed answer
 *         and cleared after a failed one
 * @param messages         questions and answers in order, oldest first
 * @param resolved         whether the reader has completed this thread
 * @param version          optimistic-locking version
 */
public record TaskDiscussion(
        String id,
        String taskId,
        DiscussionAnchor anchor,
        Optional<ReviewCommentAnchor> comment,
        Optional<String> seed,
        String providerLane,
        Optional<String> providerModel,
        Optional<DiscussionSession> sessionReference,
        List<DiscussionMessage> messages,
        boolean resolved,
        Instant createdAt,
        Instant updatedAt,
        long version)
{
    /**
     * Bounds the retained snapshot, which the discussion reads in pages rather than loading into
     * its prompt. Fail rather than silently shorten the premise the reader is relying on.
     */
    public static final int MAX_SEED_CHARACTERS = 1_200_000;

    /** A discussion is a short side conversation; a thread this long has become something else. */
    public static final int MAX_MESSAGES = 100;

    public TaskDiscussion
    {
        requireNonNull(id, "id is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(anchor, "anchor is null");
        requireNonNull(comment, "comment is null");
        requireNonNull(seed, "seed is null");
        requireNonNull(providerLane, "providerLane is null");
        requireNonNull(providerModel, "providerModel is null");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(id.matches("[A-Za-z0-9._:-]{1,100}"), "Invalid discussion id");
        checkArgument(!taskId.isBlank() && taskId.length() <= 100, "Invalid discussion task id");
        checkArgument(!providerLane.isBlank() && providerLane.length() <= 50, "Invalid discussion lane");
        checkArgument(seed.isPresent() != comment.isPresent(), "A passage discussion stores its seed; a comment thread renders it per run");
        checkArgument(comment.map(value -> value.anchor().equals(anchor)).orElse(true), "Comment anchor mismatch");
        checkArgument(seed.map(text -> !text.isBlank() && text.length() <= MAX_SEED_CHARACTERS).orElse(true), "Seed exceeds the context budget");
        checkArgument(version >= 0, "Invalid version");
        messages = ImmutableList.copyOf(messages);
        checkArgument(messages.size() <= MAX_MESSAGES, "Discussion is full");
    }

    /** Restates the stored version after a read, which the entity owns rather than the payload. */
    public TaskDiscussion withVersion(long value)
    {
        return new TaskDiscussion(id, taskId, anchor, comment, seed, providerLane, providerModel, sessionReference,
                messages, resolved, createdAt, updatedAt, value);
    }

    /** Replaces the mutable half of the aggregate: its messages, its resolution and its session. */
    public TaskDiscussion update(
            boolean nextResolved,
            List<DiscussionMessage> nextMessages,
            Optional<DiscussionSession> nextSession,
            Instant now)
    {
        return new TaskDiscussion(id, taskId, anchor, comment, seed, providerLane, providerModel, nextSession,
                nextMessages, nextResolved, createdAt, now, version);
    }
}
