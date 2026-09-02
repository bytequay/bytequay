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
import AppSidebar from '../AppSidebar';
import WatchedReposGrid from '../home/WatchedReposGrid';
import type { WorkspaceDto } from '../types';
import { WorkspaceCards } from './WorkspacesPage';
import WorkspaceSettings from './WorkspaceSettings';
import WorkspaceShell, { WorkspaceHome } from './WorkspaceShell';

describe('watched repository workspace UI', () => {
  it('keeps global navigation above app and workspace navigation', () => {
    const props = {
      canGoBack: false,
      canGoForward: false,
      onBack: () => {},
      onForward: () => {},
      onNavigate: () => {},
      workspaceTab: 'home' as const,
      onWorkspaceTab: () => {},
    };
    const app = renderToStaticMarkup(<AppSidebar {...props} page="home" workspace={null} />);
    const workspaceSidebar = renderToStaticMarkup(<AppSidebar
      {...props}
      page="workspaces"
      workspace={workspace('FORK')}
    />);

    expect(app).not.toContain('BYTEQUAY');
    expect(app).toMatch(/aria-label="Global navigation">.*aria-label="Home".*aria-label="Recent".*<\/nav><nav .*aria-label="Application navigation"/);
    expect(workspaceSidebar).toMatch(/aria-label="Global navigation">.*aria-label="Home".*aria-label="Recent".*<\/nav><button type="button" class="app-sidebar__ws-head"/);
    expect(app).toContain('class="app-sidebar__item is-active" aria-label="Home"');
    expect(workspaceSidebar).toContain('class="app-sidebar__item" aria-label="Home"');
    expect(workspaceSidebar).toContain('class="app-sidebar__item is-active" aria-label="Workspace home"');
    expect(workspaceSidebar).toContain('aria-label="contributor/project workspace home"');
  });

  it('shows the same durable ID and state on Home and Workspaces', () => {
    const value = workspace('FORK');
    const home = renderToStaticMarkup(<WatchedReposGrid
      workspaces={[value]}
      loading={false}
      prs={[]}
      onOpenWorkspace={() => {}}
      onWatch={() => {}}
    />);
    const list = renderToStaticMarkup(<WorkspaceCards
      workspaces={[value]}
      onOpen={() => {}}
    />);

    for (const html of [home, list]) {
      expect(html).toContain('data-workspace-id="workspace-id"');
    }
    // Nothing has synced yet, so the card reports the queued state.
    expect(list).toContain('queued');
  });

  it('shows only implemented actions on Workspace Home', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={workspace('FORK')}
      onCherryPickUpstream={() => {}}
      onReviewRequests={() => {}}
      onOpenIssues={() => {}}
    />);

    expect(html).toContain('What should we do?');
    expect(html).toContain('contributor/project');
    expect(html).toContain('upstream upstream/project linked');
    expect(html).toContain('Cherry-pick from upstream');
    expect(html).toContain('Review a pull request');
    expect(html).toContain('Fix issues and failures');
    expect(html).not.toContain('Explore and understand code');
    expect(html).not.toContain('4 open pull requests');
    expect(html).not.toContain('Do anything in this workspace…');
  });

  it('omits the upstream note for a direct repository', () => {
    const html = renderToStaticMarkup(<WorkspaceHome
      workspace={workspace('DIRECT')}
      onCherryPickUpstream={() => {}}
      onReviewRequests={() => {}}
      onOpenIssues={() => {}}
    />);

    expect(html).not.toContain('linked');
  });

  it('shows the agent task preview only in a development build', () => {
    const props = {
      workspace: workspace('FORK'),
      tab: 'tasks' as const,
      onSelectTab: () => {},
      onWorkspaceChanged: () => {},
      initialPullRequestNumber: null as number | null,
      initialIssueNumber: null as number | null,
      initialAgentTaskId: null as string | null,
      onSelectAgentTask: () => {},
      onSelectPullRequest: () => {},
      onSelectIssue: () => {},
      onRemoved: () => {},
    };

    const development = renderToStaticMarkup(<WorkspaceShell {...props} developmentBuild />);
    expect(development).toContain('Agent tasks');
    expect(development).toContain('New agent task');
    // A packaged build must not ship a fixture-backed preview.
    expect(renderToStaticMarkup(<WorkspaceShell {...props} />)).not.toContain('Agent tasks');

    // The route, not component memory, decides which task is open.
    const open = renderToStaticMarkup(
      <WorkspaceShell {...props} developmentBuild initialAgentTaskId="task-1" />);
    expect(open).toContain('Send a message to this task');
  });

  it('keeps repository-scoped PRs and Issues available before metadata is synchronized', () => {
    const value = workspace('FORK');
    const props = {
      workspace: value,
      onSelectTab: () => {},
      onWorkspaceChanged: () => {},
      initialPullRequestNumber: null as number | null,
      initialIssueNumber: null as number | null,
      initialAgentTaskId: null as string | null,
      onSelectAgentTask: () => {},
      onSelectPullRequest: () => {},
      onSelectIssue: () => {},
      onRemoved: () => {},
    };

    const pulls = renderToStaticMarkup(<WorkspaceShell {...props} tab="prs" />);
    const issues = renderToStaticMarkup(<WorkspaceShell {...props} tab="issues" />);

    expect(pulls).toContain('upstream/project');
    expect(issues).toContain('Open issues');
    expect(issues).toContain('upstream/project');
  });

  it('shows workspace fields and lets a watched repository fill a remote URL', () => {
    const html = renderToStaticMarkup(<WorkspaceSettings
      workspace={workspace('FORK')}
      onWorkspaceChanged={() => {}}
      onRemoved={() => {}}
    />);

    expect(html).toContain('Workspace settings');
    expect(html).toContain('overrides your global settings');
    expect(html).toContain('Remote sources');
    expect(html).toContain('aria-label="Select workspace repository"');
    expect(html).toContain('Select repo…');
    expect(html).toContain('value="https://github.com/contributor/project.git"');
    expect(html).toContain('Remove workspace');
    expect(html).not.toContain('extra remotes for browsing history');
    expect(html).not.toContain('Every source appears');
    expect(html).not.toContain('Stops watching this repository');
    expect(html).not.toContain('Linked upstream');
  });
});

function workspace(topology: WorkspaceDto['topology']): WorkspaceDto {
  const linked = topology === 'FORK';
  return {
    id: 'workspace-id',
    name: 'contributor/project',
    localRepositoryPath: '/managed/workspace-id/repository',
    defaultBranch: 'main',
    upstreamDefaultBranch: linked ? 'main' : null,
    selectedRepositoryId: 100,
    selectedOwner: 'contributor',
    selectedRepo: 'project',
    upstreamRepositoryId: linked ? 200 : null,
    upstreamOwner: linked ? 'upstream' : null,
    upstreamRepo: linked ? 'project' : null,
    monitoredRepositoryId: linked ? 200 : 100,
    monitoredOwner: linked ? 'upstream' : 'contributor',
    monitoredRepo: 'project',
    topology,
    lastCodeSyncAt: null,
    lastMetadataSyncAt: null,
    displayOrder: 0,
  };
}
