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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.service.learning.KnowledgeRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Small, cycle-free read path used when an agent Session starts: the bounded
 * project capsule, the critical workspace brain, and a small retrieved
 * projection of ACTIVE knowledge — never the complete knowledge base. The
 * whole KB can grow past any prompt limit without preventing a session from
 * starting; the long tail stays behind {@code explore_project} lookups.
 *
 * <p>Each render records exactly which knowledge ids were inserted
 * ({@code session_context_projection}) so the context inspector can explain
 * why an agent knew — or missed — something.
 */
@Service
public class SessionKnowledgeProvider
{
    /** Starting budgets from the design; tune through measurement. */
    static final int CAPSULE_CHAR_CAP = 4_000;
    static final int BRAIN_CHAR_CAP = 4_000;
    static final int RETRIEVED_ITEM_CAP = 8;
    static final int RETRIEVED_CHAR_CAP = 8_000;
    static final int QUERY_HINT_CHAR_CAP = 4_000;

    private static final Set<String> AUDIENCES = Set.of(
            "plan", "dev", "review", "ci-fix");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeRetrievalService retrieval;

    public SessionKnowledgeProvider(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            KnowledgeRetrievalService retrieval)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.retrieval = requireNonNull(retrieval, "retrieval is null");
    }

    public String render(String workspaceId, String audience)
    {
        return render(workspaceId, audience, null);
    }

    /**
     * Render the bounded projection. {@code queryHint} is the task/thread
     * text retrieval keys on; blank falls back to the top-confidence active
     * rows so a fresh thread still gets the always-relevant core.
     */
    public String render(String workspaceId, String audience, String queryHint)
    {
        if (workspaceId == null || workspaceId.isBlank()
                || audience == null || !AUDIENCES.contains(audience)) {
            return "";
        }
        return renderProjection(
                workspaceId, repoOf(workspaceId), audience, queryHint, true);
    }

    /**
     * Render context for a concrete trunk/task thread. Its latest request and
     * explicitly approved code area sharpen retrieval, while the area remains
     * guidance rather than a working-directory or sandbox boundary.
     */
    public String renderForThread(
            String workspaceId, String threadId, String audience, String queryHint)
    {
        if (workspaceId == null || workspaceId.isBlank()
                || threadId == null || threadId.isBlank()
                || audience == null || !AUDIENCES.contains(audience)) {
            return "";
        }
        ThreadContext thread = threadContext(workspaceId, threadId)
                .orElse(new ThreadContext(null, null));
        String hint = combinedHint(queryHint, thread.latestInput(), thread.scopePath());
        String rendered = renderProjection(
                workspaceId, repoOf(workspaceId), audience, hint, true);
        if (thread.scopePath() == null || thread.scopePath().isBlank()) {
            return rendered;
        }
        String scope = "# Code area\n\nPrimary code area: `" + thread.scopePath()
                + "` (focus guidance; shared changes outside it may still be required).";
        return rendered.isBlank() ? scope : scope + "\n\n" + rendered;
    }

    /** Project-only context for a repository review. Resolves the owning
     * workspace but deliberately excludes its private brain/memory. */
    public String renderForRepository(String repo, String queryHint)
    {
        if (repo == null || repo.isBlank()) {
            return "";
        }
        return learnedRepository(repo)
                .map(found -> renderProjection(
                        found.workspaceId(), found.repo(), "review", queryHint, false))
                .orElse("");
    }

    /** Typed ACTIVE review knowledge for the deterministic review planner.
     * The rendered prompt path remains separate because plan persistence
     * needs stable ids and applicability, not markdown. */
    public List<RepositoryKnowledge> reviewKnowledgeForRepository(
            String repo, String queryHint)
    {
        if (repo == null || repo.isBlank()) {
            return List.of();
        }
        return learnedRepository(repo)
                .map(found -> {
                    if (!audienceEnabled(found.workspaceId(), "review")) {
                        return List.<RepositoryKnowledge>of();
                    }
                    return retrieval.retrieve(
                                    found.workspaceId(), found.repo(), queryHint,
                                    "review", RETRIEVED_ITEM_CAP).stream()
                            .map(entry -> new RepositoryKnowledge(
                                    entry.item(),
                                    retrieval.applicability(entry.item().id()),
                                    entry.why()))
                            .toList();
                })
                .orElseGet(List::of);
    }

    private String renderProjection(
            String workspaceId,
            String repo,
            String audience,
            String queryHint,
            boolean includeBrain)
    {
        String capsule = cap(capsuleOf(workspaceId), CAPSULE_CHAR_CAP);
        String brain = includeBrain ? cap(brainOf(workspaceId), BRAIN_CHAR_CAP) : "";

        StringBuilder out = new StringBuilder();
        if (!capsule.isBlank()) {
            out.append(capsule.strip());
        }
        if (!brain.isBlank()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(brain.strip());
        }

        List<String> insertedIds = new ArrayList<>();
        int retrievedChars = 0;
        if (audienceEnabled(workspaceId, audience)) {
            List<KnowledgeRetrievalService.Retrieved> retrieved = retrieval.retrieve(
                    workspaceId, repo, queryHint, audience, RETRIEVED_ITEM_CAP);
            boolean heading = false;
            for (KnowledgeRetrievalService.Retrieved entry : retrieved) {
                KnowledgeItem item = entry.item();
                String block = "\n\n## " + (item.title() == null ? "Note" : item.title())
                        + "\n\n" + item.statement().strip()
                        + (item.rationale() == null || item.rationale().isBlank()
                                ? "" : "\n\nWhy: " + item.rationale().strip());
                if (retrievedChars + block.length() > RETRIEVED_CHAR_CAP) {
                    break;
                }
                if (!heading) {
                    if (!out.isEmpty()) {
                        out.append("\n\n");
                    }
                    out.append("# Knowledge base (").append(audience).append(")");
                    heading = true;
                }
                out.append(block);
                retrievedChars += block.length();
                insertedIds.add(item.id());
            }
        }

        recordProjection(workspaceId, audience, queryHint, insertedIds,
                capsule.length(), brain.length(), retrievedChars);
        return out.toString();
    }

    private Optional<LearnedRepository> learnedRepository(String repo)
    {
        return jdbc.query("""
                SELECT workspace_id, repo_full_name FROM workspace_repos
                WHERE lower(repo_full_name) = lower(?)
                LIMIT 1
                """, (rs, ignored) -> new LearnedRepository(
                        rs.getString("workspace_id"), rs.getString("repo_full_name")), repo)
                .stream().findFirst();
    }

    private Optional<ThreadContext> threadContext(String workspaceId, String threadId)
    {
        return jdbc.query("""
                SELECT scope.scope_path,
                       (SELECT turn.input FROM thread_turns turn
                        WHERE turn.thread_id = t.id
                        ORDER BY turn.created_at_ms DESC, turn.id DESC
                        LIMIT 1) AS latest_input
                FROM threads t
                LEFT JOIN thread_directory_scope_assignment scope
                  ON scope.thread_id = t.id AND scope.workspace_id = t.workspace_id
                WHERE t.id = ? AND t.workspace_id = ?
                """, (rs, ignored) -> new ThreadContext(
                        rs.getString("scope_path"), rs.getString("latest_input")),
                threadId, workspaceId).stream().findFirst();
    }

    private static String combinedHint(String... parts)
    {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(part.strip());
            if (out.length() >= QUERY_HINT_CHAR_CAP) {
                return out.substring(0, QUERY_HINT_CHAR_CAP);
            }
        }
        return out.toString();
    }

    private String capsuleOf(String workspaceId)
    {
        return jdbc.query("""
                SELECT capsule_md FROM repo_project_capsule WHERE workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : "", workspaceId);
    }

    private String brainOf(String workspaceId)
    {
        String brain = jdbc.query("""
                SELECT memory_md FROM workspaces WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : "", workspaceId);
        return brain == null ? "" : brain;
    }

    private String repoOf(String workspaceId)
    {
        String repo = jdbc.query("""
                SELECT repo FROM repo_project_capsule WHERE workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, workspaceId);
        if (repo != null && !repo.isBlank()) {
            return repo;
        }
        String name = jdbc.query("""
                SELECT name FROM workspaces WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : "", workspaceId);
        return name == null ? "" : name;
    }

    private void recordProjection(
            String workspaceId, String audience, String queryHint,
            List<String> itemIds, int capsuleChars, int brainChars, int retrievedChars)
    {
        try {
            jdbc.update("""
                    INSERT INTO session_context_projection (
                        workspace_id, audience, query_hint, item_ids_json,
                        capsule_chars, brain_chars, retrieved_chars, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(workspace_id, audience) DO UPDATE SET
                        query_hint = excluded.query_hint,
                        item_ids_json = excluded.item_ids_json,
                        capsule_chars = excluded.capsule_chars,
                        brain_chars = excluded.brain_chars,
                        retrieved_chars = excluded.retrieved_chars,
                        created_at_ms = excluded.created_at_ms
                    """,
                    workspaceId, audience, queryHint,
                    mapper.writeValueAsString(itemIds),
                    capsuleChars, brainChars, retrievedChars,
                    Instant.now().toEpochMilli());
        }
        catch (Exception e) {
            // Recording must never block a session from starting.
        }
    }

    private boolean audienceEnabled(String workspaceId, String audience)
    {
        List<String> rows = jdbc.queryForList("""
                SELECT settings_json FROM workspace_settings
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        if (rows.isEmpty()) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(rows.getFirst()).path("kbAudiences");
            if (!node.isArray()) {
                return true;
            }
            for (JsonNode value : node) {
                if (audience.equals(value.asText())) {
                    return true;
                }
            }
            return false;
        }
        catch (Exception ignored) {
            return true;
        }
    }

    private static String cap(String value, int max)
    {
        if (value == null) {
            return "";
        }
        return value.length() <= max
                ? value
                : value.substring(0, max) + "\n… (truncated)";
    }

    private record LearnedRepository(String workspaceId, String repo) {}

    public record RepositoryKnowledge(
            KnowledgeItem item,
            List<KnowledgeItem.Applicability> applicability,
            String why)
    {
        public RepositoryKnowledge
        {
            applicability = applicability == null ? List.of() : List.copyOf(applicability);
        }
    }

    private record ThreadContext(String scopePath, String latestInput) {}
}
