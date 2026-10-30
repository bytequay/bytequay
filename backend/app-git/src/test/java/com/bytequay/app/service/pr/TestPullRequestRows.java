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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestPullRequestRows
{
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private final PullRequestStore store = mock(PullRequestStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestRows rows = new PullRequestRows(store, workspaces);
    private final PullRequest stored = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));

    @Test
    void gitHubRowsAreFoundByRepositoryPath()
    {
        Workspace workspace = workspace(Optional.empty());
        when(store.findIdByRepoAndNumber("owner/project", 7)).thenReturn(Optional.of(30L));
        when(store.findById(30)).thenReturn(Optional.of(stored));
        when(store.findByHeadRef("owner/project", "feature")).thenReturn(ImmutableList.of(stored));

        assertThat(rows.find(workspace, "owner/project", 7)).contains(stored);
        assertThat(rows.findByHead(workspace, "owner/project", "feature")).containsExactly(stored);
        assertThat(rows.find(route(GITHUB))).contains(stored);
        assertThat(rows.findId(route(GITHUB))).contains(30L);
        verify(store, never()).findByHostingRepository(anyLong(), anyInt());
    }

    @Test
    void otherProvidersFindTheRowOfTheirRetainedRepository()
    {
        Workspace workspace = workspace(Optional.of(new HostingServer(GITLAB, URI.create("https://gitlab.example"), URI.create("https://gitlab.example/api/v4"))));
        when(workspaces.findHostingRepositoryId("workspace")).thenReturn(Optional.of(2L));
        when(store.findByHostingRepository(2, 7)).thenReturn(Optional.of(stored));
        when(stored.id()).thenReturn(40L);
        when(store.findByHostingHeadRef(2, "feature")).thenReturn(ImmutableList.of(stored));

        assertThat(rows.find(workspace, "group/project", 7)).contains(stored);
        assertThat(rows.findByHead(workspace, "group/project", "feature")).containsExactly(stored);
        assertThat(rows.findId(route(GITLAB))).contains(40L);
        verify(store, never()).findIdByRepoAndNumber(any(), anyInt());

        when(workspaces.findHostingRepositoryId("workspace")).thenReturn(Optional.empty());
        assertThat(rows.find(workspace, "group/project", 7)).isEmpty();
        assertThat(rows.findByHead(workspace, "group/project", "feature")).isEmpty();
    }

    @Test
    void refreshUpdatesAGitHubRowButReusesAnExistingHostedRow()
    {
        FetchedPullRequest summary = summary();
        when(store.saveObserved(summary)).thenReturn(stored);
        when(store.findByHostingRepository(2, 7)).thenReturn(Optional.of(stored));

        assertThat(rows.observe(route(GITHUB), summary)).isSameAs(stored);
        assertThat(rows.observe(route(GITLAB), summary)).isSameAs(stored);
        verify(store).saveObserved(summary);
        verify(store, never()).saveHosted(anyLong(), any());
    }

    @Test
    void cachedSummaryCreatesAMissingRowWithoutOverwritingAStoredOne()
    {
        FetchedPullRequest summary = summary();
        Workspace gitHub = workspace(Optional.empty());
        Workspace gitLab = workspace(Optional.of(new HostingServer(GITLAB, URI.create("https://gitlab.example"), URI.create("https://gitlab.example/api/v4"))));
        when(store.findIdByRepoAndNumber("owner/project", 7)).thenReturn(Optional.of(30L));
        when(store.findById(30)).thenReturn(Optional.of(stored));

        assertThat(rows.findOrCreate(gitHub, 2, summary)).isSameAs(stored);
        verify(store, never()).saveObserved(any());

        PullRequest created = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(store.findByHostingRepository(2, 7)).thenReturn(Optional.empty());
        when(store.saveHosted(2, summary)).thenReturn(created);
        assertThat(rows.findOrCreate(gitLab, 2, summary)).isSameAs(created);
    }

    private static PullRequestRead route(HostingProvider provider)
    {
        URI web = URI.create(provider == GITHUB ? "https://github.com" : "https://gitlab.example");
        HostingInstance instance = new HostingInstance(1, provider, web, URI.create(web + "/api"));
        HostingRepository repository = new HostingRepository(2, 1, "42", "owner/project", URI.create(web + "/owner/project"),
                URI.create(web + "/owner/project.git"), Optional.empty(), Optional.of("main"));
        return new PullRequestRead(new HostingReadSession(10, CONNECTED_ACCOUNT, new HostingAccess(instance, "token")), repository, 7, "alice");
    }

    private static Workspace workspace(Optional<HostingServer> server)
    {
        RepoRef repository = RepoRef.of("owner", "project");
        return new Workspace("workspace", "Project", 42, repository, Optional.empty(), Optional.empty(), 42, repository, Workspace.Topology.DIRECT, "main",
                "https://github.com/owner/project.git", Optional.empty(), Optional.empty(), "/tmp/project", Optional.empty(), Optional.empty(), 0, NOW, NOW, server);
    }

    private static FetchedPullRequest summary()
    {
        return new FetchedPullRequest(9001L, "owner/project", 7, "Title", "alice", "https://github.com/owner/project/pull/7", NOW, NOW, OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, "feature");
    }
}
