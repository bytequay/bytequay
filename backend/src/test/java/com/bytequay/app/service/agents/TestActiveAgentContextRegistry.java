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
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void exactProviderStopRecordsOneReasonAndRunsOnce()
    {
        ResolvedAgentContext context = new ResolvedAgentContext(
                ByteQuayRole.TRUNK, "1", AgentRole.TRUNK, null,
                Set.of(), List.of(), Set.of(), Set.of());
        registry.put("thread-1", "agent-1", context);
        AtomicInteger stops = new AtomicInteger();

        assertThat(registry.requestStop(
                "thread-1", "agent-1", "before-session")).isFalse();
        assertThat(registry.attachStop(
                "thread-1", "agent-1", stops::incrementAndGet)).isTrue();
        assertThat(registry.attachStop(
                "thread-1", "agent-1", stops::incrementAndGet)).isFalse();
        assertThat(registry.requestStop(
                "thread-1", "agent-1", "USER_WAIT:question-1")).isTrue();
        assertThat(registry.requestStop(
                "thread-1", "agent-1", "USER_WAIT:question-2")).isFalse();
        assertThat(stops).hasValue(1);
        assertThat(registry.stopReason("thread-1", "agent-1"))
                .contains("USER_WAIT:question-1");
    }
}
