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

import { relativeTime } from './relativeTime';

const now = Date.parse('2026-08-07T12:00:00Z');
const back = (ms: number) => new Date(now - ms).toISOString();

describe('relativeTime', () => {
  it('reads sub-minute as just now', () => {
    expect(relativeTime(back(30_000), { now })).toBe('just now');
  });

  it('counts down in whole units, never rounding up into one that has not elapsed', () => {
    expect(relativeTime(back(119_000), { now })).toBe('1m ago');
    expect(relativeTime(back(59 * 60_000), { now })).toBe('59m ago');
    expect(relativeTime(back(23 * 3_600_000), { now })).toBe('23h ago');
    expect(relativeTime(back(3 * 86_400_000), { now })).toBe('3d ago');
  });

  it('crosses each unit boundary exactly on the boundary', () => {
    expect(relativeTime(back(60_000), { now })).toBe('1m ago');
    expect(relativeTime(back(3_600_000), { now })).toBe('1h ago');
    expect(relativeTime(back(86_400_000), { now })).toBe('1d ago');
  });

  it('drops the suffix for dense chips', () => {
    expect(relativeTime(back(30_000), { now, suffix: false })).toBe('now');
    expect(relativeTime(back(25 * 60_000), { now, suffix: false })).toBe('25m');
    expect(relativeTime(back(2 * 3_600_000), { now, suffix: false })).toBe('2h');
  });

  it('accepts epoch milliseconds as well as ISO strings', () => {
    expect(relativeTime(now - 25 * 60_000, { now })).toBe('25m ago');
  });

  it('never reports a future timestamp as negative', () => {
    expect(relativeTime(back(-5 * 60_000), { now })).toBe('just now');
  });

  it('returns empty for null, undefined and unparseable input so callers can supply a placeholder', () => {
    expect(relativeTime(null, { now })).toBe('');
    expect(relativeTime(undefined, { now })).toBe('');
    expect(relativeTime('not a date', { now })).toBe('');
    expect(relativeTime(Number.NaN, { now })).toBe('');
  });
});
