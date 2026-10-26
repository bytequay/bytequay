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

export type BreakCompanionKind = 'chubby-cat' | 'dancing-husky';
export type FocusBreakPreferences = { enabled: boolean; workMinutes: number; breakMinutes: number; companion: BreakCompanionKind };
export type FocusClock = { phase: 'focus' | 'entering' | 'break'; remainingMs: number; updatedAt: number; paused: boolean };

export const FOCUS_PREFERENCES_KEY = 'bytequay.focus-breaks';
export const WORK_MINUTES = [15, 25, 45, 60];
export const BREAK_MINUTES = [1, 5, 10, 15];

export function parseFocusPreferences(value: unknown): FocusBreakPreferences {
  const saved = value !== null && typeof value === 'object' ? value as Record<string, unknown> : {};
  return {
    enabled: saved.enabled === true,
    workMinutes: typeof saved.workMinutes === 'number' && WORK_MINUTES.includes(saved.workMinutes) ? saved.workMinutes : 25,
    breakMinutes: typeof saved.breakMinutes === 'number' && BREAK_MINUTES.includes(saved.breakMinutes) ? saved.breakMinutes : 5,
    companion: saved.companion === 'dancing-husky' ? 'dancing-husky' : 'chubby-cat',
  };
}

export function startClock(phase: FocusClock['phase'], minutes: number, now: number): FocusClock {
  return { phase, remainingMs: minutes * 60_000, updatedAt: now, paused: false };
}

export function advanceClock(clock: FocusClock, preferences: FocusBreakPreferences, now: number, visible: boolean, canInterrupt: boolean): FocusClock {
  if (clock.phase === 'entering') return { ...clock, updatedAt: now };
  const elapsed = Math.max(0, now - clock.updatedAt);
  // shortcut: minute-long gaps stand in for sleep; use native suspend events if short sleeps need exact accounting.
  const count = !clock.paused && (clock.phase === 'break' || visible && elapsed < 60_000);
  const remainingMs = Math.max(0, clock.remainingMs - (count ? elapsed : 0));
  if (remainingMs === 0 && !clock.paused) {
    if (clock.phase === 'break') return startClock('focus', preferences.workMinutes, now);
    if (visible && canInterrupt) return startClock('entering', preferences.breakMinutes, now);
  }
  return { ...clock, remainingMs, updatedAt: now };
}

export function formatFocusTime(seconds: number): string {
  const whole = Math.max(0, Math.ceil(seconds));
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}
