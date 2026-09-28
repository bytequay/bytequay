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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestContextMergedCandidateService
{
    private static final String WORKSPACE = "workspace";
    private static final RepoRef REPO = RepoRef.of("owner", "repo");
    private static final RepositoryIdentity PHYSICAL = new RepositoryIdentity("owner", "repo");
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ContextMergedCandidateStore store = new ContextMergedCandidateStore(
            new InMemoryAppSettingsStore(), new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()));
    private final PullRequestStore prs = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PullRequestTaskStore imports = mock(PullRequestTaskStore.class);
    private final IssueDevelopmentStore issues = mock(IssueDevelopmentStore.class);
    private final JiraIssueTaskStore jira = mock(JiraIssueTaskStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final ContextBuildStore buildStore = mock(ContextBuildStore.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<PullRequest> row = new AtomicReference<>(pr(null));
    private ContextMergedCandidateService service;
    private AgentTask task;

    @BeforeEach
    void setup()
    {
        TaskWorktree worktree = new TaskWorktree(TASK_ID, WORKSPACE, PHYSICAL,
                Path.of("/tmp/worktrees", TASK_ID.toString()), "bytequay/task/" + TASK_ID, "a".repeat(40));
        task = AgentTask.created(TASK_ID, true, "Change Iceberg", TitleSource.USER, AgentLaneId.CODEX_CLI,
                worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE, Path.of("/tmp/repo"), 1, REPO, OptionalLong.of(1), REPO, "origin", "origin", "main", ImmutableList.of()));
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(RepositoryNotebook.empty());
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task));
        when(prs.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(7L));
        when(prs.findById(7L)).thenAnswer(ignored -> Optional.of(row.get()));
        when(builds.get(WORKSPACE)).thenReturn(new ContextBuildService.View(0, 0, true, Optional.empty()));
        service = new ContextMergedCandidateService(store, buildStore, prs, details, imports, issues, jira, reviews,
                tasks, turns, workspaces, notebooks, builds, clock);
    }

    @Test
    void publishedLinkBecomesCandidateOnlyAfterObservedMergeAndExplicitLearn()
    {
        service.recordPublished(task, identity(REPO, REPO));
        service.recordPublished(task, identity(REPO, REPO));
        assertThat(store.get(WORKSPACE).links()).hasSize(1);
        assertThat(service.get(WORKSPACE).candidates()).isEmpty();
        verify(builds, never()).learnHistory(any(), any());

        row.set(pr(NOW.plusSeconds(3600)));
        ContextMergedCandidate candidate = service.get(WORKSPACE).candidates().getFirst();
        assertThat(candidate.id()).isEqualTo("pr:owner/repo#7");
        assertThat(candidate.taskId()).contains(TASK_ID.toString());
        assertThat(candidate.gaps()).anyMatch(gap -> gap.contains("validate them against current code"));

        ContextMergedCandidateService.LearnRequest request = new ContextMergedCandidateService.LearnRequest(
                candidate.id(), "learn-merged", 0, 0);
        service.learn(WORKSPACE, request);
        ArgumentCaptor<ContextBuildService.Request> captured = ArgumentCaptor.forClass(ContextBuildService.Request.class);
        verify(builds).learnHistory(eq(WORKSPACE), captured.capture());
        assertThat(captured.getValue().scopeIds()).containsExactly("project");
        assertThat(captured.getValue().history()).containsExactly(
                new ContextBuild.History(ContextHistoryReader.SourceKind.PR, 7));
        assertThat(store.get(WORKSPACE).selection().orElseThrow().candidate()).isEqualTo(candidate);

        row.set(pr(NOW.plusSeconds(7200)));
        assertThat(store.get(WORKSPACE).selection().orElseThrow().candidate().mergedAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void wrongPhysicalOrBaseRepositoryCannotCreateTaskAssociation()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> service.recordPublished(task,
                identity(RepoRef.of("other", "repo"), REPO)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.recordPublished(task,
                identity(REPO, RepoRef.of("other", "repo"))));
        assertThat(store.get(WORKSPACE).links()).isEmpty();
    }

    @Test
    void exactImportedTaskLinkIsConsideredWithoutBranchNameGuessing()
    {
        AgentTask completed = task.complete(new TaskSummary("Finished task", Optional.empty(), AgentMetrics.empty(),
                Optional.empty(), ImmutableList.of()), NOW.plusSeconds(1), NOW.plusSeconds(3600));
        when(tasks.listRecentCompletedInteractive(WORKSPACE, PHYSICAL, 50)).thenReturn(ImmutableList.of(completed));
        when(imports.findByTaskId(TASK_ID.toString())).thenReturn(Optional.of(
                new PullRequestTask(TASK_ID.toString(), WORKSPACE, "owner/repo", 7, "Imported PR")));
        row.set(pr(NOW.plusSeconds(3600)));

        assertThat(service.get(WORKSPACE).candidates()).extracting(ContextMergedCandidate::id)
                .containsExactly("pr:owner/repo#7");
        assertThat(store.get(WORKSPACE).links()).singleElement().satisfies(link -> {
            assertThat(link.taskId()).isEqualTo(TASK_ID.toString());
            assertThat(link.verifiedPublication()).isFalse();
        });
    }

    private static GitHubPullRequestIdentity identity(RepoRef head, RepoRef base)
    {
        return new GitHubPullRequestIdentity(PullRequestRef.of("owner", "repo", 7),
                "https://github.com/owner/repo/pull/7", head, "bytequay/task/" + TASK_ID, "b".repeat(40),
                base, "main", "a".repeat(40), GitHubPullRequestIdentity.State.OPEN, true, "owner");
    }

    private static PullRequest pr(Instant mergedAt)
    {
        return new PullRequest(7, "owner/repo", 7, "Change Iceberg", "owner", "https://github.com/owner/repo/pull/7",
                NOW, NOW, PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false,
                null, null, null, ImmutableList.of(), null, 1, 1, 0, mergedAt == null ? "open" : "merged",
                mergedAt, mergedAt, null, null, NOW, "bytequay/task/" + TASK_ID, null, null, null, null);
    }
}
