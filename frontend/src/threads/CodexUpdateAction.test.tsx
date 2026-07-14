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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import type { Bridge } from '../types';
import { CodexUpdateAction } from './CodexUpdateAction';

afterEach(() => { vi.restoreAllMocks(); });

test('offers the official updater and retries after an incompatible CLI failure', async () => {
  const updateCodexCli = vi.fn(async () => ({
    previousVersion: '0.141.0', version: '0.144.4', output: 'updated',
  }));
  const onUpdated = vi.fn(async () => {});
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getCodexCliVersion: vi.fn(async () => ({ version: '0.141.0' })),
    updateCodexCli,
  };
  vi.spyOn(window, 'confirm').mockReturnValue(true);

  render(
    <CodexUpdateAction
      message="The 'gpt-5.6-terra' model requires a newer version of Codex."
      onUpdated={onUpdated}
    />,
  );

  await screen.findByText('Installed Codex CLI: 0.141.0');
  fireEvent.click(screen.getByRole('button', { name: 'Update & retry' }));

  await waitFor(() => expect(updateCodexCli).toHaveBeenCalledOnce());
  expect(onUpdated).toHaveBeenCalledOnce();
  await screen.findByText('Updated to 0.144.4; retry queued.');
});
