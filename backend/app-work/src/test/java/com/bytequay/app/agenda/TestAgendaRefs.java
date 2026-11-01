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
package com.bytequay.app.agenda;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgendaRefs
{
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final Workspace GITHUB_WORKSPACE = workspace("ws1", RepoRef.of("Owner", "Repo"), HostingServer.GITHUB_COM);
    private static final Workspace GITLAB_WORKSPACE = workspace("ws2", RepoRef.of("Group/Sub", "Project"),
            new HostingServer(GITLAB, URI.create("https://gitlab.example.com"), URI.create("https://gitlab.example.com/api/v4")));

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);

    @Test
    void keysAnIssueByItsWorkspaceAndLowerCasePathOnEveryProvider()
    {
        assertThat(AgendaRefs.issue(GITHUB_WORKSPACE, 3)).isEqualTo("issue:ws1:owner/repo#3");
        assertThat(AgendaRefs.issue(GITLAB_WORKSPACE, 3)).isEqualTo("issue:ws2:group/sub/project#3");
        assertThat(AgendaRefs.numbered("issue:ws2:group/sub/project#3")).contains(new AgendaRefs.Numbered("group/sub/project", 3, Optional.of("ws2")));
        assertThat(AgendaRefs.numbered("issue:group/sub/project#3")).contains(new AgendaRefs.Numbered("group/sub/project", 3));
    }

    @Test
    void findsTheWorkspaceARefNames()
    {
        when(workspaces.findById("ws2")).thenReturn(Optional.of(GITLAB_WORKSPACE));

        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("group/sub/project", 3, Optional.of("ws2")), workspaces)).contains(GITLAB_WORKSPACE);
        // A ref whose path is not its workspace's names nothing.
        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("owner/repo", 3, Optional.of("ws2")), workspaces)).isEmpty();
        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("group/sub/project", 3, Optional.of("gone")), workspaces)).isEmpty();
    }

    @Test
    void resolvesARefTypedWithoutItsWorkspaceToTheOnlyWatchedWorkspaceOnThatPathOnAnyServer()
    {
        when(workspaces.findAll()).thenReturn(ImmutableList.of(GITHUB_WORKSPACE, GITLAB_WORKSPACE));

        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("group/sub/project", 3), workspaces)).contains(GITLAB_WORKSPACE);
        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("owner/repo", 3), workspaces)).contains(GITHUB_WORKSPACE);
        assertThat(AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("other/repo", 3), workspaces)).isEmpty();

        Workspace twin = workspace("ws3", RepoRef.of("owner", "repo"), GITLAB_WORKSPACE.server());
        when(workspaces.findAll()).thenReturn(ImmutableList.of(GITHUB_WORKSPACE, twin));
        assertThatIllegalArgumentException().isThrownBy(() -> AgendaRefs.issueWorkspace(new AgendaRefs.Numbered("owner/repo", 3), workspaces))
                .withMessage("owner/repo#3 is ambiguous: more than one watched repository has that path; name the workspace");
    }

    private static Workspace workspace(String id, RepoRef repository, HostingServer server)
    {
        return new Workspace(id, repository.repo(), 1, repository, Optional.empty(), Optional.empty(), 1, repository, Workspace.Topology.DIRECT, "main",
                "https://example.com/" + repository.fullName() + ".git", Optional.empty(), Optional.empty(), "/managed/" + id, Optional.empty(), Optional.empty(), 0, NOW, NOW,
                server);
    }
}
