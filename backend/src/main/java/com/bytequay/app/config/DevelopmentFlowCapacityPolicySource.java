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
package com.bytequay.app.config;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Resolves persisted Workspace and Trunk settings captured by admission. */
final class DevelopmentFlowCapacityPolicySource
        implements CapacityManager.CapacityPolicySource
{
    private final ObjectMapper mapper;
    private final int defaultWorkspaceLimit;
    private final int defaultTrunkLimit;
    private final Map<CapacityManager.CapacityLane, Integer> laneLimits;

    DevelopmentFlowCapacityPolicySource(
            ObjectMapper mapper,
            int defaultWorkspaceLimit,
            int defaultTrunkLimit,
            Map<CapacityManager.CapacityLane, Integer> laneLimits)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.defaultWorkspaceLimit = positive(
                defaultWorkspaceLimit, "defaultWorkspaceLimit");
        this.defaultTrunkLimit = positive(defaultTrunkLimit, "defaultTrunkLimit");
        this.laneLimits = Map.copyOf(requireNonNull(laneLimits, "laneLimits is null"));
    }

    @Override
    public CapacityManager.CapacityPolicy current()
    {
        return CapacityManager.CapacityPolicy.initial(
                defaultWorkspaceLimit, defaultTrunkLimit, laneLimits);
    }

    @Override
    public CapacityManager.CapacityPolicy current(
            CapacityManager.CapacityRequest request,
            CapacityManager.CapacityPolicySnapshot snapshot)
    {
        requireNonNull(request, "request is null");
        requireNonNull(snapshot, "snapshot is null");
        String workspaceId = request.scope().workspaceId();
        String trunkId = request.scope().trunkId();
        if (workspaceId == null && trunkId == null) {
            return current();
        }
        int workspaceLimit = workspaceLimit(snapshot.workspaceSettingsJson());
        int trunkLimit = snapshot.trunkMaxRunningTasks() != null
                ? positive(snapshot.trunkMaxRunningTasks(), "Trunk maxRunningTasks")
                : defaultTrunkLimit;
        CapacityManager.CapacityPolicy base = current();
        return new CapacityManager.CapacityPolicy(
                base.laneLimits(),
                base.reservedTrunkControl(),
                defaultWorkspaceLimit,
                defaultTrunkLimit,
                workspaceId == null ? Map.of() : Map.of(workspaceId, workspaceLimit),
                trunkId == null ? Map.of() : Map.of(trunkId, trunkLimit));
    }

    private int workspaceLimit(String settingsJson)
    {
        if (settingsJson == null) {
            return defaultWorkspaceLimit;
        }
        try {
            Integer configured = mapper.readValue(
                    settingsJson, WorkspaceSettingsDto.class).maxRunningTasks();
            return configured == null
                    ? defaultWorkspaceLimit
                    : positive(configured, "Workspace maxRunningTasks");
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid Workspace settings", e);
        }
    }

    private static int positive(int value, String name)
    {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
