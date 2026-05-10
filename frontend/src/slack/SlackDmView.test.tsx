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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import SlackDmView from './SlackDmView';
import type { SlackFeedMessageDto } from '../types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type BridgeStub = {
  getSlackChannelFeed: ReturnType<typeof vi.fn>;
  postSlackFeedMessage: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const stub: BridgeStub = {
    getSlackChannelFeed: vi.fn().mockResolvedValue({ channelId: 'D200', messages: [] }),
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
    text: 'hi there',
    threadTs: null,
    hasAtYou: false,
    ...over,
  };
}

describe('SlackDmView (Slice 6)', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders the breadcrumb and routes back to the inbox on click', async () => {
    installBridge();
    const onBack = vi.fn();
    render(
      <SlackDmView
        channelId="D200"
        peerLabel="Maria Reyes"
        onBack={onBack}
        onMarkHandled={async () => undefined}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText(/DM with/)).toBeDefined();
    });
    fireEvent.click(screen.getByRole('button', { name: /^← Inbox$/ }));
    expect(onBack).toHaveBeenCalled();
  });

  it('renders the conversation and tags own messages with YOU', async () => {
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'D200',
        messages: [
          msg({ ts: '1700000010.000100', userId: 'U777', text: 'hey' }),
          msg({ ts: '1700000020.000200', userId: 'U123', text: 'on it' }),
        ],
      }),
    });
    render(
      <SlackDmView
        channelId="D200"
        peerLabel="Maria Reyes"
        authedUserId="U123"
        onBack={() => undefined}
        onMarkHandled={async () => undefined}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText('hey')).toBeDefined();
    });
    // YOU badge is on the U123 row only.
    const youBadges = screen.getAllByText('YOU');
    expect(youBadges).toHaveLength(1);
  });

  it('shows the NEW SINCE divider when localStorage has a watermark', async () => {
    window.localStorage.setItem('slack:dm-last-read:D200', '1700000015.000000');
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'D200',
        messages: [
          msg({ ts: '1700000010.000100', text: 'old' }),
          msg({ ts: '1700000020.000200', text: 'new 1' }),
          msg({ ts: '1700000030.000300', text: 'new 2' }),
        ],
      }),
    });
    render(
      <SlackDmView
        channelId="D200"
        peerLabel="Maria Reyes"
        onBack={() => undefined}
        onMarkHandled={async () => undefined}
      />,
    );
    await waitFor(() => {
      expect(screen.getByText(/NEW SINCE YOU LAST READ · 2 MESSAGES/i)).toBeDefined();
    });
  });

  it('reply box posts a top-level message (threadTs=null) and re-fetches', async () => {
    const stub = installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'D200',
        messages: [msg({ ts: '1700000010.000100', text: 'hello' })],
      }),
    });
    render(
      <SlackDmView
        channelId="D200"
        peerLabel="Maria Reyes"
        onBack={() => undefined}
        onMarkHandled={async () => undefined}
      />,
    );
    const textarea = await screen.findByPlaceholderText(/Reply to Maria Reyes/);
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'see you at 9' } });
      fireEvent.click(screen.getByRole('button', { name: /^reply$/i }));
    });
    await waitFor(() => {
      expect(stub.postSlackFeedMessage).toHaveBeenCalledWith('D200', 'see you at 9', null);
    });
  });

  it('Mark as handled calls onMarkHandled with the parent ts', async () => {
    installBridge({
      getSlackChannelFeed: vi.fn().mockResolvedValue({
        channelId: 'D200',
        messages: [
          msg({ ts: '1700000010.000100', text: 'first' }),
          msg({ ts: '1700000020.000200', text: 'second' }),
        ],
      }),
    });
    const onMarkHandled = vi.fn().mockResolvedValue(undefined);
    render(
      <SlackDmView
        channelId="D200"
        peerLabel="Maria Reyes"
        onBack={() => undefined}
        onMarkHandled={onMarkHandled}
      />,
    );
    const handledBtn = await screen.findByRole('button', { name: /mark as handled/i });
    await act(async () => { fireEvent.click(handledBtn); });

    await waitFor(() => {
      // Parent ts = the earliest message we have cached.
      expect(onMarkHandled).toHaveBeenCalledWith('1700000010.000100');
    });
  });
});
