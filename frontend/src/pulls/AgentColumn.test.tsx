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
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentReviewData, ReviewRoundRow } from '../review/agentReviewTypes';
import type { LocalPRBundle } from '../types/localPr';
import { AgentReviewConversation } from './AgentColumn';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const bundle: LocalPRBundle = {
  pr: {
    id: 'pr-30267', taskId: null, branchName: 'review-round-refactor', baseBranch: 'master',
    title: 'Refactor the agent review round conversation', description: 'Fixture PR',
    status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 30267,
    remotePrUrl: 'https://example.test/pr/30267', mergedAt: null, closedAt: null,
    origin: 'external', repo: 'trinodb/trino', author: 'reviewer', syncedAt: 1,
    syncedAdditions: 12, syncedDeletions: 4, syncedMergeable: true,
    syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
    syncedMergeQueueState: null, branchDeletedAt: null,
  },
  commits: [], timeline: [], checks: [], comments: [],
};

function fixture(status: 'RUNNING' | 'COMPLETED_WITH_QUESTIONS'): {
  data: AgentReviewData;
  round: ReviewRoundRow;
} {
  const previousRound: ReviewRoundRow = {
    id: 'round-1', review_id: 'review-1', agent_run_id: 'run-1', trigger: 'initial', scope: 'full',
    start_commit: '1111111', end_commit: '2222222', status: 'COMPLETED',
    budget_json: { cost_cap_cents: 150, wall_clock_minutes: 20 }, cost_cents: 47,
    capabilities_json: { source_mode: 'local-source', available: [], unavailable: [] },
    trigger_stage_id: null,
  };
  const round: ReviewRoundRow = {
    ...previousRound,
    id: 'round-2',
    agent_run_id: 'run-2',
    trigger: 'manual',
    start_commit: '058b8b2aaaa',
    end_commit: '613592dbbbb',
    status,
    cost_cents: 53,
  };
  const data = {
    review: {
      id: 'review-1', repo_id: 'trinodb/trino', pr_id: bundle.pr.id, base_commit: '1111111',
      reviewed_head_commit: '613592dbbbb', status: 'ACTIVE', workspace_id: 'workspace-1',
      owner_thread_id: 'thread-1', owner_task_id: null,
    },
    rounds: [previousRound, round],
    runs: [{
      id: 'run-2', taskId: null, kind: 'panel_review', source: null, parentStageId: null,
      reviewRoundId: round.id, stageId: null,
      status: status === 'RUNNING' ? 'running' : 'succeeded', iterations: 1, budget: 150,
      headline: null, metricsJson: '{"tokensIn":1234,"tokensOut":56}', startedAt: '2026-07-12T12:00:00Z',
      finishedAt: status === 'RUNNING' ? null : '2026-07-12T12:12:00Z',
    }],
    criteria: [{
      id: 'criterion-1', repo_id: 'trinodb/trino', kind: 'hard-invariant',
      statement: 'Review the changed behavior.', source_type: 'planner',
    }],
    objectives: [
      { id: 'objective-1', round_id: round.id, criterion_id: 'criterion-1', statement: 'Re-verify the null handling fix', source: 'planner', applicability_status: 'applicable', resolution_status: 'finding' },
      { id: 'objective-2', round_id: round.id, criterion_id: 'criterion-1', statement: 'Trace the executor configuration', source: 'project-intelligence', applicability_status: 'applicable', resolution_status: 'pending' },
      { id: 'objective-3', round_id: round.id, criterion_id: 'criterion-1', statement: 'Check enumeration bounds', source: 'planner', applicability_status: 'applicable', resolution_status: 'pending' },
    ],
    assignments: [
      { id: 'assignment-1', round_id: round.id, reviewer_def_id: 'correctness', runner: 'api', status: 'completed', understanding_summary: 'The null handling fix is correct.', assumptions_json: [], unknowns_json: [], budget_json: { hypotheses: 4, active_hypotheses: 2, steps: 8, findings: 3 } },
      { id: 'assignment-2', round_id: round.id, reviewer_def_id: 'configuration', runner: 'cli', status: 'running', understanding_summary: '', assumptions_json: [], unknowns_json: [], budget_json: { hypotheses: 4, active_hypotheses: 2, steps: 8, findings: 3 } },
      { id: 'assignment-3', round_id: round.id, reviewer_def_id: 'conventions', runner: 'api', status: 'queued', understanding_summary: '', assumptions_json: [], unknowns_json: [], budget_json: { hypotheses: 4, active_hypotheses: 2, steps: 8, findings: 3 } },
    ],
    hypotheses: [
      { id: 'hypothesis-1', assignment_id: 'assignment-1', objective_id: 'objective-1', claim: 'The fix is correct.', origin: 'explorer', status: 'confirmed', confidence_class: 'VERIFIED' },
      { id: 'hypothesis-2', assignment_id: 'assignment-2', objective_id: 'objective-2', claim: 'Configuration may be incomplete.', origin: 'explorer', status: 'unknown', confidence_class: 'UNKNOWN' },
      { id: 'hypothesis-3', assignment_id: 'assignment-3', objective_id: 'objective-3', claim: 'Bounds may be incomplete.', origin: 'explorer', status: 'pending', confidence_class: 'TENTATIVE' },
    ],
    steps: [
      { id: 'step-1', assignment_id: 'assignment-1', hypothesis_id: 'hypothesis-1', action_type: 'read_file', arguments_json: { path: 'src/NullHandling.java' }, reason: 'Read the fix.', planned: true, cost_cents: 11, status: 'completed' },
      { id: 'step-2', assignment_id: 'assignment-2', hypothesis_id: 'hypothesis-2', action_type: 'search_config', arguments_json: { files: ['src/Config.java', 'docs/config.md'] }, reason: 'Find configuration references.', planned: true, cost_cents: 18, status: 'completed' },
      { id: 'step-3', assignment_id: 'assignment-2', hypothesis_id: 'hypothesis-2', action_type: 'trace_symbol', arguments_json: { symbol: 'HiveMetadataFactory' }, reason: 'Trace executor configuration.', planned: true, cost_cents: 24, status: 'running' },
    ],
    observations: [],
    findings: [
      { id: 'finding-1', review_id: 'review-1', round_id: round.id, objective_id: 'objective-1', hypothesis_id: 'hypothesis-1', criterion_kind: 'hard-invariant', claim: 'The fix preserves the contract.', severity: 2, confidence_class: 'VERIFIED', verification_status: 'verified', requested_action: 'Keep the fix.', lifecycle_status: 'included', last_checked_commit: '613592dbbbb' },
      { id: 'finding-2', review_id: 'review-1', round_id: round.id, objective_id: 'objective-1', hypothesis_id: 'hypothesis-1', criterion_kind: 'hard-invariant', claim: 'A rejected alternative.', severity: 2, confidence_class: 'REJECTED', verification_status: 'rejected', requested_action: 'None.', lifecycle_status: 'dropped', last_checked_commit: '613592dbbbb' },
      { id: 'finding-3', review_id: 'review-1', round_id: round.id, objective_id: 'objective-2', hypothesis_id: 'hypothesis-2', criterion_kind: 'engineering-principle', claim: 'The intended configuration is unclear.', severity: 2, confidence_class: 'UNKNOWN', verification_status: 'unknown', requested_action: 'Ask the author.', lifecycle_status: 'NEEDS_AUTHOR_INPUT', last_checked_commit: '613592dbbbb' },
    ],
    evidence: [], verifications: [], relations: [], outcomes: [], knowledge_items: [],
    knowledge_provenance: [], activity_facts: [], round_messages: [],
    reviewed_commits: [
      { round_id: round.id, sha: '058b8b2aaaa', message: 'First', position: 0 },
      { round_id: round.id, sha: '613592dbbbb', message: 'Last', position: 1 },
    ],
    pr_comments: [], pr_timeline_events: [],
  } as AgentReviewData;
  return { data, round };
}

