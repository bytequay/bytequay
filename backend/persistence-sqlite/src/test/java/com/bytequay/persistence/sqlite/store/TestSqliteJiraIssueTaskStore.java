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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteJiraIssueTaskStore
        extends AbstractTestSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00.123Z");
    private static final JiraIssueSource SOURCE = new JiraIssueSource("connection", "cloud", "10001", "APP-7",
            "https://company.atlassian.net/browse/APP-7", "Accepted title", "Accepted **description**", NOW);

    @Autowired
    private SqliteJiraIssueTaskStore sources;

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private SqliteAgentUnitOfWork unitOfWork;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, newDatabaseUrl("bytequay-jira-task-") + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void retainsSourceWithoutCacheAndDeletesItOnlyWithTheTask()
    {
        AgentTask task = task();
        JiraIssueTask link = new JiraIssueTask(task.id().toString(), "workspace", SOURCE, "Implement the accepted scope", NOW);
        tasks.insert(task);
        sources.insert(link);
        entityManager.flush();
        entityManager.clear();
        assertThat(sources.findByTaskId(task.id().toString())).contains(link);
        assertThat(sources.findBySource("cloud", "10001")).contains(link);
        tasks.delete(task.id());
        entityManager.clear();
        assertThat(sources.findBySource("cloud", "10001")).isEmpty();
    }

    @Test
    void aRetiredAttemptGivesItsTicketToTheNextAndKeepsItsSource()
    {
        AgentTask first = task();
        AgentTask second = task();
        AgentTask third = task();
        JiraIssueTask firstLink = new JiraIssueTask(first.id().toString(), "workspace", SOURCE, "First instruction", NOW);
        JiraIssueTask secondLink = new JiraIssueTask(second.id().toString(), "workspace", SOURCE, "Another attempt", NOW.plusSeconds(60));
        tasks.insert(first);
        sources.insert(firstLink);
        sources.retire(first.id().toString());
        tasks.insert(second);
        sources.insert(secondLink);
        tasks.insert(third);
        entityManager.flush();
        entityManager.clear();

        assertThat(sources.findBySource("cloud", "10001")).contains(secondLink);
        assertThat(sources.findByTaskId(first.id().toString())).as("the earlier attempt keeps its source").contains(firstLink);
        assertThatThrownBy(() -> sources.insert(new JiraIssueTask(third.id().toString(), "workspace", SOURCE, "Third", NOW)))
                .as("the ticket still has one current attempt")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sourceUniquenessAcrossConnectionsRollsBackThePreparedTask()
    {
        AgentTask original = task();
        AgentTask rejected = task();
        JiraIssueTask link = new JiraIssueTask(original.id().toString(), "workspace", SOURCE, "First instruction", NOW);
        unitOfWork.execute(() -> {
            tasks.insert(original);
            sources.insert(link);
            return null;
        });
        JiraIssueSource sameTicket = new JiraIssueSource("another-connection", "cloud", "10001", "RENAMED-2", SOURCE.url(), SOURCE.title(), SOURCE.body(), NOW);
        try {
            assertThatThrownBy(() -> unitOfWork.execute(() -> {
                tasks.insert(rejected);
                sources.insert(new JiraIssueTask(rejected.id().toString(), "another-workspace", sameTicket, "Duplicate", NOW));
                return null;
            })).isInstanceOf(DataAccessException.class).hasMessageContaining("UNIQUE constraint failed: jira_issue_task.source_slot");
            assertThat(tasks.find(rejected.id())).isEmpty();
            assertThat(sources.findByTaskId(rejected.id().toString())).isEmpty();
            assertThat(sources.findBySource("cloud", "10001")).contains(link);
        }
        finally {
            tasks.delete(rejected.id());
            tasks.delete(original.id());
        }
    }

    private static AgentTask task()
    {
        return AgentTask.created(TaskId.random(), Optional.of("workspace"), true, "Jira development", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/bytequay-jira-worktree"), AgentPermissionPolicy.AUTO, NOW);
    }
}
