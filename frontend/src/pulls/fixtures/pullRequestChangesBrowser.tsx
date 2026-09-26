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

import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge, DiffCommitDto, DiffFileDto } from '../../types';
import { DiffViewer, type CommitRange } from '../PullChanges';
import { usePullRequestChanges, type PullRequestChangesSource } from '../usePullRequestChanges';
import { runAssignedPrActions, runCheckLogs, runListSearch, runMainScreen, runOpenReviewWorkspaceStream, runReviewSheet, runReviewWorkspace, runSelectionStability } from './pullsScreenBrowser';
import { assert, settle } from '../../fixtures/harness';

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
    assert(current().allFiles[0]?.filename === 'all', 'A selected commit must retain the complete PR snapshot for working-copy comment checks');
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
    assert(current().allFiles.length === 0, 'A new PR must never expose the previous PR snapshot for commenting');
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
    active = false;
    render();
    await settle();
    active = true;
    render();
    await settle();
    assert(defaultReads === readsBeforeRefresh + 1 && current().files[0]?.filename === 'new-head', 'Hiding and showing the tab must redraw the loaded diff, not read it again');
    completed.push('Defer hidden reads until activated');
    return completed;
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}

async function runDirectCommitSelection(): Promise<string[]> {
  const completed: string[] = [];
  let value: PullRequestChangesSource | null = null;
  let active = false;
  let revision = 'initial';
  let loadedCommits = commits;
  let historyReady = false;
  let historyResolve: ((history: DiffCommitDto[]) => void) | null = null;
  let reads = 0;
  const comparisons: { base: string | undefined; head: string | undefined; resolve: (files: DiffFileDto[]) => void }[] = [];
  Object.assign(window, { bridge: {
    fetchPrDiffFiles: async () => { reads++; return [file(`published-${revision}`)]; },
    fetchPrDiffCommits: () => historyReady ? Promise.resolve(loadedCommits) : new Promise<DiffCommitDto[]>(resolve => { historyResolve = resolve; }),
    refreshPrDiffFiles: (_repo, _number, base, head) => new Promise<DiffFileDto[]>(resolve => comparisons.push({ base, head, resolve })),
  } satisfies Pick<Bridge, 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'refreshPrDiffFiles'> });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const current = (): PullRequestChangesSource => { assert(value !== null, 'Hook must be mounted'); return value; };
  function Harness(): null {
    value = usePullRequestChanges({ repo: 'owner/repo', number: 7 }, { active, revision });
    return null;
  }
  const render = () => flushSync(() => root.render(<Harness />));
  try {
    render();
    flushSync(() => current().selectCommit('two'));
    assert(reads === 0, 'Selecting a commit in a hidden tab must defer the published snapshot read');
    active = true;
    render();
    await settle();
    assert(current().loading && current().error === null && comparisons.length === 0, 'A first-open commit request must wait for published history');
    historyReady = true;
    const resolveHistory = historyResolve as ((history: DiffCommitDto[]) => void) | null;
    assert(resolveHistory !== null, 'Opening Changes must load the PR commits');
    resolveHistory(loadedCommits);
    await settle();
    assert(comparisons[0]?.base === 'one' && comparisons[0]?.head === 'two', 'A pre-load SHA request must resolve through the published commit parent');
    assert(current().commitRange?.start === 1 && current().files.length === 0 && current().loading, 'Pending comparison must not display all changes under a selected commit');
    comparisons[0].resolve([file('published-two')]);
    await settle();
    assert(current().files[0]?.filename === 'published-two' && current().allFiles[0]?.filename === 'published-initial', 'Direct selection must retain the complete published snapshot');
    completed.push('Select a published commit before first opening Changes');

    flushSync(() => current().selectCommit('unpublished'));
    await settle();
    assert(Number(comparisons.length) === 1 && current().commitRange === null && current().error?.includes('not available in the published pull request') === true,
      'An unpublished SHA must show a clear notice without comparing an arbitrary local or historical revision');
    assert(current().files[0]?.filename === 'published-initial' && !current().loading, 'Unavailable selection may retain only the complete published PR diff');
    flushSync(() => current().changeCommitRange({ start: 0, end: 0 }));
    await settle();
    assert(current().error === null && comparisons[1]?.head === 'one', 'The published commit picker must replace an unavailable direct request');
    completed.push('Reject unavailable commits and allow a valid published range');

    revision = 'force-pushed';
    loadedCommits = [{ ...commits[0], sha: 'replacement' }];
    render();
    await settle();
    comparisons[1].resolve([file('obsolete-one')]);
    await settle();
    assert(current().commitRange === null && current().files[0]?.filename === 'published-force-pushed' && current().error === null,
      'Force push must clear the prior range and ignore its delayed comparison');
    flushSync(() => current().selectCommit('two'));
    await settle();
    assert(Number(comparisons.length) === 2 && current().error?.includes('removed by a force push') === true, 'A previously published SHA must not reopen after removal from PR history');
    completed.push('Reject removed commits after a force push');

    flushSync(() => current().refresh());
    await settle();
    assert(current().error === null && current().commitRange === null && current().files[0]?.filename === 'published-force-pushed',
      'Refresh must clear the unavailable direct selection and restore the current published snapshot');
    completed.push('Refresh clears unavailable published commit selection');
    return completed;
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}

/** Commit rows sit inside a label, so a real click must prove the message toggle never selects the commit. */
async function runCommitMessages(): Promise<string> {
  const body = 'Three pipelines shared a custom collector.\n\n    stream.collect(toFragments())\n\nReplace it with toList().';
  const history: DiffCommitDto[] = [
    { ...commits[0], message: `Simplify collectors\n\n${body}\n` },
    { ...commits[1], message: 'Reuse the partition helper\n\nOne place builds the map.' },
    { sha: 'three', parentSha: 'two', message: 'Deduplicate fragments', author: null, authoredAt: null },
  ];
  function Picker() {
    const [range, setRange] = useState<CommitRange>(null);
    return <DiffViewer files={[file('all')]} commits={history} commitRange={range} onCommitRangeChange={setRange} />;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const element = <T extends Element>(selector: string): T => {
    const found = host.querySelector<T>(selector);
    assert(found !== null, `Missing ${selector}`);
    return found;
  };
  const bodies = () => [...host.querySelectorAll('.pull-commit-picker__body')].map(entry => entry.textContent);
  try {
    flushSync(() => root.render(<Picker />));
    const picker = element<HTMLDetailsElement>('.pull-commit-picker');
    picker.open = true;
    await settle();
    assert(host.querySelectorAll('.pull-commit-picker__disclosure').length === 2, 'Only commits with a body may offer their message');
    element<HTMLButtonElement>('[aria-label="Show message"]').click();
    await settle();
    element<HTMLButtonElement>('[aria-label="Show message"]').click();
    await settle();
    const pre = element('.pull-commit-picker__body');
    assert(bodies().join('|') === `${body}|One place builds the map.` && getComputedStyle(pre).whiteSpace === 'pre',
      'Each row must expand independently to its whole body with blank lines and indentation intact');
    assert(host.querySelector('[aria-label="Choose commits, 3 selected"]') !== null && !element<HTMLInputElement>('[aria-label="Select one: Simplify collectors"]').checked,
      'Showing a message must not change the commit selection');
    element<HTMLInputElement>('[aria-label="Select one: Simplify collectors"]').click();
    await settle();
    element<HTMLButtonElement>('[aria-label="Hide message"]').click();
    await settle();
    assert(element<HTMLInputElement>('[aria-label="Deselect one: Simplify collectors"]').checked && bodies().join('|') === 'One place builds the map.',
      'An expanded row must stay selectable, and hiding its message must keep the selection');
    picker.open = false;
    await settle();
    picker.open = true;
    await settle();
    assert(bodies().length === 0 && host.querySelector('.pull-commit-picker__disclosure[aria-expanded="true"]') === null,
      'Closing the commit list must collapse every message');
    return 'Show commit messages without changing the selection';
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
  }
}

export async function run(): Promise<string[]> {
  const completed = await runHook();
  completed.push(...await runDirectCommitSelection());
  completed.push(await runCommitMessages());
  completed.push(await runMainScreen());
  completed.push(await runAssignedPrActions());
  completed.push(await runCheckLogs());
  completed.push(await runReviewWorkspace());
  completed.push(await runReviewWorkspace(true));
  completed.push(await runOpenReviewWorkspaceStream());
  completed.push(await runReviewSheet());
  completed.push(await runSelectionStability());
  completed.push(await runListSearch());
  return completed;
}
