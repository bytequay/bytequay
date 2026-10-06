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
import { useState } from 'react';
import AddRepoModal from '../AddRepoModal';
import AgentReviewTab from '../pulls/agent-review/AgentReviewTab';
import type { PullRequestDetailDto, RepositorySearchResultDto, WatchRepositoryCommand, WatchRepositoryPreviewDto, WorkspaceDto } from '../types';
import { assert, settle } from './harness';
import '../css/base.css';
import '../css/modals.css';
import '../css/agent-review.css';

const repository = { owner: 'octocat', repo: 'project' };
const result: RepositorySearchResultDto = {
  id: 1, repository, fullName: 'octocat/project', description: null, visibility: 'public',
  fork: false, defaultBranch: 'main', upstream: null, existingWorkspaceId: null,
};
const preview: WatchRepositoryPreviewDto = {
  selectedRepositoryId: 1, selectedRepository: repository, visibility: 'public', topology: 'DIRECT',
  upstreamRepositoryId: null, upstreamRepository: null, monitoredRepositoryId: 1, monitoredRepository: repository,
  defaultBranch: 'main', developmentBase: 'origin/main', pushRemote: 'origin', pushRepository: repository,
  pushWritable: true, forkRequired: false, pullRequestBase: repository, fingerprint: 'routing', existingWorkspaceId: null,
};
const workspace: WorkspaceDto = {
  id: 'workspace', name: 'project', selectedRepositoryId: 1, selectedOwner: 'octocat', selectedRepo: 'project',
  upstreamRepositoryId: null, upstreamOwner: null, upstreamRepo: null, monitoredRepositoryId: 1,
  monitoredOwner: 'octocat', monitoredRepo: 'project', topology: 'DIRECT', defaultBranch: 'main',
  upstreamDefaultBranch: null, localRepositoryPath: '/managed/project', lastCodeSyncAt: null,
  lastMetadataSyncAt: null, displayOrder: 0,
};

export async function run(): Promise<string[]> {
  const search = Promise.withResolvers<RepositorySearchResultDto[]>();
  let pendingPreview = Promise.withResolvers<WatchRepositoryPreviewDto>();
  const pendingWatch = Promise.withResolvers<WorkspaceDto>();
  const commands: WatchRepositoryCommand[] = [];
  const added: WorkspaceDto[] = [];
  let previews = 0;
  let closes = 0;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    searchRepositories: () => search.promise,
    previewWatch: () => { previews++; return pendingPreview.promise; },
    watchRepository: (command: WatchRepositoryCommand) => { commands.push(command); return pendingWatch.promise; },
  } satisfies Partial<Window['bridge']> });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const render = () => flushSync(() => root.render(<AddRepoModal
    onAdded={value => added.push(value)}
    onOpenWorkspace={() => { throw new Error('New watches must not open an existing workspace'); }}
    onClose={() => { closes++; root.render(null); }}
  />));
  const click = async (selector: string) => {
    const element = host.querySelector<HTMLElement>(selector);
    assert(element !== null, `Missing ${selector}`);
    element.click();
    await settle();
  };
  const escape = async () => {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
  };
  try {
    render();
    await click('.modal-overlay');
    assert(closes === 0 && host.textContent?.includes('Searching…'), 'Background clicks must preserve a pending search');
    search.resolve([result]);
    for (let i = 0; i < 30 && !host.querySelector('.modal-repo-row'); i++) await settle();
    await click('.modal-repo-row');
    await click('.modal-overlay');
    assert(closes === 0 && previews === 1, 'Background clicks must not close or restart the selected preview');
    assert(host.querySelector('[role="status"]')?.textContent?.includes('octocat/project'), 'Pending preview must name the selected repository');
    pendingPreview.resolve(preview);
    await settle();
    await click('.modal-overlay');
    assert(host.querySelector('.modal-preview__confirm') !== null && commands.length === 0, 'Background clicks must preserve confirmation without starting Watch');
    await click('.modal-preview__confirm');
    await click('.modal-overlay');
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
    await escape();
    pendingPreview.resolve(preview);
    await settle();
    assert(host.querySelector('.modal__search') !== null && host.querySelector('.modal-preview') === null, 'Escape must return to search and ignore the late preview');
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
      mergedAt: null, baseRef: 'main', headRef: 'feature', body: null, labels: [], assignees: [], milestone: null,
      reviewers: [], additions: 1, deletions: 0, changedFiles: 1, approvalCount: 0, ciStatus: null,
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
    return ['background clicks preserve progress', 'explicit cancellation discards a late preview', 'watch from review confirms and refreshes'];
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
