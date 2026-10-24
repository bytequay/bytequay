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
import { createFixtureRoot } from './reactFixture';
import { workspaceFixture } from '../workspaces/fixtures/workspace';
import AddRepoModal from '../AddRepoModal';
import '../css/agent-review.css';
import '../css/base.css';
import '../css/modals.css';
import AgentReviewTab from '../pulls/agent-review/AgentReviewTab';
import type { PullRequestDetailDto, RepositorySearchResultDto, WatchRepositoryCommand, WatchRepositoryPreviewDto, WatchRepositoryTopology, WorkspaceDto } from '../types';
import { assert, settle, element as requiredElement } from './harness';

const repository = { owner: 'octocat', repo: 'project' };
const result: RepositorySearchResultDto = {
  id: 1, repository, fullName: 'octocat/project', description: null, visibility: 'public',
  fork: false, defaultBranch: 'main', upstream: null, existingWorkspaceId: null,
};
const preview: WatchRepositoryPreviewDto = {
  selectedRepositoryId: 1, selectedRepository: repository, visibility: 'public', topology: 'DIRECT',
  recommendedTopology: 'DIRECT', forkModeAvailable: false,
  upstreamRepositoryId: null, upstreamRepository: null, monitoredRepositoryId: 1, monitoredRepository: repository,
  defaultBranch: 'main', developmentBase: 'origin/main', pushRemote: 'origin', pushRepository: repository,
  pushWritable: true, forkRequired: false, pullRequestBase: repository, fingerprint: 'routing', existingWorkspaceId: null,
};
const workspace = workspaceFixture({
  id: 'workspace', name: 'project', selectedRepositoryId: 1, selectedOwner: 'octocat',
  monitoredRepositoryId: 1, monitoredOwner: 'octocat', localRepositoryPath: '/managed/project',
});

