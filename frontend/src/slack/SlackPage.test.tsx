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
import SlackPage from './SlackPage';

// React 19 enforces this flag before async act() works without warning.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type BridgeStub = {
  getSlackConnection: ReturnType<typeof vi.fn>;
  getSlackAuthorizeUrl: ReturnType<typeof vi.fn>;
  disconnectSlack: ReturnType<typeof vi.fn>;
  openExternal: ReturnType<typeof vi.fn>;
  onSlackOauthComplete: ReturnType<typeof vi.fn>;
};

function installBridge(overrides: Partial<BridgeStub> = {}): BridgeStub {
  const base: BridgeStub = {
    getSlackConnection: vi.fn().mockResolvedValue({ connected: false }),
    getSlackAuthorizeUrl: vi.fn().mockResolvedValue({ configured: true, url: 'https://slack.com/oauth/v2/authorize?x=y' }),
    disconnectSlack: vi.fn().mockResolvedValue(undefined),
    openExternal: vi.fn().mockResolvedValue(undefined),
    // Returns a teardown the component must call on unmount.
    onSlackOauthComplete: vi.fn().mockReturnValue((): void => undefined),
  };
  const stub = { ...base, ...overrides };
  (window as unknown as { bridge: unknown }).bridge = stub;
  return stub;
}

describe('SlackPage (Slice 2b)', () => {
  afterEach(() => {
    cleanup();
    delete (window as unknown as { bridge?: unknown }).bridge;
  });

  it('renders the connect prompt when no workspace is linked', async () => {
    installBridge();
    render(<SlackPage onOpenIntegrationsSettings={() => undefined} />);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /connect your slack workspace/i })).toBeDefined();
    });
    expect(screen.getByText(/not connected/i)).toBeDefined();
  });

  it('opens the Slack authorize URL in the system browser when Connect is clicked', async () => {
    const stub = installBridge();
    render(<SlackPage onOpenIntegrationsSettings={() => undefined} />);
    await waitFor(() => screen.getByRole('button', { name: /^connect slack workspace$/i }));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^connect slack workspace$/i }));
    });

    await waitFor(() => {
      expect(stub.getSlackAuthorizeUrl).toHaveBeenCalledTimes(1);
    });
    expect(stub.openExternal).toHaveBeenCalledWith('https://slack.com/oauth/v2/authorize?x=y');
    // Button flips to a "Waiting for browser…" disabled state.
    expect(screen.getByRole('button', { name: /waiting for browser/i })).toBeDefined();
  });

  it('shows the not-configured hint when the backend reports configured:false', async () => {
    installBridge({
      getSlackAuthorizeUrl: vi.fn().mockResolvedValue({ configured: false }),
    });
    render(<SlackPage onOpenIntegrationsSettings={() => undefined} />);
    await waitFor(() => screen.getByRole('button', { name: /^connect slack workspace$/i }));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^connect slack workspace$/i }));
    });

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /slack oauth isn't configured/i })).toBeDefined();
    });
  });

  it('renders the connected sidebar when /connection reports a workspace', async () => {
    installBridge({
      getSlackConnection: vi.fn().mockResolvedValue({
        connected: true,
        teamId: 'T123',
        teamName: 'Acme Corp',
        authedUserId: 'U999',
      }),
    });
    render(<SlackPage onOpenIntegrationsSettings={() => undefined} />);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /acme corp linked/i })).toBeDefined();
    });
    expect(screen.getByRole('button', { name: /disconnect workspace/i })).toBeDefined();
  });
});
