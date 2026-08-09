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

import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodeGraphFirstStep
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeGraphFirstStep step = new CodeGraphFirstStep(new McpResponses(mapper));

    @Test
    void rejectsBroadBashSearchWithAnExactCodeGraphSuggestion()
    {
        Scope scope = scope();
        prepare(scope);

        ApprovalStepResult result = step.apply(bash(scope, "rg -n DailyCard"));

        assertThat(result).isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(((ApprovalStepResult.Resolve) result).response().toString())
                .contains("deny", "mcp__bytequay__codegraph_explore", "DailyCard", "symbol");
    }

    @Test
    void redirectsStructuredGrepAndGlobBeforeTheAutoGatingStep()
            throws Exception
    {
        Scope grepScope = scope();
        prepare(grepScope);
        assertThat(step.apply(context(grepScope, "Grep",
                mapper.readTree("{\"pattern\":\"Controller\",\"path\":\"backend/src\"}"))))
                .isInstanceOf(ApprovalStepResult.Resolve.class);

        Scope globScope = scope();
        prepare(globScope);
        ApprovalStepResult glob = step.apply(context(globScope, "Glob",
                mapper.readTree("{\"pattern\":\"**/*.tsx\"}")));
        assertThat(glob).isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(((ApprovalStepResult.Resolve) glob).response().toString())
                .contains("**/*.tsx", "call paths");
    }

    @Test
    void allowsExactChecksAndKnownFileReadsToContinue()
            throws Exception
    {
        Scope scope = scope();
        prepare(scope);

        assertThat(step.apply(bash(scope, "rg -F exact-value")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(context(scope, "Grep",
                mapper.readTree("{\"pattern\":\"Thing\",\"path\":\"src/Thing.java\"}"))))
                .isInstanceOf(ApprovalStepResult.Continue.class);
        assertThat(step.apply(context(scope, "Read",
                mapper.readTree("{\"file_path\":\"src/Thing.java\"}"))))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void codeGraphAttemptAndFailOpenCapBothUnlockNativeSearch()
    {
        Scope attempted = scope();
        prepare(attempted);
        CodeGraphFirstRuntime.markAttempted(attempted.threadId(), attempted.agentKey());
        assertThat(step.apply(bash(attempted, "rg Controller")))
                .isInstanceOf(ApprovalStepResult.Continue.class);

        Scope capped = scope();
        prepare(capped);
        assertThat(step.apply(bash(capped, "rg First")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(step.apply(bash(capped, "rg Second")))
                .isInstanceOf(ApprovalStepResult.Resolve.class);
        assertThat(step.apply(bash(capped, "rg Third")))
                .isInstanceOf(ApprovalStepResult.Continue.class);
    }

    @Test
    void ambiguousMutatingShellAndUnscopedCallsContinueToTheirRealGuards()
    {
        Scope scope = scope();
        prepare(scope);
        assertThat(step.apply(bash(scope, "rg Thing && rm -rf build")))
                .isInstanceOf(ApprovalStepResult.Continue.class);

        ObjectNode input = mapper.createObjectNode().put("command", "rg Thing");
        ApprovalContext unscoped = new ApprovalContext(
                scope.threadId(), scope.taskId(), null,
                JsonNodeFactory.instance.numberNode(1), "Bash", "call-1", input, ImmutableSet.of());
        assertThat(step.apply(unscoped)).isInstanceOf(ApprovalStepResult.Continue.class);
    }

    private void prepare(Scope scope)
    {
        CodeGraphFirstRuntime.prepare(
                new ProcessBuilder("/usr/bin/true"), scope.threadId(), scope.agentKey());
    }

    private ApprovalContext bash(Scope scope, String command)
    {
        return context(scope, "Bash", mapper.createObjectNode().put("command", command));
    }

    private ApprovalContext context(Scope scope, String toolName, JsonNode input)
    {
        return new ApprovalContext(
                scope.threadId(), scope.taskId(), scope.agentKey(),
                JsonNodeFactory.instance.numberNode(1), toolName, "call-1", input, ImmutableSet.of());
    }

    private static Scope scope()
    {
        String suffix = UUID.randomUUID().toString();
        return new Scope("thread-" + suffix, "task-" + suffix, "task-" + suffix);
    }

    private record Scope(String threadId, String taskId, String agentKey) {}
}
