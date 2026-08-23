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
package com.bytequay.app.git;

import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.CommitHistoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultCommitHistoryService
{
    private static final String WORKSPACE_ID = "workspace-id";
    private static final Path REPOSITORY = Path.of("/managed/repository");
    private static final RepoRef SELECTED = RepoRef.of("example-owner", "example-fork");
    private static final RepoRef UPSTREAM = RepoRef.of("example-upstream", "example-repo");

    private WorkspaceRepositoryService workspaces;
    private GitBrowseService git;
    private CommitHistoryStore store;
    private CommitHistoryRefreshWorker refreshWorker;
    private DefaultCommitHistoryService service;

    @BeforeEach
    void setUp()
    {
        workspaces = mock(WorkspaceRepositoryService.class);
        git = mock(GitBrowseService.class);
        store = mock(CommitHistoryStore.class);
        refreshWorker = mock(CommitHistoryRefreshWorker.class);
        service = new DefaultCommitHistoryService(
                workspaces,
                git,
                store,
                refreshWorker,
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC),
                53123);
        when(store.findRefreshStatus(WORKSPACE_ID)).thenReturn(CommitRefreshStatus.idle(WORKSPACE_ID));
    }

    @Test
    void readsWorkspaceHistoryFromGitAndLocalEnrichmentOnly()
    {
        ready(linkedSummary());
        Commit commit = commit("abc123", Optional.empty());
        when(git.log(REPOSITORY, "feature", 300)).thenReturn(List.of(commit));
        when(store.findEnrichment(101, List.of("abc123"))).thenReturn(new CommitEnrichmentSnapshot(List.of(
                enrichment("abc123"))));

        CommitHistorySnapshot snapshot = service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.WORKSPACE, "feature"), 500);

        assertThat(snapshot.commits()).containsExactly(new CommitHistoryItem(
                "abc123",
                "Example change",
                "Example body",
                "Example Author",
                Optional.of("http://127.0.0.1:53123/api/avatars/" + "a".repeat(64)),
                "2026-08-22T01:00:00Z",
                Optional.of(42),
                Optional.of("Example pull request")));
        assertThat(snapshot.refresh()).isEqualTo(CommitRefreshStatus.idle(WORKSPACE_ID));
        verify(git).log(REPOSITORY, "feature", 300);
    }

    @Test
    void resolvesLinkedUpstreamThroughDurableWorkspaceRouting()
    {
        ready(linkedSummary());
        Commit commit = commit("def456", Optional.of(17));
        when(git.log(REPOSITORY, "upstream/main", 25)).thenReturn(List.of(commit));
        when(store.findEnrichment(202, List.of("def456")))
                .thenReturn(new CommitEnrichmentSnapshot(List.of()));

        CommitHistorySnapshot snapshot = service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.UPSTREAM, "main"), 25);

        assertThat(snapshot.commits()).singleElement().satisfies(item -> {
            assertThat(item.sha()).isEqualTo("def456");
            assertThat(item.authorAvatarUrl()).isEmpty();
            assertThat(item.pullRequestNumber()).contains(17);
            assertThat(item.pullRequestTitle()).isEmpty();
        });
        verify(store).findEnrichment(202, List.of("def456"));
    }

    @Test
    void rejectsAnUnlinkedUpstreamBeforeReadingGit()
    {
        ready(unlinkedSummary());

        assertThatThrownBy(() -> service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.UPSTREAM, "main"), 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no upstream repository");
        verify(git, never()).log(REPOSITORY, "upstream/main", 25);
    }

    @Test
    void validatesBranchesLimitsAndWorkspaceReadiness()
    {
        assertThatThrownBy(() -> new CommitHistoryRef(Source.WORKSPACE, "-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.WORKSPACE, "main"), 0))
                .isInstanceOf(IllegalArgumentException.class);

        when(workspaces.requireReadyRepository(WORKSPACE_ID))
                .thenThrow(new IllegalStateException("repository is not ready"));
        assertThatThrownBy(() -> service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.WORKSPACE, "main"), 25))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
        verify(git, never()).log(REPOSITORY, "main", 25);
    }

    @Test
    void propagatesAMissingWorkspaceBeforeReadingGit()
    {
        when(workspaces.requireReadyRepository(WORKSPACE_ID))
                .thenThrow(new IllegalArgumentException("workspace is missing"));

        assertThatThrownBy(() -> service.listHistory(
                WORKSPACE_ID, new CommitHistoryRef(Source.WORKSPACE, "main"), 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        verify(git, never()).log(REPOSITORY, "main", 25);
    }

    @Test
    void hasNoProviderOrNetworkClientDependency()
    {
        Set<Class<?>> dependencies = java.util.Arrays.stream(DefaultCommitHistoryService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies)
                .contains(WorkspaceRepositoryService.class, GitBrowseService.class, CommitHistoryStore.class)
                .noneMatch(type -> type.getSimpleName().contains("GitHub") || type.getSimpleName().contains("Http"));
    }

    private void ready(WorkspaceSummary summary)
    {
        when(workspaces.requireReadyRepository(WORKSPACE_ID)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE_ID,
                REPOSITORY,
                summary.selectedRepositoryId(),
                SELECTED,
                summary.upstreamRepositoryId() == null
                        ? OptionalLong.empty()
                        : OptionalLong.of(summary.upstreamRepositoryId()),
                summary.upstreamRepositoryId() == null ? SELECTED : UPSTREAM,
                summary.upstreamRepositoryId() == null ? "origin" : "upstream",
                "origin",
                "main"));
    }

    private static WorkspaceSummary linkedSummary()
    {
        return summary(202L, "example-upstream", "example-repo");
    }

    private static WorkspaceSummary unlinkedSummary()
    {
        return summary(null, null, null);
    }

    private static WorkspaceSummary summary(
            Long upstreamRepositoryId,
            String upstreamOwner,
            String upstreamRepo)
    {
        return new WorkspaceSummary(
                WORKSPACE_ID,
                "Example workspace",
                101,
                SELECTED.owner(),
                SELECTED.repo(),
                upstreamRepositoryId,
                upstreamOwner,
                upstreamRepo,
                upstreamRepositoryId == null ? 101 : upstreamRepositoryId,
                upstreamOwner == null ? SELECTED.owner() : upstreamOwner,
                upstreamRepo == null ? SELECTED.repo() : upstreamRepo,
                upstreamRepositoryId == null ? Topology.DIRECT : Topology.FORK,
                "main",
                upstreamRepositoryId == null ? null : "main",
                REPOSITORY.toString(),
                CodeState.READY,
                MetadataState.READY,
                Instant.parse("2026-08-22T00:00:00Z"),
                Instant.parse("2026-08-22T00:00:00Z"),
                null,
                null,
                0);
    }

    private static Commit commit(String sha, Optional<Integer> pullRequestNumber)
    {
        return new Commit(
                sha,
                "Example change",
                "Example body",
                "Example Author",
                "2026-08-22T01:00:00Z",
                pullRequestNumber);
    }

    private static CommitEnrichment enrichment(String sha)
    {
        Instant checkedAt = Instant.parse("2026-08-22T02:00:00Z");
        return new CommitEnrichment(
                101,
                sha,
                Optional.of("a".repeat(64)),
                Optional.of(42),
                Optional.of("Example pull request"),
                Optional.of("https://example.invalid/pull/42"),
                Optional.of(checkedAt),
                Optional.of(checkedAt),
                checkedAt);
    }
}
