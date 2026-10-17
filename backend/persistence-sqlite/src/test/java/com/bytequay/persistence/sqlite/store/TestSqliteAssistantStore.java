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

import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.ServedRef;
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

class TestSqliteAssistantStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-assistant-store-" + UUID.randomUUID() + ".db").toString();
    private static final LocalDate DAY = LocalDate.parse("2026-10-17");
    private static final Instant AT = Instant.parse("2026-10-17T01:02:00Z");

    @Autowired
    private SqliteAssistantStore store;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE, "create-drop");
    }

    @Test
    void keepsADayWithItsSessionAndServedRefs()
    {
        AssistantDay day = new AssistantDay(DAY, "claude", "Sonnet", Optional.empty(), ImmutableMap.of(
                "pr:trinodb/trino#31402", new ServedRef("mine", Optional.of(AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1")))),
                "ws:ws1", new ServedRef("start_options", Optional.empty())), AT);
        store.save(day);
        AssistantDay resumable = new AssistantDay(DAY, "claude", "Sonnet", Optional.of(new DiscussionSession(1, "session-1")), day.served(), AT);
        store.save(resumable);
        entities.flush();
        entities.clear();

        assertThat(store.find(DAY)).contains(resumable);
        assertThat(store.find(DAY.minusDays(1))).isEmpty();
    }

    @Test
    void listsItemsInTheOrderTheyWereAddedEvenWithinOneMillisecond()
    {
        AssistantItem message = AssistantItem.user(DAY, AT, "what's waiting on me?", ImmutableList.of("approval:a1"), Optional.of("pr:trinodb/trino#31402"), "key-1");
        AssistantItem failure = AssistantItem.failure(DAY, AT, "Claude is signed out", true);
        AssistantItem reply = AssistantItem.reply(DAY, AT, "One card waits on task:t1.", ImmutableMap.of("task:t1", AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1")));
        for (AssistantItem item : ImmutableList.of(message, failure, reply)) {
            store.add(item);
        }
        store.add(AssistantItem.user(DAY.minusDays(1), AT, "yesterday", ImmutableList.of(), Optional.empty(), "key-0"));
        entities.flush();
        entities.clear();

        assertThat(store.items(DAY)).containsExactly(message, failure, reply);
    }

    @Test
    void dropsOldDaysWithTheirItems()
    {
        for (String date : ImmutableList.of("2026-10-15", "2026-10-16", "2026-10-17")) {
            LocalDate day = LocalDate.parse(date);
            store.save(new AssistantDay(day, "codex", "GPT", Optional.empty(), ImmutableMap.of(), AT));
            store.add(AssistantItem.user(day, AT, "hello", ImmutableList.of(), Optional.empty(), "key-" + date));
        }
        entities.flush();
        entities.clear();

        store.deleteBefore(LocalDate.parse("2026-10-16"));
        entities.flush();
        entities.clear();

        assertThat(store.find(LocalDate.parse("2026-10-15"))).isEmpty();
        assertThat(store.items(LocalDate.parse("2026-10-15"))).isEmpty();
        assertThat(store.find(LocalDate.parse("2026-10-16"))).isPresent();
        assertThat(store.items(LocalDate.parse("2026-10-17"))).hasSize(1);
    }
}
