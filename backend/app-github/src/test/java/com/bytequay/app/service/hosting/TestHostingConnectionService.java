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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestHostingConnectionService
{
    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final HostingCredentialStore credentials = mock(HostingCredentialStore.class);
    private final HostingAccounts accounts = mock(HostingAccounts.class);
    private final HostingRepositories repositories = mock(HostingRepositories.class);
    private final PullRequestReader requests = mock(PullRequestReader.class);
    private final HostingInstance instance = new HostingInstance(10, GITLAB, URI.create("https://git.company.test/code"), URI.create("https://git.company.test/code/api/v4"));
    private final HostingAccount account = new HostingAccount(20, 10, "42", "alice");
    private final HostingConnectionService service = new HostingConnectionService(identities, credentials,
            ImmutableList.of(new HostingIntegration(GITLAB, accounts, repositories, requests)));

    @BeforeEach
    void setUp()
    {
        when(identities.listInstances()).thenReturn(ImmutableList.of(instance));
        when(identities.listAccounts(10)).thenReturn(ImmutableList.of(account));
        when(identities.findAccount(20)).thenReturn(Optional.of(account));
        when(identities.findInstance(10)).thenReturn(Optional.of(instance));
        when(credentials.find(20)).thenReturn(Optional.of(new HostingCredential(20, "first-secret")));
    }

    @Test
    void verifiesRemoteSubjectBeforeStoringCredential()
    {
        when(identities.upsertInstance(new HostingServer(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl()))).thenReturn(instance);
        when(accounts.verify(any())).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.token()).isEqualTo("new-secret");
            assertThat(access.toString()).doesNotContain("new-secret");
            return new RemoteHostingAccount("different-user-id", "bob");
        });
        HostingAccount other = new HostingAccount(21, 10, "different-user-id", "bob");
        when(identities.upsertAccount(10, new RemoteHostingAccount("different-user-id", "bob"))).thenReturn(other);
        HostingConnection result = service.connect(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl(), "new-secret");
        assertThat(result.account()).isEqualTo(other);
        assertThat(result.connected()).isTrue();
        verify(credentials).save(21, "new-secret");
        verify(credentials, never()).save(eq(20L), anyString());
    }

    @Test
    void failedVerificationNeverStoresCredential()
    {
        when(identities.upsertInstance(new HostingServer(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl()))).thenReturn(instance);
        when(accounts.verify(any())).thenThrow(new HostingFailure(AUTHENTICATION_REQUIRED, "Authentication required"));
        assertThatThrownBy(() -> service.connect(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl(), "invalid"))
                .isInstanceOf(HostingFailure.class);
        verify(credentials, never()).save(anyLong(), anyString());
        verify(identities, never()).upsertInstance(any());
        verify(identities, never()).upsertAccount(anyLong(), any());
    }

    @Test
    void verifiesAnExternalSessionWithItsConfiguredServerWithoutReplacingTheConnectedToken()
    {
        HostingServer server = new HostingServer(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl());
        when(accounts.verify(any())).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.server()).isEqualTo(server);
            assertThat(access.token()).isEqualTo("external-token");
            return new RemoteHostingAccount("42", "alice");
        });
        when(identities.upsertInstance(server)).thenReturn(instance);
        when(identities.upsertAccount(10, new RemoteHostingAccount("42", "alice"))).thenReturn(account);

        HostingReadSession session = service.verifySession(server, "external", "external-token");

        assertThat(service.account(session)).isEqualTo(account);
        assertThat(session.access().server()).isEqualTo(server);
        assertThat(session.access().token()).isEqualTo("external-token");
        assertThat(service.readSession(20).access().token()).isEqualTo("first-secret");
        verify(credentials, never()).save(anyLong(), anyString());
    }

    @Test
    void retainedRepositoryLookupIsLocalAndRejectsAmbiguousPaths()
    {
        HostingReadSession session = service.readSession(20);
        HostingRepository repository = storedRepository();
        HostingRepository previous = new HostingRepository(31, repository.instanceId(), "old-id", repository.fullPath(), repository.webUrl(),
                repository.httpCloneUrl(), repository.sshCloneUrl(), repository.defaultBranch());
        when(identities.findRepositories(session.access().server(), repository.fullPath()))
                .thenReturn(ImmutableList.of(repository), ImmutableList.of(repository, previous), ImmutableList.of());

        assertThat(service.findRepository(session, repository.fullPath())).contains(repository);
        assertThat(service.findRepository(session, repository.fullPath())).isEmpty();
        assertThat(service.findRepository(session, repository.fullPath())).isEmpty();
        verifyNoInteractions(accounts, repositories, requests);
    }

    @Test
    void retainedRepositoryLookupRejectsAnotherInstance()
    {
        HostingReadSession session = service.readSession(20);
        HostingRepository other = new HostingRepository(31, 11, "99", "team/project", URI.create("https://another.test/team/project"),
                URI.create("https://another.test/team/project.git"), Optional.empty(), Optional.empty());
        when(identities.findRepositories(session.access().server(), other.fullPath())).thenReturn(ImmutableList.of(other));

        assertThatThrownBy(() -> service.findRepository(session, other.fullPath())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accounts, repositories, requests);
    }

    @Test
    void repositoryIdLookupRetainsTheStoredIdentityAfterARename()
    {
        RemoteHostingRepository renamed = new RemoteHostingRepository("99", "new/group/project", URI.create("https://git.company.test/code/new/group/project"),
                URI.create("https://git.company.test/code/new/group/project.git"), Optional.empty(), Optional.empty());
        HostingRepository retained = new HostingRepository(30, 10, renamed.remoteId(), renamed.fullPath(), renamed.webUrl(), renamed.httpCloneUrl(),
                renamed.sshCloneUrl(), renamed.defaultBranch());
        when(repositories.findById(any(), eq("99"))).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.instanceId()).hasValue(10);
            assertThat(access.token()).isEqualTo("first-secret");
            return renamed;
        });
        when(identities.upsertRepository(10, renamed)).thenReturn(retained);

        assertThat(service.resolveRepositoryById(20, "99")).isEqualTo(retained);
        verify(repositories, never()).findByPath(any(), anyString());
        verify(identities).upsertRepository(10, renamed);
    }

    @Test
    void doesNotRouteAcrossHostingInstances()
    {
        HostingRepository other = new HostingRepository(31, 11, "99", "owner/repo", URI.create("https://github.com/owner/repo"),
                URI.create("https://github.com/owner/repo.git"), Optional.empty(), Optional.of("main"));
        when(identities.findRepository(31)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.readPullRequest(service.readSession(20), 31, 1, Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(requests);
    }

    @Test
    void routesReadWithSelectedAccountAndWithoutSharedValidator()
    {
        HostingRepository repository = storedRepository();
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        when(requests.fetch(any(), eq(repository), eq(7), eq(Optional.empty()))).thenReturn(metadata());
        service.readPullRequest(service.readSession(20), 30, 7, Optional.empty(), Optional.empty());
        verify(requests).fetch(any(HostingAccess.class), eq(repository), eq(7), eq(Optional.empty()));
    }

    @Test
    void forwardsValidatorForTheSelectedAccountAndRepository()
    {
        HostingRepository repository = storedRepository();
        PullRequestFetch unchanged = new PullRequestFetch(Optional.empty(), Optional.of("\"v1\""));
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        when(requests.fetch(any(), eq(repository), eq(7), eq(unchanged.etag()))).thenAnswer(call -> {
            HostingAccess access = call.getArgument(0);
            assertThat(access.token()).isEqualTo("first-secret");
            return unchanged;
        });

        assertThat(service.readPullRequest(service.readSession(20), 30, 7, Optional.of(repository.fullPath()), unchanged.etag())).isSameAs(unchanged);
    }

    @Test
    void repositoryRenameDropsThePreviousPathValidator()
    {
        HostingRepository repository = storedRepository();
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        PullRequestFetch result = metadata();
        when(requests.fetch(any(), eq(repository), eq(7), eq(Optional.empty()))).thenReturn(result);

        assertThat(service.readPullRequest(service.readSession(20), 30, 7, Optional.of("old/group/project"), Optional.of("\"v1\""))).isSameAs(result);
        verify(requests).fetch(any(), eq(repository), eq(7), eq(Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnchangedResultsWithoutAnEffectiveValidator(boolean renamed)
    {
        HostingRepository repository = storedRepository();
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        when(requests.fetch(any(), eq(repository), eq(7), eq(Optional.empty())))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.of("\"v1\"")));

        assertThatThrownBy(() -> {
            if (renamed) {
                service.readPullRequest(service.readSession(20), 30, 7, Optional.of("old/group/project"), Optional.of("\"v1\""));
            }
            else {
                service.readPullRequest(service.readSession(20), 30, 7, Optional.empty(), Optional.empty());
            }
        }).isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
    }

    @Test
    void unsupportedProviderDoesNotCreateConnection()
    {
        assertThatThrownBy(() -> service.connect(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"), "token"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(identities, never()).upsertInstance(any());
    }

    @Test
    void coldCacheReadHasNoValidatorWithoutAnObservedPath()
    {
        HostingRepository repository = storedRepository();
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        PullRequestFetch result = metadata();
        when(requests.fetch(any(), eq(repository), eq(7), eq(Optional.empty()))).thenReturn(result);

        assertThat(service.readPullRequest(service.readSession(20), 30, 7, Optional.empty(), Optional.of("\"unscoped\""))).isSameAs(result);
        verify(requests).fetch(any(), eq(repository), eq(7), eq(Optional.empty()));
    }

    private static RemoteHostingRepository remoteRepository()
    {
        return new RemoteHostingRepository("99", "team/subgroup/project", URI.create("https://git.company.test/code/team/subgroup/project"),
                URI.create("https://git.company.test/code/team/subgroup/project.git"), Optional.empty(), Optional.empty());
    }

    private static HostingRepository storedRepository()
    {
        RemoteHostingRepository remote = remoteRepository();
        return new HostingRepository(30, 10, remote.remoteId(), remote.fullPath(), remote.webUrl(), remote.httpCloneUrl(), remote.sshCloneUrl(), remote.defaultBranch());
    }

    private static PullRequestFetch metadata()
    {
        String url = "https://git.company.test/code/team/subgroup/project/-/merge_requests/7";
        Instant updatedAt = Instant.parse("2026-10-01T10:00:00Z");
        FetchedPullRequest summary = new FetchedPullRequest(1007L, "team/subgroup/project", 7, "Title", "alice", url, updatedAt, updatedAt,
                OTHER, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, null);
        PrRawDetail detail = new PrRawDetail("Title", url, "open", "alice", null, updatedAt, updatedAt, null, "Body", ImmutableList.of(), ImmutableMap.of(),
                ImmutableList.of(), null, false, null, null, UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT, ImmutableList.of(), null, null, null, null);
        return new PullRequestFetch(Optional.of(summary), Optional.empty(), Optional.of(detail));
    }
}
