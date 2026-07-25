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
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkspaceNavShell } from './WorkspaceNavShell';
import { logoColorFor, monogram, threadStatusDot, useWorkspaceNav } from './useWorkspaceNav';
import type { ThreadDto, WorkspaceApiRequest } from '../types';
import { SIDEBAR_WIDTH_KEY } from '../ui/shell/useSidebarWidth';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
  Reflect.deleteProperty(window, 'bridge');
});

function mockBridge(over: Record<string, unknown> = {}) {
  const bridge = {
    listWorkspaces: vi.fn().mockResolvedValue([
      {
        id: 'bq', name: 'ByteQuay', color: '#8b5cf6', isScratch: false,
        repos: ['web', 'trino', 'docs'], activeThreadCount: 5, tasksInFlight: 3,
        repository: { owner: 'acme', repo: 'widget', fullName: 'acme/widget' },
      },
      { id: 'tr', name: 'Trino', color: '#0d9488', isScratch: false, repos: ['trino', 'web'], activeThreadCount: 3, tasksInFlight: 1 },
    ]),
    listTasks: vi.fn().mockResolvedValue([
      { id: 't1', title: 'Backend cleanup review', status: 'RUNNING', workspaceId: 'bq', activeTask: { workingDir: '/x/web' } },
      { id: 't2', title: 'Fix Delta Lake timestamp', status: 'AWAITING_REVIEW', workspaceId: 'bq', activeTask: { workingDir: '/x/trino' } },
    ]),
    // The no-workspace rail body is the recently-visited list.
    getFootprints: vi.fn().mockResolvedValue({
      date: '2026-07-03',
      stops: [{
        surfaceType: 'PR', surfaceId: 'org/web#42', title: 'org/web #42',
        context: 'org/web', latestVisitAt: '2026-07-03T10:00:00Z', visitCount: 2,
      }],
      totalStops: 1,
    }),
    fetchPrs: vi.fn().mockResolvedValue([]),
    workspaceApi: vi.fn().mockResolvedValue(null),
    ...over,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

function thread(id: string, title: string, workspaceId: string): ThreadDto {
  return {
    id, title, workspaceId, status: 'IDLE', kind: 'CLI_AGENT', flow: 'build', provider: 'codex',
    agentSessionId: null, model: 'default', costUsdMilli: 0, tokensIn: 0, tokensOut: 0,
    createdAt: '', updatedAt: '', endedAt: null, errorMessage: null, workModel: null,
    parallelSlots: 1,
  };
}

describe('useWorkspaceNav helpers', () => {
  it('logoColorFor is deterministic', () => {
    expect(logoColorFor('web')).toBe(logoColorFor('web'));
  });
  it('monogram lowercases the first two alphanumerics', () => {
    expect(monogram('ByteQuay')).toBe('by');
    expect(monogram('trino')).toBe('tr');
  });
  it('maps thread status to the right dot', () => {
    expect(threadStatusDot('RUNNING')).toBe('active');
    expect(threadStatusDot('AWAITING_REVIEW')).toBe('planning');
    expect(threadStatusDot('COMPLETED')).toBe('done');
    expect(threadStatusDot('IDLE')).toBe('sleep');
  });

  it('keeps workspace data together when an earlier refresh finishes after a switch', async () => {
    const oldThreads = deferred<Awaited<ReturnType<typeof window.bridge.listTasks>>>();
    const oldOverview = deferred<Record<string, unknown>>();
    const newThreads = deferred<Awaited<ReturnType<typeof window.bridge.listTasks>>>();
    const newOverview = deferred<Record<string, unknown>>();
    const listTasks = vi.fn()
      .mockResolvedValueOnce([thread('tr-thread', 'Trino trunk', 'tr')])
      .mockReturnValueOnce(oldThreads.promise)
      .mockReturnValueOnce(newThreads.promise);
    const workspaceApi = vi.fn()
      .mockResolvedValueOnce({ sidebarCounts: { pullRequests: 47 } })
      .mockReturnValueOnce(oldOverview.promise)
      .mockReturnValueOnce(newOverview.promise);
    mockBridge({ listTasks, workspaceApi });

    const { result, rerender } = renderHook(
      ({ workspaceId }) => useWorkspaceNav(workspaceId),
      { initialProps: { workspaceId: 'tr' } },
    );
    await waitFor(() => expect(result.current.rawThreads[0]?.title).toBe('Trino trunk'));
    expect(result.current.activeWorkspace?.name).toBe('Trino');

    act(() => result.current.refresh());
    await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(2));
    rerender({ workspaceId: 'bq' });
    await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(3));
    expect(result.current.activeWorkspace?.name).toBe('ByteQuay');
    expect(result.current.rawThreads).toEqual([]);
    expect(result.current.overview).toBeNull();

    await act(async () => {
      newThreads.resolve([thread('bq-thread', 'ByteQuay trunk', 'bq')]);
      newOverview.resolve({ sidebarCounts: { pullRequests: 3 } });
      await newThreads.promise;
    });
    await waitFor(() => expect(result.current.rawThreads[0]?.title).toBe('ByteQuay trunk'));
    expect(result.current.overview?.sidebarCounts.pullRequests).toBe(3);

    await act(async () => {
      oldThreads.resolve([thread('late-tr-thread', 'Late Trino trunk', 'tr')]);
      oldOverview.resolve({ sidebarCounts: { pullRequests: 47 } });
      await oldThreads.promise;
    });
    expect(result.current.rawThreads[0]?.title).toBe('ByteQuay trunk');
    expect(result.current.overview?.sidebarCounts.pullRequests).toBe(3);
  });
});

