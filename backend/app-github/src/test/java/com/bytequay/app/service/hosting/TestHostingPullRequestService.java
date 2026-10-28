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
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingCredential.Source;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.HostingPullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestHostingPullRequestService
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final HostingIdentityStore identities = mock(HostingIdentityStore.class);
    private final HostingCredentialStore credentials = mock(HostingCredentialStore.class);
    private final HostingPullRequestStore snapshots = mock(HostingPullRequestStore.class);
    private final PullRequestReader reader = mock(PullRequestReader.class);
    private final Map<Long, HostingCredential> tokens = new HashMap<>();
    private final Map<Long, HostingPullRequestSnapshot> cache = new HashMap<>();
    private HostingPullRequestService service;
    private HostingConnectionService connections;

    @BeforeEach
    void setUp()
    {
        tokens.put(20L, new HostingCredential(20, "first", Source.TOKEN));
        tokens.put(21L, new HostingCredential(21, "second", Source.TOKEN));
        when(credentials.find(anyLong())).thenAnswer(call -> Optional.ofNullable(tokens.get(call.<Long>getArgument(0))));
        when(identities.findAccount(anyLong())).thenAnswer(call -> Optional.of(new HostingAccount(call.getArgument(0), 10, "42", "alice")));
        when(identities.findRepository(30)).thenReturn(Optional.of(new HostingRepository(30, 10, "100", "team/project", URI.create("https://hosting.test/team/project"),
                URI.create("https://hosting.test/team/project.git"), Optional.empty(), Optional.of("main"))));
        when(snapshots.find(anyLong(), eq(30L), eq(7))).thenAnswer(call -> Optional.ofNullable(cache.get(call.<Long>getArgument(0))));
        when(snapshots.save(anyLong(), eq(30L), anyString(), any(), any(), any(), any())).thenAnswer(call -> {
            FetchedPullRequest summary = call.getArgument(3);
            HostingPullRequestSnapshot snapshot = new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, summary.githubId().toString()),
                    call.getArgument(0), call.getArgument(2), summary, call.getArgument(4), call.getArgument(5), call.getArgument(6));
            cache.put(snapshot.accountId(), snapshot);
            return snapshot;
        });
        doAnswer(call -> {
            cache.computeIfPresent(call.getArgument(0), (id, snapshot) -> snapshot.credentialScope().equals(call.<String>getArgument(3)) ? null : snapshot);
            return null;
        }).when(snapshots).evict(anyLong(), eq(30L), eq(7), anyString());
        doAnswer(ignored -> {
            cache.clear();
            return null;
        }).when(snapshots).evictRepository(30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenReturn(fetched("First", Optional.of("v1")));
        service = service(HostingProvider.GITLAB);
    }

    @ParameterizedTest
    @EnumSource(HostingProvider.class)
    void storesBothProvidersThroughTheSameCache(HostingProvider provider)
    {
        service = service(provider);
        HostingPullRequestSnapshot result = service.refresh(20, 30, 7);
        assertThat(result.request()).isEqualTo(new HostingPullRequest(40, 30, 7, "99"));
        assertThat(result.pullRequest().title()).isEqualTo("First");
        assertThat(result.validatedAt()).isEqualTo(NOW);
        assertThat(service.cached(20, 30, 7)).contains(result);
    }

    @ParameterizedTest
    @EnumSource(HostingProvider.class)
    void listsAndCachesPagesThroughTheSelectedProvider(HostingProvider provider)
    {
        service = service(provider);
        HostingReadSession session = connections.readSession(20);
        HostingRepository repository = identities.findRepository(30).orElseThrow();
        FetchedPullRequest summary = fetched("Listed", Optional.empty()).pullRequest().orElseThrow();
        when(reader.list(session.access(), repository, true, 2, 25)).thenReturn(new PullRequestListPage(ImmutableList.of(summary), true));

        PullRequestListPage result = service.refreshPage(session, 30, true, 2, 25);

        assertThat(result.items()).containsExactly(summary.withViewerOrigin("alice"));
        assertThat(result.hasNext()).isTrue();
        verify(snapshots).savePage(20, 30, CONNECTED_ACCOUNT, result.items());
        verify(reader, never()).fetch(any(), any(), anyInt(), any());
        assertThat(cache).isEmpty();
    }

    @Test
    void readsCachedPagesWithinTheSelectedCredentialScope()
    {
        HostingReadSession session = connections.readSession(20);
        PullRequestListPage page = new PullRequestListPage(ImmutableList.of(), false);
        when(snapshots.findPage(20, 30, CONNECTED_ACCOUNT, 2, 25)).thenReturn(page);
        reset(reader);

        assertThat(service.cachedPage(session, 30, 2, 25)).isEqualTo(page);

        verifyNoInteractions(reader);
    }

    @Test
    void failedListingDoesNotReplaceCachedSummaries()
    {
        HostingReadSession session = connections.readSession(20);
        when(reader.list(any(), any(), eq(false), eq(1), eq(100))).thenThrow(new HostingFailure(UNAVAILABLE, "Offline"));

        assertThatThrownBy(() -> service.refreshPage(session, 30, false, 1, 100)).isInstanceOf(HostingFailure.class);

        verify(snapshots, never()).savePage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void detailRefreshKeepsTheViewersOriginInStoredListSummaries()
    {
        HostingPullRequestSnapshot result = service.refresh(20, 30, 7);

        assertThat(result.pullRequest().origin()).isEqualTo(AUTHORED);
    }

    @Test
    void conditionalRefreshReturnsCompleteMetadataAndKeepsIdentity()
    {
        HostingPullRequestSnapshot first = service.refresh(20, 30, 7);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.of("v1"))))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.of("v2")));
        HostingPullRequestSnapshot validated = service.refresh(20, 30, 7);
        assertThat(validated.request()).isEqualTo(first.request());
        assertThat(validated.detail()).isEqualTo(first.detail());
        assertThat(validated.etag()).contains("v2");
        verify(reader).fetch(any(), any(), eq(7), eq(Optional.of("v1")));
    }

    @Test
    void anotherAccountCannotReadOrValidateTheFirstAccountsSnapshot()
    {
        service.refresh(20, 30, 7);
        assertThat(service.cached(21, 30, 7)).isEmpty();
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Second account", Optional.of("other")));
        HostingPullRequestSnapshot second = service.refresh(21, 30, 7);
        assertThat(second.request()).isEqualTo(cache.get(20L).request());
        assertThat(service.cached(20, 30, 7).orElseThrow().pullRequest().title()).isEqualTo("First");
        assertThat(second.pullRequest().title()).isEqualTo("Second account");
    }

    @Test
    void scopesFromTheSameAccountNeverShareMetadataOrValidators()
    {
        service.refresh(20, 30, 7);
        HostingReadSession connected = connections.readSession(20);
        HostingReadSession desktop = new HostingReadSession(20, "github-global", connected.access());
        assertThat(service.cached(desktop, 30, 7)).isEmpty();
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Desktop", Optional.of("desktop")));

        HostingPullRequestSnapshot result = service.refresh(desktop, 30, 7, false);

        assertThat(result.credentialScope()).isEqualTo("github-global");
        assertThat(service.cached(20, 30, 7)).isEmpty();
        assertThat(service.cached(desktop, 30, 7)).contains(result);
    }

    @Test
    void forcedRefreshDropsTheMatchingScopesValidator()
    {
        service.refresh(20, 30, 7);
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Forced", Optional.empty()));

        assertThat(service.refresh(connections.readSession(20), 30, 7, true).pullRequest().title()).isEqualTo("Forced");
        assertThat(cache.get(20L).credentialScope()).isEqualTo(CONNECTED_ACCOUNT);
        assertThat(cache.get(20L).etag()).isEmpty();
    }

    @Test
    void disconnectPreventsCachedReadsWithoutRemoteIo()
    {
        service.refresh(20, 30, 7);
        tokens.remove(20L);
        reset(reader);
        assertThatThrownBy(() -> service.cached(20, 30, 7))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(AUTHENTICATION_REQUIRED));
        verifyNoInteractions(reader);
        assertThat(cache).containsKey(20L);
    }

    @ParameterizedTest
    @EnumSource(value = HostingFailure.Kind.class, names = {"AUTHENTICATION_REQUIRED", "FORBIDDEN", "MISSING_OR_INACCESSIBLE"})
    void accessFailureEvictsOnlyTheAffectedAccount(HostingFailure.Kind kind)
    {
        service.refresh(20, 30, 7);
        service.refresh(21, 30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenThrow(new HostingFailure(kind, "Access unavailable"));
        assertThatThrownBy(() -> service.refresh(20, 30, 7)).isInstanceOf(HostingFailure.class);
        assertThat(cache).doesNotContainKey(20L).containsKey(21L);
    }

    @Test
    void temporaryFailurePreservesExplicitlyReadableCachedData()
    {
        HostingPullRequestSnapshot previous = service.refresh(20, 30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenThrow(new HostingFailure(UNAVAILABLE, "Offline"));
        assertThatThrownBy(() -> service.refresh(20, 30, 7)).isInstanceOf(HostingFailure.class);
        assertThat(service.cached(20, 30, 7)).contains(previous);
    }

    @Test
    void incompleteResponseCannotReplaceACompleteSnapshot()
    {
        HostingPullRequestSnapshot previous = service.refresh(20, 30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenReturn(new PullRequestFetch(Optional.of(previous.pullRequest()), Optional.empty()));
        assertThatThrownBy(() -> service.refresh(20, 30, 7))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(cache.get(20L)).isEqualTo(previous);
    }

    private HostingPullRequestService service(HostingProvider provider)
    {
        when(identities.findInstance(10)).thenReturn(Optional.of(new HostingInstance(10, provider, URI.create("https://hosting.test"), URI.create("https://hosting.test/api"))));
        connections = new HostingConnectionService(identities, credentials,
                ImmutableList.of(new HostingIntegration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), reader)), ImmutableList.of());
        return new HostingPullRequestService(connections, snapshots, identities, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PullRequestFetch fetched(String title, Optional<String> etag)
    {
        String url = "https://hosting.test/team/project/requests/7";
        FetchedPullRequest summary = new FetchedPullRequest(99L, "team/project", 7, title, "alice", url, NOW, NOW, OTHER, ImmutableList.of(), ImmutableMap.of(),
                false, "open", null, null, ImmutableList.of(), null, null, "topic");
        PrRawDetail detail = new PrRawDetail(title, url, "open", "alice", null, NOW, NOW, null, "Description", ImmutableList.of(), ImmutableMap.of(),
                ImmutableList.of(), null, false, null, null, 0, 0, 0, 0, ImmutableList.of(), "a".repeat(40), "topic", "main", "b".repeat(40));
        return new PullRequestFetch(Optional.of(summary), etag, Optional.of(detail));
    }
}
