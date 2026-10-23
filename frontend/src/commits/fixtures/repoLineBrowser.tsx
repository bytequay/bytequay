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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { CherryPickPullRequestPreviewRequest, GitCommitDto, GitDiffFileDto, GitWorktreeStatusDto, PullRequestDto, WorkspaceDto } from '../../types';
import { assert, changeInput, element, runScenarios, settle, textButton, until } from '../../fixtures/harness';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import { CommitsBrowser } from '../CommitsTab';
import { lineRepos } from '../model';
import '../../css/commits.css';

const here = workspaceFixture({ id: 'here', name: 'acme/product', monitoredOwner: 'acme', monitoredRepo: 'product', selectedOwner: 'acme',
  selectedRepo: 'product', lastCodeSyncAt: '2026-01-01T00:00:00Z' });
const repo = (id: string, owner: string, name: string): WorkspaceDto => workspaceFixture({ id, name, monitoredOwner: owner, monitoredRepo: id,
  defaultBranch: id === 'oss' ? 'master' : 'main' });
const line = lineRepos(here, {
  links: [],
  related: [
    { workspaceId: 'internal', position: 'UPSTREAM', remote: 'backport-2', unavailableReason: null },
    { workspaceId: 'oss', position: 'UPSTREAM', remote: 'backport-1', unavailableReason: null },
    { workspaceId: 'downstream', position: 'DOWNSTREAM', remote: null, unavailableReason: null },
  ],
  line: [
    { workspaceId: 'oss', parentWorkspaceId: null },
    { workspaceId: 'internal', parentWorkspaceId: 'oss' },
    { workspaceId: 'here', parentWorkspaceId: 'internal' },
    { workspaceId: 'downstream', parentWorkspaceId: 'here' },
  ],
}, [repo('oss', 'upstream', 'oss'), repo('internal', 'acme', 'internal'), repo('downstream', 'acme', 'downstream')]);

const commit = (sha: string, title: string): GitCommitDto => ({
  sha: sha.repeat(40), title, body: '', author: 'Dev', committer: 'Dev', authoredAt: '2026-01-02T00:00:00Z', committedAt: '2026-01-02T00:00:00Z',
});
const pull = (number: number, title: string): PullRequestDto => ({
  id: number, repo: 'upstream/oss', number, title, author: 'alice', htmlUrl: `https://github.com/upstream/oss/pull/${number}`, createdAt: null,
  updatedAt: '2026-01-02T00:00:00Z', mergedAt: '2026-01-02T00:00:00Z', origin: 'OTHER', labels: [], labelColors: null, draft: false, viewedAt: null,
  reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null, additions: 0, deletions: 0, commentCount: 0, state: 'merged',
  mergeable: null, mergeableState: null, headPushedAt: null, headRef: null,
});

