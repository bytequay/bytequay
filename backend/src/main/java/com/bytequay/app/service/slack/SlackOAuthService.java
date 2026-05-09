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
import org.springframework.beans.factory.annotation.Value;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Slack OAuth (v2) handshake. Supports two flows side-by-side:
 *
 * <ul>
 *   <li><b>PKCE (preferred, slice 2d).</b> Embedded {@code client_id}
 *       resolved from the {@code bytequay.slack.pkce-client-id} Spring
 *       property — public, safe to bundle. The handshake mints a fresh
 *       {@code code_verifier} per state, sends its SHA-256
 *       {@code code_challenge} to Slack, and exchanges the code +
 *       verifier (no client secret) on callback. PKCE-issued tokens
 *       come with a refresh token that auto-rotates every 30 days under
 *       Slack's custom-URI redirect policy; the bundle is persisted as
 *       JSON in the credential vault and {@link #getValidAccessToken()}
 *       refreshes lazily before expiry.</li>
 *   <li><b>BYO (legacy, slice 2c).</b> Resolves {@code client_id} +
 *       {@code client_secret} at call time from the credentials vault
 *       under {@code (INTEGRATION, "slack-oauth-app")}, with a fallback
 *       to the {@code SLACK_CLIENT_ID} / {@code SLACK_CLIENT_SECRET}
 *       env vars. Each end user has to register their own Slack app and
 *       paste these values into Settings → Integrations. Existing
 *       BYO connections continue to work after the PKCE rollout — see
 *       docs/mockups/design/slack/pkce-migration.md.</li>
 * </ul>
 *
 * <p>If both flows are configured, PKCE is preferred for new
 * connections; existing tokens are read back according to whichever
 * flow originally minted them (see {@link TokenBundle#parse}).
 *
 * <p>Flow:
 * <ol>
 *   <li>Renderer calls {@link #issueAuthorizeUrl()}; we mint a CSRF
 *       state token, store the per-state metadata (flow + verifier on
 *       PKCE), and build the Slack authorize URL.</li>
 *   <li>The user authorises in their browser; Slack redirects back to
 *       the {@code bytequay://slack-oauth-callback} custom scheme.</li>
 *   <li>Electron's {@code open-url} handler forwards the {@code code}
 *       and {@code state} to {@link #exchangeCode(String, String)},
 *       which validates the state, hits {@code oauth.v2.access} with
 *       the right shape for the recorded flow, and persists the user
 *       token under {@code (INTEGRATION, "slack")}.</li>
 * </ol>
 *
 * <p>Credential layout for the BYO Slack-app config row:
 * <ul>
 *   <li>{@code Credential.label} — the public {@code client_id}.</li>
 *   <li>{@code Credential.value} — the encrypted {@code client_secret}.</li>
 * </ul>
 *
 * <p>Credential layout for the persisted user-token row:
 * <ul>
 *   <li>{@code Credential.label} — the {@link ConnectionInfo} string.</li>
 *   <li>{@code Credential.value} — for BYO, the bare access token; for
 *       PKCE, a JSON {@link TokenBundle} with access / refresh / expiry.</li>
 * </ul>
 *
 * @see <a href="docs/mockups/design/slack/scopes.md">scopes.md</a>
 *      for the pinned user-token scope list.
 * @see <a href="docs/mockups/design/slack/pkce-migration.md">pkce-migration.md</a>
 *      for the dual-mode rollout plan.
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
    /** Lazy refresh kicks in this far ahead of {@code expiresAt} so a
     *  request that needs the token doesn't race the rotation. */
    private static final Duration REFRESH_THRESHOLD = Duration.ofMinutes(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(SlackOAuthService.class);

    private final CredentialService credentialService;
    private final String pkceClientId;
    private final String envClientId;
    private final String envClientSecret;
    private final OAuthExchanger exchanger;
    private final Clock clock;

    /** State token → metadata for that handshake. PKCE rows carry the
     *  per-state {@code code_verifier} that Slack will demand back at
     *  {@code oauth.v2.access} time; BYO rows just record the issuance
     *  timestamp and flow. Cleaned up lazily on validation + whenever
     *  {@link #issueAuthorizeUrl()} runs. */
    private final ConcurrentMap<String, IssuedState> issuedStates = new ConcurrentHashMap<>();

    @Autowired
    public SlackOAuthService(
            CredentialService credentialService,
            @Value("${bytequay.slack.pkce-client-id:}") String pkceClientId)
    {
        this(
                credentialService,
                pkceClientId,
                System.getenv(SLACK_CLIENT_ID_ENV),
                System.getenv(SLACK_CLIENT_SECRET_ENV),
                new HttpOAuthExchanger(),
                Clock.systemUTC());
    }

    /** Test seam — explicit pkce client_id + BYO env-fallback values
     *  + injectable exchanger / clock. Pass null for {@code pkceClientId}
     *  to exercise the BYO-only flow. */
    SlackOAuthService(
            CredentialService credentialService,
            String pkceClientId,
            String envClientId,
            String envClientSecret,
            OAuthExchanger exchanger,
            Clock clock)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.pkceClientId = blankToNull(pkceClientId);
        this.envClientId = blankToNull(envClientId);
        this.envClientSecret = blankToNull(envClientSecret);
        this.exchanger = requireNonNull(exchanger, "exchanger is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** True iff at least one flow (PKCE or BYO) resolves a usable
     *  client_id. Reads short-circuit with 503 otherwise. */
    public boolean isConfigured()
    {
        return isPkceConfigured() || isByoConfigured();
    }

    private boolean isPkceConfigured()
    {
        return pkceClientId != null;
    }

    private boolean isByoConfigured()
    {
        return resolveByoClientId() != null && resolveByoClientSecret() != null;
    }

    /** Constructs the Slack authorize URL the renderer should open in
     *  the system browser. Mints a fresh CSRF state token (and, on
     *  PKCE, a code_verifier paired to that state) in the process —
     *  keep that round-trip in mind when designing tests. PKCE wins
     *  when both flows are configured. */
    public String issueAuthorizeUrl()
    {
        sweepExpiredStates();
        if (isPkceConfigured()) {
            return issuePkceAuthorizeUrl();
        }
        if (isByoConfigured()) {
            return issueByoAuthorizeUrl();
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "Slack OAuth is not configured — set client_id / client_secret in Settings → Integrations");
    }

    private String issuePkceAuthorizeUrl()
    {
        String state = generateState();
        String codeVerifier = generateCodeVerifier();
        issuedStates.put(state, IssuedState.pkce(codeVerifier, clock.instant()));
        String codeChallenge = sha256Base64Url(codeVerifier);
        return AUTHORIZE_ENDPOINT
                + "?client_id=" + urlEncode(pkceClientId)
                + "&user_scope=" + urlEncode(String.join(",", USER_SCOPES))
                + "&redirect_uri=" + urlEncode(REDIRECT_URI)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);
    }

    private String issueByoAuthorizeUrl()
    {
        String state = generateState();
        issuedStates.put(state, IssuedState.byo(clock.instant()));
        return AUTHORIZE_ENDPOINT
                + "?client_id=" + urlEncode(requireNonNull(resolveByoClientId(), "byo client_id"))
                + "&user_scope=" + urlEncode(String.join(",", USER_SCOPES))
                + "&redirect_uri=" + urlEncode(REDIRECT_URI)
                + "&state=" + urlEncode(state);
    }

    /** Exchanges an OAuth code for a user token and persists it. The
     *  flow (PKCE vs BYO) is read off the recorded state so the caller
     *  doesn't need to know — Slack's callback URL is the same shape
     *  for both. A stale or unknown state triggers a 400. */
    public ConnectionInfo exchangeCode(String code, String state)
    {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth code missing");
        }
        IssuedState issued = consumeState(state);

        TokenResponse response = switch (issued.flow()) {
            case PKCE -> exchangePkce(code, issued.codeVerifier());
            case BYO -> exchangeByo(code);
        };

        ConnectionInfo info = new ConnectionInfo(response.teamId(), response.teamName(), response.authedUserId());
        String storedValue = switch (issued.flow()) {
            case PKCE -> TokenBundle.pkce(
                    response.accessToken(),
                    response.refreshToken(),
                    expiryFromResponse(response, clock.instant())).serialize();
            case BYO -> response.accessToken();
        };
        credentialService.upsert(
                CredentialType.INTEGRATION,
                SLACK_USER_TOKEN_NAME,
                DEFAULT_INSTANCE_NAME,
                storedValue,
                info.label(),
                "Connected via Slack OAuth (" + issued.flow().name().toLowerCase(Locale.ROOT) + ") on " + clock.instant());
        log.info("Slack workspace connected via {}: {} ({})", issued.flow(), info.teamName(), info.teamId());
        return info;
    }

    private TokenResponse exchangePkce(String code, String codeVerifier)
    {
        if (pkceClientId == null) {
            // Defensive: state recorded as PKCE but the property has been
            // unset between issue and exchange — fail loudly rather than
            // dropping into the BYO path with a verifier in hand.
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack PKCE client_id is no longer configured");
        }
        TokenResponse response;
        try {
            response = exchanger.exchangePkce(pkceClientId, codeVerifier, code, REDIRECT_URI);
        }
        catch (RuntimeException e) {
            log.warn("Slack oauth.v2.access (PKCE) call failed: {}", e.getMessage());
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
        return response;
    }

    private TokenResponse exchangeByo(String code)
    {
        String resolvedClientId = requireConfiguredByoClientId();
        String resolvedClientSecret = requireConfiguredByoClientSecret();
        TokenResponse response;
        try {
            response = exchanger.exchange(resolvedClientId, resolvedClientSecret, code, REDIRECT_URI);
        }
        catch (RuntimeException e) {
            log.warn("Slack oauth.v2.access (BYO) call failed: {}", e.getMessage());
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
        return response;
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

    /**
     * Returns a usable access token for the connected workspace,
     * refreshing it via {@code oauth.v2.access?grant_type=refresh_token}
     * when a PKCE row is within {@link #REFRESH_THRESHOLD} of expiry.
     * Empty if no workspace is connected. BYO rows pass through
     * untouched — those tokens never expire.
     *
     * <p>Plumbed for slice 4+ callers (inbox, channel feeds) — none of
     * the current endpoints need the token in-hand, but the rotation
     * lifecycle has to land with the rest of Phase 1 so a stored token
     * can survive past 30 days.
     */
    public Optional<String> getValidAccessToken()
    {
        Optional<Credential> credential = credentialService.get(CredentialType.INTEGRATION, SLACK_USER_TOKEN_NAME);
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> rawValue = credentialService.getSecret(CredentialType.INTEGRATION, SLACK_USER_TOKEN_NAME);
        if (rawValue.isEmpty()) {
            return Optional.empty();
        }
        TokenBundle bundle = TokenBundle.parse(rawValue.get());
        if (bundle.flow() == OAuthFlow.BYO) {
            return Optional.of(bundle.accessToken());
        }
        Instant now = clock.instant();
        if (bundle.expiresAt() == null || now.isBefore(bundle.expiresAt().minus(REFRESH_THRESHOLD))) {
            return Optional.of(bundle.accessToken());
        }
        if (pkceClientId == null || bundle.refreshToken() == null) {
            // Can't refresh without the embedded client_id (e.g. property
            // was removed) or a refresh token. Hand back what we have and
            // let the caller see the eventual 401 from Slack.
            return Optional.of(bundle.accessToken());
        }
        TokenResponse response;
        try {
            response = exchanger.refreshPkce(pkceClientId, bundle.refreshToken());
        }
        catch (RuntimeException e) {
            log.warn("Slack PKCE refresh threw: {}", e.getMessage());
            return Optional.of(bundle.accessToken());
        }
        if (!response.ok()) {
            log.warn("Slack PKCE refresh rejected: {}", response.error());
            return Optional.of(bundle.accessToken());
        }
        TokenBundle refreshed = TokenBundle.pkce(
                response.accessToken(),
                // Slack issues a fresh refresh_token on every rotation; fall
                // back to the existing one if (defensively) the response
                // omits it.
                response.refreshToken() != null ? response.refreshToken() : bundle.refreshToken(),
                expiryFromResponse(response, now));
        credentialService.upsert(
                CredentialType.INTEGRATION,
                SLACK_USER_TOKEN_NAME,
                DEFAULT_INSTANCE_NAME,
                refreshed.serialize(),
                credential.get().label(),
                "Refreshed via Slack PKCE on " + now);
        return Optional.of(refreshed.accessToken());
    }

    private static Instant expiryFromResponse(TokenResponse response, Instant now)
    {
        return response.expiresIn() > 0 ? now.plusSeconds(response.expiresIn()) : null;
    }

    /** Vault row first (label = client_id), {@code SLACK_CLIENT_ID} env-var fallback. */
    private String resolveByoClientId()
    {
        Optional<Credential> row = credentialService.get(CredentialType.INTEGRATION, SLACK_OAUTH_APP_NAME);
        String fromVault = row.map(Credential::label).orElse(null);
        return blankToNull(fromVault) != null ? fromVault.trim() : envClientId;
    }

    /** Vault row first (encrypted value = client_secret), {@code SLACK_CLIENT_SECRET}
     *  env-var fallback. */
    private String resolveByoClientSecret()
    {
        String fromVault = credentialService.getSecret(CredentialType.INTEGRATION, SLACK_OAUTH_APP_NAME)
                .orElse(null);
        return blankToNull(fromVault) != null ? fromVault.trim() : envClientSecret;
    }

    private String requireConfiguredByoClientId()
    {
        String value = resolveByoClientId();
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack BYO OAuth is not configured — set client_id / client_secret in Settings → Integrations");
        }
        return value;
    }

    private String requireConfiguredByoClientSecret()
    {
        String value = resolveByoClientSecret();
        if (value == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack BYO OAuth is not configured — set client_id / client_secret in Settings → Integrations");
        }
        return value;
    }

    private IssuedState consumeState(String state)
    {
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth state missing");
        }
        IssuedState issued = issuedStates.remove(state);
        if (issued == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Unknown OAuth state");
        }
        if (Duration.between(issued.issuedAt(), clock.instant()).compareTo(STATE_TTL) > 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "OAuth state expired");
        }
        return issued;
    }

    private void sweepExpiredStates()
    {
        Instant cutoff = clock.instant().minus(STATE_TTL);
        issuedStates.entrySet().removeIf(e -> e.getValue().issuedAt().isBefore(cutoff));
    }

    private static String generateState()
    {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 7636 — between 43 and 128 chars of URL-safe random. We use
     *  64 bytes of entropy → 86 base64url chars, comfortably in range. */
    private static String generateCodeVerifier()
    {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 7636 — code_challenge = base64url(sha256(code_verifier)). */
    static String sha256Base64Url(String codeVerifier)
    {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        }
        catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE we'll ever run on.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /** Distinguishes the two OAuth flows we support side-by-side. */
    public enum OAuthFlow
    {
        PKCE,
        BYO
    }

    /** Per-state metadata recorded at {@link #issueAuthorizeUrl} time
     *  and read back at {@link #exchangeCode} time. */
    private record IssuedState(OAuthFlow flow, String codeVerifier, Instant issuedAt)
    {
        static IssuedState pkce(String codeVerifier, Instant issuedAt)
        {
            return new IssuedState(OAuthFlow.PKCE, requireNonNull(codeVerifier, "codeVerifier"), issuedAt);
        }

        static IssuedState byo(Instant issuedAt)
        {
            return new IssuedState(OAuthFlow.BYO, null, issuedAt);
        }
    }

    /** Persistent shape of the user-token credential value. BYO rows
     *  store the bare access token (legacy); PKCE rows store a JSON
     *  envelope so the refresh token + expiry survive restarts. */
    record TokenBundle(OAuthFlow flow, String accessToken, String refreshToken, Instant expiresAt)
    {
        static TokenBundle pkce(String accessToken, String refreshToken, Instant expiresAt)
        {
            return new TokenBundle(OAuthFlow.PKCE, requireNonNull(accessToken, "accessToken"), refreshToken, expiresAt);
        }

        static TokenBundle byo(String accessToken)
        {
            return new TokenBundle(OAuthFlow.BYO, requireNonNull(accessToken, "accessToken"), null, null);
        }

        String serialize()
        {
            if (flow == OAuthFlow.BYO) {
                return accessToken;
            }
            try {
                return MAPPER.writeValueAsString(new SerializedPayload(
                        "pkce",
                        accessToken,
                        refreshToken,
                        expiresAt != null ? expiresAt.toString() : null));
            }
            catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to encode PKCE token bundle", e);
            }
        }

        static TokenBundle parse(String raw)
        {
            // PKCE rows always start with `{`. Anything else (legacy plain
            // string, xoxp- token, etc.) is treated as BYO so existing
            // connections keep working without a migration.
            if (raw == null || raw.isEmpty() || !raw.startsWith("{")) {
                return byo(raw == null ? "" : raw);
            }
            try {
                SerializedPayload payload = MAPPER.readValue(raw, SerializedPayload.class);
                if (!"pkce".equals(payload.flow())) {
                    return byo(raw);
                }
                Instant expiresAt = payload.expiresAt() != null && !payload.expiresAt().isBlank()
                        ? Instant.parse(payload.expiresAt())
                        : null;
                return pkce(payload.access(), payload.refresh(), expiresAt);
            }
            catch (Exception e) {
                // Treat malformed JSON as a BYO bare token rather than
                // erroring — this is the credential-vault decode path,
                // and a 500 here would lock the user out of the Slack
                // tab. Worst case the bare token works and the next
                // refresh quietly skips.
                log.warn("Failed to parse PKCE token bundle, falling back to bare BYO token: {}", e.getMessage());
                return byo(raw);
            }
        }
    }

    /** Wire format for the JSON envelope persisted in the credential vault. */
    record SerializedPayload(String flow, String access, String refresh, String expiresAt) {}

    /** Shape of the {@code oauth.v2.access} response we depend on.
     *  PKCE responses populate {@code refreshToken} + {@code expiresIn};
     *  BYO responses leave both null/0. */
    public record TokenResponse(
            boolean ok,
            String error,
            String accessToken,
            String teamId,
            String teamName,
            String authedUserId,
            String refreshToken,
            long expiresIn)
    {
        /** Six-arg constructor preserved for the BYO test surface. PKCE
         *  callers use the canonical eight-arg constructor. */
        public TokenResponse(boolean ok, String error, String accessToken, String teamId, String teamName, String authedUserId)
        {
            this(ok, error, accessToken, teamId, teamName, authedUserId, null, 0);
        }
    }

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
     *  pass a fake. The {@code exchange} method is the BYO / abstract one;
     *  PKCE callers and refresh callers use the corresponding default
     *  methods, which throw by default so a BYO-only fake stays simple. */
    @FunctionalInterface
    public interface OAuthExchanger
    {
        TokenResponse exchange(String clientId, String clientSecret, String code, String redirectUri);

        default TokenResponse exchangePkce(String clientId, String codeVerifier, String code, String redirectUri)
        {
            throw new UnsupportedOperationException("PKCE exchange not implemented in this exchanger");
        }

        default TokenResponse refreshPkce(String clientId, String refreshToken)
        {
            throw new UnsupportedOperationException("PKCE refresh not implemented in this exchanger");
        }
    }

    /** Real HTTP implementation that POSTs to {@link #TOKEN_ENDPOINT}. */
    static final class HttpOAuthExchanger
            implements OAuthExchanger
    {
        @Override
        public TokenResponse exchange(String clientId, String clientSecret, String code, String redirectUri)
        {
            String body = "client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri);
            return post(body);
        }

        @Override
        public TokenResponse exchangePkce(String clientId, String codeVerifier, String code, String redirectUri)
        {
            // PKCE: client_secret is REPLACED by code_verifier — the public
            // client identifies itself via the verifier matching the
            // earlier challenge, no shared secret required.
            String body = "client_id=" + urlEncode(clientId)
                    + "&code_verifier=" + urlEncode(codeVerifier)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri);
            return post(body);
        }

        @Override
        public TokenResponse refreshPkce(String clientId, String refreshToken)
        {
            // grant_type=refresh_token is the standard OAuth2 rotation
            // shape; Slack mirrors it. No client_secret on PKCE clients.
            String body = "client_id=" + urlEncode(clientId)
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + urlEncode(refreshToken);
            return post(body);
        }

        private static TokenResponse post(String body)
        {
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
                // PKCE rotation surfaces refresh_token + expires_in on
                // the authed_user payload (matching Slack's shape) and
                // optionally at the top level for refresh-grant
                // responses. Read both to be tolerant.
                String refreshToken = root.path("authed_user").path("refresh_token").asText(null);
                if (refreshToken == null || refreshToken.isBlank()) {
                    refreshToken = root.path("refresh_token").asText(null);
                }
                long expiresIn = root.path("authed_user").path("expires_in").asLong(0);
                if (expiresIn == 0) {
                    expiresIn = root.path("expires_in").asLong(0);
                }
                return new TokenResponse(true, null, accessToken, teamId, teamName, authedUserId,
                        blankToNull(refreshToken), expiresIn);
            }
            catch (JsonProcessingException e) {
                throw new RuntimeException("Slack token response was not valid JSON", e);
            }
        }
    }
}
