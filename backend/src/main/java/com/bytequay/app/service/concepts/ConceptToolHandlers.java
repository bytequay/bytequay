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
package com.bytequay.app.service.concepts;

import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentTool;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolOutcome;
import com.bytequay.app.service.tools.ToolParam;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * The two meta-tools that let an agent introspect the concept
 * registry at turn time. Both are read-only AUTO-gated tools under
 * {@link SecurityType#CONCEPT_USE} — every role has the capability
 * by default so an agent can always look up what a term means.
 *
 * <ul>
 *   <li>{@code list_terms(query?, kind?)} returns a filtered
 *       manifest of one-line briefs.</li>
 *   <li>{@code lookup_term(name)} returns the full spec plus any
 *       alternates from less-specific scopes, so a USER-defined
 *       term and the APP-scoped seed it shadows are both visible.</li>
 * </ul>
 *
 * <p>The manifest is filtered by the caller's grant: a concept that
 * declares {@code relatedTools} the role can't actually call is
 * dropped, because surfacing it would only mislead the agent into
 * trying. Concepts with no {@code relatedTools} are always visible.
 */
@Component
public class ConceptToolHandlers
{
    private final ConceptRegistry registry;
    private final AgentToolRegistry tools;
    private final PermissionResolver permissions;
    private final ObjectMapper mapper;

    public ConceptToolHandlers(
            ConceptRegistry registry,
            AgentToolRegistry tools,
            PermissionResolver permissions,
            ObjectMapper mapper)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** One row in the {@code list_terms} response. */
    public record ConceptBrief(String name, String kind, String oneLineDefinition) {}

    /** Full response of {@code lookup_term}. {@code alternates} is
     *  the other-scope candidates for the same name, ordered USER
     *  &gt; WORKSPACE &gt; REPO &gt; APP and excluding the winner. */
    public record ConceptDetail(
            String name,
            List<String> aka,
            String kind,
            String definition,
            List<String> examples,
            List<String> relatedTools,
            List<String> relatedConcepts,
            String scope,
            String source,
            List<ConceptBrief> alternates) {}

    /** Args for {@code list_terms}. */
    public record ListTermsArgs(
            @ToolParam(description = "Optional free-text substring filter (case-insensitive); "
                    + "matches name + aka + first sentence of the definition.")
            String query,
            @ToolParam(description = "Optional kind filter; values map 1:1 to ConceptKind.")
            String kind) {}

    @AgentTool(
            name = "list_terms",
            description = "List domain terms — the third metadata axis. Filter by free-text "
                    + "query and / or kind (NOUN, STATE, FILTER, VERB). Use this when "
                    + "a user message mentions a word like 'urgent' or 'parked' and "
                    + "you want a deterministic definition instead of guessing.",
            security = SecurityType.CONCEPT_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome listTerms(ListTermsArgs args, ToolCall call)
    {
        ConceptKind kindFilter = parseKind(args.kind());
        if (args.kind() != null && !args.kind().isBlank() && kindFilter == null) {
            return ToolOutcome.Completed.error(
                    "unknown kind: " + args.kind() + " (expected NOUN/STATE/FILTER/VERB)");
        }
        String needle = args.query() == null ? "" : args.query().toLowerCase(Locale.ROOT).trim();
        // Resolve role + grants against THIS call's own agent (its stamped
        // task/stage), not the thread's first running turn — under concurrent
        // stage agents on one thread that would otherwise read a sibling's
        // scope. The role is already stamped on the call at dispatch.
        AgentRole role = call.role();
        Set<SecurityType> grants = permissions.grants(
                call.threadId(), PermissionResolver.agentKeyFor(call.taskId(), call.stageId()));
        List<ConceptBrief> briefs = registry.list(kindFilter).stream()
                .filter(s -> visibleToRole(s, role, grants))
                .filter(s -> matchesQuery(s, needle))
                .map(s -> new ConceptBrief(s.name(), s.kind().name(), s.oneLineDefinition()))
                .toList();
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(briefs));
        }
        catch (JsonProcessingException e) {
            return ToolOutcome.Completed.error("failed to serialise concept briefs: " + e.getMessage());
        }
    }

    /** Args for {@code lookup_term}. */
    public record LookupTermArgs(
            @ToolParam(description = "Concept name to resolve. Returns the full spec plus "
                    + "any alternates from less-specific scopes.",
                    required = true) String name) {}

    @AgentTool(
            name = "lookup_term",
            description = "Resolve one concept by name. Returns the most-specific spec "
                    + "(USER > WORKSPACE > REPO > APP) plus alternates from less-"
                    + "specific scopes so the resolution stays auditable.",
            security = SecurityType.CONCEPT_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome lookupTerm(LookupTermArgs args, ToolCall call)
    {
        if (args.name() == null || args.name().isBlank()) {
            return ToolOutcome.Completed.error("name is required");
        }
        Optional<ConceptRegistry.Alternates> hit = registry.lookup(args.name().trim());
        if (hit.isEmpty()) {
            return ToolOutcome.Completed.error("no concept named '" + args.name() + "'");
        }
        ConceptSpec winner = hit.get().winner();
        List<ConceptBrief> alternates = hit.get().alternates().stream()
                .map(s -> new ConceptBrief(s.name(), s.kind().name(), s.oneLineDefinition()))
                .toList();
        ConceptDetail detail = new ConceptDetail(
                winner.name(),
                winner.aka(),
                winner.kind().name(),
                winner.definition(),
                winner.examples(),
                winner.relatedTools(),
                winner.relatedConcepts(),
                winner.scope().name(),
                winner.source(),
                alternates);
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(detail));
        }
        catch (JsonProcessingException e) {
            return ToolOutcome.Completed.error("failed to serialise concept detail: " + e.getMessage());
        }
    }

    /** A concept is visible to a role iff every {@code relatedTool}
     *  it points at is callable by that role. Concepts with no
     *  {@code relatedTools} are always visible — they're definitions
     *  that don't depend on the tool catalog. */
    private boolean visibleToRole(ConceptSpec spec, AgentRole role, Set<SecurityType> grants)
    {
        if (spec.relatedTools().isEmpty()) {
            return true;
        }
        for (String toolName : spec.relatedTools()) {
            ToolSpec target = tools.byName(toolName).orElse(null);
            if (target == null) {
                continue;
            }
            if (!target.availableTo(role)) {
                return false;
            }
            if (!grants.contains(target.security())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesQuery(ConceptSpec spec, String needle)
    {
        if (needle.isEmpty()) {
            return true;
        }
        if (spec.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String alias : spec.aka()) {
            if (alias.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return spec.oneLineDefinition().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ConceptKind parseKind(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ConceptKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
