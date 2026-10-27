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
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicReference<Optional<HostingCredential>> credential = new AtomicReference<>(Optional.of(new HostingCredential(20, 1, "first-secret")));
    private final HostingConnectionService service = new HostingConnectionService(identities, credentials,
            ImmutableList.of(new HostingIntegration(GITLAB, accounts, repositories, requests)));

    @BeforeEach
    void setUp()
    {
        when(identities.listInstances()).thenReturn(ImmutableList.of(instance));
        when(identities.listAccounts(10)).thenReturn(ImmutableList.of(account));
        when(credentials.generation(20)).thenAnswer(ignored -> generation.get());
        when(identities.findAccount(20)).thenReturn(Optional.of(account));
        when(identities.findInstance(10)).thenReturn(Optional.of(instance));
        when(credentials.find(20)).thenAnswer(ignored -> credential.get());
        doAnswer(ignored -> {
            credential.set(Optional.empty());
            generation.incrementAndGet();
            return null;
        }).when(credentials).delete(20);
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
    void disconnectCancelsAnEarlierReconnect()
    {
        when(accounts.verify(any())).thenAnswer(ignored -> {
            service.disconnect(20);
            return new RemoteHostingAccount("42", "alice");
        });
        assertThatThrownBy(() -> service.connect(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl(), "reconnect-secret"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(credentials, never()).save(anyLong(), anyString());
        verify(identities, never()).upsertAccount(anyLong(), any());
    }

    @Test
    void reconnectCannotOverwriteALaterCredentialReplacement()
    {
        when(accounts.verify(any())).thenAnswer(ignored -> {
            generation.incrementAndGet();
            credential.set(Optional.of(new HostingCredential(20, 2, "newer-secret")));
            return new RemoteHostingAccount("42", "alice");
        });
        assertThatThrownBy(() -> service.connect(GITLAB, instance.webBaseUrl(), instance.apiBaseUrl(), "older-secret"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(credentials, never()).save(anyLong(), anyString());
    }

    @Test
    void replacementInvalidatesFetchBeforeIdentityIsSaved()
    {
        when(repositories.findByPath(any(), eq("team/subgroup/project"))).thenAnswer(ignored -> {
            credential.set(Optional.of(new HostingCredential(20, 2, "replacement")));
            return remoteRepository();
        });
        assertThatThrownBy(() -> service.resolveRepository(20, "team/subgroup/project"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        verify(identities, never()).upsertRepository(anyLong(), any());
    }

    @Test
    void disconnectInvalidatesFetchAndPreservesRetainedIdentity()
    {
        when(repositories.findByPath(any(), anyString())).thenAnswer(ignored -> {
            service.disconnect(20);
            return remoteRepository();
        });
        assertThatThrownBy(() -> service.resolveRepository(20, "team/subgroup/project"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verify(credentials).delete(20);
        verify(identities, never()).upsertRepository(anyLong(), any());
    }

    @Test
    void doesNotRouteAcrossHostingInstances()
    {
        HostingRepository other = new HostingRepository(31, 11, "99", "owner/repo", URI.create("https://github.com/owner/repo"),
                URI.create("https://github.com/owner/repo.git"), Optional.empty(), Optional.of("main"));
        when(identities.findRepository(31)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.readPullRequest(20, 31, 1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(requests);
    }

    @Test
    void routesReadWithSelectedAccountAndWithoutSharedValidator()
    {
        RemoteHostingRepository remote = remoteRepository();
        HostingRepository repository = new HostingRepository(30, 10, remote.remoteId(), remote.fullPath(), remote.webUrl(), remote.httpCloneUrl(),
                remote.sshCloneUrl(), remote.defaultBranch());
        when(identities.findRepository(30)).thenReturn(Optional.of(repository));
        service.readPullRequest(20, 30, 7);
        verify(requests).fetch(any(HostingAccess.class), eq(repository), eq(7), eq(Optional.empty()));
    }

    @Test
    void unsupportedProviderDoesNotCreateConnection()
    {
        assertThatThrownBy(() -> service.connect(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"), "token"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(identities, never()).upsertInstance(any());
    }

    private static RemoteHostingRepository remoteRepository()
    {
        return new RemoteHostingRepository("99", "team/subgroup/project", URI.create("https://git.company.test/code/team/subgroup/project"),
                URI.create("https://git.company.test/code/team/subgroup/project.git"), Optional.empty(), Optional.empty());
    }
}
