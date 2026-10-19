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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** How a provider's permission request reads as agent events, the same for a task's turn as for any conversation that keeps them. */
public final class AgentApprovalEvents
{
    private AgentApprovalEvents() {}

    public static AgentEventPayload.ApprovalRequested requested(String approvalId, ProviderApprovalRequest request)
    {
        return new AgentEventPayload.ApprovalRequested(
                approvalId,
                approvalKind(request.kind()),
                request.title(),
                request.detail(),
                request.requestedPermissions(),
                request.sandboxed(),
                true,
                availableDecisions(request));
    }

    public static AgentEventSource source(ProviderApprovalRequest request)
    {
        ProviderAgentEventSource source = request.source();
        return new AgentEventSource(
                source.providerThreadId(),
                source.parentProviderThreadId(),
                source.logicalAgentPath(),
                source.providerTurnId(),
                source.providerItemId(),
                Optional.empty());
    }

    private static List<AgentEventPayload.ApprovalOption> availableDecisions(ProviderApprovalRequest request)
    {
        return request.availableDecisions().stream()
                .map(decision -> switch (decision) {
                    case ALLOW_ONCE -> AgentEventPayload.ApprovalOption.ALLOW_ONCE;
                    case ALLOW_FOR_TASK -> AgentEventPayload.ApprovalOption.ALLOW_FOR_TASK;
                    case DENY -> AgentEventPayload.ApprovalOption.DENY;
                })
                .collect(toImmutableList());
    }

    private static AgentEventPayload.ApprovalKind approvalKind(ProviderApprovalRequest.Kind kind)
    {
        return switch (kind) {
            case COMMAND -> AgentEventPayload.ApprovalKind.COMMAND;
            case FILE_CHANGE -> AgentEventPayload.ApprovalKind.FILE_CHANGE;
            case TOOL, OTHER -> AgentEventPayload.ApprovalKind.OTHER;
        };
    }
}
