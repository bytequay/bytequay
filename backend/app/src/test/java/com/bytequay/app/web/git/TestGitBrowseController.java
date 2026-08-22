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

import com.bytequay.app.cherrypick.CherryPickPullRequestLookup;
import com.bytequay.app.cherrypick.PullRequestAssociation;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.git.GitBrowseService.FileContent;
import com.bytequay.app.git.GitBrowseService.RepoOverview;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
    private GitHubRepoClient gitHub;
    private CredentialService credentials;
    private CherryPickPullRequestLookup pullRequests;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        workspaces = mock(WorkspaceRepositoryService.class);
        git = mock(GitBrowseService.class);
        gitHub = mock(GitHubRepoClient.class);
        credentials = mock(CredentialService.class);
        pullRequests = mock(CherryPickPullRequestLookup.class);
        mvc = standaloneSetup(new GitBrowseController(workspaces, git, gitHub, credentials, pullRequests))
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
    void addsGitHubAvatarsToUpstreamCommitHistory()
            throws Exception
    {
        when(workspaces.requireReadyRepository("workspace-a")).thenReturn(context("workspace-a", REPOSITORY_A));
        when(git.log(REPOSITORY_A, "upstream/main", 20)).thenReturn(ImmutableList.of(new Commit(
                "abc", "Title", "", "Alice Example", "2026-08-22T00:00:00Z", Optional.empty())));
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(gitHub.fetchCommitAvatars("pat", RepoRef.of("upstream", "project"), "main", 20))
                .thenReturn(ImmutableMap.of("abc", "https://avatars/alice"));
        when(pullRequests.lookUp(
                eq(RepoRef.of("upstream", "project")),
                eq(ImmutableList.of("abc")),
                eq("main"),
                anyList()))
                .thenReturn(ImmutableMap.of("abc", new PullRequestAssociation(
                        42, "Improve commit browsing", "https://github.test/pull/42", "main", "2026-08-22T00:00:00Z")));

        mvc.perform(get("/api/workspaces/workspace-a/git/commits")
                        .param("ref", "upstream/main")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].author").value("Alice Example"))
                .andExpect(jsonPath("$[0].authorAvatarUrl").value("https://avatars/alice"))
                .andExpect(jsonPath("$[0].pullRequestNumber").value(42))
                .andExpect(jsonPath("$[0].pullRequestTitle").value("Improve commit browsing"));
    }

    private static WorkspaceRepositoryContext context(String workspaceId, Path repository)
    {
        return new WorkspaceRepositoryContext(
                workspaceId,
                repository,
                RepoRef.of("fork", "project"),
                RepoRef.of("upstream", "project"),
                "upstream",
                "origin",
                "main");
    }
}
