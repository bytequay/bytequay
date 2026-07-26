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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentReviewData, ReviewRoundRow } from '../review/agentReviewTypes';
import { buildConversationModel, buildSpine, reviewedShas, roundChip, roundStats } from './agentColumnModel';

afterEach(() => {
  vi.restoreAllMocks();
});

function round(overrides: Partial<ReviewRoundRow>): ReviewRoundRow {
  return {
    id: 'round-1', review_id: 'review', agent_run_id: 'run-1', trigger: 'manual',
    scope: 'full', start_commit: '6be742d99', end_commit: null, status: 'COMPLETED',
    budget_json: { cost_cap_cents: 150, wall_clock_minutes: 20 }, cost_cents: 83,
    capabilities_json: { source_mode: 'local-source', available: [], unavailable: [] },
    trigger_stage_id: null,
    ...overrides,
  };
}

describe('roundChip', () => {
  it('maps the round-status vocabulary onto the template chips', () => {
    expect(roundChip(round({ status: 'COMPLETED' }))).toEqual({ label: 'complete', tone: 'complete' });
    expect(roundChip(round({ status: 'COMPLETED_WITH_QUESTIONS' }))).toEqual({ label: 'questions remain', tone: 'questions' });
    expect(roundChip(round({ status: 'RUNNING' }))).toEqual({ label: 'running', tone: 'running' });
    expect(roundChip(round({ status: 'RUNNING', message_gate_open: false }))).toEqual({ label: 'finalizing', tone: 'running' });
    expect(roundChip(round({ status: 'QUEUED' }))).toEqual({ label: 'queued', tone: 'queued' });
    expect(roundChip(round({ status: 'CANCELLED' }))).toEqual({ label: 'stopped', tone: 'cancelled' });
    expect(roundChip(round({ status: 'ERRORED' }))).toEqual({ label: 'errored', tone: 'errored' });
  });
});

describe('roundStats and reviewedShas', () => {
  it('splits verifier-rejected findings out and falls back to the round commits', () => {
    const data = {
      findings: [
        { id: 'f1', round_id: 'round-1', verification_status: 'verified', lifecycle_status: 'included' },
        { id: 'f2', round_id: 'round-1', verification_status: 'rejected', lifecycle_status: 'dropped' },
        { id: 'f3', round_id: 'other', verification_status: 'verified', lifecycle_status: 'included' },
      ],
      reviewed_commits: [],
    } as unknown as AgentReviewData;
    expect(roundStats(data, 'round-1')).toEqual({ findings: 1, rejected: 1 });
    expect(reviewedShas(data, round({}))).toEqual({ text: '6be742d', count: 1 });
    data.reviewed_commits = [
      { round_id: 'round-1', sha: 'aaaaaaaa1', message: 'a', position: 0 },
      { round_id: 'round-1', sha: 'bbbbbbbb2', message: 'b', position: 1 },
    ];
    expect(reviewedShas(data, round({}))).toEqual({ text: 'aaaaaaa … bbbbbbb', count: 2 });
  });
});

describe('buildSpine', () => {
  it('builds planning + assignment entries with the template chips', () => {
    const data = {
      criteria: [{ id: 'c1', kind: 'hard-invariant', statement: 's', source_type: 'shipped-rule' }],
      objectives: [
        { id: 'o1', round_id: 'round-1', criterion_id: 'c1', statement: 's', source: 'shipped-rule', applicability_status: 'applicable', resolution_status: 'pending' },
        { id: 'o2', round_id: 'round-1', criterion_id: 'c1', statement: 's', source: 'shipped-rule', applicability_status: 'applicable', resolution_status: 'pending' },
      ],
      assignments: [
        { id: 'a1', round_id: 'round-1', reviewer_def_id: 'general-cli', runner: 'cli', status: 'completed', understanding_summary: 'Replaces ad-hoc threads. Second sentence.', assumptions_json: [], unknowns_json: [], budget_json: { hypotheses: 0, active_hypotheses: 0, steps: 0, findings: 0 } },
        { id: 'a2', round_id: 'round-1', reviewer_def_id: 'independent-verifier', runner: 'api', status: 'completed', understanding_summary: '', assumptions_json: [], unknowns_json: [], budget_json: { hypotheses: 0, active_hypotheses: 0, steps: 0, findings: 0 } },
      ],
      steps: [
        { id: 's1', assignment_id: 'a1', action_type: 'read_file', arguments_json: {}, reason: '', planned: true, cost_cents: 3, status: 'completed' },
        { id: 's2', assignment_id: 'a1', action_type: 'grep', arguments_json: {}, reason: '', planned: true, cost_cents: 4, status: 'completed' },
      ],
      round_messages: [],
    } as unknown as AgentReviewData;
    expect(buildSpine(data, round({}))).toEqual([
      { kind: 'planning', chips: ['2 objectives', 'full', 'deterministic'], scopeLabel: 'Full', objectivesLabel: '2 objectives', reviewers: 'general-cli' },
      { kind: 'investigation', agent: 'general-cli', chips: ['$0.07', '2 steps', 'cli'], summary: 'Replaces ad-hoc threads.', sub: { label: 'read files + search code', steps: 2 } },
      { kind: 'verification', agent: 'independent-verifier', chips: ['$0.00', '0 steps', 'api'], summary: 'Reviewing the changed code against the assigned objectives.', sub: null },
    ]);
  });
});

