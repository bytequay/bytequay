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

import com.bytequay.core.store.JiraConnectionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class JiraConnections
{
    private final JiraConnectionStore store;
    private final JiraCache json;
    private final JiraHttpClient http;

    public JiraConnections(JiraConnectionStore store, JiraCache json, JiraHttpClient http)
    {
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.http = requireNonNull(http, "http is null");
    }

    public List<JiraDtos.Connection> list()
    {
        return store.ids().stream().map(this::get).collect(toImmutableList());
    }

    public JiraDtos.Connection get(String id)
    {
        return json.decode(stored(id).stateJson(), JiraDtos.Connection.class);
    }

    public JiraDtos.Connection connect(String brokerUrl)
    {
        URI base = broker(brokerUrl);
        JsonNode result = http.request(base.resolve("/v1/connections"), "POST", Optional.empty(), Optional.of(ImmutableMap.of()));
        String id = segment(result.path("id").asText());
        String secret = result.path("pairingToken").asText();
        checkArgument(secret.length() >= 32 && secret.length() <= 1024, "Invalid Jira pairing response");
        String authorization = result.path("authorizationUrl").asText();
        URI target = URI.create(authorization);
        checkArgument(base.getScheme().equals(target.getScheme()) && base.getAuthority().equals(target.getAuthority()), "Unexpected Jira sign-in origin");
        JiraDtos.Connection view = new JiraDtos.Connection(id, base.toString(), "pending", ImmutableList.of(),
                Optional.of(authorization), Optional.of(result.path("pairingCode").asText()), Optional.empty());
        store.save(new JiraConnectionStore.Connection(id, base.toString(), secret, json.encode(view)));
        return view;
    }

    public synchronized JiraDtos.Connection refresh(String id)
    {
        JiraConnectionStore.Connection stored = stored(id);
        JiraDtos.Connection previous = get(id);
        JsonNode status = http.request(URI.create(stored.brokerUrl()).resolve("/v1/connections/" + segment(id)), "GET", Optional.of(stored.pairingToken()), Optional.empty());
        List<JiraDtos.Site> sites = ImmutableList.copyOf(status.path("sites")).stream()
                .map(site -> new JiraDtos.Site(segment(site.path("id").asText()), site.path("name").asText(), siteUrl(site.path("url").asText())))
                .collect(toImmutableList());
        String state = status.path("state").asText();
        checkArgument(ImmutableList.of("pending", "connected", "error").contains(state), "Invalid Jira connection state");
        JiraDtos.Connection view = new JiraDtos.Connection(id, stored.brokerUrl(), state, sites,
                state.equals("pending") ? previous.authorizationUrl() : Optional.empty(), state.equals("pending") ? previous.pairingCode() : Optional.empty(),
                state.equals("error") ? Optional.of("Jira sign-in failed. Disconnect and connect again.") : Optional.empty());
        store.save(new JiraConnectionStore.Connection(id, stored.brokerUrl(), stored.pairingToken(), json.encode(view)));
        return view;
    }

    public synchronized void disconnect(String id)
    {
        JiraConnectionStore.Connection saved = stored(id);
        http.request(URI.create(saved.brokerUrl()).resolve("/v1/connections/" + segment(id)), "DELETE", Optional.of(saved.pairingToken()), Optional.empty());
        store.delete(id);
    }

    public JiraDtos.Site site(String id, String cloudId)
    {
        return get(id).sites().stream().filter(site -> site.id().equals(segment(cloudId))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This Jira site is not connected."));
    }

    public JsonNode request(String connectionId, String cloudId, String path, String method, Optional<Object> body)
    {
        site(connectionId, cloudId);
        JiraConnectionStore.Connection saved = stored(connectionId);
        JsonNode token = http.request(URI.create(saved.brokerUrl()).resolve("/v1/connections/" + segment(connectionId) + "/token"),
                "POST", Optional.of(saved.pairingToken()), Optional.of(ImmutableMap.of("cloudId", cloudId)));
        String accessToken = token.path("accessToken").asText();
        checkArgument(!accessToken.isBlank() && accessToken.length() <= 16384, "Invalid Jira access token response");
        checkArgument(path.startsWith("/rest/") && !path.contains(".."), "Invalid Jira API path");
        URI uri = URI.create("https://api.atlassian.com/ex/jira/" + segment(cloudId) + path);
        return http.request(uri, method, Optional.of(accessToken), body);
    }

    public static String segment(String value)
    {
        checkArgument(value != null && value.matches("[A-Za-z0-9_-]{1,128}"), "Invalid Jira identifier");
        return value;
    }

    static URI broker(String value)
    {
        URI uri = URI.create(requireNonNull(value, "brokerUrl is null").strip());
        boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        checkArgument(("https".equals(uri.getScheme()) || local) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null && (uri.getPath().isEmpty() || uri.getPath().equals("/")),
                "Use the HTTPS origin of your Jira sign-in service.");
        return URI.create(uri.getScheme() + "://" + uri.getAuthority());
    }

    private static String siteUrl(String value)
    {
        URI uri = URI.create(value);
        checkArgument("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getHost().endsWith(".atlassian.net")
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "Invalid Jira Cloud site URL");
        return "https://" + uri.getHost();
    }

    private JiraConnectionStore.Connection stored(String id)
    {
        return store.find(segment(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jira connection not found."));
    }
}
