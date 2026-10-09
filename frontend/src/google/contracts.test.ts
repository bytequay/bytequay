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
import { agendaRange, eventTime, googleLink } from './contracts';

describe('Google display boundaries', () => {
  it('keeps all-day dates in their original calendar day', () => {
    expect(eventTime({ id: 'day', summary: '', description: '', start: '2026-10-09', end: '2026-10-10', allDay: true, url: '' })).toBe('2026-10-09 · All day');
    const range = agendaRange('2026-10-09');
    expect(new Date(range.timeMin).getDate()).toBe(9);
    expect(new Date(range.timeMax).getDate()).toBe(16);
  });
  it('rejects executable and non-Google calendar links', () => {
    for (const link of ['javascript:alert(1)', 'file:///etc/passwd', 'https://calendar.google.com.evil.test/', 'https://user:pass@calendar.google.com/calendar', 'http://calendar.google.com/calendar']) {
      expect(googleLink(link)).toBeNull();
    }
    expect(googleLink('https://calendar.google.com/calendar/event?eid=abc')).toBe('https://calendar.google.com/calendar/event?eid=abc');
  });
});
