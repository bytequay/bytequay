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
import { afterEach, describe, expect, it, vi } from 'vitest';
import AppSidebar, { type AppPage } from './AppSidebar';
import { loadPinned, openPinned, PINNED_CHANGED, setPinned, type PinnedItem } from './home/pinned';
import { ZOOM_REFERENCE } from './githubReference';
import type { WorkspaceDto } from './types';
import type { WorkspaceTab } from './workspaces/model';

const WORKSPACE = {
  id: 'ws-1',
  name: 'trinodb/trino',
  selectedOwner: 'trinodb',
  selectedRepo: 'trino',
  monitoredOwner: 'trinodb',
  monitoredRepo: 'trino',
  localRepositoryPath: '/managed/ws-1/repository',
  topology: 'DIRECT',
} as unknown as WorkspaceDto;

function render(page: AppPage, workspace: WorkspaceDto | null, {
  settings = false,
  jiraConnected = false,
  workspaceTab = 'home',
}: { settings?: boolean; jiraConnected?: boolean; workspaceTab?: WorkspaceTab } = {}) {
  return renderToStaticMarkup(
    <AppSidebar
      page={page}
      browserRailVisible={true}
      onToggleBrowserRail={vi.fn()}
      onNavigate={vi.fn()}
      canGoBack={false}
      canGoForward={false}
      onBack={vi.fn()}
      onForward={vi.fn()}
      workspace={workspace}
      workspaceTab={workspaceTab}
      onWorkspaceTab={vi.fn()}
      jiraConnected={jiraConnected}
      settings={settings ? {
        section: 'models',
        workspaces: [WORKSPACE],
        workspaceId: 'ws-1',
        onSection: vi.fn(),
        onWorkspace: vi.fn(),
        onExit: vi.fn(),
      } : undefined}
    />,
  );
}

/** Rows are counted by their shared class, which is the point of the shape. */
function rowCount(markup: string): number {
  return markup.split('class="app-sidebar__item').length - 1;
}

function navLabels(markup: string, label: string): string[] {
  const nav = markup.split(`aria-label="${label}">`)[1]?.split('</nav>')[0] ?? '';
  return [...nav.matchAll(/<button[^>]*aria-label="([^"]+)"/g)].map(match => match[1]);
}

describe('app sidebar', () => {
  const app = render('home', null);
  const workspace = render('workspaces', WORKSPACE);
  const settings = render('settings', null, { settings: true });

  it('keeps Home, Workspaces, Circles and Recent in the global navigation in that order', () => {
    for (const markup of [app, workspace, render('jira', null, { jiraConnected: true })]) {
      expect(navLabels(markup, 'Global navigation')).toEqual(['Home', 'Workspaces', 'Circles', 'Recent']);
    }
    expect(render('workspaces', null)).toContain('aria-label="Workspaces" aria-current="page"');
    expect(render('teams', null)).toContain('aria-label="Circles" aria-current="page"');
    expect(workspace).not.toContain('aria-label="Workspaces" aria-current="page"');
    expect(workspace).toContain('aria-label="Workspace home" aria-current="page"');
    expect(workspace).not.toContain('aria-label="App home"');
    expect(workspace).not.toContain('aria-label="All workspaces"');
  });

  it('keeps global Jira discovery and combines workspace sources in Work items', () => {
    const connectedApp = render('jira', null, { jiraConnected: true });
    const connectedWorkspace = render('workspaces', WORKSPACE, { jiraConnected: true, workspaceTab: 'issues' });
    expect(navLabels(app, 'Application navigation')).toEqual(['Pull requests']);
    expect(navLabels(connectedApp, 'Application navigation')).toEqual(['Pull requests', 'Jira']);
    expect(connectedApp).toContain('aria-label="Jira" aria-current="page"');
    expect(navLabels(workspace, 'Workspace navigation')).toEqual(['Workspace home', 'Tasks', 'Pull requests', 'Work items', 'Commits', 'Memory']);
    expect(navLabels(connectedWorkspace, 'Workspace navigation')).toEqual(['Workspace home', 'Tasks', 'Pull requests', 'Work items', 'Commits',
      'Memory']);
    expect(connectedWorkspace).toContain('aria-label="Work items" aria-current="page"');
    expect(render('workspaces', WORKSPACE, { workspaceTab: 'jira' })).toContain('aria-label="Work items" aria-current="page"');
    expect(app).not.toContain('aria-label="Jira"');
    expect(workspace).not.toContain('aria-label="Jira"');
  });

  it('offers Settings from inside a workspace, not only outside one', () => {
    expect(app).toContain('aria-label="Settings"');
    // The workspace tab this replaced left no way into settings from a workspace.
    expect(workspace).toContain('aria-label="Settings"');
    expect(workspace).toContain('aria-label="Application settings"');
  });

  it('keeps its own tabs out of settings and supplies the category nav instead', () => {
    expect(settings).toContain('aria-label="Settings navigation"');
    expect(settings).not.toContain('aria-label="Global navigation"');
    expect(settings).not.toContain('aria-label="Workspace navigation"');
    expect(settings).toContain('aria-label="Back to app"');
    expect(navLabels(settings, 'Settings navigation')).toContain('Jira');
  });

  it('renders every shape through the same row, so they share size and markup', () => {
    for (const markup of [app, workspace, settings]) {
      expect(rowCount(markup)).toBeGreaterThan(2);
      // One row shape everywhere: an aria-hidden icon slot plus the collapsible label.
      expect(markup).toContain('<span aria-hidden="true">');
      expect(markup).toContain('class="app-sidebar__label"');
    }
    expect(settings).toContain('class="app-sidebar__item is-active"');
    expect(workspace).toContain('class="app-sidebar__item is-active"');
  });

  it('shares the chrome that makes the boundary draggable', () => {
    for (const markup of [app, workspace, settings]) {
      expect(markup).toContain('class="app-sidebar__resize"');
      expect(markup).toContain('aria-label="Window controls"');
    }
  });
});

