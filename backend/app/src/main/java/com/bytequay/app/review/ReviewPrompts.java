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
package com.bytequay.app.review;

import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** The managed review policy and the ByteQuay-built requests that drive a review task. */
final class ReviewPrompts
{
    static final String INSTRUCTION = loadReviewInstructions();
    private static final String DELEGATION_REQUEST = "Use up to two read-only subagents for independent investigation questions when native delegation is available, "
            + "while you continue investigating. Keep edits, tests, review decisions and saved reports in the main agent. ";

    private ReviewPrompts() {}

    static String location(ReviewFinding finding)
    {
        return finding.anchor().map(anchor -> anchor.path() + " " + anchor.label()).orElse("whole pull request");
    }

    private static String loadReviewInstructions()
    {
        try (InputStream resource = ReviewPrompts.class.getResourceAsStream("/agent-skills/bytequay-review.md")) {
            checkState(resource != null, "ByteQuay review guidance is missing");
            return new String(resource.readAllBytes(), UTF_8).strip();
        }
        catch (IOException failure) {
            throw new IllegalStateException("ByteQuay review guidance could not be read", failure);
        }
    }

    static String reviewRequest(AgentReview review, ReviewRun run, Optional<ReviewRun> previous, List<ReviewFinding> priorFindings, String userLogin)
    {
        requireNonNull(review, "review is null");
        requireNonNull(run, "run is null");
        StringBuilder request = new StringBuilder()
                .append(review.sourceTaskId().isPresent() ? "Review local development changes in " : review.replay().isPresent() ? "Review a past change in "
                        : "Review pull request ").append(review.repo())
                .append(review.number().isPresent() ? "#" + review.number().getAsInt() : "")
                .append(" at head ").append(shortSha(run.headSha()))
                .append(" (").append(run.commits().size()).append(run.commits().size() == 1 ? " commit" : " commits")
                .append(" vs ").append(run.baseRef()).append("@").append(shortSha(run.baseSha())).append(").\n\n")
                .append("Purpose: private review for ").append(userLogin).append(". The scope is frozen at this head. ");
        review.sourceTaskId().ifPresent(taskId -> request.append("Source development task: ").append(taskId)
                .append(". Review only committed changes, including unpushed commits; uncommitted development edits are excluded. ")
                .append("Your conversation and experimental worktree are separate from the developer.\n"));
        if (run.scope() == ReviewRun.Scope.INCREMENTAL && previous.isPresent()) {
            request.append("This is an incremental review: the last completed review covered head ")
                    .append(shortSha(previous.orElseThrow().headSha()))
                    .append(". Investigate what the new commits changed, recheck the findings below against the current source and dependencies, ")
                    .append("and update the overall summary rather than writing only a changelog.\n");
        }
        else {
            request.append("This is a full review of every commit in scope.\n");
        }
        if (!priorFindings.isEmpty()) {
            String open = priorFindings.stream()
                    .filter(finding -> finding.state() != ReviewFinding.State.WITHDRAWN)
                    .map(finding -> finding.id() + " (" + finding.label() + ", " + finding.state() + ", revision " + finding.revision()
                            + ", " + location(finding) + "): " + finding.title())
                    .collect(toImmutableList())
                    .toString();
            request.append("Existing findings: ").append(open).append("\n")
                    .append("Read kind findings target=<findingId> for each carried OPEN claim and include it as a numbered investigation candidate. ")
                    .append("A mapped line is only a location: ")
                    .append("recheck its contract, current source and dependencies before saving a new supported revision or withdrawing it. ")
                    .append("Old evidence is history, not a current-run ref. User-resolved findings stay resolved.\n");
        }
        return request.append("\nInvestigation pass: understand the change and trace affected behavior before proposing candidates. ")
                .append("Use review_get_context kind scope for frozen commits and requiredReviewFiles, then diff and source. Read PR or timeline context when a PR is associated. ")
                .append("Report with review_save_summary and review_save_investigation, then end this turn. ")
                .append("Do not save findings yet; ByteQuay will start a separate validation turn. ")
                .append(DELEGATION_REQUEST)
                .append("Optional CodeGraph exploration is available when codegraph_explore is listed. No remote writes.")
                .toString();
    }

