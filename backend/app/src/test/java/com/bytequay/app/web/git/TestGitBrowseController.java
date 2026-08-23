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
package com.bytequay.app.web.git;

import com.bytequay.app.git.CommitHistoryItem;
import com.bytequay.app.git.CommitHistoryRef;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryService;
import com.bytequay.app.git.CommitHistorySnapshot;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitBrowseService.FileContent;
import com.bytequay.app.git.GitBrowseService.RepoOverview;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestGitBrowseController
{
    private static final Path REPOSITORY_A = Path.of("/managed/workspace-a/repository");
    private static final Path REPOSITORY_B = Path.of("/managed/workspace-b/repository");

    private WorkspaceRepositoryService workspaces;
    private GitBrowseService git;
    private CommitHistoryService commits;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        workspaces = mock(WorkspaceRepositoryService.class);
        git = mock(GitBrowseService.class);
        commits = mock(CommitHistoryService.class);
        mvc = standaloneSetup(new GitBrowseController(workspaces, git, commits))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void resolvesOnlyTheRequestedWorkspaceManagedRepository()
            throws Exception
    {
        when(workspaces.requireReadyRepository("workspace-a")).thenReturn(context("workspace-a", REPOSITORY_A));
        when(git.overview(REPOSITORY_A)).thenReturn(new RepoOverview("main", ImmutableList.of("main"), ImmutableList.of()));

        mvc.perform(get("/api/workspaces/workspace-a/git/overview")
                        .param("repository", REPOSITORY_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("main"));

        verify(git).overview(REPOSITORY_A);
        verify(git, never()).overview(REPOSITORY_B);
    }

    @Test
    void reportsPendingAndFailedClonesWithTheStableNotReadyResponse()
            throws Exception
    {
        when(workspaces.requireReadyRepository("pending")).thenThrow(WorkspaceException.repositoryNotReady());
        when(workspaces.requireReadyRepository("failed")).thenThrow(WorkspaceException.repositoryNotReady());

        for (String workspaceId : ImmutableList.of("pending", "failed")) {
            mvc.perform(get("/api/workspaces/{workspaceId}/git/status", workspaceId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WORKSPACE_CODE_NOT_READY"))
                    .andExpect(jsonPath("$.retryable").value(true));
        }
    }

    @Test
    void acceptsOnlyRemotesConfiguredForTheWorkspace()
            throws Exception
    {
        WorkspaceRepositoryContext context = context("workspace-a", REPOSITORY_A);
        when(workspaces.requireReadyRepository("workspace-a")).thenReturn(context);

        mvc.perform(post("/api/workspaces/workspace-a/git/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remotes\":[\"origin\",\"upstream\"]}"))
                .andExpect(status().isOk());
        verify(git).fetch(REPOSITORY_A, ImmutableList.of("origin", "upstream"));

        mvc.perform(get("/api/workspaces/workspace-a/git/branches")
                        .param("remote", "--upload-pack=malicious"))
                .andExpect(status().isBadRequest());
        verify(git, never()).remoteBranches(REPOSITORY_A, "--upload-pack=malicious");
    }

    @Test
    void readsDiffContextFromTheManagedRepository()
            throws Exception
    {
        when(workspaces.requireReadyRepository("workspace-a")).thenReturn(context("workspace-a", REPOSITORY_A));
        when(git.diffFileContent(REPOSITORY_A, "base", "head", "src/App.java"))
                .thenReturn(new FileContent(Optional.of("old\n"), Optional.of("new\n")));

        mvc.perform(get("/api/workspaces/workspace-a/git/diff-content")
                        .param("base", "base")
                        .param("head", "head")
                        .param("path", "src/App.java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldContent").value("old\n"))
                .andExpect(jsonPath("$.newContent").value("new\n"));

        verify(git).diffFileContent(REPOSITORY_A, "base", "head", "src/App.java");
    }

    @Test
    void returnsTheCompleteLocalCommitHistorySnapshotWithoutProviderClients()
            throws Exception
    {
        CommitHistoryRef ref = new CommitHistoryRef(Source.UPSTREAM, "main");
        when(commits.listHistory("workspace-a", ref, 20)).thenReturn(new CommitHistorySnapshot(
                List.of(new CommitHistoryItem(
                        "abc",
                        "Example change",
                        "",
                        "Example Author",
                        Optional.of("http://127.0.0.1:53123/api/avatars/" + "a".repeat(64)),
                        "2026-08-22T00:00:00Z",
                        Optional.of(42),
                        Optional.of("Example pull request"))),
                CommitRefreshStatus.idle("workspace-a")));

        mvc.perform(get("/api/workspaces/workspace-a/git/commits")
                        .param("source", "UPSTREAM")
                        .param("branch", "main")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commits[0].author").value("Example Author"))
                .andExpect(jsonPath("$.commits[0].authorAvatarUrl").isNotEmpty())
                .andExpect(jsonPath("$.commits[0].pullRequestNumber").value(42))
                .andExpect(jsonPath("$.commits[0].pullRequestTitle").value("Example pull request"));
        verify(commits).listHistory("workspace-a", ref, 20);
    }

    private static WorkspaceRepositoryContext context(String workspaceId, Path repository)
    {
        return new WorkspaceRepositoryContext(
                workspaceId,
                repository,
                101,
                RepoRef.of("fork", "project"),
                OptionalLong.of(202),
                RepoRef.of("upstream", "project"),
                "upstream",
                "origin",
                "main");
    }
}
