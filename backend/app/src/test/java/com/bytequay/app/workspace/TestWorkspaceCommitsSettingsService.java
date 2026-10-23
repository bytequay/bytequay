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
package com.bytequay.app.workspace;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceCommitsSettingsService
{
    private static final String WORKSPACE_ID = "workspace";
    private static final String KEY = "workspace.commitsTargetBranch." + WORKSPACE_ID;
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private final InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final WorkspaceCommitsSettingsService service = new WorkspaceCommitsSettingsService(workspaces, settings, new GitCommandRunner());

    @Test
    void automaticTargetFollowsCurrentRoutingWithoutGitCommands()
    {
        GitCommandRunner git = mock(GitCommandRunner.class);
        WorkspaceCommitsSettingsService reader = new WorkspaceCommitsSettingsService(workspaces, settings, git);
        workspaces.save(workspace(false));
        assertThat(reader.get(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("origin", "fork-main", false));

        workspaces.save(workspace(true));
        assertThat(reader.get(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("upstream", "master", false));
        assertThat(reader.reset(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("upstream", "master", false));
        verifyNoInteractions(git);
        assertThat(settings.snapshot()).isEmpty();
    }

    @Test
    void readingTargetDoesNotWaitForWorkspaceSynchronization()
            throws Exception
    {
        workspaces.save(workspace(true));
        Lock lock = WorkspaceOperationLocks.get(WORKSPACE_ID);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.lock();
            try {
                assertThat(executor.submit(() -> service.get(WORKSPACE_ID)).get(1, SECONDS))
                        .isEqualTo(new WorkspaceCommitsTarget("upstream", "master", false));
            }
            finally {
                lock.unlock();
            }
        }
    }

    @Test
    void overrideSurvivesRecreationAndUnwatchWhileRemoteFollowsRouting()
    {
        workspaces.save(workspace(true));

        assertThat(service.save(WORKSPACE_ID, "upstream/release-next")).isEqualTo(new WorkspaceCommitsTarget("upstream", "upstream/release-next", true));
        assertThat(settings.snapshot()).containsOnlyKeys(KEY).containsEntry(KEY, "upstream/release-next");
        workspaces.setWatched(WORKSPACE_ID, false);
        WorkspaceCommitsSettingsService reopened = new WorkspaceCommitsSettingsService(workspaces, settings, mock(GitCommandRunner.class));
        assertThat(reopened.get(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("upstream", "upstream/release-next", true));

        workspaces.save(workspace(false));
        assertThat(reopened.get(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("origin", "upstream/release-next", true));
        assertThat(reopened.reset(WORKSPACE_ID)).isEqualTo(new WorkspaceCommitsTarget("origin", "fork-main", false));
        assertThat(settings.snapshot()).isEmpty();
        assertThat(workspaces.isWatched(WORKSPACE_ID)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "-option", "HEAD", "refs/heads/main", "main~1", "a..b", "a:b", "a.lock", "a b", "@{-1}", "trailing/"})
    void rejectsInvalidBranchWithoutReplacingPreviousOverride(String branch)
    {
        workspaces.save(workspace(true));
        settings.put(KEY, "keep-branch");

        assertThatThrownBy(() -> service.save(WORKSPACE_ID, branch)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.snapshot()).containsEntry(KEY, "keep-branch");
    }

    @Test
    void missingWorkspaceCannotReadCreateOrResetASetting()
    {
        GitCommandRunner git = mock(GitCommandRunner.class);
        WorkspaceCommitsSettingsService missing = new WorkspaceCommitsSettingsService(workspaces, settings, git);

        assertThatThrownBy(() -> missing.get(WORKSPACE_ID)).isInstanceOf(WorkspaceException.class).hasMessage("Workspace not found: workspace");
        assertThatThrownBy(() -> missing.save(WORKSPACE_ID, "main")).isInstanceOf(WorkspaceException.class).hasMessage("Workspace not found: workspace");
        assertThatThrownBy(() -> missing.reset(WORKSPACE_ID)).isInstanceOf(WorkspaceException.class).hasMessage("Workspace not found: workspace");
        assertThat(settings.snapshot()).isEmpty();
        verifyNoInteractions(git);
    }

    @Test
    void failedGitValidationDoesNotReplaceTheSetting()
    {
        workspaces.save(workspace(true));
        settings.put(KEY, "keep-branch");
        GitCommandRunner git = mock(GitCommandRunner.class);
        when(git.run(Path.of("/"), "check-ref-format", "refs/heads/release")).thenReturn(GitCommandRunner.Result.failed("Git unavailable"));
        WorkspaceCommitsSettingsService unavailable = new WorkspaceCommitsSettingsService(workspaces, settings, git);

        assertThatThrownBy(() -> unavailable.save(WORKSPACE_ID, "release")).isInstanceOf(IllegalStateException.class).hasMessage("Git branch validation could not complete.");
        assertThat(settings.snapshot()).containsEntry(KEY, "keep-branch");
    }

    private static Workspace workspace(boolean linked)
    {
        RepoRef selected = RepoRef.of("owner", "project");
        RepoRef upstream = RepoRef.of("upstream", "project");
        return new Workspace(WORKSPACE_ID, "Project", 100, selected,
                linked ? Optional.of(200L) : Optional.empty(), linked ? Optional.of(upstream) : Optional.empty(),
                linked ? 200 : 100, linked ? upstream : selected, Topology.FORK, "fork-main", "https://github.com/owner/project.git",
                linked ? Optional.of("master") : Optional.empty(), linked ? Optional.of("https://github.com/upstream/project.git") : Optional.empty(),
                "/not-provisioned/repository", Optional.empty(), Optional.empty(), 0, NOW, NOW);
    }
}
