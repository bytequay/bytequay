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

import java.util.Map;
import java.util.Optional;

/**
 * Maps a linked PR's <em>observed</em> GitHub state to the dev-lifecycle
 * {@link TaskPhase} the task should be in. This is the ground-truth
 * source the server-side reconciler uses to drive the post-push spine of
 * the lifecycle, independent of whether the agent went through our tools
 * or raw git — the PR's CI / draft / review / merge state is the same
 * either way.
 *
 * <p>The pre-push phases ({@code IMPLEMENTING}, {@code VALIDATING},
 * {@code INTERNAL_REVIEW}, {@code AWAITING_PUSH}) aren't PR-observable —
 * they're driven locally — so this returns {@link Optional#empty()} when
 * the PR carries no signal that places the task on the remote spine.
 */
final class TaskLifecyclePhases
{
    private TaskLifecyclePhases() {}

    static Optional<TaskPhase> observedPhaseFor(PullRequest pr)
    {
        if (pr == null) {
            return Optional.empty();
        }
        // Terminal: merged (success) or closed-unmerged (cancelled).
        if (pr.mergedAt() != null || "closed".equalsIgnoreCase(pr.state())) {
            return Optional.of(TaskPhase.COMPLETED);
        }
        CiStatus ci = pr.ciStatus();
        if (ci == CiStatus.FAILING) {
            return Optional.of(TaskPhase.CI_FIXING);
        }
        // CI still running (or not yet synced) — the branch is up, waiting.
        if (ci == CiStatus.PENDING || ci == null) {
            return Optional.of(TaskPhase.PUSHED_AWAITING_CI);
        }
        // CI green, or no CI gate (NONE): the PR's draft / review state
        // decides where on the remote spine the task sits.
        if (pr.draft()) {
            return Optional.of(TaskPhase.AWAITING_READY);
        }
        if (hasChangesRequested(pr)) {
            return Optional.of(TaskPhase.ADDRESSING_COMMENTS);
        }
        return Optional.of(TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private static boolean hasChangesRequested(PullRequest pr)
    {
        Map<String, String> verdicts = pr.reviewerVerdicts();
        return verdicts != null
                && verdicts.values().stream()
                        .anyMatch(v -> "CHANGES_REQUESTED".equalsIgnoreCase(v));
    }
}
