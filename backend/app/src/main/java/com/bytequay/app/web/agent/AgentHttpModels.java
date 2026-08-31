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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentProviderStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

final class AgentHttpModels
{
    private AgentHttpModels() {}

    static TaskResponse task(AgentTask task)
    {
        return new TaskResponse(
                task.id().toString(),
                task.title(),
                task.laneId().toString(),
                task.repository().toString(),
                task.worktree().toString(),
                task.permissionPolicy().name(),
                task.state().name(),
                task.version(),
                task.createdAt(),
                task.updatedAt(),
                task.terminalAt(),
                task.terminalReason(),
                task.sessionReference().map(reference -> new SessionResponse(
                        reference.laneId().toString(),
                        reference.contractVersion(),
                        reference.providerValue())),
                task.metrics(),
                task.summary(),
                task.cleanupState().name(),
                task.deleteAfter(),
                task.cleanupFailure());
    }

    static TurnResponse turn(AgentTurn turn)
    {
        return new TurnResponse(
                turn.id().toString(),
                turn.taskId().toString(),
                turn.idempotencyKey(),
                turn.state().name(),
                turn.version(),
                turn.createdAt(),
                turn.startedAt(),
                turn.endedAt(),
                turn.cancellationRequestedAt(),
                turn.providerTurnReference().map(Object::toString),
                turn.model(),
                turn.terminalReason(),
                turn.metrics());
    }

    static EventResponse event(AgentEventEnvelope envelope)
    {
        return new EventResponse(
                envelope.schemaVersion(),
                envelope.eventId().toString(),
                envelope.sequence(),
                envelope.taskId().toString(),
                envelope.turnId().map(Object::toString),
                envelope.source(),
                envelope.occurredAt(),
                envelope.type().name().toLowerCase(Locale.ROOT),
                envelope.payload());
    }

    static ApprovalResponse approval(ApprovalDecision decision)
    {
        return new ApprovalResponse(
                decision.approvalId().toString(),
                decision.decision().name(),
                decision.scopedPermission(),
                decision.resolvedAt(),
                decision.timedOut());
    }

    static ProviderStatusResponse providerStatus(AgentProviderStatus status)
    {
        return new ProviderStatusResponse(
                status.laneId().toString(),
                status.readiness(),
                status.version(),
                status.diagnostic(),
                status.capabilities());
    }

    static CompletionResponse completion(TaskCompletion completion)
    {
        return new CompletionResponse(
                completion.taskId().toString(),
                completion.summary(),
                completion.completedAt(),
                completion.deleteAfter());
    }

    static CleanupResponse cleanup(CleanupResult result)
    {
        return new CleanupResponse(
                result.taskId().toString(),
                result.state().name(),
                result.deletedAt(),
                result.failure());
    }

    record TaskResponse(
            String id,
            String title,
            String laneId,
            String repository,
            String worktree,
            String permissionPolicy,
            String state,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> terminalAt,
            Optional<String> terminalReason,
            Optional<SessionResponse> sessionReference,
            TaskMetrics metrics,
            Optional<TaskSummary> summary,
            String cleanupState,
            Optional<Instant> deleteAfter,
            Optional<String> cleanupFailure) {}

    record SessionResponse(String laneId, int contractVersion, String providerValue) {}

    record TurnResponse(
            String id,
            String taskId,
            String idempotencyKey,
            String state,
            long version,
            Instant createdAt,
            Optional<Instant> startedAt,
            Optional<Instant> endedAt,
            Optional<Instant> cancellationRequestedAt,
            Optional<String> providerTurnReference,
            Optional<String> model,
            Optional<String> terminalReason,
            TurnMetrics metrics) {}

    record EventResponse(
            int schemaVersion,
            String eventId,
            long sequence,
            String taskId,
            Optional<String> turnId,
            AgentEventSource source,
            Instant occurredAt,
            String type,
            AgentEventPayload payload) {}

    record ApprovalResponse(
            String approvalId,
            String decision,
            Optional<String> scopedPermission,
            Instant resolvedAt,
            boolean timedOut) {}

    record ProviderStatusResponse(
            String laneId,
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic,
            AgentCapabilities capabilities) {}

    record CompletionResponse(
            String taskId,
            TaskSummary summary,
            Instant completedAt,
            Instant deleteAfter) {}

    record CleanupResponse(
            String taskId,
            String state,
            Optional<Instant> deletedAt,
            Optional<String> failure) {}
}
