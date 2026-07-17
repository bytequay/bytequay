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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { invalidate, setCached } from './dataCache';
import YearInCodeHeatmap from './YearInCodeHeatmap';
import type { ContributionCalendarDto } from './types';

const LOGIN = 'chenjian2664';
const CACHE_KEY = `home:contribution-graph:${LOGIN}`;

const calendar: ContributionCalendarDto = {
  totalContributions: 4686,
  weeks: [{
    days: [
      { date: '2026-07-17', contributionCount: 4, color: '#30a14e' },
    ],
  }],
};

afterEach(() => {
  cleanup();
  invalidate(CACHE_KEY);
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'bridge');
});

function mockBridge() {
  const bridge = {
    getContributionCalendar: vi.fn().mockRejectedValue(new Error('RESOURCE_LIMITS_EXCEEDED')),
    getUserCommitsOnDate: vi.fn(),
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

describe('YearInCodeHeatmap', () => {
  it('uses a fresh cached graph without refreshing GitHub', () => {
    setCached(CACHE_KEY, calendar);
    const bridge = mockBridge();

    render(<YearInCodeHeatmap login={LOGIN} />);

    expect(screen.getByLabelText('4,686 contributions in the last year')).toBeTruthy();
    expect(bridge.getContributionCalendar).not.toHaveBeenCalled();
  });

  it('keeps the last successful graph visible when stale refresh fails', async () => {
    const now = new Date('2026-07-17T00:00:00Z').getTime();
    const dateNow = vi.spyOn(Date, 'now').mockReturnValue(now);
    setCached(CACHE_KEY, calendar);
    dateNow.mockReturnValue(now + 8 * 60 * 60 * 1000 + 1);
    mockBridge();

    render(<YearInCodeHeatmap login={LOGIN} />);

    expect(screen.getByLabelText('4,686 contributions in the last year')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Contribution refresh failed. Showing the last successful graph.')).toBeTruthy());
    expect(screen.queryByText('No contributions in the last year.')).toBeNull();
  });

  it('shows retry state when the first contribution fetch fails', async () => {
    mockBridge();

    render(<YearInCodeHeatmap login={LOGIN} />);

    expect(await screen.findByText('Contributions temporarily unavailable.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(screen.queryByText('No contributions in the last year.')).toBeNull();
  });
});
