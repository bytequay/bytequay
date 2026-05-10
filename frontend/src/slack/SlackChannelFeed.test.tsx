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
import SlackChannelFeed from './SlackChannelFeed';
import type { SlackFeedMessageDto } from '../types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type BridgeStub = {
  getSlackChannelFeed: ReturnType<typeof vi.fn>;
  postSlackFeedMessage: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const stub: BridgeStub = {
    getSlackChannelFeed: vi.fn().mockResolvedValue({ channelId: 'C100', messages: [] }),
    postSlackFeedMessage: vi.fn().mockResolvedValue({ result: 'posted', postedTs: '1700001000.000000' }),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = stub;
  return stub;
}

function msg(over: Partial<SlackFeedMessageDto> = {}): SlackFeedMessageDto {
  return {
    ts: '1700000010.000100',
    userId: 'U777',
    text: 'hello world',
    threadTs: null,
    hasAtYou: false,
    ...over,
  };
}

describe('SlackChannelFeed (Slice 6)', () => {
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders the channel header and a flat message stream', async () => {
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'C100',
        messages: [
          msg({ ts: '1700000010.000100', text: 'first' }),
          msg({ ts: '1700000020.000200', text: 'second' }),
        ],
      }),
    });
    render(<SlackChannelFeed channelId="C100" channelName="engineering" isPrivate={false} />);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /#engineering/i })).toBeDefined();
    });
    expect(screen.getByText('first')).toBeDefined();
    expect(screen.getByText('second')).toBeDefined();
  });

  it('shows a thread pill on parents with replies and expands inline on click', async () => {
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'C100',
        messages: [
          msg({ ts: '1700000010.000000', text: 'parent kicking off', threadTs: null }),
          msg({ ts: '1700000020.000100', text: 'first reply', threadTs: '1700000010.000000' }),
          msg({ ts: '1700000030.000200', text: 'second reply', threadTs: '1700000010.000000' }),
        ],
      }),
    });
    render(<SlackChannelFeed channelId="C100" channelName="engineering" isPrivate={false} />);
    const pill = await screen.findByRole('button', { name: /2 replies/i });

    await act(async () => { fireEvent.click(pill); });

    expect(screen.getByRole('button', { name: /collapse thread/i })).toBeDefined();
    expect(screen.getByText('first reply')).toBeDefined();
    expect(screen.getByText('second reply')).toBeDefined();
    expect(screen.getByPlaceholderText(/reply in thread/i)).toBeDefined();
  });

  it('thread reply posts via bridge and re-fetches the feed', async () => {
    const stub = installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'C100',
        messages: [
          msg({ ts: '1700000010.000000', text: 'parent', threadTs: null }),
          msg({ ts: '1700000020.000100', text: 'reply A', threadTs: '1700000010.000000' }),
        ],
      }),
    });
    render(<SlackChannelFeed channelId="C100" channelName="engineering" isPrivate={false} />);
    const pill = await screen.findByRole('button', { name: /1 reply/i });
    await act(async () => { fireEvent.click(pill); });
    const replyInput = await screen.findByPlaceholderText(/reply in thread/i);

    await act(async () => {
      fireEvent.change(replyInput, { target: { value: 'thanks!' } });
      // ⌘+Enter mirrors the inbox reply box's keyboard shortcut.
      fireEvent.keyDown(replyInput, { key: 'Enter', metaKey: true });
    });

    await waitFor(() => {
      expect(stub.postSlackFeedMessage).toHaveBeenCalledWith('C100', 'thanks!', '1700000010.000000');
    });
    expect(stub.getSlackChannelFeed.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('renders a synthesized parent placeholder when only thread replies are cached', async () => {
    // Defensive: a thread reply ingested before its parent (rare but
    // possible if the polling watermark advanced past the parent on a
    // prior tick) shouldn't make the feed swallow the row.
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'C100',
        messages: [
          msg({ ts: '1700000020.000100', text: 'orphan reply', threadTs: '1700000010.000000' }),
        ],
      }),
    });
    render(<SlackChannelFeed channelId="C100" channelName="engineering" isPrivate={false} />);
    await waitFor(() => {
      expect(screen.getByText(/thread parent not cached yet/i)).toBeDefined();
    });
  });
});
