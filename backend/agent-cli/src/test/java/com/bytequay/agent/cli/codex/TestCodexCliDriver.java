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

import com.bytequay.agent.cli.claude.ClaudePermissionBroker;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexCliDriver
{
    private static final String MCP_ENDPOINT = "http://127.0.0.1:9000/internal/agent/claude/mcp";
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
        JsonNode startedThread = supervisor.servers().getFirst().threadRequests().getFirst();
        assertThat(startedThread.path("model").asText()).isEqualTo("gpt-5");
        assertThat(startedThread.path("approvalsReviewer").asText()).isEqualTo("user");
        assertThat(startedThread.path("ephemeral").asBoolean()).isFalse();
        assertThat(startedThread.path("dynamicTools")).isEmpty();
        assertThat(startedThread.path("selectedCapabilityRoots")).isEmpty();
        assertThat(startedThread.path("config").path("mcp_servers")).isEmpty();
        assertThat(startedThread.path("config").path("features").path("multi_agent").asBoolean()).isTrue();

        JsonNode resumedThread = resumedServer.threadRequests().getFirst();
        assertThat(resumedThread.path("model").asText()).isEqualTo("gpt-5");
        assertThat(resumedThread.path("approvalsReviewer").asText()).isEqualTo("user");
        assertThat(resumedThread.path("config").path("mcp_servers")).isEmpty();

        JsonNode startedTurn = resumedServer.turnRequests().getFirst();
        assertThat(startedTurn.path("approvalPolicy").asText()).isEqualTo("on-request");
        assertThat(startedTurn.path("approvalsReviewer").asText()).isEqualTo("user");
        assertThat(Path.of(startedTurn.path("cwd").asText())).isEqualTo(worktree.toRealPath());
        assertThat(startedTurn.path("sandboxPolicy").path("type").asText()).isEqualTo("workspaceWrite");
        assertThat(startedTurn.path("sandboxPolicy").path("writableRoots").get(0).asText())
                .isEqualTo(worktree.toRealPath().toString());
        assertThat(startedTurn.path("sandboxPolicy").path("networkAccess").asBoolean()).isFalse();
        assertThat(startedTurn.path("sandboxPolicy").path("excludeTmpdirEnvVar").asBoolean()).isTrue();
        assertThat(startedTurn.path("sandboxPolicy").path("excludeSlashTmp").asBoolean()).isTrue();
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
        assertThat(driver.capabilities().attachmentMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif");
        assertThat(driver.capabilities().partialAssistantText()).isTrue();
        assertThat(driver.capabilities().nestedAgentActivity()).isTrue();
        assertThat(supervisor.servers())
                .allSatisfy(server -> assertThat(server.approvalPolicies()).containsExactly("on-request"));
        // Sent once per thread rather than per turn, which is equivalent while the lane advertises
        // one permission policy and one sandbox policy.
        assertThat(supervisor.servers())
                .allSatisfy(server -> assertThat(server.sandboxes()).containsExactly("workspace-write"));
        // Declared for thread/backgroundTerminals/list, which is experimental and is the only
        // inventory that can prove a thread owns no live process.
        assertThat(supervisor.servers()).allSatisfy(
                server -> assertThat(server.experimentalApi()).containsExactly(true));

        driver.close();
    }

    @Test
    void probeKeepsRecoveryEvidenceWhenItsProcessTreeIsUnverifiable()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.leaveProbeTreeUnverifiable();

        AgentProviderStatus status = driver(supervisor).probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).contains("process tree could not be verified stopped");
        assertThat(supervisor.acknowledgements).isEmpty();
    }

    @Test
    void steersOnlyTheActiveTurnAndTellsARefusalFromSilence()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.capabilities().steering()).isTrue();
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("steer"));

        turn.steer(new AgentTurnInput("also this", ImmutableList.of()));

        // The request names the turn it was meant for; App Server refuses it for any other.
        assertThat(supervisor.server().steerRequests()).singleElement().satisfies(request -> {
            assertThat(request).contains("\"threadId\":\"thread-1\"");
            assertThat(request).contains("\"expectedTurnId\":\"" + turn.reference().value() + "\"");
            assertThat(request).contains("\"type\":\"text\",\"text\":\"also this\"");
        });

        // The bytes were accepted and App Server answered, but the answer cannot prove whether it
        // applied the input. That is still uncertain, not a safe retry.
        supervisor.server().malformNextSteerResponse();
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("malformed", ImmutableList.of())))
                .isInstanceOf(AgentDeliveryUncertainException.class);

        supervisor.server().refuseSteer();
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("refused", ImmutableList.of())))
                .isInstanceOf(AgentUnavailableException.class)
                .isNotInstanceOf(AgentDeliveryUncertainException.class)
                .hasMessageContaining("turn is not active");

        // No answer and then the process gone: nothing says whether the input landed.
        supervisor.server().ignoreSteer();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            supervisor.server().disconnect();
        });
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("silent", ImmutableList.of())))
                .isInstanceOf(AgentDeliveryUncertainException.class);
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.INTERRUPTED);
        // An ended turn cannot be steered at all.
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("late", ImmutableList.of())))
                .isInstanceOf(AgentUnavailableException.class)
                .isNotInstanceOf(AgentDeliveryUncertainException.class);

        driver.close();
    }

    @Test
    void identifiesARequestRejectedBeforeAnyBytesAreSent()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexAppServerClient client = appServerClient(supervisor);

        assertThatThrownBy(() -> client.request(
                "turn/steer",
                new CodexProtocol.EmptyParams(),
                CodexProtocol.EmptyResponse.class,
                Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
        assertThat(supervisor.servers()).isEmpty();
        client.close();
        assertThatThrownBy(() -> client.request(
                "turn/steer",
                new CodexProtocol.EmptyParams(),
                CodexProtocol.EmptyResponse.class,
                Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
    }

    @Test
    void identifiesEncodingFailureBeforeARequestWrite()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexAppServerClient client = appServerClient(supervisor);
        client.start();

        assertThatThrownBy(() -> client.request(
                "turn/steer",
                new UnencodableParams(),
                CodexProtocol.EmptyResponse.class,
                Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
        assertThat(supervisor.server().steerRequests()).isEmpty();
        client.close();
    }

    @Test
    void forgetsATimedOutRequestAndIgnoresItsLateResponse()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexAppServerClient client = appServerClient(supervisor);
        client.start();
        supervisor.server().ignoreSteer();
        CodexProtocol.TurnSteerParams timedOut = new CodexProtocol.TurnSteerParams(
                "thread-1",
                "turn-timeout",
                ImmutableList.of(new CodexProtocol.TextInput("text", "late")));

        assertThatThrownBy(() -> client.request(
                "turn/steer",
                timedOut,
                CodexProtocol.TurnSteerResponse.class,
                Duration.ofMillis(20)))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Timed out");
        assertThat(client.pendingRequestCount()).isZero();

        supervisor.server().respondToIgnoredSteer();
        CodexProtocol.TurnSteerResponse response = client.request(
                "turn/steer",
                new CodexProtocol.TurnSteerParams(
                        "thread-1",
                        "turn-next",
                        ImmutableList.of(new CodexProtocol.TextInput("text", "next"))),
                CodexProtocol.TurnSteerResponse.class,
                Duration.ofSeconds(1));
        assertThat(response.turnId()).isEqualTo("turn-next");
        client.close();
    }

    @Test
    void treatsAWriteStageFailureAsUncertainDelivery()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("write-failure"));
        supervisor.server().failNextSteerWrite();

        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("maybe written", ImmutableList.of())))
                .isInstanceOf(AgentDeliveryUncertainException.class);

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

        AgentSessionRef reference = session.reference();
        session = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});
        RunningTurn failed = session.start(command("fail"));
        supervisor.servers().getLast().failTurn();
        AgentTurnResult failedResult = failed.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(failedResult.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failedResult.terminalReason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("Codex CLI 0.151.0").contains("provider failed"));

        supervisor.versionOutput("future-build");
        assertThat(driver.probe().version()).contains("future-build");

        session = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});
        RunningTurn disconnected = session.start(command("disconnect"));
        supervisor.servers().getLast().disconnect();
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
    void runsEveryCodexProcessInByteQuaysOwnHome()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);

        driver.probe();
        driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}).close();

        // Probe commands and the task's App Server alike: nothing from ~/.codex may reach a task,
        // and a shell that exports CODEX_HOME must not redirect them either.
        assertThat(supervisor.environments()).isNotEmpty();
        assertThat(supervisor.environments())
                .allSatisfy(environment -> assertThat(environment).containsEntry("CODEX_HOME", codexHome().toString()));
        driver.close();
    }

    @Test
    void namesTheCommandThatSignsByteQuaysHomeIn()
    {
        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor(objectMapper);
        loggedOut.loggedOut();
        CodexCliDriver driver = driver(loggedOut);

        AgentProviderStatus status = driver.probe();

        // A plain "codex login" would sign the user's own home in, not ByteQuay's.
        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.signInCommand()).contains("CODEX_HOME=\"" + codexHome() + "\" /fake/codex login");
        assertThat(status.diagnostic()).contains("not signed in for ByteQuay");
        driver.close();
    }

    @Test
    void reportsAmbientExecPolicyRulesAsUnavailableBeforeAnyTaskExists()
            throws IOException
    {
        Path rules = worktree.resolve(".codex").resolve("rules");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("default.rules"), "prefix_rule(pattern=[\"git\"], decision=\"allow\")\n");
        CodexCliDriver driver = driver(new ScriptedProcessSupervisor(objectMapper));

        AgentProviderStatus status = driver.probe();

        // Every turn start refuses inherited rules, so readiness must say so first, with the fix.
        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.available()).isFalse();
        assertThat(status.version()).contains("0.151.0");
        assertThat(status.diagnostic()).contains("exec-policy rules").contains("move that rules folder aside");
        driver.close();
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
    void launchesTheTaskWithOnlyItsSelectedModelsChildProtocol()
    {
        List<String> v1 = taskArgumentsForModelVersion(Optional.of("v1"));
        assertThat(v1).containsSequence("--enable", "multi_agent");
        assertThat(v1).containsSequence("--disable", "multi_agent_v2");
        assertThat(v1).contains("agents.max_depth=2");

        List<String> v2 = taskArgumentsForModelVersion(Optional.of("v2"));
        assertThat(v2).containsSequence("--disable", "multi_agent");
        assertThat(v2).containsSequence("--enable", "multi_agent_v2");
        assertThat(v2).doesNotContain("agents.max_depth=2");

        for (Optional<String> version : List.of(Optional.<String>empty(), Optional.of("disabled"))) {
            List<String> none = taskArgumentsForModelVersion(version);
            assertThat(none).containsSequence("--disable", "multi_agent");
            assertThat(none).containsSequence("--disable", "multi_agent_v2");
            assertThat(none).doesNotContain("agents.max_depth=2");
        }
    }

    @Test
    void rejectsUnknownOrUnselectableCatalogProfilesBeforeLaunchingATaskProcess()
    {
        ScriptedProcessSupervisor unknown = new ScriptedProcessSupervisor(objectMapper);
        unknown.reportModelVersion(Optional.of("future-version"));
        CodexCliDriver unknownDriver = driver(unknown);
        assertThatThrownBy(() -> unknownDriver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unknown multi-agent version");
        assertThat(unknown.servers()).isEmpty();

        ScriptedProcessSupervisor noDefault = new ScriptedProcessSupervisor(objectMapper);
        noDefault.reportModelCatalog("{\"data\":[{\"id\":\"gpt-5\",\"displayName\":\"GPT-5\","
                + "\"description\":\"d\",\"supportedReasoningEfforts\":[],"
                + "\"defaultReasoningEffort\":\"low\",\"isDefault\":false,\"hidden\":false,"
                + "\"multiAgentVersion\":\"v1\"}]}");
        CodexCliDriver noDefaultDriver = driver(noDefault);
        assertThatThrownBy(() -> noDefaultDriver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("no visible default model");
        assertThat(noDefault.servers()).isEmpty();

        ScriptedProcessSupervisor missing = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver missingDriver = driver(missing);
        assertThatThrownBy(() -> missingDriver.open(context(
                Optional.empty(),
                (request, timeout) -> CompletableFuture.completedFuture(
                        ProviderApprovalDecision.allow(Optional.empty())),
                Optional.of("not-in-catalog"),
                Optional.<String>empty()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("not in the current model catalog");
        assertThat(missing.servers()).isEmpty();
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
    void rejectsAnApprovalFromAChildThreadOutsideTheCurrentAgentGraph()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), approval), event -> {});
        session.start(command("approval graph"));
        AppServerProcess server = supervisor.server();

        server.emit("""
                {"id":103,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo child",
                "itemId":"exec-child","startedAtMs":1786884871465,
                "threadId":"unknown-child","turnId":"child-turn"}}
                """);

        awaitCondition(() -> server.error(103) != null);
        assertThat(server.error(103).path("message").asText()).contains("not in the active agent graph");
        assertThat(approval).hasValue(null);
        session.close();
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

        // Measured on codex-cli 0.152.1: an escalated command offers accept, the amendment variant
        // and cancel, but no decline. The card offers Allow once, ByteQuay's own Allow for this task
        // and Deny; the amendment is never offered, and a deny is sent as decline, which the server honours.
        decision.set(ProviderApprovalDecision.deny());
        server.emit("""
                {"id":98,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept",{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["/bin/zsh","-lc","printf hello > /Users/me/probe.txt"]}},"cancel"],
                "command":"/bin/zsh -lc 'printf hello > /Users/me/probe.txt'","itemId":"exec-3",
                "startedAtMs":1786884871464,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.result(98) != null);
        assertThat(server.result(98).path("decision").asText()).isEqualTo("decline");
        assertThat(approval.get().availableDecisions())
                .containsExactly(
                        ProviderApprovalRequest.Decision.ALLOW_ONCE,
                        ProviderApprovalRequest.Decision.ALLOW_FOR_TASK,
                        ProviderApprovalRequest.Decision.DENY);
        // A redirect makes the script compound, so only this exact command line is covered.
        assertThat(approval.get().taskScope()).contains("/bin/zsh -lc 'printf hello > /Users/me/probe.txt'");

        // A read of GitHub is answered without a card: nothing reaches the core, and the server gets accept.
        ProviderApprovalRequest before = approval.get();
        server.emit("""
                {"id":99,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],
                "command":"/bin/zsh -lc \\"gh issue list --repo trinodb/trino --state open --limit 100\\"","itemId":"exec-4",
                "startedAtMs":1786884871465,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn()));
        awaitCondition(() -> server.result(99) != null);
        assertThat(server.result(99).path("decision").asText()).isEqualTo("accept");
        assertThat(approval.get()).isSameAs(before);
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
    void provesTheLaunchProfileAndItsLayersBeforeCreatingAThread()
    {
        ScriptedProcessSupervisor noLayers = new ScriptedProcessSupervisor(objectMapper);
        noLayers.configLayers = "[]";
        assertThatThrownBy(() -> driver(noLayers).open(
                context(Optional.empty(), new AtomicReference<>()),
                event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("exactly one isolated user layer");
        assertThat(noLayers.server().threadRequests()).isEmpty();

        ScriptedProcessSupervisor wrongEffectiveProfile = new ScriptedProcessSupervisor(objectMapper);
        wrongEffectiveProfile.configBody = "{\"web_search\":\"enabled\"}";
        assertThatThrownBy(() -> driver(wrongEffectiveProfile).open(
                context(Optional.empty(), new AtomicReference<>()),
                event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("web_search");
        assertThat(wrongEffectiveProfile.server().threadRequests()).isEmpty();

        ScriptedProcessSupervisor wrongSessionFlags = new ScriptedProcessSupervisor(objectMapper);
        ArrayNode layers = objectMapper.createArrayNode();
        ObjectNode userLayer = layers.addObject();
        userLayer.putObject("name")
                .put("type", "user")
                .put("file", codexHome().resolve("config.toml").toString());
        userLayer.set("config", objectMapper.createObjectNode());
        ObjectNode sessionLayer = layers.addObject();
        sessionLayer.putObject("name").put("type", "sessionFlags");
        sessionLayer.set("config", objectMapper.createObjectNode());
        wrongSessionFlags.configLayers = layers.toString();
        assertThatThrownBy(() -> driver(wrongSessionFlags).open(
                context(Optional.empty(), new AtomicReference<>()),
                event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("web_search");
        assertThat(wrongSessionFlags.server().threadRequests()).isEmpty();

        ScriptedProcessSupervisor inheritedThreadServer = new ScriptedProcessSupervisor(objectMapper);
        inheritedThreadServer.mcpStatuses = "{\"data\":[{\"name\":\"ambient\",\"pluginId\":null}],"
                + "\"nextCursor\":null}";
        assertThatThrownBy(() -> driver(inheritedThreadServer).open(
                context(Optional.empty(), new AtomicReference<>()),
                event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("thread inherited an MCP server");
        assertThat(inheritedThreadServer.server().threadRequests()).hasSize(1);
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
                Optional.of("gpt-5"),
                Optional.of("high")));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        session = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn second = session.start(new AgentTurnCommand(
                "turn-b",
                "second",
                ImmutableList.<AgentAttachment>of(),
                Duration.ofMinutes(5),
                Optional.of("gpt-5"),
                Optional.of("minimal")));
        supervisor.servers().getLast().completeTurn();
        second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Each turn carries the snapshot taken when it was accepted; a later default cannot
        // rewrite an earlier turn.
        assertThat(supervisor.servers())
                .extracting(AppServerProcess::turnSettings)
                .containsExactly(ImmutableList.of("gpt-5/high"), ImmutableList.of("gpt-5/minimal"));
    }

    @Test
    void closingASessionInterruptsItsTurnOnceItsProcessTreeIsVerifiedStopped()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("stop"));

        session.close();

        // Killing the process is the stop; the turn reports that, and the verified tree is what
        // lets the task's worktree be released.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("stopped"));
        assertThatThrownBy(() -> session.start(command("again")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closingASessionFailsAndKeepsItsTurnUnsettledWhenTheProcessTreeCannotBeVerified()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.leaveProcessTreeUnverifiable();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("stop"));

        // Root exit is not quiescence: a tool the model started may have outlived the server, so
        // the close refuses to claim the worktree is safe and the turn keeps its claim.
        assertThatThrownBy(session::close)
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("verified stopped");
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.providerQuiescent()).isFalse();
        // Asking again re-verifies rather than pretending the earlier close succeeded.
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
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
    void reconcilesALiveChildStartWithItsTerminalHistoryWhenCompletionWasMissed()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportCompletedChildCommand("child-reconciled");
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("reconcile child"));

        supervisor.server().emitThreadStarted("child-reconciled", "Reconciled child");
        supervisor.server().emitUnfinishedChildCommand("child-reconciled");
        supervisor.server().completeTurn();

        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(events)
                .filteredOn(ProviderAgentEvent.Command.class::isInstance)
                .map(ProviderAgentEvent::providerEventId)
                .contains(Optional.of("child-exec:started"), Optional.of("child-exec:completed"));
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.BACKFILL_STARTED)
                .singleElement()
                .satisfies(event -> assertThat(event.source().providerThreadId()).contains("child-reconciled"));
    }

    @Test
    void recoversTheCurrentRootFinalAnswerWithoutEchoingItsHistoricalPrompt()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportRecoveredRootAnswer();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("recover root answer"));

        supervisor.server().completeTurnWithoutOutput();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("recovered root answer");
        assertThat(events)
                .filteredOn(ProviderAgentEvent.Message.class::isInstance)
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .contains("recovered root answer")
                .doesNotContain("do not echo this prompt");
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
    void sendsATaskOwnedImageAsLocalImageInputAndRefusesAnythingElse()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        Path image = worktree.resolve("blobs/attachments/shot.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1});

        // Only a task-owned image may be named to the App Server, whatever the caller claims.
        Path elsewhere = Files.createTempFile("elsewhere", ".png");
        assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                "turn-elsewhere",
                "look",
                ImmutableList.of(new AgentAttachment("att-2", "elsewhere.png", "image/png", elsewhere)),
                Duration.ofMinutes(1))))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("task-owned image");
        assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                "turn-text",
                "look",
                ImmutableList.of(new AgentAttachment("att-3", "notes.txt", "text/plain", image)),
                Duration.ofMinutes(1))))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("task-owned image");

        RunningTurn turn = session.start(new AgentTurnCommand(
                "turn-image",
                "What is in this screenshot?",
                ImmutableList.of(new AgentAttachment("att-1", "shot.png", "image/png", image)),
                Duration.ofMinutes(1)));
        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(supervisor.server().turnInputs().getLast())
                .contains("\"type\":\"text\"")
                .contains("\"type\":\"localImage\"")
                .contains(image.toString());
        session.close();
    }

    @Test
    void cancelsATurnWhoseStreamingMessageAndReasoningWereLeftOpen()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("stop me"));
        supervisor.server().emitOpenModelOutput("msg-open", "agentMessage");
        supervisor.server().emitOpenModelOutput("reasoning-open", "reasoning");

        turn.cancel();

        // The interrupt lands mid-token, so the App Server never completes the message or the
        // reasoning it was streaming. Neither can write a file, so nothing is left to wait for.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
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

        // The root result is not accepted as complete, but closing the per-turn process makes the
        // worktree safe even when the inventories never became quiet.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason().orElseThrow()).contains("background terminal proc-1");
        assertThat(result.providerQuiescent()).isTrue();
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

        AgentSession resumed = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn second = resumed.start(command("two"));
        supervisor.servers().getLast().completeTurn();

        // The earlier turn's outcome is already decided. Carrying its unresolved item forward
        // would fail every turn after it for a reason that is no longer about them.
        assertThat(second.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        resumed.close();
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

        supervisor.server().emitThreadStarted("child-thread-7", "Child seven");
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
        // Its final answer is its own. It is FINAL for that agent, and every event it produces
        // names a parent thread, which is what keeps it out of the task's recorded final response.
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message)
                .allSatisfy(event -> assertThat(event.source().parentProviderThreadId()).isPresent());

        supervisor.server().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        session.close();
    }

    @Test
    void discoversASubagentWithItsLineageBeforeAnythingItDidIsPublished()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("delegate"));

        supervisor.server().emitThreadStarted("child-1", "History tests");
        supervisor.server().emitSubagentActivity("child-1");

        awaitCondition(() -> events.stream().anyMatch(event -> event.toString().contains("from a child thread")));
        List<ProviderAgentEvent> discovery = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .toList();
        assertThat(discovery).hasSize(1);
        assertThat(((ProviderAgentEvent.Lifecycle) discovery.getFirst()).message()).isEqualTo("History tests");
        assertThat(discovery.getFirst().source().providerThreadId()).contains("child-1");
        assertThat(discovery.getFirst().source().parentProviderThreadId()).contains("thread-1");
        // Routing is in place before discovery is published: nothing about the child reaches the
        // log before the event that announced it.
        assertThat(events.indexOf(discovery.getFirst()))
                .isLessThan(events.stream()
                        .filter(event -> event.source().providerThreadId().filter("child-1"::equals).isPresent())
                        .filter(event -> !(event instanceof ProviderAgentEvent.Lifecycle))
                        .findFirst()
                        .map(events::indexOf)
                        .orElse(Integer.MAX_VALUE));
        // Every child event carries the same lineage as its discovery.
        assertThat(events)
                .filteredOn(event -> event.source().providerThreadId().filter("child-1"::equals).isPresent())
                .allSatisfy(event -> {
                    assertThat(event.source().parentProviderThreadId()).contains("thread-1");
                    assertThat(event.source().logicalAgentPath()).contains("History tests");
                });

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.close();
    }

    @Test
    void buffersOutOfOrderGrandchildActivityUntilBothExplicitParentsAreKnown()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("out of order lineage"));

        supervisor.server().emitThreadStarted("grandchild-early", "child-late", "Early grandchild");
        supervisor.server().emitSubagentActivity("grandchild-early");
        supervisor.server().emitThreadStarted("child-late", "Late child");

        awaitCondition(() -> events.stream().anyMatch(event -> event.toString().contains("from a child thread")));
        ProviderAgentEvent grandchild = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .filter(event -> event.source().providerThreadId().filter("grandchild-early"::equals).isPresent())
                .findFirst()
                .orElseThrow();
        assertThat(grandchild.source().parentProviderThreadId()).contains("child-late");
        assertThat(events.indexOf(grandchild))
                .isLessThan(events.stream()
                        .filter(event -> event.source().providerThreadId().filter("grandchild-early"::equals).isPresent())
                        .filter(event -> !(event instanceof ProviderAgentEvent.Lifecycle))
                        .findFirst()
                        .map(events::indexOf)
                        .orElseThrow());

        supervisor.server().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
    }

    @Test
    void failsWithADiagnosticWhenBufferedChildActivityNeverReceivesLineage()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(300));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("orphan activity"));

        supervisor.server().emitSubagentActivity("orphan-child");
        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("buffered notification(s)")
                .contains("orphan-child")
                .contains("no confirmed lineage"));
    }

    @Test
    void recordsTheSubagentsPromptAndItsTerminalOutcome()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("delegate"));

        supervisor.server().emitThreadStarted("child-2", "Preview backend");
        supervisor.server().emitSpawnAgent("child-2", "trace the preview request path");
        supervisor.server().emitChildTurnCompleted("child-2", "completed");

        awaitCondition(() -> events.stream().anyMatch(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED));
        // What the parent asked the child to do is the only record of its purpose.
        ProviderAgentEvent.Message prompt = (ProviderAgentEvent.Message) events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.PROMPT)
                .findFirst()
                .orElseThrow();
        assertThat(prompt.text()).isEqualTo("trace the preview request path");
        assertThat(prompt.source().providerThreadId()).contains("child-2");
        // A child's turn completing ends that agent, never the task's turn.
        assertThat(turn.completion().toCompletableFuture()).isNotDone();

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        session.close();
    }

    @Test
    void recoversAChildOnlyHistoryNamesBetweenExplicitBackfillMarkers()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("delegate"));
        supervisor.reportUnannouncedSubagent();

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // A child the live stream never mentioned is still discovered, and what history holds is
        // recovered between markers that say it is recovered rather than new.
        List<ProviderAgentEvent.LifecyclePhase> phases = events.stream()
                .filter(event -> event.source().providerThreadId().filter("child-unannounced"::equals).isPresent())
                .filter(ProviderAgentEvent.Lifecycle.class::isInstance)
                .map(event -> ((ProviderAgentEvent.Lifecycle) event).phase())
                .toList();
        assertThat(phases).containsSubsequence(
                ProviderAgentEvent.LifecyclePhase.AGENT_STARTED,
                ProviderAgentEvent.LifecyclePhase.BACKFILL_STARTED,
                ProviderAgentEvent.LifecyclePhase.BACKFILL_COMPLETED);
        // Process shutdown proves the recovered child stopped; it does not invent a provider
        // lifecycle event that history never supplied.
        assertThat(phases).doesNotContain(ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED);
        session.close();
    }

    @Test
    void recursivelyDiscoversV1ChildrenAndGrandchildrenOnlyFromTheirOwningHistory()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportRecursiveV1History();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("nested history"));

        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.server().backgroundTerminalThreads())
                .contains("thread-1", "child-history", "grandchild-history");
        ProviderAgentEvent child = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .filter(event -> event.source().providerThreadId().filter("child-history"::equals).isPresent())
                .findFirst()
                .orElseThrow();
        ProviderAgentEvent grandchild = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .filter(event -> event.source().providerThreadId().filter("grandchild-history"::equals).isPresent())
                .findFirst()
                .orElseThrow();
        assertThat(child.source().parentProviderThreadId()).contains("thread-1");
        assertThat(grandchild.source().parentProviderThreadId()).contains("child-history");
    }

    @Test
    void treatsV2StartedAsAnEdgeButNotInteractedAndPreservesTheFullAgentPath()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.reportV2History();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("v2 history"));

        supervisor.server().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);

        List<ProviderAgentEvent> discoveries = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .toList();
        assertThat(discoveries).singleElement().satisfies(discovery -> {
            assertThat(discovery.source().providerThreadId()).contains("v2-child");
            assertThat(discovery.source().parentProviderThreadId()).contains("thread-1");
            assertThat(discovery.source().logicalAgentPath()).contains("/agents/team/reviewer");
        });
        assertThat(supervisor.server().backgroundTerminalThreads()).doesNotContain("not-a-child");
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.PROMPT)
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .contains("supplementary prompt");
    }

    @Test
    void waitsForCanonicalV2PathWhenThreadMetadataArrivesFirst()
            throws Exception
    {
        assertStableLiveV2Path(true);
    }

    @Test
    void keepsCanonicalV2PathWhenThreadMetadataArrivesLater()
            throws Exception
    {
        assertStableLiveV2Path(false);
    }

    @Test
    void preservesNestedV2PathsForAChildAndItsDirectGrandchild()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.reportNestedV2History();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("nested v2"));

        supervisor.server().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);

        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .extracting(event -> event.source().providerThreadId().orElseThrow(),
                        event -> event.source().parentProviderThreadId().orElseThrow(),
                        event -> event.source().logicalAgentPath().orElseThrow())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("v2-child", "thread-1", "/agents/reviewer"),
                        org.assertj.core.groups.Tuple.tuple(
                                "v2-grandchild",
                                "v2-child",
                                "/agents/reviewer/researcher"));
    }

    @Test
    void rejectsUnknownRelativeOrUnstableV2SupplementaryActivity()
            throws Exception
    {
        AgentTurnResult unknown = runInvalidV2History("unknown-child", "/agents/unknown");
        assertThat(unknown.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("graph identity"));

        AgentTurnResult relative = runInvalidV2History("v2-child", "relative/path");
        assertThat(relative.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("absolute"));

        AgentTurnResult changed = runInvalidV2History("v2-child", "/agents/changed");
        assertThat(changed.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("path changed"));
        AgentTurnResult rootPath = runInvalidRootV2History();
        assertThat(rootPath.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("not nested"));
        AgentTurnResult notNested = runInvalidNestedV2History();
        assertThat(notNested.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("not nested"));
        assertThat(List.of(unknown, relative, changed, rootPath, notNested)).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(result.providerQuiescent()).isTrue();
        });
    }

    @Test
    void rejectsUnknownIdentitiesFromBothNonselectedSupplementaryFormats()
            throws Exception
    {
        AgentTurnResult v1UnderV2 = runUnknownSupplement(
                Optional.of("v2"),
                ScriptedProcessSupervisor::reportUnknownV1Supplement);
        AgentTurnResult v2UnderV1 = runUnknownSupplement(
                Optional.of("v1"),
                ScriptedProcessSupervisor::reportUnknownV2Supplement);

        assertThat(List.of(v1UnderV2, v2UnderV1)).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("graph identity"));
        });
    }

    @Test
    void recoversSameItemIdFromDistinctChildTurns()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportDuplicateChildItemIdsAcrossTurns();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("duplicate item ids"));

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message
                        && ((ProviderAgentEvent.Message) event).messageKind()
                                == ProviderAgentEvent.MessageKind.ANSWER
                        && event.source().providerThreadId().filter("duplicate-child"::equals).isPresent())
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .containsExactly("first child turn", "second child turn");
    }

    @Test
    void failsClosedOnAnUnknownHistoricalItemThenStopsTheProcessTree()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportUnknownHistoryItem();
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(300));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("unknown history"));

        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("future-item"));
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

        assertThatThrownBy(() -> driver.open(
                context(Optional.of(session.reference()), new AtomicReference<>()),
                event -> {}))
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
    void ignoresPriorRootTurnChildrenUnknownItemsAndUnfinishedWorkAfterResume()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession firstSession = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn first = firstSession.start(command("first process"));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        supervisor.reportOnlyPriorRootHistory();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AgentSession resumed = driver.open(
                context(Optional.of(firstSession.reference()), new AtomicReference<>()),
                events::add);
        RunningTurn current = resumed.start(command("current process"));
        supervisor.servers().getLast().completeTurn();
        AgentTurnResult result = current.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(events).noneMatch(event -> event.source().providerThreadId().filter("old-child"::equals).isPresent());
        assertThat(supervisor.servers().getLast().backgroundTerminalThreads()).doesNotContain("old-child");
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
        assertThatThrownBy(() -> driver.open(
                context(Optional.of(reference), new AtomicReference<>()),
                event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
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

    private CodexAppServerClient appServerClient(ProcessSupervisor supervisor)
    {
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "task-1"),
                ImmutableList.of("/fake/codex", "app-server"),
                worktree,
                ImmutableMap.of(),
                1024,
                1024,
                Duration.ofMinutes(1),
                Duration.ZERO);
        return new CodexAppServerClient(
                supervisor,
                spec,
                objectMapper,
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor, Duration quiescenceTimeout)
    {
        try {
            CodexHome.prepare(codexHome());
        }
        catch (IOException e) {
            throw new AssertionError("unable to prepare test Codex home", e);
        }
        return new CodexCliDriver(
                "/fake/codex",
                objectMapper,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        // A shell that exports its own home must not redirect ByteQuay's processes.
                        "CODEX_HOME", "/Users/someone/.codex",
                        "OPENAI_API_KEY", "known-secret"),
                codexHome(),
                new ClaudePermissionBroker(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> ""),
                quiescenceTimeout);
    }

    /** ByteQuay's own Codex home for this test, beside the worktree. */
    private Path codexHome()
    {
        return worktree.resolve(".codex");
    }

    private List<String> taskArgumentsForModelVersion(Optional<String> version)
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(version);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        List<String> arguments = supervisor.serverArgv().getFirst();
        session.close();
        return arguments;
    }

    private AgentTurnResult runInvalidV2History(String threadId, String agentPath)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.reportInvalidV2History(threadId, agentPath);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid v2"));
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void assertStableLiveV2Path(boolean threadMetadataFirst)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("live v2 path"));
        String threadId = "live-v2-child";
        String agentPath = "/agents/live-reviewer";

        if (threadMetadataFirst) {
            supervisor.server().emitThreadStarted(threadId, "Nickname must not become the path");
            supervisor.server().emitSubagentActivity(threadId);
            supervisor.server().emitV2Started(threadId, agentPath);
        }
        else {
            supervisor.server().emitV2Started(threadId, agentPath);
            supervisor.server().emitThreadStarted(threadId, "Nickname must not replace the path");
            supervisor.server().emitSubagentActivity(threadId);
        }
        supervisor.server().completeTurn();

        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        List<ProviderAgentEvent> childEvents = events.stream()
                .filter(event -> event.source().providerThreadId().filter(threadId::equals).isPresent())
                .toList();
        assertThat(childEvents)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .singleElement()
                .satisfies(event -> assertThat(((ProviderAgentEvent.Lifecycle) event).message())
                        .isEqualTo(agentPath));
        assertThat(childEvents).allSatisfy(event ->
                assertThat(event.source().logicalAgentPath()).contains(agentPath));
        int discovery = childEvents.indexOf(childEvents.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .findFirst()
                .orElseThrow());
        int firstActivity = childEvents.indexOf(childEvents.stream()
                .filter(event -> !(event instanceof ProviderAgentEvent.Lifecycle))
                .findFirst()
                .orElseThrow());
        assertThat(discovery).isLessThan(firstActivity);
    }

    private AgentTurnResult runUnknownSupplement(
            Optional<String> version,
            Consumer<ScriptedProcessSupervisor> history)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(version);
        history.accept(supervisor);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("unknown supplement"));
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private AgentTurnResult runInvalidNestedV2History()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.reportNestedV2History("/agents/other/researcher");
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid nested v2"));
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private AgentTurnResult runInvalidRootV2History()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.reportRootV2History("/");
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid root v2"));
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void bridgedSessionLaunchesWithTheTaskMcpEndpointAndKeepsTheTokenOffTheCommandLine()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.configBody = "{\"mcp_servers\":{\"bytequay\":{\"url\":\"" + MCP_ENDPOINT + "\"}}}";
        supervisor.mcpStatuses = "{\"data\":[{\"name\":\"bytequay\",\"pluginId\":null}],\"nextCursor\":null}";
        CodexCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        AgentSessionContext bridged = new AgentSessionContext(
                plain.taskId(),
                plain.worktree(),
                plain.runtimeDirectory(),
                plain.providerDirectory(),
                plain.permissionPolicy(),
                plain.sessionReference(),
                plain.approvals(),
                plain.model(),
                plain.effort(),
                plain.textOnly(),
                Optional.of(new CodeGraphBridge(query -> "graph:" + query, "use the graph")));

        AgentSession session = driver.open(bridged, event -> {});
        List<String> arguments = supervisor.serverArgv().getFirst();
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.url=\"" + MCP_ENDPOINT + "\"");
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.bearer_token_env_var=\"BYTEQUAY_CODEGRAPH_TOKEN\"");
        assertThat(arguments).containsSequence("-c", "developer_instructions=\"use the graph\"");
        Map<String, String> environment = supervisor.environments().stream()
                .filter(candidate -> candidate.containsKey("BYTEQUAY_CODEGRAPH_TOKEN"))
                .findFirst()
                .orElseThrow();
        String token = environment.get("BYTEQUAY_CODEGRAPH_TOKEN");
        assertThat(token).isNotBlank();
        assertThat(arguments).noneMatch(argument -> argument.contains(token));
        // The thread leaves the MCP map to the launch flags instead of emptying it.
        assertThat(supervisor.server().threadRequests().getFirst().path("config").has("mcp_servers")).isFalse();
        // Opening ran the isolation guard against the effective config, which exposes exactly this server.
        session.close();
    }

    @Test
    void unbridgedLaunchExposesNoMcpServerAndRejectsAnAmbientOne()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        assertThat(supervisor.serverArgv().getFirst()).noneMatch(argument -> argument.startsWith("mcp_servers") || argument.startsWith("developer_instructions"));
        assertThat(supervisor.environments()).noneMatch(candidate -> candidate.containsKey("BYTEQUAY_CODEGRAPH_TOKEN"));
        session.close();

        ScriptedProcessSupervisor ambient = new ScriptedProcessSupervisor(objectMapper);
        ambient.configBody = "{\"mcp_servers\":{\"evil\":{\"url\":\"http://127.0.0.1:1/\"}}}";
        // The isolation guard reads the effective config while the session opens.
        assertThatThrownBy(() -> driver(ambient).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("MCP servers");
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

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentApprovalHandler approvals,
            Optional<String> model,
            Optional<String> effort)
    {
        return new AgentSessionContext(
                "task-1",
                worktree,
                worktree,
                worktree.resolve("provider"),
                AgentPermissionPolicy.AUTO,
                reference,
                approvals,
                model,
                effort,
                false);
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
        private final List<List<String>> serverArgv = new CopyOnWriteArrayList<>();
        /** The environment of every process launched, App Server and probe alike, in launch order. */
        private final List<Map<String, String>> environments = new CopyOnWriteArrayList<>();
        private volatile String managedRequirements = "{\"requirements\":null}";
        private volatile String hooks = "[]";
        private volatile String configLayers;
        private volatile String configBody = "{}";
        private volatile String mcpStatuses;
        private volatile String skills = "[]";
        private volatile String backgroundTerminals = "{\"data\":[],\"nextCursor\":null}";
        private final ConcurrentMap<String, String> historyItems = new ConcurrentHashMap<>();
        private volatile String models = modelCatalog(Optional.of("v1"));
        private volatile ProcessExit.TreeTermination termination = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        private volatile ProcessExit.TreeTermination probeTermination = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        private final List<String> acknowledgements = new CopyOnWriteArrayList<>();

        /** Makes every App Server exit report a process tree that could not be observed gone. */
        private void leaveProcessTreeUnverifiable()
        {
            termination = ProcessExit.TreeTermination.UNVERIFIABLE;
        }

        private void leaveProbeTreeUnverifiable()
        {
            probeTermination = ProcessExit.TreeTermination.UNVERIFIABLE;
        }

        /** Names a subagent thread in history that the notification stream never mentioned. */
        private void reportUnannouncedSubagent()
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"collabAgentToolCall\",\"id\":\"spawn-missed\","
                    + "\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"thread-1\",\"receiverThreadIds\":[\"child-unannounced\"],"
                    + "\"agentsStates\":{\"child-unannounced\":{\"status\":\"completed\",\"message\":null}},"
                    + "\"prompt\":\"inspect history\"}}],\"nextCursor\":null}");
            historyItems.put("child-unannounced", "{\"data\":[{\"turnId\":\"child-turn\",\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"child-message\","
                    + "\"text\":\"recovered child result\",\"phase\":\"final_answer\"}}],"
                    + "\"nextCursor\":null}");
        }

        /** Reports an item history still says is running, which the live stream never delivered. */
        private void reportUnfinishedHistoryItem()
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"commandExecution\",\"cwd\":\"/tmp/wt\",\"commandActions\":[],\"id\":\"exec-missed\","
                    + "\"command\":\"sleep 600\",\"status\":\"inProgress\"}}],\"nextCursor\":null}");
        }

        private void reportSettledHistory()
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"commandExecution\",\"cwd\":\"/tmp/wt\",\"commandActions\":[],\"id\":\"exec-missed\","
                    + "\"command\":\"sleep 600\",\"status\":\"completed\"}}],\"nextCursor\":null}");
        }

        private static String modelCatalog(Optional<String> multiAgentVersion)
        {
            String version = multiAgentVersion
                    .map(value -> ",\"multiAgentVersion\":\"" + value + "\"")
                    .orElse("");
            return "{\"data\":[{\"id\":\"gpt-5\",\"displayName\":\"GPT-5\","
                    + "\"description\":\"d\",\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"low\"}],"
                    + "\"defaultReasoningEffort\":\"low\",\"isDefault\":true,\"hidden\":false"
                    + version + "}]}";
        }

        private void reportModelVersion(Optional<String> multiAgentVersion)
        {
            models = modelCatalog(multiAgentVersion);
        }

        private void reportModelCatalog(String catalog)
        {
            models = catalog;
        }

        private void reportRecursiveV1History()
        {
            historyItems.put("thread-1", v1SpawnHistory(
                    "spawn-child",
                    "thread-1",
                    "child-history",
                    "inspect the child"));
            historyItems.put("child-history", v1SpawnHistory(
                    "spawn-grandchild",
                    "child-history",
                    "grandchild-history",
                    "inspect the grandchild"));
            historyItems.put("grandchild-history", "{\"data\":[{\"turnId\":\"grandchild-turn\",\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"grandchild-message\","
                    + "\"text\":\"nested result\",\"phase\":\"final_answer\"}}],\"nextCursor\":null}");
        }

        private void reportDuplicateChildItemIdsAcrossTurns()
        {
            historyItems.put("thread-1", v1SpawnHistory(
                    "spawn-duplicate-child",
                    "thread-1",
                    "duplicate-child",
                    "compare two turns"));
            historyItems.put("duplicate-child", "{\"data\":["
                    + "{\"turnId\":\"child-turn-1\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"same-item\",\"text\":\"first child turn\",\"phase\":\"final_answer\"}},"
                    + "{\"turnId\":\"child-turn-2\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"same-item\",\"text\":\"second child turn\",\"phase\":\"final_answer\"}}"
                    + "],\"nextCursor\":null}");
        }

        private void reportV2History()
        {
            historyItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-start\",\"agentThreadId\":\"v2-child\","
                    + "\"agentPath\":\"/agents/team/reviewer\",\"kind\":\"started\"}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"collabAgentToolCall\","
                    + "\"id\":\"v1-supplement\",\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"thread-1\",\"receiverThreadIds\":[\"v2-child\"],"
                    + "\"agentsStates\":{},\"prompt\":\"supplementary prompt\"}}"
                    + "],\"nextCursor\":null}");
            historyItems.put("v2-child", "{\"data\":["
                    + "{\"turnId\":\"child-turn\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-upward\",\"agentThreadId\":\"thread-1\","
                    + "\"agentPath\":\"/\",\"kind\":\"interacted\"}},"
                    + "{\"turnId\":\"child-turn\",\"item\":{\"type\":\"collabAgentToolCall\","
                    + "\"id\":\"v1-wait\",\"tool\":\"wait\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"v2-child\",\"receiverThreadIds\":[\"thread-1\"],"
                    + "\"agentsStates\":{\"thread-1\":{\"status\":\"completed\",\"message\":null}},"
                    + "\"prompt\":null}}"
                    + "],\"nextCursor\":null}");
        }

        private void reportNestedV2History()
        {
            reportNestedV2History("/agents/reviewer/researcher");
        }

        private void reportNestedV2History(String grandchildPath)
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-child-start\","
                    + "\"agentThreadId\":\"v2-child\",\"agentPath\":\"/agents/reviewer\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
            historyItems.put("v2-child", "{\"data\":[{\"turnId\":\"child-turn\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-grandchild-start\","
                    + "\"agentThreadId\":\"v2-grandchild\","
                    + "\"agentPath\":\"" + grandchildPath + "\",\"kind\":\"started\"}}],"
                    + "\"nextCursor\":null}");
        }

        private void reportRootV2History(String childPath)
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-child-start\","
                    + "\"agentThreadId\":\"v2-child\",\"agentPath\":\"" + childPath + "\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
        }

        private void reportInvalidV2History(String secondThreadId, String secondPath)
        {
            historyItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-start\",\"agentThreadId\":\"v2-child\","
                    + "\"agentPath\":\"/agents/reviewer\",\"kind\":\"started\"}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-invalid\",\"agentThreadId\":\"" + secondThreadId + "\","
                    + "\"agentPath\":\"" + secondPath + "\",\"kind\":\"interacted\"}}"
                    + "],\"nextCursor\":null}");
        }

        private void reportUnknownV1Supplement()
        {
            historyItems.put("thread-1", v1SpawnHistory(
                    "nonselected-v1",
                    "thread-1",
                    "unknown-v1-child",
                    "must already exist"));
        }

        private void reportUnknownV2Supplement()
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"nonselected-v2\","
                    + "\"agentThreadId\":\"unknown-v2-child\",\"agentPath\":\"/agents/unknown\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
        }

        private void reportUnknownHistoryItem()
        {
            historyItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"futureWorkItem\",\"id\":\"future-item\"}}],\"nextCursor\":null}");
        }

        private void reportCompletedChildCommand(String threadId)
        {
            historyItems.put(threadId, "{\"data\":[{\"turnId\":\"child-turn\",\"item\":{"
                    + "\"type\":\"commandExecution\",\"cwd\":\"/tmp/wt\",\"commandActions\":[],"
                    + "\"id\":\"child-exec\",\"command\":\"echo done\",\"status\":\"completed\","
                    + "\"aggregatedOutput\":\"done\",\"exitCode\":0}}],\"nextCursor\":null}");
        }

        private void reportOnlyPriorRootHistory()
        {
            historyItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"prior-turn\",\"item\":{\"type\":\"collabAgentToolCall\","
                    + "\"id\":\"old-spawn\",\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"thread-1\",\"receiverThreadIds\":[\"old-child\"],"
                    + "\"agentsStates\":{},\"prompt\":\"old work\"}},"
                    + "{\"turnId\":\"prior-turn\",\"item\":{\"type\":\"commandExecution\","
                    + "\"cwd\":\"/tmp/wt\",\"commandActions\":[],\"id\":\"old-running\","
                    + "\"command\":\"sleep 600\",\"status\":\"inProgress\"}},"
                    + "{\"turnId\":\"prior-turn\",\"item\":{\"type\":\"futureWorkItem\","
                    + "\"id\":\"old-unknown\"}}],\"nextCursor\":null}");
        }

        private void reportRecoveredRootAnswer()
        {
            historyItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"userMessage\","
                    + "\"id\":\"root-prompt\",\"content\":[{\"type\":\"text\","
                    + "\"text\":\"do not echo this prompt\"}]}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"root-answer\",\"text\":\"recovered root answer\","
                    + "\"phase\":\"final_answer\"}}],\"nextCursor\":null}");
        }

        private static String v1SpawnHistory(String itemId, String sender, String receiver, String prompt)
        {
            return "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"collabAgentToolCall\",\"id\":\"" + itemId + "\","
                    + "\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"" + sender + "\",\"receiverThreadIds\":[\"" + receiver + "\"],"
                    + "\"agentsStates\":{\"" + receiver + "\":{\"status\":\"completed\",\"message\":null}},"
                    + "\"prompt\":\"" + prompt + "\"}}],\"nextCursor\":null}";
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
        public void acknowledge(String launchId)
        {
            acknowledgements.add(launchId);
        }

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
            environments.add(spec.environment());
            if (executableMissing) {
                throw new AgentUnavailableException("Unable to start process: /fake/codex", new IOException("missing"));
            }
            if (spec.argv().contains("--version")) {
                output.onOutput(
                        ProcessStream.STDOUT,
                        StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success(probeTermination);
            }
            if (spec.argv().contains("update")) {
                updateRuns.incrementAndGet();
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode("updated\n"));
                return ImmediateProcess.success(probeTermination);
            }
            if (spec.argv().contains("login")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(loggedIn ? "Logged in using ChatGPT\n" : "Not logged in\n"));
                return loggedIn
                        ? ImmediateProcess.success(probeTermination)
                        : ImmediateProcess.failure(probeTermination);
            }
            boolean catalog = spec.owner().id().equals("codex-model-catalog");
            AppServerProcess server = new AppServerProcess(
                    objectMapper,
                    output,
                    failThreadStart,
                    Path.of(spec.environment().getOrDefault("CODEX_HOME", spec.workingDirectory().toString())),
                    spec.argv(),
                    () -> managedRequirements,
                    () -> hooks,
                    () -> configLayers,
                    () -> configBody,
                    () -> mcpStatuses,
                    () -> skills,
                    () -> backgroundTerminals,
                    this::reportNoBackgroundTerminals,
                    thread -> historyItems.getOrDefault(thread, "{\"data\":[],\"nextCursor\":null}"),
                    () -> models,
                    () -> catalog ? ProcessExit.TreeTermination.VERIFIED_STOPPED : termination);
            if (!catalog) {
                // The working directory and owner bind a task process to its sandbox root. Catalog
                // processes are deliberately short-lived and are not task sessions.
                serverCwds.add(spec.workingDirectory());
                serverOwners.add(spec.owner());
                serverArgv.add(spec.argv());
                servers.add(server);
            }
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

        private List<Map<String, String>> environments()
        {
            return environments;
        }

        private List<Path> serverCwds()
        {
            return serverCwds;
        }

        private List<ProcessOwner> serverOwners()
        {
            return serverOwners;
        }

        private List<List<String>> serverArgv()
        {
            return serverArgv;
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

    private static final class UnencodableParams
    {
        public String getValue()
        {
            throw new IllegalStateException("cannot encode");
        }
    }

    private static final class AppServerProcess
            implements ManagedProcess
    {
        private final ObjectMapper objectMapper;
        private final ProcessOutputSink output;
        private final boolean failThreadStart;
        private final Path codexHome;
        private final List<String> argv;
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
        private final Supplier<String> configBody;
        private final Supplier<String> mcpStatuses;
        private final Supplier<String> skills;
        private final Supplier<String> backgroundTerminals;
        private final List<String> turnSettings = new CopyOnWriteArrayList<>();
        private final List<String> turnInputs = new CopyOnWriteArrayList<>();
        private final List<JsonNode> threadRequests = new CopyOnWriteArrayList<>();
        private final List<JsonNode> turnRequests = new CopyOnWriteArrayList<>();
        private final List<String> steerRequests = new CopyOnWriteArrayList<>();
        private volatile boolean refuseSteer;
        private volatile boolean ignoreSteer;
        private volatile boolean malformNextSteerResponse;
        private volatile boolean failNextSteerWrite;
        private volatile long ignoredSteerRequestId;
        private final List<String> sandboxes = new CopyOnWriteArrayList<>();

        private List<String> sandboxes()
        {
            return sandboxes;
        }
        private final List<Boolean> experimentalApi = new CopyOnWriteArrayList<>();
        private final List<String> terminated = new CopyOnWriteArrayList<>();
        private final List<String> backgroundTerminalThreads = new CopyOnWriteArrayList<>();

        private List<String> backgroundTerminalThreads()
        {
            return backgroundTerminalThreads;
        }
        private final Runnable clearBackgroundTerminals;
        private final Function<String, String> historyItems;
        private final Supplier<String> models;
        private final Supplier<ProcessExit.TreeTermination> termination;

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
                Path codexHome,
                List<String> argv,
                Supplier<String> managedRequirements,
                Supplier<String> hooks,
                Supplier<String> configLayers,
                Supplier<String> configBody,
                Supplier<String> mcpStatuses,
                Supplier<String> skills,
                Supplier<String> backgroundTerminals,
                Runnable clearBackgroundTerminals,
                Function<String, String> historyItems,
                Supplier<String> models,
                Supplier<ProcessExit.TreeTermination> termination)
        {
            this.termination = termination;
            this.objectMapper = objectMapper;
            this.output = output;
            this.failThreadStart = failThreadStart;
            this.codexHome = codexHome;
            this.argv = ImmutableList.copyOf(argv);
            this.managedRequirements = managedRequirements;
            this.hooks = hooks;
            this.configLayers = configLayers;
            this.configBody = configBody;
            this.mcpStatuses = mcpStatuses;
            this.skills = skills;
            this.backgroundTerminals = backgroundTerminals;
            this.clearBackgroundTerminals = clearBackgroundTerminals;
            this.historyItems = historyItems;
            this.models = models;
        }

        /** Real App Server hands out a distinct thread per start; the fixture must too. */
        private String nextThreadId()
        {
            int index = threads.incrementAndGet();
            return index == 1 ? "thread-1" : "thread-" + index;
        }

        private List<String> turnInputs()
        {
            return turnInputs;
        }

        private List<String> turnSettings()
        {
            return turnSettings;
        }

        private List<JsonNode> threadRequests()
        {
            return threadRequests;
        }

        private List<JsonNode> turnRequests()
        {
            return turnRequests;
        }

        private List<Boolean> experimentalApi()
        {
            return experimentalApi;
        }

        private JsonNode configReadResponse()
        {
            try {
                CodexLaunchProfile.ChildProfile childProfile = childProfile();
                ObjectNode config = objectMapper.valueToTree(
                        CodexLaunchProfile.threadConfig(childProfile));
                ObjectNode shell = (ObjectNode) config.path("shell_environment_policy");
                shell.putNull("exclude");
                shell.putNull("set");
                shell.putNull("filters");
                JsonNode override = objectMapper.readTree(configBody.get());
                if (!override.isObject()) {
                    throw new AssertionError("scripted config override is not an object");
                }
                config.setAll((ObjectNode) override);

                ArrayNode layers;
                if (configLayers.get() != null) {
                    JsonNode configured = objectMapper.readTree(configLayers.get());
                    if (!configured.isArray()) {
                        throw new AssertionError("scripted config layers are not an array");
                    }
                    layers = (ArrayNode) configured;
                }
                else {
                    layers = objectMapper.createArrayNode();
                    ObjectNode userLayer = layers.addObject();
                    userLayer.putObject("name")
                            .put("type", "user")
                            .put("file", codexHome.resolve("config.toml").toString());
                    userLayer.set("config", objectMapper.createObjectNode());

                    ObjectNode sessionLayer = layers.addObject();
                    sessionLayer.putObject("name").put("type", "sessionFlags");
                    sessionLayer.set(
                            "config",
                            objectMapper.valueToTree(CodexLaunchProfile.threadConfig(childProfile)));
                    sessionLayer.putNull("disabledReason");
                }
                ObjectNode response = objectMapper.createObjectNode();
                response.set("config", config);
                response.set("origins", objectMapper.createObjectNode());
                response.set("layers", layers);
                return response;
            }
            catch (IOException e) {
                throw new AssertionError("invalid scripted config", e);
            }
        }

        private CodexLaunchProfile.ChildProfile childProfile()
        {
            if (hasArguments("--enable", "multi_agent")) {
                return CodexLaunchProfile.ChildProfile.V1;
            }
            if (hasArguments("--enable", "multi_agent_v2")) {
                return CodexLaunchProfile.ChildProfile.V2;
            }
            return CodexLaunchProfile.ChildProfile.NONE;
        }

        private boolean hasArguments(String first, String second)
        {
            for (int index = 0; index + 1 < argv.size(); index++) {
                if (argv.get(index).equals(first) && argv.get(index + 1).equals(second)) {
                    return true;
                }
            }
            return false;
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
                if (failNextSteerWrite && message.path("method").asText().equals("turn/steer")) {
                    failNextSteerWrite = false;
                    return CompletableFuture.failedFuture(new AgentUnavailableException("write outcome is unknown"));
                }
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
                    respond(id, objectMapper.createObjectNode()
                            .put("codexHome", codexHome.toString())
                            .toString());
                }
                case "configRequirements/read" -> respond(id, managedRequirements.get());
                case "config/read" -> respond(id, configReadResponse().toString());
                case "skills/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"skills\":" + skills.get() + ",\"errors\":[]}]}");
                case "hooks/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"hooks\":" + hooks.get() + ",\"errors\":[],\"warnings\":[]}]}");
                case "thread/items/list" -> respond(
                        id,
                        historyItems.apply(message.path("params").path("threadId").asText()));
                case "thread/backgroundTerminals/list" -> {
                    backgroundTerminalThreads.add(message.path("params").path("threadId").asText());
                    respond(id, backgroundTerminals.get());
                }
                case "thread/backgroundTerminals/terminate" -> {
                    terminated.add(message.path("params").path("processId").asText());
                    clearBackgroundTerminals.run();
                    respond(id, "{\"terminated\":true}");
                }
                case "model/list" -> respond(id, models.get());
                case "mcpServerStatus/list" -> respond(
                        id,
                        mcpStatuses.get() != null
                                ? mcpStatuses.get()
                                : "{\"data\":[],\"nextCursor\":null}");
                case "initialized" -> {}
                case "thread/start" -> {
                    threadRequests.add(message.path("params").deepCopy());
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    sandboxes.add(message.path("params").path("sandbox").asText());
                    if (failThreadStart) {
                        emit("{\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"unsupported protocol\"}}");
                    }
                    else {
                        respond(id, "{\"thread\":{\"id\":\"" + nextThreadId() + "\"}}");
                    }
                }
                case "thread/resume" -> {
                    threadRequests.add(message.path("params").deepCopy());
                    approvalPolicies.add(message.path("params").path("approvalPolicy").asText());
                    sandboxes.add(message.path("params").path("sandbox").asText());
                    resumes.incrementAndGet();
                    respond(id, "{\"thread\":{\"id\":\"thread-1\"}}");
                }
                case "thread/unsubscribe" -> respond(id, "{\"status\":\"unsubscribed\"}");
                case "turn/start" -> {
                    turnRequests.add(message.path("params").deepCopy());
                    turnSettings.add(message.path("params").path("model").asText("none")
                            + "/" + message.path("params").path("effort").asText("none"));
                    turnInputs.add(message.path("params").path("input").toString());
                    activeTurn = "turn-" + turns.incrementAndGet();
                    respond(id, "{\"turn\":{\"id\":\"" + activeTurn
                            + "\",\"status\":\"inProgress\",\"error\":null,\"durationMs\":null,\"items\":[]}}");
                }
                case "turn/steer" -> {
                    steerRequests.add(message.path("params").toString());
                    if (malformNextSteerResponse) {
                        malformNextSteerResponse = false;
                        respond(id, "{}");
                        return;
                    }
                    if (ignoreSteer) {
                        ignoredSteerRequestId = id;
                        return;
                    }
                    if (refuseSteer) {
                        emit("{\"id\":" + id + ",\"error\":{\"code\":-32600,\"message\":\"turn is not active\"}}");
                    }
                    else {
                        respond(id, "{\"turnId\":\"" + message.path("params").path("expectedTurnId").asText() + "\"}");
                    }
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

        /** Announces a subagent thread the way App Server does when one is spawned. */
        private void emitThreadStarted(String threadId, String nickname)
        {
            emitThreadStarted(threadId, "thread-1", nickname);
        }

        private void emitThreadStarted(String threadId, String parentThreadId, String nickname)
        {
            emit("{\"method\":\"thread/started\",\"params\":{\"thread\":{"
                    + "\"id\":\"" + threadId + "\",\"sessionId\":\"session-1\","
                    + "\"parentThreadId\":\"" + parentThreadId + "\",\"turns\":[],"
                    + "\"agentNickname\":\"" + nickname + "\",\"agentRole\":null}}}");
        }

        private void emitV2Started(String threadId, String agentPath)
        {
            emit("{\"method\":\"item/completed\",\"params\":{\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"live-v2-start\","
                    + "\"agentThreadId\":\"" + threadId + "\",\"agentPath\":\"" + agentPath + "\","
                    + "\"kind\":\"started\"},\"threadId\":\"thread-1\","
                    + "\"turnId\":\"" + activeTurn + "\"}}");
        }

        /** The spawn that created a subagent, which is what carries the prompt it was given. */
        private void emitSpawnAgent(String threadId, String prompt)
        {
            emit("{\"method\":\"item/completed\",\"params\":{\"item\":{"
                    + "\"type\":\"collabAgentToolCall\",\"id\":\"spawn-1\",\"tool\":\"spawnAgent\","
                    + "\"status\":\"completed\",\"senderThreadId\":\"thread-1\","
                    + "\"receiverThreadIds\":[\"" + threadId + "\"],\"agentsStates\":{},"
                    + "\"prompt\":\"" + prompt + "\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
        }

        private void emitChildTurnCompleted(String threadId, String status)
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId + "\","
                    + "\"turn\":{\"id\":\"child-turn\",\"status\":\"" + status + "\","
                    + "\"error\":null,\"durationMs\":1,\"items\":[]}}}");
        }

        /** Starts model output and never completes it, the way an interrupt mid-token leaves it. */
        private void emitOpenModelOutput(String itemId, String type)
        {
            emit("{\"method\":\"item/started\",\"params\":{\"item\":{"
                    + "\"type\":\"" + type + "\",\"id\":\"" + itemId + "\",\"text\":\"\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
        }

        /** Starts an item and never reports it terminal, the way a cancelled turn leaves one. */
        private void emitUnfinishedItem(String itemId)
        {
            emit("{\"method\":\"item/started\",\"params\":{\"item\":{"
                    + "\"type\":\"commandExecution\",\"cwd\":\"/tmp/wt\",\"commandActions\":[],\"id\":\"" + itemId + "\","
                    + "\"command\":\"sleep 600\",\"status\":\"inProgress\"},"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + activeTurn + "\"}}");
        }

        private void emitUnfinishedChildCommand(String threadId)
        {
            emit("{\"method\":\"item/started\",\"params\":{\"item\":{"
                    + "\"type\":\"commandExecution\",\"cwd\":\"/tmp/wt\",\"commandActions\":[],"
                    + "\"id\":\"child-exec\",\"command\":\"echo done\",\"status\":\"inProgress\"},"
                    + "\"threadId\":\"" + threadId + "\",\"turnId\":\"child-turn\"}}");
        }

        private void completeTurnWithoutOutput()
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn + "\",\"status\":\"completed\","
                    + "\"error\":null,\"durationMs\":1,\"items\":[]}}}");
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

        private List<String> steerRequests()
        {
            return steerRequests;
        }

        private void refuseSteer()
        {
            refuseSteer = true;
        }

        private void ignoreSteer()
        {
            ignoreSteer = true;
        }

        private void respondToIgnoredSteer()
        {
            long requestId = ignoredSteerRequestId;
            if (requestId == 0) {
                throw new IllegalStateException("no ignored steer request");
            }
            ignoredSteerRequestId = 0;
            ignoreSteer = false;
            respond(requestId, "{\"turnId\":\"turn-timeout\"}");
        }

        private void malformNextSteerResponse()
        {
            malformNextSteerResponse = true;
        }

        private void failNextSteerWrite()
        {
            failNextSteerWrite = true;
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
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, termination.get(), Duration.ZERO, false, false));
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
            return success(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }

        private static ImmediateProcess success(ProcessExit.TreeTermination termination)
        {
            return exited(0, termination);
        }

        private static ImmediateProcess failure()
        {
            return failure(ProcessExit.TreeTermination.VERIFIED_STOPPED);
        }

        private static ImmediateProcess failure(ProcessExit.TreeTermination termination)
        {
            return exited(1, termination);
        }

        private static ImmediateProcess exited(int exitCode, ProcessExit.TreeTermination termination)
        {
            return new ImmediateProcess(CompletableFuture.completedFuture(
                    new ProcessExit(exitCode, ProcessExit.Reason.EXITED, termination, Duration.ZERO, false, false)));
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
