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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.mcp.McpHttpResponse;
import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.cli.process.LaunchIntentRecorder;
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
import com.bytequay.agent.cli.process.impl.DefaultProcessSupervisor;
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
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeCliDriver
{
    private static final String DEVELOPMENT_POLICY = AgentCommandPolicy.instructions(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false));
    private static final String DEVELOPMENT_SANDBOX = PermissionBroker.sandboxInstruction(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false));
    private static final String TASK_CONTEXT = "This task continues the existing pull request owner/repo#7.";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void readOnlyDiscussionsReceiveTheirContextToolWithoutWritePermissions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), AgentPermissionPolicy.AUTO);
        AgentSessionContext discussion = new AgentSessionContext(plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(),
                plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY,
                Optional.empty(), TASK_CONTEXT, Optional.of(new DiscussionContextBridge(query -> "saved context")));
        try (AgentSession session = driver.open(discussion, event -> {})) {
            RunningTurn turn = session.start(command("explain this"));
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            ProcessSpec spec = supervisor.turns().getLast().spec();
            // The setup's first half names the instructions the turn was launched with, all but the task's own context, which a
            // read-only session starts with.
            String instructions = spec.argv().get(spec.argv().indexOf("--append-system-prompt") + 1);
            assertThat(instructions).startsWith(TASK_CONTEXT + "\n\n");
            String own = Hashing.sha256().hashString(instructions.substring(TASK_CONTEXT.length() + 2), StandardCharsets.UTF_8).toString();
            assertThat(turn.setup().orElseThrow()).matches("[0-9a-f]{8}-[0-9a-f]{8}").startsWith(own.substring(0, 8) + "-");
            assertThat(spec.argv()).containsSequence("--tools", "Bash,Glob,Grep,Read");
            assertThat(spec.argv()).anyMatch(argument -> argument.contains("mcp__bytequay__read_discussion_context"));
            assertThat(spec.argv()).anyMatch(argument -> argument.contains(DiscussionContextBridge.INSTRUCTION));
            assertThat(spec.argv()).noneMatch(argument -> argument.contains("Edit") || argument.contains("Write"));
        }
        driver.close();
    }

    @Test
    void independentReviewReceivesOnlyItsReadContextTool()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), AgentPermissionPolicy.AUTO);
        AgentSessionContext discussion = new AgentSessionContext(plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(),
                plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY,
                Optional.empty(), "Read the independent assignment.", Optional.empty(),
                ImmutableList.of(new ReviewTool("review_get_context", "Read captured context", ImmutableMap.of("type", "object"), query -> "saved context", true)));
        try (AgentSession session = driver.open(discussion, event -> {})) {
            assertThat(await(session.start(command("explain this"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            ProcessSpec spec = supervisor.turns().getLast().spec();
            assertThat(spec.argv()).containsSequence("--tools", "Bash,Glob,Grep,Read");
            assertThat(spec.argv()).anyMatch(argument -> argument.contains("mcp__bytequay__review_get_context"));
            assertThat(spec.argv()).anyMatch(argument -> argument.contains("Read the independent assignment."));
            assertThat(spec.argv()).noneMatch(argument -> argument.contains("Edit") || argument.contains("Write"));
        }
        driver.close();
    }

    @Test
    void readOnlySessionInAScratchFolderLaunchesAsControlWork()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), AgentPermissionPolicy.AUTO);
        AgentSessionContext scratch = new AgentSessionContext(plain.taskId(), Files.createDirectories(runtimeDirectory().resolve("scratch-worktree")),
                plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(),
                plain.effort(), SessionAccess.READ_ONLY, Optional.empty(), "");
        try (AgentSession session = driver.open(scratch, event -> {})) {
            assertThat(await(session.start(command("plan the day"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            // It reaches no task worktree, so startup recovery keeps a leftover process as evidence instead of blocking every task.
            assertThat(supervisor.turns().getLast().spec().owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
        }
        driver.close();
    }

    @Test
    void artifactWriterReadsItsCheckoutAsControlWorkAndAloneMaySave()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), AgentPermissionPolicy.AUTO);
        Path checkout = Files.createDirectories(runtimeDirectory().resolveSibling("artifacts").resolve("ws1"));
        AgentSessionContext writer = new AgentSessionContext(plain.taskId(), checkout, plain.runtimeDirectory(), plain.providerDirectory(),
                plain.permissionPolicy(), plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), SessionAccess.READ_ONLY,
                Optional.empty(), "Write the page.", Optional.empty(),
                ImmutableList.of(new ReviewTool("artifact_save", "Save the page", ImmutableMap.of("type", "object"), page -> "Saved", false)));
        try (AgentSession session = driver.open(writer, event -> {})) {
            assertThat(await(session.start(command("teach me delete files"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            ProcessSpec spec = supervisor.turns().getLast().spec();
            // The checkout outlives each run so a later ask can resume, but no task owns it: a leftover process blocks no task.
            assertThat(spec.owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
            assertThat(spec.argv()).containsSequence("--tools", "Bash,Glob,Grep,Read");
            assertThat(spec.argv()).anyMatch(argument -> argument.contains("mcp__bytequay__artifact_save"));
            assertThat(spec.argv()).noneMatch(argument -> argument.contains("Edit") || argument.contains("Write,"));
        }
        driver.close();
    }

    @Test
    void toolsOnlySessionResumesWithOnlyItsByteQuayToolsAndNoChildren()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty(), AgentPermissionPolicy.AUTO);
        Path day = Files.createDirectories(runtimeDirectory().resolveSibling("assistant").resolve("2026-10-17"));
        List<ReviewTool> tools = ImmutableList.of(
                new ReviewTool("assistant_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "{}", true),
                new ReviewTool("assistant_act", "Act on the user's words", ImmutableMap.of("type", "object"), query -> "{}", false),
                new ReviewTool("assistant_show", "Show the user a list", ImmutableMap.of("type", "object"), query -> "{}", false));
        AgentSessionRef reference;
        try (AgentSession session = driver.open(toolsOnly(plain, day, Optional.empty(), tools), event -> {})) {
            assertThat(await(session.start(command("what is waiting on me?"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            ProcessSpec spec = supervisor.turns().getLast().spec();
            assertThat(spec.owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
            assertThat(spec.workingDirectory().toRealPath()).isEqualTo(day.toRealPath());
            assertThat(spec.argv()).contains("--tools=", "--disable-slash-commands").doesNotContain("--no-session-persistence");
            assertThat(spec.argv()).containsSequence("--disallowedTools", "Agent,Task,Skill,Workflow,SendMessage");
            assertThat(spec.argv().get(spec.argv().indexOf("--allowedTools") + 1).split(","))
                    .containsExactly("mcp__bytequay__assistant_context", "mcp__bytequay__assistant_act", "mcp__bytequay__assistant_show");
            assertThat(spec.argv()).noneMatch(argument -> argument.contains("bytequay_list_tools"));
            assertThat(spec.argv()).containsSequence("--session-id", session.reference().providerValue());
            JsonNode settings = objectMapper.readTree(Files.readString(runtimeDirectory().resolve("provider/scratch/sandbox-settings.json")));
            assertThat(settings.at("/permissions/deny").toString()).contains("\"Agent\"", "\"Task\"", "\"Skill\"", "\"Workflow\"", "\"SendMessage\"", "\"WebFetch\"");
            assertThat(settings.at("/sandbox/filesystem/denyWrite").toString()).contains(day.toRealPath().toString());
            assertThat(settings.has("hooks")).isFalse();
            reference = session.reference();
        }
        try (AgentSession session = driver.open(toolsOnly(plain, day, Optional.of(reference), tools), event -> {})) {
            assertThat(await(session.start(command("start it"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(supervisor.turns().getLast().spec().argv()).containsSequence("--resume", reference.providerValue());
        }
        // Claude finds the conversation by its folder, so only a stable assistant folder outside the run's scratch may hold it.
        assertThatThrownBy(() -> toolsOnly(plain, Files.createDirectories(runtimeDirectory().resolve("assistant/2026-10-17")), Optional.empty(), tools))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tools-only worktree is not an assistant folder");
        driver.close();
    }

    private static AgentSessionContext toolsOnly(AgentSessionContext plain, Path folder, Optional<AgentSessionRef> reference, List<ReviewTool> tools)
    {
        return new AgentSessionContext(plain.taskId(), folder, plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(), reference,
                plain.approvals(), plain.model(), plain.effort(), SessionAccess.TOOLS_ONLY, Optional.empty(), "", Optional.empty(), tools);
    }

    @Test
    void terminalClaudeReviewResolvesARealSupervisorObservationGapAndResumes()
            throws Exception
    {
        Files.writeString(worktree.resolve("defaults.json"), "{\"allow\":[\"" + "policy ".repeat(12 * 1024) + "\"],\"hard_deny\":[]}");
        Path executable = Files.writeString(worktree.resolve("claude-fixture"), """
                #!/bin/bash
                case "$1" in
                  --version) printf '2.1.263 (Claude Code)\\n'; exit 0 ;;
                  --help)
                    printf '%s\\n' '--output-format --include-partial-messages --include-hook-events --forward-subagent-text'
                    printf '%s\\n' '--permission-mode (choices: "auto" "manual") --mcp-config --settings --setting-sources --strict-mcp-config'
                    printf '%s\\n' '--add-dir --append-system-prompt --tools --no-session-persistence --disable-slash-commands --model --effort'
                    printf '%s\\n' '--allowedTools --permission-prompt-tool --print --verbose --resume --session-id'
                    exit 0 ;;
                  auth) printf '{"loggedIn":true}\\n'; exit 0 ;;
                  auto-mode) cat "$(dirname "$0")/defaults.json"; exit 0 ;;
                esac
                while [ "$#" -gt 0 ]; do
                  case "$1" in --session-id|--resume) session_id="$2"; shift ;; esac
                  shift
                done
                IFS= read -r prompt
                printf '{"type":"system","subtype":"init","session_id":"%s","uuid":"init"}\\n' "$session_id"
                if [ "$prompt" = "failed-real-turn" ]; then
                  printf '{"type":"result","is_error":true,"duration_ms":1,"duration_api_ms":0,"num_turns":1,"session_id":"%s",' "$session_id"
                  printf '"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},'
                  printf '"subtype":"error_during_execution","errors":["Session limit reached"],"uuid":"result"}\\n'
                  exit 1
                fi
                printf '{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash",'
                printf '"name":"Bash","input":{"command":"true"}}]},"session_id":"%s","uuid":"bash-start"}\\n' "$session_id"
                printf '{"type":"tool_progress","tool_use_id":"bash","tool_name":"Bash","elapsed_time_seconds":0.1,'
                printf '"session_id":"%s","uuid":"bash-progress"}\\n' "$session_id"
                printf '{"type":"tool_progress","tool_use_id":"bash-heartbeat-0","tool_name":"Bash","heartbeat":true,"elapsed_time_seconds":30,'
                printf '"session_id":"%s","uuid":"bash-heartbeat"}\\n' "$session_id"
                /usr/bin/true
                printf '{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"bash","content":"done"}]},'
                printf '"session_id":"%s","uuid":"bash-end"}\\n' "$session_id"
                printf '{"type":"tool_progress","tool_use_id":"bash-heartbeat-1","tool_name":"Bash","heartbeat":true,"elapsed_time_seconds":60,'
                printf '"session_id":"%s","uuid":"bash-late-heartbeat"}\\n' "$session_id"
                printf '{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"mcp",'
                printf '"name":"mcp__bytequay__review_get_context","input":{}}]},"session_id":"%s","uuid":"mcp-start"}\\n' "$session_id"
                printf '{"type":"tool_progress","tool_use_id":"mcp","tool_name":"mcp__bytequay__review_get_context","elapsed_time_seconds":0.1,'
                printf '"session_id":"%s","uuid":"mcp-progress"}\\n' "$session_id"
                printf '{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"mcp","content":"review context"}]},'
                printf '"session_id":"%s","uuid":"mcp-end"}\\n' "$session_id"
                printf '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Review complete"}]},'
                printf '"session_id":"%s","uuid":"answer"}\\n' "$session_id"
                printf '{"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"session_id":"%s",' "$session_id"
                printf '"usage":{"input_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1},'
                printf '"subtype":"success","result":"Review complete","uuid":"result"}\\n'
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        Path launches = worktree.resolve("launches");
        Map<String, Boolean> committed = new ConcurrentHashMap<>();
        LaunchIntentRecorder recorder = new LaunchIntentRecorder()
        {
            @Override
            public void prepared(String launchId, ProcessOwner owner, String command, String argvDigest)
            {
                committed.put(launchId, false);
            }

            @Override
            public void attempting(String launchId)
            {
                committed.put(launchId, true);
            }

            @Override
            public Map<String, Boolean> committedLaunches()
            {
                return ImmutableMap.copyOf(committed);
            }

            @Override
            public void forget(String launchId)
            {
                committed.remove(launchId);
            }
        };
        DefaultProcessSupervisor realSupervisor = new DefaultProcessSupervisor(launches, recorder);
        List<ManagedProcess> turnProcesses = new CopyOnWriteArrayList<>();
        List<ProcessSpec> turnSpecs = new CopyOnWriteArrayList<>();
        Map<String, JsonNode> acknowledgedReceipts = new ConcurrentHashMap<>();
        ProcessSupervisor supervisor = new ProcessSupervisor()
        {
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

            @Override
            public PreparedProcess prepare(ProcessSpec spec)
            {
                PreparedProcess launch = realSupervisor.prepare(spec);
                if (spec.owner().kind() != ProcessOwner.Kind.CLAUDE_TURN) {
                    return launch;
                }
                return new PreparedProcess()
                {
                    @Override
                    public String launchId()
                    {
                        return launch.launchId();
                    }

                    @Override
                    public ManagedProcess start(ProcessOutputSink output)
                    {
                        ManagedProcess process = launch.start(output);
                        turnProcesses.add(process);
                        turnSpecs.add(spec);
                        try {
                            // Inject the observed identity-read race, keeping the driver, receipt, and all cleanup operations real.
                            Method observation = process.getClass().getDeclaredMethod("markDescendantTrackingIncomplete", String.class);
                            observation.setAccessible(true);
                            observation.invoke(process, "Fixture descendant exited before its start token could be read");
                            assertThat(objectMapper.readTree(launches.resolve(process.launchId() + ".json").toFile())
                                    .path("descendantTrackingIncomplete").asBoolean()).isTrue();
                            return process;
                        }
                        catch (ReflectiveOperationException | IOException failure) {
                            process.cancel();
                            throw new AssertionError(failure);
                        }
                    }
                };
            }

            @Override
            public List<OwnedProcessRecovery> recoverOwnedProcesses()
            {
                return realSupervisor.recoverOwnedProcesses();
            }

            @Override
            public void acknowledge(String launchId)
            {
                try {
                    acknowledgedReceipts.put(launchId, objectMapper.readTree(launches.resolve(launchId + ".json").toFile()));
                }
                catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                realSupervisor.acknowledge(launchId);
            }
        };
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_get_context", "Read local review context", ImmutableMap.of("type", "object"), input -> "review context")), "Review the pull request");
        PermissionBroker permissions = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> Optional.of(review)).build();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeCliDriver driver = new ClaudeCliDriver(
                executable.toString(), objectMapper, permissions, supervisor, claudeHome(),
                ImmutableMap.of("HOME", worktree.toString(), "PATH", "/usr/bin:/bin"))) {
            AgentProviderStatus status = driver.probe();
            assertThat(status.readiness()).as(status.diagnostic()).isEqualTo(AgentProviderStatus.Readiness.READY);
            AgentSession session = driver.open(context(Optional.empty()), events::add);
            for (String prompt : ImmutableList.of("first-real-turn", "failed-real-turn", "second-real-turn")) {
                AgentTurnResult result = session.start(command(prompt)).completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                boolean failed = prompt.equals("failed-real-turn");
                if (failed) {
                    assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
                    assertThat(result.terminalReason()).contains("Session limit reached");
                    assertThat(result.finalResponse()).isEmpty();
                }
                else {
                    assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                    assertThat(result.finalResponse()).contains("Review complete");
                    assertThat(result.terminalReason()).isEmpty();
                }
                assertThat(result.providerQuiescent()).isTrue();
                ManagedProcess process = turnProcesses.getLast();
                ProcessExit originalExit = process.exit().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(originalExit.exitCode()).isEqualTo(failed ? 1 : 0);
                assertThat(originalExit.treeTermination()).isEqualTo(ProcessExit.TreeTermination.UNVERIFIABLE);
                // A verified stop drops the receipt with the turn, so no later startup replays it.
                assertThat(launches.resolve(process.launchId() + ".json")).doesNotExist();
                assertThat(recorder.committedLaunches()).doesNotContainKey(process.launchId());
                JsonNode receipt = acknowledgedReceipts.get(process.launchId());
                assertThat(receipt.path("descendantTrackingIncomplete").asBoolean()).isTrue();
                assertThat(receipt.path("workFinished").asBoolean()).isTrue();
                assertThat(receipt.path("treeTermination").asText()).isEqualTo("VERIFIED_STOPPED");
            }
            assertThat(turnSpecs.getLast().argv()).containsSequence("--resume", session.reference().providerValue());
            List<String> argv = turnSpecs.getLast().argv();
            assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1))
                    .contains(AgentCommandPolicy.reviewInstructions(), ClaudeCliDriver.PLAN_INSTRUCTION, review.instruction(), "bytequay_list_tools");
            assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Command command
                            && command.state() == ProviderAgentEvent.ActivityState.COMPLETED)
                    .hasSize(2);
            assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Tool tool
                            && tool.name().equals("mcp__bytequay__review_get_context") && tool.state() == ProviderAgentEvent.ActivityState.COMPLETED)
                    .hasSize(2);
        }
    }

    @Test
    void advertisesCodeQueriesAndInstructionsOnlyWhenBridged()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSessionContext plain = context(Optional.empty());
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
                "");

        AgentSession session = driver.open(bridged, event -> {});
        await(session.start(command("first")));
        ProcessSpec spec = supervisor.turns().getFirst().spec();
        assertThat(spec.argv().get(spec.argv().indexOf("--append-system-prompt") + 1))
                .contains(DEVELOPMENT_POLICY, ClaudeCliDriver.PLAN_INSTRUCTION, "use the graph", "bytequay_list_tools", "bytequay_get_tool");
        int allowed = spec.argv().indexOf("--allowedTools");
        assertThat(allowed).isPositive();
        assertThat(spec.argv().get(allowed + 1)).contains("mcp__bytequay__code_query");
        session.close();

        AgentSession unbridged = driver.open(plain, event -> {});
        await(unbridged.start(command("second")));
        ProcessSpec plainSpec = supervisor.turns().get(1).spec();
        assertThat(plainSpec.argv()).containsSequence("--append-system-prompt", DEVELOPMENT_POLICY + "\n\n" + ClaudeCliDriver.PLAN_INSTRUCTION + "\n\n" + DEVELOPMENT_SANDBOX);
        assertThat(plainSpec.argv()).noneMatch(argument -> argument.contains("code_query"));
        unbridged.close();
    }

    @Test
    void transportsLargeReviewReferenceInputOnInitialAndResumedTurns()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        String instructions = "p".repeat(100_000);
        String reference = "reference-body-marker\n" + "知".repeat(10_000);
        String input = "Review this change.\n<repository-context>\n" + reference + "\n</repository-context>\n";
        input += "x".repeat(256 * 1024 - input.getBytes(StandardCharsets.UTF_8).length);
        assertThat(input.getBytes(StandardCharsets.UTF_8)).hasSize(256 * 1024);
        Optional<AgentSessionRef> sessionReference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            try (AgentSession session = driver.open(context(sessionReference, instructions), event -> {})) {
                assertThat(await(session.start(new AgentTurnCommand("context-turn-" + index, input, ImmutableList.of(), Duration.ofMinutes(1)))).state())
                        .isEqualTo(AgentTurnResult.State.COMPLETED);
                ScriptedTurn turn = supervisor.turns().getLast();
                assertThat(turn.prompt()).isEqualTo(input + "\n");
                assertThat(turn.spec().argv().get(turn.spec().argv().indexOf("--append-system-prompt") + 1))
                        .contains(DEVELOPMENT_POLICY, instructions);
                assertThat(turn.spec().argv()).noneMatch(argument -> argument.contains("reference-body-marker"));
                assertThat(turn.spec().argv().contains("--resume")).isEqualTo(index == 1);
                sessionReference = Optional.of(session.reference());
            }
        }
        driver.close();
    }

    @Test
    void replacesManagedInstructionsOnResumeWithoutAddingTools()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        Optional<AgentSessionRef> reference = Optional.empty();
        for (String instruction : ImmutableList.of("Keep explanations concise", "Lead with the next action", "")) {
            AgentSession session = driver.open(context(reference, instruction), event -> {});
            await(session.start(command("configured")));
            List<String> arguments = supervisor.turns().getLast().spec().argv();
            String expected = DEVELOPMENT_POLICY + "\n\n" + ClaudeCliDriver.PLAN_INSTRUCTION + (instruction.isEmpty() ? "" : "\n\n" + instruction) + "\n\n" + DEVELOPMENT_SANDBOX;
            assertThat(arguments).containsSequence("--append-system-prompt", expected);
            assertThat(arguments.stream().filter(argument -> argument.startsWith("--append-system-prompt"))).hasSize(1);
            assertThat(arguments).noneMatch(argument -> argument.contains("code_query") || argument.contains("review_finish"));
            assertThat(arguments.contains("--resume")).isEqualTo(reference.isPresent());
            reference = Optional.of(session.reference());
            session.close();
        }
        driver.close();
    }

    @Test
    void probesStreamsResumesHandlesMalformedAndTrailingEventsAndCancels()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();

        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(driver.capabilities().permissionPolicies())
                .containsExactlyInAnyOrder(AgentPermissionPolicy.values());
        assertThat(driver.capabilities().nestedAgentActivity()).isTrue();
        assertThat(driver.capabilities().structuredPlans()).isTrue();
        assertThat(driver.capabilities().attachmentMediaTypes())
                .contains("text/plain; charset=utf-8");
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        AgentSessionRef reference = session.reference();

        AgentTurnResult first = await(session.start(command("first")));
        assertThat(first.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(first.finalResponse()).contains("authoritative [REDACTED]");
        List<ProviderAgentEvent.Message> messages = events.stream()
                .filter(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .toList();
        assertThat(messages).hasSize(4);
        assertThat(messages.subList(0, 2))
                .allSatisfy(message -> {
                    assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
                    assertThat(message.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
                    assertThat(message.providerEventId()).isEmpty();
                });
        ProviderAgentEvent.Message completedCommentary = messages.get(2);
        assertThat(completedCommentary.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
        assertThat(completedCommentary.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(completedCommentary.providerEventId()).contains("answer-1:text:0");
        ProviderAgentEvent.Message authoritative = messages.getLast();
        assertThat(authoritative.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.ANSWER);
        assertThat(authoritative.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(authoritative.providerEventId()).contains("result-1:answer:0");
        assertThat(messages)
                .extracting(message -> message.source().providerItemId().orElseThrow())
                .containsOnly(messages.getFirst().source().providerItemId().orElseThrow());
        Map<String, String> projectedMessages = new LinkedHashMap<>();
        messages.forEach(message -> {
            String itemId = message.source().providerItemId().orElseThrow();
            if (message.phase() == ProviderAgentEvent.MessagePhase.PARTIAL) {
                projectedMessages.merge(itemId, message.text(), String::concat);
            }
            else {
                projectedMessages.put(itemId, message.text());
            }
        });
        assertThat(projectedMessages).containsExactly(Map.entry(messages.getFirst().source().providerItemId().orElseThrow(), "authoritative [REDACTED]"));
        ProcessSpec firstSpec = supervisor.turns().getFirst().spec();
        assertThat(firstSpec.environment()).containsEntry("ANTHROPIC_API_KEY", "known-secret")
                .doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN", "GH_CONFIG_DIR", "SSH_AUTH_SOCK");
        // Probes and turns alike derive their environment from the driver's, so `--version`, `--help`,
        // `auth status` and every turn read ByteQuay's home; an exported one never redirects them.
        assertThat(supervisor.specs).hasSizeGreaterThan(1).allSatisfy(spec ->
                assertThat(spec.environment()).containsEntry("CLAUDE_CONFIG_DIR", claudeHome().toAbsolutePath().toString()));
        assertThat(firstSpec.owner().kind()).isEqualTo(ProcessOwner.Kind.CLAUDE_TURN);
        assertThat(firstSpec.argv()).containsSequence("--session-id", reference.providerValue());
        assertThat(firstSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(firstSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(firstSpec.argv()).doesNotContain("first");
        assertThat(supervisor.turns().getFirst().prompt()).isEqualTo("first\n");
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        assertThat(events).filteredOn(ProviderAgentEvent.Tool.class::isInstance)
                .anySatisfy(event -> assertThat(((ProviderAgentEvent.Tool) event).name())
                        .isEqualTo("hook/Stop"));
        int trailingHook = indexOf(events, event -> event instanceof ProviderAgentEvent.Tool tool
                && tool.name().equals("hook/Stop"));
        int completion = indexOf(events, event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED);
        assertThat(trailingHook).isLessThan(completion);
        assertThat(events.toString()).doesNotContain("known-secret");

        AgentTurnResult second = await(session.start(command("second")));
        assertThat(second.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.turns().get(1).spec().argv())
                .containsSequence("--resume", reference.providerValue());

        AgentTurnResult failed = await(session.start(command("fail")));
        assertThat(failed.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failed.terminalReason()).contains("[REDACTED] diagnostic");

        RunningTurn cancelled = session.start(command("cancel"));
        cancelled.cancel();
        assertThat(await(cancelled).state()).isEqualTo(AgentTurnResult.State.CANCELLED);

        Path configFile = runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json");
        assertThat(configFile).exists();
        // Deferred behind tool search, report_development_result is a tool the agent never finds to call.
        assertThat(objectMapper.readTree(Files.readString(configFile)).at("/mcpServers/bytequay/alwaysLoad").asBoolean()).isTrue();
        session.close();
        assertThat(configFile).doesNotExist();

        AgentSession ciSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CI_AUTOFIX), events::add);
        Path log = runtimeDirectory().resolve("blobs/ci.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "failure");
        Path realLog = log.toRealPath();
        AgentAttachment attachment = new AgentAttachment("github-actions.log", "text/plain; charset=utf-8", log);
        assertThat(await(ciSession.start(command("ci-fix", ImmutableList.of(attachment)))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec ciSpec = supervisor.turns().getLast().spec();
        assertThat(ciSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(ciSpec.argv()).contains("--strict-mcp-config");
        assertThat(ciSpec.argv()).contains("--setting-sources=");
        assertThat(ciSpec.argv()).containsSequence("--add-dir", realLog.getParent().toString());
        assertThat(ciSpec.argv().get(ciSpec.argv().indexOf("--append-system-prompt") + 1))
                .contains("ByteQuay CI Repair Scope", "Never push");
        int allowedTools = ciSpec.argv().indexOf("--allowedTools");
        assertThat(allowedTools).isGreaterThanOrEqualTo(0);
        assertThat(ciSpec.argv().get(allowedTools + 1))
                .contains("mcp__bytequay__read_ci_memory", "Read(/" + realLog + ")");
        assertThat(supervisor.turns().getLast().prompt()).contains(realLog.toString());
        assertThat(ciSpec.environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN", "GH_CONFIG_DIR", "SSH_AUTH_SOCK");
        Path settingsFile = runtimeDirectory().resolve("provider/scratch/sandbox-settings.json");
        assertThat(settingsFile).exists();
        ciSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession cherryPickSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CHERRY_PICK), events::add);
        assertThat(await(cherryPickSession.start(command("resolve"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec cherryPickSpec = supervisor.turns().getLast().spec();
        assertThat(cherryPickSpec.environment()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR");
        assertThat(cherryPickSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(cherryPickSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(cherryPickSpec.argv()).doesNotContain("--allowedTools");
        assertThat(String.join(" ", cherryPickSpec.argv())).doesNotContain("read_ci_memory");
        assertThat(settingsFile).exists();
        cherryPickSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession autoSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.AUTO), events::add);
        assertThat(await(autoSession.start(command("auto"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec autoSpec = supervisor.turns().getLast().spec();
        assertThat(autoSpec.environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN", "GH_CONFIG_DIR", "SSH_AUTH_SOCK");
        assertThat(autoSpec.stdoutCaptureLimit()).isEmpty();
        assertThat(autoSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(autoSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(autoSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(settingsFile).exists();
        autoSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession textSession = driver.open(context(
                Optional.empty(), AgentPermissionPolicy.AUTO, Optional.of("sonnet"), Optional.of("Extra"), SessionAccess.TEXT_ONLY),
                events::add);
        assertThat(await(textSession.start(command("rewrite"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec textSpec = supervisor.turns().getLast().spec();
        assertThat(textSpec.environment()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR");
        assertThat(textSpec.stdoutCaptureLimit()).isEmpty();
        assertThat(textSpec.owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "task-1"));
        assertThat(textSpec.workingDirectory().normalize().startsWith(runtimeDirectory().normalize())).isTrue();
        assertThat(textSpec.argv()).contains("--tools=");
        assertThat(textSpec.argv()).noneMatch(argument -> argument.contains("TodoWrite"));
        assertThat(textSpec.argv()).contains("--disable-slash-commands", "--no-session-persistence");
        assertThat(textSpec.argv()).containsSequence("--model", "sonnet");
        assertThat(textSpec.argv()).containsSequence("--effort", "xhigh");
        assertThat(settingsFile).exists();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/network/allowedDomains")).isEmpty();
        textSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession readOnlySession = driver.open(context(Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty(), Optional.empty(), SessionAccess.READ_ONLY), events::add);
        assertThat(await(readOnlySession.start(command("explain"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec readOnlySpec = supervisor.turns().getLast().spec();
        // Attributable to its task, unlike a text-only probe, because it reaches a real worktree.
        assertThat(readOnlySpec.owner()).isEqualTo(new ProcessOwner(ProcessOwner.Kind.DISCUSSION, "task-1"));
        assertThat(readOnlySpec.argv()).containsSequence("--tools", "Bash,Glob,Grep,Read");
        assertThat(readOnlySpec.argv()).doesNotContain("--no-session-persistence");
        assertThat(readOnlySpec.argv()).noneMatch(argument -> argument.contains("Edit") || argument.contains("Write"));
        assertThat(readOnlySpec.environment()).doesNotContainKeys("GH_TOKEN", "GH_CONFIG_DIR");
        assertThat(objectMapper.readTree(Files.readString(settingsFile)).at("/sandbox/filesystem/denyWrite").toString())
                .contains(worktree.toRealPath().toString());
        readOnlySession.close();
        assertThat(settingsFile).doesNotExist();
        driver.close();
    }

    @Test
    void probeKeepsRecoveryEvidenceWhenItsProcessTreeIsUnverifiable()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.leaveProbeTreeUnverifiable();

        AgentProviderStatus status = driver(supervisor).probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        assertThat(status.diagnostic()).contains("process tree could not be verified stopped");
        assertThat(supervisor.acknowledgements).isEmpty();
    }

    @Test
    void distinguishesUnsupportedFromUnparseableVersions()
    {
        ScriptedProcessSupervisor unsupportedSupervisor = new ScriptedProcessSupervisor();
        unsupportedSupervisor.versionOutput("1.0.0");
        ClaudeCliDriver unsupportedDriver = driver(unsupportedSupervisor);
        assertThat(unsupportedDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        unsupportedDriver.close();

        ScriptedProcessSupervisor unparseableSupervisor = new ScriptedProcessSupervisor();
        unparseableSupervisor.versionOutput("future-build");
        ClaudeCliDriver unparseableDriver = driver(unparseableSupervisor);
        assertThat(unparseableDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        unparseableDriver.close();
    }

    @Test
    void parsesAndOrdersReleasesNumerically()
    {
        assertThat(ClaudeCliDriver.parseVersion("2.1.0 (Claude Code)").map(Object::toString)).contains("2.1.0");
        assertThat(ClaudeCliDriver.parseVersion("2.1.10").orElseThrow()).isGreaterThan(ClaudeCliDriver.parseVersion("2.1.9").orElseThrow());
        assertThat(ClaudeCliDriver.parseVersion("2.1.278").orElseThrow()).isEqualByComparingTo(ClaudeCliDriver.parseVersion("2.1.278\n").orElseThrow());
        assertThat(ClaudeCliDriver.parseVersion("2.1")).isEmpty();
        assertThat(ClaudeCliDriver.parseVersion("2.1.0-beta")).isEmpty();
    }

    @Test
    void rejectsClaudeWithoutAutoPermissionMode()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.helpOutput(supervisor.helpOutput().replace("\"auto\" ", ""));
        ClaudeCliDriver driver = driver(supervisor);

        AgentProviderStatus status = driver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        assertThat(status.diagnostic()).contains("auto permission mode");
        assertThat(supervisor.authenticationProbes()).isZero();
        driver.close();
    }

    @Test
    void rejectsMissingOrMalformedAutoModeHardDenySupportBeforeAuthentication()
    {
        for (String defaults : ImmutableList.of("{}", "{\"hard_deny\":false}", "{\"hard_deny\":[1]}", "{\"hard_deny\":[]}{}", "invalid")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
            supervisor.autoModeDefaults = defaults;
            try (ClaudeCliDriver driver = driver(supervisor)) {
                assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
                assertThat(supervisor.authenticationProbes()).isZero();
            }
        }
    }

    @Test
    void acceptsLargeAutoModeDefaultsBeforeAuthentication()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.autoModeDefaults = "{\"hard_deny\":[\"" + "policy ".repeat(16 * 1024) + "\"]}";
        try (ClaudeCliDriver driver = driver(supervisor)) {
            assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
            assertThat(supervisor.authenticationProbes()).isOne();
        }
    }

    @Test
    void refusesTruncatedAutoModeDefaultsBeforeAuthentication()
    {
        for (boolean stderr : ImmutableList.of(false, true)) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
            String oversized = "{\"hard_deny\":[\"" + "policy ".repeat(64 * 1024) + "\"]}";
            if (stderr) {
                supervisor.autoModeStderr = oversized;
            }
            else {
                supervisor.autoModeDefaults = oversized;
            }
            try (ClaudeCliDriver driver = driver(supervisor)) {
                AgentProviderStatus status = driver.probe();
                assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
                assertThat(status.diagnostic()).contains("auto-mode defaults output was truncated");
                assertThat(supervisor.authenticationProbes()).isZero();
            }
        }
    }

    @Test
    void reportsAutoModeProbeTimeoutAndRedactsCommandFailures()
    {
        for (boolean timeout : ImmutableList.of(false, true)) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
            supervisor.autoModeTimeout = timeout;
            supervisor.autoModeExitCode = 1;
            supervisor.autoModeStderr = "Unable to load defaults: known-secret";
            try (ClaudeCliDriver driver = driver(supervisor)) {
                AgentProviderStatus status = driver.probe();
                assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
                if (timeout) {
                    assertThat(status.diagnostic()).contains("auto-mode defaults timed out");
                }
                else {
                    assertThat(status.diagnostic()).contains("auto-mode defaults failed", "Unable to load defaults: [REDACTED]").doesNotContain("known-secret");
                }
                assertThat(supervisor.authenticationProbes()).isZero();
            }
        }
    }

    @Test
    void appliesDistinctPerTurnSnapshotsToNewAndResumedInvocations()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty(), AgentPermissionPolicy.AUTO, Optional.of("opus"), Optional.of("High"), SessionAccess.FULL), ignored -> {});

        await(session.start(new AgentTurnCommand("turn-first", "first", ImmutableList.of(), Duration.ofMinutes(1), Optional.of("sonnet"), Optional.of("Low"))));
        await(session.start(new AgentTurnCommand("turn-second", "second", ImmutableList.of(), Duration.ofMinutes(1), Optional.of("haiku"), Optional.of("Extra"))));

        assertThat(supervisor.turns().getFirst().spec().argv())
                .containsSequence("--model", "sonnet")
                .containsSequence("--effort", "low")
                .contains("--session-id")
                .doesNotContain("--resume");
        assertThat(supervisor.turns().get(1).spec().argv())
                .containsSequence("--model", "haiku")
                .containsSequence("--effort", "xhigh")
                .contains("--resume");
        session.close();
        driver.close();
    }

    @Test
    void inheritsTheFirstJavaHomeWithAnExecutableJava()
            throws IOException
    {
        String configured = javaHome("configured");
        String running = javaHome("running");
        String withoutJava = Files.createDirectories(worktree.resolve("without-java")).toString();
        Map<String, String> allowlisted = ImmutableMap.of("PATH", "/usr/bin:/bin", "HOME", "/Users/test", "GH_TOKEN", "github-sentinel");

        assertThat(ClaudeCliDriver.inheritedEnvironment(
                ImmutableMap.<String, String>builder().putAll(allowlisted).put("JAVA_HOME", configured).put("UNLISTED", "value").buildOrThrow(),
                Optional.of(running)))
                .isEqualTo(ImmutableMap.<String, String>builder().putAll(allowlisted).put("JAVA_HOME", configured).buildOrThrow());
        assertThat(ClaudeCliDriver.inheritedEnvironment(allowlisted, Optional.of(running))).containsEntry("JAVA_HOME", running);
        assertThat(ClaudeCliDriver.inheritedEnvironment(ImmutableMap.of("JAVA_HOME", withoutJava), Optional.of(running))).isEqualTo(ImmutableMap.of("JAVA_HOME", running));
        assertThat(ClaudeCliDriver.inheritedEnvironment(ImmutableMap.of("JAVA_HOME", withoutJava), Optional.of(withoutJava))).isEmpty();
        assertThat(ClaudeCliDriver.inheritedEnvironment(allowlisted, Optional.empty())).isEqualTo(allowlisted);
    }

    private String javaHome(String name)
            throws IOException
    {
        Path home = worktree.resolve(name);
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(java, "#!/bin/sh\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        return home.toString();
    }

    @Test
    void redactsTheRuntimeTaskTokenFromTranscriptAndDiagnostics()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        Path configFile = runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json");
        String authorization = objectMapper.readTree(Files.readString(configFile))
                .at("/mcpServers/bytequay/headers/Authorization")
                .asText();
        String token = authorization.substring("Bearer ".length());
        supervisor.runtimeSecret(token);

        AgentTurnResult completed = await(session.start(command("token")));

        assertThat(completed.finalResponse())
                .hasValueSatisfying(response -> assertThat(response).contains("[REDACTED]"));
        assertThat(events).filteredOn(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .anySatisfy(message -> assertThat(message.text()).contains("[REDACTED]"));
        assertThat(events).filteredOn(ProviderAgentEvent.Tool.class::isInstance)
                .map(ProviderAgentEvent.Tool.class::cast)
                .anySatisfy(tool -> assertThat(tool.summary()).contains("[REDACTED]"));
        assertThat(events.toString()).doesNotContain(token);

        AgentTurnResult failed = await(session.start(command("fail")));
        assertThat(failed.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("[REDACTED]")
                .doesNotContain(token));
        assertThat(events.toString()).doesNotContain(token);
        session.close();
        driver.close();
    }

    @Test
    void checksReadinessWithoutNetworkAccess()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        try (ClaudeCliDriver driver = driver(supervisor)) {
            assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
            assertThat(supervisor.specs()).isNotEmpty().allSatisfy(spec -> assertThat(spec.argv())
                    .startsWith("/usr/bin/sandbox-exec", "-p", "(version 1) (allow default) (deny network*)", "/fake/claude"));
        }
    }

    @Test
    void failedAccountCheckDoesNotTellTheUsageMeterToSignIn()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.294 (Claude Code)");
        supervisor.authenticationTimeout();
        try (ClaudeCliDriver driver = driver(supervisor)) {
            assertThat(driver.quota().message()).contains("Claude account status is temporarily unavailable. Try again.");
        }
    }

    @Test
    void usesTypedAuthenticationStatusWithoutParsingStderr()
    {
        ScriptedProcessSupervisor loggedIn = new ScriptedProcessSupervisor();
        loggedIn.authentication("{\"loggedIn\":true,\"authMethod\":\"oauth\",\"apiProvider\":\"firstParty\",\"email\":\"work@example.com\"}", "ignored diagnostic", 0);
        ClaudeCliDriver loggedInDriver = driver(loggedIn);
        AgentProviderStatus ready = loggedInDriver.probe();
        assertThat(ready.readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(ready.diagnostic()).isEqualTo("Signed in as work@example.com");
        loggedInDriver.close();

        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor();
        loggedOut.authentication("{\"loggedIn\":false,\"authMethod\":\"none\",\"projectsDirectory\":\"/secret/path\"}", "{\"loggedIn\":true}", 1);
        ClaudeCliDriver loggedOutDriver = driver(loggedOut);

        AgentProviderStatus status = loggedOutDriver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.signInCommand()).contains("CLAUDE_CONFIG_DIR='" + claudeHome().toAbsolutePath() + "' /fake/claude auth login");
        assertThat(status.diagnostic()).doesNotContain("/secret/path", "authMethod");
        loggedOutDriver.close();

        ScriptedProcessSupervisor malformed = new ScriptedProcessSupervisor();
        malformed.authentication("{\"loggedIn\":false}{\"loggedIn\":true}", "", 1);
        ClaudeCliDriver malformedDriver = driver(malformed);
        assertThat(malformedDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        malformedDriver.close();

        ScriptedProcessSupervisor wrongType = new ScriptedProcessSupervisor();
        wrongType.authentication("{\"loggedIn\":\"false\"}", "", 0);
        ClaudeCliDriver wrongTypeDriver = driver(wrongType);
        assertThat(wrongTypeDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        wrongTypeDriver.close();

        ScriptedProcessSupervisor missingField = new ScriptedProcessSupervisor();
        missingField.authentication("{\"authMethod\":\"oauth\"}", "", 0);
        ClaudeCliDriver missingFieldDriver = driver(missingField);
        assertThat(missingFieldDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        missingFieldDriver.close();

        ScriptedProcessSupervisor nonzeroLoggedIn = new ScriptedProcessSupervisor();
        nonzeroLoggedIn.authentication("{\"loggedIn\":true}", "", 1);
        ClaudeCliDriver nonzeroLoggedInDriver = driver(nonzeroLoggedIn);
        assertThat(nonzeroLoggedInDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        nonzeroLoggedInDriver.close();

        ScriptedProcessSupervisor timedOut = new ScriptedProcessSupervisor();
        timedOut.authenticationTimeout();
        ClaudeCliDriver timedOutDriver = driver(timedOut);
        assertThat(timedOutDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        timedOutDriver.close();

        ScriptedProcessSupervisor hostWaitTimedOut = new ScriptedProcessSupervisor();
        hostWaitTimedOut.authenticationHostWaitTimeout();
        ClaudeCliDriver hostWaitTimedOutDriver = driver(hostWaitTimedOut);
        assertThat(hostWaitTimedOutDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        hostWaitTimedOutDriver.close();

        ScriptedProcessSupervisor truncated = new ScriptedProcessSupervisor();
        truncated.authenticationTruncated();
        ClaudeCliDriver truncatedDriver = driver(truncated);
        assertThat(truncatedDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        truncatedDriver.close();
    }

    @Test
    void usesTheConfiguredClaudeProfileForReadinessTurnsAndSignIn()
            throws Exception
    {
        Path profile = worktree.resolve("ByteQuay's Claude home");
        String executable = worktree.resolve("Claude CLI's bin/claude").toString();
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.authentication("{\"loggedIn\":false}", "", 1);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        try (ClaudeCliDriver driver = new ClaudeCliDriver(executable, objectMapper, broker, supervisor, profile,
                ImmutableMap.of("HOME", worktree.resolve("ambient-home").toString(), "CLAUDE_CONFIG_DIR", worktree.resolve("ambient-config").toString()))) {
            AgentProviderStatus status = driver.probe();
            assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
            assertThat(status.signInCommand().orElseThrow()).isEqualTo("CLAUDE_CONFIG_DIR='" + profile.toString().replace("'", "'\"'\"'")
                    + "' '" + executable.replace("'", "'\"'\"'") + "' auth login");
            assertThat(supervisor.specs()).isNotEmpty().allSatisfy(spec -> assertThat(spec.environment()).containsEntry("CLAUDE_CONFIG_DIR", profile.toString()));

            try (AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
                assertThat(await(session.start(command("profile-check"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            }
            assertThat(supervisor.turns().getFirst().spec().environment()).containsEntry("CLAUDE_CONFIG_DIR", profile.toString());
        }
    }

    @Test
    void namedAccountsKeepProbeTurnAndResumeInTheirOwnProfile()
            throws Exception
    {
        AgentLaneId workLane = new AgentLaneId("claude-account-work");
        AgentLaneId personalLane = new AgentLaneId("claude-account-personal");
        Path workHome = worktree.resolve("work-profile");
        Path personalHome = worktree.resolve("personal-profile");
        Path buildHome = Files.createDirectories(worktree.resolve("user-home"));
        ScriptedProcessSupervisor workSupervisor = new ScriptedProcessSupervisor();
        ScriptedProcessSupervisor personalSupervisor = new ScriptedProcessSupervisor();
        workSupervisor.authentication("{\"loggedIn\":false}", "", 1);
        personalSupervisor.authentication("{\"loggedIn\":true,\"email\":\"personal@example.com\"}", "", 0);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        Map<String, String> ambient = ImmutableMap.of(
                "HOME", buildHome.toString(), "JAVA_HOME", System.getProperty("java.home"), "CLAUDE_CONFIG_DIR", "/ambient", "ANTHROPIC_API_KEY", "wrong-key",
                "ANTHROPIC_AUTH_TOKEN", "wrong-token", "CLAUDE_CODE_OAUTH_TOKEN", "wrong-login", "ANTHROPIC_BASE_URL", "https://wrong.example");
        try (ClaudeCliDriver work = new ClaudeCliDriver("/fake/claude", objectMapper, broker, workSupervisor, workHome,
                workLane, "Claude · Work", Optional.of("work@example.com"), ambient);
                ClaudeCliDriver personal = new ClaudeCliDriver("/fake/claude", objectMapper, broker, personalSupervisor, personalHome,
                        personalLane, "Claude · Personal", Optional.of("personal@example.com"), ambient)) {
            AgentProviderStatus status = work.probe();
            assertThat(status.laneId()).isEqualTo(workLane);
            assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
            assertThat(status.diagnostic()).isEqualTo("Sign in as work@example.com");
            assertThat(status.signInCommand().orElseThrow()).contains("CLAUDE_CONFIG_DIR='" + workHome + "' /fake/claude auth login")
                    .endsWith(" --claudeai --email 'work@example.com'")
                    .startsWith("/usr/bin/env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL -u CLAUDE_CODE_OAUTH_TOKEN ");
            assertThat(personal.probe().diagnostic()).isEqualTo("Signed in as personal@example.com");
            workSupervisor.authentication("{\"loggedIn\":true,\"email\":\"personal@example.com\"}", "", 0);
            assertThat(work.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
            assertThat(work.probe().diagnostic()).isEqualTo("Signed in as personal@example.com; sign in as work@example.com");
            assertThat(work.probe().signInCommand().orElseThrow()).endsWith(" --email 'work@example.com'");
            workSupervisor.authentication("{\"loggedIn\":true}", "", 0);
            assertThat(work.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
            workSupervisor.authentication("{\"loggedIn\":true,\"email\":\"WORK@example.com\"}", "", 0);
            assertThat(work.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
            workSupervisor.authentication("{\"loggedIn\":true,\"email\":\"work@example.com\"}", "", 0);
            assertThat(work.probe().diagnostic()).isEqualTo("Signed in as work@example.com");
            AgentSessionRef reference;
            try (AgentSession workSession = work.open(accountContext("repository-a", Optional.empty()), event -> {});
                    AgentSession personalSession = personal.open(accountContext("repository-b", Optional.empty()), event -> {})) {
                reference = workSession.reference();
                assertThat(reference.laneId()).isEqualTo(workLane);
                assertThat(personalSession.reference().laneId()).isEqualTo(personalLane);
                var workTurn = workSession.start(command("profile-work"));
                var personalTurn = personalSession.start(command("profile-personal"));
                assertThat(await(workTurn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(await(personalTurn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            }
            assertThatThrownBy(() -> personal.open(accountContext("repository-b", Optional.of(reference)), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("session reference");
            try (AgentSession resumed = work.open(accountContext("repository-a", Optional.of(reference)), event -> {})) {
                assertThat(resumed.reference()).isEqualTo(reference);
                assertThat(await(resumed.start(command("profile-resume"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            }
            for (ScriptedProcessSupervisor supervisor : ImmutableList.of(workSupervisor, personalSupervisor)) {
                Path expected = supervisor == workSupervisor ? workHome : personalHome;
                assertThat(supervisor.specs()).isNotEmpty().allSatisfy(spec -> assertThat(spec.environment())
                        .containsEntry("CLAUDE_CONFIG_DIR", expected.toString())
                        .containsEntry("HOME", buildHome.toString())
                        .containsEntry("JAVA_HOME", System.getProperty("java.home"))
                        .doesNotContainKeys("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_BASE_URL"));
                assertThat(supervisor.specs()).filteredOn(spec -> spec.argv().contains("status"))
                        .allSatisfy(spec -> assertThat(spec.argv()).startsWith("/usr/bin/sandbox-exec", "-p", "(version 1) (allow default) (deny network*)"));
                for (String content : supervisor.sandboxSettings) {
                    JsonNode settings = objectMapper.readTree(content);
                    assertThat(settings.path("sandbox").path("filesystem").path("allowWrite"))
                            .contains(objectMapper.getNodeFactory().textNode(buildHome.toRealPath().resolve(".m2").toString()));
                }
            }
            assertThat(workSupervisor.turns()).allSatisfy(turn -> assertThat(turn.spec().workingDirectory()).isEqualTo(worktree.resolve("repository-a/task-worktree")));
            assertThat(personalSupervisor.turns()).allSatisfy(turn -> assertThat(turn.spec().workingDirectory()).isEqualTo(worktree.resolve("repository-b/task-worktree")));
            assertThat(workSupervisor.turns()).hasSize(2);
            assertThat(personalSupervisor.turns()).hasSize(1);
        }
    }

    @Test
    void namedAccountSignInUsesItsQuotedProfileDespiteAmbientCredentials()
            throws Exception
    {
        Path executable = Files.createDirectories(worktree.resolve("Claude CLI's bin")).resolve("claude");
        Files.writeString(executable, """
                #!/bin/sh
                test "$1/$2" = auth/login || exit 2
                test "$3" = --claudeai || exit 4
                test "$4" = --email || exit 5
                test "$5" = "o'connor+work@example.com" || exit 6
                test "$#" = 5 || exit 7
                test -z "${ANTHROPIC_API_KEY}${ANTHROPIC_AUTH_TOKEN}${ANTHROPIC_BASE_URL}${CLAUDE_CODE_OAUTH_TOKEN}" || exit 3
                printf '%s' "$CLAUDE_CONFIG_DIR"
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.authentication("{\"loggedIn\":false}", "", 1);
        Path profile = worktree.resolve("Work account's profile");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        try (ClaudeCliDriver driver = new ClaudeCliDriver(executable.toString(), objectMapper, broker, supervisor, profile,
                new AgentLaneId("claude-account-work"), "Claude · Work", Optional.of("o'connor+work@example.com"), ImmutableMap.of())) {
            ProcessBuilder shell = new ProcessBuilder("/bin/sh", "-c", driver.probe().signInCommand().orElseThrow());
            shell.environment().putAll(ImmutableMap.of("CLAUDE_CONFIG_DIR", "/wrong-profile", "ANTHROPIC_API_KEY", "wrong-key",
                    "ANTHROPIC_AUTH_TOKEN", "wrong-token", "ANTHROPIC_BASE_URL", "https://wrong.example", "CLAUDE_CODE_OAUTH_TOKEN", "wrong-login"));
            Process process = shell.start();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(profile.toString());
        }
    }

    @Test
    void classifiesMidTurnAuthenticationExpiryOnlyFromTypedFollowUpProbe()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        supervisor.authentication("{\"loggedIn\":false}", "expired", 1);
        AgentSession session = driver.open(context(Optional.empty()), events::add);

        AgentTurnResult result = await(session.start(command("fail")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.failureKind()).contains(AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThat(result.terminalReason()).contains("Claude sign-in required");
        assertThat(supervisor.authenticationProbes()).isEqualTo(2);
        assertThat(events).filteredOn(ProviderAgentEvent.Error.class::isInstance)
                .map(ProviderAgentEvent.Error.class::cast)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("AUTHENTICATION_REQUIRED");
                    assertThat(error.message()).isEqualTo("Claude sign-in required");
                });
        session.close();
        driver.close();
    }

    @ParameterizedTest(name = "{0} failure rejects the turn")
    @CsvSource({"write, Unable to write Claude prompt", "close, Unable to close Claude prompt input"})
    void rejectsTurnWhenPromptInputFails(String operation, String message)
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        boolean closeInput = operation.equals("close");
        if (closeInput) {
            supervisor.failNextPromptClose();
        }
        else {
            supervisor.failNextPromptWrite();
        }
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), ignored -> {});

        assertThatThrownBy(() -> session.start(command(operation + "-failure")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining(message)
                .hasRootCauseMessage("simulated prompt " + operation + " failure");

        assertThat(supervisor.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.cancelled()).isTrue();
            assertThat(turn.inputClosed()).isEqualTo(closeInput);
        });
        session.close();
        driver.close();
    }

    @Test
    void steersClaudeAndWaitsForEveryInputBeforeClosingTheProcess()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295 (Claude Code)");
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            assertThat(driver.probe().capabilities().steering()).isTrue();
            RunningTurn turn = session.start(command("Review the change"));
            ScriptedTurn process = supervisor.turns().getFirst();
            assertThat(process.spec().argv()).containsSequence("--input-format", "stream-json");
            assertThat(process.inputClosed()).isFalse();

            turn.steer(new AgentTurnInput("Docker started now\nCheck the container tests", ImmutableList.of()));
            assertThat(process.inputs).hasSize(2);
            JsonNode steer = process.inputs.getLast();
            assertThat(steer.path("priority").asText()).isEqualTo("now");
            assertThat(steer.at("/message/content/0/text").asText()).isEqualTo("Docker started now\nCheck the container tests\n");
            assertThat(steer.path("session_id").asText()).isEqualTo(session.reference().providerValue());
            assertThat(steer.path("uuid").asText()).isNotEqualTo(process.inputs.getFirst().path("uuid").asText());

            process.streamingResult("Original result");
            process.inputLifecycle(0, "completed");
            assertThat(process.inputClosed()).isFalse();
            assertThat(turn.completion().toCompletableFuture()).isNotDone();

            process.inputLifecycle(1, "started");
            process.streamingResult("Container tests checked");
            process.inputLifecycle(1, "completed");
            assertThat(process.inputClosed()).isTrue();
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            process.exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(10), false, false));
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThatThrownBy(() -> turn.steer(new AgentTurnInput("Too late", ImmutableList.of())))
                    .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("not accepting");

            RunningTurn next = session.start(command("Follow up"));
            assertThat(supervisor.turns().getLast().spec().argv()).containsSequence("--resume", session.reference().providerValue());
            next.cancel();
            assertThat(await(next).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForTheMatchingClaudeInputReceiptAndHandlesExitWhileWaiting(boolean exitBeforeReceipt)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295");
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            driver.probe();
            RunningTurn turn = session.start(command("Review"));
            ScriptedTurn process = supervisor.turns().getFirst();
            process.acknowledgeInput = false;
            CompletableFuture<Void> delivery = CompletableFuture.runAsync(() -> turn.steer(new AgentTurnInput("Docker started", ImmutableList.of())));
            process.steeringInput.get(5, TimeUnit.SECONDS);
            process.inputLifecycle(0, "queued");
            assertThat(delivery).isNotDone();
            process.streamingResult("Original result");
            process.inputLifecycle(0, "completed");
            assertThat(process.inputClosed()).isFalse();
            if (!exitBeforeReceipt) {
                process.inputLifecycle(1, "queued");
                delivery.get(5, TimeUnit.SECONDS);
                turn.cancel();
                assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
                return;
            }
            process.exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(10), false, false));

            assertThatThrownBy(() -> delivery.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentDeliveryUncertainException.class);
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(process.workFinished).isFalse();
        }
    }

    @Test
    void failsAnUncertainClaudeSteeringWriteWithoutHangingTheTurn()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295");
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            driver.probe();
            RunningTurn turn = session.start(command("Review"));
            ScriptedTurn process = supervisor.turns().getFirst();
            process.failSteerWrite = true;
            assertThatThrownBy(() -> turn.steer(new AgentTurnInput("Docker started", ImmutableList.of())))
                    .isInstanceOf(AgentDeliveryUncertainException.class);
            assertThat(process.cancelled()).isTrue();
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        }
    }

    @Test
    void leavesSteeringUnavailableWithoutTheVerifiedClaudeProtocol()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        try (ClaudeCliDriver driver = driver(supervisor)) {
            assertThat(driver.probe().capabilities().steering()).isFalse();
            supervisor.versionOutput("2.1.295");
            supervisor.helpOutput(supervisor.helpOutput().replace("--input-format", ""));
            assertThat(driver.probe().capabilities().steering()).isFalse();
        }
    }

    @Test
    void stopsClaudeWhenItDiscardsAcceptedInput()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295");
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            driver.probe();
            RunningTurn turn = session.start(command("Review"));
            turn.steer(new AgentTurnInput("Docker started", ImmutableList.of()));
            ScriptedTurn process = supervisor.turns().getFirst();
            process.inputLifecycle(1, "cancelled");
            assertThat(process.cancelled()).isTrue();
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        }
    }

    @Test
    void stopsClaudeWhenStreamingInputCannotClose()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295");
        supervisor.failNextPromptClose();
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            driver.probe();
            RunningTurn turn = session.start(command("Review"));
            ScriptedTurn process = supervisor.turns().getFirst();
            process.streamingResult("Done");
            process.inputLifecycle(0, "completed");
            assertThat(process.cancelled()).isTrue();
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(await(turn).terminalReason().orElseThrow()).contains("Unable to close Claude prompt input");
        }
    }

    @Test
    void doesNotResumeClaudeBasedOnlyOnAQueuedInputReceipt()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput("2.1.295");
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), ignored -> {})) {
            driver.probe();
            RunningTurn turn = session.start(command("Review"));
            turn.cancel();
            await(turn);
            RunningTurn next = session.start(command("Try again"));
            assertThat(supervisor.turns().getLast().spec().argv()).contains("--session-id").doesNotContain("--resume");
            next.cancel();
            await(next);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptsTurnWhenFinalUsageCannotPersist(boolean streaming)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        if (streaming) {
            supervisor.versionOutput("2.1.295");
        }
        ClaudeCliDriver driver = driver(supervisor);
        driver.probe();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicBoolean failUsage = new AtomicBoolean(true);
        AgentSession session = driver.open(context(Optional.empty()), event -> {
            if (event instanceof ProviderAgentEvent.Usage && failUsage.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated usage persistence failure");
            }
            events.add(event);
        });

        RunningTurn turn = session.start(command("usage-failure"));
        if (streaming) {
            supervisor.turns().getFirst().streamingResult("Done");
        }
        AgentTurnResult result = await(turn);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason())
                .contains("Unable to persist Claude terminal events: simulated usage persistence failure");
        assertThat(events).noneMatch(ProviderAgentEvent.Usage.class::isInstance);
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        session.close();
        driver.close();
    }

    @Test
    void refusesToCompleteATurnWhoseProcessTreeIsUnverifiable()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path scratch = Files.createDirectories(worktree.resolve(".claude/.cc-writes"));

        AgentTurnResult result = await(session.start(command("unverifiable")));

        // A clean provider result is not quiescence: the group may still be writing to the worktree.
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason()).contains("Claude process tree could not be verified stopped");
        assertThat(result.finalResponse()).isEmpty();
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(scratch).isDirectory();
        assertThatThrownBy(session::close)
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("process tree could not be verified stopped");

        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void issueContextToolIsAllowedAndInstructedOnOpenAndResumeWithoutRestrictingDelegation()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        IssueContextBridge issue = new IssueContextBridge(() -> "Accepted issue context", IssueContextBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .issueContexts(taskId -> Optional.of(issue)).build();
        ClaudeCliDriver driver = new ClaudeCliDriver("/fake/claude", objectMapper, broker, supervisor, claudeHome(), ImmutableMap.of());
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            try (AgentSession session = driver.open(context(reference), event -> {})) {
                assertThat(await(session.start(command("issue-info"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                reference = Optional.of(session.reference());
                List<String> argv = supervisor.turns().getLast().spec().argv();
                assertThat(argv).doesNotContain("--disable-slash-commands", "--tools");
                assertThat(argv.get(argv.indexOf("--allowedTools") + 1)).contains("mcp__bytequay__" + IssueContextBridge.TOOL_NAME);
                assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1)).contains(IssueContextBridge.INSTRUCTION);
            }
        }
        driver.close();
    }

    @Test
    void pullRequestCiToolIsAllowedAndInstructedOnOpenAndResumeWithoutRestrictingDelegation()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        PullRequestCiBridge ci = new PullRequestCiBridge(jobId -> "No pull request associated yet", (logId, offset) -> "raw", PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty());
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .pullRequestChecks(taskId -> Optional.of(ci)).build();
        ClaudeCliDriver driver = new ClaudeCliDriver("/fake/claude", objectMapper, broker, supervisor, claudeHome(), ImmutableMap.of());
        Optional<AgentSessionRef> reference = Optional.empty();
        for (int index = 0; index < 2; index++) {
            try (AgentSession session = driver.open(context(reference), event -> {})) {
                assertThat(await(session.start(command("ci-info"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                reference = Optional.of(session.reference());
                List<String> argv = supervisor.turns().getLast().spec().argv();
                assertThat(argv).doesNotContain("--disable-slash-commands", "--tools");
                assertThat(argv.get(argv.indexOf("--allowedTools") + 1)).contains("mcp__bytequay__" + PullRequestCiBridge.TOOL_NAME);
                assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1)).contains(PullRequestCiBridge.INSTRUCTION);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privateLocalReviewToolsPreserveDevelopmentAndNativeDelegationOnResume(boolean apiKey)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        LocalReviewBridge local = new LocalReviewBridge((commentId, contextOffset) -> "[]", reply -> "{}", resolution -> "{}", LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .developments(taskId -> Optional.of(new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())))
                .localReviews(taskId -> Optional.of(local)).build();
        Map<String, String> environment = apiKey ? ImmutableMap.of("ANTHROPIC_API_KEY", "test-api-key") : ImmutableMap.of();
        ClaudeCliDriver driver = new ClaudeCliDriver("/fake/claude", objectMapper, broker, supervisor, claudeHome(), environment);
        AgentSessionRef reference;
        try (AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
            await(session.start(command("local-review")));
            reference = session.reference();
        }
        try (AgentSession session = driver.open(context(Optional.of(reference)), event -> {})) {
            await(session.start(command("local-review-again")));
        }
        for (ScriptedTurn turn : supervisor.turns()) {
            List<String> argv = turn.spec().argv();
            assertThat(argv).doesNotContain("--disable-slash-commands", "--tools");
            assertThat(argv.get(argv.indexOf("--allowedTools") + 1).split(",")).containsExactlyInAnyOrder(
                    "mcp__bytequay__" + LocalReviewBridge.LIST_TOOL,
                    "mcp__bytequay__" + LocalReviewBridge.REPLY_TOOL,
                    "mcp__bytequay__" + LocalReviewBridge.RESOLVE_TOOL,
                    "mcp__bytequay__" + DevelopmentResultBridge.TOOL_NAME,
                    "mcp__bytequay__" + DevelopmentResultBridge.PUBLISH_TOOL_NAME,
                    "mcp__bytequay__bytequay_list_tools", "mcp__bytequay__bytequay_get_tool", "mcp__bytequay__bytequay_call_tool");
            assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1)).contains(LocalReviewBridge.INSTRUCTION, DevelopmentResultBridge.INSTRUCTION);
        }
        driver.close();
    }

    @Test
    void privateReviewPolicyNeedsNoReportingToolsOnInitialOrResumedSessions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "Keep private experiments uncommitted");
        try (ClaudeCliDriver driver = driver(supervisor, taskId -> Optional.of(review))) {
            Optional<AgentSessionRef> reference = Optional.empty();
            for (int index = 0; index < 2; index++) {
                try (AgentSession session = driver.open(context(reference), event -> {})) {
                    await(session.start(command("private review")));
                    reference = Optional.of(session.reference());
                }
                List<String> argv = supervisor.turns().getLast().spec().argv();
                assertThat(argv).contains("--disable-slash-commands");
                assertThat(argv).containsSequence("--permission-mode", "auto");
                assertThat(argv).containsSequence("--append-system-prompt", AgentCommandPolicy.reviewInstructions() + "\n\n" + ClaudeCliDriver.PLAN_INSTRUCTION + "\n\n" + review.instruction());
                assertThat(argv).doesNotContain("--allowedTools");
                assertThat(argv.get(argv.indexOf("--tools") + 1).split(","))
                        .contains("Bash", "Read", "Edit", "Write")
                        .doesNotContain("Agent", "Task", "Skill", "Workflow");
                assertThat(supervisor.turns().getLast().spec().environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN");
            }
        }
    }

    @Test
    void restoresDevelopmentScopeOnAFormerlyRestrictedSession()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        AtomicReference<Optional<ReviewBridge>> review = new AtomicReference<>(Optional.of(new ReviewBridge(ImmutableList.of(), "Keep experiments uncommitted")));
        try (ClaudeCliDriver driver = driver(supervisor, taskId -> review.get())) {
            AgentSessionRef reference;
            try (AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
                await(session.start(command("Review this change")));
                reference = session.reference();
            }
            review.set(Optional.empty());
            try (AgentSession session = driver.open(context(Optional.of(reference)), event -> {})) {
                assertThat(session.reference()).isEqualTo(reference);
                await(session.start(command("Push the changes and update the PR description")));
            }
            List<String> argv = supervisor.turns().getLast().spec().argv();
            assertThat(argv).doesNotContain("--disable-slash-commands", "--tools");
            assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1))
                    .contains(DEVELOPMENT_POLICY, "replaces earlier ByteQuay private-review restrictions")
                    .doesNotContain(AgentCommandPolicy.reviewInstructions(), "Keep experiments uncommitted");
        }
    }

    @Test
    void offersReviewChildrenOnlyOnAReleaseWithTheVerifiedGateInput()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "Review this PR");
        for (String version : ImmutableList.of("2.1.277", "2.1.278")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
            supervisor.versionOutput = version + " (Claude Code)";
            try (ClaudeCliDriver driver = driver(supervisor, taskId -> Optional.of(review))) {
                assertThat(driver.reviewChildAgents()).isFalse();
                driver.probe();
                boolean verified = version.equals("2.1.278");
                assertThat(driver.reviewChildAgents()).isEqualTo(verified);
                try (AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
                    await(session.start(command("review")));
                }
                List<String> argv = supervisor.turns().getLast().spec().argv();
                String[] nativeTools = argv.get(argv.indexOf("--tools") + 1).split(",");
                assertThat(nativeTools).doesNotContain("Task", "Skill", "Workflow", "SendMessage");
                assertThat(ImmutableList.copyOf(nativeTools).contains("Agent")).isEqualTo(verified);
            }
        }
    }

    @Test
    void readsTheCostAsTheSessionsTotalOnlyFromAReleaseThatCarriesItAcrossAResume()
            throws Exception
    {
        for (String version : ImmutableList.of("2.1.276", "2.1.277")) {
            ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
            supervisor.versionOutput = version + " (Claude Code)";
            List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
            try (ClaudeCliDriver driver = driver(supervisor)) {
                driver.probe();
                try (AgentSession session = driver.open(context(Optional.empty()), events::add)) {
                    await(session.start(command("first")));
                }
            }
            assertThat(events).filteredOn(ProviderAgentEvent.Usage.class::isInstance).singleElement()
                    .extracting(event -> ((ProviderAgentEvent.Usage) event).sample().semantics())
                    .isEqualTo(version.equals("2.1.277") ? ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST : ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION);
        }
    }

    @Test
    void restrictsNativeReviewToolsOnInitialAndResumedSessions()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_finish", "Finish review", ImmutableMap.of("type", "object"), arguments -> "{}"),
                new ReviewTool("review_extra_evidence", "Read more evidence", ImmutableMap.of("type", "object"), arguments -> "{}")), "Review this PR");
        ClaudeCliDriver driver = driver(supervisor, taskId -> Optional.of(review));
        AgentSession session = driver.open(context(Optional.empty(), "Keep explanations concise"), event -> {});
        await(session.start(command("review")));
        AgentSessionRef reference = session.reference();
        session.close();
        AgentSession resumed = driver.open(context(Optional.of(reference), "Keep explanations concise"), event -> {});
        await(resumed.start(command("review-again")));

        for (ScriptedTurn turn : supervisor.turns()) {
            List<String> argv = turn.spec().argv();
            assertThat(argv).contains("--disable-slash-commands");
            assertThat(argv).containsSequence("--permission-mode", "auto");
            assertThat(argv.get(argv.indexOf("--append-system-prompt") + 1)).contains(AgentCommandPolicy.reviewInstructions(),
                    ClaudeCliDriver.PLAN_INSTRUCTION, "Keep explanations concise", "Review this PR", "bytequay_list_tools", "bytequay_get_tool");
            assertThat(argv.stream().filter("--append-system-prompt"::equals)).hasSize(1);
            String nativeTools = argv.get(argv.indexOf("--tools") + 1);
            assertThat(nativeTools.split(",")).contains("Bash", "Read", "TaskOutput", "TaskStop")
                    .doesNotContain("Agent", "Task", "Skill", "Workflow", "SendMessage", "RemoteTrigger", "ScheduleWakeup");
            assertThat(argv.get(argv.indexOf("--allowedTools") + 1).split(","))
                    .containsExactly("mcp__bytequay__review_finish", "mcp__bytequay__review_extra_evidence",
                            "mcp__bytequay__bytequay_list_tools", "mcp__bytequay__bytequay_get_tool", "mcp__bytequay__bytequay_call_tool");
        }
        resumed.close();
        driver.close();
    }

    @Test
    void resolvesAnObservationGapAfterConfirmedClaudeWorkFinishes()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command("finished-gap")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(supervisor.turns().getFirst().workFinished).isTrue();
        session.close();
        driver.close();
    }

    @ParameterizedTest
    @CsvSource({
            "finished-live-process, COMPLETED, authoritative [REDACTED],",
            "failed-live-process, FAILED,, Session limit reached"})
    void preservesClaudeResultWhenKnownProcessCleanupStillFails(String prompt, AgentTurnResult.State state, String finalResponse, String terminalReason)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command(prompt)));

        assertThat(result.state()).isEqualTo(state);
        assertThat(result.finalResponse()).isEqualTo(Optional.ofNullable(finalResponse));
        assertThat(result.terminalReason()).isEqualTo(Optional.ofNullable(terminalReason));
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(supervisor.turns().getFirst().workFinished).isTrue();
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed-active-tool", "failed-malformed-stream"})
    void doesNotUseAFailedResultToHideUnfinishedOrUncertainWork(String prompt)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command(prompt)));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(supervisor.turns().getFirst().workFinished).isFalse();
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void retainsTheAnswerWhenTheAsynchronousCompletionReceiptFails()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        RunningTurn running = session.start(command("finished-delayed"));

        assertThat(running.completion().toCompletableFuture()).isNotDone();
        supervisor.turns().getFirst().finishedStop.completeExceptionally(new IllegalStateException("receipt write failed"));
        AgentTurnResult result = await(running);

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(result.finalResponse()).contains("authoritative [REDACTED]");
        assertThat(result.providerQuiescent()).isFalse();
        assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void doesNotUseAnIncompleteSubagentStreamAsCompletionProof()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.versionOutput = "2.1.218 (Claude Code)";
        ClaudeCliDriver driver = driver(supervisor);
        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.DEGRADED);
        assertThat(driver.capabilities().nestedAgentActivity()).isFalse();
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command("finished-gap")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(supervisor.turns().getFirst().workFinished).isFalse();
        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void doesNotRecordFinishedWorkWhileABackgroundTaskHasNoTerminalNotification()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});

        AgentTurnResult result = await(session.start(command("unfinished-background")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(supervisor.turns().getFirst().workFinished).isFalse();
        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void closingASessionWaitsForItsLiveProcessTreeToStop()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        RunningTurn running = session.start(command("cancel"));

        session.close();

        AgentTurnResult result = await(running);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(supervisor.turns()).singleElement().satisfies(turn -> assertThat(turn.cancelled()).isTrue());
        driver.close();
    }

    @Test
    void removesOnlyTheEmptyClaudeScratchAfterVerifiedProcessExit()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));

        AgentTurnResult result = await(session.start(command("scratch-cleanup")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(scratch).doesNotExist();
        assertThat(claudeDirectory).doesNotExist();
    }

    @Test
    void leavesClaudeFilesNonemptyDirectoriesAndSymlinksUntouched()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));
        Path stagedWrite = Files.writeString(scratch.resolve("pending"), "keep me\n");
        Path settings = Files.writeString(claudeDirectory.resolve("settings.json"), "{}\n");

        assertThat(await(session.start(command("nonempty-scratch"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(stagedWrite).hasContent("keep me\n");
        assertThat(settings).hasContent("{}\n");

        Files.delete(stagedWrite);
        Files.delete(scratch);
        Path scratchFile = Files.writeString(claudeDirectory.resolve(".cc-writes"), "not a directory\n");
        assertThat(await(session.start(command("scratch-file"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(scratchFile).hasContent("not a directory\n");

        Files.delete(scratchFile);
        Path outside = Files.createDirectory(worktree.resolve("outside-scratch"));
        Path scratchLink = Files.createSymbolicLink(claudeDirectory.resolve(".cc-writes"), outside);
        assertThat(await(session.start(command("scratch-symlink"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(Files.isSymbolicLink(scratchLink)).isTrue();
        assertThat(outside).isDirectory();

        Files.delete(scratchLink);
        Files.delete(settings);
        Files.delete(claudeDirectory);
        Path outsideClaude = Files.createDirectories(worktree.resolve("outside-claude/.cc-writes"));
        Path claudeLink = Files.createSymbolicLink(worktree.resolve(".claude"), outsideClaude.getParent());
        assertThat(await(session.start(command("claude-symlink"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(Files.isSymbolicLink(claudeLink)).isTrue();
        assertThat(outsideClaude).isDirectory();
    }

    @Test
    void failsActionablyWhenEmptyClaudeScratchCannotBeRemoved()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));
        Files.setPosixFilePermissions(claudeDirectory, PosixFilePermissions.fromString("r-x------"));

        try {
            AgentTurnResult result = await(session.start(command("scratch-cleanup-failure")));

            assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("could not remove Claude's empty .claude/.cc-writes")
                    .contains("worktree was retained"));
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(scratch).isDirectory();
            assertThat(worktree).isDirectory();
        }
        finally {
            Files.setPosixFilePermissions(claudeDirectory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvalTimeoutStopsItsTurnWhileAnExplicitDenialAllowsContinuation(boolean timedOut)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        ClaudeCliDriver driver = new ClaudeCliDriver("/fake/claude", objectMapper, broker, supervisor, claudeHome(), ImmutableMap.of());
        AgentSessionContext plain = context(Optional.empty());
        AgentSessionContext context = new AgentSessionContext(
                plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(),
                plain.sessionReference(), (request, timeout) -> CompletableFuture.completedFuture(timedOut ? ProviderApprovalDecision.expired() : ProviderApprovalDecision.deny()),
                Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), "");
        AgentSession session = driver.open(context, event -> {});
        RunningTurn turn = session.start(command("cancel"));
        JsonNode config = objectMapper.readTree(Files.readString(runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json")));
        String authorization = config.at("/mcpServers/bytequay/headers/Authorization").asText();
        JsonNode response = broker.handle(authorization.substring("Bearer ".length()), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"permission_prompt","arguments":{"tool_name":"Bash","input":{"command":"echo pending"}}}}
                """)).body().orElseThrow();
        JsonNode permission = objectMapper.readTree(response.at("/result/content/0/text").asText());
        assertThat(permission.path("behavior").asText()).isEqualTo("deny");
        assertThat(permission.path("message").asText()).isEqualTo(timedOut ? "Approval timed out" : "Denied by user");
        if (timedOut) {
            AgentTurnResult result = await(turn);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.terminalReason()).contains("Claude approval timed out");
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(supervisor.turns().getFirst().cancelled()).isTrue();
            assertThat(supervisor.turns().getFirst().workFinished).isFalse();
        }
        else {
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            turn.cancel();
            assertThat(await(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
        session.close();
        AgentSession resumed = driver.open(context(Optional.of(session.reference())), event -> {});
        assertThat(await(resumed.start(command("next"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.turns().getLast().spec().argv()).containsSequence("--resume", session.reference().providerValue());
        resumed.close();
    }

    @Test
    void anOldApprovalExpiryCannotCancelTheNextClaudeTurn()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        ClaudeCliDriver driver = new ClaudeCliDriver("/fake/claude", objectMapper, broker, supervisor, claudeHome(), ImmutableMap.of());
        CompletableFuture<ProviderApprovalDecision> decision = new CompletableFuture<>();
        CountDownLatch requested = new CountDownLatch(1);
        AgentSessionContext plain = context(Optional.empty());
        AgentSessionContext context = new AgentSessionContext(
                plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(),
                plain.sessionReference(), (request, timeout) -> {
                    requested.countDown();
                    return decision;
                }, Optional.empty(), Optional.empty(), SessionAccess.FULL, Optional.empty(), "");
        try (AgentSession session = driver.open(context, event -> {})) {
            RunningTurn first = session.start(command("cancel"));
            JsonNode config = objectMapper.readTree(Files.readString(runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json")));
            String token = config.at("/mcpServers/bytequay/headers/Authorization").asText().substring("Bearer ".length());
            JsonNode request = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                    "name":"permission_prompt","arguments":{"tool_name":"Bash","input":{"command":"echo stale"}}}}
                    """);
            CompletableFuture<McpHttpResponse> response = CompletableFuture.supplyAsync(() -> broker.handle(token, request));
            assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
            first.cancel();
            assertThat(await(first).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            RunningTurn next = session.start(new AgentTurnCommand("next", "cancel", ImmutableList.of(), Duration.ofMinutes(1)));
            decision.complete(ProviderApprovalDecision.expired());
            JsonNode permission = objectMapper.readTree(response.get(5, TimeUnit.SECONDS).body().orElseThrow().at("/result/content/0/text").asText());
            assertThat(permission.path("behavior").asText()).isEqualTo("deny");
            assertThat(next.completion().toCompletableFuture()).isNotDone();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unlimitedTurnsStillSupportCancellation(boolean streaming)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        if (streaming) {
            supervisor.versionOutput("2.1.295");
        }
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
            driver.probe();
            RunningTurn turn = session.start(new AgentTurnCommand("unlimited", "cancel", ImmutableList.of(), Duration.ZERO));
            assertThat(supervisor.turns().getFirst().spec().maximumRuntime()).isEqualTo(Duration.ZERO);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            turn.cancel();
            AgentTurnResult result = await(turn);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            assertThat(result.providerQuiescent()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeTimeoutReportsInterruptionWithoutFabricatingFinishedWork(boolean streaming)
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        if (streaming) {
            supervisor.versionOutput("2.1.295");
        }
        try (ClaudeCliDriver driver = driver(supervisor);
                AgentSession session = driver.open(context(Optional.empty()), event -> {})) {
            assertThat(driver.probe().capabilities().steering()).isEqualTo(streaming);
            RunningTurn turn = session.start(command("cancel"));
            supervisor.turns().getFirst().exit.complete(new ProcessExit(
                    143, ProcessExit.Reason.TIMED_OUT, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMinutes(1), false, false));
            AgentTurnResult result = await(turn);
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.terminalReason()).contains("Claude turn timed out");
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(supervisor.turns().getFirst().workFinished).isFalse();
        }
    }

    private AgentSessionContext accountContext(String repository, Optional<AgentSessionRef> reference)
            throws IOException
    {
        AgentSessionContext base = context(reference);
        Path checkout = Files.createDirectories(worktree.resolve(repository).resolve("task-worktree"));
        Path runtime = Files.createDirectories(worktree.resolve(repository).resolve("runtime"));
        return new AgentSessionContext(repository, checkout, runtime, runtime.resolve("provider"), base.permissionPolicy(), reference,
                base.approvals(), base.model(), base.effort(), base.access(), base.codeQueries(), base.managedInstructions());
    }

    private Path claudeHome()
    {
        return worktree.resolve("claude-home");
    }

    private ClaudeCliDriver driver(ProcessSupervisor supervisor)
    {
        return driver(supervisor, taskId -> Optional.empty());
    }

    private ClaudeCliDriver driver(ProcessSupervisor supervisor, BridgeResolver<ReviewBridge> reviews)
    {
        PermissionBroker permissions = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(reviews).build();
        return new ClaudeCliDriver(
                "/fake/claude",
                objectMapper,
                permissions,
                supervisor,
                claudeHome(),
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "ANTHROPIC_API_KEY", "known-secret",
                        "GH_TOKEN", "github-sentinel",
                        "GH_CONFIG_DIR", worktree.resolve("gh-config").toString(),
                        "CLAUDE_CONFIG_DIR", "/user/own/.claude"));
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return context(reference, AgentPermissionPolicy.AUTO);
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, String managedInstructions)
    {
        AgentSessionContext plain = context(reference);
        return new AgentSessionContext(
                plain.taskId(), plain.worktree(), plain.runtimeDirectory(), plain.providerDirectory(), plain.permissionPolicy(),
                plain.sessionReference(), plain.approvals(), plain.model(), plain.effort(), plain.access(), plain.codeQueries(), managedInstructions);
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AgentPermissionPolicy permissionPolicy)
    {
        return context(reference, permissionPolicy, SessionAccess.FULL);
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, AgentPermissionPolicy permissionPolicy, SessionAccess access)
    {
        return context(reference, permissionPolicy, Optional.empty(), Optional.empty(), access);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy,
            Optional<String> model,
            Optional<String> effort,
            SessionAccess access)
    {
        Path sessionWorktree = access == SessionAccess.TEXT_ONLY ? runtimeDirectory().resolve("text-worktree") : worktree;
        return new AgentSessionContext(
                "task-1",
                sessionWorktree,
                runtimeDirectory(),
                runtimeDirectory().resolve("provider/scratch"),
                permissionPolicy,
                reference,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                model,
                effort,
                access,
                Optional.empty(),
                "");
    }

    private Path runtimeDirectory()
    {
        return worktree.resolve("task-runtime");
    }

    private static AgentTurnCommand command(String message)
    {
        return command(message, ImmutableList.of());
    }

    private static AgentTurnCommand command(String message, List<AgentAttachment> attachments)
    {
        return new AgentTurnCommand("turn-" + message, message, attachments, Duration.ofMinutes(1));
    }

    private static AgentTurnResult await(RunningTurn turn)
            throws Exception
    {
        return turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static int indexOf(List<ProviderAgentEvent> events, Predicate<ProviderAgentEvent> predicate)
    {
        for (int index = 0; index < events.size(); index++) {
            if (predicate.test(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
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

        private final List<ScriptedTurn> turns = new CopyOnWriteArrayList<>();
        private final List<ProcessSpec> specs = new CopyOnWriteArrayList<>();
        private final List<String> sandboxSettings = new CopyOnWriteArrayList<>();
        private String versionOutput = "2.1.233 (Claude Code)";
        private String helpOutput = """
                --output-format --input-format --include-partial-messages --include-hook-events
                --forward-subagent-text --permission-mode (choices: \"auto\" \"manual\") --mcp-config
                --settings --setting-sources --strict-mcp-config --add-dir --append-system-prompt
                --tools --no-session-persistence --disable-slash-commands --model --effort
                --allowedTools --permission-prompt-tool --print --verbose --resume --session-id
                """;
        private String authenticationStdout = "{\"loggedIn\":true}";
        private String autoModeDefaults = "{\"hard_deny\":[]}";
        private String autoModeStderr = "";
        private int autoModeExitCode;
        private boolean autoModeTimeout;
        private String authenticationStderr = "";
        private int authenticationExitCode;
        private int authenticationProbes;
        private boolean authenticationTimeout;
        private boolean authenticationHostWaitTimeout;
        private boolean authenticationTruncated;
        private boolean probeTreeUnverifiable;
        private final List<String> acknowledgements = new CopyOnWriteArrayList<>();
        private String runtimeSecret = "";
        private boolean failNextPromptWrite;
        private boolean failNextPromptClose;

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            specs.add(spec);
            int settingsIndex = spec.argv().indexOf("--settings");
            if (settingsIndex >= 0) {
                try {
                    sandboxSettings.add(Files.readString(Path.of(spec.argv().get(settingsIndex + 1))));
                }
                catch (IOException failure) {
                    throw new AssertionError("Could not capture the turn's sandbox settings", failure);
                }
            }
            return new ScriptedLaunch(spec);
        }

        private List<ProcessSpec> specs()
        {
            return specs;
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
            if (spec.argv().contains("--version")) {
                assertThat(spec.stdoutCaptureLimit()).isPresent();
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return probeTreeUnverifiable ? ImmediateProcess.unverifiable() : ImmediateProcess.success();
            }
            if (spec.argv().contains("--help")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(helpOutput));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("auto-mode") && spec.argv().contains("defaults")) {
                ByteBuffer stdout = StandardCharsets.UTF_8.encode(autoModeDefaults);
                ByteBuffer stderr = StandardCharsets.UTF_8.encode(autoModeStderr);
                int stdoutLimit = spec.stdoutCaptureLimit().orElseThrow();
                boolean stdoutTruncated = stdout.remaining() > stdoutLimit;
                boolean stderrTruncated = stderr.remaining() > spec.maximumStderrBytes();
                stdout.limit(Math.min(stdout.remaining(), stdoutLimit));
                stderr.limit(Math.min(stderr.remaining(), spec.maximumStderrBytes()));
                output.onOutput(ProcessStream.STDOUT, stdout);
                output.onOutput(ProcessStream.STDERR, stderr);
                return new ImmediateProcess(autoModeExitCode, autoModeTimeout ? ProcessExit.Reason.TIMED_OUT : ProcessExit.Reason.EXITED, stdoutTruncated, stderrTruncated);
            }
            if (spec.argv().contains("auth") && spec.argv().contains("status")) {
                authenticationProbes++;
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(authenticationStdout));
                output.onOutput(ProcessStream.STDERR, StandardCharsets.UTF_8.encode(authenticationStderr));
                if (authenticationHostWaitTimeout) {
                    return ImmediateProcess.hostWaitTimedOut();
                }
                if (authenticationTimeout) {
                    return ImmediateProcess.timedOut();
                }
                if (authenticationTruncated) {
                    return ImmediateProcess.stdoutTruncated(authenticationExitCode);
                }
                return ImmediateProcess.exited(authenticationExitCode);
            }
            ScriptedTurn turn = new ScriptedTurn(spec, output, failNextPromptWrite, failNextPromptClose, runtimeSecret);
            failNextPromptWrite = false;
            failNextPromptClose = false;
            turns.add(turn);
            return turn;
        }

        private List<ScriptedTurn> turns()
        {
            return turns;
        }

        private void versionOutput(String output)
        {
            versionOutput = output;
        }

        private void leaveProbeTreeUnverifiable()
        {
            probeTreeUnverifiable = true;
        }

        private String helpOutput()
        {
            return helpOutput;
        }

        private void helpOutput(String output)
        {
            helpOutput = output;
        }

        private void authentication(String stdout, String stderr, int exitCode)
        {
            authenticationStdout = stdout;
            authenticationStderr = stderr;
            authenticationExitCode = exitCode;
            authenticationTimeout = false;
            authenticationHostWaitTimeout = false;
            authenticationTruncated = false;
        }

        private void authenticationTimeout()
        {
            authenticationTimeout = true;
        }

        private void authenticationHostWaitTimeout()
        {
            authenticationStdout = "{\"loggedIn\":false}";
            authenticationTimeout = false;
            authenticationHostWaitTimeout = true;
        }

        private void authenticationTruncated()
        {
            authenticationStdout = "{\"loggedIn\":true}";
            authenticationExitCode = 0;
            authenticationTimeout = false;
            authenticationHostWaitTimeout = false;
            authenticationTruncated = true;
        }

        private int authenticationProbes()
        {
            return authenticationProbes;
        }

        private void runtimeSecret(String secret)
        {
            runtimeSecret = secret;
        }

        private void failNextPromptWrite()
        {
            failNextPromptWrite = true;
        }

        private void failNextPromptClose()
        {
            failNextPromptClose = true;
        }
    }

    private static final class ScriptedTurn
            implements ManagedProcess
    {
    @Override
        public Optional<List<ProcessIdentity>> observedDescendants()
        {
            return Optional.empty();
        }

        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final boolean failPromptWrite;
        private final boolean failPromptClose;
        private final String runtimeSecret;
        private ProcessExit.TreeTermination nextStopVerification;
        private String prompt = "";
        private boolean cancelled;
        private boolean inputClosed;
        private boolean workFinished;
        private boolean completionVerified;
        private final CompletableFuture<ProcessExit.TreeTermination> finishedStop = new CompletableFuture<>();
        private final List<JsonNode> inputs = new CopyOnWriteArrayList<>();
        private final CompletableFuture<JsonNode> steeringInput = new CompletableFuture<>();
        private boolean acknowledgeInput = true;
        private boolean failSteerWrite;

        private ScriptedTurn(ProcessSpec spec, ProcessOutputSink output, boolean failPromptWrite, boolean failPromptClose, String runtimeSecret)
        {
            this.spec = spec;
            this.output = output;
            this.failPromptWrite = failPromptWrite;
            this.failPromptClose = failPromptClose;
            this.runtimeSecret = runtimeSecret;
        }

        private ProcessSpec spec()
        {
            return spec;
        }

        private String prompt()
        {
            return prompt;
        }

        private boolean cancelled()
        {
            return cancelled;
        }

        private boolean inputClosed()
        {
            return inputClosed;
        }

        private void verifyNextStop()
        {
            nextStopVerification = ProcessExit.TreeTermination.VERIFIED_STOPPED;
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
            if (failPromptWrite || failSteerWrite) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated prompt write failure"));
            }
            if (spec.argv().contains("--input-format")) {
                try {
                    JsonNode input = new ObjectMapper().readTree(StandardCharsets.UTF_8.decode(bytes).toString());
                    inputs.add(input);
                    if (acknowledgeInput) {
                        inputLifecycle(inputs.size() - 1, "queued");
                    }
                    if (inputs.size() > 1) {
                        steeringInput.complete(input);
                    }
                    return CompletableFuture.completedFuture(null);
                }
                catch (IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            prompt += StandardCharsets.UTF_8.decode(bytes).toString();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput()
        {
            inputClosed = true;
            if (failPromptClose) {
                throw new IllegalStateException("simulated prompt close failure");
            }
            if (spec.argv().contains("--input-format")) {
                return;
            }
            if (prompt.equals("cancel\n")) {
                return;
            }
            if (prompt.equals("fail\n")) {
                emit(ProcessStream.STDERR, "known-secret diagnostic" + (runtimeSecret.isEmpty() ? "" : " " + runtimeSecret) + "\n");
                exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
                return;
            }
            if (prompt.startsWith("failed-")) {
                completeFailedTurn();
                return;
            }
            String sessionId = sessionId();
            String runtimeSecretSuffix = runtimeSecret.isEmpty() ? "" : " " + runtimeSecret;
            if (!prompt.startsWith("finished-") && !prompt.equals("unfinished-background\n")) {
                emit(ProcessStream.STDOUT, "not-json\n");
            }
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"%s\","
                            .formatted(sessionId)
                            + "\"uuid\":\"init-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                            + "\"index\":0,\"delta\":{\"type\":\"text_delta\","
                            + "\"text\":\"draft known-secret\"}},\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"parent_tool_use_id\":null,\"uuid\":\"delta-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                            + "\"index\":0,\"delta\":{\"type\":\"text_delta\","
                            + "\"text\":\" still drafting\"}},\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"parent_tool_use_id\":null,\"uuid\":\"delta-2\"}\n");
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                            + "{\"type\":\"text\",\"text\":\"authoritative known-secret%s\"}]},"
                            + "\"parent_tool_use_id\":null,\"session_id\":\"%s\","
                            + "\"uuid\":\"answer-1\"}\n")
                            .formatted(runtimeSecretSuffix, sessionId));
            if (prompt.equals("unfinished-background\n")) {
                emit(ProcessStream.STDOUT, ("{\"type\":\"system\",\"subtype\":\"task_started\",\"session_id\":\"%s\","
                        + "\"task_id\":\"background-1\",\"task_type\":\"local_bash\",\"description\":\"Still working\",\"uuid\":\"task-1\"}\n")
                        .formatted(sessionId));
            }
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"result\",\"is_error\":false,\"duration_ms\":10,"
                            + "\"duration_api_ms\":8,\"num_turns\":1,\"session_id\":\"%s\","
                            + "\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":2,"
                            + "\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":4,"
                            + "\"output_tokens\":5},\"subtype\":\"success\","
                            + "\"result\":\"authoritative known-secret%s\","
                            + "\"terminal_reason\":\"completed\",\"uuid\":\"result-1\"}\n")
                            .formatted(sessionId, runtimeSecretSuffix));
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"system\",\"subtype\":\"hook_response\","
                            + "\"hook_id\":\"hook-1\",\"hook_name\":\"Stop\","
                            + "\"hook_event\":\"Stop\",\"output\":\"trailing event%s\","
                            + "\"exit_code\":0,\"outcome\":\"success\",\"session_id\":\"%s\","
                            + "\"uuid\":\"trailing-1\"}\n")
                            .formatted(runtimeSecretSuffix, sessionId));
            emit(ProcessStream.STDERR, "known-secret warning" + runtimeSecretSuffix + "\n");
            exit.complete(new ProcessExit(
                    0,
                    ProcessExit.Reason.EXITED,
                    prompt.equals("unverifiable\n") || prompt.startsWith("finished-") || prompt.equals("unfinished-background\n")
                            ? ProcessExit.TreeTermination.UNVERIFIABLE
                            : ProcessExit.TreeTermination.VERIFIED_STOPPED,
                    Duration.ofMillis(10),
                    false,
                    false));
        }

        private void inputLifecycle(int index, String state)
        {
            emit(ProcessStream.STDOUT, """
                    {"type":"command_lifecycle","command_uuid":"%s","state":"%s","session_id":"%s"}
                    """.formatted(inputs.get(index).path("uuid").asText(), state, sessionId()));
        }

        private void streamingResult(String text)
        {
            emit(ProcessStream.STDOUT, """
                    {"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":0,"num_turns":1,"session_id":"%s",
                    "usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},
                    "subtype":"success","queued_turn_count":0,"result":"%s","uuid":"result-%d"}
                    """.formatted(sessionId(), text, inputs.size()).replace("\n", "") + "\n");
        }

        private void completeFailedTurn()
        {
            if (prompt.equals("failed-active-tool\n")) {
                emit(ProcessStream.STDOUT, """
                        {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash",
                        "name":"Bash","input":{"command":"sleep 10"}}]},"session_id":"%s","uuid":"bash-start"}
                        """.formatted(sessionId()).replace("\n", "") + "\n");
            }
            if (prompt.equals("failed-malformed-stream\n")) {
                emit(ProcessStream.STDOUT, "not-json\n");
            }
            emit(ProcessStream.STDOUT, """
                    {"type":"result","is_error":true,"duration_ms":1,"duration_api_ms":0,"num_turns":1,"session_id":"%s",
                    "usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},
                    "subtype":"error_during_execution","errors":["Session limit reached"],"uuid":"result"}
                    """.formatted(sessionId()).replace("\n", "") + "\n");
            exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.UNVERIFIABLE, Duration.ofMillis(1), false, false));
        }

        private String sessionId()
        {
            int option = spec.argv().indexOf("--session-id");
            if (option < 0) {
                option = spec.argv().indexOf("--resume");
            }
            return spec.argv().get(option + 1);
        }

        private void emit(ProcessStream stream, String text)
        {
            output.onOutput(stream, StandardCharsets.UTF_8.encode(text));
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
        {
            workFinished = true;
            if (prompt.equals("finished-delayed\n")) {
                return finishedStop;
            }
            completionVerified = prompt.equals("finished-gap\n");
            return stopAndVerify();
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancelled = true;
            ProcessExit terminal = exit.getNow(null);
            ProcessExit.TreeTermination verification = nextStopVerification == null
                    ? terminal == null || completionVerified
                            ? ProcessExit.TreeTermination.VERIFIED_STOPPED
                            : terminal.treeTermination()
                    : nextStopVerification;
            nextStopVerification = null;
            if (terminal == null) {
                exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, verification, Duration.ofMillis(5), false, false));
            }
            return CompletableFuture.completedFuture(verification);
        }

        @Override
        public void close() {}
    }

    private static final class ImmediateProcess
            implements ManagedProcess
    {
    @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
        {
            return stopAndVerify();
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancel();
            return exit().thenApply(ProcessExit::treeTermination);
        }

        @Override
        public Optional<List<ProcessIdentity>> observedDescendants()
        {
            return Optional.empty();
        }

        private final CompletableFuture<ProcessExit> exit;

        private ImmediateProcess(CompletableFuture<ProcessExit> exit)
        {
            this.exit = exit;
        }

        private ImmediateProcess(int exitCode, ProcessExit.Reason reason, boolean stdoutTruncated, boolean stderrTruncated)
        {
            exit = CompletableFuture.completedFuture(
                    new ProcessExit(exitCode, reason, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, stdoutTruncated, stderrTruncated));
        }

        private static ImmediateProcess success()
        {
            return exited(0);
        }

        private static ImmediateProcess unverifiable()
        {
            return new ImmediateProcess(CompletableFuture.completedFuture(
                    new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.UNVERIFIABLE, Duration.ZERO, false, false)));
        }

        private static ImmediateProcess exited(int exitCode)
        {
            return new ImmediateProcess(exitCode, ProcessExit.Reason.EXITED, false, false);
        }

        private static ImmediateProcess stdoutTruncated(int exitCode)
        {
            return new ImmediateProcess(exitCode, ProcessExit.Reason.EXITED, true, false);
        }

        private static ImmediateProcess timedOut()
        {
            return new ImmediateProcess(143, ProcessExit.Reason.TIMED_OUT, false, false);
        }

        private static ImmediateProcess hostWaitTimedOut()
        {
            return new ImmediateProcess(new CompletableFuture<>()
            {
                @Override
                public ProcessExit get(long timeout, TimeUnit unit)
                        throws TimeoutException
                {
                    throw new TimeoutException("host wait timed out");
                }
            });
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
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }
}
