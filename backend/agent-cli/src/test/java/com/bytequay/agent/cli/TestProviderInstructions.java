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
package com.bytequay.agent.cli;

import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.SessionAccess;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestProviderInstructions
{
    private static final String TOOLS = "ab".repeat(32);
    private static final AgentPermissionScope FULL = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false);
    private static final AgentPermissionScope READ_ONLY = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false);

    @Test
    void setupNamesByteQuaysOwnInstructionsAndTheToolsApart()
    {
        String setup = ProviderInstructions.setup(FULL, "Plan first.", Optional.of(TOOLS), "Use the review tools.");

        assertThat(setup).matches("[0-9a-f]{8}-abababab").isEqualTo(ProviderInstructions.setup(FULL, "Plan first.", Optional.of(TOOLS), "Use the review tools."));
        // The instructions as a session composes them, without the managed instructions that carry a task's own context.
        String own = ProviderInstructions.compose(FULL, "Plan first.", "", "Use the review tools.");
        assertThat(setup).startsWith(Hashing.sha256().hashString(own, StandardCharsets.UTF_8).toString().substring(0, 8) + "-");
        // A change to the instructions or the scope moves only the first half, a change to the tools only the second.
        assertThat(ProviderInstructions.setup(FULL, "Plan first.", Optional.of(TOOLS), "Use the tools.")).doesNotStartWith(setup.substring(0, 8)).endsWith("-abababab");
        assertThat(ProviderInstructions.setup(READ_ONLY, "Plan first.", Optional.of(TOOLS), "Use the review tools.")).doesNotStartWith(setup.substring(0, 8)).endsWith("-abababab");
        assertThat(ProviderInstructions.setup(FULL, "Plan first.", Optional.of("cd".repeat(32)), "Use the review tools.")).isEqualTo(setup.substring(0, 9) + "cdcdcdcd");
        assertThat(ProviderInstructions.setup(FULL, "Plan first.", Optional.empty(), "Use the review tools.")).startsWith(setup.substring(0, 9)).isNotEqualTo(setup);
    }
}
