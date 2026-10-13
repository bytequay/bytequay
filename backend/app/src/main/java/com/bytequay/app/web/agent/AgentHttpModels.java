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
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * HTTP shapes for agent activity, shared with the review pane's own routes.
 *
 * <p>Public only so the sibling {@code web.review} package can render the same transcript,
 * approval, and history shapes; this type stays internal to the application module.
 */
public final class AgentHttpModels
{
    private AgentHttpModels() {}

    public static EventResponse event(AgentEventEnvelope envelope)
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

    public static ApprovalResponse approval(ApprovalDecision decision)
    {
        return new ApprovalResponse(
                decision.approvalId().toString(),
                decision.decision().name(),
                decision.scopedPermission(),
                decision.forTask(),
                decision.resolvedAt(),
                decision.timedOut());
    }

    public static HistoryResponse history(AgentHistoryPage page)
    {
        return new HistoryResponse(
                page.events().stream().map(AgentHttpModels::event).collect(toImmutableList()),
                page.beforeCursor().isPresent() ? Optional.of(Long.toString(page.beforeCursor().getAsLong())) : Optional.empty(),
                page.afterCursor().isPresent() ? Optional.of(Long.toString(page.afterCursor().getAsLong())) : Optional.empty(),
                page.latestSequence());
    }

    public static PendingApprovalResponse pendingApproval(ApprovalRequest request)
    {
        ProviderApprovalRequest provider = request.providerRequest();
        ProviderAgentEventSource source = provider.source();
        return new PendingApprovalResponse(
                request.id().toString(), request.taskId().toString(), request.turnId().toString(),
                new AgentEventSource(source.providerThreadId(), source.parentProviderThreadId(), source.logicalAgentPath(),
                        source.providerTurnId(), source.providerItemId(), Optional.empty()),
                request.requestedAt(), provider.title(), provider.detail(),
                switch (provider.kind()) {
                    case COMMAND, FILE_CHANGE -> provider.kind().name();
                    case TOOL, OTHER -> "OTHER";
                },
                provider.requestedPermissions(), provider.availableDecisions().stream().map(Enum::name).collect(toImmutableList()), true);
    }

    public static ProviderStatusResponse providerStatus(AgentProviderStatus status)
    {
        return new ProviderStatusResponse(status.laneId().toString(), status.readiness(), status.version(), status.diagnostic(), status.capabilities(), status.signInCommand());
    }

    public static CompletionResponse completion(TaskCompletion completion)
    {
        return new CompletionResponse(
                completion.taskId().toString(),
                InteractiveAgentHttpContract.summary(completion.summary()),
                completion.completedAt(),
                completion.deleteAfter());
    }

    public static RuntimeStopImpactResponse stopImpact(AgentRuntimeStopImpact impact)
    {
        return new RuntimeStopImpactResponse(impact.taskId().toString(), impact.generation(), impact.activeTurnId().map(Object::toString), impact.liveProcess());
    }

    /** A stored attachment's facts; the blob itself is read through its own endpoint. */
    public record AttachmentResponse(String id, String name, String mediaType, long byteSize, int width, int height) {}

    public static AttachmentResponse attachment(StoredAgentAttachment stored)
    {
        return new AttachmentResponse(stored.id(), stored.name(), stored.mediaType(), stored.byteSize(), stored.width(), stored.height());
    }

    public record EventResponse(
            int schemaVersion,
            String eventId,
            long sequence,
            String taskId,
            Optional<String> turnId,
            AgentEventSource source,
            Instant occurredAt,
            String type,
            AgentEventPayload payload) {}

    public record ApprovalResponse(
            String approvalId,
            String decision,
            Optional<String> scopedPermission,
            boolean forTask,
            Instant resolvedAt,
            boolean timedOut) {}

    public record HistoryResponse(List<EventResponse> events, Optional<String> beforeCursor, Optional<String> afterCursor, long latestSequence) {}

    public record PendingApprovalResponse(
            String approvalId, String taskId, String turnId, AgentEventSource source, Instant occurredAt,
            String title, String detail, String approvalKind, List<String> scope, List<String> availableDecisions, boolean actionable) {}

    public record ProviderStatusResponse(
            String laneId,
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic,
            AgentCapabilities capabilities,
            Optional<String> signInCommand) {}

    public record CompletionResponse(
            String taskId,
            InteractiveAgentHttpContract.TaskSummaryResponse summary,
            Instant completedAt,
            Instant deleteAfter) {}

    public record RuntimeStopImpactResponse(
            String taskId,
            String generation,
            Optional<String> activeTurnId,
            boolean liveProcess) {}

    /** The creation step an in-flight create request is in, or empty once that request has returned. */
    public record StartupProgressResponse(Optional<String> phase) {}
}
