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
package com.bytequay.app.service.threads;

import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.domain.ConvIndexPage;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestConvIndexService
{
    @Test
    void initialReturnsTailWindowAndDerivedUserPromptEntries()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("t1",
                userMsg(1, "First question"),
                assistantMsg(2, "First answer"),
                userMsg(3, "Second question"),
                toolResultUserMsg(4, "this is a tool_result, not a prompt"),
                userMsg(5, "Third question"));

        ConvIndexPage page = service(store).initial("t1", 50);

        assertThat(page.threadId()).isEqualTo("t1");
        assertThat(page.totalUserMessages()).isEqualTo(3);
        assertThat(page.entries()).extracting("seq").containsExactly(1L, 3L, 5L);
        assertThat(page.entries()).extracting("preview")
                .containsExactly("First question", "Second question", "Third question");
        // entries are prompt-windowed, but messages carries the FULL
        // transcript across that window — every assistant answer and
        // tool_result row from the earliest prompt through the tail.
        // Dropping the non-prompt rows here is what made trunk answers
        // vanish: the terminal would render the questions and nothing else.
        assertThat(page.messages()).extracting("seq").containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(page.messages()).filteredOn(m -> "assistant".equals(m.role()))
                .extracting("contentJson").containsExactly("{\"text\":\"First answer\"}");
        assertThat(page.loadedFromSeq()).isEqualTo(1L);
        // Window covers the whole thread, so there's nothing older to fetch.
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void busyTurnDoesNotBuryEarlierPromptsBehindToolChatter()
    {
        // Regression: a single turn emits many tool rows, so a window of
        // the last N *messages* would surface only the most recent
        // prompt. The prompt-based window must still return all prompts.
        InMemoryStore store = new InMemoryStore();
        store.seed("busy",
                userMsg(1, "First prompt"),
                assistantMsg(2, "thinking"),
                toolResultUserMsg(3, "tool out"),
                toolResultUserMsg(4, "tool out"),
                toolResultUserMsg(5, "tool out"),
                toolResultUserMsg(6, "tool out"),
                assistantMsg(7, "answer"),
                userMsg(8, "Second prompt"));

        // limit 3 messages would have caught only seq 8; prompt-based
        // limit 3 catches both real prompts.
        ConvIndexPage page = service(store).initial("busy", 3);

        assertThat(page.totalUserMessages()).isEqualTo(2);
        assertThat(page.entries()).extracting("seq").containsExactly(1L, 8L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void initialPagesBeyondLimitAndExposesBackfillCursor()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("t2",
                userMsg(1, "Q1"),
                userMsg(2, "Q2"),
                userMsg(3, "Q3"),
                userMsg(4, "Q4"));

        ConvIndexPage page = service(store).initial("t2", 2);

        assertThat(page.totalUserMessages()).isEqualTo(4);
        // Tail window of size 2 → seqs 3 and 4, oldest-first.
        assertThat(page.entries()).extracting("seq").containsExactly(3L, 4L);
        assertThat(page.loadedFromSeq()).isEqualTo(3L);
        assertThat(page.nextCursor()).isEqualTo(3L);
    }

    @Test
    void backfillReturnsOlderEntriesStrictlyBelowCursor()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("t3",
                userMsg(1, "Q1"), userMsg(2, "Q2"),
                userMsg(3, "Q3"), userMsg(4, "Q4"));

        ConvIndexPage page = service(store).backfill("t3", 3L, 50);

        assertThat(page.entries()).extracting("seq").containsExactly(1L, 2L);
        assertThat(page.loadedFromSeq()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void backfillTranscriptCarriesAnswersAndStopsBelowCursor()
    {
        // Two turns. Paging back from the second prompt (seq 4) must
        // return the first turn's transcript — prompt AND answer — and
        // nothing at or beyond seq 4.
        InMemoryStore store = new InMemoryStore();
        store.seed("t4",
                userMsg(1, "Q1"),
                assistantMsg(2, "A1"),
                userMsg(4, "Q2"),
                assistantMsg(5, "A2"));

        ConvIndexPage page = service(store).backfill("t4", 4L, 50);

        assertThat(page.entries()).extracting("seq").containsExactly(1L);
        assertThat(page.messages()).extracting("seq").containsExactly(1L, 2L);
        assertThat(page.loadedFromSeq()).isEqualTo(1L);
    }

    @Test
    void previewCollapsesMultilineAndTruncatesAt80Chars()
    {
        String longInput = "A".repeat(120);
        assertThat(ConvIndexService.summarise(longInput)).hasSize(80).endsWith("…");
        assertThat(ConvIndexService.summarise("   leading whitespace"))
                .isEqualTo("leading whitespace");
        assertThat(ConvIndexService.summarise("line one\nline two"))
                .isEqualTo("line one");
        assertThat(ConvIndexService.summarise("tab\tseparated\twords"))
                .isEqualTo("tab separated words");
        assertThat(ConvIndexService.summarise("")).isEmpty();
        assertThat(ConvIndexService.summarise("   \n   \n")).isEmpty();
    }

    @Test
    void emptyTaskReturnsEmptyPageWithNoCursor()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("empty");

        ConvIndexPage page = service(store).initial("empty", 50);

        assertThat(page.totalUserMessages()).isZero();
        assertThat(page.entries()).isEmpty();
        assertThat(page.messages()).isEmpty();
        assertThat(page.loadedFromSeq()).isNull();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void promotedTrunkIndexKeepsLegacyThenTypedHistoryInDisjointSeqSpaces()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("promoted",
                userMsg(1, "legacy prompt"),
                assistantMsg(2, "legacy answer"));
        store.markV2("promoted");
        ThreadTurnProjection projection = mock(ThreadTurnProjection.class);
        when(projection.history("promoted")).thenReturn(List.of(
                userMsg(-3, "typed prompt"),
                errorMsg(-4, "provider exploded")));

        ConvIndexPage page = service(store, projection).initial("promoted", 50);

        assertThat(page.totalUserMessages()).isEqualTo(2);
        assertThat(page.entries()).extracting("seq").containsExactly(1L, -3L);
        assertThat(page.messages()).extracting("seq")
                .containsExactly(1L, 2L, -3L, -4L);
        assertThat(page.messages().getLast().type()).isEqualTo("error");
        assertThat(page.messages().getLast().contentJson())
                .isEqualTo("{\"text\":\"provider exploded\"}");
        assertThat(page.loadedFromSeq()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void promotedTrunkBackfillCrossesTheLegacyTypedBoundaryByExactCursor()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("promoted",
                userMsg(1, "legacy one"),
                userMsg(2, "legacy two"),
                userMsg(3, "legacy three"));
        store.markV2("promoted");
        ThreadTurnProjection projection = mock(ThreadTurnProjection.class);
        when(projection.history("promoted")).thenReturn(List.of(
                userMsg(-5, "typed one"),
                userMsg(-7, "typed two")));
        ConvIndexService service = service(store, projection);

        ConvIndexPage tail = service.initial("promoted", 2);
        assertThat(tail.entries()).extracting("seq").containsExactly(-5L, -7L);
        assertThat(tail.loadedFromSeq()).isEqualTo(-5L);
        assertThat(tail.nextCursor()).isEqualTo(-5L);

        ConvIndexPage seam = service.backfill("promoted", -5L, 2);
        assertThat(seam.entries()).extracting("seq").containsExactly(2L, 3L);
        assertThat(seam.messages()).extracting("seq").containsExactly(2L, 3L);
        assertThat(seam.loadedFromSeq()).isEqualTo(2L);
        assertThat(seam.nextCursor()).isEqualTo(2L);

        ConvIndexPage first = service.backfill("promoted", 2L, 2);
        assertThat(first.entries()).extracting("seq").containsExactly(1L);
        assertThat(first.nextCursor()).isNull();
    }

    @Test
    void promotedTrunkIndexExcludesDrainingLegacySiblingMessages()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("promoted",
                userMsg(1, "trunk prompt"),
                taskMsg(2, "task prompt"),
                stageMsg(3, "stage prompt"));
        store.markV2("promoted");
        ThreadTurnProjection projection = mock(ThreadTurnProjection.class);
        when(projection.history("promoted")).thenReturn(List.of(
                userMsg(-4, "typed trunk prompt")));

        ConvIndexPage page = service(store, projection).initial("promoted", 50);

        assertThat(page.totalUserMessages()).isEqualTo(2);
        assertThat(page.entries()).extracting("seq").containsExactly(1L, -4L);
        assertThat(page.messages()).extracting("seq").containsExactly(1L, -4L);
    }

    @Test
    void promotedTrunkLateLegacyPrefixRemainsReachableFromTailCursor()
    {
        InMemoryStore store = new InMemoryStore();
        store.seed("promoted", userMsg(1, "legacy first"));
        store.markV2("promoted");
        ThreadTurnProjection projection = mock(ThreadTurnProjection.class);
        when(projection.history("promoted")).thenReturn(List.of(
                userMsg(-3, "typed tail")));
        ConvIndexService service = service(store, projection);

        assertThat(service.backfill("promoted", -3L, 50).entries())
                .extracting("seq").containsExactly(1L);

        store.seed("promoted",
                userMsg(1, "legacy first"),
                userMsg(101, "legacy late"));
        ConvIndexPage refreshedTail = service.initial("promoted", 1);
        assertThat(refreshedTail.entries()).extracting("seq")
                .containsExactly(-3L);
        assertThat(refreshedTail.totalUserMessages()).isEqualTo(3);
        assertThat(refreshedTail.nextCursor()).isEqualTo(-3L);

        ConvIndexPage late = service.backfill("promoted", -3L, 1);
        assertThat(late.entries()).extracting("seq").containsExactly(101L);
        assertThat(late.nextCursor()).isEqualTo(101L);
        assertThat(service.backfill("promoted", 101L, 1).entries())
                .extracting("seq").containsExactly(1L);
    }

    private static ConvIndexService service(InMemoryStore store)
    {
        return service(store, mock(ThreadTurnProjection.class));
    }

    private static ConvIndexService service(
            InMemoryStore store, ThreadTurnProjection projection)
    {
        return new ConvIndexService(store, new ObjectMapper(), projection);
    }

    private static ThreadMessage userMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "user", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L),
                null, ThreadScope.TRUNK);
    }

    private static ThreadMessage assistantMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "assistant", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L),
                null, ThreadScope.TRUNK);
    }

    private static ThreadMessage errorMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq,
                "assistant", "error", "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * -1000L),
                null, ThreadScope.TRUNK);
    }

    private static ThreadMessage taskMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", "task-1", seq, "user", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L),
                null, ThreadScope.TASK);
    }

    private static ThreadMessage stageMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", "task-1", seq, "user", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L),
                "stage-1", ThreadScope.STAGE);
    }

    /** The CLI emits tool results with role=user; the index must
     *  skip them so the panel only shows real human prompts. */
    private static ThreadMessage toolResultUserMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "user", "tool_result",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L),
                null, ThreadScope.TRUNK);
    }

    /** Minimal in-memory ThreadStore for the conv-index tests. Only
     *  implements the message-read methods; the rest throw because
     *  ConvIndexService doesn't call them. */
    private static final class InMemoryStore
            implements ThreadStore
    {
        private final Map<String, List<ThreadMessage>> byTask = new HashMap<>();
        private final Set<String> v2 = new HashSet<>();

        void seed(String threadId, ThreadMessage... msgs)
        {
            byTask.put(threadId, List.of(msgs));
        }

        void markV2(String threadId)
        {
            v2.add(threadId);
        }

        @Override
        public Optional<String> findTurnVersion(String threadId)
        {
            return v2.contains(threadId) ? Optional.of("V2") : Optional.empty();
        }

        @Override
        public List<ThreadMessage> listMessages(String threadId)
        {
            return byTask.getOrDefault(threadId, List.of());
        }

        // listRecentMessages / listMessagesBefore / countUserMessages
        // are picked up from the ThreadStore default implementations,
        // which read from listMessages(...) — that's exactly what we
        // want for an in-memory test.

        // ── unused for the conv-index tests ──────────────────────────────
        @Override public void saveThread(Thread thread) { throw new UnsupportedOperationException(); }
        @Override public Optional<Thread> findThreadById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Thread> listTasksByStatus(ThreadStatus status, int limit) { throw new UnsupportedOperationException(); }
        @Override public List<Thread> listTasksByIds(Collection<String> ids) { throw new UnsupportedOperationException(); }
        @Override public List<Thread> listThreadsUpdatedSince(Instant since) { throw new UnsupportedOperationException(); }
        @Override public void deleteThread(String threadId) { throw new UnsupportedOperationException(); }
        @Override public void appendMessage(ThreadMessage message) { throw new UnsupportedOperationException(); }
        @Override public void recordFile(ThreadFile file) { throw new UnsupportedOperationException(); }
        @Override public List<ThreadFile> listFiles(String threadId) { throw new UnsupportedOperationException(); }
    }
}
