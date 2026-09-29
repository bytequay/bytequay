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
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueGoal;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.google.common.collect.ImmutableList;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteIssueDevelopmentStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-issue-development-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00.123Z");
    private static final long REPOSITORY_ID = 100;

    @Autowired
    private SqliteIssueDevelopmentStore store;

    @Autowired
    private SqliteRepositoryIssueStore issues;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private SqliteAgentUnitOfWork unitOfWork;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void retainedWrappedContextNeverBecomesUserInstructionProvenance()
    {
        GitHubIssueSource source = source(issue(42, "alice"));
        store.insertTask(new IssueTask("earlier-task", "workspace-1", source, Optional.empty(), NOW));
        entityManager.createNativeQuery("UPDATE issue_task SET initial_context = :context WHERE id = :id")
                .setParameter("context", "Earlier wrapped source prompt that must not be displayed")
                .setParameter("id", "earlier-task").executeUpdate();
        entityManager.flush();
        entityManager.clear();
        IssueTask retained = store.findByTaskId("earlier-task").orElseThrow();
        assertThat(retained.source()).isEqualTo(source);
        assertThat(retained.instructions()).isEmpty();
    }

    @Test
    void retainsClassificationAndAcceptedContextAfterIssueDeletion()
    {
        RepositoryIssue issue = issue(42, "alice");
        issues.saveSyncPage(REPOSITORY_ID, ImmutableList.of(issue), "sync-1");
        IssueGoal goal = new IssueGoal("GitHub.COM", issue.id(), "Owner/Repo", issue.number(), true, NOW);
        GitHubIssueSource source = source(issue);
        IssueTask task = new IssueTask("task-1", "workspace-1", source, Optional.of("Accepted instructions"), NOW);
        store.saveGoal(goal);
        store.insertTask(task);
        issues.delete(REPOSITORY_ID, issue.number());
        entityManager.flush();
        entityManager.clear();

        assertThat(issues.find(REPOSITORY_ID, issue.number())).isEmpty();
        assertThat(store.findGoal("GITHUB.COM", issue.id())).contains(goal);
        assertThat(store.findTask("GITHUB.COM", issue.id())).contains(task);
        assertThat(store.findByTaskId(task.taskId())).contains(task);
        assertThat(task.source().host()).isEqualTo("github.com");
        assertThat(task.source().repo()).isEqualTo("owner/repo");

        IssueGoal ordinary = new IssueGoal(goal.host(), goal.issueId(), goal.repo(), goal.number(), false, NOW.plusSeconds(1));
        store.saveGoal(ordinary);
        issues.saveSyncPage(REPOSITORY_ID, ImmutableList.of(issue), "sync-2");
        issues.completeSync(REPOSITORY_ID, "sync-3", NOW.plusSeconds(2));
        entityManager.flush();
        entityManager.clear();

        assertThat(issues.find(REPOSITORY_ID, issue.number())).isEmpty();
        assertThat(store.findGoal("github.com", issue.id())).contains(ordinary);
        assertThat(store.findByTaskId(task.taskId())).contains(task);
    }

    @Test
    void attentionIncludesEveryCachedRepositoryIssueRegardlessOfAssignmentGoalOrLinkedTask()
    {
        List<RepositoryIssue> firstPage = IntStream.rangeClosed(1, 100).mapToObj(number -> issue(number, "bob")).collect(ImmutableList.toImmutableList());
        issues.saveSyncPage(REPOSITORY_ID, firstPage, "sync-1");
        RepositoryIssue goal = issue(101, "alice");
        RepositoryIssue linked = issue(102, "alice");
        RepositoryIssue eligible = issue(103, "ALICE");
        RepositoryIssue unrelated = issue(104, "alice-other");
        RepositoryIssue next = issue(105, "alice");
        issues.saveSyncPage(REPOSITORY_ID, ImmutableList.of(goal, linked, eligible, unrelated, next), "sync-1");
        store.saveGoal(new IssueGoal("github.com", goal.id(), "owner/repo", goal.number(), true, NOW));
        store.saveGoal(new IssueGoal("github.com", next.id(), "owner/repo", next.number(), false, NOW));
        store.insertTask(new IssueTask("linked-task", "another-workspace", source(linked), Optional.of("Accepted context"), NOW));
        issues.saveSyncPage(200, ImmutableList.of(issue(106, "alice")), "sync-1");
        entityManager.flush();
        entityManager.clear();

        assertThat(issues.findPage(REPOSITORY_ID, RepoRef.of("owner", "repo"), 0, 100).issues()).doesNotContain(eligible);
        assertThat(issues.findPage(REPOSITORY_ID, RepoRef.of("owner", "repo"), 0, Integer.MAX_VALUE).issues())
                .hasSize(105).containsAll(firstPage).contains(goal, linked, eligible, unrelated, next).doesNotContain(issue(106, "alice"));
    }

    @Test
    void enforcesOneTaskPerSourceAcrossWorkspaces()
    {
        GitHubIssueSource source = source(issue(42, "alice"));
        store.insertTask(new IssueTask("task-1", "workspace-1", source, Optional.of("First accepted context"), NOW));
        entityManager.clear();

        assertThatThrownBy(() -> store.insertTask(new IssueTask("task-2", "workspace-2", source, Optional.of("Second context"), NOW)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed: issue_task.source_slot");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackTaskCreationWhenTheIssueAssociationConflicts()
    {
        AgentTask original = agentTask();
        AgentTask rejected = agentTask();
        GitHubIssueSource source = source(issue(9_000, "alice"));
        IssueTask originalLink = new IssueTask(original.id().toString(), "workspace-1", source, Optional.of("First accepted context"), NOW);
        unitOfWork.execute(() -> {
            tasks.insert(original);
            store.insertTask(originalLink);
            return null;
        });

        try {
            assertThatThrownBy(() -> unitOfWork.execute(() -> {
                tasks.insert(rejected);
                store.insertTask(new IssueTask(rejected.id().toString(), "workspace-1", source, Optional.of("Rejected duplicate context"), NOW));
                return null;
            })).isInstanceOf(DataAccessException.class).hasMessageContaining("UNIQUE constraint failed: issue_task.source_slot");

            assertThat(tasks.find(rejected.id())).isEmpty();
            assertThat(store.findByTaskId(rejected.id().toString())).isEmpty();
            assertThat(tasks.find(original.id())).contains(original);
            assertThat(store.findTask(source.host(), source.issueId())).contains(originalLink);
        }
        finally {
            tasks.delete(rejected.id());
            tasks.delete(original.id());
        }
    }

    @Test
    void deletesTheIssueLinkWithItsTaskWhileRetainingGoalClassification()
    {
        AgentTask task = agentTask();
        GitHubIssueSource source = source(issue(42, "alice"));
        IssueGoal goal = new IssueGoal(source.host(), source.issueId(), source.repo(), source.number(), true, NOW);
        tasks.insert(task);
        store.insertTask(new IssueTask(task.id().toString(), "workspace-1", source, Optional.of("Accepted context"), NOW));
        store.saveGoal(goal);
        entityManager.clear();

        tasks.delete(task.id());
        entityManager.clear();

        assertThat(tasks.find(task.id())).isEmpty();
        assertThat(store.findByTaskId(task.id().toString())).isEmpty();
        assertThat(store.findTask(source.host(), source.issueId())).isEmpty();
        assertThat(store.findGoal(source.host(), source.issueId())).contains(goal);
    }

    @Test
    void refreshesContentWithoutRemovingOtherCachedFactsOrTheSyncMarker()
    {
        RepositoryIssue issue = issue(42, "alice");
        issues.saveSyncPage(REPOSITORY_ID, ImmutableList.of(issue), "sync-1");
        issues.updateContent(REPOSITORY_ID, issue.number(), "Fresh title", "Fresh body", NOW.plusSeconds(1));
        issues.updateContent(REPOSITORY_ID, 99, "Missing title", "Missing body", NOW);
        issues.completeSync(REPOSITORY_ID, "sync-1", NOW.plusSeconds(2));
        entityManager.flush();
        entityManager.clear();

        RepositoryIssue refreshed = issues.find(REPOSITORY_ID, issue.number()).orElseThrow();
        assertThat(refreshed.title()).isEqualTo("Fresh title");
        assertThat(refreshed.body()).isEqualTo("Fresh body");
        assertThat(refreshed.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(refreshed.assignees()).isEqualTo(issue.assignees());
        assertThat(refreshed.labels()).isEqualTo(issue.labels());
        assertThat(refreshed.commentCount()).isEqualTo(issue.commentCount());
        assertThat(refreshed.createdAt()).isEqualTo(issue.createdAt());
        assertThat(issues.find(REPOSITORY_ID, 99)).isEmpty();
    }

    @Test
    void keepsNewerActivityAndTheSyncMarkerAfterAnIssueWrite()
    {
        RepositoryIssue issue = issue(42, "alice");
        issues.saveSyncPage(REPOSITORY_ID, ImmutableList.of(issue), "sync-1");
        issues.updateActivity(REPOSITORY_ID, issue.number(), 4, NOW);
        issues.updateActivity(REPOSITORY_ID, issue.number(), 1, NOW.minusSeconds(1));
        issues.updateActivity(REPOSITORY_ID, 99, 1, NOW);
        issues.completeSync(REPOSITORY_ID, "sync-1", NOW.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        RepositoryIssue refreshed = issues.find(REPOSITORY_ID, issue.number()).orElseThrow();
        assertThat(refreshed.commentCount()).isEqualTo(4);
        assertThat(refreshed.updatedAt()).isEqualTo(NOW);
        assertThat(refreshed.title()).isEqualTo(issue.title());
        assertThat(refreshed.body()).isEqualTo(issue.body());
        assertThat(refreshed.assignees()).isEqualTo(issue.assignees());
        assertThat(refreshed.labels()).isEqualTo(issue.labels());
        assertThat(issues.find(REPOSITORY_ID, 99)).isEmpty();
    }

    private static GitHubIssueSource source(RepositoryIssue issue)
    {
        return new GitHubIssueSource("GitHub.COM", issue.id(), "Owner/Repo", issue.number(), issue.htmlUrl(), issue.title(), issue.body(), issue.updatedAt());
    }

    private static AgentTask agentTask()
    {
        return AgentTask.created(TaskId.random(), Optional.of("workspace-1"), true, "Issue development", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/bytequay-issue-worktree"), AgentPermissionPolicy.AUTO, NOW);
    }

    private static RepositoryIssue issue(int number, String assignee)
    {
        return new RepositoryIssue(number + 1_000L, number, "Issue " + number, "Description\nwith details", "author", "", "https://github.com/owner/repo/issues/" + number,
                ImmutableList.of("bug"), ImmutableList.of(assignee), 3, NOW.minusSeconds(1_000), NOW.minusSeconds(number));
    }
}
