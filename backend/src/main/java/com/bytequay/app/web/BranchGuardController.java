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

import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.service.review.BranchGuardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

@RestController
public class BranchGuardController
{
    private final BranchGuardService guards;
    private V2ControlRouteStore v2Routes;
    private V2BranchSyncPolicyManager v2Policies;
    private V2BranchGuardProjection v2Projection;

    public BranchGuardController(BranchGuardService guards)
    {
        this.guards = requireNonNull(guards, "guards is null");
    }

    @Autowired
    void setV2Controls(
            V2ControlRouteStore v2Routes,
            V2BranchSyncPolicyManager v2Policies,
            V2BranchGuardProjection v2Projection)
    {
        this.v2Routes = requireNonNull(v2Routes, "v2Routes is null");
        this.v2Policies = requireNonNull(v2Policies, "v2Policies is null");
        this.v2Projection = requireNonNull(v2Projection, "v2Projection is null");
    }

    @GetMapping("/api/tasks/{taskId}/guard")
    public BranchGuard guard(@PathVariable String taskId)
    {
        if (isV2Task(taskId)) {
            return requireV2Projection().project(taskId);
        }
        return guards.get(taskId);
    }

    public record GuardPatch(Boolean enabled, String schedule) {}

    @PatchMapping("/api/tasks/{taskId}/guard")
    public BranchGuard updateGuard(@PathVariable String taskId, @RequestBody GuardPatch patch)
    {
        if (isV2Task(taskId)) {
            requireV2Policies().update(
                    taskId, patch == null ? null : patch.enabled(),
                    patch == null ? null : patch.schedule());
            return requireV2Projection().project(taskId);
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "Historical LEGACY Task " + taskId
                        + " is read-only; use the typed V2 branch policy owner");
    }

    private boolean isV2Task(String taskId)
    {
        return v2Routes != null && v2Routes.isV2Task(taskId);
    }

    private V2BranchSyncPolicyManager requireV2Policies()
    {
        return requireNonNull(v2Policies, "V2 branch policies are not configured");
    }

    private V2BranchGuardProjection requireV2Projection()
    {
        return requireNonNull(v2Projection, "V2 branch projection is not configured");
    }
}
