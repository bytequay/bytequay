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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema-level checks for the V152 unification migration: {@code local_pr*}
 * became {@code pr*}, the new {@code origin}/{@code repo}/{@code author}/
 * {@code synced_at_ms} columns landed, the origin/status check constraint
 * bites, and a task-origin PR still round-trips through the real repository.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestPRSchema
{
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PRStore prStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void migrationRenamesLocalPrTablesToTheUnifiedPrAggregate()
    {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' "
                        + "AND name IN ('pr','pr_commit','pr_timeline_event','pr_check','pr_comment',"
                        + "'local_pr','local_pr_commit','local_pr_timeline_event','local_pr_check','local_pr_comment')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "pr", "pr_commit", "pr_timeline_event", "pr_check", "pr_comment");

        List<String> prColumns = jdbc.query(
                "PRAGMA table_info(pr)", (rs, n) -> rs.getString("name"));
        assertThat(prColumns).contains("origin", "repo", "author", "synced_at_ms");

        List<String> commitColumns = jdbc.query(
                "PRAGMA table_info(pr_commit)", (rs, n) -> rs.getString("name"));
        assertThat(commitColumns).contains("pr_id");

        List<String> commentColumns = jdbc.query(
                "PRAGMA table_info(pr_comment)", (rs, n) -> rs.getString("name"));
        assertThat(commentColumns).contains("published_at_ms");
    }

    @Test
    void originStatusCheckConstraintRejectsAnExternalPrInALocalOnlyStatus()
    {
        // external + a local-only status violates the constraint.
        assertThatThrownBy(() -> insertPr("external", PR.STATUS_LOCAL_DRAFTED, "acme/widget", 7))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertPr("external", PR.STATUS_LOCAL_OPEN, "acme/widget", 8))
                .isInstanceOf(DataAccessException.class);

        // The legal shapes both insert cleanly.
        insertPr("external", PR.STATUS_REMOTE_OPEN, "acme/widget", 9);
        insertPr("task", PR.STATUS_LOCAL_DRAFTED, null, null);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pr WHERE origin IN ('external','task')"
                + " AND branch_name = 'schema-test'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void taskOriginPrRoundTripsThroughTheRealRepository()
    {
        String taskId = seedTask();
        PR created = PR.create(
                UUID.randomUUID().toString(), taskId,
                "feature/x", "main", "Add cache", "", Instant.parse("2026-07-01T00:00:00Z"));

        PR saved = prStore.save(created);
        PR reloaded = prStore.findById(saved.id()).orElseThrow();

        assertThat(reloaded.origin()).isEqualTo(PR.ORIGIN_TASK);
        assertThat(reloaded.repo()).isNull();
        assertThat(reloaded.author()).isNull();
        assertThat(reloaded.syncedAt()).isNull();
        assertThat(reloaded.taskId()).isEqualTo(taskId);
    }

    /** Regression test for the dangling `REFERENCES local_pr(id)` bug: V152
     *  rebuilt `pr` via create+drop+rename rather than a straight
     *  `ALTER TABLE RENAME`, so the child tables' FK clauses kept pointing at
     *  the now-gone `local_pr` until V154 rebuilt them too. Exercise every
     *  child-row writer for real, not just the `pr` row itself. */
    @Test
    void everyChildRowWriterRoundTripsThroughTheRealRepository()
    {
        String taskId = seedTask();
        PR pr = prStore.save(PR.create(
                UUID.randomUUID().toString(), taskId,
                "feature/x", "main", "Add cache", "", Instant.parse("2026-07-01T00:00:00Z")));

        PRCommit commit = prStore.addCommit(new PRCommit(
                UUID.randomUUID().toString(), pr.id(), "abc123", "msg", 1, 0,
                Instant.parse("2026-07-01T00:00:00Z"), null));
        PRTimelineEntry event = prStore.addEvent(new PRTimelineEntry(
                UUID.randomUUID().toString(), pr.id(), PRTimelineEntry.TYPE_COMMIT, "you",
                false, null, Instant.parse("2026-07-01T00:00:00Z"), null, null));
        PRCheck check = prStore.addCheck(new PRCheck(
                UUID.randomUUID().toString(), pr.id(), PRCheck.KIND_LOCAL, "mvn verify",
                PRCheck.STATUS_PASSED, 1000L, Instant.parse("2026-07-01T00:00:00Z"), null, null));
        PRComment comment = prStore.saveComment(new PRComment(
                UUID.randomUUID().toString(), pr.id(), PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "you", "note", Instant.parse("2026-07-01T00:00:00Z"),
                null, null, null, null, null, "RIGHT", null, null));

        assertThat(prStore.commitsFor(pr.id())).containsExactly(commit);
        assertThat(prStore.timelineFor(pr.id())).containsExactly(event);
        assertThat(prStore.checksFor(pr.id())).containsExactly(check);
        assertThat(prStore.commentsFor(pr.id())).containsExactly(comment);
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Schema test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }

    private void insertPr(String origin, String status, String repo, Integer remotePrNumber)
    {
        jdbc.update(
                "INSERT INTO pr(id, task_id, branch_name, base_branch, title, description, status, "
                        + "created_at_ms, origin, repo, remote_pr_number) "
                        + "VALUES (?, NULL, 'schema-test', 'main', 'T', '', ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), status,
                Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), origin, repo, remotePrNumber);
    }
}
