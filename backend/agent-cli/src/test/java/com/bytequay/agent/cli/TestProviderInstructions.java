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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestProviderInstructions
{
    private static final String TOOLS = "ab".repeat(32);

    @Test
    void setupNamesTheInstructionsAndTheToolsApart()
    {
        String setup = ProviderInstructions.setup("Use the review tools.", Optional.of(TOOLS));

        assertThat(setup).matches("[0-9a-f]{8}-abababab").isEqualTo(ProviderInstructions.setup("Use the review tools.", Optional.of(TOOLS)));
        // A change to the instructions moves only the first half, a change to the tools only the second.
        assertThat(ProviderInstructions.setup("Use the review tools first.", Optional.of(TOOLS))).doesNotStartWith(setup.substring(0, 8)).endsWith("-abababab");
        assertThat(ProviderInstructions.setup("Use the review tools.", Optional.of("cd".repeat(32)))).isEqualTo(setup.substring(0, 9) + "cdcdcdcd");
        assertThat(ProviderInstructions.setup("Use the review tools.", Optional.empty())).startsWith(setup.substring(0, 9)).isNotEqualTo(setup);
    }
}
