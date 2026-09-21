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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ResolveApprovalCommand(
        ApprovalId approvalId,
        ApprovalDecision.Decision decision,
        Optional<String> scopedPermission,
        boolean forTask,
        /** What the user answered a question tool with, keyed by question; empty for every other approval. */
        Map<String, String> answers)
{
    public ResolveApprovalCommand
    {
        requireNonNull(approvalId, "approvalId is null");
        requireNonNull(decision, "decision is null");
        checkOptionalText(scopedPermission, "scopedPermission");
        answers = ImmutableMap.copyOf(answers);
        checkArgument(decision != ApprovalDecision.Decision.DENY || scopedPermission.isEmpty(), "a denied decision cannot grant a scoped permission");
        checkArgument(decision != ApprovalDecision.Decision.DENY || answers.isEmpty(), "a denied decision cannot answer a question");
        checkArgument(!forTask || decision == ApprovalDecision.Decision.ALLOW, "only an allow can cover the task");
    }

    public ResolveApprovalCommand(ApprovalId approvalId, ApprovalDecision.Decision decision, Optional<String> scopedPermission, boolean forTask)
    {
        this(approvalId, decision, scopedPermission, forTask, ImmutableMap.of());
    }
}
