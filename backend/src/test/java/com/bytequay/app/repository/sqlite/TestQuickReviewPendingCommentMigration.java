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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class TestQuickReviewPendingCommentMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void backfillsOnlyLatestMergeBlockingAiCommentsAsPendingRootsOnce()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("quick-review.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("219").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            seedReviewDrafts(connection);
        }

        Flyway.configure().dataSource(url, "", "").target("220").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM pr_comment
                    WHERE author = 'ai-reviewer'
                      AND resolved_at_ms IS NULL
                      AND dismissed_at_ms IS NULL
                      AND stripped_on_push_at_ms IS NULL
                      AND published_at_ms IS NULL
                    """)).isEqualTo(4);
            assertThat(singleString(connection, """
                    SELECT group_concat(
                        file_path || ':' || line_number || ':' || side || ':'
                        || COALESCE(start_line, '-') || ':' || COALESCE(start_side, '-') || ':' || body,
                        '|')
                    FROM (
                        SELECT file_path, line_number, side, start_line, start_side, body
                        FROM pr_comment
                        WHERE author = 'ai-reviewer'
                          AND resolved_at_ms IS NULL
                          AND dismissed_at_ms IS NULL
                          AND stripped_on_push_at_ms IS NULL
                          AND published_at_ms IS NULL
                        ORDER BY file_path)
                    """)).isEqualTo("""
                    src/Blocker.java:10:RIGHT:-:-:**Blocker**|src/Critical.java:20:LEFT:18:LEFT:**Edited critical**|src/Error.java:30:RIGHT:-:-:Error fallback|src/Request.java:40:RIGHT:-:-:Request body""");
            assertThat(singleLong(connection, """
                    SELECT COUNT(*)
                    FROM pr_comment
                    WHERE author = 'ai-reviewer'
                      AND file_path IN (
                        'src/Blocker.java', 'src/Critical.java',
                        'src/Error.java', 'src/Request.java')
                      AND (pr_id <> 'pr-target'
                        OR origin <> 'local'
                        OR scope <> 'file-line'
                        OR parent_comment_id IS NOT NULL
                        OR resolved_at_ms IS NOT NULL
                        OR dismissed_at_ms IS NOT NULL
                        OR stripped_on_push_at_ms IS NOT NULL
                        OR published_at_ms IS NOT NULL)
                    """)).isZero();
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM pr_comment
                    WHERE pr_id = 'pr-target'
                      AND file_path = 'src/Duplicate.java'
                      AND line_number = 50
                      AND body = 'Already pending'
                    """)).isEqualTo(1);
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM pr_comment
                    WHERE file_path IN (
                        'src/Resolved.java', 'src/CanonicalDismissed.java',
                        'src/Published.java', 'src/Stripped.java')
                    """)).isEqualTo(4);
            connection.createStatement().executeUpdate("""
                    DELETE FROM pr_comment
                    WHERE author = 'ai-reviewer' AND file_path = 'src/Blocker.java'
                    """);
        }

        Flyway.configure().dataSource(url, "", "").target("220").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM pr_comment
                    WHERE author = 'ai-reviewer'
                      AND resolved_at_ms IS NULL
                      AND dismissed_at_ms IS NULL
                      AND stripped_on_push_at_ms IS NULL
                      AND published_at_ms IS NULL
                    """)).isEqualTo(3);
        }
    }

    private static void seedReviewDrafts(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description, status,
                    created_at_ms, remote_pr_number, origin, repo)
                VALUES ('pr-target', 'feature', 'main', 'Target PR', '', 'remote-open',
                        1, 7, 'external', 'acme/widget')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO pr_review_draft(
                    id, pr_id, summary, provider_id, model, created_at, updated_at, unified_pr_id)
                VALUES
                    (10, 7, 'Older', 'provider', 'model',
                        '2026-07-01 00:00:00', '2026-07-01 00:00:00', 'pr-target'),
                    (11, 7, 'Loses timestamp tie', 'provider', 'model',
                        '2026-07-02 00:00:00', '2026-07-02 00:00:00', 'pr-target'),
                    (12, 7, 'Latest', 'provider', 'model',
                        '2026-07-02 00:00:00', '2026-07-02 00:00:00', 'pr-target'),
                    (13, 8, 'Orphan', 'provider', 'model',
                        '2026-07-03 00:00:00', '2026-07-03 00:00:00', 'missing-pr')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO pr_review_comment(
                    id, draft_id, file_path, line_number, body, severity, created_at,
                    edited_body, dismissed, source, side, start_line, start_side)
                VALUES
                    (100, 10, 'src/Old.java', 1, 'Old blocker', 'blocker',
                        '2026-07-01 00:00:00', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (101, 11, 'src/Tie.java', 2, 'Lower id blocker', 'blocker',
                        '2026-07-02 00:00:00', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (102, 12, 'src/Blocker.java', 10, '**Blocker**', ' blocker ',
                        '2026-07-02 00:00:01', NULL, 0, ' ai ', '', NULL, NULL),
                    (103, 12, 'src/Critical.java', 20, 'Critical original', 'CRITICAL',
                        '2026-07-02 00:00:02', '**Edited critical**', 0, 'AI', 'LEFT', 18, 'LEFT'),
                    (104, 12, 'src/Error.java', 30, 'Error fallback', 'error',
                        '2026-07-02 00:00:03', '   ', 0, 'AI', 'RIGHT', NULL, NULL),
                    (105, 12, 'src/Request.java', 40, 'Request body', 'Request Changes',
                        '2026-07-02 00:00:04', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (119, 12, 'src/Blocker.java', 10, '**Blocker**', 'critical',
                        '2026-07-02 00:00:04', NULL, 0, 'AI', 'LEFT', NULL, NULL),
                    (106, 12, 'src/Duplicate.java', 50, 'Already pending', 'critical',
                        '2026-07-02 00:00:05', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (116, 12, 'src/Resolved.java', 51, 'Already resolved', 'critical',
                        '2026-07-02 00:00:05', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (117, 12, 'src/CanonicalDismissed.java', 52, 'Already dismissed', 'critical',
                        '2026-07-02 00:00:05', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (118, 12, 'src/Published.java', 53, 'Already published', 'critical',
                        '2026-07-02 00:00:05', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (120, 12, 'src/Stripped.java', 54, 'Already stripped', 'critical',
                        '2026-07-02 00:00:05', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (107, 12, 'src/Warning.java', 60, 'Warning', 'warning',
                        '2026-07-02 00:00:06', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (114, 12, 'src/Info.java', 61, 'Info', 'info',
                        '2026-07-02 00:00:06', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (115, 12, 'src/Suggestion.java', 62, 'Suggestion', 'suggestion',
                        '2026-07-02 00:00:06', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (108, 12, 'src/Dismissed.java', 70, 'Dismissed', 'blocker',
                        '2026-07-02 00:00:07', NULL, 1, 'AI', 'RIGHT', NULL, NULL),
                    (109, 12, 'src/Human.java', 80, 'Human', 'blocker',
                        '2026-07-02 00:00:08', NULL, 0, 'HUMAN', 'RIGHT', NULL, NULL),
                    (110, 12, '   ', 90, 'Blank path', 'blocker',
                        '2026-07-02 00:00:09', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (111, 12, 'src/Zero.java', 0, 'Bad line', 'blocker',
                        '2026-07-02 00:00:10', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (112, 12, 'src/Blank.java', 100, '   ', 'blocker',
                        '2026-07-02 00:00:11', NULL, 0, 'AI', 'RIGHT', NULL, NULL),
                    (113, 13, 'src/Orphan.java', 1, 'Orphan blocker', 'blocker',
                        '2026-07-03 00:00:00', NULL, 0, 'AI', 'RIGHT', NULL, NULL)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO pr_comment(
                    id, pr_id, origin, scope, file_path, line_number,
                    author, body, created_at_ms, resolved_at_ms, dismissed_at_ms,
                    stripped_on_push_at_ms, published_at_ms, side)
                VALUES
                    ('existing-comment', 'pr-target', 'local', 'file-line',
                        'src/Duplicate.java', 50, 'you', 'Already pending', 1,
                        NULL, NULL, NULL, NULL, 'RIGHT'),
                    ('historical-resolved', 'pr-target', 'local', 'file-line',
                        'src/Resolved.java', 51, 'ai-reviewer', 'Already resolved', 1,
                        2, NULL, NULL, NULL, 'RIGHT'),
                    ('historical-dismissed', 'pr-target', 'local', 'file-line',
                        'src/CanonicalDismissed.java', 52, 'ai-reviewer', 'Already dismissed', 1,
                        NULL, 2, NULL, NULL, 'RIGHT'),
                    ('historical-published', 'pr-target', 'local', 'file-line',
                        'src/Published.java', 53, 'ai-reviewer', 'Already published', 1,
                        NULL, NULL, NULL, 2, 'RIGHT'),
                    ('historical-stripped', 'pr-target', 'local', 'file-line',
                        'src/Stripped.java', 54, 'ai-reviewer', 'Already stripped', 1,
                        NULL, NULL, 2, NULL, 'RIGHT')
                """);
    }

    private static String singleString(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long singleLong(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
