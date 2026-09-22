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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitCliEnvironment
{
    @Test
    void keepsProviderAuthenticationWhileRemovingGitCredentialsFromRestrictedSessions()
    {
        Map<String, String> base = ImmutableMap.of(
                "HOME", "/home/user",
                "XDG_CONFIG_HOME", "/home/user/config",
                "OPENAI_API_KEY", "provider-sentinel",
                "ANTHROPIC_AUTH_TOKEN", "claude-sentinel");
        ImmutableMap.Builder<String, String> environment = ImmutableMap.<String, String>builder().putAll(base);
        GitCliEnvironment.variables().forEach(name -> environment.put(name, "git-sentinel-" + name));
        Map<String, String> captured = environment.buildOrThrow();

        assertThat(GitCliEnvironment.forSession(captured, true)).isEqualTo(captured);
        assertThat(GitCliEnvironment.forSession(captured, false)).isEqualTo(base);
        // A review keeps the stored login's locators and the SSH agent, and no raw token the pull request's tests could read.
        assertThat(GitCliEnvironment.forReview(captured))
                .containsAllEntriesOf(base)
                .containsKeys("GH_HOST", "GH_CONFIG_DIR", "SSH_AUTH_SOCK")
                .doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN")
                .containsKey("ANTHROPIC_AUTH_TOKEN");
        SecretRedactor redactor = SecretRedactor.fromEnvironment(captured);
        for (String name : GitCliEnvironment.variables()) {
            if (name.endsWith("TOKEN")) {
                assertThat(redactor.redact(captured.get(name))).isEqualTo("[REDACTED]");
            }
        }
    }
}
