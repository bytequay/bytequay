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

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeCredentialRefresh
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.ofEpochMilli(1_000_000);

    @Test
    void waitsThroughTheRefreshWindowUntilSavedOrExplicitlyInvalidated()
    {
        assertThat(pending("{\"refreshToken\":\"fabricated\",\"expiresAt\":1299999}")).isTrue();
        assertThat(pending("{\"refreshToken\":\"fabricated\",\"expiresAt\":1300000}")).isTrue();
        assertThat(pending("{\"refreshToken\":\"fabricated\",\"expiresAt\":1300001}")).isFalse();
        assertThat(pending("{\"refreshToken\":\"\",\"accessToken\":\"\",\"expiresAt\":0}")).isFalse();
        assertThat(pending("{\"refreshToken\":null,\"expiresAt\":null}")).isFalse();
        assertThat(ClaudeCredentialRefresh.pending("{}", mapper, now)).isFalse();
    }

    @Test
    void refusesUnknownShapesWithoutLeakingCredentialValues()
    {
        for (String value : new String[] {"{\"refreshToken\":42}", "{\"refreshToken\":\"secret-value\"}",
                "{\"refreshToken\":\"secret-value\",\"expiresAt\":\"1300001\"}", "\"secret-value\"", "[\"secret-value\"]"}) {
            assertThatThrownBy(() -> pending(value)).isInstanceOf(AgentUnavailableException.class)
                    .hasMessage("Claude credential refresh status is unavailable").hasNoCause();
        }
        assertThatThrownBy(() -> ClaudeCredentialRefresh.pending("{secret-value", mapper, now))
                .hasMessage("Claude credential refresh status is unavailable").hasNoCause();
        assertThatThrownBy(() -> ClaudeCredentialRefresh.pending("{}{}", mapper, now)).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void usesTheCliKeychainNamespaceAndNormalizesUnicodeWithoutMixingAccounts()
    {
        assertThat(ClaudeCredentialRefresh.service(Path.of("/tmp/bytequay-claude-default")))
                .isEqualTo("Claude Code-credentials-868ddaaf");
        assertThat(ClaudeCredentialRefresh.service(Path.of("/tmp/café")))
                .isEqualTo(ClaudeCredentialRefresh.service(Path.of("/tmp/cafe\u0301")));
        assertThat(ClaudeCredentialRefresh.service(Path.of("/tmp/global/accounts/work")))
                .isNotEqualTo(ClaudeCredentialRefresh.service(Path.of("/tmp/global")))
                .isNotEqualTo(ClaudeCredentialRefresh.service(Path.of("/tmp/global/accounts/personal")));
    }

    private boolean pending(String oauth)
    {
        return ClaudeCredentialRefresh.pending("{\"claudeAiOauth\":" + oauth + "}", mapper, now);
    }
}
