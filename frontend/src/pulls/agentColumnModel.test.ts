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
import { describe, expect, it } from 'vitest';
import type { AgentReviewData, ReviewRoundRow } from '../review/agentReviewTypes';
import { buildSpine, reviewedShas, roundChip, roundStats } from './agentColumnModel';

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
