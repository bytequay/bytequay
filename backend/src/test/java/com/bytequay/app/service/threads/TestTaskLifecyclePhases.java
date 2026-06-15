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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.TaskPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskLifecyclePhases
{
    private static final Instant T = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void mergedPrCompletesTheTask()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "closed", /* merged */ T, null)))
                .contains(TaskPhase.COMPLETED);
    }

    @Test
    void closedUnmergedPrCompletesTheTask()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "closed", null, null)))
                .contains(TaskPhase.COMPLETED);
    }

    @Test
    void failingCiGoesToCiFixing()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.FAILING, false, "open", null, null)))
                .contains(TaskPhase.CI_FIXING);
    }

    @Test
    void pendingOrUnknownCiWaitsOnCi()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PENDING, false, "open", null, null)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(null, false, "open", null, null)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
    }

    @Test
    void greenDraftAwaitsReady()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, true, "open", null, null)))
                .contains(TaskPhase.AWAITING_READY);
    }

    @Test
    void greenReadyWithChangesRequestedAddressesComments()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "open", null, Map.of("bob", "CHANGES_REQUESTED"))))
                .contains(TaskPhase.ADDRESSING_COMMENTS);
    }

    @Test
    void greenReadyWithoutChangesAwaitsRemoteReview()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "open", null, Map.of("bob", "APPROVED"))))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
        // No CI gate (NONE) behaves like green.
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.NONE, false, "open", null, null)))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private static PullRequest pr(CiStatus ci, boolean draft, String state, Instant mergedAt,
            Map<String, String> verdicts)
    {
        return new PullRequest(
                1L, "owner/repo", 42, "Title", "alice",
                "https://github.com/owner/repo/pull/42", T, T,
                PullRequest.Origin.AUTHORED, List.of(), null, draft, null, null, null, List.of(),
                ci, 0, 0, 0, null,
                state, null, mergedAt, null, null, null, verdicts,
                null, null, "dev/task-branch");
    }
}
