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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReviewBuildSpawnCommitter
{
    @TempDir
    private Path tempDir;

    @Test
    void casFailureRollsBackCreatedTrunkAndFrozenSelection()
    {
        Fixture fixture = fixture("rollback.db");
        fixture.jdbc().update("""
                UPDATE review_passes SET round = 1 WHERE id = 'review-pass'
                """);

        assertThatThrownBy(() -> fixture.committer().commit(
                request(), pass(0), spawn(), List.of(finding()), Instant.EPOCH))
                .isInstanceOf(ReviewBuildSpawnCommitter.SpawnAttachConflict.class);

        assertThat(fixture.count("review_build_selection")).isZero();
        assertThat(fixture.count("threads WHERE id LIKE 'build-%'")).isZero();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT spawned_build_thread_id FROM review_passes
                WHERE id = 'review-pass'
                """, String.class)).isNull();
    }

    @Test
    void legacySpawnKeepsLegacyResolutionAuthorityAcrossRestart()
    {
        Fixture fixture = fixture("legacy.db", "LEGACY");

        ReviewBuildSpawnCommitter.CommittedSpawn committed =
                fixture.committer().commit(
                        request(), pass(0), spawn(), List.of(finding()),
                        Instant.EPOCH);

        assertThat(committed.selection()).isEmpty();
        assertThat(fixture.count("review_build_selection")).isZero();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT spawned_build_thread_id FROM review_passes
                WHERE id = 'review-pass'
                """, String.class)).isEqualTo(committed.thread().id());
        assertThat(fixture.restartedCommitter().findCommitted("review-pass"))
                .get().satisfies(restarted -> {
                    assertThat(restarted.thread().id())
                            .isEqualTo(committed.thread().id());
                    assertThat(restarted.selection()).isEmpty();
                });
    }

    @Test
    void duplicateAndConcurrentCommitsLeaveOneExactAttachment()
            throws Exception
    {
        Fixture fixture = fixture("concurrent.db");
        ReviewBuildSpawnCommitter.CommittedSpawn first = fixture.committer().commit(
                request(), pass(0), spawn(), List.of(finding()), Instant.EPOCH);
        assertThat(first.selection()).isPresent();

        assertThatThrownBy(() -> fixture.committer().commit(
                request(), pass(0), spawn(), List.of(finding()), Instant.EPOCH))
                .isInstanceOf(ReviewBuildSelectionStore.SelectionConflict.class)
                .satisfies(failure -> assertThat(
                        ((ReviewBuildSelectionStore.SelectionConflict) failure)
                                .sameInput()).isTrue());
        assertThat(fixture.committer().findCommitted("review-pass"))
                .get().extracting(value -> value.thread().id())
                .isEqualTo(first.thread().id());
        assertThat(fixture.count("review_build_selection")).isEqualTo(1);
        assertThat(fixture.count("threads WHERE id LIKE 'build-%'")).isEqualTo(1);

        Fixture concurrent = fixture("race.db");
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = Collections.synchronizedList(
                new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        results.add(concurrent.committer().commit(
                                request(), pass(0), spawn(), List.of(finding()),
                                Instant.EPOCH));
                    }
                    catch (Exception failure) {
                        results.add(failure);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(results).hasSize(2);
        assertThat(results.stream()
                .filter(ReviewBuildSpawnCommitter.CommittedSpawn.class::isInstance))
                .hasSize(1);
        assertThat(results.stream().filter(
                ReviewBuildSelectionStore.SelectionConflict.class::isInstance))
                .hasSize(1);
        assertThat(concurrent.count("review_build_selection")).isEqualTo(1);
        assertThat(concurrent.count("threads WHERE id LIKE 'build-%'")).isEqualTo(1);
    }

    private Fixture fixture(String file)
    {
        return fixture(file, "V2");
    }

    private Fixture fixture(String file, String createdTurnVersion)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file)
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("258").load().migrate();
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        insertThread(jdbc, "review-thread", "review", null, "V2");
        jdbc.update("""
                INSERT INTO review_passes(
                    id, thread_id, repo_full_name, pr_number, head_sha, phase,
                    round, round_cap, cost_cap_milli, cost_usd_milli,
                    created_at_ms, ended_at_ms, host_kind, host_id, kind)
                VALUES ('review-pass', 'review-thread', 'acme/widget', 42,
                    'head-1', 'TERMINATE', 0, 3, 500, 0, 2, 2,
                    'THREAD', 'review-thread', 'FRESH')
                """);
        jdbc.update("""
                INSERT INTO review_findings(
                    id, review_pass_id, path, line, severity, status, body,
                    created_at_ms)
                VALUES ('finding-1', 'review-pass', 'src/Main.java', 17,
                    'blocker', 'agreed', 'Fix the exact race', 3)
                """);

        ThreadService threads = mock(ThreadService.class);
        ThreadStore threadStore = mock(ThreadStore.class);
        AtomicInteger ids = new AtomicInteger();
        when(threads.create(any())).thenAnswer(ignored -> {
            String id = "build-" + ids.incrementAndGet();
            insertThread(jdbc, id, "build", null, createdTurnVersion);
            return buildThread(id);
        });
        doAnswer(invocation -> {
            Thread thread = invocation.getArgument(0);
            jdbc.update("""
                    UPDATE threads SET parent_review_pass_id = ? WHERE id = ?
                    """, thread.parentReviewPassId(), thread.id());
            return null;
        }).when(threadStore).saveThread(any());
        when(threadStore.findThreadById(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM threads WHERE id = ?
                    """, Integer.class, id);
            return count != null && count == 1
                    ? Optional.of(linkedBuildThread(id))
                    : Optional.empty();
        });

        ReviewBuildSelectionStore selections = new ReviewBuildSelectionStore(
                jdbc, new ObjectMapper().findAndRegisterModules());
        ReviewBuildSpawnCommitter committer = new ReviewBuildSpawnCommitter(
                threads, threadStore, selections, jdbc,
                new DataSourceTransactionManager(source));
        return new Fixture(
                jdbc, source, threads, threadStore, committer);
    }

    private static void insertThread(
            JdbcTemplate jdbc,
            String id,
            String flow,
            String parentPass,
            String turnVersion)
    {
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state,
                    aggregate_version, parent_review_pass_id)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'gpt-test',
                    0, 0, 0, 1, 1, 'workspace', ?, 1, ?,
                    CASE WHEN ? = 'V2' THEN 'IDLE' ELSE NULL END, 0, ?)
                """, id, id, flow, turnVersion, turnVersion, parentPass);
    }

    private static ThreadService.NewTaskRequest request()
    {
        return new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT, null, null,
                "Fix review findings on PR #42", null, null, "prompt",
                List.of(), null, 42, null, ThreadFlow.BUILD, "workspace", null);
    }

    private static ReviewBuildSelectionStore.SpawnInput spawn()
    {
        return new ReviewBuildSelectionStore.SpawnInput(
                "workspace", "Fix review findings on PR #42",
                ReviewBuildSelectionStore.SelectionPolicy.ALL_ELIGIBLE,
                ReviewBuildSpawnService.MODE_AUTHOR,
                "acme/widget", "acme/widget", "main", "feature/review");
    }

    private static ReviewPass pass(int round)
    {
        return new ReviewPass(
                "review-pass", "review-thread", "acme/widget", 42,
                "head-1", ReviewPhase.TERMINATE, round, 3, 500, 0,
                null, Instant.ofEpochMilli(2), Instant.ofEpochMilli(2), null);
    }

    private static ReviewFinding finding()
    {
        return new ReviewFinding(
                "finding-1", "review-pass", "src/Main.java", 17,
                ReviewFindingSeverity.BLOCKER, ReviewFindingStatus.AGREED,
                "Fix the exact race", null, null, Instant.ofEpochMilli(3));
    }

    private static Thread buildThread(String id)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "codex", null, id,
                ThreadStatus.IDLE, "gpt-test", 0L, 0L, 0L,
                Instant.ofEpochMilli(1), Instant.ofEpochMilli(1), null, null,
                ThreadFlow.BUILD, "workspace", null, null);
    }

    private static Thread linkedBuildThread(String id)
    {
        Thread thread = buildThread(id);
        return new Thread(
                thread.id(), thread.kind(), thread.provider(),
                thread.agentSessionId(), thread.title(), thread.status(),
                thread.model(), thread.costUsdMilli(), thread.tokensIn(),
                thread.tokensOut(), thread.createdAt(), thread.updatedAt(),
                thread.endedAt(), thread.errorMessage(), thread.flow(),
                thread.workspaceId(), thread.workModel(), "review-pass");
    }

    private record Fixture(
            JdbcTemplate jdbc,
            SQLiteDataSource dataSource,
            ThreadService threads,
            ThreadStore threadStore,
            ReviewBuildSpawnCommitter committer)
    {
        int count(String tableAndPredicate)
        {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + tableAndPredicate, Integer.class);
        }

        ReviewBuildSpawnCommitter restartedCommitter()
        {
            return new ReviewBuildSpawnCommitter(
                    threads, threadStore,
                    new ReviewBuildSelectionStore(
                            jdbc, new ObjectMapper().findAndRegisterModules()),
                    jdbc, new DataSourceTransactionManager(dataSource));
        }
    }
}
