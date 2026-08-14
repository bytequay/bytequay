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

import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.flow.upstream.UpstreamPicker;
import com.bytequay.app.flow.upstream.UpstreamPicker.PickResult;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real subprocess, because every property worth asserting here is about
 * ordering against one: what is persisted before the agent is given work, and
 * what is proven dead before the turn is allowed to end. A fake CLI stands in
 * for the vendor binary — the flags are the vendor's business, the lifecycle is
 * this class's.
 */
final class TestNewFlowCliTurn
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = "run-cli-1";
    /** Absolute, because a read-only turn runs in a directory of its own. */
    @FunctionalInterface
    private interface GroupSeen
    {
        void at(long pid, long pgid, Instant startedAt);
    }

    private static String marker(Path root)
    {
        return root.resolve("marker.txt").toString();
    }

    private static NewFlowAgentPermissions.PendingApproval awaitApproval(
            NewFlowAgentPermissions permissions, String runId)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<NewFlowAgentPermissions.PendingApproval> pending =
                    permissions.pending(runId);
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the permission question never appeared");
    }

    @Test
    void theGroupIsPersistedBeforeTheAgentSeesItsPrompt(@TempDir Path root)
            throws Exception
    {
        // The ordering the whole recovery path depends on. A group id learned
        // after the turn is lost by exactly the crash that makes it matter, so
        // the recorder has to have run while the agent is still blocked on an
        // empty stdin.
        Fixture fixture = Fixture.create(root, """
                read line
                printf 'prompt-read' > %s
                echo '{"type":"system","subtype":"init","session_id":"s-1"}'
                echo '{"type":"result","subtype":"success","result":"done"}'
                """.formatted(marker(root)));
        AtomicBoolean promptAlreadyRead = new AtomicBoolean();
        AtomicLong recorded = new AtomicLong();

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {
                    promptAlreadyRead.set(Files.exists(fixture.marker));
                    recorded.set(pgid);
                });

        assertThat(promptAlreadyRead)
                .as("the group must be recorded before the prompt is delivered")
                .isFalse();
        assertThat(recorded).doesNotHaveValue(0);
        assertThat(fixture.marker)
                .as("the fake CLI must actually have read its prompt")
                .exists();
        assertThat(outcome.providerSessionId()).isEqualTo("s-1");
        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.COMPLETED);
        assertThat(outcome.turn().finalText()).isEqualTo("done");
    }

    @Test
    void theTurnDoesNotEndUntilAReparentedGrandchildIsGone(@TempDir Path root)
            throws Exception
    {
        // The agent leaves a child behind and exits. A descendants() walk has
        // already lost it by then; the process group has not.
        Fixture fixture = Fixture.create(root, """
                read line
                sh -c 'sleep 300' &
                echo '{"type":"result","subtype":"success","result":"left one"}'
                """);
        AtomicLong pgid = new AtomicLong();

        fixture.run((pid, group, startedAt) -> pgid.set(group));

        assertThat(ProcessGroup.isAlive(pgid.get()))
                .as("no process of the turn may outlive the call that ran it")
                .isFalse();
    }

    @Test
    void theToolBridgeIsOpenOnlyWhileTheAgentRuns(@TempDir Path root)
            throws Exception
    {
        // A subprocess that outlived its turn must find a dead endpoint rather
        // than a live worktree, so the window is exactly the turn.
        Fixture fixture = Fixture.create(root, """
                read line
                echo '{"type":"result","subtype":"success","result":"ok"}'
                """);
        AtomicBoolean openDuring = new AtomicBoolean();

        fixture.run((pid, pgid, startedAt) ->
                openDuring.set(fixture.bridge.isOpen(RUN_ID)));

        assertThat(openDuring).isTrue();
        assertThat(fixture.bridge.isOpen(RUN_ID)).isFalse();
    }

    @Test
    void theAgentCannotPublishAndTheProgramStillCan(@TempDir Path root)
            throws Exception
    {
        // Containment covers the agent's whole turn and is lifted afterwards.
        // Both halves matter: without the first the flow's contract is prose,
        // and without the second a run could never publish.
        Fixture fixture = Fixture.create(root, """
                read line
                git push origin HEAD:refs/heads/main >> %s 2>&1 \\
                    || echo 'push-refused' >> %s
                echo '{"type":"result","subtype":"success","result":"ok"}'
                """.formatted(marker(root), marker(root)));

        fixture.run((pid, pgid, startedAt) -> {});

        assertThat(Files.readString(fixture.marker, StandardCharsets.UTF_8))
                .contains("push-refused");
        assertThat(fixture.push())
                .as("the program's own publish must still work afterwards")
                .isZero();
    }

    @Test
    void aFailingAgentIsReportedAsAbortedRatherThanComplete(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.create(root, """
                read line
                echo 'not json at all'
                exit 3
                """);

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        // A parser that shrugs at unknown lines must not turn a failed agent
        // into a successful empty turn.
        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.ABORTED);
        assertThat(outcome.providerSessionId()).isNull();
        // A failed turn still spent what it spent, so it is still journalled.
        assertThat(fixture.usage).hasSize(1);
    }

    @Test
    void whatTheTurnSpentIsJournalledWithTheSessionToResume(@TempDir Path root)
            throws Exception
    {
        // Both halves of what outlives the turn: the vendor's handle on the
        // conversation, so the next turn continues it, and the accounting the
        // budget stops on.
        Fixture fixture = Fixture.create(root, """
                read line
                echo '{"type":"system","subtype":"init","session_id":"s-7"}'
                echo '{"type":"result","subtype":"success","result":"ok","usage":{"input_tokens":11,"output_tokens":5},"total_cost_usd":0.25}'
                """);

        fixture.run((pid, pgid, startedAt) -> {});

        assertThat(fixture.usage)
                .as("exactly one journal entry per turn, or a budget"
                        + " double-counts")
                .hasSize(1);
        assertThat(fixture.usage.getFirst()).startsWith("s-7/");
    }

    @Test
    void claudeActivityStreamsBeforeTheProcessEnds(@TempDir Path root)
            throws Exception
    {
        Path release = root.resolve("release");
        Fixture fixture = Fixture.create(root, """
                case " $* " in
                  *" --input-format stream-json "*) ;;
                  *) exit 7 ;;
                esac
                IFS= read -r input
                printf '%%s' "$input" > %s
                echo '{"type":"system","subtype":"init","session_id":"live-1"}'
                echo '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"read-1","name":"Read","input":{"file_path":"one.txt"}}]}}'
                while [ ! -f %s ]; do sleep 0.01; done
                echo '{"type":"result","subtype":"success","result":"done"}'
                """.formatted(marker(root), release));

        CompletableFuture<NewFlowCliTurn.Outcome> outcome =
                CompletableFuture.supplyAsync(() -> fixture.run(
                        (pid, pgid, startedAt) -> {}));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.activity.stream().noneMatch(
                StreamEvent.ToolCallStarted.class::isInstance)
                && !outcome.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        boolean streamedBeforeExit = !outcome.isDone();
        Files.writeString(release, "go", StandardCharsets.UTF_8);
        NewFlowCliTurn.Outcome completed = outcome.get(5, TimeUnit.SECONDS);

        assertThat(streamedBeforeExit).isTrue();
        assertThat(fixture.activity).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(
                        StreamEvent.ToolCallStarted.class,
                        call -> assertThat(call.toolName()).isEqualTo("Read")));
        assertThat(MAPPER.readTree(Files.readString(fixture.marker)))
                .isEqualTo(MAPPER.readTree("""
                        {"type":"user","message":{"role":"user",
                         "content":"Work only on the exact program-selected subject."},
                         "parent_tool_use_id":null}
                        """));

        assertThat(completed.turn().finalText())
                .isEqualTo("done");
    }

    @Test
    void codexReceivesTheSealedProgramAndPermissiveToolGuidanceOnStdin(
            @TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat > %s
                echo '{"type":"thread.started","thread_id":"codex-1"}'
                echo '{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"done"}}'
                echo '{"type":"turn.completed","usage":{"input_tokens":12,"output_tokens":3}}'
                """.formatted(marker(root)));

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(Files.readString(fixture.marker, StandardCharsets.UTF_8))
                .contains("be exact")
                .contains("built-in tools are available")
                .contains("bytequay tools are the recommended way")
                .contains("inside the supplied worktree")
                .contains("if a native operation is unavailable")
                .doesNotContain("raises an approval card")
                .contains("Work only on the exact program-selected subject");
        assertThat(outcome.turn().finalText()).isEqualTo("done");
        assertThat(fixture.usage).containsExactly("codex-1/12/3/0");
    }

    @Test
    void aReadOnlyCodexTurnGetsHonestToolGuidance(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat > %s
                echo '{"type":"thread.started","thread_id":"codex-review"}'
                echo '{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"reviewed"}}'
                echo '{"type":"turn.completed","usage":{"input_tokens":8,"output_tokens":2}}'
                """.formatted(marker(root)));

        fixture.runReadOnly((pid, pgid, startedAt) -> {});

        assertThat(Files.readString(fixture.marker, StandardCharsets.UTF_8))
                .contains("This is a read-only role")
                .contains("if a native operation is unavailable")
                .doesNotContain("raises an approval card")
                .doesNotContain("You may read, search, edit");
    }

    @Test
    void aCodexFailureKeepsTheProviderMessage(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat >/dev/null
                echo '{"type":"thread.started","thread_id":"codex-failed"}'
                echo '{"type":"turn.failed","error":{"message":"sandbox could not start"}}'
                exit 1
                """);

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.ABORTED);
        assertThat(outcome.turn().finalText())
                .isEqualTo("sandbox could not start");
    }

    @Test
    void aStderrOnlyFailureIsDrainedAndReported(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat >/dev/null
                echo 'Reading additional input from stdin...'
                echo 'authentication expired' >&2
                exit 1
                """);

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.ABORTED);
        assertThat(outcome.turn().finalText())
                .contains("authentication expired");
    }

    @Test
    void aResumedCodexTurnRecordsOnlyUsageAfterTheDurableBaseline(
            @TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat >/dev/null
                echo '{"type":"thread.started","thread_id":"codex-resume"}'
                echo '{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"continued"}}'
                echo '{"type":"turn.completed","usage":{"input_tokens":130,"output_tokens":27}}'
                """);
        JdbcTemplate seed = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("runtime.db")));
        seed.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, provider_session_id,
                    total_tokens_in, total_tokens_out, created_at, updated_at
                ) VALUES ('s1', 't1', 'TASK_AGENT', 'RUNNING', 'codex-resume',
                          110, 25, 0, 0)
                """);
        seed.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    wake_kind, intended_gate_kind, state, created_at
                ) VALUES (?, 'op-1', 's1', 'TASK_AGENT', 'h', 'p', 'c', 'i',
                          'INITIAL_TASK', 'INITIAL_PUBLISH', 'RUNNING', 0)
                """, RUN_ID);
        seed.update("""
                INSERT INTO flow_runtime_agent_process_attempt (
                    process_attempt_id, run_id, operation_id,
                    claim_generation, claim_token_digest, execution_id,
                    capability_id, state, jvm_pid, jvm_started_at, thread_id,
                    thread_name, activated_at, capability_revoked_at,
                    stop_type, stop_proof_ref, stopped_at,
                    completion_outcome, completion_digest,
                    attempt_provider_session_id, attempt_tokens_in,
                    attempt_tokens_out, reserved_at
                ) VALUES ('old-provider-attempt', ?, 'op-1', 1, 'claim', 'old-execution',
                          'old-capability', 'STOPPED', 7, 0, 8, 'test', 0, 0,
                          'NORMAL_RETURN', 'gone', 0,
                          'COMPLETED', 'done', 'codex-old', 100, 20, 0)
                """, RUN_ID);
        seed.update("""
                INSERT INTO flow_runtime_agent_process_attempt (
                    process_attempt_id, run_id, operation_id,
                    claim_generation, claim_token_digest, execution_id,
                    capability_id, state, jvm_pid, jvm_started_at, thread_id,
                    thread_name, activated_at, capability_revoked_at,
                    stop_type, stop_proof_ref, stopped_at,
                    completion_outcome, completion_digest,
                    attempt_provider_session_id, attempt_tokens_in,
                    attempt_tokens_out, reserved_at
                ) VALUES ('new-provider-attempt', ?, 'op-1', 2, 'claim', 'new-execution',
                          'new-capability', 'STOPPED', 7, 0, 8, 'test', 0, 0,
                          'NORMAL_RETURN', 'gone', 0,
                          'COMPLETED', 'done', 'codex-resume', 10, 5, 0)
                """, RUN_ID);

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.COMPLETED);
        assertThat(fixture.usage).containsExactly("codex-resume/120/22/0");
    }

    @Test
    void codexResumeDoesNotReplayAfterProviderWorkStarted(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createCodex(root, """
                cat >/dev/null
                echo launched >> %s
                echo '{"type":"item.started","item":{"id":"c1","type":"command_execution","command":"printf changed"}}'
                echo '{"type":"turn.failed","error":{"message":"failed after command"}}'
                exit 1
                """.formatted(marker(root)));
        seedProviderSession(root, "codex-old");

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.ABORTED);
        assertThat(Files.readString(fixture.marker, StandardCharsets.UTF_8))
                .isEqualTo("launched\n");
    }

    private static void seedProviderSession(Path root, String providerSession)
    {
        JdbcTemplate seed = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("runtime.db")));
        seed.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, provider_session_id,
                    created_at, updated_at
                ) VALUES ('s1', 't1', 'TASK_AGENT', 'RUNNING', ?, 0, 0)
                """, providerSession);
        seed.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    wake_kind, intended_gate_kind, state, created_at
                ) VALUES (?, 'op-1', 's1', 'TASK_AGENT', 'h', 'p', 'c', 'i',
                          'INITIAL_TASK', 'INITIAL_PUBLISH', 'RUNNING', 0)
                """, RUN_ID);
    }

    @Test
    void aStaleProviderSessionFallsBackToAFreshConversation(@TempDir Path root)
            throws Exception
    {
        // A wiped vendor state or an engine switch makes the stored session
        // unresumable. The CLI exits before opening a conversation, so nothing
        // ran and nothing was spent — the turn forgets the dead handle and
        // reruns once as a fresh conversation instead of failing this role's
        // every later turn the same way.
        Fixture fixture = Fixture.create(root, """
                read line
                case "$*" in
                *--resume*)
                    echo run-resumed >> %s
                    echo 'No conversation found with session ID: s-stale' >&2
                    exit 1
                    ;;
                *)
                    echo run-fresh >> %s
                    echo '{"type":"system","subtype":"init","session_id":"s-fresh"}'
                    echo '{"type":"result","subtype":"success","result":"ok"}'
                    ;;
                esac
                """.formatted(marker(root), marker(root)));
        JdbcTemplate seed = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("runtime.db")));
        seed.update("""
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, provider_session_id,
                    created_at, updated_at
                ) VALUES ('s1', 't1', 'TASK_AGENT', 'RUNNING', 's-stale', 0, 0)
                """);
        seed.update("""
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    wake_kind, intended_gate_kind, state, created_at
                ) VALUES (?, 'op-1', 's1', 'TASK_AGENT', 'h', 'p', 'c', 'i',
                          'INITIAL_TASK', 'INITIAL_PUBLISH', 'RUNNING', 0)
                """, RUN_ID);

        NewFlowCliTurn.Outcome outcome = fixture.run((pid, pgid, started) -> {});

        assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.COMPLETED);
        assertThat(outcome.providerSessionId()).isEqualTo("s-fresh");
        assertThat(Files.readString(fixture.marker, StandardCharsets.UTF_8))
                .as("the resumed launch failed and exactly one fresh launch ran")
                .isEqualTo("run-resumed\nrun-fresh\n");
        assertThat(fixture.usage)
                .as("one journal entry despite two launches")
                .hasSize(1);
        assertThat(fixture.usage.getFirst()).startsWith("s-fresh/");
        assertThat(seed.queryForObject("""
                SELECT provider_session_id FROM flow_runtime_agent_session
                WHERE session_id = 's1'
                """, String.class))
                .as("the dead handle is forgotten so later turns start fresh")
                .isNull();
    }

    @Test
    void aReadOnlyTurnGetsNoRepositoryAndNoCredentials(@TempDir Path root)
            throws Exception
    {
        // A reviewer reads through its tools, never the filesystem. Handing it
        // an empty directory is stronger than containing a worktree: there is
        // no repository to push from and no credential to push with.
        Fixture fixture = Fixture.create(root, """
                read line
                printf '%%s\\n' "cwd=$PWD" > %s
                git rev-parse --show-toplevel >> %s 2>&1 \\
                    || echo 'no-repository' >> %s
                git config --global --list >> %s 2>&1
                echo '{"type":"result","subtype":"success","result":"reviewed"}'
                """.formatted(marker(root), marker(root), marker(root), marker(root)));
        AtomicLong pgid = new AtomicLong();

        NewFlowCliTurn.Outcome outcome = fixture.runReadOnly(
                (pid, group, startedAt) -> pgid.set(group));

        String observed = Files.readString(
                fixture.marker, StandardCharsets.UTF_8);
        assertThat(observed).contains("no-repository");
        assertThat(observed)
                .as("an empty global config is the whole point of the scrub")
                .doesNotContain("credential");
        assertThat(observed)
                .as("the agent must not be standing in the Task's worktree")
                .doesNotContain("cwd=" + fixture.worktree);
        assertThat(outcome.turn().finalText()).isEqualTo("reviewed");
        assertThat(ProcessGroup.isAlive(pgid.get())).isFalse();
    }

    @Test
    void claudeMcpCallsCanOutliveTheTwoHourValidationCeiling(
            @TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.create(root, """
                read line
                printf '%%s' "$MCP_TOOL_TIMEOUT" > %s
                echo '{"type":"result","subtype":"success","result":"done"}'
                """.formatted(marker(root)));

        fixture.run((pid, pgid, startedAt) -> {});

        long timeout = Long.parseLong(Files.readString(
                fixture.marker, StandardCharsets.UTF_8));
        assertThat(timeout).isGreaterThan(7_200_000L);
    }

    @Test
    void aWriterLaunchesFromALinkedWorktree(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = Fixture.createLinked(root, """
                read line
                echo '{"type":"result","subtype":"success","result":"linked"}'
                """);

        NewFlowCliTurn.Outcome outcome = fixture.run(
                (pid, pgid, startedAt) -> {});

        assertThat(Files.isRegularFile(fixture.worktree.resolve(".git")))
                .isTrue();
        assertThat(outcome.turn().finalText()).isEqualTo("linked");
    }

    @Test
    void aRealLoopbackToolCallRunsOnTheWriterThread(@TempDir Path root)
            throws Exception
    {
        AtomicReference<NewFlowAgentToolBridge> liveBridge =
                new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            var response = liveBridge.get().handle(RUN_ID, request)
                    .orElseThrow();
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AtomicReference<Thread> toolThread = new AtomicReference<>();
            Fixture fixture = Fixture.create(
                    root,
                    """
                    read line
                    request='{"id":1,"method":"tools/call","params":{"name":"test_tool"}}'
                    curl -sS -o /dev/null -X POST -H 'Content-Type: application/json' \
                      --data "$request" "$BYTEQUAY_NEW_FLOW_MCP_URL"
                    echo '{"type":"result","subtype":"success","result":"called"}'
                    """,
                    server.getAddress().getPort(),
                    call -> {
                        toolThread.set(Thread.currentThread());
                        return ToolExecutor.ToolCallResult.ok("tool ran");
                    });
            liveBridge.set(fixture.bridge);
            AtomicReference<Thread> owner = new AtomicReference<>();

            NewFlowCliTurn.Outcome outcome = fixture.run(
                    (pid, pgid, startedAt) -> owner.set(
                            Thread.currentThread()));

            assertThat(outcome.turn().finalText()).isEqualTo("called");
            assertThat(toolThread).hasValue(owner.get());
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void codexSeesOnlyTheSealedSemanticToolManifest(@TempDir Path root)
            throws Exception
    {
        AtomicReference<NewFlowAgentToolBridge> liveBridge =
                new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            var response = liveBridge.get().handle(RUN_ID, request)
                    .orElseThrow();
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = Fixture.createCodex(
                    root,
                    """
                    cat >/dev/null
                    request='{"id":1,"method":"tools/list"}'
                    curl -sS -X POST -H 'Content-Type: application/json' \
                      --data "$request" "$BYTEQUAY_NEW_FLOW_MCP_URL" > %s
                    echo '{"type":"thread.started","thread_id":"codex-tools"}'
                    echo '{"type":"item.completed","item":{"id":"m1","type":"agent_message","text":"listed"}}'
                    echo '{"type":"turn.completed","usage":{"input_tokens":8,"output_tokens":2}}'
                    """.formatted(marker(root)),
                    server.getAddress().getPort(),
                    call -> ToolExecutor.ToolCallResult.ok(""));
            liveBridge.set(fixture.bridge);

            fixture.run((pid, pgid, startedAt) -> {});

            String listed = Files.readString(
                    fixture.marker, StandardCharsets.UTF_8);
            assertThat(listed).contains("test_tool")
                    .doesNotContain(NewFlowAgentPermissions.TOOL_NAME);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void aContainedRunUsesTheProductRunIdForApprovalCards(@TempDir Path root)
            throws Exception
    {
        AtomicReference<NewFlowAgentToolBridge> liveBridge =
                new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            var response = liveBridge.get().handle(RUN_ID, request)
                    .orElseThrow();
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = Fixture.create(
                    root,
                    """
                    read line
                    request='{"id":1,"method":"tools/call","params":{"name":"request_tool_permission","arguments":{"tool_name":"Bash","input":{"command":"sed -i worktree.txt"}}}}'
                    curl -sS -o /dev/null -X POST -H 'Content-Type: application/json' \
                      --data "$request" "$BYTEQUAY_NEW_FLOW_MCP_URL"
                    echo '{"type":"result","subtype":"success","result":"continued"}'
                    """,
                    server.getAddress().getPort(),
                    call -> ToolExecutor.ToolCallResult.ok(""));
            liveBridge.set(fixture.bridge);
            String productRunId = "upstream-sync-run:4";
            CompletableFuture<NewFlowCliTurn.Outcome> outcome =
                    CompletableFuture.supplyAsync(() -> fixture.run(
                            productRunId, (pid, pgid, startedAt) -> {}));

            NewFlowAgentPermissions.PendingApproval pending =
                    awaitApproval(fixture.permissions, productRunId);
            assertThat(pending.runId()).isEqualTo(productRunId);
            assertThat(fixture.permissions.pending(RUN_ID)).isEmpty();
            assertThat(fixture.permissions.answer(
                    pending.approvalId(), true)).isTrue();
            assertThat(outcome.get(10, TimeUnit.SECONDS).turn().finalText())
                    .isEqualTo("continued");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void oneClaudeCliSessionRepairsOneUpstreamConflictInALinkedWorktree(
            @TempDir Path root)
            throws Exception
    {
        ConflictRange range = ConflictRange.create(root);
        String conflictedCommit = range.commits().getFirst();
        assertThat(UpstreamPicker.hasCommit(
                range.target(), conflictedCommit)).isFalse();
        UpstreamPicker.transferObjects(
                range.upstream(), range.target(), List.of(conflictedCommit));

        UpstreamPicker picker = new UpstreamPicker(range.worktree());
        ActiveConflict active = conflict(conflictedCommit, picker);
        AtomicInteger repaired = new AtomicInteger();
        ToolExecutor executor = call -> {
            try {
                return switch (call.name()) {
                    case "read_pick_conflict_context" ->
                            ToolExecutor.ToolCallResult.ok(
                                    "sha=" + active.sha()
                                            + " conflictedPaths="
                                            + active.result()
                                                    .conflictedPaths());
                    case "read_file" -> ToolExecutor.ToolCallResult.ok(
                            Files.readString(
                                    range.worktree().resolve(text(call, "path")),
                                    StandardCharsets.UTF_8));
                    case "write_file" -> {
                        String path = text(call, "path");
                        if (!active.result().conflictedPaths().contains(path)) {
                            yield ToolExecutor.ToolCallResult.error(
                                    "path is not conflicted");
                        }
                        Files.writeString(
                                range.worktree().resolve(path),
                                text(call, "content"),
                                StandardCharsets.UTF_8);
                        yield ToolExecutor.ToolCallResult.ok("written");
                    }
                    case "commit_pick_repair" -> {
                        PickResult continued = picker.continuePick(
                                active.result().head(), active.sha(),
                                active.result().conflictedPaths());
                        assertThat(continued.provenanceVerified()).isTrue();
                        repaired.incrementAndGet();
                        yield ToolExecutor.ToolCallResult.ok(
                                "conflict repaired");
                    }
                    default -> ToolExecutor.ToolCallResult.error(
                            "tool is not available");
                };
            }
            catch (IOException | RuntimeException failure) {
                return ToolExecutor.ToolCallResult.error(failure.toString());
            }
        };

        AtomicReference<NewFlowAgentToolBridge> liveBridge =
                new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = MAPPER.readTree(exchange.getRequestBody());
            var response = liveBridge.get().handle(RUN_ID, request)
                    .orElseThrow();
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path executable = fakeConflictClaude(root);
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + root.resolve("runtime.db")
                            + "?foreign_keys=ON");
            FlowRuntimeSchema.install(dataSource);
            NewFlowAgentToolBridge bridge = new NewFlowAgentToolBridge(MAPPER);
            liveBridge.set(bridge);
            NewFlowCliTurn turn = new NewFlowCliTurn(
                    new FlowRuntime(dataSource, Clock.systemUTC()), bridge,
                    new NewFlowAgentPermissions(MAPPER),
                    MAPPER, server.getAddress().getPort());
            NewFlowAgentLaunches.Binding binding = binding(executable);
            List<String> usage = new ArrayList<>();
            List<StreamEvent> activity = new ArrayList<>();

            NewFlowCliTurn.Outcome outcome = turn.runInWorktree(
                    RUN_ID, binding, conflictManifest(),
                    "Resolve only the current upstream conflict.",
                    executor, range.worktree(), new NewFlowCliTurn.TurnJournal()
                    {
                        @Override
                        public void group(
                                long pid, long pgid, Instant startedAt) {}

                        @Override
                        public void usage(
                                String providerSessionId,
                                long tokensIn,
                                long tokensOut,
                                long costMilliUsd)
                        {
                            usage.add(providerSessionId);
                        }

                        @Override
                        public void activity(StreamEvent event)
                        {
                            activity.add(event);
                        }
                    }, () -> false);

            assertThat(Files.isRegularFile(range.worktree().resolve(".git")))
                    .isTrue();
            assertThat(outcome.turn().end()).isEqualTo(TurnResult.End.COMPLETED);
            assertThat(outcome.providerSessionId())
                    .isEqualTo("claude-upstream-session");
            assertThat(usage).containsExactly("claude-upstream-session");
            assertThat(activity).anySatisfy(event -> assertThat(event)
                    .isInstanceOfSatisfying(
                            StreamEvent.ToolCallStarted.class,
                            call -> assertThat(call.toolName())
                                    .isEqualTo("Read")));
            assertThat(activity).anySatisfy(event -> assertThat(event)
                    .isInstanceOfSatisfying(
                            StreamEvent.ToolCallDone.class,
                            done -> assertThat(done.outputJson())
                                    .contains("native contents")));
            assertThat(repaired).hasValue(1);
            assertThat(picker.clean()).isTrue();
            String history = ConflictRange.git(
                    range.worktree(), "log", "-1", "--format=%B");
            assertThat(history).contains(
                    "(cherry picked from commit " + conflictedCommit + ")");
        }
        finally {
            server.stop(0);
        }
    }

    private static String text(ToolCall call, String name)
    {
        String value = call.input().path(name).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private static ActiveConflict conflict(String sha, UpstreamPicker picker)
    {
        PickResult result = picker.pick(sha);
        assertThat(result.outcome())
                .isEqualTo(UpstreamPicker.Outcome.CONFLICTED);
        assertThat(result.conflictedPaths()).hasSize(1);
        return new ActiveConflict(sha, result);
    }

    private static ArrayNode conflictManifest()
    {
        var tools = MAPPER.createArrayNode();
        for (String name : List.of(
                "read_pick_conflict_context", "read_file", "write_file",
                "commit_pick_repair")) {
            tools.addObject().put("name", name)
                    .putObject("inputSchema").put("type", "object");
        }
        return tools;
    }

    private static NewFlowAgentLaunches.Binding binding(Path executable)
    {
        return new NewFlowAgentLaunches.Binding(
                RUN_ID, "claude-code", AgentExecution.CLI, null, null,
                "sonnet", "high", null, null, null, null, "r1",
                "prompt-digest", "tool-digest", null, null,
                executable.toString(), "2.1", "binding-digest",
                Instant.EPOCH);
    }

    private static Path fakeConflictClaude(Path root)
            throws IOException
    {
        Path executable = root.resolve("fake-conflict-claude");
        Files.writeString(executable, """
                #!/bin/sh
                read line
                echo '{"type":"system","subtype":"init","session_id":"claude-upstream-session"}'
                echo '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"native-read","name":"Read","input":{"file_path":"one.txt"}}]}}'
                echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"native-read","content":"native contents","is_error":false}]}}'
                rpc() {
                  response=$(curl -sS -X POST -H 'Content-Type: application/json' --data "$1" "$BYTEQUAY_NEW_FLOW_MCP_URL") || exit 40
                  case "$response" in *'"isError":true'*) exit 41;; esac
                }
                rpc '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_pick_conflict_context","arguments":{}}}'
                rpc '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"one.txt"}}}'
                rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"write_file","arguments":{"path":"one.txt","content":"resolved one\\n"}}}'
                rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"commit_pick_repair","arguments":{}}}'
                echo '{"type":"result","subtype":"success","result":"conflict repaired"}'
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        return executable;
    }

    private record ActiveConflict(String sha, PickResult result) {}

    private record ConflictRange(
            Path upstream,
            Path target,
            Path worktree,
            List<String> commits)
    {
        private static ConflictRange create(Path root)
                throws IOException, InterruptedException
        {
            Path upstream = root.resolve("upstream");
            Path target = root.resolve("target");
            Path worktree = root.resolve("linked-task-worktree");
            run(root, "git", "init", "-b", "main", upstream.toString());
            configure(upstream);
            write(upstream, "one.txt", "base one\n");
            write(upstream, "two.txt", "base two\n");
            run(upstream, "git", "add", "-A");
            run(upstream, "git", "commit", "-m", "base");
            run(root, "git", "clone", upstream.toString(), target.toString());
            configure(target);

            write(upstream, "one.txt", "upstream one\n");
            run(upstream, "git", "add", "-A");
            run(upstream, "git", "commit", "-m", "change one upstream");
            String first = git(upstream, "rev-parse", "HEAD").strip();
            write(upstream, "two.txt", "upstream two\n");
            run(upstream, "git", "add", "-A");
            run(upstream, "git", "commit", "-m", "change two upstream");
            String second = git(upstream, "rev-parse", "HEAD").strip();

            write(target, "one.txt", "fork one\n");
            write(target, "two.txt", "fork two\n");
            run(target, "git", "add", "-A");
            run(target, "git", "commit", "-m", "fork changes");
            run(target, "git", "worktree", "add", "-b", "task",
                    worktree.toString());
            return new ConflictRange(
                    upstream, target, worktree, List.of(first, second));
        }

        private static void configure(Path repository)
                throws IOException, InterruptedException
        {
            run(repository, "git", "config", "user.email",
                    "test@bytequay.invalid");
            run(repository, "git", "config", "user.name", "Test");
        }

        private static void write(Path root, String name, String content)
                throws IOException
        {
            Files.writeString(
                    root.resolve(name), content, StandardCharsets.UTF_8);
        }

        private static String git(Path directory, String... argv)
                throws IOException, InterruptedException
        {
            Process process = new ProcessBuilder(argvWithGit(argv))
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException(
                        String.join(" ", argv) + " failed: " + output);
            }
            return output;
        }

        private static void run(Path directory, String... argv)
                throws IOException, InterruptedException
        {
            Process process = new ProcessBuilder(argv)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException(
                        String.join(" ", argv) + " failed: " + output);
            }
        }

        private static String[] argvWithGit(String[] arguments)
        {
            String[] argv = new String[arguments.length + 1];
            argv[0] = "git";
            System.arraycopy(arguments, 0, argv, 1, arguments.length);
            return argv;
        }
    }

    /** A git clone with a real remote, and a script standing in for the CLI. */
    private static final class Fixture
    {
        private final Path worktree;
        private final Path marker;
        private final NewFlowAgentToolBridge bridge;
        private final NewFlowAgentPermissions permissions;
        private final NewFlowCliTurn turn;
        private final NewFlowAgentLaunches.Binding binding;
        private final ToolExecutor executor;

        private Fixture(
                Path worktree,
                Path marker,
                Path executable,
                Path database,
                int serverPort,
                ToolExecutor executor,
                String provider,
                String model)
        {
            this.worktree = worktree;
            this.marker = marker;
            this.bridge = new NewFlowAgentToolBridge(MAPPER);
            this.permissions = new NewFlowAgentPermissions(MAPPER);
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database + "?foreign_keys=ON");
            FlowRuntimeSchema.install(dataSource);
            // Empty on purpose: this run has no session yet, so the turn asks
            // for a resume id and correctly gets none.
            this.turn = new NewFlowCliTurn(
                    new FlowRuntime(dataSource, Clock.systemUTC()),
                    bridge, permissions,
                    MAPPER, serverPort);
            this.executor = executor;
            this.binding = new NewFlowAgentLaunches.Binding(
                    RUN_ID,
                    provider,
                    AgentExecution.CLI,
                    null,
                    null,
                    model,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "r1",
                    "prompt-digest",
                    "tool-digest",
                    null,
                    null,
                    executable.toString(),
                    "1.0",
                    "binding-digest",
                    Instant.EPOCH);
        }

        static Fixture create(Path root, String script)
                throws IOException, InterruptedException
        {
            return create(
                    root, script, false, 1,
                    call -> ToolExecutor.ToolCallResult.ok(""));
        }

        static Fixture create(
                Path root,
                String script,
                int serverPort,
                ToolExecutor executor)
                throws IOException, InterruptedException
        {
            return create(root, script, false, serverPort, executor);
        }

        static Fixture createLinked(Path root, String script)
                throws IOException, InterruptedException
        {
            return create(
                    root, script, true, 1,
                    call -> ToolExecutor.ToolCallResult.ok(""));
        }

        private static Fixture create(
                Path root,
                String script,
                boolean linked,
                int serverPort,
                ToolExecutor executor)
                throws IOException, InterruptedException
        {
            Path remote = root.resolve("remote.git");
            Path clone = root.resolve("clone");
            run(root, "git", "init", "--bare", "-b", "main", remote.toString());
            run(root, "git", "clone", remote.toString(), clone.toString());
            run(clone, "git", "config", "user.email", "t@example.com");
            run(clone, "git", "config", "user.name", "Test");
            Files.writeString(clone.resolve("a.txt"), "one\n",
                    StandardCharsets.UTF_8);
            run(clone, "git", "add", "a.txt");
            run(clone, "git", "commit", "-m", "first");
            Path worktree = clone;
            if (linked) {
                worktree = root.resolve("linked");
                run(clone, "git", "worktree", "add", "-b", "linked",
                        worktree.toString());
            }

            Path executable = root.resolve("fake-cli");
            Files.writeString(executable, "#!/bin/sh\n" + script,
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(executable,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
            return new Fixture(
                    worktree, root.resolve("marker.txt"), executable,
                    root.resolve("runtime.db"), serverPort, executor,
                    "claude-code", "sonnet");
        }

        static Fixture createCodex(Path root, String script)
                throws IOException, InterruptedException
        {
            return createCodex(
                    root, script, 1,
                    call -> ToolExecutor.ToolCallResult.ok(""));
        }

        static Fixture createCodex(
                Path root,
                String script,
                int serverPort,
                ToolExecutor executor)
                throws IOException, InterruptedException
        {
            Path remote = root.resolve("remote.git");
            Path clone = root.resolve("clone");
            run(root, "git", "init", "--bare", "-b", "main", remote.toString());
            run(root, "git", "clone", remote.toString(), clone.toString());
            run(clone, "git", "config", "user.email", "t@example.com");
            run(clone, "git", "config", "user.name", "Test");
            Files.writeString(clone.resolve("a.txt"), "one\n",
                    StandardCharsets.UTF_8);
            run(clone, "git", "add", "a.txt");
            run(clone, "git", "commit", "-m", "first");
            Path executable = root.resolve("fake-codex");
            Files.writeString(executable, "#!/bin/sh\n" + script,
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(executable,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
            return new Fixture(
                    clone, root.resolve("marker.txt"), executable,
                    root.resolve("runtime.db"), serverPort, executor,
                    "codex", "gpt-5");
        }

        /** Captures what the turn reported, in place of the runtime writes. */
        private final List<String> usage = new ArrayList<>();
        private final List<StreamEvent> activity = new CopyOnWriteArrayList<>();

        NewFlowCliTurn.Outcome run(GroupSeen seen)
        {
            return turn.runInWorktree(
                    RUN_ID,
                    binding,
                    manifest(),
                    "be exact",
                    executor,
                    worktree,
                    journal(seen),
                    () -> false);
        }

        NewFlowCliTurn.Outcome run(String permissionOwnerId, GroupSeen seen)
        {
            return turn.runInWorktree(
                    RUN_ID,
                    permissionOwnerId,
                    binding,
                    manifest(),
                    "be exact",
                    executor,
                    worktree,
                    journal(seen),
                    () -> false);
        }

        NewFlowCliTurn.Outcome runReadOnly(GroupSeen seen)
        {
            return turn.runReadOnly(
                    RUN_ID,
                    binding,
                    manifest(),
                    "be exact",
                    executor,
                    journal(seen),
                    () -> false);
        }

        private static ArrayNode manifest()
        {
            var tools = MAPPER.createArrayNode();
            tools.addObject().put("name", "test_tool")
                    .putObject("inputSchema").put("type", "object");
            return tools;
        }

        private NewFlowCliTurn.TurnJournal journal(GroupSeen seen)
        {
            return new NewFlowCliTurn.TurnJournal()
            {
                @Override
                public void group(long pid, long pgid, Instant startedAt)
                {
                    seen.at(pid, pgid, startedAt);
                }

                @Override
                public void usage(
                        String providerSessionId,
                        long tokensIn,
                        long tokensOut,
                        long costMilliUsd)
                {
                    usage.add(providerSessionId + "/" + tokensIn + "/"
                            + tokensOut + "/" + costMilliUsd);
                }

                @Override
                public void activity(StreamEvent event)
                {
                    activity.add(event);
                }
            };
        }

        int push()
                throws IOException, InterruptedException
        {
            return new ProcessBuilder(
                    "git", "push", "origin", "HEAD:refs/heads/main")
                    .directory(worktree.toFile())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        }

        private static void run(Path directory, String... argv)
                throws IOException, InterruptedException
        {
            Process process = new ProcessBuilder(argv)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException(
                        String.join(" ", argv) + " failed: " + output);
            }
        }
    }
}
