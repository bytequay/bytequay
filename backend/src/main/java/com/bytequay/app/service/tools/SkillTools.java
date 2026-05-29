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
package com.bytequay.app.service.tools;

import com.bytequay.app.service.skills.SkillManifestEntry;
import com.bytequay.app.service.skills.SkillManifestQuery;
import com.bytequay.app.service.skills.SkillManifestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * The three always-present runtime tools — {@code list_skills},
 * {@code list_tools}, and {@code load_skill}.
 *
 * <h3>Cache-stability contract</h3>
 *
 * Tool definitions are returned as constant strings, byte-identical
 * across calls. The model's prefix cache (DeepSeek auto, Anthropic via
 * {@code cache_control}) only kicks in when the bytes preceding the
 * new turn are the same as the previous turn, so these definitions
 * must never depend on time, repo state, or query parameters. The
 * manifest itself is dynamic — but it appears in a tool result at the
 * tail of the history, not in the tool definitions or system prompt.
 *
 * <p>This class has no Spring-time state beyond the manifest service
 * dependency, so the same instance is safe to share across threads.
 */
@Component
public class SkillTools
{
    /** Order is part of the cached prefix when these are emitted as a
     *  JSON array. Never re-order without bumping the cache key.
     *
     *  Discussion: list_skills first so the model encounters the
     *  primary affordance up front; list_tools second so the catalog
     *  query is adjacent; load_skill third (the consumer of the
     *  manifest). */
    public static final List<String> TOOL_NAMES = List.of("list_skills", "list_tools", "load_skill");

    private static final String LIST_SKILLS_DESCRIPTION = ""
            + "List the skills available for the current turn. Returns a JSON array of "
            + "{id, name, description, scope, repo, role_tag, kind} entries. Skills are "
            + "model-triggered, not always-on — read the description (the \"loads when …\" "
            + "blurb) and decide whether to load the body via load_skill. Optional filters "
            + "narrow the result by scope or substring match against the description.";

    private static final String LIST_TOOLS_DESCRIPTION = ""
            + "List every tool available this turn, including action tools and the three "
            + "skill tools. Returns a JSON array of {name, description, kind} entries. "
            + "Useful when picking the right verb for an action.";

    private static final String LOAD_SKILL_DESCRIPTION = ""
            + "Load the body of one skill by its unique name. Returns a JSON object "
            + "{name, body}. Pair with list_skills — list to find the trigger that "
            + "matches the task, load to fetch the instructions.";

    /** Frozen JSON for the Anthropic tool-call shape. Hand-formatted so
     *  Jackson's possibly-shifting property order can't drift the bytes. */
    private static final String ANTHROPIC_LIST_SKILLS = ""
            + "{\"name\":\"list_skills\","
            + "\"description\":\"" + LIST_SKILLS_DESCRIPTION + "\","
            + "\"input_schema\":{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                    + "\"scope\":{\"type\":\"string\",\"description\":\"Optional scope filter — one of global, repo, thread. Omit to see all available.\"},"
                    + "\"query\":{\"type\":\"string\",\"description\":\"Optional substring match against the trigger description. Case-insensitive.\"}"
                + "},"
                + "\"required\":[]"
            + "}}";

    private static final String ANTHROPIC_LIST_TOOLS = ""
            + "{\"name\":\"list_tools\","
            + "\"description\":\"" + LIST_TOOLS_DESCRIPTION + "\","
            + "\"input_schema\":{"
                + "\"type\":\"object\","
                + "\"properties\":{},"
                + "\"required\":[]"
            + "}}";

    private static final String ANTHROPIC_LOAD_SKILL = ""
            + "{\"name\":\"load_skill\","
            + "\"description\":\"" + LOAD_SKILL_DESCRIPTION + "\","
            + "\"input_schema\":{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                    + "\"name\":{\"type\":\"string\",\"description\":\"Unique skill name from a prior list_skills entry.\"}"
                + "},"
                + "\"required\":[\"name\"]"
            + "}}";

    private static final String OPENAI_LIST_SKILLS = ""
            + "{\"type\":\"function\",\"function\":{"
            + "\"name\":\"list_skills\","
            + "\"description\":\"" + LIST_SKILLS_DESCRIPTION + "\","
            + "\"parameters\":{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                    + "\"scope\":{\"type\":\"string\"},"
                    + "\"query\":{\"type\":\"string\"}"
                + "},"
                + "\"required\":[]"
            + "}}}";

    private static final String OPENAI_LIST_TOOLS = ""
            + "{\"type\":\"function\",\"function\":{"
            + "\"name\":\"list_tools\","
            + "\"description\":\"" + LIST_TOOLS_DESCRIPTION + "\","
            + "\"parameters\":{"
                + "\"type\":\"object\","
                + "\"properties\":{},"
                + "\"required\":[]"
            + "}}}";

    private static final String OPENAI_LOAD_SKILL = ""
            + "{\"type\":\"function\",\"function\":{"
            + "\"name\":\"load_skill\","
            + "\"description\":\"" + LOAD_SKILL_DESCRIPTION + "\","
            + "\"parameters\":{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                    + "\"name\":{\"type\":\"string\"}"
                + "},"
                + "\"required\":[\"name\"]"
            + "}}}";

    /** Frozen JSON array of the Anthropic tool-call shape for the
     *  three skill tools. Byte-identical across calls. Action tools
     *  are appended at request-assembly time. */
    public static final String ANTHROPIC_DEFINITIONS_JSON =
            "[" + ANTHROPIC_LIST_SKILLS + "," + ANTHROPIC_LIST_TOOLS + "," + ANTHROPIC_LOAD_SKILL + "]";

