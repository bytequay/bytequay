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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Provider-neutral skill catalog diagnostics. ByteQuay's context
 * compiler selects skills before a turn; these operations are not
 * registered as model-facing runtime tools.
 *
 * <h3>Wire contract</h3>
 *
 * Each handler returns a Jackson-serialisable record ({@link SkillSummary},
 * {@link SkillBody}, {@link ToolCatalogEntry}) wrapped in a
 * {@link RuntimeToolInvocation}. The lane that consumes the outcome
 * serialises once at the wire boundary, so the bytes the model sees come
 * from one place and the handlers stay free of JSON plumbing.
 *
 * <h3>Cache-stability contract</h3>
 *
 * Frozen provider-shaped definitions remain for diagnostic clients and
 * backward-compatible serialization tests. Runtime adapters must expose
 * only the definitions selected by {@code AgentContextCompiler}.
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

    /** Tool catalog the {@code list_tools} handler returns. Reference-
     *  stable (same {@code List} instance every call) so the wire layer
     *  can rely on Jackson producing byte-identical output turn over
     *  turn — the cache-stability rule for tool results at the tail of
     *  history. */
    private static final List<ToolCatalogEntry> LIST_TOOLS_CATALOG = List.of(
            new ToolCatalogEntry("list_skills", LIST_SKILLS_DESCRIPTION, "skill"),
            new ToolCatalogEntry("list_tools", LIST_TOOLS_DESCRIPTION, "skill"),
            new ToolCatalogEntry("load_skill", LOAD_SKILL_DESCRIPTION, "skill"));

    private final SkillManifestService manifest;

    public SkillTools(SkillManifestService manifest)
    {
        this.manifest = requireNonNull(manifest, "manifest is null");
    }

    /**
     * Dispatch one tool call. The model passes the tool name and a
     * JSON object of arguments; we look up the body and return the
     * wire-shaped record the lane will serialise. The dispatcher is
     * the single entry point — providers should always go through here
     * so the cache key is stable regardless of which lane invoked it.
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
        JsonNode args = arguments == null ? MissingNode.getInstance() : arguments;
        return switch (toolName) {
            case "list_skills" -> listSkills(args.path("scope").asText(""), args.path("query").asText(""), context);
            case "list_tools" -> RuntimeToolInvocation.ok(LIST_TOOLS_CATALOG);
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
                : ImmutableSet.of(scope);
        SkillManifestQuery manifestQuery = new SkillManifestQuery(
                scopes,
                context.touchedRepos(),
                context.threadId(),
                context.role());
        List<SkillManifestEntry> entries = manifest.query(manifestQuery);
        String needle = filter.toLowerCase(Locale.ROOT);
        List<SkillSummary> summaries = entries.stream()
                .filter(e -> needle.isEmpty()
                        || (e.description() != null
                                && e.description().toLowerCase(Locale.ROOT).contains(needle)))
                .map(SkillSummary::from)
                .toList();
        return RuntimeToolInvocation.ok(summaries);
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
        return RuntimeToolInvocation.ok(new SkillBody(name, body.get()));
    }

    private static Set<String> defaultScopes(ToolContext context)
    {
        boolean hasRepo = context.touchedRepos() != null && !context.touchedRepos().isEmpty();
        boolean hasThread = context.threadId().isPresent();
        if (hasRepo && hasThread) {
            return ImmutableSet.of("global", "repo", "thread");
        }
        if (hasRepo) {
            return ImmutableSet.of("global", "repo");
        }
        if (hasThread) {
            return ImmutableSet.of("global", "thread");
        }
        return ImmutableSet.of("global");
    }

    /** Wire shape for one {@code list_skills} entry. Field order is
     *  fixed by record declaration — the wire layer's Jackson mapper
     *  follows that order, so a rename or reorder here changes the
     *  bytes the model sees. */
    public record SkillSummary(
            long id,
            String name,
            String description,
            String scope,
            String repo,
            @JsonProperty("role_tag") String roleTag,
            String kind)
    {
        static SkillSummary from(SkillManifestEntry e)
        {
            return new SkillSummary(
                    e.id(), e.name(), e.description(), e.scope(),
                    e.repo(), e.roleTag(), e.kind());
        }
    }

    /** Wire shape for {@code load_skill}'s result. */
    public record SkillBody(String name, String body) {}

    /** Wire shape for one entry in the {@code list_tools} catalog. */
    public record ToolCatalogEntry(String name, String description, String kind) {}
}
