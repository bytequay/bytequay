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

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeSearchIndex;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.learning.KnowledgeRetrievalService;
import com.bytequay.app.service.learning.ProjectLearningStore;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestProjectIntelligenceToolHandlers
{
    @TempDir
    private Path tempDir;

    private ObjectMapper mapper;
    private KnowledgeItemStore knowledge;
    private ProjectLearningStore learning;
    private ProjectIntelligenceToolHandlers handlers;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("project-intelligence.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);

        mapper = new ObjectMapper();
        knowledge = new KnowledgeItemStore(jdbc, mapper);
        KnowledgeSearchIndex index = new KnowledgeSearchIndex(jdbc);
        index.initialize();
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                jdbc, knowledge, index, new SqliteMemoryItemStore(jdbc, mapper));
        learning = new ProjectLearningStore(jdbc);

        ThreadStore threads = mock(ThreadStore.class);
        WorkspaceRepositoryResolver repositories = mock(WorkspaceRepositoryResolver.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        when(threads.findThreadById("thread-1")).thenReturn(Optional.of(thread()));
        when(repositories.resolve("ws-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.empty());

        handlers = new ProjectIntelligenceToolHandlers(
                retrieval, knowledge, new ConceptRegistry(), threads,
                repositories, watchedRepos, learning, mapper);
    }

    @Test
    void testExploreProjectIncludesBoundedCapsuleAndStructuredProvenance()
            throws Exception
    {
        learning.upsertCapsule("ws-1", "acme/widget",
                "# Widget\n\n" + "x".repeat(5_000), "digest", 1);
        insert("k-active", "active", "Scheduler slots are bounded.", List.of(
                new KnowledgeItem.Provenance(
                        "review-thread", "discussion_r17", "abc123",
                        "core/Scheduler.java",
                        "https://github.com/acme/widget/pull/17#discussion_r17",
                        "content-digest")));

        JsonNode result = explore("scheduler slots");

        assertThat(result.path("project").path("capsule").asText())
                .startsWith("# Widget")
                .endsWith("… (truncated)")
                .hasSizeLessThanOrEqualTo(4_000);
        JsonNode source = result.path("knowledge").get(0).path("sources").get(0);
        assertThat(source.isObject()).isTrue();
        assertThat(source.path("ref").asText()).isEqualTo("discussion_r17");
        assertThat(source.path("kind").asText()).isEqualTo("review-thread");
        assertThat(source.path("url").asText())
                .isEqualTo("https://github.com/acme/widget/pull/17#discussion_r17");
        assertThat(source.path("path").asText()).isEqualTo("core/Scheduler.java");
        assertThat(source.path("commit").asText()).isEqualTo("abc123");
    }

    @Test
    void testExploreProjectReturnsOnlyActiveKnowledge()
            throws Exception
    {
        insert("k-active", "active", "Scheduler queue drains in bounded waves.", List.of());
        insert("k-pending", "pending", "Scheduler queue drains in bounded waves.", List.of());
        insert("k-retired", "retired", "Scheduler queue drains in bounded waves.", List.of());

        JsonNode result = explore("scheduler queue waves");

        assertThat(result.path("knowledge").size()).isEqualTo(1);
        assertThat(result.path("knowledge").get(0).path("ref").asText())
                .isEqualTo("knowledge:k-active");
    }

    private JsonNode explore(String question)
            throws Exception
    {
        ToolOutcome.Completed outcome = (ToolOutcome.Completed) handlers.exploreProject(
                new ProjectIntelligenceToolHandlers.ExploreProjectArgs(question),
                new ToolCall(ThreadScope.TRUNK, "thread-1", null, AgentRole.TRUNK));
        assertThat(outcome.isError()).isFalse();
        return mapper.readTree(outcome.text());
    }

    private void insert(
            String id,
            String lifecycle,
            String statement,
            List<KnowledgeItem.Provenance> provenance)
    {
        knowledge.insert(new KnowledgeItem(
                id, "ws-1", "acme/widget", "recurring-concern", null, statement,
                null, List.of("dev", "review"), "high", lifecycle, null, null,
                "pr-learning", KnowledgeItemStore.statementDigest(statement + id),
                "{}", 1, 1), provenance, List.of());
    }

    private static Thread thread()
    {
        return new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "codex", null,
                "Project intelligence", ThreadStatus.IDLE, null,
                0, 0, 0, Instant.EPOCH, Instant.EPOCH, null, null,
                ThreadFlow.BUILD, "ws-1", null);
    }
}
