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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestReadFileTool
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadFileTool tool = new ReadFileTool(mapper);

    @Test
    void readsAFileRelativeToTheWorkingDir(@TempDir Path workingDir)
            throws IOException
    {
        Files.writeString(workingDir.resolve("hello.txt"), "Hi from the tool", StandardCharsets.UTF_8);

        AgentTool.Result result = tool.invoke(input("hello.txt"),
                new AgentToolContext("thread-1", null, workingDir));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isEqualTo("Hi from the tool");
    }

    @Test
    void refusesPathsThatEscapeTheWorkingDir(@TempDir Path workingDir)
    {
        AgentTool.Result result = tool.invoke(input("../../etc/passwd"),
                new AgentToolContext("thread-1", null, workingDir));

        // No accidental file serve outside the sandbox even on the
        // read path — the tool reports an error and the model can
        // course-correct on the next turn.
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).containsIgnoringCase("escape");
    }

    @Test
    void rejectsMissingPathArgument(@TempDir Path workingDir)
    {
        AgentTool.Result result = tool.invoke(mapper.createObjectNode(),
                new AgentToolContext("thread-1", null, workingDir));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).containsIgnoringCase("path");
    }

    @Test
    void returnsAClearErrorWhenTheFileIsMissing(@TempDir Path workingDir)
    {
        AgentTool.Result result = tool.invoke(input("nope.txt"),
                new AgentToolContext("thread-1", null, workingDir));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("No such file");
    }

    @Test
    void exposesTheToolInRegistryAndRendersAnthropicShape()
    {
        LogicLoopToolRegistry registry = new LogicLoopToolRegistry(List.of(tool));

        assertThat(registry.list()).extracting(AgentTool::name).containsExactly("read_file");
        var rendered = registry.renderAsAnthropicTools(mapper);
        assertThat(rendered).hasSize(1);
        assertThat(rendered.get(0).get("name").asText()).isEqualTo("read_file");
        // Anthropic Messages API expects this exact field name.
        assertThat(rendered.get(0).has("input_schema")).isTrue();
    }

    private ObjectNode input(String path)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put("path", path);
        return node;
    }
}
