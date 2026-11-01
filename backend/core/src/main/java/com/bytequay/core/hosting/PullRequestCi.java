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
package com.bytequay.core.hosting;

import com.bytequay.core.ci.CiDeliveryChecks;
import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiRerunResult;
import com.bytequay.core.ci.RerunFailedJobsCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** CI runs, job logs and checks of one request, read and rerun with the target's own credentials. */
public interface PullRequestCi
{
    /** Reads the request's current head, target branch and the latest attempt of every CI run on that head. */
    CiHeadSnapshot inspect(HostingTarget target, int number);

    /**
     * Writes the complete credential-redacted job log to {@code destination}.
     *
     * <p>Providers write plain lines where a line starting with {@code ##[group]} starts a step and one starting with
     * {@code ##[error]} marks an error, the format the shared excerpt selector reads.
     *
     * @return empty when the complete log was written, otherwise why it is unavailable
     */
    Optional<String> downloadLog(HostingTarget target, int number, long jobId, Path destination);

    /**
     * Reads the failure messages the provider attached to a job outside its log. Some causes, such as a lost runner,
     * appear only here.
     *
     * @return credential-redacted messages, or an empty list when the provider has none or refuses the read
     */
    List<String> failureNotes(HostingTarget target, int number, long jobId);

    /**
     * Names the failed jobs of a definition's latest completed push run on a branch, which tells a request's own
     * failures from ones its base already has.
     *
     * @return failed job names, or an empty set when the branch has no completed push run of that definition
     */
    Set<String> failedJobsOnBranch(HostingTarget target, long definitionId, String branch);

    /**
     * Reruns the failed jobs of one exact completed run attempt.
     *
     * <p>The caller supplies the stored user authorization and journals the request before this method is called.
     * Acceptance does not mean CI passed.
     *
     * @return accepted, obsolete, or rejected result
     */
    CiRerunResult rerunFailedJobs(HostingTarget target, int number, RerunFailedJobsCommand command);

    /** Reads every current-head check and the target branch's required checks, without a cached success fallback. */
    CiDeliveryChecks deliveryChecks(HostingTarget target, int number);
}
