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

import com.bytequay.core.context.ContextLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Starting another check requires the current check to settle, including cancellation between turns. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/build")
public class ContextBuildController
{
    private final ContextBuildService builds;

    public ContextBuildController(ContextBuildService builds)
    {
        this.builds = requireNonNull(builds, "builds is null");
    }

    @GetMapping
    public ContextBuildService.View get(@PathVariable String workspaceId)
    {
        return builds.get(workspaceId);
    }

    @PostMapping("/stop")
    public ContextBuildService.View stop(@PathVariable String workspaceId, @RequestBody TaskRequest request)
    {
        return builds.stop(workspaceId, request.taskId());
    }

    public record TaskRequest(String taskId)
    {
        public TaskRequest
        {
            ContextLimits.text(taskId, "taskId", 200);
        }
    }
}
