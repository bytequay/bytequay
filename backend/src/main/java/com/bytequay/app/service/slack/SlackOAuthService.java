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
package com.bytequay.app.service.slack;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Slack OAuth (v2) handshake. Resolves {@code client_id} and
 * {@code client_secret} at call time from the credentials vault under
 * {@code (INTEGRATION, "slack-oauth-app")} (BYO Slack app — slice 2c)
 * and falls back to the {@code SLACK_CLIENT_ID} / {@code SLACK_CLIENT_SECRET}
 * environment variables. The service is "not configured" — and every
 * endpoint short-circuits with 503 — when neither source supplies a value.
 *
 * <p>Flow:
 * <ol>
 *   <li>Renderer calls {@link #issueAuthorizeUrl()}; we mint a CSRF
 *       state token and build the Slack authorize URL.</li>
 *   <li>The user authorises in their browser; Slack redirects back to
 *       the {@code bytequay://slack-oauth-callback} custom scheme.</li>
 *   <li>Electron's {@code open-url} handler forwards the {@code code}
 *       and {@code state} to {@link #exchangeCode(String, String)},
 *       which validates the state, hits {@code oauth.v2.access}, and
 *       persists the user token under
 *       {@code (INTEGRATION, "slack")}.</li>
 * </ol>
 *
 * <p>Credential layout for the BYO Slack app row:
 * <ul>
 *   <li>{@code Credential.label} — the public {@code client_id}.</li>
 *   <li>{@code Credential.value} — the encrypted {@code client_secret}.</li>
 * </ul>
 *
 * @see <a href="docs/mockups/design/slack/scopes.md">scopes.md</a>
 *      for the pinned user-token scope list.
 */
@Service
public class SlackOAuthService
{
    /** Credential name for the Slack user token (xoxp-). */
    public static final String SLACK_USER_TOKEN_NAME = "slack";
    /** Credential name for the BYO Slack OAuth app (client_id + client_secret). */
    public static final String SLACK_OAUTH_APP_NAME = "slack-oauth-app";

    static final String SLACK_CLIENT_ID_ENV = "SLACK_CLIENT_ID";
    static final String SLACK_CLIENT_SECRET_ENV = "SLACK_CLIENT_SECRET";
    static final String REDIRECT_URI = "bytequay://slack-oauth-callback";
    static final String AUTHORIZE_ENDPOINT = "https://slack.com/oauth/v2/authorize";
    static final String TOKEN_ENDPOINT = "https://slack.com/api/oauth.v2.access";

    /** User-token scopes pinned in scopes.md. Order is preserved when
     *  serialised into the {@code user_scope} query parameter so logs
     *  read in a stable order. */
    static final List<String> USER_SCOPES = ImmutableList.of(
            "users:read",
            "channels:history",
            "groups:history",
            "im:history",
            "mpim:history",
            "channels:read",
            "groups:read",
            "im:read",
            "mpim:read",
            "chat:write");

    /** Issued state tokens older than this are considered expired. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(SlackOAuthService.class);

    private final CredentialService credentialService;
    private final String envClientId;
    private final String envClientSecret;
    private final OAuthExchanger exchanger;
    private final Clock clock;

    /** State token → issuance time. Cleaned up lazily on validation +
     *  whenever {@link #issueAuthorizeUrl()} runs. */
    private final ConcurrentMap<String, Instant> issuedStates = new ConcurrentHashMap<>();

    @Autowired
    public SlackOAuthService(CredentialService credentialService)
    {
        this(
                credentialService,
                System.getenv(SLACK_CLIENT_ID_ENV),
                System.getenv(SLACK_CLIENT_SECRET_ENV),
                new HttpOAuthExchanger(),
                Clock.systemUTC());
    }

    /** Test seam — explicit env-fallback values + injectable exchanger / clock. */
    SlackOAuthService(
            CredentialService credentialService,
            String envClientId,
            String envClientSecret,
            OAuthExchanger exchanger,
            Clock clock)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.envClientId = blankToNull(envClientId);
        this.envClientSecret = blankToNull(envClientSecret);
        this.exchanger = requireNonNull(exchanger, "exchanger is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** True iff both client_id and client_secret resolve from either the
     *  vault row or the env-var fallback. */
    public boolean isConfigured()
    {
        return resolveClientId() != null && resolveClientSecret() != null;
    }

    /** Constructs the Slack authorize URL the renderer should open in
     *  the system browser. Mints a fresh CSRF state token in the
     *  process — keep that round-trip in mind when designing tests. */
    public String issueAuthorizeUrl()
    {
        String resolvedClientId = requireConfiguredClientId();

        sweepExpiredStates();
        String state = generateState();
        issuedStates.put(state, clock.instant());
        return AUTHORIZE_ENDPOINT
                + "?client_id=" + urlEncode(resolvedClientId)
                + "&user_scope=" + urlEncode(String.join(",", USER_SCOPES))
                + "&redirect_uri=" + urlEncode(REDIRECT_URI)
                + "&state=" + urlEncode(state);
    }

    /** Exchanges an OAuth code for a user token and persists it.
     *  Validates the state token first — a stale or unknown state
     *  triggers a 400. */
    public ConnectionInfo exchangeCode(String code, String state)
    {
        String resolvedClientId = requireConfiguredClientId();
        String resolvedClientSecret = requireConfiguredClientSecret();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth code missing");
        }
        consumeState(state);

        TokenResponse response;
        try {
            response = exchanger.exchange(resolvedClientId, resolvedClientSecret, code, REDIRECT_URI);
        }
        catch (RuntimeException e) {
            log.warn("Slack oauth.v2.access call failed: {}", e.getMessage());
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "Slack OAuth exchange failed: " + e.getMessage(),
                    e);
        }
        if (!response.ok()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "Slack rejected the OAuth exchange: " + response.error());
        }

        ConnectionInfo info = new ConnectionInfo(response.teamId(), response.teamName(), response.authedUserId());
        credentialService.upsert(
                CredentialType.INTEGRATION,
                SLACK_USER_TOKEN_NAME,
                DEFAULT_INSTANCE_NAME,
                response.accessToken(),
                info.label(),
                "Connected via Slack OAuth on " + clock.instant());
        log.info("Slack workspace connected: {} ({})", info.teamName(), info.teamId());
        return info;
    }

    /** Returns the connected workspace, if any. The connection's team
     *  metadata lives in the credential's {@code label}. */
    public Optional<ConnectionInfo> getConnection()
    {
        return credentialService.get(CredentialType.INTEGRATION, SLACK_USER_TOKEN_NAME)
                .flatMap(SlackOAuthService::connectionFromLabel);
    }

    /** Clears the stored Slack user token. */
    public void disconnect()
    {
        credentialService.delete(CredentialType.INTEGRATION, SLACK_USER_TOKEN_NAME, DEFAULT_INSTANCE_NAME);
        log.info("Slack workspace disconnected");
    }

    /** Vault row first (label = client_id), {@code SLACK_CLIENT_ID} env-var fallback. */
    private String resolveClientId()
    {
        Optional<Credential> row = credentialService.get(CredentialType.INTEGRATION, SLACK_OAUTH_APP_NAME);
        String fromVault = row.map(Credential::label).orElse(null);
        return blankToNull(fromVault) != null ? fromVault.trim() : envClientId;
    }

    /** Vault row first (encrypted value = client_secret), {@code SLACK_CLIENT_SECRET}
     *  env-var fallback. */
    private String resolveClientSecret()
    {
        String fromVault = credentialService.getSecret(CredentialType.INTEGRATION, SLACK_OAUTH_APP_NAME)
                .orElse(null);
        return blankToNull(fromVault) != null ? fromVault.trim() : envClientSecret;
    }

    private String requireConfiguredClientId()
    {
        String value = resolveClientId();
        if (value == null || resolveClientSecret() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack OAuth is not configured — set client_id / client_secret in Settings → Integrations");
        }
        return value;
    }

    private String requireConfiguredClientSecret()
    {
        String value = resolveClientSecret();
        if (value == null || resolveClientId() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack OAuth is not configured — set client_id / client_secret in Settings → Integrations");
        }
        return value;
    }

    private void consumeState(String state)
    {
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth state missing");
        }
        Instant issued = issuedStates.remove(state);
        if (issued == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Unknown OAuth state");
        }
        if (Duration.between(issued, clock.instant()).compareTo(STATE_TTL) > 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth state expired");
        }
    }

    private void sweepExpiredStates()
    {
        Instant cutoff = clock.instant().minus(STATE_TTL);
        issuedStates.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    private static String generateState()
    {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private static Optional<ConnectionInfo> connectionFromLabel(Credential credential)
    {
        String label = credential.label();
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        return ConnectionInfo.fromLabel(label);
    }

    /** Shape of the {@code oauth.v2.access} response we depend on. */
    public record TokenResponse(
            boolean ok,
            String error,
            String accessToken,
            String teamId,
            String teamName,
            String authedUserId) {}

    /** What the connection-status endpoint surfaces. */
    public record ConnectionInfo(String teamId, String teamName, String authedUserId)
    {
        /** Encoded into Credential.label so getConnection() can rebuild it
         *  without an extra storage table. Format is intentionally
         *  pipe-delimited and tolerant — if anyone hand-edits the row,
         *  partial parses still work. */
        public String label()
        {
            StringBuilder b = new StringBuilder("slack|");
            b.append(safe(teamId)).append('|').append(safe(teamName)).append('|').append(safe(authedUserId));
            return b.toString();
        }

        public static Optional<ConnectionInfo> fromLabel(String label)
        {
            if (label == null || !label.startsWith("slack|")) {
                return Optional.empty();
            }
            String[] parts = label.split("\\|", -1);
            if (parts.length < 4) {
                return Optional.empty();
            }
            return Optional.of(new ConnectionInfo(
                    blankToNull(parts[1]),
                    blankToNull(parts[2]),
                    blankToNull(parts[3])));
        }

        private static String safe(String value)
        {
            return value == null ? "" : value.replace('|', ' ');
        }
    }

    /** Token-exchange seam. Production uses {@link HttpOAuthExchanger}; tests
     *  pass a fake. */
    @FunctionalInterface
    public interface OAuthExchanger
    {
        TokenResponse exchange(String clientId, String clientSecret, String code, String redirectUri);
    }

    /** Real HTTP implementation that POSTs to {@link #TOKEN_ENDPOINT}. */
    static final class HttpOAuthExchanger
            implements OAuthExchanger
    {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public TokenResponse exchange(String clientId, String clientSecret, String code, String redirectUri)
        {
            String body = "client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response;
            try (HttpClient http = HttpClient.newHttpClient()) {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            catch (IOException e) {
                throw new RuntimeException("Slack token endpoint I/O failure", e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Slack token endpoint interrupted", e);
            }
            return parseTokenResponse(response.body());
        }

        private static TokenResponse parseTokenResponse(String body)
        {
            try {
                JsonNode root = MAPPER.readTree(body);
                boolean ok = root.path("ok").asBoolean(false);
                if (!ok) {
                    return new TokenResponse(false, root.path("error").asText("unknown_error"), null, null, null, null);
                }
                String accessToken = root.path("authed_user").path("access_token").asText(null);
                if (accessToken == null || accessToken.isBlank()) {
                    accessToken = root.path("access_token").asText(null);
                }
                JsonNode team = root.path("team");
                String teamId = team.path("id").asText(null);
                String teamName = team.path("name").asText(null);
                String authedUserId = root.path("authed_user").path("id").asText(null);
                return new TokenResponse(true, null, accessToken, teamId, teamName, authedUserId);
            }
            catch (JsonProcessingException e) {
                throw new RuntimeException("Slack token response was not valid JSON", e);
            }
        }
    }
}
