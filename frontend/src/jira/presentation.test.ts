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
import { completedGroups, jiraWorkflowGroup } from './presentation';
import { jiraIssue } from './fixtures/jira';

// Vitest workers inherit their process timezone; a child process exercises native Date in each zone.
function inTimezone(timezone: string, scenario: string): unknown {
  const script = `const completedGroups = ${completedGroups.toString()};
    const local = (year, month, day, hour = 12) => new Date(year, month - 1, day, hour).toISOString();
    const done = (key, completedAt) => ({ key, completedAt, updatedAt: '2027-01-03T00:00:00Z' });
    const keys = groups => Object.fromEntries(groups.map(group => [group.label, group.issues.map(issue => issue.key)]));
    process.stdout.write(JSON.stringify(${scenario}));`;
  return JSON.parse(execFileSync(process.execPath, ['-e', script], { env: { ...process.env, TZ: timezone }, encoding: 'utf8' }));
}

describe('Jira completion dates', () => {
  it.each(['America/New_York', 'Asia/Singapore', 'UTC'])('groups local dates over a year and Monday boundary in %s', timezone => {
    const rows = "[done('older', local(2026, 12, 20)), done('last-week', local(2026, 12, 27)), done('this-week', local(2026, 12, 31)), done('yesterday', local(2027, 1, 2)), done('early-today', local(2027, 1, 3, 0)), done('late-today', local(2027, 1, 3, 10)), done('unknown', null)]";
    expect(inTimezone(timezone, `keys(completedGroups(${rows}, new Date(2027, 0, 3, 15)))`)).toEqual({ Today: ['late-today', 'early-today'], Yesterday: ['yesterday'], 'This week': ['this-week'], 'Last week': ['last-week'], Earlier: ['older'], 'Completion date unavailable': ['unknown'] });
    expect(inTimezone(timezone, `keys(completedGroups(${rows}, new Date(2027, 0, 4, 15)))`)).toEqual({ Yesterday: ['late-today', 'early-today'], 'Last week': ['yesterday', 'this-week'], Earlier: ['last-week', 'older'], 'Completion date unavailable': ['unknown'] });
  });
  it('uses calendar days across daylight saving and never falls back to updatedAt', () => {
    expect(inTimezone('America/New_York', "keys(completedGroups([done('today', '2026-03-09T04:00:00Z'), done('yesterday', '2026-03-08T05:00:00Z'), done('last-week', '2026-03-08T04:59:59Z'), done('unknown', null), done('invalid', 'not-a-date')], new Date('2026-03-09T04:30:00Z')))"))
      .toEqual({ Today: ['today'], Yesterday: ['yesterday'], 'Last week': ['last-week'], 'Completion date unavailable': ['invalid', 'unknown'] });
  });
  it('makes future dates explicit rather than claiming completion today', () => {
    expect(inTimezone('Asia/Singapore', "keys(completedGroups([done('future', local(2026, 9, 27))], new Date(2026, 8, 26, 23)))")).toEqual({ 'Future completion date': ['future'] });
  });
  it('keeps actual workflow statuses while grouping review only for display', () => {
    const issue = { ...jiraIssue, status: { id: 'custom', name: 'Awaiting review', category: 'indeterminate' } };
    expect(jiraWorkflowGroup(issue)).toBe('In review');
    expect(issue.status.name).toBe('Awaiting review');
    expect(jiraWorkflowGroup({ ...issue, status: { ...issue.status, name: 'Quality gate' } })).toBe('In progress');
    expect(jiraWorkflowGroup({ ...issue, status: { ...issue.status, category: 'unrecognized', name: 'Custom status' } })).toBe('Custom status');
  });
});
