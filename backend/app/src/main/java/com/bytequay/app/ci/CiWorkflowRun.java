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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.bytequay.app.ci.CiActionsChecks.checkText;
import static com.bytequay.app.ci.CiActionsChecks.checkUrl;
import static com.bytequay.app.ci.CiActionsChecks.normalizeConclusion;
import static com.bytequay.app.ci.CiActionsChecks.normalizeState;
import static java.util.Objects.requireNonNull;

public record CiWorkflowRun(
        long id,
        long workflowId,
        String name,
        int attempt,
        String status,
        Optional<String> conclusion,
        String headSha,
        String url,
        List<CiJob> jobs)
{
    public CiWorkflowRun
    {
        if (id <= 0 || workflowId <= 0 || attempt <= 0) {
            throw new IllegalArgumentException("workflow IDs and attempt must be positive");
        }
        checkText(name, "name", 500);
        status = normalizeState(status, "status");
        conclusion = normalizeConclusion(conclusion);
        checkSha(headSha, "headSha");
        checkUrl(url, "url");
        requireNonNull(jobs, "jobs is null");
        jobs = ImmutableList.copyOf(jobs);
        if (jobs.size() > 1000) {
            throw new IllegalArgumentException("jobs exceeds 1000 values");
        }
        if (jobs.stream().anyMatch(job -> job.workflowRunId() != id || job.attempt() > attempt)) {
            throw new IllegalArgumentException("job does not belong to this workflow attempt");
        }
    }
}