function renderConversation(status: 'RUNNING' | 'COMPLETED_WITH_QUESTIONS') {
  const { data, round } = fixture(status);
  const onBack = vi.fn();
  const onTogglePanel = vi.fn();
  const onStopRound = vi.fn();
  const onStartRound = vi.fn(async () => true);
  const onSendMessage = vi.fn(async () => true);
  const rendered = render(
    <AgentReviewConversation
      bundle={bundle}
      data={data}
      round={round}
      roundNumber={2}
      onBack={onBack}
      onTogglePanel={onTogglePanel}
      onStopRound={onStopRound}
      onStartRound={onStartRound}
      onSendMessage={onSendMessage}
    />,
  );
  return {
    ...rendered, onBack, onTogglePanel, onStopRound, onStartRound, onSendMessage,
  };
}

describe('AgentReviewConversation', () => {
  it('renders and steers a running round with done, live, and queued investigators', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-07-12T12:12:00Z'));
    const { container, onTogglePanel, onStopRound, onSendMessage } = renderConversation('RUNNING');
    const conversation = container.querySelector<HTMLElement>('[data-agent-review-state="running"]');
    if (conversation === null) throw new Error('running conversation missing');
    const view = within(conversation);

    expect(view.getByText('running · 12m')).not.toBeNull();
    expect(view.getByText('in progress · 12m')).not.toBeNull();
    expect(view.queryByRole('button', { name: 'Trigger next round' })).toBeNull();
    const learnedFocus = view.getByText('Project Intelligence focus').parentElement;
    expect(learnedFocus?.textContent).toContain('Trace the executor configuration');
    fireEvent.click(view.getByRole('button', { name: 'Stop round' }));
    expect(onStopRound).toHaveBeenCalledWith('round-2');
    fireEvent.click(view.getByRole('button', { name: 'Toggle PR panel' }));
    expect(onTogglePanel).toHaveBeenCalledOnce();

    const cards = [...conversation.querySelectorAll<HTMLElement>('[data-investigator-state]')];
    expect(cards.map(card => card.dataset.investigatorState)).toEqual(['done', 'running', 'queued']);
    expect(cards[0].querySelector<HTMLDetailsElement>('details')?.open).toBe(false);
    expect(cards[1].querySelector<HTMLDetailsElement>('details')?.open).toBe(true);
    expect(cards[1].querySelector('[data-live="true"]')?.textContent).toContain('HiveMetadataFactory');
    expect(cards[1].textContent).not.toContain('1 question → author');
    expect(cards[2].querySelector('details')).toBeNull();
    expect(cards[2].textContent).toContain('Starts when objective 2 completes');

    const liveTrace = cards[1].querySelector<HTMLDetailsElement>('details');
    const liveTraceSummary = liveTrace?.querySelector('summary');
    if (liveTrace === null || liveTrace === undefined || liveTraceSummary === null || liveTraceSummary === undefined) {
      throw new Error('running trace controls missing');
    }
    fireEvent.click(liveTraceSummary);
    expect(liveTrace.open).toBe(false);

    const usage = view.getByTitle('Usage');
    expect(usage.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(usage);
    expect(usage.getAttribute('aria-expanded')).toBe('true');
    expect(view.getByText('1,234 tokens')).not.toBeNull();
    expect(view.getByText('56 tokens')).not.toBeNull();

    const input = view.getByRole('textbox', { name: 'Steer round 2' });
    fireEvent.change(input, { target: { value: 'Check teardown ordering' } });
    expect(liveTrace.open).toBe(false);
    fireEvent.click(view.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(onSendMessage).toHaveBeenCalledWith(
      'round-2', 'panel', 'Check teardown ordering',
    ));
  });

  it('renders the finished verdict and starts the next round from its state-specific control', async () => {
    const { container, onStartRound, onSendMessage } = renderConversation('COMPLETED_WITH_QUESTIONS');
    const conversation = container.querySelector<HTMLElement>('[data-agent-review-state="finished"]');
    if (conversation === null) throw new Error('finished conversation missing');
    const view = within(conversation);

    expect(view.queryByRole('button', { name: 'Stop round' })).toBeNull();
    expect(view.getAllByText('questions remain').length).toBeGreaterThan(0);
    expect(view.getByRole('link', { name: 'Post questions to author' })).not.toBeNull();
    expect(view.getByRole('link', { name: 'Open findings ›' })).not.toBeNull();
    expect(view.getByText('0 blocking')).not.toBeNull();

    const cards = [...conversation.querySelectorAll<HTMLElement>('[data-investigator-state]')];
    expect(cards.map(card => card.dataset.investigatorState)).toEqual(['done', 'done', 'done']);
    expect(cards.every(card => card.querySelector<HTMLDetailsElement>('details')?.open === false)).toBe(true);
    expect(cards[1].textContent).toContain('1 question → author');

    fireEvent.click(view.getByRole('button', { name: 'Trigger next round' }));
    const input = view.getByRole('textbox', { name: 'Describe round 3' });
    fireEvent.change(input, { target: { value: 'Re-check the author follow-up' } });
    fireEvent.click(view.getByRole('button', { name: 'Start round 3' }));

    await waitFor(() => expect(onStartRound).toHaveBeenCalledWith('Re-check the author follow-up'));
    expect(onSendMessage).not.toHaveBeenCalled();
    await waitFor(() => expect(view.getByRole('textbox', { name: 'Steer round 2' })).toHaveProperty('value', ''));
  });
});
