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
import SlackInbox from './SlackInbox';
import type { SlackInboxItemDto } from '../types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type BridgeStub = {
  listSlackInbox: ReturnType<typeof vi.fn>;
  getSlackInboxThread: ReturnType<typeof vi.fn>;
  expandSlackInboxItem: ReturnType<typeof vi.fn>;
  replySlackInboxItem: ReturnType<typeof vi.fn>;
  archiveSlackInboxItem: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const stub: BridgeStub = {
    listSlackInbox: vi.fn().mockResolvedValue([]),
    getSlackInboxThread: vi.fn().mockResolvedValue({ channelId: '', threadTs: '', messages: [] }),
    expandSlackInboxItem: vi.fn().mockResolvedValue({ result: 'expanded' }),
    replySlackInboxItem: vi.fn().mockResolvedValue({ result: 'responded' }),
    archiveSlackInboxItem: vi.fn().mockResolvedValue({ result: 'archived' }),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = stub;
  return stub;
}

function mention(over: Partial<SlackInboxItemDto> = {}): SlackInboxItemDto {
  return {
    channelId: 'C100',
    ts: '1700000010.000100',
    state: 'unread',
    archivedAt: null,
    bumpedAt: null,
    respondedAt: null,
    expandedAt: null,
    userId: 'U999',
    text: 'Hey <@U123> can you look at this?',
    threadTs: null,
    hasAtYou: true,
    inboxKind: 'mention',
    newReplyCount: 0,
    ...over,
  };
}

const followed = [{ id: 'C100', name: 'engineering', isPrivate: false }];

describe('SlackInbox (Slice 5 phase B)', () => {
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders an empty inbox when listSlackInbox returns no rows', async () => {
    installBridge();
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    await waitFor(() => {
      expect(screen.getByText(/you're all caught up/i)).toBeDefined();
    });
  });

  it('renders MENTION + DM rows with their pills and channel name', async () => {
    installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([
        mention({ ts: '1700000020.000100' }),
        mention({
          channelId: 'D200', ts: '1700000010.000100', inboxKind: 'dm',
          hasAtYou: false, text: 'lunch?', userId: 'U777',
        }),
      ]),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    await waitFor(() => {
      expect(screen.getByText('MENTION')).toBeDefined();
      expect(screen.getByText('DM')).toBeDefined();
    });
    expect(screen.getByText(/#engineering/)).toBeDefined();
  });

  it('switches the listSlackInbox call when the Mentions filter is clicked', async () => {
    const stub = installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([mention()]),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    await waitFor(() => screen.getByText('MENTION'));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Mentions/ }));
    });

    await waitFor(() => {
      const calls = stub.listSlackInbox.mock.calls.map(c => c[0]);
      expect(calls).toContain('mentions');
    });
  });

  it('expanding a MENTION fetches the thread and shows the reply box', async () => {
    const stub = installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([mention()]),
      getSlackInboxThread: vi.fn().mockResolvedValue({
        channelId: 'C100',
        threadTs: '1700000010.000100',
        messages: [
          { ts: '1700000005.000000', userId: 'U777', text: 'kicking off the thread', hasAtYou: false },
          { ts: '1700000010.000100', userId: 'U777', text: 'Hey <@U123> can you look?', hasAtYou: true },
        ],
      }),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    // Click the inbox-item header (has the snippet text); the filter
    // pills don't carry the snippet so this is unambiguous.
    const header = await screen.findByRole('button', { name: /Hey @… can you look/i });

    await act(async () => { fireEvent.click(header); });

    await waitFor(() => {
      expect(stub.expandSlackInboxItem).toHaveBeenCalledWith('C100', '1700000010.000100');
      expect(stub.getSlackInboxThread).toHaveBeenCalledWith('C100', '1700000010.000100');
    });
    // Reply textarea visible.
    expect(screen.getByPlaceholderText(/reply in thread/i)).toBeDefined();
    // Mention rendered as @you in the thread context.
    expect(screen.getByText(/@you can you look\?/)).toBeDefined();
  });

  it('reply triggers replySlackInboxItem then re-fetches the inbox', async () => {
    const stub = installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([mention()]),
      getSlackInboxThread: vi.fn().mockResolvedValue({
        channelId: 'C100', threadTs: '1700000010.000100', messages: [],
      }),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    // Click the inbox-item header (has the snippet text); the filter
    // pills don't carry the snippet so this is unambiguous.
    const header = await screen.findByRole('button', { name: /Hey @… can you look/i });
    await act(async () => { fireEvent.click(header); });
    await screen.findByPlaceholderText(/reply in thread/i);

    await act(async () => {
      fireEvent.change(screen.getByPlaceholderText(/reply in thread/i), {
        target: { value: 'on it' },
      });
      fireEvent.click(screen.getByRole('button', { name: /^reply$/i }));
    });

    await waitFor(() => {
      expect(stub.replySlackInboxItem).toHaveBeenCalledWith('C100', '1700000010.000100', 'on it');
    });
    // Re-fetch happens after the reply lands.
    expect(stub.listSlackInbox.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('shows the responded countdown footer for RESPONDED items', async () => {
    const respondedAt = new Date(Date.now() - 60 * 60 * 1000).toISOString(); // 1h ago
    installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([
        mention({ state: 'responded', respondedAt }),
      ]),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    await waitFor(() => {
      expect(screen.getByText(/auto-archives in/i)).toBeDefined();
    });
    expect(screen.getByRole('button', { name: /archive now/i })).toBeDefined();
  });

  it('shows the BUMPED N-NEW pill when newReplyCount is non-zero', async () => {
    installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([
        mention({ state: 'bumped', newReplyCount: 3 }),
      ]),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    await waitFor(() => {
      expect(screen.getByText('3 NEW')).toBeDefined();
    });
  });

  it('Archive now calls archiveSlackInboxItem and re-fetches', async () => {
    const respondedAt = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    const stub = installBridge({
      listSlackInbox: vi.fn().mockResolvedValue([
        mention({ state: 'responded', respondedAt }),
      ]),
    });
    render(<SlackInbox followedChannels={followed} authedUserId="U123" />);
    const archiveBtn = await screen.findByRole('button', { name: /archive now/i });

    await act(async () => { fireEvent.click(archiveBtn); });

    await waitFor(() => {
      expect(stub.archiveSlackInboxItem).toHaveBeenCalledWith('C100', '1700000010.000100');
    });
  });
});
