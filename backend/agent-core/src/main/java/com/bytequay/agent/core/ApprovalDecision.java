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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ApprovalDecision(
        ApprovalId approvalId,
        Decision decision,
        Optional<String> scopedPermission,
        Instant resolvedAt,
        boolean timedOut)
{
    public ApprovalDecision
    {
        requireNonNull(approvalId, "approvalId is null");
        requireNonNull(decision, "decision is null");
        checkOptionalText(scopedPermission, "scopedPermission");
        requireNonNull(resolvedAt, "resolvedAt is null");
        checkArgument(decision != Decision.DENY || scopedPermission.isEmpty(), "a denied decision cannot grant a scoped permission");
        checkArgument(!timedOut || decision == Decision.DENY, "an approval timeout must deny");
    }

    public enum Decision
    {
        ALLOW,
        DENY
    }
}
