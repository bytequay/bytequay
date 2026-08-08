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
import { parseUnifiedDiff } from '../diffParse';
import type { DiffFileDto } from '../types';
import type { LocalPRBundle, LocalPRComment, LocalPRTimelineEvent } from '../types/localPr';
import type {
  AgentReviewData, FindingEvidenceRow, FindingVerificationRow, VerificationStatus,
} from './agentReviewTypes';

// Test-only builders. Runtime review surfaces load the persisted backend aggregate.
export function createAgentReviewFixture(bundle: LocalPRBundle, files: DiffFileDto[] | null): AgentReviewData {
  const now = Date.now();
  const head = bundle.commits.at(-1)?.sha ?? 'fixture-head';
  const base = bundle.commits.at(0)?.sha ?? 'fixture-base';
  const anchor = findAnchor(files);
  const second = findAnchor(files, 1) ?? anchor;
  const reviewId = `fixture-review-${bundle.pr.id}`;
  const roundId = `${reviewId}-round-1`;
  const observationId = `${reviewId}-observation-1`;
  const counterObservationId = `${reviewId}-observation-2`;

  const comments: LocalPRComment[] = [
    findingComment('fixture-comment-1', 'finding-1', bundle.pr.id, anchor.path, anchor.line, now - 180_000,
      'This return path now treats a missing value as success. Could we preserve the prior failure behavior and add a regression test?', 'agent-reviewer'),
    findingComment('fixture-comment-2', 'finding-2', bundle.pr.id, second.path, second.line, now - 120_000,
      'The public behavior is not clear from the current contract. Is the lenient result intentional for this case?', 'verifier'),
  ];

  const timeline = (id: string, age: number, payload: Record<string, unknown>): LocalPRTimelineEvent => ({
    id, localPrId: bundle.pr.id, eventType: 'review', actor: 'agent-reviewer', isLocalOnly: true,
    strippedOnPushAt: null, createdAt: now - age, payload,
  });
  const timelineEvents: LocalPRTimelineEvent[] = [
    timeline('fixture-review-started', 600_000, { reviewEvent: 'started', reviewId: reviewId, roundId }),
    timeline('fixture-rejected-dropped', 245_000, { reviewEvent: 'rejected-dropped', findingId: 'finding-3' }),
    timeline('fixture-review-complete', 240_000, { reviewEvent: 'round-complete', reviewId: reviewId, roundId }),
    timeline('fixture-author-reply', 210_000, { reviewEvent: 'author-reply', findingId: 'finding-1', body: 'The strict behavior is intended for API callers.' }),
    timeline('fixture-addresses', 180_000, { reviewEvent: 'addresses', findingId: 'finding-1', sha: head.slice(0, 7) }),
    timeline('fixture-synthesizer', 150_000, { reviewEvent: 'synthesizer', count: 1 }),
  ];

  const evidence: FindingEvidenceRow[] = [
    {
      finding_id: 'finding-1', observation_id: observationId, relation: 'SUPPORTS',
      proposition: 'The changed return path converts the missing case into an empty result.', strength_class: 'E2',
      strength_reason: 'The changed symbol and its direct caller agree on the new behavior.', dependency_mode: 'SYMBOL_BODY',
      dependency_json: { symbols: ['changedSymbol'] },
    },
    {
      finding_id: 'finding-1', observation_id: counterObservationId, relation: 'REFUTES',
      proposition: 'An earlier guard narrows the reachable window but does not close it.', strength_class: 'E1',
      strength_reason: 'Local code interpretation only.', dependency_mode: 'DIRECT_ONLY', dependency_json: {},
    },
    {
      finding_id: 'finding-2', observation_id: `${reviewId}-observation-3`, relation: 'SUPPORTS',
      proposition: 'No authoritative contract states whether the missing case should be lenient.', strength_class: 'E3',
      strength_reason: 'Repository contract and tests were searched.', dependency_mode: 'MODULE_CONTRACT',
      dependency_json: { contract_keys: ['missing-value-semantics'] },
    },
    {
      finding_id: 'finding-3', observation_id: counterObservationId, relation: 'SUPPORTS',
      proposition: 'The alleged null path is already guarded.', strength_class: 'E1',
      strength_reason: 'The cited source directly contains the guard.', dependency_mode: 'DIRECT_ONLY', dependency_json: {},
    },
  ];

  const verifications: FindingVerificationRow[] = [
    {
      id: 'verification-1', finding_id: 'finding-1', verifier_run_id: 'fixture-verifier-run',
      evidence_accurate: true, claim_scope_accurate: true, severity_accurate: true,
      counter_evidence_json: ['Earlier guard narrows but does not eliminate the path.'],
      status: 'verified', confidence_class: 'VERIFIED', explanation: 'Independent reconstruction reproduced the behavior change.',
    },
    {
      id: 'verification-2', finding_id: 'finding-2', verifier_run_id: 'fixture-verifier-run',
      evidence_accurate: true, claim_scope_accurate: true, severity_accurate: true,
      counter_evidence_json: [], status: 'unknown', confidence_class: 'UNKNOWN',
      explanation: 'The repository does not contain enough information; ask the author.',
    },
    {
      id: 'verification-3', finding_id: 'finding-3', verifier_run_id: 'fixture-verifier-run',
      evidence_accurate: false, claim_scope_accurate: false, severity_accurate: false,
      counter_evidence_json: ['The input is guarded before construction.'], status: 'rejected',
      confidence_class: 'REJECTED', explanation: 'The cited span contradicts the proposed finding.',
    },
  ];

  return {
    review: {
      id: reviewId,
      repo_id: bundle.pr.repo ?? 'fixture/repo',
      pr_id: bundle.pr.id,
      base_commit: base,
      reviewed_head_commit: head,
      status: 'ACTIVE',
      workspace_id: 'ws-default',
      owner_thread_id: 'review-thread-1',
      owner_task_id: null,
    },
    rounds: [{
      id: roundId, review_id: reviewId, agent_run_id: 'fixture-panel-run', trigger: 'initial', scope: 'full',
      start_commit: head, end_commit: head, status: 'COMPLETED_WITH_QUESTIONS',
      budget_json: { cost_cap_cents: 50, wall_clock_minutes: 10 }, cost_cents: 19,
      capabilities_json: {
        source_mode: 'remote-only',
        available: ['pr_diff', 'file_blobs', 'commits', 'checks'],
        unavailable: ['repository_callers', 'code_graph', 'local_tests', 'git_history'],
      },
      trigger_stage_id: null,
    }],
    runs: [{
      id: 'fixture-panel-run', taskId: bundle.pr.taskId ?? '', kind: 'panel_review', source: null,
      parentStageId: null, reviewRoundId: roundId, stageId: 'fixture-panel-stage', status: 'succeeded',
      iterations: 1, budget: 50, headline: '2 findings · 1 needs judgement',
      metricsJson: '{"tokensIn":1234,"tokensOut":56}',
      startedAt: new Date(now - 600_000).toISOString(), finishedAt: new Date(now - 240_000).toISOString(),
    }],
    criteria: [
      { id: 'criterion-1', repo_id: bundle.pr.repo ?? undefined, kind: 'hard-invariant', statement: 'Existing failure behavior is preserved.', source_type: 'rule-table' },
      { id: 'criterion-2', repo_id: bundle.pr.repo ?? undefined, kind: 'engineering-principle', statement: 'Public behavior is explicit and tested.', source_type: 'planner' },
    ],
    objectives: [
      { id: 'objective-1', round_id: roundId, criterion_id: 'criterion-1', statement: 'Preserve existing missing-value behavior', source: 'rule', applicability_status: 'applicable', resolution_status: 'finding' },
      { id: 'objective-2', round_id: roundId, criterion_id: 'criterion-2', statement: 'Confirm the new public behavior is intentional', source: 'planner-suggested', applicability_status: 'applicable', resolution_status: 'unknown' },
    ],
    assignments: [{
      id: 'assignment-1', round_id: roundId, reviewer_def_id: 'correctness', runner: 'api', status: 'completed',
      understanding_summary: 'The change alters one return path and its direct callers.', assumptions_json: ['Callers rely on the prior failure signal.'], unknowns_json: [],
      budget_json: { hypotheses: 6, active_hypotheses: 3, steps: 12, findings: 5 },
    }],
    hypotheses: [
      { id: 'hypothesis-1', assignment_id: 'assignment-1', objective_id: 'objective-1', claim: 'The missing case is silently accepted.', origin: 'explorer', status: 'confirmed', confidence_class: 'VERIFIED' },
      { id: 'hypothesis-2', assignment_id: 'assignment-1', objective_id: 'objective-2', claim: 'The lenient result may be intentional.', origin: 'explorer', status: 'unknown', confidence_class: 'UNKNOWN' },
      { id: 'hypothesis-3', assignment_id: 'assignment-1', objective_id: 'objective-1', claim: 'A null input reaches construction.', origin: 'explorer', status: 'rejected', confidence_class: 'REJECTED' },
    ],
    steps: [
      { id: 'step-1', assignment_id: 'assignment-1', hypothesis_id: 'hypothesis-1', action_type: 'readSymbol', arguments_json: { path: anchor.path }, reason: 'Check the changed return behavior.', planned: true, cost_cents: 4, status: 'completed' },
      { id: 'step-2', assignment_id: 'assignment-1', hypothesis_id: 'hypothesis-1', action_type: 'findCallers', arguments_json: { path: anchor.path }, reason: 'Check whether callers restore the prior guard.', planned: true, cost_cents: 6, status: 'completed' },
      { id: 'step-3', assignment_id: 'assignment-1', hypothesis_id: 'hypothesis-2', action_type: 'searchTests', arguments_json: { query: 'missing value' }, reason: 'Find an authoritative expectation.', planned: true, cost_cents: 9, status: 'completed' },
      { id: 'step-4', assignment_id: 'assignment-1', hypothesis_id: 'hypothesis-1', action_type: 'gitHistory', arguments_json: { path: anchor.path }, reason: 'Below the fixed-budget priority cutoff after stronger evidence landed.', planned: true, cost_cents: 0, status: 'skipped' },
    ],
    observations: [
      { id: observationId, step_id: 'step-1', source_type: 'source', commit_sha: head, path: anchor.path, start_line: anchor.line, end_line: anchor.line, symbol: 'changedSymbol', content_digest: 'fixture-digest-1', preview: 'Missing value is converted to an empty result.' },
      { id: counterObservationId, step_id: 'step-2', source_type: 'source', commit_sha: head, path: anchor.path, start_line: Math.max(1, anchor.line - 3), end_line: anchor.line, content_digest: 'fixture-digest-2', preview: 'An earlier guard narrows the path.' },
      { id: `${reviewId}-observation-3`, step_id: 'step-3', source_type: 'test-search', commit_sha: head, path: second.path, command: 'searchTests missing-value', exit_code: 0, content_digest: 'fixture-digest-3', preview: 'No matching contract test found.' },
    ],
    findings: [
      { id: 'finding-1', review_id: reviewId, round_id: roundId, objective_id: 'objective-1', hypothesis_id: 'hypothesis-1', criterion_kind: 'hard-invariant', claim: 'The missing case is now silently accepted.', severity: 4, confidence_class: 'VERIFIED', verification_status: 'verified', requested_action: 'Preserve the prior failure and add a regression test.', lifecycle_status: 'open', last_checked_commit: head },
      { id: 'finding-2', review_id: reviewId, round_id: roundId, objective_id: 'objective-2', hypothesis_id: 'hypothesis-2', criterion_kind: 'engineering-principle', claim: 'The intended missing-value behavior is unclear.', severity: 2, confidence_class: 'UNKNOWN', verification_status: 'unknown', requested_action: 'Clarify the intended contract.', lifecycle_status: 'NEEDS_USER_JUDGEMENT', last_checked_commit: head },
      { id: 'finding-3', review_id: reviewId, round_id: roundId, objective_id: 'objective-1', hypothesis_id: 'hypothesis-3', criterion_kind: 'hard-invariant', claim: 'A null input reaches object construction.', severity: 3, confidence_class: 'REJECTED', verification_status: 'rejected', requested_action: 'Add a redundant null guard.', lifecycle_status: 'dropped', last_checked_commit: head },
    ],
    evidence, verifications, relations: [], outcomes: [],
    knowledge_items: [{ id: 'knowledge-1', repo_id: bundle.pr.repo ?? 'fixture/repo', subtype: 'recipe', statement: 'Check missing-value behavior at public return paths.', steps_json: ['Read the changed symbol', 'Inspect direct callers', 'Search contract tests'], trigger_json: { paths: [anchor.path] }, state: 'pending' }],
    knowledge_provenance: [{ knowledge_item_id: 'knowledge-1', source_kind: 'finding', source_ref: 'finding-1' }],
    activity_facts: [
      { kind: 'hunks-inspected', count: 3, detail: 'Completed bounded diff reads' },
      { kind: 'objectives-resolved', count: 2, detail: '2 of 2' },
      { kind: 'budget-gaps', count: 0, detail: 'Assignments that exhausted their step cap' },
    ],
    round_messages: [],
    reviewed_commits: bundle.commits.map((commit, position) => ({
      round_id: roundId,
      sha: commit.sha,
      message: commit.message,
      position,
    })),
    pr_comments: comments, pr_timeline_events: timelineEvents,
  };
}

