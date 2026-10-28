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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.HostingPullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceHostingService
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final HostingInstance INSTANCE = new HostingInstance(10, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
    private final InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final HostingCredentialStore credentials = mock(HostingCredentialStore.class);
    private final HostingRepositories repositories = mock(HostingRepositories.class);
    private final PullRequestReader requests = mock(PullRequestReader.class);
    private final HostingConnectionService connections = new HostingConnectionService(identities, credentials,
            ImmutableList.of(new HostingIntegration(GITHUB, mock(HostingAccounts.class), repositories, requests)));
    private final HostingPullRequestStore snapshots = mock(HostingPullRequestStore.class);
    private final HostingPullRequestService cache = new HostingPullRequestService(connections, snapshots, identities, Clock.fixed(NOW, ZoneOffset.UTC));
    private final CredentialService desktop = mock(CredentialService.class);
    private final WorkspaceHostingService service = new WorkspaceHostingService(workspaces, identities, connections, cache, desktop);

    @BeforeEach
    void setUp()
    {
        workspaces.createOrGet(workspace());
        when(snapshots.find(anyLong(), anyLong(), anyInt())).thenReturn(Optional.empty());
        when(snapshots.save(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            FetchedPullRequest summary = call.getArgument(3);
            return new HostingPullRequestSnapshot(new HostingPullRequest(50, call.getArgument(1), summary.number(), summary.githubId().toString()),
                    call.getArgument(0), call.getArgument(2), summary, call.getArgument(4), call.getArgument(5), call.getArgument(6));
        });
        when(identities.findAccount(20)).thenReturn(Optional.of(new HostingAccount(20, 10, "42", "alice")));
        when(identities.findInstance(10)).thenReturn(Optional.of(INSTANCE));
        when(credentials.find(20)).thenAnswer(ignored -> Optional.of(new HostingCredential(20, "secret")));
        when(repositories.findById(any(), any())).thenAnswer(call -> remote(call.getArgument(1)));
        when(identities.upsertRepository(eq(10L), any())).thenAnswer(call -> retained(call.getArgument(1)));
        when(identities.findRepository(30)).thenReturn(Optional.of(retained(remote("100"))));
        when(identities.findRepository(31)).thenReturn(Optional.of(retained(remote("200"))));
        when(requests.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched());
    }

    @Test
    void bindsByOriginalRepositoryIdsAndRetainsWorkspaceIdentity()
    {
        Workspace before = workspaces.findById("workspace").orElseThrow();
        WorkspaceHostingBinding binding = service.bind("workspace", 20);

        assertThat(binding).isEqualTo(new WorkspaceHostingBinding("workspace", 20, 30, Optional.of(31L)));
        assertThat(binding.monitoredRepositoryId()).isEqualTo(31);
        assertThat(workspaces.findById("workspace")).contains(before);
        assertThat(service.findBinding("workspace")).contains(binding);
        verify(repositories).findById(any(), eq("100"));
        verify(repositories).findById(any(), eq("200"));
    }

    @Test
    void routesCachedRefreshToTheExplicitAccountAndMonitoredRepository()
    {
        service.bind("workspace", 20);
        HostingPullRequestSnapshot snapshot = service.readPullRequest("workspace", 7);
        assertThat(snapshot.accountId()).isEqualTo(20);
        assertThat(snapshot.request().repositoryId()).isEqualTo(31);
        assertThat(snapshot.pullRequest()).isEqualTo(fetched().pullRequest().orElseThrow());
        verify(requests).fetch(any(), eq(retained(remote("200"))), eq(7), eq(Optional.empty()));
        verifyNoInteractions(desktop);
    }

    @Test
    void boundWorkspaceRefreshesRepositoryCoordinatesAfterARename()
    {
        service.bind("workspace", 20);
        String renamed = "moved/project";
        doReturn(new RemoteHostingRepository("200", renamed,
                URI.create("https://github.com/" + renamed), URI.create("https://github.com/" + renamed + ".git"), Optional.empty(), Optional.of("main")))
                .when(repositories).findById(any(), eq("200"));

        WorkspaceHostingRead route = service.captureRead("workspace");

        assertThat(route.repository().id()).isEqualTo(31);
        assertThat(route.githubRepository()).isEqualTo(RepoRef.of("moved", "project"));
        assertThat(route.githubToken()).isEqualTo("secret");
        verifyNoInteractions(desktop);
    }

    @Test
    void unboundWorkspaceUsesItsCapturedCredentialAndStableRepositoryId()
    {
        when(desktop.captureWorkspaceHostingRead("workspace"))
                .thenReturn(new HostingReadSession(20, "workspace-override", new HostingAccess(INSTANCE, "workspace-token")));

        WorkspaceHostingRead route = service.captureRead("workspace");
        HostingPullRequestSnapshot snapshot = service.readPullRequest(route, 7);

        assertThat(route.githubToken()).isEqualTo("workspace-token");
        assertThat(route.githubRepository()).isEqualTo(RepoRef.of("renamed", "project"));
        assertThat(route.login()).isEqualTo("alice");
        assertThat(snapshot.credentialScope()).isEqualTo("workspace-override");
        verify(repositories).findById(any(), eq("200"));
        verifyNoInteractions(credentials);
    }

    @Test
    void nativeConsumersRejectOtherServers()
    {
        HostingInstance other = new HostingInstance(90, GITLAB, URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4"));
        HostingReadSession session = new HostingReadSession(20, "scope", new HostingAccess(other, "secret"));
        HostingRepository repository = new HostingRepository(30, 90, "42", "group/project", URI.create("https://gitlab.com/group/project"),
                URI.create("https://gitlab.com/group/project.git"), Optional.empty(), Optional.of("main"));
        WorkspaceHostingRead route = new WorkspaceHostingRead(session, repository, "alice");

        assertThatThrownBy(route::githubToken).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(route::githubRepository).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnotherProviderBeforeResolvingLegacyNumericIds()
    {
        when(identities.findInstance(10)).thenReturn(Optional.of(new HostingInstance(10, GITLAB, URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4"))));
        assertThatThrownBy(() -> service.bind("workspace", 20)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repositories);
    }

    @Test
    void rejectsAnotherGitHubServerBeforeResolvingLegacyNumericIds()
    {
        when(identities.findInstance(10)).thenReturn(Optional.of(new HostingInstance(10, GITHUB, URI.create("https://company.test"), URI.create("https://company.test/api/v3"))));
        assertThatThrownBy(() -> service.bind("workspace", 20)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repositories);
    }

    @Test
    void disconnectedAccountKeepsBindingButCannotRead()
    {
        WorkspaceHostingBinding binding = service.bind("workspace", 20);
        when(credentials.find(20)).thenReturn(Optional.empty());
        assertThat(service.findBinding("workspace")).contains(binding);
        assertThatThrownBy(() -> service.readPullRequest("workspace", 7))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verifyNoInteractions(requests, desktop);
    }

    private static Workspace workspace()
    {
        return new Workspace("workspace", "Project", 100, RepoRef.of("old-owner", "fork"), Optional.of(200L), Optional.of(RepoRef.of("old-owner", "project")),
                200, RepoRef.of("old-owner", "project"), Workspace.Topology.FORK, "main", "https://github.com/old-owner/fork.git", Optional.of("main"),
                Optional.of("https://github.com/old-owner/project.git"), "/tmp/retained-workspace", Optional.empty(), Optional.empty(), 0, NOW, NOW);
    }

    private static RemoteHostingRepository remote(String id)
    {
        String path = id.equals("100") ? "renamed/fork" : "renamed/project";
        return new RemoteHostingRepository(id, path, URI.create("https://github.com/" + path), URI.create("https://github.com/" + path + ".git"),
                Optional.empty(), Optional.of("main"));
    }

    private static HostingRepository retained(RemoteHostingRepository remote)
    {
        return new HostingRepository(remote.remoteId().equals("100") ? 30 : 31, 10, remote.remoteId(), remote.fullPath(), remote.webUrl(), remote.httpCloneUrl(),
                remote.sshCloneUrl(), remote.defaultBranch());
    }

    private static PullRequestFetch fetched()
    {
        String url = "https://github.com/renamed/project/pull/7";
        FetchedPullRequest summary = new FetchedPullRequest(99L, "renamed/project", 7, "Change", "alice", url, NOW, NOW, OTHER, ImmutableList.of(), ImmutableMap.of(),
                false, "open", null, null, ImmutableList.of(), null, null, "topic");
        PrRawDetail detail = new PrRawDetail("Change", url, "open", "alice", null, NOW, NOW, null, "Description", ImmutableList.of(), ImmutableMap.of(),
                ImmutableList.of(), null, false, null, null, 0, 0, 0, 0, ImmutableList.of(), "a".repeat(40), "topic", "main", "b".repeat(40));
        return new PullRequestFetch(Optional.of(summary), Optional.of("etag"), Optional.of(detail));
    }
}
