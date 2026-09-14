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

import com.bytequay.agent.cli.development.DevelopmentResultTool;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeDevelopmentResult
{
    private final ObjectMapper json = new ObjectMapper();
    private final ClaudeProtocolCodec codec = new ClaudeProtocolCodec(json);
    private final DevelopmentResultBridge bridge = new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, true);
    private final DevelopmentResultReport report = new DevelopmentResultReport(true, Optional.of("done known-secret"),
                        Optional.of(new DevelopmentLearningSummary("Purpose known-secret", ImmutableList.of(), Optional.empty(), "", ImmutableList.of())));

    @Test
    void acceptsOnlySuccessfulCurrentRootToolCompletion()
            throws Exception
    {
        ClaudeEventMapper mapper = mapper();
        assertThat(start(mapper, false)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        List<ProviderAgentEvent> events = finish(mapper, bridge, false, false);
        assertThat(events).filteredOn(ProviderAgentEvent.DevelopmentResult.class::isInstance).singleElement()
                .isInstanceOfSatisfying(ProviderAgentEvent.DevelopmentResult.class, result -> {
                    assertThat(result.report().readyForDelivery()).isTrue();
                    assertThat(result.report().message()).contains("done [REDACTED]");
                    assertThat(result.report().learningSummary()).hasValueSatisfying(summary -> {
                        assertThat(summary.purpose()).isEqualTo("Purpose [REDACTED]");
                        assertThat(summary.evidence()).isEmpty();
                    });
                    assertThat(result.source().providerThreadId()).contains("session-1");
                    assertThat(result.source().providerTurnId()).contains("turn-1");
                    assertThat(result.source().providerItemId()).contains("tool-1");
                });
        assertThat(finish(mapper, bridge, false, false)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
    }

    @Test
    void rejectsChildFailedUnknownAndStaleResults()
            throws Exception
    {
        ClaudeEventMapper child = mapper();
        start(child, true);
        assertThat(finish(child, bridge, false, false)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        ClaudeEventMapper childResult = mapper();
        start(childResult, false);
        assertThat(finish(childResult, bridge, true, false)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        ClaudeEventMapper failed = mapper();
        start(failed, false);
        assertThat(finish(failed, bridge, false, true)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        assertThat(finish(mapper(), bridge, false, false)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        ClaudeEventMapper stale = mapper();
        start(stale, false);
        assertThat(finish(stale, new DevelopmentResultBridge("previous-turn", bridge.instruction(), true), false, false))
                .noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
    }

    private ClaudeEventMapper mapper()
    {
        return new ClaudeEventMapper(SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")), Optional.of(bridge));
    }

    private List<ProviderAgentEvent> start(ClaudeEventMapper mapper, boolean child)
    {
        return mapper.map(codec.decode("""
                {"type":"assistant","session_id":"session-1","uuid":"start","parent_tool_use_id":%s,
                "message":{"role":"assistant","content":[{"type":"tool_use","id":"tool-1",
                "name":"mcp__bytequay__report_development_result","input":{"readyForDelivery":true}}]}}
                """.formatted(child ? "\"parent-1\"" : "null")));
    }

    private List<ProviderAgentEvent> finish(ClaudeEventMapper mapper, DevelopmentResultBridge receiptBridge, boolean child, boolean failed)
            throws Exception
    {
        String content = json.writeValueAsString(DevelopmentResultTool.receipt(receiptBridge, report));
        return mapper.map(codec.decode("""
                {"type":"user","session_id":"session-1","uuid":"finish","parent_tool_use_id":%s,
                "message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-1",
                "is_error":%s,"content":%s}]}}
                """.formatted(child ? "\"parent-1\"" : "null", failed, content)));
    }
}
