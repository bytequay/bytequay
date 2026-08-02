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
package com.bytequay.app.developmentflow.task.creation;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestV2TaskCreationHandoff
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final String WORKSPACE = "workspace-1";
    private static final String TRUNK = "trunk-1";
    private static final String REPOSITORY = "acme/widget";

    @TempDir
    private Path tempDir;

    @Test
    void persistsAllAssignmentVariantsAndBothNewTaskCreationSources()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("all-shapes.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        CreationRuntime handoff = runtime(dataSource);

        List<TaskCreationInput> inputs = allInputs();
        for (int index = 0; index < inputs.size(); index++) {
            TaskCreationHandoff.Result result = handoff.create(command(
                    "create-" + index, index, inputs.get(index), repositoryRoot));
            assertThat(result.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
            assertThat(result.task().task().id()).isEqualTo(TRUNK + ".k" + (index + 1));
            assertThat(result.task().receipt().taskSequence()).isEqualTo(index + 1);
        }

        assertThat(count(jdbc, "tasks")).isEqualTo(7);
        assertThat(count(jdbc, "task_assignment")).isEqualTo(7);
        assertThat(jdbc.queryForList("""
                SELECT linked_pr_ref FROM tasks
                WHERE linked_pr_ref IS NOT NULL ORDER BY linked_pr_ref
                """, String.class))
                .containsExactly("acme/widget#42", "acme/widget#43");
        assertThat(jdbc.queryForList("""
                SELECT finding_id FROM task_assignment_review_finding
                ORDER BY position
                """, String.class)).containsExactly("finding-1", "finding-2");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status FROM provision_task_operation
                """, String.class)).containsExactly("DISPATCHED");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status FROM dispatch_ticket
                WHERE operation_kind = 'PROVISION_TASK'
                """, String.class)).containsExactly("REQUESTED");
        assertThat(countWhere(jdbc, "outbox", "aggregate_kind = 'DISPATCH_TICKET'"))
                .isEqualTo(7);
        assertThat(countWhere(jdbc, "task_transition",
                "from_state IS NULL AND to_state = 'PROVISIONING'"
                        + " AND cause = 'CREATE_TASK'"))
                .isEqualTo(7);
        assertThat(countWhere(jdbc, "tasks",
                "branch_name IS NULL AND worktree_path IS NULL"
                        + " AND epoch = 1 AND aggregate_version = 0"))
                .isEqualTo(7);
        assertThat(jdbc.queryForMap("""
                SELECT context.base_source, context.planning_base_sha,
                       context.assignment_base_sha, context.assignment_head_sha,
                       operation.expected_base_sha,
                       operation.expected_remote_head_sha
                FROM task_assignment assignment
                JOIN task_creation_context context
                  ON context.assignment_id = assignment.id
                JOIN provision_task_operation operation
                  ON operation.task_id = context.task_id
                WHERE assignment.id = 'assignment-new-direct'
                """))
                .containsEntry("base_source", "FRESH_REMOTE_BASE")
                .containsEntry("planning_base_sha", null)
                .containsEntry("assignment_base_sha", null)
                .containsEntry("assignment_head_sha", null)
                .containsEntry("expected_base_sha", null)
                .containsEntry("expected_remote_head_sha", null);
        assertThat(jdbc.queryForList("""
                SELECT worktree_path FROM task_provision_target ORDER BY task_id
                """, String.class))
                .allMatch(path -> path.startsWith(repositoryRoot.toString() + "/.worktrees/"));
        assertThat(jdbc.queryForMap("""
                SELECT task.name, task.task_type, task.opening_prompt,
                       authorization.task_name AS authorized_name,
                       context.task_name AS context_name
                FROM tasks task
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN trunk_task_creation_authorization authorization
                  ON authorization.id = context.authorization_id
                WHERE task.assignment_id = 'assignment-new-direct'
                """))
                .containsEntry("name", "direct plan seed")
                .containsEntry("task_type", "DEVELOP")
                .containsEntry("opening_prompt", "implement directly")
                .containsEntry("authorized_name", "direct plan seed")
                .containsEntry("context_name", "direct plan seed");
    }

    @Test
    void exactReplaySurvivesRestartAndCloneRelocationWhileProviderConflictFailsClosed()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("replay.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        TaskCreationInput input = allInputs().getFirst();
        TaskCreationHandoff.Command command = command(
                "same-command", 0, input, repositoryRoot);

        TaskCreationHandoff.Result created = runtime(dataSource).create(command);
        String taskId = created.task().task().id();
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES ('later-plan', ?, 'PLAN', 1, 0, 'DRAFTING', ?)
                """, taskId, NOW.toEpochMilli() + 1);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, 'later-plan', 1)
                """, taskId);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
        Path relocatedRoot = tempDir.resolve("relocated-repo").toAbsolutePath();
        jdbc.update("UPDATE watched_repos SET local_clone_path = ?",
                relocatedRoot.toString());
        TaskCreationHandoff.Result replayed = runtime(dataSource).create(command(
                "same-command", 0, input, relocatedRoot));

        assertThat(replayed.disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replayed.task().receipt()).isEqualTo(created.task().receipt());
        assertThat(replayed.task().task().lifecycle())
                .isEqualTo(TaskLifecycle.PROVISIONING);
        assertThat(replayed.task().task().version()).isZero();
        assertThat(replayed.task().target()).isEqualTo(created.task().target());
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM tasks WHERE id = ?
                """, String.class, taskId)).isEqualTo("ACTIVE");
        assertThat(count(jdbc, "tasks")).isOne();
        assertThat(count(jdbc, "trunk_task_creation_authorization")).isOne();
        assertThat(number(jdbc, "SELECT aggregate_version FROM threads WHERE id = ?", TRUNK))
                .isOne();

        TaskCreationInput conflicting = new TaskCreationInput(
                input.workspaceId(), input.assignment(), input.policy(), input.base(),
                new TaskCreationInput.EngineSnapshot(
                        "different-provider", input.engine().model(), input.engine().value()),
                input.workModel(), input.createdAt());
        assertThatThrownBy(() -> runtime(dataSource).create(command(
                "same-command", 0, conflicting, repositoryRoot)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(COMMAND_ID_CONFLICT));
        assertThat(count(jdbc, "tasks")).isOne();
    }

    @Test
    void keepsCreationCommandIdsExclusiveAcrossOwnerReceiptTables()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource ordinaryFirst = database("ordinary-first.db");
        JdbcTemplate ordinaryJdbc = new JdbcTemplate(ordinaryFirst);
        seedTrunk(ordinaryJdbc, repositoryRoot);
        CreationRuntime ordinaryRuntime = runtime(ordinaryFirst);
        ordinaryRuntime.trunks().markIdle(new TrunkManager.Command(
                "shared-command", "user", TRUNK, 0));

        assertConflict(() -> ordinaryRuntime.create(command(
                "shared-command", 1, allInputs().getFirst(), repositoryRoot)));
        assertThat(count(ordinaryJdbc, "trunk_task_creation_authorization")).isZero();
        assertThat(count(ordinaryJdbc, "tasks")).isZero();

        DataSource creationFirst = database("creation-first.db");
        JdbcTemplate creationJdbc = new JdbcTemplate(creationFirst);
        seedTrunk(creationJdbc, repositoryRoot);
        CreationRuntime creationRuntime = runtime(creationFirst);
        TaskCreationHandoff.Command creation = command(
                "shared-command", 0, allInputs().getFirst(), repositoryRoot);
        String taskId = creationRuntime.create(creation).task().task().id();

        assertConflict(() -> creationRuntime.trunks().markIdle(
                new TrunkManager.Command("shared-command", "user", TRUNK, 1)));
        assertConflict(() -> creationRuntime.tasks().requestPause(
                new TaskManager.Command("shared-command", "user", taskId, 1, 0)));

        creationJdbc.execute("DROP TRIGGER task_command_receipt_insert");
        creationJdbc.update("""
                INSERT INTO task_command_receipt(
                    id, task_id, command_id, cause, actor, disposition,
                    expected_task_epoch, expected_task_version,
                    returned_trunk_id, returned_lifecycle, returned_epoch,
                    returned_version, recorded_at_ms)
                VALUES ('corrupt-ordinary-receipt', ?, 'shared-command',
                    'REQUEST_PAUSE', 'user', 'APPLIED', 1, 0,
                    ?, 'PROVISIONING', 1, 1, ?)
                """, taskId, TRUNK, NOW.toEpochMilli());
        assertConflict(() -> runtime(creationFirst).create(creation));
    }

    @Test
    void persistsForkProvenanceFromUpstreamBaseToForkPublishRepository()
    {
        Path repositoryRoot = tempDir.resolve("fork-repo").toAbsolutePath();
        DataSource dataSource = database("fork.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedForkTrunk(jdbc, repositoryRoot);
        TaskAssignment.Fork repositories = new TaskAssignment.Fork(
                "acme/widget", "jack/widget");
        TaskAssignment.PullRequestRef pullRequest = new TaskAssignment.PullRequestRef(
                repositories, 73, "main", "feature/fork",
                "fork-base", "fork-head");
        TaskCreationInput input = input(
                new TaskAssignment.ExistingOwnPr(identity("fork"), pullRequest),
                new TaskCreationInput.ExistingPrHead(pullRequest));

        TaskCreationHandoff.Result created = runtime(dataSource).create(command(
                "create-fork", 0, input, repositoryRoot));

        assertThat(created.task().target().repositoryId()).isEqualTo("jack/widget");
        assertThat(created.task().target().publishRepositoryId())
                .isEqualTo("jack/widget");
        assertThat(jdbc.queryForMap("""
                SELECT authorization.repository_id,
                       authorization.upstream_repository_id,
                       authorization.publish_repository_id,
                       authorization.base_repository_id,
                       assignment.repository_route,
                       operation.repository_id AS operation_repository_id,
                       operation.base_repository_id AS operation_base_repository_id
                FROM trunk_task_creation_authorization authorization
                JOIN task_assignment assignment
                  ON assignment.id = authorization.assignment_id
                JOIN task_creation_receipt receipt
                  ON receipt.authorization_id = authorization.id
                JOIN provision_task_operation operation
                  ON operation.id = receipt.provision_operation_id
                """))
                .containsEntry("repository_id", "jack/widget")
                .containsEntry("upstream_repository_id", "acme/widget")
                .containsEntry("publish_repository_id", "jack/widget")
                .containsEntry("base_repository_id", "acme/widget")
                .containsEntry("repository_route", "FORK")
                .containsEntry("operation_repository_id", "jack/widget")
                .containsEntry("operation_base_repository_id", "acme/widget");
    }

    @Test
    void rejectsWrongRepositoryRootAndResumesTheDurableAuthorization()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("repository-root.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        TaskCreationInput input = allInputs().getFirst();

        assertThatThrownBy(() -> runtime(dataSource).create(command(
                "create-root", 0, input,
                tempDir.resolve("another-repo").toAbsolutePath())))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
        assertThat(count(jdbc, "trunk_task_creation_authorization")).isOne();
        assertThat(count(jdbc, "tasks")).isZero();

        TaskCreationHandoff.Result recovered = runtime(dataSource).create(command(
                "create-root", 0, input, repositoryRoot));
        assertThat(recovered.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(count(jdbc, "tasks")).isOne();
    }

    @Test
    void lateBundleFailureRollsBackEveryTaskOwnedRecord()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("rollback.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        jdbc.execute("DROP TRIGGER dispatch_ticket_requested_wake_insert");

        assertThatThrownBy(() -> runtime(dataSource).create(command(
                "create-rollback", 0, allInputs().getFirst(), repositoryRoot)))
                .isInstanceOf(DataAccessException.class);

        assertThat(count(jdbc, "trunk_task_creation_authorization")).isOne();
        assertThat(count(jdbc, "task_assignment")).isOne();
        assertThat(count(jdbc, "task_policy_revision")).isOne();
        assertThat(number(jdbc, "SELECT aggregate_version FROM threads WHERE id = ?", TRUNK))
                .isOne();
        for (String table : List.of(
                "tasks", "task_creation_context", "task_brain",
                "task_provision_target", "provision_task_operation",
                "dispatch_ticket", "task_creation_receipt", "task_transition")) {
            assertThat(count(jdbc, table)).as(table).isZero();
        }
    }

    @Test
    void createsSiblingTasksWhileAnExistingTaskIsRunningWithoutInterference()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("siblings.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms)
                VALUES ('legacy-running', ?, 1, 'RUNNING', 'IMPLEMENTING', 1)
                """, TRUNK);
        CreationRuntime handoff = runtime(dataSource);

        TaskCreationHandoff.Result first = handoff.create(command(
                "create-sibling-1", 0, allInputs().get(3), repositoryRoot));
        TaskCreationHandoff.Result second = handoff.create(command(
                "create-sibling-2", 1, allInputs().get(4), repositoryRoot));

        assertThat(first.task().receipt().taskSequence()).isEqualTo(2);
        assertThat(second.task().receipt().taskSequence()).isEqualTo(3);
        assertThat(first.task().task().id()).isNotEqualTo(second.task().task().id());
        assertThat(first.task().target().worktreePath())
                .isNotEqualTo(second.task().target().worktreePath());
        assertThat(jdbc.queryForObject("""
                SELECT status FROM tasks WHERE id = 'legacy-running'
                """, String.class)).isEqualTo("RUNNING");
        assertThat(countWhere(jdbc, "dispatch_ticket",
                "task_id IN ('trunk-1.k2', 'trunk-1.k3')"))
                .isEqualTo(2);
    }

    @Test
    void concurrentFactoriesSnapshotDistinctPolicyAndTrunkVersionsUnderTheStripe()
            throws Exception
    {
        Path repositoryRoot = tempDir.resolve("concurrent-repo").toAbsolutePath();
        DataSource dataSource = database("concurrent.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        CreationRuntime runtime = runtime(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<TaskCreationHandoff.Result> first = workers.submit(() -> {
                ready.countDown();
                start.await();
                return runtime.handoff().create(
                        TRUNK,
                        () -> concurrentCommand(jdbc, "first", repositoryRoot));
            });
            Future<TaskCreationHandoff.Result> second = workers.submit(() -> {
                ready.countDown();
                start.await();
                return runtime.handoff().create(
                        TRUNK,
                        () -> concurrentCommand(jdbc, "second", repositoryRoot));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS).task().receipt().taskSequence(),
                    second.get(10, TimeUnit.SECONDS).task().receipt().taskSequence()))
                    .containsExactlyInAnyOrder(1L, 2L);
        }

        assertThat(jdbc.queryForList("""
                SELECT revision FROM task_policy_revision ORDER BY revision
                """, Integer.class)).containsExactly(1, 2);
        assertThat(number(jdbc,
                "SELECT aggregate_version FROM threads WHERE id = ?", TRUNK))
                .isEqualTo(2);
        assertThat(count(jdbc, "tasks")).isEqualTo(2);
    }

    private DataSource database(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static CreationRuntime runtime(DataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TrunkManager.Store trunkStore;
        TaskManager.Store taskStore;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(DataSourceTransactionManager.class,
                    () -> transactionManager);
            context.scan(
                    "com.bytequay.app.developmentflow.trunk.persistence",
                    "com.bytequay.app.developmentflow.task.persistence");
            context.refresh();
            trunkStore = context.getBean(TrunkManager.Store.class);
            taskStore = context.getBean(TaskManager.Store.class);
        }
        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        TrunkManager trunks = new TrunkManager(commands, trunkStore);
        TaskManager tasks = new TaskManager(commands, taskStore);
        return new CreationRuntime(
                new TaskCreationHandoff(
                        commands, trunks, tasks, new IdGenerator(ignored -> 1)),
                trunks, tasks);
    }

    private static void seedTrunk(JdbcTemplate jdbc, Path repositoryRoot)
    {
        seedTrunk(jdbc, repositoryRoot, REPOSITORY);
    }

    private static void seedTrunk(
            JdbcTemplate jdbc, Path repositoryRoot, String repository)
    {
        String[] name = repository.split("/", 2);
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES (?, ?, ?)
                """, name[0], name[1], repositoryRoot.toString());
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, ?, '', 0, 1, 1)
                """, WORKSPACE, WORKSPACE);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES (?, ?, 'main', 0, 1)
                """, WORKSPACE, repository);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'test', 0, 0, 0,
                    1, 1, ?, 'build', 4, 'V2', 'ACTIVE')
                """, TRUNK, TRUNK, WORKSPACE);
    }

    private static void seedForkTrunk(JdbcTemplate jdbc, Path repositoryRoot)
    {
        seedTrunk(jdbc, repositoryRoot, "jack/widget");
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-upstream', 'upstream', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES ('workspace-upstream', 'acme/widget', 'main', 0, 1)
                """);
        jdbc.update("""
                INSERT INTO workspace_relation(
                    workspace_id, upstream_workspace_id,
                    created_at_ms, updated_at_ms)
                VALUES (?, 'workspace-upstream', 1, 1)
                """, WORKSPACE);
    }

    private static TaskCreationHandoff.Command command(
            String commandId,
            long expectedVersion,
            TaskCreationInput input,
            Path repositoryRoot)
    {
        return new TaskCreationHandoff.Command(
                new TrunkManager.TaskCreationCommand(
                        commandId, "user", expectedVersion, input),
                repositoryRoot);
    }

    private static TaskCreationHandoff.Command concurrentCommand(
            JdbcTemplate jdbc, String suffix, Path repositoryRoot)
    {
        int policyRevision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM task_policy_revision WHERE trunk_id = ?
                """, Integer.class, TRUNK);
        long trunkVersion = number(
                jdbc,
                "SELECT aggregate_version FROM threads WHERE id = ?", TRUNK);
        TaskAssignment.Identity identity = new TaskAssignment.Identity(
                "assignment-" + suffix, TRUNK, "authorization-" + suffix,
                "user", NOW);
        TaskCreationInput input = new TaskCreationInput(
                WORKSPACE,
                new TaskAssignment.Automation(
                        identity, "test", "concurrent " + suffix),
                new TaskCreationInput.TaskPolicy(
                        "policy-" + suffix, TRUNK, policyRevision,
                        "TRUNK", true, false, 1, 3, 3, true,
                        Optional.of("permissions-1"), "user", NOW),
                fresh(),
                new TaskCreationInput.EngineSnapshot(
                        "openai", "review-model", "engine-v1"),
                new TaskCreationInput.WorkModelSnapshot("work-model-v1"), NOW);
        return command(
                "concurrent-" + suffix, trunkVersion, input, repositoryRoot);
    }

    private static List<TaskCreationInput> allInputs()
    {
        TaskAssignment.PullRequestRef existingPr = pullRequest("existing");
        TaskAssignment.PullRequestRef reviewedPr = pullRequest("review", 43);
        return List.of(
                input(
                        new TaskAssignment.NewFromTrunk(
                                identity("new"),
                                new TaskAssignment.AgentHandoff("base-new"),
                                "plan seed", "implement the plan"),
                        new TaskCreationInput.PlanningSnapshot(
                                direct(), "main", "base-new")),
                input(
                        new TaskAssignment.NewFromTrunk(
                                identity("new-direct"),
                                new TaskAssignment.DirectUser(),
                                "direct plan seed", "implement directly"),
                        fresh()),
                input(
                        new TaskAssignment.ExistingOwnPr(
                                identity("existing"), existingPr),
                        new TaskCreationInput.ExistingPrHead(existingPr)),
                input(
                        new TaskAssignment.ReviewFindings(
                                identity("review"), "review-session", reviewedPr,
                                List.of(
                                        finding("finding-1"),
                                        finding("finding-2"))),
                        new TaskCreationInput.ExistingPrHead(reviewedPr)),
                input(
                        new TaskAssignment.Issue(
                                identity("issue"), "acme/widget#42"),
                        fresh()),
                input(
                        new TaskAssignment.Automation(
                                identity("automation"), "nightly",
                                "dependency refresh"),
                        fresh()),
                input(
                        new TaskAssignment.QualityScan(
                                identity("quality"), "scan-evidence-1"),
                        fresh()));
    }

    private static TaskCreationInput input(
            TaskAssignment assignment, TaskCreationInput.CreationBase base)
    {
        return new TaskCreationInput(
                WORKSPACE, assignment, policy(), base,
                new TaskCreationInput.EngineSnapshot(
                        "openai", "review-model", "engine-v1"),
                new TaskCreationInput.WorkModelSnapshot("work-model-v1"), NOW);
    }

    private static TaskCreationInput.TaskPolicy policy()
    {
        return new TaskCreationInput.TaskPolicy(
                "policy-1", TRUNK, 1, "TRUNK", true, false,
                1, 3, 3, true, Optional.of("permissions-1"), "user", NOW);
    }

    private static TaskAssignment.Identity identity(String suffix)
    {
        return new TaskAssignment.Identity(
                "assignment-" + suffix, TRUNK, "authorization-" + suffix,
                "user", NOW);
    }

    private static TaskAssignment.Direct direct()
    {
        return new TaskAssignment.Direct(REPOSITORY);
    }

    private static TaskCreationInput.FreshRemoteBase fresh()
    {
        return new TaskCreationInput.FreshRemoteBase(direct(), "main");
    }

    private static TaskAssignment.PullRequestRef pullRequest(String suffix)
    {
        return pullRequest(suffix, 42);
    }

    private static TaskAssignment.PullRequestRef pullRequest(
            String suffix, int number)
    {
        return new TaskAssignment.PullRequestRef(
                direct(), number, "main", "feature/" + suffix,
                "base-" + suffix, "head-" + suffix);
    }

    private static TaskAssignment.ReviewFindingRef finding(String findingId)
    {
        return new TaskAssignment.ReviewFindingRef(
                "review-session", findingId, 2, "digest-" + findingId);
    }

    private static void assertConflict(ThrowingCallable command)
    {
        assertThatThrownBy(command)
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(COMMAND_ID_CONFLICT));
    }

    private record CreationRuntime(
            TaskCreationHandoff handoff,
            TrunkManager trunks,
            TaskManager tasks)
    {
        private TaskCreationHandoff.Result create(TaskCreationHandoff.Command command)
        {
            return handoff.create(command);
        }
    }

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String predicate)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }

    private static long number(JdbcTemplate jdbc, String sql, Object... arguments)
    {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }
}
