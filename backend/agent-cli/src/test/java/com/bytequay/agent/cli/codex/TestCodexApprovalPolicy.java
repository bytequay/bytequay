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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentCommandPolicy;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestCodexApprovalPolicy
{
    @Test
    void preservesNativeRiskRulesWhileAuthorizingRoutineDevelopment()
    {
        String policy = CodexApprovalPolicy.policy();
        int nativeStart = policy.indexOf("## Environment Profile\n");
        int scopeStart = policy.indexOf("\n## ByteQuay Automatic Approval Scope\n");

        assertThat(nativeStart).isNotNegative();
        assertThat(scopeStart).isGreaterThan(nativeStart);
        // A replacement policy must retain every native rule, not only the familiar section names.
        assertThat(Hashing.sha256().hashString(policy.substring(nativeStart, scopeStart), UTF_8).toString())
                .isEqualTo("e6b0cf0a2e1c4cabc0a37ac2a0bc424ddd7c89e85d049e32d281a8db6e8d3ce6");
        assertThat(policy.substring(scopeStart + 1)).isEqualTo(AgentCommandPolicy.instructions());
        AgentCommandPolicy.restrictions().forEach(restriction -> assertThat(policy).contains(restriction));
        assertThat(policy).doesNotContain("Deny every non-read-only action crossing the sandbox boundary");
    }
}
