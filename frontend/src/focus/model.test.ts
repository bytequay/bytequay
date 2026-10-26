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
import { advanceClock, formatFocusTime, parseFocusPreferences, startClock, type FocusBreakPreferences } from './model';

const preferences: FocusBreakPreferences = { enabled: true, workMinutes: 25, breakMinutes: 5, companion: 'chubby-cat' };

describe('focus breaks', () => {
  it('starts disabled and rejects invalid saved times', () => {
    expect(parseFocusPreferences(null)).toEqual({ ...preferences, enabled: false });
    expect(parseFocusPreferences({ enabled: 'true', workMinutes: -1, breakMinutes: Infinity })).toEqual({ ...preferences, enabled: false });
    expect(parseFocusPreferences({ enabled: true, workMinutes: 45, breakMinutes: 10 })).toEqual({ ...preferences, workMinutes: 45, breakMinutes: 10 });
  });

  it('restores the selected companion and defaults missing or unknown choices to the cat', () => {
    expect(parseFocusPreferences({ ...preferences, companion: 'dancing-husky' })).toEqual({ ...preferences, companion: 'dancing-husky' });
    expect(parseFocusPreferences({ enabled: true, workMinutes: 25, breakMinutes: 5 })).toEqual(preferences);
    expect(parseFocusPreferences({ ...preferences, companion: 'unknown' })).toEqual(preferences);
  });

  it('preserves the full break duration through arrival, then starts a fresh focus session after the break', () => {
    const focus = { ...startClock('focus', 25, 0), remainingMs: 1000 };
    const arriving = advanceClock(focus, preferences, 1100, true, true);
    expect(arriving).toEqual(startClock('entering', 5, 1100));
    expect(advanceClock(arriving, preferences, 600_000, true, false)).toEqual({ ...arriving, updatedAt: 600_000 });
    const rest = startClock('break', 5, 1100);
    expect(advanceClock(rest, preferences, 301_100, true, true)).toEqual(startClock('focus', 25, 301_100));
  });

  it('pauses focus while hidden, sleeping, or manually paused, without counting that time on return', () => {
    const focus = startClock('focus', 25, 0);
    const hidden = advanceClock(focus, preferences, 30_000, false, true);
    const resumed = advanceClock(hidden, preferences, 31_000, true, true);
    expect(hidden.remainingMs).toBe(1_500_000);
    expect(resumed.remainingMs).toBe(1_499_000);
    expect(advanceClock(focus, preferences, 3_600_000, true, true).remainingMs).toBe(1_500_000);
    expect(advanceClock({ ...focus, paused: true }, preferences, 1000, true, true).remainingMs).toBe(1_500_000);
  });

  it('waits for an open form or confirmation before showing a due break', () => {
    const focus = { ...startClock('focus', 25, 0), remainingMs: 1000 };
    const waiting = advanceClock(focus, preferences, 2000, true, false);
    expect(waiting.phase).toBe('focus');
    expect(waiting.remainingMs).toBe(0);
    expect(advanceClock(waiting, preferences, 3000, false, true).phase).toBe('focus');
    expect(advanceClock(waiting, preferences, 3000, true, true)).toEqual(startClock('entering', 5, 3000));
  });

  it('finishes a break while away and never subtracts time for a backwards clock', () => {
    const rest = startClock('break', 5, 1000);
    expect(advanceClock(rest, preferences, 600_000, false, false)).toEqual(startClock('focus', 25, 600_000));
    expect(advanceClock(rest, preferences, 0, true, true).remainingMs).toBe(rest.remainingMs);
    expect(formatFocusTime(61)).toBe('1:01');
    expect(formatFocusTime(0)).toBe('0:00');
  });
});
