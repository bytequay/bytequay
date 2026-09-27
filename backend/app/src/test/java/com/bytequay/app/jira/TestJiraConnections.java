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
package com.bytequay.app.jira;

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraConnectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestJiraConnections
{
    private static final String SECRET = "private-pairing-secret-that-is-long-enough";
    private static final JiraDtos.Account ACCOUNT = new JiraDtos.Account("account-1", "Alice", Optional.of("alice@example.com"));
    private static final JiraDtos.Site SITE = new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JiraConnectionStore store = mock(JiraConnectionStore.class);
    private final JiraHttpClient http = mock(JiraHttpClient.class);
    private final JiraCache cache = new JiraCache(mock(AppSettingsStore.class), mapper);
    private final JiraConnections connections = new JiraConnections(store, cache, http, "https://broker.example");
    private final Map<String, JiraConnectionStore.Connection> records = new HashMap<>();

    @BeforeEach
    void setup()
    {
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
        JiraConnections unavailable = new JiraConnections(store, cache, http, " ");
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
                .thenReturn(mapper.readTree("{\"accessToken\":\"atlassian-access-token\"}"));
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/10001"), "GET", Optional.of("atlassian-access-token"), Optional.empty()))
                .thenReturn(mapper.readTree("{\"id\":\"10001\"}"));
        assertThat(connections.request("connection", "cloud", "/rest/api/3/issue/10001", "GET", Optional.empty()).path("id").asText()).isEqualTo("10001");
        assertThat(cache.encode(connections.list())).doesNotContain(SECRET, "atlassian-access-token", "brokerUrl");
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
                .thenReturn(mapper.readTree("{\"accessToken\":\"old-access-token\"}"));
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
                .thenReturn(mapper.readTree("{\"accessToken\":\"access\"}"));
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/1"), "GET", Optional.of("access"), Optional.empty()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connections.request("connection", "cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get("connection").state()).isEqualTo("connected");
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
        assertThat(JiraConnections.broker("http://127.0.0.1:8787")).isEqualTo(URI.create("http://127.0.0.1:8787"));
        assertThatThrownBy(() -> JiraConnections.broker("http://broker.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JiraConnections.broker("https://user:secret@broker.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JiraConnections.broker("https://broker.example/path")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
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
