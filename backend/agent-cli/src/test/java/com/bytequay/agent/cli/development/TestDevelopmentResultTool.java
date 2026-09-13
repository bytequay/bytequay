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
package com.bytequay.agent.cli.development;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentResultTool
{
    private final ObjectMapper json = new ObjectMapper();
    private final DevelopmentResultBridge bridge = new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION);
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());

    @Test
    void reportsRoundTripWithoutChangingReadiness()
            throws Exception
    {
        for (boolean ready : ImmutableList.of(false, true)) {
            var report = DevelopmentResultTool.parse(json.readTree("""
                    {"readyForDelivery":%s,"message":"Development result"}
                    """.formatted(ready)));
            assertThat(report.readyForDelivery()).isEqualTo(ready);
            assertThat(report.message()).contains("Development result");
            assertThat(DevelopmentResultTool.readReceipt(TextNode.valueOf(DevelopmentResultTool.receipt(bridge, report)), bridge, redactor))
                    .contains(report);
        }
        assertThat(DevelopmentResultTool.parse(json.readTree("{\"readyForDelivery\":false}")).message()).isEmpty();
    }

    @Test
    void reportsCannotSetDeliveryPolicy()
            throws Exception
    {
        for (String value : ImmutableList.of("local_changes", "commits", "draft", "ci")) {
            String report = """
                    {"readyForDelivery":true,"stopAfter":"%s"}
                    """.formatted(value);
            assertThatThrownBy(() -> DevelopmentResultTool.parse(json.readTree(report)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unknown argument: stopAfter");
            assertThat(DevelopmentResultTool.readReceipt(TextNode.valueOf("{\"turnId\":\"turn-1\",\"report\":" + report + "}"), bridge, redactor)).isEmpty();
        }
    }

    @Test
    void malformedOrErrorReceiptsNeverBecomeReady()
            throws Exception
    {
        for (String text : ImmutableList.of("", "null", "{}", "true", "broken", "{\"turnId\":\"turn-1\",\"report\":{}}",
                "{\"turnId\":\"turn-1\",\"report\":{\"readyForDelivery\":\"true\"}}")) {
            assertThat(DevelopmentResultTool.readReceipt(TextNode.valueOf(text), bridge, redactor)).isEmpty();
        }
        assertThat(DevelopmentResultTool.readReceipt(json.readTree("{\"isError\":true,\"content\":[]}"), bridge, redactor)).isEmpty();
        assertThatThrownBy(() -> DevelopmentResultTool.parse(json.readTree("{\"readyForDelivery\":true,\"message\":\" \"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
