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

import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static java.util.Objects.requireNonNull;

public record ProviderApprovalDecision(Decision decision, Optional<String> scopedPermission)
{
    public ProviderApprovalDecision
    {
        requireNonNull(decision, "decision is null");
        checkOptionalText(scopedPermission, "scopedPermission");
        if (decision == Decision.DENY && scopedPermission.isPresent()) {
            throw new IllegalArgumentException("a denied decision cannot grant a scoped permission");
        }
    }

    public static ProviderApprovalDecision allow(Optional<String> scopedPermission)
    {
        return new ProviderApprovalDecision(Decision.ALLOW, scopedPermission);
    }

    public static ProviderApprovalDecision deny()
    {
        return new ProviderApprovalDecision(Decision.DENY, Optional.empty());
    }

    public enum Decision
    {
        ALLOW,
        DENY
    }
}
