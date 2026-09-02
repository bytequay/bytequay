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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexCliDriver
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void probesResumesAndMapsAnAuthoritativeRedactedResult()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();

        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        AgentSession initial = driver.open(context(Optional.empty(), approval), events::add);
        AgentSessionRef reference = initial.reference();
        initial.close();
        AgentSession resumed = driver.open(context(Optional.of(reference), approval), events::add);
        // Each session owns its own process, so the resume is served by a second one.
        assertThat(supervisor.servers()).hasSize(2);
        AppServerProcess resumedServer = supervisor.servers().getLast();
        assertThat(resumedServer.resumeCount()).isOne();

        RunningTurn turn = resumed.start(command("complete"));
        resumedServer.completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(result.toString()).doesNotContain("known-secret");
        assertThat(approval.get().detail()).isEqualTo("echo [REDACTED]");
        assertThat(resumedServer.approvalDecision()).isEqualTo("accept");
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(ProviderAgentEvent.Message.class);
            ProviderAgentEvent.Message message = (ProviderAgentEvent.Message) event;
            assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
            assertThat(message.text()).isEqualTo("draft [REDACTED]");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(ProviderAgentEvent.Usage.class);
            ProviderAgentEvent.Usage usage = (ProviderAgentEvent.Usage) event;
            assertThat(usage.sample().semantics()).isEqualTo(
                    com.bytequay.agent.spi.ProviderUsageSample.Semantics.CUMULATIVE);
            assertThat(usage.sample().inputTokens()).isEqualTo(80);
            assertThat(usage.sample().outputTokens()).isEqualTo(20);
        });
        assertThat(events.toString()).doesNotContain("known-secret");

        resumed.close();

        assertThat(driver.capabilities().permissionPolicies())
                .containsExactly(AgentPermissionPolicy.AUTO);
        assertThat(supervisor.servers())
                .allSatisfy(server -> assertThat(server.approvalPolicies()).containsExactly("on-request"));
        // Declared for thread/backgroundTerminals/list, which is experimental and is the only
        // inventory that can prove a thread owns no live process.
        assertThat(supervisor.servers()).allSatisfy(
                server -> assertThat(server.experimentalApi()).containsExactly(true));

        driver.close();
    }

    @Test
    void reportsCancellationFailureAndAppServerDisconnect()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});

        RunningTurn cancelled = session.start(command("cancel"));
        cancelled.cancel();
        AgentTurnResult cancelledResult = cancelled.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(cancelledResult.state()).isEqualTo(AgentTurnResult.State.CANCELLED);

        RunningTurn failed = session.start(command("fail"));
        supervisor.server().failTurn();
        AgentTurnResult failedResult = failed.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(failedResult.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failedResult.terminalReason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("Codex CLI 0.151.0").contains("provider failed"));

        supervisor.versionOutput("future-build");
        assertThat(driver.probe().version()).contains("future-build");

        RunningTurn disconnected = session.start(command("disconnect"));
        supervisor.server().disconnect();
        AgentTurnResult disconnectedResult = disconnected.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(disconnectedResult.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(disconnectedResult.terminalReason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("Codex CLI 0.151.0").contains("exited with code 1"));

        driver.close();
    }

    @Test
    void updatesTheCodexCliOnlyWhenItIsOlderThanTheVerifiedMinimum()
            throws Exception
    {
        // `codex update` installs the latest release, so running it unconditionally moves a user's
        // CLI on every backend start, past the protocol this lane was generated against.
        assertThat(CodexCliDriver.isOlderThanMinimum("0.150.9")).isTrue();
        assertThat(CodexCliDriver.isOlderThanMinimum("0.151.0-alpha.7.2")).isTrue();
        assertThat(CodexCliDriver.isOlderThanMinimum("0.151.0")).isFalse();
        assertThat(CodexCliDriver.isOlderThanMinimum("0.152.1")).isFalse();
        assertThat(CodexCliDriver.isOlderThanMinimum("1.0.0")).isFalse();
        // Guessing would upgrade a CLI on no evidence.
        assertThat(CodexCliDriver.isOlderThanMinimum("not-a-version")).isFalse();

        ScriptedProcessSupervisor newer = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.152.1");
        assertThat(driver(newer).update()).get().asString().contains("already meets the verified minimum");
        assertThat(newer.updateRuns()).isZero();

        ScriptedProcessSupervisor older = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.150.0");
        driver(older).update();
        assertThat(older.updateRuns()).isOne();
    }

    @Test
    void pinsTheVerifiedMinimumToTheReferenceProjection()
            throws Exception
    {
        // The minimum is the version the committed protocol records were generated from. If the two
        // drift apart the lane would update toward a schema nothing has verified.
        InputStream bindings = getClass().getResourceAsStream("app-server-v0.151.0-bindings.json");
        assertThat(bindings).as("reference bindings are missing").isNotNull();
        JsonNode reference = objectMapper.readTree(bindings);
        assertThat(reference.path("codexVersion").asText()).isEqualTo(CodexCliDriver.MINIMUM_VERSION);
    }

    @Test
    void acceptsAndReportsTheInstalledCodexVersion()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "future-build");
        CodexCliDriver driver = driver(supervisor);

        AgentProviderStatus status = driver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(status.version()).contains("future-build");
        driver.close();
    }

    @Test
    void distinguishesAuthenticationAndExecutableFailures()
    {
        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor(objectMapper);
        loggedOut.loggedOut();
        CodexCliDriver loggedOutDriver = driver(loggedOut);
        AgentProviderStatus authentication = loggedOutDriver.probe();
        assertThat(authentication.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(authentication.available()).isFalse();
        loggedOutDriver.close();

        ScriptedProcessSupervisor missing = new ScriptedProcessSupervisor(objectMapper);
        missing.executableMissing();
        CodexCliDriver missingDriver = driver(missing);
        AgentProviderStatus executable = missingDriver.probe();
        assertThat(executable.readiness()).isEqualTo(AgentProviderStatus.Readiness.CLI_MISSING);
        assertThat(executable.available()).isFalse();
        missingDriver.close();
    }

    @Test
    void reportsTheInstalledVersionWhenAppServerIsIncompatible()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "future-build", true);
        CodexCliDriver driver = driver(supervisor);

        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Codex CLI future-build")
                .hasMessageContaining("thread/start")
                .hasMessageContaining("unsupported protocol");

        driver.close();
    }

    @Test
    void rejectsLatePermissionAndLegacyRequestsWithoutRoutingAnApproval()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AgentSession session = driver.open(context(Optional.empty(), approval), event -> {});
        session.start(command("approval-routing"));
        AppServerProcess server = supervisor.server();

        server.emit("""
                {"id":92,"method":"item/commandExecution/requestApproval","params":{
                "threadId":"thread-1","turnId":"stale-turn","itemId":"exec-stale",
                "command":"echo stale","availableDecisions":["accept","decline"],
                "startedAtMs":1786884871461}}
                """);
        server.emit("""
                {"id":93,"method":"item/permissions/requestApproval","params":{
                "cwd":"/worktree","itemId":"permission-1","permissions":{},
                "startedAtMs":1786884871462,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        server.emit("""
                {"id":94,"method":"execCommandApproval","params":{
                "callId":"call-1","command":["echo","legacy"],"conversationId":"thread-1",
                "cwd":"/worktree","parsedCmd":[]}}
                """);
        server.emit("""
                {"id":95,"method":"applyPatchApproval","params":{
                "callId":"call-2","conversationId":"thread-1","fileChanges":{}}}
                """);
        server.emit("""
                {"id":96,"method":"item/commandExecution/requestApproval","params":{
                "additionalPermissions":{"network":{"enabled":true}},"command":"curl example.com",
                "itemId":"exec-permission","startedAtMs":1786884871463,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        server.emit("""
                {"id":97,"method":"item/commandExecution/requestApproval","params":{
                "command":"input","itemId":"exec-input","kind":"writeStdin",
                "startedAtMs":1786884871464,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        server.emit("""
                {"id":99,"method":"item/commandExecution/requestApproval","params":{
                "command":"curl example.com","itemId":"exec-network",
                "networkApprovalContext":{"host":"example.com","protocol":"https"},
                "startedAtMs":1786884871465,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        server.emit("""
                {"id":100,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"/worktree/generated","itemId":"patch-scoped",
                "startedAtMs":1786884871466,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        server.emit("""
                {"id":102,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"","itemId":"patch-empty-root","startedAtMs":1786884871467,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));

        // Every rejection is answered on the dispatch thread, so all of them have to arrive
        // before any is read. Waiting for only the first few leaves the rest racing the asserts.
        List<Long> rejected = List.of(92L, 93L, 94L, 95L, 96L, 97L, 99L, 100L, 102L);
        awaitCondition(() -> rejected.stream().allMatch(id -> server.error(id) != null));
        assertThat(server.error(92).path("message").asText()).contains("active turn");
        assertThat(server.error(93).path("message").asText()).contains("Unsupported Codex request");
        assertThat(server.error(94).path("message").asText()).contains("no active thread");
        assertThat(server.error(95).path("message").asText()).contains("no active thread");
        assertThat(server.error(96).path("message").asText()).contains("cannot represent");
        assertThat(server.error(97).path("message").asText()).contains("approval kind");
        assertThat(server.error(99).path("message").asText()).contains("cannot represent");
        assertThat(server.error(100).path("message").asText()).contains("scoped grant");
        assertThat(server.error(102).path("message").asText()).contains("scoped grant");
        assertThat(approval).hasValue(null);
        driver.close();
    }

    @Test
    void rejectsAnApprovalDecisionAfterItsTurnFinishes()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        CompletableFuture<ProviderApprovalDecision> decision = new CompletableFuture<>();
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            approval.set(request);
            return decision;
        }), event -> {});
        RunningTurn late = session.start(command("late-decision"));
        AppServerProcess server = supervisor.server();

        server.emit("""
                {"id":98,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo late",
                "itemId":"exec-late","startedAtMs":1786884871465,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> approval.get() != null);
        assertThat(approval.get().detail()).isEqualTo("echo late");

        server.failTurn();
        awaitCondition(() -> late.completion().toCompletableFuture().isDone());
        session.start(command("replacement"));
        decision.complete(ProviderApprovalDecision.allow(Optional.empty()));

        awaitCondition(() -> server.error(98) != null);
        assertThat(server.error(98).path("message").asText()).contains("no longer belongs");
        driver.close();
    }

    @Test
    void rejectsAnApprovalDecisionAfterCancellationIsRequested()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        CompletableFuture<ProviderApprovalDecision> decision = new CompletableFuture<>();
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            approval.set(request);
            return decision;
        }), event -> {});
        RunningTurn turn = session.start(command("cancelled-decision"));
        AppServerProcess server = supervisor.server();

        server.emit("""
                {"id":101,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo cancelled",
                "itemId":"exec-cancelled","startedAtMs":1786884871466,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> approval.get() != null);
        assertThat(approval.get().detail()).isEqualTo("echo cancelled");

        server.holdInterruptCompletion();
        turn.cancel();
        approval.set(null);
        server.emit("""
                {"id":103,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo after-cancel",
                "itemId":"exec-after-cancel","startedAtMs":1786884871467,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.error(103) != null);
        assertThat(server.error(103).path("message").asText()).contains("active turn");
        assertThat(approval).hasValue(null);
        decision.complete(ProviderApprovalDecision.allow(Optional.empty()));

        assertThat(server.error(101).path("message").asText()).contains("no longer belongs");
        server.releaseInterruptCompletion();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.CANCELLED);
        driver.close();
    }

    @Test
    void mapsOnlyOfferedUnscopedBinaryApprovalDecisions()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AtomicReference<ProviderApprovalDecision> decision = new AtomicReference<>(ProviderApprovalDecision.deny());
        AgentSession session = driver.open(context(Optional.empty(), approval, decision), event -> {});
        session.start(command("approval-decisions"));
        AppServerProcess server = supervisor.server();

        server.emit("""
                {"id":95,"method":"item/fileChange/requestApproval","params":{
                "itemId":"patch-1","reason":"write file","startedAtMs":1786884871461,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.result(95) != null);
        assertThat(server.result(95).path("decision").asText()).isEqualTo("decline");

        decision.set(ProviderApprovalDecision.allow(Optional.empty()));
        server.emit("""
                {"id":96,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["decline"],"command":"echo unavailable","itemId":"exec-1",
                "startedAtMs":1786884871462,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.error(96) != null);
        assertThat(server.error(96).path("message").asText()).contains("did not offer decision accept");

        decision.set(ProviderApprovalDecision.allow(Optional.of("session")));
        server.emit("""
                {"id":97,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo scoped","itemId":"exec-2",
                "startedAtMs":1786884871463,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.error(97) != null);
        assertThat(server.error(97).path("message").asText()).contains("scoped permission");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        driver.close();
    }

    @Test
    void bindsEachTaskToItsOwnAppServerProcess()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        Path second = Files.createDirectories(worktree.resolve("second-task"));

        AgentSession first = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        first.start(command("first"));
        AgentSession other = driver.open(
                context("task-2", second, Optional.empty(), (request, timeout) -> new CompletableFuture<>()),
                event -> {});

        // One App Server process per task, each rooted in its own worktree. That working directory
        // is the sandbox root, which is what confines a turn without a separate execution
        // environment.
        assertThat(supervisor.servers()).hasSize(2);
        assertThat(supervisor.serverCwds()).containsExactly(worktree, second);
        assertThat(supervisor.serverOwners())
                .extracting(ProcessOwner::kind)
                .containsOnly(ProcessOwner.Kind.APP_SERVER);
        // The owner is the task, so a recovered orphan is attributable to the task that leaked it.
        assertThat(supervisor.serverOwners())
                .extracting(ProcessOwner::id)
                .containsExactly("task-1", "task-2")
                .doesNotHaveDuplicates();

        other.close();
        first.close();
    }

    @Test
    void refusesATurnWhenAnInheritedCapabilityCannotBeProvenAbsent()
    {
        // Managed policy applies, so an exec rule could be inherited without ByteQuay seeing it.
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.reportManagedRequirements(
                "{\"requirements\":{\"allowLoginShell\":true}}")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("managed requirements apply");

        // An unknown sibling field means a policy dimension exists that this release cannot read.
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.reportManagedRequirements(
                "{\"requirements\":null,\"futurePolicy\":{}}")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unknown managed-policy field");

        // A lifecycle hook sits in the approval path that ByteQuay assumes it owns.
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.reportHooks("[{\"name\":\"stop\"}]")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
    }

    @Test
    void appliesEachTurnsOwnSettingsSnapshotToTurnStart()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});

        RunningTurn first = session.start(new AgentTurnCommand(
                "turn-a",
                "first",
                ImmutableList.<AgentAttachment>of(),
                Duration.ofMinutes(5),
                Optional.of("gpt-5-codex"),
                Optional.of("high")));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        RunningTurn second = session.start(new AgentTurnCommand(
                "turn-b",
                "second",
                ImmutableList.<AgentAttachment>of(),
                Duration.ofMinutes(5),
                Optional.of("gpt-5"),
                Optional.of("minimal")));
        supervisor.server().completeTurn();
        second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Each turn carries the snapshot taken when it was accepted; a later default cannot
        // rewrite an earlier turn.
        assertThat(supervisor.server().turnSettings())
                .containsExactly("gpt-5-codex/high", "gpt-5/minimal");
    }

    @Test
    void waitsForAYieldedCommandBeforeCompletingATurn()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();

        supervisor.server().completeTurn();

        // A yielded command outlives the item that started it, so the root reporting completion is
        // not proof that anything stopped touching the worktree.
        Thread.sleep(400);
        assertThat(turn.completion().toCompletableFuture()).isNotDone();

        supervisor.reportNoBackgroundTerminals();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        session.close();
    }

    @Test
    void waitsForAnItemOnlyHistoryKnowsIsStillRunning()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("closure"));
        // Never announced through the notification stream, so only history can see it.
        supervisor.reportUnfinishedHistoryItem();

        supervisor.server().completeTurn();

        // The live stream can miss an item; closing over history is the only thing that catches it.
        Thread.sleep(400);
        assertThat(turn.completion().toCompletableFuture()).isNotDone();

        supervisor.reportSettledHistory();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        session.close();
    }

    @Test
    void findsASubagentThreadThatOnlyHistoryMentions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("delegate"));
        supervisor.reportUnannouncedSubagent();

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // A subagent that never reached the notification stream still has to be checked, so its
        // thread is taken from the root's history rather than from what happened to arrive.
        assertThat(supervisor.server().backgroundTerminalThreads()).contains("child-unannounced");
        session.close();
    }

    @Test
    void terminatesAYieldedCommandWhenTheTurnIsCancelled()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();

        turn.cancel();

        // A cancelled turn is not waiting for its work, it is stopping it, so the command it left
        // running is terminated rather than watched until the bound expires.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(supervisor.server().terminated()).containsExactly("proc-1");
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        session.close();
    }

    @Test
    void failsATurnWhoseProcessCannotBeShownStopped()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();

        supervisor.server().completeTurn();

        // Reporting COMPLETED here would release the task's claim over files a live command may
        // still be writing, so an unproven turn fails and the task keeps its worktree.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason().orElseThrow()).contains("could not be shown stopped");
        assertThat(result.terminalReason().orElseThrow()).contains("background terminal proc-1");
        session.close();
    }

    @Test
    void doesNotHoldATurnForAnEarlierTurnsUnfinishedItem()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});

        RunningTurn first = session.start(command("one"));
        supervisor.server().emitUnfinishedItem("exec-stuck");
        supervisor.server().completeTurn();
        assertThat(first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.FAILED);

        RunningTurn second = session.start(command("two"));
        supervisor.server().completeTurn();

        // The earlier turn's outcome is already decided. Carrying its unresolved item forward
        // would fail every turn after it for a reason that is no longer about them.
        assertThat(second.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        session.close();
    }

    @Test
    void surfacesSubagentActivityWithoutEndingTheTasksTurn()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("delegate"));

        supervisor.server().emitSubagentActivity("child-thread-7");

        // Every thread in this process belongs to this task, so a subagent's work is reported
        // rather than filtered out for carrying its own turn id.
        awaitCondition(() -> events.stream().anyMatch(event -> event.toString().contains("from a child thread")));
        // A subagent's turn completing is not the task's turn completing, and the subagent finishes
        // first.
        assertThat(turn.completion().toCompletableFuture()).isNotDone();
        // A subagent keeps its own cumulative token counter. Feeding it to the task's turn would
        // overwrite the task's real usage with a second, unrelated running total.
        assertThat(events).noneMatch(event -> event instanceof ProviderAgentEvent.Usage);
        // Its final answer is its own. Published as FINAL it could be read back as the task's
        // recorded final response.
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message)
                .allSatisfy(event -> assertThat(((ProviderAgentEvent.Message) event).phase())
                        .isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL));

        supervisor.server().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        session.close();
    }

    @Test
    void rechecksInheritedCapabilitiesBeforeEveryTurn()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn first = session.start(command("first"));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // A workspace-write turn can leave a hook behind, so a clean first turn proves nothing
        // about the second.
        supervisor.reportHooks("[{\"name\":\"stop\"}]");

        assertThatThrownBy(() -> session.start(command("second")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
    }

    @Test
    void relaunchesTheAppServerInTheSameWorktreeOnResume()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        AgentSessionRef reference = initial.reference();
        initial.close();

        AgentSession resumed = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});

        // The process that served the first session died with it, so a stored thread is only
        // resumable inside a replacement rooted at the same worktree.
        assertThat(supervisor.servers()).hasSize(2);
        assertThat(supervisor.serverCwds()).containsExactly(worktree, worktree);
        assertThat(supervisor.serverOwners())
                .extracting(ProcessOwner::id)
                .containsExactly("task-1", "task-1");
        assertThat(supervisor.servers().getLast().resumeCount()).isOne();
        resumed.close();
    }

    @Test
    void rechecksInheritedCapabilitiesOnTheFirstTurnAfterResume()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        AgentSessionRef reference = initial.reference();
        initial.close();

        // A hook can appear while a task is unloaded, so a resumed thread proves nothing about the
        // process now serving it.
        supervisor.reportHooks("[{\"name\":\"stop\"}]");
        AgentSession resumed = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});

        assertThatThrownBy(() -> resumed.start(command("after-resume")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
        resumed.close();
    }

    /** Opens a session and starts a turn against a scripted App Server configured by {@code setup}. */
    private void startTurnWith(Consumer<ScriptedProcessSupervisor> setup)
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        setup.accept(supervisor);
        CodexCliDriver driver = driver(supervisor);
        driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})
                .start(command("probe"));
    }

    /** Waits for asynchronously delivered work, since handlers no longer run on the caller. */
    private static void awaitCondition(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before the deadline");
            }
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor)
    {
        return driver(supervisor, Duration.ofSeconds(30));
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor, Duration quiescenceTimeout)
    {
        return new CodexCliDriver(
                "/fake/codex",
                objectMapper,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "OPENAI_API_KEY", "known-secret"),
                quiescenceTimeout);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AtomicReference<ProviderApprovalRequest> approval)
    {
        return context(
                reference,
                approval,
                new AtomicReference<>(ProviderApprovalDecision.allow(Optional.empty())));
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AtomicReference<ProviderApprovalRequest> approval,
            AtomicReference<ProviderApprovalDecision> decision)
    {
        return context(reference, (request, timeout) -> {
            approval.set(request);
            return CompletableFuture.completedFuture(decision.get());
        });
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentApprovalHandler approvals)
    {
        return context("task-1", worktree, reference, approvals);
    }

    private static AgentSessionContext context(
            String taskId,
            Path taskWorktree,
            Optional<AgentSessionRef> reference,
            AgentApprovalHandler approvals)
    {
        return new AgentSessionContext(
                taskId,
                taskWorktree,
                taskWorktree,
                taskWorktree.resolve("provider"),
                AgentPermissionPolicy.AUTO,
                reference,
                approvals);
    }

    private static AgentTurnCommand command(String message)
    {
        return new AgentTurnCommand("turn-" + message, message, ImmutableList.of(), Duration.ofMinutes(1));
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
        private final ObjectMapper objectMapper;
        private final boolean failThreadStart;
        private volatile String versionOutput;
        private boolean loggedIn = true;
        private boolean executableMissing;
        // One App Server process per task, so the harness keeps every launch rather than the last.
        private final List<AppServerProcess> servers = new CopyOnWriteArrayList<>();
        private final List<Path> serverCwds = new CopyOnWriteArrayList<>();
        private final List<ProcessOwner> serverOwners = new CopyOnWriteArrayList<>();
        private volatile String managedRequirements = "{\"requirements\":null}";
        private volatile String hooks = "[]";
        private volatile String configLayers = "[]";
        private volatile String skills = "[]";
        private volatile String backgroundTerminals = "{\"data\":[],\"nextCursor\":null}";
        private volatile String historyItems = "{\"data\":[],\"nextCursor\":null}";

        /** Names a subagent thread in history that the notification stream never mentioned. */
        private void reportUnannouncedSubagent()
        {
            historyItems = "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"sub-1\","
                    + "\"agentThreadId\":\"child-unannounced\",\"agentPath\":\"a/b\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}";
        }

        /** Reports an item history still says is running, which the live stream never delivered. */
        private void reportUnfinishedHistoryItem()
        {
            historyItems = "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"commandExecution\",\"id\":\"exec-missed\","
                    + "\"command\":\"sleep 600\",\"status\":\"inProgress\"}}],\"nextCursor\":null}";
        }

        private void reportSettledHistory()
        {
            historyItems = "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"commandExecution\",\"id\":\"exec-missed\","
                    + "\"command\":\"sleep 600\",\"status\":\"completed\"}}],\"nextCursor\":null}";
        }
        private final AtomicInteger updateRuns = new AtomicInteger();

        private int updateRuns()
        {
            return updateRuns.get();
        }

        /** Reports one command still running after the item that started it went terminal. */
        private void reportBackgroundTerminal()
        {
            backgroundTerminals = "{\"data\":[{\"processId\":\"proc-1\",\"itemId\":\"exec-1\","
                    + "\"command\":\"sleep 600\",\"cwd\":\"/worktree\"}],\"nextCursor\":null}";
        }

        private void reportNoBackgroundTerminals()
        {
            backgroundTerminals = "{\"data\":[],\"nextCursor\":null}";
        }

        private void reportManagedRequirements(String response)
        {
            managedRequirements = response;
        }

        private void reportHooks(String discovered)
        {
            hooks = discovered;
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper)
        {
            this(objectMapper, "codex-cli 0.151.0", false);
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper, String versionOutput)
        {
            this(objectMapper, versionOutput, false);
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper, String versionOutput, boolean failThreadStart)
        {
            this.objectMapper = objectMapper;
            this.versionOutput = versionOutput;
            this.failThreadStart = failThreadStart;
        }

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            return new ScriptedLaunch(spec);
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            return ImmutableList.of();
        }

        @Override
        public void acknowledge(String launchId) {}

        private final class ScriptedLaunch
                implements PreparedProcess
        {
            private final ProcessSpec spec;

            private ScriptedLaunch(ProcessSpec spec)
            {
                this.spec = spec;
            }

            @Override
            public String launchId()
            {
                return "scripted";
            }

            @Override
            public void abandon() {}

            @Override
            public ManagedProcess start(ProcessOutputSink output)
            {
                return ScriptedProcessSupervisor.this.start(spec, output);
            }
        }

        private ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
        {
            if (executableMissing) {
                throw new AgentUnavailableException("Unable to start process: /fake/codex", new IOException("missing"));
            }
            if (spec.argv().contains("--version")) {
                output.onOutput(
                        ProcessStream.STDOUT,
                        StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("update")) {
                updateRuns.incrementAndGet();
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("updated\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("login")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(loggedIn ? "Logged in using ChatGPT\n" : "Not logged in\n"));
                return loggedIn ? ImmediateProcess.success() : ImmediateProcess.failure();
            }
            // The working directory and owner are load-bearing now: they are what binds a task to
            // its own process and its own sandbox root.
            serverCwds.add(spec.workingDirectory());
            serverOwners.add(spec.owner());
            AppServerProcess server = new AppServerProcess(
                    objectMapper,
                    output,
                    failThreadStart,
                    () -> managedRequirements,
                    () -> hooks,
                    () -> configLayers,
                    () -> skills,
                    () -> backgroundTerminals,
                    this::reportNoBackgroundTerminals,
                    () -> historyItems);
            servers.add(server);
            return server;
        }

        /** The only launched App Server, for the tests that open exactly one session. */
        private AppServerProcess server()
        {
            assertThat(servers).hasSize(1);
            return servers.getFirst();
        }

        private List<AppServerProcess> servers()
        {
            return servers;
        }

        private List<Path> serverCwds()
        {
            return serverCwds;
        }

        private List<ProcessOwner> serverOwners()
        {
            return serverOwners;
        }

        private void versionOutput(String versionOutput)
        {
            this.versionOutput = versionOutput;
        }

        private void loggedOut()
        {
            loggedIn = false;
        }

        private void executableMissing()
        {
            executableMissing = true;
        }
    }

    private static final class AppServerProcess
            implements ManagedProcess
    {
        private final ObjectMapper objectMapper;
        private final ProcessOutputSink output;
        private final boolean failThreadStart;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final ExecutorService reader = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("scripted-app-server-reader").factory());
        private final AtomicInteger turns = new AtomicInteger();
        private final AtomicInteger threads = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final List<String> approvalPolicies = new CopyOnWriteArrayList<>();
        private final Supplier<String> managedRequirements;
        private final Supplier<String> hooks;
        private final Supplier<String> configLayers;
        private final Supplier<String> skills;
        private final Supplier<String> backgroundTerminals;
        private final List<String> turnSettings = new CopyOnWriteArrayList<>();
        private final List<Boolean> experimentalApi = new CopyOnWriteArrayList<>();
        private final List<String> terminated = new CopyOnWriteArrayList<>();
        private final List<String> backgroundTerminalThreads = new CopyOnWriteArrayList<>();

        private List<String> backgroundTerminalThreads()
        {
            return backgroundTerminalThreads;
        }
        private final Runnable clearBackgroundTerminals;
        private final Supplier<String> historyItems;

        private List<String> terminated()
        {
            return terminated;
        }
        private final ConcurrentMap<Long, JsonNode> results = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, JsonNode> errors = new ConcurrentHashMap<>();
        private volatile CompletableFuture<Void> interruptCompletionGate = CompletableFuture.completedFuture(null);
        private volatile String activeTurn;

        private AppServerProcess(
                ObjectMapper objectMapper,
                ProcessOutputSink output,
                boolean failThreadStart,
                Supplier<String> managedRequirements,
                Supplier<String> hooks,
                Supplier<String> configLayers,
                Supplier<String> skills,
                Supplier<String> backgroundTerminals,
                Runnable clearBackgroundTerminals,
                Supplier<String> historyItems)
        {
            this.objectMapper = objectMapper;
            this.output = output;
            this.failThreadStart = failThreadStart;
            this.managedRequirements = managedRequirements;
            this.hooks = hooks;
            this.configLayers = configLayers;
            this.skills = skills;
            this.backgroundTerminals = backgroundTerminals;
            this.clearBackgroundTerminals = clearBackgroundTerminals;
            this.historyItems = historyItems;
        }

        /** Real App Server hands out a distinct thread per start; the fixture must too. */
        private String nextThreadId()
        {
            int index = threads.incrementAndGet();
            return index == 1 ? "thread-1" : "thread-" + index;
        }

        private List<String> turnSettings()
        {
            return turnSettings;
        }

        private List<Boolean> experimentalApi()
        {
            return experimentalApi;
        }

        @Override
        public String launchId()
        {
            return "scripted";
        }

        @Override
        public long pid()
        {
            return 4242;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            try {
                JsonNode message = objectMapper.readTree(StandardCharsets.UTF_8.decode(bytes).toString());
                receive(message);
                return CompletableFuture.completedFuture(null);
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        private void receive(JsonNode message)
        {
            String method = message.path("method").asText();
            long id = message.path("id").asLong();
            switch (method) {
                case "initialize" -> {
                    experimentalApi.add(message.path("params").path("capabilities")
                            .path("experimentalApi").asBoolean());
                    respond(id, "{\"codexHome\":\"/codex-home\"}");
                }
                case "configRequirements/read" -> respond(id, managedRequirements.get());
                case "config/read" -> respond(id, "{\"config\":{},\"origins\":{},\"layers\":" + configLayers.get() + "}");
                case "skills/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"skills\":" + skills.get() + ",\"errors\":[]}]}");
                case "hooks/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"hooks\":" + hooks.get() + ",\"errors\":[],\"warnings\":[]}]}");
                case "thread/items/list" -> respond(id, historyItems.get());
                case "thread/backgroundTerminals/list" -> {
                    backgroundTerminalThreads.add(message.path("params").path("threadId").asText());
                    respond(id, backgroundTerminals.get());
                }
                case "thread/backgroundTerminals/terminate" -> {
                    terminated.add(message.path("params").path("processId").asText());
                    clearBackgroundTerminals.run();
                    respond(id, "{\"terminated\":true}");
                }
                case "model/list" -> respond(id, "{\"data\":[{\"id\":\"gpt-5\",\"displayName\":\"GPT-5\","
                        + "\"description\":\"d\",\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"low\"}],"
                        + "\"defaultReasoningEffort\":\"low\",\"isDefault\":true,\"hidden\":false}]}");
                case "initialized" -> {}
                case "thread/start" -> {
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    if (failThreadStart) {
                        emit("{\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"unsupported protocol\"}}");
                    }
                    else {
                        respond(id, "{\"thread\":{\"id\":\"" + nextThreadId() + "\"}}");
                    }
                }
                case "thread/resume" -> {
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    resumes.incrementAndGet();
                    respond(id, "{\"thread\":{\"id\":\"thread-1\"}}");
                }
                case "thread/unsubscribe" -> respond(id, "{\"status\":\"unsubscribed\"}");
                case "turn/start" -> {
                    turnSettings.add(message.path("params").path("model").asText("none")
                            + "/" + message.path("params").path("effort").asText("none"));
                    activeTurn = "turn-" + turns.incrementAndGet();
                    respond(id, "{\"turn\":{\"id\":\"" + activeTurn
                            + "\",\"status\":\"inProgress\",\"error\":null,\"durationMs\":null,\"items\":[]}}");
                }
                case "turn/interrupt" -> {
                    String interruptedTurn = activeTurn;
                    CompletableFuture<Void> completionGate = interruptCompletionGate;
                    Thread.ofVirtual().start(() -> {
                        respond(id, "{}");
                        completionGate.join();
                        emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                                + "\"turn\":{\"id\":\"" + interruptedTurn
                                + "\",\"status\":\"interrupted\",\"error\":null,\"durationMs\":1,\"items\":[]}}}");
                    });
                }
                default -> {
                    if (message.has("result")) {
                        results.put(id, message.path("result").deepCopy());
                    }
                    if (message.has("error")) {
                        errors.put(id, message.path("error").deepCopy());
                    }
                }
            }
        }

        /**
         * Emits what a subagent produces: activity on its own thread under its own turn, then that
         * turn completing before the root's.
         */
        private void emitSubagentActivity(String threadId)
        {
            emit("{\"method\":\"item/agentMessage/delta\",\"params\":{"
                    + "\"threadId\":\"" + threadId + "\",\"turnId\":\"subagent-turn\","
                    + "\"itemId\":\"message-9\",\"delta\":\"from a child thread\"}}");
            // Its own cumulative counter, which is not the task's.
            emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{"
                    + "\"threadId\":\"" + threadId + "\",\"turnId\":\"subagent-turn\","
                    + "\"tokenUsage\":{\"total\":{\"inputTokens\":900,\"cachedInputTokens\":0,"
                    + "\"outputTokens\":300,\"reasoningOutputTokens\":0,\"totalTokens\":1200}}}}");
            // Its own final answer, which is not the task's answer.
            emit("{\"method\":\"item/completed\",\"params\":{\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"message-9\","
                    + "\"text\":\"from a child thread\",\"phase\":\"final_answer\"},"
                    + "\"threadId\":\"" + threadId + "\",\"turnId\":\"subagent-turn\"}}");
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId + "\","
                    + "\"turn\":{\"id\":\"subagent-turn\",\"status\":\"completed\","
                    + "\"error\":null,\"durationMs\":1,\"items\":[]}}}");
        }

        /** Starts an item and never reports it terminal, the way a cancelled turn leaves one. */
        private void emitUnfinishedItem(String itemId)
        {
            emit("{\"method\":\"item/started\",\"params\":{\"item\":{"
                    + "\"type\":\"commandExecution\",\"id\":\"" + itemId + "\","
                    + "\"command\":\"sleep 600\",\"status\":\"inProgress\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
        }

        private void completeTurn()
        {
            emit("{\"id\":91,\"method\":\"item/commandExecution/requestApproval\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"itemId\":\"exec-1\",\"command\":\"echo known-secret\","
                    + "\"availableDecisions\":[\"accept\",\"decline\"],"
                    + "\"reason\":null,\"startedAtMs\":1786884871461}}");
            emit("{\"method\":\"item/agentMessage/delta\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"itemId\":\"message-1\",\"delta\":\"draft known-secret\"}}");
            emit("{\"method\":\"item/completed\",\"params\":{\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"message-1\","
                    + "\"text\":\"authoritative known-secret\",\"phase\":\"final_answer\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
            emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\","
                    + "\"tokenUsage\":{\"total\":{\"totalTokens\":100,\"inputTokens\":80,"
                    + "\"cachedInputTokens\":30,\"outputTokens\":20}}}}");
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"completed\",\"error\":null,\"durationMs\":2,\"items\":[]}}}");
        }

        private void failTurn()
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"failed\",\"error\":{\"message\":\"provider failed\"},\"durationMs\":2,\"items\":[]}}}");
        }

        private void disconnect()
        {
            exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        }

        private int resumeCount()
        {
            return resumes.get();
        }

        private List<String> approvalPolicies()
        {
            return approvalPolicies;
        }

        private String approvalDecision()
        {
            return result(91).path("decision").asText();
        }

        private String activeTurn()
        {
            return activeTurn;
        }

        private void holdInterruptCompletion()
        {
            interruptCompletionGate = new CompletableFuture<>();
        }

        private void releaseInterruptCompletion()
        {
            interruptCompletionGate.complete(null);
        }

        private JsonNode result(long id)
        {
            return results.get(id);
        }

        private JsonNode error(long id)
        {
            return errors.get(id);
        }

        private void respond(long id, String result)
        {
            emit("{\"id\":" + id + ",\"result\":" + result + "}");
        }

        /**
         * Delivers one line the way a real process does: on its own reader thread, in order.
         *
         * <p>Delivering inline on the caller's thread would make the caller and the reader the same
         * thread, which is exactly the confusion that hid the dispatch deadlock.
         */
        private void emit(String message)
        {
            String line = message.replace("\n", "").replace("\r", "");
            reader.execute(() -> output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(line + "\n")));
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        }

        @Override
        public void close()
        {
            cancel();
        }
    }

    private record ImmediateProcess(CompletableFuture<ProcessExit> exit)
            implements ManagedProcess
    {
        private static ImmediateProcess success()
        {
            return new ImmediateProcess(CompletableFuture.completedFuture(
                    new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false)));
        }

        private static ImmediateProcess failure()
        {
            return new ImmediateProcess(CompletableFuture.completedFuture(
                    new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false)));
        }

        @Override
        public String launchId()
        {
            return "scripted";
        }

        @Override
        public long pid()
        {
            return 1;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("input is closed"));
        }

        @Override
        public void closeInput() {}

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }
}