function mount() {
  const calls = { logs: [] as string[], merged: [] as string[], previews: [] as CherryPickPullRequestPreviewRequest[], backports: [] as unknown[][] };
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    gitOverview: async () => ({ currentBranch: 'main', branches: ['main'], remotes: [
      { name: 'backport-1', url: 'https://github.com/upstream/oss.git', headBranch: 'master' },
      { name: 'backport-2', url: 'https://github.com/acme/internal.git', headBranch: 'main' }] }),
    gitRemoteBranches: async (_: string, remote: string) => (remote === 'backport-1' ? ['master'] : ['main', 'release-1']),
    gitStatus: async (): Promise<GitWorktreeStatusDto> => ({ branch: 'main', files: [] }),
    gitDiff: async (): Promise<GitDiffFileDto[]> => [],
    gitLog: async (workspaceId: string, source: string, branch: string, _limit: number, remote?: string) => {
      calls.logs.push(`${workspaceId}:${source}:${remote ?? ''}:${branch}`);
      return { commits: [commit(remote === 'backport-1' ? 'a' : 'b', `${remote ?? 'here'} commit`)], refresh: { workspaceId, state: 'IDLE' } };
    },
    getCommitRefreshStatus: async (workspaceId: string) => ({ workspaceId, state: 'IDLE' }),
    listMergedPullRequests: async (workspaceId: string, search: string) => {
      calls.merged.push(`${workspaceId}:${search}`);
      return [pull(28411, 'Fix NPE when a Parquet column is missing'), pull(28398, 'Retry S3 reads')]
        .filter(pr => search === '' || pr.title.toLowerCase().includes(search.toLowerCase()) || String(pr.number) === search);
    },
    previewCherryPickPullRequest: async (request: CherryPickPullRequestPreviewRequest) => {
      calls.previews.push(request);
      throw new Error('Preview stopped by the fixture');
    },
    startBackportChain: async (workspaceId: string, request: unknown) => {
      calls.backports.push([workspaceId, request]);
      return {};
    },
    getBackportChains: async (): Promise<[]> => [],
    listCherryPickExecutions: async (): Promise<[]> => [],
  } });
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<CommitsBrowser workspace={here} settings={{ commitsTarget: { remote: 'origin', branch: 'main', overridden: false } }}
    repos={line} onSourceSelectionChange={() => {}} onOpenPullRequest={() => {}} onOpenSettings={() => {}} />));
  const chip = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('.cmt-line-repo')].find(item => item.textContent === name);
  return { host, calls, chip, dispose };
}

async function cherryPickAPullRequestFromTheLine() {
  const view = mount();
  try {
    await until(() => view.calls.logs.includes('here:WORKSPACE::main'), 'This workspace history loads first');
    assert(view.chip('acme/product')?.getAttribute('aria-pressed') === 'true', 'This workspace starts selected');
    assert(view.host.querySelector('[aria-label="List"]') !== null, 'A workspace something takes changes from offers its pull requests');
    view.chip('oss')?.click();
    await until(() => view.calls.logs.includes('here:REMOTE:backport-1:master'), 'oss history is read through its line remote on its default branch');
    textButton('Pull requests', view.host).click();
    await until(() => view.calls.merged.includes('oss:') && view.host.textContent?.includes('Retry S3 reads') === true, 'Merged pull requests of oss are listed');
    changeInput(element<HTMLInputElement>('input[aria-label="Search merged pull requests by title, number, or link"]', view.host), 'https://github.com/upstream/oss/pull/28411');
    await until(() => view.calls.merged.includes('oss:28411'), 'A pasted link searches by its number');
    await until(() => !view.host.textContent?.includes('Retry S3 reads'), 'Only the matching pull request stays');
    element<HTMLButtonElement>('.cmt-merged-row', view.host).click();
    await settle();
    assert(view.host.textContent?.includes('#28411 selected') === true, 'The strip names the picked pull request');
    textButton('Cherry-pick', view.host).click();
    await until(() => view.calls.previews.length === 1, 'Cherry-pick previews the pull request');
    const [request] = view.calls.previews;
    assert(JSON.stringify(request) === JSON.stringify({ workspaceId: 'here', sourceRemote: 'backport-1', owner: 'upstream', repo: 'oss', number: 28411, targetBranch: 'main' }),
      `Preview names the source remote, repository and target: ${JSON.stringify(request)}`);
    await until(() => view.host.querySelector('[role="alert"]')?.textContent === 'Preview stopped by the fixture', 'A failed preview is reported in place');
  } finally { view.dispose(); }
}

async function pickAnOlderPullRequestByNumber() {
  const view = mount();
  try {
    view.chip('oss')?.click();
    await settle();
    textButton('Pull requests', view.host).click();
    await settle();
    changeInput(element<HTMLInputElement>('input[aria-label="Search merged pull requests by title, number, or link"]', view.host), '#99');
    await until(() => view.host.textContent?.includes('Not in the recent list') === true, 'An unlisted number is still offered');
    element<HTMLButtonElement>('.cmt-merged-row', view.host).click();
    await settle();
    textButton('Cherry-pick', view.host).click();
    await until(() => view.calls.previews[0]?.number === 99, 'The typed number is previewed');
  } finally { view.dispose(); }
}

