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
import YearInCodeHeatmap from '../../YearInCodeHeatmap';
import { previewTask } from '../../agents/fixtures/previewBridge';
import { setCached } from '../../dataCache';
import { assert, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge, ContributionCalendarDto, UserCommitDto } from '../../types';
import MyWork from '../MyWork';
import { myWorkItems, type WorkItem } from '../work';

const date = '2026-10-20';
const calendar: ContributionCalendarDto = { totalContributions: 2, weeks: [{ days: [
  { date, contributionCount: 1, color: '#9be9a8' }, { date: '2026-10-21', contributionCount: 1, color: '#9be9a8' },
] }] };

async function contributionRequests() {
  window.localStorage.clear();
  setCached('home:contribution-graph:alice', calendar);
  setCached('home:contribution-graph:bob', calendar);
  const pending: ReturnType<typeof Promise.withResolvers<UserCommitDto[]>>[] = [];
  const calls: string[] = [];
  window.bridge = {
    getUserCommitsOnDate: async (login, day) => {
      calls.push(`${login}:${day}`);
      const read = Promise.withResolvers<UserCommitDto[]>();
      pending.push(read);
      return read.promise;
    },
  } as Partial<Bridge> as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  const show = (login: string) => flushSync(() => root.render(<YearInCodeHeatmap login={login} />));
  const choose = (index = 0) => host.querySelectorAll('rect')[index].dispatchEvent(new MouseEvent('click', { bubbles: true }));
  const message = (text: string): UserCommitDto[] => [{ sha: text, shortMessage: text, repoFullName: 'owner/repo', htmlUrl: 'https://github.com/owner/repo/commit/123', authoredAt: date }];
  try {
    show('alice'); choose();
    await until(() => calls.length === 1, 'Selecting a day starts its request');
    await settle();
    assert(document.querySelector('[role="dialog"]')?.textContent?.includes('Loading commits') === true, 'Loading is shown until the request completes');
    element<HTMLButtonElement>('[aria-label="Close"]').click(); await settle(); choose();
    await settle();
    assert(calls.length === 1, 'Closing and reopening shares the in-flight request');
    pending[0].resolve(message('First day result'));
    await until(() => document.body.textContent?.includes('First day result') === true, 'Setting loading must not cancel the request');
    choose(1);
    await until(() => calls.length === 2, 'Another day gets its own request');
    choose(0); await settle();
    pending[1].resolve(message('Second day result')); await settle();
    assert(document.querySelector('[role="dialog"]')?.textContent?.includes('First day result') === true, 'A late other-day response cannot replace the selected day');
    show('bob');
    await until(() => calls.length === 3, 'Commit caches are scoped to the user');
    pending[2].reject(new Error('Unavailable'));
    await until(() => document.querySelector('[role="dialog"]')?.textContent?.includes("Couldn't load commits") === true, 'A failed request leaves loading');
  }
  finally { dispose(); }
}

async function visibleCheckoutReads() {
  window.localStorage.clear();
  window.localStorage.setItem('bytequay.myWork.collapsed', 'true');
  const calls: { workspace: string; tasks: string[] }[] = [];
  window.bridge = { listAgentTaskChanges: async (workspace, tasks) => {
    calls.push({ workspace, tasks }); return Object.fromEntries(tasks.map(id => [id, { commits: 2, uncommitted: false }]));
  } } as Partial<Bridge> as Bridge;
  const items = Array.from({ length: 6 }, (_, index): WorkItem => {
    const task = previewTask('workspace', `task-${index}`);
    const [item] = myWorkItems({ candidates: [], activity: {}, work: [], viewerLogin: null,
      tasks: [{ task, workspace: { id: 'workspace', name: 'Workspace', repository: 'owner/repo' } }] });
    return { ...item, key: task.id, source: index < 4 ? 'local' : 'issue', title: `Work ${index}`, updatedAt: date,
      running: null, branch: task.generatedBranch, prs: [], lane: 'progress', next: null, keys: [] };
  });
  const { host, root, dispose } = createFixtureRoot();
  const button = (text: string) => [...host.querySelectorAll('button')].find(value => value.textContent?.trim() === text);
  try {
    flushSync(() => root.render(<MyWork items={items} todayKeys={new Set()} />)); await settle();
    assert(Number(calls.length) === 0, 'Collapsed My work never scans checkouts');
    button('Expand')?.click();
    await until(() => calls.length === 1, 'Expanding reads the visible lane');
    assert(calls[0].tasks.join(',') === 'task-0,task-1,task-2', 'Hidden lane overflow is not scanned');
    assert(host.textContent?.includes('2 local commits') === true, 'Visible cards receive their checkout counts');
    button('Show 3 more')?.click();
    await until(() => calls.length === 2, 'Showing more refreshes the expanded lane');
    assert(calls[1].tasks.length === 6, 'Expanded rows are included');
    button('Issues')?.click();
    await until(() => calls.length === 3, 'Filtering changes the checkout read');
    assert(calls[2].tasks.join(',') === 'task-4,task-5', 'Filtered-out tasks are not scanned');
    button('Collapse')?.click(); await settle();
    assert(calls.length === 3, 'Collapsing does not start another checkout read');
  }
  finally { dispose(); }
}

export async function run() {
  return runScenarios([
    ['loads and caches selected contribution days', contributionRequests],
    ['scans only visible task checkouts', visibleCheckoutReads],
  ]);
}