function findingComment(
  id: string, findingId: string, localPrId: string, filePath: string, lineNumber: number,
  createdAt: number, body: string, author: string,
): LocalPRComment {
  return {
    id, localPrId, origin: 'local', scope: 'file-line', filePath, lineNumber, side: 'RIGHT', startLine: null,
    startSide: null, author, body, createdAt, resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
    parentCommentId: null, publishedAt: null, findingId,
  };
}

function findAnchor(files: DiffFileDto[] | null, index = 0): { path: string; line: number } {
  const file = files?.[index] ?? files?.[0];
  if (file === undefined) return { path: 'src/ChangedFile.ts', line: 1 };
  const line = parseUnifiedDiff(file.patch).flatMap(hunk => hunk.rows)
    .find(row => row.newLine !== null)?.newLine ?? 1;
  return { path: file.filename, line };
}

export function createVerificationStateFixture(data: AgentReviewData, status: VerificationStatus): AgentReviewData {
  const confidence = status === 'rejected' ? 'REJECTED' : status === 'unknown' ? 'UNKNOWN' : status === 'verified' ? 'VERIFIED' : 'SUPPORTED';
  return {
    ...data,
    findings: data.findings.map((finding, index) => index === 0 ? { ...finding, verification_status: status, confidence_class: confidence } : finding),
    verifications: data.verifications.map((verification, index) => index === 0 ? { ...verification, status, confidence_class: confidence } : verification),
  };
}
