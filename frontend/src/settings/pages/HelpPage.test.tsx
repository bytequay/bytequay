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
import type { IssueDto, ProductIssueMonitorStatusDto } from '../../types';
import HelpPage from './HelpPage';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => cleanup());

function imageClipboardData(file: File) {
  return {
    items: [{ type: file.type, getAsFile: () => file }],
  } as unknown as DataTransfer;
}

it('submits a product issue without consulting watched repositories', async () => {
  const created: IssueDto = {
    id: 41,
    number: 19,
    title: 'Toolbar freezes',
    author: 'reporter',
    state: 'open',
    htmlUrl: 'https://github.com/bytequay/bytequay/issues/19',
    updatedAt: '2026-07-19T00:00:00Z',
    labels: [],
  };
  const reportByteQuayIssue = vi.fn(async () => created);
  const monitor: ProductIssueMonitorStatusDto = { enabled: false, eligible: false, reason: null };
  window.bridge = {
    reportByteQuayIssue,
    getByteQuayIssueMonitor: vi.fn(async () => monitor),
  } as unknown as typeof window.bridge;
  render(<HelpPage />);

  fireEvent.change(screen.getByLabelText('Short title'), { target: { value: 'Toolbar freezes' } });
  fireEvent.change(screen.getByLabelText('What happened?'), { target: { value: 'It stops responding.' } });
  fireEvent.click(screen.getByRole('button', { name: 'Submit issue' }));

  await waitFor(() => expect(reportByteQuayIssue)
    .toHaveBeenCalledWith('Toolbar freezes', 'It stops responding.'));
  expect(await screen.findByText('Issue #19 created.')).toBeTruthy();
});

it('captures pasted screenshots and never silently drops them on submit', async () => {
  const reportByteQuayIssue = vi.fn();
  const monitor: ProductIssueMonitorStatusDto = { enabled: false, eligible: false, reason: null };
  window.bridge = {
    reportByteQuayIssue,
    getByteQuayIssueMonitor: vi.fn(async () => monitor),
  } as unknown as typeof window.bridge;
  render(<HelpPage />);

  const file = new File(['fake-png-bytes'], 'shot.png', { type: 'image/png' });
  fireEvent.paste(screen.getByLabelText('What happened?'), { clipboardData: imageClipboardData(file) });
  expect(await screen.findByAltText('Pasted screenshot')).toBeTruthy();

  fireEvent.change(screen.getByLabelText('Short title'), { target: { value: 'Toolbar freezes' } });
  fireEvent.change(screen.getByLabelText('What happened?'), { target: { value: 'It stops responding.' } });
  fireEvent.click(screen.getByRole('button', { name: 'Submit issue' }));

  expect(reportByteQuayIssue).not.toHaveBeenCalled();
  expect(screen.getByRole('alert').textContent).toContain('public attachment upload endpoint');
});
