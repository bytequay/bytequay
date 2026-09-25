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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.context.DevelopmentContextActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Read-only task detail for saved development notebook offers and agent lookups. */
@RestController
@RequestMapping("/agent/tasks/{taskId}/repository-context")
public class DevelopmentContextActivityController
{
    private final AgentTaskService tasks;
    private final DevelopmentContextActivityService activity;

    public DevelopmentContextActivityController(AgentTaskService tasks, DevelopmentContextActivityService activity)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.activity = requireNonNull(activity, "activity is null");
    }

    @GetMapping
    public DevelopmentContextActivityService.Activity get(@PathVariable String taskId, @RequestParam String workspaceId)
    {
        tasks.getInteractive(workspaceId, TaskId.valueOf(taskId));
        return activity.activity(workspaceId, taskId);
    }
}