describe('buildConversationModel', () => {
  it('maps a running round to done, live, and queued objective-first investigators', () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-07-12T12:12:00Z'));
    const liveRound = round({ status: 'RUNNING', cost_cents: 31 });
    const data = {
      rounds: [liveRound],
      runs: [{
        id: 'run-1', startedAt: '2026-07-12T12:00:00Z', finishedAt: null,
      }],
      criteria: [{ id: 'c1', kind: 'hard-invariant', statement: 's', source_type: 'planner' }],
      objectives: [
        { id: 'o1', round_id: 'round-1', criterion_id: 'c1', statement: 'Re-verify fixes', source: 'planner', applicability_status: 'applicable', resolution_status: 'pending' },
        { id: 'o2', round_id: 'round-1', criterion_id: 'c1', statement: 'Check config surface', source: 'project-intelligence', applicability_status: 'applicable', resolution_status: 'pending' },
        { id: 'o3', round_id: 'round-1', criterion_id: 'c1', statement: 'Check enumeration bounds', source: 'project-intelligence', applicability_status: 'not-applicable', resolution_status: 'pending' },
      ],
      assignments: [
        { id: 'a1', round_id: 'round-1', reviewer_def_id: 'general-api', runner: 'api', status: 'completed', understanding_summary: 'Both fixes verified.', assumptions_json: [], unknowns_json: [], budget_json: {} },
        { id: 'a2', round_id: 'round-1', reviewer_def_id: 'general-cli', runner: 'cli', status: 'running', understanding_summary: '', assumptions_json: [], unknowns_json: [], budget_json: {} },
        { id: 'a3', round_id: 'round-1', reviewer_def_id: 'general-api', runner: 'api', status: 'queued', understanding_summary: '', assumptions_json: [], unknowns_json: [], budget_json: {} },
        { id: 'guidance', round_id: 'round-1', reviewer_def_id: 'planner', runner: 'api', status: 'completed', understanding_summary: 'Internal guidance.', assumptions_json: [], unknowns_json: [], budget_json: {} },
      ],
      hypotheses: [
        { id: 'h1', assignment_id: 'a1', objective_id: 'o1' },
        { id: 'h2', assignment_id: 'a2', objective_id: 'o2' },
      ],
      steps: [
        { id: 's1', assignment_id: 'a1', action_type: 'read_file', arguments_json: { path: 'src/A.ts' }, reason: 'Read the fix.', cost_cents: 11, status: 'completed' },
        { id: 's2', assignment_id: 'a2', action_type: 'search_config', arguments_json: { files: ['cli/A.java', 'docs/a.md'] }, reason: 'Find config references.', cost_cents: 8, status: 'completed' },
        { id: 's3', assignment_id: 'a2', action_type: 'trace_symbol', arguments_json: { symbol: 'HiveMetadataFactory' }, reason: 'Trace executor config.', cost_cents: 12, status: 'running' },
      ],
      findings: [
        { id: 'f1', round_id: 'round-1', hypothesis_id: 'h1', objective_id: 'o1', verification_status: 'verified', lifecycle_status: 'included' },
        { id: 'f2', round_id: 'round-1', hypothesis_id: 'h1', objective_id: 'o1', verification_status: 'rejected', lifecycle_status: 'dropped' },
        { id: 'f3', round_id: 'round-1', hypothesis_id: 'h2', objective_id: 'o2', verification_status: 'unknown', lifecycle_status: 'NEEDS_AUTHOR_INPUT' },
        { id: 'f4', round_id: 'round-1', objective_id: 'o3', verification_status: 'verified', lifecycle_status: 'included' },
      ],
      outcomes: [],
      round_messages: [{ assignment_id: 'guidance' }],
      reviewed_commits: [
        { round_id: 'round-1', sha: '058b8b2aaaa', message: 'first', position: 0 },
        { round_id: 'round-1', sha: '613592dbbbb', message: 'last', position: 1 },
      ],
    } as unknown as AgentReviewData;

    const model = buildConversationModel(data, liveRound);

    expect(model.running).toBe(true);
    expect(model.finished).toBe(false);
    expect(model.objectives.map(objective => objective.state)).toEqual(['done', 'running', 'queued']);
    expect(model.learnedObjectives).toEqual(['Check config surface']);
    expect(model.doneObjectives).toBe(1);
    expect(model.investigators.map(investigator => [
      investigator.objectiveTitle, investigator.state, investigator.scope,
    ])).toEqual([
      ['Re-verify fixes', 'done', 'api'],
      ['Check config surface', 'running', 'cli'],
      ['Check enumeration bounds', 'queued', 'api'],
    ]);
    expect(model.investigators[1].trace).toEqual([
      expect.objectContaining({ target: 'cli/A.java, docs/a.md', live: false }),
      expect.objectContaining({ target: 'HiveMetadataFactory', live: true }),
    ]);
    expect(model.investigators[1].traceOpen).toBe(true);
    expect(model.investigators[2].foldLabel).toBeNull();
    expect(model.investigators[0].findings).toMatchObject({ accepted: 1, refuted: 1, questions: 0 });
    expect(model.investigators[0].chips.map(chip => chip.label)).toEqual(['kept F1', 'refuted 1']);
    expect(model.investigators[1].chips).toEqual([]);
    expect(model.totals).toMatchObject({ steps: 3, stepCostCents: 31, costCents: 31, budgetCapCents: 150 });
    expect(model.totals.findings).toMatchObject({ total: 4, accepted: 3, refuted: 1, questions: 1 });
    expect(model.reviewedCommits).toEqual({ text: '058b8b2 … 613592d', count: 2 });
    expect(model.durationLabel).toBe('12m');
    expect(model.dateLabel).toBe('now');
    expect(model.activeInvestigatorNumber).toBe(2);
  });

  it('finishes every objective and exposes verdict chips, duration, and date', () => {
    const finishedRound = round({ status: 'COMPLETED_WITH_QUESTIONS', cost_cents: 53 });
    const data = {
      rounds: [finishedRound],
      runs: [{
        id: 'run-1', startedAt: '2026-07-12T11:58:48Z', finishedAt: '2026-07-12T12:00:00Z',
      }],
      criteria: [{ id: 'c1', kind: 'hard-invariant', statement: 's', source_type: 'planner' }],
      objectives: [
        { id: 'o1', round_id: 'round-1', criterion_id: 'c1', statement: 'One', source: 'planner', applicability_status: 'applicable', resolution_status: 'finding' },
        { id: 'o2', round_id: 'round-1', criterion_id: 'c1', statement: 'Two', source: 'planner', applicability_status: 'applicable', resolution_status: 'unknown' },
      ],
      assignments: [
        { id: 'a1', round_id: 'round-1', reviewer_def_id: 'general-api', runner: 'api', status: 'completed', understanding_summary: 'Review complete.', assumptions_json: [], unknowns_json: [], budget_json: {} },
      ],
      hypotheses: [{ id: 'h1', assignment_id: 'a1', objective_id: 'o1' }],
      steps: [{ id: 's1', assignment_id: 'a1', action_type: 'read', arguments_json: {}, reason: '', cost_cents: 8, status: 'completed' }],
      findings: [
        { id: 'f1', round_id: 'round-1', hypothesis_id: 'h1', objective_id: 'o1', verification_status: 'verified', lifecycle_status: 'included' },
        { id: 'f2', round_id: 'round-1', objective_id: 'o2', verification_status: 'unknown', lifecycle_status: 'NEEDS_USER_JUDGEMENT' },
        { id: 'f3', round_id: 'round-1', objective_id: 'o1', verification_status: 'rejected', lifecycle_status: 'dropped' },
      ],
      outcomes: [], round_messages: [], reviewed_commits: [],
    } as unknown as AgentReviewData;

    const model = buildConversationModel(data, finishedRound);

    expect(model.finished).toBe(true);
    expect(model.objectives.map(objective => objective.state)).toEqual(['done', 'done']);
    expect(model.doneObjectives).toBe(2);
    expect(model.doneInvestigators).toBe(1);
    expect(model.findingChips.map(chip => [chip.label, chip.tone])).toEqual([
      ['kept F1', 'neutral'],
      ['refuted 1', 'success'],
      ['1 question → author', 'question'],
    ]);
    expect(model.durationSeconds).toBe(72);
    expect(model.durationLabel).toBe('1m 12s');
    expect(model.dateLabel).toBe('Jul 12');
  });
});
