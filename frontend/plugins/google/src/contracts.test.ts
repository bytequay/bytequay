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
import { execFileSync } from 'node:child_process';
import { describe, expect, it } from 'vitest';
import { agendaRange, dayLayout, eventHours, eventTime, githubLink, googleLink } from './contracts';

describe('Google display boundaries', () => {
  it('keeps all-day dates in their original calendar day', () => {
    expect(eventTime({ id: 'day', summary: '', description: '', start: '2026-10-09', end: '2026-10-10', allDay: true, url: '' })).toMatch(/\b9\b.* · All day$/);
    const range = agendaRange('2026-10-09');
    expect(new Date(range.timeMin).getDate()).toBe(9);
    expect(new Date(range.timeMax).getDate()).toBe(16);
  });
  it('rejects executable and non-Google calendar links', () => {
    for (const link of ['javascript:alert(1)', 'file:///etc/passwd', 'https://calendar.google.com.evil.test/', 'https://user:pass@calendar.google.com/calendar', 'http://calendar.google.com/calendar']) {
      expect(googleLink(link)).toBeNull();
    }
    expect(googleLink('https://calendar.google.com/calendar/event?eid=abc')).toBe('https://calendar.google.com/calendar/event?eid=abc');
    expect(githubLink('https://github.com.evil.test/a/b/pull/1')).toBeNull();
    expect(githubLink('https://github.com/a/b/pull/1')).toBe('https://github.com/a/b/pull/1');
  });
  it('puts overlapping events side by side and gives the next free slot back', () => {
    expect(dayLayout([{ start: 9, end: 10 }, { start: 9.5, end: 11 }, { start: 10, end: 10.5 }, { start: 13, end: 14 }])).toEqual([
      { column: 0, columns: 2 }, { column: 1, columns: 2 }, { column: 0, columns: 2 }, { column: 0, columns: 1 }]);
  });
});

it('keeps events aligned with clock labels across daylight-saving transitions and midnight', () => {
  // Vitest's worker threads keep their startup time zone. Exercise the actual helper in a process with a DST zone.
  const result = execFileSync(process.execPath, ['-e', `
    const hours = ${eventHours.toString()};
    process.stdout.write(JSON.stringify([
      ...['2026-03-08', '2026-11-01'].map(day => hours({ start: day + 'T09:00:00', end: day + 'T10:30:00' }, day)),
      hours({ start: '2026-03-07T23:00:00', end: '2026-03-09T01:00:00' }, '2026-03-08'),
      hours({ start: '2026-11-01T01:45:00-04:00', end: '2026-11-01T01:15:00-05:00' }, '2026-11-01')
    ]));
  `], { env: { ...process.env, TZ: 'America/New_York' }, encoding: 'utf8' });
  expect(JSON.parse(result)).toEqual([{ start: 9, end: 10.5 }, { start: 9, end: 10.5 }, { start: 0, end: 24 }, { start: 1.75, end: 1.75 }]);
});