describe('WorkspaceNavShell', () => {
  it('shows the recently-visited list when no workspace is active', async () => {
    mockBridge();
    const onResumeVisit = vi.fn();
    render(<WorkspaceNavShell activeWorkspaceId={null} onResumeVisit={onResumeVisit} />);
    // The footprint stop shows once, as a Continue row. (The Today "Working on"
    // line is PR-derived now, and this mock has no PRs.)
    const rows = await screen.findAllByText('org/web #42');
    expect(rows.length).toBe(1);
    fireEvent.click(rows[0]);
    expect(onResumeVisit).toHaveBeenCalledWith(expect.objectContaining({ surfaceId: 'org/web#42' }));
  });

  it('shows the locked switcher + expandable trunk tree on a selected trunk', async () => {
    const bridge = mockBridge();
    const onOpenThread = vi.fn();
    const { container } = render(
      <WorkspaceNavShell activeWorkspaceId="bq" selectedThreadId="t1" onOpenThread={onOpenThread} />,
    );
    await waitFor(() => expect(bridge.listTasks).toHaveBeenCalledWith({ workspaceId: 'bq' }));
    // Switcher shows the active workspace.
    expect(container.querySelector('.workspace-page-switcher')?.textContent).toContain('ByteQuay');
    expect(container.querySelector('.workspace-page-switcher__tile img')?.getAttribute('src'))
      .toBe('https://github.com/acme.png?size=56');
    // Trunk rows render with the selected one highlighted.
    expect(await screen.findByText('Backend cleanup review')).toBeTruthy();
    expect(container.querySelector('.trunk-page-v2-nav__trunk > button.is-active')?.textContent)
      .toContain('Backend cleanup review');
    expect(container.querySelectorAll('.trunk-page-v2-nav__directory .tree-folder')).toHaveLength(2);
    expect(container.querySelector('.trunk-page-v2-nav__trunk-icon svg')).toBeTruthy();
    fireEvent.click(screen.getByText('Fix Delta Lake timestamp'));
    expect(onOpenThread).toHaveBeenCalledWith('t2');
  });

  it('uses the Home rail shell with trunk-first content for an active workspace', async () => {
    mockBridge();
    const onSwitchWorkspace = vi.fn();
    const { container } = render(
      <WorkspaceNavShell activeWorkspaceId="bq" onSwitchWorkspace={onSwitchWorkspace} />,
    );
    expect(container.querySelector('.shell.shell-rail.workspace-mode')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Home' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Workspaces' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Pull requests' })).toBeTruthy();
    await waitFor(() => expect(container.querySelector('.workspace-page-switcher')).toBeTruthy());

    const text = container.textContent ?? '';
    expect(text.indexOf('TRUNKS')).toBeLessThan(text.indexOf('WORKSPACE'));
    expect(screen.queryByText('WORK')).toBeNull();
    expect(screen.queryByText('REPO')).toBeNull();
    expect(screen.queryByText('BRAIN')).toBeNull();
    expect(screen.queryByText('PINNED TRUNKS')).toBeNull();
    expect(screen.getByRole('button', { name: 'WORKSPACE' }).getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(container.querySelector('.workspace-page-switcher') as HTMLElement);
    expect(onSwitchWorkspace).toHaveBeenCalledOnce();
  });

  it('shows the linked upstream directly below the switcher', async () => {
    const onOpenRelation = vi.fn();
    mockBridge({
      workspaceApi: vi.fn(async (request: WorkspaceApiRequest) => request.path.endsWith('/relation')
        ? {
            workspaceId: 'bq', upstreamWorkspaceId: 'tr', upstreamWorkspaceName: 'Trino',
            upstreamRepoFullName: 'trinodb/trino', commitsEnabled: true, tagsEnabled: true,
            branchesEnabled: false, issuesPullRequestsEnabled: false, lastFetchedAt: null as string | null,
            autoFetchIntervalMinutes: 30, indexedCommitCount: 10,
          }
        : null),
    });
    render(
      <WorkspaceNavShell activeWorkspaceId="bq" onOpenRelation={onOpenRelation} />,
    );

    const row = await screen.findByTitle('Manage read-only upstream trinodb/trino');
    expect(row.textContent).toContain('fork of Trino');
    expect(row.textContent).toContain('reads');
    fireEvent.click(row);
    expect(onOpenRelation).toHaveBeenCalledOnce();
  });

  it('shares the saved rail width with Home and lets the workspace rail be dragged', () => {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, '320');
    mockBridge();
    const { container } = render(<WorkspaceNavShell activeWorkspaceId="bq" />);
    const rail = container.querySelector('.shell.shell-rail.workspace-mode') as HTMLElement;
    expect(rail.style.width).toBe('320px');

    const handle = screen.getByRole('separator', { name: 'Resize sidebar' });
    Object.defineProperty(handle, 'setPointerCapture', { value: vi.fn() });
    fireEvent.pointerDown(handle, { clientX: 0, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 40, pointerId: 1 });
    fireEvent.pointerUp(handle, { pointerId: 1 });

    expect(rail.style.width).toBe('360px');
    expect(localStorage.getItem(SIDEBAR_WIDTH_KEY)).toBe('360');
  });

  it('remembers whether the workspace navigation group is folded', async () => {
    mockBridge();
    const { unmount } = render(<WorkspaceNavShell activeWorkspaceId="bq" />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'WORKSPACE' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'WORKSPACE' }));
    expect(screen.getByRole('button', { name: 'WORKSPACE' }).getAttribute('aria-expanded')).toBe('true');
    expect(document.querySelector('.trunk-page-v2-nav__workspace-items')?.textContent)
      .toContain('Pull requests');

    unmount();
    render(<WorkspaceNavShell activeWorkspaceId="bq" />);
    expect(screen.getByRole('button', { name: 'WORKSPACE' }).getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: 'WORKSPACE' }));
    expect(document.querySelector('.trunk-page-v2-nav__workspace-items')).toBeNull();
  });

  it('opens a nested task with its owning trunk from a workspace page', async () => {
    mockBridge({
      listTasks: vi.fn().mockResolvedValue([
        { id: 't1', title: 'Backend cleanup review', status: 'IDLE', workspaceId: 'bq', flow: 'build' },
      ]),
      listTasksForThread: vi.fn().mockResolvedValue([
        { id: 'task-1', threadId: 't1', name: 'Remove dead flag', branchName: 'task/remove-dead-flag', status: 'RUNNING', prNumber: null },
      ]),
    });
    const onOpenTask = vi.fn();
    render(<WorkspaceNavShell activeWorkspaceId="bq" onOpenTask={onOpenTask} />);

    const trunk = await screen.findByRole('button', { name: /Backend cleanup review/ });
    await waitFor(() => expect(trunk.getAttribute('aria-expanded')).toBe('false'));
    fireEvent.click(trunk);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove dead flag' }));
    expect(onOpenTask).toHaveBeenCalledWith('t1', 'task-1');
  });

  it('renders live workspace counts with selected tasks in the locked workspace group', async () => {
    mockBridge({
      listTasks: vi.fn().mockResolvedValue([
        {
          id: 't1', title: 'Workspace implementation', status: 'RUNNING',
          workspaceId: 'bq', flow: 'build',
        },
        {
          id: 't-review', title: 'Review PR #42', status: 'IDLE',
          workspaceId: 'bq', flow: 'review',
        },
      ]),
      workspaceApi: vi.fn().mockResolvedValue({
        repository: { owner: 'acme', repo: 'widget', fullName: 'acme/widget' },
        sidebarCounts: {
          todayNeedsYou: 2,
          trunks: 3,
          pullRequests: 4,
          issues: 5,
          backlog: 6,
          branches: 7,
          sessions: 1,
          notifications: 8,
        },
        pinnedTrunks: [],
      }),
    });

    render(
      <WorkspaceNavShell
        activeWorkspaceId="bq"
        selectedThreadId="t1"
        tasks={[{ id: 'task-1', label: 'Implement parser', dot: 'active' }]}
      />,
    );

    expect(await screen.findByText('Implement parser')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'WORKSPACE' }));
    const workspaceItems = document.querySelector('.trunk-page-v2-nav__workspace-items');
    const workspaceRows = Array.from(workspaceItems?.querySelectorAll('.workspace-page-row') ?? []);
    expect(workspaceRows.find(row => row.textContent?.includes('Pull requests'))?.textContent).toContain('4');
    expect(workspaceRows.find(row => row.textContent?.includes('Issues'))?.textContent).toContain('5');
    expect((screen.getByRole('button', { name: /Backlog/ }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: /Sessions/ }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Memory' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText('Review PR #42')).toBeNull();
    expect(screen.getByText('WORKSPACE')).toBeTruthy();
  });

  it('shows every development trunk and persists its expansion state', async () => {
    mockBridge({
      listTasks: vi.fn().mockResolvedValue([
        { id: 't1', title: 'First trunk', status: 'IDLE', workspaceId: 'bq', flow: 'build', taskCount: 1 },
        { id: 't2', title: 'Second trunk', status: 'IDLE', workspaceId: 'bq', flow: 'build' },
        { id: 't3', title: 'Third trunk', status: 'IDLE', workspaceId: 'bq', flow: 'build' },
        { id: 't4', title: 'Fourth trunk', status: 'IDLE', workspaceId: 'bq', flow: 'build' },
        { id: 't5', title: 'Fifth trunk', status: 'IDLE', workspaceId: 'bq', flow: 'build' },
        { id: 'review', title: 'Review PR #42', status: 'IDLE', workspaceId: 'bq', flow: 'review' },
      ]),
    });

    render(<WorkspaceNavShell activeWorkspaceId="bq" selectedThreadId="t1" />);

    expect(await screen.findByText('Fourth trunk')).toBeTruthy();
    expect(screen.getByText('Fifth trunk')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '2 more…' })).toBeNull();

    const first = screen.getByRole('button', { name: /First trunk/ });
    expect(first.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(first);
    expect(first.getAttribute('aria-expanded')).toBe('false');
    expect(JSON.parse(window.localStorage.getItem('byq.trunkExpanded.v1') ?? '{}').b)
      .toMatchObject({ 'First trunk': false });
  });
});
