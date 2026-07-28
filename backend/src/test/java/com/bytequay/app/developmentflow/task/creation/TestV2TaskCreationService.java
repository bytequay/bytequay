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
import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowCanaryRoute;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.review.ReviewBuildSelectionStore;
import com.bytequay.app.service.review.ReviewBuildSpawnService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2TaskCreationService
{
    private static final String WORKSPACE = "workspace-1";
    private static final String TRUNK = "trunk-1";
    private static final String TASK = "trunk-1.k1";
    private static final WorkModel PLAN_MODEL = new WorkModel(
            WorkModelKind.CLI, "codex", "gpt-test", null, "high");

    @TempDir
    private Path tempDir;

    @Test
    void retriesAConcurrentSiblingAndHandsOffOneExactDirectAssignment()
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        JdbcTemplate jdbc = database("creation-service.db", repositoryRoot);
        TaskCreationHandoff handoff = mock(TaskCreationHandoff.class);
        TaskStore tasks = mock(TaskStore.class);
        ThreadStore threads = mock(ThreadStore.class);
        ThreadEngineOverrides engines = mock(ThreadEngineOverrides.class);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        V2DevelopmentFlowProjection projection =
                mock(V2DevelopmentFlowProjection.class);
        Task raw = taskShape();
        Task projected = taskShape();

        when(engines.forAudience(TRUNK, SessionAudience.PLAN))
                .thenReturn(Optional.of(PLAN_MODEL));
        when(repositories.resolve(WORKSPACE)).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(relations.find(WORKSPACE)).thenReturn(Optional.empty());
        when(tasks.findTaskById(TASK)).thenReturn(Optional.of(raw));
        when(projection.project(raw)).thenReturn(projected);

        AtomicInteger calls = new AtomicInteger();
        when(handoff.create(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                jdbc.update("""
                        UPDATE threads SET aggregate_version = 1 WHERE id = ?
                        """, TRUNK);
                throw new CommandRejectedException(
                        STALE_VERSION, "simulated sibling Task creation");
            }
            return creationResult(repositoryRoot);
        });

        V2TaskCreationService service = new V2TaskCreationService(
                new DevelopmentFlowCanaryRoute(true, WORKSPACE),
                handoff, jdbc, threads, tasks, engines, repositories, relations,
                mock(PullRequestRepository.class), mock(PatResolver.class),
                mock(ReviewBuildSelectionStore.class), new ObjectMapper(), projection);
        Thread trunk = trunk();
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "codex", "gpt-test", "Build exact flow",
                repositoryRoot.toString(), null, "Implement the accepted design",
                List.of(), "DEVELOP", null, null, ThreadFlow.BUILD, WORKSPACE,
                PLAN_MODEL);

        assertThat(service.create(trunk, request)).isSameAs(projected);

        ArgumentCaptor<TaskCreationHandoff.Command> commands =
                ArgumentCaptor.forClass(TaskCreationHandoff.Command.class);
        verify(handoff, times(2)).create(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(value -> value.authorization().expectedTrunkVersion())
                .containsExactly(0L, 1L);
        TaskCreationInput input = commands.getValue().authorization().input();
        assertThat(input.assignment())
                .isInstanceOf(TaskAssignment.NewFromTrunk.class);
        TaskAssignment.NewFromTrunk assignment =
                (TaskAssignment.NewFromTrunk) input.assignment();
        assertThat(assignment.origin()).isInstanceOf(TaskAssignment.DirectUser.class);
        assertThat(assignment.planSeed()).isEqualTo("Build exact flow");
        assertThat(assignment.prompt()).isEqualTo("Implement the accepted design");
        assertThat(input.base()).isInstanceOf(TaskCreationInput.FreshRemoteBase.class);
        assertThat(input.base().repositories().repositoryId()).isEqualTo("acme/widget");
        assertThat(input.presentation()).isEqualTo(new TaskCreationInput.Presentation(
                "Build exact flow", "DEVELOP", null,
                "Implement the accepted design", Task.ORIGIN_USER));
        assertThat(jdbc.queryForObject("""
                SELECT turn_version FROM threads WHERE id = ?
                """, String.class, TRUNK)).isEqualTo("V2");
    }

    @Test
    void mapsFrozenReviewBuildSelectionToExactReviewFindingsAssignment()
    {
        Path repositoryRoot = tempDir.resolve("review-repo").toAbsolutePath();
        JdbcTemplate jdbc = database("review-creation.db", repositoryRoot);
        TaskCreationHandoff handoff = mock(TaskCreationHandoff.class);
        TaskStore tasks = mock(TaskStore.class);
        ThreadStore threads = mock(ThreadStore.class);
        ThreadEngineOverrides engines = mock(ThreadEngineOverrides.class);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        ReviewBuildSelectionStore selections = mock(
                ReviewBuildSelectionStore.class);
        V2DevelopmentFlowProjection projection = mock(
                V2DevelopmentFlowProjection.class);
        Task raw = taskShape();

        when(engines.forAudience(TRUNK, SessionAudience.PLAN))
                .thenReturn(Optional.of(PLAN_MODEL));
        when(repositories.resolve(WORKSPACE)).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(pullRequests.fetchPrDetail(any(), any())).thenReturn(new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1, 0,
                List.of(), "reviewed-head", "feature/review", "acme/widget",
                "main", "acme/widget", "open", false, "base-head", null));
        ReviewBuildSelectionStore.Finding selected =
                new ReviewBuildSelectionStore.Finding(
                        "review-pass", "finding-1", 1, "{\"id\":\"finding-1\"}",
                        "finding-digest");
        when(selections.find(TRUNK)).thenReturn(Optional.of(
                new ReviewBuildSelectionStore.Selection(
                        TRUNK, "review-pass", "acme/widget", 42,
                        "reviewed-head",
                        new ReviewBuildSelectionStore.SpawnInput(
                                WORKSPACE, "Fix finding",
                                ReviewBuildSelectionStore.SelectionPolicy.EXPLICIT,
                                ReviewBuildSpawnService.MODE_AUTHOR,
                                "acme/widget", "acme/widget", "main",
                                "feature/review"),
                        "selection-digest",
                        List.of(selected), Instant.ofEpochMilli(2))));
        when(selections.matchesCurrent(any())).thenReturn(true);
        when(handoff.create(any())).thenReturn(creationResult(repositoryRoot));
        when(tasks.findTaskById(TASK)).thenReturn(Optional.of(raw));
        when(projection.project(raw)).thenReturn(raw);

        V2TaskCreationService service = new V2TaskCreationService(
                new DevelopmentFlowCanaryRoute(true, WORKSPACE),
                handoff, jdbc, threads, tasks, engines, repositories, relations,
                pullRequests, pats, selections, new ObjectMapper(), projection);
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "codex", "gpt-test", "Fix finding",
                repositoryRoot.toString(), null, "Address #finding-finding-1",
                List.of(), "DEVELOP", 42, null, ThreadFlow.BUILD, WORKSPACE,
                PLAN_MODEL);

        service.create(reviewTrunk(), request);

        ArgumentCaptor<TaskCreationHandoff.Command> command =
                ArgumentCaptor.forClass(TaskCreationHandoff.Command.class);
        verify(handoff).create(command.capture());
        assertThat(command.getValue().authorization().input().assignment())
                .isInstanceOfSatisfying(
                        TaskAssignment.ReviewFindings.class,
                        assignment -> {
                            assertThat(assignment.sourceReviewId())
                                    .isEqualTo("review-pass");
                            assertThat(assignment.pullRequest().remoteHeadSha())
                                    .isEqualTo("reviewed-head");
                            assertThat(assignment.findings())
                                    .extracting(TaskAssignment.ReviewFindingRef::findingId)
                                    .containsExactly("finding-1");
                        });
    }

    @Test
    void rejectsAReviewSelectionChangedBeforeTaskMaterialization()
    {
        Path repositoryRoot = tempDir.resolve("stale-review-repo").toAbsolutePath();
        JdbcTemplate jdbc = database("stale-review-creation.db", repositoryRoot);
        TaskCreationHandoff handoff = mock(TaskCreationHandoff.class);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        ReviewBuildSelectionStore selections = mock(
                ReviewBuildSelectionStore.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        when(repositories.resolve(WORKSPACE)).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        ReviewBuildSelectionStore.Selection selection =
                new ReviewBuildSelectionStore.Selection(
                        TRUNK, "review-pass", "acme/widget", 42,
                        "reviewed-head",
                        new ReviewBuildSelectionStore.SpawnInput(
                                WORKSPACE, "Fix finding",
                                ReviewBuildSelectionStore.SelectionPolicy.EXPLICIT,
                                ReviewBuildSpawnService.MODE_AUTHOR,
                                "acme/widget", "acme/widget", "main",
                                "feature/review"),
                        "selection-digest",
                        List.of(new ReviewBuildSelectionStore.Finding(
                                "review-pass", "finding-1", 1, "{}", "digest")),
                        Instant.ofEpochMilli(2));
        when(selections.find(TRUNK)).thenReturn(Optional.of(selection));
        when(selections.matchesCurrent(selection)).thenReturn(false);
        V2TaskCreationService service = new V2TaskCreationService(
                new DevelopmentFlowCanaryRoute(true, WORKSPACE),
                handoff, jdbc, mock(ThreadStore.class), mock(TaskStore.class),
                mock(ThreadEngineOverrides.class), repositories,
                mock(WorkspaceRelationService.class), pullRequests,
                mock(PatResolver.class), selections, new ObjectMapper(),
                mock(V2DevelopmentFlowProjection.class));
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "codex", "gpt-test", "Fix finding",
                repositoryRoot.toString(), null, "Address #finding-finding-1",
                List.of(), "DEVELOP", 42, null, ThreadFlow.BUILD, WORKSPACE,
                PLAN_MODEL);

        assertThatThrownBy(() -> service.create(reviewTrunk(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed before V2 Task materialization");
        verify(pullRequests, never()).fetchPrDetail(any(), any());
        verify(handoff, never()).create(any());
    }

    @Test
    void suggestedChangeSelectionCannotMaterializeAWritableV2Task()
    {
        Path repositoryRoot = tempDir.resolve("suggested-repo").toAbsolutePath();
        JdbcTemplate jdbc = database("suggested-creation.db", repositoryRoot);
        TaskCreationHandoff handoff = mock(TaskCreationHandoff.class);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        ReviewBuildSelectionStore selections = mock(
                ReviewBuildSelectionStore.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        when(repositories.resolve(WORKSPACE)).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(selections.find(TRUNK)).thenReturn(Optional.of(
                new ReviewBuildSelectionStore.Selection(
                        TRUNK, "review-pass", "acme/widget", 42,
                        "reviewed-head",
                        new ReviewBuildSelectionStore.SpawnInput(
                                WORKSPACE, "Fix finding",
                                ReviewBuildSelectionStore.SelectionPolicy.EXPLICIT,
                                ReviewBuildSpawnService.MODE_SUGGESTED,
                                "acme/widget", "other/widget", "main",
                                "feature/review"),
                        "selection-digest",
                        List.of(new ReviewBuildSelectionStore.Finding(
                                "review-pass", "finding-1", 1, "{}", "digest")),
                        Instant.ofEpochMilli(2))));
        V2TaskCreationService service = new V2TaskCreationService(
                new DevelopmentFlowCanaryRoute(true, WORKSPACE),
                handoff, jdbc, mock(ThreadStore.class), mock(TaskStore.class),
                mock(ThreadEngineOverrides.class), repositories,
                mock(WorkspaceRelationService.class), pullRequests,
                mock(PatResolver.class), selections, new ObjectMapper(),
                mock(V2DevelopmentFlowProjection.class));
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, "codex", "gpt-test", "Fix finding",
                repositoryRoot.toString(), null, "Address #finding-finding-1",
                List.of(), "DEVELOP", 42, null, ThreadFlow.BUILD, WORKSPACE,
                PLAN_MODEL);

        assertThatThrownBy(() -> service.create(reviewTrunk(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comment-only")
                .hasMessageContaining("cannot materialize a writable V2 Task");
        verify(pullRequests, never()).fetchPrDetail(any(), any());
        verify(handoff, never()).create(any());
    }

    private JdbcTemplate database(String name, Path repositoryRoot)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("246").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('acme', 'widget', ?)
                """, repositoryRoot.toString());
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, 'Workspace', '', 0, 1, 1)
                """, WORKSPACE);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES (?, 'acme/widget', 'main', 0, 1)
                """, WORKSPACE);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state, aggregate_version)
                VALUES (?, 'CLI_AGENT', 'codex', 'Trunk', 'IDLE', 'gpt-test',
                    0, 0, 0, 1, 1, ?, 'build', 4, 'LEGACY', NULL, 0)
                """, TRUNK, WORKSPACE);
        return jdbc;
    }

    private static TaskCreationHandoff.Result creationResult(Path repositoryRoot)
    {
        TaskManager.State state = new TaskManager.State(
                TASK, TRUNK, TaskLifecycle.PROVISIONING, 1, 0,
                null, null, null, null, null);
        TaskManager.TaskCreationReceipt receipt = new TaskManager.TaskCreationReceipt(
                state, 1, "authorization", "assignment", "policy", "brain",
                "provision", "ticket", "operation", "dev/" + TASK,
                repositoryRoot.resolve(".worktrees").resolve(TASK).toString());
        ProvisionTarget target = ProvisionTarget.derive(
                TASK, repositoryRoot, new TaskAssignment.Direct("acme/widget"));
        TaskManager.TaskCreationResult task = new TaskManager.TaskCreationResult(
                receipt, target, CommandResult.Disposition.APPLIED);
        return new TaskCreationHandoff.Result(
                new TrunkManager.State(TRUNK, TrunkLifecycle.ACTIVE, 2),
                task, CommandResult.Disposition.APPLIED);
    }

    private static Thread trunk()
    {
        Instant now = Instant.ofEpochMilli(1);
        return new Thread(
                TRUNK, ThreadKind.CLI_AGENT, "codex", null, "Trunk",
                ThreadStatus.IDLE, "gpt-test", 0, 0, 0, now, now,
                null, null, ThreadFlow.BUILD, WORKSPACE, PLAN_MODEL);
    }

    private static Thread reviewTrunk()
    {
        Instant now = Instant.ofEpochMilli(1);
        return new Thread(
                TRUNK, ThreadKind.CLI_AGENT, "codex", null, "Review build",
                ThreadStatus.IDLE, "gpt-test", 0, 0, 0, now, now,
                null, null, ThreadFlow.BUILD, WORKSPACE, PLAN_MODEL,
                "review-pass");
    }

    private static Task taskShape()
    {
        return new Task(
                TASK, TRUNK, 1, TaskStatus.PENDING,
                "dev/" + TASK, "/tmp/" + TASK, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0, 0, 0, null, Instant.ofEpochMilli(1), null, null,
                "Build exact flow", null, PLAN_MODEL, null, null, null,
                0, null, "Implement the accepted design", Task.ORIGIN_USER);
    }
}
