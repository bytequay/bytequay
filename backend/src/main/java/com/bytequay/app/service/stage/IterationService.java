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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.sqlite.IterationStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Read and summary-write access for historical monitor-stage iterations.
 */
@Component
public class IterationService
{
    /** Max summary length; longer text is truncated when stored. */
    public static final int SUMMARY_MAX_CHARS = 280;

    private final IterationStore iterationStore;
    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadTurnEventStore turnEventStore;

    public IterationService(
            IterationStore iterationStore,
            StageStore stageStore,
            TaskStore taskStore,
            ThreadTurnEventStore turnEventStore)
    {
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
    }

    /**
     * Record a summary for an iteration: dual-writes the iteration row's
     * {@code summary_text} and an {@code is_summary} thread-turn-event row
     * for the brain feed. Shared by the {@code record_iteration_summary}
     * tool. Text is truncated to {@link #SUMMARY_MAX_CHARS}.
     */
    @Transactional
    public TaskStageIteration recordSummary(UUID iterationId, String text)
    {
        TaskStageIteration iteration = iterationStore.findById(iterationId)
                .orElseThrow(() -> new IllegalArgumentException("no iteration: " + iterationId));
        String trimmed = truncate(text);
        Instant now = Instant.now();
        TaskStageIteration summarised = iteration.withSummary(trimmed, now);
        iterationStore.save(summarised);

        String threadId = taskStore.findTaskById(iteration.taskId())
                .map(Task::threadId)
                .orElse(null);
        if (threadId != null) {
            turnEventStore.appendEvent(ThreadTurnEvent.summary(
                    UUID.randomUUID().toString(),
                    iteration.turnId(),
                    threadId,
                    iteration.taskId(),
                    iteration.stageId().toString(),
                    now,
                    trimmed));
        }
        return summarised;
    }

    /**
     * The recorded iteration summaries of the task's most recent remote
     * monitor stage, oldest-first — context the comment-addressing prompt
     * can use to know what the preceding remote loop did. Empty when the
     * task never ran such a stage or it recorded no summaries.
     */
    public List<String> latestCiFixingSummaries(String taskId)
    {
        StageInstance latest = latestRemoteMonitorStage(taskId);
        if (latest == null) {
            return List.of();
        }
        List<String> summaries = new ArrayList<>();
        for (TaskStageIteration it : iterationStore.findByStage(latest.id())) {
            if (it.summaryText() != null && !it.summaryText().isBlank()) {
                summaries.add(it.summaryText().strip());
            }
        }
        return summaries;
    }

    /** A recorded CI-fixing iteration summary with the time it was
     *  recorded — drives the {@code get_new_updated_ci_fixing_log} tool's
     *  newer-than-marker filter. */
    public record CiFixingSummaryEntry(int iterationNumber, String text, Instant summarizedAt) {}

    /**
     * The recorded iteration summaries of the task's most recent remote
     * monitor stage, oldest-first, each with its recorded-at timestamp. The
     * {@code get_new_updated_ci_fixing_log} tool filters these against its
     * per-task last-query marker. Empty when no CI-fixing stage ran or it
     * recorded no summaries.
     */
    public List<CiFixingSummaryEntry> latestCiFixingSummaryEntries(String taskId)
    {
        StageInstance latest = latestRemoteMonitorStage(taskId);
        if (latest == null) {
            return List.of();
        }
        List<CiFixingSummaryEntry> out = new ArrayList<>();
        for (TaskStageIteration it : iterationStore.findByStage(latest.id())) {
            if (it.summaryText() != null && !it.summaryText().isBlank() && it.summarizedAt() != null) {
                out.add(new CiFixingSummaryEntry(
                        it.iterationNumber(), it.summaryText().strip(), it.summarizedAt()));
            }
        }
        return out;
    }

    private StageInstance latestRemoteMonitorStage(String taskId)
    {
        StageInstance latest = null;
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (!isMonitorStage(stage)) {
                continue;
            }
            if (latest == null || stage.openedAt().isAfter(latest.openedAt())) {
                latest = stage;
            }
        }
        return latest;
    }

    private static boolean isMonitorStage(StageInstance stage)
    {
        return stage.type() == StageType.CI_FIXING_STAGE
                || stage.type() == StageType.REMOTE_DEVELOPMENT_STAGE
                || stage.type() == StageType.REVIEW_MONITOR_STAGE;
    }

    private static String truncate(String text)
    {
        if (text == null) {
            return "";
        }
        return text.length() <= SUMMARY_MAX_CHARS ? text : text.substring(0, SUMMARY_MAX_CHARS);
    }
}
