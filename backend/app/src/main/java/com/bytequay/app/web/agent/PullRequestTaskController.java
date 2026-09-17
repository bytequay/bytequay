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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.app.agent.PullRequestTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/agent/pull-request-task")
public class PullRequestTaskController
{
    private final PullRequestTaskService tasks;

    public PullRequestTaskController(PullRequestTaskService tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @GetMapping
    public ResponseEntity<TaskLink> find(@RequestParam String workspaceId, @RequestParam String repo, @RequestParam int number)
    {
        return tasks.find(workspaceId, repo, number).map(task -> ResponseEntity.ok(link(task))).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public TaskLink assign(@RequestBody AssignmentRequest request)
    {
        return link(tasks.assign(request.workspaceId(), request.repo(), request.number()));
    }

    private static TaskLink link(AgentTask task)
    {
        return new TaskLink(task.workspaceId().orElseThrow(), task.id().toString());
    }

    record AssignmentRequest(String workspaceId, String repo, int number) {}

    record TaskLink(String workspaceId, String taskId) {}
}
