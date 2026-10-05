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
import AddRepoModal from '../AddRepoModal';
import type { RepositorySearchResultDto, WatchRepositoryCommand, WatchRepositoryPreviewDto, WorkspaceDto } from '../types';
import { assert, settle } from './harness';
import '../css/base.css';
import '../css/modals.css';

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
    return ['background clicks preserve progress', 'explicit cancellation discards a late preview'];
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
