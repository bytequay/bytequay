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
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentProviderStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

final class AgentHttpModels
{
    private AgentHttpModels() {}

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
                InteractiveAgentHttpContract.summary(completion.summary()),
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
            InteractiveAgentHttpContract.TaskSummaryResponse summary,
            Instant completedAt,
            Instant deleteAfter) {}

    record CleanupResponse(
            String taskId,
            String state,
            Optional<Instant> deletedAt,
            Optional<String> failure) {}
}
