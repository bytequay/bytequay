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

import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge, RepositoryIssueSnapshotDto, RepositoryMetadataDto, WorkspaceIssueDetailDto } from '../../types';
import WorkspaceIssuesScreen from '../WorkspaceIssuesScreen';

type Issue = RepositoryIssueSnapshotDto['issues'][number];

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }

async function closeKeepsIssue(): Promise<void> {
  const writes: string[] = [];
  let issues = [7, 8, 9].map((number): Issue => ({
    id: 7000 + number, number, title: `Issue ${number}`, body: 'Details', author: 'alice', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  const repository = { owner: 'upstream', repo: 'app' };
  const snapshot = (): RepositoryIssueSnapshotDto => ({
    repositoryId: 40, repository, issues, page: 0, pageSize: 50, totalIssues: issues.length, hasMore: false, syncedAt: null,
  });
  const metadata: RepositoryMetadataDto = {
    repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
  };
  const detail = (number: number): WorkspaceIssueDetailDto => ({
    repositoryId: 40, issueNumber: number, timeline: [], currentType: null, availableTypes: [], refreshedAt: '2026-09-02T00:00:00Z',
  });
  window.bridge = {
    fetchWorkspaceIssues: async () => snapshot(),
    getWorkspaceMetadata: async () => metadata,
    fetchWorkspaceIssueDetail: async (_workspace: string, number: number) => detail(number),
    recordRecentTouch: async () => {},
    commentWorkspaceIssue: async (_workspace: string, number: number, body: string) => { writes.push(`comment #${number}: ${body}`); },
    // The backend drops a closed issue from the open list, so a reload no longer contains it.
    closeWorkspaceIssue: async (_workspace: string, number: number) => {
      writes.push(`close #${number}`);
      issues = issues.filter(issue => issue.number !== number);
    },
  } as unknown as Bridge;
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  const composer = () => {
    const found = host.querySelector('textarea');
    assert(found !== null, 'Missing issue composer');
    return found;
  };
  const type = (value: string) => {
    const field = composer();
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(field, value);
    field.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const row = (number: number) => [...host.querySelectorAll<HTMLButtonElement>('button[aria-pressed]')]
    .find(candidate => candidate.textContent?.includes(`#${number}`));
  const open = (number: number) => {
    const found = row(number);
    assert(found !== undefined, `Missing issue row #${number}`);
    found.click();
  };
  try {
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={7} onSelectIssue={() => {}} />));
    await settle();
    type('Fixed in #30786'); await settle();
    const close = host.querySelector<HTMLButtonElement>('.pr-composer__close');
    assert(close?.textContent === 'Close with comment', 'A typed draft turns Close into Close with comment');
    close.click(); await settle();
    assert(writes.join() === 'comment #7: Fixed in #30786,close #7', 'Closing with a draft posts it before closing');
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === 'Issue 7' && host.querySelector('.pr-state')?.textContent?.trim() === 'Closed',
      'The closed issue stays open and shows its new state');
    assert(composer().value === '' && host.querySelector('.pr-composer__close') === null, 'The posted draft clears and a closed issue offers no close');
    open(8); await settle();
    assert(row(7) === undefined && host.querySelector('.issues-list__count')?.textContent === '2', 'Leaving the closed issue drops it from the open list');
    type('Draft for #8'); await settle();
    open(9); await settle();
    assert(composer().value === '', 'A draft never follows the reader to another issue');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const name = 'Closing an issue keeps it in view until the reader leaves';
  try {
    await closeKeepsIssue();
    return [{ name }];
  }
  catch (error) { return [{ name, error: error instanceof Error ? error.message : String(error) }]; }
}
