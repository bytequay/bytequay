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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One accepted review or explicit learning execution owns these captured inputs; task lifecycle and cancellation remain authoritative elsewhere. */
public record ActiveContextPreparation(
        String attemptId,
        String requestKey,
        String requestDigest,
        String ownerTaskId,
        String ownerTurnId,
        String ownerTurnKey,
        Optional<String> reviewId,
        Optional<String> runId,
        long baseContentRevision,
        List<ContextInputFingerprint> inputs)
{
    public static final int MAXIMUM_INPUT_BYTES = 256 * 1024;

    public ActiveContextPreparation
    {
        ContextLimits.text(attemptId, "preparation attempt id", 200);
        ContextLimits.text(requestKey, "preparation request key", 200);
        ContextLimits.text(requestDigest, "preparation request digest", 71);
        checkArgument(requestDigest.matches("sha256:[0-9a-f]{64}"), "Invalid preparation request digest");
        ContextLimits.text(ownerTaskId, "preparation task id", 200);
        ContextLimits.text(ownerTurnId, "preparation turn id", 200);
        ContextLimits.text(ownerTurnKey, "preparation turn key", 500);
        requireNonNull(reviewId, "reviewId is null");
        requireNonNull(runId, "runId is null");
        checkArgument(reviewId.isPresent() == runId.isPresent(), "Preparation review and run identities must be paired");
        reviewId.ifPresent(value -> ContextLimits.text(value, "preparation review id", 200));
        runId.ifPresent(value -> ContextLimits.text(value, "preparation run id", 200));
        checkArgument(baseContentRevision >= 0, "Negative preparation revision");
        inputs = ImmutableList.copyOf(inputs);
        ContextLimits.unique(inputs.stream().map(ContextInputFingerprint::scopeId).toList(), "preparation input scopes", 100);
        checkArgument(!inputs.isEmpty(), "Preparation needs selected scopes");
        checkArgument(inputs.stream().allMatch(input -> input.digest().isPresent()), "Preparation needs complete input identities");
        checkArgument(inputs.stream().mapToLong(ContextInputFingerprint::inputBytes).sum() <= MAXIMUM_INPUT_BYTES,
                "Preparation input identities exceed UTF-8 budget");
    }
}
