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

import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TestFact;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.spi.AgentLaneId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Workspace-safe HTTP shapes for the interactive task surface.
 *
 * <p>These records become the public route contract after the application can
 * resolve a workspace to a verified managed worktree. Keeping the replacement
 * shape separate until then avoids a half-migrated endpoint that still trusts
 * renderer paths.
 */
final class InteractiveAgentHttpContract
{
    private InteractiveAgentHttpContract() {}

    record CreateTaskRequest(
            String workspaceId,
            String title,
            String laneId,
            TaskDefaults defaults)
    {
        CreateTaskRequest
        {
            if (!AgentLaneId.CODEX_CLI.value().equals(laneId)) {
                throw new IllegalArgumentException("Interactive tasks support only the Codex lane before CP11");
            }
        }
    }

    record TaskDefaults(
            String model,
            Optional<String> effort,
            String permissionPolicy) {}

    record TaskResponse(
            String id,
            String workspaceId,
            String title,
            String laneId,
            TaskDefaults defaults,
            String state,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> terminalAt,
            Optional<String> terminalReason,
            TaskMetrics metrics,
            Optional<TaskSummaryResponse> summary,
            String cleanupState,
            Optional<Instant> deleteAfter,
            Optional<String> cleanupFailure) {}

    record TaskSummaryResponse(
            String finalResponse,
            Optional<String> resultBranch,
            String resultHead,
            List<TestFact> tests,
            TaskMetrics metrics,
            List<String> learnings,
            Optional<String> notes) {}

    record TurnResponse(
            String id,
            String taskId,
            String state,
            long version,
            Instant createdAt,
            Optional<Instant> startedAt,
            Optional<Instant> endedAt,
            Optional<Instant> cancellationRequestedAt,
            TurnSettings settings,
            Optional<String> terminalReason,
            TurnMetrics metrics) {}

    record TurnSettings(
            String model,
            Optional<String> effort,
            String permissionPolicy,
            String sandboxPolicy,
            long maximumRuntimeMillis,
            List<AttachmentReference> attachments) {}

    record AttachmentReference(
            String id,
            String name,
            String mediaType) {}
}
