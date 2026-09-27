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
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.agent.TaskActivity;
import com.bytequay.app.agent.TaskActivityReader;
import com.bytequay.app.agent.TaskMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** What each listed task last said and waits on, for task lists and Needs attention. */
@RestController
public class TaskActivityController
{
    private final AgentTaskService tasks;
    private final TaskActivityReader activity;

    public TaskActivityController(AgentTaskService tasks, TaskActivityReader activity)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.activity = requireNonNull(activity, "activity is null");
    }

    /** By task id, for the same tasks the workspace's task list returns; nothing here reads a provider. */
    @GetMapping("/agent/tasks/activity")
    public Map<String, TaskActivityResponse> taskActivity(@RequestParam String workspaceId)
    {
        return activity.read(tasks.list(workspaceId, 100)).entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().toString(), entry -> response(entry.getValue())));
    }

    private static TaskActivityResponse response(TaskActivity activity)
    {
        return new TaskActivityResponse(
                activity.lastMessage().map(message -> new MessageResponse(message.author(), message.text(), message.at(), message.turnId().map(TurnId::toString))),
                activity.pendingApproval().map(AgentHttpModels::pendingApproval),
                activity.latestTurn().map(TaskActivityController::turn));
    }

    private static LatestTurnResponse turn(AgentTurn turn)
    {
        return new LatestTurnResponse(turn.id().toString(), turn.state().name(), turn.endedAt());
    }

    public record TaskActivityResponse(
            Optional<MessageResponse> lastMessage,
            Optional<AgentHttpModels.PendingApprovalResponse> pendingApproval,
            Optional<LatestTurnResponse> latestTurn) {}

    public record MessageResponse(TaskMessage.Author author, String text, Instant at, Optional<String> turnId) {}

    public record LatestTurnResponse(String id, String state, Optional<Instant> endedAt) {}
}
