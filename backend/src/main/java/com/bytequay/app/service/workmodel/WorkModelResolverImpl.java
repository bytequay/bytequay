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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorkspaceStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Component
class WorkModelResolverImpl
        implements WorkModelResolver
{
    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final WorkspaceStore workspaceStore;
    private final StageStore stageStore;

    WorkModelResolverImpl(
            ThreadStore threadStore,
            TaskStore taskStore,
            WorkspaceStore workspaceStore,
            StageStore stageStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    @Override
    public Resolved resolveForThread(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Thread thread = threadStore.findThreadById(threadId)
                .orElseThrow(() -> notFound("thread", threadId));
        return resolveFromThread(thread);
    }

    @Override
    public Resolved resolveForTask(String threadId, String taskId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(taskId, "taskId is null");
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("task", taskId));
        if (!task.threadId().equals(threadId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                    "task " + taskId + " is not on thread " + threadId);
        }
        if (task.workModel() != null) {
            return new Resolved(task.workModel(),
                    new Provenance(Source.TASK, taskId, "task " + taskId));
        }
        Thread thread = threadStore.findThreadById(threadId)
                .orElseThrow(() -> notFound("thread", threadId));
        return resolveFromThread(thread);
    }

    @Override
    public Resolved resolveForStage(String threadId, String taskId, String stageId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(stageId, "stageId is null");
        StageInstance stage = stageStore.findStageById(UUID.fromString(stageId))
                .orElseThrow(() -> notFound("stage", stageId));
        if (!stage.taskId().equals(taskId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                    "stage " + stageId + " is not on task " + taskId);
        }
        if (stage.workModel() != null) {
            return new Resolved(stage.workModel(),
                    new Provenance(Source.STAGE, stageId, "stage " + stageId));
        }
        return resolveForTask(threadId, taskId);
    }

    private Resolved resolveFromThread(Thread thread)
    {
        if (thread.workModel() != null) {
            return new Resolved(thread.workModel(),
                    new Provenance(Source.THREAD, thread.id(), "thread " + thread.id()));
        }
        return resolveFromWorkspace(thread.workspaceId());
    }

    private Resolved resolveFromWorkspace(String workspaceId)
    {
        if (workspaceId != null && !workspaceId.isBlank()) {
            Optional<Workspace> ws = workspaceStore.findWorkspaceById(workspaceId);
            if (ws.isPresent() && ws.get().workModel() != null) {
                return new Resolved(ws.get().workModel(),
                        new Provenance(Source.WORKSPACE, workspaceId,
                                "workspace " + ws.get().name()));
            }
        }
        return globalDefault();
    }

    /**
     * The fallback when every override is empty: the first CLI agent
     * in the curated catalog with its catalog-default model. Chosen
     * because v1's expected install is Claude Code on the user's Mac;
     * keeping the catalog as the source of truth means a future
     * catalog reorder shifts the default without code churn here.
     */
    private static Resolved globalDefault()
    {
        WorkModelCatalog.CatalogAgent agent = WorkModelCatalog.CLI_AGENTS.get(0);
        WorkModel choice = new WorkModel(
                WorkModelKind.CLI,
                agent.id(),
                agent.defaultModel().id(),
                null);
        return new Resolved(choice,
                new Provenance(Source.GLOBAL_DEFAULT, null, "ByteQuay default"));
    }

    private static ResponseStatusException notFound(String label, String id)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(404),
                "no " + label + ": " + id);
    }
}
