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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentEventService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private RecordingTaskLogStore logStore;
    private RecordingEventOutboxStore outboxStore;
    private InMemoryTaskStore taskStore;
    private DefaultAgentEventService service;
    private AgentTask task;

    @BeforeEach
    void setUp()
    {
        logStore = new RecordingTaskLogStore();
        outboxStore = new RecordingEventOutboxStore();
        taskStore = new InMemoryTaskStore();
        service = new DefaultAgentEventService(logStore, outboxStore, taskStore, Clock.fixed(NOW, ZoneOffset.UTC));
        task = AgentTask.created(
                TaskId.random(),
                Optional.empty(),
                false,
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
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(1);

        UnsequencedAgentEvent firstEvent = event("first");
        AgentEventEnvelope first = service.append(firstEvent);
        DefaultAgentEventService restarted = new DefaultAgentEventService(
                logStore,
                outboxStore,
                taskStore,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        AgentEventEnvelope second = restarted.append(event("second"));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.eventId()).isEqualTo(firstEvent.eventId());
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(subscriber.events()).containsExactly(first);
        assertThat(subscriber.observedUnpersistedEvent()).isFalse();
    }

    @Test
    void providesGapFreeReplayAndLiveEventsWithBackpressure()
    {
        AgentEventEnvelope replay = service.append(event("replay"));
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        AgentEventEnvelope live = service.append(event("live"));

        assertThat(subscriber.events()).isEmpty();
        subscriber.request(1);
        assertThat(subscriber.events()).containsExactly(replay);
        subscriber.request(1);
        assertThat(subscriber.events()).containsExactly(replay, live);
    }

    @Test
    void waitsForTerminalOutboxBeforeClosingSubscriber()
    {
        AgentEventOutboxEntry terminalEntry = outbox(new AgentEventOutboxPayload.Inline(
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TASK_FAILED,
                        "Task failed")));
        outboxStore.insert(terminalEntry);
        taskStore.put(failedTask());

        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);

        assertThat(subscriber.events()).isEmpty();
        assertThat(subscriber.completed()).isFalse();

        AgentEventEnvelope terminal = service.drain(terminalEntry.eventId());

        assertThat(subscriber.events()).containsExactly(terminal);
        assertThat(subscriber.completed()).isTrue();
    }

    @Test
    void closesLegacyTerminalTaskWhenNoOutboxIsPending()
    {
        AgentEventEnvelope replay = service.append(event("legacy result"));
        taskStore.put(failedTask());

        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);

        assertThat(subscriber.events()).containsExactly(replay);
        assertThat(subscriber.completed()).isTrue();
    }

    @Test
    void closesAfterRemainingOutboxDrainsWhenTerminalEventWasAlreadyPersisted()
    {
        AgentEventOutboxEntry earlier = inlineOutbox("earlier");
        AgentEventOutboxEntry terminal = outbox(new AgentEventOutboxPayload.Inline(
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TASK_FAILED,
                        "Task failed")));
        outboxStore.insert(earlier);
        outboxStore.insert(terminal);
        taskStore.put(failedTask());

        AgentEventEnvelope persistedTerminal = service.drain(terminal.eventId());
        DefaultAgentEventService restarted = new DefaultAgentEventService(
                logStore,
                outboxStore,
                taskStore,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        restarted.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);

        assertThat(subscriber.events()).containsExactly(persistedTerminal);
        assertThat(subscriber.completed()).isFalse();

        AgentEventEnvelope persistedEarlier = restarted.drain(earlier.eventId());

        assertThat(subscriber.events()).containsExactly(persistedTerminal, persistedEarlier);
        assertThat(subscriber.completed()).isTrue();
    }

    @Test
    void appendsTheSameEventIdentityOnlyOnce()
    {
        UnsequencedAgentEvent event = event("same");

        AgentEventEnvelope first = service.append(event);
        AgentEventEnvelope repeated = service.append(event);

        assertThat(repeated).isEqualTo(first);
        assertThat(logStore.events).containsExactly(first);
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

    @Test
    void drainsOutboxInOrderAndMaterializesBlobBeforePublishing()
    {
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);

        AgentEventOutboxEntry first = inlineOutbox("first");
        BlobRef message = logStore.writeBlob(
                task.id(),
                ByteBuffer.wrap("second".getBytes(StandardCharsets.UTF_8)),
                "text/plain; charset=utf-8");
        AgentEventOutboxEntry second = outbox(new AgentEventOutboxPayload.UserMessageBlob(
                message,
                ImmutableList.of(),
                AgentEventPayload.DeliveryKind.TURN));
        outboxStore.insert(first);
        outboxStore.insert(second);

        AgentEventEnvelope firstEnvelope = service.drain(first.eventId());
        AgentEventEnvelope secondEnvelope = service.drain(second.eventId());

        assertThat(firstEnvelope.sequence()).isEqualTo(1);
        assertThat(secondEnvelope.sequence()).isEqualTo(2);
        assertThat(secondEnvelope.occurredAt()).isEqualTo(second.occurredAt());
        assertThat(secondEnvelope.payload()).isEqualTo(new AgentEventPayload.UserMessage(
                "second",
                ImmutableList.of(),
                AgentEventPayload.DeliveryKind.TURN));
        assertThat(subscriber.events()).containsExactly(firstEnvelope, secondEnvelope);
        assertThat(subscriber.observedUnpersistedEvent()).isFalse();
        assertThat(subscriber.observedUnmarkedOutboxEvent()).isFalse();
        assertThat(outboxStore.find(first.eventId()).orElseThrow().sequence()).contains(1L);
        assertThat(outboxStore.find(second.eventId()).orElseThrow().sequence()).contains(2L);
    }

    @Test
    void recoversFlushBeforeMarkAndDoesNotRepublishSuccessfulDrain()
    {
        AgentEventOutboxEntry entry = inlineOutbox("recovered");
        AgentEventPayload payload = ((AgentEventOutboxPayload.Inline) entry.payload()).payload();
        AgentEventEnvelope persisted = new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                entry.eventId(),
                1,
                entry.taskId(),
                entry.turnId(),
                entry.source(),
                entry.occurredAt(),
                payload);
        logStore.append(persisted);
        outboxStore.insert(entry);

        AgentEventEnvelope recovered = service.drain(entry.eventId());
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 1).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);
        AgentEventEnvelope repeated = service.drain(entry.eventId());

        assertThat(recovered).isEqualTo(persisted);
        assertThat(repeated).isEqualTo(persisted);
        assertThat(logStore.events).containsExactly(persisted);
        assertThat(outboxStore.find(entry.eventId()).orElseThrow().sequence()).contains(1L);
        assertThat(subscriber.events()).isEmpty();
    }

    @Test
    void blocksLaterTaskEventsWhenOutboxMarkIsUncertain()
    {
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);
        AgentEventOutboxEntry uncertain = inlineOutbox("uncertain");
        AgentEventOutboxEntry later = inlineOutbox("later");
        outboxStore.insert(uncertain);
        outboxStore.insert(later);
        outboxStore.failNextMark = true;

        assertThatThrownBy(() -> service.drain(uncertain.eventId()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("uncertain outbox mark");
        assertThatThrownBy(() -> service.drain(later.eventId()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("uncertain outbox mark");
        assertThatThrownBy(() -> service.append(event("display-only")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("uncertain outbox mark");
        assertThatThrownBy(() -> service.readAfter(task.id(), 0, 10))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("uncertain outbox mark");

        assertThat(logStore.events)
                .extracting(AgentEventEnvelope::eventId)
                .containsExactly(uncertain.eventId());
        assertThat(outboxStore.find(uncertain.eventId()).orElseThrow().sequence()).isEmpty();
        assertThat(outboxStore.find(later.eventId()).orElseThrow().sequence()).isEmpty();
        assertThat(subscriber.events()).isEmpty();

        DefaultAgentEventService restarted = new DefaultAgentEventService(
                logStore,
                outboxStore,
                taskStore,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        assertThat(restarted.drain(uncertain.eventId()).sequence()).isEqualTo(1);
        assertThat(logStore.events)
                .extracting(AgentEventEnvelope::eventId)
                .containsExactly(uncertain.eventId());
        assertThat(outboxStore.find(uncertain.eventId()).orElseThrow().sequence()).contains(1L);
    }

    @Test
    void retriesMarkedTerminalEventWhenCompletionCheckFails()
    {
        AgentEventOutboxEntry terminalEntry = outbox(new AgentEventOutboxPayload.Inline(
                new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TASK_FAILED,
                        "Task failed")));
        outboxStore.insert(terminalEntry);
        taskStore.put(failedTask());
        TestSubscriber subscriber = new TestSubscriber(logStore, outboxStore);
        service.subscribe(task.id(), 0).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);
        outboxStore.failNextPendingCheck = true;

        assertThatThrownBy(() -> service.drain(terminalEntry.eventId()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("pending check failed");

        assertThat(outboxStore.find(terminalEntry.eventId()).orElseThrow().sequence()).contains(1L);
        assertThat(subscriber.events()).isEmpty();
        assertThat(subscriber.completed()).isFalse();
        assertThatThrownBy(() -> service.append(event("later")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("before sequence 1 is published");
        assertThatThrownBy(() -> service.subscribe(task.id(), 0).subscribe(new TestSubscriber(logStore, outboxStore)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("awaits publication");

        AgentEventEnvelope terminal = service.drain(terminalEntry.eventId());
        AgentEventEnvelope repeated = service.drain(terminalEntry.eventId());

        assertThat(repeated).isEqualTo(terminal);
        assertThat(logStore.events).containsExactly(terminal);
        assertThat(subscriber.events()).containsExactly(terminal);
        assertThat(subscriber.completed()).isTrue();
    }

    @Test
    void isolatesPersistedEventsFromBrokenSubscriber()
    {
        service.subscribe(task.id(), 0).subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEventEnvelope item)
            {
                throw new IllegalStateException("subscriber failed");
            }

            @Override
            public void onError(Throwable failure) {}

            @Override
            public void onComplete() {}
        });

        assertThat(service.append(event("first")).sequence()).isEqualTo(1);
        assertThat(service.append(event("second")).sequence()).isEqualTo(2);
        assertThat(logStore.events).hasSize(2);
    }

    private UnsequencedAgentEvent event(String text)
    {
        return new UnsequencedAgentEvent(
                EventId.random(),
                task.id(),
                Optional.empty(),
                root(),
                new AgentEventPayload.AssistantMessage(
                        AgentEventPayload.MessageKind.ANSWER,
                        AgentEventPayload.MessagePhase.FINAL,
                        text));
    }

    private AgentEventOutboxEntry inlineOutbox(String text)
    {
        return outbox(new AgentEventOutboxPayload.Inline(new AgentEventPayload.AssistantMessage(
                AgentEventPayload.MessageKind.ANSWER,
                AgentEventPayload.MessagePhase.FINAL,
                text)));
    }

    private AgentEventOutboxEntry outbox(AgentEventOutboxPayload payload)
    {
        return new AgentEventOutboxEntry(
                EventId.random(),
                task.id(),
                Optional.empty(),
                root(),
                payload,
                NOW.plusSeconds(outboxStore.size() + 1L),
                Optional.empty());
    }

    private AgentTask failedTask()
    {
        return task.fail(
                "failed",
                NOW.plusSeconds(2),
                NOW.plusSeconds(3));
    }

    private static final class TestSubscriber
            implements Flow.Subscriber<AgentEventEnvelope>
    {
        private final RecordingTaskLogStore logStore;
        private final RecordingEventOutboxStore outboxStore;
        private final List<AgentEventEnvelope> events = new ArrayList<>();
        private Flow.Subscription subscription;
        private boolean observedUnpersistedEvent;
        private boolean observedUnmarkedOutboxEvent;
        private boolean completed;

        private TestSubscriber(RecordingTaskLogStore logStore, RecordingEventOutboxStore outboxStore)
        {
            this.logStore = logStore;
            this.outboxStore = outboxStore;
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
            observedUnmarkedOutboxEvent |= outboxStore.find(item.eventId()).stream()
                    .anyMatch(entry -> entry.sequence().isEmpty());
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

        public boolean observedUnmarkedOutboxEvent()
        {
            return observedUnmarkedOutboxEvent;
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
        private final Map<String, byte[]> blobs = new HashMap<>();
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
        public Optional<Long> findSequence(TaskId taskId, EventId eventId)
        {
            return events.stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .filter(event -> event.eventId().equals(eventId))
                    .map(AgentEventEnvelope::sequence)
                    .findFirst();
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return events.stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .collect(toImmutableList());
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
            ByteBuffer bytes = content.asReadOnlyBuffer();
            byte[] stored = new byte[bytes.remaining()];
            bytes.get(stored);
            String path = "blobs/" + blobs.size();
            blobs.put(taskId + ":" + path, stored);
            return new BlobRef(path, mediaType, stored.length);
        }

        @Override
        public ByteBuffer readBlob(TaskId taskId, BlobRef blobRef)
        {
            byte[] stored = blobs.get(taskId + ":" + blobRef.relativePath());
            if (stored == null || stored.length != blobRef.size()) {
                throw new AgentStorageException("blob is missing");
            }
            return ByteBuffer.wrap(stored).asReadOnlyBuffer();
        }
    }

    private static final class RecordingEventOutboxStore
            implements AgentEventOutboxStore
    {
        private final Map<EventId, AgentEventOutboxEntry> entries = new LinkedHashMap<>();
        private boolean failNextMark;
        private boolean failNextPendingCheck;

        @Override
        public void insert(AgentEventOutboxEntry entry)
        {
            if (entries.putIfAbsent(entry.eventId(), entry) != null) {
                throw new AgentStorageException("event already exists");
            }
        }

        @Override
        public Optional<AgentEventOutboxEntry> find(EventId eventId)
        {
            return Optional.ofNullable(entries.get(eventId));
        }

        @Override
        public List<AgentEventOutboxEntry> listPending(int limit)
        {
            return entries.values().stream()
                    .filter(entry -> entry.sequence().isEmpty())
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public List<AgentEventOutboxEntry> listPending(TaskId taskId, int limit)
        {
            return entries.values().stream()
                    .filter(entry -> entry.taskId().equals(taskId))
                    .filter(entry -> entry.sequence().isEmpty())
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public boolean hasPending(TaskId taskId)
        {
            if (failNextPendingCheck) {
                failNextPendingCheck = false;
                throw new AgentStorageException("pending check failed");
            }
            return entries.values().stream()
                    .anyMatch(entry -> entry.taskId().equals(taskId) && entry.sequence().isEmpty());
        }

        @Override
        public void markAppended(EventId eventId, long sequence)
        {
            if (failNextMark) {
                failNextMark = false;
                throw new AgentStorageException("mark failed");
            }
            AgentEventOutboxEntry entry = find(eventId)
                    .orElseThrow(() -> new AgentStorageException("event is missing"));
            if (entry.sequence().isPresent()) {
                if (entry.sequence().orElseThrow() != sequence) {
                    throw new AgentStorageException("event sequence changed");
                }
                return;
            }
            entries.put(eventId, new AgentEventOutboxEntry(
                    entry.eventId(),
                    entry.taskId(),
                    entry.turnId(),
                    entry.source(),
                    entry.payload(),
                    entry.occurredAt(),
                    Optional.of(sequence)));
        }

        public int size()
        {
            return entries.size();
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
        public List<AgentTask> listRecentInteractive(String workspaceId, int limit)
        {
            return tasks.values().stream()
                    .filter(AgentTask::interactive)
                    .filter(task -> task.workspaceId().stream().anyMatch(workspaceId::equals))
                    .limit(limit)
                    .collect(toImmutableList());
        }

        @Override
        public boolean hasWorktreeReference(String workspaceId)
        {
            return tasks.values().stream()
                    .anyMatch(task -> task.taskWorktree().stream()
                            .anyMatch(worktree -> worktree.workspaceId().equals(workspaceId)));
        }

        @Override
        public List<AgentTask> listLive()
        {
            return tasks.values().stream()
                    .filter(task -> !task.state().terminal())
                    .collect(toImmutableList());
        }

        @Override
        public List<AgentTask> listPendingWorktreeCleanup(AgentPermissionPolicy permissionPolicy, Instant retryBefore)
        {
            return ImmutableList.of();
        }

        @Override
        public List<AgentTask> listCleanupDue(Instant now, Instant retryBefore, int limit)
        {
            return ImmutableList.of();
        }

        public void put(AgentTask task)
        {
            tasks.put(task.id(), task);
        }
    }
}
