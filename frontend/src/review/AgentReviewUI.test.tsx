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
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DiffInlineComments, diffInlineCommentFromLocalPr } from '../diff/DiffInlineComments';
import type { LocalPRBundle, LocalPRComment } from '../types/localPr';
import { createAgentReviewFixture, createRoundStateFixtures, createVerificationStateFixture } from './agentReviewTestData';
import { AgentReviewHeaderAction } from './AgentReviewHeaderAction';
import { AgentReviewRoundEpisode, episodeState } from './AgentReviewRoundEpisode';
import { AgentReviewRoundPage } from './AgentReviewRoundPage';
import { AgentReviewTimeline } from './AgentReviewTimeline';
import { SubmitReviewPopover } from './SubmitReviewPopover';
import { AgentFindingContent, findingMarkdown, findingSummary, presentFinding } from './AgentEvidence';

const ROUND_LEFT_WIDTH_KEY = 'bq.agentReviewRoundLeftWidth.v2';
const ROUND_PR_WIDTH_KEY = 'bq.agentReviewRoundPrWidth.v2';

afterEach(() => {
  localStorage.removeItem(ROUND_LEFT_WIDTH_KEY);
  localStorage.removeItem(ROUND_PR_WIDTH_KEY);
});

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

  it('adds conservative Markdown to legacy finding prose and keeps folded summaries plain', () => {
    const prose = 'DynamicTrinoCatalog uses ConnectorIdentity.\n\nCould you clarify the intended behavior here? Keep `Session` isolated.';
    expect(findingMarkdown(prose)).toContain('`DynamicTrinoCatalog` uses `ConnectorIdentity`');
    expect(findingMarkdown(prose)).toContain('**Question:** Keep `Session` isolated.');
    expect(findingMarkdown('GitHub links stay prose.')).toBe('GitHub links stay prose.');
    expect(findingSummary('**Risk:** `DynamicTrinoCatalog` reuses identity.')).toBe('Risk: DynamicTrinoCatalog reuses identity.');
  });

  it('edits, excludes, removes, and submits fixture comments from one popover', () => {
    const data = fixture();
    const onToggle = vi.fn();
    const onEdit = vi.fn();
    const onRemove = vi.fn();
    const onSubmit = vi.fn();
    render(<SubmitReviewPopover comments={data.pr_comments} excluded={new Set()} onToggle={onToggle} onEdit={onEdit} onRemove={onRemove} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 2 ▾' }));
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

  it('renders panel-review rounds as a compact, navigable timeline card', () => {
    const data = fixture();
    const onOpen = vi.fn();
    const { container } = render(<AgentReviewRoundEpisode data={data} round={data.rounds[0]} run={data.runs[0]} onOpen={onOpen} />);
    expect(container.querySelector('.agent-review-round-card')).not.toBeNull();
    const row = container.querySelector('.agent-review-round-row');
    expect(row?.querySelector('.agent-review-round-card__marker svg')).not.toBeNull();
    expect(row?.querySelector('.agent-review-round-card__marker')?.textContent).not.toContain('✓');
    expect(container.querySelector('.agent-review-round-card .agent-review-round-card__marker')).toBeNull();
    expect(container.querySelector('.sp-node')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Open round 1: complete/ }));
    expect(onOpen).toHaveBeenCalledOnce();
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
    expect(text.indexOf('Verifier rejected F3')).toBeLessThan(text.indexOf('Round 1 complete'));
    expect(text.indexOf('Round 1 complete')).toBeLessThan(text.indexOf('Author replied on F1'));
    expect(text.indexOf('Author replied on F1')).toBeLessThan(text.indexOf('Commit abcdef0 addresses F1'));
    expect(text).toContain('Round 1 complete — 2 findings');
    expect(container.querySelector('.agent-review-round-card')).not.toBeNull();
    expect(container.querySelector('.agent-judgement-card')).toBeNull();
    const roundComplete = container.querySelector('.agent-review-event--round-complete');
    expect(roundComplete?.querySelector('.agent-review-event__icon svg')).not.toBeNull();
    expect(roundComplete?.querySelector('.agent-review-event__icon')?.classList.contains('green')).toBe(false);
    expect(roundComplete?.querySelector('.agent-review-event__icon')?.textContent).not.toContain('✓');
  });

  it('keeps a user-stopped round as a terminal PR timeline episode', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'CANCELLED' };
    data.runs[0] = { ...data.runs[0], status: 'cancelled' };
    data.pr_timeline_events = data.pr_timeline_events.map(event =>
      event.payload?.reviewEvent === 'round-complete'
        ? { ...event, payload: { ...event.payload, reviewEvent: 'round-cancelled' } }
        : event);

    const { container, rerender } = render(<AgentReviewTimeline data={data} />);
    expect(container.textContent).toContain('Round 1 · stopped');
    expect(container.textContent).toContain('You stopped round 1 — nothing was posted to GitHub');

    data.pr_timeline_events = data.pr_timeline_events.map(event =>
      event.payload?.reviewEvent === 'round-cancelled'
        ? { ...event, actor: 'agent', payload: { ...event.payload, recovered: true } }
        : event);
    rerender(<AgentReviewTimeline data={data} />);
    expect(container.textContent).toContain('Round 1 stopped after a backend restart');
    expect(container.textContent).not.toContain('You stopped round 1');
  });

  it('renders all header entry states with review actions in page content', () => {
    const data = fixture();
    const props = {
      comments: data.pr_comments, excluded: new Set<string>(), onStart: vi.fn(), onOpenRound: vi.fn(),
      onToggle: vi.fn(), onEdit: vi.fn(), onRemove: vi.fn(), onSubmit: vi.fn(),
    };
    const { rerender } = render(<AgentReviewHeaderAction state="never" {...props} />);
    expect(screen.getByRole('button', { name: /^Full review$/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Customize agent review' }));
    fireEvent.click(screen.getByRole('radio', { name: /CLI runner/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Start review' }));
    expect(props.onStart).toHaveBeenLastCalledWith({ runner: 'cli' });
    rerender(<AgentReviewHeaderAction state="running" {...props} />);
    expect(screen.getByRole('button', { name: /Full review • running/ })).toBeTruthy();
    rerender(<AgentReviewHeaderAction state="stale" {...props} />);
    fireEvent.click(screen.getByRole('button', { name: /Full review · update available/ }));
    expect(props.onOpenRound).toHaveBeenCalledOnce();
  });

  it('keeps Full review but hides Submit review on review-only PR surfaces', () => {
    const data = fixture();
    const view = render(
      <AgentReviewHeaderAction
        state="done"
        comments={data.pr_comments}
        excluded={new Set()}
        onStart={vi.fn()}
        onOpenRound={vi.fn()}
        onToggle={vi.fn()}
        onEdit={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    const header = within(view.container);
    expect(header.getByRole('button', { name: /Full review · Round/ })).toBeTruthy();
    expect(header.queryByRole('button', { name: /Submit review/ })).toBeNull();
  });

  it('keeps the round right panel supplied by the shared PRView owner and jumps timeline findings by anchor', () => {
    const data = fixture();
    const onOpenFinding = vi.fn();
    const { container } = render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div data-testid="shared-pr-view">shared PRView</div>} onBack={vi.fn()} onOpenFinding={onOpenFinding} />);
    expect(screen.getByTestId('shared-pr-view')).toBeTruthy();
    expect(screen.getByText('REMOTE ONLY')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Hide PR panel' }));
    expect(screen.getByTestId('shared-pr-view')).toBeTruthy();
    expect(container.querySelector('.agent-round-pr-panel')?.classList.contains('is-folded')).toBe(true);
    const prPanelContent = container.querySelector('.agent-round-pr-panel__content');
    expect(prPanelContent?.getAttribute('aria-hidden')).toBe('true');
    expect(prPanelContent?.hasAttribute('inert')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Show PR panel' }));
    expect(screen.getByTestId('shared-pr-view')).toBeTruthy();
    expect(prPanelContent?.hasAttribute('inert')).toBe(false);
    expect(container.querySelector('.agent-round-finding-card')).toBeNull();
    const findingEvent = container.querySelector<HTMLDetailsElement>('.agent-round-finding-event__content');
    expect(findingEvent?.open).toBe(false);
    expect(findingEvent?.querySelector('summary')?.textContent).toContain(data.findings[0].claim);
    expect(findingEvent?.querySelector('summary > span')?.textContent).toBe('Finding 1');
    if (findingEvent === null) throw new Error('folded finding event missing');
    fireEvent.click(findingEvent.querySelector('summary') as HTMLElement);
    expect(findingEvent.open).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /finding-1/ }));
    expect(onOpenFinding).toHaveBeenCalledWith('finding-1', 'src/ChangedFile.ts', 3);
  });

  it('resizes and persists both round workspace boundaries without changing the folded PR rail width', () => {
    localStorage.removeItem(ROUND_LEFT_WIDTH_KEY);
    localStorage.removeItem(ROUND_PR_WIDTH_KEY);
    const data = fixture();
    const { container, unmount } = render(
      <AgentReviewRoundPage
        data={data}
        roundId={data.rounds[0].id}
        prView={<div />}
        onBack={vi.fn()}
        onOpenFinding={vi.fn()}
      />,
    );
    const view = within(container);
    const page = container.querySelector<HTMLElement>('.agent-round-page');
    if (page === null) throw new Error('round workspace missing');
    const pageBounds = vi.spyOn(page, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, top: 0, right: 1500, bottom: 980, left: 0,
      width: 1500, height: 980, toJSON: () => ({}),
    });

    expect(page.style.gridTemplateColumns).toBe('244px minmax(0, 1fr) 440px');
    const leftHandle = view.getByRole('separator', { name: 'Resize review rounds sidebar' });
    fireEvent.mouseDown(leftHandle, { clientX: 244 });
    fireEvent.mouseMove(window, { clientX: 300 });
    fireEvent.mouseUp(window);
    expect(page.style.gridTemplateColumns).toBe('300px minmax(0, 1fr) 440px');
    expect(localStorage.getItem(ROUND_LEFT_WIDTH_KEY)).toBe('300');

    const rightHandle = view.getByRole('separator', { name: 'Resize pull request context' });
    fireEvent.mouseDown(rightHandle, { clientX: 1060 });
    fireEvent.mouseMove(window, { clientX: 980 });
    fireEvent.mouseUp(window);
    expect(page.style.gridTemplateColumns).toBe('300px minmax(0, 1fr) 520px');
    expect(localStorage.getItem(ROUND_PR_WIDTH_KEY)).toBe('520');

    pageBounds.mockReturnValue({
      x: 0, y: 0, top: 0, right: 2020, bottom: 980, left: 0,
      width: 2020, height: 980, toJSON: () => ({}),
    });
    fireEvent.mouseDown(rightHandle, { clientX: 1500 });
    fireEvent.mouseMove(window, { clientX: 800 });
    fireEvent.mouseUp(window);
    expect(page.style.gridTemplateColumns).toBe('300px minmax(0, 1fr) 1200px');
    expect(localStorage.getItem(ROUND_PR_WIDTH_KEY)).toBe('1200');

    fireEvent.click(view.getByRole('button', { name: 'Hide PR panel' }));
    expect(page.style.gridTemplateColumns).toBe('300px minmax(0, 1fr) 46px');
    expect(view.queryByRole('separator', { name: 'Resize pull request context' })).toBeNull();
    fireEvent.click(view.getByRole('button', { name: 'Show PR panel' }));
    expect(page.style.gridTemplateColumns).toBe('300px minmax(0, 1fr) 1200px');
    expect(view.getByRole('separator', { name: 'Resize pull request context' })).toBeTruthy();

    unmount();
    const restored = render(
      <AgentReviewRoundPage
        data={data}
        roundId={data.rounds[0].id}
        prView={<div />}
        onBack={vi.fn()}
        onOpenFinding={vi.fn()}
      />,
    );
    expect(restored.container.querySelector<HTMLElement>('.agent-round-page')?.style.gridTemplateColumns)
      .toBe('300px minmax(0, 1fr) 1200px');
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
    const investigation = [...container.querySelectorAll('.agent-round-stage')]
      .find(stage => stage.querySelector('.agent-round-stage__head')?.textContent?.includes('correctness'))
      ?.querySelector<HTMLDetailsElement>('.agent-round-work');
    expect(investigation).not.toBeNull();
    expect(investigation?.textContent).toContain('read files');
    expect(investigation?.open).toBe(false);
    fireEvent.click((investigation as HTMLElement).querySelector('summary') as HTMLElement);
    expect((investigation as HTMLDetailsElement).open).toBe(true);
    expect(investigation?.querySelectorAll('.agent-round-step')).toHaveLength(4);
    expect(within(investigation as HTMLElement).getAllByText('src/ChangedFile.ts')).toHaveLength(3);
    expect([...container.querySelectorAll('.agent-round-stage__prose')]
      .some(stage => stage.textContent?.includes('Full scope — 2 objectives assigned to correctness'))).toBe(true);
    expect(view.getByText(/\(author\) replied on finding-1/)).toBeTruthy();
    expect(view.getByText(/acceptance criterion now includes the author’s clarification/)).toBeTruthy();
    expect(view.getByText(/pushed abcdef0.*addresses finding-1/)).toBeTruthy();
    expect(container.querySelector('.agent-round-outcome')?.textContent).toContain('Round 1 questions remain');
  });

  it('renders a contract-backed fixed finding as a local-only timeline resolution', () => {
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
    fireEvent.click(view.getByRole('button', { name: 'View on diff' }));
    expect(onOpenFinding).toHaveBeenCalledWith('finding-1', 'src/ChangedFile.ts', 3);
    fireEvent.click(view.getByRole('button', { name: 'View in review list' }));
    expect(onOpenReviewList).toHaveBeenCalledWith('finding-1');
    fireEvent.click(view.getByRole('button', { name: 'Reopen finding' }));
    expect(onReopenFinding).toHaveBeenCalledWith('finding-1');
  });

  it('renders a fixed outcome once and removes its stale pending event from its original round', () => {
    const data = fixture();
    const secondRound = { ...data.rounds[0], id: 'round-2', agent_run_id: 'run-2' };
    data.rounds.push(secondRound);
    data.runs.push({ ...data.runs[0], id: 'run-2', reviewRoundId: 'round-2' });
    data.outcomes.push({
      finding_id: 'finding-1', user_disposition: 'published', author_response: 'fixed',
      epistemic_resolution: 'confirmed', utility_assessment: 'useful', style_edit_magnitude: 0,
    });
    const { container } = render(
      <AgentReviewRoundPage data={data} roundId="round-2" prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />,
    );
    const firstRoundHeader = container.querySelector<HTMLButtonElement>('.agent-round-section__header');
    if (firstRoundHeader === null) throw new Error('first round header missing');
    fireEvent.click(firstRoundHeader);

    expect(container.querySelectorAll('.agent-round-resolution')).toHaveLength(1);
    expect([...container.querySelectorAll('.agent-round-finding-event__content')]
      .some(event => event.textContent?.includes(data.findings[0].claim))).toBe(false);
  });

  it('returns focus and the once-only fixed outcome to a running round when its queued successor is cancelled', async () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'RUNNING' };
    const queued = {
      ...data.rounds[0], id: 'round-2', agent_run_id: 'run-2', scope: 'delta', status: 'QUEUED' as const,
    };
    data.rounds.push(queued);
    data.runs.push({ ...data.runs[0], id: 'run-2', reviewRoundId: 'round-2', status: 'running' });
    data.outcomes.push({
      finding_id: 'finding-1', user_disposition: 'published', author_response: 'fixed',
      epistemic_resolution: 'confirmed', utility_assessment: 'useful', style_edit_magnitude: 0,
    });
    const { container, rerender } = render(
      <AgentReviewRoundPage data={data} roundId="round-2" prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />,
    );
    expect(container.querySelector('.agent-round-list button.active')?.textContent).toContain('Round 2');

    const cancelled = {
      ...data,
      rounds: [data.rounds[0], { ...queued, status: 'CANCELLED' as const }],
    };
    rerender(
      <AgentReviewRoundPage data={cancelled} roundId="round-2" prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />,
    );

    await waitFor(() => expect(container.querySelector('.agent-round-list button.active')?.textContent)
      .toContain('Round 1'));
    expect(container.querySelector('.agent-round-resolution')?.closest('[data-round-id]')
      ?.getAttribute('data-round-id')).toBe(data.rounds[0].id);
  });

  it('labels published findings honestly instead of presenting them as pending drafts', () => {
    const data = fixture();
    data.findings[0] = { ...data.findings[0], lifecycle_status: 'published' };
    data.pr_comments[0] = { ...data.pr_comments[0], publishedAt: Date.now() };
    const { container } = render(
      <AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />,
    );
    const card = [...container.querySelectorAll('.agent-round-finding-event__content')]
      .find(row => row.textContent?.includes(data.findings[0].claim));
    expect(card?.querySelector('summary')?.textContent).toContain('Published');
    expect(card?.querySelector('.agent-finding-chip.pending')).toBeNull();
  });

  it('lets the reviewer stop a running round from the canonical round header', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'RUNNING' };
    const onStopRound = vi.fn();
    render(<AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} onStopRound={onStopRound} />);
    fireEvent.click(screen.getByRole('button', { name: 'Stop round' }));
    expect(onStopRound).toHaveBeenCalledWith(data.rounds[0].id);
  });

  it('steers a live round, targets its agents, starts the next round, and updates the cap', async () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'RUNNING' };
    data.assignments[0] = { ...data.assignments[0], status: 'running' };
    const onSendMessage = vi.fn(async () => true);
    const onStartRound = vi.fn(async () => true);
    const onUpdateBudget = vi.fn(async () => true);
    const { container } = render(
      <AgentReviewRoundPage
        data={data}
        roundId={data.rounds[0].id}
        prView={<div />}
        onBack={vi.fn()}
        onOpenFinding={vi.fn()}
        onSendMessage={onSendMessage}
        onStartRound={onStartRound}
        onUpdateBudget={onUpdateBudget}
      />,
    );
    const view = within(container);

    fireEvent.click(view.getByRole('button', { name: /Review panel/ }));
    expect(view.getByRole('menuitemradio', { name: /independent-verifier/ })).toBeTruthy();
    fireEvent.click(view.getByRole('menuitemradio', { name: /correctness/ }));
    const direct = view.getByRole('textbox', { name: 'Message correctness' });
    fireEvent.change(direct, { target: { value: 'Trace the cancellation path' } });
    fireEvent.keyDown(direct, { key: 'Enter', shiftKey: true });
    expect(onSendMessage).not.toHaveBeenCalled();
    fireEvent.keyDown(direct, { key: 'Enter' });
    await waitFor(() => expect(onSendMessage).toHaveBeenCalledWith(
      data.rounds[0].id, 'correctness', 'Trace the cancellation path',
    ));

    fireEvent.click(view.getByRole('button', { name: 'Increase round budget' }));
    expect(onUpdateBudget).toHaveBeenCalledWith(data.rounds[0].id, 75);

    fireEvent.click(view.getByRole('button', { name: /Trigger next round/ }));
    const nextRound = view.getByRole('textbox', { name: 'Describe round 2' });
    fireEvent.change(nextRound, { target: { value: 'Check teardown ordering' } });
    fireEvent.keyDown(nextRound, { key: 'Enter' });
    await waitFor(() => expect(onStartRound).toHaveBeenCalledWith('Check teardown ordering'));
  });

  it('stops advertising steering once a running round enters finalization', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'RUNNING', message_gate_open: false };
    const { container } = render(
      <AgentReviewRoundPage
        data={data}
        roundId={data.rounds[0].id}
        prView={<div />}
        onBack={vi.fn()}
        onOpenFinding={vi.fn()}
        onSendMessage={vi.fn()}
      />,
    );
    const view = within(container);
    expect(view.getByText(/FINALIZING/)).toBeTruthy();
    expect(view.queryByRole('button', { name: /Talk to/ })).toBeNull();
    expect(view.getByRole('textbox', { name: 'Message panel' })).toHaveProperty('disabled', true);
    fireEvent.click(view.getByRole('button', { name: /Review panel/ }));
    expect(view.queryByRole('menuitemradio', { name: /planner/ })).toBeNull();
  });

  it('does not offer the independent verifier for a trivial round', () => {
    const data = fixture();
    data.rounds[0] = {
      ...data.rounds[0], status: 'RUNNING',
      budget_json: { cost_cap_cents: 50, wall_clock_minutes: 5 },
    };
    data.verifications = [];
    const { container } = render(
      <AgentReviewRoundPage
        data={data}
        roundId={data.rounds[0].id}
        prView={<div />}
        onBack={vi.fn()}
        onOpenFinding={vi.fn()}
        onSendMessage={vi.fn()}
      />,
    );
    const view = within(container);
    fireEvent.click(view.getByRole('button', { name: /Review panel/ }));
    expect(view.queryByRole('menuitemradio', { name: /independent-verifier/ })).toBeNull();
  });

  it('renders guidance replies without duplicating their internal assignment stage', () => {
    const data = fixture();
    data.round_messages.push({
      id: 'message-1', round_id: data.rounds[0].id, assignment_id: 'guidance-assignment',
      target: 'correctness', sender: 'you', body: 'Check cancellation', status: 'completed',
      response: 'Cancellation is covered.', created_at: 1, completed_at: 2,
    });
    data.assignments.push({
      ...data.assignments[0], id: 'guidance-assignment',
      understanding_summary: 'Duplicate guidance stage', status: 'completed',
    });
    const { container } = render(
      <AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} />,
    );
    const view = within(container);
    expect(view.getByText('Check cancellation')).toBeTruthy();
    expect(view.getByText('Cancellation is covered.')).toBeTruthy();
    expect(view.queryByText('Duplicate guidance stage')).toBeNull();
    expect([...container.querySelectorAll('.agent-round-stage')]
      .filter(stage => stage.querySelector('.agent-round-stage__head > span')?.textContent === 'correctness'
        && stage.querySelector('.agent-round-work') !== null)).toHaveLength(1);
  });

  it('shows queued rounds as cancellable without a terminal outcome', () => {
    const data = fixture();
    data.rounds[0] = { ...data.rounds[0], status: 'QUEUED', message_gate_open: false };
    const onStopRound = vi.fn();
    const { container } = render(
      <AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} onStopRound={onStopRound} />,
    );
    const view = within(container);
    expect(view.getByText(/QUEUED/)).toBeTruthy();
    expect(container.querySelector('.agent-round-outcome')).toBeNull();
    fireEvent.click(view.getByRole('button', { name: 'Cancel queued round' }));
    expect(onStopRound).toHaveBeenCalledWith(data.rounds[0].id);
  });

  it('does not lower a live budget below persisted spend', () => {
    const data = fixture();
    data.rounds[0] = {
      ...data.rounds[0], status: 'RUNNING', cost_cents: 70,
      budget_json: { ...data.rounds[0].budget_json, cost_cap_cents: 75 },
    };
    const { container } = render(
      <AgentReviewRoundPage data={data} roundId={data.rounds[0].id} prView={<div />} onBack={vi.fn()} onOpenFinding={vi.fn()} onUpdateBudget={vi.fn()} />,
    );
    expect(within(container).getByRole('button', { name: 'Decrease round budget' }))
      .toHaveProperty('disabled', true);
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
