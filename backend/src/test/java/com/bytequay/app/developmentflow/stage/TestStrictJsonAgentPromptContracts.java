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
package com.bytequay.app.developmentflow.stage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These prompts used to demand a raw JSON object as the Turn's final message,
 * and this test guarded that wording. Every remote payload is a result tool
 * now, so the same guard asks the opposite question: does the system prompt
 * name the tool the delivery actually reads, and has the raw-JSON instruction
 * that contradicted it gone.
 */
class TestStrictJsonAgentPromptContracts
{
    @Test
    void remoteRepairStageAndBrainPromptsNameTheirResultTool()
            throws Exception
    {
        String stage = prompt(RemoteRepairTurnRuntime.class, "stageSystemPrompt");
        String brain = prompt(RemoteRepairTurnRuntime.class, "brainSystemPrompt");

        assertReportsThroughTool(stage, "record_repair_summary");
        assertReportsThroughTool(brain, "record_development_verdict");
        assertBrainCardinality(brain);
    }

    @Test
    void remoteFeedbackSharedPromptsNameTheirResultToolForEveryRetry()
            throws Exception
    {
        String stage = prompt(
                RemoteFeedbackRuntimeCoordinator.class, "stageSystemPrompt");
        String brain = prompt(
                RemoteFeedbackRuntimeCoordinator.class, "brainSystemPrompt");

        assertReportsThroughTool(stage, "record_feedback_repair");
        assertReportsThroughTool(brain, "record_development_verdict");
        assertBrainCardinality(brain);
    }

    private static String prompt(Class<?> owner, String methodName)
            throws Exception
    {
        Method method = owner.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, "test role skill");
    }

    private static void assertReportsThroughTool(String prompt, String tool)
    {
        assertThat(prompt)
                .contains("calling " + tool)
                .contains("as your last act")
                .contains("ends without it is discarded")
                // Not "your final message is not read": the CI recovery only
                // fires on non-blank final text, so telling the agent its
                // message is worthless silently disables it.
                .contains("final message is not the result")
                .contains("do not leave it empty")
                // The contradiction that broke the Local review before it was
                // converted: a system prompt still demanding raw JSON while the
                // user prompt asked for a tool call.
                .doesNotContain("Return exactly one raw JSON object")
                .doesNotContain("\"schemaVersion\":1");
    }

    private static void assertBrainCardinality(String prompt)
    {
        assertThat(prompt)
                .contains("APPROVED or CHANGES_REQUESTED")
                .contains("APPROVED takes an empty findings list")
                .contains("CHANGES_REQUESTED takes one or more non-blank findings");
    }
}
