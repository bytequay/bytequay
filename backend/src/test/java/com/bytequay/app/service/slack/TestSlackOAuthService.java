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
        assertThatThrownBy(() -> service.exchangeCode("c", "s"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
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
        CredentialService credentials = new CredentialService(store, new InMemoryAppSettingsStore());
        return new SlackOAuthService(credentials, clientId, clientSecret, exchanger, clock);
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
