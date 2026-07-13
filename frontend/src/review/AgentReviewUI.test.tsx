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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DiffInlineComments, diffInlineCommentFromLocalPr } from '../diff/DiffInlineComments';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { createAgentReviewFixture, createRoundStateFixtures, createVerificationStateFixture } from './agentReviewTestData';
import { AgentReviewHeaderAction } from './AgentReviewHeaderAction';
import { AgentReviewRoundEpisode, episodeState } from './AgentReviewRoundEpisode';
import { AgentReviewRoundPage } from './AgentReviewRoundPage';
import { AgentReviewTimeline } from './AgentReviewTimeline';
import { SubmitReviewPopover } from './SubmitReviewPopover';
import { AgentFindingContent, presentFinding } from './AgentEvidence';

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1', taskId: null, branchName: 'feature', baseBranch: 'main', title: 'Preserve missing behavior',
      description: 'Fixture PR', status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 42,
      remotePrUrl: 'https://example.test/pr/42', mergedAt: null, closedAt: null, origin: 'external',
      repo: 'acme/widget', author: 'maria', syncedAt: 1, syncedAdditions: 2, syncedDeletions: 1,
      syncedMergeable: true, syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [{ id: 'c-1', localPrId: 'pr-1', sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
    timeline: [], checks: [], comments: [],
  };
}

function fixture() {
  return createAgentReviewFixture(bundle(), [{
    filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
    patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
  }]);
}