    public static final String OPENAI_DEFINITIONS_JSON =
            "[" + OPENAI_LIST_SKILLS + "," + OPENAI_LIST_TOOLS + "," + OPENAI_LOAD_SKILL + "]";

    private static final String LIST_TOOLS_CATALOG_JSON = ""
            + "[{\"name\":\"list_skills\",\"description\":\"" + LIST_SKILLS_DESCRIPTION + "\",\"kind\":\"skill\"},"
            + "{\"name\":\"list_tools\",\"description\":\"" + LIST_TOOLS_DESCRIPTION + "\",\"kind\":\"skill\"},"
            + "{\"name\":\"load_skill\",\"description\":\"" + LOAD_SKILL_DESCRIPTION + "\",\"kind\":\"skill\"}]";

    private final SkillManifestService manifest;
    private final ObjectMapper mapper = jsonMapper();

    public SkillTools(SkillManifestService manifest)
    {
        this.manifest = requireNonNull(manifest, "manifest is null");
    }

    /**
     * Dispatch one tool call. The model passes the tool name and a
     * JSON object of arguments; we look up the body and return the
     * JSON the model sees back. The dispatcher is the single entry
     * point — providers should always go through here so the cache
     * key is stable regardless of which lane invoked it.
     *
     * @param toolName  one of {@link #TOOL_NAMES}
     * @param arguments the JSON object the model emitted as the tool's
     *                  input. Pass an empty object when the tool has
     *                  no parameters.
     * @param context   the turn context — the lane fills this in so
     *                  scope filters resolve correctly
     */
    public RuntimeToolInvocation dispatch(String toolName, JsonNode arguments, ToolContext context)
    {
        requireNonNull(toolName, "toolName is null");
        requireNonNull(context, "context is null");
        JsonNode args = arguments == null ? mapper.createObjectNode() : arguments;
        return switch (toolName) {
            case "list_skills" -> listSkills(args.path("scope").asText(""), args.path("query").asText(""), context);
            case "list_tools" -> RuntimeToolInvocation.ok(LIST_TOOLS_CATALOG_JSON);
            case "load_skill" -> loadSkill(args.path("name").asText(""));
            default -> RuntimeToolInvocation.error("unknown tool: " + toolName);
        };
    }

    /** Project the skill manifest for the turn, narrowed by an optional
     *  scope and an optional case-insensitive description match. The
     *  {@code list_skills} tool calls this directly; {@link #dispatch}
     *  routes to it for the provider lane. */
    public RuntimeToolInvocation listSkills(String requestedScope, String query, ToolContext context)
    {
        requireNonNull(context, "context is null");
        String scope = requestedScope == null ? "" : requestedScope;
        String filter = query == null ? "" : query;
        Set<String> scopes = scope.isEmpty()
                ? defaultScopes(context)
                : Set.of(scope);
        SkillManifestQuery manifestQuery = new SkillManifestQuery(
                scopes,
                context.touchedRepos(),
                context.threadId(),
                context.role());
        List<SkillManifestEntry> entries = manifest.query(manifestQuery);
        if (!filter.isEmpty()) {
            String needle = filter.toLowerCase(Locale.ROOT);
            entries = entries.stream()
                    .filter(e -> e.description() != null
                            && e.description().toLowerCase(Locale.ROOT).contains(needle))
                    .collect(Collectors.toUnmodifiableList());
        }
        ArrayNode array = mapper.createArrayNode();
        for (SkillManifestEntry e : entries) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", e.id());
            node.put("name", e.name());
            node.put("description", e.description());
            node.put("scope", e.scope());
            node.put("repo", e.repo());
            node.put("role_tag", e.roleTag());
            node.put("kind", e.kind());
            array.add(node);
        }
        return RuntimeToolInvocation.ok(serialise(array));
    }

    /** Load one skill's body by name. The {@code load_skill} tool calls
     *  this directly; {@link #dispatch} routes to it for the provider
     *  lane. */
    public RuntimeToolInvocation loadSkill(String skillName)
    {
        String name = skillName == null ? "" : skillName;
        if (name.isBlank()) {
            return RuntimeToolInvocation.error("name argument is required");
        }
        Optional<String> body = manifest.loadBody(name);
        if (body.isEmpty()) {
            return RuntimeToolInvocation.error("skill not found or disabled: " + name);
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.put("body", body.get());
        return RuntimeToolInvocation.ok(serialise(node));
    }

    private static Set<String> defaultScopes(ToolContext context)
    {
        boolean hasRepo = context.touchedRepos() != null && !context.touchedRepos().isEmpty();
        boolean hasThread = context.threadId().isPresent();
        if (hasRepo && hasThread) {
            return Set.of("global", "repo", "thread");
        }
        if (hasRepo) {
            return Set.of("global", "repo");
        }
        if (hasThread) {
            return Set.of("global", "thread");
        }
        return Set.of("global");
    }

    private String serialise(JsonNode node)
    {
        try {
            return mapper.writeValueAsString(node);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise tool result", e);
        }
    }

    private static ObjectMapper jsonMapper()
    {
        ObjectMapper m = new ObjectMapper();
        // Keep tool-result bytes byte-stable across calls and Jackson
        // versions — the model's prefix cache hashes on the previous
        // turn's bytes verbatim.
        m.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
        return m;
    }
}
