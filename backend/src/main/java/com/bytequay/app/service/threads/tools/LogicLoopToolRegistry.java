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

import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolOutcome;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Registry of tools the API-lane loop exposes to the model.
 *
 * <p>Two sources are combined behind one façade:
 *
 * <ul>
 *   <li><strong>Native API-lane tools</strong> — Spring-discovered
 *       {@link AgentTool} beans on the classpath (today: just
 *       {@link ReadFileTool}). These exist because Claude Code's MCP
 *       server didn't ship a generic file-read tool, so the CLI-lane
 *       registry doesn't have one to delegate to.</li>
 *   <li><strong>CLI-lane bridge</strong> — every {@link ToolSpec}
 *       discovered by the existing
 *       {@link com.bytequay.app.service.tools.AgentToolRegistry}.
 *       The bridge wraps each spec as an {@link AgentTool} that
 *       dispatches through the CLI-lane registry's
 *       {@code invoke(name, ToolCall)} entry point, so the API lane
 *       gets {@code recall_memory}, {@code lookup_memory},
 *       {@code create_task}, {@code list_skills}, {@code list_terms}
 *       and the rest of the catalog for free. No duplicate
 *       implementations.</li>
 * </ul>
 *
 * <p>Tools whose {@link ToolSpec#handlerMethod()} returns
 * {@code void} are declaration-only stubs (their behaviour lives in
 * lane-specific hand-coded dispatch in the CLI lane and hasn't
 * migrated to the registry yet) and are filtered out of the API
 * lane's catalog — the CLI-lane registry's {@code invoke} returns
 * {@code Optional.empty()} on those, which would surface as a
 * confusing "Tool not dispatchable" error otherwise.
 */
@Component
public class LogicLoopToolRegistry
{
    private static final Logger log = LoggerFactory.getLogger(LogicLoopToolRegistry.class);

    private final Map<String, AgentTool> nativeByName;
    private final AgentToolRegistry cliLaneTools;
    private final ObjectMapper mapper;

    public LogicLoopToolRegistry(
            List<AgentTool> nativeTools,
            AgentToolRegistry cliLaneTools,
            ObjectMapper mapper)
    {
        requireNonNull(nativeTools, "nativeTools is null");
        this.cliLaneTools = requireNonNull(cliLaneTools, "cliLaneTools is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        ImmutableMap.Builder<String, AgentTool> b = ImmutableMap.builder();
        for (AgentTool tool : nativeTools) {
            b.put(tool.name(), tool);
        }
        this.nativeByName = b.buildOrThrow();
    }

    public List<AgentTool> list()
    {
        return combinedView();
    }

    public Optional<AgentTool> find(String name)
    {
        if (name == null) {
            return Optional.empty();
        }
        AgentTool nativeT = nativeByName.get(name);
        if (nativeT != null) {
            return Optional.of(nativeT);
        }
        return cliLaneTools.byName(name)
                .filter(LogicLoopToolRegistry::isDispatchable)
                .map(this::bridge);
    }

    /** Render the registry as the {@code tools} array Anthropic's
     *  Messages API expects: a list of
     *  {@code {"name", "description", "input_schema"}} objects. */
    public ArrayNode renderAsAnthropicTools(ObjectMapper mapper)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : combinedView()) {
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
     *  "parameters"}}} objects. */
    public ArrayNode renderAsOpenAiTools(ObjectMapper mapper)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : combinedView()) {
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

    private List<AgentTool> combinedView()
    {
        // Order: native tools first (small set, deterministic), then
        // the CLI-lane catalog (sorted-by-name from the upstream
        // registry). Using LinkedHashMap so dedup keeps the native
        // entry when a CLI-lane spec happens to share a name.
        LinkedHashMap<String, AgentTool> out = new LinkedHashMap<>(nativeByName);
        for (ToolSpec spec : cliLaneTools.all()) {
            if (!isDispatchable(spec)) {
                continue;
            }
            out.putIfAbsent(spec.name(), bridge(spec));
        }
        return ImmutableList.copyOf(out.values());
    }

    private static boolean isDispatchable(ToolSpec spec)
    {
        return spec.handlerMethod().getReturnType() == ToolOutcome.class;
    }

    /** Wrap a CLI-lane {@link ToolSpec} as an {@link AgentTool} the
     *  API lane can call. Schema is parsed once into a JsonNode so
     *  every render pass doesn't reparse the string. */
    private AgentTool bridge(ToolSpec spec)
    {
        JsonNode parsedSchema = parseSchema(spec);
        return new BridgedTool(spec, parsedSchema, mapper, cliLaneTools);
    }

    private JsonNode parseSchema(ToolSpec spec)
    {
        try {
            return mapper.readTree(spec.inputSchema());
        }
        catch (JsonProcessingException e) {
            log.warn("Tool {} has malformed inputSchema; surfacing empty object schema: {}",
                    spec.name(), e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** Adapter that lets the API lane invoke a CLI-lane handler.
     *  Each instance is bound to one {@link ToolSpec} and forwards
     *  through {@link AgentToolRegistry#invoke}; the role is derived
     *  from the per-call context. */
    private static final class BridgedTool
            implements AgentTool
    {
        private final ToolSpec spec;
        private final JsonNode parsedSchema;
        private final ObjectMapper mapper;
        private final AgentToolRegistry cliLaneTools;

        BridgedTool(ToolSpec spec, JsonNode parsedSchema, ObjectMapper mapper,
                AgentToolRegistry cliLaneTools)
        {
            this.spec = spec;
            this.parsedSchema = parsedSchema;
            this.mapper = mapper;
            this.cliLaneTools = cliLaneTools;
        }

        @Override
        public String name()
        {
            return spec.name();
        }

        @Override
        public String description()
        {
            return spec.description();
        }

        @Override
        public JsonNode inputSchema()
        {
            return parsedSchema;
        }

        @Override
        public boolean isReadOnly()
        {
            // The CLI-lane registry encodes mutation surface on
            // SecurityType, but the API lane doesn't gate on that yet
            // — every tool runs without a confirmation prompt. Mark
            // everything read-only so the existing isReadOnly() check
            // doesn't reject anything; the proper permission gate
            // lands alongside the CLI lane's parked-proposal flow.
            return true;
        }

        @Override
        public Result invoke(JsonNode input, AgentToolContext ctx)
        {
            AgentRole role = ctx.taskId() == null ? AgentRole.TRUNK : AgentRole.TASK;
            JsonNode args = input == null ? mapper.createObjectNode() : input;
            ToolCall call = new ToolCall(ctx.threadId(), args, role);
            try {
                Optional<ToolOutcome> outcome = cliLaneTools.invoke(spec.name(), call);
                if (outcome.isEmpty()) {
                    return Result.error("Tool '" + spec.name() + "' is not dispatchable on "
                            + "the API lane yet (CLI lane still owns this entry point).");
                }
                if (outcome.get() instanceof ToolOutcome.Completed completed) {
                    return new Result(completed.text(), completed.isError());
                }
                return Result.error("Tool '" + spec.name() + "' returned an unexpected outcome shape: "
                        + outcome.get().getClass().getSimpleName());
            }
            catch (RuntimeException e) {
                return Result.error("Tool '" + spec.name() + "' threw: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
    }

    /** Test-friendly view of bridged tool names. Used by smoke tests
     *  to confirm a refactor of the CLI-lane registry didn't drop a
     *  tool the API lane needs. */
    public List<String> bridgedToolNames()
    {
        List<String> out = new ArrayList<>();
        for (ToolSpec spec : cliLaneTools.all()) {
            if (isDispatchable(spec)) {
                out.add(spec.name());
            }
        }
        return ImmutableList.copyOf(out);
    }
}
