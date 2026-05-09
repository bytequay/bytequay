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
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.bytequay.app.service.slack.SlackOAuthService.OAuthExchanger;
import com.bytequay.app.service.slack.SlackOAuthService.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSlackOAuthService
{
    private static final TokenResponse OK_RESPONSE = new TokenResponse(
            true,
            null,
            "xoxp-test-token",
            "T123",
            "Acme Corp",
            "U999");

    @Test
    void testNotConfiguredShortCircuits()
    {
        SlackOAuthService service = build(null, null, (cid, sec, code, uri) -> { throw new AssertionError("exchanger must not be called"); }, Clock.systemUTC());

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(service::issueAuthorizeUrl)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
        // exchangeCode without a known state surfaces as a 400 (Unknown state)
        // before it can reach a configured-flow check, since the state token
        // is the only thing that decides which flow to dispatch on.
        assertThatThrownBy(() -> service.exchangeCode("c", "s"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown OAuth state");
    }

    @Test
    void testIssueAuthorizeUrlContainsScopesAndRedirect()
    {
        SlackOAuthService service = build("client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, Clock.systemUTC());

        String url = service.issueAuthorizeUrl();

        assertThat(url).startsWith(SlackOAuthService.AUTHORIZE_ENDPOINT + "?");
        assertThat(url).contains("client_id=client");
        assertThat(url).contains("redirect_uri=bytequay%3A%2F%2Fslack-oauth-callback");
        for (String scope : SlackOAuthService.USER_SCOPES) {
            assertThat(url).contains(scope.replace(":", "%3A"));
        }
        assertThat(url).contains("&state=");
    }

    @Test
    void testExchangeCodePersistsToken()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        SlackOAuthService service = build(store, "client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, Clock.systemUTC());

        String state = stateFromUrl(service.issueAuthorizeUrl());

        ConnectionInfo info = service.exchangeCode("auth-code", state);

        assertThat(info.teamId()).isEqualTo("T123");
        assertThat(info.teamName()).isEqualTo("Acme Corp");
        assertThat(info.authedUserId()).isEqualTo("U999");
        assertThat(store.findSecret(CredentialType.INTEGRATION, SlackOAuthService.SLACK_USER_TOKEN_NAME))
                .contains("xoxp-test-token");
    }

    @Test
    void testGetConnectionRoundTripsAfterExchange()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        SlackOAuthService service = build(store, "client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, Clock.systemUTC());

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));

        Optional<ConnectionInfo> info = service.getConnection();
        assertThat(info).isPresent();
        assertThat(info.get().teamId()).isEqualTo("T123");
        assertThat(info.get().teamName()).isEqualTo("Acme Corp");
        assertThat(info.get().authedUserId()).isEqualTo("U999");
    }

    @Test
    void testExchangeCodeRejectsUnknownState()
    {
        SlackOAuthService service = build("client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, Clock.systemUTC());

        // Don't call issueAuthorizeUrl — this state was never minted.
        assertThatThrownBy(() -> service.exchangeCode("auth-code", "fabricated-state"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown OAuth state");
    }

    @Test
    void testExchangeCodeRejectsExpiredState()
    {
        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-05-08T00:00:00Z"));
        SlackOAuthService service = build("client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, clock);
        String state = stateFromUrl(service.issueAuthorizeUrl());

        // 11 minutes later — past the 10-minute STATE_TTL.
        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.exchangeCode("auth-code", state))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void testExchangeCodePropagatesSlackError()
    {
        OAuthExchanger errorExchanger = (cid, sec, code, uri) -> new TokenResponse(false, "invalid_code", null, null, null, null);
        SlackOAuthService service = build("client", "secret", errorExchanger, Clock.systemUTC());

        assertThatThrownBy(() -> service.exchangeCode("bad-code", stateFromUrl(service.issueAuthorizeUrl())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_code");
    }

    @Test
    void testDisconnectClearsCredential()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        SlackOAuthService service = build(store, "client", "secret", (cid, sec, code, uri) -> OK_RESPONSE, Clock.systemUTC());

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));
        assertThat(service.getConnection()).isPresent();

        service.disconnect();

        assertThat(service.getConnection()).isEmpty();
        assertThat(store.findSecret(CredentialType.INTEGRATION, SlackOAuthService.SLACK_USER_TOKEN_NAME)).isEmpty();
    }

    @Test
    void testVaultRowConfiguresService()
    {
        // No env fallback — only the vault row. Mirrors the BYO Settings flow:
        // user pastes client_id (label) + client_secret (value) and the
        // service should pick them up at call time without a restart.
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        store.upsert(
                CredentialType.INTEGRATION,
                SlackOAuthService.SLACK_OAUTH_APP_NAME,
                "default",
                "vault-secret",
                "vault-client",
                null);
        SlackOAuthService service = build(store, null, null, (cid, sec, code, uri) -> {
            assertThat(cid).isEqualTo("vault-client");
            assertThat(sec).isEqualTo("vault-secret");
            return OK_RESPONSE;
        }, Clock.systemUTC());

        assertThat(service.isConfigured()).isTrue();
        String url = service.issueAuthorizeUrl();
        assertThat(url).contains("client_id=vault-client");
        service.exchangeCode("auth-code", stateFromUrl(url));
    }

    @Test
    void testVaultRowOverridesEnvFallback()
    {
        // Env-fallback values are present but the vault row should win
        // — keeps a stale env var from masking a freshly pasted secret.
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        store.upsert(
                CredentialType.INTEGRATION,
                SlackOAuthService.SLACK_OAUTH_APP_NAME,
                "default",
                "vault-secret",
                "vault-client",
                null);
        SlackOAuthService service = build(store, "env-client", "env-secret", (cid, sec, code, uri) -> {
            assertThat(cid).isEqualTo("vault-client");
            assertThat(sec).isEqualTo("vault-secret");
            return OK_RESPONSE;
        }, Clock.systemUTC());

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));
    }

    @Test
    void testEnvFallbackUsedWhenVaultEmpty()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        SlackOAuthService service = build(store, "env-client", "env-secret", (cid, sec, code, uri) -> {
            assertThat(cid).isEqualTo("env-client");
            assertThat(sec).isEqualTo("env-secret");
            return OK_RESPONSE;
        }, Clock.systemUTC());

        assertThat(service.isConfigured()).isTrue();
        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));
    }

    @Test
    void testVaultRowMissingSecretFallsBackToEnv()
    {
        // A row with only client_id (label) but no value is treated as
        // half-configured — the env secret fills the gap. This matches
        // a partial save where the user pasted only the client_id.
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        store.upsert(
                CredentialType.INTEGRATION,
                SlackOAuthService.SLACK_OAUTH_APP_NAME,
                "default",
                "",
                "vault-client",
                null);
        SlackOAuthService service = build(store, "env-client", "env-secret", (cid, sec, code, uri) -> {
            assertThat(cid).isEqualTo("vault-client");
            assertThat(sec).isEqualTo("env-secret");
            return OK_RESPONSE;
        }, Clock.systemUTC());

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));
    }

    // ── PKCE flow ──────────────────────────────────────────────────────

    @Test
    void testPkceAuthorizeUrlContainsChallengeAndS256()
    {
        SlackOAuthService service = buildPkce(
                new InMemoryCredentialStore(),
                "pkce-client",
                new RecordingExchanger(),
                Clock.systemUTC());

        String url = service.issueAuthorizeUrl();

        assertThat(url).contains("client_id=pkce-client");
        assertThat(url).contains("code_challenge=");
        assertThat(url).contains("code_challenge_method=S256");
        assertThat(url).contains("&state=");
        for (String scope : SlackOAuthService.USER_SCOPES) {
            assertThat(url).contains(scope.replace(":", "%3A"));
        }
    }

    @Test
    void testPkcePreferredOverByoWhenBothConfigured()
    {
        // Both PKCE client_id AND BYO env vars are set. issueAuthorizeUrl
        // should pick PKCE (it's the preferred flow for new connections),
        // detectable by the presence of code_challenge.
        SlackOAuthService service = buildWithPkce(
                new InMemoryCredentialStore(),
                "pkce-client",
                "byo-env-client",
                "byo-env-secret",
                new RecordingExchanger(),
                Clock.systemUTC());

        String url = service.issueAuthorizeUrl();

        assertThat(url).contains("client_id=pkce-client");
        assertThat(url).contains("code_challenge=");
        assertThat(url).doesNotContain("client_id=byo-env-client");
    }

    @Test
    void testPkceExchangePersistsJsonEnvelope()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        RecordingExchanger exchanger = new RecordingExchanger();
        exchanger.pkceResponse = new TokenResponse(
                true, null, "xoxp-pkce-token", "T123", "Acme Corp", "U999",
                "xoxr-refresh-token", 3600);
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T00:00:00Z"), ZoneOffset.UTC);
        SlackOAuthService service = buildPkce(store, "pkce-client", exchanger, clock);

        String state = stateFromUrl(service.issueAuthorizeUrl());
        ConnectionInfo info = service.exchangeCode("auth-code", state);

        assertThat(info.teamId()).isEqualTo("T123");
        assertThat(exchanger.lastPkceClientId).isEqualTo("pkce-client");
        assertThat(exchanger.lastPkceCodeVerifier).isNotBlank();
        // The vault row holds a JSON envelope, not a bare token, on PKCE.
        Optional<String> raw = store.findSecret(CredentialType.INTEGRATION, SlackOAuthService.SLACK_USER_TOKEN_NAME);
        assertThat(raw).isPresent();
        assertThat(raw.get())
                .startsWith("{")
                .contains("\"flow\":\"pkce\"")
                .contains("\"access\":\"xoxp-pkce-token\"")
                .contains("\"refresh\":\"xoxr-refresh-token\"")
                .contains("\"expiresAt\":\"2026-05-10T01:00:00Z\"");
    }

    @Test
    void testGetValidAccessTokenRefreshesNearExpiry()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        RecordingExchanger exchanger = new RecordingExchanger();
        // Initial PKCE handshake: token expires in 60s, well inside the
        // 5-minute REFRESH_THRESHOLD, so the next read should rotate.
        exchanger.pkceResponse = new TokenResponse(
                true, null, "xoxp-old", "T1", "Acme", "U1",
                "xoxr-old-refresh", 60);
        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-05-10T00:00:00Z"));
        SlackOAuthService service = buildPkce(store, "pkce-client", exchanger, clock);

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));

        // Pre-expiry refresh response.
        exchanger.refreshResponse = new TokenResponse(
                true, null, "xoxp-new", "T1", "Acme", "U1",
                "xoxr-new-refresh", 1800);

        // Move close enough to expiry that the threshold trips.
        clock.advance(Duration.ofSeconds(30));

        Optional<String> token = service.getValidAccessToken();

        assertThat(token).contains("xoxp-new");
        assertThat(exchanger.lastRefreshClientId).isEqualTo("pkce-client");
        assertThat(exchanger.lastRefreshToken).isEqualTo("xoxr-old-refresh");
        // Vault row was rewritten with the new bundle.
        Optional<String> raw = store.findSecret(CredentialType.INTEGRATION, SlackOAuthService.SLACK_USER_TOKEN_NAME);
        assertThat(raw).isPresent();
        assertThat(raw.get()).contains("\"access\":\"xoxp-new\"").contains("\"refresh\":\"xoxr-new-refresh\"");
    }

    @Test
    void testGetValidAccessTokenSkipsRefreshWhenStillFresh()
    {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        RecordingExchanger exchanger = new RecordingExchanger();
        exchanger.pkceResponse = new TokenResponse(
                true, null, "xoxp-fresh", "T1", "Acme", "U1",
                "xoxr-refresh", 3600);
        AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-05-10T00:00:00Z"));
        SlackOAuthService service = buildPkce(store, "pkce-client", exchanger, clock);

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));

        // Still 55 minutes from the 60-min expiry — well outside the 5-min
        // refresh threshold; the exchanger.refreshPkce path must NOT fire.
        clock.advance(Duration.ofMinutes(5));

        Optional<String> token = service.getValidAccessToken();

        assertThat(token).contains("xoxp-fresh");
        assertThat(exchanger.lastRefreshClientId).isNull();
    }

    @Test
    void testGetValidAccessTokenReturnsByoTokenWithoutRefresh()
    {
        // Existing BYO connection persisted as a bare access token. PKCE
        // refresh logic must not touch it — those tokens never expire.
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        store.upsert(
                CredentialType.INTEGRATION,
                SlackOAuthService.SLACK_USER_TOKEN_NAME,
                "default",
                "xoxp-legacy-byo",
                "slack|T1|Acme|U1",
                null);
        RecordingExchanger exchanger = new RecordingExchanger();
        SlackOAuthService service = buildWithPkce(store, "pkce-client", null, null, exchanger, Clock.systemUTC());

        Optional<String> token = service.getValidAccessToken();

        assertThat(token).contains("xoxp-legacy-byo");
        assertThat(exchanger.lastRefreshClientId).isNull();
    }

    @Test
    void testByoFlowStillPersistsBareToken()
    {
        // Regression: even after the PKCE plumbing landed, BYO connections
        // must continue to store a plain string in Credential.value so the
        // existing read path (and any out-of-process tools) sees them
        // unchanged.
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        SlackOAuthService service = build(
                store, "byo-client", "byo-secret",
                (cid, sec, code, uri) -> OK_RESPONSE,
                Clock.systemUTC());

        service.exchangeCode("auth-code", stateFromUrl(service.issueAuthorizeUrl()));

        Optional<String> raw = store.findSecret(CredentialType.INTEGRATION, SlackOAuthService.SLACK_USER_TOKEN_NAME);
        assertThat(raw).contains("xoxp-test-token");
        assertThat(raw.get()).doesNotStartWith("{");
    }

    /** Records the most recent BYO / PKCE / refresh call for assertions
     *  and lets each test pre-stage the response it wants returned. */
    private static final class RecordingExchanger
            implements OAuthExchanger
    {
        TokenResponse byoResponse = OK_RESPONSE;
        TokenResponse pkceResponse = OK_RESPONSE;
        TokenResponse refreshResponse;

        String lastPkceClientId;
        String lastPkceCodeVerifier;
        String lastRefreshClientId;
        String lastRefreshToken;

        @Override
        public TokenResponse exchange(String clientId, String clientSecret, String code, String redirectUri)
        {
            return byoResponse;
        }

        @Override
        public TokenResponse exchangePkce(String clientId, String codeVerifier, String code, String redirectUri)
        {
            this.lastPkceClientId = clientId;
            this.lastPkceCodeVerifier = codeVerifier;
            return pkceResponse;
        }

        @Override
        public TokenResponse refreshPkce(String clientId, String refreshToken)
        {
            this.lastRefreshClientId = clientId;
            this.lastRefreshToken = refreshToken;
            if (refreshResponse == null) {
                throw new AssertionError("refreshPkce called without a staged response");
            }
            return refreshResponse;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static SlackOAuthService build(String clientId, String clientSecret, OAuthExchanger exchanger, Clock clock)
    {
        return build(new InMemoryCredentialStore(), clientId, clientSecret, exchanger, clock);
    }

    private static SlackOAuthService build(
            InMemoryCredentialStore store,
            String clientId,
            String clientSecret,
            OAuthExchanger exchanger,
            Clock clock)
    {
        // BYO-only construction. PKCE-flavoured tests use buildPkce() so the
        // BYO test surface stays pkceClientId-agnostic.
        return buildWithPkce(store, null, clientId, clientSecret, exchanger, clock);
    }

    private static SlackOAuthService buildPkce(
            InMemoryCredentialStore store,
            String pkceClientId,
            OAuthExchanger exchanger,
            Clock clock)
    {
        return buildWithPkce(store, pkceClientId, null, null, exchanger, clock);
    }

    private static SlackOAuthService buildWithPkce(
            InMemoryCredentialStore store,
            String pkceClientId,
            String envClientId,
            String envClientSecret,
            OAuthExchanger exchanger,
            Clock clock)
    {
        CredentialService credentials = new CredentialService(store, new InMemoryAppSettingsStore());
        return new SlackOAuthService(credentials, pkceClientId, envClientId, envClientSecret, exchanger, clock);
    }

    private static String stateFromUrl(String url)
    {
        int idx = url.indexOf("&state=");
        if (idx < 0) {
            throw new AssertionError("authorize URL has no state param: " + url);
        }
        return url.substring(idx + "&state=".length());
    }

    /** Minimal in-memory CredentialStore — just enough for the SlackOAuthService
     *  test surface: upsert, find by (type, name), getSecret, delete. */
    static final class InMemoryCredentialStore
            implements CredentialStore
    {
        private final Map<Key, Stored> rows = new HashMap<>();
        private final AtomicLong nextId = new AtomicLong(1);

        @Override
        public List<Credential> findAll()
        {
            return rows.values().stream().map(Stored::toCredential).toList();
        }

        @Override
        public List<Credential> findByType(CredentialType type)
        {
            return rows.values().stream()
                    .filter(s -> s.type == type)
                    .map(Stored::toCredential)
                    .toList();
        }

        @Override
        public List<Credential> findByTypeAndName(CredentialType type, String name)
        {
            return rows.values().stream()
                    .filter(s -> s.type == type && s.name.equals(name))
                    .map(Stored::toCredential)
                    .toList();
        }

        @Override
        public Optional<Credential> find(CredentialType type, String name)
        {
            return rows.values().stream()
                    .filter(s -> s.type == type && s.name.equals(name))
                    .findFirst()
                    .map(Stored::toCredential);
        }

        @Override
        public Optional<Credential> find(CredentialType type, String name, String instanceName)
        {
            Stored row = rows.get(new Key(type, name, instanceName));
            return row == null ? Optional.empty() : Optional.of(row.toCredential());
        }

        @Override
        public Optional<String> getSecret(CredentialType type, String name)
        {
            return rows.values().stream()
                    .filter(s -> s.type == type && s.name.equals(name))
                    .findFirst()
                    .map(s -> s.value);
        }

        @Override
        public Optional<String> getSecret(CredentialType type, String name, String instanceName)
        {
            Stored row = rows.get(new Key(type, name, instanceName));
            return row == null ? Optional.empty() : Optional.of(row.value);
        }

        @Override
        public Credential upsert(CredentialType type, String name, String instanceName, String value, String label, String notes)
        {
            Key key = new Key(type, name, instanceName);
            Stored stored = new Stored(nextId.getAndIncrement(), type, name, instanceName, label, notes, value, Instant.now());
            rows.put(key, stored);
            return stored.toCredential();
        }

        @Override
        public void delete(CredentialType type, String name, String instanceName)
        {
            rows.remove(new Key(type, name, instanceName));
        }

        Optional<String> findSecret(CredentialType type, String name)
        {
            return getSecret(type, name);
        }

        private record Key(CredentialType type, String name, String instanceName) {}

        private record Stored(
                long id,
                CredentialType type,
                String name,
                String instanceName,
                String label,
                String notes,
                String value,
                Instant timestamp)
        {
            Credential toCredential()
            {
                return new Credential(
                        id, type, name, instanceName, label,
                        value.length() > 4 ? "•••" + value.substring(value.length() - 4) : "•••",
                        notes, timestamp, timestamp, null);
            }
        }
    }

    /** Stub AppSettingsStore for the CredentialService legacy-PAT migration. */
    private static final class InMemoryAppSettingsStore
            implements AppSettingsStore
    {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value)
        {
            values.put(key, value);
        }
    }

    /** Mutable Clock so we can fast-forward past the state TTL. */
    private static final class AdvanceableClock
            extends Clock
    {
        private Instant now;

        AdvanceableClock(Instant start)
        {
            this.now = start;
        }

        void advance(Duration delta)
        {
            now = now.plus(delta);
        }

        @Override
        public Instant instant()
        {
            return now;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }
    }
}
