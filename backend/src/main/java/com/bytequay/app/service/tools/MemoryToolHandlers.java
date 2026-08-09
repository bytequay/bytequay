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

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Agent-facing read tools over the typed memory items.
 *
 * <ul>
 *   <li>{@code recall_memory} — filtered manifest of one-line
 *       briefs. The agent calls this <em>before</em> asking the
 *       user for a decision so it can cite prior context with
 *       provenance instead of re-litigating something the team
 *       already decided.</li>
 *   <li>{@code lookup_memory} — full item by id, including the
 *       successor on a superseded row so an agent never cites a
 *       dead decision.</li>
 * </ul>
 *
 * <p>Both gate on {@link SecurityType#MEMORY_READ}, which every
 * role's base grants carry. Ordering is deterministic so the same
 * input produces the same response bytes — the prefix cache hashes
 * the tool result, so a stable shape is load-bearing.
 */
@Component
public class MemoryToolHandlers
{
    /** Hard cap on rows returned by {@code recall_memory}. Keeps the
     *  response inside one tool-result message even when the
     *  workspace's memory bank is large. */
    private static final int RECALL_HARD_CAP = 50;

    /** Default limit when the caller leaves it null. */
    private static final int RECALL_DEFAULT_LIMIT = 20;

    /** Per-row one-line trim. Same shape the concept axis uses for
     *  list_terms — 120 chars or up to the first sentence boundary,
     *  whichever is shorter. */
    private static final int ONE_LINE_CAP = 120;

    private final SqliteMemoryItemStore store;
    private final ThreadStore threadStore;
    private final ObjectMapper mapper;

    public MemoryToolHandlers(SqliteMemoryItemStore store, ThreadStore threadStore, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Compact wire shape returned by {@code recall_memory}. */
    public record MemoryBrief(
            long id,
            String kind,
            String scope,
            String oneLineSummary,
            String confidence,
            List<MemoryItemSource> sources,
            Long supersededBy) {}

    /** Full wire shape returned by {@code lookup_memory}. Carries the
     *  live successor (if any) so the agent picks the current row
     *  for citation. */
    public record MemoryDetail(
            long id,
            String kind,
            String scope,
            String text,
            String confidence,
            List<MemoryItemSource> sources,
            List<String> tags,
            Long supersededBy,
            MemoryBrief liveSuccessor,
            Instant proposedAt,
            Instant appliedAt,
            Instant resolvedAt) {}

    public record RecallMemoryArgs(
            @ToolParam(description = "Optional kind filter (DECISION, BLOCKER, CONVENTION, "
                    + "FOCUS_SHIFT, OPEN_QUESTION, RECURRING_PATTERN). Narrow first by kind; "
                    + "the query filter is cheap but the kind cut is even cheaper.")
            String kind,
            @ToolParam(description = "Optional free-text substring filter, case-insensitive. "
                    + "Matched against the item text.")
            String query,
            @ToolParam(description = "Optional scope filter (WORKSPACE or THREAD). Default "
                    + "WORKSPACE. THREAD scopes to the calling thread.")
            String scope,
            @ToolParam(description = "Max rows to return (default " + RECALL_DEFAULT_LIMIT
                    + ", capped at " + RECALL_HARD_CAP + ").")
            Integer limit,
            @ToolParam(description = "Set true to audit historical rows too: pending "
                    + "proposals, superseded and resolved items. Default false — only "
                    + "live (applied, current) memory is returned.")
            Boolean includeHistorical) {}

    @AgentTool(
            name = "recall_memory",
            description = "Filtered manifest of stored memory items — DECISIONs, BLOCKERs, "
                    + "CONVENTIONs, etc. Call this BEFORE asking the user to choose between "
                    + "alternatives so a prior decision can be honoured instead of re-asked. "
                    + "Returns one-line briefs; follow up with lookup_memory(id) for the full "
                    + "definition + sources.",
            whenToUse = "Before asking the user for a decision, or before parking a publish "
                    + "for approval. Cite the returned row by id and surface its sources "
                    + "in the user-facing message.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome recallMemory(RecallMemoryArgs args, ToolCall call)
    {
        MemoryItemKind kindFilter = parseKind(args.kind());
        if (args.kind() != null && !args.kind().isBlank() && kindFilter == null) {
            return ToolOutcome.Completed.error(
                    "unknown kind: " + args.kind() + " (expected DECISION/BLOCKER/CONVENTION/"
                            + "FOCUS_SHIFT/OPEN_QUESTION/RECURRING_PATTERN)");
        }
        MemoryItemScopeKind scopeKind = parseScope(args.scope());
        if (args.scope() != null && !args.scope().isBlank() && scopeKind == null) {
            return ToolOutcome.Completed.error(
                    "unknown scope: " + args.scope() + " (expected WORKSPACE or THREAD)");
        }
        MemoryItemScopeKind resolvedScope = scopeKind == null ? MemoryItemScopeKind.WORKSPACE : scopeKind;
        Optional<String> scopeId = scopeIdFor(resolvedScope, call);
        if (scopeId.isEmpty()) {
            return ToolOutcome.Completed.error("recall_memory has no scope id to look up under");
        }
        int limit = args.limit() == null ? RECALL_DEFAULT_LIMIT
                : Math.clamp(args.limit(), 1, RECALL_HARD_CAP);
        String needle = args.query() == null ? "" : args.query().toLowerCase(Locale.ROOT).trim();

        // Live-by-default: historical/pending rows require the explicit audit
        // flag so a stale or never-accepted item cannot steer an agent.
        List<MemoryItem> all = Boolean.TRUE.equals(args.includeHistorical())
                ? store.findByScope(resolvedScope, scopeId.get())
                : store.findLive(resolvedScope, scopeId.get());
        List<MemoryBrief> briefs = new ArrayList<>();
        for (MemoryItem row : all) {
            if (kindFilter != null && row.kind() != kindFilter) {
                continue;
            }
            if (!needle.isEmpty() && !row.text().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            briefs.add(toBrief(row, resolvedScope));
            if (briefs.size() >= limit) {
                break;
            }
        }
        // Deterministic ordering: applied (live) first, then by id
        // descending. Pending / superseded rows surface but never
        // ahead of a live row at the same name.
        briefs.sort(Comparator
                .<MemoryBrief>comparingInt(b -> b.supersededBy() == null ? 0 : 1)
                .thenComparing((a, b) -> Long.compare(b.id(), a.id())));
        return jsonResult(briefs);
    }

    public record LookupMemoryArgs(
            @ToolParam(description = "Memory item id returned by recall_memory.",
                    required = true) Long id) {}

    @AgentTool(
            name = "lookup_memory",
            description = "Full record for one memory item: the text, sources, confidence, "
                    + "and lifecycle (applied / superseded / resolved). On a superseded row "
                    + "the response includes the live successor so the citation stays current.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome lookupMemory(LookupMemoryArgs args, ToolCall call)
    {
        if (args.id() == null) {
            return ToolOutcome.Completed.error("id is required");
        }
        Optional<MemoryItem> hit = store.findById(args.id());
        if (hit.isEmpty()) {
            return ToolOutcome.Completed.error("no memory item with id " + args.id());
        }
        MemoryItem row = hit.get();
        if (!canRead(row, call)) {
            return ToolOutcome.Completed.error("memory item " + args.id() + " is outside this agent's scope");
        }
        MemoryBrief successor = null;
        if (row.supersededBy() != null) {
            successor = store.findById(row.supersededBy())
                    .filter(succ -> canRead(succ, call))
                    .map(succ -> toBrief(succ, succ.scopeKind()))
                    .orElse(null);
        }
        MemoryDetail detail = new MemoryDetail(
                row.id(),
                row.kind().name(),
                row.scopeKind().name(),
                row.text(),
                row.confidence().name(),
                row.sources(),
                row.tags(),
                row.supersededBy(),
                successor,
                row.proposedAt(),
                row.appliedAt(),
                row.resolvedAt());
        return jsonResult(detail);
    }

    private Optional<String> scopeIdFor(MemoryItemScopeKind scope, ToolCall call)
    {
        if (scope == MemoryItemScopeKind.THREAD) {
            return Optional.ofNullable(call)
                    .map(ToolCall::threadId)
                    .filter(id -> id != null && !id.isBlank());
        }
        return workspaceScopeId(call);
    }

    private Optional<String> workspaceScopeId(ToolCall call)
    {
        return Optional.ofNullable(call)
                .map(ToolCall::threadId)
                .filter(id -> id != null && !id.isBlank())
                .flatMap(threadStore::findThreadById)
                .map(Thread::workspaceId)
                .filter(id -> id != null && !id.isBlank());
    }

    private boolean canRead(MemoryItem row, ToolCall call)
    {
        return scopeIdFor(row.scopeKind(), call)
                .map(scopeId -> scopeId.equals(row.scopeId()))
                .orElse(false);
    }

    private MemoryBrief toBrief(MemoryItem row, MemoryItemScopeKind scope)
    {
        return new MemoryBrief(
                row.id(),
                row.kind().name(),
                scope.name(),
                trimOneLine(row.text()),
                row.confidence().name(),
                row.sources(),
                row.supersededBy());
    }

    private ToolOutcome jsonResult(Object payload)
    {
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(payload));
        }
        catch (JsonProcessingException e) {
            return ToolOutcome.Completed.error("failed to serialise memory tool result: " + e.getMessage());
        }
    }

    private static String trimOneLine(String text)
    {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int firstStop = -1;
        int dot = text.indexOf('.');
        int newline = text.indexOf('\n');
        if (dot >= 0) {
            firstStop = dot;
        }
        if (newline >= 0 && (firstStop < 0 || newline < firstStop)) {
            firstStop = newline;
        }
        String head = firstStop >= 0 ? text.substring(0, firstStop) : text;
        return head.length() > ONE_LINE_CAP ? head.substring(0, ONE_LINE_CAP - 1) + "…" : head;
    }

    private static MemoryItemKind parseKind(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MemoryItemKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MemoryItemScopeKind parseScope(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MemoryItemScopeKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
