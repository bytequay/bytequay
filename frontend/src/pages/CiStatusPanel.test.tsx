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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CiStatusPanel } from './CiStatusPanel';
import type { RealtimeCi } from '../types/brainView';

afterEach(cleanup);

const CI: RealtimeCi = {
  status: 'pending',
  prUrl: 'https://github.com/trinodb/trino/pull/30070',
  checks: [
    { name: 'build', status: 'ok', durationSec: 95 },
    { name: 'test (core)', status: 'pending', durationSec: null },
  ],
  lastPolledAt: '2026-06-27T00:00:00Z',
};

describe('CiStatusPanel', () => {
  it('renders the overall status, each check, and opens the PR', () => {
    const onOpenGitHub = vi.fn();
    const { container } = render(<CiStatusPanel ci={CI} onOpenGitHub={onOpenGitHub} />);
    expect(container.querySelector('.ci-panel--pending')).toBeTruthy();
    expect(screen.getByText('CI · Running')).toBeTruthy();
    expect(screen.getByText('build')).toBeTruthy();
    expect(screen.getByText('test (core)')).toBeTruthy();
    expect(screen.getByText('1m 35s')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /View on GitHub/ }));
    expect(onOpenGitHub).toHaveBeenCalledOnce();
  });
});
