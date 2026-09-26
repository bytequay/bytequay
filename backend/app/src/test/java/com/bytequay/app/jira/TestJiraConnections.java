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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestJiraConnections
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JiraConnectionStore store = mock(JiraConnectionStore.class);
    private final JiraHttpClient http = mock(JiraHttpClient.class);
    private final JiraCache cache = new JiraCache(mock(AppSettingsStore.class), mapper);
    private final JiraConnections connections = new JiraConnections(store, cache, http);

    @Test
    void keepsPairingSecretOutOfPublicProjectionAndUsesSeparateAtlassianToken()
            throws Exception
    {
        String pairingSecret = "private-pairing-secret-that-is-long-enough";
        JiraDtos.Connection view = new JiraDtos.Connection("connection", "https://broker.example", "connected",
                ImmutableList.of(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net")), Optional.empty(), Optional.empty(), Optional.empty());
        when(store.find("connection")).thenReturn(Optional.of(new JiraConnectionStore.Connection("connection", "https://broker.example", pairingSecret, cache.encode(view))));
        when(http.request(URI.create("https://broker.example/v1/connections/connection/token"), "POST", Optional.of(pairingSecret),
                Optional.of(ImmutableMap.of("cloudId", "cloud")))).thenReturn(mapper.readTree("{\"accessToken\":\"atlassian-access-token\"}"));
        when(http.request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/10001"), "GET", Optional.of("atlassian-access-token"), Optional.empty()))
                .thenReturn(mapper.readTree("{\"id\":\"10001\"}"));
        assertThat(cache.encode(connections.get("connection"))).doesNotContain(pairingSecret);
        assertThat(connections.request("connection", "cloud", "/rest/api/3/issue/10001", "GET", Optional.empty()).path("id").asText()).isEqualTo("10001");
        verify(http).request(URI.create("https://api.atlassian.com/ex/jira/cloud/rest/api/3/issue/10001"), "GET", Optional.of("atlassian-access-token"), Optional.empty());
    }

    @Test
    void refusesUnexpectedAuthorizationOriginAndUnconnectedSites()
            throws Exception
    {
        when(http.request(URI.create("https://broker.example/v1/connections"), "POST", Optional.empty(), Optional.of(ImmutableMap.of())))
                .thenReturn(mapper.readTree("{\"id\":\"connection\",\"pairingToken\":\"private-token-that-is-at-least-32-characters\","
                        + "\"authorizationUrl\":\"https://other.example/login\"}"));
        assertThatThrownBy(() -> connections.connect("https://broker.example")).hasMessageContaining("origin");
        verify(store, never()).save(any());
        JiraDtos.Connection view = new JiraDtos.Connection("connection", "https://broker.example", "connected", ImmutableList.of(),
                Optional.empty(), Optional.empty(), Optional.empty());
        when(store.find("connection")).thenReturn(Optional.of(new JiraConnectionStore.Connection("connection", "https://broker.example", "private", cache.encode(view))));
        assertThatThrownBy(() -> connections.request("connection", "other-cloud", "/rest/api/3/issue/1", "GET", Optional.empty())).hasMessageContaining("not connected");
        verify(http, never()).request(any(), any(), eq(Optional.of("private")), any());
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
}
