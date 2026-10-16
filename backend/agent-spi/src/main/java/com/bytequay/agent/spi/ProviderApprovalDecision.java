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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ProviderApprovalDecision(
        Decision decision,
        Optional<String> scopedPermission,
        /** What the user answered a question tool with, keyed by question; empty for every other approval. */
        Map<String, String> answers,
        boolean timedOut)
{
    public ProviderApprovalDecision
    {
        requireNonNull(decision, "decision is null");
        checkOptionalText(scopedPermission, "scopedPermission");
        answers = ImmutableMap.copyOf(answers);
        checkArgument(decision != Decision.DENY || scopedPermission.isEmpty(), "a denied decision cannot grant a scoped permission");
        checkArgument(decision != Decision.DENY || answers.isEmpty(), "a denied decision cannot answer a question");
        checkArgument(!timedOut || decision == Decision.DENY, "an expired decision cannot grant permission");
    }

    public ProviderApprovalDecision(Decision decision, Optional<String> scopedPermission, boolean timedOut)
    {
        this(decision, scopedPermission, ImmutableMap.of(), timedOut);
    }

    public static ProviderApprovalDecision allow(Optional<String> scopedPermission)
    {
        return allow(scopedPermission, ImmutableMap.of());
    }

    public static ProviderApprovalDecision allow(Optional<String> scopedPermission, Map<String, String> answers)
    {
        return new ProviderApprovalDecision(Decision.ALLOW, scopedPermission, answers, false);
    }

    public static ProviderApprovalDecision deny()
    {
        return new ProviderApprovalDecision(Decision.DENY, Optional.empty(), false);
    }

    public static ProviderApprovalDecision expired()
    {
        return new ProviderApprovalDecision(Decision.DENY, Optional.empty(), true);
    }

    public enum Decision
    {
        ALLOW,
        DENY
    }
}
