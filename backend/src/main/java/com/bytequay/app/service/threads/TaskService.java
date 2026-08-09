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

import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Read compatibility plus the typed V2 Task control boundary. */
@Service
public class TaskService
{
    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final WatchedRepoStore watchedRepoStore;
    private V2DevelopmentFlowProjection v2Projection;
    private V2TaskControlService v2Controls;

    public TaskService(
            ThreadStore threadStore,
            TaskStore taskStore,
            WatchedRepoStore watchedRepoStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepoStore = requireNonNull(
                watchedRepoStore, "watchedRepoStore is null");
    }

    @Autowired
    void setV2Projection(V2DevelopmentFlowProjection v2Projection)
    {
        this.v2Projection = requireNonNull(v2Projection, "v2Projection is null");
    }

    @Autowired(required = false)
    void setV2Controls(V2TaskControlService v2Controls)
    {
        this.v2Controls = requireNonNull(v2Controls, "v2Controls is null");
    }

    public List<Task> listTasksForThread(String threadId)
    {
        requireThread(threadId);
        return taskStore.listTasksByThread(threadId).stream()
                .map(task -> v2Projection != null && v2Projection.isV2Task(task.id())
                        ? v2Projection.project(task)
                        : task)
                .toList();
    }

    public boolean isAutoApprove(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.isV2Task(taskId)
                ? requireV2Controls().isAutoApprove(taskId)
                : taskStore.isAutoApprove(taskId);
    }

    public int getMinApprovals(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.isV2Task(taskId)
                ? requireV2Controls().minApprovals(taskId)
                : taskStore.minApprovals(taskId);
    }

    public int setMinApprovals(String threadId, String taskId, int minApprovals)
    {
        requireV2Task(threadId, taskId);
        return requireV2Controls().setMinApprovals(
                taskId, Math.clamp(minApprovals, 0, 2));
    }

    public boolean setAutoApprove(String threadId, String taskId, boolean enabled)
    {
        requireV2Task(threadId, taskId);
        return requireV2Controls().setAutoApprove(taskId, enabled);
    }

    public boolean isAutoMerge(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.isV2Task(taskId)
                ? requireV2Controls().isAutoMerge(taskId)
                : taskStore.isAutoMerge(taskId);
    }

    public boolean setAutoMerge(String threadId, String taskId, boolean enabled)
    {
        requireV2Task(threadId, taskId);
        return requireV2Controls().setAutoMerge(taskId, enabled);
    }

