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
package com.bytequay.app.context;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One bounded latest-request binding in existing settings; no second task store or execution lifecycle. */
@Component
public class ContextBuildStore
{
    static final String PREFIX = "workspace.contextBuild.";
    private static final int MAX_STORED_BYTES = 1024 * 1024;

    private final AppSettingsStore settings;
    private final AgentTaskStore tasks;
    private final ObjectMapper mapper;

    public ContextBuildStore(AppSettingsStore settings, AgentTaskStore tasks, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public Optional<ContextBuild> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Saved context build exceeds 1 MiB");
                ContextBuild build = requireNonNull(mapper.readValue(json, ContextBuild.class), "Saved build is null");
                checkArgument(build.workspaceId().equals(workspaceId), "Saved build belongs to another workspace");
                return build;
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved context build cannot be read", failure);
            }
        });
    }

    public Optional<ContextBuild> findByTask(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId)).flatMap(task -> task.workspaceId().flatMap(this::find)).filter(build -> build.taskId().equals(taskId));
    }

    public ContextBuild save(Optional<ContextBuild> expected, ContextBuild replacement)
    {
        return WorkspaceOperationLocks.withLock(replacement.workspaceId(), () -> {
            checkArgument(find(replacement.workspaceId()).equals(expected), "Context build changed; inspect the latest request before retrying");
            try {
                String json = mapper.writeValueAsString(replacement);
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Context build exceeds 1 MiB");
                settings.put(PREFIX + replacement.workspaceId(), json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Context build cannot be saved", failure);
            }
        });
    }

    public void delete(String workspaceId)
    {
        settings.delete(PREFIX + workspaceId);
    }
}
