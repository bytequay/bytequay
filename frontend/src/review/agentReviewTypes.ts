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
import type { AgentRunDto } from '../types/brainView';
import type { LocalPRComment, LocalPRTimelineEvent } from '../types/localPr';

export type CriterionKind = 'hard-invariant' | 'engineering-principle' | 'repo-convention';
export type EvidenceStrength = 'E0' | 'E1' | 'E2' | 'E3' | 'E4' | 'E5';
export type ConfidenceClass =
  | 'TENTATIVE'
  | 'SUPPORTED'
  | 'STRONGLY_SUPPORTED'
  | 'VERIFIED'
  | 'UNKNOWN'
  | 'REJECTED';
export type VerificationStatus = 'verified' | 'partially' | 'unknown' | 'rejected';

export type AgentReviewRow = {
  id: string;
  repo_id: string;
  pr_id: string;
  base_commit: string;
  reviewed_head_commit: string;
  status: string;
  workspace_id: string | null;
  owner_thread_id: string | null;
  owner_task_id: string | null;
};

export type ReviewRoundRow = {
  id: string;
  review_id: string;
  agent_run_id: string;
  trigger: string;
  scope: string;
  start_commit: string;
  end_commit: string | null;
  status: 'COMPLETED' | 'COMPLETED_WITH_QUESTIONS' | 'ERRORED' | 'CANCELLED' | 'RUNNING';
  budget_json: { cost_cap_cents: number; wall_clock_minutes: number };
  cost_cents: number;
  capabilities_json: ReviewCapabilities;
  trigger_stage_id: string | null;
};

export type ReviewCapabilities = {
  source_mode: 'local-source' | 'remote-only';
  available: string[];
  unavailable: string[];
};

export type CriterionRow = {
  id: string;
  repo_id?: string;
  kind: CriterionKind;
  statement: string;
  source_type: string;
  source_ref?: string;
};

export type ReviewObjectiveRow = {
  id: string;
  round_id: string;
  criterion_id: string;
  statement: string;
  source: string;
  applicability_status: string;
  resolution_status: string;
};

export type ReviewAssignmentRow = {
  id: string;
  round_id: string;
  reviewer_def_id: string;
  runner: 'api' | 'cli';
  status: string;
  understanding_summary: string;
  assumptions_json: string[];
  unknowns_json: string[];
  budget_json: { hypotheses: number; active_hypotheses: number; steps: number; findings: number };
};

export type HypothesisRow = {
  id: string;
  assignment_id: string;
  objective_id?: string;
  claim: string;
  origin: string;
  status: string;
  confidence_class: ConfidenceClass;
};

export type InvestigationStepRow = {
  id: string;
  assignment_id: string;
  hypothesis_id?: string;
  action_type: string;
  arguments_json: Record<string, unknown>;
  reason: string;
  planned: boolean;
  cost_cents: number;
  status: string;
};

export type ObservationRow = {
  id: string;
  step_id: string;
  source_type: string;
  commit_sha: string;
  path?: string;
  start_line?: number;
  end_line?: number;
  symbol?: string;
  command?: string;
  exit_code?: number;
  artifact_ref?: string;
  content_digest: string;
  preview: string;
};

export type FindingRow = {
  id: string;
  review_id: string;
  round_id: string;
  objective_id: string;
  hypothesis_id?: string;
  criterion_kind: CriterionKind;
  claim: string;
  severity: 1 | 2 | 3 | 4 | 5;
  confidence_class: ConfidenceClass;
  verification_status: VerificationStatus;
  requested_action: string;
  lifecycle_status: string;
  last_checked_commit: string;
};

export type FindingEvidenceRow = {
  finding_id: string;
  observation_id: string;
  relation: 'SUPPORTS' | 'REFUTES';
  proposition: string;
  strength_class: EvidenceStrength;
  strength_reason: string;
  dependency_mode: 'DIRECT_ONLY' | 'SYMBOL_BODY' | 'CALLER_SET' | 'MODULE_CONTRACT';
  dependency_json: Record<string, unknown>;
};

export type FindingVerificationRow = {
  id: string;
  finding_id: string;
  verifier_run_id: string;
  evidence_accurate: boolean;
  claim_scope_accurate: boolean;
  severity_accurate: boolean;
  counter_evidence_json: string[];
  status: VerificationStatus;
  confidence_class: ConfidenceClass;
  explanation: string;
};

