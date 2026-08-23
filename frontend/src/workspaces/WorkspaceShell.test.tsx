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
import WorkspaceShell, { WorkspaceHome } from './WorkspaceShell';
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

  it('shows the hero, action cards, and quick links on Workspace Home', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={{ ...workspace('FORK'), metadataState: 'FAILED', metadataError: 'Metadata refresh failed.' }}
      onWorkspaceChanged={() => {}}
      onCherryPickUpstream={() => {}}
      onReviewRequests={() => {}}
      onOpenIssues={() => {}}
    />);

    expect(html).toContain('What should we do?');
    expect(html).toContain('contributor/project');
    expect(html).toContain('upstream upstream/project linked');
    expect(html).toContain('Explore and understand code');
    expect(html).toContain('Review a pull request');
    expect(html).toContain('4 open pull requests');
    expect(html).toContain('Do anything in this workspace…');
    expect(html).toContain('Retry preparation');
  });

  it('omits the upstream note for a direct repository', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={workspace('DIRECT')}
      onWorkspaceChanged={() => {}}
      onCherryPickUpstream={() => {}}
      onReviewRequests={() => {}}
      onOpenIssues={() => {}}
    />);

    expect(html).not.toContain('linked');
    expect(html).not.toContain('Retry preparation');
  });

  it('keeps repository-scoped PRs and Issues available when reference metadata failed', () => {
    const value = { ...workspace('FORK'), metadataState: 'FAILED' as const };
    const props = {
      workspace: value,
      onSelectTab: () => {},
      onWorkspaceChanged: () => {},
      onRemoved: () => {},
    };

    const pulls = renderToStaticMarkup(<WorkspaceShell {...props} tab="prs" />);
    const issues = renderToStaticMarkup(<WorkspaceShell {...props} tab="issues" />);

    expect(pulls).toContain('upstream/project');
    expect(issues).toContain('Open issues');
    expect(issues).toContain('upstream/project');
  });
});

function workspace(topology: Workspace['topology']): Workspace {
  const linked = topology === 'FORK';
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    path: '/managed/workspace-id/repository',
    defaultBranch: 'main',
    upstreamDefaultBranch: linked ? 'main' : null,
    originRemote: 'origin',
    upstreamRemote: linked ? 'upstream' : null,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamWorkspaceId: linked ? 'upstream-workspace' : null,
    upstreamOwner: linked ? 'upstream' : null,
    upstreamRepo: linked ? 'project' : null,
    monitoredOwner: linked ? 'upstream' : 'contributor',
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
