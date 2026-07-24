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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.service.review.BrainReviewService;
import org.springframework.stereotype.Component;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * {@code record_review_verdict} — the brain's one write tool for adversarial
 * review (plan-rail-runs.md R20-R24). Brain-only: it only ever runs from a
 * turn on the task's brain thread (the plan self-review, or a code
 * lock-point review turn), never from a dev/ci_fix/comments turn.
 */
@Component
public class BrainReviewToolHandlers
{
    private static final Set<String> SCOPES = Set.of("plan", "dev", "round");

    private final BrainReviewService brainReview;

    public BrainReviewToolHandlers(BrainReviewService brainReview)
    {
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
    }

    /** Args for {@code record_review_verdict}. */
    public record RecordReviewVerdictArgs(
            @ToolParam(description = "What you reviewed: 'plan' (the self-review, R20) or 'dev'/'round' "
                    + "(a code lock-point review, R21) — use whichever the prompt told you this pass is.",
                    required = true) String scope,
            @ToolParam(description = "'approved' only when there are zero concerns; otherwise leave one "
                    + "record_pr_comment per concern and use 'changes_requested'.",
                    required = true) String verdict) {}

    @AgentTool(
            name = "record_review_verdict",
            description = "Record your verdict on the plan or code you just adversarially reviewed. "
                    + "Call this once per review pass, after leaving any comments (record_pr_comment for "
                    + "code, record_plan for a plan revision). Do not put review prose or a summary in "
                    + "the verdict; the structured comments are the review. This is how the loop knows whether to stop, "
                    + "loop into another fix pass, or (for the plan) let planning proceed.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK},
            kinds = ThreadKind.BRAIN_AGENT)
    public ToolOutcome recordReviewVerdict(RecordReviewVerdictArgs args, ToolCall call)
    {
        String taskId = call.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("no active task on this thread");
        }
        if (call.stageId() == null) {
            return ToolOutcome.Completed.error("this tool needs a stage-scoped turn");
        }
        if (args == null || args.scope() == null || !SCOPES.contains(args.scope())) {
            return ToolOutcome.Completed.error("scope must be one of " + SCOPES);
        }
        if (!ReviewRound.VERDICT_APPROVED.equals(args.verdict())
                && !ReviewRound.VERDICT_CHANGES_REQUESTED.equals(args.verdict())) {
            return ToolOutcome.Completed.error("verdict must be 'approved' or 'changes_requested'");
        }
        brainReview.recordVerdict(
                taskId, call.stageId(), call.agentRunId(), args.scope(), args.verdict());
        return ToolOutcome.Completed.ok("recorded " + args.verdict() + " (" + args.scope() + ")");
    }
}
