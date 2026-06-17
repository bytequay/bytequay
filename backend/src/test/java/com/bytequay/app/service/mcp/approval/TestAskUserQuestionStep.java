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
package com.bytequay.app.service.mcp.approval;

import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestAskUserQuestionStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final AskUserQuestionStep step = new AskUserQuestionStep(responses);

    @Test
    void deniesAskUserQuestionSoTheTurnEndsAndTheCardRenders()
    {
        ApprovalStepResult result = step.apply(ctx(AskUserQuestionStep.TARGET_TOOL));
        assertThat(result).isInstanceOf(ApprovalStepResult.Resolve.class);
        // Denying the tool is how the CLI is told to stop the turn; the
        // user replies via the rendered card, not the tool result.
        assertThat(((ApprovalStepResult.Resolve) result).response().toString()).contains("deny");
    }

    @Test
    void everyOtherToolFallsThrough()
    {
        assertThat(step.apply(ctx("Bash")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(ctx("mcp__bytequay__open_pr")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private ApprovalContext ctx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), Set.of());
    }
}
