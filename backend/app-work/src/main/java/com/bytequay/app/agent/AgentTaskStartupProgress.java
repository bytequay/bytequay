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
package com.bytequay.app.agent;

import com.bytequay.agent.core.TaskId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Which creation step a task is in while its create-and-start request is in flight, so the
 * screen that sent the first message can name it. The task may not exist yet when the first
 * phases are recorded, so this is keyed by the client-generated id and lives only for that
 * request: nothing here is durable, replayed, or scoped to a workspace.
 */
@Component
public final class AgentTaskStartupProgress
{
    public enum Phase
    {
        /** Probing the provider CLI and its sign-in. */
        PROVIDER,
        /** Refreshing the workspace repository from GitHub. */
        REPOSITORY,
        /** Understanding the request to name the task and branch. */
        NAMING,
        /** Provisioning the task branch and worktree, then persisting the task. */
        WORKTREE,
        /** Storing the first message's images. */
        ATTACHMENTS,
        /** Opening the provider session and sending the first message. */
        SESSION
    }

    private final Map<TaskId, Phase> phases = new ConcurrentHashMap<>();

    public void record(TaskId taskId, Phase phase)
    {
        phases.put(requireNonNull(taskId, "taskId is null"), requireNonNull(phase, "phase is null"));
    }

    public Optional<Phase> current(TaskId taskId)
    {
        return Optional.ofNullable(phases.get(requireNonNull(taskId, "taskId is null")));
    }

    public void clear(TaskId taskId)
    {
        phases.remove(requireNonNull(taskId, "taskId is null"));
    }
}
