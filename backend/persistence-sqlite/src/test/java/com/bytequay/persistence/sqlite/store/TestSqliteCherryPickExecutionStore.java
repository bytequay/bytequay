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

import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionJournal;
import com.bytequay.core.cherrypick.CherryPickExecution.ActionType;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PauseReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.core.cherrypick.CherryPickExecution.Step;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictType;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.cherrypick.CherryPickItem.PullRequestRefView;
import com.bytequay.core.cherrypick.CherryPickItem.Result;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.IntStream.rangeClosed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteCherryPickExecutionStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-cherry-pick-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String TARGET = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String TIP = "c".repeat(40);

    @Autowired
    private SqliteCherryPickExecutionStore store;

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
    void roundTripsAThreeHundredItemExecution()
    {
        List<CherryPickItem> items = rangeClosed(1, 300)
                .mapToObj(position -> planned(position, disposition(position)))
                .collect(toImmutableList());
        CherryPickExecution created = store.createOrGet(execution("round-trip", items));

        CherryPickExecution loaded = store.find(created.id()).orElseThrow();
        assertThat(loaded.items()).hasSize(300);
        assertThat(loaded.items()).isEqualTo(created.items());
        assertThat(loaded.workspace()).isEqualTo(created.workspace());
        assertThat(loaded.range()).isEqualTo(created.range());
        assertThat(loaded.cursor().orElseThrow().position()).isEqualTo(1);
    }

    @Test
    void roundTripsConflictAndJournalDetail()
    {
        CherryPickExecution created = store.createOrGet(execution("detail", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution updated = store.save(created
                .withJournal(
                        new ActionJournal(
                                ActionType.CONTINUE_SEQUENCE,
                                Optional.of(sha(1)),
                                TARGET,
                                2,
                                "action-key",
                                NOW),
                        NOW.plusSeconds(1))
                .recordConflict(
                        sha(1),
                        ImmutableList.of(new ConflictPath(
                                "core/trino-main/src/Main.java",
                                ConflictType.MODIFY_DELETE,
                                Optional.of("core/trino-main"))),
                        NOW.plusSeconds(2))
                .withStep(Step.RESOLVING, NOW.plusSeconds(3)));

        CherryPickExecution loaded = store.find(updated.id()).orElseThrow();
        CherryPickItem item = loaded.items().getFirst();
        assertThat(loaded.journal().orElseThrow().type()).isEqualTo(ActionType.CONTINUE_SEQUENCE);
        assertThat(loaded.journal().orElseThrow().attempt()).isEqualTo(2);
        assertThat(loaded.currentStep()).contains(Step.RESOLVING);
        assertThat(item.conflictPaths()).hasSize(1);
        assertThat(item.conflictPaths().getFirst().type()).isEqualTo(ConflictType.MODIFY_DELETE);
        assertThat(item.conflictPaths().getFirst().module()).contains("core/trino-main");
        assertThat(item.pullRequest().orElseThrow().number()).isEqualTo(1001);
    }

    @Test
    void returnsTheSameExecutionForARepeatedIdempotencyKey()
    {
        CherryPickExecution first = store.createOrGet(execution("repeat", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution second = store.createOrGet(execution("repeat", List.of(planned(1, Disposition.TO_CHERRY_PICK))));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.listRecent(10).stream().filter(value -> value.startIdempotencyKey().equals("repeat")))
                .hasSize(1);
    }

    @Test
    void rejectsASaveWhoseVersionMoved()
    {
        CherryPickExecution created = store.createOrGet(execution("version", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution saved = store.save(created.withStep(Step.APPLYING, NOW.plusSeconds(1)));
        assertThat(saved.version()).isGreaterThan(created.version());

        assertThatThrownBy(() -> store.save(created.withStep(Step.RESOLVING, NOW.plusSeconds(2))))
                .isInstanceOf(CherryPickConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void listsRecentNewestFirstAndRunningOldestFirst()
    {
        store.createOrGet(execution("older", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution newer = store.createOrGet(execution("newer", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        store.save(newer.withStep(Step.APPLYING, NOW.plusSeconds(60)));

        assertThat(store.listRecent(10).stream().map(CherryPickExecution::startIdempotencyKey))
                .containsSubsequence("newer", "older");
        assertThat(store.listRunning(10).stream().map(CherryPickExecution::startIdempotencyKey))
                .containsSubsequence("older", "newer");
    }

    @Test
    void listsOnlyRunningExecutionsForTheMonitor()
    {
        CherryPickExecution running = store.createOrGet(execution("running", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution paused = store.createOrGet(execution("paused", List.of(planned(1, Disposition.TO_CHERRY_PICK))));
        CherryPickExecution finished = store.createOrGet(execution("finished", List.of(planned(1, Disposition.USER_SKIPPED))));
        store.save(paused.pause(PauseReason.UNSAFE_GIT_STATE, "unexpected commit", NOW.plusSeconds(1)));
        store.save(finished.finish(FinishReason.COMPLETED, "nothing to do", NOW.plusSeconds(1)));

        assertThat(store.listRunning(10).stream().map(CherryPickExecution::id))
                .containsExactly(running.id());
        assertThat(store.find(paused.id()).orElseThrow().status()).isEqualTo(Status.PAUSED);
        assertThat(store.find(finished.id()).orElseThrow().status()).isEqualTo(Status.FINISHED);
    }

    @Test
    void rejectsListLimitsOutsideTheSupportedRange()
    {
        assertThatThrownBy(() -> store.listRecent(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.listRunning(201)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsAppliedTargetShasThroughAReload()
    {
        CherryPickExecution created = store.createOrGet(execution(
                "applied",
                List.of(planned(1, Disposition.TO_CHERRY_PICK), planned(2, Disposition.TO_CHERRY_PICK))));
        store.save(created.recordApplied(
                ImmutableMap.of(sha(1), created(1), sha(2), created(2)),
                created(2),
                NOW.plusSeconds(1)));

        CherryPickExecution loaded = store.find(created.id()).orElseThrow();
        assertThat(loaded.items()).allMatch(item -> item.result() == Result.APPLIED);
        assertThat(loaded.items().stream().map(item -> item.targetSha().orElseThrow()))
                .containsExactly(created(1), created(2));
        assertThat(loaded.currentTargetSha()).isEqualTo(created(2));
        assertThat(loaded.cursor()).isEmpty();
    }

    private static CherryPickExecution execution(String key, List<CherryPickItem> items)
    {
        return CherryPickExecution.create(
                CherryPickExecutionId.random(),
                key,
                new Workspace(
                        RepoRef.of("trinodb", "trino"),
                        "origin",
                        "fork",
                        "master",
                        "fork-master",
                        "bump-477-478-" + key,
                        Path.of("/tmp/bytequay-cherry-pick/" + key),
                        "claude"),
                new Range(BASE, TIP, TARGET),
                items,
                NOW);
    }

    private static Disposition disposition(int position)
    {
        if (position % 25 == 0) {
            return Disposition.ALREADY_ON_TARGET;
        }
        return position % 40 == 0 ? Disposition.USER_SKIPPED : Disposition.TO_CHERRY_PICK;
    }

    private static CherryPickItem planned(int position, Disposition disposition)
    {
        return CherryPickItem.planned(
                position,
                sha(position),
                parent(position),
                "Commit " + position,
                Optional.of(new PullRequestRefView(
                        1000 + position,
                        "Pull request " + position,
                        "https://github.com/trinodb/trino/pull/" + (1000 + position))),
                disposition);
    }

    private static String sha(int position)
    {
        return String.format("%040x", position);
    }

    private static String parent(int position)
    {
        return String.format("%040x", 1_000_000 + position);
    }

    private static String created(int position)
    {
        return String.format("%040x", 2_000_000 + position);
    }
}
