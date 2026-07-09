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
package com.bytequay.app.service.threads.tools;

import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.service.threads.LogicLoopThreadAgent;
import com.bytequay.app.service.tools.AgentRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring-boot exercise of the bridge layer. Confirms the API-lane
 * registry surfaces the CLI-lane catalog so the model can call
 * {@code recall_memory} and friends through the
 * {@link com.bytequay.app.service.threads.LogicLoopThreadAgent}
 * without each tool being re-implemented in {@code service.threads.tools}.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestLogicLoopToolRegistryBridge
{
    @Autowired
    private LogicLoopToolRegistry registry;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void exposesTheCliLaneCatalogIncludingTheCoreMemoryAndDiscoveryTools()
    {
        List<String> bridged = registry.bridgedToolNames();
        // The conversation that prompted the bridge surfaced
        // recall_memory / lookup_memory specifically; assert those.
        // The rest of the catalog is in flux so we don't pin every
        // name — but the discovery + memory surface should be the
        // floor for any future trim.
        assertThat(bridged).contains(
                "recall_memory",
                "lookup_memory",
                "list_tools",
                "list_skills");
    }

    @Test
    void rendersBridgedToolsIntoTheAnthropicShapeWithAnInputSchema()
    {
        ArrayNode anthropicTools = registry.renderAsAnthropicTools(mapper);
        // At least the native read_file + a non-empty CLI bridge.
        assertThat(anthropicTools.size()).isGreaterThan(1);
        boolean foundRecall = false;
        for (int i = 0; i < anthropicTools.size(); i++) {
            var node = anthropicTools.get(i);
            assertThat(node.has("name")).isTrue();
            assertThat(node.has("description")).isTrue();
            // Anthropic Messages API rejects tools without the
            // verbatim input_schema field name.
            assertThat(node.has("input_schema")).isTrue();
            if ("recall_memory".equals(node.get("name").asText())) {
                foundRecall = true;
            }
        }
        assertThat(foundRecall)
                .as("recall_memory should be in the Anthropic-shape tools array")
                .isTrue();
    }

    @Test
    void rendersBridgedToolsIntoTheOpenAiShapeWithAFunctionWrapper()
    {
        ArrayNode openAiTools = registry.renderAsOpenAiTools(mapper);
        assertThat(openAiTools.size()).isGreaterThan(1);
        boolean foundRecall = false;
        for (int i = 0; i < openAiTools.size(); i++) {
            var node = openAiTools.get(i);
            assertThat(node.get("type").asText()).isEqualTo("function");
            assertThat(node.has("function")).isTrue();
            var fn = node.get("function");
            assertThat(fn.has("name")).isTrue();
            assertThat(fn.has("description")).isTrue();
            assertThat(fn.has("parameters")).isTrue();
            if ("recall_memory".equals(fn.get("name").asText())) {
                foundRecall = true;
            }
        }
        assertThat(foundRecall)
                .as("recall_memory should be in the OpenAI-shape tools array")
                .isTrue();
    }

    @Test
    void taskLogicLoopCatalogFiltersRoleAndThreadKind()
    {
        ArrayNode taskTools = registry.renderAsAnthropicTools(
                mapper, null, AgentRole.TASK, ThreadKind.LOGIC_LOOP);

        List<String> names = anthropicNames(taskTools);
        assertThat(names).contains("record_pr_comment", "resolve_pr_comment");
        assertThat(names).doesNotContain("create_task", "record_plan", "record_review_verdict");
    }

    @Test
    void brainCatalogUsesAllowlistOnTopOfRoleAndKindFilters()
    {
        ArrayNode brainTools = registry.renderAsAnthropicTools(
                mapper, LogicLoopThreadAgent.BRAIN_TOOL_ALLOWLIST,
                AgentRole.TASK, ThreadKind.BRAIN_AGENT);

        List<String> names = anthropicNames(brainTools);
        assertThat(names).contains("record_plan", "record_pr_comment", "record_review_verdict");
        assertThat(names).doesNotContain(
                "create_task", "record_round_reply", "resolve_pr_comment", "push", "merge_pr");
    }

    @Test
    void bridgedInvocationRefusesWrongThreadKindBeforeCallingHandler()
    {
        var input = mapper.createObjectNode();
        input.put("scope", "dev");
        input.put("verdict", "approved");

        var result = registry.find("record_review_verdict").orElseThrow().invoke(
                input,
                new AgentToolContext(
                        "thread-1", "task-1", Path.of("."), null,
                        "stage-1", ThreadKind.LOGIC_LOOP));

        assertThat(result.isError()).isTrue();
        assertThat(result.text())
                .contains("not available to the current thread kind")
                .contains("LOGIC_LOOP");
    }

    @Test
    void findsRecallMemoryByNameAndReportsTheBridgedSchema()
    {
        var tool = registry.find("recall_memory").orElseThrow();
        assertThat(tool.name()).isEqualTo("recall_memory");
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.inputSchema()).isNotNull();
    }

    private static List<String> anthropicNames(ArrayNode tools)
    {
        ImmutableList.Builder<String> names = ImmutableList.builder();
        for (int i = 0; i < tools.size(); i++) {
            names.add(tools.get(i).get("name").asText());
        }
        return names.build();
    }
}
