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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
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
 * Schema-level checks for the stages migrations: the new tables and
 * indexes exist, the {@code tasks} merge-notification column landed, the
 * {@code review_comment} source/remote-link check constraint bites.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskStageSchema
{
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void migrationCreatesTablesAndIndexes()
    {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' "
                        + "AND name IN ('task_stage','task_stage_event','review_comment')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "task_stage", "task_stage_event", "review_comment");

        List<String> indexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' "
                        + "AND name IN ('idx_task_stage_task_state','idx_task_stage_task_opened',"
                        + "'idx_task_stage_event_stage_at','idx_task_stage_event_task_at',"
                        + "'idx_task_stage_event_type_at','idx_review_comment_task_resolved',"
                        + "'idx_review_comment_task_source')",
                String.class);
        assertThat(indexes).hasSize(7);

        List<String> taskColumns = jdbc.query(
                "PRAGMA table_info(tasks)", (rs, n) -> rs.getString("name"));
        assertThat(taskColumns).contains("merge_notification_sent_at_ms");
    }

    @Test
    void reviewCommentCheckConstraintTiesRemoteLinkToSource()
    {
        String taskId = seedTask(TaskPhase.IMPLEMENTING, false);

        // REMOTE_REVIEWER without a remote link violates the constraint.
        assertThatThrownBy(() -> insertComment(taskId, "REMOTE_REVIEWER", null))
                .isInstanceOf(DataAccessException.class);
        // LOCAL_USER with a remote link violates it too.
        assertThatThrownBy(() -> insertComment(taskId, "LOCAL_USER", "https://github.com/x/y/pull/1"))
                .isInstanceOf(DataAccessException.class);

        // The legal shapes both insert cleanly.
        insertComment(taskId, "LOCAL_USER", null);
        insertComment(taskId, "REMOTE_REVIEWER", "https://github.com/x/y/pull/1#r1");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_comment WHERE task_id = ?", Integer.class, taskId);
        assertThat(count).isEqualTo(2);
    }

    private void insertComment(String taskId, String source, String remoteLink)
    {
        jdbc.update(
                "INSERT INTO review_comment(id,task_id,file,line,body,created_at_ms,source,remote_link,resolved) "
                        + "VALUES (?,?,?,?,?,?,?,?,0)",
                UUID.randomUUID().toString(), taskId, "src/Foo.java", 1, "body",
                Instant.parse("2026-06-20T10:00:00Z").toEpochMilli(), source, remoteLink);
    }

    private String seedTask(TaskPhase phase, boolean completed)
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
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
        if (completed) {
            taskStore.completeTask(taskId, Instant.parse("2026-06-20T11:00:00Z"));
        }
        taskStore.updatePhase(taskId, phase);
        return taskId;
    }
}
