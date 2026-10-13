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

import com.bytequay.agent.cli.development.DevelopmentResultTool;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexDevelopmentResult
{
    private final ObjectMapper json = new ObjectMapper().registerModule(new Jdk8Module());
    private final CodexProtocolCodec codec = new CodexProtocolCodec(json);
    private final CodexEventMapper mapper = new CodexEventMapper(Path.of("/tmp"),
            SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret")));
    private final DevelopmentResultBridge bridge = new DevelopmentResultBridge("app-turn", DevelopmentResultBridge.INSTRUCTION, true, Optional.empty());

    @Test
    void acceptsSuccessfulLiveRootCompletionAndKeepsReplayAsOrdinaryActivity()
            throws Exception
    {
        CodexProtocol.Notification notification = notification("item/completed", "native-turn", "completed", "null", bridge);
        assertThat(((CodexProtocol.McpToolCallItem) ((CodexProtocol.ItemCompletedNotification) notification).params().item()).error()).isEmpty();
        assertThat(mapper.map(notification, true, CodexEventMapper.ThreadLineage.NONE)).noneMatch(ProviderAgentEvent.DevelopmentResult.class::isInstance);
        assertThat(mapper.developmentResult(notification, true, "native-turn", Optional.of(bridge)))
                .hasValueSatisfying(result -> {
                    assertThat(result.report().readyForDelivery()).isTrue();
                    assertThat(result.report().message()).contains("done [REDACTED]");
                    assertThat(result.report().learningSummary()).hasValueSatisfying(summary -> {
                        assertThat(summary.purpose()).isEqualTo("Purpose [REDACTED]");
                        assertThat(summary.evidence()).isEmpty();
                    });
                    assertThat(result.source().providerThreadId()).contains("root-thread");
                    assertThat(result.source().providerTurnId()).contains("native-turn");
                    assertThat(result.source().providerItemId()).contains("tool-1");
                });
        assertThat(mapper.developmentResult(notification, false, "native-turn", Optional.of(bridge))).isEmpty();
        assertThat(mapper.developmentResult(notification, true, "new-native-turn", Optional.of(bridge))).isEmpty();
        assertThat(mapper.developmentResult(notification, true, "native-turn", Optional.empty())).isEmpty();
    }

    @Test
    void acceptsDispatchedReportsOnlyForTheNamedToolAndSuccessfulRootCompletion()
            throws Exception
    {
        for (String type : ImmutableList.of("mcpToolCall", "dynamicToolCall")) {
            ObjectNode event = json.valueToTree(notification("item/completed", "native-turn", "completed", "null", bridge));
            ObjectNode tool = (ObjectNode) event.at("/params/item");
            tool.put("type", type).put("tool", "bytequay_call_tool");
            ObjectNode arguments = tool.putObject("arguments").put("name", DevelopmentResultBridge.TOOL_NAME);
            arguments.putObject("arguments").put("readyForDelivery", true);
            tool.put("success", true);
            tool.putArray("contentItems").addObject().put("type", "inputText").put("text", tool.at("/result/content/0/text").asText());
            CodexProtocol.Notification completed = codec.decodeNotification(event.toString());
            assertThat(mapper.developmentResult(completed, true, "native-turn", Optional.of(bridge))).isPresent();
            assertThat(mapper.developmentResult(completed, false, "native-turn", Optional.of(bridge))).isEmpty();
            assertThat(mapper.developmentResult(completed, true, "stale-turn", Optional.of(bridge))).isEmpty();
            arguments.put("name", "read_issue_context");
            assertThat(mapper.developmentResult(codec.decodeNotification(event.toString()), true, "native-turn", Optional.of(bridge))).isEmpty();
            arguments.put("name", DevelopmentResultBridge.TOOL_NAME);
            tool.put("status", "failed").put("success", false);
            assertThat(mapper.developmentResult(codec.decodeNotification(event.toString()), true, "native-turn", Optional.of(bridge))).isEmpty();
        }
    }

    @Test
    void rejectsFailedStartedAndStaleReceipts()
            throws Exception
    {
        for (CodexProtocol.Notification notification : ImmutableList.of(
                notification("item/started", "native-turn", "inProgress", "null", bridge),
                notification("item/completed", "native-turn", "failed", "null", bridge),
                notification("item/completed", "native-turn", "completed", "{\"message\":\"failed\"}", bridge),
                notification("item/completed", "native-turn", "completed", "null", new DevelopmentResultBridge("old-app-turn", bridge.instruction(), true, Optional.empty())))) {
            assertThat(mapper.developmentResult(notification, true, "native-turn", Optional.of(bridge))).isEmpty();
        }
    }

    private CodexProtocol.Notification notification(String method, String nativeTurn, String status, String error, DevelopmentResultBridge receiptBridge)
            throws Exception
    {
        String receipt = json.writeValueAsString(DevelopmentResultTool.receipt(receiptBridge,
                new DevelopmentResultReport(true, Optional.of("done known-secret"),
                        Optional.of(new DevelopmentLearningSummary("Purpose known-secret", ImmutableList.of(), Optional.empty(), "", ImmutableList.of())))));
        return codec.decodeNotification("""
                {"method":"%s","params":{"threadId":"root-thread","turnId":"%s","item":{
                "type":"mcpToolCall","id":"tool-1","server":"bytequay","tool":"report_development_result",
                "arguments":{},"status":"%s","error":%s,"result":{"content":[{"type":"text","text":%s}]}}}}
                """.formatted(method, nativeTurn, status, error, receipt));
    }
}
