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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ReviewThreadPage from './ReviewThreadPage';
import type {
  Bridge,
  ReviewFindingDto,
  ReviewPanelMessageDto,
  ReviewParticipantDto,
  ReviewPassDetailDto,
  ReviewPassDto,
} from '../types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('ReviewThreadPage', () => {
  it('renders the roster, transcript, findings, and suggested verdict', async () => {
    const detail = buildDetail({
      verdict: 'COMMENT',
      findings: [
        finding({ id: 'f-blocker', severity: 'BLOCKER', body: 'Reachable null deref.', path: 'src/foo.ts', line: 12 }),
        finding({ id: 'f-nit', severity: 'NIT', body: 'Trailing whitespace.', path: 'src/bar.ts', line: null }),
      ],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);

    // Header shows the PR ref + verdict pill.
    await waitFor(() => {
      expect(screen.getByText(/Review · acme\/widget#42/)).toBeTruthy();
    });
    expect(screen.getByText('comment')).toBeTruthy();

    // Each persona label can appear in multiple places: the roster
    // row, and (for reviewers / moderator) above any transcript
    // bubble they authored. getAllByText asserts the label rendered
    // without nailing down placement.
    expect(screen.getAllByText('Moderator').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Claude (Anthropic)').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('You').length).toBeGreaterThanOrEqual(1);

    // Transcript bubble contains the kickoff body.
    expect(screen.getByText(/Reviewing acme\/widget#42/)).toBeTruthy();
    expect(screen.getByText('Mostly fine.')).toBeTruthy();

    // Findings render with the file:line anchor and a severity chip
    // each — the line=null finding falls back to just the path.
    // Each finding body appears twice now (the read-only Findings
    // section and the publish form's checkbox list); the file:line
    // anchors do too, so just assert >=1 match for each.
    expect(screen.getAllByText('Reachable null deref.').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('src/foo.ts:12').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Trailing whitespace.').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('src/bar.ts').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByLabelText('severity-blocker').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByLabelText('severity-nit').length).toBeGreaterThanOrEqual(1);
  });

  it('shows an empty state when no pass exists for the thread', async () => {
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => null),
    });

    render(<ReviewThreadPage threadId="thread-empty" onBack={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText(/No review pass found/i)).toBeTruthy();
    });
  });

  it('renders a status chip per finding and default-unchecks DISPUTED ones in the publish form', async () => {
    // Multi-reviewer pass shape: one AGREED (panel consensus) + one
    // DISPUTED (only one reviewer raised). The status chip should
    // render for each, and the publish form should default the
    // disputed finding to UN-checked so the user opts in deliberately.
    const detail = buildDetail({
      verdict: 'COMMENT',
      findings: [
        finding({ id: 'f-agreed', severity: 'BLOCKER', status: 'AGREED',
            body: 'Both reviewers flagged this.', path: 'src/foo.ts', line: 12 }),
        finding({ id: 'f-disputed', severity: 'NIT', status: 'DISPUTED',
            body: '[Claude] Solo nit.', path: 'src/bar.ts', line: 7 }),
      ],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/Verdict suggestion/i));

    // Both status chips render — by aria-label so the test doesn't
    // hinge on the exact severity text getting matched first.
    expect(screen.getAllByLabelText('status-agreed').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByLabelText('status-disputed').length).toBeGreaterThanOrEqual(1);

    // Publish counter reflects 1/2 included (the agreed one).
    expect(screen.getByText(/Findings to post \(1\/2\)/)).toBeTruthy();
    const [agreedBox, disputedBox] = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(agreedBox.checked).toBe(true);
    expect(disputedBox.checked).toBe(false);
  });

  it('surfaces a backend error inline without rendering the panel sections', async () => {
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => {
        throw new Error('backend GET /api/reviews/by-thread/thread-broken returned 500');
      }),
    });

    render(<ReviewThreadPage threadId="thread-broken" onBack={() => {}} />);

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('returned 500');
    });
    expect(screen.queryByText('Panel')).toBeNull();
    expect(screen.queryByText(/Findings/i)).toBeNull();
  });

  it('renders the publish form with the suggested verdict pre-selected and all findings checked', async () => {
    const detail = buildDetail({
      verdict: 'APPROVE',
      findings: [finding({ id: 'f1', body: 'Nit.' })],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);

    await waitFor(() => screen.getByText(/Verdict suggestion/i));
    const approveRadio = screen.getByRole('radio', { name: 'APPROVE' }) as HTMLInputElement;
    expect(approveRadio.checked).toBe(true);
    // All findings default to included.
    expect(screen.getByText(/Findings to post \(1\/1\)/)).toBeTruthy();
    const findingCheckbox = screen.getByRole('checkbox') as HTMLInputElement;
    expect(findingCheckbox.checked).toBe(true);

    const publishBtn = screen.getByText('Post review to PR') as HTMLButtonElement;
    expect(publishBtn.disabled).toBe(false);
  });

  it('publishes the pass with the selected verdict + finding ids and re-renders the published state', async () => {
    const initial = buildDetail({
      verdict: 'COMMENT',
      findings: [
        finding({ id: 'f-keep', severity: 'MAJOR', body: 'Keep me.' }),
        finding({ id: 'f-drop', severity: 'NIT', body: 'Drop me.' }),
      ],
    });
    const published: ReviewPassDetailDto = {
      ...initial,
      pass: { ...initial.pass, phase: 'PUBLISHED', verdict: 'REQUEST_CHANGES' },
      findings: initial.findings.map(f => ({ ...f, status: 'POSTED' })),
    };
    const publishReviewPass = vi.fn(async () => published);
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => initial),
      publishReviewPass,
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/Verdict suggestion/i));

    // Switch verdict + un-check the second finding.
    await act(async () => {
      fireEvent.click(screen.getByRole('radio', { name: 'REQUEST_CHANGES' }));
    });
    const dropCheckbox = screen.getAllByRole('checkbox')[1] as HTMLInputElement;
    await act(async () => { fireEvent.click(dropCheckbox); });
    expect(dropCheckbox.checked).toBe(false);

    await act(async () => {
      fireEvent.click(screen.getByText('Post review to PR'));
    });

    expect(publishReviewPass).toHaveBeenCalledTimes(1);
    expect(publishReviewPass).toHaveBeenCalledWith('pass-1', 'REQUEST_CHANGES', ['f-keep']);
    // Published state replaces the form with a one-line confirmation
    // and shows a Published badge in the heading.
    await waitFor(() => expect(screen.getByLabelText('published')).toBeTruthy());
    expect(screen.getByText(/Posted to the PR as a/i).textContent).toContain('REQUEST_CHANGES');
    // The post button is gone — the form is locked once published.
    expect(screen.queryByText('Post review to PR')).toBeNull();
  });

  it('renders the arbitration ballot when phase=ARBITRATE and routes include/drop to the bridge', async () => {
    // ARBITRATE phase: publish form should NOT show; ballot does.
    // Each disputed finding gets [Include] [Drop] buttons.
    const base = buildDetail({ verdict: 'COMMENT' });
    const arbitrating: ReviewPassDetailDto = {
      ...base,
      pass: { ...base.pass, phase: 'ARBITRATE', endedAt: null },
      findings: [
        finding({ id: 'd1', severity: 'NIT', status: 'DISPUTED',
            body: '[Claude] solo nit', path: 'src/a.ts', line: 1 }),
        finding({ id: 'd2', severity: 'NIT', status: 'DISPUTED',
            body: '[GPT] solo nit', path: 'src/b.ts', line: 2 }),
      ],
    };
    const afterInclude: ReviewPassDetailDto = {
      ...arbitrating,
      findings: arbitrating.findings.map(f =>
        f.id === 'd1' ? { ...f, status: 'ARBITRATED', resolution: 'include' } : f),
    };
    const arbitrateReviewFinding = vi.fn(async () => afterInclude);
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => arbitrating),
      arbitrateReviewFinding,
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/Arbitration ballot/i));

    // Publish form is hidden — the user must arbitrate first.
    expect(screen.queryByText('Post review to PR')).toBeNull();
    expect(screen.queryByText(/Verdict suggestion/i)).toBeNull();

    // Two ballot items each with Include + Drop buttons. Look by
    // role to avoid matching the same words used inside the hint
    // text above the list.
    const includes = screen.getAllByRole('button', { name: 'Include' });
    const drops = screen.getAllByRole('button', { name: 'Drop' });
    expect(includes).toHaveLength(2);
    expect(drops).toHaveLength(2);

    // Clicking the first Include calls the bridge with 'include'.
    await act(async () => {
      fireEvent.click(includes[0]);
    });
    expect(arbitrateReviewFinding).toHaveBeenCalledWith('pass-1', 'd1', 'include');
  });

  it('surfaces the backend error inline and leaves the form mounted for retry', async () => {
    const detail = buildDetail({
      verdict: 'COMMENT',
      findings: [finding({ id: 'f1', body: 'A.' })],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
      publishReviewPass: vi.fn(async () => {
        throw new Error('backend POST /api/reviews/pass-1/publish returned 502: '
            + 'GitHub rejected the review');
      }),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText('Post review to PR'));

    await act(async () => {
      fireEvent.click(screen.getByText('Post review to PR'));
    });

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('GitHub rejected the review');
    });
    // Form stays so the user can retry after fixing the upstream issue.
    expect(screen.getByText('Post review to PR')).toBeTruthy();
  });
});

