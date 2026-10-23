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
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestCiHeadSnapshot
{
    private static final String SHA = "a".repeat(40);

    @Test
    void passingJobsCannotHidePendingOrEmptyWorkflows()
    {
        CiWorkflowRun passing = workflow(1, "completed", Optional.of("success"), true);
        for (String status : ImmutableList.of("queued", "in_progress", "waiting", "requested", "unknown")) {
            CiHeadSnapshot snapshot = snapshot(ImmutableList.of(passing, workflow(2, status, Optional.empty(), false)));
            assertThat(snapshot.pending()).isTrue();
            assertThat(snapshot.green()).isFalse();
        }
        assertThat(snapshot(ImmutableList.of(passing, workflow(2, "completed", Optional.of("success"), false))).green()).isFalse();
        assertThat(snapshot(ImmutableList.of()).green()).isFalse();
    }

    @Test
    void passingJobsCannotHideFailedOrUnknownWorkflowConclusions()
    {
        for (String conclusion : ImmutableList.of("failure", "cancelled", "timed_out", "unknown")) {
            assertThat(snapshot(ImmutableList.of(workflow(1, "completed", Optional.of(conclusion), true))).green()).isFalse();
        }
        assertThat(snapshot(ImmutableList.of(workflow(1, "completed", Optional.empty(), true))).green()).isFalse();
    }

    @Test
    void acceptsCompletedNonemptySuccessNeutralAndSkippedEvidence()
    {
        for (String conclusion : ImmutableList.of("success", "neutral", "skipped")) {
            assertThat(snapshot(ImmutableList.of(workflow(1, "completed", Optional.of(conclusion), true))).green()).isTrue();
        }
    }

    private static CiWorkflowRun workflow(long id, String status, Optional<String> conclusion, boolean withJob)
    {
        List<CiJob> jobs = withJob
                ? ImmutableList.of(new CiJob(id, id, "build", 1, "completed", Optional.of("success"), "https://github.com/job/" + id, ImmutableList.of()))
                : ImmutableList.of();
        return new CiWorkflowRun(id, id, "build", 1, status, conclusion, SHA, "https://github.com/run/" + id, jobs);
    }

    private static CiHeadSnapshot snapshot(List<CiWorkflowRun> workflows)
    {
        return new CiHeadSnapshot(PullRequestRef.of("owner", "repo", 1), true, "owner", RepoRef.of("owner", "repo"), "feature", SHA, "main", SHA, "https://github.com/owner/repo/pull/1", workflows);
    }
}
