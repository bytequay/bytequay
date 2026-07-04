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

import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.TaskPhase;

import java.time.Instant;
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
        // Red, still running, or not yet synced — the branch is up, waiting
        // either way; a red check is handled by a ci_fix AgentRun beside
        // this phase, not by moving it.
        if (ci == CiStatus.FAILING || ci == CiStatus.PENDING || ci == null) {
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

    /**
     * Maps a freshly-fetched {@link PullRequestDetail} to the remote-spine
     * phase. Used by the lifecycle driver's direct per-task fetch, which
     * works for any linked PR (it doesn't depend on the dashboard sync
     * having the PR). The detail carries CI + draft state; merge → DONE
     * stays on the PR-merged event path, so this only places the task on
     * the CI / ready / review spine.
     */
    static Optional<TaskPhase> observedPhaseFromDetail(PullRequestDetail detail)
    {
        if (detail == null) {
            return Optional.empty();
        }
        // Terminal: merged (success) or closed-unmerged (cancelled). This
        // is what lets the reconciler drain a task off the remote spine
        // when its PR finished anywhere — github.com, an agent, the merge
        // queue — not just via the in-app merge action.
        if (detail.merged() || "closed".equalsIgnoreCase(detail.state())) {
            return Optional.of(TaskPhase.COMPLETED);
        }
        CiStatus ci = detail.ciStatus();
        // Red, actively running, or not yet reported — keep waiting either
        // way; a red check is handled by a ci_fix AgentRun beside this
        // phase, not by moving it.
        if (ci == CiStatus.FAILING || ci == CiStatus.PENDING || ci == null) {
            return Optional.of(TaskPhase.PUSHED_AWAITING_CI);
        }
        // CI green / no CI gate: draft holds for "mark ready", a ready PR
        // is out for remote review.
        return Optional.of(detail.draft()
                ? TaskPhase.AWAITING_READY
                : TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private static boolean hasChangesRequested(PullRequest pr)
    {
        Map<String, String> verdicts = pr.reviewerVerdicts();
        return verdicts != null
                && verdicts.values().stream()
                        .anyMatch(v -> GithubReviewState.CHANGES_REQUESTED.equalsIgnoreCase(v));
    }

    /**
     * The timestamp of the newest comment in an <em>unresolved</em> review
     * thread that is strictly newer than {@code addressedThrough} (the
     * per-task last-addressed marker; null means nothing has been
     * addressed, so every unresolved comment counts as new), or empty when
     * there are no new unresolved comments to address.
     *
     * <p>Drives the post-ship address-comments loop: a ready PR with a
     * non-empty result has a fresh round of reviewer feedback, so the
     * reconciler moves the task onto the {@code ADDRESSING_COMMENTS} spine
     * and asks the user. Resolved threads are skipped — they've been dealt
     * with — and the returned instant becomes the next marker so the same
     * comments don't re-trigger on the next poll.
     */
    static Optional<Instant> newestUnaddressedReviewComment(
            PullRequestDetail detail, Instant addressedThrough)
    {
        if (detail == null || detail.reviewThreads() == null) {
            return Optional.empty();
        }
        Instant newest = null;
        for (ReviewThread thread : detail.reviewThreads()) {
            if (Boolean.TRUE.equals(thread.resolved()) || thread.messages() == null) {
                continue;
            }
            for (ReviewMessage message : thread.messages()) {
                Instant at = message.createdAt();
                if (at == null) {
                    continue;
                }
                if (addressedThrough != null && !at.isAfter(addressedThrough)) {
                    continue;
                }
                if (newest == null || at.isAfter(newest)) {
                    newest = at;
                }
            }
        }
        return Optional.ofNullable(newest);
    }
}
