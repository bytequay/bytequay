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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Registry of {@link AgentTool}s the API-lane loop can call. Spring
 * injects every {@link AgentTool} bean on the classpath; lookup is by
 * the tool's {@link AgentTool#name()} so the model and the registry
 * agree on the identifier.
 *
 * <p>B4 wires up the read-only tools (file IO, glob). The dispatcher
 * the LogicLoopThreadAgent calls assumes auto-allow for those; the
 * mutating tools land in B5 alongside the permission-gate hook the
 * CLI lane already has.
 */
@Component
public class LogicLoopToolRegistry
{
    private final Map<String, AgentTool> byName;

    public LogicLoopToolRegistry(List<AgentTool> tools)
    {
        requireNonNull(tools, "tools is null");
        ImmutableMap.Builder<String, AgentTool> b = ImmutableMap.builder();
        for (AgentTool tool : tools) {
            b.put(tool.name(), tool);
        }
        this.byName = b.buildOrThrow();
    }

    public List<AgentTool> list()
    {
        return ImmutableList.copyOf(byName.values());
    }

    public Optional<AgentTool> find(String name)
    {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /** Render the registry as the {@code tools} array Anthropic's
     *  Messages API expects: a list of
     *  {@code {"name", "description", "input_schema"}} objects. */
    public ArrayNode renderAsAnthropicTools(ObjectMapper mapper)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : byName.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("input_schema", tool.inputSchema());
            arr.add(node);
        }
        return arr;
    }

    /** Render the registry as the {@code tools} array OpenAI's and
     *  DeepSeek's chat-completions API expects: a list of
     *  {@code {"type": "function", "function": {"name", "description",
     *  "parameters"}}} objects. The {@code parameters} schema is the
     *  same JSON Schema object Anthropic calls {@code input_schema}. */
    public ArrayNode renderAsOpenAiTools(ObjectMapper mapper)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : byName.values()) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.inputSchema());
            wrapper.set("function", fn);
            arr.add(wrapper);
        }
        return arr;
    }
}
