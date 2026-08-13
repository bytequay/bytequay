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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestLocalChecks
{
    private static final Instant NOW = Instant.parse("2026-08-11T10:15:30Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    private Path temporaryDirectory;

    private JdbcTemplate jdbc;
    private MutableClock clock;
    private FlowRuntime runtime;
    private LocalChecks localChecks;
    private Path repository;
    private Path worktree;
    private Task task;
    private ActiveWriter writer;
    private LocalCheckPolicyRevision policy;

    @BeforeEach
    void setUp()
    {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("runtime.db")
                        + "?foreign_keys=ON");
        FlowRuntimeSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock(NOW);
        runtime = new FlowRuntime(dataSource, clock);
        localChecks = new LocalChecks(dataSource, runtime, clock);

        repository = temporaryDirectory.resolve("repository");
        worktree = temporaryDirectory.resolve("worktree");
        initializeRepository(repository, worktree);
        String head = git(repository, "rev-parse", "HEAD");
        task = FlowRuntimeTestSupport.startTask(runtime,
                "request-1",
                "repo-1",
                "Implement",
                worktree.toString());
        worktree = Path.of(task.worktreePath());
        FlowRuntimeTestSupport.provisionTask(runtime, claim(OperationKind.PROVISION_TASK), head);
        task = runtime.task(task.taskId()).orElseThrow();
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        WriterFence fence = FlowRuntimeTestSupport.acquireWriterFixture(
                runtime,
                turn,
                AgentRole.TASK_AGENT,
                new WorktreeSnapshot(
                        head,
                        "admission-tree:" + head,
                        "admission:" + head),
                TTL);
        AgentRun run = runtime.startWriterAgent(
                turn, fence, "prompt:task", "capabilities:task");
        writer = new ActiveWriter(turn, fence, run);
        policy = recordPolicy("policy:v1", "/usr/bin/true",
                List.of(), Duration.ofSeconds(5));
    }

    @Test
    void appendsAttemptsAndSelectsOnlyTheLatestExactEvidence()
    {
        List<LocalCheckRun> attempts = inWriter(capability -> {
            LocalCheckRun first = capability.runChecks(
                    localChecks, repository, null).getFirst();
            LocalCheckRun second = capability.runChecks(
                    localChecks, repository, null).getFirst();
            return List.of(first, second);
        });
        LocalCheckRun first = attempts.get(0);
        LocalCheckRun second = attempts.get(1);
        String changeSet = first.changeSetRevisionId();

        assertThat(first.conclusion()).isEqualTo(LocalCheckConclusion.PASSED);
        assertThat(second.conclusion()).isEqualTo(LocalCheckConclusion.PASSED);
        assertThat(first.attemptSequence()).isEqualTo(1);
        assertThat(second.attemptSequence()).isEqualTo(2);
        assertThat(first.changeSetRevisionId()).isEqualTo(changeSet);
        assertThat(second.changeSetRevisionId()).isEqualTo(changeSet);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_change_set_revision",
                Integer.class)).isEqualTo(1);
        assertThat(localChecks.requiredEvidence(
                task.taskId(), changeSet, GateIntent.INITIAL_PUBLISH)
                .checkRunRefs()).containsExactly(second.checkRunId());

        advancePolicy(
                "policy:v2", "/usr/bin/true", List.of(),
                Duration.ofSeconds(5));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_local_check_run",
                Integer.class)).isEqualTo(2);
        assertThat(localChecks.requiredEvidence(
                task.taskId(), changeSet, GateIntent.INITIAL_PUBLISH)
                .checkRunRefs()).isEmpty();
    }

    @Test
    void agentSelectedCommandExecutesAndPersistsExactInvocation()
            throws Exception
    {
        Files.createDirectory(worktree.resolve("backend"));
        advancePolicy(
                "policy:v2",
                "/bin/sh",
                List.of("-c", "exit 91"),
                Duration.ofSeconds(5));
        List<String> command = List.of(
                "/bin/sh", "-c", "printf 'narrow validation\\n'");

        LocalCheckRun run = inWriter(capability -> capability.runChecks(
                localChecks, repository, command, "backend").getFirst());

        assertThat(run.conclusion()).isEqualTo(LocalCheckConclusion.PASSED);
        assertThat(run.outputText()).isEqualTo("narrow validation\n");
        assertThat(run.command()).containsExactlyElementsOf(command);
        assertThat(run.workingDirectory()).isEqualTo("backend");
        assertThat(localChecks.run(run.checkRunId())).contains(run);
    }

    @Test
    void agentSelectedCommandCannotChangeTheFrozenExecutable()
    {
        inWriter(capability -> {
            assertThatThrownBy(() -> capability.runChecks(
                    localChecks,
                    repository,
                    List.of("/usr/bin/false"),
                    "."))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "executable is not allowed by the local-check policy");
            return true;
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_local_check_run",
                Integer.class)).isZero();
    }

    @Test
    void agentSelectedWorkingDirectoryCannotEscapeTheWorktree()
    {
        inWriter(capability -> {
            assertThatThrownBy(() -> capability.runChecks(
                    localChecks,
                    repository,
                    List.of("/usr/bin/true"),
                    "../outside"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsafe relative path");
            return true;
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_local_check_run",
                Integer.class)).isZero();
    }

    @Test
    void cappedOutputStillDrainsToEof()
    {
        advancePolicy(
                "policy:v2",
                "/usr/bin/jot",
                List.of("-b", "output", "300000"),
                Duration.ofSeconds(10));

        LocalCheckRun run = inWriter(capability -> capability.runChecks(
                localChecks, repository, null).getFirst());

        assertThat(run.conclusion()).isEqualTo(LocalCheckConclusion.PASSED);
        assertThat(run.outputTruncated()).isTrue();
        assertThat(run.outputText().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(256 * 1024);
        assertThat(run.unavailableReasonCode()).isNull();
    }

    @Test
    void sensitiveOversizedOutputIsOmittedWholesale()
    {
        String home = System.getenv("HOME");
        assertThat(home).isNotBlank();
        LocalCheckPolicyRevision current = localChecks.currentPolicy(
                task.repositoryId()).orElseThrow();
        localChecks.recordPolicy(
                task.repositoryId(),
                current.policyRevisionId(),
                "policy:v2",
                "digest:policy:v2",
                List.of(new LocalChecks.ProfileDefinition(
                        "required",
                        List.of(
                                "/bin/sh",
                                "-c",
                                "i=0; while [ $i -lt 30000 ]; do "
                                        + "printf %s \"$HOME\"; "
                                        + "i=$((i+1)); done; "
                                        + "printf %.3s \"$HOME\""),
                        ".",
                        List.of("HOME"),
                        Duration.ofSeconds(5),
                        List.of(GateIntent.INITIAL_PUBLISH))));

        LocalCheckRun run = inWriter(capability -> capability.runChecks(
                localChecks, repository, null).getFirst());

        assertThat(run.conclusion()).isEqualTo(LocalCheckConclusion.PASSED);
        assertThat(run.outputText())
                .isEqualTo("local check output omitted because allowlisted "
                        + "environment values were present\n")
                .doesNotContain(home)
                .doesNotContain(home.substring(0, Math.min(3, home.length())));
        assertThat(run.outputTruncated()).isTrue();
    }

    @Test
    void twoAllowlistedValuesOmitAllChildOutput()
    {
        String home = System.getenv("HOME");
        String user = System.getenv("USER");
        assertThat(home).isNotBlank();
        assertThat(user).isNotBlank();
        LocalCheckPolicyRevision current = localChecks.currentPolicy(
                task.repositoryId()).orElseThrow();
        localChecks.recordPolicy(
                task.repositoryId(),
                current.policyRevisionId(),
                "policy:v2",
                "digest:policy:v2",
                List.of(new LocalChecks.ProfileDefinition(
                        "required",
                        List.of(
                                "/bin/sh",
                                "-c",
                                "printf '%s:%s' \"$HOME\" \"$USER\""),
                        ".",
                        List.of("HOME", "USER"),
                        Duration.ofSeconds(5),
                        List.of(GateIntent.INITIAL_PUBLISH))));

        LocalCheckRun run = inWriter(capability -> capability.runChecks(
                localChecks, repository, null).getFirst());

        assertThat(run.outputText())
                .isEqualTo("local check output omitted because allowlisted "
                        + "environment values were present\n")
                .doesNotContain(home)
                .doesNotContain(user);
        assertThat(run.outputTruncated()).isTrue();
    }

    @Test
    void genuineUnavailableRunRoundTripsANullExitCodeAsEvidence()
    {
        advancePolicy(
                "policy:v2",
                "/does/not/exist",
                List.of(),
                Duration.ofSeconds(5));

        LocalCheckRun run = inWriter(capability -> capability.runChecks(
                localChecks, repository, null).getFirst());

        assertThat(run.conclusion())
                .isEqualTo(LocalCheckConclusion.UNAVAILABLE);
        assertThat(run.exitCode()).isNull();
        assertThat(run.unavailableReasonCode())
                .isEqualTo("EXECUTABLE_UNAVAILABLE");
        assertThat(localChecks.requiredEvidence(
                task.taskId(),
                run.changeSetRevisionId(),
                GateIntent.INITIAL_PUBLISH).blockerCodes()).isEmpty();
    }

    @Test
    void delayedPolicyPublicationCannotReplaceCurrentOrMoveReplay()
    {
        LocalCheckPolicyRevision second = advancePolicy(
                "policy:v2", "/usr/bin/true", List.of(),
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> localChecks.recordPolicy(
                task.repositoryId(),
                policy.policyRevisionId(),
                "delayed-policy:v3",
                "digest:delayed-policy:v3",
                List.of(profile("/usr/bin/true", List.of(),
                        Duration.ofSeconds(5)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current revision changed");
        assertThat(localChecks.recordPolicy(
                task.repositoryId(),
                null,
                "policy:v1",
                "digest:policy:v1",
                List.of(profile("/usr/bin/true", List.of(),
                        Duration.ofSeconds(5)))))
                .isEqualTo(policy);
        assertThat(localChecks.currentPolicy(task.repositoryId()))
                .contains(second);
    }

    @Test
    void policyRejectsFractionalSecondTimeout()
    {
        assertThatThrownBy(() -> profile(
                "/usr/bin/true", List.of(), Duration.ofMillis(1_500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void frozenBatchRejectsPolicyPublishedBeforeExecution()
    {
        Path sentinel = temporaryDirectory.resolve("must-not-run");
        advancePolicy(
                "policy:v2",
                "/usr/bin/touch",
                List.of(sentinel.toString()),
                Duration.ofSeconds(5));
        inWriter(capability -> {
            LocalChecks.PreparedLocalCheckBatch prepared =
                    localChecks.prepareBatch(writer.run().runId(), null);
            advancePolicy(
                    "policy:v3", "/usr/bin/true", List.of(),
                    Duration.ofSeconds(5));
            assertThatThrownBy(() -> capability.callTool(() ->
                    localChecks.runAndRecord(
                            prepared,
                            writer.claim(),
                            writer.fence(),
                            repository,
                            () -> {})))
                    .isInstanceOf(
                            FlowRuntime.StaleWriterFenceException.class)
                    .hasMessageContaining("policy changed");
            return true;
        });
        assertThat(sentinel).doesNotExist();
    }

    @Test
    void policyAdvanceDuringCommandRejectsTheUncommittedAttempt()
    {
        Path started = temporaryDirectory.resolve("check-started");
        Path release = temporaryDirectory.resolve("check-release");
        advancePolicy(
                "policy:v2",
                "/bin/sh",
                List.of(
                        "-c",
                        "/usr/bin/touch '" + started + "'; "
                                + "while [ ! -f '" + release + "' ]; do "
                                + "/bin/sleep 0.05; done"),
                Duration.ofSeconds(10));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InProcessWriterAgentSupervisor supervisor =
                new InProcessWriterAgentSupervisor(runtime);
        var handle = FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    try {
                        capability.runChecks(localChecks, repository, null);
                    }
                    catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        awaitFile(started);
        advancePolicy(
                "policy:v3", "/usr/bin/true", List.of(),
                Duration.ofSeconds(5));
        write(release, "release\n");
        supervisor.awaitAndFinish(handle, TTL);

        assertThat(failure.get())
                .isInstanceOf(FlowRuntime.StaleWriterFenceException.class)
                .hasMessageContaining("authority changed");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_local_check_run",
                Integer.class)).isZero();
    }

    @Test
    void renewedLiveExecutionRejectsPoisonAndKeepsLaterExpiryOnRedelivery()
            throws Exception
    {
        advancePolicy(
                "policy:v2", "/usr/bin/true", List.of(),
                Duration.ofMinutes(10));
        CountDownLatch renewed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InProcessWriterAgentSupervisor supervisor =
                new InProcessWriterAgentSupervisor(runtime);
        var handle = FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    capability.runChecks(localChecks, repository, null);
                    renewed.countDown();
                    await(release);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofMinutes(6));

        assertThat(FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                ignored -> {
                    throw new AssertionError("redelivery launched a new body");
                })).isEqualTo(handle);
        Claim futureClaim = new Claim(
                writer.claim().operationId(),
                writer.claim().taskId(),
                writer.claim().kind(),
                writer.claim().generation(),
                writer.claim().claimToken(),
                writer.claim().workerId(),
                NOW.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                futureClaim,
                writer.fence(),
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleWriterFenceException.class);
        WriterFence futureFence = new WriterFence(
                writer.fence().taskId(),
                writer.fence().operationId(),
                writer.fence().taskEpoch(),
                writer.fence().holderKind(),
                writer.fence().fencingToken(),
                writer.fence().claimGeneration(),
                writer.fence().claimTokenDigest(),
                writer.fence().headSha(),
                writer.fence().treeDigest(),
                writer.fence().snapshotEvidenceRef(),
                NOW.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                futureFence,
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleWriterFenceException.class);
        Claim changedToken = new Claim(
                writer.claim().operationId(),
                writer.claim().taskId(),
                writer.claim().kind(),
                writer.claim().generation(),
                "changed-token",
                writer.claim().workerId(),
                writer.claim().expiresAt());
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                changedToken,
                writer.fence(),
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleClaimException.class);
        Claim changedGeneration = new Claim(
                writer.claim().operationId(),
                writer.claim().taskId(),
                writer.claim().kind(),
                writer.claim().generation() + 1,
                writer.claim().claimToken(),
                writer.claim().workerId(),
                writer.claim().expiresAt());
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                changedGeneration,
                writer.fence(),
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleClaimException.class);
        WriterFence changedHead = new WriterFence(
                writer.fence().taskId(),
                writer.fence().operationId(),
                writer.fence().taskEpoch(),
                writer.fence().holderKind(),
                writer.fence().fencingToken(),
                writer.fence().claimGeneration(),
                writer.fence().claimTokenDigest(),
                "f".repeat(40),
                writer.fence().treeDigest(),
                writer.fence().snapshotEvidenceRef(),
                writer.fence().expiresAt());
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                changedHead,
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleWriterFenceException.class);

        release.countDown();
        assertThat(supervisor.awaitAndFinish(handle, TTL).terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                ignored -> null))
                .isInstanceOf(FlowRuntime.StaleClaimException.class);
    }

    private <T> T inWriter(
            Function<InProcessWriterAgentSupervisor.WriterToolCapability, T>
                    body)
    {
        AtomicReference<T> result = new AtomicReference<>();
        InProcessWriterAgentSupervisor supervisor =
                new InProcessWriterAgentSupervisor(runtime);
        var handle = FlowRuntimeTestSupport.launchWriterFixture(
                supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    result.set(body.apply(capability));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(supervisor.awaitAndFinish(handle, TTL).terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        return result.get();
    }

    private LocalCheckPolicyRevision advancePolicy(
            String source,
            String executable,
            List<String> arguments,
            Duration timeout)
    {
        LocalCheckPolicyRevision current = localChecks.currentPolicy(
                task.repositoryId()).orElseThrow();
        return localChecks.recordPolicy(
                task.repositoryId(),
                current.policyRevisionId(),
                source,
                "digest:" + source,
                List.of(profile(executable, arguments, timeout)));
    }

    private LocalCheckPolicyRevision recordPolicy(
            String source,
            String executable,
            List<String> arguments,
            Duration timeout)
    {
        return localChecks.recordPolicy(
                task.repositoryId(),
                null,
                source,
                "digest:" + source,
                List.of(profile(executable, arguments, timeout)));
    }

    private static LocalChecks.ProfileDefinition profile(
            String executable, List<String> arguments, Duration timeout)
    {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        return new LocalChecks.ProfileDefinition(
                "required",
                command,
                ".",
                List.of(),
                timeout,
                List.of(GateIntent.INITIAL_PUBLISH));
    }

    private Claim claim(OperationKind expected)
    {
        Claim claimed = runtime.claimNext("worker", TTL).orElseThrow();
        assertThat(claimed.kind()).isEqualTo(expected);
        return claimed;
    }

    private static void awaitFile(Path path)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("check did not start");
            }
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void initializeRepository(Path repository, Path worktree)
    {
        createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "ByteQuay Test");
        git(repository, "config", "user.email", "test@bytequay.invalid");
        write(repository.resolve("base.txt"), "base\n");
        git(repository, "add", "base.txt");
        git(repository, "commit", "-m", "base");
        git(repository, "worktree", "add", "-b", "task/change",
                worktree.toString());
    }

    private static String git(Path directory, String... arguments)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output.strip();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path path, String content)
    {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void createDirectories(Path path)
    {
        try {
            Files.createDirectories(path);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record ActiveWriter(Claim claim, WriterFence fence, AgentRun run) {}

    private static final class MutableClock
            extends Clock
    {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial)
        {
            instant = new AtomicReference<>(initial);
        }

        private void advance(Duration duration)
        {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return instant.get();
        }
    }
}
