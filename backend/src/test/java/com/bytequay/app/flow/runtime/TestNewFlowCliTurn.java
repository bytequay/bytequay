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

import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    /** A git clone with a real remote, and a script standing in for the CLI. */
    private static final class Fixture
    {
        private final Path worktree;
        private final Path marker;
        private final NewFlowAgentToolBridge bridge;
        private final NewFlowCliTurn turn;
        private final NewFlowAgentLaunches.Binding binding;

        private Fixture(
                Path worktree, Path marker, Path executable, Path database)
        {
            this.worktree = worktree;
            this.marker = marker;
            this.bridge = new NewFlowAgentToolBridge(MAPPER);
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:sqlite:" + database + "?foreign_keys=ON");
            FlowRuntimeSchema.install(dataSource);
            // Empty on purpose: this run has no session yet, so the turn asks
            // for a resume id and correctly gets none.
            this.turn = new NewFlowCliTurn(
                    new FlowRuntime(dataSource, Clock.systemUTC()),
                    bridge, MAPPER, 1);
            this.binding = new NewFlowAgentLaunches.Binding(
                    RUN_ID,
                    "claude-code",
                    AgentExecution.CLI,
                    null,
                    null,
                    "sonnet",
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
            Path remote = root.resolve("remote.git");
            Path worktree = root.resolve("clone");
            run(root, "git", "init", "--bare", "-b", "main", remote.toString());
            run(root, "git", "clone", remote.toString(), worktree.toString());
            run(worktree, "git", "config", "user.email", "t@example.com");
            run(worktree, "git", "config", "user.name", "Test");
            Files.writeString(worktree.resolve("a.txt"), "one\n",
                    StandardCharsets.UTF_8);
            run(worktree, "git", "add", "a.txt");
            run(worktree, "git", "commit", "-m", "first");

            Path executable = root.resolve("fake-cli");
            Files.writeString(executable, "#!/bin/sh\n" + script,
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(executable,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
            return new Fixture(
                    worktree, root.resolve("marker.txt"), executable,
                    root.resolve("runtime.db"));
        }

        /** Captures what the turn reported, in place of the runtime writes. */
        private final List<String> usage = new ArrayList<>();

        NewFlowCliTurn.Outcome run(GroupSeen seen)
        {
            return turn.runInWorktree(
                    RUN_ID,
                    binding,
                    MAPPER.createArrayNode(),
                    "be exact",
                    call -> ToolExecutor.ToolCallResult.ok(""),
                    worktree,
                    journal(seen),
                    () -> false);
        }

        NewFlowCliTurn.Outcome runReadOnly(GroupSeen seen)
        {
            return turn.runReadOnly(
                    RUN_ID,
                    binding,
                    MAPPER.createArrayNode(),
                    "be exact",
                    call -> ToolExecutor.ToolCallResult.ok(""),
                    journal(seen),
                    () -> false);
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
