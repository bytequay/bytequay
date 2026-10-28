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
import com.bytequay.core.store.PrDetailStore;
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
    @Test
    void scopesGitLabIssueKeysAndUsesTheSelectedAccountAndServer()
    {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        RepoRef repository = RepoRef.of("group/subgroup", "repo");
        Workspace workspace = new Workspace("workspace", "Repo", 42, repository, Optional.empty(), Optional.empty(), 42, repository,
                Workspace.Topology.DIRECT, "main", "https://gitlab.example.com/gitlab/group/subgroup/repo.git", Optional.empty(), Optional.empty(), "/tmp/repo",
                Optional.empty(), Optional.empty(), 0, now, now, Optional.of(new HostingServer(GITLAB, URI.create("https://gitlab.example.com/gitlab"),
                        URI.create("https://gitlab.example.com/gitlab/api/v4"))));
        RecentStore recent = mock(RecentStore.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
        HostingIdentityStore identities = mock(HostingIdentityStore.class);
        CredentialService credentials = mock(CredentialService.class);
        when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
        when(workspaces.findHostingBinding(workspace.id())).thenReturn(Optional.of(new WorkspaceHostingBinding(workspace.id(), 10, 99, Optional.empty())));
        when(identities.findAccount(10)).thenReturn(Optional.of(new HostingAccount(10, 1, "2", "alice")));
        String url = "https://gitlab.example.com/gitlab/group/subgroup/repo/-/issues/7";
        when(issues.find(workspaces, workspace, 7)).thenReturn(Optional.of(new RepositoryIssue(70, 7, "Fix selection", "body", "alice", "", url,
                ImmutableList.of(), ImmutableList.of(), 0, now, now)));
        RecentTouchRecorder recorder = new RecentTouchRecorder(recent, mock(PullRequestStore.class), mock(PrDetailStore.class), issues, workspaces,
                identities, credentials, Clock.fixed(now, ZoneOffset.UTC));

        recorder.issueAction(workspace.id(), 7, RecentReason.COMMENTED);

        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:group/subgroup/repo#7") && touch.mine()
                && touch.htmlUrl().equals(url) && touch.workspaceId().equals(workspace.id())));
        verifyNoInteractions(credentials);
        recorder.issueAction(workspace.id(), 8, RecentReason.EDITED);
        verify(recent).recordTouch(argThat(touch -> touch.itemKey().equals("issue:workspace:group/subgroup/repo#8")
                && touch.htmlUrl().equals("https://gitlab.example.com/gitlab/group/subgroup/repo/-/issues/8")));
    }
}
