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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TodaysFootprints from './TodaysFootprints';
import type { FootprintsTrailDto } from '../types';

let getFootprints: ReturnType<typeof vi.fn>;

const TRAIL: FootprintsTrailDto = {
  date: '2026-06-19',
  totalStops: 2,
  stops: [
    {
      surfaceType: 'PR', surfaceId: 'trinodb/trino#5680',
      title: 'trinodb/trino #5680', context: 'trinodb/trino',
      latestVisitAt: '2026-06-19T15:30:00.000Z', visitCount: 3,
    },
    {
      surfaceType: 'TASK', surfaceId: 't1/k1',
      title: 'Cost-meter task', context: 't1',
      latestVisitAt: '2026-06-19T16:00:00.000Z', visitCount: 1,
    },
  ],
};

beforeEach(() => {
  getFootprints = vi.fn().mockResolvedValue(TRAIL);
  (window as { bridge?: unknown }).bridge = { getFootprints };
});

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
});

describe('TodaysFootprints', () => {
  it('renders a pin per stop from the bridge response, plus the revisit badge', async () => {
    render(<TodaysFootprints onResume={() => {}} />);
    await waitFor(() => expect(screen.getByText('trinodb/trino #5680')).toBeTruthy());
    expect(screen.getByText('Cost-meter task')).toBeTruthy();
    // The PR was visited 3 times → "3×" badge; the task once → no badge.
    expect(screen.getByText('3×')).toBeTruthy();
  });

  it('resumes the stop when its pin is clicked', async () => {
    const onResume = vi.fn();
    render(<TodaysFootprints onResume={onResume} />);
    const pin = await screen.findByRole('button', { name: /trinodb\/trino #5680 — resume/ });
    fireEvent.click(pin);
    expect(onResume).toHaveBeenCalledWith(TRAIL.stops[0]);
  });

  it('shows a calm empty state when there are no visits', async () => {
    getFootprints.mockResolvedValue({ date: '2026-06-19', totalStops: 0, stops: [] });
    render(<TodaysFootprints onResume={() => {}} />);
    await waitFor(() => expect(screen.getByText('No footprints yet today.')).toBeTruthy());
  });

  it('pages to the previous day and refetches', async () => {
    render(<TodaysFootprints onResume={() => {}} />);
    await waitFor(() => expect(getFootprints).toHaveBeenCalledTimes(1));
    const firstDate = getFootprints.mock.calls[0][0] as string;

    fireEvent.click(screen.getByLabelText('Previous day'));
    await waitFor(() => expect(getFootprints).toHaveBeenCalledTimes(2));
    const secondDate = getFootprints.mock.calls[1][0] as string;
    expect(secondDate).not.toBe(firstDate);
    expect(secondDate < firstDate).toBe(true);
  });

  it('disables the next-day step on today but enables it after stepping back', async () => {
    render(<TodaysFootprints onResume={() => {}} />);
    await waitFor(() => expect(getFootprints).toHaveBeenCalled());
    expect((screen.getByLabelText('Next day') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('Previous day'));
    expect((screen.getByLabelText('Next day') as HTMLButtonElement).disabled).toBe(false);
  });
});