describe('agent review UI', () => {
  it('renders a pending finding through the shared comment card and expands SUPPORTS/REFUTES citations', () => {
    const data = fixture();
    render(<DiffInlineComments comments={[diffInlineCommentFromLocalPr(data.pr_comments[0], data)]} allowLocalComments={false} />);
    expect(screen.getByText('AGENT')).toBeTruthy();
    expect(screen.getByText('MAJOR')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /evidence/ }));
    expect(screen.getByText('SUPPORTS · 1')).toBeTruthy();
    expect(screen.getByText('REFUTES · 1')).toBeTruthy();
    expect(screen.getByText(/ChangedFile\.ts:3@abcdef0/)).toBeTruthy();
  });

  it('edits, excludes, removes, and submits fixture comments from one popover', () => {
    const data = fixture();
    const onToggle = vi.fn();
    const onEdit = vi.fn();
    const onRemove = vi.fn();
    const onSubmit = vi.fn();
    render(<SubmitReviewPopover comments={data.pr_comments} excluded={new Set()} onToggle={onToggle} onEdit={onEdit} onRemove={onRemove} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: /Submit review/ }));
    const textareas = screen.getAllByRole('textbox');
    fireEvent.change(textareas[0], { target: { value: 'Edited finding' } });
    expect(onEdit).toHaveBeenCalledWith('fixture-comment-1', 'Edited finding');
    fireEvent.click(screen.getAllByRole('checkbox')[0]);
    expect(onToggle).toHaveBeenCalledWith('finding-1');
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove pending comment' })[0]);
    expect(onRemove).toHaveBeenCalledWith('fixture-comment-1');
    fireEvent.click(screen.getByRole('button', { name: 'Submit review (2)' }));
    expect(onSubmit).toHaveBeenCalledWith('REQUEST_CHANGES');
  });

  it('shows manual drafts in the agent-review submission and includes them in its count', () => {
    const data = fixture();
    const manual: LocalPRComment = {
      ...data.pr_comments[0],
      id: 'manual-comment',
      findingId: null,
      author: 'you',
      body: 'Manual reviewer draft',
    };
    const onRemove = vi.fn();
    const view = render(<SubmitReviewPopover
      comments={[...data.pr_comments, manual]}
      excluded={new Set()}
      onToggle={vi.fn()}
      onEdit={vi.fn()}
      onRemove={onRemove}
      onSubmit={vi.fn()}
    />);
    const popover = within(view.container);

    fireEvent.click(popover.getByRole('button', { name: /Submit review/ }));
    expect(popover.getByDisplayValue('Manual reviewer draft')).toBeTruthy();
    expect(popover.getByRole('button', { name: 'Submit review (3)' })).toBeTruthy();
    const manualRow = popover.getByDisplayValue('Manual reviewer draft').closest('label');
    if (manualRow === null) throw new Error('manual draft row missing');
    fireEvent.click(within(manualRow).getByRole('button', { name: 'Remove pending comment' }));
    expect(onRemove).toHaveBeenCalledWith('manual-comment');
  });

  it('maps every locked episode presentation to landed run statuses plus trigger/cap facts', () => {
    const data = fixture();
    expect(createRoundStateFixtures(data).map(({ run, round }) => episodeState(run, round)))
      .toEqual(['complete', 'live', 'errored', 'halted', 'stale', 'auto']);
    expect(episodeState(
      { ...data.runs[0], status: 'cancelled' },
      { ...data.rounds[0], status: 'CANCELLED' },
    )).toBe('cancelled');
    expect(episodeState(
      { ...data.runs[0], status: 'cancelled' },
      { ...data.rounds[0], status: 'CANCELLED' },
      true,
    )).toBe('stale');
  });

  it('renders panel-review rounds through the shared run episode and tool-feed primitives', () => {
    const data = fixture();
    const { container } = render(<AgentReviewRoundEpisode data={data} round={data.rounds[0]} run={data.runs[0]} />);
    expect(container.querySelector('.sp-node')).not.toBeNull();
    expect(container.querySelector('.agent-round-episode')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Round 1 · complete/ }));
    expect(container.querySelectorAll('.tool-block').length).toBeGreaterThan(0);
  });

  it('renders the PR review history in event order with stable finding labels and round-scoped plan data', () => {
    const data = fixture();
    const secondRound = { ...data.rounds[0], id: 'round-2', agent_run_id: 'run-2' };
    data.rounds.push(secondRound);
    data.runs.push({ ...data.runs[0], id: 'run-2', reviewRoundId: 'round-2' });
    data.objectives.push({ ...data.objectives[0], id: 'objective-round-2', round_id: 'round-2' });
    data.assignments.push({ ...data.assignments[0], id: 'assignment-2' });
    data.findings.push({
      ...data.findings[0], id: 'finding-duplicate', lifecycle_status: 'dropped',
    });

    const { container } = render(<AgentReviewTimeline data={data} />);
    const text = container.textContent ?? '';
    expect(text).toContain('2 objectives · cap $0.50');
    expect(text).toContain('2 reviewers');
    expect(text).toContain('Verifier rejected F3 — dropped');
    expect(text).toContain('The cited span contradicts the proposed finding.');
    expect(text).not.toContain('finding-3');
    expect(text.indexOf('Verifier rejected F3')).toBeLessThan(text.indexOf('Needs your judgement'));
    expect(text.indexOf('Needs your judgement')).toBeLessThan(text.indexOf('Round 1 complete'));
    expect(text.indexOf('Round 1 complete')).toBeLessThan(text.indexOf('Author replied on F1'));
    expect(text.indexOf('Author replied on F1')).toBeLessThan(text.indexOf('Commit abcdef0 addresses F1'));
    expect(text).toContain('Round 1 complete — 2 findings');
    expect(text).toContain('Round 1 · complete2 findings');
  });

  it('keeps a user-stopped round as a terminal PR timeline episode', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'CANCELLED' };
    data.runs[0] = { ...data.runs[0], status: 'cancelled' };
    data.pr_timeline_events = data.pr_timeline_events.map(event =>
      event.payload?.reviewEvent === 'round-complete'
        ? { ...event, payload: { ...event.payload, reviewEvent: 'round-cancelled' } }
        : event);

    const { container } = render(<AgentReviewTimeline data={data} />);
    expect(container.textContent).toContain('Round 1 · stopped');
    expect(container.textContent).toContain('You stopped round 1 — nothing was posted to GitHub');
  });

  it('renders all header entry states with review actions in page content', () => {
    const data = fixture();
    const props = {
      comments: data.pr_comments, excluded: new Set<string>(), onStart: vi.fn(), onOpenRound: vi.fn(),
      onToggle: vi.fn(), onEdit: vi.fn(), onRemove: vi.fn(), onSubmit: vi.fn(),
    };
    const { rerender } = render(<AgentReviewHeaderAction state="never" {...props} />);
    expect(screen.getByRole('button', { name: /Review with agent/ })).toBeTruthy();
    rerender(<AgentReviewHeaderAction state="running" {...props} />);
    expect(screen.getByRole('button', { name: /reviewing/ })).toBeTruthy();
    rerender(<AgentReviewHeaderAction state="stale" {...props} />);
    expect(screen.getByRole('button', { name: /Continue review/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Customize agent review' }));
    fireEvent.click(screen.getByRole('radio', { name: /CLI runner/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Re-review' }));
    expect(props.onStart).toHaveBeenLastCalledWith({ runner: 'cli' });
  });

  it('keeps the round right panel supplied by the shared PRView owner and jumps pending cards by finding anchor', () => {
    const data = fixture();
    const onOpenFinding = vi.fn();
    render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div data-testid="shared-pr-view">shared PRView</div>} onBack={vi.fn()} onOpenFinding={onOpenFinding} />);
    expect(screen.getByTestId('shared-pr-view')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Hide PR panel' }));
    expect(screen.queryByTestId('shared-pr-view')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show PR panel' }));
    expect(screen.getByTestId('shared-pr-view')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /finding-1/ }));
    expect(onOpenFinding).toHaveBeenCalledWith('finding-1', 'src/ChangedFile.ts', 3);
  });

  it('keeps assignment bookkeeping out of the round conversation and groups steps with their reviewer', () => {
    const data = fixture();
    data.assignments[0] = {
      ...data.assignments[0],
      understanding_summary: 'Re-recording assignment to confirm objective IDs: 56fcc818-9a69-4b7a-a269-2acc796eb562 and 3229f0f8-6615-4fba-a724-dbde0dab5468.',
    };
    const { container } = render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />);
    const view = within(container);
    expect(view.queryByText(/Re-recording assignment/)).toBeNull();
    expect(view.getByText('Investigating 3 hypotheses across 2 review objectives.')).toBeTruthy();
    const investigation = view.getByText('correctness — investigation').closest('.sp-work');
    expect(investigation).not.toBeNull();
    expect(investigation?.querySelectorAll('.tool-block')).toHaveLength(0);
    fireEvent.click(view.getByRole('button', { name: /correctness — investigation/ }));
    expect(investigation?.querySelectorAll('.tool-block')).toHaveLength(4);
    expect(within(investigation as HTMLElement).getAllByText('src/ChangedFile.ts')).toHaveLength(3);
    expect(view.getByText(/Full scope: 2 objectives assigned to correctness/)).toBeTruthy();
    expect(view.getByText(/\(author\) replied on finding-1/)).toBeTruthy();
    expect(view.getByText(/acceptance criterion now includes the author’s clarification/)).toBeTruthy();
    expect(view.getByText(/pushed abcdef0.*addresses finding-1/)).toBeTruthy();
    expect(view.getByText(/Round 1 complete.*3 findings/)).toBeTruthy();
  });

  it('renders a contract-backed fixed finding as the canonical local-only resolution card', () => {
    const data = fixture();
    data.outcomes.push({
      finding_id: 'finding-1', user_disposition: 'published', author_response: 'fixed',
      epistemic_resolution: 'confirmed', utility_assessment: 'useful', style_edit_magnitude: 0,
    });
    const onOpenFinding = vi.fn();
    const onOpenReviewList = vi.fn();
    const onReopenFinding = vi.fn();
    const { container } = render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={onOpenFinding} onOpenReviewList={onOpenReviewList} onReopenFinding={onReopenFinding} />);
    const view = within(container);
    expect(view.getByText(/finding-1 fixed — fix verified · resolve \+ reply drafted/)).toBeTruthy();
    expect(view.getByText(/Nothing posts to GitHub until you submit/)).toBeTruthy();
    expect(view.queryByRole('button', { name: /finding-1 ·/ })).toBeNull();
    fireEvent.click(view.getByRole('button', { name: 'View on diff →' }));
    expect(onOpenFinding).toHaveBeenCalledWith('finding-1', 'src/ChangedFile.ts', 3);
    fireEvent.click(view.getByRole('button', { name: 'View in review list →' }));
    expect(onOpenReviewList).toHaveBeenCalledWith('finding-1');
    fireEvent.click(view.getByRole('button', { name: 'Reopen finding' }));
    expect(onReopenFinding).toHaveBeenCalledWith('finding-1');
  });

  it('lets the reviewer stop a running round from the canonical round header', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'RUNNING' };
    const onStopRound = vi.fn();
    render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} onStopRound={onStopRound} />);
    fireEvent.click(screen.getByRole('button', { name: 'Stop round' }));
    expect(onStopRound).toHaveBeenCalledWith(data.rounds[0].id);
  });

  it.each([
    ['verified', /verified/],
    ['partially', /partially verified/],
    ['unknown', /unknown — asks author/],
    ['rejected', /rejected — dropped/],
  ] as const)('renders %s verifier chrome from fixture verification rows', (status, label) => {
    const data = createVerificationStateFixture(fixture(), status);
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;
    const { container } = render(<AgentFindingContent view={view} body="Finding body" pending />);
    expect(within(container).getByText(label)).toBeTruthy();
  });

  it('derives the confidence ceiling from supporting evidence only', () => {
    const data = fixture();
    data.findings[0] = { ...data.findings[0], verification_status: 'partially' };
    data.evidence = data.evidence.map(row => row.finding_id !== 'finding-1' ? row : {
      ...row,
      strength_class: row.relation === 'SUPPORTS' ? 'E1' : 'E4',
    });
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;

    const { container } = render(<AgentFindingContent view={view} body="Finding body" />);

    expect(container.querySelector('.agent-finding-chip.confidence')?.textContent).toContain('≤0.45');
  });
});
