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
package com.bytequay.app.web;

import com.bytequay.app.flow.timeline.PrTimelineProjection;
import com.bytequay.app.flow.timeline.PrTimelineProjection.OwnerType;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelineCursor;
import com.bytequay.app.flow.timeline.PrTimelineProjection.TimelinePage;
import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.timeline.TaskViews.EventDetail;
import com.bytequay.app.flow.timeline.TaskViews.RoundView;
import com.bytequay.app.flow.timeline.TaskViews.TaskSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Read side of the greenfield flow: everything the run view renders.
 *
 * <p>Separate from the task command controller on purpose — that one is an
 * idempotency-keyed write and this one has no side effects at all.
 */
@RestController
@RequestMapping("/api/new-flow")
public final class NewFlowTaskViewController
{
    private static final int DEFAULT_LIST_LIMIT = 25;
    private static final int DEFAULT_PAGE_LIMIT = 50;

    private final TaskViews views;

    public NewFlowTaskViewController(TaskViews views)
    {
        this.views = requireNonNull(views, "views is null");
    }

    @GetMapping("/repositories/{owner}/{repository}/tasks")
    public List<TaskSummary> list(
            @PathVariable String owner,
            @PathVariable String repository,
            @RequestParam(defaultValue = "" + DEFAULT_LIST_LIMIT) int limit)
    {
        requireRepositoryPart(owner, "owner");
        requireRepositoryPart(repository, "repository");
        return views.list(owner + "/" + repository, bounded(limit, TaskViews.MAX_LIST_SIZE));
    }

    @GetMapping("/tasks/{taskId}")
    public TaskSummary task(@PathVariable String taskId)
    {
        return views.summary(taskId).orElseThrow(NewFlowTaskViewController::unknownTask);
    }

    /** Oldest first; the view reads consecutive rows as "121 -> 27 failing". */
    @GetMapping("/tasks/{taskId}/rounds")
    public List<RoundView> rounds(@PathVariable String taskId)
    {
        return views.rounds(taskId);
    }

    /**
     * Empty until the Task materializes its one PR: before a clean committed
     * diff exists there is no PR to anchor a timeline to.
     */
    @GetMapping("/tasks/{taskId}/timeline")
    public TimelinePage timeline(
            @PathVariable String taskId,
            @RequestParam(required = false) Long afterRecordedAt,
            @RequestParam(required = false) Integer afterTypeRank,
            @RequestParam(required = false) String afterEventId,
            @RequestParam(required = false) Long afterEventCount,
            @RequestParam(required = false) Integer afterSchemaVersion,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_LIMIT) int limit)
    {
        TaskSummary task = views.summary(taskId)
                .orElseThrow(NewFlowTaskViewController::unknownTask);
        if (task.prId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "task has no pull request yet");
        }
        TimelineCursor after = cursor(
                task.prId(), afterRecordedAt, afterTypeRank, afterEventId,
                afterEventCount, afterSchemaVersion);
        return views.timeline(
                        taskId, after,
                        bounded(limit, PrTimelineProjection.MAX_PAGE_SIZE))
                .orElseThrow(NewFlowTaskViewController::unknownTask);
    }

    /** The body behind one timeline event; 404 when the owner has none. */
    @GetMapping("/events/{ownerType}/{ownerId}")
    public EventDetail detail(
            @PathVariable String ownerType,
            @PathVariable String ownerId)
    {
        OwnerType type;
        try {
            type = OwnerType.valueOf(ownerType.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown owner type");
        }
        return views.detail(type, ownerId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "no detail for this owner"));
    }

    /**
     * A partial cursor is a client bug, not a first page: silently dropping it
     * would restart the caller at the top and duplicate everything it has.
     */
    private static TimelineCursor cursor(
            String prId,
            Long recordedAt,
            Integer typeRank,
            String eventId,
            Long eventCount,
            Integer schemaVersion)
    {
        boolean none = recordedAt == null && typeRank == null && eventId == null
                && eventCount == null && schemaVersion == null;
        if (none) {
            return null;
        }
        if (recordedAt == null || typeRank == null || eventId == null
                || eventCount == null || schemaVersion == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "cursor is incomplete");
        }
        try {
            return new TimelineCursor(
                    prId, schemaVersion, eventCount,
                    Instant.ofEpochMilli(recordedAt), typeRank, eventId);
        }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "cursor is invalid");
        }
    }

    private static int bounded(int limit, int maximum)
    {
        if (limit < 1 || limit > maximum) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + maximum);
        }
        return limit;
    }

    private static ResponseStatusException unknownTask()
    {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown task");
    }

    private static void requireRepositoryPart(String value, String name)
    {
        if (value == null || value.isBlank() || value.length() > 100
                || !value.equals(value.strip())
                || value.indexOf('/') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, name + " is invalid");
        }
    }
}
