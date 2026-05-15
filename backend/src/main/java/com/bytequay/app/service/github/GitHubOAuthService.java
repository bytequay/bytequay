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
package com.bytequay.app.service.github;

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

import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static com.bytequay.app.service.CredentialService.GITHUB_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * GitHub OAuth (Web Application Flow) with PKCE. The token lands in
 * the same credential slot ({@link CredentialService#GITHUB_ACCOUNT_NAME})
 * the user's PAT lives in, so {@code PatResolver.resolve()} (and the
 * rest of the app) doesn't care whether the bearer came from a PAT
 * paste or an OAuth dance.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Renderer calls {@link #issueAuthorizeUrl()} which mints a
 *       state + PKCE pair, stashes the verifier under that state, and
 *       returns the URL to open in the system browser.</li>
 *   <li>User authorises; GitHub redirects to
 *       {@code bytequay://github-oauth-callback?code=…&state=…}.
 *       Electron's {@code open-url} handler forwards code + state to
 *       {@link #exchangeCode(String, String)}.</li>
 *   <li>This service POSTs to GitHub's token endpoint with the code +
 *       PKCE verifier + client_secret, then resolves the new token's
 *       login by calling {@code /user}, then upserts the token into
 *       the credentials store under {@code (ACCOUNT, "github")}.</li>
 * </ol>
 *
 * <p>Configuration sources, checked in order:
 * <ul>
 *   <li>{@code (INTEGRATION, "github-oauth-app")} credential row,
 *       where {@code label} = {@code client_id} and {@code value} =
 *       {@code client_secret}. Lets the user paste their own OAuth
 *       App without restarting the backend.</li>
 *   <li>{@code GITHUB_CLIENT_ID} / {@code GITHUB_CLIENT_SECRET}
 *       environment variables. Convenient for development.</li>
 * </ul>
 * When neither source supplies values, the service is "not configured"
 * — every endpoint short-circuits with 503, the renderer hides the
 * "Sign in with GitHub" button and falls back to PAT.
 */
@Service
public class GitHubOAuthService
{
    /** Credential row that holds the BYO OAuth App client_id (label)
     *  and client_secret (value). */
    public static final String GITHUB_OAUTH_APP_NAME = "github-oauth-app";

    static final String GITHUB_CLIENT_ID_ENV = "GITHUB_CLIENT_ID";
    static final String GITHUB_CLIENT_SECRET_ENV = "GITHUB_CLIENT_SECRET";
    static final String REDIRECT_URI = "bytequay://github-oauth-callback";
    static final String AUTHORIZE_ENDPOINT = "https://github.com/login/oauth/authorize";
    static final String TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    static final String USER_ENDPOINT = "https://api.github.com/user";

    /** Default scopes. Matches the PAT scopes the OnboardingScreen
     *  asks for plus {@code read:org} so org-membership / SAML repos
     *  resolve. {@code repo} covers private repo access — the broad
     *  scope is required because GitHub OAuth Apps don't expose
     *  per-repo granularity (fine-grained PATs do). */
    static final List<String> SCOPES = ImmutableList.of(
            "repo",
            "read:user",
            "read:org");

    /** State tokens older than this are considered expired. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthService.class);

    private final CredentialService credentialService;
    private final String envClientId;
    private final String envClientSecret;
    private final OAuthExchanger exchanger;
    private final Clock clock;

    /** state token → pending exchange (issued time + PKCE verifier).
     *  Cleaned up lazily on validation + on-demand expiry. */
    private final ConcurrentMap<String, PendingExchange> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public GitHubOAuthService(CredentialService credentialService)
    {
        this(
                credentialService,
                System.getenv(GITHUB_CLIENT_ID_ENV),
                System.getenv(GITHUB_CLIENT_SECRET_ENV),
                new HttpExchanger(),
                Clock.systemUTC());
    }

    GitHubOAuthService(
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

    /** True when both client_id and client_secret resolve from somewhere. */
    public boolean isConfigured()
    {
        return resolveClientId().isPresent() && resolveClientSecret().isPresent();
    }

    /**
     * Mints a fresh CSRF state token + PKCE pair, stashes the verifier
     * under the state, and returns the URL to open in the system
     * browser. Throws 503 if the OAuth App isn't configured.
     */
    public String issueAuthorizeUrl()
    {
        String clientId = requireConfigured(resolveClientId(), "client_id");
        // Drop any pending entries that have aged out, so a long-running
        // process doesn't accumulate dead state.
        purgeExpired();
        String state = randomUrlSafe(32);
        String codeVerifier = randomUrlSafe(64);
        String codeChallenge = sha256Base64Url(codeVerifier);
        pending.put(state, new PendingExchange(Instant.now(clock), codeVerifier));
        return AUTHORIZE_ENDPOINT
                + "?client_id=" + url(clientId)
                + "&redirect_uri=" + url(REDIRECT_URI)
                + "&scope=" + url(String.join(" ", SCOPES))
                + "&state=" + url(state)
                + "&code_challenge=" + url(codeChallenge)
                + "&code_challenge_method=S256"
                // GitHub-specific: prevents the consent screen from
                // remembering the previous account when the user has
                // multiple GitHub logins on the same browser.
                + "&allow_signup=false";
    }

    /**
     * Completes the handshake. Validates {@code state} against the
     * pending map, posts to GitHub's token endpoint with the code and
     * PKCE verifier, fetches the {@code /user} endpoint to discover
     * the login, then upserts the token into the credentials store.
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
        String accessToken = exchanger.exchange(clientId, clientSecret, code, exchange.codeVerifier());
        String login = exchanger.fetchLogin(accessToken);
        // Token writes to the same slot the PAT path uses; downstream
        // (PatResolver, GitHubClient) doesn't know or care about origin.
        credentialService.upsert(
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                DEFAULT_INSTANCE_NAME,
                accessToken,
                login,
                "Acquired via GitHub OAuth on " + Instant.now(clock));
        log.info("GitHub OAuth completed for login={}", login);
        return new ConnectionInfo(login);
    }

    /** Returns the connection info for the currently-stored token, if
     *  the slot exists. The {@code label} carries the GitHub login. */
    public Optional<ConnectionInfo> getConnection()
    {
        return credentialService.get(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME)
                .map(c -> new ConnectionInfo(c.label()));
    }

    /** Drops the stored token. Idempotent. */
    public void disconnect()
    {
        credentialService.delete(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME, DEFAULT_INSTANCE_NAME);
    }

    private Optional<String> resolveClientId()
    {
        return credentialService.get(CredentialType.INTEGRATION, GITHUB_OAUTH_APP_NAME)
                .map(Credential::label)
                .map(GitHubOAuthService::blankToNull)
                .or(() -> Optional.ofNullable(envClientId));
    }

    private Optional<String> resolveClientSecret()
    {
        return credentialService.getSecret(CredentialType.INTEGRATION, GITHUB_OAUTH_APP_NAME)
                .map(GitHubOAuthService::blankToNull)
                .or(() -> Optional.ofNullable(envClientSecret));
    }

    private static String requireConfigured(Optional<String> value, String which)
    {
        return value.orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "GitHub OAuth App not configured — set " + which
                        + " in env (GITHUB_CLIENT_ID/SECRET) or under "
                        + "(INTEGRATION, github-oauth-app) in credentials."));
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

    public record ConnectionInfo(String login) {}

    private record PendingExchange(Instant issuedAt, String codeVerifier) {}

    /** Test seam — extracted so unit tests don't have to mock HTTP. */
    interface OAuthExchanger
    {
        String exchange(String clientId, String clientSecret, String code, String codeVerifier);

        String fetchLogin(String accessToken);
    }

    /** Default implementation hitting GitHub directly. */
    static final class HttpExchanger
            implements OAuthExchanger
    {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        @Override
        public String exchange(String clientId, String clientSecret, String code, String codeVerifier)
        {
            String form = "client_id=" + url(clientId)
                    + "&client_secret=" + url(clientSecret)
                    + "&code=" + url(code)
                    + "&redirect_uri=" + url(REDIRECT_URI)
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
                        "GitHub token exchange failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode body = parse(resp.body());
            if (body.has("error")) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "GitHub OAuth error: " + body.path("error").asText()
                                + " — " + body.path("error_description").asText());
            }
            String token = body.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "GitHub OAuth returned no access_token");
            }
            return token;
        }

        @Override
        public String fetchLogin(String accessToken)
        {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(USER_ENDPOINT))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendOrThrow(req);
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "GitHub /user returned " + resp.statusCode() + ": " + resp.body());
            }
            return parse(resp.body()).path("login").asText("unknown");
        }

        private HttpResponse<String> sendOrThrow(HttpRequest req)
        {
            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "GitHub OAuth call failed: " + e.getMessage(), e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "GitHub OAuth call interrupted", e);
            }
        }

        private JsonNode parse(String body)
        {
            try {
                return json.readTree(body);
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "GitHub OAuth returned non-JSON: " + body, e);
            }
        }
    }
}
