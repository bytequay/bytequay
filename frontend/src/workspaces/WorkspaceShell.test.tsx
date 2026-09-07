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
import type { ButtonHTMLAttributes, MouseEvent, ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import AppSidebar from '../AppSidebar';
import WatchedReposGrid from '../home/WatchedReposGrid';
import type { WorkspaceDto } from '../types';
import { RetiredWorkspaceCards, WorkspaceCards } from './WorkspacesPage';
import WorkspaceSettings from './WorkspaceSettings';
import UnwatchWorkspaceDialog from './UnwatchWorkspaceDialog';
import WorkspaceShell from './WorkspaceShell';

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

  it('asks before unwatching and explains that local data is kept', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const props: Parameters<typeof UnwatchWorkspaceDialog>[0] = { workspace: workspace('FORK'), busy: false, error: null, onConfirm, onCancel };
    const view = UnwatchWorkspaceDialog(props);
    const html = renderToStaticMarkup(view);

    expect(html).toContain('>Cancel</button>');
    expect(html).toContain('>Unwatch</button>');
    expect(html).toContain('local checkout, tasks, settings, and history will be kept');
    expect(html).toContain('moves the workspace to Retired');
    expect(html).toContain('Open it from Retired to access saved tasks and files');
    expect(html).toContain('watch it again to rerun setup');
    expect(onConfirm).not.toHaveBeenCalled();
    view.props.onCancel();
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).not.toHaveBeenCalled();
    view.props.onConfirm();
    expect(onConfirm).toHaveBeenCalledOnce();

    UnwatchWorkspaceDialog({ ...props, busy: true }).props.onConfirm();
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(renderToStaticMarkup(UnwatchWorkspaceDialog({ ...props, error: 'Try again' })))
      .toContain('role="alert">Try again</p>');
  });

  it('keeps Unwatch and Delete as separate actions outside the workspace link', () => {
    const value = workspace('FORK');
    const onOpen = vi.fn();
    const onUnwatch = vi.fn();
    const onDelete = vi.fn();
    const cards = WorkspaceCards({ workspaces: [value], onOpen, onUnwatch, onDelete });
    const card = cards.props.children[0][0] as ReactElement<{ children: ReactElement[] }>;
    const actions = card.props.children[7] as ReactElement<{ children: ReactElement<ButtonHTMLAttributes<HTMLButtonElement>>[] }>;
    const html = renderToStaticMarkup(cards);

    expect(html).toMatch(/<a[^>]*aria-label="Open contributor\/project"[^>]*><\/a>/);
    expect(html).toContain('aria-label="Unwatch contributor/project"');
    expect(html).toContain('aria-label="Delete contributor/project local data"');
    expect(html).toContain('Move to Retired and keep all local data');

    actions.props.children[0].props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onUnwatch).toHaveBeenCalledWith(value);
    expect(onDelete).not.toHaveBeenCalled();
    expect(onOpen).not.toHaveBeenCalled();

    actions.props.children[1].props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onDelete).toHaveBeenCalledWith(value);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('opens retired data by its original workspace ID and keeps watch and delete actions separate', () => {
    const value = { ...workspace('DIRECT'), id: 'original-upstream-workspace', name: 'My saved Trino tasks', selectedOwner: 'trinodb', selectedRepo: 'trino' };
    const onOpen = vi.fn();
    const onWatch = vi.fn();
    const onDelete = vi.fn();
    const cards = RetiredWorkspaceCards({ workspaces: [value], onOpen, onWatch, onDelete });
    const card = cards.props.children[0] as ReactElement<{ children: ReactElement[] }>;
    const actions = card.props.children[3] as ReactElement<{ children: ReactElement<ButtonHTMLAttributes<HTMLButtonElement>>[] }>;
    const html = renderToStaticMarkup(cards);

    expect(html).toContain('My saved Trino tasks');
    expect(html).toContain('trinodb/trino · main');
    expect(html).toContain('Local data kept. Open to access saved tasks and files.');
    expect(html).not.toMatch(/queued|syncing|cloning|Retry|ws-chip|ws-spark/);
    expect(onOpen).not.toHaveBeenCalled();
    expect(onWatch).not.toHaveBeenCalled();
    expect(onDelete).not.toHaveBeenCalled();

    actions.props.children[0].props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onOpen).toHaveBeenCalledWith('original-upstream-workspace');
    expect(onWatch).not.toHaveBeenCalled();
    expect(onDelete).not.toHaveBeenCalled();

    actions.props.children[1].props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onWatch).toHaveBeenCalledWith(value);
    expect(onDelete).not.toHaveBeenCalled();

    actions.props.children[2].props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onDelete).toHaveBeenCalledWith(value);
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('explains where unwatched repositories are kept when Retired is empty', () => {
    const html = renderToStaticMarkup(<RetiredWorkspaceCards workspaces={[]} onOpen={vi.fn()} onWatch={vi.fn()} onDelete={vi.fn()} />);

    expect(html).toContain('No retired workspaces. Repositories you unwatch will appear here with their local data kept.');
    expect(html).not.toContain('<button');
  });

  it('ships the agent task surfaces in every build', () => {
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

    const tasks = renderToStaticMarkup(<WorkspaceShell {...props} />);
    expect(tasks).toContain('Agent tasks');
    expect(tasks).toContain('Describe a task');
    // Every agent screen carries the overview control.
    expect(tasks).toContain('aria-label="Show all tasks"');
    const home = renderToStaticMarkup(<WorkspaceShell {...props} tab="home" />);
    expect(home).toContain('class="agt-home__repo">upstream/project<');
    expect(home).toContain('Open tasks · 0');
    expect(home).toContain('aria-label="Describe a task"');

    // The route, not component memory, decides which task is open.
    const open = renderToStaticMarkup(
      <WorkspaceShell {...props} initialAgentTaskId="task-1" />);
    expect(open).toContain('Message the agent');
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
    expect(html).toContain('Unwatch workspace');
    expect(html).toContain('Delete workspace local data');
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
