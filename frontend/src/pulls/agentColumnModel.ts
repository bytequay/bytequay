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
import type {
  AgentReviewData, FindingRow, InvestigationStepRow, ReviewAssignmentRow,
  ReviewObjectiveRow, ReviewRoundRow,
} from '../review/agentReviewTypes';
import { formatCents, roundPlanObjectives } from '../review/agentReviewTypes';

/**
 * Pure mapping from the AgentReview aggregate to the workspace agent
 * column's template rows (docs/mockups/design/pr-redesign/Workspace
 * PRs.dc.html, agent view). The status vocabulary and episode
 * interpretation mirror review/AgentReviewRoundPage.tsx's roundStatus /
 * RoundSection so both surfaces read the same data the same way.
 */

export type RoundChipTone = 'complete' | 'questions' | 'running' | 'queued' | 'cancelled' | 'errored';
export type RoundChip = { label: string; tone: RoundChipTone };

/** Where "open this review round" navigates — the PR-owned agent column. */
export type AgentReviewNavTarget = {
  threadId: string | null;
  taskId: string | null;
  roundId: string;
  workspaceId: string;
  prId: string;
  repo: string;
  prNumber: number | null;
};

/** Same vocabulary as AgentReviewRoundPage's roundStatus(). */
export function roundChip(round: ReviewRoundRow): RoundChip {
  if (round.status === 'QUEUED') return { label: 'queued', tone: 'queued' };
  if (round.status === 'RUNNING') return round.message_gate_open === false
    ? { label: 'finalizing', tone: 'running' }
    : { label: 'running', tone: 'running' };
  if (round.status === 'CANCELLED') return { label: 'stopped', tone: 'cancelled' };
  if (round.status === 'ERRORED') return { label: 'errored', tone: 'errored' };
  if (round.status === 'COMPLETED_WITH_QUESTIONS') return { label: 'questions remain', tone: 'questions' };
  return { label: 'complete', tone: 'complete' };
}

export function roundIsLive(round: ReviewRoundRow): boolean {
  return round.status === 'QUEUED' || round.status === 'RUNNING';
}

/** Accepted/rejected split — same computation as RoundSection. */
export function roundStats(data: AgentReviewData, roundId: string): { findings: number; rejected: number } {
  const findings = data.findings.filter(row => row.round_id === roundId);
  const accepted = findings.filter(row =>
    row.verification_status !== 'rejected' && row.lifecycle_status !== 'dropped').length;
  return { findings: accepted, rejected: findings.length - accepted };
}

