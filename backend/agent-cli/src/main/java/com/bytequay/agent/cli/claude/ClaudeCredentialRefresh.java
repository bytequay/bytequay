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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.ProviderCli;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/** Reads only refresh readiness from the CLI-owned credential entry; never writes or retains tokens. */
final class ClaudeCredentialRefresh
{
    private static final int MAX_CREDENTIAL_BYTES = 64 * 1024;

    private ClaudeCredentialRefresh() {}

    static boolean pending(ProcessSupervisor supervisor, ProcessSpec probe, ObjectMapper mapper)
            throws InterruptedException
    {
        Path home = Path.of(probe.environment().get("CLAUDE_CONFIG_DIR"));
        if (Files.exists(home.resolve(".oauth_refresh.lock"))) {
            return true;
        }
        String account = Optional.ofNullable(probe.environment().get("USER")).filter(value -> !value.isEmpty()).orElse(System.getProperty("user.name"));
        if (!account.matches("[a-zA-Z0-9._-]+")) {
            account = "claude-code-user";
        }
        ProviderCli.Result result = ProviderCli.run(supervisor, new ProcessSpec(
                new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "claude-refresh-status"),
                ImmutableList.of("/usr/bin/security", "find-generic-password", "-a", account, "-w", "-s", service(home)),
                home, probe.environment(), OptionalInt.of(MAX_CREDENTIAL_BYTES), 1024, Duration.ofSeconds(10), Duration.ofSeconds(3)), "Claude credential status");
        if (result.timedOut() || result.truncated()) {
            throw unavailable();
        }
        if (result.exitCode() == 0) {
            return pending(result.stdout(), mapper, Instant.now()) || Files.exists(home.resolve(".oauth_refresh.lock"));
        }
        if (result.exitCode() != 44) {
            throw unavailable();
        }
        // Claude falls back to this file when no Keychain entry is available.
        Path fallback = home.resolve(".credentials.json");
        try {
            if (!Files.exists(fallback)) {
                return false;
            }
            if (Files.size(fallback) > MAX_CREDENTIAL_BYTES) {
                throw unavailable();
            }
            return pending(Files.readString(fallback), mapper, Instant.now()) || Files.exists(home.resolve(".oauth_refresh.lock"));
        }
        catch (IOException failure) {
            throw unavailable();
        }
    }

    static String service(Path home)
    {
        String normalized = Normalizer.normalize(home.toAbsolutePath().normalize().toString(), Normalizer.Form.NFC);
        return "Claude Code-credentials-" + Hashing.sha256().hashString(normalized, StandardCharsets.UTF_8).toString().substring(0, 8);
    }

    static boolean pending(String credentials, ObjectMapper mapper, Instant now)
    {
        if (credentials.length() > MAX_CREDENTIAL_BYTES) {
            throw unavailable();
        }
        try (var parser = mapper.createParser(credentials)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw unavailable();
            }
            JsonNode oauth = root.path("claudeAiOauth");
            if (oauth.isMissingNode() || oauth.isNull()) {
                return false;
            }
            if (!oauth.isObject()) {
                throw unavailable();
            }
            JsonNode refresh = oauth.path("refreshToken");
            if (refresh.isMissingNode() || refresh.isNull()) {
                return false;
            }
            if (!refresh.isTextual()) {
                throw unavailable();
            }
            if (refresh.textValue().isEmpty()) {
                return false;
            }
            JsonNode expiresAt = oauth.path("expiresAt");
            if (!expiresAt.isIntegralNumber() || !expiresAt.canConvertToLong()) {
                throw unavailable();
            }
            // Claude begins proactive refresh five minutes before access-token expiry.
            return expiresAt.longValue() <= now.plus(Duration.ofMinutes(5)).toEpochMilli();
        }
        catch (IOException failure) {
            // JSON parser errors can include credential contents. Never propagate them.
            throw unavailable();
        }
    }

    private static AgentUnavailableException unavailable()
    {
        return new AgentUnavailableException("Claude credential refresh status is unavailable");
    }
}
