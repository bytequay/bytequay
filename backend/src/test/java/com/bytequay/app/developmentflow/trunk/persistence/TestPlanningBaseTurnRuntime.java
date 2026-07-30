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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.persistence.SqliteThreadTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.trunk.PlanningBaseRefreshOperationHandler;
import com.bytequay.app.developmentflow.trunk.PlanningBaseTurnRuntime;
import com.bytequay.app.developmentflow.trunk.SqlitePlanningBaseTurnStore;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.ThreadTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.threads.MessageAttachments;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPlanningBaseTurnRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void refreshResultLaunchesOneTurnWithItsExactSnapshotAcrossRestart()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("planning.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("repo"));
        Path planning = Files.createDirectory(tempDir.resolve("planning"));
        Path image = Files.write(
                tempDir.resolve("planning-screenshot.png"), new byte[] {1, 2, 3});
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        SqlitePlanningBaseTurnStore planningStore =
                new SqlitePlanningBaseTurnStore(jdbc);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        when(repositories.resolve("workspace-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0,
                        repository.toString(), null, null)));
        ThreadTurnHandoff handoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        PlanningBaseTurnRuntime runtime = new PlanningBaseTurnRuntime(
                manager, trunkStore, planningStore, handoff,
                repositories, watched, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
        String attached = MessageAttachments.encode(
                json, "start the next task", List.of(image.toString()));
        PlanningBaseTurnRuntime.Request request = new PlanningBaseTurnRuntime.Request(
                "request-1", "user", "trunk-1", "workspace-1",
                "TRUNK_CONVERSATION", AgentTurnProviderSession.Transport.CLI,
                "codex", null, "gpt-5.6", "high", "trunk role",
                attached, MessageAttachments.encode(
                        json, "compile a plan", List.of(image.toString())));

        PlanningBaseTurnRuntime.Receipt first = runtime.request(request);
        Files.delete(repository);
        PlanningBaseTurnRuntime.Receipt duplicate = runtime.request(request);
        assertThat(first.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(duplicate.disposition()).isEqualTo(
                CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.turnId()).isEqualTo(first.turnId());
        assertThat(count(jdbc, "planning_base_refresh_operation")).isOne();
        assertThat(count(jdbc, "dispatch_ticket")).isOne();
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(count(jdbc, "thread_attachment")).isZero();
        assertThat(count(jdbc, "capacity_lease")).isZero();
        var pendingPrompt = new ThreadTurnProjection(jdbc, json)
                .history("trunk-1").getFirst();
        JsonNode pendingContent = json.readTree(pendingPrompt.contentJson());
        assertThat(pendingContent.path("text").asText())
                .isEqualTo("start the next task");
        assertThat(pendingContent.path("images").get(0).asText())
                .isEqualTo(image.toString());
        assertThat(jdbc.queryForObject("""
                SELECT launch_intent FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, String.class, first.operationId()))
                .doesNotContain("bq-img");

        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, first.operationId(), 1,
                null, null, null);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, first.operationId(), "trunk-1", repository.toString(),
                        planning.toString(), "refs/remotes/origin/main",
                        "0123456789abcdef", NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(snapshot), "{}", null);
        markResultPending(jdbc, first.dispatchTicketId(), raw);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE);
        DispatchTicket.DeliveryReceipt accepted =
                runtime.deliver(owner, fence, raw);
        assertThat(accepted.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(new ThreadTurnProjection(jdbc, json)
                .turns("trunk-1", 10))
                .singleElement()
                .satisfies(turn -> {
                    assertThat(turn.turnId()).isEqualTo(first.turnId());
                    assertThat(turn.status()).isEqualTo("REQUESTED");
                    assertThat(turn.finishedAt()).isNull();
                    assertThat(turn.error()).isNull();
                });

        markDelivered(jdbc, first.dispatchTicketId(), accepted);
        runtime.afterDeliveryCommitted(owner, fence, raw, accepted);
        runtime.afterDeliveryCommitted(owner, fence, raw, accepted);
        new PlanningBaseTurnRuntime(
                manager(dataSource, new V2TrunkStore(new JdbcTemplate(dataSource))),
                new V2TrunkStore(new JdbcTemplate(dataSource)),
                new SqlitePlanningBaseTurnStore(new JdbcTemplate(dataSource)),
                new ThreadTurnHandoff(
                        manager, json, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                        53123),
                repositories, watched, json,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                .recoverCommittedDeliveries(20);

        assertThat(count(jdbc, "thread_turn")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT id, turn_id, kind, content_ref, media_type, digest
                FROM thread_attachment
                """))
                .containsEntry("id", first.turnId() + ":attachment:00000001")
                .containsEntry("turn_id", first.turnId())
                .containsEntry("kind", "image")
                .containsEntry("content_ref", image.toString())
                .containsEntry("media_type", "image/png")
                .containsEntry("digest",
                        "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        assertThat(count(jdbc, "trunk_thread_turn_request_receipt")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT planning_operation_id, expected_base_sha, status
                FROM thread_turn
                """))
                .containsEntry("planning_operation_id", first.planningOperationId())
                .containsEntry("expected_base_sha", "0123456789abcdef")
                .containsEntry("status", "REQUESTED");
        JsonNode launch = json.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM thread_turn
                """, String.class));
        assertThat(launch.path("prompt").asText())
                .isEqualTo("compile a plan");
        assertThat(launch.path("images").get(0).path("path").asText())
                .isEqualTo(image.toString());
        assertThat(jdbc.queryForMap("""
                SELECT planning_base_sha, planning_repo_root
                FROM threads WHERE id = 'trunk-1'
                """))
                .containsEntry("planning_base_sha", "0123456789abcdef")
                .containsEntry("planning_repo_root", repository.toString());
        assertThat(new ThreadTurnProjection(
                new JdbcTemplate(dataSource), new ObjectMapper())
                .history("trunk-1"))
                .singleElement()
                .satisfies(launchedPrompt -> {
                    assertThat(launchedPrompt.id()).isEqualTo(pendingPrompt.id());
                    assertThat(launchedPrompt.seq()).isEqualTo(pendingPrompt.seq());
                    assertThat(launchedPrompt.seq()).isEqualTo(-1L);
                    assertThat(launchedPrompt.contentJson())
                            .isEqualTo(pendingPrompt.contentJson());
                });
    }

    @Test
    void nextCliTurnResumesItsExactTrunkSessionAndFreezesHistoryFallback()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("cli-resume.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("resume-repo"));
        Path planning = Files.createDirectory(tempDir.resolve("resume-planning"));
        Path image = Files.write(
                tempDir.resolve("resume-history.png"), new byte[] {1, 2, 3});
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt prior = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "prior-cli-turn", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "trunk role",
                        MessageAttachments.encode(
                                json, "first question", List.of(image.toString())),
                        MessageAttachments.encode(
                                json, "compiled first", List.of(image.toString()))));
        deliverSuccessfulRefresh(
                runtime, jdbc, prior, repository, planning, json);
        runtime.recoverCommittedDeliveries(20);
        String priorOperationId = jdbc.queryForObject("""
                SELECT operation_id FROM thread_turn WHERE id = ?
                """, String.class, prior.turnId());
        String priorTicketId = jdbc.queryForObject("""
                SELECT id FROM dispatch_ticket
                WHERE owner_kind = 'THREAD_TURN' AND owner_id = ?
                """, String.class, prior.turnId());
        new SqliteThreadTurnOperationStore(jdbc).tryStart(
                prior.turnId(), priorOperationId, NOW.plusSeconds(1));
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    tokens_in, tokens_out)
                VALUES ('prior-execution', ?, 1, 'codex', 'session-trunk-1',
                    'SUCCEEDED', ?, ?, 100, 40)
                """, priorTicketId, NOW.plusSeconds(1).toEpochMilli(),
                NOW.plusSeconds(2).toEpochMilli());
        DispatchTicket.OperationFence priorFence = new DispatchTicket.OperationFence(
                null, null, null, priorOperationId, 1,
                null, null, null);
        AgentTurnOperationHandler.RawResult priorPayload =
                new AgentTurnOperationHandler.RawResult(
                        1, prior.turnId(), DispatchTicket.OwnerKind.THREAD_TURN,
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", "session-trunk-1", "first answer",
                        100, 40, 0, 1L,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null, null, 100L, 40L);
        DispatchTicket.DispatchResult priorResult =
                new DispatchTicket.DispatchResult(
                        priorFence, DispatchTicket.Outcome.SUCCEEDED,
                        json.writeValueAsString(priorPayload), "{}", null);
        jdbc.update("""
                UPDATE agent_execution SET raw_result = ?
                WHERE id = 'prior-execution'
                """, json.writeValueAsString(priorResult));
        markResultPending(jdbc, priorTicketId, priorResult);
        DispatchTicket.DeliveryReceipt priorDelivery =
                new ThreadTurnResultDeliveryPort(
                        manager, new AgentTurnOwnerResultCodec(json), json,
                        Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC))
                        .deliver(
                                new DispatchTicket.OwnerReference(
                                        DispatchTicket.OwnerKind.THREAD_TURN,
                                        prior.turnId(), "THREAD_TURN_RESULT"),
                                priorFence, priorResult);
        markDelivered(jdbc, priorTicketId, priorDelivery);

        jdbc.update("""
                UPDATE agent_execution SET provider = 'claude'
                WHERE id = 'prior-execution'
                """);
        assertThat(new SqlitePlanningBaseTurnStore(jdbc)
                .latestSuccessfulCliSession(
                        "trunk-1", "codex", "gpt-5.6", planning.toString()))
                .isEmpty();
        jdbc.update("""
                UPDATE agent_execution SET provider = 'codex'
                WHERE id = 'prior-execution'
                """);

        PlanningBaseTurnRuntime.Receipt next = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "next-cli-turn", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "trunk role",
                        "second question", "compiled second"));
        deliverSuccessfulRefresh(
                runtime, jdbc, next, repository, planning, json);
        runtime.recoverCommittedDeliveries(20);

        JsonNode launch = json.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM thread_turn WHERE id = ?
                """, String.class, next.turnId()));
        assertThat(launch.path("resumeSessionId").asText())
                .isEqualTo("session-trunk-1");
        assertThat(launch.path("priorCumulativeInputTokens").asLong())
                .isEqualTo(100);
        assertThat(launch.path("priorCumulativeOutputTokens").asLong())
                .isEqualTo(40);
        assertThat(launch.path("prompt").asText()).isEqualTo("compiled second");
        assertThat(launch.path("fallbackPrompt").asText())
                .contains("first question", "first answer", "compiled second",
                        image.toString());
        assertThat(launch.path("images").get(0).path("path").asText())
                .isEqualTo(image.toString());
        assertThat(count(jdbc, "thread_attachment")).isOne();
    }

    @Test
    void userWaitContinuationResumesExactSessionWithoutANormalResultReceipt()
            throws Exception
    {
        SQLiteDataSource dataSource = database(
                tempDir.resolve("cli-user-wait-resume.db"), "265");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(
                tempDir.resolve("user-wait-repo"));
        Path planning = Files.createDirectory(
                tempDir.resolve("user-wait-planning"));
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt sourceRequest = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "user-wait-source", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "trunk role",
                        "source question", "source full prompt"));
        deliverSuccessfulRefresh(
                runtime, jdbc, sourceRequest, repository, planning, json);
        runtime.recoverCommittedDeliveries(20);
        String sourceTurnId = sourceRequest.turnId();
        String sourceOperationId = jdbc.queryForObject("""
                SELECT operation_id FROM thread_turn WHERE id = ?
                """, String.class, sourceTurnId);
        String sourceTicketId = jdbc.queryForObject("""
                SELECT id FROM dispatch_ticket
                WHERE owner_kind = 'THREAD_TURN' AND owner_id = ?
                """, String.class, sourceTurnId);
        new SqliteThreadTurnOperationStore(jdbc).tryStart(
                sourceTurnId, sourceOperationId, NOW.plusSeconds(1));
        jdbc.update("""
                INSERT INTO thread_question(
                    id, turn_id, call_id, prompt, state, created_at_ms)
                VALUES ('question-1', ?, 'call-1', 'Choose one', 'OPEN', ?)
                """, sourceTurnId, NOW.plusSeconds(1).toEpochMilli());
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms)
                VALUES ('user-wait-execution', ?, 1, 'codex',
                    'session-user-wait', 'SUCCEEDED', ?, ?)
                """, sourceTicketId, NOW.plusSeconds(1).toEpochMilli(),
                NOW.plusSeconds(2).toEpochMilli());
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('user-wait-execution', 0,
                    '{"type":"assistant","text":"partial analysis"}', ?)
                """, NOW.plusSeconds(1).toEpochMilli());
        DispatchTicket.OperationFence waitFence =
                new DispatchTicket.OperationFence(
                        null, null, null, sourceOperationId, 1,
                        null, null, null);
        AgentTurnOperationHandler.RawResult waitPayload =
                new AgentTurnOperationHandler.RawResult(
                        1, sourceTurnId, DispatchTicket.OwnerKind.THREAD_TURN,
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", "session-user-wait", "", 0, 0, 0, 1L,
                        AgentTurnOperationHandler.Disposition.USER_WAIT, null,
                        new AgentTurnOperationHandler.UserWaitRef(
                                "QUESTION", "question-1"));
        markResultPending(
                jdbc, sourceTicketId, new DispatchTicket.DispatchResult(
                        waitFence, DispatchTicket.Outcome.SUCCEEDED,
                        json.writeValueAsString(waitPayload), "{}", null));
        new V2UserWaitStore(jdbc).recordUserWait(
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.THREAD_TURN,
                        sourceTurnId, sourceOperationId),
                "QUESTION", "question-1", "payload-digest", "result-evidence",
                NOW.plusSeconds(2));
        markDelivered(
                jdbc, sourceTicketId,
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "{}"));
        SqlitePlanningBaseTurnStore planningStore =
                new SqlitePlanningBaseTurnStore(jdbc);
        assertThat(planningStore.userWaitSource(
                sourceTurnId, sourceOperationId, "trunk-1")).isEmpty();
        jdbc.update("""
                UPDATE thread_question
                SET state = 'ANSWERED', answer = 'approve', answer_revision = 1,
                    answered_at_ms = ?, answer_free_form = 'approve',
                    answer_actor = 'user', continuation_state = 'READY'
                WHERE id = 'question-1'
                """, NOW.plusSeconds(3).toEpochMilli());
        jdbc.update("""
                UPDATE agent_execution SET provider = 'claude'
                WHERE id = 'user-wait-execution'
                """);
        assertThat(planningStore.userWaitSource(
                sourceTurnId, sourceOperationId, "trunk-1"))
                .hasValueSatisfying(source -> assertThat(
                        source.providerSessionId()).isNull());
        jdbc.update("""
                UPDATE agent_execution SET provider = 'codex'
                WHERE id = 'user-wait-execution'
                """);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trunk_thread_turn_result_receipt
                WHERE turn_id = ?
                """, Integer.class, sourceTurnId)).isZero();

        PlanningBaseTurnRuntime.Receipt continuation = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "user-wait-continuation", "user", "trunk-1",
                        "workspace-1", "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "trunk role",
                        "User answered the question: approve",
                        "User answered the question: approve",
                        sourceTurnId, sourceOperationId));
        deliverSuccessfulRefresh(
                runtime, jdbc, continuation, repository, planning, json);
        runtime.recoverCommittedDeliveries(20);

        JsonNode launch = json.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM thread_turn WHERE id = ?
                """, String.class, continuation.turnId()));
        assertThat(launch.path("resumeSessionId").asText())
                .isEqualTo("session-user-wait");
        assertThat(launch.path("prompt").asText())
                .isEqualTo("User answered the question: approve");
        assertThat(launch.path("fallbackPrompt").asText())
                .contains("source full prompt", "partial analysis",
                        "User answered the question: approve");
        assertThat(jdbc.queryForObject("""
                SELECT json_extract(launch_intent, '$.continuationTurnId')
                FROM planning_base_refresh_operation WHERE operation_id = ?
                """, String.class, continuation.operationId()))
                .isEqualTo(sourceTurnId);
    }

    @Test
    void conversationAndSessionUseTrunkVersionWhenTimestampsTie()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("turn-order.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path planning = Files.createDirectory(tempDir.resolve("turn-order-worktree"));
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        Path repository = Files.createDirectory(tempDir.resolve("turn-order-repo"));
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        String firstTurnId = completePlannedCliTurn(
                runtime, jdbc, manager, repository, planning,
                "request-0", "first question",
                "first prompt", "first answer", "first-session",
                "first-execution", json);
        String secondTurnId = completePlannedCliTurn(
                runtime, jdbc, manager, repository, planning,
                "request-1", "second question",
                "second prompt", "second answer", "second-session",
                "second-execution", json);
        assertThat(secondTurnId).isLessThan(firstTurnId);

        SqlitePlanningBaseTurnStore store = new SqlitePlanningBaseTurnStore(jdbc);
        assertThat(store.conversation("trunk-1"))
                .extracting(SqlitePlanningBaseTurnStore.ConversationMessage::body)
                .containsExactly(
                        "first question", "first answer",
                        "second question", "second answer");
        assertThat(store.latestSuccessfulCliSession(
                "trunk-1", "codex", "gpt-5.6", planning.toString()))
                .get()
                .extracting(SqlitePlanningBaseTurnStore.CliSession::providerSessionId)
                .isEqualTo("second-session");
    }

    @Test
    void nonConversationTurnNeitherJoinsHistoryNorConsumesConversationSession()
            throws Exception
    {
        SQLiteDataSource dataSource = database(
                tempDir.resolve("conversation-purpose.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path planning = Files.createDirectory(
                tempDir.resolve("conversation-purpose-worktree"));
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        Path repository = Files.createDirectory(
                tempDir.resolve("conversation-purpose-repo"));
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        completePlannedCliTurn(
                runtime, jdbc, manager, repository, planning,
                "conversation-1", "conversation question", "conversation prompt",
                "conversation answer", "conversation-session",
                "conversation-execution", json);
        String nonConversationTurnId = completeCliTurn(
                runtime, jdbc, manager, repository, planning, "PLANNING",
                "non-conversation", "internal question", "internal prompt",
                "internal answer", "internal-session", "internal-execution", json);

        JsonNode nonConversationLaunch = json.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM thread_turn WHERE id = ?
                """, String.class, nonConversationTurnId));
        assertThat(nonConversationLaunch.has("resumeSessionId")).isFalse();
        assertThat(nonConversationLaunch.has("fallbackPrompt")).isFalse();
        assertThat(nonConversationLaunch.path("prompt").asText())
                .isEqualTo("internal prompt");

        SqlitePlanningBaseTurnStore store = new SqlitePlanningBaseTurnStore(jdbc);
        assertThat(store.conversation("trunk-1"))
                .extracting(SqlitePlanningBaseTurnStore.ConversationMessage::body)
                .containsExactly("conversation question", "conversation answer");
        assertThat(store.latestSuccessfulCliSession(
                "trunk-1", "codex", "gpt-5.6", planning.toString()))
                .get()
                .extracting(SqlitePlanningBaseTurnStore.CliSession::providerSessionId)
                .isEqualTo("conversation-session");

        PlanningBaseTurnRuntime.Receipt next = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "conversation-2", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", "high", "trunk role",
                        "next question", "next prompt"));
        deliverSuccessfulRefresh(
                runtime, jdbc, next, repository, planning, json);
        runtime.recoverCommittedDeliveries(20);

        JsonNode launch = json.readTree(jdbc.queryForObject("""
                SELECT launch_input FROM thread_turn WHERE id = ?
                """, String.class, next.turnId()));
        assertThat(launch.path("resumeSessionId").asText())
                .isEqualTo("conversation-session");
        assertThat(launch.path("fallbackPrompt").asText())
                .contains("conversation question", "conversation answer", "next prompt")
                .doesNotContain("internal question", "internal answer", "internal prompt");
    }

    @Test
    void fixedClockRestartKeepsPlanningFifoByReturnedTrunkVersionNotUuid()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("fifo.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("fifo-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager(dataSource, trunkStore), trunkStore, repository);

        PlanningBaseTurnRuntime.Receipt first = runtime.request(request("request-0"));
        PlanningBaseTurnRuntime.Receipt second = runtime.request(request("request-1"));

        assertThat(second.operationId()).isLessThan(first.operationId());
        assertThat(jdbc.queryForList("""
                SELECT returned_trunk_version
                FROM trunk_planning_base_request_receipt
                ORDER BY returned_trunk_version
                """, Long.class)).containsExactly(1L, 2L);
        assertThat(jdbc.queryForList("""
                SELECT requested_at_ms FROM planning_base_refresh_operation
                ORDER BY id
                """, Long.class)).containsOnly(NOW.toEpochMilli());
        assertThat(new SqlitePlanningBaseTurnStore(jdbc)
                .require(second.operationId()).unlaunchedPredecessor()).isTrue();

        SqliteDispatchTicketStore restarted = new SqliteDispatchTicketStore(dataSource);
        assertThat(restarted.findEligiblePage(
                        NOW.plusMillis(1), null, 20).tickets())
                .extracting(DispatchTicket::id)
                .containsExactly(first.dispatchTicketId());
    }

    @Test
    void poisonedAttachmentIsSuppressedAndRecoveryContinuesWithNextCandidate()
            throws Exception
    {
        SQLiteDataSource dataSource = database(
                tempDir.resolve("planning-attachment-fence.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(
                tempDir.resolve("attachment-fence-repo"));
        Path planning = Files.createDirectory(
                tempDir.resolve("attachment-fence-worktree"));
        Path image = Files.write(
                tempDir.resolve("attachment-fence.png"), new byte[] {1, 2, 3});
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        String input = MessageAttachments.encode(
                json, "inspect", List.of(image.toString()));
        PlanningBaseTurnRuntime.Receipt poisoned = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "attachment-fence", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null, input, input));
        PlanningBaseTurnRuntime.Receipt valid = runtime.request(
                request("valid-after-attachment-fence"));
        assertThat(jdbc.queryForObject("""
                SELECT json_extract(launch_intent, '$.images[0].digest')
                FROM planning_base_refresh_operation WHERE operation_id = ?
                """, String.class, poisoned.operationId()))
                .isEqualTo(
                        "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

        Files.write(image, new byte[] {9, 9, 9});
        deliverSuccessfulRefresh(
                runtime, jdbc, poisoned, repository, planning, json);
        deliverSuccessfulRefresh(
                runtime, jdbc, valid, repository, planning, json);

        runtime.recoverCommittedDeliveries(20);

        assertThat(jdbc.queryForMap("""
                SELECT launch_disposition, launch_disposition_reason
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, poisoned.operationId()))
                .containsEntry("launch_disposition", "SUPPRESSED")
                .hasEntrySatisfying("launch_disposition_reason", reason ->
                        assertThat(reason.toString())
                                .contains("frozen input is invalid")
                                .contains("content changed before provider launch"));
        assertThat(jdbc.queryForObject("""
                SELECT launch_disposition
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, String.class, valid.operationId())).isEqualTo("LAUNCHED");
        assertThat(jdbc.queryForList(
                "SELECT id FROM thread_turn", String.class))
                .containsExactly(valid.turnId());
        assertThat(count(jdbc, "thread_attachment")).isZero();
        assertThat(new SqlitePlanningBaseTurnStore(jdbc).readyOperationIds(20))
                .isEmpty();
    }

    @Test
    void cancelAfterRefreshDurablySuppressesLaunchAcrossRestart()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("suppressed.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("suppressed-repo"));
        Path planning = Files.createDirectory(tempDir.resolve("suppressed-planning"));
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt requested = runtime.request(
                request("suppress-after-refresh"));
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, requested.operationId(), 1,
                null, null, null);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, requested.operationId(), "trunk-1",
                        repository.toString(), planning.toString(),
                        "refs/remotes/origin/main", "abcdef0123456789",
                        NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(snapshot), "{}", null);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE);
        markResultPending(jdbc, requested.dispatchTicketId(), raw);
        DispatchTicket.DeliveryReceipt accepted = runtime.deliver(owner, fence, raw);
        markDelivered(jdbc, requested.dispatchTicketId(), accepted);

        assertThat(runtime.suppressPending(
                "trunk-1", "User canceled before provider launch")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT launch_disposition, launch_disposition_reason
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, requested.operationId()))
                .containsEntry("launch_disposition", "SUPPRESSED")
                .containsEntry("launch_disposition_reason",
                        "User canceled before provider launch");
        assertThat(new ThreadTurnProjection(jdbc, json).turns("trunk-1", 10))
                .singleElement()
                .satisfies(turn -> {
                    assertThat(turn.status()).isEqualTo("CANCELED");
                    assertThat(turn.error())
                            .isEqualTo("User canceled before provider launch");
                });

        PlanningBaseTurnRuntime restarted = runtime(
                new JdbcTemplate(dataSource),
                manager(dataSource, new V2TrunkStore(new JdbcTemplate(dataSource))),
                new V2TrunkStore(new JdbcTemplate(dataSource)), repository);
        restarted.recoverCommittedDeliveries(20);
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(new SqlitePlanningBaseTurnStore(jdbc).readyOperationIds(20))
                .isEmpty();
    }

    @Test
    void suppressesOnlyTheExactReservedTurn()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("exact-suppress.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("exact-suppress-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager(dataSource, trunkStore), trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt first = runtime.request(request("first"));
        PlanningBaseTurnRuntime.Receipt second = runtime.request(request("second"));
        ThreadTurnProjection projection = new ThreadTurnProjection(
                jdbc, new ObjectMapper());

        assertThat(projection.latestCancelableTurnId("trunk-1"))
                .contains(second.turnId());
        assertThat(projection.cancelableTicketId("trunk-1", second.turnId()))
                .contains(second.dispatchTicketId());

        assertThat(runtime.suppressPending(
                "trunk-1", second.turnId(), "cancel second")).isOne();
        assertThat(projection.latestCancelableTurnId("trunk-1"))
                .contains(first.turnId());
        assertThat(jdbc.queryForObject("""
                SELECT launch_disposition
                FROM planning_base_refresh_operation
                WHERE id = ?
                """, String.class, first.planningOperationId()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT launch_disposition
                FROM planning_base_refresh_operation
                WHERE id = ?
                """, String.class, second.planningOperationId()))
                .isEqualTo("SUPPRESSED");
    }

    @Test
    void failedRefreshKeepsTheAcceptedTurnVisibleAndTerminal()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("failed.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("failed-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Request request =
                new PlanningBaseTurnRuntime.Request(
                        "failed-request", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "start the next task", "compile a plan");

        PlanningBaseTurnRuntime.Receipt accepted = runtime.request(request);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, accepted.operationId(), 1,
                null, null, null);
        DispatchTicket.DispatchResult failed = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.FAILED,
                null, "{}", "watched clone unavailable");
        markResultPending(jdbc, accepted.dispatchTicketId(), failed);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE);
        DispatchTicket.DeliveryReceipt receipt = runtime.deliver(
                owner, fence, failed);
        markDelivered(jdbc, accepted.dispatchTicketId(), receipt);

        ThreadTurnProjection projection = new ThreadTurnProjection(
                jdbc, new ObjectMapper());
        assertThat(projection.turns("trunk-1", 10))
                .singleElement()
                .satisfies(turn -> {
                    assertThat(turn.turnId()).isEqualTo(accepted.turnId());
                    assertThat(turn.status()).isEqualTo("FAILED");
                    assertThat(turn.error())
                            .isEqualTo("watched clone unavailable");
                });
        assertThat(projection.history("trunk-1"))
                .hasSize(2)
                .extracting(message -> message.contentJson())
                .anySatisfy(content -> assertThat(content)
                        .contains("start the next task"))
                .anySatisfy(content -> assertThat(content)
                        .contains("failed before this turn could start")
                        .contains("watched clone unavailable"));
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(projection.cancelableTicketIds("trunk-1")).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("IDLE");
    }

    @Test
    void pendingRefreshWaitsOutsideCapacityWhileATrunkTurnIsLive()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("wait.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('live-turn', 'trunk-1', 'PLANNING', 'RUNNING',
                    'live-operation', 1, 'CLI', '{}', 1)
                """);
        Path repository = Files.createDirectory(tempDir.resolve("wait-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);

        PlanningBaseTurnRuntime.Receipt receipt = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "waiting", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "next", "next"));

        assertThat(new SqlitePlanningBaseTurnStore(jdbc)
                .require(receipt.operationId()).liveThreadTurn()).isTrue();
        assertThat(new SqliteDispatchTicketStore(dataSource)
                .findEligiblePage(NOW.plusSeconds(1), null, 20).tickets())
                .extracting(DispatchTicket::id)
                .doesNotContain(receipt.dispatchTicketId());
        assertThat(count(jdbc, "capacity_lease")).isZero();

        assertThat(new CapacityManager.CapacityRequest(
                receipt.operationId(), CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", null, null),
                true, false, false).trunkControl()).isTrue();
        assertThatThrownBy(() -> new CapacityManager.CapacityRequest(
                "ordinary", CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", null, null),
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Task writer, Trunk control, or exact workspace repository lease");
    }

    @Test
    void archivedTrunkSupersedesASuccessfulRefreshWithoutKeepingItsSnapshot()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("archived.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("archived-repo"));
        Path planning = Files.createDirectory(tempDir.resolve("archived-planning"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt request = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "archived-request", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "next", "next"));
        manager.archive(new TrunkManager.Command(
                "archive", "user", "trunk-1", 1));

        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, request.operationId(), 1,
                null, null, null);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, request.operationId(), "trunk-1",
                        repository.toString(), planning.toString(),
                        "refs/remotes/origin/main", "abcdef0123456789",
                        NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                new ObjectMapper().writeValueAsString(snapshot), "{}", null);
        markResultPending(jdbc, request.dispatchTicketId(), raw);
        DispatchTicket.DeliveryReceipt delivered = runtime.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                        PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE),
                fence, raw);

        assertThat(delivered.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(jdbc.queryForMap("""
                SELECT status, result_worktree_path, result_base_sha
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, request.operationId()))
                .containsEntry("status", "SUPERSEDED")
                .containsEntry("result_worktree_path", null)
                .containsEntry("result_base_sha", null);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForMap("""
                SELECT launch_disposition, launch_disposition_reason
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, request.operationId()))
                .containsEntry("launch_disposition", "SUPPRESSED")
                .containsEntry("launch_disposition_reason",
                        "Trunk archived before turn launch");
        runtime.recoverCommittedDeliveries(20);
        assertThat(count(jdbc, "thread_turn")).isZero();
    }

    private static PlanningBaseTurnRuntime.Request request(String commandId)
    {
        return new PlanningBaseTurnRuntime.Request(
                commandId, "user", "trunk-1", "workspace-1",
                "TRUNK_CONVERSATION", AgentTurnProviderSession.Transport.CLI,
                "codex", null, "gpt-5.6", null, null,
                "next", "next");
    }

    private PlanningBaseTurnRuntime runtime(
            JdbcTemplate jdbc,
            TrunkManager manager,
            V2TrunkStore trunkStore,
            Path repository)
    {
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        when(repositories.resolve("workspace-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0,
                        repository.toString(), null, null)));
        ObjectMapper json = new ObjectMapper();
        return new PlanningBaseTurnRuntime(
                manager, trunkStore, new SqlitePlanningBaseTurnStore(jdbc),
                new ThreadTurnHandoff(
                        manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123),
                repositories, watched, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrunkManager manager(
            SQLiteDataSource dataSource, V2TrunkStore store)
    {
        return new TrunkManager(
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(dataSource)),
                store);
    }

    private static void markResultPending(
            JdbcTemplate jdbc,
            String ticketId,
            DispatchTicket.DispatchResult result)
    {
        DispatchTicket.OperationFence fence = result.fence();
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = ?, pending_result_payload = ?,
                    pending_result_evidence = ?, pending_result_error = ?,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ?
                """, result.outcome().name(), result.payloadJson(),
                result.evidenceJson(), result.error(), fence.taskEpoch(),
                fence.stageId(), fence.stageGeneration(), fence.operationId(),
                fence.attempt(), fence.expectedCodeFingerprint(),
                fence.expectedHeadSha(), fence.expectedBaseSha(), ticketId);
    }

    private static void deliverSuccessfulRefresh(
            PlanningBaseTurnRuntime runtime,
            JdbcTemplate jdbc,
            PlanningBaseTurnRuntime.Receipt requested,
            Path repository,
            Path planning,
            ObjectMapper json)
            throws Exception
    {
        String expectedBaseSha = jdbc.queryForObject("""
                SELECT expected_base_sha FROM dispatch_ticket WHERE id = ?
                """, String.class, requested.dispatchTicketId());
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, requested.operationId(), 1,
                null, null, expectedBaseSha);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, requested.operationId(), "trunk-1",
                        repository.toString(), planning.toString(),
                        "refs/remotes/origin/main", "abcdef0123456789",
                        NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(snapshot), "{}", null);
        markResultPending(jdbc, requested.dispatchTicketId(), raw);
        DispatchTicket.DeliveryReceipt accepted = runtime.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                        PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE),
                fence, raw);
        markDelivered(jdbc, requested.dispatchTicketId(), accepted);
    }

    private static String completePlannedCliTurn(
            PlanningBaseTurnRuntime runtime,
            JdbcTemplate jdbc,
            TrunkManager manager,
            Path repository,
            Path workingDirectory,
            String commandId,
            String userMessage,
            String prompt,
            String answer,
            String sessionId,
            String executionId,
            ObjectMapper json)
            throws Exception
    {
        return completeCliTurn(
                runtime, jdbc, manager, repository, workingDirectory,
                "TRUNK_CONVERSATION", commandId, userMessage, prompt, answer,
                sessionId, executionId, json);
    }

    private static String completeCliTurn(
            PlanningBaseTurnRuntime runtime,
            JdbcTemplate jdbc,
            TrunkManager manager,
            Path repository,
            Path workingDirectory,
            String purpose,
            String commandId,
            String userMessage,
            String prompt,
            String answer,
            String sessionId,
            String executionId,
            ObjectMapper json)
            throws Exception
    {
        String turnId;
        if ("TRUNK_CONVERSATION".equals(purpose)) {
            PlanningBaseTurnRuntime.Receipt request = runtime.request(
                    new PlanningBaseTurnRuntime.Request(
                            commandId, "user", "trunk-1", "workspace-1",
                            purpose,
                            AgentTurnProviderSession.Transport.CLI,
                            "codex", null, "gpt-5.6", "high", "trunk role",
                            userMessage, prompt));
            deliverSuccessfulRefresh(
                    runtime, jdbc, request, repository, workingDirectory, json);
            runtime.recoverCommittedDeliveries(20);
            turnId = request.turnId();
        }
        else {
            long version = jdbc.queryForObject("""
                    SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                    """, Long.class);
            turnId = new ThreadTurnHandoff(
                    manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123)
                    .request(new ThreadTurnHandoff.Request(
                            commandId, "user", "trunk-1", "workspace-1",
                            version, purpose,
                            AgentTurnProviderSession.Transport.CLI,
                            "codex", null, "gpt-5.6", "high", workingDirectory,
                            "trunk role", userMessage, prompt))
                    .state().turnId();
        }
        String operationId = jdbc.queryForObject("""
                SELECT operation_id FROM thread_turn WHERE id = ?
                """, String.class, turnId);
        String ticketId = jdbc.queryForObject("""
                SELECT id FROM dispatch_ticket
                WHERE owner_kind = 'THREAD_TURN' AND owner_id = ?
                """, String.class, turnId);
        new SqliteThreadTurnOperationStore(jdbc).tryStart(
                turnId, operationId, NOW.plusSeconds(1));
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms)
                VALUES (?, ?, 1, 'codex', ?, 'SUCCEEDED', ?, ?)
                """, executionId, ticketId, sessionId,
                NOW.plusSeconds(1).toEpochMilli(),
                NOW.plusSeconds(2).toEpochMilli());
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, operationId, 1,
                null, null, null);
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turnId, DispatchTicket.OwnerKind.THREAD_TURN,
                        purpose,
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", sessionId, answer, 1, 1, 0, 1L,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null, null, null, 100L, 40L);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(payload), "{}", null);
        jdbc.update("""
                UPDATE agent_execution SET raw_result = ? WHERE id = ?
                """, json.writeValueAsString(result), executionId);
        markResultPending(jdbc, ticketId, result);
        DispatchTicket.DeliveryReceipt delivered =
                new ThreadTurnResultDeliveryPort(
                        manager, new AgentTurnOwnerResultCodec(json), json,
                        Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC))
                        .deliver(
                                new DispatchTicket.OwnerReference(
                                        DispatchTicket.OwnerKind.THREAD_TURN,
                                        turnId, "THREAD_TURN_RESULT"),
                                fence, result);
        markDelivered(jdbc, ticketId, delivered);
        return turnId;
    }

    private static void markDelivered(
            JdbcTemplate jdbc,
            String ticketId,
            DispatchTicket.DeliveryReceipt receipt)
    {
        String outcome = jdbc.queryForObject("""
                SELECT pending_result_outcome FROM dispatch_ticket WHERE id = ?
                """, String.class, ticketId);
        String status = switch (outcome) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "CANCELED" -> "CANCELED";
            default -> "FAILED";
        };
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = ?,
                    pending_result_outcome = NULL,
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = NULL,
                    pending_result_task_epoch = NULL,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = NULL,
                    pending_result_attempt = NULL,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = NULL,
                    pending_result_expected_base_sha = NULL,
                    next_attempt_at_ms = NULL,
                    delivery_acceptance = ?, delivery_evidence = ?,
                    completed_at_ms = ?
                WHERE id = ?
                """, status, receipt.acceptance().name(), receipt.evidenceJson(),
                NOW.plusSeconds(1).toEpochMilli(), ticketId);
    }

    private static SQLiteDataSource database(Path file)
    {
        return database(file, "260");
    }

    private static SQLiteDataSource database(Path file, String target)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedTrunk(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'workspace-1', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'trunk-1', 'IDLE',
                    'gpt-5.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'IDLE')
                """);
    }

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
