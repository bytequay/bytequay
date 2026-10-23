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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The first message of an interactive task, and the source it starts from. */
public record InteractiveTaskStart(
        TaskId taskId,
        String workspaceId,
        String message,
        AgentLaneId laneId,
        AgentTaskDefaults defaults,
        String idempotencyKey,
        /** The first turn's automation; the task's defaults apply when it is empty. */
        Optional<DevelopmentAutomation> initialAutomation,
        Optional<IssueSourceInput> issueSource,
        Optional<JiraSourceInput> jiraSource,
        Optional<String> workItemId,
        List<StoreAgentAttachmentCommand> uploads)
{
    public InteractiveTaskStart
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(message, "message is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(defaults, "defaults is null");
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        requireNonNull(initialAutomation, "initialAutomation is null");
        requireNonNull(issueSource, "issueSource is null");
        requireNonNull(jiraSource, "jiraSource is null");
        requireNonNull(workItemId, "workItemId is null");
        uploads = ImmutableList.copyOf(uploads);
    }
}
