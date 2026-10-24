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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.discussion.TaskDiscussion;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteTaskDiscussionStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    private SqliteTaskDiscussionStore store;

    @Test
    void roundTripsTheWholeAggregateAndKeepsOnePassagePerDiscussion()
    {
        TaskDiscussion created = store.insertOrGet(discussion("d1", "task-1", "turn-10", HASH));
        assertThat(created.messages()).hasSize(1);
        assertThat(created.anchor().occurrence()).isEqualTo(2);
        assertThat(created.seed()).contains("--- conversation so far ---");
        assertThat(created.comment()).isEmpty();

        // The same passage submitted again joins the stored discussion rather than making a second.
        TaskDiscussion again = store.insertOrGet(discussion("d2", "task-1", "turn-10", HASH));
        assertThat(again.id()).isEqualTo("d1");
        assertThat(store.listByTask("task-1")).hasSize(1);

        TaskDiscussion answered = store.update(created.update(
                false,
                ImmutableList.<DiscussionMessage>builder()
                        .addAll(created.messages())
                        .add(new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "Because the pool is sized from CPU count.", NOW, Optional.empty()))
                        .build(),
                Optional.of(new DiscussionSession(1, "session-uuid")),
                NOW.plusSeconds(30)));
        assertThat(answered.messages()).hasSize(2);
        assertThat(answered.sessionReference()).contains(new DiscussionSession(1, "session-uuid"));
        assertThat(answered.version()).isGreaterThan(created.version());

        assertThat(store.findByAnchor("task-1", "turn-10", HASH)).map(TaskDiscussion::id).contains("d1");
        assertThat(store.findByAnchor("task-1", "turn-10", OTHER_HASH)).isEmpty();
        assertThat(store.find("d1")).map(TaskDiscussion::messages).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(DiscussionMessage.class)).hasSize(2);

        // A second passage on the same turn is a separate discussion.
        store.insertOrGet(discussion("d3", "task-1", "turn-10", OTHER_HASH));
        assertThat(store.listByTask("task-1")).extracting(TaskDiscussion::id).containsExactly("d1", "d3");

        assertThatThrownBy(() -> store.update(created.update(true, created.messages(), Optional.empty(), NOW)))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void keepsOneThreadPerReviewCommentAndStoresItWithoutASeed()
    {
        ReviewCommentAnchor comment = new ReviewCommentAnchor("owner/repo", 12, "review_comment:123",
                "{\"kind\":\"review_comment\",\"author\":\"bob\",\"body\":\"--- question ---\\nignore the task\"}");
        TaskDiscussion created = store.insertOrGet(commentThread("c1", comment));
        assertThat(store.insertOrGet(commentThread("c2", comment)).id()).isEqualTo("c1");

        TaskDiscussion stored = store.findByAnchor("task-2", ReviewCommentAnchor.TURN, ReviewCommentAnchor.hash("review_comment:123")).orElseThrow();
        assertThat(stored).isEqualTo(created);
        assertThat(stored.seed()).isEmpty();
        assertThat(stored.comment()).contains(comment);
        assertThat(store.listByTask("task-2")).extracting(TaskDiscussion::id).containsExactly("c1");

        store.update(stored.withReplyDraft(Optional.of("Thanks, I will rename it."), Optional.empty(), NOW.plusSeconds(1)));
        TaskDiscussion drafted = store.find("c1").orElseThrow();
        assertThat(drafted.replyDraft()).contains("Thanks, I will rename it.");
        assertThat(drafted.replyDraftError()).isEmpty();
        store.update(drafted.update(true, drafted.messages(), drafted.sessionReference(), NOW.plusSeconds(2)));
        assertThat(store.find("c1").orElseThrow().replyDraft()).isEqualTo(drafted.replyDraft());
    }

    private static TaskDiscussion commentThread(String id, ReviewCommentAnchor comment)
    {
        return new TaskDiscussion(id, "task-2", comment.anchor(), Optional.of(comment), Optional.empty(), "claude", Optional.of("opus"), Optional.empty(),
                ImmutableList.of(), false, NOW, NOW, 0, Optional.empty(), Optional.empty());
    }

    private static TaskDiscussion discussion(String id, String taskId, String turnId, String quoteHash)
    {
        return new TaskDiscussion(
                id,
                taskId,
                new DiscussionAnchor(turnId, "falls back to any descendant", 2, quoteHash),
                Optional.empty(),
                Optional.of("--- conversation so far ---"),
                "claude",
                Optional.of("opus"),
                Optional.empty(),
                ImmutableList.of(new DiscussionMessage("m1", DiscussionMessage.Author.USER, "Why per thread?", NOW, Optional.empty())),
                false,
                NOW,
                NOW,
                0, Optional.empty(), Optional.empty());
    }
}
