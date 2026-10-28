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
package com.bytequay.app.service.credentials;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.HostingPullRequestStore;
import com.bytequay.core.store.WorkspaceCredentialStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCredentialHostingRead
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private final CredentialStore globalStore = mock(CredentialStore.class);
    private final GitHubAccountClient profiles = mock(GitHubAccountClient.class);
    private final WorkspaceCredentialStore workspaceStore = mock(WorkspaceCredentialStore.class);
    private final GhCliService ghCli = mock(GhCliService.class);
    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final HostingCredentialStore manualCredentials = mock(HostingCredentialStore.class);
    private final HostingAccounts accounts = mock(HostingAccounts.class);
    private final HostingRepositories repositories = mock(HostingRepositories.class);
    private final PullRequestReader reader = mock(PullRequestReader.class);
    private final HostingPullRequestStore snapshots = mock(HostingPullRequestStore.class);
    private final AtomicReference<String> token = new AtomicReference<>("desktop-token");
    private final AtomicReference<String> revision = new AtomicReference<>("revision-1");
    private final AtomicReference<HostingPullRequestSnapshot> cached = new AtomicReference<>();
    private final HostingInstance instance = new HostingInstance(10, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
    private final HostingAccount account = new HostingAccount(20, 10, "123", "alice");
    private final HostingRepository repository = new HostingRepository(30, 10, "42", "owner/repo", URI.create("https://github.com/owner/repo"),
            URI.create("https://github.com/owner/repo.git"), Optional.empty(), Optional.of("main"));
    private CredentialService credentials;
    private HostingPullRequestService requests;

    @BeforeEach
    void setUp()
    {
        when(globalStore.getSecret()).thenAnswer(ignored -> Optional.ofNullable(token.get()));
        when(globalStore.revision()).thenAnswer(ignored -> revision.get());
        when(workspaceStore.revision(anyString())).thenReturn("workspace-revision");
        doAnswer(call -> {
            token.set(call.getArgument(0));
            revision.set(UUID.randomUUID().toString());
            return null;
        }).when(globalStore).save(anyString(), any());
        when(profiles.fetchUserProfile(anyString())).thenReturn(new UserProfile("alice", "Alice", "", "", 0, 0, 0, "", "", "", ""));
        when(accounts.verify(any())).thenReturn(new RemoteHostingAccount("123", "alice"));
        when(identities.upsertInstance(any())).thenReturn(instance);
        when(identities.upsertAccount(eq(10L), any())).thenReturn(account);
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        when(identities.findRepositories(any(), eq("owner/repo"))).thenReturn(ImmutableList.of(repository));
        when(snapshots.find(20, 30, 7)).thenAnswer(ignored -> Optional.ofNullable(cached.get()));
        when(snapshots.save(eq(20L), eq(30L), anyString(), anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
            FetchedPullRequest summary = call.getArgument(4);
            HostingPullRequestSnapshot value = new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, summary.githubId().toString()),
                    20, call.getArgument(2), call.getArgument(3), summary, call.getArgument(5), call.getArgument(6), call.getArgument(7));
            cached.set(value);
            return value;
        });
        doAnswer(call -> {
            cached.updateAndGet(value -> value != null && value.credentialScope().equals(call.<String>getArgument(3)) ? null : value);
            return null;
        }).when(snapshots).evict(eq(20L), eq(30L), eq(7), anyString());
        doAnswer(ignored -> {
            cached.set(null);
            return null;
        }).when(snapshots).evictRepository(30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenReturn(fetched());
        HostingConnectionService connections = new HostingConnectionService(identities, manualCredentials,
                ImmutableList.of(new HostingIntegration(GITHUB, accounts, repositories, reader)));
        credentials = new CredentialService(globalStore, workspaceStore, ghCli, profiles, connections);
        requests = new HostingPullRequestService(connections, snapshots, identities, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cachedSessionNeverStartsIdentityVerificationOrCliWork()
    {
        assertThat(credentials.cachedHostingRead()).isEmpty();
        verifyNoInteractions(globalStore, accounts, ghCli);
        when(globalStore.source()).thenReturn(Optional.of(CredentialService.GITHUB_CLI_SOURCE));
        when(ghCli.currentToken()).thenReturn(Optional.of("desktop-token"));
        HostingReadSession selected = credentials.captureHostingRead();
        clearInvocations(ghCli);

        assertThat(credentials.cachedHostingRead()).containsSame(selected);
        verify(accounts).verify(any());
        verifyNoInteractions(ghCli);
    }

    @Test
    void cachedSessionRejectsAnExternallyChangedCredential()
    {
        credentials.captureHostingRead();
        revision.set("replacement-revision");

        assertThatThrownBy(credentials::cachedHostingRead)
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
    }

    @Test
    void reusesVerifiedIdentityAndRoutesTheSelectedTokenThroughSharedCache()
    {
        when(accounts.verify(any())).thenAnswer(call -> {
            assertThat(Thread.holdsLock(credentials)).isFalse();
            assertThat(call.<HostingAccess>getArgument(0).token()).isEqualTo("desktop-token");
            return new RemoteHostingAccount("123", "alice");
        });
        HostingReadSession session = credentials.captureHostingRead();
        HostingPullRequestSnapshot first = requests.refresh(session, 30, 7, false);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.of("v1")))).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.token()).isEqualTo("desktop-token");
            return new PullRequestFetch(Optional.empty(), Optional.of("v1"));
        });

        assertThat(credentials.captureHostingRead()).isSameAs(session);
        assertThat(requests.refresh(session, 30, 7, false)).isEqualTo(first);
        assertThat(requests.cached(session, 30, 7)).contains(first);
        assertThat(first.credentialScope()).isEqualTo("github-global:revision-1");
        verify(accounts).verify(any());
        verifyNoInteractions(repositories);
        verifyNoInteractions(manualCredentials);
    }

    @Test
    void manualAccountSnapshotCannotSupplyDesktopMetadataOrValidator()
    {
        PullRequestFetch fetched = fetched();
        cached.set(new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, "99"), 20, CONNECTED_ACCOUNT, 9,
                fetched.pullRequest().orElseThrow(), fetched.detail().orElseThrow(), Optional.of("manual-validator"), NOW));

        HostingPullRequestSnapshot result = requests.refresh(credentials.captureHostingRead(), 30, 7, false);

        assertThat(result.credentialScope()).isEqualTo("github-global:revision-1");
        verify(reader).fetch(any(), any(), eq(7), eq(Optional.empty()));
        verifyNoInteractions(manualCredentials);
    }

    @Test
    void replacementRejectsAnInflightResultBeforeItReachesTheCache()
    {
        when(reader.fetch(any(), any(), anyInt(), any())).thenAnswer(ignored -> {
            credentials.saveGitHubToken("replacement");
            return fetched();
        });

        assertThatThrownBy(() -> requests.refresh(credentials.captureHostingRead(), 30, 7, false))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        assertThat(cached).hasNullValue();
        verify(snapshots, never()).save(anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void replacementDuringIdentityVerificationDoesNotRetainTheOldSelection()
    {
        when(accounts.verify(any())).thenAnswer(ignored -> {
            credentials.saveGitHubToken("replacement");
            return new RemoteHostingAccount("123", "alice");
        });

        assertThatThrownBy(() -> requests.refresh(credentials.captureHostingRead(), 30, 7, false))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(identities, never()).upsertAccount(anyLong(), any());
        verifyNoInteractions(reader);
    }

    @Test
    void replacingTheSameTokenChangesScopeAndReverifiesIdentity()
    {
        HostingReadSession previous = credentials.captureHostingRead();
        requests.refresh(credentials.captureHostingRead(), 30, 7, false);
        credentials.saveGitHubToken("desktop-token");
        assertThat(credentials.cachedHostingRead()).isEmpty();
        assertThatThrownBy(() -> previous.withCurrent(() -> "stale"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));

        HostingReadSession current = credentials.captureHostingRead();
        assertThat(current.cacheScope()).isNotEqualTo(previous.cacheScope());
        assertThat(requests.cached(current, 30, 7)).isEmpty();
        requests.refresh(credentials.captureHostingRead(), 30, 7, false);
        verify(accounts, times(2)).verify(any());
        verify(reader, times(2)).fetch(any(), any(), eq(7), eq(Optional.empty()));
    }

    @Test
    void invalidationAfterAWriteNeedsNoCredentialOrRemoteResolution()
    {
        PullRequestFetch fetched = fetched();
        cached.set(new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, "99"), 20, CONNECTED_ACCOUNT, 9,
                fetched.pullRequest().orElseThrow(), fetched.detail().orElseThrow(), Optional.of("manual-validator"), NOW));
        token.set(null);

        requests.invalidate(CredentialService.GITHUB_SERVER, "owner/repo", 7);

        assertThat(cached).hasNullValue();
        verify(snapshots).evictRepository(30, 7);
        verifyNoInteractions(globalStore, accounts, repositories, reader, manualCredentials);
    }

    @Test
    void workspaceOverrideUsesItsOwnVerifiedIdentityWithoutChangingTheGlobalSelection()
    {
        HostingReadSession global = credentials.captureHostingRead();
        when(workspaceStore.getSecret("workspace")).thenReturn(Optional.of("workspace-token"));
        when(accounts.verify(any())).thenAnswer(call -> {
            assertThat(call.<HostingAccess>getArgument(0).token()).isEqualTo("workspace-token");
            return new RemoteHostingAccount("123", "alice");
        });

        HostingReadSession session = credentials.captureWorkspaceHostingRead("workspace", current());

        assertThat(session.access().token()).isEqualTo("workspace-token");
        assertThat(session.credentialScope()).isEqualTo("github-workspace:workspace:workspace-revision:workspace-revision");
        assertThat(global.withCurrent(() -> "global selection retained")).isEqualTo("global selection retained");
        verifyNoInteractions(manualCredentials);
    }

    @Test
    void workspaceFallbackCannotSurviveAChangedOverride()
    {
        HostingReadSession session = credentials.captureWorkspaceHostingRead("workspace", current());
        assertThat(session.access().token()).isEqualTo("desktop-token");
        assertThat(session.credentialScope()).isEqualTo("github-workspace:workspace:workspace-revision:revision-1");
        when(workspaceStore.getSecret("workspace")).thenReturn(Optional.of("override"));
        when(workspaceStore.revision("workspace")).thenReturn("new-revision");

        assertThatThrownBy(session.access()::token).isInstanceOf(HostingFailure.class);
        assertThatThrownBy(() -> session.withCurrent(() -> "late result")).isInstanceOf(HostingFailure.class);
    }

    @Test
    void workspaceRebindingDuringVerificationCannotRetainTheOldAccount()
    {
        AtomicBoolean active = new AtomicBoolean(true);
        HostingReadSession.Guard routing = new HostingReadSession.Guard()
        {
            @Override
            public <T> T withCurrent(Supplier<T> action)
            {
                if (!active.get()) {
                    throw new HostingFailure(REVISION_CONFLICT, "Workspace rebound");
                }
                return action.get();
            }
        };
        when(accounts.verify(any())).thenAnswer(call -> {
            assertThat(call.<HostingAccess>getArgument(0).token()).isEqualTo("desktop-token");
            active.set(false);
            return new RemoteHostingAccount("123", "alice");
        });

        assertThatThrownBy(() -> credentials.captureWorkspaceHostingRead("workspace", routing)).isInstanceOf(HostingFailure.class);
        verify(identities, never()).upsertAccount(anyLong(), any());
    }

    private static HostingReadSession.Guard current()
    {
        return new HostingReadSession.Guard()
        {
            @Override
            public <T> T withCurrent(Supplier<T> action)
            {
                return action.get();
            }
        };
    }

    private static PullRequestFetch fetched()
    {
        String url = "https://github.com/owner/repo/pull/7";
        FetchedPullRequest summary = new FetchedPullRequest(99L, "owner/repo", 7, "Title", "alice", url, NOW, NOW, OTHER, ImmutableList.of(), ImmutableMap.of(),
                false, "open", null, null, ImmutableList.of(), null, null, "topic");
        PrRawDetail detail = new PrRawDetail("Title", url, "open", "alice", null, NOW, NOW, null, "Body", ImmutableList.of(), ImmutableMap.of(),
                ImmutableList.of(), null, false, null, null, UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT, ImmutableList.of(),
                "a".repeat(40), "topic", "main", "b".repeat(40));
        return new PullRequestFetch(Optional.of(summary), Optional.of("v1"), Optional.of(detail));
    }
}
