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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Bridge, UserProfileDto } from '../../types';
import AccountPage from './AccountPage';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const profile: UserProfileDto = {
  login: 'octocat',
  name: 'Octo Cat',
  avatarUrl: 'https://example.com/avatar.png',
  htmlUrl: 'https://github.com/octocat',
  publicRepos: 8,
  followers: 10,
  following: 2,
  bio: null,
  location: null,
  company: null,
  email: null,
  hasSponsors: false,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function installBridge(
  resetAvailable: boolean,
  requestDevLocalDataReset = vi.fn(async () => true),
): typeof requestDevLocalDataReset {
  window.bridge = {
    getUserProfile: vi.fn(async () => profile),
    getGitHubOAuthConnection: vi.fn(async () => ({ connected: false })),
    isDevLocalDataResetAvailable: vi.fn(async () => resetAvailable),
    requestDevLocalDataReset,
  } as unknown as Bridge;
  return requestDevLocalDataReset;
}

it('hides the local data reset outside the supported development launcher', async () => {
  installBridge(false);

  render(<AccountPage />);

  await waitFor(() => expect(window.bridge.isDevLocalDataResetAvailable).toHaveBeenCalledOnce());
  expect(screen.queryByRole('button', { name: 'Reset and restart' })).toBeNull();
});

it('confirms and requests a development data reset', async () => {
  const requestReset = installBridge(true);
  render(<AccountPage />);

  // The destructive action is two-step: the button reveals an inline
  // confirmation strip, and only "Yes, reset" calls the bridge.
  fireEvent.click(await screen.findByRole('button', { name: 'Reset and restart' }));
  expect(requestReset).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

  await waitFor(() => expect(requestReset).toHaveBeenCalledOnce());
  expect((screen.getByRole('button', { name: 'Resetting…' }) as HTMLButtonElement).disabled).toBe(true);
});

it('does not request a reset when the destructive confirmation is cancelled', async () => {
  const requestReset = installBridge(true);
  render(<AccountPage />);

  fireEvent.click(await screen.findByRole('button', { name: 'Reset and restart' }));
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  expect(requestReset).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Reset and restart' })).toBeTruthy();
});
