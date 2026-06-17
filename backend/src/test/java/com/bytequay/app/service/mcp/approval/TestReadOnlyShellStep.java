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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestReadOnlyShellStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final ReadOnlyShellStep step = new ReadOnlyShellStep(responses);

    @Test
    void autoApprovesAProvablyReadOnlyShellCommand()
    {
        assertThat(step.apply(bash("git status")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(step.apply(bash("grep -rn foo src | head")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void aNonReadOnlyShellCommandFallsThroughToThePrompt()
    {
        assertThat(step.apply(bash("rm -rf build")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(bash("git push")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void ignoresNonShellTools()
    {
        assertThat(step.apply(ctx("mcp__bytequay__open_pr", mapper.createObjectNode())))
                .isInstanceOf(ApprovalStepResult.Continue.class);
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
                toolName, "call-1", input, Set.of());
    }
}
