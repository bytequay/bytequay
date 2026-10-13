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
package com.bytequay.core.ci;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An exact description proposal and the facts needed to reconcile its write and replacement workflow. */
public record CiDescriptionChange(String expectedBody, String body, long workflowId, long previousRunId, Optional<Instant> writeStartedAt, Optional<Instant> appliedAt)
{
    public CiDescriptionChange
    {
        requireNonNull(expectedBody, "expectedBody is null");
        checkArgument(expectedBody.length() <= 60_000, "expectedBody exceeds 60000 characters");
        checkText(body, "body", 60_000);
        checkArgument(!body.equals(expectedBody), "description is unchanged");
        checkArgument(workflowId > 0 && previousRunId > 0, "workflow IDs must be positive");
        requireNonNull(writeStartedAt, "writeStartedAt is null");
        requireNonNull(appliedAt, "appliedAt is null");
        checkArgument(appliedAt.isEmpty() || writeStartedAt.isPresent(), "applied description requires a write journal");
    }

    public CiDescriptionChange startingWrite(Instant now)
    {
        return new CiDescriptionChange(expectedBody, body, workflowId, previousRunId, Optional.of(now), appliedAt);
    }

    public CiDescriptionChange applied(Instant now)
    {
        return new CiDescriptionChange(expectedBody, body, workflowId, previousRunId, writeStartedAt, Optional.of(now));
    }
}
