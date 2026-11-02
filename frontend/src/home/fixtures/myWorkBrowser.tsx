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
import '../../index.css';
import { assert, button, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { pr, prDetail } from '../../pulls/fixtures/pullRequest';
import type { Bridge, PullRequestDetailDto, PullRequestDto } from '../../types';
import { AllMyWork, MyWorkBoard } from '../MyWork';
import type { WorkItem } from '../work';

const row = (overrides: Partial<PullRequestDto> = {}) => pr({
  id: 42, repo: 'owner/repository', number: 42, title: 'Improve query planning', htmlUrl: 'https://github.com/owner/repository/pull/42',
  reviewDecision: 'APPROVED', headSha: 'shown-head', workspaceId: 'workspace-1', ...overrides,
});

function work(rows: PullRequestDto[], overrides: Partial<WorkItem> = {}): WorkItem {
  const first = rows[0];
  return {
    key: `${first.repo}#${first.number}`, source: 'pr', label: `repository#${first.number}`, title: first.title, updatedAt: first.updatedAt,
    target: { kind: 'PULL', repo: first.repo, number: first.number, id: String(first.id), workspaceId: first.workspaceId ?? null, connectionId: null, cloudId: null },
    task: null, running: null, branch: null, prs: rows.map(each => ({ repo: each.repo, number: each.number, title: each.title,
      state: each.state === 'closed' ? 'closed' : 'open', row: each })), lane: 'ready', next: 'Merge · no conflicts', decision: null,
    keys: [], ended: null, repos: [...new Set(rows.map(each => each.repo))], agents: [], ...overrides,
  };
}

function mount(items: WorkItem[], all = false) {
  const view = createFixtureRoot();
  view.host.style.cssText = 'padding:20px;';
  let opens = 0;
  flushSync(() => view.root.render(all ? <AllMyWork items={items} onDecision={() => {}} />
    : <MyWorkBoard items={items} lane={null} onLane={() => {}} onOpen={() => { opens++; }} onDecision={() => {}} onAllWork={() => {}} />));
  return { ...view, opens: () => opens };
}

function installBridge(rows: PullRequestDto[], options: {
  detail?: (number: number) => Partial<PullRequestDetailDto>;
  refresh?: (number: number, opened?: boolean) => Promise<Partial<PullRequestDetailDto>>;
  queued?: boolean;
  merge?: (number: number) => Promise<void>;
} = {}) {
  const calls: { operation: string; args: unknown[] }[] = [];
  const detail = (repo: string, number: number) => prDetail({ repo, number, headSha: 'shown-head', ...options.detail?.(number) });
  window.bridge = {
    findStoredPullRequest: async (...args) => {
      calls.push({ operation: 'find', args });
      return rows.find(each => each.repo === args[0] && each.number === args[1]) ?? null;
    },
    refreshPullRequestDetailByRef: async (...args) => {
      calls.push({ operation: 'refresh', args });
      return { full: true, detail: { ...detail(args[0], args[1]), ...await options.refresh?.(args[1], args[3]) }, status: null };
    },
    fetchPullRequestDetailByRef: async (...args) => {
      calls.push({ operation: 'detail', args });
      return detail(args[0], args[1]);
    },
    fetchMergeMode: async (...args) => { calls.push({ operation: 'mode', args }); return { mergeQueue: options.queued ?? false }; },
    mergePr: async (...args) => { calls.push({ operation: 'merge', args }); await options.merge?.(args[2]); },
  } satisfies Partial<Bridge> as Bridge;
  return calls;
}

const mergeCalls = (calls: ReturnType<typeof installBridge>) => calls.filter(call => call.operation === 'merge');

async function mergesOnlyOnceWithoutOpening(): Promise<void> {
  const pull = row();
  let release = () => {};
  const calls = installBridge([pull], { merge: () => new Promise<void>(resolve => { release = resolve; }) });
  const view = mount([work([pull])]);
  let refreshed = 0;
  const onRefresh = () => { refreshed++; };
  window.addEventListener('bytequay:pr-attention-updated', onRefresh);
  try {
    const merge = button('Merge owner/repository #42', view.host);
    assert(merge.textContent?.trim() === 'Merge · no conflicts' && merge.getBoundingClientRect().width > 0, 'The merge action is a visible button');
    merge.click();
    merge.click();
    await until(() => mergeCalls(calls).length > 0, 'A direct click must submit the merge');
    assert(merge.disabled && merge.textContent?.trim() === 'Merging…', 'The button reports progress and disables repeat clicks');
    merge.querySelector('svg')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    assert(view.opens() === 0 && mergeCalls(calls).length === 1, 'Clicking Merge neither opens the card nor submits twice');
    assert(JSON.stringify(mergeCalls(calls)[0].args) === '[42,"owner/repository",42,"default","shown-head","workspace-1"]',
      'The merge binds the displayed revision and uses the pull request workspace');
    release();
    await until(() => refreshed > 0, 'A successful merge must refresh My work');
    assert(merge.disabled && merge.textContent?.trim() === 'Merge requested', 'A successful merge cannot be submitted again');
    assert(calls.filter(call => call.operation === 'refresh').length === 2, 'The remote state is refreshed before and after the merge');
  }
  finally { window.removeEventListener('bytequay:pr-attention-updated', onRefresh); view.dispose(); }
}

async function refusesChangedOrBlockedPullsAndRetries(): Promise<void> {
  const pull = row();
  let current: Partial<PullRequestDetailDto> = { headSha: 'new-head' };
  let reject = true;
  const calls = installBridge([pull], {
    detail: () => current,
    merge: async () => { if (reject) throw new Error("Error invoking remote method 'prs:merge': Error: Branch protection changed"); },
  });
  const view = mount([work([pull])]);
  try {
    const merge = button('Merge owner/repository #42', view.host);
    merge.click();
    await until(() => view.host.querySelector('[role="alert"]') !== null, 'Changed commits must show a refusal');
    assert(view.host.querySelector('[role="alert"]')?.textContent?.includes('New commits arrived') && mergeCalls(calls).length === 0,
      'New commits are never merged from an older card');

    current = { viewerCanWrite: false };
    merge.click();
    await until(() => view.host.querySelector('[role="alert"]')?.textContent?.includes('Not ready to merge') === true, 'Missing permission must show a refusal');
    assert(mergeCalls(calls).length === 0, 'Read-only users cannot submit a merge');

    current = {};
    merge.click();
    await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Branch protection changed', 'The provider failure must appear on the card');
    assert(!merge.disabled && view.opens() === 0, 'A failed merge remains in place and can be retried');

    reject = false;
    merge.click();
    await until(() => merge.textContent === 'Merge requested', 'Retry must finish the merge');
    assert(view.host.querySelector('[role="alert"]') === null && mergeCalls(calls).length === 2, 'Successful retry clears the earlier failure');
  }
  finally { view.dispose(); }
}

async function queuesIndividualPullsThroughTheirAccounts(): Promise<void> {
  const pulls = [row({ workspaceId: null, accountId: 7 }), row({ id: 43, number: 43, workspaceId: 'workspace-2' }), row({ id: 44, number: 44, state: 'closed' })];
  const calls = installBridge(pulls, { queued: true });
  const view = mount([
    work(pulls),
    work([row({ id: 45, number: 45 })], { lane: 'review', next: null }),
    work([row({ id: 46, number: 46 })], { ended: 'Done' }),
  ], true);
  const opened: Event[] = [];
  const onOpen = (event: Event) => opened.push(event);
  window.addEventListener('bytequay:zoom-reference', onOpen);
  try {
    const buttons = [...view.host.querySelectorAll<HTMLButtonElement>('.mw-merge')];
    assert(buttons.length === 2, 'Only the two open pull requests on active ready work offer merge buttons');
    assert(buttons[0].textContent?.includes('#42') && buttons[1].textContent?.includes('#43'), 'Each action names the pull request it merges');
    buttons[0].click();
    await until(() => buttons[0].textContent === 'Merge requested', 'Queue-enabled branches must report their queued merge');
    assert(mergeCalls(calls).length === 1 && !buttons[1].disabled, 'One button merges only its own pull request');
    assert(JSON.stringify(mergeCalls(calls)[0].args) === '[42,"owner/repository",42,"when-ready","shown-head",{"accountId":7}]',
      'An account-only request keeps its account when queueing');
    for (const call of calls) {
      assert(JSON.stringify(call.args.at(-1)) === '{"accountId":7}', `${call.operation} must keep the same hosting account`);
    }
    buttons[1].click();
    await until(() => buttons[1].textContent === 'Merge requested', 'The second pull request has its own merge action');
    assert(JSON.stringify(mergeCalls(calls)[1].args) === '[43,"owner/repository",43,"when-ready","shown-head","workspace-2"]',
      'Each linked pull request keeps its own workspace');
    assert(opened.length === 0, 'All my work also merges in place');
    await settle();
  }
  finally { window.removeEventListener('bytequay:zoom-reference', onOpen); view.dispose(); }
}

async function loadsAndMergesSeparateCardsIndependently(): Promise<void> {
  const pulls = [row(), row({ id: 47, number: 47, headSha: null, workspaceId: null, accountId: 7 })];
  let releaseUpper = () => {};
  let loadLower = () => {};
  const calls = installBridge(pulls, {
    detail: number => ({ headSha: number === 47 ? 'loaded-head' : 'shown-head' }),
    refresh: async (number, opened) => number === 47 && opened === true
      ? new Promise<Partial<PullRequestDetailDto>>(resolve => { loadLower = () => resolve({ headSha: 'loaded-head' }); }) : {},
    merge: async number => { if (number === 42) await new Promise<void>(resolve => { releaseUpper = resolve; }); },
  });
  const view = mount(pulls.map(pull => work([pull])));
  try {
    const upper = button('Merge owner/repository #42', view.host);
    const lower = button('Merge owner/repository #47', view.host);
    assert(lower.disabled && lower.textContent?.trim() === 'Checking merge…', 'A card without a cached revision loads its own merge check');
    assert(!upper.disabled, 'Loading one card must not disable another ready card');
    lower.click();
    assert(mergeCalls(calls).length === 0, 'Loading a missing revision is read-only');
    assert(JSON.stringify(calls) === '[{"operation":"refresh","args":["owner/repository",47,null,true,{"accountId":7}]}]',
      'The missing revision loads through its account without marking the pull request viewed');

    upper.click();
    await until(() => mergeCalls(calls).length === 1, 'The upper card can merge while the lower card loads');
    loadLower();
    await until(() => !lower.disabled, 'The lower card enables itself when its revision is loaded');
    assert(upper.disabled && upper.textContent?.trim() === 'Merging…', 'The upper card remains in flight independently');
    lower.click();
    await until(() => lower.textContent === 'Merge requested', 'The lower card merges without waiting for the upper card');
    assert(JSON.stringify(mergeCalls(calls)[1].args) === '[47,"owner/repository",47,"default","loaded-head",{"accountId":7}]',
      'The lower card merges only its loaded revision through its own account');
    assert(upper.textContent?.trim() === 'Merging…' && view.opens() === 0, 'The second action leaves the first request and board in place');
    releaseUpper();
    await until(() => upper.textContent === 'Merge requested', 'The upper card finishes independently');
    assert(lower.disabled, 'Completing the upper card cannot re-enable the lower card after its merge');
  }
  finally { releaseUpper(); view.dispose(); }
}

async function retriesMissingRevisionAndRejectsLaterCommits(): Promise<void> {
  const pull = row({ headSha: null });
  let failed = true;
  let currentHead = 'loaded-head';
  const calls = installBridge([pull], {
    detail: () => ({ headSha: currentHead }),
    refresh: async (_number, opened) => {
      if (opened === true && failed) throw new Error("Error invoking remote method 'prs:refresh': Error: Could not load merge status");
      return { headSha: currentHead };
    },
  });
  const view = mount([work([pull])]);
  try {
    const merge = button('Merge owner/repository #42', view.host);
    await until(() => view.host.querySelector('[role="alert"]') !== null, 'A failed merge check must show its error');
    assert(!merge.disabled && merge.textContent?.trim() === 'Retry merge check', 'A failed load offers a local retry');
    assert(view.host.querySelector('[role="alert"]')?.textContent === 'Could not load merge status', 'The load error is readable');
    failed = false;
    merge.click();
    await until(() => merge.textContent === 'Merge · no conflicts', 'Retry loads the missing revision');
    assert(mergeCalls(calls).length === 0 && view.host.querySelector('[role="alert"]') === null, 'Retry only reads; merging still needs its own click');
    currentHead = 'new-head';
    merge.click();
    await until(() => view.host.querySelector('[role="alert"]')?.textContent?.includes('New commits arrived') === true,
      'New commits after loading must require another review');
    assert(mergeCalls(calls).length === 0 && view.opens() === 0, 'Hydrating a card preserves the stale-revision guard');
  }
  finally { view.dispose(); }
}

export function run() {
  return runScenarios([
    ['merges once without opening', mergesOnlyOnceWithoutOpening],
    ['refuses stale or blocked work and retries failures', refusesChangedOrBlockedPullsAndRetries],
    ['queues individual pull requests through their accounts', queuesIndividualPullsThroughTheirAccounts],
    ['loads and merges separate cards independently', loadsAndMergesSeparateCardsIndependently],
    ['retries a missing revision and rejects later commits', retriesMissingRevisionAndRejectsLaterCommits],
  ]);
}

export async function showPreview(): Promise<void> {
  document.body.style.margin = '0';
  document.body.style.background = 'var(--bg)';
  mount([
    work([row({ id: 1, number: 1, title: 'Add query diagnostics' })], { lane: 'progress', next: null }),
    work([row({ id: 2, number: 2, title: 'Improve result caching', reviewDecision: null })], { lane: 'review', next: null }),
    work([row({ id: 3, number: 3, title: 'Handle empty result sets', ciStatus: 'FAILING', reviewDecision: null })], { lane: 'needs', next: 'Fix the failing checks' }),
    work([row()]),
    work([row({ id: 43, number: 43, title: 'Reduce repeated metadata reads' })]),
  ]);
  await settle();
}
