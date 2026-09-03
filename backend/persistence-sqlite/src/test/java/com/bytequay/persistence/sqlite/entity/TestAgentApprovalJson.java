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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_FOR_TASK;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentApprovalJson
{
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void roundTripsATaskScope()
    {
        ApprovalRequest request = request(ImmutableList.of(ALLOW_ONCE, ALLOW_FOR_TASK, DENY), Optional.of("gh issue list"));
        assertThat(AgentApprovalJson.decode(AgentApprovalJson.encode(request))).isEqualTo(request);
    }

    @Test
    void decodesARequestStoredBeforeTaskScopeExisted()
    {
        ApprovalRequest request = request(ImmutableList.of(ALLOW_ONCE, DENY), Optional.empty());
        String json = AgentApprovalJson.encode(request);
        assertThat(json).contains("\"taskScope\":null");
        assertThat(AgentApprovalJson.decode(json.replace(",\"taskScope\":null", ""))).isEqualTo(request);
    }

    private static ApprovalRequest request(List<ProviderApprovalRequest.Decision> decisions, Optional<String> taskScope)
    {
        return new ApprovalRequest(
                ApprovalId.random(),
                TaskId.random(),
                TurnId.random(),
                new ProviderApprovalRequest(
                        "provider-1",
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Allow Codex command",
                        "/bin/zsh -lc 'gh issue list --repo t/t'",
                        ImmutableList.of("/bin/zsh -lc 'gh issue list --repo t/t'"),
                        decisions,
                        taskScope,
                        new ProviderAgentEventSource(
                                Optional.of("thread-1"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("turn-1"),
                                Optional.of("item-1")),
                        NOW),
                NOW);
    }
}
