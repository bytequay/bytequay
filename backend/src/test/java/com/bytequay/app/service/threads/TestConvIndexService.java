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

import com.bytequay.app.domain.ConvIndexPage;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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

        ConvIndexPage page = new ConvIndexService(store, new ObjectMapper()).initial("t1", 50);

        assertThat(page.threadId()).isEqualTo("t1");
        assertThat(page.totalUserMessages()).isEqualTo(3);
        assertThat(page.entries()).extracting("seq").containsExactly(1L, 3L, 5L);
        assertThat(page.entries()).extracting("preview")
                .containsExactly("First question", "Second question", "Third question");
        // messages now carries only the user prompts (the index window is
        // prompt-based), not the assistant / tool_result rows.
        assertThat(page.messages()).hasSize(3);
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
        ConvIndexPage page = new ConvIndexService(store, new ObjectMapper()).initial("busy", 3);

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

        ConvIndexPage page = new ConvIndexService(store, new ObjectMapper()).initial("t2", 2);

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

        ConvIndexPage page = new ConvIndexService(store, new ObjectMapper()).backfill("t3", 3L, 50);

        assertThat(page.entries()).extracting("seq").containsExactly(1L, 2L);
        assertThat(page.loadedFromSeq()).isEqualTo(1L);
        assertThat(page.nextCursor()).isNull();
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

        ConvIndexPage page = new ConvIndexService(store, new ObjectMapper()).initial("empty", 50);

        assertThat(page.totalUserMessages()).isZero();
        assertThat(page.entries()).isEmpty();
        assertThat(page.messages()).isEmpty();
        assertThat(page.loadedFromSeq()).isNull();
        assertThat(page.nextCursor()).isNull();
    }

    private static ThreadMessage userMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "user", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L));
    }

    private static ThreadMessage assistantMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "assistant", "text",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L));
    }

    /** The CLI emits tool results with role=user; the index must
     *  skip them so the panel only shows real human prompts. */
    private static ThreadMessage toolResultUserMsg(long seq, String text)
    {
        return new ThreadMessage(
                "m-" + seq, "thread", /* taskId */ null, seq, "user", "tool_result",
                "{\"text\":\"" + text + "\"}",
                null, null, null, null, Instant.ofEpochMilli(seq * 1000L));
    }

    /** Minimal in-memory ThreadStore for the conv-index tests. Only
     *  implements the message-read methods; the rest throw because
     *  ConvIndexService doesn't call them. */
    private static final class InMemoryStore
            implements ThreadStore
    {
        private final Map<String, List<ThreadMessage>> byTask = new HashMap<>();

        void seed(String threadId, ThreadMessage... msgs)
        {
            byTask.put(threadId, List.of(msgs));
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
