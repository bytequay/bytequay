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
import { assert, element, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge, GitCommitDto, GitDiffFileDto } from '../../types';
import HistoryView from '../HistoryView';

export async function run(): Promise<string> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot('main');
  const commits: GitCommitDto[] = ['newer', 'older'].map(sha => ({
    sha, title: `${sha} commit`, body: '', author: 'alice', committer: 'alice',
    authoredAt: '2026-09-20T10:00:00Z', committedAt: '2026-09-20T10:00:00Z',
  }));
  const file = (name: string): GitDiffFileDto => ({
    path: `${name}-only.txt`, status: 'modified', additions: 1, deletions: 1,
    patch: `@@ -1 +1 @@\n-before\n+${name} code only`,
  });
  let nextDiff = Promise.withResolvers<GitDiffFileDto[]>();
  const reads: Parameters<Bridge['gitDiff']>[] = [];
  const bridge = {
    gitDiff: (...args) => {
      reads.push(args);
      return args[2] === 'newer' ? Promise.resolve([file('newer')]) : nextDiff.promise;
    },
  } satisfies Pick<Bridge, 'gitDiff'>;
  Object.assign(window, { bridge });
  const render = (snapshot: GitCommitDto[]) => flushSync(() => root.render(
    <HistoryView workspaceId="workspace" branch="main" logRef="main" isUpstream={false}
      canRewrite={false} commits={snapshot} loading={false} checked={new Set()}
      onChangeChecked={() => {}} onHistoryRewritten={() => {}} onOpenPullRequest={() => {}} />,
  ));
  try {
    render(commits);
    await until(() => host.textContent?.includes('newer-only.txt') === true, 'The initial commit diff must load');
    const rows = host.querySelectorAll<HTMLElement>('.cmt-commit-row');
    assert(rows.length === 2, 'Both commits must be selectable');
    rows[1].click(); await settle();
    assert(element('.cmt-view-title', host).textContent === 'older commit', 'The diff title must follow the selected commit');
    assert(host.querySelector('.pull-changes__loading') !== null && !host.textContent?.includes('newer-only.txt'),
      'The next read must hide the previous commit’s files');
    nextDiff.reject(new Error('Cannot read selected commit')); await settle();
    assert(element('[role="alert"]', host).textContent === 'Cannot read selected commit', 'A failed diff must show its error');
    assert(host.querySelector('.pull-changes__loading') === null && host.querySelector('.pull-diff-file') === null
      && !host.textContent?.includes('newer-only.txt') && !host.textContent?.includes('newer code only'),
    'A failed read must never display another commit’s files or patch under the selected title');

    nextDiff = Promise.withResolvers<GitDiffFileDto[]>();
    render([...commits]); await settle();
    nextDiff.resolve([file('older')]);
    await until(() => host.textContent?.includes('older-only.txt') === true, 'A fresh log snapshot must retry the selected diff');
    assert(host.querySelector('[role="alert"]') === null && !host.textContent?.includes('newer-only.txt'),
      'A successful retry must clear the error and display only the selected commit’s files');
    assert(reads.length === 3 && reads.slice(1).every(([, base, head]) => base === 'older^' && head === 'older'),
      'Selection and retry must request the same selected commit');
    return 'Failed commit reads never show another commit’s files and refresh retries the selected commit';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}
