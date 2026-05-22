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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
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
    expect(screen.getByText('Reachable null deref.')).toBeTruthy();
    expect(screen.getByText('src/foo.ts:12')).toBeTruthy();
    expect(screen.getByText('Trailing whitespace.')).toBeTruthy();
    expect(screen.getByText('src/bar.ts')).toBeTruthy();
    expect(screen.getByLabelText('severity-blocker')).toBeTruthy();
    expect(screen.getByLabelText('severity-nit')).toBeTruthy();
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

  it('renders the publish placeholder as disabled with the suggested verdict copy', async () => {
    const detail = buildDetail({ verdict: 'APPROVE', findings: [] });
    installBridge({
      getReviewPassByThread: vi.fn(async (): Promise<ReviewPassDetailDto | null> => detail),
    });

    render(<ReviewThreadPage threadId="thread-1" onBack={() => {}} />);

    await waitFor(() => screen.getByText(/Suggested verdict/i));
    const publishBtn = screen.getByText('Post review to PR (coming soon)') as HTMLButtonElement;
    expect(publishBtn.disabled).toBe(true);
    // The verdict copy is split across "Suggested verdict: " + a
    // <strong> for the value, so traverse the publish section's
    // <p> and assert on its full textContent.
    const publishHint = screen.getByText(/Suggested verdict:/i).closest('p');
    expect(publishHint?.textContent).toContain('APPROVE');
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