    public Task requireTask(String threadId, String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("no task: " + taskId));
        if (!task.threadId().equals(threadId)) {
            throw notFound("task " + taskId + " is not on thread " + threadId);
        }
        return task;
    }

    public Task shipAndContinue(String threadId, String taskId, ShipRequest request)
    {
        return advance(threadId, taskId, request, false, false);
    }

    public Task shipApprovedParkedTask(
            String threadId, String taskId, ShipRequest request)
    {
        return advance(threadId, taskId, request, false, true);
    }

    @Transactional
    public Task setWorkModel(String threadId, String taskId, WorkModel workModel)
    {
        requireV2Task(threadId, taskId);
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409), "V2 Task engines are frozen at creation");
    }

    public boolean isWorkModelAgentLocked(String threadId, String taskId)
    {
        Task task = requireTask(threadId, taskId);
        return (task.agentSessionId() != null && !task.agentSessionId().isBlank())
                || !threadStore.listStageMessagesByTask(taskId).isEmpty();
    }

    @Transactional
    public Task renameTask(String threadId, String taskId, String newName)
    {
        Task current = requireV2Task(threadId, taskId);
        String trimmed = newName == null ? null : newName.trim();
        Task next = current.withName(
                trimmed == null || trimmed.isEmpty() ? null : trimmed);
        taskStore.saveTask(next);
        return next;
    }

    public Task parkAndStartNext(String threadId, String taskId, ShipRequest request)
    {
        return advance(threadId, taskId, request, true, false);
    }

    public Task startNextFromApprovedParkedTask(
            String threadId, String taskId, ShipRequest request)
    {
        return advance(threadId, taskId, request, true, true);
    }

    private Task advance(
            String threadId,
            String taskId,
            ShipRequest request,
            boolean next,
            boolean approvedParked)
    {
        requireNonNull(request, "request is null");
        Task current = requireV2Task(threadId, taskId);
        if (next && !approvedParked) {
            return v2Projection == null ? current : v2Projection.project(current);
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "V2 Task promotion is owned by Local Development");
    }

    public void completeTasksForMergedPr(String repoFullName, int prNumber)
    {
        rejectMatchingLegacyTask(repoFullName, prNumber);
    }

    public void closeTasksForRemotePr(String repoFullName, int prNumber)
    {
        rejectMatchingLegacyTask(repoFullName, prNumber);
    }

    public void authorizeMergeForPr(String repoFullName, int prNumber)
    {
        rejectMatchingLegacyTask(repoFullName, prNumber);
    }

    private void rejectMatchingLegacyTask(String repoFullName, int prNumber)
    {
        taskStore.findByLinkedPrNumber(prNumber).stream()
                .filter(task -> !taskStore.isV2Task(task.id()))
                .filter(task -> repoMatches(task, repoFullName))
                .findAny()
                .ifPresent(task -> { throw legacyMutationRetired(task.id()); });
    }

    public Task cancelTask(String threadId, String taskId)
    {
        requireV2Task(threadId, taskId);
        requireV2Controls().cancel(taskId);
        return projectV2Task(taskId);
    }

    public Task pauseTask(String threadId, String taskId)
    {
        requireV2Task(threadId, taskId);
        requireV2Controls().pause(taskId);
        return projectV2Task(taskId);
    }

    public Task resumeTask(String threadId, String taskId)
    {
        requireV2Task(threadId, taskId);
        requireV2Controls().resume(taskId);
        return projectV2Task(taskId);
    }

    public Task resumeTask(String taskId)
    {
        requireV2Task(taskId);
        requireV2Controls().resume(taskId);
        return projectV2Task(taskId);
    }

    public Task retryFailedCi(String threadId, String taskId)
    {
        requireV2Task(threadId, taskId);
        requireV2Controls().retryFailedCi(taskId);
        return projectV2Task(taskId);
    }

    private V2TaskControlService requireV2Controls()
    {
        if (v2Controls == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Task controls are not configured");
        }
        return v2Controls;
    }

    private Task requireV2Task(String threadId, String taskId)
    {
        Task task = requireTask(threadId, taskId);
        if (!taskStore.isV2Task(taskId)) {
            throw legacyMutationRetired(taskId);
        }
        return task;
    }

    private Task requireV2Task(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("no task: " + taskId));
        if (!taskStore.isV2Task(taskId)) {
            throw legacyMutationRetired(taskId);
        }
        return task;
    }

    private Task projectV2Task(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("no task: " + taskId));
        return v2Projection == null ? task : v2Projection.project(task);
    }

    private boolean repoMatches(Task task, String repoFullName)
    {
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            return false;
        }
        Path workingDir = Path.of(task.workingDir());
        return watchedRepoStore.findAll().stream()
                .filter(repo -> repo.localClonePath() != null
                        && !repo.localClonePath().isBlank())
                .filter(repo -> Path.of(repo.localClonePath()).equals(workingDir))
                .map(WatchedRepo::fullName)
                .anyMatch(repoFullName::equals);
    }

    private Thread requireThread(String threadId)
    {
        return threadStore.findThreadById(threadId)
                .orElseThrow(() -> notFound("no thread: " + threadId));
    }

    private static ResponseStatusException legacyMutationRetired(String taskId)
    {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "Historical LEGACY Task " + taskId
                        + " is read-only; use a typed V2 Task");
    }

    private static ResponseStatusException notFound(String reason)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(404), reason);
    }

    public enum BaseMode
    {
        MAIN,
        STACKED,
    }

    public record ShipRequest(
            String nextTitle,
            BaseMode baseMode,
            String prTitle,
            String prBody)
    {
        public ShipRequest
        {
            if (baseMode == null) {
                baseMode = BaseMode.MAIN;
            }
        }

        public ShipRequest(String nextTitle, BaseMode baseMode)
        {
            this(nextTitle, baseMode, null, null);
        }
    }
}
