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
import com.bytequay.app.domain.PRTriageState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.google.common.collect.ImmutableSet;
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
    private SqlitePRStore prStore;
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
    void findByRepoAndRemotePrNumberReturnsTheExternalRowWhenATaskSharesTheNumber()
    {
        // A task that publishes its PR stamps the same remote number onto its
        // own origin='task' row, so (repo, number) matches two rows. The
        // finder must return the single external row, not blow up with
        // NonUniqueResultException. Distinct branch_name so these rows don't
        // pollute the branch-scoped counts other tests assert on (the shared
        // SQLite context isn't rolled back between tests).
        insertDupPr("task", PR.STATUS_MERGED, "dup/repo", 42);
        insertDupPr("external", PR.STATUS_REMOTE_OPEN, "dup/repo", 42);

        PR found = prStore.findByRepoAndRemotePrNumber("dup/repo", 42).orElseThrow();

        assertThat(found.origin()).isEqualTo(PR.ORIGIN_EXTERNAL);
        assertThat(found.status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
    }

    @Test
    void reparentChildrenFoldsAnExternalTwinIntoTheTaskRowWithDedup()
    {
        String taskId = seedTask();
        Instant t = Instant.parse("2026-07-01T00:00:00Z");
        // Surviving (pushed) task row + the dashboard's external twin — same
        // (repo, number).
        prStore.save(PR.create("fold-task", taskId, "dev/x", "main", "T", "", t)
                .withRemote("dup/repo", 77, "https://github.com/dup/repo/pull/77", t)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, t));
        prStore.save(PR.createExternal("fold-ext", "dup/repo", 77,
                "https://github.com/dup/repo/pull/77", "@octocat", "head", "main", "T", "",
                PR.STATUS_REMOTE_OPEN, t, null, null));

        // task owns a commit shared with the twin (same sha).
        prStore.addCommit(new PRCommit("c-task", "fold-task", "sha-shared", "shared", 1, 0, t, null));
        // twin: the same shared commit (a dup) + one only it has, plus a remote
        // check and a triage row the survivor lacks.
        prStore.addCommit(new PRCommit("c-ext-dup", "fold-ext", "sha-shared", "shared", 1, 0, t, null));
        prStore.addCommit(new PRCommit("c-ext-new", "fold-ext", "sha-ext", "ext only", 2, 0, t, null));
        prStore.addCheck(new PRCheck("chk-ext", "fold-ext", PRCheck.KIND_REMOTE, "build",
                PRCheck.STATUS_PASSED, 1L, t, t, "run-ext"));
        prStore.saveTriage(new PRTriageState("fold-ext", t, null, null, null, null, null));
        jdbc.update("""
                INSERT INTO pr_review_draft
                    (pr_id, unified_pr_id, summary, provider_id, model, status)
                VALUES (0, 'fold-ext', 'quick result', 'test', 'test', 'COMPLETE')
                """);

        prStore.reparentChildren("fold-ext", "fold-task");
        prStore.deletePr("fold-ext");

        // Shared commit deduped (not doubled); the twin-only commit moved → 2.
        assertThat(prStore.commitsFor("fold-task")).extracting(PRCommit::sha)
                .containsExactlyInAnyOrder("sha-shared", "sha-ext");
        // The remote check moved onto the survivor.
        assertThat(prStore.checksFor("fold-task")).extracting(PRCheck::runId).contains("run-ext");
        // Triage moved (survivor had none).
        assertThat(prStore.findTriage("fold-task")).isPresent();
        // The one-shot review follows the surviving unified PR identity.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pr_review_draft WHERE unified_pr_id = 'fold-task'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pr_review_draft WHERE unified_pr_id = 'fold-ext'",
                Integer.class)).isZero();
        // The twin row is gone (its redundant children cascaded away).
        assertThat(prStore.findById("fold-ext")).isEmpty();
    }

    @Test
    void reparentChildrenLetsTheTwinsDashboardTriageWinOverTheSurvivorsOwn()
    {
        String taskId = seedTask();
        Instant t = Instant.parse("2026-07-01T00:00:00Z");
        prStore.save(PR.create("t2-task", taskId, "dev/y", "main", "T", "", t)
                .withRemote("dup/repo", 88, "https://github.com/dup/repo/pull/88", t)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, t));
        prStore.save(PR.createExternal("t2-ext", "dup/repo", 88,
                "https://github.com/dup/repo/pull/88", "@octocat", "head", "main", "T", "",
                PR.STATUS_REMOTE_OPEN, t, null, null));
        // Survivor was merely viewed on a task surface; the twin carries a snooze
        // the user set from the dashboard — that intent must survive the fold.
        prStore.saveTriage(new PRTriageState("t2-task", t, null, null, null, null, null));
        Instant snoozeUntil = Instant.parse("2026-07-05T00:00:00Z");
        prStore.saveTriage(new PRTriageState("t2-ext", t, null, null, snoozeUntil, t, "manual"));

        prStore.reparentChildren("t2-ext", "t2-task");
        prStore.deletePr("t2-ext");

        assertThat(prStore.findTriage("t2-task")).get()
                .extracting(PRTriageState::snoozedUntil).isEqualTo(snoozeUntil);
    }

    @Test
    void reparentChildrenMergesAgentReviewHistoryIntoTheTaskOwnedReview()
    {
        String taskId = seedTask();
        String taskThreadId = taskStore.findTaskById(taskId).orElseThrow().threadId();
        Instant t = Instant.parse("2026-07-01T00:00:00Z");
        prStore.save(PR.create("review-task", taskId, "dev/review", "main", "T", "", t)
                .withRemote("dup/repo", 99, "https://github.com/dup/repo/pull/99", t)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, t));
        prStore.save(PR.createExternal("review-ext", "dup/repo", 99,
                "https://github.com/dup/repo/pull/99", "@octocat", "head", "main", "T", "",
                PR.STATUS_REMOTE_OPEN, t, null, null));

        Thread reviewThread = new Thread(
                "review-thread-ext", ThreadKind.LOGIC_LOOP, "codex", null,
                "External review", ThreadStatus.COMPLETED, "codex",
                10L, 0L, 0L, t, t, t, null, ThreadFlow.REVIEW, "ws-default", null);
        threadStore.saveThread(reviewThread);
        insertReview("review-session-task", "review-task", taskThreadId, taskId, t);
        insertReview("review-session-ext", "review-ext", reviewThread.id(), null, t);
        insertRound("review-round-task", "review-session-task", "review-run-task", t);
        insertRound("review-round-ext", "review-session-ext", "review-run-ext", t);

        prStore.reparentChildren("review-ext", "review-task");
        prStore.deletePr("review-ext");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_session WHERE pr_id = 'review-task'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT session_id FROM review_round WHERE id IN ('review-round-task','review-round-ext')",
                String.class)).containsOnly("review-session-task");
        assertThat(jdbc.queryForObject(
                "SELECT task_id FROM agent_run WHERE id = 'review-run-ext'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT thread_id FROM agent_run WHERE id = 'review-run-ext'", String.class))
                .isNull();
        assertThat(threadStore.findThreadById(reviewThread.id())).isEmpty();
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

    @Test
    void retainChecksRemovesStaleRemoteRunsWithoutTouchingLocalChecks()
    {
        String taskId = seedTask();
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        PR pr = prStore.save(PR.create(
                UUID.randomUUID().toString(), taskId, "feature/x", "main", "CI snapshot", "", now));
        prStore.addCheck(new PRCheck(
                "local", pr.id(), PRCheck.KIND_LOCAL, "mvn test", PRCheck.STATUS_PASSED,
                1L, now, now, null));
        prStore.addCheck(new PRCheck(
                "stale", pr.id(), PRCheck.KIND_REMOTE, "build", PRCheck.STATUS_FAILED,
                null, now, now, "old-run"));
        prStore.addCheck(new PRCheck(
                "current", pr.id(), PRCheck.KIND_REMOTE, "build", PRCheck.STATUS_PASSED,
                null, now, now, "current-run"));

        prStore.retainChecks(pr.id(), PRCheck.KIND_REMOTE, ImmutableSet.of("current-run"));

        assertThat(prStore.checksFor(pr.id())).extracting(PRCheck::id)
                .containsExactlyInAnyOrder("local", "current");
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

    private void insertReview(
            String id, String prId, String ownerThreadId, String ownerTaskId, Instant createdAt)
    {
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                    workspace_id, owner_thread_id, owner_task_id, created_at_ms, updated_at_ms)
                VALUES (?, 'dup/repo', ?, 'base', 'head', 'ACTIVE',
                        'ws-default', ?, ?, ?, ?)
                """, id, prId, ownerThreadId, ownerTaskId,
                createdAt.toEpochMilli(), createdAt.toEpochMilli());
    }

    private void insertRound(
            String id, String sessionId, String runId, Instant createdAt)
    {
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status, iterations, budget,
                    started_at_ms, finished_at_ms, cost_usd_milli,
                    tokens_in, tokens_out, step_cursor, outcome)
                VALUES (?, 'review_compatibility_header',
                        'v2_review_assignment_turn_fk', ?, 'succeeded', 0, 50,
                        ?, ?, 0, 0, 0, 0, 'completed')
                """, runId, id, createdAt.toEpochMilli(), createdAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, cost_cents, created_at_ms)
                VALUES (?, ?, ?, 'manual', 'full', 'base',
                        'COMPLETED', '{}', 1, ?)
                """, id, sessionId, runId, createdAt.toEpochMilli());
    }

    private void insertPr(String origin, String status, String repo, Integer remotePrNumber)
    {
        insertPr("schema-test", origin, status, repo, remotePrNumber);
    }

    private void insertDupPr(String origin, String status, String repo, Integer remotePrNumber)
    {
        insertPr("dup-finder", origin, status, repo, remotePrNumber);
    }

    private void insertPr(String branch, String origin, String status, String repo, Integer remotePrNumber)
    {
        jdbc.update(
                "INSERT INTO pr(id, task_id, branch_name, base_branch, title, description, status, "
                        + "created_at_ms, origin, repo, remote_pr_number) "
                        + "VALUES (?, NULL, ?, 'main', 'T', '', ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), branch, status,
                Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), origin, repo, remotePrNumber);
    }
}
