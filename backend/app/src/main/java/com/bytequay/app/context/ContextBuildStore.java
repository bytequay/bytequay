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
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One bounded latest-request binding in existing settings, plus summaries of the runs it replaced;
 * no second task store or execution lifecycle.
 */
@Component
public class ContextBuildStore
{
    static final String PREFIX = "workspace.repositoryContextBuild.";
    static final String RUNS_PREFIX = "workspace.repositoryContextRuns.";
    static final int MAX_RUNS = 30;
    private static final int MAX_STORED_BYTES = 4 * 1024 * 1024;
    private final AppSettingsStore settings;
    private final AgentTaskStore tasks;
    private final ObjectMapper mapper;

    public ContextBuildStore(AppSettingsStore settings, AgentTaskStore tasks, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .addMixIn(ContextMatch.class, RepositoryContextService.MatchJson.class)
                .addMixIn(ContextReference.class, RepositoryContextService.ReferenceJson.class)
                .addMixIn(ContextSourceRead.class, RepositoryContextService.SourceReadJson.class)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public Optional<ContextBuild> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Saved context build exceeds 4 MiB");
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

    /** Earlier runs, oldest first. The current build's run is derived from its live task instead. */
    public List<ContextRun> runs(String workspaceId)
    {
        return settings.get(RUNS_PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Saved context runs exceed 4 MiB");
                List<ContextRun> runs = ImmutableList.copyOf(mapper.readValue(json, ContextRun[].class));
                checkArgument(runs.size() <= MAX_RUNS, "Too many saved context runs");
                return runs;
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved context runs cannot be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    /** Keeps the newest runs; each summary's activity is already bounded by its capture. */
    public void recordRun(String workspaceId, ContextRun run)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            List<ContextRun> current = runs(workspaceId);
            if (current.stream().anyMatch(value -> value.taskId().equals(run.taskId()))) {
                return null;
            }
            List<ContextRun> updated = ImmutableList.<ContextRun>builder().addAll(current).add(run).build();
            List<ContextRun> kept = updated.subList(Math.max(0, updated.size() - MAX_RUNS), updated.size());
            try {
                String json = mapper.writeValueAsString(kept);
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Context runs exceed 4 MiB");
                settings.put(RUNS_PREFIX + workspaceId, json);
                return null;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Context runs cannot be saved", failure);
            }
        });
    }

    public ContextBuild save(Optional<ContextBuild> expected, ContextBuild replacement)
    {
        return WorkspaceOperationLocks.withLock(replacement.workspaceId(), () -> {
            checkArgument(find(replacement.workspaceId()).equals(expected), "Context build changed; inspect the latest request before retrying");
            try {
                String json = mapper.writeValueAsString(replacement);
                checkArgument(ContextLimits.bytes(json) <= MAX_STORED_BYTES, "Context build exceeds 4 MiB");
                settings.put(PREFIX + replacement.workspaceId(), json);
                return replacement;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Context build cannot be saved", failure);
            }
        });
    }
}
