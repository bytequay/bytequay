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
import com.bytequay.app.domain.ThreadKind;
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
public class WorkModelResolver
{
    public record Resolved(WorkModel choice, Provenance provenance) {}

    public record Provenance(Source source, String scopeId, String scopeLabel) {}

    public enum Source
    {
        STAGE,
        TASK,
        THREAD,
        WORKSPACE,
        GLOBAL_DEFAULT,
    }

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final WorkspaceStore workspaceStore;
    private final StageStore stageStore;
    private final WorkspaceEngineSettings engineSettings;
    private final ThreadEngineOverrides threadEngines;

    WorkModelResolver(
            ThreadStore threadStore,
            TaskStore taskStore,
            WorkspaceStore workspaceStore,
            StageStore stageStore,
            WorkspaceEngineSettings engineSettings,
            ThreadEngineOverrides threadEngines)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.engineSettings = requireNonNull(engineSettings, "engineSettings is null");
        this.threadEngines = requireNonNull(threadEngines, "threadEngines is null");
    }

    public Resolved resolveForWorkspace(String workspaceId, String audience)
    {
        return workspaceEngine(workspaceId, audience);
    }

    public Resolved resolveForThread(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        Thread thread = thread(threadId);
        // A task brain is a child thread created after its parent trunk. Its
        // work_model stores the parent's already-frozen plan choice so it must
        // not fall back to whatever the workspace happens to say later.
        if (thread.kind() == ThreadKind.BRAIN_AGENT && hasEngine(thread.workModel())) {
            return new Resolved(thread.workModel(), new Provenance(
                    Source.THREAD, thread.id(), "parent trunk snapshot · plan"));
        }
        return compose(thread, SessionAudience.forThread(thread), effortOf(thread.workModel()));
    }

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
        Thread thread = thread(threadId);
        return compose(thread, SessionAudience.forTask(thread), firstEffort(
                effortOf(task.workModel()),
                effortOf(thread.workModel())));
    }

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
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("task", taskId));
        if (!task.threadId().equals(threadId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                    "task " + taskId + " is not on thread " + threadId);
        }
        Thread thread = thread(threadId);
        return compose(thread,
                SessionAudience.forStage(thread, stage.type()),
                firstEffort(
                        effortOf(stage.workModel()),
                        effortOf(task.workModel()),
                        effortOf(thread.workModel())));
    }

    /**
     * The engine for this audience — the trunk's creation-time snapshot for
     * new rows, or the workspace fallback for legacy sparse rows — wearing
     * the nearest scope's reasoning effort. Beyond that snapshot the scopes
     * contribute effort only: an engine stored on a thread / task / stage
     * {@code work_model} row is deliberately ignored so a task can't quietly
     * switch providers mid-flight.
     */
    private Resolved compose(Thread thread, String audience, String effort)
    {
        Resolved engine = threadEngines.forAudience(thread.id(), audience)
                .map(model -> new Resolved(model, new Provenance(
                        Source.THREAD, thread.id(), "this trunk · " + audience)))
                .orElseGet(() -> workspaceEngine(thread.workspaceId(), audience));
        if (effort == null || effort.equals(engine.choice().reasoningEffort())) {
            return engine;
        }
        WorkModel choice = engine.choice();
        return new Resolved(
                new WorkModel(choice.kind(), choice.agentOrProvider(),
                        choice.model(), choice.account(), effort),
                engine.provenance());
    }

    private Resolved workspaceEngine(String workspaceId, String audience)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return globalDefault();
        }
        Optional<Workspace> workspace = workspaceStore.findWorkspaceById(workspaceId);
        String name = workspace.map(Workspace::name).orElse(workspaceId);
        Optional<WorkspaceEngineSettings.Engine> configured =
                engineSettings.forAudience(workspaceId, audience);
        if (configured.isPresent()) {
            String label = "workspace " + name
                    + (configured.get().fromRole() ? " · " + audience : "");
            return new Resolved(configured.get().model(),
                    new Provenance(Source.WORKSPACE, workspaceId, label));
        }
        // The scope-override column predates the settings page's engine rows
        // and is still what the workspace REST endpoint writes.
        if (workspace.isPresent() && workspace.get().workModel() != null) {
            return new Resolved(workspace.get().workModel(),
                    new Provenance(Source.WORKSPACE, workspaceId, "workspace " + name));
        }
        return globalDefault();
    }

    private Thread thread(String threadId)
    {
        return threadStore.findThreadById(threadId)
                .orElseThrow(() -> notFound("thread", threadId));
    }

    private static String effortOf(WorkModel scoped)
    {
        if (scoped == null) {
            return null;
        }
        String effort = scoped.reasoningEffort();
        return effort == null || effort.isBlank() ? null : effort;
    }

    private static boolean hasEngine(WorkModel model)
    {
        return model != null
                && model.kind() != null
                && model.agentOrProvider() != null
                && !model.agentOrProvider().isBlank();
    }

    private static String firstEffort(String... candidates)
    {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The fallback when the workspace configured nothing: the first CLI
     * agent in the curated catalog with its catalog-default model. Chosen
     * because v1's expected install is Claude Code on the user's Mac;
     * keeping the catalog as the source of truth means a future catalog
     * reorder shifts the default without code churn here.
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
