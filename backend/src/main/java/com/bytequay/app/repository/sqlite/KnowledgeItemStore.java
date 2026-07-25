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

import com.bytequay.app.domain.KnowledgeItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Persistence for the canonical repository-knowledge store:
 * {@code knowledge_item} plus its {@code knowledge_provenance} evidence
 * links and {@code knowledge_applicability} tags. Shared by the workspace
 * knowledge-base CRUD, the session prompt projection, and the project
 * learning pipeline — the app deliberately has no other learned-knowledge
 * store.
 */
@Repository
public class KnowledgeItemStore
{
    /** Rows the user-facing KB surface and the distill drift digest manage.
     *  Learned rows ({@code pr-learning}) live beside them but flow through
     *  the lifecycle, not through distill previews. */
    private static final List<String> MANAGED_CREATORS =
            List.of("user", "docs-bootstrap", "distill");

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeItemStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Transactional
    public void insert(
            KnowledgeItem item,
            List<KnowledgeItem.Provenance> provenance,
            List<KnowledgeItem.Applicability> applicability)
    {
        jdbc.update("""
                INSERT INTO knowledge_item (
                    id, workspace_id, repo_id, subtype, title, statement,
                    rationale, trigger_json, state, counters_json,
                    audiences_json, confidence, validated_at_commit,
                    last_verified_at_ms, created_by, statement_digest,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                item.id(), item.workspaceId(), item.repo(), item.kind(),
                item.title(), item.statement(), item.rationale(),
                item.lifecycle(), item.countersJson() == null ? "{}" : item.countersJson(),
                write(item.audiences()), item.confidence(),
                item.validatedAtCommit(), item.lastVerifiedAtMs(),
                item.createdBy(), item.statementDigest(),
                item.createdAtMs(), item.updatedAtMs());
        addProvenance(item.id(), provenance);
        for (KnowledgeItem.Applicability tag : applicability) {
            jdbc.update("""
                    INSERT OR IGNORE INTO knowledge_applicability (
                        knowledge_item_id, kind, value)
                    VALUES (?, ?, ?)
                    """, item.id(), tag.kind(), tag.value());
        }
    }

    /** Update the user-editable fields, deliberately leaving provenance and
     *  applicability untouched so an edit never drops evidence. */
    public void updateContent(
            String id,
            String title,
            String statement,
            String rationale,
            List<String> audiences,
            long updatedAtMs)
    {
        jdbc.update("""
                UPDATE knowledge_item
                SET title = ?, statement = ?, rationale = ?,
                    audiences_json = ?, updated_at_ms = ?
                WHERE id = ?
                """, title, statement, rationale, write(audiences), updatedAtMs, id);
    }

    public void setLifecycle(String id, String lifecycle, String validatedAtCommit, long nowMs)
    {
        jdbc.update("""
                UPDATE knowledge_item
                SET state = ?,
                    validated_at_commit = coalesce(?, validated_at_commit),
                    last_verified_at_ms = ?,
                    updated_at_ms = ?
                WHERE id = ?
                """, lifecycle, validatedAtCommit, nowMs, nowMs, id);
    }

    public void setCounters(String id, String countersJson, long nowMs)
    {
        jdbc.update("UPDATE knowledge_item SET counters_json = ?, updated_at_ms = ? WHERE id = ?",
                countersJson, nowMs, id);
    }

    public void addProvenance(String itemId, List<KnowledgeItem.Provenance> provenance)
    {
        for (KnowledgeItem.Provenance source : provenance) {
            jdbc.update("""
                    INSERT OR IGNORE INTO knowledge_provenance (
                        knowledge_item_id, source_kind, source_ref,
                        commit_sha, file_path, url, content_digest)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    itemId, source.sourceKind(), source.sourceRef(),
                    source.commitSha(), source.filePath(), source.url(),
                    source.contentDigest());
        }
    }

    /** Delete provenance of curation kinds only (distill-operation, imported,
     *  user) — learned evidence links (pr, thread, commit, …) survive every
     *  edit, per the never-drop-provenance contract. */
    public void replaceCurationProvenance(String itemId, List<KnowledgeItem.Provenance> provenance)
    {
        jdbc.update("""
                DELETE FROM knowledge_provenance
                WHERE knowledge_item_id = ?
                  AND source_kind IN ('distill-operation', 'imported', 'user')
                """, itemId);
        addProvenance(itemId, provenance);
    }

    public void delete(String id)
    {
        jdbc.update("DELETE FROM knowledge_item WHERE id = ?", id);
    }

    public Optional<KnowledgeItem> findById(String id)
    {
        return jdbc.query("SELECT * FROM knowledge_item WHERE id = ?", mapper(), id)
                .stream().findFirst();
    }

    public Optional<KnowledgeItem> findByDigest(String workspaceId, String repo, String digest)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_item
                WHERE workspace_id = ? AND repo_id = ? AND statement_digest = ?
                """, mapper(), workspaceId, repo, digest).stream().findFirst();
    }

    /** Rows the user-facing KB surface and distill drift digest cover. */
    public List<KnowledgeItem> listManaged(String workspaceId)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_item
                WHERE workspace_id = ? AND created_by IN ('user', 'docs-bootstrap', 'distill')
                ORDER BY updated_at_ms DESC, id
                """, mapper(), workspaceId);
    }

    public List<KnowledgeItem> listByLifecycle(String workspaceId, String lifecycle)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_item
                WHERE workspace_id = ? AND state = ?
                ORDER BY updated_at_ms DESC, id
                """, mapper(), workspaceId, lifecycle);
    }

    public List<KnowledgeItem> listActiveByRepo(String repo)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_item
                WHERE repo_id = ? AND state = 'active'
                ORDER BY updated_at_ms DESC, id
                """, mapper(), repo);
    }

    /** Active glossary rows across all repositories — the startup reload
     *  set for the concept registry. */
    public List<KnowledgeItem> listActiveGlossary()
    {
        return jdbc.query("""
                SELECT * FROM knowledge_item
                WHERE subtype = 'glossary' AND state = 'active'
                ORDER BY repo_id, id
                """, mapper());
    }

    public List<KnowledgeItem.Provenance> provenance(String itemId)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_provenance
                WHERE knowledge_item_id = ?
                ORDER BY source_kind, source_ref
                """,
                (rs, ignored) -> new KnowledgeItem.Provenance(
                        rs.getString("source_kind"),
                        rs.getString("source_ref"),
                        rs.getString("commit_sha"),
                        rs.getString("file_path"),
                        rs.getString("url"),
                        rs.getString("content_digest")),
                itemId);
    }

    public List<KnowledgeItem.Applicability> applicability(String itemId)
    {
        return jdbc.query("""
                SELECT * FROM knowledge_applicability
                WHERE knowledge_item_id = ?
                ORDER BY kind, value
                """,
                (rs, ignored) -> new KnowledgeItem.Applicability(
                        rs.getString("kind"), rs.getString("value")),
                itemId);
    }

    /** Distinct merged-PR sources backing an item — the independent-
     *  confirmation count for the activation rules. */
    public int distinctPrSources(String itemId)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT source_ref) FROM knowledge_provenance
                WHERE knowledge_item_id = ? AND source_kind = 'pr'
                """, Integer.class, itemId);
        return n == null ? 0 : n;
    }

    public int countByCreator(String workspaceId, String repo, String createdBy)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_item
                WHERE workspace_id = ? AND repo_id = ? AND created_by = ?
                """, Integer.class, workspaceId, repo, createdBy);
        return n == null ? 0 : n;
    }

    public int countPending(String workspaceId, String repo)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_item
                WHERE workspace_id = ? AND repo_id = ? AND state = 'pending'
                """, Integer.class, workspaceId, repo);
        return n == null ? 0 : n;
    }

    /** True when the creators set of this row is distill-managed; used by the
     *  KB surface to refuse edits to rows it does not own. */
    public static boolean isManagedCreator(String createdBy)
    {
        return MANAGED_CREATORS.contains(createdBy);
    }

    /** Normalized dedup key over the statement text: lowercase, collapsed
     *  whitespace, trailing punctuation stripped, SHA-256 hex. */
    public static String statementDigest(String statement)
    {
        String normalized = statement == null ? "" : statement
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?\\s]+$", "")
                .strip();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private RowMapper<KnowledgeItem> mapper()
    {
        return this::map;
    }

    private KnowledgeItem map(ResultSet rs, int rowNum)
            throws SQLException
    {
        Long verifiedAt = rs.getObject("last_verified_at_ms") == null
                ? null : rs.getLong("last_verified_at_ms");
        return new KnowledgeItem(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("repo_id"),
                rs.getString("subtype"),
                rs.getString("title"),
                rs.getString("statement"),
                rs.getString("rationale"),
                readStrings(rs.getString("audiences_json")),
                rs.getString("confidence"),
                rs.getString("state"),
                rs.getString("validated_at_commit"),
                verifiedAt,
                rs.getString("created_by"),
                rs.getString("statement_digest"),
                rs.getString("counters_json"),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"));
    }

    private List<String> readStrings(String json)
    {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRINGS);
        }
        catch (IOException e) {
            return List.of();
        }
    }

    private String write(List<String> values)
    {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize knowledge fields", e);
        }
    }
}