function buildDetail(
    overrides: { verdict?: ReviewPassDto['verdict']; findings?: ReviewFindingDto[] },
): ReviewPassDetailDto {
  const moderator = participant({
    id: 'p-mod', kind: 'MODERATOR', personaLabel: 'Moderator',
  });
  const reviewer = participant({
    id: 'p-rev', kind: 'REVIEWER', personaLabel: 'Claude (Anthropic)',
    credentialId: 'claude',
  });
  const human = participant({
    id: 'p-you', kind: 'HUMAN', personaLabel: 'You',
  });
  return {
    pass: {
      id: 'pass-1',
      threadId: 'thread-1',
      repoFullName: 'acme/widget',
      prNumber: 42,
      headSha: 'abc123',
      phase: 'TERMINATE',
      round: 0,
      roundCap: 3,
      costCapMilli: 500,
      costUsdMilli: 0,
      verdict: overrides.verdict ?? null,
      createdAt: '2026-05-22T12:00:00Z',
      endedAt: '2026-05-22T12:00:10Z',
      spawnedBuildThreadId: null,
    },
    participants: [moderator, reviewer, human],
    messages: [
      message({
        id: 'm1', participantId: moderator.id, phase: 'KICKOFF',
        body: 'Reviewing acme/widget#42 with Claude. Independent phase starting.',
      }),
      message({
        id: 'm2', participantId: reviewer.id, phase: 'INDEPENDENT',
        body: 'Mostly fine.',
      }),
    ],
    findings: overrides.findings ?? [],
  };
}

function participant(overrides: Partial<ReviewParticipantDto>): ReviewParticipantDto {
  return {
    id: 'p',
    reviewPassId: 'pass-1',
    kind: 'REVIEWER',
    credentialId: null,
    personaLabel: 'Reviewer',
    model: null,
    color: null,
    createdAt: '2026-05-22T12:00:00Z',
    ...overrides,
  };
}

function message(overrides: Partial<ReviewPanelMessageDto>): ReviewPanelMessageDto {
  return {
    id: 'm',
    reviewPassId: 'pass-1',
    participantId: 'p',
    phase: 'KICKOFF',
    round: 0,
    body: '',
    mentions: [],
    refs: [],
    costUsdMilli: 0,
    createdAt: '2026-05-22T12:00:00Z',
    ...overrides,
  };
}

function finding(overrides: Partial<ReviewFindingDto>): ReviewFindingDto {
  return {
    id: 'f',
    reviewPassId: 'pass-1',
    path: null,
    line: null,
    severity: 'MAJOR',
    status: 'AGREED',
    body: '',
    resolution: null,
    postedCommentId: null,
    createdAt: '2026-05-22T12:00:00Z',
    ...overrides,
  };
}

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => null),
    ...overrides,
  } as Partial<Bridge>;
}
