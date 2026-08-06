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
import OnboardingScreen from './OnboardingScreen';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => cleanup());

function bridge(available: boolean, importGh: () => Promise<{ login: string }>) {
  window.bridge = {
    getGitHubOAuthAuthorizeUrl: async () => ({ configured: false, url: null as string | null }),
    getGitHubCliAvailable: async () => ({ available }),
    importGitHubCliToken: importGh,
    onGitHubOauthComplete: () => () => {},
  } as unknown as typeof window.bridge;
}

it('tells a user without gh how to get one instead of hiding the option', async () => {
  bridge(false, async () => ({ login: 'nobody' }));
  render(<OnboardingScreen onSaved={vi.fn()} />);

  expect(await screen.findByText('brew install gh && gh auth login')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Use my GitHub CLI login' })).toBeNull();
});

it('warns on a fine-grained token but leaves classic and legacy ones alone', async () => {
  bridge(false, async () => ({ login: 'nobody' }));
  render(<OnboardingScreen onSaved={vi.fn()} />);
  const field = await screen.findByLabelText('GitHub personal access token');
  const warning = /only reach the repositories it was issued for/;

  fireEvent.change(field, { target: { value: 'github_pat_11ABCDE' } });
  expect(screen.getByText(warning)).toBeTruthy();

  fireEvent.change(field, { target: { value: 'ghp_16CharsOfToken' } });
  expect(screen.queryByText(warning)).toBeNull();

  // pre-2021 classic tokens are bare 40-hex, and gh's is gho_ — neither warns
  fireEvent.change(field, { target: { value: 'a'.repeat(40) } });
  expect(screen.queryByText(warning)).toBeNull();
  fireEvent.change(field, { target: { value: 'gho_16CharsOfToken' } });
  expect(screen.queryByText(warning)).toBeNull();
});

it('surfaces the login command when gh is installed but logged out', async () => {
  bridge(true, async () => {
    throw new Error('The GitHub CLI couldn\'t provide a token: please run: gh auth login');
  });
  render(<OnboardingScreen onSaved={vi.fn()} />);

  fireEvent.click(await screen.findByRole('button', { name: 'Use my GitHub CLI login' }));
  expect(await screen.findByText('gh auth login')).toBeTruthy();

  const writeText = vi.fn(async () => {});
  Object.assign(navigator, { clipboard: { writeText } });
  fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
  await waitFor(() => expect(writeText).toHaveBeenCalledWith('gh auth login'));
});

it('retries the gh probe rather than reading a cold sidecar as "no gh"', async () => {
  // The window opens the moment the backend answers /hello, so the very first
  // probe can still lose the race. Treating that as absence used to pin the
  // install-gh advice in front of users who had gh installed all along.
  let calls = 0;
  bridge(true, async () => ({ login: 'nobody' }));
  window.bridge.getGitHubCliAvailable = async () => {
    calls += 1;
    if (calls === 1) throw new Error('fetch failed');
    return { available: true };
  };
  render(<OnboardingScreen onSaved={vi.fn()} />);

  expect(await screen.findByRole(
    'button', { name: 'Use my GitHub CLI login' }, { timeout: 4_000 })).toBeTruthy();
  expect(screen.queryByText('brew install gh && gh auth login')).toBeNull();
}, 10_000);
