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
package com.bytequay.app.web;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.GitHubIssuePreview;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestRepoControllerIssuePreview
{
    private final GitHubRepoClient gitHub = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final RepoController controller = new RepoController(
            new RepoService(mock(GitHubPullRequestReadClient.class), mock(GitHubAccountClient.class), gitHub, credentials),
            workspaces);

    @Test
    void returnsAnIssuePreviewWithoutRequiringAWorkspaceOrPullRequestRow()
            throws Exception
    {
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(gitHub.fetchIssuePreview("pat", RepoRef.of("owner", "repository"), 30611))
                .thenReturn(new GitHubIssuePreview(
                        "Kerberos container times out", "Details", "alice", ImmutableList.of("bug"), Instant.parse("2026-08-21T00:00:00Z"), "closed", false,
                        "https://github.com/owner/repository/issues/30611", ""));

        standaloneSetup(controller).build().perform(get("/api/repos/owner/repository/issues/30611/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Kerberos container times out"))
                .andExpect(jsonPath("$.body").value("Details"))
                .andExpect(jsonPath("$.author").value("alice"))
                .andExpect(jsonPath("$.labels[0]").value("bug"))
                .andExpect(jsonPath("$.state").value("closed"))
                .andExpect(jsonPath("$.pullRequest").value(false));

        verify(gitHub).fetchIssuePreview("pat", RepoRef.of("owner", "repository"), 30611);
        verifyNoInteractions(workspaces);
    }

    @Test
    void rejectsInvalidIssueNumbersBeforeResolvingCredentials()
            throws Exception
    {
        standaloneSetup(controller).build().perform(get("/api/repos/owner/repository/issues/0/preview"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(credentials, gitHub, workspaces);
    }

    @Test
    void activityTitleLookupsCannotImportPullRequests()
            throws Exception
    {
        standaloneSetup(controller).build().perform(get("/api/repos/example/harbor/pulls/32005"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(credentials, gitHub, workspaces);
    }
}
