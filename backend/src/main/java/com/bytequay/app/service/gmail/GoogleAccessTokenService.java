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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.CredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.bytequay.app.service.gmail.GmailOAuthService.GMAIL_ACCOUNT_NAME;
import static java.util.Objects.requireNonNull;

/**
 * Trades a stored OAuth refresh token for a fresh access token, with
 * an in-memory cache so a burst of Gmail API calls in the same minute
 * doesn't slam Google's token endpoint. The refresh token itself
 * never moves out of the credentials vault.
 *
 * <p>Google access tokens last 3,600 seconds. We cache them with a
 * margin (~5 minutes shy of expiry) so a token that's about to expire
 * doesn't get handed to a long-running call.
 *
 * <p>OAuth client config (client_id + client_secret) is resolved
 * exactly the way {@link GmailOAuthService} does — same credential
 * row, same env-var fallback — so a single source of truth.
 */
@Service
public class GoogleAccessTokenService
{
    /** Refresh slightly before the actual TTL so a token handed out
     *  here doesn't expire mid-request. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(GoogleAccessTokenService.class);

    private final CredentialService credentialService;
    private final String envClientId;
    private final String envClientSecret;
    private final TokenRefresher refresher;
    private final Clock clock;

    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    @Autowired
    public GoogleAccessTokenService(CredentialService credentialService)
    {
        this(
                credentialService,
                System.getenv(GmailOAuthService.GMAIL_CLIENT_ID_ENV),
                System.getenv(GmailOAuthService.GMAIL_CLIENT_SECRET_ENV),
                new HttpTokenRefresher(),
                Clock.systemUTC());
    }

    GoogleAccessTokenService(
            CredentialService credentialService,
            String envClientId,
            String envClientSecret,
            TokenRefresher refresher,
            Clock clock)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.envClientId = blankToNull(envClientId);
        this.envClientSecret = blankToNull(envClientSecret);
        this.refresher = requireNonNull(refresher, "refresher is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Returns a non-expired access token for {@code email}. Re-uses a
     * cached value when one is available; otherwise hits Google's
     * token endpoint with the stored refresh token. Throws 401 when
     * no refresh token is stored for that email, 502 when Google
     * rejects the refresh.
     */
    public String getAccessToken(String email)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        Instant now = Instant.now(clock);
        CachedToken cached = cache.get(email);
        if (cached != null && cached.expiresAt().minus(EXPIRY_MARGIN).isAfter(now)) {
            return cached.token();
        }
        String refreshToken = credentialService
                .getSecret(CredentialType.ACCOUNT, GMAIL_ACCOUNT_NAME, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(401),
                        "No OAuth refresh token stored for " + email));
        String clientId = resolveClientId().orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "Gmail OAuth client not configured — set client_id."));
        String clientSecret = resolveClientSecret().orElseThrow(() -> new ResponseStatusException(
                HttpStatusCode.valueOf(503),
                "Gmail OAuth client not configured — set client_secret."));
        TokenResponse fresh = refresher.refresh(clientId, clientSecret, refreshToken);
        Instant expiresAt = now.plusSeconds(fresh.expiresInSeconds());
        cache.put(email, new CachedToken(fresh.accessToken(), expiresAt));
        log.debug("Refreshed access token for {} (expires {})", email, expiresAt);
        return fresh.accessToken();
    }

    /** Drops the cached token for {@code email}. Called after a 401
     *  bubbles out of the Gmail API client so the next call retries
     *  with a fresh token rather than the stale cached one. */
    public void invalidate(String email)
    {
        cache.remove(email);
    }

    private Optional<String> resolveClientId()
    {
        return credentialService.get(CredentialType.INTEGRATION, GmailOAuthService.GMAIL_OAUTH_APP_NAME)
                .map(c -> blankToNull(c.label()))
                .or(() -> Optional.ofNullable(envClientId));
    }

    private Optional<String> resolveClientSecret()
    {
        return credentialService.getSecret(CredentialType.INTEGRATION, GmailOAuthService.GMAIL_OAUTH_APP_NAME)
                .map(GoogleAccessTokenService::blankToNull)
                .or(() -> Optional.ofNullable(envClientSecret));
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }

    private record CachedToken(String token, Instant expiresAt) {}

    public record TokenResponse(String accessToken, long expiresInSeconds) {}

    interface TokenRefresher
    {
        TokenResponse refresh(String clientId, String clientSecret, String refreshToken);
    }

    static final class HttpTokenRefresher
            implements TokenRefresher
    {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        @Override
        public TokenResponse refresh(String clientId, String clientSecret, String refreshToken)
        {
            String form = "client_id=" + url(clientId)
                    + "&client_secret=" + url(clientSecret)
                    + "&refresh_token=" + url(refreshToken)
                    + "&grant_type=refresh_token";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GmailOAuthService.TOKEN_ENDPOINT))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp;
            try {
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google token refresh call failed: " + e.getMessage(), e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google token refresh call interrupted", e);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google token refresh failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode body;
            try {
                body = json.readTree(resp.body());
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google token refresh returned non-JSON: " + resp.body(), e);
            }
            String accessToken = body.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "Google token refresh returned no access_token");
            }
            long expiresIn = body.path("expires_in").asLong(3600L);
            return new TokenResponse(accessToken, expiresIn);
        }

        private static String url(String s)
        {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }
}
