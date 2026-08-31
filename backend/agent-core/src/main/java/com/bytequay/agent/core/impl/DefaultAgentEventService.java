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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.google.common.collect.ImmutableList;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentEventService
        implements AgentEventService
{
    private static final int REPLAY_PAGE_SIZE = 1000;

    private final TaskLogStore logStore;
    private final AgentTaskStore taskStore;
    private final Clock clock;
    private final ConcurrentMap<TaskId, TaskChannel> channels = new ConcurrentHashMap<>();

    public DefaultAgentEventService(TaskLogStore logStore, AgentTaskStore taskStore, Clock clock)
    {
        this.logStore = requireNonNull(logStore, "logStore is null");
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
        boolean terminal;
        synchronized (channel) {
            envelope = new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    event.eventId(),
                    ++channel.lastSequence,
                    event.taskId(),
                    event.turnId(),
                    event.source(),
                    Instant.now(clock),
                    event.payload());
            try {
                logStore.append(envelope);
            }
            catch (RuntimeException e) {
                channel.lastSequence--;
                throw e;
            }
            subscriptions = ImmutableList.copyOf(channel.subscriptions);
            terminal = task.state().terminal();
            subscriptions.forEach(subscription -> subscription.enqueue(envelope, terminal));
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
        return logStore.readAfter(taskId, afterSequence, limit);
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
                List<AgentEventEnvelope> replay = readReplay(taskId, afterSequence);
                subscription = new EventSubscription(subscriber, channel, replay, task.state().terminal());
                channel.subscriptions.add(subscription);
            }
            subscriber.onSubscribe(subscription);
            subscription.drain();
        };
    }

    private TaskChannel channel(TaskId taskId)
    {
        return channels.computeIfAbsent(taskId, id -> new TaskChannel(logStore.lastSequence(id)));
    }

    private AgentTask requireTask(TaskId taskId)
    {
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
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

        private TaskChannel(long lastSequence)
        {
            this.lastSequence = lastSequence;
        }

        private synchronized void remove(EventSubscription subscription)
        {
            subscriptions.remove(subscription);
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
