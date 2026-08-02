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

class TestStrictJsonAgentPromptContracts
{
    @Test
    void remoteRepairStageAndBrainPromptsRequireRawJson()
            throws Exception
    {
        String stage = prompt(RemoteRepairTurnRuntime.class, "stageSystemPrompt");
        String brain = prompt(RemoteRepairTurnRuntime.class, "brainSystemPrompt");

        assertRawJson(stage);
        assertThat(stage).contains(
                "{\"schemaVersion\":1,\"summary\":\"string\"}");
        assertRawJson(brain);
        assertBrainCardinality(brain);
    }

    @Test
    void remoteFeedbackSharedPromptsKeepSchemasForEveryRetry()
            throws Exception
    {
        String stage = prompt(
                RemoteFeedbackRuntimeCoordinator.class, "stageSystemPrompt");
        String brain = prompt(
                RemoteFeedbackRuntimeCoordinator.class, "brainSystemPrompt");

        assertRawJson(stage);
        assertThat(stage)
                .contains("\"schemaVersion\":1")
                .contains("\"summary\":\"string\"")
                .contains("\"replies\":[")
                .contains("\"batchItemOrdinal\":1")
                .contains("POST_INLINE_REPLY, POST_TOP_LEVEL_REPLY, or RESOLVE_THREAD")
                .contains("RESOLVE_THREAD requires body=null");
        assertRawJson(brain);
        assertBrainCardinality(brain);
    }

    private static String prompt(Class<?> owner, String methodName)
            throws Exception
    {
        Method method = owner.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, "test role skill");
    }

    private static void assertRawJson(String prompt)
    {
        assertThat(prompt)
                .contains("Return exactly one raw JSON object")
                .contains("first non-whitespace character must be '{'")
                .contains("last non-whitespace character must be '}'")
                .contains("Do not wrap it in Markdown fences")
                .contains("or add prose before or after it");
    }

    private static void assertBrainCardinality(String prompt)
    {
        assertThat(prompt)
                .contains("\"verdict\":\"APPROVED\"")
                .contains("\"findings\":[]")
                .contains("APPROVED requires an empty findings array")
                .contains("CHANGES_REQUESTED requires one or more")
                .contains("non-blank finding strings");
    }
}
