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
package com.bytequay.app.service.mcp.approval;

import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.mcp.McpResponses;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Fails a Task-scoped typed callback closed before any policy can allow it. */
@Component
@Order(50)
public class TypedPolicyGuardStep
        implements ApprovalStep
{
    private final TaskManager.Store tasks;
    private final McpResponses responses;

    public TypedPolicyGuardStep(
            TaskManager.Store tasks,
            McpResponses responses)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!ctx.isTypedV2Owner() || ctx.taskId() == null
                || tasks.findPolicy(ctx.taskId()).isPresent()) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(responses.toolResponse(
                ctx.id(), responses.deny(
                        "The exact V2 Task policy is unavailable; permission fails closed.")));
    }
}
