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
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ReviewThreadPage from './ReviewThreadPage';
import type {
  Bridge,
  PullRequestCommitDto,
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

    // The PR title shows in the header and the Reviewing card.
    await waitFor(() => {
      expect(screen.getAllByText('Add retry logic').length).toBeGreaterThanOrEqual(1);
    });
    // The breadcrumb shows the repo + PR ref (split across elements now
    // that the PR ref can be a link).
    expect(screen.getAllByText(/acme\/widget/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('PR #42')).toBeTruthy();
    expect(screen.getByText('comment')).toBeTruthy();

    // Each persona label can appear in multiple places: the roster
    // row, and (for reviewers / the lead) above any transcript
    // bubble they authored. getAllByText asserts the label rendered
    // without nailing down placement.
    expect(screen.getAllByText('Lead').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Claude (Anthropic)').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('You').length).toBeGreaterThanOrEqual(1);
    // The "Moderator" terminology is fully scrubbed from the panel.
    expect(screen.queryByText('Moderator')).toBeNull();

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

  it('renders the agenda widget with per-phase statuses and a summary line', async () => {
    const detail = buildDetail({});
    window.bridge = {
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    } as unknown as typeof window.bridge;

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('4 phases (1 done · 1 in progress · 2 open)')).toBeTruthy();
    });
    expect(screen.getByText('Run parallel reviews')).toBeTruthy();
    expect(screen.getByText('Cross-examine')).toBeTruthy();
    expect(screen.getByText('Classify consensus')).toBeTruthy();
    expect(screen.getByText('Debate disputes')).toBeTruthy();
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

  it('renders the empty-agenda placeholder while the pass is still running', async () => {
    const base = buildDetail({});
    const running: ReviewPassDetailDto = {
      ...base,
      pass: { ...base.pass, phase: 'INDEPENDENT', endedAt: null },
      agenda: [],
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => running),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/Lead is laying out the agenda/i));
  });

  it('renders an all-done agenda with every glyph checked', async () => {
    const base = buildDetail({});
    const done: ReviewPassDetailDto = {
      ...base,
      agenda: base.agenda.map(p => ({ ...p, status: 'DONE' })),
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => done),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => {
      expect(screen.getByText('4 phases (4 done · 0 in progress · 0 open)')).toBeTruthy();
    });
  });

  it('badges the LEAD seat as "lead" and never shows "Moderator"', async () => {
    const detail = buildDetail({});
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getAllByText('Add retry logic'));

    // The lead's transcript bubble + roster row carry the lead tag,
    // and the old Moderator wording is gone everywhere.
    expect(screen.getAllByText('lead').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('Moderator')).toBeNull();
    expect(screen.queryByText('moderator')).toBeNull();
  });

  it('seats five reviewers with their persona-color avatars', async () => {
    // jsdom normalises the gradient's hex stops to rgb(), so match on that.
    const seats = [
      { label: 'Claude', rgb: 'rgb(217, 119, 6)' },
      { label: 'GPT-5', rgb: 'rgb(16, 185, 129)' },
      { label: 'DeepSeek', rgb: 'rgb(37, 99, 235)' },
      { label: 'Sonnet', rgb: 'rgb(167, 139, 250)' },
      { label: 'Gemini', rgb: 'rgb(52, 211, 153)' },
    ];
    const detail = buildDetail({});
    detail.participants = [
      participant({ id: 'p-lead', kind: 'LEAD', personaLabel: 'Lead' }),
      ...seats.map((s, i) =>
        participant({ id: `p-${i}`, kind: 'REVIEWER', personaLabel: s.label })),
      participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' }),
    ];
    // The default fixture's messages reference the original participants;
    // clear them so the roster is the only thing under test.
    detail.messages = [];
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByLabelText('Panel roster'));

    const roster = screen.getByLabelText('Panel roster');
    for (const seat of seats) {
      const row = within(roster).getByText(seat.label).closest('li');
      expect(row).not.toBeNull();
      // The avatar is the row's aria-hidden span; its background carries
      // the persona gradient (which contains the seat's signature hex).
      const style = row!.querySelector('[aria-hidden]')?.getAttribute('style') ?? '';
      expect(style).toContain('linear-gradient');
      expect(style).toContain(seat.rgb);
    }
    // All five seats present — no row dropped / overflowed out of the rail.
    expect(within(roster).getAllByText(/^(Claude|GPT-5|DeepSeek|Sonnet|Gemini)$/))
      .toHaveLength(5);
  });

  it('toggles the @mention transcript filter on click, clears on re-click, switches on a different chip', async () => {
    const base = buildDetail({});
    const lead = participant({ id: 'p-mod', kind: 'LEAD', personaLabel: 'Lead' });
    const claude = participant({ id: 'p-claude', kind: 'REVIEWER', personaLabel: 'Claude' });
    const gpt = participant({ id: 'p-gpt', kind: 'REVIEWER', personaLabel: 'GPT-5' });
    const detail: ReviewPassDetailDto = {
      ...base,
      participants: [lead, claude, gpt, participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' })],
      messages: [
        message({ id: 'd', participantId: 'p-mod', phase: 'CROSS_REVIEW',
            mentions: ['p-claude', 'p-gpt'], body: '@Claude @GPT-5 cross-examine' }),
        message({ id: 'rc', participantId: 'p-claude', phase: 'CROSS_REVIEW', body: 'Claude says.' }),
        message({ id: 'rg', participantId: 'p-gpt', phase: 'CROSS_REVIEW', body: 'GPT says.' }),
      ],
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByRole('button', { name: '@Claude' }));

    // No filter initially → both reviewer messages visible.
    expect(screen.getByText('Claude says.')).toBeTruthy();
    expect(screen.getByText('GPT says.')).toBeTruthy();

    // Click @Claude → filter on; indicator shows; GPT's message hidden.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '@Claude' })); });
    expect(screen.getByText(/Filtered to/i).textContent).toContain('Claude');
    expect(screen.queryByText('GPT says.')).toBeNull();
    expect(screen.getByText('Claude says.')).toBeTruthy();

    // Click @Claude again → filter cleared; both visible again.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '@Claude' })); });
    expect(screen.queryByText(/Filtered to/i)).toBeNull();
    expect(screen.getByText('GPT says.')).toBeTruthy();

    // Filter to Claude, then click @GPT-5 → switches without a clear.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '@Claude' })); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '@GPT-5' })); });
    expect(screen.getByText(/Filtered to/i).textContent).toContain('GPT-5');
    expect(screen.getByText('GPT says.')).toBeTruthy();
    expect(screen.queryByText('Claude says.')).toBeNull();
  });

  it('coalesces a multi-dispatch lead turn into one bubble with an arrow chip per addressee', async () => {
    const base = buildDetail({});
    const lead = participant({ id: 'p-mod', kind: 'LEAD', personaLabel: 'Lead' });
    const claude = participant({ id: 'p-claude', kind: 'REVIEWER', personaLabel: 'Claude' });
    const gpt = participant({ id: 'p-gpt', kind: 'REVIEWER', personaLabel: 'GPT-5' });
    const detail: ReviewPassDetailDto = {
      ...base,
      participants: [lead, claude, gpt, participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' })],
      messages: [
        // Two consecutive lead dispatches in one round → one group.
        message({ id: 'd1', participantId: 'p-mod', phase: 'CROSS_REVIEW', round: 1,
            mentions: ['p-claude'], body: '@Claude find candidate issues' }),
        message({ id: 'd2', participantId: 'p-mod', phase: 'CROSS_REVIEW', round: 1,
            mentions: ['p-gpt'], body: '@GPT-5 find candidate issues' }),
        // Reviewer responses land below as separate bubbles.
        message({ id: 'r1', participantId: 'p-claude', phase: 'CROSS_REVIEW', round: 1,
            body: 'Claude response.' }),
        message({ id: 'r2', participantId: 'p-gpt', phase: 'CROSS_REVIEW', round: 1,
            body: 'GPT response.' }),
      ],
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/dispatched 2 reviewers in parallel/i));

    // One fan-out group, two arrow chips in dispatch order.
    expect(screen.getAllByText(/dispatched 2 reviewers in parallel/i)).toHaveLength(1);
    const arrows = screen.getAllByRole('button', { name: /^@/ });
    const arrowLabels = arrows.map(b => b.textContent);
    expect(arrowLabels).toContain('@Claude');
    expect(arrowLabels).toContain('@GPT-5');

    // Reviewer responses render as their own bubbles below the group.
    expect(screen.getByText('Claude response.')).toBeTruthy();
    expect(screen.getByText('GPT response.')).toBeTruthy();
  });

  it('gates the spawn-build button: disabled with no Major+ AGREED finding, enabled with one', async () => {
    const nitOnly = buildDetail({
      findings: [finding({ id: 'n', severity: 'NIT', status: 'AGREED', body: 'Nit.' })],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => nitOnly),
    });
    const { unmount } = render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByRole('button', { name: '→ Spawn build thread' }));
    expect((screen.getByRole('button', { name: '→ Spawn build thread' }) as HTMLButtonElement).disabled)
        .toBe(true);
    unmount();
    cleanup();

    const withMajor = buildDetail({
      findings: [finding({ id: 'm', severity: 'MAJOR', status: 'AGREED', body: 'Real bug.' })],
    });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => withMajor),
    });
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByRole('button', { name: '→ Spawn build thread' }));
    expect((screen.getByRole('button', { name: '→ Spawn build thread' }) as HTMLButtonElement).disabled)
        .toBe(false);
  });

  it('shows the spawned-build breadcrumb instead of the button once a build thread exists', async () => {
    const base = buildDetail({
      findings: [finding({ id: 'm', severity: 'MAJOR', status: 'AGREED', body: 'Real bug.' })],
    });
    const spawned: ReviewPassDetailDto = {
      ...base,
      pass: { ...base.pass, spawnedBuildThreadId: 'build-thread-abcdef12' },
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => spawned),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText(/build thread/i));
    // The actionable button is replaced by the breadcrumb strip naming
    // the spawned thread (id sliced to 8 chars: "build-th").
    expect(screen.queryByRole('button', { name: '→ Spawn build thread' })).toBeNull();
    expect(screen.getByText(/build-th/i)).toBeTruthy();
  });

  it('SAFETY: no transcript/filter/finding affordance posts to GitHub — only PublishSection does', async () => {
    const lead = participant({ id: 'p-mod', kind: 'LEAD', personaLabel: 'Lead' });
    const claude = participant({ id: 'p-claude', kind: 'REVIEWER', personaLabel: 'Claude' });
    const base = buildDetail({
      findings: [finding({ id: 'f1', severity: 'MAJOR', status: 'AGREED', body: 'Bug.' })],
    });
    const detail: ReviewPassDetailDto = {
      ...base,
      participants: [lead, claude, participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' })],
      messages: [
        message({ id: 'd', participantId: 'p-mod', phase: 'CONSENSUS',
            mentions: ['p-claude'], refs: ['finding:f1'], body: '@Claude confirm' }),
        message({ id: 'r', participantId: 'p-claude', phase: 'CONSENSUS', body: 'Confirmed.' }),
      ],
    };
    const publishReviewPass = vi.fn(async () => detail);
    const spawnBuildFromReview = vi.fn(
        async (): Promise<{ threadId: string; taskId: string | null; mode: string }> =>
          ({ threadId: 't', taskId: null, mode: 'x' }));
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
      publishReviewPass,
      spawnBuildFromReview,
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByRole('button', { name: '@Claude' }));

    // Exercise every non-publish affordance: filter chip, clear, and a
    // finding row jump.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '@Claude' })); });
    await act(async () => { fireEvent.click(screen.getByText(/✕ clear/i)); });
    const findingRow = screen.getAllByRole('button').find(
        b => b.textContent?.includes('Bug.'));
    if (findingRow !== undefined) {
      await act(async () => { fireEvent.click(findingRow); });
    }

    // Nothing reached GitHub. The publish endpoint is only hit through
    // the PublishSection's explicit "Post review to PR" confirm.
    expect(publishReviewPass).not.toHaveBeenCalled();
    expect(spawnBuildFromReview).not.toHaveBeenCalled();
  });

  it('gives the five reviewer seats five mutually-distinct avatar backgrounds', async () => {
    // Guards the regression where two personas collapse onto the same
    // gradient — each seat row must carry its own avatar background.
    const labels = ['Claude', 'GPT-5', 'DeepSeek', 'Sonnet', 'Gemini'];
    const detail = buildDetail({});
    detail.participants = [
      participant({ id: 'p-lead', kind: 'LEAD', personaLabel: 'Lead' }),
      ...labels.map((label, i) =>
        participant({ id: `p-${i}`, kind: 'REVIEWER', personaLabel: label })),
      participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' }),
    ];
    detail.messages = [];
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByLabelText('Panel roster'));

    const roster = screen.getByLabelText('Panel roster');
    const backgrounds = labels.map(label => {
      const row = within(roster).getByText(label).closest('li');
      const style = row!.querySelector('[aria-hidden]')?.getAttribute('style') ?? '';
      const bg = /background[^:]*:\s*([^;]+)/i.exec(style)?.[1]?.trim() ?? '';
      expect(bg).not.toBe('');
      return bg;
    });
    // All five gradients are unique — no two seats share an identity.
    expect(new Set(backgrounds).size).toBe(5);
  });

  it('shows the consensus empty-state hints when no findings have landed', async () => {
    // The default fixture carries no findings, so both rail groups fall
    // back to their italic placeholder copy rather than an empty box.
    const detail = buildDetail({ findings: [] });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText('Nothing locked in yet.'));
    expect(screen.getByText('All disagreements resolved or arbitrated.')).toBeTruthy();
  });

  it('opens the PR in-app when the PR ref is clicked (repo split into owner/repo)', async () => {
    const detail = buildDetail({});
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });
    const onOpenPr = vi.fn();
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} onOpenPr={onOpenPr} />);

    await waitFor(() => screen.getByRole('button', { name: 'PR #42' }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'PR #42' })); });
    expect(onOpenPr).toHaveBeenCalledWith('acme', 'widget', 42);
  });

  it('steers a reviewer: @ autocomplete picks a seat and Send posts the steer message', async () => {
    const detail = buildDetail({});
    const steerReview = vi.fn(
        async (_passId: string, _targetId: string, _message: string): Promise<ReviewPassDetailDto> => detail);
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
      steerReview,
    });
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByPlaceholderText(/Message the panel/));

    const box = screen.getByPlaceholderText(/Message the panel/);
    // Typing @ opens the autocomplete with the addressable seats.
    await act(async () => { fireEvent.change(box, { target: { value: '@' } }); });
    const option = await screen.findByRole('option', { name: /Claude/ });
    await act(async () => { fireEvent.click(option); });
    // Type the rest of the message, then send.
    await act(async () => {
      fireEvent.change(box, { target: { value: '@Claude (Anthropic) please recheck the null path' } });
    });
    await act(async () => { fireEvent.click(screen.getByText('Send')); });

    expect(steerReview).toHaveBeenCalledTimes(1);
    const [passId, targetId, message] = steerReview.mock.calls[0];
    expect(passId).toBe('pass-1');
    expect(targetId).toBe('p-rev');             // the reviewer seat, not the human
    expect(message).toContain('please recheck the null path');
  });

  it('raises the budget: "+ $0.50" posts a cost bump and "+ 1 round" a debate-round bump', async () => {
    const detail = buildDetail({});
    const raiseReviewBudget = vi.fn(
        async (_passId: string, _addCostMilli: number, _addRounds: number): Promise<ReviewPassDetailDto> => detail);
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
      raiseReviewBudget,
    });
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText('+ $0.50'));

    await act(async () => { fireEvent.click(screen.getByText('+ $0.50')); });
    expect(raiseReviewBudget).toHaveBeenLastCalledWith('pass-1', 500, 0);

    await act(async () => { fireEvent.click(screen.getByText('+ 1 round')); });
    expect(raiseReviewBudget).toHaveBeenLastCalledWith('pass-1', 0, 1);
  });

  it('caps the cost raise at $10: the button greys out and reads "$10 max"', async () => {
    const detail = buildDetail({});
    detail.pass.costCapMilli = 10_000;          // already at the ceiling
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText('$10 max'));
    expect((screen.getByText('$10 max') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the PR commits (subject only) in the Reviewing card', async () => {
    const detail = buildDetail({});
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
      fetchPrCommits: vi.fn(async (): Promise<PullRequestCommitDto[]> => [
        { sha: 'abc1234567', authorLogin: 'maria-l', authorName: 'Maria',
          authoredAt: null, message: 'Wire the retry backoff\n\nlong body text' },
        { sha: 'def7654321', authorLogin: 'maria-l', authorName: 'Maria',
          authoredAt: null, message: 'Fix the flake' },
      ]),
    });
    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);

    await waitFor(() => screen.getByText('abc1234'));   // 7-char short sha
    expect(screen.getByText('Wire the retry backoff')).toBeTruthy();  // subject only
    expect(screen.getByText('Fix the flake')).toBeTruthy();
    expect(screen.queryByText(/long body text/)).toBeNull();          // body dropped
  });

  it('renders leaked DSML tool-call tokens as clean tool cards, not raw markup', async () => {
    const base = buildDetail({});
    const claude = participant({ id: 'p-c', kind: 'REVIEWER', personaLabel: 'Claude' });
    const detail: ReviewPassDetailDto = {
      ...base,
      participants: [claude, participant({ id: 'p-you', kind: 'HUMAN', personaLabel: 'You' })],
      messages: [
        message({
          id: 'tc', participantId: 'p-c', phase: 'INDEPENDENT',
          body: '<｜｜DSML｜｜tool_calls> <｜｜DSML｜｜invoke name="search_code"> '
            + '<｜｜DSML｜｜parameter name="query" string="true">requireColumnSize'
            + '</｜｜DSML｜｜parameter> </｜｜DSML｜｜invoke> </｜｜DSML｜｜tool_calls>',
        }),
      ],
    };
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);
    await waitFor(() => screen.getByText('search_code'));
    // The tool name + parameter render as structured fields.
    expect(screen.getByText('query')).toBeTruthy();
    expect(screen.getByText('requireColumnSize')).toBeTruthy();
    // The raw DSML markup never reaches the DOM.
    expect(screen.queryByText(/DSML/)).toBeNull();
  });
});

function buildDetail(
    overrides: { verdict?: ReviewPassDto['verdict']; findings?: ReviewFindingDto[] },
): ReviewPassDetailDto {
  const moderator = participant({
    id: 'p-mod', kind: 'LEAD', personaLabel: 'Lead',
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
      agendaJson: null,
    },
    prTitle: 'Add retry logic',
    agenda: [
      { id: 'p_independent', title: 'Run parallel reviews', status: 'DONE' },
      { id: 'p_crossreview', title: 'Cross-examine', status: 'IN_PROGRESS' },
      { id: 'p_consensus', title: 'Classify consensus', status: 'OPEN' },
      { id: 'p_debate', title: 'Debate disputes', status: 'OPEN' },
    ],
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
    payloadKind: 'prose',
    payloadJson: null,
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
