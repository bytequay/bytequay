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
    timeline: [],
    subscribed: false,
    origin: 'user',
    ...over,
  };
}

type BridgeStub = {
  getIssueDetail: ReturnType<typeof vi.fn>;
  addIssueDetailCommentReaction: ReturnType<typeof vi.fn>;
  createIssueComment: ReturnType<typeof vi.fn>;
  setIssueSubscription: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const stub: BridgeStub = {
    getIssueDetail: vi.fn().mockResolvedValue(detail()),
    addIssueDetailCommentReaction: vi.fn().mockResolvedValue({ result: 'reacted' }),
    createIssueComment: vi.fn().mockResolvedValue(null),
    setIssueSubscription: vi.fn().mockResolvedValue({ result: 'subscribed' }),
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

describe('IssueDetailScreen — Activity / Linked tabs (I3b)', () => {
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders activity rows for structural events on the Activity tab', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({
        timeline: [
          {
            event: 'labeled', actor: 'maria', timestamp: '2026-05-09T10:00:00Z',
            label: { name: 'bug', color: 'd73a4a' },
            assignee: null, milestone: null, rename: null, crossReference: null,
          },
          {
            event: 'assigned', actor: 'jack', timestamp: '2026-05-09T11:00:00Z',
            label: null, assignee: 'jack', milestone: null, rename: null, crossReference: null,
          },
          {
            event: 'closed', actor: 'maria', timestamp: '2026-05-09T12:00:00Z',
            label: null, assignee: null, milestone: null, rename: null, crossReference: null,
          },
        ],
      })),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const activityTab = await screen.findByRole('tab', { name: /Activity/i });
    await act(async () => { fireEvent.click(activityTab); });

    // Two activity rows are by @maria (labeled + closed); use getAllByText.
    expect(screen.getAllByText('@maria').length).toBeGreaterThanOrEqual(2);
    // The label chip carries the label name.
    expect(screen.getByText('bug')).toBeDefined();
    // The 'assigned' row mentions the assignee. Actor + assignee both
    // happen to be @jack in this fixture so there can be multiple
    // matches — getAllByText is sufficient to assert presence.
    expect(screen.getAllByText('@jack').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/closed this/)).toBeDefined();
  });

  it('Linked tab surfaces PR cross-references and skips plain issue refs', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({
        timeline: [
          {
            event: 'cross-referenced', actor: 'maria', timestamp: '2026-05-09T10:00:00Z',
            label: null, assignee: null, milestone: null, rename: null,
            crossReference: {
              number: 123, title: 'Fix the planner regression',
              state: 'open', isPullRequest: true,
              repoFullName: 'o/r', htmlUrl: 'https://github.com/o/r/pull/123',
            },
          },
          {
            event: 'cross-referenced', actor: 'jack', timestamp: '2026-05-09T11:00:00Z',
            label: null, assignee: null, milestone: null, rename: null,
            crossReference: {
              number: 456, title: 'Related plan',
              state: 'open', isPullRequest: false,
              repoFullName: 'o/r', htmlUrl: null,
            },
          },
        ],
      })),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const linkedTab = await screen.findByRole('tab', { name: /Linked PRs/i });
    await act(async () => { fireEvent.click(linkedTab); });

    // PR row shows up …
    expect(screen.getByText(/Fix the planner regression/i)).toBeDefined();
    expect(screen.getByText('#123')).toBeDefined();
    // … but the plain-issue cross-ref does not.
    expect(screen.queryByText(/Related plan/i)).toBeNull();
  });

  it('Linked tab shows an empty state when no PRs reference the issue', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({ timeline: [] })),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const linkedTab = await screen.findByRole('tab', { name: /Linked PRs/i });
    await act(async () => { fireEvent.click(linkedTab); });

    expect(screen.getByText(/No PRs link to this issue yet/i)).toBeDefined();
  });

  it('Subscribe button toggles to Unsubscribe optimistically and posts subscribed:true', async () => {
    const stub = installBridge();
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const button = await screen.findByRole('button', { name: /^Subscribe$/ });

    await act(async () => { fireEvent.click(button); });

    // Optimistic flip — button label is now "Unsubscribe".
    expect(screen.getByRole('button', { name: /^Unsubscribe$/ })).toBeDefined();
    // Bridge call carries subscribed=true (PUT on the GitHub side).
    expect(stub.setIssueSubscription).toHaveBeenCalledWith('o', 'r', 42, true);
  });

  it('Unsubscribe rolls back when the bridge rejects', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({ subscribed: true })),
      setIssueSubscription: vi.fn().mockRejectedValue(new Error('rate limited')),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const button = await screen.findByRole('button', { name: /^Unsubscribe$/ });

    await act(async () => { fireEvent.click(button); });

    // Failure path returns the button to Unsubscribe (still subscribed).
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^Unsubscribe$/ })).toBeDefined();
    });
  });

  it('Tab counts reflect the contents of each tab', async () => {
    installBridge({
      getIssueDetail: vi.fn().mockResolvedValue(detail({
        timeline: [
          { event: 'labeled', actor: 'a', timestamp: '2026-05-09T10:00:00Z',
            label: { name: 'bug', color: 'red' }, assignee: null, milestone: null, rename: null, crossReference: null },
          { event: 'cross-referenced', actor: 'b', timestamp: '2026-05-09T11:00:00Z',
            label: null, assignee: null, milestone: null, rename: null,
            crossReference: { number: 12, title: 't', state: 'open', isPullRequest: true, repoFullName: 'o/r', htmlUrl: null } },
        ],
      })),
    });
    render(<IssueDetailScreen owner="o" repo="r" number={42} />);
    const linkedTab = await screen.findByRole('tab', { name: /Linked PRs/i });
    const activityTab = screen.getByRole('tab', { name: /Activity/i });
    const conversationTab = screen.getByRole('tab', { name: /Conversation/i });
    // Conversation count = number of comments (1 in the fixture detail()).
    expect(conversationTab.textContent).toMatch(/1$/);
    // Activity count = non-cross-ref events + non-PR cross-refs = 1.
    expect(activityTab.textContent).toMatch(/1$/);
    // Linked count = PR cross-refs = 1.
    expect(linkedTab.textContent).toMatch(/1$/);
  });
});
