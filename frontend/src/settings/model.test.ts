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
import { MINUTE_OPTIONS, formatMinuteOfDay, providerStatusLabel } from './model';

describe('settings model helpers', () => {
  it('formats minutes of day as 12-hour times', () => {
    expect(formatMinuteOfDay(0)).toBe('12:00 AM');
    expect(formatMinuteOfDay(60)).toBe('1:00 AM');
    expect(formatMinuteOfDay(450)).toBe('7:30 AM');
    expect(formatMinuteOfDay(720)).toBe('12:00 PM');
    expect(formatMinuteOfDay(23 * 60 + 30)).toBe('11:30 PM');
  });

  it('offers every half hour of the day', () => {
    expect(MINUTE_OPTIONS).toHaveLength(48);
    expect(MINUTE_OPTIONS[0]).toBe(0);
    expect(MINUTE_OPTIONS[47]).toBe(23 * 60 + 30);
  });

  it('maps provider health to badges, with checking winning', () => {
    expect(providerStatusLabel('AVAILABLE', true).label).toBe('Checking…');
    expect(providerStatusLabel('AVAILABLE', false)).toEqual({ label: '✓ Available', tone: 'is-ok' });
    expect(providerStatusLabel('DEGRADED', false).tone).toBe('is-warn');
    expect(providerStatusLabel('UNAVAILABLE', false).label).toBe('Unavailable');
  });
});
