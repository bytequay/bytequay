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

import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.skills.RoleSkillService;
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

import static java.util.Objects.requireNonNull;

/**
 * Builds an {@link AssembledContext} — the read-only view of "what
 * would be in the agent's prompt right now" for one thread (TRUNK
 * scope) or one task (TASK scope). Calls into the production
 * {@link TurnAssembler} so the wire bytes are produced by the same
 * code path a real turn uses; no parallel assembler.
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
 * {@link RoleSkillService}'s preamble builder. ⑤ SKILL_MANIFEST
 * pulls top-N briefs from the skill manifest. ⑥ MEMORY renders
 * applied memory items via {@link MemoryItemService}.
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
    private final RoleSkillService roleSkills;
    private final WorkspaceService workspaces;
    private final SkillManifestService skillManifest;
    private final MemoryItemService memoryItems;
    private final TurnAssembler turnAssembler;

    public ContextAssembler(
            ThreadStore threadStore,
            TaskStore taskStore,
            RoleSkillService roleSkills,
            WorkspaceService workspaces,
            SkillManifestService skillManifest,
            MemoryItemService memoryItems,
            TurnAssembler turnAssembler)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.roleSkills = requireNonNull(roleSkills, "roleSkills is null");
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
        String roleBody = roleSkills.trunkTemplate();
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
        // The role-skill body is frozen on the task row at creation;
        // an older task pre-dating the preamble feature carries a
        // null/blank value, in which case fall back to the live
        // template so the inspector still has something to show.
        String roleBody = task.roleSkill() != null && !task.roleSkill().isBlank()
                ? task.roleSkill()
                : "";
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
        String preamble = roleSkills.buildConceptPreamble();
        String manifestBody = renderSkillManifest();
        String memoryBody = workspaceId == null ? ""
                : memoryItems.renderToMarkdown(MemoryItemScopeKind.WORKSPACE, workspaceId);
        List<String> history = loadHistory(thread.id());
        String newTurn = "";

        // Wire bytes via the production assembler — identical to
        // what a real turn would dispatch for the same inputs.
        TurnRequest wire = turnAssembler.assemble(
                List.of(),
                ProviderShape.ANTHROPIC,
                roleBody,
                brainBody,
                preamble,
                manifestBody,
                memoryBody,
                history,
                newTurn);

        List<ContextSection> sections = buildSections(
                wire, roleBody, brainBody, preamble, manifestBody, memoryBody, history, newTurn);
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
            TurnRequest wire,
            String roleBody,
            String brainBody,
            String preamble,
            String manifestBody,
            String memoryBody,
            List<String> history,
            String newTurn)
    {
        List<ContextSection> sections = new ArrayList<>(8);
        sections.add(section(SectionKind.TOOLS, "① tools",
                String.join("\n", wire.tools())));
        sections.add(section(SectionKind.ROLE, "② role", nullToEmpty(roleBody)));
        sections.add(section(SectionKind.BRAIN, "③ brain", nullToEmpty(brainBody)));
        sections.add(section(SectionKind.CONCEPT_PREAMBLE, "④ concepts",
                nullToEmpty(preamble)));
        sections.add(section(SectionKind.SKILL_MANIFEST, "⑤ skills",
                nullToEmpty(manifestBody)));
        sections.add(section(SectionKind.MEMORY, "⑥ memory",
                nullToEmpty(memoryBody)));
        sections.add(section(SectionKind.HISTORY, "⑦ history",
                String.join("\n", history == null ? List.of() : history)));
        sections.add(section(SectionKind.NEW_TURN, "⑧ this turn",
                nullToEmpty(newTurn)));
        return sections;
    }

    private static ContextSection section(SectionKind kind, String label, String body)
    {
        String trimmed = body == null ? "" : body;
        return new ContextSection(kind, label, trimmed, estimateTokens(trimmed), List.of());
    }

    private static int estimateTokens(String body)
    {
        return body == null || body.isEmpty() ? 0 : body.length() / CHARS_PER_TOKEN;
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
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

    private String renderSkillManifest()
    {
        SkillManifestQuery query = SkillManifestQuery.forRepoContext(null, null);
        List<SkillManifestEntry> entries = skillManifest.query(query);
        if (entries.isEmpty()) {
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

    private List<String> loadHistory(String threadId)
    {
        List<ThreadMessage> rows = threadStore.listRecentMessages(threadId, MAX_HISTORY_MESSAGES);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rows.size());
        for (ThreadMessage row : rows) {
            // contentJson is the wire payload the lane already
            // serialised — re-rendering would create drift. The
            // inspector shows raw JSON; the section view labels it.
            String body = row.contentJson() == null ? "" : row.contentJson();
            out.add(body);
        }
        return out;
    }
}
