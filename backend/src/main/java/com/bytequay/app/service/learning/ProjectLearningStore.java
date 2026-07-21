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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Persistence for the durable project-learning artifacts: the resumable
 * {@code repo_learning_run}, the merged-PR catalog ({@code repo_pr_source}),
 * the heading-level document index ({@code repo_doc_section}), and the
 * bounded project capsule ({@code repo_project_capsule}). Plain
 * {@link JdbcTemplate} SQL, matching the workspace-creation / knowledge
 * services this coordinator sits beside.
 */
@Repository
public class ProjectLearningStore
{
    private final JdbcTemplate jdbc;

    public ProjectLearningStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    // ── repo_learning_run ───────────────────────────────────────────

    public void insertRun(ProjectLearningRun run)
    {
        jdbc.update("""
                INSERT INTO repo_learning_run (
                    id, workspace_id, repo, trigger_kind, state,
                    snapshot_sha, catalog_cursor, counts_json,
                    extractor_version, started_at_ms, updated_at_ms,
                    completed_at_ms, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.id(), run.workspaceId(), run.repo(), run.triggerKind(),
                run.state(), run.snapshotSha(), run.catalogCursor(),
                run.countsJson(), run.extractorVersion(), run.startedAtMs(),
                run.updatedAtMs(), run.completedAtMs(), run.lastError());
    }

    public Optional<ProjectLearningRun> findRun(String id)
    {
        return jdbc.query("SELECT * FROM repo_learning_run WHERE id = ?",
                RUN_MAPPER, id).stream().findFirst();
    }

    /** The newest run for a workspace repository, live or finished. */
    public Optional<ProjectLearningRun> latestRun(String workspaceId, String repo)
    {
        return jdbc.query("""
                SELECT * FROM repo_learning_run
                WHERE workspace_id = ? AND repo = ?
                ORDER BY started_at_ms DESC LIMIT 1
                """, RUN_MAPPER, workspaceId, repo).stream().findFirst();
    }

    /** Ids of runs a restart should resume: still-live runs plus runs parked
     *  partial by a rate limit / truncation. A restart resumes their
     *  incomplete sources rather than restarting the repository. */
    public List<String> resumableRunIds()
    {
        return jdbc.queryForList("""
                SELECT id FROM repo_learning_run
                WHERE state IN ('queued', 'indexing', 'cataloging', 'partial')
                """, String.class);
    }

    public void updateRun(
            String id,
            String state,
            String snapshotSha,
            String catalogCursor,
            String countsJson,
            Long completedAtMs,
            String lastError,
            long updatedAtMs)
    {
        jdbc.update("""
                UPDATE repo_learning_run
                SET state = ?,
                    snapshot_sha = COALESCE(?, snapshot_sha),
                    catalog_cursor = ?,
                    counts_json = ?,
                    completed_at_ms = ?,
                    last_error = ?,
                    updated_at_ms = ?
                WHERE id = ?
                """, state, snapshotSha, catalogCursor, countsJson,
                completedAtMs, lastError, updatedAtMs, id);
    }

    // ── repo_pr_source ──────────────────────────────────────────────

    /** Upsert one cataloged PR. The composite primary key
     *  {@code (workspace_id, repo, pr_number)} makes a rerun idempotent —
     *  it refreshes the row in place rather than inserting a duplicate. */
    public void upsertPrSource(RepoPrSource s)
    {
        jdbc.update("""
                INSERT INTO repo_pr_source (
                    workspace_id, repo, pr_number, merged_at, merge_sha,
                    metadata_json, completeness_json, source_digest,
                    priority_score, analysis_state, extractor_version,
                    analyzed_at_ms, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(workspace_id, repo, pr_number) DO UPDATE SET
                    merged_at = excluded.merged_at,
                    merge_sha = excluded.merge_sha,
                    metadata_json = excluded.metadata_json,
                    completeness_json = excluded.completeness_json,
                    source_digest = excluded.source_digest,
                    extractor_version = excluded.extractor_version
                """,
                s.workspaceId(), s.repo(), s.prNumber(), s.mergedAt(),
                s.mergeSha(), s.metadataJson(), s.completenessJson(),
                s.sourceDigest(), s.priorityScore(), s.analysisState(),
                s.extractorVersion(), s.analyzedAtMs(), s.lastError());
    }

    public int countCataloged(String workspaceId, String repo)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM repo_pr_source
                WHERE workspace_id = ? AND repo = ?
                """, Integer.class, workspaceId, repo);
        return n == null ? 0 : n;
    }

    public int countAnalyzed(String workspaceId, String repo)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM repo_pr_source
                WHERE workspace_id = ? AND repo = ? AND analysis_state = 'analyzed'
                """, Integer.class, workspaceId, repo);
        return n == null ? 0 : n;
    }

    // ── repo_doc_section ────────────────────────────────────────────

    public void deleteDocSections(String workspaceId, String repo)
    {
        jdbc.update("DELETE FROM repo_doc_section WHERE workspace_id = ? AND repo = ?",
                workspaceId, repo);
    }

    public void insertDocSection(
            String workspaceId,
            String repo,
            String path,
            String headingPath,
            int lineStart,
            int lineEnd,
            String contentDigest,
            String knowledgeType,
            String tagsJson,
            String commitSha,
            long indexedAtMs)
    {
        jdbc.update("""
                INSERT INTO repo_doc_section (
                    workspace_id, repo, path, heading_path, line_start,
                    line_end, content_digest, knowledge_type, tags_json,
                    commit_sha, indexed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(workspace_id, repo, path, heading_path) DO UPDATE SET
                    line_start = excluded.line_start,
                    line_end = excluded.line_end,
                    content_digest = excluded.content_digest,
                    knowledge_type = excluded.knowledge_type,
                    tags_json = excluded.tags_json,
                    commit_sha = excluded.commit_sha,
                    indexed_at_ms = excluded.indexed_at_ms
                """,
                workspaceId, repo, path, headingPath, lineStart, lineEnd,
                contentDigest, knowledgeType, tagsJson, commitSha, indexedAtMs);
    }

    public int countDocSections(String workspaceId, String repo)
    {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM repo_doc_section
                WHERE workspace_id = ? AND repo = ?
                """, Integer.class, workspaceId, repo);
        return n == null ? 0 : n;
    }

