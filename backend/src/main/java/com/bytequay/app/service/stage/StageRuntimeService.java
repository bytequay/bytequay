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
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Resolves a stage id to its owning Task agent. The stage chooses transcript
 *  scope; it never doubles as provider-session identity. */
@Service
public class StageRuntimeService
{
    private final StageStore stages;
    private final TaskStore tasks;
    private final ThreadStore threads;
    private final ThreadRegistry registry;

    public StageRuntimeService(
            StageStore stages, TaskStore tasks, ThreadStore threads, ThreadRegistry registry)
    {
        this.stages = requireNonNull(stages, "stages is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.registry = requireNonNull(registry, "registry is null");
    }

    public Runnable subscribe(UUID stageId, Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        ThreadAgent agent = resolve(stageId, true).orElseThrow();
        String expectedStageId = stageId.toString();
        return agent.subscribeToEvents(event -> {
            if (expectedStageId.equals(agent.activeStageId())) {
                listener.accept(event);
            }
        });
    }

    public void interrupt(UUID stageId)
    {
        String expectedStageId = requireNonNull(stageId, "stageId is null").toString();
        resolve(stageId, false)
                .filter(agent -> expectedStageId.equals(agent.activeStageId()))
                .ifPresent(ThreadAgent::interrupt);
    }

    private Optional<ThreadAgent> resolve(UUID stageId, boolean create)
    {
        StageInstance stage = stages.findStageById(requireNonNull(stageId, "stageId is null"))
                .orElseThrow(() -> new NoSuchElementException("no stage: " + stageId));
        Task task = tasks.findTaskById(stage.taskId())
                .orElseThrow(() -> new NoSuchElementException("no task: " + stage.taskId()));
        if (stage.type() == StageType.PLAN_STAGE) {
            Thread brain = threads.findBrainThreadByTask(task.id())
                    .orElseThrow(() -> new NoSuchElementException(
                            "no brain thread for task: " + task.id()));
            if (brain.kind() != ThreadKind.BRAIN_AGENT
                    || !task.id().equals(brain.parentTaskId())) {
                throw new IllegalStateException(
                        "thread " + brain.id() + " is not the brain for task " + task.id());
            }
            return create
                    ? Optional.of(registry.getOrCreateTaskBrainAgent(brain))
                    : registry.findTrunk(brain.id());
        }
        if (stage.type() == StageType.CLEANUP_STAGE) {
            throw new IllegalArgumentException("CleanupStage has no agent runtime");
        }
        Thread thread = threads.findThreadById(task.threadId())
                .orElseThrow(() -> new NoSuchElementException("no thread: " + task.threadId()));
        if (thread.kind() == ThreadKind.BRAIN_AGENT) {
            throw new IllegalStateException(
                    "task " + task.id() + " points to a brain thread as its dev runtime");
        }
        return create
                ? Optional.of(registry.getOrCreateTaskAgent(thread, task, stage.id().toString()))
                : registry.findTask(thread.id(), task.id());
    }
}
