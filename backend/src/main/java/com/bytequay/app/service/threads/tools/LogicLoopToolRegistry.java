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

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
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
import java.util.Set;
import java.util.UUID;

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
        return renderAsAnthropicTools(mapper, null);
    }

    /** Allowlist-filtered variant. When {@code allowedNames} is non-null,
     *  only tools whose {@link AgentTool#name()} is in the set make it
     *  into the rendered array. Null = no filter (legacy behaviour). */
    public ArrayNode renderAsAnthropicTools(ObjectMapper mapper, Set<String> allowedNames)
    {
        return renderAsAnthropicTools(mapper, allowedNames, /* role */ null, /* kind */ null);
    }

    /** Role/kind-filtered variant for production API-lane turns. When
     *  {@code allowedNames} is non-null it is applied in addition to the
     *  {@link ToolSpec} role + kind contract. */
    public ArrayNode renderAsAnthropicTools(
            ObjectMapper mapper, Set<String> allowedNames, AgentRole role, ThreadKind kind)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : combinedView()) {
            if (!isVisible(tool, allowedNames, role, kind)) {
                continue;
            }
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
        return renderAsOpenAiTools(mapper, null);
    }

    /** Allowlist-filtered variant for OpenAI / DeepSeek. Mirrors
     *  {@link #renderAsAnthropicTools(ObjectMapper, Set)}. */
    public ArrayNode renderAsOpenAiTools(ObjectMapper mapper, Set<String> allowedNames)
    {
        return renderAsOpenAiTools(mapper, allowedNames, /* role */ null, /* kind */ null);
    }

    /** Role/kind-filtered variant for OpenAI / DeepSeek. Mirrors
     *  {@link #renderAsAnthropicTools(ObjectMapper, Set, AgentRole, ThreadKind)}. */
    public ArrayNode renderAsOpenAiTools(
            ObjectMapper mapper, Set<String> allowedNames, AgentRole role, ThreadKind kind)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (AgentTool tool : combinedView()) {
            if (!isVisible(tool, allowedNames, role, kind)) {
                continue;
            }
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

    private static boolean isVisible(
            AgentTool tool, Set<String> allowedNames, AgentRole role, ThreadKind kind)
    {
        if (allowedNames != null && !allowedNames.contains(tool.name())) {
            return false;
        }
        if (tool instanceof BridgedTool bridged) {
            return bridged.visibleTo(role, kind);
        }
        return true;
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
            // Auto-gated tools (recall_memory, lookup_memory, list_*,
            // read_*) are the read-only set. Anything that needs a
            // user prompt or parking is mutating by definition.
            return spec.gating() == Gating.AUTO;
        }

        @Override
        public Result invoke(JsonNode input, AgentToolContext ctx)
        {
            AgentRole role = ctx.taskId() == null ? AgentRole.TRUNK : AgentRole.TASK;
            if (!spec.availableTo(role)) {
                return Result.error("Tool '" + spec.name()
                        + "' is not available to the current role (" + role + ").");
            }
            if (!availableToKind(ctx.threadKind())) {
                return Result.error("Tool '" + spec.name()
                        + "' is not available to the current thread kind ("
                        + ctx.threadKind() + ").");
            }
            JsonNode args = input == null ? mapper.createObjectNode() : input;
            String callId = "tool-" + UUID.randomUUID();

            // Permission gate for anything that's not auto-allowed.
            // PARKED tools (request_review, push, merge_pr, …) ride
            // the CLI lane's notification + publish-service flow
            // today; the API lane refuses them with a clear pointer
            // rather than silently no-op'ing or running on the
            // model's word.
            if (spec.gating() != Gating.AUTO) {
                if (ctx.permissionMediator() == null) {
                    return Result.error("Tool '" + spec.name() + "' requires a "
                            + spec.gating() + " gate but no permission mediator is wired. "
                            + "Refusing for safety.");
                }
                PermissionDecision decision = ctx.permissionMediator().admit(
                        callId, spec.name(), spec.gating(), summariseInput(spec.name(), args));
                if (decision != PermissionDecision.ALLOW) {
                    String reason = spec.gating() == Gating.PARKED
                            ? "Tool '" + spec.name() + "' is published through the parked-"
                                    + "proposal flow (Notifications → Approve), which the "
                                    + "API lane doesn't drive in v1. Use a CLI work model "
                                    + "for tools that publish to GitHub or mutate shared "
                                    + "state."
                            : "User denied tool call: " + spec.name() + ".";
                    return Result.error(reason);
                }
            }

            ToolCall call = new ToolCall(ctx.threadId(), args, role, ctx.taskId(), ctx.stageId());
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

        /** One-line description the Allow / Deny banner renders to
         *  the user. Surface the tool name + a short args preview
         *  (capped) so the user has enough to decide without
         *  expanding a JSON blob. */
        private String summariseInput(String toolName, JsonNode args)
        {
            String preview;
            try {
                preview = mapper.writeValueAsString(args);
            }
            catch (Exception e) {
                preview = String.valueOf(args);
            }
            if (preview.length() > 200) {
                preview = preview.substring(0, 200) + "…";
            }
            return toolName + " " + preview;
        }

        boolean visibleTo(AgentRole role, ThreadKind kind)
        {
            boolean roleOk = role == null || spec.availableTo(role);
            boolean kindOk = kind == null || availableToKind(kind);
            return roleOk && kindOk;
        }

        private boolean availableToKind(ThreadKind kind)
        {
            return kind == null ? spec.kinds().isEmpty() : spec.availableToKind(kind);
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
