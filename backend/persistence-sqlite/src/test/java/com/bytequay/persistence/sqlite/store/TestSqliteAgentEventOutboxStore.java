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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.repository.AgentEventOutboxJpaRepository;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestSqliteAgentEventOutboxStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-event-outbox-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private AgentUnitOfWork unitOfWork;

    @Autowired
    private AgentEventOutboxStore store;

    @Autowired
    private AgentTaskStore tasks;

    @Autowired
    private AgentTurnStore turns;

    @Autowired
    private AgentEventOutboxJpaRepository repository;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @BeforeEach
    void clearOutbox()
    {
        repository.deleteAll();
    }

    @Test
    void commitsAllOutboxWritesInOneUnitOfWork()
    {
        AgentEventOutboxEntry first = inlineEntry(EventId.random());
        AgentEventOutboxEntry second = blobEntry(EventId.random());

        String result = unitOfWork.execute(() -> {
            store.insert(first);
            store.insert(second);
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(store.listPending(10))
                .extracting(AgentEventOutboxEntry::eventId)
                .containsExactly(first.eventId(), second.eventId());
        assertThat(store.find(second.eventId())).contains(second);
    }

    @Test
    void rollsBackAllOutboxWritesWhenWorkFails()
    {
        AgentEventOutboxEntry first = inlineEntry(EventId.random());
        AgentEventOutboxEntry second = inlineEntry(EventId.random());

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            store.insert(first);
            store.insert(second);
            throw new IllegalStateException("failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");

        assertThat(store.find(first.eventId())).isEmpty();
        assertThat(store.find(second.eventId())).isEmpty();
    }

    @Test
    void rollsBackTaskStateAndItsOutboxEventTogether()
    {
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Transactional task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-transactional-task"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW);
        AgentEventOutboxEntry event = new AgentEventOutboxEntry(
                EventId.random(),
                taskId,
                Optional.empty(),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.SESSION_OPENED,
                        "Task created")),
                NOW,
                Optional.empty());

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            tasks.insert(task);
            store.insert(event);
            throw new IllegalStateException("failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");

        assertThat(tasks.find(taskId)).isEmpty();
        assertThat(store.find(event.eventId())).isEmpty();
    }

    @Test
    void rollsBackAcceptedTurnAndItsUserMessageTogether()
    {
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Transactional turn task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-transactional-turn"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW);
        tasks.insert(task);
        TurnId turnId = TurnId.random();
        AgentTurn turn = AgentTurn.accepted(
                turnId,
                taskId,
                "request-1",
                new AgentTurnSettings(
                        Optional.empty(),
                        Optional.empty(),
                        AgentPermissionPolicy.AUTO,
                        AgentSandboxPolicy.WORKSPACE_WRITE,
                        Duration.ofMinutes(5),
                        ImmutableList.of()),
                NOW);
        AgentEventOutboxEntry event = new AgentEventOutboxEntry(
                EventId.random(),
                taskId,
                Optional.of(turnId),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.UserMessageBlob(
                        new BlobRef("blobs/message.txt", "text/plain", 7),
                        ImmutableList.of(),
                        AgentEventPayload.DeliveryKind.TURN),
                NOW,
                Optional.empty());

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            turns.insert(turn);
            store.insert(event);
            throw new IllegalStateException("failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");

        assertThat(turns.find(turnId)).isEmpty();
        assertThat(store.find(event.eventId())).isEmpty();
    }

    @Test
    void listsPendingEntriesInInsertionOrder()
    {
        AgentEventOutboxEntry first = inlineEntry(EventId.random());
        AgentEventOutboxEntry second = blobEntry(EventId.random());
        AgentEventOutboxEntry third = inlineEntry(EventId.random());
        store.insert(first);
        store.insert(second);
        store.insert(third);

        assertThat(store.listPending(2))
                .extracting(AgentEventOutboxEntry::eventId)
                .containsExactly(first.eventId(), second.eventId());
        assertThat(store.listPending(second.taskId(), 2))
                .extracting(AgentEventOutboxEntry::eventId)
                .containsExactly(second.eventId());
    }

    @Test
    void rejectsDuplicateEventIdentity()
    {
        EventId eventId = EventId.random();
        store.insert(inlineEntry(eventId));

        assertThatThrownBy(() -> store.insert(blobEntry(eventId)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining(eventId.toString());
    }

    @Test
    void marksAnEntryIdempotently()
    {
        AgentEventOutboxEntry entry = inlineEntry(EventId.random());
        store.insert(entry);

        assertThat(store.hasPending(entry.taskId())).isTrue();

        store.markAppended(entry.eventId(), 17);
        store.markAppended(entry.eventId(), 17);

        assertThat(store.listPending(10)).isEmpty();
        assertThat(store.hasPending(entry.taskId())).isFalse();
        assertThat(store.find(entry.eventId()))
                .get()
                .extracting(AgentEventOutboxEntry::sequence)
                .isEqualTo(Optional.of(17L));
        assertThatThrownBy(() -> store.markAppended(entry.eventId(), 18))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void rejectsUndrainableBlobAndRollsBackTheTaskMutation()
    {
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Oversized event task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-oversized-event-task"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW);
        AgentEventOutboxEntry event = new AgentEventOutboxEntry(
                EventId.random(),
                taskId,
                Optional.empty(),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.UserMessageBlob(
                        new BlobRef(
                                "blobs/events/oversized-message.txt",
                                "text/plain",
                                AgentEventEnvelope.MAX_SERIALIZED_BYTES),
                        ImmutableList.of(),
                        AgentEventPayload.DeliveryKind.TURN),
                NOW,
                Optional.empty());

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            tasks.insert(task);
            store.insert(event);
            return null;
        }))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("task-log size limit");

        assertThat(tasks.find(taskId)).isEmpty();
        assertThat(store.find(event.eventId())).isEmpty();
    }

    private static AgentEventOutboxEntry inlineEntry(EventId eventId)
    {
        return new AgentEventOutboxEntry(
                eventId,
                TaskId.random(),
                Optional.of(TurnId.random()),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.Lifecycle(
                        AgentEventPayload.LifecyclePhase.TURN_STARTED,
                        "Turn started")),
                NOW,
                Optional.empty());
    }

    private static AgentEventOutboxEntry blobEntry(EventId eventId)
    {
        return new AgentEventOutboxEntry(
                eventId,
                TaskId.random(),
                Optional.empty(),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.UserMessageBlob(
                        new BlobRef("blobs/events/message.txt", "text/plain", 12),
                        ImmutableList.of(new AgentAttachmentReference("attachment-1", "image.png", "image/png")),
                        AgentEventPayload.DeliveryKind.QUEUED),
                NOW,
                Optional.empty());
    }
}
