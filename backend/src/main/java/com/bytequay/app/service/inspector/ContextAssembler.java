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
package com.bytequay.app.service.inspector;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.skills.SkillManifestEntry;
import com.bytequay.app.service.skills.SkillManifestQuery;
import com.bytequay.app.service.skills.SkillManifestService;
import com.bytequay.app.service.tools.TurnAssembler;
import com.bytequay.app.service.tools.TurnAssembler.ProviderShape;
import com.bytequay.app.service.tools.TurnRequest;
import com.bytequay.app.service.workspaces.MemoryItemService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

/**
 * Builds an {@link AssembledContext} — a read-only diagnostic view of one
 * thread (TRUNK scope) or task (TASK scope). The wire preview uses the shared
 * {@link TurnAssembler}; catalog-only data is kept out of that preview.
 *
 * <h3>Non-negotiable: view, not send.</h3>
 *
 * Every code path here is read-only — no provider dispatch, no
 * history mutation, no token / cost accounting, no prefix-cache
 * warm. The endpoint layer adds {@code dryRun} validation; this
 * class would still be inert if called by another service.
 *
 * <h3>v1 scope</h3>
 *
 * Sections ① TOOLS, ② ROLE, ③ BRAIN, ⑦ HISTORY, ⑧ NEW_TURN are
 * populated. ④ CONCEPT_PREAMBLE renders from the
 * {@link RoleRegistry}'s preamble builder. ⑤ SKILL_MANIFEST is an
 * administrative catalog view and is deliberately not injected into the
 * wire prompt. ⑥ MEMORY renders applied memory items via
 * {@link MemoryItemService}.
 */
@Service
public class ContextAssembler
{
    /** Token estimate constant matching {@link WorkspaceService} —
     *  English markdown averages ~4 chars per BPE token, accurate
     *  enough for the inspector's token bar. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Hard cap on how many history messages the inspector loads —
     *  bounds the read-only response so a pathological thread
     *  doesn't blow out the JSON payload. */
    private static final int MAX_HISTORY_MESSAGES = 200;

    /** Top-N skill briefs surfaced in the SKILL_MANIFEST section. */
    private static final int SKILL_MANIFEST_LIMIT = 30;

    /** Hard-coded for v1 — once the model is per-workspace, swap
     *  this for a settings lookup. */
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final RoleRegistry roles;
    private final WorkspaceService workspaces;
    private final SkillManifestService skillManifest;
    private final MemoryItemService memoryItems;
    private final TurnAssembler turnAssembler;

    public ContextAssembler(
            ThreadStore threadStore,
            TaskStore taskStore,
            RoleRegistry roles,
            WorkspaceService workspaces,
            SkillManifestService skillManifest,
            MemoryItemService memoryItems,
            TurnAssembler turnAssembler)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.roles = requireNonNull(roles, "roles is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.skillManifest = requireNonNull(skillManifest, "skillManifest is null");
        this.memoryItems = requireNonNull(memoryItems, "memoryItems is null");
        this.turnAssembler = requireNonNull(turnAssembler, "turnAssembler is null");
    }

