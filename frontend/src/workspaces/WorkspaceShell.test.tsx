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
import { Children, isValidElement, type ButtonHTMLAttributes, type MouseEvent, type ReactElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { workspaceFixture } from './fixtures/workspace';
import { ZOOM_REFERENCE } from '../githubReference';
import AppSidebar from '../AppSidebar';
import type { TaskAttention } from '../agents/attention';
import WorkItemsScreen from '../workitems/WorkItemsScreen';
import WatchedReposGrid from '../home/WatchedReposGrid';
import type { WorkspaceDto } from '../types';
import { RetiredWorkspaceCards, WorkspaceCards } from './WorkspacesPage';
import UnwatchWorkspaceDialog from './UnwatchWorkspaceDialog';
import WorkspaceShell from './WorkspaceShell';

const taskAttention: TaskAttention = { items: [], tasks: [], readStartedAt: {}, activity: {}, refresh: () => {} };

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

function workspaceView(props: Parameters<typeof WorkspaceShell>[0]) {
  const shell = WorkspaceShell(props);
  const surface = shell.type as (props: Parameters<typeof WorkspaceShell>[0]) => ReactElement;
  const view = surface(shell.props);
  return (view.props as { children: ReactElement }).children;
}

/** Captures what a surface asks to zoom; the app itself listens for the same event. */
function captureZoom() {
  const dispatchEvent = vi.fn();
  vi.stubGlobal('window', { dispatchEvent });
  return () => dispatchEvent.mock.calls
    .filter(([event]) => (event as Event).type === ZOOM_REFERENCE)
    .map(([event]) => (event as CustomEvent).detail);
}

describe('watched repository workspace UI', () => {
  it('keeps global navigation above app and workspace navigation', () => {
    const props = {
      browserRailVisible: true,
      onToggleBrowserRail: () => {},
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
    expect(workspaceSidebar).toContain('aria-label="upstream/project"');
    expect(workspaceSidebar).not.toContain('contributor.png');
  });

  it('offers cached merge requests before a GitLab workspace is cloned', () => {
    const html = renderToStaticMarkup(<AppSidebar browserRailVisible onToggleBrowserRail={() => {}}
      canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}} onNavigate={() => {}}
      page="workspaces" workspaceTab="prs" onWorkspaceTab={() => {}} workspace={workspaceFixture({
        hostingServer: { provider: 'GITLAB', webBaseUrl: 'https://gitlab.example.com', apiBaseUrl: 'https://gitlab.example.com/api/v4' },
        lastCodeSyncAt: null,
      })} />);
    expect(html).toContain('aria-label="Merge requests" aria-current="page"');
    expect(html).not.toContain('aria-label="Pull requests"');
    expect(html).not.toMatch(/aria-label="Merge requests"[^>]*disabled/);
  });

  it('shows the same durable ID and state on Home and Workspaces', () => {
    const value = { ...workspace('FORK'), upstreamDefaultBranch: 'master' };
    const home = renderToStaticMarkup(<WatchedReposGrid
      workspaces={[value]}
      loading={false}
      onOpenWorkspace={() => {}}
      onWatch={() => {}}
    />);
    const list = renderToStaticMarkup(<WorkspaceCards
      workspaces={[value]}
      onOpen={() => {}}
    />);

    for (const html of [home, list]) {
      expect(html).toContain('data-workspace-id="workspace-id"');
      expect(html).toContain('aria-label="upstream/project"');
      expect(html).not.toContain('src="https://github.com/upstream.png');
      expect(html).not.toContain('contributor.png');
    }
    expect(home).toContain('class="home-repo-tile__name">upstream/project</span>');
    expect(list).toContain('class="ws-card-path">master · follows upstream/project</div>');
    expect(list).not.toContain('main · follows');
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

    expect(html).toMatch(/<a[^>]*aria-label="Open upstream\/project"[^>]*><\/a>/);
    expect(html).toContain('aria-label="Unwatch upstream/project"');
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
    const value = {
      ...workspace('DIRECT'),
      id: 'original-upstream-workspace',
      name: 'My saved Repository tasks',
      selectedOwner: 'owner',
      selectedRepo: 'repository',
      monitoredOwner: 'owner',
      monitoredRepo: 'repository',
    };
    const onOpen = vi.fn();
    const onWatch = vi.fn();
    const onDelete = vi.fn();
    const cards = RetiredWorkspaceCards({ workspaces: [value], onOpen, onWatch, onDelete });
    const card = cards.props.children[0] as ReactElement<{ children: ReactElement[] }>;
    const actions = card.props.children[3] as ReactElement<{ children: ReactElement<ButtonHTMLAttributes<HTMLButtonElement>>[] }>;
    const html = renderToStaticMarkup(cards);

    expect(html).toContain('My saved Repository tasks');
    expect(html).toContain('owner/repository · main');
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

  it('shows retired fork metadata from upstream and keeps Delete tied to the physical checkout', () => {
    const value = { ...workspace('FORK'), name: 'Saved release work', upstreamDefaultBranch: 'master' };
    const html = renderToStaticMarkup(<RetiredWorkspaceCards
      workspaces={[value]}
      onOpen={vi.fn()}
      onWatch={vi.fn()}
      onDelete={vi.fn()}
    />);

    expect(html).toContain('Saved release work');
    expect(html).toContain('title="upstream/project">upstream/project · master</div>');
    expect(html).toContain('aria-label="upstream/project"');
    expect(html).toContain('aria-label="Open upstream/project"');
    expect(html).toContain('aria-label="Watch upstream/project again"');
    expect(html).toContain('aria-label="Delete contributor/project local data"');
    expect(html).not.toContain('contributor/project · main');
    expect(html).not.toContain('contributor.png');
  });

  it('explains where unwatched repositories are kept when Retired is empty', () => {
    const html = renderToStaticMarkup(<RetiredWorkspaceCards workspaces={[]} onOpen={vi.fn()} onWatch={vi.fn()} onDelete={vi.fn()} />);

    expect(html).toContain('No retired workspaces. Repositories you unwatch will appear here with their local data kept.');
    expect(html).not.toContain('<button');
  });

  it.each([true, false])('ships agent task surfaces with development mode %s', development => {
    vi.stubEnv('NODE_ENV', development ? 'development' : 'production');
    const props = {
      workspace: workspace('FORK'),
      taskAttention,
      tab: 'tasks' as const,
      onSelectTab: () => {},
      onWorkspaceChanged: () => {},
      initialPullRequestNumber: null as number | null,
      initialIssueNumber: null as number | null,
      initialAgentTaskId: null as string | null,
      onSelectAgentTask: () => {},
      onSelectPullRequest: () => {},
      onSelectIssue: () => {},
    };

    const tasks = renderToStaticMarkup(<WorkspaceShell {...props} />);
    expect(tasks).toContain('aria-label="Task types"');
    expect(tasks).toContain('Describe a task');
    // Every agent screen carries the overview control.
    expect(tasks).toContain('aria-label="Show all tasks"');
    expect(tasks).toContain('class="agt-headbtn agt-overview-toggle"');
    expect(tasks).toContain('title="upstream/project · tasks start in this repo"');
    const home = renderToStaticMarkup(<WorkspaceShell {...props} tab="home" />);
    expect(home).toContain('<h1>upstream/project</h1>');
    expect(home).toContain('title="upstream/project · tasks start in this repo"');
    expect(home).toContain('Open tasks · 0');
    expect(home).toContain('aria-label="Describe a task"');
    expect(home).toContain('class="agt-headbtn agt-overview-toggle"');

    // The route, not component memory, decides which task is open.
    const open = renderToStaticMarkup(
      <WorkspaceShell {...props} initialAgentTaskId="task-1" />);
    expect(open).toContain('Message the agent');
    expect(open.match(/aria-label="Show all tasks"/g)).toHaveLength(1);
    expect(open).toMatch(/<header class="agt-screen-head">.*class="agt-screen-head__actions">.*aria-label="Show all tasks".*<\/header>/);
    expect(open).not.toContain('agt-overview-toggle');

    const review = renderToStaticMarkup(<WorkspaceShell {...props} initialAgentReviewId="review-1" />);
    expect(review).toContain('aria-label="Review task"');
    expect(review).toContain('Loading review task…');
    expect(review).not.toContain('Message the agent');
    expect(review.match(/aria-label="Show all tasks"/g)).toHaveLength(1);
  });

  it('keeps repository-scoped PRs and Issues available before metadata is synchronized', () => {
    const value = workspace('FORK');
    const props = {
      workspace: value,
      taskAttention,
      onSelectTab: () => {},
      onWorkspaceChanged: () => {},
      initialPullRequestNumber: null as number | null,
      initialIssueNumber: null as number | null,
      initialAgentTaskId: null as string | null,
      onSelectAgentTask: () => {},
      onSelectPullRequest: () => {},
      onSelectIssue: () => {},
    };

    const pulls = renderToStaticMarkup(<WorkspaceShell {...props} tab="prs" />);
    vi.stubGlobal('window', { location: { hash: `#/workspaces/${value.id}/issues?source=github` } });
    const issues = renderToStaticMarkup(<WorkspaceShell {...props} tab="issues" />);

    expect(pulls).toContain('upstream/project');
    expect(issues).toContain('GitHub issues');
    expect(issues).toContain('Open issues');
    expect(issues).toContain('upstream/project');
    expect(issues).toContain('aria-label="Work item sources"');
  });

  it('opens Jira inside Work items with the selected workspace', () => {
    const value = workspace('FORK');
    let jira: ReactElement<Parameters<typeof WorkItemsScreen>[0]> | undefined;
    function CaptureJira(): null {
      jira = workspaceView({
        taskAttention,
        workspace: value, tab: 'jira',
        onSelectTab: vi.fn(), onWorkspaceChanged: vi.fn(),
        initialPullRequestNumber: null, initialIssueNumber: null, initialAgentTaskId: null,
        onSelectAgentTask: vi.fn(), onSelectPullRequest: vi.fn(), onSelectIssue: vi.fn(),
      }) as ReactElement<Parameters<typeof WorkItemsScreen>[0]>;
      return null;
    }
    renderToStaticMarkup(<CaptureJira />);
    expect(jira?.type).toBe(WorkItemsScreen);
    expect(jira?.props.tab).toBe('jira');
    expect(jira?.props.workspace).toBe(value);
  });

  it('zooms a workspace Home PR over the page', () => {
    const zoomed = captureZoom();
    const onSelectPullRequest = vi.fn();
    const onSelectTab = vi.fn();
    let home: ReactElement<{ onOpenPullRequest: (number: number) => void }> | undefined;
    function CaptureHome() {
      const view = workspaceView({
        taskAttention,
        workspace: workspace('FORK'),
        tab: 'home',
        onSelectTab,
        onWorkspaceChanged: () => {},
        initialPullRequestNumber: null,
        initialIssueNumber: null,
        initialAgentTaskId: null,
        onSelectAgentTask: () => {},
        onSelectPullRequest,
        onSelectIssue: () => {},
      });
      home = find(view, props => typeof props.onOpenPullRequest === 'function');
      return view;
    }
    renderToStaticMarkup(<CaptureHome />);

    expect(home).toBeDefined();
    home?.props.onOpenPullRequest(42);
    expect(zoomed()).toEqual([{ kind: 'pull', repo: 'upstream/project', number: 42, workspaceId: 'workspace-id' }]);
    expect(onSelectPullRequest).not.toHaveBeenCalled();
    expect(onSelectTab).not.toHaveBeenCalled();
  });

  /** A screen can ride in a zoom card rather than being the stage's only child. */
  function find<T>(node: ReactNode, has: (props: Record<string, unknown>) => boolean): ReactElement<T> | undefined {
    if (!isValidElement(node)) return undefined;
    const props = node.props as Record<string, unknown> & { children?: ReactNode };
    if (has(props)) return node as ReactElement<T>;
    return Children.toArray(props.children).map(child => find<T>(child, has)).find(found => found !== undefined);
  }

  const tasksTab = (overrides: Partial<Parameters<typeof WorkspaceShell>[0]> = {}) => workspaceView({
    taskAttention,
    workspace: workspace('FORK'),
    tab: 'tasks',
    onSelectTab: () => {},
    onWorkspaceChanged: () => {},
    initialPullRequestNumber: null,
    initialIssueNumber: null,
    initialAgentTaskId: null,
    onSelectAgentTask: () => {},
    onSelectPullRequest: () => {},
    onSelectIssue: () => {},
    ...overrides,
  });

  it('opens a task PR full width over the task instead of the PR tab', () => {
    const zoomed = captureZoom();
    const onSelectPullRequest = vi.fn();
    let task: ReactElement<{ onOpenPullRequest: (number: number) => void }> | undefined;
    function CaptureTask() {
      const view = workspaceView({
        taskAttention,
        workspace: workspace('FORK'),
        tab: 'tasks',
        onSelectTab: () => {},
        onWorkspaceChanged: () => {},
        initialPullRequestNumber: null,
        initialIssueNumber: null,
        initialAgentTaskId: 'task-1',
        onSelectAgentTask: () => {},
        onSelectPullRequest,
        onSelectIssue: () => {},
      });
      task = find(view, props => typeof props.taskId === 'string');
      return view;
    }
    renderToStaticMarkup(<CaptureTask />);

    task?.props.onOpenPullRequest(42);
    expect(zoomed()).toEqual([{ kind: 'pull', repo: 'upstream/project', number: 42, workspaceId: 'workspace-id' }]);
    expect(onSelectPullRequest).not.toHaveBeenCalled();
  });

  it('gives an open agent task the whole page, not a card over the list', () => {
    function OpenTask() { return tasksTab({ initialAgentTaskId: 'task-1' }); }
    const html = renderToStaticMarkup(<OpenTask />);

    expect(html).not.toContain('zoom-scrim');
    expect(html).not.toContain('zoom-card');
    // The list the task was picked from is replaced, not left behind a card.
    expect(html).not.toContain('aria-label="Task types"');
  });

  it('opens a cherry-pick execution without leaving the Tasks tab', () => {
    const onSelectTab = vi.fn();
    let tasks: ReactElement<{
      onOpenCherryPick: (execution: { executionId: string }) => void;
      onNewItem: (type: 'review' | 'cherry' | 'ci') => void;
    }> | undefined;
    function CaptureTasks() {
      const view = tasksTab({ onSelectTab });
      tasks = find(view, props => props.onOpenCherryPick !== undefined);
      return view;
    }
    renderToStaticMarkup(<CaptureTasks />);

    expect(tasks).toBeDefined();
    tasks?.props.onOpenCherryPick({ executionId: 'execution-1' });
    expect(onSelectTab).not.toHaveBeenCalled();
    // A new cherry-pick starts from upstream commits; a review or a CI fix from a pull request.
    tasks?.props.onNewItem('cherry');
    tasks?.props.onNewItem('review');
    tasks?.props.onNewItem('ci');
    expect(onSelectTab.mock.calls).toEqual([['commits'], ['prs'], ['prs']]);
  });

  it('loads the saved target before opening fork workspace commits', () => {
    const html = renderToStaticMarkup(<WorkspaceShell
      taskAttention={taskAttention}
      workspace={{ ...workspace('FORK'), lastCodeSyncAt: '2026-09-07T00:00:00Z', upstreamDefaultBranch: 'master' }}
      tab="commits"
      onSelectTab={() => {}}
      onWorkspaceChanged={() => {}}
      initialPullRequestNumber={null}
      initialIssueNumber={null}
      initialAgentTaskId={null}
      onSelectAgentTask={() => {}}
      onSelectPullRequest={() => {}}
      onSelectIssue={() => {}}
    />);

    expect(html).toContain('Loading commits target…');
    expect(html).not.toContain('cmt-switch');
  });

});

function workspace(topology: WorkspaceDto['topology']): WorkspaceDto {
  const linked = topology === 'FORK';
  return workspaceFixture({
    topology,
    upstreamRepositoryId: linked ? 200 : null,
    upstreamOwner: linked ? 'upstream' : null,
    upstreamRepo: linked ? 'project' : null,
    monitoredRepositoryId: linked ? 200 : 100,
    monitoredOwner: linked ? 'upstream' : 'contributor',
    upstreamDefaultBranch: linked ? 'main' : null,
  });
}
