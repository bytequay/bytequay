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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentEventService
        implements AgentEventService
{
    private static final int REPLAY_PAGE_SIZE = 1000;

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
        AgentTask task = requireTask(event.taskId());
        TaskChannel channel = channel(event.taskId());
        AgentEventEnvelope envelope;
        List<EventSubscription> subscriptions;
        synchronized (channel) {
            channel.checkUsable();
            Optional<Long> existingSequence = logStore.findSequence(event.taskId(), event.eventId());
            if (existingSequence.isPresent()) {
                envelope = requirePersistedEnvelope(event, existingSequence.orElseThrow());
                subscriptions = finishPublication(task, channel, envelope);
            }
            else {
                channel.checkReadyToPersist();
                envelope = new AgentEventEnvelope(
                        AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                        event.eventId(),
                        channel.lastSequence + 1,
                        event.taskId(),
                        event.turnId(),
                        event.source(),
                        Instant.now(clock),
                        event.payload());
                logStore.append(envelope);
                channel.lastSequence = envelope.sequence();
                subscriptions = finishPublication(task, channel, envelope);
            }
        }
        subscriptions.forEach(EventSubscription::drain);
        return envelope;
    }

    @Override
    public AgentEventEnvelope drain(EventId eventId)
    {
        requireNonNull(eventId, "eventId is null");
        AgentEventOutboxEntry discovered = requireOutboxEntry(eventId);
        AgentTask task = requireTask(discovered.taskId());
        TaskChannel channel = channel(discovered.taskId());
        AgentEventEnvelope envelope;
        List<EventSubscription> subscriptions;
        synchronized (channel) {
            channel.checkUsable();
            AgentEventOutboxEntry entry = requireOutboxEntry(eventId);
            if (!entry.taskId().equals(discovered.taskId())) {
                throw new AgentStorageException("Event outbox task identity changed: " + eventId);
            }
            if (entry.sequence().isPresent()) {
                envelope = requirePersistedEnvelope(entry, entry.sequence().orElseThrow());
            }
            else {
                Optional<Long> existingSequence = logStore.findSequence(entry.taskId(), eventId);
                if (existingSequence.isPresent()) {
                    envelope = requirePersistedEnvelope(entry, existingSequence.orElseThrow());
                }
                else {
                    channel.checkReadyToPersist();
                    envelope = materialize(entry, channel.lastSequence + 1);
                    logStore.append(envelope);
                    channel.lastSequence = envelope.sequence();
                }

                try {
                    outboxStore.markAppended(eventId, envelope.sequence());
                }
                catch (RuntimeException failure) {
                    throw channel.block(eventId, failure);
                }
            }
            subscriptions = finishPublication(task, channel, envelope);
        }
        subscriptions.forEach(EventSubscription::drain);
        return envelope;
    }

    @Override
    public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        validateCursor(afterSequence, limit);
        requireTask(taskId);
        TaskChannel channel = channel(taskId);
        synchronized (channel) {
            channel.checkUsable();
            return logStore.readAfter(taskId, afterSequence, limit);
        }
    }

    @Override
    public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
    {
        requireNonNull(taskId, "taskId is null");
        if (afterSequence < 0) {
            throw new InvalidAgentRequestException("afterSequence must be non-negative");
        }
        requireTask(taskId);
        return subscriber -> {
            requireNonNull(subscriber, "subscriber is null");
            AgentTask task = requireTask(taskId);
            TaskChannel channel = channel(taskId);
            EventSubscription subscription;
            synchronized (channel) {
                channel.checkUsable();
                channel.checkReadyToSubscribe();
                List<AgentEventEnvelope> replay = readReplay(taskId, afterSequence);
                boolean terminal = channel.terminal || shouldComplete(task, channel);
                channel.terminal |= terminal;
                subscription = new EventSubscription(subscriber, channel, replay, terminal);
                channel.subscriptions.add(subscription);
            }
            subscriber.onSubscribe(subscription);
            subscription.drain();
        };
    }

    private TaskChannel channel(TaskId taskId)
    {
        return channels.computeIfAbsent(taskId, id -> new TaskChannel(
                logStore.lastSequence(id),
                containsTaskTerminalEvent(id)));
    }

    private AgentTask requireTask(TaskId taskId)
    {
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
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

    private static boolean isTaskTerminalEvent(AgentEventEnvelope envelope)
    {
        if (envelope.turnId().isPresent() || !(envelope.payload() instanceof AgentEventPayload.Lifecycle lifecycle)) {
            return false;
        }
        return lifecycle.phase() == AgentEventPayload.LifecyclePhase.TASK_COMPLETED
                || lifecycle.phase() == AgentEventPayload.LifecyclePhase.TASK_FAILED;
    }

    private List<AgentEventEnvelope> readReplay(TaskId taskId, long afterSequence)
    {
        ImmutableList.Builder<AgentEventEnvelope> replay = ImmutableList.builder();
        long cursor = afterSequence;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, cursor, REPLAY_PAGE_SIZE);
            replay.addAll(page);
            if (page.size() < REPLAY_PAGE_SIZE) {
                return replay.build();
            }
            cursor = page.getLast().sequence();
        }
    }

    private boolean containsTaskTerminalEvent(TaskId taskId)
    {
        long cursor = 0;
        while (true) {
            List<AgentEventEnvelope> page = logStore.readAfter(taskId, cursor, REPLAY_PAGE_SIZE);
            if (page.stream().anyMatch(DefaultAgentEventService::isTaskTerminalEvent)) {
                return true;
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                return false;
            }
            cursor = page.getLast().sequence();
        }
    }

    private boolean shouldComplete(AgentTask task, TaskChannel channel)
    {
        return task.state().terminal()
                && channel.terminalEventPersisted
                && !outboxStore.hasPending(task.id());
    }

    private List<EventSubscription> finishPublication(
            AgentTask task,
            TaskChannel channel,
            AgentEventEnvelope envelope)
    {
        channel.observePersisted(envelope.sequence());
        if (!channel.needsPublication(envelope.sequence())) {
            return ImmutableList.of();
        }
        channel.terminalEventPersisted |= isTaskTerminalEvent(envelope);
        boolean terminal = shouldComplete(task, channel);
        List<EventSubscription> subscriptions = ImmutableList.copyOf(channel.subscriptions);
        subscriptions.forEach(subscription -> subscription.enqueue(envelope, terminal));
        channel.markPublished(envelope.sequence());
        channel.terminal |= terminal;
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
        private long lastSequence;
        private long lastPublishedSequence;
        private AgentStorageException blockedFailure;
        private boolean terminalEventPersisted;
        private boolean terminal;

        private TaskChannel(long lastSequence, boolean terminalEventPersisted)
        {
            this.lastSequence = lastSequence;
            this.lastPublishedSequence = lastSequence;
            this.terminalEventPersisted = terminalEventPersisted;
        }

        private synchronized void remove(EventSubscription subscription)
        {
            subscriptions.remove(subscription);
        }

        private void checkUsable()
        {
            if (blockedFailure != null) {
                throw blockedFailure;
            }
        }

        private AgentStorageException block(EventId eventId, RuntimeException failure)
        {
            blockedFailure = new AgentStorageException(
                    "Task event channel is blocked after an uncertain outbox mark: " + eventId,
                    failure);
            return blockedFailure;
        }

        private void checkReadyToPersist()
        {
            if (lastPublishedSequence != lastSequence) {
                throw new AgentStorageException("Cannot persist a later task event before sequence "
                        + (lastPublishedSequence + 1) + " is published");
            }
        }

        private void checkReadyToSubscribe()
        {
            if (lastPublishedSequence != lastSequence) {
                throw new AgentStorageException("Cannot subscribe while task event sequence "
                        + (lastPublishedSequence + 1) + " awaits publication");
            }
        }

        private void observePersisted(long sequence)
        {
            if (sequence > lastSequence) {
                if (sequence != lastSequence + 1) {
                    throw new AgentStorageException("Task event publication sequence has a gap");
                }
                lastSequence = sequence;
            }
        }

        private boolean needsPublication(long sequence)
        {
            if (sequence <= lastPublishedSequence) {
                return false;
            }
            if (sequence != lastPublishedSequence + 1) {
                throw new AgentStorageException("Cannot publish task event sequence " + sequence
                        + " before sequence " + (lastPublishedSequence + 1));
            }
            return true;
        }

        private void markPublished(long sequence)
        {
            lastPublishedSequence = sequence;
        }
    }

    private static final class EventSubscription
            implements Flow.Subscription
    {
        private final Flow.Subscriber<? super AgentEventEnvelope> subscriber;
        private final TaskChannel channel;
        private final ArrayDeque<AgentEventEnvelope> pending;
        private long demand;
        private boolean terminal;
        private boolean cancelled;
        private boolean draining;

        private EventSubscription(
                Flow.Subscriber<? super AgentEventEnvelope> subscriber,
                TaskChannel channel,
                List<AgentEventEnvelope> replay,
                boolean terminal)
        {
            this.subscriber = subscriber;
            this.channel = channel;
            this.pending = new ArrayDeque<>(replay);
            this.terminal = terminal;
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

        private synchronized void enqueue(AgentEventEnvelope envelope, boolean completeAfterEvent)
        {
            if (!cancelled) {
                pending.add(envelope);
                terminal |= completeAfterEvent;
            }
        }

        private void drain()
        {
            synchronized (this) {
                if (draining || cancelled) {
                    return;
                }
                draining = true;
            }
            while (true) {
                AgentEventEnvelope next;
                boolean complete;
                synchronized (this) {
                    if (cancelled) {
                        draining = false;
                        return;
                    }
                    if (demand > 0 && !pending.isEmpty()) {
                        demand--;
                        next = pending.remove();
                        complete = false;
                    }
                    else {
                        next = null;
                        complete = terminal && pending.isEmpty();
                        draining = false;
                        if (complete) {
                            cancelled = true;
                        }
                    }
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
