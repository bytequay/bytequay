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

import com.bytequay.agent.core.TaskId;
import com.bytequay.core.domain.PullRequestRef;

import static com.bytequay.app.ci.CiActionsChecks.checkPullRequest;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ReadCiLogQuery(
        TaskId taskId,
        PullRequestRef pullRequest,
        CiWorkflowRun workflowRun,
        CiJob job,
        int maximumBytes)
{
    public static final int MAXIMUM_BYTES = 10 * 1024 * 1024;

    public ReadCiLogQuery
    {
        requireNonNull(taskId, "taskId is null");
        checkPullRequest(pullRequest);
        requireNonNull(workflowRun, "workflowRun is null");
        requireNonNull(job, "job is null");
        checkArgument(job.workflowRunId() == workflowRun.id() && job.attempt() <= workflowRun.attempt(),
                "job does not belong to workflowRun");
        checkArgument(maximumBytes > 0 && maximumBytes <= MAXIMUM_BYTES,
                "maximumBytes must be between 1 and " + MAXIMUM_BYTES);
    }
}
