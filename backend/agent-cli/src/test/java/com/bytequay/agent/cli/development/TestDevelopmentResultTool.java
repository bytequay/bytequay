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
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentResultTool
{
    private final ObjectMapper json = new ObjectMapper();
    private final DevelopmentResultBridge bridge = new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty());
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
    void refinementSummaryIsBoundedScopedAndFullyRedacted()
            throws Exception
    {
        DevelopmentResultBridge refinement = new DevelopmentResultBridge("turn-1", bridge.instruction(), true, Optional.empty());
        var input = json.readTree("""
                {"readyForDelivery":true,"message":"done known-secret","learningSummary":{
                  "purpose":"purpose known-secret","commits":[{"sha":"%s","title":"title known-secret","summary":"commit known-secret"}]}}
                """.formatted("a".repeat(40)));
        var report = DevelopmentResultTool.parse(input, refinement);
        assertThat(report.learningSummary()).hasValueSatisfying(summary -> {
            assertThat(summary.combinedResult()).isEmpty();
            assertThat(summary.evidence()).isEmpty();
            assertThat(summary.limitations()).isEmpty();
        });
        var secret = redactor.withSecret("known-secret");
        String receipt = DevelopmentResultTool.receipt(refinement, DevelopmentResultTool.redact(report, secret));
        assertThat(receipt).doesNotContain("known-secret", "combinedResult", "evidence", "limitations")
                .contains("purpose [REDACTED]", "title [REDACTED]", "commit [REDACTED]");
        assertThat(DevelopmentResultTool.readReceipt(TextNode.valueOf(DevelopmentResultTool.receipt(refinement, report)), refinement, secret))
                .contains(DevelopmentResultTool.redact(report, secret));
        assertThat(DevelopmentResultTool.readReceipt(TextNode.valueOf(receipt), bridge, redactor)).isEmpty();
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, bridge)).hasMessageContaining("only accepted during summary refinement");
        assertThat(DevelopmentResultTool.schema(bridge).toString()).doesNotContain("learningSummary");
        var summarySchema = json.valueToTree(DevelopmentResultTool.schema(refinement)).at("/properties/learningSummary");
        assertThat(summarySchema.path("properties").size()).isEqualTo(2);
        assertThat(summarySchema.path("required")).isEqualTo(json.readTree("[\"purpose\",\"commits\"]"));
        assertThat(summarySchema.path("additionalProperties").booleanValue()).isFalse();

        var summary = (ObjectNode) input.path("learningSummary");
        for (String field : ImmutableList.of("unexpected", "combinedResult", "evidence", "limitations")) {
            summary.put(field, "value");
            assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessage("unknown argument: " + field);
            summary.remove(field);
        }
        summary.put("purpose", "x".repeat(DevelopmentLearningSummary.MAXIMUM_PURPOSE_LENGTH + 1));
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessageContaining("purpose is too long");
        summary.remove("purpose");
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessage("purpose is required");
        summary.put("purpose", "Explain the task's intention");
        var commits = summary.withArray("commits");
        commits.add(commits.get(0).deepCopy());
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessageContaining("duplicate commit");
        commits.remove(1);
        ((ObjectNode) commits.get(0)).put("sha", "short");
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessageContaining("full commit identifier");
        commits.removeAll();
        for (int index = 0; index < 20; index++) {
            commits.addObject().put("sha", "%040x".formatted(index)).put("title", "Commit").put("summary", "x".repeat(4000));
        }
        assertThatThrownBy(() -> DevelopmentResultTool.parse(input, refinement)).hasMessageContaining("learningSummary text is too long");
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