export async function run(): Promise<string[]> {
  const otherRepository = { owner: 'octocat', repo: 'other' };
  const otherResult = { ...result, id: 2, repository: otherRepository, fullName: 'octocat/other' };
  const otherPreview = { ...preview, selectedRepositoryId: 2, selectedRepository: otherRepository,
    monitoredRepositoryId: 2, monitoredRepository: otherRepository, pushRepository: otherRepository,
    pullRequestBase: otherRepository, fingerprint: 'other-routing' };
  const search = Promise.withResolvers<RepositorySearchResultDto[]>();
  let pendingPreview = Promise.withResolvers<WatchRepositoryPreviewDto>();
  const pendingWatch = Promise.withResolvers<WorkspaceDto>();
  const commands: WatchRepositoryCommand[] = [];
  const added: WorkspaceDto[] = [];
  let previews = 0;
  let closes = 0;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    searchRepositories: () => search.promise,
    previewWatch: (id: number) => { previews++; return id === 2 ? Promise.resolve(otherPreview) : pendingPreview.promise; },
    watchRepository: (command: WatchRepositoryCommand) => { commands.push(command); return pendingWatch.promise; },
  } satisfies Partial<Window['bridge']> });
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  const render = () => flushSync(() => root.render(<AddRepoModal
    onAdded={value => added.push(value)}
    onOpenWorkspace={() => { throw new Error('New watches must not open an existing workspace'); }}
    onClose={() => { closes++; root.render(null); }}
  />));
  const click = async (selector: string) => {
    const element = requiredElement<HTMLElement>(selector, host, `Missing ${selector}`);
    element.click();
    await settle();
  };
  const escape = async () => {
    host.querySelector('dialog')?.dispatchEvent(new Event('cancel', { bubbles: false, cancelable: true }));
    await settle();
  };
  try {
    render();
    await click('dialog.modal');
    assert(closes === 0 && host.textContent?.includes('Searching…'), 'Background clicks must preserve a pending search');
    search.resolve([result, otherResult]);
    for (let i = 0; i < 30 && !host.querySelector('.modal-repo-row'); i++) await settle();
    await click('.modal-repo-row');
    await click('dialog.modal');
    assert(closes === 0 && previews === 1, 'Background clicks must not close or restart the selected preview');
    assert(host.querySelector('[role="status"]')?.textContent?.includes('octocat/project'), 'Pending preview must name the selected repository');
    pendingPreview.resolve(preview);
    await settle();
    await click('dialog.modal');
    assert(host.querySelector('.modal-preview__confirm') !== null && commands.length === 0, 'Background clicks must preserve confirmation without starting Watch');
    await click('.modal-preview__confirm');
    await click('dialog.modal');
    await click('.modal__close');
    await escape();
    assert(closes === 0 && Number(commands.length) === 1, 'An active Watch must stay open without duplicate commands');
    assert(host.querySelector('[role="status"]')?.textContent?.includes('Saving your workspace…'), 'An active Watch must show its progress');
    pendingWatch.resolve(workspace);
    await settle();
    assert(added.length === 1 && added[0] === workspace && Number(closes) === 1, 'Success must publish the saved workspace and close once');

    pendingPreview = Promise.withResolvers<WatchRepositoryPreviewDto>();
    render();
    for (let i = 0; i < 30 && !host.querySelector('.modal-repo-row'); i++) await settle();
    await click('.modal-repo-row');
    assert(Number(previews) === 2, 'Reopening the dialog must start with fresh routing');
    await escape();
    pendingPreview.resolve(preview);
    await settle();
    assert(host.querySelector('.modal__search') !== null && host.querySelector('.modal-preview') === null, 'Escape must return to search and ignore the late preview');
    await click('.modal-repo-row');
    assert(Number(previews) === 2 && host.querySelector('.modal-preview__name')?.textContent === result.fullName,
      'Reselecting a repository must reuse its completed preview');
    await escape();
    await click('.modal-repo-row:nth-child(2)');
    assert(Number(previews) === 3 && host.querySelector('.modal-preview__name')?.textContent === otherResult.fullName,
      'Each repository must have its own cached routing');
    await escape();
    await click('.modal-repo-row');
    assert(Number(previews) === 3 && host.querySelector('.modal-preview__name')?.textContent === result.fullName,
      'Returning to the first repository must restore its own cached routing');
    await click('.modal__close');
    assert(Number(closes) === 2 && host.childElementCount === 0, 'Explicit Close must still dismiss the modal');
    let failLookup = true;
    let watched = 0;
    Object.defineProperty(window, 'bridge', { configurable: true, value: {
      searchRepositories: async (query: string) => {
        assert(query === result.fullName, 'Watch must look up the current PR repository');
        if (failLookup) throw new Error('Repository access unavailable');
        return [result];
      },
      previewWatch: async (id: number) => {
        assert(id === result.id, 'Watch must preview the exact repository');
        return preview;
      },
      watchRepository: async (command: WatchRepositoryCommand) => {
        assert(command.selectedRepositoryId === result.id && command.previewFingerprint === preview.fingerprint,
          'Watch must use the confirmed routing');
        watched++;
        return workspace;
      },
    } satisfies Partial<Window['bridge']> });
    const detail: PullRequestDetailDto = {
      id: 1, repo: result.fullName, number: 2, title: 'Update project', htmlUrl: null, state: 'open', draft: false,
      mergeable: null, mergeableState: null, author: 'octocat', authorAvatarUrl: null, createdAt: null, updatedAt: null,
      mergedAt: null, baseRef: 'main', headRef: 'feature', headSha: 'head', body: null, labels: [], assignees: [], milestone: null,
      reviewers: [], additions: 1, deletions: 0, changedFiles: 1, approvalCount: 0, ciStatus: 'NONE',
      recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: true,
    };
    const Review = () => {
      const [workspaceId, setWorkspaceId] = useState<string>();
      return <AgentReviewTab detail={detail} workspaceId={workspaceId} viewer={null} review={null}
        onReviewChange={() => {}} onNavigateToChanges={() => {}} focusFindingId={null}
        onRepositoryWatched={() => setWorkspaceId(workspace.id)} />;
    };
    flushSync(() => root.render(<Review />));
    await click('.agr-action');
    assert(host.querySelector('[role="alert"]')?.textContent === 'Repository access unavailable', 'Lookup failures must be visible');
    failLookup = false;
    await click('.agr-action');
    assert(host.querySelector('.modal-preview__confirm') !== null && watched === 0, 'Watch must require confirmation before writing');
    await click('.modal__close');
    assert(watched === 0 && host.querySelector('.modal') === null, 'Cancelling must not watch the repository');
    await click('.agr-action');
    await click('.modal-preview__confirm');
    assert(Number(watched) === 1 && host.textContent?.includes('Start review') && host.querySelector('.modal') === null,
      'Watching must refresh the review context and close confirmation');
    return ['background clicks preserve progress', 'explicit cancellation discards a late preview', 'preview caches belong to each repository and dialog',
      'watch from review confirms and refreshes'];
  }
  finally { disposeRoot(); }
}