async function backportAlongTheLine() {
  const view = mount();
  const routeText = () => [...view.host.querySelectorAll('.cmt-backport-route li')].map(item => item.textContent).join(' | ');
  try {
    view.chip('oss')?.click();
    await settle();
    textButton('Pull requests', view.host).click();
    await until(() => view.host.querySelector('.cmt-merged-row') !== null, 'Merged pull requests are listed');
    element<HTMLButtonElement>('.cmt-merged-row', view.host).click();
    await settle();
    textButton('Backport', view.host).click();
    await settle();
    const dialog = element<HTMLDialogElement>('dialog', view.host, 'Backport asks before starting');
    assert(dialog.textContent?.includes('Backport upstream/oss#28411') === true, 'The dialog names the pull request');
    assert(routeText() === 'internalfrom oss | acme/productfrom internal | downstreamfrom acme/product', `The line below oss is the route: ${routeText()}`);
    assert(view.calls.backports.length === 0, 'Opening the dialog starts nothing');
    const boxes = [...dialog.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')];
    boxes[1].click();
    await settle();
    assert(boxes[2].disabled && !boxes[2].checked, 'A repository whose source is left out is left out too');
    textButton('Start backport', dialog).click();
    await until(() => view.calls.backports.length === 1, 'Start backport sends the route');
    assert(JSON.stringify(view.calls.backports[0]) === JSON.stringify(['here', { sourceWorkspaceId: 'oss', number: 28411, destinations: ['internal'], sourceHops: [-1] }]),
      `Only the kept hops are sent: ${JSON.stringify(view.calls.backports[0])}`);
    await until(() => view.host.textContent?.includes('Cherry-pick tasks') === true, 'The chain shows with cherry-picks once started');

    view.chip('acme/product')?.click();
    await settle();
    textButton('Pull requests', view.host).click();
    await until(() => view.calls.merged.includes('here:') && view.host.querySelector('.cmt-merged-row') !== null,
      'This workspace lists its own merged pull requests because something takes changes from it');
    element<HTMLButtonElement>('.cmt-merged-row', view.host).click();
    await settle();
    assert(!view.host.textContent?.includes('Cherry-pick'), 'A pull request of this workspace is only carried downstream');
    textButton('Backport', view.host).click();
    await settle();
    textButton('Start backport', element('dialog', view.host)).click();
    await until(() => view.calls.backports.length === 2, 'A backport from this workspace starts');
    assert(JSON.stringify(view.calls.backports[1]) === JSON.stringify(['here', { sourceWorkspaceId: 'here', number: 28411, destinations: ['downstream'], sourceHops: [-1] }]),
      `Downstream of this workspace is the route: ${JSON.stringify(view.calls.backports[1])}`);
  } finally { view.dispose(); }
}

async function downstreamOpensItsOwnCommits() {
  const view = mount();
  try {
    await settle();
    assert(view.chip('downstream')?.title === 'downstream takes changes from acme/product · open its Commits', 'Downstream repositories say where they take changes from');
    view.chip('downstream')?.click();
    await settle();
    assert(window.location.hash === '#/workspaces/downstream/commits', `Downstream opens its own Commits: ${window.location.hash}`);
  } finally { view.dispose(); window.location.hash = ''; }
}

export function run() {
  return runScenarios([
    ['cherry-picks a merged pull request found in an upstream repository of the line', cherryPickAPullRequestFromTheLine],
    ['picks an older pull request by its number', pickAnOlderPullRequestByNumber],
    ['backports a pull request down the repo line after confirming the route', backportAlongTheLine],
    ['opens a downstream repository in its own Commits', downstreamOpensItsOwnCommits],
  ]);
}
