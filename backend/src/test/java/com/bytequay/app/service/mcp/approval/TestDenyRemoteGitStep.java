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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestDenyRemoteGitStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final DenyRemoteGitStep step = new DenyRemoteGitStep(responses);

    @Test
    void deniesARawGitPushAndExplainsTheRightTool()
    {
        ApprovalStepResult result = step.apply(bash("git push origin dev/x"));

        assertThat(result).isInstanceOf(ApprovalStepResult.Resolve.class);
        String body = denyText((ApprovalStepResult.Resolve) result);
        assertThat(body).contains("deny");
        assertThat(body).contains("git push");
        assertThat(body).contains("push");
    }

    @Test
    void deniesAGhPrCreate()
    {
        assertThat(step.apply(bash("gh pr create --fill")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void letsReadOnlyShellContinueDownTheChain()
    {
        assertThat(step.apply(bash("git status")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(bash("gh pr view 5")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void ignoresNonShellTools()
    {
        assertThat(step.apply(ctx("mcp__bytequay__open_pr", mapper.createObjectNode())))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private String denyText(ApprovalStepResult.Resolve resolve)
    {
        // The deny envelope is framed as a JSON-RPC result with a text
        // content block; the agent-visible message is in that text.
        return resolve.response().toString();
    }

    private ApprovalContext bash(String command)
    {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return ctx("Bash", input);
    }

    private ApprovalContext ctx(String toolName, JsonNode input)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", input, ImmutableSet.of());
    }
}