describe('global pinned items', () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  const pull: PinnedItem = { key: 'trinodb/trino#42', title: 'Fix the connector', reference: '#42', target: { kind: 'pull', repo: 'trinodb/trino', number: 42 } };
  const task: PinnedItem = { key: 'task:task-1', title: 'Implement the connector', target: { kind: 'task', workspaceId: 'ws-2', taskId: 'task-1' } };

  function storage() {
    const values = new Map<string, string>();
    const dispatchEvent = vi.fn();
    const setPrAttentionPinned = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('window', {
      localStorage: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) },
      location: { hash: '#/' },
      bridge: { setPrAttentionPinned },
      dispatchEvent,
    });
    return { values, dispatchEvent, setPrAttentionPinned };
  }

  it('retains pins across remounts and workspace navigation and removes only the unpinned item', () => {
    const { dispatchEvent, setPrAttentionPinned } = storage();
    setPinned(pull, true);
    setPinned(task, true);
    setPinned(pull, true);
    expect(loadPinned()).toEqual([pull, task]);
    expect(dispatchEvent.mock.calls.at(-1)?.[0].type).toBe(PINNED_CHANGED);
    expect(setPrAttentionPinned).toHaveBeenLastCalledWith([pull.key, task.key]);
    for (const markup of [render('home', null), render('workspaces', WORKSPACE), render('workspaces', { ...WORKSPACE, id: 'ws-2' })]) {
      expect(navLabels(markup, 'Pinned navigation')).toEqual([pull.title, `Unpin ${pull.title}`, task.title, `Unpin ${task.title}`]);
      expect(markup).toContain('aria-label="Pinned items" aria-expanded="true"');
      expect(markup).toContain('aria-pressed="true"');
    }
    setPinned(pull, false);
    expect(loadPinned()).toEqual([task]);
    expect(render('home', null)).not.toContain(pull.title);
  });

  it('opens stored references and routes a task to its own workspace', () => {
    const { dispatchEvent } = storage();
    const issue: PinnedItem = { key: 'trinodb/trino#5', title: 'Connector issue', target: { kind: 'issue', repo: 'trinodb/trino', number: 5, workspaceId: 'ws-2' } };
    const jira: PinnedItem = { key: 'jira:cloud:6', title: 'Connector ticket', target: { kind: 'jira', connectionId: 'connection', cloudId: 'cloud', issueId: '6' } };
    for (const item of [pull, issue, jira]) {
      setPinned(item, true);
      const restored = loadPinned().find(candidate => candidate.key === item.key);
      expect(restored).toBeDefined();
      if (restored === undefined) throw new Error('Pin was not restored');
      openPinned(restored);
      const event = dispatchEvent.mock.calls.at(-1)?.[0] as CustomEvent;
      expect(event.type).toBe(ZOOM_REFERENCE);
      expect(event.detail).toEqual(item.target.kind === 'jira' ? { ...item.target, start: false } : item.target);
    }
    openPinned(task);
    expect(window.location.hash).toBe('#/workspaces/ws-2/tasks/agent/task-1');
    const review: PinnedItem = { key: 'review:review-1', title: 'Local review', target: { kind: 'review', workspaceId: 'ws-3', reviewId: 'review-1' } };
    setPinned(review, true);
    expect(loadPinned()).toContainEqual(review);
    openPinned(review);
    expect(window.location.hash).toBe('#/workspaces/ws-3/tasks/review/review-1');
  });

  it('ignores malformed persisted pins instead of exposing a broken navigation control', () => {
    const { values } = storage();
    values.set('bytequay.attention.pinned.v1', JSON.stringify([pull, { title: 'Broken' }, { ...task, target: { kind: 'task' } }]));
    expect(loadPinned()).toEqual([pull]);
    values.set('bytequay.attention.pinned.v1', '{');
    expect(loadPinned()).toEqual([]);
  });
});
