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

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.repository.AgentApprovalJpaRepository;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestSqliteAgentApprovalStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-agent-approval-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private AgentApprovalStore approvals;

    @Autowired
    private AgentEventOutboxStore outbox;

    @Autowired
    private AgentTaskStore tasks;

    @Autowired
    private AgentUnitOfWork unitOfWork;

    @Autowired
    private AgentApprovalJpaRepository repository;

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
    void clearApprovals()
    {
        repository.deleteAll();
    }

    @Test
    void roundTripsRequestAndResolution()
    {
        AgentApproval pending = approval(TurnId.random(), NOW, ApprovalId.random());
        approvals.insert(pending);

        assertThat(approvals.find(pending.request().id())).contains(pending);

        ApprovalDecision decision = decision(pending.request().id(), ApprovalDecision.Decision.ALLOW, NOW.plusSeconds(1));
        assertThat(approvals.resolve(pending.request().id(), decision, AgentEventPayload.ApprovalOutcome.ALLOWED)).isTrue();
        assertThat(approvals.find(pending.request().id()))
                .contains(pending.resolve(decision, AgentEventPayload.ApprovalOutcome.ALLOWED));
    }

    @Test
    void listsOnlyPendingApprovalsInStableOrder()
    {
        TurnId turnId = TurnId.random();
        AgentApproval second = approval(turnId, NOW.plusSeconds(2), ApprovalId.random());
        AgentApproval first = approval(turnId, NOW.plusSeconds(1), ApprovalId.random());
        AgentApproval resolved = approval(turnId, NOW, ApprovalId.random());
        AgentApproval otherTurn = approval(TurnId.random(), NOW, ApprovalId.random());
        approvals.insert(second);
        approvals.insert(first);
        approvals.insert(resolved);
        approvals.insert(otherTurn);
        ApprovalDecision denial = decision(resolved.request().id(), ApprovalDecision.Decision.DENY, NOW.plusSeconds(3));
        approvals.resolve(resolved.request().id(), denial, AgentEventPayload.ApprovalOutcome.DENIED);

        assertThat(approvals.listPending(turnId))
                .extracting(approval -> approval.request().id())
                .containsExactly(first.request().id(), second.request().id());
        assertThat(approvals.listPending())
                .extracting(approval -> approval.request().id())
                .containsExactlyInAnyOrder(first.request().id(), second.request().id(), otherTurn.request().id());
    }

    @Test
    void onlyFirstResolutionWins()
    {
        AgentApproval pending = approval(TurnId.random(), NOW, ApprovalId.random());
        approvals.insert(pending);
        ApprovalDecision allowed = decision(pending.request().id(), ApprovalDecision.Decision.ALLOW, NOW.plusSeconds(1));
        ApprovalDecision denied = decision(pending.request().id(), ApprovalDecision.Decision.DENY, NOW.plusSeconds(2));

        assertThat(approvals.resolve(pending.request().id(), allowed, AgentEventPayload.ApprovalOutcome.ALLOWED)).isTrue();
        assertThat(approvals.resolve(pending.request().id(), allowed, AgentEventPayload.ApprovalOutcome.ALLOWED)).isFalse();
        assertThat(approvals.resolve(pending.request().id(), denied, AgentEventPayload.ApprovalOutcome.DENIED)).isFalse();
        assertThat(approvals.find(pending.request().id()).orElseThrow().decision()).contains(allowed);
    }

    @Test
    void rollsBackApprovalAndOutboxTogether()
    {
        AgentApproval pending = approval(TurnId.random(), NOW, ApprovalId.random());
        AgentTask task = task(pending.request().taskId());
        tasks.insert(task);
        AgentEventOutboxEntry event = requestEvent(pending, EventId.random());

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            approvals.insert(pending);
            outbox.insert(event);
            throw new IllegalStateException("failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");

        assertThat(approvals.find(pending.request().id())).isEmpty();
        assertThat(outbox.find(event.eventId())).isEmpty();
    }

    @Test
    void rollsBackResolutionWhenOutboxInsertFails()
    {
        AgentApproval pending = approval(TurnId.random(), NOW, ApprovalId.random());
        tasks.insert(task(pending.request().taskId()));
        approvals.insert(pending);
        EventId eventId = EventId.random();
        outbox.insert(requestEvent(pending, eventId));
        ApprovalDecision decision = decision(pending.request().id(), ApprovalDecision.Decision.ALLOW, NOW.plusSeconds(1));

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            assertThat(approvals.resolve(pending.request().id(), decision, AgentEventPayload.ApprovalOutcome.ALLOWED)).isTrue();
            outbox.insert(requestEvent(pending, eventId));
            return null;
        }))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining(eventId.toString());

        assertThat(approvals.find(pending.request().id())).contains(pending);
    }

    private static AgentApproval approval(TurnId turnId, Instant requestedAt, ApprovalId approvalId)
    {
        return AgentApproval.pending(new ApprovalRequest(
                approvalId,
                TaskId.random(),
                turnId,
                new ProviderApprovalRequest(
                        "provider-" + approvalId,
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        ImmutableList.of(ALLOW_ONCE, DENY),
                        new ProviderAgentEventSource(
                                Optional.of("thread-1"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("turn-1"),
                                Optional.of("item-1")),
                        requestedAt),
                requestedAt));
    }

    private static ApprovalDecision decision(ApprovalId approvalId, ApprovalDecision.Decision decision, Instant resolvedAt)
    {
        // Every allow here covers the task, so the round trip exercises the flag.
        return new ApprovalDecision(approvalId, decision, Optional.empty(), decision == ApprovalDecision.Decision.ALLOW, resolvedAt, false);
    }

    private static AgentEventOutboxEntry requestEvent(AgentApproval approval, EventId eventId)
    {
        return new AgentEventOutboxEntry(
                eventId,
                approval.request().taskId(),
                Optional.of(approval.request().turnId()),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.ApprovalRequested(
                        approval.request().id().toString(),
                        AgentEventPayload.ApprovalKind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("run: git status"),
                        true,
                        ImmutableList.of(AgentEventPayload.ApprovalOption.ALLOW_ONCE, AgentEventPayload.ApprovalOption.DENY))),
                NOW,
                Optional.empty());
    }

    private static AgentTask task(TaskId taskId)
    {
        return AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Approval task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-approval-" + taskId),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW);
    }
}