    static String stepRequest(ReviewRun run)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        String assignment = switch (step.kind()) {
            case INVESTIGATE -> "Finish the investigation: save the intention and every commit explanation, then seal review_save_investigation. Do not save findings.";
            case CHECK_NITS -> "The review has zero supported findings. Take one more look specifically for concrete nits: "
                    + "method and field naming, API calls and usage, code simplification, and test clarity and simplification. "
                    + "Verify that tests exercise the intended feature or fix: would the same assertions fail without it for the intended behavioral reason, "
                    + "rather than an unrelated serialization error, compilation failure, or broken setup? "
                    + "Read the saved investigation, previous validation and verification handoff; reuse relevant evidence and completed checks. "
                    + "Preserve assessed scope and existing candidate text and numbering, and append up to five concrete Nit: candidates with supporting evidence. "
                    + "Do not invent nits or downgrade a real defect to a nit; record any newly discovered defect as a normal candidate. "
                    + "Seal review_save_investigation with empty answers, then end this turn without saving findings. "
                    + "ByteQuay will validate the candidates before finishing; a second zero-finding result is valid.";
            case COLLECT_EVIDENCE -> "Collect evidence for the questions in assignment.previousValidation. Execute or inspect each requested check; "
                    + "capture its result with review_get_context and answer it in review_save_investigation. Explain unavailable evidence as a limitation. "
                    + "Preserve candidate numbering. End this turn after sealing; ByteQuay will schedule validation.";
            case VALIDATE -> "Validate the investigation and every candidate against the current evidence. "
                    + "Read assignment and investigation context, including its verification recipe and captured results. "
                    + "Reuse completed checks when their code, test setup, environment and purpose still match; rerun changed inputs or checks needed for different evidence. "
                    + "Try to disprove each claim. Save supported findings, then seal review_finish for the assigned investigationStep. "
                    + "Use NEEDS_EVIDENCE with a specific missing proposition, next check, and resolving criterion if another inspection is necessary. "
                    + "Use UNRESOLVED when missing evidence prevents settling a candidate; zero findings is valid. "
                    + "Unavailable tests alone are warnings, not omissions, and do not prevent a complete review. "
                    + "For fixes/features, HEAD-pass and old-behavior-fail support only the behavior tested. A remaining defect must exist at frozen HEAD.";
        };
        return "Review assignment " + step.number() + " for frozen PR head " + shortSha(run.headSha()) + ". "
                + (step.repair() ? "ByteQuay is automatically continuing this assignment after the previous turn could not finish. "
                        + "Stay on this frozen head. Read review_get_context kind assignment, summary, and investigation before doing more work. "
                        + "Reuse saved evidence and findings; do not repeat completed checks or ask the user to retry. " : "")
                + (step.sealed() ? "" : DELEGATION_REQUEST)
                + (step.sealed() ? "The required report is already saved and sealed. End this turn without resubmitting it; ByteQuay will continue the review. " : assignment)
                + (step.repair() && !run.hasCompleteSummary()
                        ? " The saved summary is missing or lacks commit explanations: save purpose and an explanation for every commit in scope." : "");
    }

    static String question(String targetLabel, int targetRevision, String headSha, String message)
    {
        return "Question about " + targetLabel + " (revision " + targetRevision + ", reviewed head " + shortSha(headSha) + "):\n\n"
                + message
                + "\n\nReply privately with review_reply targeting " + targetLabel
                + ". You may revise or withdraw its finding. After an accepted review, save newly validated bugs or concrete improvements separately "
                + "with review_save_finding and the relevant diff anchors, not as recommendations in this card's reply. "
                + "This is a discussion, not a new full review; review_finish is not needed.";
    }

    static String shortSha(String sha)
    {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
