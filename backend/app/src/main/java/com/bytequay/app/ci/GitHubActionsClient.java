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

public interface GitHubActionsClient
{
    /**
     * Reads the current PR head, target branch, and latest check attempts.
     *
     * @param query repository and PR number
     * @return one current GitHub snapshot
     */
    CiHeadSnapshot inspect(InspectCiHeadQuery query);

    /**
     * Downloads one bounded raw job log without exposing credentials.
     *
     * @param query exact workflow, job, attempt, task, and byte limit
     * @return task-relative log blob, normalized terminal errors, and truncation facts
     */
    BoundedCiLog readLog(ReadCiLogQuery query);

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
