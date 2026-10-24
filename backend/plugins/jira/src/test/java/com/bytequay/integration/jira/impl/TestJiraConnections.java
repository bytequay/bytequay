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
package com.bytequay.integration.jira.impl;

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraConnectionStore;
import com.bytequay.integration.jira.JiraDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestJiraConnections
{
    private static final String SECRET = "private-pairing-secret-that-is-long-enough";
    private static final JiraDtos.Account ACCOUNT = new JiraDtos.Account("account-1", "Alice", Optional.of("alice@example.com"));
    private static final JiraDtos.Site SITE = new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net");
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JiraConnectionStore store = mock(JiraConnectionStore.class);
    private final JiraHttpClient http = mock(JiraHttpClient.class);
    private final JiraCache cache = new JiraCache(mock(AppSettingsStore.class), mapper);
    private final DefaultJiraConnections connections = new DefaultJiraConnections(store, cache, http, "https://broker.example", clock);
    private final Map<String, JiraConnectionStore.Connection> records = new HashMap<>();

    @BeforeEach
    void setup()
    {
        when(clock.instant()).thenReturn(NOW);
        when(store.ids()).thenAnswer(ignored -> records.keySet().stream().sorted().collect(toImmutableList()));
        when(store.find(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        doAnswer(call -> {
            JiraConnectionStore.Connection record = call.getArgument(0);
            records.put(record.id(), record);
            return null;
        }).when(store).save(any());
        doAnswer(call -> {
            records.remove(call.getArgument(0));
            return null;
        }).when(store).delete(anyString());
    }

    @Test
    void unconfiguredInstallationCannotStartSignIn()
    {
        DefaultJiraConnections unavailable = new DefaultJiraConnections(store, cache, http, " ");
        assertThat(unavailable.configured()).isFalse();
        assertThat(connections.configured()).isTrue();
        assertThatThrownBy(unavailable::connect).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not configured");
        verifyNoInteractions(http);
    }

    @Test
    void connectsThroughConfiguredOriginWithoutExposingItOrPairingSecret()
            throws Exception
    {
        when(http.request(URI.create("https://broker.example/v1/connections"), "POST", Optional.empty(), Optional.of(ImmutableMap.of())))
                .thenReturn(mapper.readTree("""
                        {"id":"connection","pairingToken":"private-pairing-secret-that-is-long-enough",
                        "pairingCode":"ABCD1234","authorizationUrl":"https://broker.example/oauth/start?state=one"}
                        """));
        JiraDtos.Connection connected = connections.connect();
        assertThat(connected.state()).isEqualTo("pending");
        assertThat(connected.account()).isEmpty();
        assertThat(connected.pairingCode()).contains("ABCD1234");
        assertThat(cache.encode(connected)).doesNotContain(SECRET, "brokerUrl");
        assertThat(records.get("connection").authUrl()).isEqualTo("https://broker.example");
        assertThat(records.get("connection").credential()).isEqualTo(SECRET);
    }

    @Test
    void mapsAuthenticatedAccountAndUsesSeparateAtlassianToken()
            throws Exception
    {
        put("connection", "https://broker.example", "pending", Optional.empty(), ImmutableList.of());
        when(http.request(statusUri("connection"), "GET", Optional.of(SECRET), Optional.empty())).thenReturn(mapper.readTree("""
                {"state":"connected","account":{"accountId":"account-1","displayName":"Alice","email":"alice@example.com"},
                "sites":[{"id":"cloud","name":"Example","url":"https://example.atlassian.net"}]}
                """));
        assertThat(connections.refresh("connection").account()).contains(ACCOUNT);
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("atlassian-access-token", NOW.plusSeconds(3600)));
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/10001"), "GET", Optional.of("atlassian-access-token"), Optional.empty()))
                .thenReturn(mapper.readTree("{\"id\":\"10001\"}"));
        assertThat(connections.request("connection", "cloud", "/rest/api/3/issue/10001", "GET", Optional.empty()).path("id").asText()).isEqualTo("10001");
        assertThat(cache.encode(connections.list())).doesNotContain(SECRET, "atlassian-access-token", "brokerUrl");
    }

    @Test
    void reusesSiteTokenUntilItsRefreshWindowWithoutPersistingIt()
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-access-one", NOW.plusSeconds(3600)), token("test-access-two", NOW.plusSeconds(7200)));
        URI issue = URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1");
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3569));
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        verify(http, times(1)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http, times(2)).request(issue, "GET", Optional.of("test-access-one"), Optional.empty());
        when(clock.instant()).thenReturn(NOW.plusSeconds(3570));
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        verify(http, times(2)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http).request(issue, "GET", Optional.of("test-access-two"), Optional.empty());
        verify(store, never()).save(any());
        assertThat(cache.encode(connections.list())).doesNotContain("test-access-one", "test-access-two");
    }

    @Test
    void cachedTokensStayWithinTheirConnectionAndAuthorizedSite()
    {
        JiraDtos.Site second = new JiraDtos.Site("other-cloud", "Other", "https://other.atlassian.net");
        put("first", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE, second));
        put("second", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("first"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-first-site", NOW.plusSeconds(3600)));
        when(http.request(tokenUri("first"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "other-cloud"))))
                .thenReturn(token("test-other-site", NOW.plusSeconds(3600)));
        when(http.request(tokenUri("second"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-second-connection", NOW.plusSeconds(3600)));
        for (int index = 0; index < 2; index++) {
            connections.request("first", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
            connections.request("first", "other-cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
            connections.request("second", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        }
        verify(http).request(tokenUri("first"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http).request(tokenUri("first"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "other-cloud")));
        verify(http).request(tokenUri("second"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http, times(2)).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("test-first-site"), Optional.empty());
        verify(http, times(2)).request(URI.create("https://api.atlassian.com/ex/jira/other-cloud/rest/api/3/issue/1"), "GET", Optional.of("test-other-site"), Optional.empty());
        verify(http, times(2)).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("test-second-connection"), Optional.empty());
    }

    @Test
    void concurrentRequestsShareTheTokenFetch()
            throws Exception
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenAnswer(ignored -> {
                    fetching.countDown();
                    assertThat(release.await(5, SECONDS)).isTrue();
                    return token("test-shared-access", NOW.plusSeconds(3600));
                });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty()));
            try {
                assertThat(fetching.await(5, SECONDS)).isTrue();
                Future<?> second = executor.submit(() -> connections.request("connection", "cloud", "/rest/api/3/issue/2", "GET", Optional.empty()));
                release.countDown();
                first.get(5, SECONDS);
                second.get(5, SECONDS);
                verify(http).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
            }
            finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectedCachedTokenDoesNotReplayAWritingRequest()
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-rejected-access", NOW.plusSeconds(3600)));
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        URI comments = URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1/comment");
        Optional<Object> body = Optional.of(ImmutableMap.of("body", "Test comment"));
        when(http.request(comments, "POST", Optional.of("test-rejected-access"), body)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1/comment", "POST", body)).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get("connection").state()).isEqualTo("error");
        verify(http).request(comments, "POST", Optional.of("test-rejected-access"), body);
        verify(http).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
    }

    @Test
    void failedReconnectDiscardsCachedAuthorizationBeforeAnotherWrite()
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-before-reconnect", NOW.plusSeconds(3600))).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        when(http.request(URI.create("https://broker.example/v1/connections/connection/reconnect"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of())))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> connections.reconnect("connection")).isInstanceOf(ResponseStatusException.class);
        Optional<Object> body = Optional.of(ImmutableMap.of("body", "Test comment"));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1/comment", "POST", body)).isInstanceOf(ResponseStatusException.class);
        verify(http, times(2)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http, never()).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1/comment"), "POST", Optional.of("test-before-reconnect"), body);
    }

    @Test
    void disconnectDiscardsTheCachedGrantEvenIfRemoteRemovalFails()
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("test-before-removal", NOW.plusSeconds(3600)), token("test-after-removal", NOW.plusSeconds(3600)));
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        when(http.request(statusUri("connection"), "DELETE", Optional.of(SECRET), Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> connections.disconnect("connection")).isInstanceOf(ResponseStatusException.class);
        connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
        verify(http, times(2)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        verify(http).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("test-after-removal"), Optional.empty());
    }

    @Test
    void reconnectKeepsIdentitySitesCredentialAndOriginalBroker()
            throws Exception
    {
        put("connection", "https://original.example", "error", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(URI.create("https://original.example/v1/connections/connection/reconnect"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of())))
                .thenReturn(mapper.readTree("""
                        {"id":"connection","pairingCode":"ABCD1234","authorizationUrl":"https://original.example/oauth/start?state=again"}
                        """));
        JiraDtos.Connection pending = connections.reconnect("connection");
        assertThat(pending.state()).isEqualTo("pending");
        assertThat(pending.account()).contains(ACCOUNT);
        assertThat(pending.sites()).containsExactly(SITE);
        assertThat(records.get("connection").authUrl()).isEqualTo("https://original.example");
        assertThat(records.get("connection").credential()).isEqualTo(SECRET);
        assertThatThrownBy(() -> connections.site("connection", "cloud")).hasMessageContaining("Reconnect");
        verify(store, never()).delete(anyString());
    }

    @Test
    void rejectsAccountReplacementButPreservesOriginalAccountAndSites()
            throws Exception
    {
        put("connection", "https://broker.example", "pending", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(statusUri("connection"), "GET", Optional.of(SECRET), Optional.empty())).thenReturn(mapper.readTree("""
                {"state":"connected","account":{"accountId":"other-account","displayName":"Someone else"},"sites":[]}
                """));
        JiraDtos.Connection rejected = connections.refresh("connection");
        assertThat(rejected.state()).isEqualTo("error");
        assertThat(rejected.account()).contains(ACCOUNT);
        assertThat(rejected.sites()).containsExactly(SITE);
        assertThat(rejected.error()).get().asString().contains("original Jira account");
        assertThat(rejected.authorizationUrl()).isEmpty();
        assertThat(rejected.pairingCode()).isEmpty();
    }

    @Test
    void rejectedReconnectKeepsTheConnectedAccountAndItsVisibleError()
            throws Exception
    {
        put("connection", "https://broker.example", "pending", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(statusUri("connection"), "GET", Optional.of(SECRET), Optional.empty())).thenReturn(mapper.readTree("""
                {"state":"connected","account":{"accountId":"account-1","displayName":"Alice"},
                "sites":[{"id":"cloud","name":"Example","url":"https://example.atlassian.net"}],"error":"remote-private-message"}
                """));
        JiraDtos.Connection refreshed = connections.refresh("connection");
        assertThat(refreshed.state()).isEqualTo("connected");
        assertThat(refreshed.account().orElseThrow().accountId()).isEqualTo(ACCOUNT.accountId());
        assertThat(refreshed.error()).get().asString().contains("original account").doesNotContain("remote-private-message");
    }

    @Test
    void missingRemoteConnectionExplainsRemoveAndAddWithoutRemovingLocally()
    {
        put("connection", "https://broker.example", "error", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(URI.create("https://broker.example/v1/connections/connection/reconnect"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of())))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> connections.reconnect("connection")).hasMessageContaining("Remove it and add");
        assertThat(connections.get("connection").account()).contains(ACCOUNT);
        verify(store, never()).delete(anyString());
    }

    @Test
    void flagsOnlyExactAccountAndSiteSetDuplicatesWithoutMergingGrants()
    {
        JiraDtos.Site second = new JiraDtos.Site("other-cloud", "Other", "https://other.atlassian.net");
        put("first", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE, second));
        put("second", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(second, SITE));
        put("third", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        put("fourth", "https://broker.example", "connected", Optional.of(new JiraDtos.Account("another", "Alice", ACCOUNT.email())), ImmutableList.of(SITE, second));
        assertThat(connections.get("first").duplicateOf()).isEmpty();
        assertThat(connections.get("second").duplicateOf()).contains("first");
        assertThat(connections.get("third").duplicateOf()).isEmpty();
        assertThat(connections.get("fourth").duplicateOf()).isEmpty();
        assertThat(connections.list()).hasSize(4);
        verify(store, never()).save(any());
        verify(store, never()).delete(anyString());
        verifyNoInteractions(http);
    }

    @Test
    void authenticationFailuresBecomeVisibleWithoutDeletingConnection()
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get("connection").state()).isEqualTo("error");
        assertThat(connections.get("connection").account()).contains(ACCOUNT);
        verify(store, never()).delete(anyString());
        when(http.request(statusUri("connection"), "GET", Optional.of(SECRET), Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThat(connections.refresh("connection").state()).isEqualTo("error");
    }

    @Test
    void reconnectDuringAnOlderRequestFinishesConnectedDespiteItsLaterFailure()
            throws Exception
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        String originalState = records.get("connection").stateJson();
        when(http.request(URI.create("https://broker.example/v1/connections/connection/reconnect"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of())))
                .thenReturn(mapper.readTree("""
                        {"id":"connection","pairingCode":"ABCD1234","authorizationUrl":"https://broker.example/oauth/start?state=new"}
                        """));
        when(http.request(statusUri("connection"), "GET", Optional.of(SECRET), Optional.empty())).thenReturn(mapper.readTree("""
                {"state":"connected","account":{"accountId":"account-1","displayName":"Alice","email":"alice@example.com"},
                "sites":[{"id":"cloud","name":"Example","url":"https://example.atlassian.net"}]}
                """));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("old-access-token", NOW.plusSeconds(3600)), token("test-new-access", NOW.plusSeconds(3600)));
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("old-access-token"), Optional.empty()))
                .thenAnswer(ignored -> {
                    requestStarted.countDown();
                    assertThat(releaseFailure.await(5, SECONDS)).isTrue();
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = executor.submit(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty()));
            try {
                assertThat(requestStarted.await(5, SECONDS)).isTrue();
                connections.reconnect("connection");
                assertThat(connections.refresh("connection").state()).isEqualTo("connected");
                releaseFailure.countDown();
                assertThatThrownBy(() -> request.get(5, SECONDS)).hasCauseInstanceOf(ResponseStatusException.class);
                // The restored state equals the one the request read, so only the change count tells its 401 is stale.
                assertThat(records.get("connection").stateJson()).isEqualTo(originalState);
                connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty());
                verify(http).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("test-new-access"), Optional.empty());
                verify(http, times(2)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
            }
            finally {
                releaseFailure.countDown();
            }
        }
    }

    @Test
    void ticketPermissionFailureDoesNotDisconnectTheAccount()
            throws Exception
    {
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud"))))
                .thenReturn(token("access", NOW.plusSeconds(3600)));
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("access"), Optional.empty()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get("connection").state()).isEqualTo("connected");
        // Recheck the site's authorization on the next request without replaying the rejected request.
        verify(http).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).isInstanceOf(ResponseStatusException.class);
        verify(http, times(2)).request(tokenUri("connection"), "POST", Optional.of(SECRET), Optional.of(ImmutableMap.of("cloudId", "cloud")));
    }

    @Test
    void disconnectCanRemoveAnAlreadyExpiredRemoteConnection()
    {
        put("connection", "https://broker.example", "error", Optional.of(ACCOUNT), ImmutableList.of(SITE));
        when(http.request(statusUri("connection"), "DELETE", Optional.of(SECRET), Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        connections.disconnect("connection");
        assertThat(records).isEmpty();
    }

    @Test
    void refusesUnexpectedAuthorizationOriginAndUnconnectedSites()
            throws Exception
    {
        when(http.request(URI.create("https://broker.example/v1/connections"), "POST", Optional.empty(), Optional.of(ImmutableMap.of())))
                .thenReturn(mapper.readTree("{\"id\":\"connection\",\"pairingToken\":\"private-token-that-is-at-least-32-characters\","
                        + "\"authorizationUrl\":\"https://other.example/login\"}"));
        assertThatThrownBy(connections::connect).hasMessageContaining("origin");
        verify(store, never()).save(any());
        put("connection", "https://broker.example", "connected", Optional.of(ACCOUNT), ImmutableList.of());
        assertThatThrownBy(() -> connections.request("connection", "other-cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).hasMessageContaining("not connected");
        verify(http, never()).request(any(), any(), eq(Optional.of(SECRET)), any());
    }

    @Test
    void acceptsOnlyHttpsOriginsOrExplicitLocalDevelopmentOrigins()
    {
        assertThat(DefaultJiraConnections.broker("http://127.0.0.1:8787")).isEqualTo(URI.create("http://127.0.0.1:8787"));
        assertThatThrownBy(() -> DefaultJiraConnections.broker("http://broker.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DefaultJiraConnections.broker("https://user:secret@broker.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DefaultJiraConnections.broker("https://broker.example/path")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }

    private JsonNode token(String value, Instant expiresAt)
    {
        return mapper.valueToTree(ImmutableMap.of("accessToken", value, "expiresAt", expiresAt.toString()));
    }

    private void put(String id, String brokerUrl, String state, Optional<JiraDtos.Account> account, List<JiraDtos.Site> sites)
    {
        records.put(id, new JiraConnectionStore.Connection(id, brokerUrl, SECRET,
                cache.encode(new JiraDtos.Connection(id, state, sites, Optional.empty(), Optional.empty(), Optional.empty(), account, Optional.empty(), "oauth", Optional.empty()))));
    }

    private static URI statusUri(String id)
    {
        return URI.create("https://broker.example/v1/connections/" + id);
    }

    private static URI tokenUri(String id)
    {
        return URI.create("https://broker.example/v1/connections/" + id + "/token");
    }
}
