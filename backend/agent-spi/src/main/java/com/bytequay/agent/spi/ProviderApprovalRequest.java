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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ProviderApprovalRequest(
        String providerRequestId,
        Kind kind,
        String title,
        String detail,
        List<String> requestedPermissions,
        List<Decision> availableDecisions,
        /** The words a task-wide allow covers; present whenever {@link Decision#ALLOW_FOR_TASK} is offered. */
        Optional<String> taskScope,
        ProviderAgentEventSource source,
        Instant requestedAt)
{
    public ProviderApprovalRequest
    {
        checkNotBlank(providerRequestId, "providerRequestId");
        requireNonNull(kind, "kind is null");
        checkNotBlank(title, "title");
        requireNonNull(detail, "detail is null");
        requestedPermissions = ImmutableList.copyOf(requestedPermissions);
        availableDecisions = ImmutableList.copyOf(availableDecisions);
        checkOptionalText(taskScope, "taskScope");
        requireNonNull(source, "source is null");
        requireNonNull(requestedAt, "requestedAt is null");
        checkArgument(requestedPermissions.stream().noneMatch(String::isBlank), "requestedPermissions contains a blank value");
        checkArgument(!availableDecisions.isEmpty(), "availableDecisions is empty");
        checkArgument(availableDecisions.contains(Decision.DENY), "availableDecisions does not contain DENY");
        checkArgument(!availableDecisions.contains(Decision.ALLOW_FOR_TASK) || taskScope.isPresent(), "ALLOW_FOR_TASK is offered without a taskScope");
    }

    /** A request nothing task-wide can cover, such as one from a provider that names no scope. */
    public ProviderApprovalRequest(
            String providerRequestId,
            Kind kind,
            String title,
            String detail,
            List<String> requestedPermissions,
            List<Decision> availableDecisions,
            ProviderAgentEventSource source,
            Instant requestedAt)
    {
        this(providerRequestId, kind, title, detail, requestedPermissions, availableDecisions, Optional.empty(), source, requestedAt);
    }

    public enum Kind
    {
        COMMAND,
        FILE_CHANGE,
        TOOL,
        OTHER
    }

    public enum Decision
    {
        ALLOW_ONCE,
        /** Allows this command and every later one in the task with the same {@code taskScope}. */
        ALLOW_FOR_TASK,
        DENY
    }
}
