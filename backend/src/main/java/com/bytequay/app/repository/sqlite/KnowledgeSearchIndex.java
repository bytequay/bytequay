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
package com.bytequay.app.repository.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Runtime-managed SQLite FTS5 index over the canonical knowledge store.
 * Created outside Flyway on purpose: when the packaged SQLite lacks FTS5 the
 * index quietly degrades to indexed {@code LIKE} matching — the retrieval
 * contract is preserved, only ranking quality and speed drop, and
 * {@link #degraded()} exposes the search-health signal.
 *
 * <p>Synchronization is by SQLite triggers on {@code knowledge_item}, so no
 * writer needs to know the index exists; startup rebuilds the mirror to heal
 * any rows written while triggers were absent.
 */
@Repository
public class KnowledgeSearchIndex
{
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchIndex.class);

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public KnowledgeSearchIndex(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize()
    {
        try {
            jdbc.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts
                    USING fts5(item_id UNINDEXED, title, statement, rationale)
                    """);
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS knowledge_fts_ai
                    AFTER INSERT ON knowledge_item BEGIN
                        INSERT INTO knowledge_fts (item_id, title, statement, rationale)
                        VALUES (new.id, coalesce(new.title, ''), new.statement,
                                coalesce(new.rationale, ''));
                    END
                    """);
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS knowledge_fts_au
                    AFTER UPDATE ON knowledge_item BEGIN
                        DELETE FROM knowledge_fts WHERE item_id = old.id;
                        INSERT INTO knowledge_fts (item_id, title, statement, rationale)
                        VALUES (new.id, coalesce(new.title, ''), new.statement,
                                coalesce(new.rationale, ''));
                    END
                    """);
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS knowledge_fts_ad
                    AFTER DELETE ON knowledge_item BEGIN
                        DELETE FROM knowledge_fts WHERE item_id = old.id;
                    END
                    """);
            jdbc.execute("DELETE FROM knowledge_fts");
            jdbc.execute("""
                    INSERT INTO knowledge_fts (item_id, title, statement, rationale)
                    SELECT id, coalesce(title, ''), statement, coalesce(rationale, '')
                    FROM knowledge_item
                    """);
            available = true;
        }
        catch (RuntimeException e) {
            available = false;
            log.warn("FTS5 unavailable — knowledge search degrades to LIKE matching: {}",
                    e.getMessage());
        }
    }

    /** True when FTS5 could not be initialized and search runs on the
     *  correctness-preserving LIKE fallback. */
    public boolean degraded()
    {
        return !available;
    }

    /**
     * Ranked ids of ACTIVE knowledge for one workspace/repository matching
     * the query tokens. FTS5 ranks by bm25; the fallback ranks by recency
     * among LIKE matches.
     */
    public List<String> searchActive(String workspaceId, String repo, List<String> tokens, int limit)
    {
        if (tokens.isEmpty()) {
            return List.of();
        }
        if (available) {
            try {
                return jdbc.queryForList("""
                        SELECT f.item_id FROM knowledge_fts f
                        JOIN knowledge_item k ON k.id = f.item_id
                        WHERE knowledge_fts MATCH ?
                          AND k.workspace_id = ? AND k.repo_id = ? AND k.state = 'active'
                        ORDER BY bm25(knowledge_fts)
                        LIMIT ?
                        """, String.class, matchExpression(tokens), workspaceId, repo, limit);
            }
            catch (RuntimeException e) {
                log.debug("FTS query failed, falling back to LIKE: {}", e.getMessage());
            }
        }
        return likeSearch(workspaceId, repo, tokens, limit);
    }

    private List<String> likeSearch(
            String workspaceId, String repo, List<String> tokens, int limit)
    {
        StringBuilder sql = new StringBuilder("""
                SELECT id FROM knowledge_item
                WHERE workspace_id = ? AND repo_id = ? AND state = 'active' AND (
                """);
        Object[] args = new Object[2 + tokens.size() + 1];
        args[0] = workspaceId;
        args[1] = repo;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("lower(coalesce(title, '') || ' ' || statement || ' ' "
                    + "|| coalesce(rationale, '')) LIKE ?");
            args[2 + i] = "%" + tokens.get(i).toLowerCase(Locale.ROOT) + "%";
        }
        sql.append(") ORDER BY updated_at_ms DESC LIMIT ?");
        args[args.length - 1] = limit;
        return jdbc.queryForList(sql.toString(), String.class, args);
    }

    /** OR of quoted tokens — user text never reaches FTS syntax directly. */
    private static String matchExpression(List<String> tokens)
    {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            String cleaned = token.replace("\"", "").strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(" OR ");
            }
            out.append('"').append(cleaned).append('"');
        }
        return out.toString();
    }
}
