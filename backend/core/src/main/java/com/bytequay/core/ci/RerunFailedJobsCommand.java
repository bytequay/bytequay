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

import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;

/** One exact completed run attempt whose failed jobs to rerun, and whether the user authorized it. */
public record RerunFailedJobsCommand(long runId, String headSha, int previousAttempt, boolean authorized)
{
    public RerunFailedJobsCommand
    {
        checkArgument(runId > 0 && previousAttempt > 0, "runId and previousAttempt must be positive");
        checkSha(headSha, "headSha");
    }
}
