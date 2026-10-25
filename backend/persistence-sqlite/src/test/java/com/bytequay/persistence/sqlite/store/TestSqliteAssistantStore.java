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
import com.bytequay.core.domain.AssistantConversation;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAssistantStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final String CONVERSATION = "0c6a3f0e-5b1d-4c39-9d43-7f1f3a2b9e10";
    private static final String OTHER = "5f0c1d9a-2e7b-4a61-8c3f-9b4d2e6a1c07";
    private static final String THIRD = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d";
    private static final Instant AT = Instant.parse("2026-10-17T01:02:00Z");

    @Autowired
    private SqliteAssistantStore store;

    @Autowired
    private EntityManager entities;

    @Test
    void keepsAConversationWithItsSessionAndServedRefs()
    {
        store.save(AssistantConversation.start(CONVERSATION, "what's waiting on me?", AT));
        AssistantConversation replied = new AssistantConversation(CONVERSATION, "what's waiting on me?", Optional.of("claude"), Optional.of("Sonnet"),
                Optional.of(new DiscussionSession(1, "session-1")), ImmutableMap.of(
                        "pr:owner/repository#31402", new ServedRef("mine", Optional.of(AgendaTarget.pull("owner/repository", 31402, Optional.of("ws1")))),
                        "ws:ws1", new ServedRef("start_options", Optional.empty())), AT, AT.plusSeconds(20));
        store.save(replied);
        entities.flush();
        entities.clear();

        assertThat(store.find(CONVERSATION)).contains(replied);
        assertThat(store.find(OTHER)).isEmpty();
    }

    @Test
    void listsTheMostRecentlyUpdatedConversationsFirst()
    {
        store.save(AssistantConversation.start(CONVERSATION, "first", AT));
        store.save(AssistantConversation.start(OTHER, "second", AT.plusSeconds(60)));
        store.save(AssistantConversation.start(THIRD, "third", AT.plusSeconds(30)));
        entities.flush();
        entities.clear();

        assertThat(store.conversations(2)).extracting(AssistantConversation::title).containsExactly("second", "third");
    }

    @Test
    void listsItemsInTheOrderTheyWereAddedEvenWithinOneMillisecond()
    {
        AssistantItem message = AssistantItem.user(CONVERSATION, AT, "what's waiting on me?", ImmutableList.of("approval:a1"), Optional.of("pr:owner/repository#31402"), "key-1");
        AssistantItem failure = AssistantItem.failure(CONVERSATION, AT, "Claude is signed out", true);
        AssistantItem reply = AssistantItem.reply(CONVERSATION, AT, "One card waits on task:t1.", ImmutableMap.of("task:t1", AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1")));
        AssistantItem receipt = AssistantItem.receipt(CONVERSATION, AT, "Started “Expose snapshot id”", new AssistantReceipt(AssistantReceipt.Operation.START_TASK,
                AssistantReceipt.Outcome.DONE, Optional.of("owner/repository · Claude Opus 5.5"), Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t2")),
                Optional.empty(), Optional.of("turn-1"), Optional.empty(), Optional.of("start the snapshot id one")));
        AssistantItem opened = AssistantItem.receipt(CONVERSATION, AT, "Opened Settings · Models", new AssistantReceipt(AssistantReceipt.Operation.OPEN,
                AssistantReceipt.Outcome.DONE, Optional.empty(), Optional.empty(), Optional.of("settings/models"), Optional.empty(), Optional.empty(),
                Optional.of("take me to model settings")));
        for (AssistantItem item : ImmutableList.of(message, failure, reply, receipt, opened)) {
            store.add(item);
        }
        store.add(AssistantItem.user(OTHER, AT, "another topic", ImmutableList.of(), Optional.empty(), "key-0"));
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION)).containsExactly(message, failure, reply, receipt, opened);
    }

    @Test
    void keepsAListsStyleTitleAndRefsButNoRowText()
    {
        AssistantItem list = AssistantItem.list(CONVERSATION, AT, "Waiting on you", new AssistantList(AssistantList.Style.TIMELINE,
                ImmutableList.of("pr:owner/repository#31402", "task:t1", "epic:LOCAL:w1")));
        store.add(AssistantItem.user(CONVERSATION, AT, "what is waiting on me?", ImmutableList.of(), Optional.empty(), "key-1"));
        store.add(list);
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION).getLast()).isEqualTo(list);
        AssistantItem draft = AssistantItem.draft(CONVERSATION, AT, "Iceberg snapshot metadata", new AssistantDraft("ws1", "owner/repository", "Track the snapshot work.", "w9"));
        store.add(draft);
        entities.flush();
        entities.clear();
        assertThat(store.items(CONVERSATION).getLast()).isEqualTo(draft);
        Object stored = entities.createNativeQuery("select value from assistant_conversation_item where id = ?").setParameter(1, list.id()).getSingleResult();
        // The refs alone: each row's text is read from the stores when the conversation is drawn.
        assertThat(stored.toString()).contains("\"text\":\"Waiting on you\"")
                .contains("\"list\":{\"style\":\"TIMELINE\",\"refs\":[\"pr:owner/repository#31402\",\"task:t1\",\"epic:LOCAL:w1\"]}");
    }

    @Test
    void keepsEachMessagesActionsInOrderAndDropsThemWithTheirDay()
    {
        AssistantItem message = AssistantItem.user(CONVERSATION, AT, "allow it, then start the snapshot one", ImmutableList.of("approval:a1"), Optional.empty(), "key-1");
        AssistantAction allowed = AssistantAction.of(message, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a1", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT.plusSeconds(2));
        AssistantAction refused = AssistantAction.of(message, AssistantReceipt.Operation.START_TASK, "work:w22", "start the snapshot one", AssistantReceipt.Outcome.REFUSED,
                Optional.of("Starting tasks is off"), Optional.empty(), AT.plusSeconds(1));
        AssistantItem yesterday = AssistantItem.user(OTHER, AT.minus(Duration.ofDays(1)), "allow it", ImmutableList.of(), Optional.empty(), "key-0");
        store.addAction(allowed);
        store.addAction(refused);
        store.addAction(AssistantAction.of(yesterday, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a0", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT.minus(Duration.ofDays(1))));
        entities.flush();
        entities.clear();

        assertThat(store.actions(message.id())).containsExactly(refused, allowed);
        // An action's day is the user's wall-clock day.
        store.deleteBefore(AT.minus(Duration.ofDays(30)), LocalDate.ofInstant(AT, ZoneId.systemDefault()));
        entities.flush();
        entities.clear();
        assertThat(store.actions(yesterday.id())).isEmpty();
        assertThat(store.actions(message.id())).hasSize(2);
    }

    @Test
    void dropsConversationsLastUpdatedBeforeTheCutoffWithTheirItems()
    {
        ImmutableMap<String, Instant> updated = ImmutableMap.of(CONVERSATION, AT.minus(Duration.ofDays(2)), OTHER, AT.minus(Duration.ofDays(1)), THIRD, AT);
        updated.forEach((id, at) -> {
            store.save(AssistantConversation.start(id, "hello", at));
            store.add(AssistantItem.user(id, at, "hello", ImmutableList.of(), Optional.empty(), "key-" + id));
        });
        entities.flush();
        entities.clear();

        store.deleteBefore(AT.minus(Duration.ofHours(36)), LocalDate.parse("2026-01-01"));
        entities.flush();
        entities.clear();

        assertThat(store.find(CONVERSATION)).isEmpty();
        assertThat(store.items(CONVERSATION)).isEmpty();
        assertThat(store.find(OTHER)).isPresent();
        assertThat(store.items(THIRD)).hasSize(1);
    }
}
