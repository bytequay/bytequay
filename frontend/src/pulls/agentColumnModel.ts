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
import type { AgentReviewData, InvestigationStepRow, ReviewRoundRow } from '../review/agentReviewTypes';
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
  if (recorded.length > 1) {
    return { text: `${recorded[0].sha.slice(0, 7)} … ${recorded.at(-1)!.sha.slice(0, 7)}`, count: recorded.length };
  }
  const sha = recorded[0]?.sha ?? round.end_commit ?? round.start_commit;
  return sha.length === 0 ? null : { text: sha.slice(0, 7), count: 1 };
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/gi;

/** First readable sentence of the reviewer's understanding summary —
 * the same internal-marker filtering AgentReviewRoundPage applies, with
 * a single static fallback. */
export function assignmentSummary(summary: string): string {
  const compact = summary.replaceAll(/\s+/g, ' ').trim();
  const internal = compact.includes('<｜｜DSML｜｜')
    || compact.includes('<tool_call>')
    || /(?:re-?recording|record)_assignment|objective ids?/i.test(compact)
    || (compact.match(UUID_PATTERN)?.length ?? 0) > 1;
  if (internal || compact.length === 0) return 'Reviewing the changed code against the assigned objectives.';
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

/** "read files + search code" — the collapsed work sub-row's label. */
export function workLabel(steps: InvestigationStepRow[]): string {
  const labels = [...new Set(steps.map(step => stepVerb(step.action_type)))];
  return labels.slice(0, 2).join(' + ') || 'review work';
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
