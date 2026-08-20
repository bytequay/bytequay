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

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebase.ActionJournal;
import com.bytequay.core.rebase.BranchRebase.ActionType;
import com.bytequay.core.rebase.BranchRebase.PauseReason;
import com.bytequay.core.rebase.BranchRebase.Step;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteBranchRebaseStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-rebase-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String ORIGINAL = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String STOPPED = "c".repeat(40);

    @Autowired
    private SqliteBranchRebaseStore store;

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
    void roundTripsConflictAndActionJournal()
    {
        BranchRebase created = store.createOrGet(rebase("round-trip"));
        ActionJournal journal = new ActionJournal(
                ActionType.AGENT_TURN,
                Optional.of(STOPPED),
                ORIGINAL,
                1,
                "turn-1",
                NOW.plusSeconds(1));
        BranchRebase saved = store.save(created
                .resolving(STOPPED, NOW.plusSeconds(1))
                .recordTurnStarted(NOW.plusSeconds(2))
                .withJournal(journal, NOW.plusSeconds(3)));

        BranchRebase loaded = store.find(saved.id()).orElseThrow();
        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.currentStep()).contains(Step.RESOLVING);
        assertThat(loaded.conflictCommitSha()).contains(STOPPED);
        assertThat(loaded.journal()).contains(journal);
    }

    @Test
    void returnsOneRecordForARepeatedIdempotencyKey()
    {
        BranchRebase first = store.createOrGet(rebase("repeat"));
        BranchRebase second = store.createOrGet(rebase("repeat"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.findByIdempotencyKey("repeat")).contains(first);
    }

    @Test
    void rejectsAStaleSave()
    {
        BranchRebase created = store.createOrGet(rebase("version"));
        store.save(created.replaying(NOW.plusSeconds(1)));

        assertThatThrownBy(() -> store.save(created.replaying(NOW.plusSeconds(2))))
                .isInstanceOf(BranchRebaseException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void listsOnlyRunningRecordsOldestFirst()
    {
        BranchRebase older = store.createOrGet(rebase("older"));
        BranchRebase paused = store.createOrGet(rebase("paused"));
        BranchRebase newer = store.createOrGet(rebase("newer"));
        store.save(paused.pause(
                PauseReason.UNSAFE_GIT_STATE,
                "inspect",
                NOW.plusSeconds(1)));
        store.save(newer.replaying(NOW.plusSeconds(2)));

        assertThat(store.listRunning(10).stream().map(BranchRebase::id))
                .containsExactly(older.id(), newer.id());
    }

    private static BranchRebase rebase(String key)
    {
        return BranchRebase.create(
                BranchRebaseId.random(),
                key,
                "task-" + key,
                RepoRef.of("trinodb", "trino"),
                Path.of("/tmp/bytequay-rebase/" + key),
                "feature-" + key,
                "master",
                ORIGINAL,
                BASE,
                NOW);
    }
}
