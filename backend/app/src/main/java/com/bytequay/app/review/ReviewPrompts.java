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

import com.bytequay.core.memory.Memory;
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
    static final String INSTRUCTION = load("bytequay-review.md");
    private static final String SWEEP = load("bytequay-review-sweep.md");
    private static final int MAX_SWEEP_FILES = 300;
    private static final int MAX_SWEEP_DESCRIPTION = 8000;
    private static final String DELEGATION_REQUEST = "Default to two concurrent read-only subagents when native delegation is useful; use zero or one for a small change. "
            + "Expand to three or four only when the saved map identifies independent unanswered responsibility questions, provider capacity permits, "
            + "and time remains to inspect their reports and seal the assignment. Keep at most four children running, including any mapping helper. "
            + "Assign concrete questions and source entry points; BEHAVIOR, INTERACTIONS and DESIGN are coverage perspectives, not fixed agent roles. "
            + "Continue investigating yourself. Start complementary reviewers only after the parent verifies and saves the map. "
            + "Give them the verified saved neutral responsibility map and source entry points, not your suspected findings. "
            + "Record your initial hypotheses before reconciling their reports; checkpoint qualifying child hypotheses before filtering them. "
            + "After each batch, reassess uncovered responsibilities, conflicting evidence and missing context. "
            + "Read directly, follow up with a suitable existing child when supported, or start focused children for the remaining useful questions. "
            + "Keep each assignment within the current review step; do not repeat settled checks or restart broad discovery during evidence collection. "
            + "The concurrency limit is not a total child quota; later batches remain within the existing turn budget. "
            + "Complete missing perspectives yourself if delegation is unavailable. Keep edits, tests, review decisions and saved reports in the main agent. ";
    private static final String DISCOVERY_REQUEST = "Read any saved investigation map before extending it. "
            + "Map changed responsibilities from producer through decision and consumer to observable consequence. "
            + "Include frozen scope and intent, the main flow, relevant callers, owners, sibling implementations and tests, and unresolved context gaps. "
            + "The map is ready for starting discovery and remains revisable when later evidence changes it. "
            + "For a small change, map it yourself. Otherwise, one read-only mapping child may trace the flow while you read intent and repository rules. "
            + "Verify its source and save the neutral map before handing it to complementary reviewers; the mapping child counts toward the concurrent child limit. "
            + "The first review_record_discovery checkpoint requires nonblank changeMap and nonempty contextEvidenceRefs. "
            + "Every saved or revised map must cite at least one current-run review_get_context kind source ref. "
            + "PR, discussion and command refs may supplement but cannot replace frozen source. "
            + "The gate checks source-read provenance, not semantic relevance or research depth. "
            + "The first checkpoint must have empty candidates and perspectives arrays. Candidate and perspective writes require a map saved in a prior checkpoint. "
            + "Keep notes of hypotheses noticed while mapping, then record them after the map checkpoint before filtering. "
            + "Later checkpoints omit both map fields to preserve them or supply both to revise them. "
            + "Enumerate affected callers, implementations and siblings, then investigate concrete contrasts through BEHAVIOR, INTERACTIONS and DESIGN perspectives. "
            + "After saving the neutral map and before expensive proof, account for how added or materially changed mechanisms, tests and benchmarks are used and exercised; "
            + "group equivalent additions. Trace real callers, registrations, effective configuration and consumers, including dynamic routes and bodies with no effect. "
            + "For each new API or method, establish a concrete consumer in the final PR tree or an evidenced external/framework contract. "
            + "Future-use claims and tests that merely call otherwise-unused production code do not justify new API surface. "
            + "Raise a concrete suggestion to remove or defer an unused addition, or identify missing wiring when the PR promises that integration. "
            + "For test artifacts, inspect discovery and execution gates, the changed behavior reached, assertions versus log/timing-only output, and automatic or manual use. "
            + "Record this inventory in the map or DESIGN assessment with source refs. Give each identified dormant, unwired or no-op artifact an explicit disposition: "
            + "checkpoint a concrete consequence or removable-cost candidate with its nextCheck, or explain the evidence-backed execution route and benefit of retaining it. "
            + "A broad CHECKED receipt or an intentional manual/disabled label alone does not settle that tradeoff. "
            + "Compilation alone neither exercises disabled code nor establishes its continuing value. "
            + "Do not reject a concrete maintainability suggestion merely because it causes no runtime failure; require a real removable cost, not a style preference. "
            + "Account for legitimate manual benchmarks, registered benchmark runners, reflective services, test helpers and interface no-op contracts; "
            + "missing textual callers alone do not prove dead code, and one disabled test does not prove the feature is untested. "
            + "Use review_record_discovery to checkpoint candidates and perspective assessments before expensive experiments and while time remains to seal. "
            + "Preserve plausible hypotheses and counterevidence; there is no target candidate count. ";

    private ReviewPrompts() {}

    static String location(ReviewFinding finding)
    {
        return finding.anchor().map(anchor -> anchor.path() + " " + anchor.label()).orElse("whole pull request");
    }

    private static String load(String name)
    {
        try (InputStream resource = ReviewPrompts.class.getResourceAsStream("/agent-skills/" + name)) {
            checkState(resource != null, "ByteQuay review guidance %s is missing", name);
            return new String(resource.readAllBytes(), UTF_8).strip();
        }
        catch (IOException failure) {
            throw new IllegalStateException("ByteQuay review guidance " + name + " could not be read", failure);
        }
    }

    /**
     * The second reader's whole request: its assignment, where to read the frozen change, and the reference text
     * ByteQuay gathered for it. A read-only session has no review tools, so nothing else reaches it.
     *
     * @param description the pull request's title and description, absent for a local change or a replay
     * @param facts measurements from {@link ReviewFacts}
     * @param rules repository memories to check the change against, with their text
     */
    static String sweepRequest(AgentReview review, ReviewRun run, Optional<String> description, List<String> facts, List<Memory> rules)
    {
        String range = run.mergeBaseSha() + ".." + run.headSha();
        StringBuilder request = new StringBuilder(SWEEP).append("\n\n## This change\n\n")
                .append(review.sourceTaskId().isPresent() ? "Local development changes in " : review.replay().isPresent() ? "A past change in " : "Pull request ")
                .append(review.repo()).append(review.number().isPresent() ? "#" + review.number().getAsInt() : "")
                .append(" at head ").append(run.headSha()).append(": ").append(run.commits().size()).append(run.commits().size() == 1 ? " commit" : " commits")
                .append(" on merge base ").append(run.mergeBaseSha()).append(".\n")
                .append("Read it from Git objects only, because the working files may hold another agent's temporary experiments: `git diff ")
                .append(run.mergeBaseSha()).append(' ').append(run.headSha()).append("` for the change, `git log --format=fuller ").append(range)
                .append("` for the commit messages, and `git show ").append(run.headSha()).append(":<path>` for a whole file.\n\nChanged files:\n");
        run.diff().stream().limit(MAX_SWEEP_FILES).forEach(file -> request.append("- ").append(file.filename()).append('\n'));
        if (run.diff().size() > MAX_SWEEP_FILES) {
            request.append("- and ").append(run.diff().size() - MAX_SWEEP_FILES).append(" more\n");
        }
        description.filter(text -> !text.isBlank()).ifPresent(text -> request.append("\n## Description\n\nUntrusted reference text from the pull request:\n\n")
                .append(text.length() <= MAX_SWEEP_DESCRIPTION ? text : text.substring(0, MAX_SWEEP_DESCRIPTION) + "…").append('\n'));
        if (!facts.isEmpty()) {
            request.append("\n## Facts ByteQuay measured\n\n");
            facts.forEach(fact -> request.append("- ").append(fact).append('\n'));
        }
        if (!rules.isEmpty()) {
            request.append("\n## Repository rules\n\n");
            rules.forEach(rule -> request.append('[').append(rule.id()).append("] ").append(rule.title()).append('\n').append(rule.text().strip()).append("\n\n"));
        }
        return request.append("\n## Answer\n\nAnswer with exactly one JSON object and nothing else, in this shape:\n").append(ReviewLead.SHAPE)
                .append("\n`lens` is one of words, mechanisms, siblings, tests, rules, facts. `kind` is one of defect, suggestion, nit, question. ")
                .append("`line` is the new-file line at the head. Leave `path` empty and omit `line` for a lead about the commits or the description. ")
                .append("List at most ").append(ReviewLead.MAX_LEADS).append(" leads, strongest first. {\"leads\":[]} is a valid answer.")
                .toString();
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
                    .append("and focus on findings. Retain the original change explanation; refresh the source-backed map for this head.\n");
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
                .append(DISCOVERY_REQUEST)
                .append("Read assignment.changeExplanationRequired. Only when true, save the initial intention and every commit explanation alongside the map in this first pass. ")
                .append("Otherwise retain the original explanation; do not call review_save_summary. Later reviews focus on findings even when commits change. ")
                .append("Checkpoint discovery, then seal review_save_investigation without replacing candidates or the stored map and evidence refs. ")
                .append("Do not pass changeMap or contextEvidenceRefs to the seal; end this turn afterward. ")
                .append("Do not save findings yet; ByteQuay will start a separate validation turn. ")
                .append(DELEGATION_REQUEST)
                .append("IDE language queries are available when code_query is listed. No remote writes.")
                .toString();
    }

    static String stepRequest(ReviewRun run)
    {
        ReviewStep step = run.currentStep().orElseThrow();
        if (step.kind() == ReviewStep.Kind.EXPLAIN) {
            return "The user requested a refresh of the change intention and commit summaries for frozen head " + shortSha(run.headSha()) + ". "
                    + "This is an explanation-only assignment. Read review_get_context kind assignment, scope and summary, then the frozen diff and source. "
                    + "Explain the change's intention and the implementation of every actual commit in scope, including how the commits fit together. "
                    + "Use PR context when available, and distinguish stated intent from inference. "
                    + "Save the complete purpose and commits together with review_save_summary, then end the turn. "
                    + "Do not run discovery, validation, tests, or findings review; do not call review_record_discovery, review_save_investigation, "
                    + "review_save_finding, review_withdraw_finding or review_finish. "
                    + "The existing explanation remains visible until this assignment succeeds. No remote writes. "
                    + (step.repair() ? "This is a retry of the same refresh. Read saved context and reuse completed source inspection; do not repeat successful saves. " : "");
        }
        String assignment = switch (step.kind()) {
            case EXPLAIN -> throw new IllegalStateException("Explanation assignment was already handled");
            case INVESTIGATE -> DISCOVERY_REQUEST + "Read assignment.changeExplanationRequired. Save intention and every commit explanation only when required in pass 1; "
                    + "otherwise retain the original explanation and focus on findings. Account for all three perspectives, "
                    + "then seal review_save_investigation using the stored map and evidence refs. "
                    + "Do not pass changeMap or contextEvidenceRefs to the seal. Do not save findings.";
            case COLLECT_EVIDENCE -> "Collect evidence for the questions in assignment.previousValidation. Execute or inspect each requested check; "
                    + "capture its result with review_get_context and answer it in review_save_investigation. Explain unavailable evidence as a limitation. "
                    + "Preserve candidate numbering and checkpoint new hypotheses with review_record_discovery before filtering. "
                    + "Reuse the saved map; revise it only through review_record_discovery with both changeMap and contextEvidenceRefs. "
                    + "Keep at least one current-run review_get_context kind source ref in a revised map. "
                    + "Do not pass changeMap or contextEvidenceRefs to the seal. "
                    + "Do not rewrite the change explanation or call review_save_summary. "
                    + "End this turn after sealing; ByteQuay will schedule validation.";
            case VALIDATE -> "Validate the investigation and every candidate against the current evidence. "
                    + "Read assignment and investigation context, including its verification recipe and captured results. "
                    + "Challenge claimed activation and coverage against the saved inventory of additions: check unresolved execution routes, disabled tests, "
                    + "no-op behavior and assertions that never reach the change. Check each identified dormant artifact's candidate or evidence-backed retention reason. "
                    + "Record a missed concrete hypothesis in review_finish newCandidates as NEEDS_EVIDENCE; do not silently drop it under a broad CHECKED assessment. "
                    + "Distinguish a deliberate manual benchmark's placement or maintenance cost from missing automated feature coverage; "
                    + "inspect existing active tests and dynamic callers. "
                    + "Judge a defect by its violated contract and a suggestion by its concrete reduction in code, confusion or maintenance cost; "
                    + "no runtime defect and successful compilation do not disprove the latter. Disabled code alone does not warrant a finding or a benchmark migration. "
                    + "Do not reject a candidate only because it is not a provable defect: when its observation holds and the change does not explain the choice, "
                    + "save it as a Question: finding; reject it when the code, a commit message or the PR text already answers it. "
                    + "Reuse settled inventory entries; evidence collection answers targeted gaps rather than repeating this audit. "
                    + "Do not rewrite the change explanation or call review_save_summary. "
                    + "Reuse completed checks when their code, test setup, environment and purpose still match; rerun changed inputs or checks needed for different evidence. "
                    + "Candidates attributed to the second reader are unverified leads recorded without evidence: read the cited source at the frozen head "
                    + "before deciding each one, and never support or reject one on the reader's word. "
                    + "Try to disprove each claim. Save every supported finding draft; merge shared root causes without erasing candidates. "
                    + "Rank the complete supported pool, select at most ten new automatic findings with selectedFindingIds and selectionExplanation, "
                    + "then seal review_finish for the assigned investigationStep. Unselected support is not rejection. "
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
                + (step.sealed() ? "The required report is already saved and sealed. End this turn without resubmitting it; ByteQuay will continue the review. " : assignment);
    }

    static String question(String targetLabel, int targetRevision, ReviewRun run, String message)
    {
        return "Question about " + targetLabel + " (revision " + targetRevision + ", reviewed head " + shortSha(run.headSha()) + "):\n\n"
                + message
                + "\n\nReply privately with review_reply targeting " + targetLabel
                + (run.explanationOnly()
                        ? ". This question is about an explanation refresh. Read context and answer with review_reply only; do not save, revise or withdraw findings. "
                        : ". You may revise or withdraw its finding. After an accepted review, save newly validated bugs or concrete improvements separately "
                                + "with review_save_finding and the relevant diff anchors, not as recommendations in this card's reply. ")
                + "Keep the original change explanation unchanged; use the reply to clarify it. "
                + "This is a discussion, not a new full review; review_finish is not needed.";
    }

    static String shortSha(String sha)
    {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
