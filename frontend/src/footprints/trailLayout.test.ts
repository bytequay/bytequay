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
import { describe, expect, it } from 'vitest';
import {
  addDays, formatClock, formatDayLabel, isSameDay, pinPositions, surfaceMeta, toYmd,
} from './trailLayout';

describe('surfaceMeta', () => {
  it('codes each surface with its concept colour and icon', () => {
    expect(surfaceMeta('PR_KANBAN')).toEqual({ color: '#3E74D9', icon: 'kanban' });
    expect(surfaceMeta('PR')).toEqual({ color: '#3E74D9', icon: 'pull-request' });
    expect(surfaceMeta('TASK')).toEqual({ color: '#6E5DE7', icon: 'robot' });
    expect(surfaceMeta('THREAD')).toEqual({ color: '#1E9E78', icon: 'message' });
  });
});

describe('pinPositions', () => {
  it('returns one position per pin within the card bounds', () => {
    expect(pinPositions(0)).toEqual([]);
    const five = pinPositions(5);
    expect(five).toHaveLength(5);
    for (const p of five) {
      expect(p.leftPct).toBeGreaterThanOrEqual(0);
      expect(p.leftPct).toBeLessThanOrEqual(100);
      expect(p.topPct).toBeGreaterThan(0);
      expect(p.topPct).toBeLessThan(100);
    }
  });

  it('splits pins across a top row and a lower bottom row', () => {
    const six = pinPositions(6);
    const rows = new Set(six.map(p => p.topPct.toFixed(2)));
    expect(rows.size).toBe(2);
    const [topRow, bottomRow] = [...rows].map(Number).sort((a, b) => a - b);
    expect(topRow).toBeLessThan(bottomRow);
  });
});

describe('date helpers', () => {
  it('formats the stepper day label as "Wkd Mon D"', () => {
    expect(formatDayLabel(new Date(2026, 5, 19))).toMatch(/^[A-Z][a-z]{2} Jun 19$/);
  });

  it('formats a local YYYY-MM-DD with zero padding', () => {
    expect(toYmd(new Date(2026, 5, 9))).toBe('2026-06-09');
  });

  it('formats a clock time from an instant in local time', () => {
    const local = new Date(2026, 5, 19, 15, 30);
    expect(formatClock(local.toISOString())).toBe('15:30');
  });

  it('compares calendar days and offsets by whole days', () => {
    const a = new Date(2026, 5, 19, 9, 0);
    const b = new Date(2026, 5, 19, 23, 0);
    expect(isSameDay(a, b)).toBe(true);
    expect(isSameDay(a, addDays(a, 1))).toBe(false);
    expect(toYmd(addDays(new Date(2026, 5, 19), -1))).toBe('2026-06-18');
  });
});
