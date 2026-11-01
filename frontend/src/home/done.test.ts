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
import { previewTask } from '../agents/fixtures/previewBridge';
import { pr } from '../pulls/fixtures/pullRequest';
import type { DoneItemDto, PullRequestDto } from '../types';
import { pullRequestItem } from './attention';
import { standupText } from './done';
import { myWorkItems, type WorkItem } from './work';

const TODAY = '2026-11-02';

function done(kind: DoneItemDto['kind'], title: string, number: number | null): DoneItemDto {
  return { kind, title, repo: number === null ? null : 'owner/trino', number, workspaceId: null, id: number === null ? 't1' : null, at: '2026-11-02T03:00:00Z' };
}

function mine(number: number, extra: Partial<PullRequestDto>): WorkItem[] {
  return myWorkItems({
    candidates: [pullRequestItem(pr({ repo: 'owner/trino', number, title: `Change ${number}`, author: 'me', state: 'open', ...extra }))],
    tasks: [], activity: {}, work: [], viewerLogin: 'me',
  });
}

function work(extra: Partial<WorkItem>): WorkItem {
  return {
    key: extra.title ?? '', source: 'local', label: '', title: '', updatedAt: '2026-11-02T00:00:00Z',
    target: { kind: 'WORK', workspaceId: 'ws1', repo: null, number: null, id: 'w1', connectionId: null, cloudId: null },
    task: null, running: null, branch: null, prs: [], lane: 'progress', next: null, keys: [], ended: null, repos: [], agents: [], ...extra,
  };
}

describe('stand-up notes', () => {
  it('lists what merged, what finished, what is in progress and what was reviewed', () => {
    const items = [
      done('MERGED', 'Fix the parser', 1234),
      done('APPROVED', 'Add a metric', 123),
      done('TASK_DONE', 'Investigate a flaky test', null),
      done('REVIEWED', 'Rename a column', 1236),
      done('CHANGES_REQUESTED', 'Drop a cache', 333),
      done('MERGED_BY_YOU', 'Bump a library', 99),
    ];
    const ticket = { source: 'jira' as const, label: 'ENG-1235', title: 'Support views', task: previewTask('ws1', 'task-1') };
    const inProgress = [
      ...mine(1235, { draft: true, ciStatus: 'FAILING' }),
      ...mine(1237, { requestedReviewers: ['ana'] }),
      work({ title: 'Write the design', task: previewTask('ws1', 'task-2') }),
      work({ ...ticket, prs: [{ repo: 'owner/trino', number: 234, title: 'Support views', state: 'draft', row: null }] }),
      // Nobody started it, someone else owns it, or all it delivered has ended: none of these is in progress.
      work({ source: 'jira', label: 'ENG-9', title: 'Untouched ticket' }),
      work({ source: 'issue', label: 'trino#7', title: 'Opened for others', authored: true }),
      work({ ...ticket, label: 'ENG-8', prs: [{ repo: 'owner/trino', number: 200, title: 'Done already', state: 'merged', row: null }] }),
    ];

    expect(standupText(TODAY, TODAY, items, inProgress)).toBe(`Today:
Merged:

* Fix the parser trino#1234

Done:

* Investigate a flaky test

Work in progress:

* Draft Change 1235 trino#1235
   * Making CI green
* Sent Change 1237 trino#1237
* Developing Write the design
* Draft Support views ENG-1235 trino#234

Reviewed:

* Approved Add a metric trino#123
* Rename a column trino#1236
* Requested changes on Drop a cache trino#333
* Merged Bump a library trino#99`);
  });

  it('names an earlier day and leaves out how things stand now', () => {
    const items = [done('MERGED', 'Fix the parser', 1234)];
    const inProgress = mine(1237, { requestedReviewers: ['ana'] });

    expect(standupText('2026-11-01', TODAY, items, inProgress)).toBe('Yesterday:\nMerged:\n\n* Fix the parser trino#1234');
    expect(standupText('2026-10-30', TODAY, [], inProgress)).toBe('Fri 30 Oct:\nNothing finished');
  });
});
