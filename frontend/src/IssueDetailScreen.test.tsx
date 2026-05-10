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
import { act } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import IssueDetailScreen from './IssueDetailScreen';
import type { IssueDetailDto, ReactionsDto } from './types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const ZERO_REACTIONS: ReactionsDto = {
  plusOne: 0, minusOne: 0, laugh: 0, hooray: 0,
  confused: 0, heart: 0, rocket: 0, eyes: 0,
};

function detail(over: Partial<IssueDetailDto> = {}): IssueDetailDto {
  return {
    id: 1,
    number: 42,
    title: 'flaky import on x86',
    body: 'Steps to reproduce…',
    author: 'jack',
    authorAvatarUrl: null,
    state: 'open',
    htmlUrl: 'https://github.com/o/r/issues/42',
    createdAt: '2026-05-10T08:00:00Z',
    updatedAt: '2026-05-10T08:00:00Z',
    closedAt: null,
    labels: [],
    assignees: [],
    milestone: null,
    comments: [
      {
        id: 1001,
        author: 'maria',
        authorAvatarUrl: null,
        body: 'Saw the same on my run too.',
        createdAt: '2026-05-10T09:00:00Z',
        reactions: { ...ZERO_REACTIONS, plusOne: 2 },
      },
    ],
    ...over,
  };
}

type BridgeStub = {
  getIssueDetail: ReturnType<typeof vi.fn>;
  addIssueDetailCommentReaction: ReturnType<typeof vi.fn>;
  createIssueComment: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const stub: BridgeStub = {
    getIssueDetail: vi.fn().mockResolvedValue(detail()),
    addIssueDetailCommentReaction: vi.fn().mockResolvedValue({ result: 'reacted' }),
    createIssueComment: vi.fn().mockResolvedValue(null),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = stub;
  return stub;
}

describe('IssueDetailScreen — reactions (I3b)', () => {
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders existing reaction chips on a comment', async () => {
    installBridge();
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    // The existing +2 👍 reaction surfaces as a chip with the count.
    await waitFor(() => {
      expect(screen.getByLabelText(/Add reaction \(2 so far\)/i)).toBeDefined();
    });
  });

  it('clicking an existing 👍 chip adds the reaction and bumps the count optimistically', async () => {
    const stub = installBridge();
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const chip = await screen.findByLabelText(/Add reaction \(2 so far\)/i);

    await act(async () => { fireEvent.click(chip); });

    // The bridge call fires with the right args …
    await waitFor(() => {
      expect(stub.addIssueDetailCommentReaction).toHaveBeenCalledWith('o', 'r', 1001, '+1');
    });
    // … and the count optimistically bumps to 3 without waiting for a refetch.
    expect(screen.getByLabelText(/Add reaction \(3 so far\)/i)).toBeDefined();
  });

  it('rolls the count back when the bridge call rejects', async () => {
    installBridge({
      addIssueDetailCommentReaction: vi.fn().mockRejectedValue(new Error('rate limited')),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const chip = await screen.findByLabelText(/Add reaction \(2 so far\)/i);

    await act(async () => { fireEvent.click(chip); });

    // Optimistic +1 reverts on failure — the chip is back to count=2.
    await waitFor(() => {
      expect(screen.getByLabelText(/Add reaction \(2 so far\)/i)).toBeDefined();
    });
  });

  it('comment with no reactions shows the smiley-plus add button', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({
        comments: [
          {
            id: 1002,
            author: 'maria',
            authorAvatarUrl: null,
            body: 'second comment',
            createdAt: '2026-05-10T09:00:00Z',
            reactions: ZERO_REACTIONS,
          },
        ],
      })),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /add a reaction/i })).toBeDefined();
    });
  });
});
