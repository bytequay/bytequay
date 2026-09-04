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
package com.bytequay.agent.core;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * One user request waiting in a task's FIFO queue, and what became of it.
 *
 * <p>The text lives only in a task-owned blob. The value returned to callers carries a bounded
 * preview derived from that blob, but persistence does not copy it into SQLite. The settings are
 * the ones in force when the request was queued, so a default changed later does not rewrite it.
 * A claimed row keeps its first message event across a known-unsent retry, so the transcript never
 * duplicates that user message.
 */
public record QueuedAgentInput(
        QueuedInputId id,
        TaskId taskId,
        long position,
        State state,
        String idempotencyKey,
        BlobRef textBlob,
        String preview,
        AgentTurnSettings settings,
        Optional<EventId> messageEventId,
        Optional<TurnId> turnId,
        Optional<String> pauseReason,
        Instant createdAt,
        Instant updatedAt,
        long version)
{
    public static final int PREVIEW_LENGTH = 200;

    public QueuedAgentInput
    {
        requireNonNull(id, "id is null");
        requireNonNull(taskId, "taskId is null");
        checkArgument(position > 0, "position must be positive");
        requireNonNull(state, "state is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        requireNonNull(textBlob, "textBlob is null");
        checkNotBlank(preview, "preview");
        requireNonNull(settings, "settings is null");
        requireNonNull(messageEventId, "messageEventId is null");
        requireNonNull(turnId, "turnId is null");
        checkOptionalText(pauseReason, "pauseReason");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkArgument(version >= 0, "version is negative");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        checkArgument(turnId.isPresent() == messageEventId.isPresent(), "a claimed input has exactly one message event");
        checkArgument(state == State.QUEUED || state == State.PAUSED || state == State.REMOVED || turnId.isPresent(), "state " + state + " requires a turn");
        checkArgument((state == State.PAUSED || state == State.UNCERTAIN || state == State.ABANDONED) == pauseReason.isPresent(), "pauseReason and state disagree");
    }

    public static QueuedAgentInput queued(
            QueuedInputId id,
            TaskId taskId,
            long position,
            String idempotencyKey,
            BlobRef textBlob,
            String text,
            AgentTurnSettings settings,
            Instant now)
    {
        checkNotBlank(text, "text");
        return new QueuedAgentInput(
                id,
                taskId,
                position,
                State.QUEUED,
                idempotencyKey,
                textBlob,
                preview(text),
                settings,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now,
                0);
    }

    /** The first line or so of the text, which is all the queue strip shows. */
    public static String preview(String text)
    {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH - 1) + "…";
    }

    /** Claims this input for a turn. The message event is emitted once, on the first claim. */
    public QueuedAgentInput starting(TurnId claimedTurn, EventId messageEvent, Instant now)
    {
        requireNonNull(claimedTurn, "claimedTurn is null");
        requireNonNull(messageEvent, "messageEvent is null");
        checkState(state == State.QUEUED || state == State.PAUSED, "cannot start an input in state " + state);
        checkArgument(messageEventId.isEmpty() || messageEventId.orElseThrow().equals(messageEvent), "a claimed input keeps its message event");
        return copy(State.STARTING, Optional.of(messageEvent), Optional.of(claimedTurn), Optional.empty(), now);
    }

    /** The provider acknowledged the turn, so the input is consumed whatever happens to that turn next. */
    public QueuedAgentInput started(Instant now)
    {
        requireState(State.STARTING);
        return copy(State.STARTED, messageEventId, turnId, Optional.empty(), now);
    }

    public QueuedAgentInput paused(String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        checkState(state == State.QUEUED || state == State.STARTING, "cannot pause an input in state " + state);
        return copy(State.PAUSED, messageEventId, turnId, Optional.of(reason), now);
    }

    /** The turn was sent and never acknowledged; whether the provider ran it is unknown. */
    public QueuedAgentInput uncertain(String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        requireState(State.STARTING);
        return copy(State.UNCERTAIN, messageEventId, turnId, Optional.of(reason), now);
    }

    public QueuedAgentInput removed(Instant now)
    {
        checkState(state == State.QUEUED || state == State.PAUSED, "cannot remove an input in state " + state);
        return copy(State.REMOVED, messageEventId, turnId, Optional.empty(), now);
    }

    /** The user declined to resend an uncertain input; this says nothing about whether it arrived. */
    public QueuedAgentInput abandoned(String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        requireState(State.UNCERTAIN);
        return copy(State.ABANDONED, messageEventId, turnId, Optional.of(reason), now);
    }

    public QueuedAgentInput withVersion(long updatedVersion)
    {
        return new QueuedAgentInput(id, taskId, position, state, idempotencyKey, textBlob, preview, settings,
                messageEventId, turnId, pauseReason, createdAt, updatedAt, updatedVersion);
    }

    /** Adds the display-only preview derived from the owner-only text blob. */
    public QueuedAgentInput withPreview(String text)
    {
        checkNotBlank(text, "text");
        return new QueuedAgentInput(id, taskId, position, state, idempotencyKey, textBlob, preview(text), settings,
                messageEventId, turnId, pauseReason, createdAt, updatedAt, version);
    }

    private QueuedAgentInput copy(
            State updatedState,
            Optional<EventId> updatedMessageEventId,
            Optional<TurnId> updatedTurnId,
            Optional<String> updatedPauseReason,
            Instant now)
    {
        requireNonNull(now, "now is null");
        return new QueuedAgentInput(id, taskId, position, updatedState, idempotencyKey, textBlob, preview, settings,
                updatedMessageEventId, updatedTurnId, updatedPauseReason, createdAt, now, version);
    }

    private void requireState(State expected)
    {
        checkState(state == expected, "expected queued input state " + expected + " but was " + state);
    }

    public enum State
    {
        QUEUED,
        STARTING,
        STARTED,
        PAUSED,
        UNCERTAIN,
        REMOVED,
        ABANDONED;

        /** Waiting or being claimed: what stands between the task and Complete. */
        public boolean live()
        {
            return this == QUEUED || this == PAUSED || this == STARTING;
        }

        /** Consumed or discarded: history that blocks nothing and is no longer shown. */
        public boolean historical()
        {
            return this == STARTED || this == REMOVED || this == ABANDONED;
        }
    }
}
