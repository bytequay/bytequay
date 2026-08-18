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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ProviderApprovalRequest(
        String providerRequestId,
        Kind kind,
        String title,
        String detail,
        List<String> requestedPermissions,
        Instant requestedAt)
{
    public ProviderApprovalRequest
    {
        checkNotBlank(providerRequestId, "providerRequestId");
        requireNonNull(kind, "kind is null");
        checkNotBlank(title, "title");
        requireNonNull(detail, "detail is null");
        requireNonNull(requestedPermissions, "requestedPermissions is null");
        requestedPermissions = ImmutableList.copyOf(requestedPermissions);
        requireNonNull(requestedAt, "requestedAt is null");
        checkArgument(requestedPermissions.stream().noneMatch(String::isBlank),
                "requestedPermissions contains a blank value");
    }

    public enum Kind
    {
        COMMAND,
        FILE_CHANGE,
        TOOL,
        OTHER
    }
}
