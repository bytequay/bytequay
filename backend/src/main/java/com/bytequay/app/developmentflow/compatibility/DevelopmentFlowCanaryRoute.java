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
package com.bytequay.app.developmentflow.compatibility;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable-at-creation canary route: one global switch plus a Workspace allow-list. */
@Component
public final class DevelopmentFlowCanaryRoute
{
    private final boolean v2CreationEnabled;
    private final boolean v2DispatchEnabled;
    private final Set<String> workspaceAllowList;

    public DevelopmentFlowCanaryRoute(
            @Value("${bytequay.development-flow.v2-task-creation-enabled:false}")
            boolean v2CreationEnabled,
            @Value("${bytequay.development-flow.v2-dispatch-enabled:false}")
            boolean v2DispatchEnabled,
            @Value("${bytequay.development-flow.v2-workspace-allow-list:}")
            String workspaceAllowList)
    {
        this.v2CreationEnabled = v2CreationEnabled;
        this.v2DispatchEnabled = v2DispatchEnabled;
        this.workspaceAllowList = Arrays.stream(workspaceAllowList.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean routesNewTaskToV2(String workspaceId)
    {
        return v2CreationEnabled
                && workspaceId != null
                && workspaceAllowList.contains(workspaceId);
    }

    public Snapshot snapshot()
    {
        return new Snapshot(
                v2CreationEnabled, v2DispatchEnabled, workspaceAllowList);
    }

    public record Snapshot(
            boolean v2TaskCreationEnabled,
            boolean v2DispatchEnabled,
            Set<String> workspaceAllowList) {}
}
