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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the heading-level document index and the bounded project capsule
 * against a small on-disk fixture repository.
 */
class TestDocumentIndexer
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private DocumentIndexer indexer;
    private Path repoRoot;

    @BeforeEach
    void setUp()
            throws IOException
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("docs.db") + "?foreign_keys=ON";
        copyTo(tempDir.resolve("docs.db"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        indexer = new DocumentIndexer(new ProjectLearningStore(jdbc));

        repoRoot = tempDir.resolve("clone");
        Files.createDirectories(repoRoot.resolve("backend"));
        Files.writeString(repoRoot.resolve("README.md"), """
                # Widget

                A widget factory for gadgets.

                ## Setup

                Run mvn verify.

                ### Prereqs

                Java 21.
                """);
        Files.writeString(repoRoot.resolve("backend").resolve("README.md"),
                "# Backend\n\nThe service.\n");
    }

    @Test
    void testSplitsDocumentsByHeadingWithHeadingTrail()
    {
        DocumentIndexer.IndexResult result = indexer.index("ws-1", "acme/widget", repoRoot, "sha1");

        assertThat(result.sections()).isGreaterThanOrEqualTo(4);
        List<String> headingPaths = jdbc.queryForList("""
                SELECT heading_path FROM repo_doc_section
                WHERE workspace_id = 'ws-1' AND path = 'README.md'
                ORDER BY line_start
                """, String.class);
        assertThat(headingPaths).contains("Widget", "Widget > Setup", "Widget > Setup > Prereqs");
    }

    @Test
    void testCapsuleIsBoundedAndCarriesIdentityAndModules()
    {
        DocumentIndexer.IndexResult result = indexer.index("ws-1", "acme/widget", repoRoot, "sha1");

        assertThat(result.capsuleMd()).hasSizeLessThanOrEqualTo(DocumentIndexer.CAPSULE_CHAR_CAP);
        assertThat(result.capsuleMd()).contains("widget factory for gadgets");
        assertThat(result.capsuleMd()).contains("backend");
    }

    @Test
    void testReindexReplacesPriorSections()
    {
        indexer.index("ws-1", "acme/widget", repoRoot, "sha1");
        int first = jdbc.queryForObject(
                "SELECT count(*) FROM repo_doc_section WHERE workspace_id = 'ws-1'", Integer.class);
        indexer.index("ws-1", "acme/widget", repoRoot, "sha2");
        int second = jdbc.queryForObject(
                "SELECT count(*) FROM repo_doc_section WHERE workspace_id = 'ws-1'", Integer.class);

        assertThat(second).isEqualTo(first);
    }
}