    /**
     * Build a TRUNK-scoped context view for one thread. Returns a
     * 404 when the thread doesn't exist; never throws on empty
     * axes (a fresh workspace with no memory just shows empty
     * sections).
     */
    public AssembledContext forThread(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Thread thread = threadStore.findThreadById(threadId).orElseThrow(
                () -> new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "no thread with id " + threadId));
        String workspaceId = thread.workspaceId();
        String roleBody = roles.trunkTemplate();
        return assemble(ContextScope.TRUNK, threadId, workspaceId, roleBody, thread);
    }

    /**
     * Build a TASK-scoped context view for one task. 404 when the
     * task can't be loaded or it doesn't belong to the named
     * thread.
     */
    public AssembledContext forTask(String threadId, String taskId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(taskId, "taskId is null");
        Thread thread = threadStore.findThreadById(threadId).orElseThrow(
                () -> new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "no thread with id " + threadId));
        Task task = taskStore.findTaskById(taskId).orElseThrow(
                () -> new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "no task with id " + taskId));
        if (!threadId.equals(task.threadId())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                    "task " + taskId + " is not on thread " + threadId);
        }
        // New rows carry a versioned role reference; legacy rows may still
        // carry a frozen body. RoleRegistry resolves both forms.
        String roleBody = roles.resolveForTask(task);
        return assemble(ContextScope.TASK, taskId, thread.workspaceId(), roleBody, thread);
    }

    private AssembledContext assemble(
            ContextScope scope,
            String scopeId,
            String workspaceId,
            String roleBody,
            Thread thread)
    {
        String brainBody = workspaceId == null ? "" : safeMemory(workspaceId);
        String preamble = roles.buildConceptPreamble();
        List<SkillManifestEntry> skillEntries = skillManifest.query(
                SkillManifestQuery.forRepoContext(null, null));
        String manifestBody = renderSkillManifest(skillEntries);
        List<MemoryItem> liveMemory = workspaceId == null
                ? List.of()
                : memoryItems.listLive(MemoryItemScopeKind.WORKSPACE, workspaceId);
        String memoryBody = workspaceId == null ? ""
                : memoryItems.renderToMarkdown(MemoryItemScopeKind.WORKSPACE, workspaceId);
        List<ThreadMessage> messages = threadStore.listRecentMessages(thread.id(), MAX_HISTORY_MESSAGES);
        List<String> history = messages.stream().map(m -> nullToEmpty(m.contentJson())).toList();
        String newTurn = "";

        // The catalog remains visible below for settings/diagnostics, but it
        // is not a system block. AgentContextCompiler selects runtime skills.
        TurnRequest wire = turnAssembler.assemble(
                List.of(),
                ProviderShape.ANTHROPIC,
                roleBody,
                brainBody,
                preamble,
                null,
                memoryBody,
                history,
                newTurn);

        List<ContextSection> sections = buildSections(
                scope, wire, roleBody, brainBody, preamble, manifestBody, memoryBody,
                skillEntries, liveMemory, messages, newTurn);
        int totalTokens = sections.stream().mapToInt(ContextSection::tokenCount).sum();
        ContextMeta meta = new ContextMeta(
                DEFAULT_MODEL,
                ProviderShape.ANTHROPIC.name(),
                Instant.now(),
                totalTokens,
                // A turn whose prefix is already warm — heuristic: if
                // tools + role + brain together are over a few k
                // tokens, the provider's cache is almost certainly
                // populated from a prior request.
                totalTokens > 5_000);
        return new AssembledContext(scope, scopeId, meta, sections, wire);
    }

    private static List<ContextSection> buildSections(
            ContextScope scope,
            TurnRequest wire,
            String roleBody,
            String brainBody,
            String preamble,
            String manifestBody,
            String memoryBody,
            List<SkillManifestEntry> skillEntries,
            List<MemoryItem> liveMemory,
            List<ThreadMessage> messages,
            String newTurn)
    {
        List<ContextSection> sections = new ArrayList<>(8);
        sections.add(section(SectionKind.TOOLS, "① tools",
                String.join("\n", wire.tools()),
                toolProvenance(wire.tools())));
        sections.add(section(SectionKind.ROLE, "② role", nullToEmpty(roleBody),
                roleProvenance(scope, roleBody)));
        sections.add(section(SectionKind.BRAIN, "③ brain", nullToEmpty(brainBody),
                brainProvenance(brainBody)));
        sections.add(section(SectionKind.CONCEPT_PREAMBLE, "④ concepts",
                nullToEmpty(preamble),
                conceptPreambleProvenance(preamble)));
        sections.add(section(SectionKind.SKILL_MANIFEST, "⑤ skill catalog (not injected)",
                nullToEmpty(manifestBody),
                skillProvenance(skillEntries)));
        sections.add(section(SectionKind.MEMORY, "⑥ memory",
                nullToEmpty(memoryBody),
                memoryProvenance(liveMemory)));
        sections.add(section(SectionKind.HISTORY, "⑦ history",
                String.join("\n", messages.stream().map(m -> nullToEmpty(m.contentJson())).toList()),
                historyProvenance(messages)));
        sections.add(section(SectionKind.NEW_TURN, "⑧ this turn",
                nullToEmpty(newTurn), List.of()));
        return sections;
    }

    private static ContextSection section(
            SectionKind kind, String label, String body, List<Provenance> sources)
    {
        String trimmed = body == null ? "" : body;
        return new ContextSection(kind, label, trimmed, estimateTokens(trimmed), sources);
    }

    // ── Per-axis provenance builders ───────────────────────────────

    private static List<Provenance> toolProvenance(List<String> tools)
    {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Provenance> out = new ArrayList<>(tools.size());
        for (String toolJson : tools) {
            String name = extractToolName(toolJson);
            out.add(new Provenance("tool", name, null, null));
        }
        return out;
    }

    private static List<Provenance> roleProvenance(ContextScope scope, String roleBody)
    {
        if (roleBody == null || roleBody.isEmpty()) {
            return List.of();
        }
        String label = scope == ContextScope.TRUNK ? "trunk role template" : "task role skill";
        return List.of(new Provenance("role", label, "/settings/skills#role", null));
    }

    private static List<Provenance> brainProvenance(String brainBody)
    {
        if (brainBody == null || brainBody.isEmpty()) {
            return List.of();
        }
        return List.of(new Provenance("brain", "WORKSPACE.md",
                "/settings/workspace-memory", null));
    }

    private static List<Provenance> conceptPreambleProvenance(String preamble)
    {
        if (preamble == null || preamble.isEmpty()) {
            return List.of();
        }
        List<Provenance> out = new ArrayList<>(RoleRegistry.TASK_PREAMBLE_CONCEPTS.size());
        for (String name : RoleRegistry.TASK_PREAMBLE_CONCEPTS) {
            out.add(new Provenance("concept", name,
                    "/settings/concepts#" + name, null));
        }
        return out;
    }

    private static List<Provenance> skillProvenance(List<SkillManifestEntry> entries)
    {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Provenance> out = new ArrayList<>();
        int rendered = 0;
        for (SkillManifestEntry e : entries) {
            if (rendered >= SKILL_MANIFEST_LIMIT) {
                break;
            }
            out.add(new Provenance("skill", e.name(),
                    "/settings/skills#" + slug(e.name()), null));
            rendered++;
        }
        return out;
    }

    private static List<Provenance> memoryProvenance(List<MemoryItem> live)
    {
        if (live == null || live.isEmpty()) {
            return List.of();
        }
        List<Provenance> out = new ArrayList<>(live.size());
        for (MemoryItem item : live) {
            out.add(new Provenance(
                    "memory_item",
                    "item " + item.id() + " · " + item.kind().name().toLowerCase(Locale.ROOT),
                    "/settings/workspace-memory#item-" + item.id(),
                    null));
        }
        return out;
    }

    private static List<Provenance> historyProvenance(List<ThreadMessage> messages)
    {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Provenance> out = new ArrayList<>(messages.size());
        for (ThreadMessage m : messages) {
            String label = m.role() + " · seq " + m.seq();
            out.add(new Provenance("history", label, null, null));
        }
        return out;
    }

    /** Extract the tool name from a tool-definition JSON snippet.
     *  Looks for {@code "name":"..."} verbatim — the SkillTools
     *  constants and downstream tool registrations all use that
     *  shape, so a one-line scan beats parsing the full JSON. */
    private static String extractToolName(String toolJson)
    {
        if (toolJson == null) {
            return "?";
        }
        int idx = toolJson.indexOf("\"name\"");
        if (idx < 0) {
            return "?";
        }
        int colon = toolJson.indexOf(':', idx);
        if (colon < 0) {
            return "?";
        }
        int quote = toolJson.indexOf('"', colon + 1);
        if (quote < 0) {
            return "?";
        }
        int end = toolJson.indexOf('"', quote + 1);
        if (end < 0) {
            return "?";
        }
        return toolJson.substring(quote + 1, end);
    }

    private static String slug(String name)
    {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static int estimateTokens(String body)
    {
        return body == null || body.isEmpty() ? 0 : body.length() / CHARS_PER_TOKEN;
    }

    private String safeMemory(String workspaceId)
    {
        try {
            String body = workspaces.getMemory(workspaceId);
            return body == null ? "" : body;
        }
        catch (RuntimeException e) {
            // A missing workspace row shouldn't 500 the inspector;
            // a fresh install or test fixture trips this.
            return "";
        }
    }

    private static String renderSkillManifest(List<SkillManifestEntry> entries)
    {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("Skill manifest (top ").append(SKILL_MANIFEST_LIMIT).append("):\n");
        int rendered = 0;
        for (SkillManifestEntry e : entries) {
            if (rendered >= SKILL_MANIFEST_LIMIT) {
                break;
            }
            out.append("- ").append(e.name());
            if (e.description() != null && !e.description().isBlank()) {
                out.append(" — ").append(e.description());
            }
            out.append('\n');
            rendered++;
        }
        return out.toString().stripTrailing();
    }
}
