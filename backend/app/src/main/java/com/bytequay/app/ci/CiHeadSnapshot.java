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
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.app.ci.CiActionsChecks.checkPullRequest;
import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.bytequay.app.ci.CiActionsChecks.checkText;
import static com.bytequay.app.ci.CiActionsChecks.checkUrl;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public record CiHeadSnapshot(
        PullRequestRef pullRequest,
        boolean open,
        String author,
        boolean sameRepository,
        String headRef,
        String headSha,
        String baseRef,
        String baseSha,
        String url,
        List<CiWorkflowRun> workflowRuns)
{
    public CiHeadSnapshot
    {
        checkPullRequest(pullRequest);
        checkText(author, "author", 100);
        checkText(headRef, "headRef", 255);
        checkSha(headSha, "headSha");
        checkText(baseRef, "baseRef", 255);
        checkSha(baseSha, "baseSha");
        checkUrl(url, "url");
        requireNonNull(workflowRuns, "workflowRuns is null");
        workflowRuns = ImmutableList.copyOf(workflowRuns);
        checkArgument(workflowRuns.size() <= 1000, "workflowRuns exceeds 1000 values");
        checkArgument(workflowRuns.stream().allMatch(run -> run.headSha().equals(headSha)), "workflow run belongs to another head");
    }

    public List<CiJob> jobs()
    {
        return workflowRuns.stream()
                .flatMap(run -> run.jobs().stream())
                .collect(toImmutableList());
    }

    public List<CiJob> actionableFailures()
    {
        return jobs().stream()
                .filter(CiJob::actionableFailure)
                .collect(toImmutableList());
    }

    public boolean pending()
    {
        return workflowRuns.stream().anyMatch(run -> !"completed".equals(run.status()))
                || jobs().stream().anyMatch(CiJob::pending);
    }

    public boolean green()
    {
        List<CiJob> jobs = jobs();
        return !jobs.isEmpty() && jobs.stream().allMatch(CiJob::passing);
    }
}
