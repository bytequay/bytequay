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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestTaskProvisioning
{
    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private FlowRuntime runtime;
    private Path repository;
    private Path worktreeRoot;
    private TaskProvisioning.RepositoryConfig config;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON&busy_timeout=5000"
                        + "&transaction_mode=IMMEDIATE");
        FlowRuntimeSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock(NOW);
        runtime = new FlowRuntime(dataSource, clock);
        repository = temporaryDirectory.resolve("repository");
        worktreeRoot = temporaryDirectory.resolve("worktrees");
        initializeRepository(repository);
        createDirectories(worktreeRoot);
        config = new TaskProvisioning.RepositoryConfig(
                "repo-1",
                "octocat",
                "bytequay",
                repository,
                repository.resolve(".git"),
                "origin",
                "refs/remotes/origin/main",
                worktreeRoot);
    }

    @Test
    void provisionsOneExactLocalWorktreeAndReplaysAfterTaskAdvances()
    {
        List<List<String>> commands = new ArrayList<>();
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        TaskProvisioning provisioning = provisioning(config, (root, arguments) -> {
            commands.add(List.copyOf(arguments));
            return direct.run(root, arguments);
        });
        Task started = provisioning.startTask("request-1", "repo-1", "goal");
        assertThat(started.launchBaseSha()).isNull();
        assertThat(started.branchName()).startsWith("bytequay/");
        assertThat(git(repository, "check-ref-format", "--branch",
                started.branchName())).isEqualTo(started.branchName());

        Claim claim = claim();
        provisioning.execute(claim);

        Task active = runtime.task(started.taskId()).orElseThrow();
        assertThat(active.status())
                .as(operationResult(claim.operationId()))
                .isEqualTo(TaskStatus.ACTIVE);
        assertThat(active.launchBaseSha()).isEqualTo(baseSha());
        assertThat(git(Path.of(active.worktreePath()), "rev-parse", "HEAD"))
                .isEqualTo(baseSha());
        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(1);
        assertThat(count("flow_runtime_agent_session")).isEqualTo(1);
        assertThat(count("flow_runtime_inbox")).isEqualTo(1);

        assertThatThrownBy(() -> provisioning.execute(new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation(), "wrong", claim.workerId(),
                claim.expiresAt())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provisioning.execute(new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation() + 1, claim.claimToken(), claim.workerId(),
                claim.expiresAt())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provisioning.execute(new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation(), claim.claimToken(), "wrong-worker",
                claim.expiresAt())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provisioning.execute(new Claim(
                claim.operationId(), "task:wrong", claim.kind(),
                claim.generation(), claim.claimToken(), claim.workerId(),
                claim.expiresAt())))
                .isInstanceOf(IllegalStateException.class);

        runtime.transitionTask(
                active.taskId(), active.currentLifecycleRevisionId(),
                TaskStatus.COMPLETED, "TEST_COMPLETED", "test:evidence");
        provisioning.execute(claim);
        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(1);
        assertThat(commands).allSatisfy(arguments ->
                assertThat(arguments).doesNotContain(
                        "fetch", "push", "ls-remote", "credential"));
    }

    @Test
    void repeatedWorkGetsANewBranchAndWorktree()
    {
        TaskProvisioning provisioning = provisioning(config);

        Task first = provisioning.startTask(
                "upstream-submit-1", "repo-1", "same range");
        Task second = provisioning.startTask(
                "upstream-submit-2", "repo-1", "same range");

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(second.branchName()).isNotEqualTo(first.branchName());
        assertThat(second.worktreePath()).isNotEqualTo(first.worktreePath());
    }

    @Test
    void directGitDistinguishesAnAbsentLiteralBranch()
    {
        TaskProvisioning.ProcessResult absent =
                new TaskProvisioning.DirectGitProcess().run(
                        repository,
                        List.of("rev-parse", "--verify", "--quiet",
                                "refs/heads/bytequay/absent"));
        assertThat(absent.complete()).isTrue();
        assertThat(absent.exitCode()).isEqualTo(1);
        assertThat(absent.stdout()).isEmpty();
    }

    @Test
    void responseLossReplayNeverConsultsMovedCatalogAndConflictRejects()
    {
        Task first = provisioning(config)
                .startTask("request-1", "repo-1", "goal");
        AtomicInteger reads = new AtomicInteger();
        TaskProvisioning replay = new TaskProvisioning(
                dataSource,
                runtime,
                ignored -> {
                    reads.incrementAndGet();
                    throw new AssertionError("catalog must not be read");
                },
                clock);

        assertThat(replay.startTask("request-1", "repo-1", "goal"))
                .isEqualTo(first);
        assertThat(reads).hasValue(0);
        assertThatThrownBy(() -> replay.startTask(
                "request-1", "repo-1", "different"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(0);
    }

    @Test
    void boundsTaskCommandBeforeCatalogAndReplaysExactMaximumText()
    {
        AtomicInteger reads = new AtomicInteger();
        TaskProvisioning guarded = new TaskProvisioning(
                dataSource,
                runtime,
                ignored -> {
                    reads.incrementAndGet();
                    return config;
                },
                clock);
        assertThatThrownBy(() -> guarded.startTask(
                "r".repeat(257), "repo-1", "goal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guarded.startTask(
                "request", "r".repeat(257), "goal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guarded.startTask(
                "request", "repo-1", "g".repeat(16_385)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guarded.startTask(
                "request\u0000", "repo-1", "goal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guarded.startTask(
                "request", "repo\n1", "goal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guarded.startTask(
                "request", "repo-1", "goal\u0000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reads).hasValue(0);
        assertThat(count("flow_runtime_task")).isZero();

        String request = "q".repeat(256);
        String repositoryId = "r".repeat(256);
        String goal = "g".repeat(16_382) + "\n\t";
        TaskProvisioning.RepositoryConfig maximumConfig =
                new TaskProvisioning.RepositoryConfig(
                        repositoryId,
                        config.repositoryOwner(),
                        config.repositoryName(),
                        config.repositoryRoot(),
                        config.gitCommonDir(),
                        config.remoteName(),
                        config.baseRef(),
                        config.worktreeRoot());
        Task maximum = provisioning(maximumConfig)
                .startTask(request, repositoryId, goal);
        assertThat(maximum.requestKey()).isEqualTo(request);
        assertThat(maximum.repositoryId()).isEqualTo(repositoryId);
        assertThat(maximum.goalText()).isEqualTo(goal);

        TaskProvisioning replay = new TaskProvisioning(
                dataSource,
                runtime,
                ignored -> {
                    throw new AssertionError("replay consulted repository catalog");
                },
                clock);
        assertThat(replay.startTask(request, repositoryId, goal))
                .isEqualTo(maximum);
        assertThatThrownBy(() -> replay.startTask(
                request, repositoryId, goal.substring(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transientProofRearmsImmediatelyAndTheNextGenerationCompletes()
    {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        TaskProvisioning.GitProcess onceUnavailable = (root, arguments) -> {
            if (unavailable.getAndSet(false)) {
                return new TaskProvisioning.ProcessResult(false, -1, "");
            }
            return direct.run(root, arguments);
        };
        TaskProvisioning provisioning = provisioning(config, onceUnavailable);
        Task task = provisioning.startTask("request-1", "repo-1", "goal");

        Claim first = claim();
        provisioning.execute(first);
        assertThat(runtime.operation(first.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.READY);
        assertThat(ticketState(first.operationId())).isEqualTo("AVAILABLE");

        clock.advance(Duration.ofSeconds(2));
        Claim second = claim();
        assertThat(second.generation()).isEqualTo(first.generation() + 1);
        provisioning(config).execute(second);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void completedNoEffectIsStableButIncompleteNoEffectRetries()
    {
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        TaskProvisioning.GitProcess completedFailure = (root, arguments) ->
                mutation(arguments)
                        ? new TaskProvisioning.ProcessResult(true, 1, "")
                        : direct.run(root, arguments);
        TaskProvisioning stable = provisioning(config, completedFailure);
        Task failed = stable.startTask("stable", "repo-1", "goal");
        stable.execute(claim());
        assertThat(runtime.task(failed.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(count("flow_runtime_provision_subject")).isEqualTo(1);

        setUpSecondRuntime();
        TaskProvisioning.GitProcess incomplete = (root, arguments) ->
                mutation(arguments)
                        ? new TaskProvisioning.ProcessResult(false, -1, "")
                        : direct.run(root, arguments);
        TaskProvisioning retry = provisioning(config, incomplete);
        Task pending = retry.startTask("retry", "repo-1", "goal");
        Claim retryClaim = claim();
        assertThat(retryClaim.taskId()).isEqualTo(pending.taskId());
        retry.execute(retryClaim);
        assertThat(runtime.operation(retryClaim.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.READY);
    }

    @Test
    void crashAfterGitRearmsExactWorktreeAndFreshGenerationFinalizes()
    {
        TaskProvisioning provisioning = provisioning(config);
        Task task = provisioning.startTask("request-1", "repo-1", "goal");
        Claim first = claim();
        jdbc.execute("""
                CREATE TRIGGER fail_provision_finalize
                BEFORE INSERT ON flow_runtime_agent_session
                BEGIN SELECT RAISE(ABORT, 'late failure'); END
                """);

        assertThatThrownBy(() -> provisioning.execute(first))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("late failure");
        assertThat(Files.isDirectory(Path.of(task.worktreePath()))).isTrue();
        assertThat(runtime.operation(first.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(count("flow_runtime_task_base_revision")).isZero();
        assertThat(count("flow_runtime_agent_session")).isZero();
        assertThat(count("flow_runtime_inbox")).isZero();

        clock.advance(TTL.plusSeconds(1));
        assertThat(provisioning.recover(runtime.expiredClaims().getFirst()))
                .isTrue();
        assertThat(runtime.operation(first.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.READY);
        jdbc.execute("DROP TRIGGER fail_provision_finalize");

        Claim second = claim();
        provisioning.execute(second);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
        assertThat(count("flow_runtime_task_base_revision")).isEqualTo(1);
    }

    @Test
    void expiredAbsentRedrivesButPartialAndMismatchRequireAttention()
    {
        TaskProvisioning absent = crashAfterBindingProvisioning();
        Task absentTask = absent.startTask("absent", "repo-1", "goal");
        Claim absentClaim = claim();
        assertThatThrownBy(() -> absent.execute(absentClaim))
                .hasMessage("crash after subject binding");
        clock.advance(TTL.plusSeconds(1));
        assertThat(absent.recover(runtime.expiredClaims().getFirst())).isTrue();
        assertThat(runtime.operation(absentClaim.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.READY);
        Claim second = claim();
        assertThat(second.generation()).isEqualTo(absentClaim.generation() + 1);
        provisioning(config).execute(second);
        assertThat(runtime.task(absentTask.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);

        setUpSecondRuntime();
        TaskProvisioning partial = crashAfterBindingProvisioning();
        Task partialTask = partial.startTask("partial", "repo-1", "goal");
        Claim partialClaim = claim();
        assertThatThrownBy(() -> partial.execute(partialClaim))
                .hasMessage("crash after subject binding");
        git(repository, "update-ref", "refs/heads/" + partialTask.branchName(),
                subjectBase(partialClaim.operationId()));
        clock.advance(TTL.plusSeconds(1));
        assertThat(partial.recover(runtime.expiredClaims().getFirst())).isFalse();
        assertThat(runtime.task(partialTask.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);

        setUpSecondRuntime();
        TaskProvisioning mismatch = crashAfterBindingProvisioning();
        Task mismatchTask = mismatch.startTask("mismatch", "repo-1", "goal");
        Claim mismatchClaim = claim();
        assertThatThrownBy(() -> mismatch.execute(mismatchClaim))
                .hasMessage("crash after subject binding");
        commit(repository, "mismatch.txt", "mismatch\n");
        git(repository, "worktree", "add", "-b",
                mismatchTask.branchName(), mismatchTask.worktreePath(), "HEAD");
        clock.advance(TTL.plusSeconds(1));
        assertThat(mismatch.recover(runtime.expiredClaims().getFirst())).isFalse();
        assertThat(runtime.task(mismatchTask.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
    }

    @Test
    void credentialFreeSshRemoteWorksButIdentityDriftSettlesAttention()
    {
        git(repository, "remote", "set-url", "origin",
                "git@github.com:octocat/bytequay.git");
        Task ssh = provisioning(config).startTask("ssh", "repo-1", "goal");
        provisioning(config).execute(claim());
        assertThat(runtime.task(ssh.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);

        setUpSecondRuntime();
        git(repository, "remote", "set-url", "origin",
                "https://github.com/someone-else/bytequay.git");
        Task wrong = provisioning(config).startTask("wrong", "repo-1", "goal");
        Claim wrongClaim = claim();
        provisioning(config).execute(wrongClaim);
        assertThat(runtime.task(wrong.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(operationResult(wrongClaim.operationId()))
                .isEqualTo("PROVISION_INVALID:REMOTE_IDENTITY_CHANGED");
        assertThat(count("flow_runtime_provision_subject")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT evidence_ref FROM flow_runtime_task_lifecycle_revision
                WHERE task_id = ? ORDER BY sequence DESC LIMIT 1
                """, String.class, wrong.taskId()))
                .isEqualTo("provision-operation:" + wrongClaim.operationId());
        assertThat(runtime.operation(wrongClaim.operationId())).isPresent();

        setUpSecondRuntime();
        git(repository, "remote", "set-url", "origin",
                "git@github.com:wrong/bytequay.git");
        Task wrongSsh = provisioning(config)
                .startTask("wrong-ssh", "repo-1", "goal");
        Claim wrongSshClaim = claim();
        provisioning(config).execute(wrongSshClaim);
        assertThat(runtime.task(wrongSsh.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(operationResult(wrongSshClaim.operationId()))
                .isEqualTo("PROVISION_INVALID:REMOTE_IDENTITY_CHANGED");
    }

    @Test
    void outputOverflowSettlesAttentionWithoutPersistingOutput()
    {
        String secret = "do-not-store-this-output";
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        TaskProvisioning.GitProcess overflow = (root, arguments) ->
                arguments.contains("config")
                        ? new TaskProvisioning.ProcessResult(
                                true, 0, secret, true)
                        : direct.run(root, arguments);
        Task task = provisioning(config, overflow)
                .startTask("overflow", "repo-1", "goal");
        Claim claim = claim();
        provisioning(config, overflow).execute(claim);

        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(operationResult(claim.operationId()))
                .isEqualTo("PROVISION_INVALID:GIT_OUTPUT_LIMIT")
                .doesNotContain(secret);
    }

    @Test
    void terminalReplayRejectsCorruptAttentionAndSuccessGraphs()
    {
        git(repository, "remote", "set-url", "origin",
                "https://github.com/wrong/repository.git");
        TaskProvisioning invalid = provisioning(config);
        Task attention = invalid.startTask("attention", "repo-1", "goal");
        Claim attentionClaim = claim();
        invalid.execute(attentionClaim);
        var attentionReplay = new FlowRuntimeRecords.ExpiredClaim(
                attentionClaim.operationId(), attention.taskId(),
                OperationKind.PROVISION_TASK, attentionClaim.generation(),
                attentionClaim.expiresAt(), null, null, null);
        assertThat(invalid.recover(attentionReplay)).isFalse();
        jdbc.update("""
                UPDATE flow_runtime_task_lifecycle_revision
                SET evidence_ref = 'provision-operation:wrong'
                WHERE lifecycle_revision_id = (
                    SELECT current_lifecycle_revision_id
                    FROM flow_runtime_task WHERE task_id = ?)
                """, attention.taskId());
        assertThatThrownBy(() -> invalid.recover(attentionReplay))
                .isInstanceOf(IllegalStateException.class);

        git(repository, "remote", "set-url", "origin",
                "https://github.com/octocat/bytequay.git");
        setUpSecondRuntime();
        TaskProvisioning success = provisioning(config);
        Task active = success.startTask("success", "repo-1", "goal");
        Claim successClaim = claim();
        success.execute(successClaim);
        jdbc.update("""
                UPDATE flow_runtime_inbox SET subject_head = ?
                WHERE task_id = ? AND kind = 'INITIAL_TASK'
                """, "0".repeat(40), active.taskId());
        assertThatThrownBy(() -> success.execute(successClaim))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoRuntimeInstancesIssueOnlyOneExactClaim()
            throws Exception
    {
        provisioning(config).startTask("race", "repo-1", "goal");
        FlowRuntime secondRuntime = new FlowRuntime(dataSource, clock);
        CountDownLatch start = new CountDownLatch(1);
        List<Claim> claims = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Runnable first = () -> claimAfter(
                start, runtime, claims, failures, "one");
        Runnable second = () -> claimAfter(
                start, secondRuntime, claims, failures, "two");
        Thread one = Thread.ofPlatform().start(first);
        Thread two = Thread.ofPlatform().start(second);
        start.countDown();
        one.join();
        two.join();

        assertThat(failures).isEmpty();
        assertThat(claims).hasSize(1);
        assertThat(runtime.operation(claims.getFirst().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CLAIMED);
    }

    @Test
    void existingSubjectRevalidatesConfigAndKeepsItsFrozenBase()
    {
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        TaskProvisioning.GitProcess noMutation = (root, arguments) ->
                mutation(arguments)
                        ? new TaskProvisioning.ProcessResult(false, -1, "")
                        : direct.run(root, arguments);
        TaskProvisioning first = provisioning(config, noMutation);
        Task task = first.startTask("request-1", "repo-1", "goal");
        Claim firstClaim = claim();
        first.execute(firstClaim);
        String frozen = subjectBase(firstClaim.operationId());

        git(repository, "config", "Filter.Evil.Process", "secret-command");
        clock.advance(Duration.ofSeconds(2));
        Claim unsafeClaim = claim();
        provisioning(config).execute(unsafeClaim);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(Files.exists(Path.of(task.worktreePath()))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT result_ref FROM flow_runtime_operation "
                        + "WHERE operation_id = ?",
                String.class,
                unsafeClaim.operationId()))
                .isEqualTo("PROVISION_INVALID:UNSAFE_GIT_CONFIG");
        assertThat(jdbc.queryForObject(
                "SELECT reason_code FROM flow_runtime_task_lifecycle_revision "
                        + "WHERE task_id = ? ORDER BY sequence DESC LIMIT 1",
                String.class,
                task.taskId()))
                .isEqualTo("PROVISION_INVALID:UNSAFE_GIT_CONFIG");

        git(repository, "config", "--unset-all", "filter.Evil.process");
        setUpSecondRuntime();
        TaskProvisioning second = provisioning(config, noMutation);
        Task secondTask = second.startTask("base-move", "repo-1", "goal");
        Claim bind = claim();
        second.execute(bind);
        String oldBase = subjectBase(bind.operationId());
        commit(repository, "later.txt", "later\n");
        git(repository, "update-ref", "refs/remotes/origin/main", "HEAD");
        clock.advance(Duration.ofSeconds(2));
        Claim execute = claim();
        provisioning(config).execute(execute);
        assertThat(runtime.task(secondTask.taskId()).orElseThrow().launchBaseSha())
                .isEqualTo(oldBase)
                .isEqualTo(frozen);
    }

    @Test
    void staleClaimAndCorruptLaunchPerformNoGit()
    {
        AtomicInteger calls = new AtomicInteger();
        TaskProvisioning.GitProcess counting = (root, arguments) -> {
            calls.incrementAndGet();
            return new TaskProvisioning.ProcessResult(false, -1, "");
        };
        TaskProvisioning provisioning = provisioning(config, counting);
        Task task = provisioning.startTask("request-1", "repo-1", "goal");
        Claim claim = claim();
        Claim forged = new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation(), "wrong", claim.workerId(), claim.expiresAt());
        assertThatThrownBy(() -> provisioning.execute(forged))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);

        Claim wrongGeneration = new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation() + 1, claim.claimToken(),
                claim.workerId(), claim.expiresAt());
        Claim wrongOwner = new Claim(
                claim.operationId(), claim.taskId(), claim.kind(),
                claim.generation(), claim.claimToken(),
                "other-worker", claim.expiresAt());
        assertThatThrownBy(() -> provisioning.execute(wrongGeneration))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provisioning.execute(wrongOwner))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);

        jdbc.update("UPDATE flow_runtime_task SET remote_name = 'other' "
                + "WHERE task_id = ?", task.taskId());
        assertThatThrownBy(() -> provisioning.execute(claim))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void genericLifecycleCannotBypassProvisioning()
    {
        Task task = provisioning(config)
                .startTask("request-1", "repo-1", "goal");
        assertThatThrownBy(() -> runtime.transitionTask(
                task.taskId(), task.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE, "BYPASS", "fake"))
                .isInstanceOf(IllegalStateException.class);
        Task stored = runtime.task(task.taskId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TaskStatus.CREATED);
        assertThat(stored.taskSessionId()).isNull();
        assertThat(stored.launchBaseSha()).isNull();
        assertThat(count("flow_runtime_task_lifecycle_revision")).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeLocatorRefAndWorktreeRootInputs()
    {
        assertThatThrownBy(() -> new TaskProvisioning.RepositoryConfig(
                "repo-1", "octocat", "bytequay", repository,
                repository.resolve(".git"), "--push",
                "refs/remotes/--push/main", worktreeRoot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskProvisioning.RepositoryConfig(
                "repo-1", "octocat", "bytequay", repository,
                repository.resolve(".git"), "origin",
                "refs/remotes/origin/main~1", worktreeRoot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskProvisioning.RepositoryConfig(
                "repo-1", "octocat", "bytequay", repository,
                repository.resolve(".git"), "origin",
                "refs/remotes/origin/main", repository.resolve("nested")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TaskProvisioning provisioning(
            TaskProvisioning.RepositoryConfig repositoryConfig)
    {
        return provisioning(
                repositoryConfig, new TaskProvisioning.DirectGitProcess());
    }

    private TaskProvisioning provisioning(
            TaskProvisioning.RepositoryConfig repositoryConfig,
            TaskProvisioning.GitProcess git)
    {
        return new TaskProvisioning(
                dataSource,
                runtime,
                ignored -> repositoryConfig,
                git,
                new FlowWorktreeInspector(),
                clock);
    }

    private Claim claim()
    {
        return runtime.claimNextForDispatch(
                Set.of(OperationKind.PROVISION_TASK), "worker", TTL, 1)
                .orElseThrow();
    }

    private String baseSha()
    {
        return git(repository, "rev-parse", "HEAD");
    }

    private String subjectBase(String operationId)
    {
        return jdbc.queryForObject(
                "SELECT base_sha FROM flow_runtime_provision_subject "
                        + "WHERE operation_id = ?",
                String.class,
                operationId);
    }

    private String ticketState(String operationId)
    {
        return jdbc.queryForObject(
                "SELECT delivery_state FROM flow_runtime_dispatch_ticket "
                        + "WHERE operation_id = ?",
                String.class,
                operationId);
    }

    private String operationResult(String operationId)
    {
        return jdbc.queryForObject(
                "SELECT result_ref FROM flow_runtime_operation "
                        + "WHERE operation_id = ?",
                String.class,
                operationId);
    }

    private TaskProvisioning crashAfterBindingProvisioning()
    {
        TaskProvisioning.DirectGitProcess direct =
                new TaskProvisioning.DirectGitProcess();
        AtomicBoolean crash = new AtomicBoolean(true);
        return provisioning(config, (root, arguments) -> {
            if (crash.get()
                    && arguments.size() >= 4
                    && arguments.getFirst().equals("rev-parse")
                    && arguments.getLast().startsWith("refs/heads/bytequay/")) {
                crash.set(false);
                throw new IllegalStateException("crash after subject binding");
            }
            return direct.run(root, arguments);
        });
    }

    private void claimAfter(
            CountDownLatch start,
            FlowRuntime owner,
            List<Claim> claims,
            List<Throwable> failures,
            String worker)
    {
        try {
            start.await();
            owner.claimNextForDispatch(
                    Set.of(OperationKind.PROVISION_TASK), worker, TTL, 1)
                    .ifPresent(claims::add);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private int count(String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void setUpSecondRuntime()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve(
                        "flow-" + System.nanoTime() + ".db")
                        + "?foreign_keys=ON&busy_timeout=5000"
                        + "&transaction_mode=IMMEDIATE");
        FlowRuntimeSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock(NOW);
        runtime = new FlowRuntime(dataSource, clock);
        Path root = temporaryDirectory.resolve("worktrees-" + System.nanoTime());
        createDirectories(root);
        worktreeRoot = root;
        config = new TaskProvisioning.RepositoryConfig(
                "repo-1", "octocat", "bytequay", repository,
                repository.resolve(".git"), "origin",
                "refs/remotes/origin/main", worktreeRoot);
    }

    private void initializeRepository(Path root)
    {
        createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "test@bytequay.invalid");
        write(root.resolve("base.txt"), "base\n");
        git(root, "add", "base.txt");
        git(root, "commit", "-m", "base");
        git(root, "remote", "add", "origin",
                "https://github.com/octocat/bytequay.git");
        git(root, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private void commit(Path root, String name, String content)
    {
        write(root.resolve(name), content);
        git(root, "add", name);
        git(root, "commit", "-m", name);
    }

    private static boolean mutation(List<String> arguments)
    {
        return arguments.contains("worktree") && arguments.contains("add");
    }

    private static String git(Path root, String... arguments)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output;
        }
        catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void createDirectories(Path path)
    {
        try {
            Files.createDirectories(path);
        }
        catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void write(Path path, String value)
    {
        try {
            Files.writeString(path, value, StandardCharsets.UTF_8);
        }
        catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant instant;

        private MutableClock(Instant instant)
        {
            this.instant = instant;
        }

        private void advance(Duration duration)
        {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
