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

import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Schema update keeps a v0.5.0 table as it was: its NOT NULL columns and enum CHECK lists stay. */
class TestSqliteCiAutofixStoreUpgrade
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-ci-autofix-upgrade-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    @Autowired
    private SqliteCiAutofixStore store;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createV050Table();
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "update");
    }

    @Test
    void insertsRunsIntoTheV050Table()
    {
        CiAutofixRun run = newRun();
        store.insert(run);
        entityManager.clear();

        assertThat(store.find(run.id())).contains(run);
    }

    @Test
    void loadsRunsV050FinishedWhenTheirAuthorizationExpired()
    {
        CiAutofixRun run = newRun();
        store.insert(run);
        CiAutofixRun expired = store.update(run.finish(FinishReason.AUTHORIZATION_EXPIRED, "CI Autofix authorization expired", NOW.plusSeconds(60)));
        entityManager.clear();

        assertThat(store.find(run.id())).contains(expired);
    }

    private static CiAutofixRun newRun()
    {
        PullRequestRef pullRequest = PullRequestRef.of("bytequay", "sample", 7);
        String key = "upgrade-" + UUID.randomUUID();
        return CiAutofixRun.create(
                CiAutofixRunId.random(),
                key,
                new Scope(pullRequest, RepoRef.of("bytequay", "sample"), "workspace", RepoRef.of("bytequay", "physical"), "author", key, "main", "codex", Optional.empty()),
                "a".repeat(40),
                "a".repeat(40),
                "b".repeat(40),
                new Authorization(NOW),
                NOW);
    }

    private static void createV050Table()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            // The DDL Hibernate generated for v0.5.0's entity.
            statement.execute("""
                    CREATE TABLE ci_autofix_run (id varchar(255) not null, active_branch_slot varchar(255), agent_task_id varchar(255), attempts_used integer not null,
                        author varchar(255) not null, authorization_expires_at varchar(255) not null, authorized_at varchar(255) not null, base_ref varchar(255) not null,
                        created_at varchar(255) not null, current_rebase_id varchar(255), current_turn_id varchar(255), expected_remote_head varchar(255) not null,
                        final_check_at varchar(255), final_check_diagnostics TEXT, final_check_head_sha varchar(255),
                        final_check_result varchar(255) check (final_check_result in ('VALID','INVALID')),
                        finish_reason varchar(255) check (finish_reason in ('GREEN','CANCELLED','STALE_HEAD','MAX_ATTEMPTS','AUTHORIZATION_EXPIRED','ERROR')),
                        fork_repository_name varchar(255), fork_repository_owner varchar(255), head_ref varchar(255) not null, lane_id varchar(255) not null,
                        latest_observed_base_sha varchar(255) not null, parent_task_id varchar(255),
                        pause_reason varchar(255) check (pause_reason in ('AUTHENTICATION_REQUIRED','TURN_FAILED','TURN_INTERRUPTED','TURN_CANCELLED','INVALID_GIT_RESULT',
                            'REBASE_PAUSED','INVALID_FIXUP_POSITION','RERUN_UNAVAILABLE','RERUN_EXHAUSTED','ERROR')),
                        physical_repository_name varchar(255) not null, physical_repository_owner varchar(255) not null, pull_request_number integer not null,
                        push_attempt_id varchar(255), push_expected_remote_sha varchar(255), push_local_fingerprint varchar(255), push_local_head_sha varchar(255),
                        push_retries integer, push_started_at varchar(255), repository_name varchar(255) not null, repository_owner varchar(255) not null,
                        rerun_attempt_id varchar(255), rerun_head_sha varchar(255), rerun_journal_head_sha varchar(255), rerun_observed_attempt integer,
                        rerun_previous_attempt integer, rerun_started_at varchar(255), rerun_workflow_run_id bigint, reruns_used integer not null,
                        start_idempotency_key varchar(255) not null, status varchar(255) not null check (status in ('ACTIVE','PAUSED','FINISHED')), summary TEXT,
                        task_start_commit varchar(255) not null, terminal_at varchar(255), updated_at varchar(255) not null, version bigint not null,
                        warnings_json TEXT not null, workspace_id varchar(255) not null, primary key (id))
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
