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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Google OAuth (Authorization Code Flow) with PKCE for Gmail. Supports
 * multiple connected accounts on day 1 — each account's refresh token
 * lives in its own credential row keyed by
 * {@code (ACCOUNT, "gmail", instanceName=<email>)}, with {@code label} =
 * {@code email} so the renderer can list connected accounts without
 * decrypting the secret.
 *
 * <p>Lifecycle (mirrors {@code GitHubOAuthService} but per-account):
 * <ol>
 *   <li>Renderer calls {@link #issueAuthorizeUrl()} which mints state +
 *       PKCE pair, stashes the verifier under that state, and returns
 *       the URL to open in the system browser.</li>
 *   <li>User picks a Google account, grants {@code gmail.modify}; Google
 *       redirects to {@code bytequay://gmail-oauth-callback?code=…&state=…}.
 *       Electron's {@code open-url} handler forwards code + state to
 *       {@link #exchangeCode(String, String)}.</li>
 *   <li>This service POSTs to Google's token endpoint with the code +
 *       PKCE verifier + client_secret, then resolves the connected
 *       account's email by calling the {@code userinfo} endpoint, and
 *       upserts the refresh token under that email's credential row.
 *       Connecting the same email a second time replaces the row.</li>
 * </ol>
 *
 * <p>Configuration sources, checked in order:
 * <ul>
 *   <li>{@code (INTEGRATION, "gmail-oauth-app")} credential row, where
 *       {@code label} = {@code client_id} and {@code value} =
 *       {@code client_secret}. Lets the user paste their OAuth client
 *       in Settings → Integrations without restarting the backend.</li>
 *   <li>{@code GMAIL_CLIENT_ID} / {@code GMAIL_CLIENT_SECRET} env vars.
 *       Convenient for development.</li>
 * </ul>
 * When neither source supplies values, {@link #isConfigured()} returns
 * false and the renderer falls back to a "configure your Gmail OAuth
 * client" empty state.
 */
@Service
public class GmailOAuthService
{
    /** Credential row that holds the BYO OAuth client_id (label) and
     *  client_secret (value). */
    public static final String GMAIL_OAUTH_APP_NAME = "gmail-oauth-app";

    /** Credential row family that holds per-account refresh tokens.
     *  {@code instanceName} is the connected email address. */
    public static final String GMAIL_ACCOUNT_NAME = "gmail";

    static final String GMAIL_CLIENT_ID_ENV = "GMAIL_CLIENT_ID";
    static final String GMAIL_CLIENT_SECRET_ENV = "GMAIL_CLIENT_SECRET";
    static final String AUTHORIZE_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo";

    /** {@code gmail.modify} covers read + archive + label changes — the
     *  v1 surface from {@code docs/mockups/design/email/SUMMARY.md}.
     *  {@code openid email} is added so userinfo returns the email of
     *  the just-connected account, which we use to key the credential
     *  row. {@code gmail.send} is intentionally absent until reply ships. */
    static final List<String> SCOPES = ImmutableList.of(
            "openid",
            "email",
            "https://www.googleapis.com/auth/gmail.modify");

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(GmailOAuthService.class);

    private final CredentialService credentialService;
    private final String envClientId;
    private final String envClientSecret;
    private final OAuthExchanger exchanger;
    private final Clock clock;
    /** Optional collaborator. Wired by Spring; null in unit tests so the
     *  exchange flow doesn't require a fake access-token service. */
    private final Consumer<String> accessTokenInvalidator;

    private final ConcurrentMap<String, PendingExchange> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public GmailOAuthService(CredentialService credentialService, GoogleAccessTokenService tokens)
    {
        this(
                credentialService,
                System.getenv(GMAIL_CLIENT_ID_ENV),
                System.getenv(GMAIL_CLIENT_SECRET_ENV),
                new HttpExchanger(),
                Clock.systemUTC(),
                tokens::invalidate);
    }

    GmailOAuthService(
            CredentialService credentialService,
            String envClientId,
            String envClientSecret,
            OAuthExchanger exchanger,
            Clock clock)
    {
        this(credentialService, envClientId, envClientSecret, exchanger, clock, ignored -> {});
    }

    GmailOAuthService(
            CredentialService credentialService,
            String envClientId,
            String envClientSecret,
            OAuthExchanger exchanger,
            Clock clock,
            Consumer<String> accessTokenInvalidator)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.envClientId = blankToNull(envClientId);
        this.envClientSecret = blankToNull(envClientSecret);
        this.exchanger = requireNonNull(exchanger, "exchanger is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.accessTokenInvalidator = requireNonNull(accessTokenInvalidator, "accessTokenInvalidator is null");
    }

    public boolean isConfigured()
    {
        return resolveClientId().isPresent() && resolveClientSecret().isPresent();
    }

    /**
     * Mints a fresh CSRF state token + PKCE pair, stashes the verifier
     * (and the loopback {@code redirectUri} the renderer just bound to)
     * under the state, and returns the URL to open in the system
     * browser.
     *
     * <p>Google's "Desktop app" OAuth client type doesn't support
     * custom URI schemes — only HTTP loopback addresses
     * ({@code http://127.0.0.1:<port>/...}). The renderer therefore
     * spins up an ephemeral HTTP listener before each connect, passes
     * its bound URL here, and Google redirects there with the code.
     * The exact same URL must be sent at token-exchange time, which
     * is why we stash it in {@link PendingExchange}.
     */
    public String issueAuthorizeUrl(String redirectUri)
    {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "redirectUri must not be blank");
        }
        String clientId = requireConfigured(resolveClientId(), "client_id");
        purgeExpired();
        String state = randomUrlSafe(32);
        String codeVerifier = randomUrlSafe(64);
        String codeChallenge = sha256Base64Url(codeVerifier);
        pending.put(state, new PendingExchange(Instant.now(clock), codeVerifier, redirectUri));
        return AUTHORIZE_ENDPOINT
                + "?client_id=" + url(clientId)
                + "&redirect_uri=" + url(redirectUri)
                + "&response_type=code"
                + "&scope=" + url(String.join(" ", SCOPES))
                + "&state=" + url(state)
                + "&code_challenge=" + url(codeChallenge)
                + "&code_challenge_method=S256"
                // Force the chooser so the user can pick which Google
                // account to connect, even when their browser already
                // has a default session — required for multi-account.
                + "&prompt=" + url("consent select_account")
                // offline gets us a refresh_token; without it Google only
                // returns short-lived access_tokens, which doesn't survive
                // a backend restart.
                + "&access_type=offline";
    }

    /**
     * Completes the handshake. Validates {@code state}, exchanges the
     * code for tokens, fetches the connected account's email, and
     * upserts the refresh token at {@code (ACCOUNT, "gmail", email)}.
     * Returns the connected email so the renderer can confirm visually.
     */
    public ConnectionInfo exchangeCode(String code, String state)
    {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "code must not be blank");
        }
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "state must not be blank");
        }
        PendingExchange exchange = pending.remove(state);
        if (exchange == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Unknown or expired state token. Restart the connect flow.");
        }
        if (Instant.now(clock).isAfter(exchange.issuedAt().plus(STATE_TTL))) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Authorisation timed out. Restart the connect flow.");
        }
        String clientId = requireConfigured(resolveClientId(), "client_id");
        String clientSecret = requireConfigured(resolveClientSecret(), "client_secret");
        TokenResponse tokens = exchanger.exchange(
                clientId, clientSecret, code, exchange.codeVerifier(), exchange.redirectUri());
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            // Without offline access_type or with a previously-consented
            // app where Google decided not to mint a new refresh token,
            // we end up with only the short-lived access_token. That's
            // not enough to survive a backend restart, so refuse it.
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Google did not return a refresh_token. Revoke ByteQuay at "
                            + "https://myaccount.google.com/permissions and retry.");
        }
        String email = exchanger.fetchEmail(tokens.accessToken());
        credentialService.upsert(
                CredentialType.ACCOUNT,
                GMAIL_ACCOUNT_NAME,
                email,
                tokens.refreshToken(),
                email,
                "Acquired via Gmail OAuth on " + Instant.now(clock));
        // Drop any cached access token derived from a previously-stored
        // refresh token for this email — its scope set is now stale.
        accessTokenInvalidator.accept(email);
        log.info("Gmail OAuth completed for email={}", email);
        return new ConnectionInfo(email);
    }

    /** All currently connected Gmail accounts. The {@code label} carries
     *  the email; the secret column carries the refresh token, which we
     *  never expose here. */
    public List<ConnectionInfo> listAccounts()
    {
        return credentialService.listByTypeAndName(CredentialType.ACCOUNT, GMAIL_ACCOUNT_NAME)
                .stream()
                .map(c -> new ConnectionInfo(c.label() != null ? c.label() : c.instanceName()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Drops the account row keyed by {@code email}, and clears any
     *  cached access token derived from it. Idempotent. */
    public void disconnect(String email)
    {
        accessTokenInvalidator.accept(email);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        credentialService.delete(CredentialType.ACCOUNT, GMAIL_ACCOUNT_NAME, email);
    }

    private Optional<String> resolveClientId()
    {
        return credentialService.get(CredentialType.INTEGRATION, GMAIL_OAUTH_APP_NAME)
                .map(Credential::label)
                .map(GmailOAuthService::blankToNull)
                .or(() -> Optional.ofNullable(envClientId));
    }

    private Optional<String> resolveClientSecret()
    {
        return credentialService.getSecret(CredentialType.INTEGRATION, GMAIL_OAUTH_APP_NAME)
                .map(GmailOAuthService::blankToNull)
                .or(() -> Optional.ofNullable(envClientSecret));
    }

    private static String requireConfigured(Optional<String> value, String which)
    {
        return value.orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "Gmail OAuth client not configured — set " + which
                        + " in env (GMAIL_CLIENT_ID/SECRET) or under "
                        + "(INTEGRATION, gmail-oauth-app) in credentials."));
    }

    private void purgeExpired()
    {
        Instant cutoff = Instant.now(clock).minus(STATE_TTL);
        pending.entrySet().removeIf(e -> e.getValue().issuedAt().isBefore(cutoff));
    }

    private String randomUrlSafe(int byteLength)
    {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64Url(String input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String url(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }

    public record ConnectionInfo(String email) {}

    public record TokenResponse(String accessToken, String refreshToken) {}

    private record PendingExchange(Instant issuedAt, String codeVerifier, String redirectUri) {}

    /** Test seam — extracted so unit tests don't have to mock HTTP. */
    interface OAuthExchanger
    {
        TokenResponse exchange(
                String clientId,
                String clientSecret,
                String code,
                String codeVerifier,
                String redirectUri);

        String fetchEmail(String accessToken);
    }

    /** Default implementation hitting Google directly. */
    static final class HttpExchanger
            implements OAuthExchanger
    {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        @Override
        public TokenResponse exchange(
                String clientId,
                String clientSecret,
                String code,
                String codeVerifier,
                String redirectUri)
        {
            String form = "client_id=" + url(clientId)
                    + "&client_secret=" + url(clientSecret)
                    + "&code=" + url(code)
                    + "&grant_type=authorization_code"
                    + "&redirect_uri=" + url(redirectUri)
                    + "&code_verifier=" + url(codeVerifier);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = sendOrThrow(req);
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Google token exchange failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode body = parse(resp.body());
            if (body.has("error")) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Google OAuth error: " + body.path("error").asText()
                                + " — " + body.path("error_description").asText());
            }
            String accessToken = body.path("access_token").asText(null);
            String refreshToken = body.path("refresh_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Google OAuth returned no access_token");
            }
            return new TokenResponse(accessToken, refreshToken);
        }

        @Override
        public String fetchEmail(String accessToken)
        {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(USERINFO_ENDPOINT))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendOrThrow(req);
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Google userinfo returned " + resp.statusCode() + ": " + resp.body());
            }
            String email = parse(resp.body()).path("email").asText(null);
            if (email == null || email.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Google userinfo returned no email");
            }
            return email;
        }

        private HttpResponse<String> sendOrThrow(HttpRequest req)
        {
            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google OAuth call failed: " + e.getMessage(), e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google OAuth call interrupted", e);
            }
        }

        private JsonNode parse(String body)
        {
            try {
                return json.readTree(body);
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google OAuth returned non-JSON: " + body, e);
            }
        }
    }
}
