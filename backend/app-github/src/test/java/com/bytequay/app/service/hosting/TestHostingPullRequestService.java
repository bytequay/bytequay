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
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.PullRequestFetch;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.AUTHENTICATION_REQUIRED;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
    private final Map<Long, Long> generations = new ConcurrentHashMap<>();
    private final Map<Long, HostingPullRequestSnapshot> cache = new ConcurrentHashMap<>();
    private HostingPullRequestService service;
    private HostingConnectionService connections;

    @BeforeEach
    void setUp()
    {
        generations.put(20L, 1L);
        generations.put(21L, 1L);
        when(credentials.find(anyLong())).thenAnswer(call -> Optional.ofNullable(generations.get(call.<Long>getArgument(0)))
                .map(generation -> new HostingCredential(call.getArgument(0), generation, "secret")));
        when(identities.findAccount(anyLong())).thenAnswer(call -> Optional.of(new HostingAccount(call.getArgument(0), 10, "42", "alice")));
        when(identities.findRepository(30)).thenReturn(Optional.of(new HostingRepository(30, 10, "100", "team/project", URI.create("https://hosting.test/team/project"),
                URI.create("https://hosting.test/team/project.git"), Optional.empty(), Optional.of("main"))));
        when(snapshots.find(anyLong(), eq(30L), eq(7))).thenAnswer(call -> Optional.ofNullable(cache.get(call.<Long>getArgument(0))));
        when(snapshots.save(anyLong(), eq(30L), anyString(), anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
            FetchedPullRequest summary = call.getArgument(4);
            HostingPullRequestSnapshot snapshot = new HostingPullRequestSnapshot(new HostingPullRequest(40, 30, 7, summary.githubId().toString()),
                    call.getArgument(0), call.getArgument(2), call.getArgument(3), summary, call.getArgument(5), call.getArgument(6), call.getArgument(7));
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
    void credentialReplacementHidesOldSnapshotAndDropsItsValidator()
    {
        service.refresh(20, 30, 7);
        generations.put(20L, 2L);
        assertThat(service.cached(20, 30, 7)).isEmpty();
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Replacement", Optional.empty()));
        assertThat(service.refresh(20, 30, 7).credentialGeneration()).isEqualTo(2);
        assertThat(cache.get(20L).etag()).isEmpty();
    }

    @Test
    void scopesFromTheSameAccountNeverShareMetadataOrValidators()
    {
        service.refresh(20, 30, 7);
        HostingReadSession connected = connections.readSession(20);
        HostingReadSession desktop = new HostingReadSession(20, "github-global:revision", 1, connected.access(), connected.guard());
        assertThat(service.cached(desktop, 30, 7)).isEmpty();
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Desktop", Optional.of("desktop")));

        HostingPullRequestSnapshot result = service.refresh(desktop, 30, 7, false);

        assertThat(result.credentialScope()).isEqualTo("github-global:revision");
        assertThat(service.cached(20, 30, 7)).isEmpty();
        service.evict(connected, 30, 7);
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
        generations.remove(20L);
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
    void lateCredentialReplacementCannotOverwriteAcceptedSnapshot()
    {
        HostingPullRequestSnapshot previous = service.refresh(20, 30, 7);
        when(reader.fetch(any(), any(), anyInt(), any())).thenAnswer(call -> {
            generations.put(20L, 2L);
            return fetched("Late", Optional.of("late"));
        });
        assertThatThrownBy(() -> service.refresh(20, 30, 7))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        assertThat(cache.get(20L)).isEqualTo(previous);
        assertThat(service.cached(20, 30, 7)).isEmpty();
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

    @Test
    void evictionRetainsOtherAccountsAndAllowsAnUnconditionalRefresh()
    {
        service.refresh(20, 30, 7);
        service.refresh(21, 30, 7);
        service.evict(20, 30, 7);
        assertThat(service.cached(20, 30, 7)).isEmpty();
        assertThat(service.cached(21, 30, 7)).isPresent();
        reset(reader);
        when(reader.fetch(any(), any(), eq(7), eq(Optional.empty()))).thenReturn(fetched("Reloaded", Optional.empty()));
        assertThat(service.refresh(20, 30, 7).request().id()).isEqualTo(40);
    }

    @Test
    void serializesRefreshesBeforeCapturingValidators()
            throws Exception
    {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        when(reader.fetch(any(), any(), anyInt(), any())).thenAnswer(call -> {
            int index = calls.incrementAndGet();
            if (index == 1) {
                firstStarted.countDown();
                assertThat(releaseFirst.await(5, SECONDS)).isTrue();
            }
            else {
                assertThat(call.<Optional<String>>getArgument(3)).contains("v1");
            }
            return fetched("Refresh " + index, Optional.of("v" + index));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.refresh(20, 30, 7));
            try {
                assertThat(firstStarted.await(5, SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return service.refresh(20, 30, 7);
                });
                await().atMost(5, SECONDS).until(() -> secondThread.get() != null && (second.isDone() || secondThread.get().getState() == Thread.State.WAITING));
                assertThat(calls.get()).isEqualTo(1);
                releaseFirst.countDown();
                assertThat(first.get(5, SECONDS).pullRequest().title()).isEqualTo("Refresh 1");
                assertThat(second.get(5, SECONDS).pullRequest().title()).isEqualTo("Refresh 2");
                assertThat(service.cached(20, 30, 7).orElseThrow().etag()).contains("v2");
            }
            finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void writeInvalidationWaitsForInflightRefreshAndRemovesItsResult()
            throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> invalidating = new AtomicReference<>();
        when(reader.fetch(any(), any(), anyInt(), any())).thenAnswer(ignored -> {
            started.countDown();
            assertThat(release.await(5, SECONDS)).isTrue();
            return fetched("Before write", Optional.of("old"));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh = executor.submit(() -> service.refresh(20, 30, 7));
            try {
                assertThat(started.await(5, SECONDS)).isTrue();
                var invalidate = executor.submit(() -> {
                    invalidating.set(Thread.currentThread());
                    service.invalidateRepository(30, 7);
                });
                await().atMost(5, SECONDS).until(() -> invalidating.get() != null && (invalidate.isDone() || invalidating.get().getState() == Thread.State.WAITING));
                assertThat(invalidate.isDone()).isFalse();
                release.countDown();
                refresh.get(5, SECONDS);
                invalidate.get(5, SECONDS);
                assertThat(cache).isEmpty();
            }
            finally {
                release.countDown();
            }
        }
    }

    private HostingPullRequestService service(HostingProvider provider)
    {
        when(identities.findInstance(10)).thenReturn(Optional.of(new HostingInstance(10, provider, URI.create("https://hosting.test"), URI.create("https://hosting.test/api"))));
        connections = new HostingConnectionService(identities, credentials,
                ImmutableList.of(new HostingIntegration(provider, mock(HostingAccounts.class), mock(HostingRepositories.class), reader)));
        return new HostingPullRequestService(connections, snapshots, Clock.fixed(NOW, ZoneOffset.UTC));
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
