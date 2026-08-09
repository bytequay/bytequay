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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeSearchIndex;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import com.google.common.collect.ImmutableSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * The task-scoped projection over Project Intelligence: given a question or
 * task text, return a small ranked set of ACTIVE knowledge plus matching
 * live memory, document sections, and PR evidence references. Never a
 * synthesized answer — typed records the caller (an agent or the session
 * projection) reads and cites.
 *
 * <p>Ranking follows the design contract: lexical relevance, exact
 * symbol/path/module matches, provenance/confidence weight, and currentness,
 * with a conflict penalty. Recency alone never wins.
 */
@Service
public class KnowledgeRetrievalService
{
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_./-]{3,}");
    private static final int MAX_TOKENS = 12;
    /** An exact scope hit must outrank the best lexical hit even at maximum
     *  confidence (3.0 base + 2.0 high-confidence). */
    private static final double EXACT_MATCH_BOOST = 6.0;

    private final JdbcTemplate jdbc;
    private final KnowledgeItemStore store;
    private final KnowledgeSearchIndex searchIndex;
    private final SqliteMemoryItemStore memoryStore;

    public KnowledgeRetrievalService(
            JdbcTemplate jdbc,
            KnowledgeItemStore store,
            KnowledgeSearchIndex searchIndex,
            SqliteMemoryItemStore memoryStore)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.store = requireNonNull(store, "store is null");
        this.searchIndex = requireNonNull(searchIndex, "searchIndex is null");
        this.memoryStore = requireNonNull(memoryStore, "memoryStore is null");
    }

    /** One retrieved knowledge item and why it ranked into the context. */
    public record Retrieved(KnowledgeItem item, double score, String why) {}

    /** Structured applicability for callers that need to explain why a
     * retrieved item belongs to a concrete module, path, symbol, or concept. */
    public List<KnowledgeItem.Applicability> applicability(String itemId)
    {
        return store.applicability(itemId);
    }

    /**
     * Ranked active knowledge for a query. A blank query returns the
     * top-confidence active rows (the pre-turn default when no task text is
     * available). {@code audience} filters when non-null.
     */
    public List<Retrieved> retrieve(
            String workspaceId, String repo, String query, String audience, int limit)
    {
        List<String> tokens = tokenize(query);
        Map<String, Retrieved> ranked = new LinkedHashMap<>();

        // Lexical hits, best first: position converts to a descending base.
        List<String> lexical = searchIndex.searchActive(workspaceId, repo, tokens, limit * 3);
        for (int i = 0; i < lexical.size(); i++) {
            double base = 3.0 * (lexical.size() - i) / lexical.size();
            String id = lexical.get(i);
            store.findById(id).ifPresent(item ->
                    ranked.put(id, new Retrieved(item, base, "text match")));
        }

        // Exact applicability hits (path/symbol/module/concept) get the
        // strongest boost — an exact symbol beats a fuzzy sentence.
        for (String id : applicabilityMatches(workspaceId, repo, tokens, limit * 3)) {
            Retrieved prior = ranked.get(id);
            if (prior != null) {
                ranked.put(id, new Retrieved(prior.item(), prior.score() + EXACT_MATCH_BOOST,
                        prior.why() + ", exact scope match"));
            }
            else {
                store.findById(id).ifPresent(item -> ranked.put(id,
                        new Retrieved(item, EXACT_MATCH_BOOST, "exact scope match")));
            }
        }

        // Blank query: top-confidence active rows as the neutral projection.
        if (tokens.isEmpty()) {
            for (KnowledgeItem item : store.listByLifecycle(
                    workspaceId, KnowledgeItem.LIFECYCLE_ACTIVE)) {
                if (repo.equals(item.repo())) {
                    ranked.putIfAbsent(item.id(),
                            new Retrieved(item, 0.0, "always-relevant"));
                }
            }
        }

        return ranked.values().stream()
                .filter(entry -> entry.item().isActive())
                .filter(entry -> audience == null
                        || entry.item().audiences().isEmpty()
                        || entry.item().audiences().contains(audience))
                .map(this::weighted)
                .sorted(Comparator.comparingDouble(Retrieved::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Live workspace memory whose text matches any token. */
    public List<MemoryItem> matchingMemory(String workspaceId, String query, int limit)
    {
        List<String> tokens = tokenize(query);
        List<MemoryItem> out = new ArrayList<>();
        for (MemoryItem item : memoryStore.findLive(MemoryItemScopeKind.WORKSPACE, workspaceId)) {
            String text = item.text().toLowerCase(Locale.ROOT);
            if (tokens.isEmpty() || tokens.stream().anyMatch(
                    token -> text.contains(token.toLowerCase(Locale.ROOT)))) {
                out.add(item);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** One matching indexed document section: where to read the source. */
    public record DocSection(String path, String headingPath, int lineStart, int lineEnd) {}

    public List<DocSection> matchingDocs(
            String workspaceId, String repo, String query, int limit)
    {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT path, heading_path, line_start, line_end FROM repo_doc_section
                WHERE workspace_id = ? AND repo = ? AND (
                """);
        List<Object> args = new ArrayList<>(List.of(workspaceId, repo));
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("lower(path || ' ' || heading_path) LIKE ?");
            args.add("%" + tokens.get(i).toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(") ORDER BY path, line_start LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, ignored) -> new DocSection(
                        rs.getString("path"),
                        rs.getString("heading_path"),
                        rs.getInt("line_start"),
                        rs.getInt("line_end")),
                args.toArray());
    }

    /** One matching evidence pointer: an analyzed PR touching a query path. */
    public record EvidenceHit(int prNumber, String title, String filePath) {}

    public List<EvidenceHit> matchingEvidence(
            String workspaceId, String repo, String query, int limit)
    {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT r.pr_number, r.file_path,
                       json_extract(s.metadata_json, '$.title') AS title
                FROM repo_pr_evidence_ref r
                LEFT JOIN repo_pr_source s
                  ON s.workspace_id = r.workspace_id AND s.repo = r.repo
                 AND s.pr_number = r.pr_number
                WHERE r.workspace_id = ? AND r.repo = ? AND r.file_path IS NOT NULL AND (
                """);
        List<Object> args = new ArrayList<>(List.of(workspaceId, repo));
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("lower(r.file_path) LIKE ?");
            args.add("%" + tokens.get(i).toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(") ORDER BY r.pr_number DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, ignored) -> new EvidenceHit(
                        rs.getInt("pr_number"),
                        rs.getString("title"),
                        rs.getString("file_path")),
                args.toArray());
    }

    // ── ranking ─────────────────────────────────────────────────────

    private Retrieved weighted(Retrieved entry)
    {
        KnowledgeItem item = entry.item();
        double score = entry.score();
        score += switch (item.confidence() == null ? "low" : item.confidence()) {
            case "high" -> 2.0;
            case "medium" -> 1.0;
            default -> 0.0;
        };
        score += Math.min(store.distinctPrSources(item.id()), 3);
        if (item.validatedAtCommit() != null) {
            score += 1.0;
        }
        String counters = item.countersJson();
        if (counters != null && counters.contains("\"conflictsWith\"")) {
            score -= 3.0;
        }
        if (counters != null && counters.contains("\"possiblyStale\"")) {
            score -= 2.0;
        }
        return new Retrieved(item, score, entry.why());
    }

    private List<String> applicabilityMatches(
            String workspaceId, String repo, List<String> tokens, int limit)
    {
        if (tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT a.knowledge_item_id FROM knowledge_applicability a
                JOIN knowledge_item k ON k.id = a.knowledge_item_id
                WHERE k.workspace_id = ? AND k.repo_id = ? AND k.state = 'active' AND (
                """);
        List<Object> args = new ArrayList<>(List.of(workspaceId, repo));
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("lower(a.value) = ? "
                    + "OR (a.kind IN ('module', 'path') AND ("
                    + "? LIKE rtrim(lower(a.value), '/') || '/%' "
                    + "OR lower(a.value) LIKE rtrim(?, '/') || '/%')) "
                    + "OR (a.kind NOT IN ('module', 'path') AND lower(a.value) LIKE ?)");
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            args.add(token);
            args.add(token);
            args.add(token);
            args.add("%" + token + "%");
        }
        sql.append(") LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    public static List<String> tokenize(String query)
    {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        var matcher = TOKEN.matcher(query);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token.toLowerCase(Locale.ROOT))) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static final Set<String> STOP_WORDS = ImmutableSet.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "what",
            "how", "does", "where", "when", "are", "was", "has", "have", "should",
            "would", "could", "here", "there", "then", "them", "they", "its");
}
