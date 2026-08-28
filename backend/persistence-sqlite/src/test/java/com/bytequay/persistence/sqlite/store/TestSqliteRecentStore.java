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

import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RecentTouch;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteRecentStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-recent-store-" + UUID.randomUUID() + ".db").toString();
    private static final LocalDate TODAY = LocalDate.parse("2026-08-28");
    private static final Instant MORNING = Instant.parse("2026-08-28T09:00:00Z");
    private static final Instant AFTERNOON = Instant.parse("2026-08-28T14:00:00Z");

    @Autowired
    private SqliteRecentStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void keepsOneRowPerItemPerDayCarryingTheStrongestReason()
    {
        store.recordTouch(touch("pr:trinodb/trino#1", TODAY, MORNING, RecentReason.READ, 12));
        store.recordTouch(touch("pr:trinodb/trino#1", TODAY, AFTERNOON, RecentReason.APPROVED, 0));
        store.recordTouch(touch("pr:trinodb/trino#1", TODAY, AFTERNOON.plusSeconds(60), RecentReason.READ, 3));

        List<RecentTouch> rows = store.findTouches(TODAY.minusDays(13), TODAY);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().reason()).isEqualTo(RecentReason.APPROVED);
        assertThat(rows.getFirst().touchedAt()).isEqualTo(AFTERNOON.plusSeconds(60));
    }

    @Test
    void separatesTheSameItemOnDifferentDays()
    {
        store.recordTouch(touch("pr:trinodb/trino#2", TODAY.minusDays(1), MORNING.minusSeconds(86_400), RecentReason.READ, 4));
        store.recordTouch(touch("pr:trinodb/trino#2", TODAY, MORNING, RecentReason.MERGED, 0));

        assertThat(store.findTouches(TODAY.minusDays(13), TODAY)).hasSize(2);
        assertThat(store.findTouches(TODAY, TODAY)).hasSize(1);
    }

    @Test
    void findsTheUsersOwnWordsCaseInsensitively()
    {
        store.saveText(text("pr:trinodb/trino#3:comment:1",
                "The metadata cache invalidation still Races with a concurrent snapshot commit."));
        store.saveText(text("pr:trinodb/trino#4:comment:2", "Looks good, shipping it."));

        assertThat(store.findTextContaining("race", 10)).extracting(RecentText::id)
                .containsExactly("pr:trinodb/trino#3:comment:1");
        assertThat(store.findTextContaining("shipping", 10)).extracting(RecentText::id)
                .containsExactly("pr:trinodb/trino#4:comment:2");
        assertThat(store.findTextContaining("nothingmatches", 10)).isEmpty();
    }

    @Test
    void replacesABodyRewrittenUnderTheSameIdRatherThanAccumulating()
    {
        store.saveText(text("pr:trinodb/trino#5:draft", "first attempt at the reply"));
        store.saveText(text("pr:trinodb/trino#5:draft", "second attempt at the rejoinder"));

        assertThat(store.findTextContaining("attempt", 10)).hasSize(1);
        assertThat(store.findTextContaining("rejoinder", 10)).hasSize(1);
        assertThat(store.findTextContaining("reply", 10)).isEmpty();
    }

    @Test
    void treatsPunctuationAndBlanksAsOrdinaryText()
    {
        store.saveText(text("pr:trinodb/trino#6:comment:9", "the NOT NULL constraint (again)"));

        assertThat(store.findTextContaining("not null", 10)).hasSize(1);
        assertThat(store.findTextContaining("(again)", 10)).hasSize(1);
        assertThat(store.findTextContaining("   ", 10)).isEmpty();
    }

    @Test
    void returnsTheNewestMatchesFirstAndHonoursTheLimit()
    {
        store.saveText(dated("one", "a note about the race", MORNING));
        store.saveText(dated("two", "another note about the race", AFTERNOON));

        assertThat(store.findTextContaining("race", 10)).extracting(RecentText::id).containsExactly("two", "one");
        assertThat(store.findTextContaining("race", 1)).extracting(RecentText::id).containsExactly("two");
        assertThat(store.findTextContaining("race", 0)).isEmpty();
    }

    @Test
    void sweepsRowsAndTheirWordsOutsideTheWindow()
    {
        store.recordTouch(touch("pr:trinodb/trino#7", TODAY.minusDays(20), MORNING.minusSeconds(20 * 86_400), RecentReason.READ, 2));
        store.recordTouch(touch("pr:trinodb/trino#8", TODAY, MORNING, RecentReason.READ, 2));
        store.saveText(new RecentText(
                "pr:trinodb/trino#7:comment:1",
                RecentItemType.PULL_REQUEST,
                "pr:trinodb/trino#7",
                RecentTextKind.COMMENT,
                "an ancient remark",
                MORNING.minusSeconds(30 * 86_400),
                7,
                "trinodb/trino",
                "Old",
                null));

        assertThat(store.deleteBefore(TODAY.minusDays(13))).isEqualTo(1);
        assertThat(store.findTouches(TODAY.minusDays(30), TODAY)).hasSize(1);
        assertThat(store.findTextContaining("ancient", 10)).isEmpty();
    }

    @Test
    void clearsTheStreamAndTheIndexTogether()
    {
        store.recordTouch(touch("pr:trinodb/trino#9", TODAY, MORNING, RecentReason.COMMENTED, 0));
        store.saveText(text("pr:trinodb/trino#9:comment:1", "a distinctive incantation"));

        store.deleteAll();

        assertThat(store.findTouches(TODAY.minusDays(13), TODAY)).isEmpty();
        assertThat(store.findTextContaining("incantation", 10)).isEmpty();
    }

    @Test
    void removesOneRowAndReportsWhetherItWasThere()
    {
        store.recordTouch(touch("pr:trinodb/trino#10", TODAY, MORNING, RecentReason.READ, 1));

        assertThat(store.deleteTouch("pr:trinodb/trino#10@" + TODAY)).isTrue();
        assertThat(store.deleteTouch("pr:trinodb/trino#10@" + TODAY)).isFalse();
    }

    private static RecentTouch touch(String itemKey, LocalDate day, Instant at, RecentReason reason, int count)
    {
        return new RecentTouch(
                itemKey + "@" + day,
                RecentItemType.PULL_REQUEST,
                itemKey,
                day,
                at,
                reason,
                count,
                1,
                "Support sorted by nested fields in Iceberg",
                "trinodb/trino",
                null,
                "https://github.com/trinodb/trino/pull/1",
                false,
                11L,
                "ezra-b");
    }

    private static RecentText dated(String id, String body, Instant writtenAt)
    {
        return new RecentText(
                id, RecentItemType.PULL_REQUEST, "pr:trinodb/trino#9", RecentTextKind.COMMENT, body,
                writtenAt, 9, "trinodb/trino", "Some pull request", null);
    }

    private static RecentText text(String id, String body)
    {
        return new RecentText(
                id,
                RecentItemType.PULL_REQUEST,
                id.substring(0, id.indexOf(':', 3)),
                RecentTextKind.COMMENT,
                body,
                MORNING,
                3,
                "trinodb/trino",
                "Some pull request",
                null);
    }
}
