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

import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record CiPushResult(Outcome outcome, Optional<String> pushedSha, String reason)
{
    public CiPushResult
    {
        requireNonNull(outcome, "outcome is null");
        requireNonNull(pushedSha, "pushedSha is null");
        pushedSha.ifPresent(sha -> checkSha(sha, "pushedSha"));
        requireNonNull(reason, "reason is null");
        if (reason.length() > 2 * 1024) {
            reason = reason.substring(0, 2 * 1024);
        }
        checkArgument((outcome == Outcome.PUSHED) == pushedSha.isPresent(),
                "only PUSHED contains pushedSha");
    }

    public static CiPushResult pushed(String sha)
    {
        return new CiPushResult(Outcome.PUSHED, Optional.of(sha), "");
    }

    public static CiPushResult failed(Outcome outcome, String reason)
    {
        return new CiPushResult(outcome, Optional.empty(), reason);
    }

    public enum Outcome
    {
        PUSHED,
        RETRYABLE,
        STALE,
        UNCERTAIN,
        REJECTED
    }
}
