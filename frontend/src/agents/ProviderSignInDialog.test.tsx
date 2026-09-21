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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import ProviderSignInDialog, { type SignInBridge } from './ProviderSignInDialog';

const bridge: SignInBridge = {
  openProviderSignIn: vi.fn(),
  writeReviewTerminal: vi.fn(),
  resizeReviewTerminal: vi.fn(),
  onReviewTerminalEvent: vi.fn(() => () => {}),
};

describe('provider sign-in dialog', () => {
  it('names the lane and says whose home is being signed in', () => {
    const markup = renderToStaticMarkup(
      <ProviderSignInDialog laneId="claude" providerName="Claude" bridge={bridge} onClose={vi.fn()} />);

    expect(markup).toContain('Sign in to Claude');
    expect(markup).toContain('Starting the sign-in…');
    // Cancel until the shell reports, so a half-finished sign-in is never called done.
    expect(markup).toContain('Cancel');
    expect(markup).not.toContain('Done');
    // The command is the main process's to build; naming it here would be a second source of truth.
    expect(markup).not.toContain('CLAUDE_CONFIG_DIR');
    // Effects have not run server-side, so nothing was spawned by rendering alone.
    expect(bridge.openProviderSignIn).not.toHaveBeenCalled();
  });

  it('works for any lane, so Codex needs no separate dialog', () => {
    expect(renderToStaticMarkup(
      <ProviderSignInDialog laneId="codex" providerName="Codex" bridge={bridge} onClose={vi.fn()} />))
      .toContain('Sign in to Codex');
  });
});
