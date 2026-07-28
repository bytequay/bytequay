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
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Announces a task's completion on its thread trunk. When a task reaches
 * {@link TaskPhase#COMPLETED}, {@link BrainServiceImpl#onTaskCompleted}
 * enqueues a one-shot "summarize this task" brain turn; once that turn
 * finishes (see {@link TaskTurnFinishedEvent}), this class writes the
 * brain's answer as a single {@code task_summary} row on the trunk (a
 * {@code task_id IS NULL} row, so it renders in the trunk feed rather than
 * any task slice). The trunk UI groups everything up to this marker into a
 * foldable "Task N" block.
 *
 * <p>Nothing is written eagerly on completion (no placeholder) — a task's
 * trunk section stays unfolded until either the brain answers or {@link
 * #sweepStaleCompletions} gives up on it after a grace window and writes a
 * mechanical fallback instead, so a lost/failed brain turn can never leave
 * the trunk permanently missing a marker.
 */
@Component
public class TaskCompletionAnnouncer
{
    /** Message {@code type} for the trunk completion marker (also read by the
     *  trunk UI to delimit a task's foldable block). */
    public static final String TASK_SUMMARY_TYPE = "task_summary";

    /** How long a completed task waits for its brain summary before the
     *  sweep gives up and writes the mechanical fallback instead. */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(15);

    /** Cap on how many recently-completed tasks one sweep pass inspects —
     *  bounds the scan even if a backlog of stale completions builds up. */
    private static final int SWEEP_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(TaskCompletionAnnouncer.class);

    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ObjectMapper mapper;

    public TaskCompletionAnnouncer(
            TaskStore taskStore,
            ThreadStore threadStore,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Reacts to the brain's completion-summary turn finishing (or failing).
     * Ignores any other turn — a brain thread's ordinary user chat also
     * fires this same event type, so a pending-turn-id match is what tells
     * us this particular finish is the one we asked for.
     */
    @EventListener
    @Transactional
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        Task task = taskStore.findTaskByPendingCompletionSummaryTurnId(event.turnId()).orElse(null);
        if (task == null || taskStore.isV2Task(task.id())) {
            return;
        }
        try {
            taskStore.clearPendingCompletionSummaryTurnId(task.id());
            if (markerExists(task.threadId(), task.id())) {
                // A re-delivered finish event must not post a second marker.
                return;
            }
            String brainAnswer = event.failed() ? null : latestBrainAnswer(task.id());
            announce(task, brainAnswer != null && !brainAnswer.isBlank()
                    ? truncate(brainAnswer) : fallbackSummary(task));
        }
        catch (RuntimeException e) {
            // The marker is cosmetic — never fail the completion over it.
            log.warn("failed to write completion summary for task {}", task.id(), e);
        }
    }

    /**
     * Backstop for a completion whose brain turn never started (the enqueue
     * call itself threw) or never finished (hung/lost) — without this,
     * either failure mode would leave the trunk permanently missing a
     * marker for that task. Runs every 5 minutes; only acts on a completed
     * task past the grace period with no marker yet.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void sweepStaleCompletions()
    {
        for (Task task : taskStore.listByPhases(Set.of(TaskPhase.COMPLETED), SWEEP_LIMIT)) {
            if (taskStore.isV2Task(task.id())) {
                continue;
            }
            try {
                if (task.endedAt() != null
                        && Duration.between(task.endedAt(), Instant.now()).compareTo(GRACE_PERIOD) < 0) {
                    continue; // still inside the grace window — give the brain turn a chance
                }
                if (markerExists(task.threadId(), task.id())) {
                    continue;
                }
                taskStore.clearPendingCompletionSummaryTurnId(task.id());
                announce(task, fallbackSummary(task));
            }
            catch (RuntimeException e) {
                log.warn("stale-completion sweep failed for task {}", task.id(), e);
            }
        }
    }

    private void announce(Task task, String summary)
    {
        ObjectNode env = mapper.createObjectNode();
        env.put("text", summary);
        env.put("taskId", task.id());
        env.put("taskSeq", task.seq());
        long seq = threadStore.maxMessageSeq(task.threadId()).map(m -> m + 1).orElse(0L);
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), task.threadId(), /* taskId (trunk) */ null, seq,
                "assistant", TASK_SUMMARY_TYPE, env.toString(),
                null, null, null, null, Instant.now(), null, ThreadScope.TRUNK));
        log.debug("wrote completion summary marker for task {} on thread {}", task.id(), task.threadId());
    }

    /** The brain thread's latest assistant reply, or null when there's no
     *  brain thread / no assistant text on it yet. A brain thread only ever
     *  has one turn in flight at a time, so once its finish event has
     *  fired, the newest assistant {@code text} row is that turn's answer. */
    private String latestBrainAnswer(String taskId)
    {
        Thread brain = threadStore.findBrainThreadByTask(taskId).orElse(null);
        if (brain == null) {
            return null;
        }
        return threadStore.listMessages(brain.id()).stream()
                .filter(m -> "assistant".equals(m.role()) && "text".equals(m.type()))
                .max(Comparator.comparingLong(ThreadMessage::seq))
                .map(m -> {
                    try {
                        return mapper.readTree(m.contentJson()).path("text").asText(null);
                    }
                    catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    /** Longer than a trunk marker needs is still bounded — the brain is
     *  asked for 1-3 sentences, but nothing enforces that. */
    private static String truncate(String text)
    {
        int max = 500;
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** No brain answer to roll up (the turn failed, or never finished in
     *  time) — still needs a marker, or the trunk fold never closes over
     *  this task at all. */
    private static String fallbackSummary(Task task)
    {
        String title = task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        if (task.prNumber() == null) {
            return "Shipped " + title + " — completed with no pull request opened.";
        }
        return "closed".equals(task.prState())
                ? "Shipped " + title + " (PR #" + task.prNumber() + ") — closed without merging."
                : "Shipped " + title + " (PR #" + task.prNumber() + ") — merged.";
    }

    /** True once a {@code task_summary} marker for this task already sits on
     *  the trunk — the idempotency guard against a re-delivered finish event
     *  or a sweep pass racing an in-flight write. */
    private boolean markerExists(String threadId, String taskId)
    {
        for (ThreadMessage m : threadStore.listMessages(threadId)) {
            if (!TASK_SUMMARY_TYPE.equals(m.type())) {
                continue;
            }
            try {
                if (taskId.equals(mapper.readTree(m.contentJson()).path("taskId").asText(null))) {
                    return true;
                }
            }
            catch (JsonProcessingException ignored) {
                // A malformed marker envelope can't be ours — keep scanning.
            }
        }
        return false;
    }
}
