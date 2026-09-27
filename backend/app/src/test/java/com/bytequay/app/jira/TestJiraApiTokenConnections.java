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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestJiraApiTokenConnections
{
    private static final String SITE = "https://example.atlassian.net";
    private static final String CLOUD = "12345678-1234-1234-1234-123456789abc";
    private static final String EMAIL = "alice@example.com";
    private static final String TOKEN = "personal-api-secret";
    private static final URI TENANT = URI.create(SITE + "/_edge/tenant_info");
    private static final URI MYSELF = URI.create("https://api.atlassian.com/ex/jira/" + CLOUD + "/rest/api/3/myself");
    private static final URI ISSUE = URI.create("https://api.atlassian.com/ex/jira/" + CLOUD + "/rest/api/3/issue/10001");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JiraConnectionStore store = mock(JiraConnectionStore.class);
    private final JiraHttpClient http = mock(JiraHttpClient.class);
    private final JiraCache json = new JiraCache(mock(AppSettingsStore.class), mapper);
    private final JiraConnections connections = new JiraConnections(store, json, http, "");
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
    void discoversWithoutCredentialsAndVerifiesAtFixedGatewayBeforeSaving()
            throws Exception
    {
        prepare();
        JiraDtos.Connection connected = connections.connectToken("https://EXAMPLE.atlassian.net/", " alice@example.com ", TOKEN);
        assertThat(connected.authType()).isEqualTo("api_token");
        assertThat(connected.state()).isEqualTo("connected");
        assertThat(connected.tokenEmail()).contains(EMAIL);
        assertThat(connected.account().orElseThrow().accountId()).isEqualTo("account-1");
        assertThat(connected.account().orElseThrow().email()).isEmpty();
        assertThat(connected.sites()).containsExactly(new JiraDtos.Site(CLOUD, "example.atlassian.net", SITE));
        assertThat(connected.authorizationUrl()).isEmpty();
        assertThat(connected.pairingCode()).isEmpty();
        JiraConnectionStore.Connection saved = records.get(connected.id());
        assertThat(saved.credential()).isEqualTo(TOKEN);
        assertThat(saved.authUrl()).isEqualTo(SITE);
        assertThat(saved.stateJson()).doesNotContain(TOKEN);
        assertThat(saved.toString()).doesNotContain(TOKEN);
        assertThat(json.encode(connections.list())).doesNotContain(TOKEN, "credential", "brokerUrl");
        verify(http).request(TENANT, "GET", Optional.empty(), Optional.empty());
        verify(http).basicRequest(MYSELF, "GET", EMAIL, TOKEN, Optional.empty());
    }

    @Test
    void rejectsUnsafeSitesAndCredentialsBeforeAnyNetworkOrSave()
    {
        for (String site : ImmutableList.of("http://example.atlassian.net", "https://example.atlassian.net:443", "https://example.atlassian.net.evil.test",
                "https://example.atlassian.net@evil.test", "https://user:secret@example.atlassian.net", "https://example.atlassian.net/path",
                "https://example.atlassian.net?token=secret", "https://example.atlassian.net#secret", "https://nested.example.atlassian.net", "invalid secret")) {
            assertThatThrownBy(() -> connections.connectToken(site, EMAIL, TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("secret");
        }
        for (String email : ImmutableList.of("", "alice", "alice:secret@example.com", "alice\r\n@example.com")) {
            assertThatThrownBy(() -> connections.connectToken(SITE, email, TOKEN)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
        for (String token : ImmutableList.of("", "bad secret", "bad\r\nsecret", "x".repeat(16385))) {
            assertThatThrownBy(() -> connections.connectToken(SITE, EMAIL, token)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
        verifyNoInteractions(http);
        assertThat(records).isEmpty();
    }

    @Test
    void refusesInvalidCloudIdentityAndNonHumanOrInactiveAccounts()
            throws Exception
    {
        when(http.request(TENANT, "GET", Optional.empty(), Optional.empty())).thenReturn(mapper.readTree("{\"cloudId\":\"../other\"}"));
        assertThatThrownBy(() -> connections.connectToken(SITE, EMAIL, TOKEN)).hasMessageContaining("site identity");
        verify(http, never()).basicRequest(any(), any(), any(), any(), any());
        prepare();
        for (String user : ImmutableList.of(
                "{\"accountId\":\"app\",\"displayName\":\"Bot\",\"active\":true,\"accountType\":\"app\"}",
                "{\"accountId\":\"account-1\",\"displayName\":\"Alice\",\"active\":false,\"accountType\":\"atlassian\"}")) {
            when(http.basicRequest(MYSELF, "GET", EMAIL, TOKEN, Optional.empty())).thenReturn(mapper.readTree(user));
            assertThatThrownBy(() -> connections.connectToken(SITE, EMAIL, TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("active Atlassian account");
        }
        assertThat(records).isEmpty();
    }

    @Test
    void replacementPreservesIdentityAndOriginalCredentialOnEveryVerificationFailure()
            throws Exception
    {
        prepare();
        JiraDtos.Connection original = connections.connectToken(SITE, EMAIL, TOKEN);
        JiraConnectionStore.Connection saved = records.get(original.id());
        when(http.basicRequest(MYSELF, "GET", EMAIL, "replacement", Optional.empty()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> connections.replaceToken(original.id(), SITE, EMAIL, "replacement")).isInstanceOf(ResponseStatusException.class);
        assertThat(records.get(original.id())).isEqualTo(saved);
        when(http.basicRequest(MYSELF, "GET", EMAIL, "wrong-account", Optional.empty()))
                .thenReturn(mapper.readTree("{\"accountId\":\"account-2\",\"displayName\":\"Other\",\"active\":true,\"accountType\":\"atlassian\"}"));
        assertThatThrownBy(() -> connections.replaceToken(original.id(), SITE, EMAIL, "wrong-account")).hasMessageContaining("original Jira account");
        assertThat(records.get(original.id())).isEqualTo(saved);
        assertThatThrownBy(() -> connections.replaceToken(original.id(), "https://other.atlassian.net", EMAIL, "replacement")).hasMessageContaining("original Jira site");
        assertThat(records.get(original.id())).isEqualTo(saved);
        doReturn(mapper.readTree(user())).when(http).basicRequest(MYSELF, "GET", EMAIL, "replacement", Optional.empty());
        JiraDtos.Connection replaced = connections.replaceToken(original.id(), SITE, EMAIL, "replacement");
        assertThat(replaced.id()).isEqualTo(original.id());
        assertThat(replaced.sites()).isEqualTo(original.sites());
        assertThat(replaced.account()).isEqualTo(original.account());
        assertThat(records.get(original.id()).credential()).isEqualTo("replacement");
        assertThat(records).hasSize(1);
    }

    @Test
    void allTicketCallsUseBasicAtGatewayAndOnly401InvalidatesConnection()
            throws Exception
    {
        prepare();
        JiraDtos.Connection connected = connections.connectToken(SITE, EMAIL, TOKEN);
        when(http.basicRequest(ISSUE, "GET", EMAIL, TOKEN, Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> request(connected.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get(connected.id()).state()).isEqualTo("connected");
        doReturn(mapper.readTree("{\"id\":\"10001\"}")).when(http).basicRequest(ISSUE, "GET", EMAIL, TOKEN, Optional.empty());
        request(connected.id());
        verify(http, never()).request(eq(ISSUE), any(), any(), any());
        when(http.basicRequest(ISSUE, "GET", EMAIL, TOKEN, Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> request(connected.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get(connected.id()).state()).isEqualTo("error");
        assertThat(connections.get(connected.id()).error()).get().asString().contains("Replace the API token");
        assertThat(records.get(connected.id()).credential()).isEqualTo(TOKEN);
        assertThat(connections.refresh(connected.id()).state()).isEqualTo("connected");
    }

    @Test
    void rejectionOfTheOldTokenKeepsATokenReplacedDuringTheRequest()
            throws Exception
    {
        prepare();
        JiraDtos.Connection connected = connections.connectToken(SITE, EMAIL, TOKEN);
        doReturn(mapper.readTree(user())).when(http).basicRequest(MYSELF, "GET", EMAIL, "replacement", Optional.empty());
        when(http.basicRequest(ISSUE, "GET", EMAIL, TOKEN, Optional.empty())).thenAnswer(ignored -> {
            // The user replaces the token while this request is still waiting for Jira.
            connections.replaceToken(connected.id(), SITE, EMAIL, "replacement");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        });
        assertThatThrownBy(() -> request(connected.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(records.get(connected.id()).credential()).isEqualTo("replacement");
        assertThat(connections.get(connected.id()).state()).isEqualTo("connected");
    }

    @Test
    void refreshReportsExpiredTokenAndDisconnectDoesNotRevokeRemotely()
            throws Exception
    {
        prepare();
        JiraDtos.Connection connected = connections.connectToken(SITE, EMAIL, TOKEN);
        when(http.basicRequest(MYSELF, "GET", EMAIL, TOKEN, Optional.empty())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connections.refresh(connected.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(connections.get(connected.id()).state()).isEqualTo("connected");
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).when(http).basicRequest(MYSELF, "GET", EMAIL, TOKEN, Optional.empty());
        assertThat(connections.refresh(connected.id()).error()).get().asString().contains("Replace the API token");
        assertThatThrownBy(() -> connections.reconnect(connected.id())).hasMessageContaining("Replace the API token");
        clearInvocations(http);
        connections.disconnect(connected.id());
        assertThat(records).isEmpty();
        verifyNoInteractions(http);
    }

    @Test
    void rejectsUnconnectedSitesAndEncodedTraversalBeforeSendingCredentials()
            throws Exception
    {
        prepare();
        JiraDtos.Connection connected = connections.connectToken(SITE, EMAIL, TOKEN);
        clearInvocations(http);
        assertThatThrownBy(() -> connections.request(connected.id(), "other", "/rest/api/3/myself", "GET", Optional.empty())).hasMessageContaining("not connected");
        for (String path : ImmutableList.of("/rest/../oauth", "/rest/%2e%2e/oauth", "/rest/api#fragment", "https://evil.test/rest/api")) {
            assertThatThrownBy(() -> connections.request(connected.id(), CLOUD, path, "GET", Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(http);
    }

    @Test
    void existingOauthSnapshotsKeepTheirAuthenticationType()
    {
        JiraDtos.Connection previous = json.decode("{\"id\":\"old\",\"state\":\"connected\",\"sites\":[]}", JiraDtos.Connection.class);
        assertThat(previous.authType()).isEqualTo("oauth");
        assertThat(previous.tokenEmail()).isEmpty();
    }

    private void prepare()
            throws Exception
    {
        when(http.request(TENANT, "GET", Optional.empty(), Optional.empty())).thenReturn(mapper.readTree("{\"cloudId\":\"" + CLOUD + "\"}"));
        when(http.basicRequest(MYSELF, "GET", EMAIL, TOKEN, Optional.empty())).thenReturn(mapper.readTree(user()));
    }

    private static String user()
    {
        return "{\"accountId\":\"account-1\",\"displayName\":\"Alice\",\"active\":true,\"accountType\":\"atlassian\"}";
    }

    private void request(String id)
    {
        connections.request(id, CLOUD, "/rest/api/3/issue/10001", "GET", Optional.empty());
    }
}