/** "6be742d" or "6be742d … 9ab01f2"; null when nothing was recorded. */
export function reviewedShas(data: AgentReviewData, round: ReviewRoundRow): { text: string; count: number } | null {
  const recorded = (data.reviewed_commits ?? []).filter(row => row.round_id === round.id)
    .sort((a, b) => a.position - b.position);
  const first = recorded[0];
  const last = recorded.at(-1);
  if (recorded.length > 1 && first !== undefined && last !== undefined) {
    return { text: `${first.sha.slice(0, 7)} … ${last.sha.slice(0, 7)}`, count: recorded.length };
  }
  const sha = recorded[0]?.sha ?? round.end_commit ?? round.start_commit;
  return sha.length === 0 ? null : { text: sha.slice(0, 7), count: 1 };
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi;
const ASSIGNMENT_SUMMARY_FALLBACK = 'Reviewing the changed code against the assigned objectives.';

/** First readable sentence of the reviewer's understanding summary —
 * the same internal-marker filtering AgentReviewRoundPage applies, with
 * a single static fallback. */
export function assignmentSummary(summary: string): string {
  const compact = summary.replaceAll(/\s+/g, ' ').trim();
  const internal = compact.includes('<｜｜DSML｜｜')
    || compact.includes('<tool_call>')
    || /(?:re-?recording|record)_assignment|objective ids?/i.test(compact)
    || (compact.match(UUID_PATTERN)?.length ?? 0) > 1;
  if (internal || compact.length === 0) return ASSIGNMENT_SUMMARY_FALLBACK;
  const firstSentence = compact.match(/^.*?[.!?](?:\s|$)/)?.[0]?.trim() ?? compact;
  return firstSentence.length <= 240 ? firstSentence : `${firstSentence.slice(0, 237).trimEnd()}…`;
}

function stepVerb(actionType: string): string {
  const action = actionType.toLowerCase();
  if (action.includes('read')) return 'read files';
  if (action.includes('search') || action.includes('grep')) return 'search code';
  if (action.includes('trace')) return 'trace';
  if (action.includes('test') || action.includes('check')) return 'check';
  return (actionType.split(':').at(-1) ?? actionType).replaceAll(/[_-]+/g, ' ').toLowerCase();
}

function traceVerb(actionType: string): string {
  const action = actionType.toLowerCase();
  if (action.includes('line_scan')) return 'Line-scanned';
  if (action.includes('read')) return 'Read';
  if (action.includes('search') || action.includes('grep')) return 'Searched';
  if (action.includes('trace')) return 'Tracing';
  if (action.includes('diff')) return 'Diffed';
  if (action.includes('test') || action.includes('check')) return 'Checked';
  if (action.includes('inspect')) return 'Inspected';
  const text = (actionType.split(':').at(-1) ?? actionType).replaceAll(/[_-]+/g, ' ').trim();
  return text.length === 0 ? 'Reviewed' : `${text[0].toUpperCase()}${text.slice(1)}`;
}

function conversationSummary(summary: string): string {
  const compact = summary.replaceAll(/\s+/g, ' ').trim();
  const safe = assignmentSummary(summary);
  if (safe === ASSIGNMENT_SUMMARY_FALLBACK) return safe;
  return compact.length <= 480 ? compact : `${compact.slice(0, 477).trimEnd()}…`;
}

/** "read files + search code" — the collapsed work sub-row's label. */
export function workLabel(steps: InvestigationStepRow[]): string {
  const labels = [...new Set(steps.map(step => stepVerb(step.action_type)))];
  return labels.slice(0, 2).join(' + ') || 'review work';
}

export type ConversationProgressState = 'done' | 'running' | 'queued';
export type ConversationAccent = 'blue' | 'amber' | 'purple';
export type ConversationFindingChipTone = 'neutral' | 'success' | 'question';

export type ConversationFindingChip = {
  label: string;
  tone: ConversationFindingChipTone;
};

export type ConversationFindingCounts = {
  total: number;
  accepted: number;
  blocking: number;
  kept: number;
  refuted: number;
  questions: number;
  resolved: number;
  acceptedLabels: string[];
  blockingLabels: string[];
  keptLabels: string[];
  refutedLabels: string[];
  questionLabels: string[];
  resolvedLabels: string[];
};

export type ConversationTraceRow = {
  id: string;
  action: string;
  target: string;
  post: string;
  reason: string;
  status: string;
  live: boolean;
};

export type ConversationInvestigator = {
  assignmentId: string;
  number: number;
  accent: ConversationAccent;
  objectiveId: string | null;
  objectiveTitle: string;
  agent: string;
  scope: ReviewAssignmentRow['runner'];
  state: ConversationProgressState;
  statusLabel: string;
  stepCount: number;
  costCents: number;
  costLabel: string;
  foldLabel: string | null;
  traceOpen: boolean;
  trace: ConversationTraceRow[];
  summary: string;
  timeLabel: string;
  findings: ConversationFindingCounts;
  chips: ConversationFindingChip[];
};

export type ConversationObjective = {
  id: string;
  title: string;
  resolution: string;
  state: ConversationProgressState;
};

export type AgentConversationModel = {
  roundId: string;
  roundNumber: number;
  status: RoundChip;
  running: boolean;
  finished: boolean;
  scope: string;
  objectives: ConversationObjective[];
  learnedObjectives: string[];
  doneObjectives: number;
  investigators: ConversationInvestigator[];
  doneInvestigators: number;
  activeInvestigatorNumber: number | null;
  totals: {
    steps: number;
    stepCostCents: number;
    costCents: number;
    budgetCapCents: number;
    findings: ConversationFindingCounts;
  };
  findingChips: ConversationFindingChip[];
  reviewedCommits: { text: string; count: number } | null;
  durationSeconds: number | null;
  durationLabel: string | null;
  dateLabel: string;
};

const ACCENTS: ConversationAccent[] = ['blue', 'amber', 'purple'];
const QUESTION_LIFECYCLES = new Set(['NEEDS_USER_JUDGEMENT', 'NEEDS_AUTHOR_INPUT']);

function investigatorState(round: ReviewRoundRow, assignment: ReviewAssignmentRow): ConversationProgressState {
  if (round.status === 'COMPLETED' || round.status === 'COMPLETED_WITH_QUESTIONS') return 'done';
  const status = assignment.status.toLowerCase();
  if (status === 'completed' || status === 'succeeded' || status === 'done') return 'done';
  if (status === 'running' || status === 'active' || status === 'processing') return 'running';
  return 'queued';
}

function stepTarget(argumentsJson: Record<string, unknown> | null | undefined): string {
  if (argumentsJson == null) return 'review context';
  const preferred = ['path', 'file', 'files', 'target', 'symbol', 'command']
    .flatMap(key => key in argumentsJson ? [argumentsJson[key]] : []);
  const source = preferred.length > 0 ? preferred : Object.entries(argumentsJson)
    .filter(([key]) => key !== 'post')
    .map(([, value]) => value);
  const values = source.flatMap(value => {
    if (typeof value === 'string' || typeof value === 'number') return [String(value)];
    if (Array.isArray(value)) {
      const items = value.filter(item => typeof item === 'string' || typeof item === 'number').map(String);
      return items.length === 0 ? [] : [items.join(', ')];
    }
    return [];
  });
  return values.join(' · ') || 'review context';
}

function stepPost(argumentsJson: Record<string, unknown> | null | undefined): string {
  return typeof argumentsJson?.post === 'string' ? argumentsJson.post : '';
}

function representativeSteps(steps: InvestigationStepRow[]): InvestigationStepRow[] {
  if (steps.length <= 3) return steps;
  return [steps[0], steps[1], steps[steps.length - 1]];
}

function findingCounts(
  data: AgentReviewData,
  findings: FindingRow[],
): ConversationFindingCounts {
  const labels = new Map(data.findings.map((finding, index) => [finding.id, `F${index + 1}`]));
  const outcomes = new Map(data.outcomes.map(outcome => [outcome.finding_id, outcome]));
  const refuted = findings.filter(finding =>
    finding.verification_status === 'rejected' || finding.lifecycle_status === 'dropped');
  const accepted = findings.filter(finding => !refuted.includes(finding));
  const questions = accepted.filter(finding => finding.verification_status === 'unknown'
    || QUESTION_LIFECYCLES.has(finding.lifecycle_status.toUpperCase()));
  const resolved = accepted.filter(finding => {
    const outcome = outcomes.get(finding.id);
    return outcome?.author_response === 'fixed' && outcome.epistemic_resolution === 'confirmed';
  });
  const questionIds = new Set(questions.map(finding => finding.id));
  const resolvedIds = new Set(resolved.map(finding => finding.id));
  const kept = accepted.filter(finding => !questionIds.has(finding.id) && !resolvedIds.has(finding.id));
  const blocking = accepted.filter(finding => finding.severity >= 4);
  const findingLabels = (rows: FindingRow[]) => rows.map(row => labels.get(row.id) ?? row.id);
  return {
    total: findings.length,
    accepted: accepted.length,
    blocking: blocking.length,
    kept: kept.length,
    refuted: refuted.length,
    questions: questions.length,
    resolved: resolved.length,
    acceptedLabels: findingLabels(accepted),
    blockingLabels: findingLabels(blocking),
    keptLabels: findingLabels(kept),
    refutedLabels: findingLabels(refuted),
    questionLabels: findingLabels(questions),
    resolvedLabels: findingLabels(resolved),
  };
}

function findingChips(counts: ConversationFindingCounts): ConversationFindingChip[] {
  if (counts.total === 0) return [{ label: 'no findings', tone: 'success' }];
  const chips: ConversationFindingChip[] = [];
  if (counts.resolved > 0) chips.push({ label: `${counts.resolvedLabels.join(' ')} resolved`, tone: 'success' });
  if (counts.kept > 0) chips.push({ label: `kept ${counts.keptLabels.join(' ')}`, tone: 'neutral' });
  if (counts.refuted > 0) chips.push({ label: `refuted ${counts.refuted}`, tone: 'success' });
  if (counts.questions > 0) {
    chips.push({
      label: `${counts.questions} ${counts.questions === 1 ? 'question' : 'questions'} → author`,
      tone: 'question',
    });
  }
  return chips;
}

function statusLabel(state: ConversationProgressState, counts: ConversationFindingCounts, steps: number): string {
  if (state === 'running') return `investigating · ${steps} steps`;
  if (state === 'queued') return 'queued';
  if (counts.resolved > 0) return `resolved ${counts.resolved}`;
  if (counts.questions > 0) return `${counts.questions} ${counts.questions === 1 ? 'question' : 'questions'}`;
  if (counts.kept === 0 && counts.refuted === 0) return 'no findings';
  return [counts.kept > 0 ? `kept ${counts.kept}` : null, counts.refuted > 0 ? `refuted ${counts.refuted}` : null]
    .filter((part): part is string => part !== null).join(' · ');
}

function runTiming(data: AgentReviewData, round: ReviewRoundRow): {
  durationSeconds: number | null;
  durationLabel: string | null;
  dateLabel: string;
} {
  const run = data.runs.find(row => row.id === round.agent_run_id);
  if (run === undefined) return { durationSeconds: null, durationLabel: null, dateLabel: roundIsLive(round) ? 'now' : '' };
  const started = Date.parse(run.startedAt);
  const finished = run.finishedAt == null ? Date.now() : Date.parse(run.finishedAt);
  const validDuration = Number.isFinite(started) && Number.isFinite(finished) && finished >= started;
  const durationSeconds = validDuration ? Math.round((finished - started) / 1000) : null;
  const durationLabel = durationSeconds === null ? null
    : durationSeconds < 60 ? `${durationSeconds}s`
      : durationSeconds % 60 === 0 ? `${durationSeconds / 60}m`
        : `${Math.floor(durationSeconds / 60)}m ${durationSeconds % 60}s`;
  const dated = run.finishedAt ?? run.startedAt;
  const date = new Date(dated);
  const dateLabel = roundIsLive(round) ? 'now' : Number.isNaN(date.getTime()) ? ''
    : date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
  return { durationSeconds, durationLabel, dateLabel };
}

function objectiveForAssignment(
  data: AgentReviewData,
  objectives: ReviewObjectiveRow[],
  assignment: ReviewAssignmentRow,
  index: number,
): ReviewObjectiveRow | undefined {
  const hypothesisObjectiveIds = new Set(data.hypotheses
    .filter(hypothesis => hypothesis.assignment_id === assignment.id)
    .flatMap(hypothesis => hypothesis.objective_id == null ? [] : [hypothesis.objective_id]));
  return objectives.find(objective => hypothesisObjectiveIds.has(objective.id))
    ?? objectives[index]
    ?? objectives[0];
}

/**
 * The v2 conversation's one-window view model. It deliberately contains no
 * layout values: the component owns the literal design styles, while this
 * mapper keeps live and finished states on the same persisted aggregate.
 */
export function buildConversationModel(data: AgentReviewData, round: ReviewRoundRow): AgentConversationModel {
  const timing = runTiming(data, round);
  const objectives = roundPlanObjectives(data, round.id);
  const guidance = new Set((data.round_messages ?? [])
    .flatMap(message => message.assignment_id == null ? [] : [message.assignment_id]));
  const assignments = data.assignments.filter(assignment =>
    assignment.round_id === round.id && !guidance.has(assignment.id));
  const states = assignments.map(assignment => investigatorState(round, assignment));
  const roundFindings = data.findings.filter(finding => finding.round_id === round.id);
  const hypothesisAssignments = new Map(data.hypotheses.map(hypothesis => [hypothesis.id, hypothesis.assignment_id]));
  const investigators = assignments.map((assignment, index): ConversationInvestigator => {
    const objective = objectiveForAssignment(data, objectives, assignment, index);
    const state = states[index];
    const steps = data.steps.filter(step => step.assignment_id === assignment.id);
    const assignmentFindings = roundFindings.filter(finding => {
      const hypothesisAssignment = finding.hypothesis_id == null
        ? undefined : hypothesisAssignments.get(finding.hypothesis_id);
      return hypothesisAssignment === assignment.id
        || (hypothesisAssignment === undefined && objective !== undefined && finding.objective_id === objective.id);
    });
    const counts = findingCounts(data, assignmentFindings);
    const objectiveTitle = objective?.statement
      ?? assignmentSummary(assignment.understanding_summary)
      ?? `Review objective ${index + 1}`;
    const suppliedSummary = conversationSummary(assignment.understanding_summary);
    const readableSummary = state === 'running'
      ? /^verifying\b/i.test(suppliedSummary) ? suppliedSummary : `Verifying ${objectiveTitle}…`
      : state === 'queued'
        ? /^starts\b/i.test(suppliedSummary)
          ? suppliedSummary
          : `Starts when objective ${Math.max(1, index)} completes — ${objectiveTitle}.`
        : assignment.understanding_summary.trim().length > 0
          ? suppliedSummary
          : 'Review complete.';
    const costCents = steps.reduce((sum, step) => sum + step.cost_cents, 0);
    return {
      assignmentId: assignment.id,
      number: index + 1,
      accent: ACCENTS[index % ACCENTS.length],
      objectiveId: objective?.id ?? null,
      objectiveTitle,
      agent: assignment.reviewer_def_id,
      scope: assignment.runner,
      state,
      statusLabel: statusLabel(state, counts, steps.length),
      stepCount: steps.length,
      costCents,
      costLabel: steps.length === 0 && state === 'queued' ? '—' : formatCents(costCents),
      foldLabel: state === 'queued' ? null
        : `${state === 'running' ? 'Working' : 'Worked'}${timing.durationLabel === null ? '' : `${state === 'running' ? ' — ' : ' for '}${timing.durationLabel}`}`,
      traceOpen: state === 'running',
      trace: representativeSteps(steps).map((step, stepIndex, visibleSteps) => ({
        id: step.id,
        action: traceVerb(step.action_type),
        target: stepTarget(step.arguments_json),
        post: stepPost(step.arguments_json),
        reason: step.reason,
        status: step.status,
        live: state === 'running' && stepIndex === visibleSteps.length - 1,
      })),
      summary: readableSummary,
      timeLabel: state === 'running' ? 'live' : state === 'queued' ? 'queued' : timing.dateLabel,
      findings: counts,
      chips: state === 'done'
        ? [
            ...findingChips(counts),
            ...(roundIsLive(round) && counts.resolved > 0 && counts.kept === 0 && counts.questions === 0
              ? [{ label: 'no new findings', tone: 'neutral' as const }] : []),
            ...(!roundIsLive(round) && counts.questions > 0 && counts.blocking === 0
              ? [{ label: 'no blocking defects', tone: 'neutral' as const }] : []),
          ]
        : [],
    };
  });
  const finished = round.status === 'COMPLETED' || round.status === 'COMPLETED_WITH_QUESTIONS';
  const objectiveRows = objectives.map((objective, index): ConversationObjective => {
    const state = finished || objective.resolution_status !== 'pending'
      ? 'done'
      : states[index] ?? 'queued';
    return { id: objective.id, title: objective.statement, resolution: objective.resolution_status, state };
  });
  const learnedObjectives = objectives
    .filter(objective => objective.source === 'project-intelligence'
      && objective.applicability_status === 'applicable')
    .map(objective => objective.statement);
  const totalFindingCounts = findingCounts(data, roundFindings);
  return {
    roundId: round.id,
    roundNumber: Math.max(1, data.rounds.findIndex(row => row.id === round.id) + 1),
    status: roundChip(round),
    running: roundIsLive(round),
    finished,
    scope: round.scope,
    objectives: objectiveRows,
    learnedObjectives,
    doneObjectives: objectiveRows.filter(objective => objective.state === 'done').length,
    investigators,
    doneInvestigators: investigators.filter(investigator => investigator.state === 'done').length,
    activeInvestigatorNumber: investigators.find(investigator => investigator.state === 'running')?.number ?? null,
    totals: {
      steps: investigators.reduce((sum, investigator) => sum + investigator.stepCount, 0),
      stepCostCents: investigators.reduce((sum, investigator) => sum + investigator.costCents, 0),
      costCents: round.cost_cents,
      budgetCapCents: round.budget_json.cost_cap_cents,
      findings: totalFindingCounts,
    },
    findingChips: findingChips(totalFindingCounts),
    reviewedCommits: reviewedShas(data, round),
    ...timing,
  };
}

export type SpineEntry =
  | {
      kind: 'planning';
      chips: string[];
      scopeLabel: 'Full' | 'Delta';
      objectivesLabel: string;
      /** "general-cli, general-api" — empty when no assignments yet. */
      reviewers: string;
    }
  | {
      kind: 'investigation' | 'verification';
      agent: string;
      chips: string[];
      summary: string;
      sub: { label: string; steps: number } | null;
    };

/** The latest-round timeline: PLANNING, then one entry per assignment,
 * excluding guidance-only assignments — same partition as RoundSection. */
export function buildSpine(data: AgentReviewData, round: ReviewRoundRow): SpineEntry[] {
  const guidance = new Set((data.round_messages ?? [])
    .flatMap(message => message.assignment_id == null ? [] : [message.assignment_id]));
  const assignments = data.assignments.filter(row => row.round_id === round.id && !guidance.has(row.id));
  const investigations = assignments.filter(row => row.reviewer_def_id !== 'independent-verifier');
  const objectives = roundPlanObjectives(data, round.id);
  const objectivesLabel = `${objectives.length} ${objectives.length === 1 ? 'objective' : 'objectives'}`;
  const entries: SpineEntry[] = [{
    kind: 'planning',
    chips: [objectivesLabel, round.scope, 'deterministic'],
    scopeLabel: round.scope === 'full' ? 'Full' : 'Delta',
    objectivesLabel,
    reviewers: investigations.map(row => row.reviewer_def_id).join(', '),
  }];
  for (const assignment of assignments) {
    const steps = data.steps.filter(step => step.assignment_id === assignment.id);
    const cost = steps.reduce((sum, step) => sum + step.cost_cents, 0);
    entries.push({
      kind: assignment.reviewer_def_id === 'independent-verifier' ? 'verification' : 'investigation',
      agent: assignment.reviewer_def_id,
      chips: [formatCents(cost), `${steps.length} steps`, assignment.runner],
      summary: assignmentSummary(assignment.understanding_summary),
      sub: steps.length === 0 ? null : { label: workLabel(steps), steps: steps.length },
    });
  }
  return entries;
}
