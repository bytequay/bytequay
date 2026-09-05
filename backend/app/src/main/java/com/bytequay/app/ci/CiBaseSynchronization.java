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
package com.bytequay.app.ci;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Result of synchronizing one exact CI target into a private local branch. */
public record CiBaseSynchronization(
        Outcome outcome,
        Optional<String> localBaseBranch,
        String diagnostics)
{
    public CiBaseSynchronization
    {
        requireNonNull(outcome, "outcome is null");
        checkOptionalText(localBaseBranch, "localBaseBranch", 255);
        requireNonNull(diagnostics, "diagnostics is null");
        checkArgument(diagnostics.length() <= 8 * 1024, "diagnostics is too long");
        checkArgument((outcome == Outcome.READY) == localBaseBranch.isPresent(),
                "only a ready synchronization has a local base branch");
        checkArgument(outcome == Outcome.READY ? diagnostics.isEmpty() : !diagnostics.isBlank(),
                "synchronization diagnostics disagree with the outcome");
    }

    public static CiBaseSynchronization ready(String localBaseBranch)
    {
        return new CiBaseSynchronization(Outcome.READY, Optional.of(localBaseBranch), "");
    }

    public static CiBaseSynchronization invalid(String diagnostics)
    {
        return new CiBaseSynchronization(Outcome.INVALID, Optional.empty(), diagnostics);
    }

    public enum Outcome
    {
        READY,
        INVALID
    }
}
