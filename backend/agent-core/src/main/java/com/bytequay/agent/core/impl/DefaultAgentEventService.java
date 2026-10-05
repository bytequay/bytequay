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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentEventService
        implements AgentEventService
{
    private static final int REPLAY_PAGE_SIZE = 8;

    private final TaskLogStore logStore;
    private final AgentEventOutboxStore outboxStore;
    private final AgentTaskStore taskStore;
    private final Clock clock;
    private final ConcurrentMap<TaskId, TaskChannel> channels = new ConcurrentHashMap<>();

    public DefaultAgentEventService(
            TaskLogStore logStore,
            AgentEventOutboxStore outboxStore,
            AgentTaskStore taskStore,
            Clock clock)
    {
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public AgentEventEnvelope append(UnsequencedAgentEvent event)
    {
        requireNonNull(event, "event is null");
        TaskChannel channel = channel(event.taskId());
        AgentEventEnvelope envelope;
        List<EventSubscription> subscriptions;
        synchronized (channel) {
            if (!channel.writerInitialized) {
                // Once a channel serves writes its task is known; a streamed turn appends without asking the database.
                taskStore.require(event.taskId());
            }
            initializeForWrite(event.taskId(), channel);
            Optional<Long> existingSequence = channel.sequenceOf(event.eventId());
            if (existingSequence.isPresent()) {
                // Appended earlier, so its subscribers already have it; a repeat must not reach them twice.
                return requirePersistedEnvelope(event, existingSequence.orElseThrow());
            }
            envelope = new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    event.eventId(),
                    channel.lastSequence + 1,
                    event.taskId(),
                    event.turnId(),
                    event.source(),
                    Instant.now(clock),
                    event.payload());
            // A display event needs no flush of its own: the next state event's flush makes it durable too.
            logStore.appendWithoutFlush(envelope);
            channel.appended(envelope);
            // Display events come only from a live turn, so none of them can settle a terminal task.
            subscriptions = handOver(channel, envelope);
        }
        subscriptions.forEach(EventSubscription::drain);
        return envelope;
    }

    @Override
    public AgentEventEnvelope drain(EventId eventId)
    {
        requireNonNull(eventId, "eventId is null");
        AgentEventOutboxEntry discovered = requireOutboxEntry(eventId);
        AgentTask task = taskStore.require(discovered.taskId());
        TaskChannel channel = channel(discovered.taskId());
        AgentEventEnvelope envelope;
        List<EventSubscription> subscriptions;
        RuntimeException deferred = null;
        synchronized (channel) {
            initializeForWrite(discovered.taskId(), channel);
            AgentEventOutboxEntry entry = requireOutboxEntry(eventId);
            if (!entry.taskId().equals(discovered.taskId())) {
                throw new AgentStorageException("Event outbox task identity changed: " + eventId);
            }
            if (entry.sequence().isPresent()) {
                // Appended, marked, and handed over; only a close that an earlier failed check missed can still be owed.
                envelope = requirePersistedEnvelope(entry, entry.sequence().orElseThrow());
                subscriptions = completeIfSettled(task, channel);
            }
            else {
                Optional<Long> existingSequence = channel.sequenceOf(eventId);
                if (existingSequence.isPresent()) {
                    // Appended and handed over before its mark was lost; the mark is what is still owed.
                    envelope = requirePersistedEnvelope(entry, existingSequence.orElseThrow());
                    outboxStore.markAppended(eventId, envelope.sequence());
                    subscriptions = completeIfSettled(task, channel);
                }
                else {
                    envelope = materialize(entry, channel.lastSequence + 1);
                    logStore.append(envelope);
                    channel.appended(envelope);
                    subscriptions = handOver(channel, envelope);
                    try {
                        outboxStore.markAppended(eventId, envelope.sequence());
                        completeIfSettled(task, channel);
                    }
                    catch (RuntimeException failure) {
                        // The event is in the log and with its subscribers. A lost mark leaves a pending
                        // row that the next drain settles without appending again, and that drain also
                        // redoes a close check that failed here.
                        deferred = failure;
                    }
                }
            }
        }
        subscriptions.forEach(EventSubscription::drain);
        if (deferred != null) {
            throw deferred;
        }
        return envelope;
    }

    @Override
    public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        validateCursor(afterSequence, limit);
        taskStore.require(taskId);
        return logStore.readAfter(taskId, afterSequence, limit);
    }

    @Override
    public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
    {
        requireNonNull(taskId, "taskId is null");
        if (afterSequence < 0) {
            throw new InvalidAgentRequestException("afterSequence must be non-negative");
        }
        taskStore.require(taskId);
        return subscriber -> {
            requireNonNull(subscriber, "subscriber is null");
            AgentTask task = taskStore.require(taskId);
            TaskChannel channel = channel(taskId);
            EventSubscription subscription;
            synchronized (channel) {
                subscription = new EventSubscription(subscriber, channel, taskId, afterSequence, channel.lastSequence, shouldComplete(task));
                channel.subscriptions.add(subscription);
            }
            subscriber.onSubscribe(subscription);
            subscription.start();
        };
    }

    @Override
    public AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(before, "before is null");
        requireNonNull(after, "after is null");
        if ((before.isPresent() && after.isPresent()) || before.orElse(0) < 0 || after.orElse(0) < 0) {
            throw new InvalidAgentRequestException("Provide at most one non-negative history cursor");
        }
        taskStore.require(taskId);
        return logStore.readHistory(taskId, before, after);
    }

    private TaskChannel channel(TaskId taskId)
    {
        return channels.computeIfAbsent(taskId, id -> new TaskChannel(
                logStore.readHistory(id, OptionalLong.empty(), OptionalLong.empty()).latestSequence()));
    }

    /** Reading a task must not scan old history; only a writer needs full validation and the deduplication index. */
    private void initializeForWrite(TaskId taskId, TaskChannel channel)
    {
        if (channel.writerInitialized) {
            return;
        }
        // lastSequence first: it validates the prefix and repairs a torn tail before anything new can be appended.
        long lastSequence = logStore.lastSequence(taskId);
        channel.sequences.clear();
        long indexed = 0;
        OptionalLong after = OptionalLong.of(0);
        // Byte-bounded pages: an index of a long log reopens the file a handful of times, not once per few events.
        while (indexed < lastSequence) {
            AgentHistoryPage page = logStore.readHistory(taskId, OptionalLong.empty(), after);
            if (page.events().isEmpty()) {
                throw new AgentStorageException("Task log ended before its last sequence: " + taskId);
            }
            page.events().forEach(envelope -> channel.sequences.put(envelope.eventId(), envelope.sequence()));
            indexed = page.events().getLast().sequence();
            after = page.afterCursor();
        }
        channel.lastSequence = lastSequence;
        channel.writerInitialized = true;
    }

    private AgentEventOutboxEntry requireOutboxEntry(EventId eventId)
    {
        return outboxStore.find(eventId)
                .orElseThrow(() -> new AgentStorageException("Event is absent from the outbox: " + eventId));
    }

    private AgentEventEnvelope materialize(AgentEventOutboxEntry entry, long sequence)
    {
        AgentEventPayload payload = switch (entry.payload()) {
            case AgentEventOutboxPayload.Inline inline -> inline.payload();
            case AgentEventOutboxPayload.UserMessageBlob blob -> new AgentEventPayload.UserMessage(
                    readUtf8(entry.taskId(), blob.textBlob()),
                    blob.attachments(),
                    blob.deliveryKind());
        };
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                entry.eventId(),
                sequence,
                entry.taskId(),
                entry.turnId(),
                entry.source(),
                entry.occurredAt(),
                payload);
    }

    private String readUtf8(TaskId taskId, BlobRef blobRef)
    {
        ByteBuffer content = logStore.readBlob(taskId, blobRef);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(content).toString();
        }
        catch (CharacterCodingException e) {
            throw new AgentStorageException("User-message blob is not valid UTF-8", e);
        }
    }

    private AgentEventEnvelope requirePersistedEnvelope(AgentEventOutboxEntry entry, long sequence)
    {
        AgentEventEnvelope envelope = requirePersistedEnvelope(entry.taskId(), entry.eventId(), sequence);
        AgentEventEnvelope expected = materialize(entry, sequence);
        if (!envelope.equals(expected)) {
            throw new AgentStorageException("Outbox event does not match the task log: " + entry.eventId());
        }
        return envelope;
    }

    private AgentEventEnvelope requirePersistedEnvelope(UnsequencedAgentEvent event, long sequence)
    {
        AgentEventEnvelope envelope = requirePersistedEnvelope(event.taskId(), event.eventId(), sequence);
        if (!envelope.turnId().equals(event.turnId())
                || !envelope.source().equals(event.source())
                || !envelope.payload().equals(event.payload())) {
            throw new AgentStorageException("Event identity refers to different task-log content: " + event.eventId());
        }
        return envelope;
    }

    private AgentEventEnvelope requirePersistedEnvelope(TaskId taskId, EventId eventId, long sequence)
    {
        List<AgentEventEnvelope> events = logStore.readAfter(taskId, sequence - 1, 1);
        if (events.isEmpty()) {
            throw new AgentStorageException("Event sequence is absent from the task log: " + eventId);
        }
        AgentEventEnvelope envelope = events.getFirst();
        if (envelope.sequence() != sequence || !envelope.eventId().equals(eventId)) {
            throw new AgentStorageException("Event sequence does not match the task log: " + eventId);
        }
        return envelope;
    }

    private boolean shouldComplete(AgentTask task)
    {
        // The terminal event is committed with the terminal state, so a terminal task with nothing
        // pending already has that event in its log.
        return task.state().terminal() && !outboxStore.hasPending(task.id());
    }

    /** Advances each subscriber's durable watermark; one that is caught up takes the event itself instead of rereading it. */
    private static List<EventSubscription> handOver(TaskChannel channel, AgentEventEnvelope envelope)
    {
        List<EventSubscription> subscriptions = ImmutableList.copyOf(channel.subscriptions);
        subscriptions.forEach(subscription -> subscription.appended(envelope));
        return subscriptions;
    }

    /** Ends every current subscription of a terminal task once nothing of its is left to drain. */
    private List<EventSubscription> completeIfSettled(AgentTask task, TaskChannel channel)
    {
        if (!shouldComplete(task)) {
            return ImmutableList.of();
        }
        List<EventSubscription> subscriptions = ImmutableList.copyOf(channel.subscriptions);
        subscriptions.forEach(EventSubscription::complete);
        return subscriptions;
    }

    private static void validateCursor(long afterSequence, int limit)
    {
        if (afterSequence < 0 || limit <= 0) {
            throw new InvalidAgentRequestException(
                    "afterSequence must be non-negative and limit must be positive");
        }
    }

    private static final class TaskChannel
    {
        private final List<EventSubscription> subscriptions = new ArrayList<>();
        // ponytail: writer-only identity metadata gives O(1) retries; a persistent ID index can bound it if material.
        private final Map<EventId, Long> sequences = new HashMap<>();
        /** Every sequence up to this one is in the log and was handed to the subscribers of its time. */
        private long lastSequence;
        private boolean writerInitialized;

        private TaskChannel(long lastSequence)
        {
            this.lastSequence = lastSequence;
        }

        private Optional<Long> sequenceOf(EventId eventId)
        {
            return Optional.ofNullable(sequences.get(eventId));
        }

        private void appended(AgentEventEnvelope envelope)
        {
            lastSequence = envelope.sequence();
            sequences.put(envelope.eventId(), envelope.sequence());
        }

        private synchronized void remove(EventSubscription subscription)
        {
            subscriptions.remove(subscription);
        }
    }

    private final class EventSubscription
            implements Flow.Subscription
    {
        private final Flow.Subscriber<? super AgentEventEnvelope> subscriber;
        private final TaskChannel channel;
        private final TaskId taskId;
        private final ArrayDeque<AgentEventEnvelope> pending = new ArrayDeque<>();
        private long cursor;
        private long availableThrough;
        private long demand;
        private boolean started;
        private boolean terminal;
        private boolean cancelled;
        private boolean draining;

        private EventSubscription(
                Flow.Subscriber<? super AgentEventEnvelope> subscriber,
                TaskChannel channel,
                TaskId taskId,
                long cursor,
                long availableThrough,
                boolean terminal)
        {
            this.subscriber = subscriber;
            this.channel = channel;
            this.taskId = taskId;
            this.cursor = cursor;
            this.availableThrough = availableThrough;
            this.terminal = terminal;
        }

        private void start()
        {
            synchronized (this) {
                started = true;
            }
            drain();
        }

        @Override
        public void request(long requested)
        {
            if (requested <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("requested event count must be positive"));
                return;
            }
            synchronized (this) {
                demand = addCap(demand, requested);
            }
            drain();
        }

        @Override
        public void cancel()
        {
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                pending.clear();
            }
            channel.remove(this);
        }

        /** Holds at most the one next event, so a stalled subscriber retains no more than a single payload. */
        private synchronized void appended(AgentEventEnvelope envelope)
        {
            if (cancelled) {
                return;
            }
            if (pending.isEmpty() && cursor == envelope.sequence() - 1) {
                pending.addLast(envelope);
            }
            availableThrough = envelope.sequence();
        }

        /** Ends the subscription once its durable watermark has been delivered. */
        private synchronized void complete()
        {
            terminal = true;
        }

        private void drain()
        {
            synchronized (this) {
                if (!started || draining || cancelled) {
                    return;
                }
                draining = true;
            }
            while (true) {
                AgentEventEnvelope next = null;
                boolean complete = false;
                RuntimeException failure = null;
                synchronized (this) {
                    if (cancelled) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && cursor < availableThrough) {
                        try {
                            if (pending.isEmpty()) {
                                int limit = (int) Math.min(REPLAY_PAGE_SIZE, Math.min(demand, availableThrough - cursor));
                                pending.addAll(logStore.readAfter(taskId, cursor, limit));
                            }
                            if (pending.isEmpty() || pending.getFirst().sequence() != cursor + 1) {
                                throw new AgentStorageException("Task log has a gap after sequence " + cursor + ": " + taskId);
                            }
                            next = pending.remove();
                            cursor = next.sequence();
                            demand--;
                        }
                        catch (RuntimeException readFailure) {
                            failure = readFailure;
                            cancelled = true;
                            draining = false;
                            pending.clear();
                        }
                    }
                    else {
                        complete = terminal && cursor >= availableThrough;
                        draining = false;
                        if (complete) {
                            cancelled = true;
                        }
                    }
                }
                if (failure != null) {
                    channel.remove(this);
                    try {
                        subscriber.onError(failure);
                    }
                    catch (RuntimeException ignored) {
                        // A broken subscriber must not make a persisted append fail.
                    }
                    return;
                }
                if (next != null) {
                    try {
                        subscriber.onNext(next);
                    }
                    catch (RuntimeException ignored) {
                        cancel();
                        return;
                    }
                    continue;
                }
                if (complete) {
                    channel.remove(this);
                    try {
                        subscriber.onComplete();
                    }
                    catch (RuntimeException ignored) {
                        // A broken subscriber must not make a persisted append fail.
                    }
                }
                return;
            }
        }

        private static long addCap(long current, long additional)
        {
            long sum = current + additional;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
