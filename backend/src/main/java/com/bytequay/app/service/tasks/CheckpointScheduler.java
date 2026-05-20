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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.TaskCheckpoint;
import com.bytequay.app.repository.TaskCheckpointStore;
import com.bytequay.app.repository.TaskStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/**
 * Decides when a task should grow a new per-segment checkpoint and
 * coordinates the Anthropic call + persistence on a background
 * executor. The agent session fires {@link #onTurnDone} after every
 * successful turn; the scheduler quickly returns and (when threshold
 * is crossed) submits the heavy work to its own pool so the session
 * thread never blocks on Anthropic.
 *
 * <p>Serialisation: each task gets its own {@link ReentrantLock} so
 * two near-simultaneous {@code turn_done} events for the same task
 * can't produce two overlapping segments. Different tasks proceed
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

    /** Per-task token threshold above which a new segment is generated.
     *  25k matches the design doc default; a follow-up can promote
     *  this to a per-task or per-user setting. */
    static final long DEFAULT_THRESHOLD_TOKENS = 25_000L;

    private final TaskStore tasks;
    private final TaskCheckpointStore checkpoints;
    private final CheckpointSummariser summariser;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Last error message per task — populated by the background
     *  catch block, cleared on the next successful generation or
     *  no-op pass. Used by the UI status endpoint so the rail can
     *  surface "summariser disabled" instead of an empty list. */
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();
    private final long thresholdTokens;

    @Autowired
    public CheckpointScheduler(
            TaskStore tasks,
            TaskCheckpointStore checkpoints,
            CheckpointSummariser summariser)
    {
        this(tasks, checkpoints, summariser, defaultExecutor(), DEFAULT_THRESHOLD_TOKENS);
    }

    CheckpointScheduler(
            TaskStore tasks,
            TaskCheckpointStore checkpoints,
            CheckpointSummariser summariser,
            ExecutorService executor,
            long thresholdTokens)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.summariser = requireNonNull(summariser, "summariser is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.thresholdTokens = thresholdTokens;
    }

    @Override
    public void onTurnDone(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        // Schedule + return — never block the session's event thread on
        // Anthropic. The submitted task does the per-task locking.
        executor.execute(() -> tryGenerateThresholdSegment(taskId));
    }

    @Override
    public Optional<TaskCheckpoint> manualGenerate(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        // Manual runs are user-initiated (the "+ save checkpoint"
        // button) so the caller wants the result synchronously to
        // show in the rail. Still take the per-task lock so a
        // concurrent threshold-fired generation doesn't race.
        ReentrantLock lock = lockFor(taskId);
        lock.lock();
        try {
            Optional<TaskCheckpoint> result = generateSegmentIfPending(taskId, /* force */ true);
            lastErrors.remove(taskId);
            return result;
        }
        catch (RuntimeException e) {
            // Stash the failure so a later status fetch can render it,
            // then rethrow — manual generate is synchronous and the
            // caller wants the error surfaced now (the UI shows it
            // inline near the button).
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrors.put(taskId, message);
            throw e;
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> lastErrorFor(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return Optional.ofNullable(lastErrors.get(taskId));
    }

    private void tryGenerateThresholdSegment(String taskId)
    {
        ReentrantLock lock = lockFor(taskId);
        // Don't queue threshold-fired work — if the lock is held the
        // in-flight generation will cover this turn's range too. Skip
        // and let the next onTurnDone re-evaluate.
        if (!lock.tryLock()) {
            return;
        }
        try {
            generateSegmentIfPending(taskId, /* force */ false);
            // Clear any prior error — a successful (or no-op) pass
            // means the next UI status fetch shouldn't keep advertising
            // a stale failure.
            lastErrors.remove(taskId);
        }
        catch (RuntimeException e) {
            // Swallow so a background failure doesn't drag the executor
            // pool with it. The threshold remains crossed; the next
            // turn will re-attempt. Stash the message so the UI can
            // explain why the rail stays empty.
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrors.put(taskId, message);
            log.warn("CheckpointScheduler: segment generation failed for task {}: {}",
                    taskId, message);
        }
        finally {
            lock.unlock();
        }
    }

    private Optional<TaskCheckpoint> generateSegmentIfPending(String taskId, boolean force)
    {
        Optional<TaskCheckpoint> lastSegment = checkpoints.findLastSegment(taskId);
        long firstSeq = lastSegment.map(c -> c.lastMsgSeq() + 1L).orElse(1L);
        Optional<Long> maxSeq = tasks.maxMessageSeq(taskId);
        if (maxSeq.isEmpty() || maxSeq.get() < firstSeq) {
            return Optional.empty();
        }
        long lastSeq = maxSeq.get();
        long tokensCovered = tasks.sumTokensBetween(taskId, firstSeq, lastSeq);
        if (!force && tokensCovered < thresholdTokens) {
            return Optional.empty();
        }
        TaskCheckpoint segment = generateAndPersistSegment(taskId, firstSeq, lastSeq, tokensCovered);
        refreshOverall(taskId);
        return Optional.of(segment);
    }

    private TaskCheckpoint generateAndPersistSegment(
            String taskId, long firstSeq, long lastSeq, long tokensCovered)
    {
        CheckpointSummaryResult summary = summariser.summariseSegment(taskId, firstSeq, lastSeq);
        long nextSeq = checkpoints.nextSegmentSeq(taskId);
        TaskCheckpoint cp = new TaskCheckpoint(
                UUID.randomUUID().toString(),
                taskId,
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
                /* supersededAt */ null);
        checkpoints.saveSegment(cp);
        return cp;
    }

    private void refreshOverall(String taskId)
    {
        List<TaskCheckpoint> active = checkpoints.listActive(taskId);
        List<TaskCheckpoint> segmentsAsc = new ArrayList<>();
        for (TaskCheckpoint cp : active) {
            if (!cp.isOverall()) {
                segmentsAsc.add(cp);
            }
        }
        if (segmentsAsc.isEmpty()) {
            return;
        }
        // listActive returns segments newest-first; the Overall rollup
        // expects oldest-first so the model reads the task in narrative
        // order.
        segmentsAsc.sort(Comparator.comparingLong(TaskCheckpoint::seq));

        long firstMsgSeq = segmentsAsc.get(0).firstMsgSeq();
        long lastMsgSeq = segmentsAsc.get(segmentsAsc.size() - 1).lastMsgSeq();
        long tokensCovered = tasks.sumTokensBetween(taskId, firstMsgSeq, lastMsgSeq);

        CheckpointSummaryResult summary = summariser.refreshOverall(taskId, segmentsAsc);
        TaskCheckpoint overall = new TaskCheckpoint(
                UUID.randomUUID().toString(),
                taskId,
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
                /* supersededAt */ null);
        checkpoints.replaceOverall(taskId, overall);
    }

    private ReentrantLock lockFor(String taskId)
    {
        return locks.computeIfAbsent(taskId, id -> new ReentrantLock());
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdownNow();
    }

    private static ExecutorService defaultExecutor()
    {
        // A small pool — checkpoint generation is I/O-bound on
        // Anthropic latency, not CPU-bound, and per-task serialisation
        // means we only ever want a handful in flight across tasks.
        ThreadFactory factory = new ThreadFactory()
        {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "checkpoint-scheduler-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(2, factory);
    }
}
