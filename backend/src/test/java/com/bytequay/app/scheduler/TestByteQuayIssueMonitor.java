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
package com.bytequay.app.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static com.bytequay.app.scheduler.ByteQuayIssueMonitor.Route.APPROVAL;
import static com.bytequay.app.scheduler.ByteQuayIssueMonitor.Route.AUTO;
import static com.bytequay.app.scheduler.ByteQuayIssueMonitor.Route.BACKLOG;
import static org.assertj.core.api.Assertions.assertThat;

class TestByteQuayIssueMonitor
{
    @Test
    void autoRoutesOnlyHighConfidenceLowRiskSmallPlans()
            throws Exception
    {
        assertThat(ByteQuayIssueMonitor.classify(plan("high", "low", "small"))).isEqualTo(AUTO);
        assertThat(ByteQuayIssueMonitor.classify(plan("medium", "low", "small"))).isEqualTo(APPROVAL);
        assertThat(ByteQuayIssueMonitor.classify(plan("low", "low", "small"))).isEqualTo(BACKLOG);
        assertThat(ByteQuayIssueMonitor.classify(plan("high", "high", "small"))).isEqualTo(BACKLOG);
        assertThat(ByteQuayIssueMonitor.classify(plan("high", "low", "large"))).isEqualTo(BACKLOG);
    }

    @Test
    void requiresASummaryAndAtLeastOneStep()
            throws Exception
    {
        assertThat(ByteQuayIssueMonitor.isStructurallyComplete(plan("high", "low", "small"))).isTrue();
        assertThat(ByteQuayIssueMonitor.isStructurallyComplete(json("""
                {"status":"finalized","goal":"Understand it","intent":{"steps":[]}}
                """))).isFalse();
    }

    private static JsonNode plan(String confidence, String risk, String complexity)
            throws JsonProcessingException
    {
        return json("""
                {
                  "status": "finalized",
                  "goal": "Fix the reported problem",
                  "intent": {"steps": [{"action": "Change the failing path"}]},
                  "signals": {
                    "confidence": "%s",
                    "riskLevel": "%s",
                    "estimatedComplexity": "%s"
                  }
                }
                """.formatted(confidence, risk, complexity));
    }

    private static JsonNode json(String value)
            throws JsonProcessingException
    {
        return new ObjectMapper().readTree(value);
    }
}
