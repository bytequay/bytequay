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
package com.bytequay.app.repository.github.actions;

import com.bytequay.core.domain.PullRequestRef;

import java.util.List;
import java.util.Set;

public interface GitHubActionsClient
{
    /** Reads the complete current description; an empty body is returned as an empty string. */
    String readDescription(PullRequestRef pullRequest);

    /**
     * Reads the current PR head, target branch, and latest check attempts.
     *
     * @param pullRequest repository and PR number
     * @return one current GitHub snapshot
     */
    CiHeadSnapshot inspect(PullRequestRef pullRequest);

    /**
     * Caches a complete credential-redacted job log and selects intact diagnostic blocks.
     *
     * @param query exact workflow, job, attempt, task, and excerpt byte limit
     * @return bounded excerpt blob, cached raw-log identifier, terminal errors, and selection facts
     */
    BoundedCiLog readLog(ReadCiLogQuery query);

    /**
     * Reads the failure messages GitHub attached to a job's check run. Some causes, such as a lost
     * runner, appear only here and never in the job log.
     *
     * @return credential-redacted messages, or an empty list when GitHub has none or refuses the read
     */
    List<String> readFailureAnnotations(PullRequestRef pullRequest, long jobId);

    /**
     * Names the failed jobs of a workflow's latest completed push run on a branch, which tells a pull request's own failures
     * from ones its base already has.
     *
     * @return failed job names, or an empty set when the branch has no completed push run of that workflow
     */
    Set<String> failedJobsOnBranch(PullRequestRef pullRequest, long workflowId, String branch);

    /**
     * Reruns failed jobs for one exact completed workflow run.
     *
     * <p>The caller supplies the stored user authorization and journals the
     * request before this method is called. Acceptance does not mean CI passed.
     *
     * @param command exact workflow run, head SHA, and rerun authorization
     * @return accepted, obsolete, or rejected result
     */
    CiRerunResult rerunFailedJobs(RerunFailedJobsCommand command);
}
