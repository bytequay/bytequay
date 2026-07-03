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
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Announces a task's completion on its thread trunk. When a task reaches
 * {@link TaskPhase#COMPLETED} it writes a single {@code task_summary} row to
 * the trunk (a {@code task_id IS NULL} row, so it renders in the trunk feed
 * rather than any task slice), carrying the task's last stage summary. The
 * trunk UI groups everything up to this marker into a foldable "Task N" block.
 *
 * <p>The row is written on the phase-machine's {@link TaskPhaseTransitionedEvent}
 * (fired after the transition + audit row), so no completion path is
 * bypassed. It rides the transition's transaction but never throws — a
 * missing summary or a write hiccup must not roll back the completion; a
 * task that finished with no iteration summary simply gets no marker.
 */
@Component
public class TaskCompletionAnnouncer
{
    /** Message {@code type} for the trunk completion marker (also read by the
     *  trunk UI to delimit a task's foldable block). */
    public static final String TASK_SUMMARY_TYPE = "task_summary";

    private static final Logger log = LoggerFactory.getLogger(TaskCompletionAnnouncer.class);

    private final TaskStore taskStore;
    private final IterationStore iterationStore;
    private final ThreadStore threadStore;
    private final ObjectMapper mapper;

    public TaskCompletionAnnouncer(
            TaskStore taskStore,
            IterationStore iterationStore,
            ThreadStore threadStore,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @EventListener
    @Transactional
    public void onPhaseTransition(TaskPhaseTransitionedEvent event)
    {
        if (event.to() != TaskPhase.COMPLETED) {
            return;
        }
        try {
            announce(event.taskId());
        }
        catch (RuntimeException e) {
            // The marker is cosmetic — never fail the completion over it.
            log.warn("failed to write completion summary for task {}", event.taskId(), e);
        }
    }

    private void announce(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        String summary = iterationStore.findRecentSummaries(taskId, 1).stream()
                .findFirst()
                .map(TaskStageIteration::summaryText)
                .orElse(null);
        if (summary == null || summary.isBlank()) {
            // Nothing to roll up (e.g. a task that never ran an iteration).
            return;
        }
        if (markerExists(task.threadId(), taskId)) {
            // A re-delivered completion (e.g. a duplicate merge webhook) must
            // not post a second marker.
            return;
        }

        ObjectNode env = mapper.createObjectNode();
        env.put("text", summary);
        env.put("taskId", taskId);
        env.put("taskSeq", task.seq());
        long seq = threadStore.maxMessageSeq(task.threadId()).map(m -> m + 1).orElse(0L);
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), task.threadId(), /* taskId (trunk) */ null, seq,
                "assistant", TASK_SUMMARY_TYPE, env.toString(),
                null, null, null, null, Instant.now()));
        log.debug("wrote completion summary marker for task {} on thread {}", taskId, task.threadId());
    }

    /** True once a {@code task_summary} marker for this task already sits on
     *  the trunk — the idempotency guard against a re-fired completion. */
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
