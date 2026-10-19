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
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.bytequay.core.domain.AssistantReceipt;
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
        AssistantItem receipt = AssistantItem.receipt(DAY, AT, "Started “Expose snapshot id”", new AssistantReceipt(AssistantReceipt.Operation.START_TASK,
                AssistantReceipt.Outcome.DONE, Optional.of("trinodb/trino · Claude Opus 5.5"), Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t2")),
                Optional.empty(), Optional.of("turn-1"), Optional.empty(), Optional.of("start the snapshot id one")));
        AssistantItem opened = AssistantItem.receipt(DAY, AT, "Opened Settings · Models", new AssistantReceipt(AssistantReceipt.Operation.OPEN, AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.empty(), Optional.of("settings/models"), Optional.empty(), Optional.empty(), Optional.of("take me to model settings")));
        for (AssistantItem item : ImmutableList.of(message, failure, reply, receipt, opened)) {
            store.add(item);
        }
        store.add(AssistantItem.user(DAY.minusDays(1), AT, "yesterday", ImmutableList.of(), Optional.empty(), "key-0"));
        entities.flush();
        entities.clear();

        assertThat(store.items(DAY)).containsExactly(message, failure, reply, receipt, opened);
    }

    @Test
    void keepsAListsStyleTitleAndRefsButNoRowText()
    {
        AssistantItem list = AssistantItem.list(DAY, AT, "Waiting on you", new AssistantList(AssistantList.Style.TIMELINE,
                ImmutableList.of("pr:trinodb/trino#31402", "task:t1", "epic:LOCAL:w1")));
        store.add(AssistantItem.user(DAY, AT, "what is waiting on me?", ImmutableList.of(), Optional.empty(), "key-1"));
        store.add(list);
        entities.flush();
        entities.clear();

        assertThat(store.items(DAY).getLast()).isEqualTo(list);
        AssistantItem draft = AssistantItem.draft(DAY, AT, "Iceberg snapshot metadata", new AssistantDraft("ws1", "trinodb/trino", "Track the snapshot work.", "w9"));
        store.add(draft);
        entities.flush();
        entities.clear();
        assertThat(store.items(DAY).getLast()).isEqualTo(draft);
        Object stored = entities.createNativeQuery("select value from assistant_item where id = ?").setParameter(1, list.id()).getSingleResult();
        // The refs alone: each row's text is read from the stores when the day is drawn.
        assertThat(stored.toString()).contains("\"text\":\"Waiting on you\"")
                .contains("\"list\":{\"style\":\"TIMELINE\",\"refs\":[\"pr:trinodb/trino#31402\",\"task:t1\",\"epic:LOCAL:w1\"]}");
    }

    @Test
    void readsAnItemSavedBeforeReceiptsAsHavingNone()
    {
        entities.createNativeQuery("insert into assistant_item (id, day, ordinal, value) values ('old', '2026-10-17', 0, ?)")
                .setParameter(1, "{\"id\":\"old\",\"day\":\"2026-10-17\",\"at\":\"2026-10-17T01:02:00Z\",\"kind\":\"REPLY\",\"text\":\"Answer task:t1 first.\",\"visible\":[],"
                        + "\"onScreen\":null,\"key\":null,\"targets\":{},\"notReady\":false}")
                .executeUpdate();
        entities.clear();

        assertThat(store.items(DAY)).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(AssistantItem.Kind.REPLY);
            assertThat(item.receipt()).isEmpty();
            assertThat(item.list()).isEmpty();
            assertThat(item.draft()).isEmpty();
        });
    }

    @Test
    void keepsEachMessagesActionsInOrderAndDropsThemWithTheirDay()
    {
        AssistantItem message = AssistantItem.user(DAY, AT, "allow it, then start the snapshot one", ImmutableList.of("approval:a1"), Optional.empty(), "key-1");
        AssistantAction allowed = AssistantAction.of(message, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a1", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT.plusSeconds(2));
        AssistantAction refused = AssistantAction.of(message, AssistantReceipt.Operation.START_TASK, "work:w22", "start the snapshot one", AssistantReceipt.Outcome.REFUSED,
                Optional.of("Starting tasks is off"), Optional.empty(), AT.plusSeconds(1));
        AssistantItem yesterday = AssistantItem.user(DAY.minusDays(1), AT, "allow it", ImmutableList.of(), Optional.empty(), "key-0");
        store.addAction(allowed);
        store.addAction(refused);
        store.addAction(AssistantAction.of(yesterday, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a0", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT));
        entities.flush();
        entities.clear();

        assertThat(store.actions(message.id())).containsExactly(refused, allowed);
        store.deleteBefore(DAY);
        entities.flush();
        entities.clear();
        assertThat(store.actions(yesterday.id())).isEmpty();
        assertThat(store.actions(message.id())).hasSize(2);
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
