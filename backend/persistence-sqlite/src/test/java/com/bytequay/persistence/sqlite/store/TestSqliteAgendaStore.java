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

import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaRecap;
import com.bytequay.core.domain.AgendaRun;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.persistence.sqlite.repository.AgendaRunJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAgendaStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-agenda-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant MADE = Instant.parse("2026-10-16T01:02:00Z");

    @Autowired
    private SqliteAgendaStore store;

    @Autowired
    private AgendaRunJpaRepository runs;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void keepsADayWithItsItemsRecapAndMarks()
    {
        AgendaTarget pull = AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1"));
        AgendaItem item = new AgendaItem("pr:trinodb/trino#31402", AgendaItem.Action.REPLY, "Answer elric-t's review on #31402", "elric-t on #31402",
                "Changes requested yesterday.", Optional.of(MADE.minusSeconds(3600)), Optional.of("ws1"), pull, Optional.of("trinodb/trino #31402"));
        AgendaDay day = new AgendaDay(LocalDate.parse("2026-10-16"), AgendaDay.State.READY, MADE, MADE.minusSeconds(86400), "claude", "Sonnet", "Two people wait on you.",
                ImmutableList.of(item), Optional.of(new AgendaRecap("Merged 3 PRs.", ImmutableList.of(new AgendaRecap.Link("pr:trinodb/trino#31390",
                AgendaTarget.pull("trinodb/trino", 31390, Optional.empty()))), ImmutableList.of())), ImmutableMap.of(), Optional.empty());
        store.save(day);
        store.save(day.withMarks(ImmutableMap.of(item.ref(), AgendaDay.Mark.DONE)));
        entities.flush();
        entities.clear();

        assertThat(store.find(LocalDate.parse("2026-10-16"))).contains(day.withMarks(ImmutableMap.of(item.ref(), AgendaDay.Mark.DONE)));
        assertThat(store.find(LocalDate.parse("2026-10-15"))).isEmpty();
    }

    @Test
    void listsTheNewestDaysFirstAndDropsOldDaysWithTheirRuns()
    {
        for (String date : ImmutableList.of("2026-10-13", "2026-10-15", "2026-10-14")) {
            store.save(failed(LocalDate.parse(date)));
            store.record(new AgendaRun(UUID.randomUUID().toString(), LocalDate.parse(date), MADE, 1200, AgendaDay.State.FAILED, AgendaRun.Trigger.SCHEDULED,
                    "claude", "sonnet", ImmutableList.of(new AgendaRun.Call("overview", 0, 0, 180, 3, Optional.empty())), ImmutableList.of(),
                    ImmutableList.of(new AgendaRun.Drop("pr:x/y#1", "unknown ref")), Optional.of("Claude is signed out")));
        }
        entities.flush();
        entities.clear();

        assertThat(store.recent(2)).extracting(AgendaDay::day).containsExactly(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-14"));

        store.deleteBefore(LocalDate.parse("2026-10-15"));
        entities.flush();
        entities.clear();

        assertThat(store.recent(5)).extracting(AgendaDay::day).containsExactly(LocalDate.parse("2026-10-15"));
        assertThat(runs.count()).isEqualTo(1);
    }

    private static AgendaDay failed(LocalDate day)
    {
        return new AgendaDay(day, AgendaDay.State.FAILED, MADE, MADE, "claude", "Sonnet", "", ImmutableList.of(), Optional.empty(), ImmutableMap.of(),
                Optional.of("Claude is signed out"));
    }
}
