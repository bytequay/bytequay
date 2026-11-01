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
import { setCached } from '../../dataCache';
import { assert, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge, ContributionCalendarDto, UserCommitDto } from '../../types';

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

export async function run() {
  return runScenarios([
    ['loads and caches selected contribution days', contributionRequests],
  ]);
}
