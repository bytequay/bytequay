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

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttempt.LogReference;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.CheckResult;
import com.bytequay.core.ci.CiAutofixRun.FinalFixupCheck;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.PushJournal;
import com.bytequay.core.ci.CiAutofixRun.RerunJournal;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStorageException;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.entity.CiAutofixAttemptEntity;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.CiAutofixAttemptJpaRepository;
import com.bytequay.persistence.sqlite.repository.CiAutofixRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteCiAutofixStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-ci-autofix-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String FIXED_HEAD = "c".repeat(40);

    @Autowired
    private SqliteCiAutofixStore store;

    @Autowired
    private CiAutofixAttemptJpaRepository attemptRepository;

    @Autowired
    private CiAutofixRunJpaRepository runRepository;

    @Autowired
    private WorkspaceJpaRepository workspaceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void roundTripsCompleteRunAttemptJournalsAndWarnings()
    {
        CiAutofixRun run = newRun("round-trip", "feature", NOW)
                .assignAgentTask("task-1", NOW.plusSeconds(1));
        CiAutofixAttempt attempt = newAttempt(run, 1, NOW.plusSeconds(2));
        run = run.addAttempt(attempt, NOW.plusSeconds(2))
                .linkAttemptTurn(attempt.id(), "turn-1", NOW.plusSeconds(3))
                .clearCurrentTurn("turn-1", NOW.plusSeconds(4));

        CiAutofixAttempt completed = run.attempts().getFirst()
                .withAgentNote("Untrusted explanation", NOW.plusSeconds(5))
                .withGitResult(
                        new GitResult(
                                FIXED_HEAD,
                                "fingerprint-1",
                                ImmutableList.of("src/Main.java"),
                                ImmutableList.of("Fix feature")),
                        NOW.plusSeconds(6))
                .withPushResult(HEAD, FIXED_HEAD, NOW.plusSeconds(7), NOW.plusSeconds(7))
                .withCiResult(CiResult.PASSED, NOW.plusSeconds(8))
                .withTimelineEvent("ci-autofix:push:1", NOW.plusSeconds(9));
        run = run.updateAttempt(completed, NOW.plusSeconds(9))
                .recordPushJournal(
                        new PushJournal(
                                attempt.id(),
                                HEAD,
                                FIXED_HEAD,
                                "fingerprint-1",
                                NOW.plusSeconds(10),
                                1),
                        NOW.plusSeconds(10))
                .recordRerunJournal(
                        new RerunJournal(
                                attempt.id(),
                                100,
                                HEAD,
                                1,
                                NOW.plusSeconds(11),
                                Optional.of(2)),
                        NOW.plusSeconds(11))
                .withFinalFixupCheck(
                        new FinalFixupCheck(FIXED_HEAD, CheckResult.VALID, "", NOW.plusSeconds(12)),
                        NOW.plusSeconds(12))
                .addWarning(new Warning("MEMORY", "memory unavailable", NOW.plusSeconds(13)), NOW.plusSeconds(13))
                .pause(PauseReason.RERUN_UNAVAILABLE, "waiting for user", NOW.plusSeconds(14));

        store.insert(run);
        entityManager.flush();
        entityManager.clear();

        SqliteCiAutofixStore restartedStore = new SqliteCiAutofixStore(
                runRepository, attemptRepository, workspaceRepository);
        assertThat(restartedStore.find(run.id())).contains(run);
        assertThat(restartedStore.findByIdempotencyKey("round-trip")).contains(run);
        assertThat(restartedStore.findByAgentTaskId("task-1")).contains(run);
        assertThat(restartedStore.findByAgentTaskId("missing-task")).isEmpty();
    }

    @Test
    void enforcesOptimisticUpdates()
    {
        CiAutofixRun original = newRun("optimistic", "feature-opt", NOW);
        store.insert(original);

        CiAutofixRun updated = store.update(original.assignAgentTask("task-1", NOW.plusSeconds(1)));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(store.find(original.id())).contains(updated);
        assertThatThrownBy(() -> store.update(original.assignAgentTask("task-2", NOW.plusSeconds(2))))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void roundTripsAPausedBranchRebaseLink()
    {
        CiAutofixRun run = newRun("rebase-link", "feature-rebase", NOW)
                .assignAgentTask("task-rebase", NOW.plusSeconds(1))
                .linkBranchRebase("rebase-1", NOW.plusSeconds(2))
                .pause(PauseReason.REBASE_PAUSED, "rebase paused", NOW.plusSeconds(3));

        store.insert(run);
        entityManager.flush();
        entityManager.clear();

        assertThat(store.find(run.id())).contains(run);
    }

    @Test
    void rejectsDuplicateStartIdempotencyKey()
    {
        store.insert(newRun("same-key", "feature-a", NOW));

        assertThatThrownBy(() -> store.insert(newRun("same-key", "feature-b", NOW.plusSeconds(1))))
                .isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void rejectsSecondNonFinishedRunForHeadBranch()
    {
        store.insert(newRun("branch-1", "same-feature", NOW));

        assertThatThrownBy(() -> store.insert(newRun("branch-2", "same-feature", NOW.plusSeconds(1))))
                .isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void allowsSamePullRequestNumberAndBranchInDifferentRepositories()
    {
        CiAutofixRun first = newRun(
                "repository-1", "same-feature", PullRequestRef.of("owner-1", "sample", 2234), NOW);
        CiAutofixRun second = newRun(
                "repository-2", "same-feature", PullRequestRef.of("owner-2", "sample", 2234), NOW);

        store.insert(first);
        store.insert(second);

        assertThat(store.find(first.id())).contains(first);
        assertThat(store.find(second.id())).contains(second);
    }

    @Test
    void releasesActiveBranchSlotAfterFinish()
    {
        CiAutofixRun first = newRun("finished-1", "reusable", NOW);
        store.insert(first);
        store.update(first.finish(FinishReason.CANCELLED, "cancelled", NOW.plusSeconds(1)));

        CiAutofixRun second = newRun("finished-2", "reusable", NOW.plusSeconds(2));
        store.insert(second);

        assertThat(store.find(second.id())).contains(second);
    }

    @Test
    void enforcesAttemptNumberUniquenessInSqlite()
    {
        CiAutofixRun run = newRun("attempt-slot", "attempt-feature", NOW);
        CiAutofixAttempt first = newAttempt(run, 1, NOW.plusSeconds(1));
        store.insert(run.addAttempt(first, NOW.plusSeconds(1)));
        CiAutofixAttempt duplicateNumber = newAttempt(run, 1, NOW.plusSeconds(2));

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(
                CiAutofixAttemptEntity.fromDomain(duplicateNumber)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }

    @Test
    void listsCompleteProjectionsInDeterministicOrder()
    {
        CiAutofixRun oldest = newRun("list-1", "list-a", NOW);
        CiAutofixRun paused = newRun("list-2", "list-b", NOW.plusSeconds(1))
                .pause(PauseReason.ERROR, "paused", NOW.plusSeconds(2));
        CiAutofixRun finished = newRun("list-3", "list-c", NOW.plusSeconds(3))
                .finish(FinishReason.ERROR, "finished", NOW.plusSeconds(4));
        store.insert(oldest);
        store.insert(paused);
        store.insert(finished);

        assertThat(store.listRecent(10)).containsExactly(finished, paused, oldest);
        assertThat(store.listNonFinished(10)).containsExactly(oldest, paused);
        assertThatThrownBy(() -> store.listRecent(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesLegacyWorktreeToItsManagedWorkspace()
    {
        String legacyPath = "/tmp/workspaces/legacy/repository";
        assertThat(jdbc.queryForObject(
                "SELECT \"notnull\" FROM pragma_table_info('ci_autofix_run') WHERE name = 'workspace_id'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT \"notnull\" FROM pragma_table_info('ci_autofix_run') WHERE name = 'worktree'",
                Integer.class)).isOne();
        workspaceRepository.saveAndFlush(WorkspaceEntity.fromDomain(workspace("legacy", legacyPath)));
        CiAutofixRun run = newRun("legacy", "legacy-feature", NOW);
        store.insert(run);
        jdbc.update(
                "UPDATE ci_autofix_run SET workspace_id = NULL, worktree = ? WHERE id = ?",
                legacyPath,
                run.id().toString());
        entityManager.clear();

        assertThat(store.find(run.id()).orElseThrow().scope().workspaceId()).isEqualTo("legacy");
    }

    @Test
    void rejectsAnIncompletePhysicalRepository()
    {
        CiAutofixRun run = newRun("incomplete-repository", "feature", NOW);
        store.insert(run);
        jdbc.update(
                "UPDATE ci_autofix_run SET physical_repository_owner = NULL WHERE id = ?",
                run.id().toString());
        entityManager.clear();

        assertThatThrownBy(() -> store.find(run.id()))
                .isInstanceOf(CiAutofixStorageException.class)
                .hasRootCauseInstanceOf(AgentStorageException.class)
                .hasStackTraceContaining("physical repository is incomplete");
    }

    private static CiAutofixRun newRun(String key, String headRef, Instant now)
    {
        return newRun(key, headRef, PullRequestRef.of("bytequay", "sample", 7), now);
    }

    private static CiAutofixRun newRun(
            String key,
            String headRef,
            PullRequestRef pullRequest,
            Instant now)
    {
        return CiAutofixRun.create(
                CiAutofixRunId.random(),
                key,
                new Scope(
                        pullRequest,
                        "workspace-" + key,
                        Optional.of(RepoRef.of("bytequay", "physical")),
                        "author",
                        headRef,
                        "main",
                        "codex"),
                HEAD,
                HEAD,
                BASE,
                new Authorization(true, true, true, 3, now, now.plus(Duration.ofHours(8))),
                now);
    }

    private static CiAutofixAttempt newAttempt(CiAutofixRun run, int number, Instant now)
    {
        return CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                run.id(),
                number,
                HEAD,
                ImmutableList.of(new FailureEvidence(
                        "compile-" + number,
                        "compile failed",
                        100,
                        1,
                        200,
                        1,
                        Optional.of(new LogReference("blobs/log-" + number, "text/plain", 10, false)))),
                now);
    }

    private static Workspace workspace(String id, String localRepositoryPath)
    {
        RepoRef repository = RepoRef.of("bytequay", "sample");
        return new Workspace(
                id,
                repository.fullName(),
                100,
                repository,
                Optional.empty(),
                Optional.empty(),
                100,
                repository,
                Topology.DIRECT,
                "main",
                "https://github.com/bytequay/sample.git",
                Optional.empty(),
                Optional.empty(),
                localRepositoryPath,
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }
}
