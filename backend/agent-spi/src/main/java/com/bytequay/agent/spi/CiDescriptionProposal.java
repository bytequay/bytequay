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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A description-only repair of one failed job on the task-bound pull request. */
public record CiDescriptionProposal(long jobId, String expectedBody, String body)
{
    public static final String TOOL_NAME = "propose_ci_description";

    public CiDescriptionProposal
    {
        checkArgument(jobId > 0, "jobId must be positive");
        requireNonNull(expectedBody, "expectedBody is null");
        requireNonNull(body, "body is null");
        checkArgument(expectedBody.length() <= 60_000 && body.length() <= 60_000 && !body.isBlank(), "description exceeds bounds or is blank");
        checkArgument(!body.equals(expectedBody), "description is unchanged");
    }
}
