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

import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.cli.process.BuildCacheRoots;
import com.bytequay.agent.cli.process.GitWorktreeWriteRoots;
import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentCommandPolicy;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.RelatedWorkspaceBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Execution(ExecutionMode.CONCURRENT)
class TestCodexCliDriver
{
    private static final String DEVELOPMENT_POLICY = AgentCommandPolicy.instructions(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false));
    private static final String MCP_ENDPOINT = "http://127.0.0.1:9000/internal/agent/claude/mcp";
    private static final String TASK_CONTEXT = "This task continues the existing pull request owner/repo#7.";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
    private final List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();

    @TempDir
    private Path worktree;

    @Test
    void readOnlyDiscussionsReceiveTheirContextToolWithoutWritePermissions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        CodexCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        AgentSessionContext discussion = new AgentSessionContext(plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(),
                plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY,
                Optional.empty(), TASK_CONTEXT, Optional.of(new DiscussionContextBridge(query -> "saved context")));
        try (AgentSession session = driver.open(discussion, event -> {})) {
            assertThat(supervisor.serverArgv.getLast()).anyMatch(argument -> argument.contains("read_discussion_context"));
            JsonNode thread = supervisor.server().threadRequests.getFirst();
            String instructions = thread.path("developerInstructions").asText();
            assertThat(instructions).contains(DiscussionContextBridge.INSTRUCTION).startsWith(TASK_CONTEXT + "\n\n");
            RunningTurn turn = session.start(command("explain this"));
            // The setup's first half names the instructions the thread was given, all but the task's own context, which a
            // read-only session starts with.
            String own = Hashing.sha256().hashString(instructions.substring(TASK_CONTEXT.length() + 2), StandardCharsets.UTF_8).toString();
            assertThat(turn.setup().orElseThrow()).matches("[0-9a-f]{8}-[0-9a-f]{8}").startsWith(own.substring(0, 8) + "-");
            JsonNode request = supervisor.server().turnRequests.getFirst();
            assertThat(request.path("sandboxPolicy").path("type").asText()).isEqualTo("readOnly");
            assertThat(request.path("sandboxPolicy").path("networkAccess").asBoolean()).isFalse();
            supervisor.server().completeTurnWithSummary("[{\"type\":\"agentMessage\",\"id\":\"answer\",\"text\":\"Explained.\",\"phase\":\"final_answer\"}]");
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    void independentReviewReceivesOnlyItsReadContextTool()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        CodexCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        AgentSessionContext discussion = new AgentSessionContext(plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(),
                plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY,
                Optional.empty(), "Read the independent assignment.", Optional.empty(),
                ImmutableList.of(new ReviewTool("review_get_context", "Read captured context", ImmutableMap.of("type", "object"), query -> "saved context", true)));
        try (AgentSession session = driver.open(discussion, event -> {})) {
            assertThat(supervisor.serverArgv.getLast()).anyMatch(argument -> argument.contains("review_get_context"));
            JsonNode thread = supervisor.server().threadRequests.getFirst();
            assertThat(thread.path("developerInstructions").asText()).contains("Read the independent assignment.");
            RunningTurn turn = session.start(command("explain this"));
            JsonNode request = supervisor.server().turnRequests.getFirst();
            assertThat(request.path("sandboxPolicy").path("type").asText()).isEqualTo("readOnly");
            assertThat(request.path("sandboxPolicy").path("networkAccess").asBoolean()).isFalse();
            supervisor.server().completeTurnWithSummary("[{\"type\":\"agentMessage\",\"id\":\"answer\",\"text\":\"Explained.\",\"phase\":\"final_answer\"}]");
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    void readOnlySessionInAScratchFolderLaunchesAsControlWork()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        Path runtime = worktree.resolve("task-runtime");
        for (Path sessionWorktree : ImmutableList.of(worktree, Files.createDirectories(runtime.resolve("scratch-worktree")))) {
            driver.open(new AgentSessionContext(plain.taskId(), sessionWorktree, runtime, runtime.resolve("provider"), plain.permissionPolicy(), plain.sessionReference(),
                    plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY, Optional.empty(), ""), event -> {}).close();
        }

        // Only a session on a real worktree is held for its task at startup recovery; a scratch folder reaches no task worktree.
        assertThat(supervisor.serverOwners).containsExactly(
                new ProcessOwner(ProcessOwner.Kind.DISCUSSION, "task-1"),
                new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
    }

    @Test
    void toolsOnlySessionKeepsItsThreadWithOnlyByteQuayTools()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        CodexCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        Path runtime = worktree.resolve("task-runtime");
        Path day = Files.createDirectories(worktree.resolve("assistant/2026-10-17"));
        List<ReviewTool> tools = ImmutableList.of(
                new ReviewTool("assistant_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "{}", true),
                new ReviewTool("assistant_act", "Act on the user's words", ImmutableMap.of("type", "object"), query -> "{}", false),
                new ReviewTool("assistant_show", "Show the user a list", ImmutableMap.of("type", "object"), query -> "{}", false));
        AgentSessionContext toolsOnly = new AgentSessionContext(plain.taskId(), day, runtime, runtime.resolve("provider"), plain.permissionPolicy(), Optional.empty(),
                plain.approvals(), plain.model(), plain.effort(), SessionAccess.TOOLS_ONLY, Optional.empty(), "", Optional.empty(), tools);
        AgentSessionRef reference;
        try (AgentSession session = driver.open(toolsOnly, event -> {})) {
            assertThat(supervisor.serverOwners).containsExactly(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
            List<String> argv = supervisor.serverArgv.getFirst();
            for (String feature : ImmutableList.of("shell_tool", "unified_exec", "code_mode", "js_repl", "shell_snapshot", "multi_agent", "multi_agent_v2")) {
                assertThat(argv).containsSequence("--disable", feature);
            }
            assertThat(argv).contains("web_search=\"disabled\"", "approval_policy=\"never\"", "agents.enabled=false");
            assertThat(argv).filteredOn(argument -> argument.startsWith("mcp_servers.bytequay.tools="))
                    .containsExactly("mcp_servers.bytequay.tools={\"assistant_context\"={approval_mode=\"approve\"},\"assistant_act\"={approval_mode=\"approve\"},"
                            + "\"assistant_show\"={approval_mode=\"approve\"}}");
            assertThat(argv).noneMatch(argument -> argument.contains("bytequay_list_tools"));
            JsonNode thread = supervisor.server().threadRequests.getFirst();
            assertThat(thread.path("ephemeral").asBoolean()).isFalse();
            assertThat(thread.path("sandbox").asText()).isEqualTo("read-only");
            assertThat(thread.path("approvalPolicy").asText()).isEqualTo("never");
            RunningTurn turn = session.start(command("what is waiting on me?"));
            JsonNode request = supervisor.server().turnRequests.getFirst();
            assertThat(request.path("sandboxPolicy").path("type").asText()).isEqualTo("readOnly");
            assertThat(request.path("sandboxPolicy").path("networkAccess").asBoolean()).isFalse();
            supervisor.server().completeTurnWithSummary("[{\"type\":\"agentMessage\",\"id\":\"answer\",\"text\":\"Two cards wait.\",\"phase\":\"final_answer\"}]");
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            // Unlike an ephemeral text-only thread, the kept thread still proves its current turn finished.
            assertThat(supervisor.server().turnLists).hasPositiveValue();
            reference = session.reference();
        }

        AgentSessionContext resumed = new AgentSessionContext(plain.taskId(), day, runtime, runtime.resolve("provider"), plain.permissionPolicy(), Optional.of(reference),
                plain.approvals(), plain.model(), plain.effort(), SessionAccess.TOOLS_ONLY, Optional.empty(), "", Optional.empty(), tools);
        driver.open(resumed, event -> {}).close();
        JsonNode resume = supervisor.servers.getLast().threadRequests.getFirst();
        assertThat(resume.path("threadId").asText()).isEqualTo(reference.providerValue());
        assertThat(resume.path("excludeTurns").asBoolean()).isTrue();
        assertThat(resume.path("cwd").asText()).isEqualTo(day.toRealPath().toString());
    }

    @Test
    void deepSeekAdvertisesCiAutofix()
    {
        CodexCliDriver driver = CodexCliDriver.deepSeek(
                "/fake/codex", "0.156.1", objectMapper, new ScriptedProcessSupervisor(objectMapper),
                codexHome(),
                PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "").build(),
                new AgentLaneId("deepseek-key-123e4567-e89b-12d3-a456-426614174000"), "DeepSeek", () -> Optional.of("fake-key"));

        assertThat(driver.capabilities().permissionPolicies())
                .containsExactly(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX);
    }

    @Test
    void deepSeekSessionRefusesAnImageAttachment()
            throws Exception
    {
        CodexCliDriver driver = CodexCliDriver.deepSeek(
                "/fake/codex", "0.153.4", objectMapper, supervisor,
                codexHome(),
                PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "").build(),
                new AgentLaneId("deepseek-key-123e4567-e89b-12d3-a456-426614174000"), "DeepSeek", () -> Optional.of("fake-key"));
        supervisor.models = """
                {"data":[
                {"id":"deepseek-flash","displayName":"Flash","description":"d","hidden":false,"isDefault":true,
                "defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}],
                "multiAgentVersion":"disabled","inputModalities":["text"]},
                {"id":"deepseek-v4-pro","displayName":"Pro","description":"d","hidden":false,"isDefault":false,
                "defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}],
                "multiAgentVersion":"disabled","inputModalities":["text"]}]}
                """;
        Path image = worktree.resolve("blobs/attachments/shot.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1});

        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                    "turn-image", "look", ImmutableList.of(new AgentAttachment("att-1", "shot.png", "image/png", image)), Duration.ofMinutes(1))))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("task-owned attachment");
        }
        assertThat(supervisor.server().turnRequests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void threadStartupUsesTheSelectedEffortBeforeTheFirstTurn(boolean resume)
    {
        supervisor.models = """
                {"data":[{"id":"gpt-5","displayName":"GPT-5","description":"d",
                "supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"max"}],
                "defaultReasoningEffort":"low","isDefault":true,"hidden":false}]}
                """;
        CodexCliDriver driver = driver(supervisor);
        Optional<AgentSessionRef> reference = resume ? Optional.of(new AgentSessionRef(driver.id(), 1, "thread-1")) : Optional.empty();
        AgentSessionContext context = context(reference, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                Optional.of("gpt-5"), Optional.of("max"));

        try (AgentSession session = driver.open(context, event -> {})) {
            AppServerProcess server = supervisor.server();
            assertThat(server.resumeCount()).isEqualTo(resume ? 1 : 0);
            assertThat(server.threadRequests.getFirst().at("/config/model_reasoning_effort").asText()).isEqualTo("max");
            session.start(command("Continue"));
            assertThat(server.turnRequests.getFirst().path("effort").asText()).isEqualTo("max");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void threadStartupLeavesUnselectedEffortToTheProvider(boolean resume)
    {
        CodexCliDriver driver = driver(supervisor);
        Optional<AgentSessionRef> reference = resume ? Optional.of(new AgentSessionRef(driver.id(), 1, "thread-1")) : Optional.empty();
        try (AgentSession session = driver.open(context(reference, new AtomicReference<>()), event -> {})) {
            AppServerProcess server = supervisor.server();
            assertThat(server.resumeCount()).isEqualTo(resume ? 1 : 0);
            assertThat(server.threadRequests.getFirst().path("config").has("model_reasoning_effort")).isFalse();
            session.start(command("Continue"));
            assertThat(server.turnRequests.getFirst().has("effort")).isFalse();
        }
    }

    @Test
    void probesResumesAndMapsAnAuthoritativeRedactedResult()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        List<String> writableRoots = Stream.concat(Stream.of(worktree.toRealPath()), BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", worktree.toString())).stream())
                .map(Path::toString).toList();
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();

        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(supervisor.environments).allSatisfy(environment -> assertThat(environment).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR"));
        AgentSession initial = driver.open(context(Optional.empty(), approval), events::add);
        assertThat(supervisor.environments.getLast()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR", "OPENAI_API_KEY");
        AgentSessionRef reference = initial.reference();
        initial.close();
        AgentSession resumed = driver.open(context(Optional.of(reference), approval), events::add);
        assertThat(supervisor.environments.getLast()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR", "OPENAI_API_KEY");
        // Each session owns its own process, so the resume is served by a second one.
        assertThat(supervisor.servers).hasSize(2);
        AppServerProcess resumedServer = supervisor.servers.getLast();
        assertThat(resumedServer.resumeCount()).isOne();

        RunningTurn turn = resumed.start(command("complete"));
        JsonNode startedThread = supervisor.servers.getFirst().threadRequests.getFirst();
        assertThat(startedThread.path("developerInstructions").asText()).doesNotContain(CodexReviewGate.INSTRUCTION);
        assertThat(startedThread.path("model").asText()).isEqualTo("gpt-5");
        assertThat(startedThread.path("approvalsReviewer").asText()).isEqualTo("auto_review");
        assertThat(startedThread.path("config").path("web_search").asText()).isEqualTo("live");
        assertThat(startedThread.path("config").path("project_doc_max_bytes").asInt(-1)).isZero();
        assertThat(startedThread.path("config").has("auto_review")).isFalse();
        assertThat(startedThread.path("ephemeral").asBoolean()).isFalse();
        assertThat(startedThread.path("dynamicTools")).isEmpty();
        assertThat(startedThread.path("selectedCapabilityRoots")).isEmpty();
        assertThat(startedThread.path("config").path("mcp_servers")).isEmpty();
        assertThat(startedThread.path("config").path("features").path("multi_agent").asBoolean()).isTrue();
        assertThat(startedThread.path("config").path("sandbox_workspace_write").path("network_access").asBoolean()).isTrue();

        JsonNode resumedThread = resumedServer.threadRequests.getFirst();
        assertThat(resumedThread.path("developerInstructions").asText()).doesNotContain(CodexReviewGate.INSTRUCTION);
        assertThat(resumedThread.path("excludeTurns").asBoolean()).isTrue();
        assertThat(resumedThread.path("model").asText()).isEqualTo("gpt-5");
        assertThat(resumedThread.path("approvalsReviewer").asText()).isEqualTo("auto_review");
        assertThat(resumedThread.path("config").has("auto_review")).isFalse();
        assertThat(resumedThread.path("config").path("project_doc_max_bytes").asInt(-1)).isZero();
        assertThat(resumedThread.path("config").path("sandbox_workspace_write").path("network_access").asBoolean()).isTrue();
        assertThat(resumedThread.path("config").path("mcp_servers")).isEmpty();

        JsonNode startedTurn = resumedServer.turnRequests.getFirst();
        assertThat(startedTurn.path("approvalPolicy").asText()).isEqualTo("on-request");
        assertThat(startedTurn.path("approvalsReviewer").asText()).isEqualTo("auto_review");
        assertThat(Path.of(startedTurn.path("cwd").asText())).isEqualTo(worktree.toRealPath());
        assertThat(startedTurn.path("sandboxPolicy").path("type").asText()).isEqualTo("workspaceWrite");
        assertThat(startedTurn.path("sandboxPolicy").path("writableRoots"))
                .isEqualTo(objectMapper.valueToTree(writableRoots));
        assertThat(startedTurn.path("sandboxPolicy").path("networkAccess").asBoolean()).isTrue();
        assertThat(startedTurn.path("sandboxPolicy").path("excludeTmpdirEnvVar").booleanValue()).isFalse();
        assertThat(startedTurn.path("sandboxPolicy").path("excludeSlashTmp").booleanValue()).isFalse();
        // A turn can report before its first response, repeating the total the resume replayed.
        resumedServer.emitUsage("thread-1", resumedServer.activeTurn, AppServerProcess.EARLIER_USAGE, AppServerProcess.EARLIER_USAGE);
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
        // What the thread counted in earlier turns is none of this turn's usage, however often it is reported.
        assertThat(events.stream().filter(ProviderAgentEvent.Usage.class::isInstance).map(event -> ((ProviderAgentEvent.Usage) event).sample()))
                .allSatisfy(sample -> assertThat(sample.semantics()).isEqualTo(ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION))
                .extracting(ProviderUsageSample::inputTokens, ProviderUsageSample::cachedInputTokens, ProviderUsageSample::outputTokens)
                .containsExactly(tuple(0L, 0L, 0L), tuple(80L, 30L, 20L));
        assertThat(events.toString()).doesNotContain("known-secret");

        resumed.close();

        assertThat(driver.capabilities().permissionPolicies())
                .containsExactly(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX);
        assertThat(driver.capabilities().attachmentMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/gif", "text/plain", "text/plain; charset=utf-8");
        assertThat(driver.capabilities().nestedAgentActivity()).isTrue();
        assertThat(supervisor.servers)
                .allSatisfy(server -> assertThat(server.approvalPolicies).containsExactly("on-request"));
        // Ordinary development keeps its attended policy on both fresh and resumed threads.
        assertThat(supervisor.servers)
                .allSatisfy(server -> assertThat(server.sandboxes).containsExactly("workspace-write"));
        // Declared for thread/backgroundTerminals/list, which is experimental and is the only
        // inventory that can prove a thread owns no live process.
        assertThat(supervisor.servers).allSatisfy(server -> assertThat(server.experimentalApi).containsExactly(true));
    }

    @Test
    void routesCiApprovalsThroughByteQuayOnStartResumeAndTurns()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        CodexCliDriver driver = driver(supervisor);
        // CI pins both its Git writes and the fixed build caches across fresh and resumed sessions.
        Files.createDirectories(worktree.resolve(".m2/repository"));
        List<ProviderApprovalRequest> approvals = new CopyOnWriteArrayList<>();
        AgentApprovalHandler handler = (request, timeout) -> {
            approvals.add(request);
            return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        };
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int attempt = 0; attempt < 2; attempt++) {
            AgentSessionContext context = ciContext(reference, handler);
            try (AgentSession session = driver.open(context, event -> {})) {
                assertThat(supervisor.environments.getLast()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR");
                RunningTurn turn = session.start(command("repair CI"));
                AppServerProcess server = supervisor.servers.getLast();
                JsonNode thread = server.threadRequests.getFirst();
                assertThat(thread.path("approvalPolicy").asText()).isEqualTo("on-request");
                assertThat(thread.path("approvalsReviewer").asText()).isEqualTo("auto_review");
                assertThat(thread.path("config").path("web_search").asText()).isEqualTo("disabled");
                assertThat(thread.path("developerInstructions").asText()).contains("ByteQuay CI Repair Scope", "Never push");
                assertThat(thread.path("config").path("features").path("guardian_approval").asBoolean()).isTrue();
                assertThat(thread.path("config").path("features").path("multi_agent").asBoolean()).isFalse();
                assertThat(thread.path("config").path("features").path("multi_agent_v2").asBoolean()).isFalse();
                assertThat(thread.path("config").path("features").path("hooks").asBoolean()).isFalse();
                assertThat(thread.path("config").has("mcp_servers")).isFalse();
                JsonNode request = server.turnRequests.getFirst();
                assertThat(request.path("approvalPolicy").asText()).isEqualTo("on-request");
                assertThat(request.path("approvalsReviewer").asText()).isEqualTo("auto_review");
                JsonNode sandbox = request.path("sandboxPolicy");
                assertThat(sandbox.path("writableRoots")).isEqualTo(objectMapper.valueToTree(ciWritableRoots(context.worktree())));
                assertThat(sandbox.path("networkAccess").asBoolean()).isTrue();
                assertThat(sandbox.path("excludeTmpdirEnvVar").asBoolean()).isTrue();
                assertThat(sandbox.path("excludeSlashTmp").asBoolean()).isTrue();
                server.emit("""
                        {"id":92,"method":"item/commandExecution/requestApproval","params":{
                        "threadId":"thread-1","turnId":"%s","itemId":"exec-network",
                        "command":"mvn test",
                        "startedAtMs":1786884871461}}
                        """.formatted(server.activeTurn));
                server.emit("""
                        {"id":93,"method":"item/fileChange/requestApproval","params":{
                        "threadId":"thread-1","turnId":"%s","itemId":"patch-scoped",
                        "reason":"Write generated test result","startedAtMs":1786884871462}}
                        """.formatted(server.activeTurn));
                awaitCondition(() -> server.results.containsKey(92L) && server.results.containsKey(93L));
                assertThat(server.results.get(92L).path("decision").asText()).isEqualTo("decline");
                assertThat(server.results.get(93L).path("decision").asText()).isEqualTo("decline");
                assertThat(approvals.stream().filter(value -> value.source().providerItemId().equals(Optional.of("exec-network"))).toList())
                        .hasSize(attempt + 1)
                        .allSatisfy(value -> assertThat(value.kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND));
                assertThat(approvals.stream().filter(value -> value.source().providerItemId().equals(Optional.of("patch-scoped"))).toList())
                        .hasSize(attempt + 1)
                        .allSatisfy(value -> assertThat(value.kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE));
                assertThat(approvals).noneMatch(ProviderApprovalRequest::sandboxed);
                server.completeTurn();
                assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                reference = Optional.of(session.reference());
            }
        }
        assertThat(supervisor.servers.getLast().resumes.get()).isEqualTo(1);
    }

    @Test
    void attendedDevelopmentCanWriteItsLinkedWorktreeGitMetadata()
            throws Exception
    {
        AgentSessionContext linked = ciContext(Optional.empty());
        AgentSessionContext attended = new AgentSessionContext(
                "development-task", linked.worktree(), linked.runtimeDirectory(), linked.providerDirectory(),
                AgentPermissionPolicy.AUTO, Optional.empty(), linked.approvals(), Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), "");
        try (AgentSession session = driver(supervisor).open(attended, event -> {})) {
            RunningTurn turn = session.start(command("commit local changes"));
            JsonNode roots = supervisor.server().turnRequests.getFirst().path("sandboxPolicy").path("writableRoots");
            assertThat(roots).isEqualTo(objectMapper.valueToTree(Stream.concat(
                    GitWorktreeWriteRoots.writableRoots(attended.worktree()).stream(),
                    BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", worktree.toString())).stream().map(Path::toString))
                    .toList()));
            supervisor.server().completeTurn();
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                    .isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    void resumesCiWithNarrowerWritesDuringProgramOwnedRebase()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        CodexCliDriver driver = driver(supervisor);
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentSessionContext context = ciContext(reference);
            Path gitDirectory = Path.of(GitWorktreeWriteRoots.writableRoots(context.worktree()).get(1));
            Path rebase = gitDirectory.resolve("rebase-merge");
            if (attempt == 1) {
                Files.createDirectory(rebase);
                Files.writeString(gitDirectory.resolve("HEAD"), "a".repeat(40) + "\n");
                Files.writeString(rebase.resolve("head-name"), "refs/heads/bytequay/task\n");
                Files.writeString(rebase.resolve("orig-head"), "b".repeat(40) + "\n");
                Files.writeString(rebase.resolve("onto"), "c".repeat(40) + "\n");
            }
            if (attempt == 2) {
                Files.delete(rebase.resolve("head-name"));
                Files.delete(rebase.resolve("orig-head"));
                Files.delete(rebase.resolve("onto"));
                Files.delete(rebase);
            }
            try (AgentSession session = driver.open(context, event -> {})) {
                RunningTurn turn = session.start(command("repair CI"));
                AppServerProcess server = supervisor.servers.getLast();
                JsonNode sandbox = server.turnRequests.getFirst().path("sandboxPolicy");
                assertThat(sandbox.path("writableRoots")).hasSize(attempt == 1 ? 6 : 10);
                assertThat(sandbox.path("networkAccess").asBoolean()).isTrue();
                server.completeTurn();
                assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                reference = Optional.of(session.reference());
            }
        }
        assertThat(supervisor.servers).hasSize(3);
        assertThat(supervisor.servers.getLast().resumes.get()).isEqualTo(1);
    }

    @Test
    void refusesCiBranchDriftBeforeAnotherTurnStarts()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        AgentSessionContext context = ciContext(Optional.empty());
        try (AgentSession session = driver(supervisor).open(context, event -> {})) {
            Path gitDirectory = Path.of(GitWorktreeWriteRoots.writableRoots(context.worktree()).get(1));
            Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/another\n");
            assertThatThrownBy(() -> session.start(command("repair CI")))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("Git metadata or branch changed");
            assertThat(supervisor.server().turnRequests).isEmpty();
        }
    }

    @Test
    void refusesCiPolicyDriftBeforeAnotherTurnStarts()
            throws Exception
    {
        Map<String, String> overrides = ImmutableMap.of(
                "approval_policy", "{\"approval_policy\":\"never\"}",
                "approvals_reviewer", "{\"approvals_reviewer\":\"user\"}",
                "writable roots", "{\"sandbox_workspace_write\":{\"writable_roots\":[\"/\"]}}",
                "web_search", "{\"web_search\":\"live\"}");
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            assertCiDriftRejected(override.getValue(), override.getKey());
        }
        for (String field : ImmutableList.of("network_access", "exclude_tmpdir_env_var", "exclude_slash_tmp")) {
            ObjectNode sandbox = objectMapper.valueToTree(CodexLaunchProfile.ciSandboxConfig(ciWritableRoots(ciContext(Optional.empty()).worktree())));
            sandbox.put(field, false);
            assertCiDriftRejected(objectMapper.createObjectNode().set("sandbox_workspace_write", sandbox).toString(), field);
        }
    }

    private void assertCiDriftRejected(String override, String diagnostic)
            throws IOException
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        try (AgentSession session = driver(supervisor).open(ciContext(Optional.empty()), event -> {})) {
            supervisor.configBody = override;
            assertThatThrownBy(() -> session.start(command("repair CI")))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining(diagnostic);
            assertThat(supervisor.server().turnRequests).isEmpty();
        }
    }

    private AgentSessionContext ciContext(Optional<AgentSessionRef> reference)
            throws IOException
    {
        return ciContext(reference, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()));
    }

    private AgentSessionContext ciContext(Optional<AgentSessionRef> reference, AgentApprovalHandler handler)
            throws IOException
    {
        Path taskWorktree = Files.createDirectories(worktree.resolve("ci")).toRealPath();
        Path common = Files.createDirectories(worktree.resolve("repository/.git")).toRealPath();
        Path gitDirectory = Files.createDirectories(common.resolve("worktrees/task"));
        Files.createDirectories(common.resolve("objects"));
        Files.writeString(common.resolve("config"), "[core]\n\trepositoryformatversion = 0\n");
        Files.writeString(taskWorktree.resolve(".git"), "gitdir: " + gitDirectory + "\n");
        Files.writeString(gitDirectory.resolve("gitdir"), taskWorktree.resolve(".git") + "\n");
        Files.writeString(gitDirectory.resolve("commondir"), "../..\n");
        Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/bytequay/task\n");
        Path runtime = Files.createDirectories(worktree.resolve("runtime"));
        return new AgentSessionContext(
                "ci-task", taskWorktree, runtime, runtime.resolve("provider"), AgentPermissionPolicy.CI_AUTOFIX, reference,
                handler, Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), "");
    }

    @Test
    void probeKeepsRecoveryEvidenceWhenItsProcessTreeIsUnverifiable()
    {
        supervisor.leaveProbeTreeUnverifiable();

        AgentProviderStatus status = driver(supervisor).probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).contains("process tree could not be verified stopped");
        assertThat(supervisor.acknowledgements).isEmpty();
    }

    @Test
    void completesAnEphemeralTextOnlySessionWithoutWriteOrApprovalAuthority()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        Files.createDirectories(worktree.resolve(".m2/repository"));
        AgentSessionContext plain = context(Optional.empty(), new AtomicReference<>());
        AgentSessionContext textOnly = new AgentSessionContext(
                plain.taskId(),
                plain.worktree(),
                plain.runtimeDirectory(),
                plain.providerDirectory(),
                plain.permissionPolicy(),
                plain.sessionReference(),
                plain.approvals(),
                plain.model(),
                plain.effort(),
                SessionAccess.TEXT_ONLY,
                Optional.empty(),
                "");

        AgentSession session = driver.open(textOnly, event -> {});

        assertThat(supervisor.environments.getLast()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR");

        assertThat(supervisor.serverOwners).extracting(ProcessOwner::kind)
                .containsExactly(ProcessOwner.Kind.CLI_PROBE);
        assertThat(supervisor.server().approvalPolicies).containsExactly("never");
        assertThat(supervisor.server().sandboxes).containsExactly("read-only");
        JsonNode thread = supervisor.server().threadRequests.getFirst();
        assertThat(thread.path("developerInstructions").asText()).doesNotContain("update_plan");
        assertThat(thread.path("approvalsReviewer").asText()).isEqualTo("auto_review");
        assertThat(thread.path("config").path("web_search").asText()).isEqualTo("disabled");
        assertThat(thread.path("config").has("auto_review")).isFalse();
        assertThat(thread.path("ephemeral").asBoolean()).isTrue();
        assertThat(thread.path("config").path("features").path("multi_agent").asBoolean()).isFalse();
        assertThat(thread.path("config").path("features").path("multi_agent_v2").asBoolean()).isFalse();
        assertThat(supervisor.serverArgv.getFirst()).containsSequence("--disable", "shell_tool");
        assertThat(supervisor.serverArgv.getFirst()).containsSequence("--disable", "code_mode");
        assertThat(supervisor.serverArgv.getFirst()).containsSequence("--disable", "js_repl");
        assertThat(supervisor.serverArgv.getFirst()).containsSequence("--disable", "shell_snapshot");
        assertThat(supervisor.serverArgv.getFirst()).containsSequence("--disable", "unified_exec");
        assertThat(supervisor.serverArgv.getFirst()).noneMatch(argument -> argument.startsWith("forced_login_method="));
        RunningTurn turn = session.start(command("read only"));
        JsonNode request = supervisor.server().turnRequests.getFirst();
        assertThat(request.path("approvalPolicy").asText()).isEqualTo("never");
        assertThat(request.path("approvalsReviewer").asText()).isEqualTo("auto_review");
        assertThat(request.path("sandboxPolicy").path("type").asText()).isEqualTo("readOnly");
        assertThat(request.path("sandboxPolicy").has("writableRoots")).isFalse();
        assertThat(request.path("sandboxPolicy").path("networkAccess").asBoolean()).isFalse();
        supervisor.server().completeTurnWithSummary("[{\"type\":\"agentMessage\",\"id\":\"title\"," + "\"text\":\"Find refactoring opportunities\",\"phase\":\"final_answer\"}]");
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("Find refactoring opportunities");
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(supervisor.server().finishedWorkStops).hasValue(1);
        assertThat(supervisor.server().historyRequests).isNotEmpty();
        session.close();

        assertThatThrownBy(() -> new AgentSessionContext(
                plain.taskId(),
                plain.worktree(),
                plain.runtimeDirectory().resolve("scratch"),
                plain.runtimeDirectory().resolve("scratch/provider"),
                plain.permissionPolicy(),
                plain.sessionReference(),
                plain.approvals(),
                plain.model(),
                plain.effort(),
                SessionAccess.TEXT_ONLY,
                Optional.empty(),
                ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text-only worktree escapes runtimeDirectory");

        ScriptedProcessSupervisor poisoned = new ScriptedProcessSupervisor(objectMapper);
        ObjectNode config = objectMapper.valueToTree(CodexLaunchProfile.threadConfig(
                new CodexLaunchProfile.Options(CodexLaunchProfile.ChildProfile.NONE, true, ImmutableList.of(), false, false, false, Optional.empty()),
                false));
        ((ObjectNode) config.path("features")).put("shell_tool", true);
        poisoned.configBody = config.toString();
        assertThatThrownBy(() -> driver(poisoned).open(textOnly, event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("shell_tool");

        ScriptedProcessSupervisor proxyEnabled = new ScriptedProcessSupervisor(objectMapper);
        ObjectNode proxyConfig = objectMapper.valueToTree(CodexLaunchProfile.threadConfig(
                new CodexLaunchProfile.Options(CodexLaunchProfile.ChildProfile.NONE, true, ImmutableList.of(), false, false, false, Optional.empty()),
                false));
        ((ObjectNode) proxyConfig.path("features")).putObject("network_proxy").put("enabled", true);
        proxyEnabled.configBody = proxyConfig.toString();
        assertThatThrownBy(() -> driver(proxyEnabled).open(textOnly, event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("network_proxy");

        ScriptedProcessSupervisor helperWithWeb = new ScriptedProcessSupervisor(objectMapper);
        helperWithWeb.configBody = "{\"web_search\":\"live\"}";
        assertThatThrownBy(() -> driver(helperWithWeb).open(textOnly, event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("web_search");
    }

    @Test
    void recordsNativeAutomaticReviewWithoutRequestingAHumanDecision()
            throws Exception
    {
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AgentSession session = driver(supervisor).open(context(Optional.empty(), approval), events::add);
        RunningTurn turn = session.start(command("Read public documentation"));
        supervisor.server().emit("""
                {"method":"item/autoApprovalReview/started","params":{"threadId":"thread-1",
                "turnId":"turn-1","reviewId":"review-1","review":{"status":"inProgress"}}}
                """);
        supervisor.server().emit("""
                {"method":"item/autoApprovalReview/completed","params":{"threadId":"thread-1",
                "turnId":"turn-1","reviewId":"review-1","review":{"status":"approved",
                "riskLevel":"low","rationale":"Read-only public documentation fetch"}}}
                """);
        supervisor.server().completeTurnWithoutOutput();

        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(approval.get()).isNull();
        assertThat(events.stream().filter(ProviderAgentEvent.Tool.class::isInstance).map(ProviderAgentEvent.Tool.class::cast))
                .extracting(ProviderAgentEvent.Tool::state)
                .containsExactly(ProviderAgentEvent.ActivityState.STARTED, ProviderAgentEvent.ActivityState.COMPLETED);
        session.close();
    }

    @Test
    void steersOnlyTheActiveTurnAndTellsARefusalFromSilence()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.capabilities().steering()).isTrue();
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("steer"));

        turn.steer(new AgentTurnInput("also this", ImmutableList.of()));

        // The request names the turn it was meant for; App Server refuses it for any other.
        assertThat(supervisor.server().steerRequests).singleElement().satisfies(request -> {
            assertThat(request).contains("\"threadId\":\"thread-1\"");
            assertThat(request).contains("\"expectedTurnId\":\"" + turn.reference().value() + "\"");
            assertThat(request).contains("\"type\":\"text\",\"text\":\"also this\"");
        });

        // The bytes were accepted and App Server answered, but the answer cannot prove whether it
        // applied the input. That is still uncertain, not a safe retry.
        supervisor.server().malformNextSteerResponse = true;
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("malformed", ImmutableList.of())))
                .isInstanceOf(AgentDeliveryUncertainException.class);

        supervisor.server().refuseSteer = true;
        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("refused", ImmutableList.of())))
                .isInstanceOf(AgentUnavailableException.class)
                .isNotInstanceOf(AgentDeliveryUncertainException.class)
                .hasMessageContaining("turn is not active");

        // No answer and then the process gone: nothing says whether the input landed.
        supervisor.server().ignoreSteer = true;
        int delivered = supervisor.server().steerRequests.size();
        Thread.ofVirtual().start(() -> {
            awaitCondition(() -> supervisor.server().steerRequests.size() > delivered);
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
    }

    @Test
    void identifiesARequestRejectedBeforeAnyBytesAreSent()
    {
        CodexAppServerClient client = appServerClient(supervisor);

        assertThatThrownBy(() -> client.request("turn/steer", new CodexProtocol.EmptyParams(), CodexProtocol.EmptyResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
        assertThat(supervisor.servers).isEmpty();
        client.close();
        assertThatThrownBy(() -> client.request("turn/steer", new CodexProtocol.EmptyParams(), CodexProtocol.EmptyResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
    }

    @Test
    void identifiesEncodingFailureBeforeARequestWrite()
    {
        CodexAppServerClient client = appServerClient(supervisor);
        client.start();

        assertThatThrownBy(() -> client.request("turn/steer", new UnencodableParams(), CodexProtocol.EmptyResponse.class, Duration.ofSeconds(1)))
                .isInstanceOf(CodexAppServerClient.RequestNotSent.class);
        assertThat(supervisor.server().steerRequests).isEmpty();
        client.close();
    }

    @Test
    void forgetsATimedOutRequestAndIgnoresItsLateResponse()
    {
        CodexAppServerClient client = appServerClient(supervisor);
        client.start();
        supervisor.server().ignoreSteer = true;
        CodexProtocol.TurnSteerParams timedOut = new CodexProtocol.TurnSteerParams("thread-1", "turn-timeout", ImmutableList.of(new CodexProtocol.TextInput("text", "late")));

        assertThatThrownBy(() -> client.request("turn/steer", timedOut, CodexProtocol.TurnSteerResponse.class, Duration.ofMillis(20)))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Timed out");
        assertThat(client.pendingRequestCount()).isZero();

        supervisor.server().respondToIgnoredSteer();
        CodexProtocol.TurnSteerResponse response = client.request(
                "turn/steer",
                new CodexProtocol.TurnSteerParams("thread-1", "turn-next", ImmutableList.of(new CodexProtocol.TextInput("text", "next"))),
                CodexProtocol.TurnSteerResponse.class,
                Duration.ofSeconds(1));
        assertThat(response.turnId()).isEqualTo("turn-next");
        client.close();
    }

    @Test
    void treatsAWriteStageFailureAsUncertainDelivery()
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("write-failure"));
        supervisor.server().failNextSteerWrite = true;

        assertThatThrownBy(() -> turn.steer(new AgentTurnInput("maybe written", ImmutableList.of())))
                .isInstanceOf(AgentDeliveryUncertainException.class);
    }

    @Test
    void reportsCancellationFailureAndAppServerDisconnect()
            throws Exception
    {
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
        supervisor.servers.getLast().failTurn();
        AgentTurnResult failedResult = failed.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(failedResult.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failedResult.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("Codex CLI 0.153.4").contains("provider failed"));

        session = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});
        supervisor.versionOutput = "future-build";
        assertThat(driver.probe().version()).contains("future-build");

        RunningTurn disconnected = session.start(command("disconnect"));
        supervisor.servers.getLast().disconnect();
        AgentTurnResult disconnectedResult = disconnected.completion()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(disconnectedResult.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(disconnectedResult.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("Codex CLI 0.153.4").contains("exited with code 1"));
    }

    @Test
    void acceptsEqualOrNewerVersions()
    {
        for (String version : ImmutableList.of("0.153.4", "0.153.4+build.1", "0.153.5", "0.154.0-alpha.1", "0.200.0", "1.0.0")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli " + version);
            CodexCliDriver driver = driver(supervisor);

            assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
            assertThat(driver.models()).isNotEmpty();
        }
    }

    @Test
    void blocksOlderOrUnreadableVersionsBeforeModelDiscoveryOrTaskLaunch()
    {
        for (String version : ImmutableList.of("0.152.1", "0.153.4-alpha.1", "future-build", "0.153", "999999999999999.0.0")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli " + version);
            CodexCliDriver driver = driver(supervisor);

            AgentProviderStatus status = driver.probe();
            assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
            assertThat(status.version()).contains(version);
            assertThat(status.diagnostic()).contains("requires 0.153.4 or newer", "npm install --global @openai/codex@0.153.4");
            assertThatThrownBy(driver::models)
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("requires 0.153.4 or newer");
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("requires 0.153.4 or newer");
            assertThat(supervisor.servers).isEmpty();
        }
    }

    @Test
    void usesTheConfiguredMinimumForReadiness()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1");
        CodexCliDriver driver = driver(supervisor, "0.159.3", Duration.ofSeconds(30));

        AgentProviderStatus status = driver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        assertThat(status.version()).contains("0.156.1");
        assertThat(status.diagnostic()).contains("npm install --global @openai/codex@0.159.3");
        supervisor.versionOutput = "codex-cli 0.159.3";
        assertThat(driver.models()).isNotEmpty();

        supervisor.versionOutput = "codex-cli 0.159.2";
        assertThatThrownBy(driver::models).hasMessageContaining("minimum version 0.159.3");
        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .hasMessageContaining("minimum version 0.159.3");
    }

    @Test
    void rejectsNonReleaseMinimumConfiguration()
    {
        for (String minimum : ImmutableList.of("latest", "0.153", "0.153.4-alpha.1", "0.153.4;echo unsafe")) {
            assertThatThrownBy(() -> driver(supervisor, minimum, Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stable x.y.z release");
        }
    }

    @Test
    void distinguishesAuthenticationAndExecutableFailures()
    {
        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor(objectMapper);
        loggedOut.loggedIn = false;
        CodexCliDriver loggedOutDriver = driver(loggedOut);
        AgentProviderStatus authentication = loggedOutDriver.probe();
        assertThat(authentication.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(authentication.available()).isFalse();

        ScriptedProcessSupervisor missing = new ScriptedProcessSupervisor(objectMapper);
        missing.executableMissing = true;
        CodexCliDriver missingDriver = driver(missing);
        AgentProviderStatus executable = missingDriver.probe();
        assertThat(executable.readiness()).isEqualTo(AgentProviderStatus.Readiness.CLI_MISSING);
        assertThat(executable.available()).isFalse();
    }

    @Test
    void runsEveryCodexProcessInByteQuaysOwnHome()
    {
        CodexCliDriver driver = driver(supervisor);

        driver.probe();
        driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}).close();

        // Probe commands and the task's App Server alike: nothing from ~/.codex may reach a task,
        // and a shell that exports CODEX_HOME must not redirect them either.
        assertThat(supervisor.environments).isNotEmpty();
        assertThat(supervisor.environments)
                .allSatisfy(environment -> assertThat(environment).containsEntry("CODEX_HOME", codexHome().toString()));
    }

    @Test
    void reusesRecentReadinessWhenOpeningAndResumingATask()
    {
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().available()).isTrue();
        AgentSessionRef reference;
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            reference = session.reference();
        }
        try (AgentSession session = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {})) {
            assertThat(session.reference()).isEqualTo(reference);
        }

        assertThat(supervisor.probeCommands).hasValue(2);
        assertThat(supervisor.servers).hasSize(2);
        supervisor.loggedIn = false;
        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("not signed in");
        assertThat(supervisor.servers).hasSize(2);
    }

    @Test
    void checksNewExecPolicyRulesEvenWhenReadinessIsRecent()
            throws IOException
    {
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().available()).isTrue();
        Path rules = codexHome().resolve("rules");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("default.rules"), "prefix_rule(pattern=[\"git\"], decision=\"allow\")\n");

        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("exec-policy rules");
        assertThat(supervisor.probeCommands).hasValue(2);
        assertThat(supervisor.servers).isEmpty();
    }

    @Test
    void verifiesTheAccountAgainWhenReadinessIsRecent()
    {
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().available()).isTrue();
        supervisor.account = "{\"requiresOpenaiAuth\":true,\"account\":null}";

        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("isolated ChatGPT account");
        assertThat(supervisor.probeCommands).hasValue(2);
    }

    @Test
    void namesTheCommandThatSignsByteQuaysHomeIn()
    {
        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor(objectMapper);
        loggedOut.loggedIn = false;
        CodexCliDriver driver = driver(loggedOut);

        AgentProviderStatus status = driver.probe();

        // A plain "codex login" would sign the user's own home in, not ByteQuay's.
        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.signInCommand()).contains("CODEX_HOME=\"" + codexHome() + "\" /fake/codex login");
        assertThat(status.diagnostic()).contains("not signed in for ByteQuay");
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
        assertThat(status.version()).contains("0.153.4");
        assertThat(status.diagnostic()).contains("exec-policy rules").contains("move that rules folder aside");
    }

    @Test
    void reportsTheInstalledVersionWhenAppServerIsIncompatible()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.153.4", true);
        CodexCliDriver driver = driver(supervisor);

        assertThatThrownBy(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Codex CLI 0.153.4")
                .hasMessageContaining("thread/start")
                .hasMessageContaining("unsupported protocol");
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
    void concurrentTaskStartsShareTheInitialModelCatalog()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        assertThat(driver.probe().available()).isTrue();
        CountDownLatch catalogStarted = new CountDownLatch(1);
        CompletableFuture<Void> releaseCatalog = new CompletableFuture<>();
        supervisor.beforeCatalog = () -> {
            catalogStarted.countDown();
            releaseCatalog.join();
        };
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}));
            try {
                assertThat(catalogStarted.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
                });
                // Both opens reached the catalog while its first discovery still owns the lock.
                awaitCondition(() -> secondThread.get() != null && secondThread.get().getState() == Thread.State.BLOCKED);
                releaseCatalog.complete(null);
                try (AgentSession firstSession = first.get(5, TimeUnit.SECONDS);
                        AgentSession secondSession = second.get(5, TimeUnit.SECONDS)) {
                    assertThat(firstSession.reference()).isNotNull();
                    assertThat(secondSession.reference()).isNotNull();
                    assertThat(supervisor.catalogLaunches).hasValue(1);
                }
            }
            finally {
                releaseCatalog.complete(null);
            }
        }
        driver.models();
        assertThat(supervisor.catalogLaunches).hasValue(2);
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
        assertThat(unknown.servers).isEmpty();

        ScriptedProcessSupervisor noDefault = new ScriptedProcessSupervisor(objectMapper);
        noDefault.models = "{\"data\":[{\"id\":\"gpt-5\",\"displayName\":\"GPT-5\","
                + "\"description\":\"d\",\"supportedReasoningEfforts\":[],"
                + "\"defaultReasoningEffort\":\"low\",\"isDefault\":false,\"hidden\":false,"
                + "\"multiAgentVersion\":\"v1\"}]}";
        CodexCliDriver noDefaultDriver = driver(noDefault);
        assertThatThrownBy(() -> noDefaultDriver.open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("no visible default model");
        assertThat(noDefault.servers).isEmpty();

        ScriptedProcessSupervisor missing = new ScriptedProcessSupervisor(objectMapper);
        CodexCliDriver missingDriver = driver(missing);
        assertThatThrownBy(() -> missingDriver.open(context(
                Optional.empty(),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                Optional.of("not-in-catalog"),
                Optional.<String>empty()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("not in the current model catalog");
        assertThat(missing.servers).isEmpty();
    }

    @Test
    void routesStructuredPermissionsAndRejectsLateOrLegacyRequests()
    {
        CodexCliDriver driver = driver(supervisor);
        List<ProviderApprovalRequest> approvals = new CopyOnWriteArrayList<>();
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            approvals.add(request);
            return CompletableFuture.completedFuture(request.source().providerItemId().equals(Optional.of("permission-denied"))
                    ? ProviderApprovalDecision.deny()
                    : ProviderApprovalDecision.allow(Optional.empty()));
        }), event -> {});
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
                """.formatted(server.activeTurn));
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
                "reason":"Need known-secret to download Maven dependencies",
                "itemId":"exec-permission","startedAtMs":1786884871463,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":97,"method":"item/commandExecution/requestApproval","params":{
                "command":"input","itemId":"exec-input","kind":"writeStdin",
                "startedAtMs":1786884871464,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":99,"method":"item/commandExecution/requestApproval","params":{
                "command":"curl example.com","itemId":"exec-network",
                "networkApprovalContext":{"host":"example.com","protocol":"https"},
                "startedAtMs":1786884871465,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":100,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"/worktree/generated","itemId":"patch-scoped",
                "startedAtMs":1786884871466,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":102,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"","itemId":"patch-empty-root","startedAtMs":1786884871467,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":103,"method":"item/tool/requestUserInput","params":{
                "threadId":"thread-1","turnId":"%s","itemId":"unregistered-input","isBlocking":true,"questions":[]}}
                """.formatted(server.activeTurn));
        server.emit("""
                {"id":104,"method":"item/permissions/requestApproval","params":{
                "cwd":"/worktree","itemId":"permission-denied",
                "permissions":{"network":{"enabled":true}},
                "startedAtMs":1786884871468,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));

        // Every response is written on the dispatch thread, so wait for all before asserting.
        List<Long> rejected = ImmutableList.of(92L, 94L, 95L, 97L, 102L);
        List<Long> approved = ImmutableList.of(93L, 96L, 99L, 100L, 103L, 104L);
        awaitCondition(() -> rejected.stream().allMatch(id -> server.error(id) != null)
                && approved.stream().allMatch(id -> server.result(id) != null));
        assertThat(server.error(92).path("message").asText()).contains("active turn");
        assertThat(server.error(94).path("message").asText()).contains("no active thread");
        assertThat(server.error(95).path("message").asText()).contains("no active thread");
        assertThat(server.error(97).path("message").asText()).contains("approval kind");
        // A question with nothing to choose from shows no card; the agent goes on without an answer.
        assertThat(server.result(103).path("answers")).isEqualTo(objectMapper.createObjectNode());
        assertThat(server.error(102).path("message").asText()).contains("blank write root");
        assertThat(server.result(93).path("permissions")).isEqualTo(objectMapper.createObjectNode());
        assertThat(server.result(93).path("scope").asText()).isEqualTo("turn");
        assertThat(server.result(96).path("decision").asText()).isEqualTo("accept");
        assertThat(server.result(99).path("decision").asText()).isEqualTo("accept");
        assertThat(server.result(100).path("decision").asText()).isEqualTo("accept");
        assertThat(server.result(104).path("permissions")).isEqualTo(objectMapper.createObjectNode());
        assertThat(server.result(104).has("scope")).isFalse();
        assertThat(approvals.stream().map(value -> value.source().providerItemId().orElseThrow()).toList())
                .containsExactlyInAnyOrder("permission-1", "exec-permission", "exec-network", "patch-scoped", "permission-denied");
        assertThat(approvals.stream().filter(request -> request.source().providerItemId().orElseThrow().equals("exec-permission"))
                .findFirst().orElseThrow().detail())
                .contains("Reason: Need [REDACTED] to download Maven dependencies")
                .doesNotContain("known-secret");
        assertThat(approvals.stream().map(ProviderApprovalRequest::detail).toList())
                .anySatisfy(detail -> assertThat(detail).contains("network", "enabled"))
                .anySatisfy(detail -> assertThat(detail).contains("Network approval", "example.com"))
                .anySatisfy(detail -> assertThat(detail).contains("Requested write root", "/worktree/generated"));
    }

    @Test
    void asksCodexQuestionsWithTheSharedQuestionCardAndAnswersByQuestionId()
            throws IOException
    {
        CodexCliDriver driver = driver(supervisor);
        List<ProviderApprovalRequest> approvals = new CopyOnWriteArrayList<>();
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            approvals.add(request);
            return CompletableFuture.completedFuture(request.source().providerItemId().equals(Optional.of("ask-declined"))
                    ? ProviderApprovalDecision.deny()
                    : ProviderApprovalDecision.allow(Optional.empty(), ImmutableMap.of("Which path?", "Read path")));
        }), event -> {});
        session.start(command("questions"));
        AppServerProcess server = supervisor.server();
        // A task's turn may ask, so the launch turns on Codex's question tool, and config/read must report it.
        assertThat(supervisor.serverArgv.getLast()).containsSequence("--enable", CodexLaunchProfile.ASK_USER_FEATURE);

        String path = """
                {"id":"q-path","header":"Path","question":"Which path?","isOther":true,
                "options":[{"label":"Read path","description":"How a scan skips rows"},{"label":"Write path","description":"How deletes are written"}]}""";
        server.emit("""
                {"id":120,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"%s","itemId":"ask-1","isBlocking":true,
                "questions":[%s,{"id":"q-key","header":"Key","question":"Your API key?","isSecret":true,"options":[{"label":"Paste it","description":""}]}]}}
                """.formatted(server.activeTurn, path));
        server.emit("""
                {"id":121,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"%s","itemId":"ask-declined","isBlocking":true,
                "questions":[%s]}}
                """.formatted(server.activeTurn, path));
        server.emit("""
                {"id":122,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"%s","itemId":"ask-typed","isBlocking":true,
                "questions":[{"id":"q-name","header":"Name","question":"What name?","options":null}]}}
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.result(120) != null && server.result(121) != null && server.result(122) != null);

        // Answers go back keyed by Codex's question ids; a declined question, and one only typed, get none.
        assertThat(server.result(120).path("answers")).isEqualTo(objectMapper.readTree("{\"q-path\":{\"answers\":[\"Read path\"]}}"));
        assertThat(server.result(121).path("answers")).isEqualTo(objectMapper.createObjectNode());
        assertThat(server.result(122).path("answers")).isEqualTo(objectMapper.createObjectNode());
        assertThat(approvals).extracting(request -> request.source().providerItemId().orElseThrow()).containsExactlyInAnyOrder("ask-1", "ask-declined");
        // The question reaches ByteQuay as Claude's does, so one card shows it; the secret stays out of it.
        ProviderApprovalRequest asked = approvals.stream().filter(request -> request.source().providerItemId().equals(Optional.of("ask-1"))).findFirst().orElseThrow();
        assertThat(asked.requestedPermissions()).containsExactly(ProviderApprovalRequest.QUESTION_TOOL);
        assertThat(objectMapper.readTree(asked.detail())).isEqualTo(objectMapper.readTree("""
                {"questions":[{"question":"Which path?","header":"Path","multiSelect":false,
                "options":[{"label":"Read path","description":"How a scan skips rows"},{"label":"Write path","description":"How deletes are written"}]}]}"""));
    }

    @Test
    void checksFileWriteRootBeforeRequestingUserApproval()
            throws IOException
    {
        CodexCliDriver driver = driver(supervisor);
        List<ProviderApprovalRequest> approvals = new CopyOnWriteArrayList<>();
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            approvals.add(request);
            return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
        }), event -> {});
        session.start(command("file-write-approval"));
        AppServerProcess server = supervisor.server();
        Path inside = Files.createDirectory(worktree.resolve("generated")).toRealPath();
        Path outside = worktree.resolveSibling("outside").toAbsolutePath();

        server.emit("""
                {"id":110,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"%s","itemId":"patch-inside","startedAtMs":1786884871466,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(inside, server.activeTurn));
        awaitCondition(() -> server.result(110) != null);
        assertThat(server.result(110).path("decision").asText()).isEqualTo("accept");
        assertThat(approvals).isEmpty();

        server.emit("""
                {"id":111,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"%s","itemId":"patch-outside","startedAtMs":1786884871467,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(outside, server.activeTurn));
        awaitCondition(() -> server.result(111) != null);
        assertThat(server.result(111).path("decision").asText()).isEqualTo("accept");
        assertThat(approvals).hasSize(1);
        assertThat(approvals.getFirst().detail()).contains("Requested write root: " + outside);
        session.close();
    }

    @Test
    void rejectsAnApprovalFromAChildThreadOutsideTheCurrentAgentGraph()
    {
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
    void approvalTimeoutInterruptsTheNativeTurnAndPermitsAResumedSession()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.expired())), event -> {});
        RunningTurn turn = session.start(command("approval-timeout"));
        AppServerProcess server = supervisor.server();
        server.emit("""
                {"id":98,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo expired",
                "itemId":"exec-expired","startedAtMs":1786884871465,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason()).contains("Codex approval timed out");
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(server.finishedWorkStops.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.result(98).path("decision").asText()).isEqualTo("cancel");
        session.close();
        AgentSession resumed = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn next = resumed.start(command("next"));
        assertThat(next.completion().toCompletableFuture()).isNotDone();
        resumed.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvalTimeoutRetainsItsCauseWhenReconciliationFails(boolean processUnverifiable)
            throws Exception
    {
        if (processUnverifiable) {
            supervisor.leaveProcessTreeUnverifiable();
        }
        AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.expired())), event -> {});
        RunningTurn turn = session.start(command("approval-timeout-missing-history"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("missing-child"));
        server.historyOverrides.put("missing-child", "{\"thread\":{\"id\":\"missing-child\",\"sessionId\":\"session-1\"," + "\"status\":{\"type\":\"idle\"},\"turns\":[]}}");
        server.emit("""
                {"id":98,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo expired",
                "itemId":"exec-expired","startedAtMs":1786884871465,
                "threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("Codex approval timed out", "no current turn history"));
        assertThat(result.providerQuiescent()).isEqualTo(!processUnverifiable);
        assertThat(server.finishedWorkStops).hasValue(0);
    }

    @Test
    void runtimeTimeoutInterruptsTheNativeTurnWithAPreciseReason()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(new AgentTurnCommand("timeout", "wait", ImmutableList.of(), Duration.ofMillis(50)));
            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.terminalReason()).contains("Codex turn timed out");
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(supervisor.server().finishedWorkStops.get()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void unlimitedTurnsStillSupportCancellation()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(new AgentTurnCommand("unlimited", "wait", ImmutableList.of(), Duration.ZERO));
            assertThatThrownBy(() -> turn.completion().toCompletableFuture().get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            turn.cancel();
            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            assertThat(result.providerQuiescent()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnApprovalDecisionAfterItsTurnFinishes(boolean timedOut)
    {
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
                """.formatted(server.activeTurn));
        awaitCondition(() -> approval.get() != null);
        assertThat(approval.get().detail()).isEqualTo("echo late");

        server.failTurn();
        awaitCondition(() -> late.completion().toCompletableFuture().isDone());
        AgentSession resumed = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn next = resumed.start(command("next"));
        decision.complete(timedOut ? ProviderApprovalDecision.expired() : ProviderApprovalDecision.allow(Optional.empty()));

        awaitCondition(() -> server.error(98) != null);
        assertThat(server.error(98).path("message").asText()).contains("no longer belongs");
        assertThat(next.completion().toCompletableFuture()).isNotDone();
        resumed.close();
    }

    @Test
    void rejectsAnApprovalDecisionAfterCancellationIsRequested()
            throws Exception
    {
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
                """.formatted(server.activeTurn));
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
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.error(103) != null);
        assertThat(server.error(103).path("message").asText()).contains("active turn");
        assertThat(approval).hasValue(null);
        decision.complete(ProviderApprovalDecision.allow(Optional.empty()));

        assertThat(server.error(101).path("message").asText()).contains("no longer belongs");
        server.releaseInterruptCompletion();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gh pr view 42", "git status && gh pr view 42 && git push origin HEAD", "sh -lc 'git -C /task/worktree status && gh api repos/owner/repo'"})
    void preservesNativeApprovalRequestsForGitHubCommands(String shellCommand)
    {
        CodexCliDriver driver = driver(supervisor);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AtomicReference<ProviderApprovalDecision> decision = new AtomicReference<>(ProviderApprovalDecision.deny());
        try (AgentSession session = driver.open(context(Optional.empty(), approval, decision), event -> {})) {
            session.start(command("GitHub approval"));
            AppServerProcess server = supervisor.server();
            ObjectNode params = objectMapper.createObjectNode()
                    .put("threadId", "thread-1")
                    .put("turnId", server.activeTurn)
                    .put("itemId", "github-command")
                    .put("command", shellCommand)
                    .put("startedAtMs", 1786884871461L);
            params.set("availableDecisions", objectMapper.valueToTree(ImmutableList.of("accept", "decline")));
            ObjectNode request = objectMapper.createObjectNode().put("id", 95).put("method", "item/commandExecution/requestApproval");
            request.set("params", params);
            server.emit(request.toString());
            awaitCondition(() -> server.result(95) != null);
            assertThat(approval.get().detail()).isEqualTo(shellCommand);
            assertThat(approval.get().taskScope()).isEmpty();
            assertThat(approval.get().availableDecisions()).containsExactly(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
            assertThat(server.result(95).path("decision").asText()).isEqualTo("decline");
        }
    }

    @Test
    void mapsOnlyOfferedUnscopedBinaryApprovalDecisions()
    {
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
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.result(95) != null);
        assertThat(server.result(95).path("decision").asText()).isEqualTo("decline");

        decision.set(ProviderApprovalDecision.allow(Optional.empty()));
        server.emit("""
                {"id":96,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["decline"],"command":"echo unavailable","itemId":"exec-1",
                "startedAtMs":1786884871462,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.error(96) != null);
        assertThat(server.error(96).path("message").asText()).contains("did not offer decision accept");

        decision.set(ProviderApprovalDecision.allow(Optional.of("session")));
        server.emit("""
                {"id":97,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],"command":"echo scoped","itemId":"exec-2",
                "startedAtMs":1786884871463,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.error(97) != null);
        assertThat(server.error(97).path("message").asText()).contains("scoped permission");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);

        // Measured on codex-cli 0.152.1: an escalated command offers accept, the amendment variant
        // and cancel, but no decline. The card offers only Allow once and Deny; the amendment is never
        // offered, and a deny is sent as decline, which the server honours.
        decision.set(ProviderApprovalDecision.deny());
        server.emit("""
                {"id":98,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept",{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["/bin/zsh","-lc","printf hello > /Users/me/probe.txt"]}},"cancel"],
                "command":"/bin/zsh -lc 'printf hello > /Users/me/probe.txt'","itemId":"exec-3",
                "startedAtMs":1786884871464,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.result(98) != null);
        assertThat(server.result(98).path("decision").asText()).isEqualTo("decline");
        assertThat(approval.get().availableDecisions())
                .containsExactly(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
        assertThat(approval.get().taskScope()).isEmpty();

        // Native review decides whether even a read needs approval; ByteQuay does not classify the shell again.
        ProviderApprovalRequest before = approval.get();
        server.emit("""
                {"id":99,"method":"item/commandExecution/requestApproval","params":{
                "availableDecisions":["accept","decline"],
                "command":"/bin/zsh -lc \\"gh issue list --repo owner/repository --state open --limit 100\\"","itemId":"exec-4",
                "startedAtMs":1786884871465,"threadId":"thread-1","turnId":"%s"}}
                """.formatted(server.activeTurn));
        awaitCondition(() -> server.result(99) != null);
        assertThat(server.result(99).path("decision").asText()).isEqualTo("decline");
        assertThat(approval.get()).isNotSameAs(before);
        assertThat(approval.get().taskScope()).isEmpty();
    }

    @Test
    void bindsEachTaskToItsOwnAppServerProcess()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        Path second = Files.createDirectories(worktree.resolve("second-task"));

        AgentSession first = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        first.start(command("first"));
        AgentSession other = driver.open(context("task-2", second, Optional.empty(), (request, timeout) -> new CompletableFuture<>()), event -> {});

        // One App Server process per task, each rooted in its own worktree. That working directory
        // is the sandbox root, which is what confines a turn without a separate execution
        // environment.
        assertThat(supervisor.servers).hasSize(2);
        assertThat(supervisor.serverCwds).containsExactly(worktree, second);
        assertThat(supervisor.serverOwners)
                .extracting(ProcessOwner::kind)
                .containsOnly(ProcessOwner.Kind.APP_SERVER);
        // The owner is the task, so a recovered orphan is attributable to the task that leaked it.
        assertThat(supervisor.serverOwners)
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
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.managedRequirements = "{\"requirements\":{\"allowLoginShell\":true}}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("managed requirements apply");

        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.managedRequirements = "{\"requirements\":{\"allowedLoginMethods\":[\"chatgpt\"]}}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("managed requirements apply");

        // An unknown sibling field means a policy dimension exists that this release cannot read.
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.managedRequirements = "{\"requirements\":null,\"futurePolicy\":{}}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unknown managed-policy field");
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.managedRequirements = "{\"supportsIndependentSpeedModes\":true}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unknown managed-policy field");

        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.account = "{\"requiresOpenaiAuth\":true,\"account\":{\"type\":\"apiKey\"}}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("isolated ChatGPT account");

        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.account = "{\"requiresOpenaiAuth\":false,\"account\":{\"type\":\"chatgpt\"}}"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("isolated ChatGPT account");

        // A lifecycle hook sits in the approval path that ByteQuay assumes it owns.
        assertThatThrownBy(() -> startTurnWith(supervisor -> supervisor.hooks = "[{\"name\":\"stop\"}]"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
    }

    @Test
    void readsTheAccountOncePerProcess()
    {
        driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {})
                .start(command("probe"));

        // The open checks the account; the turn's own checks in the same process do not wait on ChatGPT again.
        assertThat(supervisor.server().accountReads.get()).isEqualTo(1);
    }

    @Test
    void provesTheLaunchProfileAndItsLayersBeforeCreatingAThread()
    {
        ScriptedProcessSupervisor noLayers = new ScriptedProcessSupervisor(objectMapper);
        noLayers.configLayers = "[]";
        assertThatThrownBy(() -> driver(noLayers).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("exactly one isolated user layer");
        assertThat(noLayers.server().threadRequests).isEmpty();

        for (String mode : ImmutableList.of("disabled", "cached", "enabled")) {
            ScriptedProcessSupervisor wrongEffectiveProfile = new ScriptedProcessSupervisor(objectMapper);
            wrongEffectiveProfile.configBody = "{\"web_search\":\"" + mode + "\"}";
            assertThatThrownBy(() -> driver(wrongEffectiveProfile).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessageContaining("web_search");
            assertThat(wrongEffectiveProfile.server().threadRequests).isEmpty();
        }

        ScriptedProcessSupervisor disabledNativeReviewer = new ScriptedProcessSupervisor(objectMapper);
        ObjectNode disabledFeatures = objectMapper.valueToTree(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.V1, false, false, false));
        disabledFeatures.put("guardian_approval", false);
        disabledNativeReviewer.configBody = objectMapper.createObjectNode().set("features", disabledFeatures).toString();
        assertThatThrownBy(() -> driver(disabledNativeReviewer).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("guardian_approval");
        assertThat(disabledNativeReviewer.server().threadRequests).isEmpty();

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
        assertThatThrownBy(() -> driver(wrongSessionFlags).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("web_search");
        assertThat(wrongSessionFlags.server().threadRequests).isEmpty();

        ScriptedProcessSupervisor inheritedThreadServer = new ScriptedProcessSupervisor(objectMapper);
        inheritedThreadServer.mcpStatuses = "{\"data\":[{\"name\":\"ambient\",\"pluginId\":null}]," + "\"nextCursor\":null}";
        assertThatThrownBy(() -> driver(inheritedThreadServer).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("thread inherited an MCP server");
        assertThat(inheritedThreadServer.server().threadRequests).hasSize(1);
    }

    @Test
    void rejectsMissingOrMismatchedAgentEnablementBeforeCreatingAThread()
    {
        for (boolean enabled : ImmutableList.of(false, true)) {
            for (String value : ImmutableList.of("{}", "{\"enabled\":null}", "{\"enabled\":\"false\"}", "{\"enabled\":" + !enabled + "}")) {
                ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
                supervisor.reportModelVersion(Optional.of(enabled ? "v2" : "disabled"));
                supervisor.configBody = "{\"agents\":" + value + "}";

                assertThatThrownBy(() -> driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                        .isInstanceOf(AgentUnavailableException.class)
                        .hasMessageContaining("enabled");
                assertThat(supervisor.server().threadRequests).isEmpty();
            }
        }
    }

    @Test
    void appliesEachTurnsOwnSettingsSnapshotToTurnStart()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                Optional.of("gpt-5"), Optional.of("low")), event -> {});
        assertThat(supervisor.server().threadRequests.getFirst().at("/config/model_reasoning_effort").asText()).isEqualTo("low");

        RunningTurn first = session.start(new AgentTurnCommand(
                "turn-a",
                "first",
                ImmutableList.<AgentAttachment>of(),
                Duration.ofMinutes(5),
                Optional.of("gpt-5"),
                Optional.of("high"),
                Optional.of("priority")));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        session = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn second = session.start(new AgentTurnCommand(
                "turn-b",
                "second",
                ImmutableList.<AgentAttachment>of(),
                Duration.ofMinutes(5),
                Optional.of("gpt-5"),
                Optional.of("low")));
        supervisor.servers.getLast().completeTurn();
        second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Each turn carries the snapshot taken when it was accepted; a later default cannot
        // rewrite an earlier turn.
        assertThat(supervisor.servers)
                .extracting(process -> process.turnSettings)
                .containsExactly(ImmutableList.of("gpt-5/high"), ImmutableList.of("gpt-5/low"));
        assertThat(supervisor.servers)
                .extracting(server -> server.turnRequests.getFirst().path("serviceTierForTurn").asText())
                .containsExactly("priority", "default");
    }

    @Test
    void closingASessionInterruptsItsTurnOnceItsProcessTreeIsVerifiedStopped()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("stop"));

        session.close();

        // Native interruption settles the provider first; verified process shutdown releases ownership.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(supervisor.server().finishedWorkStops.get()).isGreaterThanOrEqualTo(1);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("interrupted"));
        assertThatThrownBy(() -> session.start(command("again")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closingASessionFailsAndKeepsItsTurnUnsettledWhenTheProcessTreeCannotBeVerified()
            throws Exception
    {
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
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        assertThat(result.providerQuiescent()).isFalse();
        // Asking again re-verifies rather than pretending the earlier close succeeded.
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void preservesACompletedTurnWhenProcessCleanupFailsAndRetainsProofForRetry()
            throws Exception
    {
        supervisor.leaveProcessTreeUnverifiable();
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("finish before cleanup"));

        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(result.terminalReason()).isEmpty();
        assertThat(supervisor.server().finishedWorkStops).hasValue(1);
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
        assertThat(supervisor.server().finishedWorkStops).hasValue(2);
        assertThat(supervisor.server().historyRequests).allSatisfy(request -> assertThat(request.path("includeTurns").asBoolean()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"systemError", "missing-history", "stale-history"})
    void stopsFailedTurnsWithoutWaitingForIdleHistory(String condition)
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("rate limited"));
        AppServerProcess server = supervisor.server();
        if (condition.equals("systemError")) {
            server.threadStatuses.put("thread-1", "systemError");
        }
        else {
            String history = condition.equals("missing-history") ? "[]" : "[{\"id\":\"prior-turn\",\"status\":\"completed\",\"items\":[]}]";
            server.historyOverrides.put("thread-1", "{\"thread\":{\"id\":\"thread-1\",\"sessionId\":\"session-1\","
                    + "\"status\":{\"type\":\"idle\"},\"turns\":" + history + "}}");
        }
        server.failTurn("exceeded retry limit, last status: 429 Too Many Requests");

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("429 Too Many Requests"));
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(server.stopRequests).hasValue(1);
        assertThat(server.finishedWorkStops).hasValue(0);
        assertThat(server.historyRequests).isEmpty();
        assertThat(server.turnLists).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disconnectCannotReplaceTheAuthoritativeFailureBeforeItsStopVerificationBegins(boolean verifiedStop)
            throws Exception
    {
        if (!verifiedStop) {
            supervisor.leaveProcessTreeUnverifiable();
        }
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("failure races disconnect"));
        AppServerProcess server = supervisor.server();
        CodexMessageDispatcher.Listener listener = (CodexMessageDispatcher.Listener) session;
        CodexProtocol.Turn reported = new CodexProtocol.Turn(server.activeTurn, CodexProtocol.TurnStatus.FAILED,
                Optional.of(new CodexProtocol.TurnError("429 Too Many Requests known-secret")), Optional.of(2L), ImmutableList.of());
        // Hold the session monitor so the asynchronous settler cannot win this race by chance.
        synchronized (session) {
            listener.onNotification(new CodexProtocol.TurnCompletedNotification(new CodexProtocol.TurnNotificationParams("thread-1", reported)));
            listener.onDisconnect(new IllegalStateException("connection lost known-secret"));
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
        }

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.providerQuiescent()).isEqualTo(verifiedStop);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> {
            assertThat(reason).contains("429 Too Many Requests [REDACTED]").doesNotContain("connection lost", "known-secret");
            if (!verifiedStop) {
                assertThat(reason).contains("Runtime cleanup:", "could not be verified stopped");
            }
        });
        assertThat(server.stopRequests).hasValue(1);
        assertThat(server.finishedWorkStops).hasValue(0);
    }

    @Test
    void failedTurnWaitsForProcessTreeStopEvenWhenChildrenAndTerminalsStillRun()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("failure with descendants"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("busy-child"));
        server.threadStatuses.put("busy-child", "active");
        server.turnStatuses.put("busy-child", "inProgress");
        supervisor.reportBackgroundTerminal();
        server.stopVerificationGate = new CompletableFuture<>();

        server.failTurn();
        awaitCondition(() -> server.stopRequests.get() == 1);

        assertThat(turn.completion().toCompletableFuture()).isNotDone();
        assertThat(server.finishedWorkStops).hasValue(0);
        server.stopVerificationGate.complete(null);
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("provider failed"));
    }

    @Test
    void preservesProviderFailureAndPartialAnswerWhenProcessStopCannotBeVerified()
            throws Exception
    {
        supervisor.leaveProcessTreeUnverifiable();
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("failure before cleanup"));
        AppServerProcess server = supervisor.server();
        server.emit("""
                {"method":"item/completed","params":{"threadId":"thread-1","turnId":"%s",
                "item":{"type":"agentMessage","id":"partial","text":"partial known-secret","phase":"commentary"}}}
                """.formatted(server.activeTurn));
        server.failTurn("429 Too Many Requests known-secret");

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.finalResponse()).contains("partial [REDACTED]");
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("429 Too Many Requests [REDACTED]", "Runtime cleanup:", "could not be verified stopped").doesNotContain("known-secret"));
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
        assertThat(server.stopRequests).hasValue(2);
        assertThat(server.finishedWorkStops).hasValue(0);
        assertThat(turn.completion().toCompletableFuture().join()).isEqualTo(result);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorNotificationsDoNotStopATurnBeforeItsTerminalCompletion(boolean willRetry)
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("retry transient failure"));
        AppServerProcess server = supervisor.server();
        server.emit("""
                {"method":"error","params":{"threadId":"thread-1","turnId":"%s",
                "error":{"message":"temporarily unavailable"},"willRetry":%s}}
                """.formatted(server.activeTurn, willRetry));
        awaitCondition(() -> events.stream().anyMatch(ProviderAgentEvent.Error.class::isInstance));

        assertThat(turn.completion().toCompletableFuture()).isNotDone();
        assertThat(server.stopRequests).hasValue(0);
        assertThat(events.stream().filter(ProviderAgentEvent.Error.class::isInstance).map(ProviderAgentEvent.Error.class::cast))
                .singleElement().satisfies(error -> assertThat(error.retryable()).isEqualTo(willRetry));
        server.completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
    }

    @Test
    void waitsForAChildTurnWithNoItemsEvenAfterTheRootFinishes()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("wait for empty child turn"));
        AppServerProcess server = supervisor.server();
        server.inventoryPageSize = 1;
        server.inventoryChildren.put("thread-1", ImmutableList.of("finished-child", "inventory-child"));
        server.turnStatuses.put("inventory-child", "inProgress");
        server.completeTurn();

        awaitCondition(() -> server.historyRequests.stream().filter(request -> request.path("threadId").asText().equals("inventory-child")).count() >= 2);
        assertThat(turn.completion().toCompletableFuture()).isNotDone();
        assertThat(server.finishedWorkStops).hasValue(0);

        // The active child appears only on inventory's second page, never in events or items.
        server.turnStatuses.put("inventory-child", "completed");
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(server.finishedWorkStops).hasValue(1);
    }

    @Test
    void acceptsAFailedChildTurnAsFinishedWork()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("terminal failed child"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("failed-child"));
        server.turnStatuses.put("failed-child", "failed");
        server.completeTurn();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(server.finishedWorkStops).hasValue(1);
    }

    @Test
    void waitsForAnActiveGrandchildAfterItsParentTurnFinishes()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("recursive completion"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("finished-child"));
        server.inventoryChildren.put("finished-child", ImmutableList.of("active-grandchild"));
        server.threadStatuses.put("finished-child", "notLoaded");
        server.threadStatuses.put("active-grandchild", "active");
        server.completeTurn();

        awaitCondition(() -> server.historyRequests.stream().filter(request -> request.path("threadId").asText().equals("active-grandchild")).count() >= 2);
        assertThat(turn.completion().toCompletableFuture()).isNotDone();
        assertThat(server.finishedWorkStops).hasValue(0);

        server.threadStatuses.put("active-grandchild", "idle");
        assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(server.finishedWorkStops).hasValue(1);
        assertThat(server.backgroundTerminalThreads).doesNotContain("finished-child");
    }

    @Test
    void waitsPastTheSettleBoundForASubagentStillWorking()
            throws Exception
    {
        try (AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(command("long child"));
            AppServerProcess server = supervisor.server();
            server.inventoryChildren.put("thread-1", ImmutableList.of("busy-child"));
            server.threadStatuses.put("busy-child", "active");
            server.completeTurn();

            // Working-child polls are one second apart; a third proves the bound was renewed twice.
            awaitCondition(() -> server.historyRequests.stream().filter(request -> request.path("threadId").asText().equals("busy-child")).count() >= 3);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThat(server.finishedWorkStops).hasValue(0);

            server.threadStatuses.put("busy-child", "idle");
            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(result.providerQuiescent()).isTrue();
        }
    }

    @Test
    void stopInterruptsASubagentTheFinishedRootLeftWorking()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("stop the child"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("busy-child"));
        server.threadStatuses.put("busy-child", "active");
        server.completeTurn();
        awaitCondition(() -> server.historyRequests.stream().anyMatch(request -> request.path("threadId").asText().equals("busy-child")));

        turn.cancel();

        // The root has no turn left to interrupt, and refusing one must not end the turn unverified.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(server.interruptedThreads).containsExactly("busy-child");
    }

    @Test
    void neverRecordsFinishedWorkWhenAChildStatusIsMissingOrUnknown()
            throws Exception
    {
        for (String status : ImmutableList.of("", "future-status", "systemError")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
            AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
            RunningTurn turn = session.start(command("unknown child status"));
            AppServerProcess server = supervisor.server();
            server.inventoryChildren.put("thread-1", ImmutableList.of("unknown-child"));
            server.threadStatuses.put("unknown-child", status);
            server.completeTurn();

            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).as("child status %s", status).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("unknown-child"));
            assertThat(server.finishedWorkStops).hasValue(0);
        }
    }

    @Test
    void neverRecordsFinishedWorkWhenAChildHasNoTurnHistory()
            throws Exception
    {
        AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("missing child history"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("missing-child"));
        server.historyOverrides.put("missing-child", "{\"thread\":{\"id\":\"missing-child\",\"sessionId\":\"session-1\"," + "\"status\":{\"type\":\"idle\"},\"turns\":[]}}");
        server.completeTurn();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("no current turn history"));
        assertThat(server.finishedWorkStops).hasValue(0);
    }

    @Test
    void neverUsesAnotherThreadsHistoryOrAnOlderRootTurnAsCompletionProof()
            throws Exception
    {
        for (String threadId : ImmutableList.of("thread-1", "other-thread")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
            AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
            RunningTurn turn = session.start(command("exact current root history"));
            AppServerProcess server = supervisor.server();
            server.historyOverrides.put("thread-1", "{\"thread\":{\"id\":\"" + threadId + "\",\"sessionId\":\"session-1\","
                    + "\"status\":{\"type\":\"idle\"},\"turns\":[{\"id\":\"prior-turn\",\"status\":\"completed\",\"items\":[]}]}}");
            server.completeTurn();

            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(server.finishedWorkStops).hasValue(0);
        }
    }

    @Test
    void rejectsACycleInTheProviderChildInventory()
            throws Exception
    {
        AgentSession session = driver(supervisor, Duration.ofMillis(300)).open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("cyclic child inventory"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("thread-1"));
        server.completeTurn();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("cycle"));
        assertThat(server.finishedWorkStops).hasValue(0);
    }

    @Test
    void waitsForAYieldedCommandBeforeCompletingATurn()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(command("yield"));
            supervisor.reportBackgroundTerminal();
            AppServerProcess server = supervisor.server();
            server.completeTurn();

            // Quiet work completes after two passes, so a third proves the command prevented completion.
            awaitCondition(() -> server.backgroundTerminalThreads.size() >= 3);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThat(server.finishedWorkStops).hasValue(0);

            supervisor.reportNoBackgroundTerminals();
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                    .isEqualTo(AgentTurnResult.State.COMPLETED);
            // A command that ends within the bound finishes its own work instead of being stopped.
            assertThat(server.terminated).isEmpty();
        }
    }

    @Test
    void waitsForActiveRootStatusEvenWhenTheCompletionNotificationArrived()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(command("closure"));
            AppServerProcess server = supervisor.server();
            server.threadStatuses.put("thread-1", "active");
            server.turnStatuses.put("thread-1", "inProgress");
            server.completeTurn();

            // Quiet work completes after two passes, so a third proves active status prevented completion.
            awaitCondition(() -> server.historyRequests.size() >= 3);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThat(server.finishedWorkStops).hasValue(0);

            server.threadStatuses.put("thread-1", "idle");
            server.turnStatuses.put("thread-1", "completed");
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state())
                    .isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    void acceptsIdleChildStatusWhenTheCommandCompletionNotificationWasMissed()
            throws Exception
    {
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
                .containsExactly(Optional.of("child-exec:started"));
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.BACKFILL_STARTED)
                .isEmpty();
    }

    @Test
    void answersWithTheLastMessageOfAModelThatLabelsNoPhases()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("no phases"));
        AppServerProcess server = supervisor.server();
        for (String message : ImmutableList.of("Reading the failing test.", "The test passes now.")) {
            server.emit("""
                    {"method":"item/completed","params":{"item":{"type":"agentMessage","id":"%s","text":"%s"},
                    "threadId":"thread-1","turnId":"%s"}}
                    """.formatted("message-" + message.length(), message, server.activeTurn));
        }
        server.completeTurnWithoutOutput();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.finalResponse()).contains("The test passes now.");
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message message && message.phase() == ProviderAgentEvent.MessagePhase.FINAL)
                .extracting(event -> ((ProviderAgentEvent.Message) event).text())
                .containsExactly("The test passes now.");
    }

    @Test
    void recoversTheRootAnswerFromCompletionWithoutLoadingHistory()
            throws Exception
    {
        supervisor.reportRecoveredRootAnswer();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("recover root answer"));

        supervisor.server().completeTurnWithSummary("[{\"type\":\"agentMessage\",\"id\":\"final-summary\"," + "\"phase\":\"final_answer\",\"text\":\"recovered root answer\"}]");
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("recovered root answer");
        assertThat(events)
                .filteredOn(ProviderAgentEvent.Message.class::isInstance)
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .doesNotContain("recovered root answer", "do not echo this prompt");
    }

    @Test
    void checksASubagentThreadThatOnlyTheProviderInventoryMentions()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("delegate"));
        supervisor.reportUnannouncedSubagent();
        supervisor.server().inventoryChildren.put("thread-1", ImmutableList.of("child-unannounced"));

        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // A subagent that never reached the notification stream still has to be checked, so its
        // thread is taken from provider inventory rather than from what happened to arrive.
        assertThat(supervisor.server().backgroundTerminalThreads).contains("child-unannounced");
        session.close();
    }

    @Test
    void terminatesAYieldedCommandWhenTheTurnIsCancelled()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();

        turn.cancel();

        // A cancelled turn is not waiting for its work, it is stopping it, so the command it left
        // running is terminated rather than watched until the bound expires.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(supervisor.server().terminated).containsExactly("proc-1");
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        session.close();
    }

    @Test
    void sendsATaskOwnedImageAsLocalImageInputAndRefusesUnsupportedAttachments()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        Path image = worktree.resolve("blobs/attachments/shot.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {1});

        // Neither an external path nor an unsupported media type may reach the App Server.
        Path elsewhere = Files.createTempFile("elsewhere", ".png");
        assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                "turn-elsewhere",
                "look",
                ImmutableList.of(new AgentAttachment("att-2", "elsewhere.png", "image/png", elsewhere)),
                Duration.ofMinutes(1))))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("task-owned attachment");
        assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                "turn-text",
                "look",
                ImmutableList.of(new AgentAttachment("att-3", "notes.bin", "application/octet-stream", image)),
                Duration.ofMinutes(1))))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("task-owned attachment");

        RunningTurn turn = session.start(new AgentTurnCommand(
                "turn-image",
                "What is in this screenshot?",
                ImmutableList.of(new AgentAttachment("att-1", "shot.png", "image/png", image)),
                Duration.ofMinutes(1)));
        supervisor.server().completeTurn();
        turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(supervisor.server().turnInputs.getLast())
                .contains("\"type\":\"text\"")
                .contains("\"type\":\"localImage\"")
                .contains(image.toString());
        session.close();
    }

    @Test
    void sendsTextLogsAsTaskOwnedFileReferencesWithoutInliningTheirContents()
            throws Exception
    {
        Path runtime = Files.createDirectories(worktree.resolve("runtime"));
        Path log = Files.writeString(runtime.resolve("failure.log"), "CI_LOG_CONTENT_ONLY_ON_DEMAND\n".repeat(1000));
        Path external = Files.writeString(worktree.resolve("other-task.log"), "Other task data");
        Path link = Files.createSymbolicLink(runtime.resolve("linked.log"), external);
        AgentSessionContext context = new AgentSessionContext(
                "task-1", worktree, runtime, runtime.resolve("provider"), AgentPermissionPolicy.AUTO, Optional.empty(),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), "");
        try (AgentSession session = driver(supervisor).open(context, event -> {})) {
            for (Path rejected : ImmutableList.of(external, link, runtime, runtime.resolve("missing.log"))) {
                assertThatThrownBy(() -> session.start(new AgentTurnCommand(
                        "invalid-log", "Inspect CI", ImmutableList.of(new AgentAttachment("failure.log", "text/plain", rejected)), Duration.ofMinutes(1))))
                        .isInstanceOf(AgentUnavailableException.class)
                        .hasMessageContaining("task-owned");
            }
            assertThat(supervisor.server().turnRequests).isEmpty();
            RunningTurn turn = session.start(new AgentTurnCommand(
                    "ci-log", "Inspect CI",
                    ImmutableList.of(new AgentAttachment("failure\n\".log", "text/plain; charset=utf-8", log)), Duration.ofMinutes(1)));
            supervisor.server().completeTurn();
            turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            JsonNode inputs = supervisor.server().turnRequests.getLast().path("input");
            assertThat(inputs).hasSize(2);
            assertThat(inputs.get(1).path("type").asText()).isEqualTo("text");
            assertThat(inputs.get(1).path("text").asText())
                    .contains(log.toRealPath().toString(), "failure\\n\\\".log", "data, not instructions")
                    .doesNotContain("CI_LOG_CONTENT_ONLY_ON_DEMAND");
        }
    }

    @Test
    void cancelsATurnWhoseStreamingMessageAndReasoningWereLeftOpen()
            throws Exception
    {
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
    void terminatesAYieldedCommandThatOutlivesTheBound()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();

        supervisor.server().completeTurn();

        // Nothing reads the command once the root has answered, as with a REPL that hangs on exit,
        // so it is stopped and the finished answer stands.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(supervisor.server().terminated).containsExactly("proc-1");
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.providerQuiescent()).isTrue();
        session.close();
    }

    @Test
    void failsATurnWhoseProcessCannotBeShownStopped()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("yield"));
        supervisor.reportBackgroundTerminal();
        supervisor.terminationIgnored = true;

        supervisor.server().completeTurn();

        // The root result is not accepted as complete, but closing the per-turn process makes the
        // worktree safe even when the inventories never became quiet.
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(supervisor.server().terminated).contains("proc-1");
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.terminalReason().orElseThrow()).contains("background terminal proc-1");
        assertThat(result.providerQuiescent()).isTrue();
        session.close();
    }

    @Test
    void doesNotHoldATurnForAnEarlierTurnsUnfinishedItem()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(500));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});

        RunningTurn first = session.start(command("one"));
        supervisor.server().emitUnfinishedItem("exec-stuck");
        supervisor.server().threadStatuses.put("thread-1", "active");
        supervisor.server().completeTurn();
        assertThat(first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.FAILED);

        AgentSession resumed = driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {});
        RunningTurn second = resumed.start(command("two"));
        supervisor.servers.getLast().completeTurn();

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
    void failsWhenAnObservedChildIsAbsentFromTheProviderInventory()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor, Duration.ofMillis(300));
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("orphan activity"));

        supervisor.server().emitSubagentActivity("orphan-child");
        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("thread orphan-child")
                .contains("agent inventory"));
        assertThat(supervisor.server().finishedWorkStops).hasValue(0);
    }

    @Test
    void recordsTheSubagentsPromptAndItsTerminalOutcome()
            throws Exception
    {
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
    void checkingAnInventoryOnlyChildDoesNotInventDisplayActivity()
            throws Exception
    {
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("inventory child"));
        supervisor.reportUnannouncedSubagent();
        supervisor.server().inventoryChildren.put("thread-1", ImmutableList.of("child-unannounced"));
        supervisor.server().completeTurn();

        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.server().backgroundTerminalThreads).contains("child-unannounced");
        assertThat(events).noneMatch(event -> event.source().providerThreadId().filter("child-unannounced"::equals).isPresent());
    }

    @Test
    void preservesV1ChildAndGrandchildLineageFromLiveActivity()
            throws Exception
    {
        supervisor.scriptRecursiveV1Activity();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("nested live activity"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.emitScriptedActivity("child-history");
        supervisor.emitScriptedActivity("grandchild-history");

        supervisor.server().completeTurn();
        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.server().backgroundTerminalThreads)
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routesV2ChildMessagesToTheRootOnFreshAndResumedSessions(boolean resume)
            throws Exception
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.scriptV2Activity();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        if (resume) {
            AgentSessionRef reference = session.reference();
            session.close();
            session = driver.open(context(Optional.of(reference), new AtomicReference<>()), events::add);
            assertThat(supervisor.servers.getLast().resumeCount()).isOne();
        }
        RunningTurn turn = session.start(command("v2 live activity"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.emitScriptedActivity("v2-child");

        supervisor.servers.getLast().completeTurn();
        assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);

        List<ProviderAgentEvent> discoveries = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .toList();
        assertThat(discoveries).singleElement().satisfies(discovery -> {
            assertThat(discovery.source().providerThreadId()).contains("v2-child");
            assertThat(discovery.source().parentProviderThreadId()).contains("thread-1");
            assertThat(discovery.source().logicalAgentPath()).contains("/root/team/reviewer");
        });
        assertThat(events)
                .filteredOn(event -> event.source().providerThreadId().filter("thread-1"::equals).isPresent())
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.source().parentProviderThreadId()).isEmpty();
                    assertThat(event.source().logicalAgentPath()).contains("/");
                });
        assertThat(supervisor.servers.getLast().backgroundTerminalThreads).doesNotContain("not-a-child");
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.PROMPT)
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .contains("supplementary prompt");
    }

    @Test
    void finishesAnIdleInventoryConfirmedV2ChildWithoutInventingItsMissingDisplayPath()
            throws Exception
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("missing display path"));
        AppServerProcess server = supervisor.server();
        server.inventoryChildren.put("thread-1", ImmutableList.of("v2-no-path"));
        server.emitThreadStarted("v2-no-path", "Display metadata is incomplete");
        server.emitSubagentActivity("v2-no-path");
        server.completeTurn();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(server.finishedWorkStops).hasValue(1);
        assertThat(events).noneMatch(event -> event.source().providerThreadId().filter("v2-no-path"::equals).isPresent());
    }

    @Test
    void preservesNestedV2PathsForAChildAndItsDirectGrandchild()
            throws Exception
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.scriptNestedV2Activity();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("nested v2"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.emitScriptedActivity("v2-child");

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
                        org.assertj.core.groups.Tuple.tuple("v2-child", "thread-1", "/root/reviewer"),
                        org.assertj.core.groups.Tuple.tuple("v2-grandchild", "v2-child", "/root/reviewer/researcher"));
    }

    @Test
    void rejectsUnknownRelativeOrUnstableV2SupplementaryActivity()
            throws Exception
    {
        AgentTurnResult unknown = runInvalidV2Activity("unknown-child", "/root/unknown");
        assertThat(unknown.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("graph identity"));

        AgentTurnResult relative = runInvalidV2Activity("v2-child", "relative/path");
        assertThat(relative.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("absolute"));

        AgentTurnResult changed = runInvalidV2Activity("v2-child", "/root/changed");
        assertThat(changed.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("path changed"));
        AgentTurnResult changedRoot = runInvalidV2Activity("thread-1", "/");
        assertThat(changedRoot.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("path changed"));
        AgentTurnResult rootPath = runInvalidRootV2Activity();
        assertThat(rootPath.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("not nested"));
        AgentTurnResult notNested = runInvalidNestedV2Activity();
        assertThat(notNested.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("not nested"));
        assertThat(ImmutableList.of(unknown, relative, changed, changedRoot, rootPath, notNested)).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.providerQuiescent()).isTrue();
        });
    }

    @Test
    void rejectsUnknownIdentitiesFromBothNonselectedSupplementaryFormats()
            throws Exception
    {
        AgentTurnResult v1UnderV2 = runUnknownSupplement(Optional.of("v2"), ScriptedProcessSupervisor::reportUnknownV1Supplement);
        AgentTurnResult v2UnderV1 = runUnknownSupplement(Optional.of("v1"), ScriptedProcessSupervisor::reportUnknownV2Supplement);

        assertThat(ImmutableList.of(v1UnderV2, v2UnderV1)).allSatisfy(result -> {
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("graph identity"));
        });
    }

    @Test
    void preservesLiveMessagesWithTheSameItemIdFromDistinctChildTurns()
            throws Exception
    {
        supervisor.reportDuplicateChildItemIdsAcrossTurns();
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("duplicate item ids"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.emitScriptedActivity("duplicate-child");

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
    void syntheticHistoryMessageIdsDoNotDuplicateOrReplaceTheLiveAnswer()
            throws Exception
    {
        supervisor.reportRecoveredRootAnswer();
        AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("synthetic message identity"));
        supervisor.server().completeTurn();

        AgentTurnResult result = turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(events)
                .filteredOn(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.ANSWER
                        && message.phase() == ProviderAgentEvent.MessagePhase.FINAL)
                .map(event -> ((ProviderAgentEvent.Message) event).text())
                .containsExactly("authoritative [REDACTED]");
        assertThat(events).noneMatch(event -> event.providerEventId().filter("item-2"::equals).isPresent());
    }

    @Test
    void rechecksInheritedCapabilitiesBeforeEveryTurn()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn first = session.start(command("first"));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // A workspace-write turn can leave a hook behind, so a clean first turn proves nothing
        // about the second.
        supervisor.hooks = "[{\"name\":\"stop\"}]";

        assertThatThrownBy(() -> driver.open(context(Optional.of(session.reference()), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("lifecycle hooks apply");
    }

    @Test
    void relaunchesTheAppServerInTheSameWorktreeOnResume()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        AgentSessionRef reference = initial.reference();
        initial.close();

        AgentSession resumed = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {});

        // The process that served the first session died with it, so a stored thread is only
        // resumable inside a replacement rooted at the same worktree.
        assertThat(supervisor.servers).hasSize(2);
        assertThat(supervisor.serverCwds).containsExactly(worktree, worktree);
        assertThat(supervisor.serverOwners)
                .extracting(ProcessOwner::id)
                .containsExactly("task-1", "task-1");
        assertThat(supervisor.servers.getLast().resumeCount()).isOne();
        resumed.close();
    }

    @Test
    void ignoresPriorRootTurnChildrenUnknownItemsAndUnfinishedWorkAfterResume()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession firstSession = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn first = firstSession.start(command("first process"));
        supervisor.server().completeTurn();
        first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        supervisor.reportOnlyPriorRootHistory();
        AgentSession resumed = driver.open(context(Optional.of(firstSession.reference()), new AtomicReference<>()), events::add);
        RunningTurn current = resumed.start(command("current process"));
        supervisor.servers.getLast().completeTurn();
        AgentTurnResult result = current.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(events).noneMatch(event -> event.source().providerThreadId().filter("old-child"::equals).isPresent());
        assertThat(supervisor.servers.getLast().backgroundTerminalThreads).doesNotContain("old-child");
    }

    @Test
    void rechecksInheritedCapabilitiesOnTheFirstTurnAfterResume()
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        AgentSessionRef reference = initial.reference();
        initial.close();

        // A hook can appear while a task is unloaded, so a resumed thread proves nothing about the
        // process now serving it.
        supervisor.hooks = "[{\"name\":\"stop\"}]";
        assertThatThrownBy(() -> driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {}))
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

    private List<String> ciWritableRoots(Path taskWorktree)
    {
        return Stream.concat(
                        GitWorktreeWriteRoots.writableRoots(taskWorktree).stream(),
                        BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", worktree.toString())).stream().map(Path::toString))
                .toList();
    }

    private CodexAppServerClient appServerClient(ProcessSupervisor supervisor)
    {
        ProcessSpec spec = new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.APP_SERVER, "task-1"),
                ImmutableList.of("/fake/codex", "app-server"),
                worktree,
                ImmutableMap.of(),
                OptionalInt.empty(),
                1024,
                Duration.ofMinutes(1),
                Duration.ZERO);
        return new CodexAppServerClient(supervisor, spec, objectMapper, SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty());
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor, Duration quiescenceTimeout)
    {
        return driver(supervisor, "0.153.4", quiescenceTimeout);
    }

    private CodexCliDriver driver(ProcessSupervisor supervisor, String minimumVersion, Duration quiescenceTimeout)
    {
        try {
            CodexHome.prepare(codexHome());
        }
        catch (IOException e) {
            throw new AssertionError("unable to prepare test Codex home", e);
        }
        return new CodexCliDriver(
                "/fake/codex",
                minimumVersion,
                objectMapper,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        // A shell that exports its own home must not redirect ByteQuay's processes.
                        "CODEX_HOME", "/Users/someone/.codex",
                        "OPENAI_API_KEY", "known-secret",
                        "GH_TOKEN", "github-sentinel",
                        "GH_CONFIG_DIR", worktree.resolve("gh-config").toString()),
                codexHome(),
                PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "").build(),
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
        List<String> arguments = supervisor.serverArgv.getFirst();
        session.close();
        return arguments;
    }

    private AgentTurnResult runInvalidV2Activity(String threadId, String agentPath)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.scriptInvalidV2Activity(threadId, agentPath);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid v2"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsCanonicalV2PathRegardlessOfThreadMetadataOrder(boolean threadMetadataFirst)
            throws Exception
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), events::add);
        RunningTurn turn = session.start(command("live v2 path"));
        String threadId = "live-v2-child";
        String agentPath = "/root/live-reviewer";

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
        assertThat(childEvents).allSatisfy(event -> assertThat(event.source().logicalAgentPath()).contains(agentPath));
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

    private AgentTurnResult runUnknownSupplement(Optional<String> version, Consumer<ScriptedProcessSupervisor> activity)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(version);
        activity.accept(supervisor);
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("unknown supplement"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private AgentTurnResult runInvalidNestedV2Activity()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.scriptNestedV2Activity("/root/other/researcher");
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid nested v2"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.emitScriptedActivity("v2-child");
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private AgentTurnResult runInvalidRootV2Activity()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper);
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.scriptRootV2Activity("/root");
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        RunningTurn turn = session.start(command("invalid root v2"));
        supervisor.emitScriptedActivity("thread-1");
        supervisor.server().completeTurn();
        return turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void privateReviewPolicyRestrictsInitialAndResumedSessionsWithoutReportingTools()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "Keep private experiments uncommitted");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        CodexHome.prepare(codexHome());
        List<String> writableRoots = Stream.concat(Stream.of(worktree.toRealPath()), BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", worktree.toString())).stream())
                .map(Path::toString).toList();
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString(), "GH_TOKEN", "github-sentinel",
                        "GH_CONFIG_DIR", worktree.resolve("gh-config").toString(), "SSH_AUTH_SOCK", worktree.resolve("ssh.sock").toString()),
                codexHome(), broker, Duration.ofSeconds(30));
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
            try (AgentSession session = driver.open(context(reference, approval), event -> {})) {
                AppServerProcess server = supervisor.servers.getLast();
                JsonNode thread = server.threadRequests.getFirst();
                assertThat(thread.path("developerInstructions").asText())
                        .startsWith(AgentCommandPolicy.reviewInstructions()).contains("native update_plan").endsWith(review.instruction())
                        .doesNotContain(CodexReviewGate.INSTRUCTION);
                assertThat(thread.path("approvalsReviewer").asText()).isEqualTo("auto_review");
                assertThat(thread.path("config").has("auto_review")).isFalse();
                assertThat(supervisor.serverArgv.getLast())
                        .noneMatch(argument -> argument.startsWith("auto_review.policy="));
                assertThat(supervisor.serverArgv.getLast()).containsSequence("--enable", "guardian_approval");
                assertThat(thread.path("config").path("features").path("guardian_approval").asBoolean()).isTrue();
                assertThat(supervisor.serverArgv.getLast()).contains("mcp_servers.bytequay.default_tools_approval_mode=\"approve\"");
                assertThat(thread.path("config").path("features").path("multi_agent_v2").asBoolean()).isFalse();
                assertThat(thread.path("config").path("features").path("hooks").asBoolean()).isFalse();
                assertThat(supervisor.environments.getLast())
                        .doesNotContainKeys("GH_CONFIG_DIR", "SSH_AUTH_SOCK", "GH_TOKEN", "GITHUB_TOKEN");
                assertThat(thread.path("config").path("shell_environment_policy").path("include_only"))
                        .isEqualTo(objectMapper.valueToTree(CodexLaunchProfile.expectedShellEnvironmentIncludeOnly()));
                String token = supervisor.environments.getLast().get("BYTEQUAY_TOOL_TOKEN");
                assertThat(token).isNotBlank();
                JsonNode listed = broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)).body().orElseThrow();
                assertThat(listed.at("/result/tools")).isEmpty();
                assertThat(supervisor.serverArgv.getLast()).contains("mcp_servers.bytequay.tools={}");

                String admittedConfig = supervisor.configBody;
                supervisor.configBody = admittedConfig.replace("approve", "prompt");
                assertThatThrownBy(() -> session.start(command("private review")))
                        .isInstanceOf(AgentUnavailableException.class)
                        .hasMessageContaining("default_tools_approval_mode");
                assertThat(server.turnRequests).isEmpty();
                ObjectNode wrongScope = (ObjectNode) objectMapper.readTree(admittedConfig);
                ObjectNode wrongFeatures = objectMapper.valueToTree(CodexLaunchProfile.expectedFeatures(CodexLaunchProfile.ChildProfile.NONE, false, true, false));
                wrongFeatures.put("guardian_approval", false);
                wrongScope.set("features", wrongFeatures);
                supervisor.configBody = wrongScope.toString();
                assertThatThrownBy(() -> session.start(command("private review")))
                        .isInstanceOf(AgentUnavailableException.class)
                        .hasMessageContaining("guardian_approval");
                assertThat(server.turnRequests).isEmpty();
                supervisor.configBody = admittedConfig;
                session.start(command("private review"));
                assertThat(server.turnRequests.getFirst().path("approvalsReviewer").asText()).isEqualTo("auto_review");
                assertThat(server.turnRequests.getFirst().path("sandboxPolicy").path("type").asText()).isEqualTo("workspaceWrite");
                assertThat(server.turnRequests.getFirst().path("sandboxPolicy").path("writableRoots"))
                        .isEqualTo(objectMapper.valueToTree(writableRoots));
                List<String> deniedCommands = ImmutableList.of("git commit -am experiment", "git update-ref refs/heads/topic HEAD", "git push origin HEAD");
                for (String shellCommand : deniedCommands) {
                    approval.set(null);
                    ObjectNode params = objectMapper.createObjectNode()
                            .put("threadId", "thread-1")
                            .put("turnId", server.activeTurn)
                            .put("itemId", "private-review-command")
                            .put("command", shellCommand)
                            .put("startedAtMs", 1786884871461L);
                    params.set("availableDecisions", objectMapper.valueToTree(ImmutableList.of("accept", "decline")));
                    int requestId = 95 + deniedCommands.indexOf(shellCommand);
                    ObjectNode request = objectMapper.createObjectNode().put("id", requestId).put("method", "item/commandExecution/requestApproval");
                    request.set("params", params);
                    server.emit(request.toString());
                    awaitCondition(() -> server.result(requestId) != null);
                    assertThat(server.result(requestId).path("decision").asText()).isEqualTo("decline");
                    assertThat(approval).hasValue(null);
                }
                server.emit("""
                        {"id":98,"method":"item/commandExecution/requestApproval","params":{
                        "threadId":"thread-1","turnId":"%s","itemId":"stage-experiment",
                        "command":"git add TestExperiment.java","availableDecisions":["accept","decline"],
                        "startedAtMs":1786884871461}}
                        """.formatted(server.activeTurn));
                awaitCondition(() -> server.result(98) != null);
                assertThat(server.result(98).path("decision").asText()).isEqualTo("accept");
                assertThat(approval.get().detail()).contains("git add TestExperiment.java");
                reference = Optional.of(session.reference());
            }
        }
    }

    @Test
    void rejectsOutdatedRootToolDefinitionsBeforeStartingTheProvider()
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSessionRef outdated = new AgentSessionRef(new AgentLaneId("codex"), 2, "thread-1");
        assertThatThrownBy(() -> driver.open(context(Optional.of(outdated), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("outdated tool list", "Start a new task");
        assertThat(supervisor.servers).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void endingATurnInterruptsItsRunningRootTool(boolean cancel)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "context", true),
                new ReviewTool("review_finish", "Finish review", ImmutableMap.of("type", "object"), arguments -> {
                    entered.countDown();
                    try {
                        release.await();
                        return "finished";
                    }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        interrupted.countDown();
                        throw new AgentUnavailableException("Tool interrupted", failure);
                    }
                })), "Review privately");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            RunningTurn turn = session.start(command("Review privately"));
            AppServerProcess server = supervisor.servers.getLast();
            server.emit("""
                    {"id":201,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                    "callId":"root-write","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"review_finish","arguments":{}}}}
                    """.formatted(server.activeTurn));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            if (cancel) {
                turn.cancel();
            }
            else {
                server.completeTurn();
            }

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void guardedReviewUsesReadOnlyMcpAndStickyRootToolsAcrossResume()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "context", true),
                new ReviewTool("review_finish", "Finish review", ImmutableMap.of("type", "object"), arguments -> "finished")), "Review privately");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
            try (AgentSession session = driver.open(context(reference, approval), event -> {})) {
                assertThat(session.reference().contractVersion()).isEqualTo(3);
                assertThat(driver.reviewChildAgents(Optional.of(session.reference()), Optional.empty())).isTrue();
                assertThat(driver.reviewChildAgents(Optional.of(new AgentSessionRef(
                        session.reference().laneId(), 1, session.reference().providerValue())), Optional.empty())).isFalse();
                assertThat(driver.reviewChildAgents(Optional.of(session.reference()), Optional.of("unknown-model"))).isFalse();
                JsonNode thread = supervisor.servers.getLast().threadRequests.getFirst();
                assertThat(thread.path("developerInstructions").asText())
                        .contains(review.instruction(), "repository_memory SEARCH/READ", "read-only source searches", "send_message",
                                "in each child's self-contained assignment", "in your final answer", "must not edit files", "request broader permissions")
                        .endsWith(CodexReviewGate.INSTRUCTION);
                assertThat(thread.path("config").path("bypass_hook_trust").asBoolean()).isTrue();
                assertThat(thread.path("config").path("features").path("multi_agent_v2").asBoolean()).isTrue();
                if (index == 0) {
                    assertThat(thread.path("dynamicTools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(PermissionBroker.CALL_TOOL);
                }
                else {
                    assertThat(thread.path("dynamicTools").isMissingNode()).isTrue();
                }
                List<String> arguments = supervisor.serverArgv.getLast();
                assertThat(arguments).contains("mcp_servers.bytequay.tools={\"review_get_context\"={approval_mode=\"approve\"},"
                        + "\"bytequay_list_tools\"={approval_mode=\"approve\"},\"bytequay_get_tool\"={approval_mode=\"approve\"}}");
                String token = supervisor.environments.getLast().get("BYTEQUAY_TOOL_TOKEN");
                JsonNode listed = broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)).body().orElseThrow();
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly("review_get_context", "bytequay_list_tools", "bytequay_get_tool");
                AppServerProcess server = supervisor.servers.getLast();
                session.start(command("Review privately"));
                server.emit("{\"id\":201,\"method\":\"item/tool/call\",\"params\":{"
                        + "\"threadId\":\"thread-1\",\"turnId\":\"" + server.activeTurn + "\","
                        + "\"callId\":\"root-write\",\"tool\":\"bytequay_call_tool\",\"namespace\":null,\"arguments\":{\"name\":\"review_finish\",\"arguments\":{}}}}");
                awaitCondition(() -> server.result(201) != null);
                assertThat(server.result(201).path("success").asBoolean()).isTrue();
                assertThat(server.result(201).at("/contentItems/0/text").asText()).isEqualTo("finished");
                server.emit("{\"id\":203,\"method\":\"item/tool/call\",\"params\":{"
                        + "\"threadId\":\"thread-1\",\"turnId\":\"stale-turn\","
                        + "\"callId\":\"stale-write\",\"tool\":\"bytequay_call_tool\",\"namespace\":null,\"arguments\":{\"name\":\"review_finish\",\"arguments\":{}}}}");
                awaitCondition(() -> server.error(203) != null);
                server.emitV2Started("child-thread", "/fixture-child");
                server.emit("{\"id\":202,\"method\":\"item/tool/call\",\"params\":{"
                        + "\"threadId\":\"child-thread\",\"turnId\":\"child-turn\","
                        + "\"callId\":\"child-write\",\"tool\":\"bytequay_call_tool\",\"namespace\":null,\"arguments\":{\"name\":\"review_finish\",\"arguments\":{}}}}");
                awaitCondition(() -> server.error(202) != null);
                server.emit("{\"id\":204,\"method\":\"item/commandExecution/requestApproval\",\"params\":{"
                        + "\"threadId\":\"child-thread\",\"turnId\":\"child-turn\",\"itemId\":\"child-shell\","
                        + "\"command\":\"touch file\",\"availableDecisions\":[\"accept\",\"decline\"],"
                        + "\"startedAtMs\":1786884871461}}");
                awaitCondition(() -> server.error(204) != null);
                assertThat(approval.get()).isNull();
                reference = Optional.of(session.reference());
            }
        }
    }

    @Test
    void relatedWorkUsesRootToolsAcrossResumeAndRejectsChildAndStaleTurnCalls()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        AtomicInteger calls = new AtomicInteger();
        var related = new RelatedWorkspaceBridge(arguments -> {
            calls.incrementAndGet();
            return "linked";
        });
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(related)).build();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            try (AgentSession session = driver.open(context(reference, new AtomicReference<>()), event -> {})) {
                assertThat(session.reference().contractVersion()).isEqualTo(3);
                AppServerProcess server = supervisor.servers.getLast();
                JsonNode thread = server.threadRequests.getFirst();
                if (index == 0) {
                    assertThat(thread.path("dynamicTools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(PermissionBroker.CALL_TOOL);
                }
                else {
                    assertThat(thread.path("dynamicTools").isMissingNode()).isTrue();
                }
                session.start(command("Investigate in Iceberg"));
                server.emit("""
                        {"id":301,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                        "callId":"root","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"related_workspace_work","arguments":{}}}}
                        """.formatted(server.activeTurn));
                awaitCondition(() -> server.result(301) != null);
                assertThat(server.result(301).path("success").asBoolean()).isTrue();
                server.emit("""
                        {"id":302,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"stale",
                        "callId":"stale","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"related_workspace_work","arguments":{}}}}
                        """);
                awaitCondition(() -> server.error(302) != null);
                server.emitV2Started("child-thread", "/fixture-child");
                server.emit("""
                        {"id":303,"method":"item/tool/call","params":{"threadId":"child-thread","turnId":"child-turn",
                        "callId":"child","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"related_workspace_work","arguments":{}}}}
                        """);
                awaitCondition(() -> server.error(303) != null);
                assertThat(calls).hasValue(index + 1);
                reference = Optional.of(session.reference());
            }
        }
    }

    @Test
    void reusesOneProcessWithFreshTurnCallbacksAndRootAuthority()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.159.3", false);
        AtomicInteger generation = new AtomicInteger();
        List<Integer> writes = new CopyOnWriteArrayList<>();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> {
                    int bound = generation.get();
                    return Optional.of(new ReviewBridge(ImmutableList.of(
                            new ReviewTool("review_get_context", "Read context", ImmutableMap.of("type", "object"), arguments -> "context-" + bound, true),
                            new ReviewTool("review_finish", "Finish review", ImmutableMap.of("type", "object"), arguments -> {
                                writes.add(bound);
                                return "finished";
                            })), "Review privately"));
                }).build();
        CodexCliDriver driver = reusableDriver(supervisor, broker);
        List<List<ProviderAgentEvent>> events = new ArrayList<>();
        List<ProviderAgentEvent> firstEvents = new CopyOnWriteArrayList<>();
        List<Integer> eventCounts = new ArrayList<>();
        try (AgentSession session = driver.open(reusableContext(Optional.empty()), firstEvents::add)) {
            session.allowReuse();
            AppServerProcess server = supervisor.server();
            String token = supervisor.environments.getLast().get("BYTEQUAY_TOOL_TOKEN");
            for (int index = 0; index < 3; index++) {
                generation.set(index);
                List<ProviderAgentEvent> currentEvents = index == 0 ? firstEvents : new CopyOnWriteArrayList<>();
                events.add(currentEvents);
                if (index > 0) {
                    assertThat(session.prepareTurn(reusableContext(Optional.of(session.reference())), currentEvents::add)).isTrue();
                }
                RunningTurn turn = session.start(command("message-" + index));
                server.emit("""
                        {"method":"account/rateLimits/updated","params":{"rateLimits":{}}}
                        """);
                int requestId = 700 + index;
                server.emit("""
                        {"id":%d,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                        "callId":"root","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"review_finish","arguments":{}}}}
                        """.formatted(requestId, server.activeTurn));
                awaitCondition(() -> server.result(requestId) != null);
                assertThat(server.result(requestId).path("success").asBoolean()).isTrue();
                assertThat(broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"review_finish","arguments":{}}}
                        """)).body().orElseThrow().has("error")).isTrue();
                assertThat(broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"bytequay_call_tool","arguments":{"name":"review_finish","arguments":{}}}}
                        """)).body().orElseThrow().has("error")).isTrue();
                assertThat(broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"review_get_context","arguments":{}}}
                        """)).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("context-" + index);
                if (index > 0) {
                    int staleId = 800 + index;
                    server.emit("""
                            {"id":%d,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-%d",
                            "callId":"stale","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"review_finish","arguments":{}}}}
                            """.formatted(staleId, index));
                    awaitCondition(() -> server.error(staleId) != null);
                    server.emitUsage("thread-1", "turn-" + index, AppServerProcess.tokens(1, 1, 0, 0), AppServerProcess.tokens(1, 1, 0, 0));
                }
                server.emitUsage("thread-1", server.activeTurn,
                        AppServerProcess.tokens(1100 + 100L * index, 880 + 80L * index, 330 + 30L * index, 220 + 20L * index), AppServerProcess.tokens(100, 80, 30, 20));
                server.emit("""
                        {"method":"item/completed","params":{"threadId":"thread-1","turnId":"%s",
                        "item":{"type":"agentMessage","id":"message","text":"answer-%d","phase":"final_answer"}}}
                        """.formatted(server.activeTurn, index));
                server.completeTurnWithoutOutput();
                assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(currentEvents).anyMatch(event -> event instanceof ProviderAgentEvent.Message);
                assertThat(currentEvents).filteredOn(event -> event instanceof ProviderAgentEvent.Usage)
                        .singleElement().satisfies(event -> assertThat(((ProviderAgentEvent.Usage) event).sample().inputTokens()).isEqualTo(80));
                for (int previous = 0; previous < index; previous++) {
                    assertThat(events.get(previous)).hasSize(eventCounts.get(previous));
                }
                eventCounts.add(currentEvents.size());
                assertThat(session.retained()).isTrue();
                assertThat(server.exit).isNotDone();
                assertThat(server.finishedWorkStops).hasValue(0);
            }
            assertThat(writes).containsExactly(0, 1, 2);
            assertThat(supervisor.servers).hasSize(1);
            assertThat(server.threadRequests).hasSize(1);
            assertThat(server.turnRequests).hasSize(3);
        }
        assertThat(supervisor.server().exit).isDone();
    }

    @Test
    void retainedDoesNotWaitForAnEventBeingRecorded()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.159.3", false);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(new RelatedWorkspaceBridge(arguments -> "linked"))).build();
        CodexCliDriver driver = reusableDriver(supervisor, broker);
        ReentrantLock taskLock = new ReentrantLock();
        CountDownLatch recording = new CountDownLatch(1);
        try (AgentSession session = driver.open(reusableContext(Optional.empty()), event -> {
            if (event instanceof ProviderAgentEvent.Usage) {
                recording.countDown();
                // Agent-core records usage under the task lock, which its own threads hold while asking for retained().
                taskLock.lock();
                taskLock.unlock();
            }
        })) {
            session.allowReuse();
            RunningTurn turn = session.start(command("first"));
            AppServerProcess server = supervisor.server();
            taskLock.lock();
            try {
                server.emitUsage("thread-1", server.activeTurn, AppServerProcess.tokens(10, 8, 0, 2), AppServerProcess.tokens(10, 8, 0, 2));
                assertThat(recording.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(CompletableFuture.supplyAsync(session::retained, Thread::startVirtualThread).get(5, TimeUnit.SECONDS)).isFalse();
            }
            finally {
                taskLock.unlock();
            }
            server.completeTurnWithoutOutput();
            turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(session.retained()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "config", "instructions", "model", "effort", "task", "worktree", "questions"})
    void incompatibleConfigurationRejectsWarmReuseBeforeSending(String change)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.159.3", false);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(new RelatedWorkspaceBridge(arguments -> "linked"))).build();
        CodexCliDriver driver = reusableDriver(supervisor, broker);
        try (AgentSession session = driver.open(reusableContext(Optional.empty()), event -> {})) {
            session.allowReuse();
            RunningTurn first = session.start(command("first"));
            supervisor.server().completeTurnWithoutOutput();
            first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(session.retained()).isTrue();
            AgentSessionContext original = reusableContext(Optional.of(session.reference()));
            if (change.equals("auth") || change.equals("config")) {
                Files.writeString(codexHome().resolve(change.equals("auth") ? "auth.json" : "config.toml"), "changed");
            }
            AgentSessionContext next = new AgentSessionContext(
                    change.equals("task") ? "another-task" : original.taskId(), change.equals("worktree") ? worktree.resolve("other") : original.worktree(),
                    original.runtimeDirectory(), original.providerDirectory(), original.permissionPolicy(), original.sessionReference(), original.approvals(),
                    change.equals("model") ? Optional.of("another-model") : original.model(), change.equals("effort") ? Optional.of("high") : original.effort(),
                    change.equals("questions") ? SessionAccess.READ_ONLY : original.access(), original.codeQueries(),
                    change.equals("instructions") ? "new instructions" : original.managedInstructions(),
                    original.discussionContext(), original.sessionTools(), change.equals("questions"));
            assertThat(session.prepareTurn(next, event -> {})).isFalse();
            assertThat(supervisor.server().turnRequests).hasSize(1);
        }
        assertThat(supervisor.server().exit).isDone();
    }

    @Test
    void uninterruptibleLocalToolKeepsShutdownUnverifiedUntilItReturns()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.159.3", false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(new RelatedWorkspaceBridge(arguments -> {
                    entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            release.await();
                            released = true;
                        }
                        catch (InterruptedException ignored) {
                            // Simulate a local operation that cannot stop until its external call returns.
                        }
                    }
                    returned.countDown();
                    return "linked";
                }))).build();
        AgentSession session = reusableDriver(supervisor, broker).open(reusableContext(Optional.empty()), event -> {});
        try {
            session.allowReuse();
            RunningTurn turn = session.start(command("slow local tool"));
            AppServerProcess server = supervisor.server();
            server.emit("""
                    {"id":900,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                    "callId":"slow","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"related_workspace_work","arguments":{}}}}
                    """.formatted(server.activeTurn));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            server.completeTurnWithoutOutput();
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).providerQuiescent()).isFalse();
            assertThat(session.retained()).isFalse();
            assertThatThrownBy(session::close).hasMessageContaining("Local tool callbacks could not be verified stopped");
            release.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            session.close();
        }
        finally {
            release.countDown();
            session.close();
        }
    }

    @Test
    void approvalActivityForcesShutdownEvenWhenReuseIsEnabled()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.159.3", false);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(new RelatedWorkspaceBridge(arguments -> "linked"))).build();
        try (AgentSession session = reusableDriver(supervisor, broker).open(reusableContext(Optional.empty()), event -> {})) {
            session.allowReuse();
            RunningTurn turn = session.start(command("permission"));
            supervisor.server().completeTurn();
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(session.retained()).isFalse();
            assertThat(supervisor.server().exit).isDone();
        }
    }

    private CodexCliDriver reusableDriver(ScriptedProcessSupervisor supervisor, PermissionBroker broker)
            throws IOException
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.withTaskTools();
        CodexHome.prepare(codexHome());
        return new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
    }

    private AgentSessionContext reusableContext(Optional<AgentSessionRef> reference)
            throws IOException
    {
        AgentSessionContext original = context(reference, new AtomicReference<>());
        return new AgentSessionContext(original.taskId(), original.worktree(), Files.createDirectories(worktree.resolve("runtime")),
                Files.createDirectories(worktree.resolve("runtime/provider")), original.permissionPolicy(), reference, original.approvals(), Optional.of("gpt-5"), original.effort(),
                original.access(), original.codeQueries(), original.managedInstructions());
    }

    @Test
    void withdrawsAnApprovalAppServerSettledItself()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        CompletableFuture<ProviderApprovalDecision> decision = new CompletableFuture<>();
        AtomicBoolean asked = new AtomicBoolean();
        try (AgentSession session = driver.open(context(Optional.empty(), (request, timeout) -> {
            asked.set(true);
            return decision;
        }), event -> {})) {
            RunningTurn turn = session.start(command("Run the check"));
            AppServerProcess server = supervisor.server();
            server.emit("{\"id\":501,\"method\":\"item/commandExecution/requestApproval\",\"params\":{"
                    + "\"threadId\":\"thread-1\",\"turnId\":\"" + server.activeTurn + "\","
                    + "\"itemId\":\"exec-9\",\"command\":\"echo check\","
                    + "\"availableDecisions\":[\"accept\",\"decline\"],\"reason\":null,\"startedAtMs\":1}}");
            awaitCondition(asked::get);
            // App Server answers its own request, as when the agent that asked is closed.
            server.emit("{\"method\":\"serverRequest/resolved\",\"params\":{\"threadId\":\"thread-1\",\"requestId\":501}}");

            awaitCondition(decision::isCancelled);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void keepsTheTurnRunningWhenAnEventCannotBeRecorded()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> recorded = new CopyOnWriteArrayList<>();
        AtomicBoolean refused = new AtomicBoolean();
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {
            if (event instanceof ProviderAgentEvent.Message && refused.compareAndSet(false, true)) {
                throw new IllegalStateException("Event exceeds the configured size limit");
            }
            recorded.add(event);
        })) {
            RunningTurn turn = session.start(command("complete"));
            supervisor.server().completeTurn();

            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(refused).isTrue();
            assertThat(recorded).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(ProviderAgentEvent.Error.class,
                    error -> assertThat(error.code()).isEqualTo("codex_stream")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent-started", "agent-prompt"})
    void keepsChildRoutingWhenItsDiscoveryCannotBeRecorded(String refusedEvent)
            throws Exception
    {
        AtomicInteger refused = new AtomicInteger();
        try (AgentSession session = driver(supervisor).open(context(Optional.empty(), new AtomicReference<>()), event -> {
            if (event.providerEventId().filter(("child-1:" + refusedEvent)::equals).isPresent()) {
                refused.incrementAndGet();
                throw new IllegalStateException("Event exceeds the configured size limit");
            }
            events.add(event);
        })) {
            RunningTurn turn = session.start(command("delegate"));
            supervisor.server().emitThreadStarted("child-1", "History tests");
            supervisor.server().emitSpawnAgent("child-1", "Trace the request path");
            supervisor.server().emitSubagentActivity("child-1");

            awaitCondition(() -> events.stream().anyMatch(event -> event instanceof ProviderAgentEvent.Message message
                    && message.text().contains("from a child thread")));
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThat(events)
                    .filteredOn(event -> event.source().providerThreadId().filter("child-1"::equals).isPresent())
                    .isNotEmpty()
                    .allSatisfy(event -> {
                        assertThat(event.source().parentProviderThreadId()).contains("thread-1");
                        assertThat(event.source().logicalAgentPath()).contains("History tests");
                    });
            assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(ProviderAgentEvent.Error.class,
                    error -> assertThat(error.code()).isEqualTo("codex_stream")));

            supervisor.server().completeTurn();
            assertThat(turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(refused).hasValue(1);
        }
    }

    @Test
    void addressesAV1SubagentFromAnEarlierTurn()
            throws Exception
    {
        supervisor.persistedChildren.put("thread-1", ImmutableList.of("earlier-child"));
        CodexCliDriver driver = driver(supervisor);
        AgentSessionRef reference;
        try (AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            reference = initial.reference();
        }
        try (AgentSession resumed = driver.open(context(Optional.of(reference), new AtomicReference<>()), events::add)) {
            RunningTurn turn = resumed.start(command("Ask the earlier agent again"));
            AppServerProcess server = supervisor.servers.getLast();
            // The child ran in an earlier process; App Server sends no edge for it before the root addresses it.
            server.emit("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"" + server.activeTurn + "\","
                    + "\"item\":{\"type\":\"collabAgentToolCall\",\"id\":\"send-input\",\"tool\":\"sendInput\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"thread-1\",\"receiverThreadIds\":[\"earlier-child\"],"
                    + "\"agentsStates\":{\"earlier-child\":{\"status\":\"completed\",\"message\":null}},\"prompt\":\"look again\"}}}");
            server.completeTurn();

            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            // It was announced in its own turn, so this one adds no second start.
            assertThat(events).noneMatch(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                    && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED);
        }
    }

    @Test
    void addressesAV2SubagentFromAnEarlierTurnByItsSavedPath()
            throws Exception
    {
        supervisor.reportModelVersion(Optional.of("v2"));
        supervisor.persistedChildren.put("thread-1", ImmutableList.of("earlier-child"));
        supervisor.persistedAgentPaths.put("earlier-child", "/root/explorer");
        CodexCliDriver driver = driver(supervisor);
        AgentSessionRef reference;
        try (AgentSession initial = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            reference = initial.reference();
        }
        try (AgentSession resumed = driver.open(context(Optional.of(reference), new AtomicReference<>()), events::add)) {
            RunningTurn turn = resumed.start(command("Follow up with the explorer"));
            AppServerProcess server = supervisor.servers.getLast();
            server.emit("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"" + server.activeTurn + "\","
                    + "\"item\":{\"type\":\"subAgentActivity\",\"id\":\"follow-up\",\"agentThreadId\":\"earlier-child\","
                    + "\"agentPath\":\"/root/explorer\",\"kind\":\"interacted\"}}}");
            server.completeTurn();

            AgentTurnResult result = turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    void deliversChangedInstructionsOnceToAResumedThread()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        Files.createDirectories(worktree.resolve("provider"));
        Optional<AgentSessionRef> reference = Optional.empty();
        List<JsonNode> turnContexts = new ArrayList<>();
        for (String skill : ImmutableList.of("Answer tersely.", "Answer tersely.", "Cite every file you read.", "Cite every file you read.")) {
            AgentSessionContext context = new AgentSessionContext("task-1", worktree, worktree, worktree.resolve("provider"), AgentPermissionPolicy.AUTO,
                    reference, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                    Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), skill);
            try (AgentSession session = driver.open(context, event -> {})) {
                session.start(command("Continue"));
                turnContexts.add(supervisor.servers.getLast().turnRequests.getFirst().path("additionalContext"));
                reference = Optional.of(session.reference());
            }
        }

        // A new thread starts with its instructions, and an unchanged resume sends nothing more.
        assertThat(turnContexts.get(0).isMissingNode()).isTrue();
        assertThat(turnContexts.get(1).isMissingNode()).isTrue();
        // App Server keeps a thread's first instructions, so a change reaches the model once, with the next turn.
        JsonNode update = turnContexts.get(2).path("bytequay_instructions");
        assertThat(update.path("kind").asText()).isEqualTo("application");
        assertThat(update.path("value").asText()).contains("Cite every file you read.").doesNotContain("Answer tersely.");
        assertThat(turnContexts.get(3).isMissingNode()).isTrue();
    }

    @Test
    void restoresDevelopmentPermissionsForAPreviouslyRestrictedTask()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        AtomicBoolean localReview = new AtomicBoolean();
        var related = new RelatedWorkspaceBridge(arguments -> "linked");
        ReviewBridge privateReview = new ReviewBridge(ImmutableList.of(), "This task is in private local review of commit abc.");
        LocalReviewBridge local = new LocalReviewBridge((commentId, contextOffset) -> "[]", reply -> "{}", resolution -> "{}", LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> localReview.get() ? Optional.of(privateReview) : Optional.empty())
                .localReviews(taskId -> localReview.get() ? Optional.of(local) : Optional.empty())
                .relatedWork(taskId -> localReview.get() ? Optional.empty() : Optional.of(related)).build();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        AgentSessionRef reference;
        try (AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {})) {
            reference = session.reference();
        }
        assertThat(reference.contractVersion()).isEqualTo(3);

        localReview.set(true);
        try (AgentSession session = driver.open(context(Optional.of(reference), new AtomicReference<>()), event -> {})) {
            // The task keeps the reference it saved, so the turn service accepts the resumed session.
            assertThat(session.reference()).isEqualTo(reference);
            AppServerProcess server = supervisor.servers.getLast();
            assertThat(server.threadRequests.getFirst().path("excludeTurns").asBoolean()).isTrue();
            String token = supervisor.environments.getLast().get("BYTEQUAY_TOOL_TOKEN");
            JsonNode listed = broker.handle(token, objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                    """)).body().orElseThrow();
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsAll(ImmutableList.of(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL));
            session.start(command("Explain this comment"));
            // The dispatcher survives a scope change, but only current capabilities can run.
            server.emit("""
                    {"id":301,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                    "callId":"root","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"related_workspace_work","arguments":{}}}}
                    """.formatted(server.activeTurn));
            awaitCondition(() -> server.result(301) != null);
            assertThat(server.result(301).path("success").asBoolean()).isFalse();
            server.emit("""
                    {"id":302,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"%s",
                    "callId":"root","tool":"bytequay_call_tool","namespace":null,"arguments":{"name":"list_local_review_comments","arguments":{}}}}
                    """.formatted(server.activeTurn));
            awaitCondition(() -> server.result(302) != null);
            assertThat(server.result(302).path("success").asBoolean()).isTrue();
        }

        localReview.set(false);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        try (AgentSession session = driver.open(context(Optional.of(reference), approval), event -> {})) {
            assertThat(session.reference()).isEqualTo(reference);
            AppServerProcess server = supervisor.servers.getLast();
            assertThat(server.threadRequests.getFirst().path("developerInstructions").asText())
                    .startsWith(DEVELOPMENT_POLICY).doesNotContain(AgentCommandPolicy.reviewInstructions());
            session.start(command("Push the changes and update the PR description"));
            assertThat(server.turnRequests.getFirst().at("/additionalContext/bytequay_instructions/value").asText())
                    .contains(DEVELOPMENT_POLICY, "replaces earlier ByteQuay private-review restrictions").doesNotContain(privateReview.instruction());
            for (String shellCommand : ImmutableList.of("git push origin HEAD", "gh pr edit 7 --body-file description.md")) {
                approval.set(null);
                ObjectNode params = objectMapper.createObjectNode()
                        .put("threadId", reference.providerValue()).put("turnId", server.activeTurn)
                        .put("itemId", "authorized-publication").put("command", shellCommand).put("startedAtMs", 1786884871461L);
                params.set("availableDecisions", objectMapper.valueToTree(ImmutableList.of("accept", "decline")));
                int requestId = shellCommand.startsWith("git") ? 303 : 304;
                ObjectNode request = objectMapper.createObjectNode().put("id", requestId).put("method", "item/commandExecution/requestApproval");
                request.set("params", params);
                server.emit(request.toString());
                awaitCondition(() -> server.result(requestId) != null);
                assertThat(server.result(requestId).path("decision").asText()).isEqualTo("accept");
                assertThat(approval.get().detail()).contains(shellCommand);
            }
        }
    }

    @Test
    void approvesOnlyTheFixedLearningToolsOnInitialAndResumedSessions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper, "codex-cli 0.156.1", false).withTaskTools();
        List<String> names = ImmutableList.of("repository_context_read", "repository_context_spec_search");
        ReviewBridge review = new ReviewBridge(names.stream()
                .map(name -> new ReviewTool(name, "Fixed task tool", ImmutableMap.of("type", "object"), arguments -> "captured"))
                .collect(ImmutableList.toImmutableList()), "Check the selected specs");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            try (AgentSession session = driver.open(context(reference, new AtomicReference<>()), event -> {})) {
                List<String> arguments = supervisor.serverArgv.getLast();
                assertThat(arguments).filteredOn(argument -> argument.startsWith("mcp_servers.bytequay.tools="))
                        .containsExactly("mcp_servers.bytequay.tools={"
                                + "\"repository_context_read\"={approval_mode=\"approve\"},\"repository_context_spec_search\"={approval_mode=\"approve\"},"
                                + "\"bytequay_list_tools\"={approval_mode=\"approve\"},\"bytequay_get_tool\"={approval_mode=\"approve\"},"
                                + "\"bytequay_call_tool\"={approval_mode=\"approve\"}}");
                assertThat(arguments).contains("mcp_servers.bytequay.default_tools_approval_mode=\"approve\"");
                assertThat(arguments).contains("approval_policy=\"on-request\"");
                String token = supervisor.environments.getLast().get("BYTEQUAY_TOOL_TOKEN");
                JsonNode listed = broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)).body().orElseThrow();
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactlyElementsOf(ImmutableList.<String>builder().addAll(names)
                        .add("bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL).build());
                assertThat(supervisor.servers.getLast().threadRequests.getFirst().path("approvalsReviewer").asText()).isEqualTo("auto_review");
                reference = Optional.of(session.reference());
            }
        }
    }

    private enum TaskTool
    {
        ISSUE_CONTEXT,
        PULL_REQUEST_CI,
        LOCAL_REVIEW,
    }

    /** A task-scoped tool bridge: the broker serving it, the instruction it adds, and the tools it lists. */
    private record BridgedTool(PermissionBroker broker, String instruction, List<String> toolNames) {}

    @ParameterizedTest
    @EnumSource(TaskTool.class)
    void taskToolsUseTheTaskEndpointOnOpenAndResumeWithoutRestrictingChildren(TaskTool tool)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
        supervisor.reportModelVersion(Optional.of("v2"));
        BridgedTool bridged = switch (tool) {
            case ISSUE_CONTEXT -> {
                IssueContextBridge issue = new IssueContextBridge(() -> "Accepted issue context", IssueContextBridge.INSTRUCTION);
                yield new BridgedTool(
                        PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                                .issueContexts(taskId -> Optional.of(issue)).build(),
                        IssueContextBridge.INSTRUCTION,
                        ImmutableList.of(IssueContextBridge.TOOL_NAME));
            }
            case PULL_REQUEST_CI -> {
                PullRequestCiBridge ci = new PullRequestCiBridge(jobId -> "No pull request associated yet", (logId, offset) -> "raw", PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty());
                yield new BridgedTool(
                        PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                                .pullRequestChecks(taskId -> Optional.of(ci)).build(),
                        PullRequestCiBridge.INSTRUCTION,
                        ImmutableList.of(PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME));
            }
            case LOCAL_REVIEW -> {
                LocalReviewBridge local = new LocalReviewBridge((commentId, contextOffset) -> "[]", reply -> "{}", resolution -> "{}", LocalReviewBridge.INSTRUCTION);
                yield new BridgedTool(
                        PermissionBroker.builder(() -> URI.create(MCP_ENDPOINT), objectMapper, (taskId, query) -> "")
                                .localReviews(taskId -> Optional.of(local)).build(),
                        LocalReviewBridge.INSTRUCTION,
                        ImmutableList.of(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL));
            }
        };
        PermissionBroker broker = bridged.broker();
        CodexHome.prepare(codexHome());
        CodexCliDriver driver = new CodexCliDriver("/fake/codex", "0.153.4", objectMapper, supervisor,
                ImmutableMap.of("HOME", worktree.toString()), codexHome(), broker, Duration.ofSeconds(30));
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            AgentSession session = driver.open(context(reference, new AtomicReference<>()), event -> {});
            List<String> arguments = supervisor.serverArgv.getLast();
            assertThat(arguments).containsSequence("--enable", "multi_agent_v2");
            assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.url=\"" + MCP_ENDPOINT + "\"");
            assertThat(supervisor.servers.getLast().threadRequests.getFirst().path("developerInstructions").asText()).contains(bridged.instruction());
            String token = supervisor.environments.stream().filter(environment -> environment.containsKey("BYTEQUAY_TOOL_TOKEN"))
                    .reduce((previous, current) -> current).orElseThrow().get("BYTEQUAY_TOOL_TOKEN");
            assertThat(arguments).noneMatch(argument -> argument.contains(token));
            JsonNode listed = broker.handle(token, objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                    """)).body().orElseThrow();
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactlyElementsOf(ImmutableList.<String>builder().addAll(bridged.toolNames())
                    .add("bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL).build());
            reference = Optional.of(session.reference());
            session.close();
            assertThat(broker.handle(token, objectMapper.readTree("{}")).status()).isEqualTo(401);
        }
    }

    @Test
    void bridgedSessionLaunchesWithTheTaskMcpEndpointAndKeepsTheTokenOffTheCommandLine()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor(objectMapper).withTaskTools();
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
                plain.access(),
                Optional.of(new CodeQueryBridge(query -> "graph:" + query, "use the graph")),
                "Keep explanations concise");

        AgentSession session = driver.open(bridged, event -> {});
        List<String> arguments = supervisor.serverArgv.getFirst();
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.url=\"" + MCP_ENDPOINT + "\"");
        assertThat(arguments).containsSequence("-c", "mcp_servers.bytequay.bearer_token_env_var=\"BYTEQUAY_TOOL_TOKEN\"");
        assertThat(arguments).anySatisfy(argument -> assertThat(argument).startsWith("developer_instructions=")
                .contains("use the graph", "bytequay_list_tools", "bytequay_get_tool"));
        Map<String, String> environment = supervisor.environments.stream()
                .filter(candidate -> candidate.containsKey("BYTEQUAY_TOOL_TOKEN"))
                .findFirst()
                .orElseThrow();
        String token = environment.get("BYTEQUAY_TOOL_TOKEN");
        assertThat(token).isNotBlank();
        assertThat(arguments).noneMatch(argument -> argument.contains(token));
        // The thread leaves the MCP map to the launch flags instead of emptying it.
        assertThat(supervisor.server().threadRequests.getFirst().path("config").has("mcp_servers")).isFalse();
        assertThat(supervisor.server().threadRequests.getFirst().path("developerInstructions").asText())
                .startsWith(DEVELOPMENT_POLICY).contains("native update_plan", "Keep explanations concise", "bytequay_list_tools").endsWith("use the graph");
        // Opening ran the isolation guard against the effective config, which exposes exactly this server.
        session.close();
    }

    @Test
    void transportsLargeReviewReferenceInputOnInitialAndResumedTurns()
            throws Exception
    {
        CodexCliDriver driver = driver(supervisor);
        String instructions = "p".repeat(100_000);
        String reference = "reference-body-marker\n" + "知".repeat(10_000);
        String input = "Review this change.\n<repository-context>\n" + reference + "\n</repository-context>\n";
        input += "x".repeat(256 * 1024 - input.getBytes(StandardCharsets.UTF_8).length);
        assertThat(input.getBytes(StandardCharsets.UTF_8)).hasSize(256 * 1024);
        Optional<AgentSessionRef> sessionReference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            AgentSessionContext plain = context(sessionReference, new AtomicReference<>());
            AgentSessionContext configured = new AgentSessionContext(
                    plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(),
                    plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), plain.access(), plain.codeQueries(), instructions);
            try (AgentSession session = driver.open(configured, event -> {})) {
                RunningTurn turn = session.start(new AgentTurnCommand("context-turn-" + index, input, ImmutableList.of(), Duration.ofMinutes(1)));
                AppServerProcess server = supervisor.servers.getLast();
                JsonNode submitted = server.turnRequests.getFirst().path("input");
                assertThat(submitted).hasSize(1);
                assertThat(submitted.get(0).path("text").asText()).isEqualTo(input);
                assertThat(server.threadRequests.getFirst().path("developerInstructions").asText())
                        .contains(instructions).doesNotContain("reference-body-marker");
                assertThat(supervisor.serverArgv.getLast()).noneMatch(argument -> argument.contains("reference-body-marker"));
                assertThat(server.resumeCount()).isEqualTo(index);
                server.completeTurn();
                assertThat(turn.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                sessionReference = Optional.of(session.reference());
            }
        }
    }

    @Test
    void replacesManagedInstructionsOnResumeWithoutAddingTools()
    {
        CodexCliDriver driver = driver(supervisor);
        Optional<AgentSessionRef> reference = Optional.empty();
        for (String instruction : ImmutableList.of("Keep explanations concise", "Lead with the next action", "")) {
            AgentSessionContext plain = context(reference, new AtomicReference<>());
            AgentSessionContext configured = new AgentSessionContext(
                    plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(),
                    plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), plain.access(), plain.codeQueries(), instruction);
            AgentSession session = driver.open(configured, event -> {});
            AppServerProcess server = supervisor.servers.getLast();
            JsonNode request = server.threadRequests.getFirst();
            assertThat(request.has("developerInstructions")).isTrue();
            assertThat(request.path("developerInstructions").asText())
                    .startsWith(DEVELOPMENT_POLICY).contains("native update_plan", "Do not repeat the checklist");
            if (!instruction.isEmpty()) {
                assertThat(request.path("developerInstructions").asText()).endsWith(instruction);
            }
            for (String previous : ImmutableList.of("Keep explanations concise", "Lead with the next action")) {
                if (!previous.equals(instruction)) {
                    assertThat(request.path("developerInstructions").asText()).doesNotContain(previous);
                }
            }
            assertThat(request.path("config").path("mcp_servers")).isEmpty();
            assertThat(supervisor.serverArgv.getLast()).noneMatch(argument -> argument.startsWith("mcp_servers"));
            assertThat(server.resumeCount()).isEqualTo(reference.isPresent() ? 1 : 0);
            reference = Optional.of(session.reference());
            session.close();
        }
        assertThat(supervisor.environments).noneMatch(environment -> environment.containsKey("BYTEQUAY_TOOL_TOKEN"));
    }

    @Test
    void unbridgedLaunchExposesNoMcpServerAndRejectsAnAmbientOne()
    {
        CodexCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), new AtomicReference<>()), event -> {});
        assertThat(supervisor.serverArgv.getFirst()).noneMatch(argument -> argument.startsWith("mcp_servers") || argument.startsWith("developer_instructions"));
        assertThat(supervisor.environments).noneMatch(candidate -> candidate.containsKey("BYTEQUAY_TOOL_TOKEN"));
        session.close();

        ScriptedProcessSupervisor ambient = new ScriptedProcessSupervisor(objectMapper);
        ambient.configBody = "{\"mcp_servers\":{\"evil\":{\"url\":\"http://127.0.0.1:1/\"}}}";
        // The isolation guard reads the effective config while the session opens.
        assertThatThrownBy(() -> driver(ambient).open(context(Optional.empty(), new AtomicReference<>()), event -> {}))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("MCP servers");
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AtomicReference<ProviderApprovalRequest> approval)
    {
        return context(reference, approval, new AtomicReference<>(ProviderApprovalDecision.allow(Optional.empty())));
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AtomicReference<ProviderApprovalRequest> approval, AtomicReference<ProviderApprovalDecision> decision)
    {
        return context(reference, (request, timeout) -> {
            approval.set(request);
            return CompletableFuture.completedFuture(decision.get());
        });
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AgentApprovalHandler approvals)
    {
        return context("task-1", worktree, reference, approvals);
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort)
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
                SessionAccess.FULL,
                Optional.empty(),
                "");
    }

    private static AgentSessionContext context(String taskId, Path taskWorktree, Optional<AgentSessionRef> reference, AgentApprovalHandler approvals)
    {
        return new AgentSessionContext(
                taskId,
                taskWorktree,
                taskWorktree,
                taskWorktree.resolve("provider"),
                AgentPermissionPolicy.AUTO,
                reference,
                approvals,
                Optional.empty(),
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                "");
    }

    private static AgentTurnCommand command(String message)
    {
        return new AgentTurnCommand("turn-" + message, message, ImmutableList.of(), Duration.ofMinutes(1));
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
        @Override
        public boolean launchedHere(String launchId)
        {
            return false;
        }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String launchId)
        {
            return new OwnedProcessRecovery(
                    launchId,
                    Optional.empty(),
                    Optional.empty(),
                    OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                    "Targeted recovery is not supported by this supervisor");
        }

        private final ObjectMapper objectMapper;
        private final boolean failThreadStart;
        private volatile String versionOutput;
        private boolean loggedIn = true;
        private boolean executableMissing;
        private final AtomicInteger probeCommands = new AtomicInteger();
        private final AtomicInteger catalogLaunches = new AtomicInteger();
        private Runnable beforeCatalog = () -> {};
        // One App Server process per task, so the harness keeps every launch rather than the last.
        private final List<AppServerProcess> servers = new CopyOnWriteArrayList<>();
        private final List<Path> serverCwds = new CopyOnWriteArrayList<>();
        private final List<ProcessOwner> serverOwners = new CopyOnWriteArrayList<>();
        private final List<List<String>> serverArgv = new CopyOnWriteArrayList<>();
        /** The environment of every process launched, App Server and probe alike, in launch order. */
        private final List<Map<String, String>> environments = new CopyOnWriteArrayList<>();
        private volatile String managedRequirements = "{\"requirements\":null,\"supportsIndependentSpeedModes\":true}";
        private volatile String account = "{\"requiresOpenaiAuth\":true,\"account\":{\"type\":\"chatgpt\"}}";
        private volatile String hooks = "[]";
        private volatile String configLayers;
        private volatile String configBody = "{}";
        private volatile String mcpStatuses;
        private volatile String skills = "[]";
        private volatile String backgroundTerminals = "{\"data\":[],\"nextCursor\":null}";
        // Keeps a terminated command listed, as one that survives its stop request would be.
        private volatile boolean terminationIgnored;
        private final ConcurrentMap<String, String> scriptedItems = new ConcurrentHashMap<>();
        private volatile String models = modelCatalog(Optional.of("v1"));
        private volatile ProcessExit.TreeTermination termination = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        private volatile ProcessExit.TreeTermination probeTermination = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        private final List<String> acknowledgements = new CopyOnWriteArrayList<>();
        /** Subagents App Server saved in earlier processes, by parent, which every new process lists again. */
        private final ConcurrentMap<String, List<String>> persistedChildren = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, String> persistedAgentPaths = new ConcurrentHashMap<>();

        /** Makes every App Server exit report a process tree that could not be observed gone. */
        private void leaveProcessTreeUnverifiable()
        {
            termination = ProcessExit.TreeTermination.UNVERIFIABLE;
        }

        private void leaveProbeTreeUnverifiable()
        {
            probeTermination = ProcessExit.TreeTermination.UNVERIFIABLE;
        }

        private void emitScriptedActivity(String threadId)
        {
            try {
                for (JsonNode entry : objectMapper.readTree(scriptedItems.get(threadId)).path("data")) {
                    servers.getLast().emit("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + threadId
                            + "\",\"turnId\":\"" + entry.path("turnId").asText() + "\",\"item\":" + entry.path("item") + "}}");
                }
            }
            catch (IOException e) {
                throw new AssertionError("invalid scripted activity", e);
            }
        }

        /** A child's saved message is available even when its live events were missed. */
        private void reportUnannouncedSubagent()
        {
            scriptedItems.put("child-unannounced", "{\"data\":[{\"turnId\":\"child-turn\",\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"child-message\","
                    + "\"text\":\"recovered child result\",\"phase\":\"final_answer\"}}],"
                    + "\"nextCursor\":null}");
        }

        private static String modelCatalog(Optional<String> multiAgentVersion)
        {
            String version = multiAgentVersion
                    .map(value -> ",\"multiAgentVersion\":\"" + value + "\"")
                    .orElse("");
            return "{\"data\":[{\"id\":\"gpt-5\",\"displayName\":\"GPT-5\","
                    + "\"description\":\"d\",\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"low\"}],"
                    + "\"defaultReasoningEffort\":\"low\",\"isDefault\":true,\"hidden\":false,"
                    + "\"serviceTiers\":[{\"id\":\"priority\",\"name\":\"Fast\",\"description\":\"Faster processing\"}]"
                    + version + "}]}";
        }

        private void reportModelVersion(Optional<String> multiAgentVersion)
        {
            models = modelCatalog(multiAgentVersion);
        }

        private void scriptRecursiveV1Activity()
        {
            scriptedItems.put("thread-1", v1SpawnItems("spawn-child", "thread-1", "child-history", "inspect the child"));
            scriptedItems.put("child-history", v1SpawnItems("spawn-grandchild", "child-history", "grandchild-history", "inspect the grandchild"));
            scriptedItems.put("grandchild-history", "{\"data\":[{\"turnId\":\"grandchild-turn\",\"item\":{"
                    + "\"type\":\"agentMessage\",\"id\":\"grandchild-message\","
                    + "\"text\":\"nested result\",\"phase\":\"final_answer\"}}],\"nextCursor\":null}");
        }

        private void reportDuplicateChildItemIdsAcrossTurns()
        {
            scriptedItems.put("thread-1", v1SpawnItems("spawn-duplicate-child", "thread-1", "duplicate-child", "compare two turns"));
            scriptedItems.put("duplicate-child", "{\"data\":["
                    + "{\"turnId\":\"child-turn-1\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"same-item\",\"text\":\"first child turn\",\"phase\":\"final_answer\"}},"
                    + "{\"turnId\":\"child-turn-2\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"same-item\",\"text\":\"second child turn\",\"phase\":\"final_answer\"}}"
                    + "],\"nextCursor\":null}");
        }

        private void scriptV2Activity()
        {
            scriptedItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-start\",\"agentThreadId\":\"v2-child\","
                    + "\"agentPath\":\"/root/team/reviewer\",\"kind\":\"started\"}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"collabAgentToolCall\","
                    + "\"id\":\"v1-supplement\",\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"thread-1\",\"receiverThreadIds\":[\"v2-child\"],"
                    + "\"agentsStates\":{},\"prompt\":\"supplementary prompt\"}}"
                    + "],\"nextCursor\":null}");
            scriptedItems.put("v2-child", "{\"data\":["
                    + "{\"turnId\":\"child-turn\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-upward\",\"agentThreadId\":\"thread-1\","
                    + "\"agentPath\":\"/root\",\"kind\":\"interacted\"}},"
                    + "{\"turnId\":\"child-turn\",\"item\":{\"type\":\"collabAgentToolCall\","
                    + "\"id\":\"v1-wait\",\"tool\":\"wait\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"v2-child\",\"receiverThreadIds\":[\"thread-1\"],"
                    + "\"agentsStates\":{\"thread-1\":{\"status\":\"completed\",\"message\":null}},"
                    + "\"prompt\":null}}"
                    + "],\"nextCursor\":null}");
        }

        private void scriptNestedV2Activity()
        {
            scriptNestedV2Activity("/root/reviewer/researcher");
        }

        private void scriptNestedV2Activity(String grandchildPath)
        {
            scriptedItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-child-start\","
                    + "\"agentThreadId\":\"v2-child\",\"agentPath\":\"/root/reviewer\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
            scriptedItems.put("v2-child", "{\"data\":[{\"turnId\":\"child-turn\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-grandchild-start\","
                    + "\"agentThreadId\":\"v2-grandchild\","
                    + "\"agentPath\":\"" + grandchildPath + "\",\"kind\":\"started\"}}],"
                    + "\"nextCursor\":null}");
        }

        private void scriptRootV2Activity(String childPath)
        {
            scriptedItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"v2-child-start\","
                    + "\"agentThreadId\":\"v2-child\",\"agentPath\":\"" + childPath + "\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
        }

        private void scriptInvalidV2Activity(String secondThreadId, String secondPath)
        {
            scriptedItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-start\",\"agentThreadId\":\"v2-child\","
                    + "\"agentPath\":\"/root/reviewer\",\"kind\":\"started\"}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"subAgentActivity\","
                    + "\"id\":\"v2-invalid\",\"agentThreadId\":\"" + secondThreadId + "\","
                    + "\"agentPath\":\"" + secondPath + "\",\"kind\":\"interacted\"}}"
                    + "],\"nextCursor\":null}");
        }

        private void reportUnknownV1Supplement()
        {
            scriptedItems.put("thread-1", v1SpawnItems("nonselected-v1", "thread-1", "unknown-v1-child", "must already exist"));
        }

        private void reportUnknownV2Supplement()
        {
            scriptedItems.put("thread-1", "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"subAgentActivity\",\"id\":\"nonselected-v2\","
                    + "\"agentThreadId\":\"unknown-v2-child\",\"agentPath\":\"/root/unknown\","
                    + "\"kind\":\"started\"}}],\"nextCursor\":null}");
        }

        private void reportOnlyPriorRootHistory()
        {
            scriptedItems.put("thread-1", "{\"data\":["
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
            scriptedItems.put("thread-1", "{\"data\":["
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"userMessage\","
                    + "\"id\":\"root-prompt\",\"content\":[{\"type\":\"text\","
                    + "\"text\":\"do not echo this prompt\"}]}},"
                    + "{\"turnId\":\"turn-1\",\"item\":{\"type\":\"agentMessage\","
                    + "\"id\":\"root-answer\",\"text\":\"recovered root answer\","
                    + "\"phase\":\"final_answer\"}}],\"nextCursor\":null}");
        }

        private static String v1SpawnItems(String itemId, String sender, String receiver, String prompt)
        {
            return "{\"data\":[{\"turnId\":\"turn-1\",\"item\":{"
                    + "\"type\":\"collabAgentToolCall\",\"id\":\"" + itemId + "\","
                    + "\"tool\":\"spawnAgent\",\"status\":\"completed\","
                    + "\"senderThreadId\":\"" + sender + "\",\"receiverThreadIds\":[\"" + receiver + "\"],"
                    + "\"agentsStates\":{\"" + receiver + "\":{\"status\":\"completed\",\"message\":null}},"
                    + "\"prompt\":\"" + prompt + "\"}}],\"nextCursor\":null}";
        }
        /** Reports one command still running after the item that started it went terminal. */
        private void reportBackgroundTerminal()
        {
            backgroundTerminals = "{\"data\":[{\"processId\":\"proc-1\",\"itemId\":\"exec-1\"," + "\"command\":\"sleep 600\",\"cwd\":\"/worktree\"}],\"nextCursor\":null}";
        }

        private void reportNoBackgroundTerminals()
        {
            backgroundTerminals = "{\"data\":[],\"nextCursor\":null}";
        }

        private void terminateBackgroundTerminals()
        {
            if (!terminationIgnored) {
                reportNoBackgroundTerminals();
            }
        }

        private ScriptedProcessSupervisor(ObjectMapper objectMapper)
        {
            this(objectMapper, "codex-cli 0.153.4", false);
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

        private ScriptedProcessSupervisor withTaskTools()
        {
            configBody = "{\"mcp_servers\":{\"bytequay\":{\"url\":\"" + MCP_ENDPOINT + "\",\"default_tools_approval_mode\":\"approve\"}}}";
            mcpStatuses = "{\"data\":[{\"name\":\"bytequay\",\"pluginId\":null}],\"nextCursor\":null}";
            return this;
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
                probeCommands.incrementAndGet();
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success(probeTermination);
            }
            if (spec.argv().contains("login")) {
                probeCommands.incrementAndGet();
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(loggedIn ? "Logged in using ChatGPT\n" : "Not logged in\n"));
                return loggedIn ? ImmediateProcess.success(probeTermination) : ImmediateProcess.failure(probeTermination);
            }
            boolean catalog = spec.owner().id().equals("codex-model-catalog");
            if (catalog) {
                catalogLaunches.incrementAndGet();
                beforeCatalog.run();
            }
            AppServerProcess server = new AppServerProcess(
                    objectMapper,
                    output,
                    failThreadStart,
                    Path.of(spec.environment().getOrDefault("CODEX_HOME", spec.workingDirectory().toString())),
                    spec.argv(),
                    () -> managedRequirements,
                    () -> account,
                    () -> hooks,
                    () -> configLayers,
                    () -> configBody,
                    () -> mcpStatuses,
                    () -> skills,
                    () -> backgroundTerminals,
                    this::terminateBackgroundTerminals,
                    thread -> scriptedItems.getOrDefault(thread, "{\"data\":[],\"nextCursor\":null}"),
                    () -> models,
                    () -> catalog ? ProcessExit.TreeTermination.VERIFIED_STOPPED : termination);
            server.inventoryChildren.putAll(persistedChildren);
            server.agentPaths.putAll(persistedAgentPaths);
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
    @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            stopRequests.incrementAndGet();
            cancel();
            return stopVerificationGate.thenCompose(ignored -> exit().thenApply(ProcessExit::treeTermination));
        }

        @Override
        public Optional<List<ProcessIdentity>> observedDescendants()
        {
            return Optional.empty();
        }

        /** What thread-1 counted in the turns before a session resumes it. */
        private static final String EARLIER_USAGE = tokens(1000, 800, 300, 200);

        private final ObjectMapper objectMapper;
        private final ProcessOutputSink output;
        private final boolean failThreadStart;
        private final Path codexHome;
        private final List<String> argv;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final ExecutorService reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("scripted-app-server-reader").factory());
        private final AtomicInteger turns = new AtomicInteger();
        private final AtomicInteger threads = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final AtomicInteger accountReads = new AtomicInteger();
        private final List<String> approvalPolicies = new CopyOnWriteArrayList<>();
        private final Supplier<String> managedRequirements;
        private final Supplier<String> account;
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

        private final List<Boolean> experimentalApi = new CopyOnWriteArrayList<>();
        private final List<String> terminated = new CopyOnWriteArrayList<>();
        private final List<String> interruptedThreads = new CopyOnWriteArrayList<>();
        private final List<String> backgroundTerminalThreads = new CopyOnWriteArrayList<>();
        private final List<JsonNode> historyRequests = new CopyOnWriteArrayList<>();
        private final AtomicInteger turnLists = new AtomicInteger();
        private final ConcurrentMap<String, String> threadStatuses = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, String> turnStatuses = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, String> historyOverrides = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, List<String>> inventoryChildren = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, String> agentPaths = new ConcurrentHashMap<>();
        private volatile int inventoryPageSize = Integer.MAX_VALUE;
        private final AtomicInteger finishedWorkStops = new AtomicInteger();
        private final AtomicInteger stopRequests = new AtomicInteger();
        private volatile CompletableFuture<Void> stopVerificationGate = CompletableFuture.completedFuture(null);

        private final Runnable clearBackgroundTerminals;
        private final Function<String, String> scriptedItems;
        private final Supplier<String> models;
        private final Supplier<ProcessExit.TreeTermination> termination;

        private final ConcurrentMap<Long, JsonNode> results = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, JsonNode> errors = new ConcurrentHashMap<>();
        private volatile CompletableFuture<Void> interruptCompletionGate = CompletableFuture.completedFuture(null);
        private volatile String activeTurn;
        private volatile String activeThread;

        private AppServerProcess(
                ObjectMapper objectMapper,
                ProcessOutputSink output,
                boolean failThreadStart,
                Path codexHome,
                List<String> argv,
                Supplier<String> managedRequirements,
                Supplier<String> account,
                Supplier<String> hooks,
                Supplier<String> configLayers,
                Supplier<String> configBody,
                Supplier<String> mcpStatuses,
                Supplier<String> skills,
                Supplier<String> backgroundTerminals,
                Runnable clearBackgroundTerminals,
                Function<String, String> scriptedItems,
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
            this.account = account;
            this.hooks = hooks;
            this.configLayers = configLayers;
            this.configBody = configBody;
            this.mcpStatuses = mcpStatuses;
            this.skills = skills;
            this.backgroundTerminals = backgroundTerminals;
            this.clearBackgroundTerminals = clearBackgroundTerminals;
            this.scriptedItems = scriptedItems;
            this.models = models;
        }

        /** Real App Server hands out a distinct thread per start; the fixture must too. */
        private String nextThreadId()
        {
            int index = threads.incrementAndGet();
            return index == 1 ? "thread-1" : "thread-" + index;
        }

        private JsonNode configReadResponse()
        {
            try {
                ObjectNode config = launchConfig();
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
                    sessionLayer.set("config", launchConfig());
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

        private ObjectNode launchConfig()
                throws IOException
        {
            boolean review = hasArguments("--enable", "hooks")
                    || (!textOnlyProfile() && hasArguments("--disable", "guardian_approval"));
            ObjectNode config = objectMapper.valueToTree(
                    CodexLaunchProfile.threadConfig(
                            new CodexLaunchProfile.Options(childProfile(), textOnlyProfile(), ciWritableRoots(), allowsGitHubCommands() && !review, review,
                                    hasArguments("--enable", CodexLaunchProfile.ASK_USER_FEATURE), deepSeekProfile()),
                            false));
            for (String argument : argv) {
                for (String key : ImmutableList.of("approval_policy", "approvals_reviewer", "cli_auth_credentials_store")) {
                    String prefix = key + "=";
                    if (argument.startsWith(prefix)) {
                        config.set(key, objectMapper.readTree(argument.substring(prefix.length())));
                    }
                }
            }
            return config;
        }

        private Optional<CodexLaunchProfile.DeepSeekProfile> deepSeekProfile()
                throws IOException
        {
            Optional<String> catalog = configArgument("model_catalog_json=");
            Optional<String> baseUrl = configArgument("model_providers.deepseek.base_url=");
            if (catalog.isEmpty() || baseUrl.isEmpty()) {
                return Optional.empty();
            }
            Path catalogPath = Path.of(objectMapper.readTree(catalog.get()).asText());
            return Optional.of(new CodexLaunchProfile.DeepSeekProfile(catalogPath, URI.create(objectMapper.readTree(baseUrl.get()).asText())));
        }

        private Optional<String> configArgument(String prefix)
        {
            return argv.stream().filter(argument -> argument.startsWith(prefix)).map(argument -> argument.substring(prefix.length())).findFirst();
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

        private boolean textOnlyProfile()
        {
            return hasArguments("--disable", "shell_tool");
        }

        private boolean allowsGitHubCommands()
        {
            return argv.contains("sandbox_workspace_write.network_access=true")
                    && argv.stream().noneMatch(argument -> argument.startsWith("sandbox_workspace_write.writable_roots="));
        }

        private List<String> ciWritableRoots()
                throws IOException
        {
            String prefix = "sandbox_workspace_write.writable_roots=";
            for (String argument : argv) {
                if (argument.startsWith(prefix)) {
                    ImmutableList.Builder<String> roots = ImmutableList.builder();
                    for (JsonNode root : objectMapper.readTree(argument.substring(prefix.length()))) {
                        roots.add(root.asText());
                    }
                    return roots.build();
                }
            }
            return ImmutableList.of();
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

        private String hookInventory()
        {
            if (!hooks.get().equals("[]") || !hasArguments("--enable", "hooks")) {
                return hooks.get();
            }
            ArrayNode inventory = objectMapper.createArrayNode();
            inventory.addObject()
                    .put("eventName", "preToolUse")
                    .put("handlerType", "command")
                    .put("command", CodexReviewGate.command(CodexReviewGate.path(codexHome)))
                    .put("matcher", ".*")
                    .put("source", "sessionFlags")
                    .put("sourcePath", "/<session-flags>/config.toml")
                    .put("enabled", true)
                    .put("async", false)
                    .put("timeoutSec", 5);
            return inventory.toString();
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
                throws IOException
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
                case "configRequirements/read" -> {
                    String requirements = managedRequirements.get();
                    // Native Codex reports the forced login setting as a requirement even without host policy.
                    if (argv.contains("forced_login_method=\"chatgpt\"") && requirements.startsWith("{\"requirements\":null")) {
                        requirements = "{\"requirements\":{\"allowedLoginMethods\":[\"chatgpt\"]}}";
                    }
                    respond(id, requirements);
                }
                case "account/read" -> {
                    accountReads.incrementAndGet();
                    // What Codex 0.164.0 reports for a provider that does not use OpenAI auth with ephemeral credentials.
                    respond(id, argv.contains("model_provider=\"deepseek\"") ? "{\"account\":null,\"requiresOpenaiAuth\":false}" : account.get());
                }
                case "config/read" -> respond(id, configReadResponse().toString());
                case "skills/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"skills\":" + skills.get() + ",\"errors\":[]}]}");
                case "hooks/list" -> respond(id, "{\"data\":[{\"cwd\":\""
                        + message.path("params").path("cwds").get(0).asText()
                        + "\",\"hooks\":" + hookInventory() + ",\"errors\":[],\"warnings\":[]}]}");
                case "thread/list" -> {
                    String parent = message.path("params").path("parentThreadId").asText();
                    ObjectNode response = objectMapper.createObjectNode();
                    ArrayNode children = response.putArray("data");
                    List<String> childIds = inventoryChildren.getOrDefault(parent, ImmutableList.of());
                    int offset = message.path("params").path("cursor").asInt(0);
                    int end = offset + Math.min(inventoryPageSize, childIds.size() - offset);
                    for (String child : childIds.subList(offset, end)) {
                        ObjectNode thread = children.addObject();
                        thread.put("id", child);
                        thread.put("sessionId", "session-1");
                        thread.put("parentThreadId", parent);
                        thread.putObject("status").put("type", "idle");
                        thread.putArray("turns");
                        if (agentPaths.containsKey(child)) {
                            thread.putObject("source").putObject("subAgent").putObject("thread_spawn").put("agent_path", agentPaths.get(child));
                        }
                    }
                    if (end < childIds.size()) {
                        response.put("nextCursor", Integer.toString(end));
                    }
                    else {
                        response.putNull("nextCursor");
                    }
                    respond(id, response.toString());
                }
                case "thread/read" -> {
                    assertThat(message.path("params").path("includeTurns").asBoolean()).isFalse();
                    historyRequests.add(message.path("params").deepCopy());
                    ObjectNode response = (ObjectNode) objectMapper.readTree(historyResponse(message.path("params").path("threadId").asText()));
                    ((ObjectNode) response.path("thread")).putArray("turns");
                    respond(id, response.toString());
                }
                case "thread/turns/list" -> {
                    turnLists.incrementAndGet();
                    if (threadRequests.stream().anyMatch(request -> request.path("ephemeral").asBoolean())) {
                        emit("{\"id\":" + id + ",\"error\":{\"code\":-32600,\"message\":\"ephemeral threads do not support thread/turns/list\"}}");
                        return;
                    }
                    JsonNode params = message.path("params");
                    assertThat(params.path("limit").asInt()).isEqualTo(1);
                    assertThat(params.path("sortDirection").asText()).isEqualTo("desc");
                    assertThat(params.path("itemsView").asText()).isEqualTo("notLoaded");
                    JsonNode turns = objectMapper.readTree(historyResponse(params.path("threadId").asText())).path("thread").path("turns");
                    ObjectNode response = objectMapper.createObjectNode();
                    ArrayNode data = response.putArray("data");
                    if (!turns.isEmpty()) {
                        ObjectNode latest = turns.get(turns.size() - 1).deepCopy();
                        latest.putArray("items");
                        data.add(latest);
                    }
                    respond(id, response.toString());
                }
                case "thread/items/list" -> throw new AssertionError("legacy App Server threads do not support thread/items/list");
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
                case "mcpServerStatus/list" -> respond(id, mcpStatuses.get() != null ? mcpStatuses.get() : "{\"data\":[],\"nextCursor\":null}");
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
                    // As App Server does for the client that resumes a thread: its token counter, under the turn that last moved it.
                    emitUsage("thread-1", "earlier-turn", EARLIER_USAGE, EARLIER_USAGE);
                }
                case "thread/unsubscribe" -> respond(id, "{\"status\":\"unsubscribed\"}");
                case "turn/start" -> {
                    turnRequests.add(message.path("params").deepCopy());
                    turnSettings.add(message.path("params").path("model").asText("none") + "/" + message.path("params").path("effort").asText("none"));
                    turnInputs.add(message.path("params").path("input").toString());
                    activeThread = message.path("params").path("threadId").asText();
                    activeTurn = "turn-" + turns.incrementAndGet();
                    respond(id, "{\"turn\":{\"id\":\"" + activeTurn + "\",\"status\":\"inProgress\",\"error\":null,\"durationMs\":null,\"items\":[]}}");
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
                    String interruptedThread = message.path("params").path("threadId").asText();
                    interruptedThreads.add(interruptedThread);
                    if (!interruptedThread.equals("thread-1")) {
                        // A subagent stops its own turn; the root's is untouched.
                        threadStatuses.put(interruptedThread, "idle");
                        respond(id, "{}");
                        return;
                    }
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

        private String historyResponse(String threadId)
        {
            if (historyOverrides.containsKey(threadId)) {
                return historyOverrides.get(threadId);
            }
            try {
                Map<String, ArrayNode> itemsByTurn = new LinkedHashMap<>();
                // Legacy thread/read omits tools and generates message IDs unrelated to live events.
                for (JsonNode entry : objectMapper.readTree(scriptedItems.apply(threadId)).path("data")) {
                    ArrayNode items = itemsByTurn.computeIfAbsent(entry.path("turnId").asText(), ignored -> objectMapper.createArrayNode());
                    JsonNode item = entry.path("item");
                    if (item.path("type").asText().equals("userMessage") || item.path("type").asText().equals("agentMessage")) {
                        ObjectNode historical = item.deepCopy();
                        historical.put("id", "item-" + (items.size() + 1));
                        items.add(historical);
                    }
                }
                // An empty current turn is still present even when fixtures also contain old history.
                if (threadId.equals(activeThread)) {
                    itemsByTurn.computeIfAbsent(activeTurn, ignored -> objectMapper.createArrayNode());
                }
                else if (itemsByTurn.isEmpty()) {
                    itemsByTurn.put("child-turn", objectMapper.createArrayNode());
                }
                ObjectNode response = objectMapper.createObjectNode();
                ObjectNode thread = response.putObject("thread");
                thread.put("id", threadId);
                thread.put("sessionId", "session-1");
                String status = threadStatuses.getOrDefault(threadId, "idle");
                if (!status.isEmpty()) {
                    thread.putObject("status").put("type", status);
                }
                ArrayNode history = thread.putArray("turns");
                itemsByTurn.forEach((turnId, items) -> {
                    ObjectNode turn = history.addObject();
                    turn.put("id", turnId);
                    turn.put("status", turnStatuses.getOrDefault(threadId, "completed"));
                    turn.putNull("error");
                    turn.putNull("durationMs");
                    turn.set("items", items);
                });
                return response.toString();
            }
            catch (IOException e) {
                throw new AssertionError("invalid scripted thread history", e);
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
            emitUsage(threadId, "subagent-turn", tokens(1200, 900, 0, 300), tokens(1200, 900, 0, 300));
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
                    + "\"parentThreadId\":\"" + parentThreadId + "\",\"turns\":[],\"status\":{\"type\":\"idle\"},"
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

        private void emitUsage(String threadId, String turnId, String total, String last)
        {
            emit("{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId + "\","
                    + "\"tokenUsage\":{\"total\":" + total + ",\"last\":" + last + "}}}");
        }

        private static String tokens(long total, long input, long cachedInput, long output)
        {
            return "{\"totalTokens\":%d,\"inputTokens\":%d,\"cachedInputTokens\":%d,\"outputTokens\":%d,\"reasoningOutputTokens\":0}".formatted(total, input, cachedInput, output);
        }

        private void completeTurnWithoutOutput()
        {
            completeTurnWithSummary("[]");
        }

        private void completeTurnWithSummary(String items)
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn + "\",\"status\":\"completed\","
                    + "\"error\":null,\"durationMs\":1,\"items\":" + items + "}}}");
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
            // One response: the thread's running total, which earlier turns moved too, and what that response used.
            emitUsage("thread-1", activeTurn, tokens(1100, 880, 330, 220), tokens(100, 80, 30, 20));
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"completed\",\"error\":null,\"durationMs\":2,\"items\":[]}}}");
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

        private void failTurn()
        {
            failTurn("provider failed");
        }

        private void failTurn(String message)
        {
            emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\","
                    + "\"turn\":{\"id\":\"" + activeTurn
                    + "\",\"status\":\"failed\",\"error\":" + objectMapper.createObjectNode().put("message", message) + ",\"durationMs\":2,\"items\":[]}}}");
        }

        private void disconnect()
        {
            exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        }

        private int resumeCount()
        {
            return resumes.get();
        }

        private String approvalDecision()
        {
            return result(91).path("decision").asText();
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
        public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
        {
            finishedWorkStops.incrementAndGet();
            return stopAndVerify();
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
        @Override
        public Optional<List<ProcessIdentity>> observedDescendants()
        {
            return Optional.empty();
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancel();
            return exit().thenApply(ProcessExit::treeTermination);
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
        {
            return stopAndVerify();
        }

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
            return new ImmediateProcess(CompletableFuture.completedFuture(new ProcessExit(exitCode, ProcessExit.Reason.EXITED, termination, Duration.ZERO, false, false)));
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
