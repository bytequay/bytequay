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

import com.bytequay.app.flow.runtime.FlowRuntime.StaleClaimException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleOwnerRevisionException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleWriterFenceException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GitHubRepositoryLocator;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestFlowRuntimeChangeSets
{
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    private Path temporaryDirectory;

    private JdbcTemplate jdbc;
    private FlowRuntime runtime;
    private Path repository;
    private Path worktree;
    private String baseSha;
    private Task task;
    private Claim provisionClaim;

    @BeforeEach
    void setUp()
    {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("runtime.db")
                        + "?foreign_keys=ON");
        FlowRuntimeSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(
                dataSource,
                Clock.fixed(
                        Instant.parse("2026-08-10T10:15:30Z"),
                        ZoneOffset.UTC));

        repository = temporaryDirectory.resolve("repository");
        worktree = temporaryDirectory.resolve("worktree");
        initializeRepository(repository, worktree);
        baseSha = git(repository, "rev-parse", "HEAD");
        task = FlowRuntimeTestSupport.startTask(runtime,
                "request-1",
                "repo-1",
                "Implement",
                worktree.toString());
        worktree = Path.of(task.worktreePath());
        provisionClaim = claim(OperationKind.PROVISION_TASK);
        FlowRuntimeTestSupport.provisionTask(runtime, provisionClaim, baseSha);
        task = runtime.task(task.taskId()).orElseThrow();
    }

    @Test
    void provisioningCreatesOnlyOneInitialBaseRevision()
    {
        assertThat(runtime.currentBaseRevision(task.taskId()))
                .hasValueSatisfying(base -> {
                    assertThat(base.sequence()).isEqualTo(1);
                    assertThat(base.previousBaseSha()).isNull();
                    assertThat(base.baseSha()).isEqualTo(baseSha);
                    assertThat(base.reasonCode()).isEqualTo("INITIAL");
                    assertThat(base.evidenceRef()).startsWith("provision-operation:");
                });
        assertThat(runtime.currentChangeSet(task.taskId())).isEmpty();

        FlowRuntimeTestSupport.provisionTask(runtime, provisionClaim, baseSha);

        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(1);
        assertThat(count("flow_runtime_change_set_revision")).isZero();

        assertProvisionReplayRejected(new Claim(
                provisionClaim.operationId(),
                provisionClaim.taskId(),
                provisionClaim.kind(),
                provisionClaim.generation(),
                "forged-token",
                provisionClaim.workerId(),
                provisionClaim.expiresAt()));
        assertProvisionReplayRejected(new Claim(
                provisionClaim.operationId(),
                provisionClaim.taskId(),
                provisionClaim.kind(),
                provisionClaim.generation() + 1,
                provisionClaim.claimToken(),
                provisionClaim.workerId(),
                provisionClaim.expiresAt()));
        assertProvisionReplayRejected(new Claim(
                provisionClaim.operationId(),
                provisionClaim.taskId(),
                provisionClaim.kind(),
                provisionClaim.generation(),
                provisionClaim.claimToken(),
                "forged-worker",
                provisionClaim.expiresAt()));
    }

    @Test
    void provisioningRejectsRefLikeObjectIdentity()
    {
        Task invalid = FlowRuntimeTestSupport.startTask(runtime,
                "request-invalid",
                "repo-1",
                "Invalid provision",
                temporaryDirectory.resolve("invalid-worktree").toString());
        Claim invalidProvision = claim(OperationKind.PROVISION_TASK);

        assertThatThrownBy(() -> FlowRuntimeTestSupport.provisionTask(runtime,
                invalidProvision, "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full object ID");
        assertThat(runtime.currentBaseRevision(invalid.taskId())).isEmpty();
    }

    @Test
    void provisioningRedeliveryUsesItsInitialBaseAfterBasePointerAdvances()
    {
        ActiveWriter writer = taskWriter();
        run(writer, () -> {});
        String advancedBase = "c".repeat(40);
        String advancedRevisionId = "base-advanced";
        jdbc.update(
                """
                INSERT INTO flow_runtime_task_base_revision (
                    base_revision_id, task_id, sequence, previous_base_sha,
                    base_sha, reason_code, evidence_ref,
                    source_operation_id, recorded_at
                ) VALUES (?, ?, 2, ?, ?, 'EXPLICIT_RECONCILIATION', ?, ?, ?)
                """,
                advancedRevisionId,
                task.taskId(),
                baseSha,
                advancedBase,
                "future-base-evidence",
                writer.claim().operationId(),
                Instant.parse("2026-08-10T10:16:30Z").toEpochMilli());
        jdbc.update(
                """
                UPDATE flow_runtime_task
                SET current_base_sha = ?, current_base_revision_id = ?
                WHERE task_id = ?
                """,
                advancedBase,
                advancedRevisionId,
                task.taskId());

        FlowRuntimeTestSupport.provisionTask(runtime, provisionClaim, baseSha);

        assertThat(runtime.currentBaseRevision(task.taskId()))
                .hasValueSatisfying(base -> assertThat(base.baseSha())
                        .isEqualTo(advancedBase));
        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(2);
    }

    @Test
    void oneTaskWriterAdoptsTwoHeadsAndRedeliversTheSameRevision()
    {
        ActiveWriter writer = taskWriter();
        AtomicReference<ChangeSetRevision> first = new AtomicReference<>();
        AtomicReference<ChangeSetRevision> duplicate = new AtomicReference<>();
        AtomicReference<ChangeSetRevision> second = new AtomicReference<>();
        AtomicBoolean fenceStayedValid = new AtomicBoolean();
        AtomicBoolean movedRedeliveryRejected = new AtomicBoolean();

        run(writer, () -> {
            commit("task.txt", "one\n", "one");
            first.set(runtime.adoptChangeSet(
                    writer.claim(), writer.fence(), repository, null));
            duplicate.set(runtime.adoptChangeSet(
                    writer.claim(), writer.fence(), repository, null));
            runtime.assertWriterFence(writer.claim(), writer.fence());
            commit("task.txt", "two\n", "two");
            try {
                runtime.adoptChangeSet(
                        writer.claim(), writer.fence(), repository, null);
            }
            catch (StaleOwnerRevisionException expected) {
                movedRedeliveryRejected.set(true);
            }
            second.set(runtime.adoptChangeSet(
                    writer.claim(), writer.fence(), repository,
                    first.get().changeSetRevisionId()));
            runtime.assertWriterFence(writer.claim(), writer.fence());
            fenceStayedValid.set(true);
        });

        assertThat(duplicate.get()).isEqualTo(first.get());
        assertThat(second.get().previousChangeSetRevisionId())
                .isEqualTo(first.get().changeSetRevisionId());
        assertThat(second.get().previousHeadSha()).isEqualTo(first.get().headSha());
        assertThat(second.get().source()).isEqualTo(ChangeSetSource.TASK_AGENT);
        assertThat(second.get().sourceRunId()).isEqualTo(writer.run().runId());
        assertThat(movedRedeliveryRejected).isTrue();
        assertThat(fenceStayedValid).isTrue();
        assertThat(runtime.currentChangeSet(task.taskId())).contains(second.get());
        assertThat(count("flow_runtime_change_set_revision")).isEqualTo(2);
        FlowRuntimeTestSupport.provisionTask(runtime, provisionClaim, baseSha);
        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(1);
        assertThatThrownBy(() -> runtime.materializePullRequest(
                task.taskId(), first.get().changeSetRevisionId(),
                "main", "main", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active Task head");
        assertThat(count("flow_runtime_pr")).isZero();
    }

    @Test
    void ciAdoptionUsesItsExactRunAndResultReadyUsesAdoptedHead()
    {
        ActiveWriter taskWriter = taskWriter();
        AtomicReference<ChangeSetRevision> initial = new AtomicReference<>();
        run(taskWriter, () -> {
            commit("task.txt", "task\n", "task");
            initial.set(runtime.adoptChangeSet(
                    taskWriter.claim(), taskWriter.fence(), repository, null));
        });
        var localPr = runtime.materializePullRequest(
                task.taskId(), initial.get().changeSetRevisionId(),
                "main", "main", "main");
        var publishedPr = FlowRuntimeTestSupport.bindGitHubFixture(runtime,
                localPr.prId(), initial.get().headSha(),
                new GitHubRepositoryLocator(
                        "repo", "octocat", "bytequay"),
                new GitHubRepositoryLocator(
                        "head-repo", "octocat", "bytequay"), 42,
                "PR_42", "https://example.test/42", "receipt:42");
        runtime.registerFinalRed(
                "round-1", task.taskId(), localPr.prId(),
                initial.get().headSha(), "ci:round-1");
        selectReconciliation();

        ActiveWriter ciWriter = writer(
                OperationKind.RUN_CI_FIXER, AgentRole.CI_FIXER);
        AtomicReference<ChangeSetRevision> fixed = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                ciWriter.run().runId(),
                ciWriter.claim(),
                ciWriter.fence(),
                "TEST_CI_FINALIZER",
                (runId, claim, fence, completion) -> {
                    var prepared = runtime.prepareChangeSet(
                            claim,
                            fence,
                            repository,
                            initial.get().changeSetRevisionId());
                    ChangeSetRevision output = runtime.adoptPreparedChangeSet(
                            claim, fence, prepared);
                    fixed.set(output);
                    return runtime.finishCiAgentRun(
                            runId,
                            claim,
                            fence,
                            completion.terminalOutcome(),
                            completion.finalContent(),
                            completion.errorRef(),
                            "attempt-1",
                            CiFixOutcome.FIX_PREPARED,
                            output.headSha(),
                            output.changeSetRevisionId());
                },
                capability -> {
                    commit("ci.txt", "fixed\n", "ci fix");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        supervisor.awaitAndFinalize(handle, TTL, "TEST_CI_FINALIZER");

        assertThat(fixed.get().source()).isEqualTo(ChangeSetSource.CI_FIXER);
        assertThat(fixed.get().sourceRunId()).isEqualTo(ciWriter.run().runId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(ready -> assertThat(ready.subjectHead())
                        .isEqualTo(fixed.get().headSha()));
        assertThat(runtime.materializePullRequest(
                task.taskId(), initial.get().changeSetRevisionId(),
                "main", "main", "main"))
                .isEqualTo(publishedPr);
        assertThatThrownBy(() -> runtime.materializePullRequest(
                task.taskId(), fixed.get().changeSetRevisionId(),
                "main", "main", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different PR subject");
    }

    @Test
    void dirtyUntrackedAndWrongBranchNeverAppend()
    {
        ActiveWriter writer = taskWriter();
        List<FailureCode> failures = new ArrayList<>();
        run(writer, () -> {
            write(worktree.resolve("base.txt"), "dirty\n");
            failures.add(inspectorFailure(writer, null));
            git(worktree, "reset", "--hard", "HEAD");

            write(worktree.resolve("untracked.txt"), "untracked\n");
            failures.add(inspectorFailure(writer, null));
            delete(worktree.resolve("untracked.txt"));

            git(worktree, "switch", "-c", "task/wrong");
            failures.add(inspectorFailure(writer, null));
            git(worktree, "switch", task.branchName());
        });

        assertThat(failures).containsExactly(
                FailureCode.DIRTY,
                FailureCode.DIRTY,
                FailureCode.WRONG_BRANCH);
        assertThat(count("flow_runtime_change_set_revision")).isZero();
    }

    @Test
    void rewrittenPredecessorAndStaleExpectedPointerNeverAppend()
    {
        ActiveWriter writer = taskWriter();
        AtomicReference<ChangeSetRevision> first = new AtomicReference<>();
        AtomicReference<FailureCode> predecessorFailure = new AtomicReference<>();
        AtomicBoolean stalePointerRejected = new AtomicBoolean();
        run(writer, () -> {
            commit("task.txt", "task\n", "task");
            first.set(runtime.adoptChangeSet(
                    writer.claim(), writer.fence(), repository, null));

            write(repository.resolve("main.txt"), "sibling\n");
            git(repository, "add", "main.txt");
            git(repository, "commit", "-m", "sibling");
            git(worktree, "reset", "--hard", "main");
            predecessorFailure.set(inspectorFailure(
                    writer, first.get().changeSetRevisionId()));
            git(worktree, "reset", "--hard", first.get().headSha());

            try {
                runtime.adoptChangeSet(
                        writer.claim(), writer.fence(), repository, "stale-pointer");
            }
            catch (StaleOwnerRevisionException expected) {
                stalePointerRejected.set(true);
            }
        });

        assertThat(predecessorFailure.get())
                .isEqualTo(FailureCode.PREDECESSOR_NOT_ANCESTOR);
        assertThat(stalePointerRejected).isTrue();
        assertThat(count("flow_runtime_change_set_revision")).isEqualTo(1);
    }

    @Test
    void staleClaimAndFenceCannotAdopt()
    {
        ActiveWriter writer = taskWriter();
        AtomicBoolean staleClaim = new AtomicBoolean();
        AtomicBoolean staleFence = new AtomicBoolean();
        run(writer, () -> {
            Claim forgedClaim = new Claim(
                    writer.claim().operationId(),
                    writer.claim().taskId(),
                    writer.claim().kind(),
                    writer.claim().generation(),
                    "forged-token",
                    writer.claim().workerId(),
                    writer.claim().expiresAt());
            try {
                runtime.adoptChangeSet(
                        forgedClaim, writer.fence(), repository, null);
            }
            catch (StaleClaimException expected) {
                staleClaim.set(true);
            }
            WriterFence forgedFence = new WriterFence(
                    writer.fence().taskId(),
                    writer.fence().operationId(),
                    writer.fence().taskEpoch(),
                    writer.fence().holderKind(),
                    writer.fence().fencingToken(),
                    writer.fence().claimGeneration(),
                    writer.fence().claimTokenDigest(),
                    writer.fence().headSha(),
                    "forged-tree",
                    writer.fence().snapshotEvidenceRef(),
                    writer.fence().expiresAt());
            try {
                runtime.adoptChangeSet(
                        writer.claim(), forgedFence, repository, null);
            }
            catch (StaleWriterFenceException expected) {
                staleFence.set(true);
            }
        });

        assertThat(staleClaim).isTrue();
        assertThat(staleFence).isTrue();
        assertThat(count("flow_runtime_change_set_revision")).isZero();
    }

    @Test
    void failedTaskPointerCasRollsBackTheRevision()
    {
        jdbc.execute("""
                CREATE TRIGGER fail_change_set_pointer
                BEFORE UPDATE OF current_change_set_revision_id
                ON flow_runtime_task
                BEGIN
                    SELECT RAISE(ABORT, 'forced pointer failure');
                END
                """);
        ActiveWriter writer = taskWriter();
        AtomicBoolean rejected = new AtomicBoolean();
        run(writer, () -> {
            commit("task.txt", "task\n", "task");
            try {
                runtime.adoptChangeSet(
                        writer.claim(), writer.fence(), repository, null);
            }
            catch (RuntimeException expected) {
                rejected.set(true);
            }
        });

        assertThat(rejected).isTrue();
        assertThat(count("flow_runtime_change_set_revision")).isZero();
        assertThat(runtime.currentChangeSet(task.taskId())).isEmpty();
    }

    @Test
    void unchangedTreeRevisionCannotMaterializeAPullRequest()
    {
        ActiveWriter writer = taskWriter();
        AtomicReference<ChangeSetRevision> unchanged = new AtomicReference<>();
        run(writer, () -> unchanged.set(runtime.adoptChangeSet(
                writer.claim(), writer.fence(), repository, null)));

        assertThat(unchanged.get().differsFromBase()).isFalse();
        assertThatThrownBy(() -> runtime.materializePullRequest(
                task.taskId(), unchanged.get().changeSetRevisionId(),
                "main", "main", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty Task");
        assertThat(count("flow_runtime_pr")).isZero();
    }

    private ActiveWriter taskWriter()
    {
        selectReconciliation();
        return writer(OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
    }

    private void assertProvisionReplayRejected(Claim forgedClaim)
    {
        assertThatThrownBy(() -> FlowRuntimeTestSupport.provisionTask(runtime,
                forgedClaim, baseSha))
                .isInstanceOf(StaleClaimException.class);
    }

    private ActiveWriter writer(OperationKind kind, AgentRole role)
    {
        Claim claim = claim(kind);
        Task current = runtime.task(claim.taskId()).orElseThrow();
        WriterFence fence = runtime.acquireWriterLease(
                claim,
                role,
                new WorktreeSnapshot(
                        current.currentHeadSha(),
                        "admission-tree:" + current.currentHeadSha(),
                        "admission:" + current.currentHeadSha()),
                TTL);
        AgentRun run = runtime.startWriterAgent(
                claim, fence, "prompt:" + role, "capabilities:" + role);
        return new ActiveWriter(claim, fence, run);
    }

    private void run(ActiveWriter writer, Runnable body)
    {
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    body.run();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(supervisor.awaitAndFinish(handle, TTL).terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
    }

    private FailureCode inspectorFailure(
            ActiveWriter writer, String expectedRevision)
    {
        try {
            runtime.adoptChangeSet(
                    writer.claim(), writer.fence(), repository, expectedRevision);
            throw new AssertionError("expected inspector failure");
        }
        catch (InspectionFailure failure) {
            return failure.code();
        }
    }

    private void selectReconciliation()
    {
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation)).isPresent();
    }

    private Claim claim(OperationKind expected)
    {
        Claim claim = runtime.claimNext("worker", TTL).orElseThrow();
        assertThat(claim.kind()).isEqualTo(expected);
        return claim;
    }

    private void commit(String path, String content, String message)
    {
        write(worktree.resolve(path), content);
        git(worktree, "add", path);
        git(worktree, "commit", "-m", message);
    }

    private int count(String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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
        git(
                repository,
                "worktree",
                "add",
                "-b",
                "task/change",
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
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    private static void delete(Path path)
    {
        try {
            Files.delete(path);
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
}
