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
package com.bytequay.app.service.recent;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRecentIssueIdentity
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final HostingServer GITLAB_SERVER = new HostingServer(GITLAB, URI.create("https://gitlab.example.com/gitlab"),
            URI.create("https://gitlab.example.com/gitlab/api/v4"));

    private final RecentStore recent = mock(RecentStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final RecentTouchRecorder recorder = new RecentTouchRecorder(recent, mock(PullRequestStore.class), issues, workspaces, identities, credentials,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void scopesGitLabIssueKeysAndUsesTheSelectedAccountAndServer()
    {
        Workspace workspace = workspace(RepoRef.of("group/subgroup", "repo"), GITLAB_SERVER);
        when(workspaces.findHostingBinding(workspace.id())).thenReturn(Optional.of(new WorkspaceHostingBinding(workspace.id(), 10, 99, Optional.empty())));
        when(identities.findAccount(10)).thenReturn(Optional.of(new HostingAccount(10, 1, "2", "alice")));
        String url = "https://gitlab.example.com/gitlab/group/subgroup/repo/-/issues/7";
        when(issues.find(workspaces, workspace, 7)).thenReturn(Optional.of(issue(url)));

        recorder.issueAction(workspace.id(), 7, RecentReason.COMMENTED);

        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:group/subgroup/repo#7") && touch.mine()
                && touch.htmlUrl().equals(url) && touch.workspaceId().equals(workspace.id())));
        verifyNoInteractions(credentials);
        recorder.issueAction(workspace.id(), 8, RecentReason.EDITED);
        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:group/subgroup/repo#8")
                && touch.htmlUrl().equals("https://gitlab.example.com/gitlab/group/subgroup/repo/-/issues/8")));
    }

    @Test
    void keysAGitHubIssueByItsWorkspaceAndReadsTheDesktopSignInWhenTheWorkspaceHasNoAccount()
    {
        Workspace workspace = workspace(RepoRef.of("Owner", "Repo"), HostingServer.GITHUB_COM);
        when(credentials.cachedLogin()).thenReturn(Optional.of("alice"));
        when(issues.find(workspaces, workspace, 7)).thenReturn(Optional.of(issue("https://github.com/Owner/Repo/issues/7")));

        recorder.issueAction(workspace.id(), 7, RecentReason.COMMENTED);
        recorder.issueAction(workspace.id(), 8, RecentReason.EDITED);

        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:owner/repo#7") && touch.mine()));
        // An issue not stored yet opens through its provider's page, even when the workspace stores its server.
        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:owner/repo#8") && touch.htmlUrl().equals("https://github.com/Owner/Repo/issues/8")));
    }

    @Test
    void aGitLabWorkspaceWithoutAnAccountNeverReadsTheDesktopSignIn()
    {
        Workspace workspace = workspace(RepoRef.of("group", "repo"), GITLAB_SERVER);
        when(issues.find(workspaces, workspace, 7)).thenReturn(Optional.of(issue("https://gitlab.example.com/gitlab/group/repo/-/issues/7")));

        recorder.issueAction(workspace.id(), 7, RecentReason.COMMENTED);

        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:group/repo#7") && !touch.mine()));
        verifyNoInteractions(credentials);
    }

    private Workspace workspace(RepoRef repository, HostingServer server)
    {
        Workspace workspace = new Workspace("workspace", "Repo", 42, repository, Optional.empty(), Optional.empty(), 42, repository, Workspace.Topology.DIRECT, "main",
                server.webBaseUrl() + "/" + repository.fullName() + ".git", Optional.empty(), Optional.empty(), "/tmp/repo", Optional.empty(), Optional.empty(), 0, NOW, NOW,
                Optional.of(server));
        when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private static RepositoryIssue issue(String url)
    {
        return new RepositoryIssue(70, 7, "Fix selection", "body", "alice", "", url, ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW);
    }
}
