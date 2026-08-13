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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator.TaskInspectionToolCapability;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator.TaskToolContext;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WakeKind;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor.CiLearningToolCapability;
import com.bytequay.app.flow.runtime.InitialTaskCoordinator.InitialToolCapability;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.CI_LEARNER;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.CI_REPAIR;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.TASK_CI_FIX;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.TASK_INITIAL;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.TASK_INITIAL_REVIEW_RESULT;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.UPSTREAM_PICK_REPAIR;
import static com.bytequay.app.service.agents.TurnSpec.Transport.ANTHROPIC;
import static com.bytequay.app.service.agents.TurnSpec.Transport.OPENAI_COMPAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class TestNewFlowAgentRuntimeBoundaries
{
    private static final Instant NOW = Instant.parse(
            "2026-08-12T10:15:30.123456789Z");
    private static final String HEAD = "a".repeat(40);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void replaysTheStoredLaunchAfterConfigDriftAndPinsNanoseconds()
    {
        DataSource dataSource = dataSource();
        FlowRuntime runtime = mock(FlowRuntime.class);
        CredentialStore credentials = mock(CredentialStore.class);
        AgentRun run = ciRun();
        Credential credential = credential(NOW);
        when(runtime.run(run.runId())).thenReturn(Optional.of(run));
        when(credentials.find(
                CredentialType.AI, "anthropic", "ci"))
                .thenReturn(Optional.of(credential));

        NewFlowAgentLaunches.Binding first = launches(
                dataSource, runtime, credentials, "first-model")
                .bind(run, CI_REPAIR);
        NewFlowAgentLaunches.Binding replay = launches(
                dataSource, runtime, credentials, "drifted-model")
                .bind(run, CI_REPAIR);

        assertThat(replay).isEqualTo(first);
        assertThat(replay.model()).isEqualTo("first-model");
        assertThat(replay.credentialUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsSameMillisecondCredentialRotationBeforeSecretRead()
    {
        DataSource dataSource = dataSource();
        FlowRuntime runtime = mock(FlowRuntime.class);
        CredentialStore credentials = mock(CredentialStore.class);
        AgentRun run = ciRun();
        Credential original = credential(NOW);
        Credential rotated = credential(NOW.plusNanos(1));
        when(runtime.run(run.runId())).thenReturn(Optional.of(run));
        when(credentials.find(
                CredentialType.AI, "anthropic", "ci"))
                .thenReturn(Optional.of(original));
        when(credentials.getSecret(
                CredentialType.AI, "anthropic", "ci"))
                .thenReturn(Optional.of("secret"));
        NewFlowAgentLaunches launches = launches(
                dataSource, runtime, credentials, "first-model");
        NewFlowAgentLaunches.Binding binding = launches.bind(run, CI_REPAIR);
        when(credentials.find(
                CredentialType.AI, "anthropic", "ci"))
                .thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> launches.resolveSecret(binding))
                .isInstanceOf(
                        NewFlowAgentLaunches.LaunchUnavailableException.class)
                .hasMessage("bound AI credential revision changed");
        verify(credentials, never()).getSecret(
                CredentialType.AI, "anthropic", "ci");
    }

    @Test
    void aCliLaunchBindsWithoutACredentialAndIsRefusedTheInJvmPath()
    {
        DataSource dataSource = dataSource();
        FlowRuntime runtime = mock(FlowRuntime.class);
        CredentialStore credentials = mock(CredentialStore.class);
        AgentRun run = ciRun();
        when(runtime.run(run.runId())).thenReturn(Optional.of(run));
        NewFlowAgentLaunches launches = new NewFlowAgentLaunches(
                dataSource,
                runtime,
                credentials,
                resolvingTo(NewFlowAgentLaunches.Config.cli(
                        "claude-code", "claude-opus-4-8", "high",
                        "claude", "2.1.0")),
                Clock.fixed(NOW, ZoneOffset.UTC),
                MAPPER);

        NewFlowAgentLaunches.Binding binding = launches.bind(run, CI_REPAIR);

        assertThat(binding.execution()).isEqualTo(AgentExecution.CLI);
        assertThat(binding.cliBinary()).isEqualTo("claude");
        assertThat(binding.cliVersion()).isEqualTo("2.1.0");
        assertThat(binding.credentialId()).isNull();
        assertThat(binding.endpoint()).isNull();
        assertThat(binding.transport()).isNull();
        // The credential store is never asked. A CLI turn is authorized by the
        // user's own login, so looking one up would either fail the launch or
        // pin a credential the subprocess never presents.
        verifyNoInteractions(credentials);
        // Reloading proves the row satisfies the per-execution CHECK: a shape
        // that borrowed an API column would have been rejected on insert, and
        // one that lost cli_binary would fail to reconstruct here.
        assertThat(launches.binding(run.runId())).contains(binding);
        assertThatThrownBy(() -> launches.resolveSecret(binding))
                .isInstanceOf(
                        NewFlowAgentLaunches.LaunchUnavailableException.class)
                .hasMessageContaining("must not take the in-JVM turn path");
    }

    @Test
    void theUpstreamPickRepairProgramCannotAskForReviewMidRange()
    {
        NewFlowAgentLaunches launches = launches(
                dataSource(), mock(FlowRuntime.class),
                mock(CredentialStore.class), "model");

        List<String> tools = toolNames(
                launches.tools(UPSTREAM_PICK_REPAIR, ANTHROPIC));
        String prompt = launches.systemPrompt(UPSTREAM_PICK_REPAIR);

        // A conflicted pick used to run on TASK_INITIAL, whose prompt tells the
        // agent to implement a Task goal that does not exist here and to finish
        // by requesting review — which the repair turn reads as declining the
        // conflict. Neither may come back.
        assertThat(tools)
                .contains("read_pick_conflict_context", "commit_pick_repair",
                        "decline_pick_repair")
                .doesNotContain("request_initial_review",
                        "read_initial_task_context", "commit_initial_change");
        assertThat(prompt).doesNotContain("review");
        // The contrast is the point: an ordinary initial Task must ask for
        // review, so the absence above is a property of this program alone.
        assertThat(launches.systemPrompt(TASK_INITIAL)).contains("review");
    }

    @Test
    void rejectsCaseInsensitiveGitMetadataPaths()
            throws Exception
    {
        Path worktree = Files.createDirectory(
                temporaryDirectory.resolve("worktree"));
        Path metadata = Files.createDirectory(worktree.resolve(".GIT"));
        Files.writeString(metadata.resolve("config"), "secret");
        NewFlowWorkspaceTools tools = new NewFlowWorkspaceTools(worktree);

        assertThat(tools.listRepository()).isEmpty();
        assertThatThrownBy(() -> tools.readFile(".GIT/config"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.writeFile(".GiT/config", "changed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCallsDoNotConsumeTheCheckOrTerminalAllowance()
    {
        NewFlowAgentLaunches launches = mock(NewFlowAgentLaunches.class);
        TurnRunner runner = mock(TurnRunner.class);
        LocalChecks localChecks = mock(LocalChecks.class);
        TaskInspectionToolCapability capability =
                mock(TaskInspectionToolCapability.class);
        TaskToolContext context = mock(TaskToolContext.class);
        NewFlowAgentLaunches.Binding binding = binding();
        when(launches.resolveSecret(binding)).thenReturn("secret");
        when(launches.systemPrompt(TASK_CI_FIX)).thenReturn("system");
        when(launches.tools(TASK_CI_FIX, ANTHROPIC))
                .thenReturn(MAPPER.createArrayNode());
        when(capability.runChecks()).thenReturn(List.of());

        List<ToolExecutor.ToolCallResult> results = new ArrayList<>();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor executor = invocation.getArgument(1);
            TurnHooks hooks = invocation.getArgument(2);
            results.add(executor.execute(call(
                    "spawn_adversarial_reviewer", "{", "{}")));
            results.add(executor.execute(call(
                    "spawn_adversarial_reviewer", "[]", "[]")));
            results.add(executor.execute(call(
                    "run_checks", "{\"profile\":4}",
                    "{\"profile\":4}")));
            results.add(executor.execute(call(
                    "run_checks", "{\"profile\":null}",
                    "{\"profile\":null}")));
            results.add(executor.execute(call(
                    "run_checks", "{}", "{}")));
            results.add(executor.execute(call(
                    "spawn_adversarial_reviewer", "{}", "{}")));
            assertThat(hooks.interrupted()).isTrue();
            return new TurnResult(
                    "untrusted failure prose", 1, 1, 0, 1,
                    TurnResult.End.INTERRUPTED);
        });
        NewFlowAgentBodies bodies = new NewFlowAgentBodies(
                launches, runner, MAPPER, localChecks, null);

        var completion = bodies.taskFixReview(
                binding,
                temporaryDirectory,
                context,
                capability,
                false);

        assertThat(results).extracting(ToolExecutor.ToolCallResult::isError)
                .containsExactly(true, true, true, false, true, false);
        verify(capability).runChecks();
        verify(capability).spawnAdversarialReviewer();
        assertThat(completion.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(completion.errorRef()).isNull();
    }

    @Test
    void initialProgramsFreezeDisjointToolsAndOnlyAcceptedTerminalsSeal()
            throws Exception
    {
        DataSource dataSource = dataSource();
        FlowRuntime runtime = mock(FlowRuntime.class);
        CredentialStore credentials = mock(CredentialStore.class);
        AgentRun firstRun = initialRun(
                "initial-first", "task-initial-prompt:v1",
                "task-initial-capabilities:v1");
        AgentRun reviewRun = initialRun(
                "initial-review", "task-initial-review-prompt:v1",
                "task-initial-review-capabilities:v1");
        when(runtime.run(firstRun.runId())).thenReturn(Optional.of(firstRun));
        when(runtime.run(reviewRun.runId())).thenReturn(Optional.of(reviewRun));
        when(credentials.find(CredentialType.AI, "anthropic", "ci"))
                .thenReturn(Optional.of(credential(NOW)));
        NewFlowAgentLaunches actual = launches(
                dataSource, runtime, credentials, "initial-model");
        NewFlowAgentLaunches.Binding first = actual.bind(
                firstRun, TASK_INITIAL);
        NewFlowAgentLaunches.Binding review = actual.bind(
                reviewRun, TASK_INITIAL_REVIEW_RESULT);

        assertThat(first.promptRevision()).isEqualTo("task-initial-turn:v1");
        assertThat(review.promptRevision())
                .isEqualTo("task-initial-review-turn:v1");
        assertThat(first.promptDigest()).isNotEqualTo(review.promptDigest());
        assertThat(first.toolManifestDigest())
                .isNotEqualTo(review.toolManifestDigest());
        assertThat(toolNames(actual.tools(TASK_INITIAL, ANTHROPIC)))
                .containsExactly(
                        "read_initial_task_context", "list_repository",
                        "read_file", "search_repository", "write_file",
                        "delete_file", "commit_initial_change",
                        "request_initial_review")
                .doesNotContain(
                        "ready_for_initial_publish", "request_user_input",
                        "shell", "git", "run_id", "claim_id");
        assertThat(toolNames(actual.tools(
                TASK_INITIAL_REVIEW_RESULT, ANTHROPIC)))
                .containsExactly(
                        "read_initial_review_context", "read_candidate_diff",
                        "list_repository", "read_file", "search_repository",
                        "write_file", "delete_file",
                        "commit_initial_change", "request_initial_review",
                        "ready_for_initial_publish")
                .doesNotContain(
                        "read_initial_task_context", "request_user_input",
                        "shell", "git", "run_id", "claim_id");

        NewFlowAgentLaunches mockedLaunches = mock(
                NewFlowAgentLaunches.class);
        TurnRunner runner = mock(TurnRunner.class);
        InitialToolCapability capability = mock(InitialToolCapability.class);
        when(mockedLaunches.resolveSecret(first)).thenReturn("secret");
        when(mockedLaunches.systemPrompt(TASK_INITIAL)).thenReturn("system");
        when(mockedLaunches.tools(TASK_INITIAL, ANTHROPIC))
                .thenReturn(actual.tools(TASK_INITIAL, ANTHROPIC));
        when(capability.requestReview("title", "body"))
                .thenThrow(new IllegalStateException("rejected"))
                .thenReturn(mock(FlowRuntimeRecords.ReviewerRequest.class));
        List<ToolExecutor.ToolCallResult> calls = new ArrayList<>();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor executor = invocation.getArgument(1);
            TurnHooks hooks = invocation.getArgument(2);
            calls.add(executor.execute(call(
                    "request_initial_review",
                    "{\"title\":\"title\",\"body\":\"body\"}",
                    "{\"title\":\"title\",\"body\":\"body\"}")));
            assertThat(hooks.interrupted()).isFalse();
            calls.add(executor.execute(call(
                    "request_initial_review",
                    "{\"title\":\"title\",\"body\":\"body\"}",
                    "{\"title\":\"title\",\"body\":\"body\"}")));
            assertThat(hooks.interrupted()).isTrue();
            calls.add(executor.execute(call(
                    "ready_for_initial_publish", "{}", "{}")));
            return new TurnResult(
                    "opaque failure", 1, 1, 0, 1,
                    TurnResult.End.ABORTED);
        });
        NewFlowAgentBodies bodies = new NewFlowAgentBodies(
                mockedLaunches, runner, MAPPER, mock(LocalChecks.class), null);

        var firstCompletion = bodies.initialTask(
                first, temporaryDirectory, capability, false);

        assertThat(calls).extracting(ToolExecutor.ToolCallResult::isError)
                .containsExactly(true, false, true);
        assertThat(firstCompletion.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(firstCompletion.errorRef()).isNull();

        TurnRunner readyRunner = mock(TurnRunner.class);
        InitialToolCapability readyCapability = mock(
                InitialToolCapability.class);
        when(mockedLaunches.resolveSecret(review)).thenReturn("secret");
        when(mockedLaunches.systemPrompt(TASK_INITIAL_REVIEW_RESULT))
                .thenReturn("review-system");
        when(mockedLaunches.tools(TASK_INITIAL_REVIEW_RESULT, ANTHROPIC))
                .thenReturn(actual.tools(
                        TASK_INITIAL_REVIEW_RESULT, ANTHROPIC));
        when(readyRunner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor executor = invocation.getArgument(1);
            TurnHooks hooks = invocation.getArgument(2);
            assertThat(executor.execute(call(
                    "ready_for_initial_publish", "{", "{}"))
                    .isError()).isTrue();
            assertThat(hooks.interrupted()).isFalse();
            assertThat(executor.execute(call(
                    "ready_for_initial_publish", "{}", "{}"))
                    .isError()).isFalse();
            assertThat(hooks.interrupted()).isTrue();
            return new TurnResult(
                    "opaque interruption", 1, 1, 0, 1,
                    TurnResult.End.INTERRUPTED);
        });
        NewFlowAgentBodies readyBodies = new NewFlowAgentBodies(
                mockedLaunches, readyRunner, MAPPER, mock(LocalChecks.class), null);

        var readyCompletion = readyBodies.initialTask(
                review, temporaryDirectory, readyCapability, true);

        verify(readyCapability).readyForInitialPublish();
        assertThat(readyCompletion.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(readyCompletion.errorRef()).isNull();
    }

    @Test
    void learnerReadsTheSupervisorBoundedLogWindow()
            throws Exception
    {
        NewFlowAgentLaunches launches = mock(NewFlowAgentLaunches.class);
        TurnRunner runner = mock(TurnRunner.class);
        CiLearningToolCapability capability =
                mock(CiLearningToolCapability.class);
        NewFlowAgentLaunches.Binding binding = binding();
        when(launches.resolveSecret(binding)).thenReturn("secret");
        when(launches.systemPrompt(CI_LEARNER)).thenReturn("system");
        when(launches.tools(CI_LEARNER, ANTHROPIC))
                .thenReturn(MAPPER.createArrayNode());
        when(capability.readCiLog("log-1", 7, 32_768))
                .thenReturn("bounded log window");
        List<ToolExecutor.ToolCallResult> results = new ArrayList<>();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor executor = invocation.getArgument(1);
            results.add(executor.execute(call(
                    "read_ci_log",
                    "{\"index\":0,\"offset\":7}",
                    "{\"index\":0,\"offset\":7}")));
            results.add(executor.execute(call(
                    "save_ci_lesson",
                    "{\"title\":\"Bounded\",\"markdown\":\"Lesson\"}",
                    "{\"title\":\"Bounded\",\"markdown\":\"Lesson\"}")));
            return new TurnResult(
                    "saved", 1, 1, 0, 1, TurnResult.End.INTERRUPTED);
        });
        NewFlowAgentBodies bodies = new NewFlowAgentBodies(
                launches, runner, MAPPER, mock(LocalChecks.class), null);

        var completion = bodies.learner(
                binding, List.of("log-1"), capability);

        assertThat(results).extracting(ToolExecutor.ToolCallResult::text)
                .containsExactly("bounded log window", "lesson saved");
        verify(capability).readCiLog("log-1", 7, 32_768);
        verify(capability).saveCiLesson("Bounded", "Lesson");
        assertThat(completion.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
    }

    @Test
    void streamedInterruptionCannotBecomeCompleted()
            throws Exception
    {
        String frame = String.join("",
                "data: {\"type\":\"content_block_delta\",\"index\":0,",
                "\"delta\":{\"type\":\"text_delta\",",
                "\"text\":\"partial\"}}\n\n",
                "data: {\"type\":\"message_stop\"}\n");
        AtomicBoolean interrupted = new AtomicBoolean();
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public void onTextDelta(int blockIndex, String chunk)
            {
                interrupted.set(true);
            }

            @Override
            public boolean interrupted()
            {
                return interrupted.get();
            }
        };

        TurnResult result = streamRunner(frame).runTurn(
                turnSpec(ANTHROPIC),
                call -> ToolExecutor.ToolCallResult.ok("unused"),
                hooks);

        assertThat(result.end()).isEqualTo(TurnResult.End.INTERRUPTED);
        assertThat(result.end()).isNotEqualTo(TurnResult.End.COMPLETED);
    }

    @Test
    void openAiDispatchesToolCallsByProviderIndex()
            throws Exception
    {
        var calls = MAPPER.createArrayNode();
        openAiCall(calls, 33, "terminal", "spawn_adversarial_reviewer");
        openAiCall(calls, 17, "commit", "commit_task_change");
        openAiCall(calls, 1, "write", "write_file");
        var delta = MAPPER.createObjectNode().set("tool_calls", calls);
        var choices = MAPPER.createArrayNode();
        choices.addObject().set("delta", delta);
        String toolRound = String.join("",
                "data: ",
                MAPPER.createObjectNode().set("choices", choices).toString(),
                "\n\ndata: [DONE]\n");
        String finalRound = String.join("",
                "data: {\"choices\":[{\"delta\":{\"content\":",
                "\"done\"}}]}\n\ndata: [DONE]\n");
        List<String> executed = new ArrayList<>();

        TurnResult result = streamRunner(toolRound, finalRound).runTurn(
                turnSpec(OPENAI_COMPAT, 2),
                call -> {
                    executed.add(call.name());
                    return ToolExecutor.ToolCallResult.ok("ok");
                },
                TurnHooks.NONE);

        assertThat(result.end()).isEqualTo(TurnResult.End.COMPLETED);
        assertThat(executed).containsExactly(
                "write_file",
                "commit_task_change",
                "spawn_adversarial_reviewer");
    }

    @Test
    void truncatedProviderStreamsCannotComplete()
            throws Exception
    {
        TurnRunner anthropic = streamRunner(
                "data: {\"type\":\"message_start\",\"message\":{}}\n");
        TurnRunner openAi = streamRunner(String.join("",
                "data: {\"choices\":[{\"delta\":{\"content\":",
                "\"partial\"}}]}\n"));

        assertThatThrownBy(() -> anthropic.runTurn(
                turnSpec(ANTHROPIC),
                call -> ToolExecutor.ToolCallResult.ok("unused"),
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Anthropic streaming ended without a terminal marker");
        assertThatThrownBy(() -> openAi.runTurn(
                turnSpec(OPENAI_COMPAT),
                call -> ToolExecutor.ToolCallResult.ok("unused"),
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OpenAI-compatible streaming ended without a terminal marker");
    }

    @Test
    void providerStreamCapsFailBeforeAnyToolDispatch()
            throws Exception
    {
        AtomicInteger dispatched = new AtomicInteger();
        String oversizedLine = "data: " + "x".repeat(1024 * 1024 + 1)
                + "\n";
        assertThatThrownBy(() -> streamRunner(oversizedLine).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider SSE line exceeded its bound");

        String argumentChunk = "x".repeat(600 * 1024);
        String toolArguments = String.join("",
                "data: {\"type\":\"content_block_start\",\"index\":1,",
                "\"content_block\":{\"type\":\"tool_use\",",
                "\"id\":\"tool\",\"name\":\"write_file\"}}\n\n",
                anthropicArgumentDelta(argumentChunk),
                anthropicArgumentDelta(argumentChunk));
        assertThatThrownBy(() -> streamRunner(toolArguments).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider tool arguments exceeded its bound");

        String comment = ": " + "x".repeat(1024) + "\n";
        String aggregate = comment.repeat(17_000);
        assertThatThrownBy(() -> streamRunner(aggregate).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider response exceeded its bound");
        assertThat(dispatched).hasValue(0);
    }

    @Test
    void anthropicCountsSequentialCallsAndRejectsReusedIndices()
            throws Exception
    {
        StringBuilder sequential = new StringBuilder();
        for (int index = 0; index < 65; index++) {
            sequential.append(anthropicToolStart(index));
            sequential.append("data: {\"type\":\"content_block_stop\",\"index\":")
                    .append(index)
                    .append("}\n\n");
        }
        sequential.append("data: {\"type\":\"message_stop\"}\n");
        AtomicInteger dispatched = new AtomicInteger();
        assertThatThrownBy(() -> streamRunner(sequential.toString()).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider tool calls exceeded its bound");

        String duplicate = anthropicToolStart(4)
                + anthropicToolStart(4)
                + "data: {\"type\":\"message_stop\"}\n";
        assertThatThrownBy(() -> streamRunner(duplicate).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider tool calls exceeded its bound");

        String trailing = String.join("",
                "data: {\"type\":\"content_block_delta\",\"index\":0,",
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"prior\"}}\n\n",
                "data: {\"type\":\"message_stop\"}\n\n",
                anthropicToolStart(7),
                "data: {\"type\":\"content_block_stop\",\"index\":7}\n\n");
        TurnResult sealed = streamRunner(trailing).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE);
        assertThat(sealed.end()).isEqualTo(TurnResult.End.COMPLETED);
        assertThat(sealed.finalText()).isEqualTo("prior");

        String incomplete = anthropicToolStart(9)
                + "data: {\"type\":\"message_stop\"}\n";
        assertThatThrownBy(() -> streamRunner(incomplete).runTurn(
                turnSpec(ANTHROPIC),
                call -> {
                    dispatched.incrementAndGet();
                    return ToolExecutor.ToolCallResult.ok("unexpected");
                },
                TurnHooks.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete tool call");
        assertThat(dispatched).hasValue(0);
    }

    private DataSource dataSource()
    {
        Path database = temporaryDirectory.resolve(
                "launch-%d.db".formatted(System.nanoTime()));
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:%s".formatted(database));
        FlowRuntimeSchema.install(dataSource);
        return dataSource;
    }

    private static NewFlowAgentLaunches launches(
            DataSource dataSource,
            FlowRuntime runtime,
            CredentialStore credentials,
            String model)
    {
        return new NewFlowAgentLaunches(
                dataSource,
                runtime,
                credentials,
                resolvingTo(NewFlowAgentLaunches.Config.api(
                        "anthropic",
                        ANTHROPIC,
                        "https://provider.test/v1/messages",
                        model,
                        "high",
                        "anthropic",
                        "ci",
                        1_024,
                        2)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                MAPPER);
    }

    /** The engine is resolved per run from workspace settings; these
     *  boundaries are about the binding, not about which engine it names. */
    private static NewFlowEngineResolver resolvingTo(NewFlowAgentLaunches.Config config)
    {
        NewFlowEngineResolver engines = mock(NewFlowEngineResolver.class);
        when(engines.resolve(any(FlowRuntimeRecords.AgentRun.class))).thenReturn(config);
        return engines;
    }

    private static Credential credential(Instant updatedAt)
    {
        return new Credential(
                7,
                CredentialType.AI,
                "anthropic",
                "ci",
                "CI",
                "***",
                null,
                true,
                null,
                NOW.minusSeconds(1),
                updatedAt,
                null);
    }

    private static AgentRun ciRun()
    {
        return new AgentRun(
                "run-1",
                "operation-1",
                "session-1",
                AgentRole.CI_FIXER,
                HEAD,
                "ci-fix-prompt:v1",
                "ci-fix-capabilities:v1",
                "ci-input",
                null,
                null,
                null,
                null,
                RunState.QUEUED,
                null,
                NOW,
                null,
                null);
    }

    private static AgentRun initialRun(
            String runId, String prompt, String capabilities)
    {
        boolean review = prompt.equals("task-initial-review-prompt:v1");
        return new AgentRun(
                runId,
                "operation-" + runId,
                "session-initial",
                AgentRole.TASK_AGENT,
                HEAD,
                prompt,
                capabilities,
                "inbox:initial",
                review ? "change-set-revision" : null,
                null,
                review ? WakeKind.AGENT_RESULT_READY : WakeKind.INITIAL_TASK,
                GateIntent.INITIAL_PUBLISH,
                RunState.QUEUED,
                null,
                NOW,
                null,
                null);
    }

    private static List<String> toolNames(ArrayNode tools)
    {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").textValue()));
        return names;
    }

    private static NewFlowAgentLaunches.Binding binding()
    {
        return new NewFlowAgentLaunches.Binding(
                "task-run",
                "anthropic",
                AgentExecution.API,
                ANTHROPIC,
                "https://provider.test/v1/messages",
                "model",
                "high",
                7L,
                "anthropic",
                "ci",
                NOW,
                "task-ci-fix-review-turn:v1",
                "prompt-digest",
                "tool-digest",
                1_024,
                2,
                null,
                null,
                "binding-digest",
                NOW);
    }

    private static ToolCall call(
            String name, String rawArguments, String parsedArguments)
            throws Exception
    {
        return new ToolCall(
                String.join("-", "call", name,
                        Long.toString(System.nanoTime())),
                name,
                rawArguments,
                MAPPER.readTree(parsedArguments));
    }

    private static void openAiCall(
            ArrayNode calls,
            int index,
            String id,
            String name)
    {
        var call = calls.addObject();
        call.put("index", index);
        call.put("id", id);
        call.putObject("function")
                .put("name", name)
                .put("arguments", "{}");
    }

    private static String anthropicArgumentDelta(String chunk)
            throws Exception
    {
        var delta = MAPPER.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", chunk);
        var frame = MAPPER.createObjectNode();
        frame.put("type", "content_block_delta");
        frame.put("index", 1);
        frame.set("delta", delta);
        return "data: " + MAPPER.writeValueAsString(frame) + "\n\n";
    }

    private static String anthropicToolStart(int index)
    {
        return "data: {\"type\":\"content_block_start\",\"index\":"
                + index
                + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-"
                + index
                + "\",\"name\":\"read_file\"}}\n\n";
    }

    @SuppressWarnings("unchecked")
    private static TurnRunner streamRunner(String... frames)
            throws Exception
    {
        Deque<String> pending = new ArrayDeque<>(List.of(frames));
        HttpClient http = mock(HttpClient.class);
        when(http.send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpResponse<InputStream> response =
                            mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.body()).thenReturn(new ByteArrayInputStream(
                            pending.removeFirst().getBytes(
                                    StandardCharsets.UTF_8)));
                    return response;
                });
        return new TurnRunner(http, MAPPER);
    }

    private static TurnSpec turnSpec(TurnSpec.Transport transport)
    {
        return turnSpec(transport, 1);
    }

    private static TurnSpec turnSpec(
            TurnSpec.Transport transport, int maxToolIterations)
    {
        var messages = MAPPER.createArrayNode();
        messages.addObject().put("role", "user").put("content", "go");
        return new TurnSpec(
                transport,
                "https://provider.test/v1/messages",
                "secret",
                "model",
                null,
                "system",
                messages,
                MAPPER.createArrayNode(),
                1_024,
                maxToolIterations);
    }
}
