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
package com.bytequay.app.service.agents;

import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.tools.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestActiveAgentContextRegistry
{
    private final ActiveAgentContextRegistry registry = new ActiveAgentContextRegistry();

    @Test
    void contextsAreScopedByThreadAndAgentAndCanBeRemoved()
    {
        ResolvedAgentContext context = new ResolvedAgentContext(
                ByteQuayRole.TRUNK, "1", AgentRole.TRUNK, null,
                Set.of(), List.of("codegraph-first"), Set.of(), Set.of("codegraph_explore"));

        registry.put("thread-1", "trunk", context);

        assertThat(registry.find("thread-1", "trunk")).contains(context);
        assertThat(registry.find("thread-1", "stage-1")).isEmpty();
        assertThat(registry.find("thread-2", "trunk")).isEmpty();

        registry.remove("thread-1", "trunk");
        assertThat(registry.find("thread-1", "trunk")).isEmpty();
    }
}