export async function runStrategies(): Promise<string[]> {
  const upstream = { owner: 'company', repo: 'private-project' };
  const direct: WatchRepositoryPreviewDto = { ...preview, selectedRepository: upstream, monitoredRepository: upstream,
    pushRepository: upstream, pullRequestBase: upstream, visibility: 'private', forkModeAvailable: true,
    pushWritable: false, fingerprint: 'direct-routing' };
  const fork: WatchRepositoryPreviewDto = { ...direct, topology: 'FORK', pushRepository: { owner: 'octocat', repo: 'private-project' },
    upstreamRepository: upstream, upstreamRepositoryId: 1, developmentBase: 'upstream/main', forkRequired: true, fingerprint: 'fork-routing' };
  const pending: ReturnType<typeof Promise.withResolvers<WatchRepositoryPreviewDto>>[] = [];
  const commands: WatchRepositoryCommand[] = [];
  const requests: (WatchRepositoryTopology | undefined)[] = [];
  let failWatch = true;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    previewWatch: async (_id: number, topology?: WatchRepositoryTopology) => {
      requests.push(topology);
      if (topology !== 'FORK') return direct;
      const request = Promise.withResolvers<WatchRepositoryPreviewDto>();
      pending.push(request);
      return request.promise;
    },
    watchRepository: async (command: WatchRepositoryCommand) => {
      commands.push(command);
      if (failWatch) throw new Error('Forking is disabled');
      return workspace;
    },
  } satisfies Partial<Window['bridge']> });
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  const click = async (selector: string) => {
    const element = requiredElement<HTMLElement>(selector, host, `Missing ${selector}`);
    element.click();
    await settle();
  };
  const confirm = () => host.querySelector<HTMLButtonElement>('.modal-preview__confirm');
  try {
    flushSync(() => root.render(<AddRepoModal initialRepositoryId={1} onAdded={() => {}} onOpenWorkspace={() => {}} onClose={() => {}} />));
    await settle();
    assert(host.querySelector<HTMLInputElement>('input[value="DIRECT"]')?.checked, 'A private repository must display the recommended direct workflow');
    assert(host.textContent?.includes('do not have write access') && host.textContent.includes('still clone and follow'), 'Direct mode must explain missing write access');
    assert(!confirm()?.disabled && commands.length === 0, 'Read-only repositories can be followed after confirmation');

    await click('input[value="FORK"]');
    assert(confirm()?.disabled && host.querySelector('.modal-routing') === null, 'Stale routing cannot be confirmed while switching');
    await click('input[value="DIRECT"]');
    assert(!confirm()?.disabled && requests.join(',') === ',FORK', 'The initial recommendation must also cache its explicit mode');
    await click('input[value="FORK"]');
    assert(pending.length === 1 && requests.join(',') === ',FORK', 'Switching back to a pending mode must reuse its request');
    pending[0].reject(new Error('Preview unavailable'));
    await settle();
    assert(host.textContent?.includes('Preview unavailable') && confirm()?.disabled, 'A failed mode preview must not allow confirming the old routing');
    await click('input[value="DIRECT"]');
    assert(!confirm()?.disabled && !host.textContent?.includes('Preview unavailable'), 'Switching back must recover from a preview failure');

    await click('input[value="FORK"]');
    assert(requests.join(',') === ',FORK,FORK', 'A failed preview must be retried');
    await click('input[value="DIRECT"]');
    pending[1].resolve(fork);
    await settle();
    assert(host.querySelector<HTMLInputElement>('input[value="DIRECT"]')?.checked && host.textContent?.includes('origin/main'), 'Late fork responses must not replace direct mode');
    for (let i = 0; i < 3; i++) {
      await click('input[value="FORK"]');
      assert(!confirm()?.disabled && host.textContent?.includes('upstream/main'), 'A completed fork preview must be reusable after switching away');
      assert(!host.textContent?.includes('Checking repository routing'), 'Cached routing must not wait on another lookup');
      await click('input[value="DIRECT"]');
    }
    await click('input[value="FORK"]');
    assert(requests.join(',') === ',FORK,FORK', 'Repeated switches must not recompute successful previews');
    assert(confirm()?.textContent === 'Create fork and watch' && commands.length === 0, 'Choosing fork mode must not create a fork');
    await click('.modal-preview__confirm');
    assert(commands[0].topology === 'FORK' && commands[0].previewFingerprint === 'fork-routing' && commands[0].allowForkCreation,
      'Fork creation must use explicit consent for the chosen routing');
    assert(requests.join(',') === ',FORK,FORK,FORK', 'Watch failures must refresh the chosen workflow rather than reuse its cached preview');
    pending[2].resolve({ ...fork, forkRequired: false, pushWritable: true, fingerprint: 'refreshed-fork-routing' });
    await settle();
    await click('input[value="DIRECT"]');
    assert(requests.at(-1) === 'DIRECT', 'A watch failure must invalidate the other cached mode too');
    await click('input[value="FORK"]');
    assert(confirm()?.textContent === 'Watch repository' && requests.at(-1) === 'DIRECT', 'Switching back must reuse refreshed routing and fork consent');
    failWatch = false;
    await click('.modal-preview__confirm');
    assert(commands[1].previewFingerprint === 'refreshed-fork-routing' && !commands[1].allowForkCreation,
      'A refreshed fork preview must submit its new fingerprint and consent');
    await click('input[value="DIRECT"]');
    await click('.modal-preview__confirm');
    assert(commands[2].topology === 'DIRECT' && commands[2].previewFingerprint === 'direct-routing' && !commands[2].allowForkCreation,
      'Direct clone must never consent to creating a fork');
    return ['workflow switching reuses pending and completed previews', 'workflow switching ignores stale previews', 'workflow errors recover without changing consent'];
  }
  finally { disposeRoot(); }
}
