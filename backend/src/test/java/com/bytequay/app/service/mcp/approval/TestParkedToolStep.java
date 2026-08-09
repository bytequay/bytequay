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
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestParkedToolStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final AgentToolRegistry registry = mock(AgentToolRegistry.class);
    private final ParkedToolStep step = new ParkedToolStep(registry, responses);

    @Test
    void parkedToolIsAutoAllowedSoItCanParkItsProposal()
    {
        stub("open_pr", Gating.PARKED);
        assertThat(step.apply(ctx("mcp__bytequay__open_pr")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
    }

    @Test
    void gatedToolFallsThroughToThePrompt()
    {
        stub("run_shell", Gating.GATED);
        assertThat(step.apply(ctx("mcp__bytequay__run_shell")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void autoToolIsLeftToTheAutoGatingStep()
    {
        stub("list_tools", Gating.AUTO);
        assertThat(step.apply(ctx("mcp__bytequay__list_tools")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void unknownOrBuiltinToolFallsThrough()
    {
        when(registry.byName("Bash")).thenReturn(Optional.empty());
        assertThat(step.apply(ctx("Bash")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private void stub(String shortName, Gating gating)
    {
        ToolSpec spec = mock(ToolSpec.class);
        when(spec.gating()).thenReturn(gating);
        when(registry.byName(shortName)).thenReturn(Optional.of(spec));
    }

    private ApprovalContext ctx(String toolName)
    {
        return new ApprovalContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                toolName, "call-1", mapper.createObjectNode(), ImmutableSet.of());
    }
}
