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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentEventService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private RecordingTaskLogStore logStore;
    private InMemoryTaskStore taskStore;
    private DefaultAgentEventService service;
    private AgentTask task;

    @BeforeEach
    void setUp()
    {
        logStore = new RecordingTaskLogStore();
        taskStore = new InMemoryTaskStore();
        service = new DefaultAgentEventService(logStore, taskStore, Clock.fixed(NOW, ZoneOffset.UTC));
        task = AgentTask.created(
                TaskId.random(),
                "Task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/worktree"),
                AgentPermissionPolicy.AUTO,
                NOW);
        taskStore.insert(task);
    }

    @Test
    void persistsBeforePublishingAndRecoversSequence()
    {
        TestSubscriber subscriber = new TestSubscriber(logStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(1);

        AgentEventEnvelope first = service.append(event("first"));
        DefaultAgentEventService restarted = new DefaultAgentEventService(
                logStore,
                taskStore,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        AgentEventEnvelope second = restarted.append(event("second"));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(subscriber.events()).containsExactly(first);
        assertThat(subscriber.observedUnpersistedEvent()).isFalse();
    }

    @Test
    void providesGapFreeReplayAndLiveEventsWithBackpressure()
    {
        AgentEventEnvelope replay = service.append(event("replay"));
        TestSubscriber subscriber = new TestSubscriber(logStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        AgentEventEnvelope live = service.append(event("live"));

        assertThat(subscriber.events()).isEmpty();
        subscriber.request(1);
        assertThat(subscriber.events()).containsExactly(replay);
        subscriber.request(1);
        assertThat(subscriber.events()).containsExactly(replay, live);
    }

    @Test
    void closesSubscriberAfterTerminalTaskEventIsDrained()
    {
        TestSubscriber subscriber = new TestSubscriber(logStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        taskStore.put(task.ready(NOW.plusSeconds(1)).fail(
                "failed",
                NOW.plusSeconds(2),
                NOW.plusSeconds(3)));

        AgentEventEnvelope terminal = service.append(event("terminal"));
        subscriber.request(Long.MAX_VALUE);

        assertThat(subscriber.events()).containsExactly(terminal);
        assertThat(subscriber.completed()).isTrue();
    }

    @Test
    void doesNotConsumeSequenceWhenPersistenceFails()
    {
        logStore.failNextAppend = true;
        assertThatThrownBy(() -> service.append(event("failed")))
                .isInstanceOf(AgentStorageException.class);

        assertThat(service.append(event("persisted")).sequence()).isEqualTo(1);
        assertThatThrownBy(() -> service.readAfter(task.id(), -1, 1))
                .isInstanceOf(InvalidAgentRequestException.class);
    }

    private UnsequencedAgentEvent event(String text)
    {
        return new UnsequencedAgentEvent(task.id(), Optional.empty(), new ProviderAgentEvent.Message(
                Optional.empty(),
                Optional.empty(),
                ProviderAgentEvent.MessagePhase.FINAL,
                text,
                Optional.empty()));
    }

    private static final class TestSubscriber
            implements Flow.Subscriber<AgentEventEnvelope>
    {
        private final RecordingTaskLogStore logStore;
        private final List<AgentEventEnvelope> events = new ArrayList<>();
        private Flow.Subscription subscription;
        private boolean observedUnpersistedEvent;
        private boolean completed;

        private TestSubscriber(RecordingTaskLogStore logStore)
        {
            this.logStore = logStore;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            this.subscription = subscription;
        }

        @Override
        public void onNext(AgentEventEnvelope item)
        {
            observedUnpersistedEvent |= !logStore.events.contains(item);
            events.add(item);
        }

        @Override
        public void onError(Throwable failure)
        {
            throw new AssertionError(failure);
        }

        @Override
        public void onComplete()
        {
            completed = true;
        }

        public void request(long count)
        {
            subscription.request(count);
        }

        public List<AgentEventEnvelope> events()
        {
            return ImmutableList.copyOf(events);
        }

        public boolean observedUnpersistedEvent()
        {
            return observedUnpersistedEvent;
        }

        public boolean completed()
        {
            return completed;
        }
    }

    private static final class RecordingTaskLogStore
            implements TaskLogStore
    {
        private final List<AgentEventEnvelope> events = new ArrayList<>();
        private boolean failNextAppend;

        @Override
        public void append(AgentEventEnvelope envelope)
        {
            if (failNextAppend) {
                failNextAppend = false;
                throw new AgentStorageException("append failed");
            }
            events.add(envelope);
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return events.stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .collect(ImmutableList.toImmutableList());
        }

        @Override
        public long lastSequence(TaskId taskId)
        {
            return events.stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .mapToLong(AgentEventEnvelope::sequence)
                    .max()
                    .orElse(0);
        }

        @Override
        public BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryTaskStore
            implements AgentTaskStore
    {
        private final Map<TaskId, AgentTask> tasks = new HashMap<>();

        @Override
        public void insert(AgentTask task)
        {
            tasks.put(task.id(), task);
        }

        @Override
        public Optional<AgentTask> find(TaskId taskId)
        {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public AgentTask update(AgentTask task)
        {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public List<AgentTask> listRecent(int limit)
        {
            return ImmutableList.copyOf(tasks.values());
        }

        @Override
        public List<AgentTask> listLive()
        {
            return tasks.values().stream()
                    .filter(task -> !task.state().terminal())
                    .collect(ImmutableList.toImmutableList());
        }

        @Override
        public List<AgentTask> listCleanupDue(Instant now, int limit)
        {
            return ImmutableList.of();
        }

        public void put(AgentTask task)
        {
            tasks.put(task.id(), task);
        }
    }
}
