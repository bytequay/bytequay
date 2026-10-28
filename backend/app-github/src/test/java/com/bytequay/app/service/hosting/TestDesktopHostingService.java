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
package com.bytequay.app.service.hosting;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestRef;
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
import com.bytequay.core.hosting.RemoteHostingRepository;
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
import java.util.concurrent.atomic.AtomicReference;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDesktopHostingService
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);
    private final CredentialStore globalStore = mock(CredentialStore.class);
    private final GitHubAccountClient profiles = mock(GitHubAccountClient.class);
    private final CredentialService credentials = new CredentialService(globalStore, mock(WorkspaceCredentialStore.class), mock(GhCliService.class), profiles);
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
    private DesktopHostingService desktop;

    @BeforeEach
    void setUp()
    {
        when(globalStore.getSecret()).thenAnswer(ignored -> Optional.ofNullable(token.get()));
        when(globalStore.revision()).thenAnswer(ignored -> revision.get());
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
        RemoteHostingRepository remote = new RemoteHostingRepository(repository.remoteId(), repository.fullPath(), repository.webUrl(), repository.httpCloneUrl(),
                repository.sshCloneUrl(), repository.defaultBranch());
        when(repositories.findByPath(any(), eq("owner/repo"))).thenReturn(remote);
        when(identities.upsertRepository(10, remote)).thenReturn(repository);
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
        desktop = new DesktopHostingService(credentials, identities, connections,
                new HostingPullRequestService(connections, snapshots, Clock.fixed(NOW, ZoneOffset.UTC)),
                ImmutableList.of(new HostingIntegration(GITHUB, accounts, repositories, reader)));
    }

    @Test
    void scopeAndColdCacheDoNotResolveRemoteIdentity()
    {
        assertThat(desktop.scope("desktop-token")).isEqualTo("github-global:revision-1");
        assertThat(desktop.cached("desktop-token", REF)).isEmpty();
        verifyNoInteractions(accounts, repositories, reader, manualCredentials);
    }

    @Test
    void reusesVerifiedIdentityAndRoutesTheSelectedTokenThroughSharedCache()
    {
        HostingPullRequestSnapshot first = desktop.refresh("desktop-token", REF, false);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.of("v1")))).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.token()).isEqualTo("desktop-token");
            return new PullRequestFetch(Optional.empty(), Optional.of("v1"));
        });

        assertThat(desktop.refresh("desktop-token", REF, false)).isEqualTo(first);
        assertThat(desktop.cached("desktop-token", REF)).contains(first);
        assertThat(first.credentialScope()).isEqualTo("github-global:revision-1");
        verify(accounts).verify(any());
        verify(repositories).findByPath(any(), eq("owner/repo"));
        verifyNoInteractions(manualCredentials);
    }

    @Test
    void manualAccountSnapshotCannotSupplyDesktopMetadataOrValidator()
    {
        PullRequestFetch fetched = fetched();
        cached.set(new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, "99"), 20, CONNECTED_ACCOUNT, 9,
                fetched.pullRequest().orElseThrow(), fetched.detail().orElseThrow(), Optional.of("manual-validator"), NOW));

        HostingPullRequestSnapshot result = desktop.refresh("desktop-token", REF, false);

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

        assertThatThrownBy(() -> desktop.refresh("desktop-token", REF, false))
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

        assertThatThrownBy(() -> desktop.refresh("desktop-token", REF, false))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(identities, never()).upsertAccount(anyLong(), any());
        verifyNoInteractions(reader);
    }

    @Test
    void replacingTheSameTokenChangesScopeAndReverifiesIdentity()
    {
        String previous = desktop.scope("desktop-token");
        desktop.refresh("desktop-token", REF, false);
        credentials.saveGitHubToken("desktop-token");
        assertThatThrownBy(() -> desktop.withCurrent(previous, () -> "stale"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));

        assertThat(desktop.scope("desktop-token")).isNotEqualTo(previous);
        assertThat(desktop.cached("desktop-token", REF)).isEmpty();
        desktop.refresh("desktop-token", REF, false);
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

        desktop.evict("owner/repo", 7);

        assertThat(cached).hasNullValue();
        verify(snapshots).evictRepository(30, 7);
        verifyNoInteractions(globalStore, accounts, repositories, reader, manualCredentials);
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
