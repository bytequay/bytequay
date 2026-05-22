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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCheckpointScheduler
{
    private static final long THRESHOLD = 25_000L;

    @Test
    void noSegmentWhenTokensBelowThreshold()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        threads.seed("t1",
                msg(1, 5_000),
                msg(2, 8_000));
        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        scheduler.onTurnDone("t1");

        assertThat(ckps.findLastSegment("t1")).isEmpty();
        assertThat(ckps.findActiveOverall("t1")).isEmpty();
        verify(summariser, never()).summariseSegment(anyString(), anyLong(), anyLong());
    }

    @Test
    void thresholdCrossingGeneratesFirstSegmentAndOverall()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        threads.seed("t1",
                msg(1, 15_000),
                msg(2, 12_000));
        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        when(summariser.summariseSegment(eq("t1"), eq(1L), eq(2L)))
                .thenReturn(result("- Did the thing\n\nDid the thing.", List.of("Did the thing")));
        when(summariser.refreshOverall(eq("t1"), any()))
                .thenReturn(result("- Overall rollup\n\nRollup.", List.of("Overall rollup")));
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        scheduler.onTurnDone("t1");

        Optional<ThreadCheckpoint> seg = ckps.findLastSegment("t1");
        assertThat(seg).isPresent();
        assertThat(seg.get().seq()).isEqualTo(1L);
        assertThat(seg.get().firstMsgSeq()).isEqualTo(1L);
        assertThat(seg.get().lastMsgSeq()).isEqualTo(2L);
        assertThat(seg.get().tokensCovered()).isEqualTo(27_000L);

        Optional<ThreadCheckpoint> overall = ckps.findActiveOverall("t1");
        assertThat(overall).isPresent();
        assertThat(overall.get().firstMsgSeq()).isEqualTo(1L);
        assertThat(overall.get().lastMsgSeq()).isEqualTo(2L);
    }

    @Test
    void secondSegmentRangeStartsAfterPriorSegment()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        // First "turn batch" — seqs 1-2 cross the 25k threshold.
        threads.seed("t1",
                msg(1, 13_000),
                msg(2, 14_000));
        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        when(summariser.summariseSegment(anyString(), anyLong(), anyLong()))
                .thenReturn(result("- bullet\n\nprose.", List.of("bullet")));
        when(summariser.refreshOverall(anyString(), any()))
                .thenReturn(result("- overall\n\nprose.", List.of("overall")));
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        // First trigger fires cp-1 over seqs 1-2 (27k tokens).
        scheduler.onTurnDone("t1");

        // Simulate another turn landing — append seqs 3-4 then re-fire.
        // The second segment must start at lastSegment.lastMsgSeq + 1
        // = 3, not at 1 again.
        threads.append("t1",
                msg(3, 12_000),
                msg(4, 14_000));
        scheduler.onTurnDone("t1");

        ArgumentCaptor<Long> firstSeq = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> lastSeq = ArgumentCaptor.forClass(Long.class);
        verify(summariser, times(2))
                .summariseSegment(eq("t1"), firstSeq.capture(), lastSeq.capture());
        assertThat(firstSeq.getAllValues()).containsExactly(1L, 3L);
        assertThat(lastSeq.getAllValues()).containsExactly(2L, 4L);

        List<ThreadCheckpoint> active = ckps.listActive("t1");
        // Overall + 2 segments = 3 active rows. Overall first.
        assertThat(active).hasSize(3);
        assertThat(active.get(0).isOverall()).isTrue();
        assertThat(active.get(1).seq()).isEqualTo(2L);
        assertThat(active.get(2).seq()).isEqualTo(1L);
    }

    @Test
    void manualGenerateSummarisesEvenBelowThreshold()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        threads.seed("t1",
                msg(1, 1_000),
                msg(2, 1_500));
        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        when(summariser.summariseSegment(eq("t1"), eq(1L), eq(2L)))
                .thenReturn(result("- Manual rollup\n\nprose.", List.of("Manual rollup")));
        when(summariser.refreshOverall(eq("t1"), any()))
                .thenReturn(result("- Overall\n\nprose.", List.of("Overall")));
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        Optional<ThreadCheckpoint> produced = scheduler.manualGenerate("t1");

        assertThat(produced).isPresent();
        assertThat(produced.get().seq()).isEqualTo(1L);
        assertThat(produced.get().bulletTitles()).containsExactly("Manual rollup");
        assertThat(ckps.findActiveOverall("t1")).isPresent();
    }

    @Test
    void manualGenerateReturnsEmptyWhenNothingNewSinceLastSegment()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        threads.seed("t1", msg(1, 5_000));
        // Pretend cp-1 already covers seq 1.
        ckps.saveSegment(new ThreadCheckpoint(
                UUID.randomUUID().toString(), "t1", 1L, false,
                1L, 1L, 5_000L, "covered", List.of(),
                "haiku", 0L, 0L, 0L,
                Instant.ofEpochMilli(1_000L), null,
                /* taskId */ null));
        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        Optional<ThreadCheckpoint> produced = scheduler.manualGenerate("t1");

        assertThat(produced).isEmpty();
        verify(summariser, never()).summariseSegment(anyString(), anyLong(), anyLong());
        verify(summariser, never()).refreshOverall(anyString(), any());
    }

    @Test
    void overallRollupReceivesSegmentsInAscendingSeqOrder()
    {
        InMemoryTaskStore threads = new InMemoryTaskStore();
        InMemoryCheckpointStore ckps = new InMemoryCheckpointStore();
        // Seed three segments already + new messages above threshold so
        // the next trigger writes cp-4 and the Overall sees cp-1..cp-4.
        Instant base = Instant.ofEpochMilli(1_000L);
        ckps.saveSegment(seg("t1", 1, 1, 4, 20_000L, base));
        ckps.saveSegment(seg("t1", 2, 5, 8, 20_000L, base.plusMillis(1)));
        ckps.saveSegment(seg("t1", 3, 9, 12, 20_000L, base.plusMillis(2)));
        threads.seed("t1",
                msg(13, 13_000),
                msg(14, 14_000));

        CheckpointSummariser summariser = mock(CheckpointSummariser.class);
        when(summariser.summariseSegment(eq("t1"), eq(13L), eq(14L)))
                .thenReturn(result("- new bullet\n\nprose.", List.of("new bullet")));
        when(summariser.refreshOverall(eq("t1"), any()))
                .thenReturn(result("- rollup\n\nprose.", List.of("rollup")));
        CheckpointScheduler scheduler = newScheduler(threads, ckps, summariser);

        scheduler.onTurnDone("t1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ThreadCheckpoint>> rolledUp = ArgumentCaptor.forClass(List.class);
        verify(summariser).refreshOverall(eq("t1"), rolledUp.capture());
        assertThat(rolledUp.getValue()).extracting("seq")
                .containsExactly(1L, 2L, 3L, 4L);
    }

    private static CheckpointScheduler newScheduler(
            InMemoryTaskStore threads, InMemoryCheckpointStore ckps, CheckpointSummariser summariser)
    {
        return new CheckpointScheduler(
                threads, new EmptyTaskStore(), ckps, summariser, sameThreadExecutor(), THRESHOLD);
    }

    /**
     * Runs submitted threads inline so onTurnDone's side effects are
     * visible by the time the call returns — no test-side waits or
     * latches needed. The Executor contract permits this; we only
     * implement the surface CheckpointScheduler actually uses.
     */
    private static ExecutorService sameThreadExecutor()
    {
        return new AbstractExecutorService()
        {
            private volatile boolean shutdown;

            @Override public void shutdown() { shutdown = true; }

            @Override
            public List<Runnable> shutdownNow()
            {
                shutdown = true;
                return List.of();
            }

            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private static ThreadMessage msg(long seq, long tokens)
    {
        return new ThreadMessage(
                "m-" + seq, "t1", seq,
                seq % 2 == 1 ? "user" : "assistant",
                "text",
                "{\"text\":\"msg " + seq + "\"}",
                null,
                /* tokensIn */ tokens / 2,
                /* tokensOut */ tokens - tokens / 2,
                /* costUsdMilli */ 1L,
                Instant.ofEpochMilli(seq * 1000L));
    }

    private static ThreadCheckpoint seg(
            String threadId, long seq, long firstMsg, long lastMsg, long tokens, Instant when)
    {
        return new ThreadCheckpoint(
                UUID.randomUUID().toString(), threadId, seq, /* isOverall */ false,
                firstMsg, lastMsg, tokens,
                "summary", List.of("b"),
                "haiku", 0L, 0L, 0L,
                when, null,
                /* taskId */ null);
    }

    private static CheckpointSummaryResult result(String summary, List<String> bullets)
    {
        return new CheckpointSummaryResult(summary, bullets, "haiku-test", 100L, 50L, 1L);
    }

    /** Returns empty for every Task query — the scheduler treats the
     *  result as "no active task" and falls back to thread-scope
     *  segment saves. */
    private static final class EmptyTaskStore
            implements TaskStore
    {
        @Override public void saveTask(Task task) {}
        @Override public Optional<Task> findTaskById(String id) { return Optional.empty(); }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) { return List.of(); }
        @Override public Optional<Task> findActiveTaskForThread(String threadId) { return Optional.empty(); }
        @Override public Optional<Long> maxSeqForThread(String threadId) { return Optional.empty(); }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** Minimal ThreadStore that only serves the message-side queries the
     *  scheduler hits. */
    private static final class InMemoryTaskStore
            implements ThreadStore
    {
        private final Map<String, List<ThreadMessage>> byTask = new HashMap<>();

        void seed(String threadId, ThreadMessage... msgs)
        {
            byTask.put(threadId, new ArrayList<>(List.of(msgs)));
        }

        void append(String threadId, ThreadMessage... msgs)
        {
            byTask.computeIfAbsent(threadId, k -> new ArrayList<>()).addAll(List.of(msgs));
        }

        @Override
        public List<ThreadMessage> listMessages(String threadId)
        {
            return List.copyOf(byTask.getOrDefault(threadId, List.of()));
        }

        // Default impls of maxMessageSeq / sumTokensBetween /
        // listMessagesBetween fall through to listMessages, which is
        // exactly what we want in-memory.

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

    /** In-memory checkpoint store with the same invariants the SQLite
     *  impl enforces: one active Overall per thread, monotonic seq for
     *  segments, listActive returns Overall first then segments
     *  newest-first. */
    private static final class InMemoryCheckpointStore
            implements ThreadCheckpointStore
    {
        private final Map<String, List<ThreadCheckpoint>> byTask = new HashMap<>();

        @Override
        public void saveSegment(ThreadCheckpoint segment)
        {
            if (segment.isOverall()) {
                throw new IllegalArgumentException("saveSegment refuses Overall rows");
            }
            byTask.computeIfAbsent(segment.threadId(), k -> new ArrayList<>()).add(segment);
        }

        @Override
        public List<ThreadCheckpoint> listActiveForTask(String taskId)
        {
            return List.of();
        }

        @Override
        public List<ThreadCheckpoint> listAllActiveOveralls(int limit)
        {
            return List.of();
        }

        @Override
        public void replaceOverall(String threadId, ThreadCheckpoint next)
        {
            if (!next.isOverall()) {
                throw new IllegalArgumentException("replaceOverall requires Overall");
            }
            List<ThreadCheckpoint> rows = byTask.computeIfAbsent(threadId, k -> new ArrayList<>());
            // Mark prior active Overall as superseded so listActive
            // filters it out, matching the SQLite store's behaviour.
            for (int i = 0; i < rows.size(); i++) {
                ThreadCheckpoint cp = rows.get(i);
                if (cp.isOverall() && cp.supersededAt() == null) {
                    rows.set(i, new ThreadCheckpoint(
                            cp.id(), cp.threadId(), cp.seq(), true,
                            cp.firstMsgSeq(), cp.lastMsgSeq(), cp.tokensCovered(),
                            cp.summaryMd(), cp.bulletTitles(),
                            cp.modelUsed(), cp.promptTokens(), cp.completionTokens(),
                            cp.costUsdMilli(), cp.generatedAt(), next.generatedAt(),
                            cp.taskId()));
                }
            }
            rows.add(next);
        }

        @Override
        public List<ThreadCheckpoint> listActive(String threadId)
        {
            List<ThreadCheckpoint> all = byTask.getOrDefault(threadId, List.of());
            List<ThreadCheckpoint> out = new ArrayList<>();
            for (ThreadCheckpoint cp : all) {
                if (cp.supersededAt() == null) {
                    out.add(cp);
                }
            }
            out.sort(Comparator
                    .<ThreadCheckpoint, Boolean>comparing(ThreadCheckpoint::isOverall).reversed()
                    .thenComparing(Comparator.comparingLong(ThreadCheckpoint::seq).reversed()));
            return List.copyOf(out);
        }

        @Override
        public Optional<ThreadCheckpoint> findById(String id)
        {
            for (List<ThreadCheckpoint> rows : byTask.values()) {
                for (ThreadCheckpoint cp : rows) {
                    if (cp.id().equals(id)) {
                        return Optional.of(cp);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<ThreadCheckpoint> findActiveOverall(String threadId)
        {
            for (ThreadCheckpoint cp : byTask.getOrDefault(threadId, List.of())) {
                if (cp.isOverall() && cp.supersededAt() == null) {
                    return Optional.of(cp);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<ThreadCheckpoint> findLastSegment(String threadId)
        {
            ThreadCheckpoint best = null;
            for (ThreadCheckpoint cp : byTask.getOrDefault(threadId, List.of())) {
                if (cp.isOverall()) {
                    continue;
                }
                if (best == null || cp.seq() > best.seq()) {
                    best = cp;
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        public long nextSegmentSeq(String threadId)
        {
            return findLastSegment(threadId).map(cp -> cp.seq() + 1L).orElse(1L);
        }

        @Override
        public void deleteSegment(String id) { throw new UnsupportedOperationException(); }
    }
}
