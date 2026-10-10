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
package com.bytequay.app.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspaceConnector
{
    @Test
    void isolatesCredentialNamespaceAndFallbackPathsWithoutEditingInstallation(@TempDir Path temporary)
            throws Exception
    {
        Path entry = temporary.resolve("index.js");
        String original = "console.log(\"gemini-cli-workspace-oauth\", [\"userinfo.profile\"], __dirname, process.env.GEMINI_API_KEY || 'no-key')";
        Files.writeString(entry, original);
        String profile = "00000000-0000-0000-0000-000000000001";
        ProcessBuilder builder = WorkspaceConnector.command(ImmutableList.of("node", "-e", WorkspaceConnector.PROFILE_BOOTSTRAP,
                entry.toString(), temporary.resolve("profile").toString(), profile), System.getenv());
        builder.environment().put("GEMINI_API_KEY", "must-not-inherit");
        Process process = builder.start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes(), UTF_8))
                .contains("com.bytequay.google." + profile, "profile/workspace-server/dist", "userinfo.email", "no-key").doesNotContain("must-not-inherit");
        assertThat(Files.readString(entry)).isEqualTo(original);
        Files.writeString(entry, "console.log('unsupported')");
        Process unsupported = builder.start();
        assertThat(unsupported.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(unsupported.exitValue()).isNotZero();
    }

    @Test
    void resolvesDesktopCommandsWithoutPassingDesktopOrModelSecrets()
            throws Exception
    {
        ProcessBuilder command = WorkspaceConnector.command(ImmutableList.of("sh", "-c", "printf '%s|%s|%s' \"$BYTEQUAY_DESKTOP_SESSION_TOKEN\" \"$OPENAI_API_KEY\" \"$PATH\""),
                ImmutableMap.of("PATH", "/usr/bin:/bin", "BYTEQUAY_DESKTOP_SESSION_TOKEN", "desktop-secret", "OPENAI_API_KEY", "model-secret"));
        assertThat(command.command().getFirst()).startsWith("/");
        Process process = command.start();
        assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
        assertThat(new String(process.getInputStream().readAllBytes(), UTF_8))
                .isEqualTo("||/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin");
    }

    @Test
    void speaksMcpAndRejectsUnlistedToolsAndEmbeddedToolErrors()
            throws Exception
    {
        Process process = new ProcessBuilder("/bin/sh", "-c", """
                read request
                printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"capabilities":{"tools":{}}}}'
                read notification
                read request
                printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"Other connector activity"}}'
                printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"Waiting for authentication... Check your browser."}}'
                printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\\"messages\\":[]}"}]}}'
                read request
                printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\\"error\\":\\"private upstream error\\"}"}]}}'
                read request
                """).start();
        AtomicInteger authenticationRequests = new AtomicInteger();
        try (var session = new WorkspaceConnector.Session(process, new ObjectMapper(), Duration.ofSeconds(2), authenticationRequests::incrementAndGet)) {
            assertThat(session.call("gmail_search", ImmutableMap.of()).path("messages").isArray()).isTrue();
            assertThat(authenticationRequests.get()).isEqualTo(1);
            assertThatThrownBy(() -> session.call("auth_clear", ImmutableMap.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session.call("gmail_get", ImmutableMap.of()))
                    .hasMessageContaining("Google could not complete").hasMessageNotContaining("private upstream");
        }
        assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void terminatesAConnectorThatNeverInitializes()
            throws Exception
    {
        Process process = new ProcessBuilder("/bin/sh", "-c", "read request; read request").start();
        assertThatThrownBy(() -> new WorkspaceConnector.Session(process, new ObjectMapper(), Duration.ofMillis(50), () -> {}))
                .hasMessageContaining("did not respond in time");
        assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
    }
}
