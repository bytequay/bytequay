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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import WatchedReposGrid from '../home/WatchedReposGrid';
import { WorkspaceCards } from './WorkspacesPage';
import { WorkspaceHome } from './WorkspaceShell';
import type { Workspace } from './model';

describe('watched repository workspace UI', () => {
  it('shows the same durable ID and state on Home and Workspaces', () => {
    const value = workspace('FORK');
    const home = renderToStaticMarkup(<WatchedReposGrid
      workspaces={[value]}
      loading={false}
      events={[]}
      onOpenWorkspace={() => {}}
      onWatch={() => {}}
      onRetry={() => {}}
      onRemove={() => {}}
    />);
    const list = renderToStaticMarkup(<WorkspaceCards
      workspaces={[value]}
      onOpen={() => {}}
      onRetry={() => {}}
    />);

    for (const html of [home, list]) {
      expect(html).toContain('data-workspace-id="workspace-id"');
      expect(html).toContain('Preparing');
    }
  });

  it('shows fork routing and independent readiness on Workspace Home', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={{ ...workspace('FORK'), metadataState: 'FAILED', metadataError: 'Metadata refresh failed.' }}
      onSelectTab={() => {}}
      onWorkspaceChanged={() => {}}
    />);

    expect(html).toContain('contributor/project · origin');
    expect(html).toContain('upstream/project · upstream');
    expect(html).toContain('Followed for updates');
    expect(html).toContain('upstream/main');
    expect(html).toContain('Repository code');
    expect(html).toContain('Repository metadata');
    expect(html).toContain('Metadata refresh failed.');
  });

  it('uses origin as the development base for a direct repository', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={workspace('DIRECT')}
      onSelectTab={() => {}}
      onWorkspaceChanged={() => {}}
    />);

    expect(html).toContain('origin/main');
    expect(html).not.toContain('upstream/project · upstream');
  });
});

function workspace(topology: Workspace['topology']): Workspace {
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    path: '/managed/workspace-id/repository',
    defaultBranch: 'main',
    originRemote: 'origin',
    upstreamRemote: topology === 'FORK' ? 'upstream' : null,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamOwner: topology === 'FORK' ? 'upstream' : null,
    upstreamRepo: topology === 'FORK' ? 'project' : null,
    monitoredOwner: topology === 'FORK' ? 'upstream' : 'contributor',
    monitoredRepo: 'project',
    topology,
    codeState: 'PENDING',
    metadataState: 'PENDING',
    codeError: null,
    metadataError: null,
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