export type FindingRelationRow = {
  source_finding_id: string;
  target_finding_id: string;
  relation: 'DUPLICATES' | 'CONTRADICTS' | 'RELATED_ROOT_CAUSE' | 'ADDRESSES';
};

export type ReviewOutcomeRow = {
  finding_id: string;
  user_disposition: 'published' | 'edited' | 'dismissed' | 'deferred';
  author_response: 'fixed' | 'disputed' | 'acknowledged' | 'ignored';
  epistemic_resolution: 'confirmed' | 'refuted' | 'unresolved';
  utility_assessment: string;
  style_edit_magnitude: number;
};

export type KnowledgeItemRow = {
  id: string;
  repo_id: string;
  subtype: 'concern' | 'recipe' | 'convention' | 'invariant' | 'principle' | 'rationale';
  statement: string;
  steps_json?: string[];
  trigger_json: Record<string, unknown>;
  state: 'pending' | 'active' | 'decayed' | 'retired';
};

export type KnowledgeProvenanceRow = {
  knowledge_item_id: string;
  source_kind: string;
  source_ref: string;
};

export type ActivityFactRow = {
  kind: 'hunks-inspected' | 'public-symbols-traced' | 'deletions-evaluated'
    | 'applicable-classes-resolved' | 'objectives-resolved' | 'tests-inspected' | 'budget-gaps';
  count: number;
  detail: string;
};

/** Existing run rows plus the review-round FK the implemented AgentRun DTO
 * does not yet carry directly. Status remains the landed AgentRun status set. */
export type PanelReviewRunRow = Omit<AgentRunDto, 'taskId' | 'stageId' | 'reviewRoundId'> & {
  taskId: string | null;
  stageId: string | null;
  reviewRoundId: string | null;
  metricsJson?: string | null;
};

export type AgentReviewData = {
  review: AgentReviewRow;
  rounds: ReviewRoundRow[];
  runs: PanelReviewRunRow[];
  criteria: CriterionRow[];
  objectives: ReviewObjectiveRow[];
  assignments: ReviewAssignmentRow[];
  hypotheses: HypothesisRow[];
  steps: InvestigationStepRow[];
  observations: ObservationRow[];
  findings: FindingRow[];
  evidence: FindingEvidenceRow[];
  verifications: FindingVerificationRow[];
  relations: FindingRelationRow[];
  outcomes: ReviewOutcomeRow[];
  knowledge_items: KnowledgeItemRow[];
  knowledge_provenance: KnowledgeProvenanceRow[];
  activity_facts: ActivityFactRow[];
  pr_comments: LocalPRComment[];
  pr_timeline_events: LocalPRTimelineEvent[];
};

/** Failure-class ledger rows are audit data, not user-facing plan objectives. */
export function roundPlanObjectives(data: AgentReviewData, roundId: string): ReviewObjectiveRow[] {
  return data.objectives.filter(objective => {
    if (objective.round_id !== roundId) return false;
    const criterion = data.criteria.find(row => row.id === objective.criterion_id);
    return criterion?.source_type !== 'failure-class';
  });
}

const EVIDENCE_CEILING: Record<EvidenceStrength, number> = {
  E0: 0,
  E1: 0.45,
  E2: 0.60,
  E3: 0.75,
  E4: 0.90,
  E5: 0.90,
};

/** Deterministic v6 ceiling, displayed as a rubric aid rather than a
 * calibrated probability. */
export function confidenceCeiling(
  strength: EvidenceStrength,
  verification: VerificationStatus,
  criterionKind: CriterionKind,
): number {
  if (verification === 'rejected') return 0;
  const verified = verification === 'verified' ? 0.98 : EVIDENCE_CEILING[strength];
  const statusCap = verification === 'unknown' ? Math.min(verified, 0.50) : verified;
  const kindPenalty = criterionKind === 'hard-invariant' ? 0 : 0.10;
  return Math.max(0, Number((statusCap - kindPenalty).toFixed(2)));
}

export function findingComment(data: AgentReviewData, findingId: string): LocalPRComment | undefined {
  return data.pr_comments.find(comment => comment.findingId === findingId);
}

/** Integer cents rendered consistently across the agent-review surfaces. */
export function formatCents(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}
