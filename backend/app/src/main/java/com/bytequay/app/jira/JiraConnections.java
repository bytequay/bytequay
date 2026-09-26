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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class JiraConnections
{
    private final JiraConnectionStore store;
    private final JiraCache json;
    private final JiraHttpClient http;
    private final Optional<URI> authOrigin;

    public JiraConnections(JiraConnectionStore store, JiraCache json, JiraHttpClient http, @Value("${bytequay.jira.auth-url:}") String authUrl)
    {
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.http = requireNonNull(http, "http is null");
        this.authOrigin = Optional.of(requireNonNull(authUrl, "authUrl is null").strip()).filter(value -> !value.isEmpty()).map(JiraConnections::broker);
    }

    public boolean configured()
    {
        return authOrigin.isPresent();
    }

    public List<JiraDtos.Connection> list()
    {
        List<JiraDtos.Connection> connections = snapshots();
        return connections.stream().map(connection -> duplicates(connection, connections)).collect(toImmutableList());
    }

    public JiraDtos.Connection get(String id)
    {
        return duplicates(view(stored(id)), snapshots());
    }

    public synchronized JiraDtos.Connection connect()
    {
        URI base = authOrigin.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Jira sign-in is not configured for this installation."));
        JsonNode result = http.request(base.resolve("/v1/connections"), "POST", Optional.empty(), Optional.of(ImmutableMap.of()));
        String id = segment(result.path("id").asText());
        checkArgument(store.find(id).isEmpty(), "Jira sign-in returned an existing connection id");
        String secret = result.path("pairingToken").asText();
        checkArgument(secret.length() >= 32 && secret.length() <= 1024, "Invalid Jira pairing response");
        JiraDtos.Connection pending = pending(id, base, result, Optional.empty(), ImmutableList.of());
        store.save(new JiraConnectionStore.Connection(id, base.toString(), secret, json.encode(pending)));
        return pending;
    }

    public synchronized JiraDtos.Connection reconnect(String id)
    {
        JiraConnectionStore.Connection saved = stored(id);
        JiraDtos.Connection previous = view(saved);
        URI base = broker(saved.brokerUrl());
        JsonNode result;
        try {
            result = http.request(base.resolve("/v1/connections/" + segment(id) + "/reconnect"), "POST",
                    Optional.of(saved.pairingToken()), Optional.of(ImmutableMap.of()));
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 404
                    || failure.getStatusCode().value() == 409 && previous.account().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This connection cannot be restored. Remove it and add the Jira account again. Existing tasks will remain.");
            }
            throw failure;
        }
        checkArgument(id.equals(result.path("id").asText()), "Jira reconnect changed connection identity");
        return save(saved, pending(id, base, result, previous.account(), previous.sites()));
    }

    public synchronized JiraDtos.Connection refresh(String id)
    {
        JiraConnectionStore.Connection saved = stored(id);
        JiraDtos.Connection previous = view(saved);
        JsonNode status;
        try {
            status = http.request(broker(saved.brokerUrl()).resolve("/v1/connections/" + segment(id)), "GET", Optional.of(saved.pairingToken()), Optional.empty());
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 404) {
                return save(saved, error(previous, "This Jira connection is no longer available. Remove it and add the account again. Existing tasks will remain."));
            }
            throw failure;
        }
        String state = status.path("state").asText();
        checkArgument(ImmutableList.of("pending", "connected", "error").contains(state), "Invalid Jira connection state");
        Optional<JiraDtos.Account> account = account(status.path("account"));
        if (state.equals("connected") && (account.isEmpty() || previous.account().isPresent()
                && !previous.account().orElseThrow().accountId().equals(account.orElseThrow().accountId()))) {
            return save(saved, error(previous, "This sign-in did not match the original Jira account. Reconnect with that account, or add a separate account."));
        }
        List<JiraDtos.Site> sites = state.equals("connected") ? ImmutableList.copyOf(status.path("sites")).stream()
                .map(site -> new JiraDtos.Site(segment(site.path("id").asText()), site.path("name").asText(), siteUrl(site.path("url").asText())))
                .collect(toImmutableList()) : previous.sites();
        Optional<String> error = state.equals("error") ? Optional.of("Jira sign-in failed. Reconnect this account.")
                : JiraMapping.text(status, "error").map(ignored -> "Sign-in did not change this account. Reconnect with the original account, or add another account.");
        return save(saved, new JiraDtos.Connection(id, state, sites, state.equals("pending") ? previous.authorizationUrl() : Optional.empty(),
                state.equals("pending") ? previous.pairingCode() : Optional.empty(), error, account.or(previous::account), Optional.empty()));
    }

    public synchronized void disconnect(String id)
    {
        JiraConnectionStore.Connection saved = stored(id);
        try {
            http.request(broker(saved.brokerUrl()).resolve("/v1/connections/" + segment(id)), "DELETE", Optional.of(saved.pairingToken()), Optional.empty());
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() != 401 && failure.getStatusCode().value() != 404) {
                throw failure;
            }
        }
        store.delete(id);
    }

    public JiraDtos.Site site(String id, String cloudId)
    {
        JiraDtos.Connection connection = get(id);
        if (!connection.state().equals("connected")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reconnect this Jira account before refreshing or editing tickets.");
        }
        return connection.sites().stream().filter(site -> site.id().equals(segment(cloudId))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This Jira site is not connected."));
    }

    // ponytail: Global monitor prevents reconnect races; use per-connection locks if measured request contention warrants it.
    public synchronized JsonNode request(String connectionId, String cloudId, String path, String method, Optional<Object> body)
    {
        site(connectionId, cloudId);
        checkArgument(path.startsWith("/rest/") && !path.contains(".."), "Invalid Jira API path");
        JiraConnectionStore.Connection saved = stored(connectionId);
        boolean tokenAccepted = false;
        try {
            JsonNode token = http.request(broker(saved.brokerUrl()).resolve("/v1/connections/" + segment(connectionId) + "/token"),
                    "POST", Optional.of(saved.pairingToken()), Optional.of(ImmutableMap.of("cloudId", cloudId)));
            String accessToken = token.path("accessToken").asText();
            checkArgument(!accessToken.isBlank() && accessToken.length() <= 16384, "Invalid Jira access token response");
            tokenAccepted = true;
            URI uri = URI.create("https://api.atlassian.com/ex/jira/" + segment(cloudId) + path);
            return http.request(uri, method, Optional.of(accessToken), body);
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 401 || !tokenAccepted && failure.getStatusCode().value() == 404) {
                save(saved, error(view(saved), "Jira authorization expired or was revoked. Reconnect this account."));
            }
            else if (!tokenAccepted && failure.getStatusCode().value() == 403) {
                try {
                    refresh(connectionId);
                }
                catch (ResponseStatusException ignored) {
                    // Keep the original site-authorization error if its status cannot be refreshed.
                }
            }
            throw failure;
        }
    }

    private JiraDtos.Connection pending(String id, URI base, JsonNode result, Optional<JiraDtos.Account> account, List<JiraDtos.Site> sites)
    {
        String authorization = result.path("authorizationUrl").asText();
        URI target = URI.create(authorization);
        checkArgument(base.getScheme().equals(target.getScheme()) && base.getAuthority().equals(target.getAuthority()), "Unexpected Jira sign-in origin");
        String code = result.path("pairingCode").asText();
        checkArgument(code.matches("[A-Fa-f0-9]{8}"), "Invalid Jira pairing code");
        return new JiraDtos.Connection(id, "pending", sites, Optional.of(authorization), Optional.of(code), Optional.empty(), account, Optional.empty());
    }

    private static JiraDtos.Connection error(JiraDtos.Connection previous, String message)
    {
        return new JiraDtos.Connection(previous.id(), "error", previous.sites(), Optional.empty(), Optional.empty(), Optional.of(message), previous.account(), Optional.empty());
    }

    private static Optional<JiraDtos.Account> account(JsonNode account)
    {
        if (account.isNull() || account.isMissingNode()) {
            return Optional.empty();
        }
        String id = account.path("accountId").asText();
        String name = account.path("displayName").asText();
        checkArgument(!id.isBlank() && id.length() <= 256 && !name.isBlank() && name.length() <= 512, "Invalid Jira account identity");
        return Optional.of(new JiraDtos.Account(id, name, JiraMapping.text(account, "email")));
    }

    private List<JiraDtos.Connection> snapshots()
    {
        return store.ids().stream().map(id -> view(stored(id))).collect(toImmutableList());
    }

    private JiraDtos.Connection view(JiraConnectionStore.Connection stored)
    {
        return json.decode(stored.stateJson(), JiraDtos.Connection.class);
    }

    private JiraDtos.Connection save(JiraConnectionStore.Connection stored, JiraDtos.Connection view)
    {
        store.save(new JiraConnectionStore.Connection(stored.id(), stored.brokerUrl(), stored.pairingToken(), json.encode(view)));
        return duplicates(view, snapshots());
    }

    private static JiraDtos.Connection duplicates(JiraDtos.Connection connection, List<JiraDtos.Connection> connections)
    {
        Optional<String> duplicate = connection.state().equals("connected") && connection.account().isPresent() ? connections.stream()
                .filter(other -> other.state().equals("connected") && other.id().compareTo(connection.id()) < 0)
                .filter(other -> other.account().map(JiraDtos.Account::accountId).equals(connection.account().map(JiraDtos.Account::accountId)))
                .filter(other -> other.sites().stream().map(JiraDtos.Site::id).collect(toImmutableSet())
                        .equals(connection.sites().stream().map(JiraDtos.Site::id).collect(toImmutableSet())))
                .map(JiraDtos.Connection::id).sorted().findFirst() : Optional.empty();
        return new JiraDtos.Connection(connection.id(), connection.state(), connection.sites(), connection.authorizationUrl(), connection.pairingCode(), connection.error(),
                connection.account(), duplicate);
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