    // ── repo_project_capsule ────────────────────────────────────────

    public void upsertCapsule(
            String workspaceId,
            String repo,
            String capsuleMd,
            String sourceDigest,
            long generatedAtMs)
    {
        jdbc.update("""
                INSERT INTO repo_project_capsule (
                    workspace_id, repo, capsule_md, source_digest, generated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(workspace_id) DO UPDATE SET
                    repo = excluded.repo,
                    capsule_md = excluded.capsule_md,
                    source_digest = excluded.source_digest,
                    generated_at_ms = excluded.generated_at_ms
                """, workspaceId, repo, capsuleMd, sourceDigest, generatedAtMs);
    }

    public Optional<String> capsuleDigest(String workspaceId)
    {
        return jdbc.queryForList("""
                SELECT source_digest FROM repo_project_capsule WHERE workspace_id = ?
                """, String.class, workspaceId).stream().findFirst();
    }

    private static final RowMapper<ProjectLearningRun> RUN_MAPPER = ProjectLearningStore::mapRun;

    private static ProjectLearningRun mapRun(ResultSet rs, int rowNum)
            throws SQLException
    {
        Long completed = rs.getObject("completed_at_ms") == null
                ? null : rs.getLong("completed_at_ms");
        return new ProjectLearningRun(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("repo"),
                rs.getString("trigger_kind"),
                rs.getString("state"),
                rs.getString("snapshot_sha"),
                rs.getString("catalog_cursor"),
                rs.getString("counts_json"),
                rs.getInt("extractor_version"),
                rs.getLong("started_at_ms"),
                rs.getLong("updated_at_ms"),
                completed,
                rs.getString("last_error"));
    }
}
