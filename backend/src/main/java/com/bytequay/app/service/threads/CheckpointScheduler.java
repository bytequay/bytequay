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
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

import static com.bytequay.app.config.AsyncConfig.CHECKPOINT_EXECUTOR;
import static java.util.Objects.requireNonNull;

/**
 * Decides when a thread should grow a new per-segment checkpoint and
 * coordinates the Anthropic call + persistence on a background
 * executor. The agent session fires {@link #onTurnDone} after every
 * successful turn; the scheduler quickly returns and (when threshold
 * is crossed) submits the heavy work to its own pool so the session
 * thread never blocks on Anthropic.
 *
 * <p>Serialisation: each thread gets its own {@link ReentrantLock} so
 * two near-simultaneous {@code turn_done} events for the same thread
 * can't produce two overlapping segments. Different threads proceed
 * concurrently up to the executor's pool size.
 *
 * <p>Retries: a transient Anthropic failure leaves the checkpoint
 * row unwritten; the next {@code onTurnDone} will see the threshold
 * still exceeded and try again. We don't retry inside the lock — a
 * 5xx burst would just hold the lock open and pile up turn events.
 */
@Component
public class CheckpointScheduler
        implements CheckpointTrigger
{
    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduler.class);

    /** Per-thread token threshold above which a new segment is generated.
     *  25k matches the design doc default; a follow-up can promote
     *  this to a per-thread or per-user setting. */
    static final long DEFAULT_THRESHOLD_TOKENS = 25_000L;

    private final ThreadStore threads;
    private final TaskStore taskStore;
    private final ThreadCheckpointStore checkpoints;
    private final CheckpointSummariser summariser;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Last error message per thread — populated by the background
     *  catch block, cleared on the next successful generation or
     *  no-op pass. Used by the UI status endpoint so the rail can
     *  surface "summariser disabled" instead of an empty list. */
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();
    private final long thresholdTokens;

    @Autowired
    public CheckpointScheduler(
            ThreadStore threads,
            TaskStore taskStore,
            ThreadCheckpointStore checkpoints,
            CheckpointSummariser summariser,
            @Qualifier(CHECKPOINT_EXECUTOR) ExecutorService executor)
    {
        this(threads, taskStore, checkpoints, summariser, executor, DEFAULT_THRESHOLD_TOKENS);
    }

    CheckpointScheduler(
            ThreadStore threads,
            TaskStore taskStore,
            ThreadCheckpointStore checkpoints,
            CheckpointSummariser summariser,
            ExecutorService executor,
            long thresholdTokens)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.summariser = requireNonNull(summariser, "summariser is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.thresholdTokens = thresholdTokens;
    }

    @Override
    public void onTurnDone(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        // Schedule + return — never block the session's event thread on
        // Anthropic. The submitted thread does the per-thread locking.
        executor.execute(() -> tryGenerateThresholdSegment(threadId));
    }

    @Override
    public Optional<ThreadCheckpoint> manualGenerate(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        // Manual runs are user-initiated (the "+ save checkpoint"
        // button) so the caller wants the result synchronously to
        // show in the rail. Still take the per-thread lock so a
        // concurrent threshold-fired generation doesn't race.
        ReentrantLock lock = lockFor(threadId);
        lock.lock();
        try {
            Optional<ThreadCheckpoint> result = generateSegmentIfPending(threadId, /* force */ true);
            lastErrors.remove(threadId);
            return result;
        }
        catch (RuntimeException e) {
            // Stash the failure so a later status fetch can render it,
            // then rethrow — manual generate is synchronous and the
            // caller wants the error surfaced now (the UI shows it
            // inline near the button).
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrors.put(threadId, message);
            throw e;
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> lastErrorFor(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        return Optional.ofNullable(lastErrors.get(threadId));
    }

    private void tryGenerateThresholdSegment(String threadId)
    {
        ReentrantLock lock = lockFor(threadId);
        // Don't queue threshold-fired work — if the lock is held the
        // in-flight generation will cover this turn's range too. Skip
        // and let the next onTurnDone re-evaluate.
        if (!lock.tryLock()) {
            return;
        }
        try {
            generateSegmentIfPending(threadId, /* force */ false);
            // Clear any prior error — a successful (or no-op) pass
            // means the next UI status fetch shouldn't keep advertising
            // a stale failure.
            lastErrors.remove(threadId);
        }
        catch (RuntimeException e) {
            // Swallow so a background failure doesn't drag the executor
            // pool with it. The threshold remains crossed; the next
            // turn will re-attempt. Stash the message so the UI can
            // explain why the rail stays empty.
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrors.put(threadId, message);
            log.warn("CheckpointScheduler: segment generation failed for thread {}: {}",
                    threadId, message);
        }
        finally {
            lock.unlock();
        }
    }

    private Optional<ThreadCheckpoint> generateSegmentIfPending(String threadId, boolean force)
    {
        Optional<ThreadCheckpoint> lastSegment = checkpoints.findLastSegment(threadId);
        long firstSeq = lastSegment.map(c -> c.lastMsgSeq() + 1L).orElse(1L);
        Optional<Long> maxSeq = threads.maxMessageSeq(threadId);
        if (maxSeq.isEmpty() || maxSeq.get() < firstSeq) {
            return Optional.empty();
        }
        long lastSeq = maxSeq.get();
        long tokensCovered = threads.sumTokensBetween(threadId, firstSeq, lastSeq);
        if (!force && tokensCovered < thresholdTokens) {
            return Optional.empty();
        }
        ThreadCheckpoint segment = generateAndPersistSegment(threadId, firstSeq, lastSeq, tokensCovered);
        refreshOverall(threadId);
        return Optional.of(segment);
    }

    private ThreadCheckpoint generateAndPersistSegment(
            String threadId, long firstSeq, long lastSeq, long tokensCovered)
    {
        CheckpointSummaryResult summary = summariser.summariseSegment(threadId, firstSeq, lastSeq);
        long nextSeq = checkpoints.nextSegmentSeq(threadId);
        // Attribute the segment to the thread's active task when one
        // exists — this builds the Task tier of the memory hierarchy
        // (per-Task summaries compact upward into the Thread Overall,
        // which compacts into workspace memory). Threads in the
        // 0-Task brainstorm state leave taskId null, which routes the
        // row to the legacy thread-scope slice.
        String taskId = taskStore.findActiveTaskForThread(threadId)
                .map(Task::id)
                .orElse(null);
        ThreadCheckpoint cp = new ThreadCheckpoint(
                UUID.randomUUID().toString(),
                threadId,
                nextSeq,
                /* isOverall */ false,
                firstSeq,
                lastSeq,
                tokensCovered,
                summary.summaryMd(),
                summary.bulletTitles(),
                summary.modelUsed(),
                summary.promptTokens(),
                summary.completionTokens(),
                summary.costUsdMilli(),
                Instant.now(),
                /* supersededAt */ null,
                taskId);
        checkpoints.saveSegment(cp);
        return cp;
    }

    private void refreshOverall(String threadId)
    {
        List<ThreadCheckpoint> active = checkpoints.listActive(threadId);
        List<ThreadCheckpoint> segmentsAsc = new ArrayList<>();
        for (ThreadCheckpoint cp : active) {
            if (!cp.isOverall()) {
                segmentsAsc.add(cp);
            }
        }
        if (segmentsAsc.isEmpty()) {
            return;
        }
        // listActive returns segments newest-first; the Overall rollup
        // expects oldest-first so the model reads the thread in narrative
        // order.
        segmentsAsc.sort(Comparator.comparingLong(ThreadCheckpoint::seq));

        long firstMsgSeq = segmentsAsc.get(0).firstMsgSeq();
        long lastMsgSeq = segmentsAsc.get(segmentsAsc.size() - 1).lastMsgSeq();
        long tokensCovered = threads.sumTokensBetween(threadId, firstMsgSeq, lastMsgSeq);

        CheckpointSummaryResult summary = summariser.refreshOverall(threadId, segmentsAsc);
        ThreadCheckpoint overall = new ThreadCheckpoint(
                UUID.randomUUID().toString(),
                threadId,
                /* seq */ 0L,
                /* isOverall */ true,
                firstMsgSeq,
                lastMsgSeq,
                tokensCovered,
                summary.summaryMd(),
                summary.bulletTitles(),
                summary.modelUsed(),
                summary.promptTokens(),
                summary.completionTokens(),
                summary.costUsdMilli(),
                Instant.now(),
                /* supersededAt */ null,
                /* taskId — Overall always thread-scoped */ null);
        checkpoints.replaceOverall(threadId, overall);
    }

    private ReentrantLock lockFor(String threadId)
    {
        return locks.computeIfAbsent(threadId, id -> new ReentrantLock());
    }
}
