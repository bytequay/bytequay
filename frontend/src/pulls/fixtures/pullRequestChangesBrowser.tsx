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
import type { Bridge, DiffCommitDto, DiffFileDto } from '../../types';
import { usePullRequestChanges, type PullRequestChangesSource } from '../usePullRequestChanges';
import { runMainScreen, runReviewSheet, runReviewWorkspace } from './pullsScreenBrowser';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
const file = (filename: string): DiffFileDto => ({ filename, status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old\n+new' });
const commits: DiffCommitDto[] = ['one', 'two'].map((sha, index): DiffCommitDto => ({ sha, parentSha: index === 0 ? 'base' : 'one', message: sha, author: null, authoredAt: null }));

/** Real React effects and user selections, with controllable requests to expose response races. */
async function runHook(): Promise<string[]> {
  const completed: string[] = [];
  let value: PullRequestChangesSource | null = null;
  let repository = 'owner/first';
  let revision = 'head-one';
  let active = true;
  let defaultFile = 'all';
  let defaultReads = 0;
  let loadedCommits = commits;
  const comparisons: { repo: string; base: string | undefined; head: string | undefined; resolve: (files: DiffFileDto[]) => void; reject: (failure: Error) => void }[] = [];
  const bridge = {
    fetchPrDiffFiles: async () => { defaultReads++; return [file(defaultFile)]; },
    fetchPrDiffCommits: async () => loadedCommits,
    refreshPrDiffFiles: (repo, _number, base, head) => new Promise<DiffFileDto[]>((resolve, reject) => comparisons.push({ repo, base, head, resolve, reject })),
  } satisfies Pick<Bridge, 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'refreshPrDiffFiles'>;
  Object.assign(window, { bridge });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const current = (): PullRequestChangesSource => { assert(value !== null, 'Hook must be mounted'); return value; };
  function Harness() {
    value = usePullRequestChanges({ repo: repository, number: 7 }, { active, revision });
    return <>
      <button onClick={() => value?.changeCommitRange({ start: 0, end: 0 })}>First</button>
      <button onClick={() => value?.changeCommitRange({ start: 1, end: 1 })}>Second</button>
      <button onClick={() => value?.changeCommitRange(null)}>All</button>
      <button onClick={() => value?.refresh()}>Refresh</button>
      <div>{value.files.map(entry => entry.filename).join(',')}</div>
    </>;
  }
  const render = () => flushSync(() => root.render(<Harness />));
  const click = (label: string) => {
    const button = [...host.querySelectorAll('button')].find(entry => entry.textContent === label);
    assert(button !== undefined, `Missing ${label} button`);
    flushSync(() => button.click());
  };
  try {
    render();
    await settle();
    assert(defaultReads === 1 && current().files[0]?.filename === 'all', 'Initial read must use the complete cached diff');
    click('First');
    assert(current().files.length === 0 && current().loading, 'Selecting a commit must immediately hide the previous diff');
    await settle();
    assert(comparisons[0]?.base === 'base' && comparisons[0]?.head === 'one', 'A single commit must compare its parent against its SHA');
    comparisons[0].resolve([file('first-only')]);
    await settle();
    assert(current().files[0]?.filename === 'first-only' && current().commits.length === 2, 'Selected files must preserve the full commit picker');
    click('All');
    await settle();
    assert(current().commitRange === null && current().files[0]?.filename === 'all', 'All commits must restore the cached complete diff');
    completed.push('Select a commit and return to all commits');

    click('First');
    await settle();
    click('Second');
    await settle();
    comparisons[2].resolve([file('second-only')]);
    await settle();
    comparisons[1].resolve([file('late-first')]);
    await settle();
    assert(current().files[0]?.filename === 'second-only' && current().commitRange?.start === 1, 'An earlier commit response must not replace the latest selection');
    completed.push('Ignore out-of-order comparison responses');

    click('First');
    await settle();
    repository = 'owner/second';
    defaultFile = 'other-pr';
    render();
    assert(current().commitRange === null && current().files.length === 0, 'Changing PR must discard the previous selection and files before effects run');
    await settle();
    comparisons[3].reject(new Error('late failure from the old PR'));
    await settle();
    assert(current().files[0]?.filename === 'other-pr' && current().error === null, 'An earlier PR failure must not affect the current PR');
    click('Second');
    await settle();
    revision = 'head-two';
    defaultFile = 'new-head';
    render();
    await settle();
    comparisons[4].resolve([file('old-head')]);
    await settle();
    assert(current().commitRange === null && current().files[0]?.filename === 'new-head', 'A new head must reset the range and reject the older comparison');
    completed.push('Reset selections across PR and head changes');

    click('First');
    await settle();
    comparisons[5].reject(new Error('Comparison unavailable'));
    await settle();
    assert(current().files.length === 0 && current().error === 'Comparison unavailable' && !current().loading, 'A failed comparison must not show the complete diff under a selected commit');
    click('Refresh');
    await settle();
    assert(current().commitRange === null && current().files[0]?.filename === 'new-head' && current().error === null, 'Refresh must clear the failed selection and reload cached changes');
    loadedCommits = [{ ...commits[0], parentSha: null }, commits[1]];
    click('Refresh');
    await settle();
    click('First');
    await settle();
    assert(comparisons.length === 6 && current().files.length === 0 && current().error?.includes('Git history') === true, 'A missing parent must report an error without requesting an invalid comparison');
    completed.push('Keep failed and invalid comparisons distinct from all commits');

    active = false;
    render();
    const readsBeforeRefresh = defaultReads;
    click('Refresh');
    await settle();
    assert(defaultReads === readsBeforeRefresh, 'Inactive changes must not read snapshots');
    active = true;
    render();
    await settle();
    assert(defaultReads === readsBeforeRefresh + 1 && current().files[0]?.filename === 'new-head', 'Activating changes must load the invalidated snapshot');
    completed.push('Defer hidden reads until activated');
    return completed;
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}

export async function run(): Promise<string[]> {
  const completed = await runHook();
  completed.push(await runMainScreen());
  completed.push(await runReviewWorkspace());
  completed.push(await runReviewSheet());
  return completed;
}
