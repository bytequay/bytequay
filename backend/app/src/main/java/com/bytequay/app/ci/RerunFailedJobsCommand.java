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

import com.bytequay.core.domain.PullRequestRef;

import static com.bytequay.app.ci.CiActionsChecks.checkPullRequest;
import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;

public record RerunFailedJobsCommand(
        PullRequestRef pullRequest,
        long workflowRunId,
        String headSha,
        int previousAttempt,
        boolean authorized)
{
    public RerunFailedJobsCommand
    {
        checkPullRequest(pullRequest);
        checkArgument(workflowRunId > 0 && previousAttempt > 0,
                "workflowRunId and previousAttempt must be positive");
        checkSha(headSha, "headSha");
    }
}
