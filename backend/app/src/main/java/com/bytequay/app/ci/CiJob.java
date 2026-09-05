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

import static com.bytequay.app.ci.CiActionsChecks.checkUrl;
import static com.bytequay.app.ci.CiActionsChecks.isActionableConclusion;
import static com.bytequay.app.ci.CiActionsChecks.isPassingConclusion;
import static com.bytequay.app.ci.CiActionsChecks.normalizeConclusion;
import static com.bytequay.app.ci.CiActionsChecks.normalizeState;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public record CiJob(
        long id,
        long workflowRunId,
        String name,
        int attempt,
        String status,
        Optional<String> conclusion,
        String url,
        List<CiStep> steps)
{
    public CiJob
    {
        checkArgument(id > 0 && workflowRunId > 0 && attempt > 0, "job IDs and attempt must be positive");
        checkText(name, "name", 500);
        status = normalizeState(status, "status");
        conclusion = normalizeConclusion(conclusion);
        checkUrl(url, "url");
        requireNonNull(steps, "steps is null");
        steps = ImmutableList.copyOf(steps);
        checkArgument(steps.size() <= 500, "steps exceeds 500 values");
    }

    public boolean actionableFailure()
    {
        return "completed".equals(status) && isActionableConclusion(conclusion);
    }

    public boolean pending()
    {
        return !"completed".equals(status);
    }

    public boolean passing()
    {
        return "completed".equals(status) && isPassingConclusion(conclusion);
    }

    public List<CiStep> failedSteps()
    {
        return steps.stream()
                .filter(CiStep::actionableFailure)
                .collect(toImmutableList());
    }

    public String failureSummary()
    {
        List<String> names = failedSteps().stream()
                .map(CiStep::name)
                .limit(5)
                .collect(toImmutableList());
        return names.isEmpty() ? name + " failed" : name + " failed at " + String.join(", ", names);
    }
}
