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

import com.bytequay.app.beans.stage.BrainFeedRow;
import com.bytequay.app.beans.stage.CommitDto;
import com.bytequay.app.beans.stage.ContextWindowDto;
import com.bytequay.app.beans.stage.LinkedPrDto;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.StageEventDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Service
public class StageServiceImpl
        implements StageService
{
    /** Placeholder context-window cap until the token accounting lands. */
    private static final int DEFAULT_CONTEXT_TOKEN_LIMIT = 200_000;

    /** Max events returned with a stage detail payload. */
    private static final int STAGE_DETAIL_EVENT_LIMIT = 50;

    private final TaskStore taskStore;
    private final StageStore stageStore;

    public StageServiceImpl(TaskStore taskStore, StageStore stageStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    @Override
    public TaskBrainViewData getBrain(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("no task: " + taskId));
        List<StageInstance> allStages = stageStore.findStagesByTask(taskId);
        List<StageEvent> allEvents = stageStore.findEventsByTask(taskId);

        List<StageDto> topLevel = allStages.stream()
                .filter(s -> s.callerStageId().isEmpty())
                .map(StageServiceImpl::toDto)
                .toList();
        List<StageDto> subStages = allStages.stream()
                .filter(s -> s.callerStageId().isPresent())
                .map(StageServiceImpl::toDto)
                .toList();

        Map<UUID, StageType> stageTypes = allStages.stream()
                .collect(Collectors.toMap(StageInstance::id, StageInstance::type));

        return new TaskBrainViewData(
                buildTask(task),
                buildAggregate(allStages),
                topLevel,
                subStages,
                buildBrainFeed(allEvents, stageTypes),
                buildRightRail(task),
                buildScrubbers(allEvents));
    }

    @Override
    public List<StageDto> getStages(String taskId)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.callerStageId().isEmpty())
                .map(StageServiceImpl::toDto)
                .toList();
    }

    @Override
    public List<StageDto> getActiveStages(String taskId)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.state() == StageState.OPEN || s.state() == StageState.ACTIVE)
                .map(StageServiceImpl::toDto)
                .toList();
    }

    @Override
    public StageDetailDto getStageDetail(UUID stageId)
    {
        StageInstance stage = stageStore.findStageById(stageId)
                .orElseThrow(() -> notFound("no stage: " + stageId));
        List<StageEventDto> events = stageStore
                .findRecentEventsByStage(stageId, STAGE_DETAIL_EVENT_LIMIT).stream()
                .map(StageServiceImpl::toEventDto)
                .toList();
        return new StageDetailDto(toDto(stage), events);
    }

    // ── brain-view builders ─────────────────────────────────────────────

    private static TaskBrainViewData.BrainTask buildTask(Task task)
    {
        return new TaskBrainViewData.BrainTask(
                task.id(),
                title(task),
                task.seq(),
                nullToEmpty(task.branchName()),
                repoFullName(task.linkedPrRef()),
                task.prNumber(),
                isDraft(task.prState()),
                task.phase().name(),
                statusLabel(task),
                "CLI",
                "");
    }

    private static TaskBrainViewData.Aggregate buildAggregate(List<StageInstance> stages)
    {
        long activeTimeSec = stages.stream()
                .filter(s -> s.closedAt().isPresent())
                .mapToLong(s -> Math.max(0,
                        (s.closedAt().get().toEpochMilli() - s.openedAt().toEpochMilli()) / 1000))
                .sum();
        // Everything except active time depends on machinery that lands
        // later (pushes, tool calls, turns, messages, panels, cost, budget).
        return new TaskBrainViewData.Aggregate(0, activeTimeSec, 0, 0, 0, 0, 0, 0, null);
    }

    private static List<BrainFeedRow> buildBrainFeed(
            List<StageEvent> events, Map<UUID, StageType> stageTypes)
    {
        return events.stream()
                .map(e -> brainFeedRow(e, stageTypes))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private static Optional<BrainFeedRow> brainFeedRow(StageEvent e, Map<UUID, StageType> stageTypes)
    {
        String type = switch (e.eventType()) {
            case OPENED -> "STAGE_OPENED";
            case CLOSED -> "STAGE_CLOSED";
            // The conversational / iteration / notify rows come from write
            // sites that don't exist yet; skip everything else for now.
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        StageType stageType = stageTypes.get(e.stageId());
        String stageTypeName = stageType == null ? null : stageType.name();
        String verb = e.eventType() == StageEventType.OPENED ? "opened" : "closed";
        String body = (stageType == null ? "Stage" : humanize(stageType)) + " " + verb;
        return Optional.of(new BrainFeedRow(
                e.id().toString(),
                type,
                e.stageId().toString(),
                stageTypeName,
                e.eventAt().toString(),
                body,
                null));
    }

    private static TaskBrainViewData.RightRail buildRightRail(Task task)
    {
        LinkedPrDto linkedPr = task.prNumber() == null ? null : buildLinkedPr(task);
        ContextWindowDto context = new ContextWindowDto(0, DEFAULT_CONTEXT_TOKEN_LIMIT, "safe");
        return new TaskBrainViewData.RightRail(null, linkedPr, context, List.<CommitDto>of());
    }

    private static LinkedPrDto buildLinkedPr(Task task)
    {
        return new LinkedPrDto(
                task.prNumber(),
                nullToEmpty(task.branchName()),
                prStatus(task.prState()),
                ciStatus(task.ciState()),
                "",
                0,
                0,
                "unknown",
                false);
    }

    private static TaskBrainViewData.Scrubbers buildScrubbers(List<StageEvent> events)
    {
        List<StageEvent> opens = events.stream()
                .filter(e -> e.eventType() == StageEventType.OPENED)
                .toList();
        int lastIdx = opens.size() - 1;
        List<ScrubberDash> stageEvents = new ArrayList<>(opens.size());
        for (int i = 0; i < opens.size(); i++) {
            StageEvent e = opens.get(i);
            stageEvents.add(new ScrubberDash(
                    e.id().toString(),
                    e.eventAt().toString(),
                    i == lastIdx));
        }
        return new TaskBrainViewData.Scrubbers(stageEvents, List.<ScrubberDash>of());
    }

    // ── mappers + placeholders ──────────────────────────────────────────

    private static StageDto toDto(StageInstance s)
    {
        return new StageDto(
                s.id().toString(),
                s.taskId(),
                s.type().name(),
                s.state().name(),
                s.openedAt().toString(),
                s.closedAt().map(Instant::toString).orElse(null),
                s.callerStageId().map(UUID::toString).orElse(null),
                humanize(s.type()),
                0);
    }

    private static StageEventDto toEventDto(StageEvent e)
    {
        return new StageEventDto(
                e.id().toString(),
                e.stageId().toString(),
                e.taskId(),
                e.eventType().name(),
                e.eventAt().toString(),
                e.payloadJson());
    }

    private static String title(Task task)
    {
        if (task.name() != null && !task.name().isBlank()) {
            return task.name();
        }
        return nullToEmpty(task.branchName());
    }

    private static String statusLabel(Task task)
    {
        // Server-computed label; a humanised phase name is the placeholder
        // until the richer "CI FIXING · iter #N" form lands.
        return task.phase().name().replace('_', ' ');
    }

    /** Parse {@code owner/repo} out of a {@code owner/repo#n} link ref. */
    private static String repoFullName(String linkedPrRef)
    {
        if (linkedPrRef == null || linkedPrRef.isBlank()) {
            return "";
        }
        int hash = linkedPrRef.indexOf('#');
        return hash < 0 ? linkedPrRef : linkedPrRef.substring(0, hash);
    }

    private static boolean isDraft(String prState)
    {
        return prState != null && prState.toLowerCase(Locale.ROOT).contains("draft");
    }

    private static String prStatus(String prState)
    {
        if (prState == null) {
            return "open";
        }
        String s = prState.toLowerCase(Locale.ROOT);
        if (s.contains("draft")) {
            return "draft";
        }
        if (s.contains("merg")) {
            return "merged";
        }
        if (s.contains("close")) {
            return "closed";
        }
        return "open";
    }

    private static String ciStatus(String ciState)
    {
        if (ciState == null || ciState.isBlank()) {
            return "unknown";
        }
        String s = ciState.toLowerCase(Locale.ROOT);
        if (s.contains("success") || s.contains("green") || s.contains("pass")) {
            return "green";
        }
        if (s.contains("fail") || s.contains("red") || s.contains("error")) {
            return "failing";
        }
        if (s.contains("pend") || s.contains("running") || s.contains("queue")) {
            return "pending";
        }
        return "unknown";
    }

    private static String humanize(StageType type)
    {
        return switch (type) {
            case DEVELOPMENT_STAGE -> "Development";
            case CI_FIXING_STAGE -> "CI fixing";
            case REVIEW_MONITOR_STAGE -> "Review monitor";
            case CLEANUP_STAGE -> "Cleanup";
            case REVIEW_STAGE -> "Review panel";
        };
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static ResponseStatusException notFound(String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(404), message);
    }
}
