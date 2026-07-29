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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Logo } from '../primitives';
import {
  ThreadList, WorkspaceNavSidebar, WorkspaceSwitcher, WorkspaceTopBar,
} from './index';
import { SIDEBAR_WIDTH_KEY } from '../shell/useSidebarWidth';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
  localStorage.clear();
});

describe('Logo', () => {
  it('applies the size + colour modifiers', () => {
    const { container } = render(<Logo initials="TR" color="pink" size="sm" />);
    expect(container.querySelector('.v3-logo')?.className).toBe('v3-logo v3-logo--sm v3-logo--pink');
    expect(container.querySelector('.v3-logo')?.getAttribute('aria-label')).toBe('TR');
    expect(container.querySelector('.v3-logo svg')).not.toBeNull();
  });
});

describe('WorkspaceNavSidebar', () => {
  it('renders the reduced nav without Automations, Email, or a user footer', () => {
    const onNavigate = vi.fn();
    const { container } = render(
      <WorkspaceNavSidebar
        activeNav="workspaces"
        onNavigate={onNavigate}
      >
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    const items = Array.from(container.querySelectorAll('.sb-nav-item')).map(b => b.textContent);
    expect(items.some(t => t?.includes('Home'))).toBe(true);
    expect(items.some(t => t?.includes('Workspaces'))).toBe(true);
    expect(items.some(t => t?.includes('Pull requests'))).toBe(true);
    expect(items.some(t => t?.includes('My work'))).toBe(false);
    expect(items.some(t => t?.includes('Reviews'))).toBe(false);
    expect(items.some(t => t?.includes('Automations'))).toBe(false);
    expect(items.some(t => t?.includes('Email'))).toBe(false);
    expect(items.some(t => t?.includes('Search'))).toBe(false);
    expect(items.findIndex(t => t?.includes('Report a bug')))
      .toBeLessThan(items.findIndex(t => t?.includes('Notifications')));
    expect(container.querySelector('.ws-user-footer')).toBeNull();
    expect(container.querySelector('.sb-nav-item.active')?.textContent).toContain('Workspaces');
    expect(screen.getByText('Pull requests').closest('.sb-nav-item')).toBeTruthy();
    const notifications = screen.getByText('Notifications').closest('.sb-nav-item');
    expect(notifications?.getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(screen.getByText('Notifications'));
    expect(onNavigate).not.toHaveBeenCalledWith('notifications');
    fireEvent.click(screen.getByText('Home'));
    expect(onNavigate).toHaveBeenCalledWith('home');
    fireEvent.click(screen.getByText('Report a bug'));
    expect(onNavigate).toHaveBeenCalledWith('bug-report');
  });

  it('flags fullscreen on the rail so the traffic-light dots show', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getFullScreenState: vi.fn().mockResolvedValue(true),
      onFullScreenChange: vi.fn().mockReturnValue(() => {}),
    };
    const { container } = render(
      <WorkspaceNavSidebar><div /></WorkspaceNavSidebar>,
    );
    await waitFor(() => expect(container.querySelector('.shell-rail.is-fullscreen')).toBeTruthy());
    // The dots + the collapse toggle live in the chrome row.
    expect(container.querySelector('.sb-traffic .dots')).toBeTruthy();
    expect(container.querySelector('.sb-traffic .sb-toggle')).toBeTruthy();
  });

  it('stays windowed (no fullscreen flag) by default', () => {
    const { container } = render(
      <WorkspaceNavSidebar><div /></WorkspaceNavSidebar>,
    );
    expect(container.querySelector('.shell-rail.is-fullscreen')).toBeNull();
    expect(container.querySelector('.shell-rail')).toBeTruthy();
  });

  it('resizes the workspace rail and restores its persisted width', () => {
    const { container, unmount } = render(
      <WorkspaceNavSidebar workspaceMode><div /></WorkspaceNavSidebar>,
    );
    const handle = screen.getByRole('separator', { name: 'Resize sidebar' });
    Object.defineProperty(handle, 'setPointerCapture', { value: vi.fn() });
    fireEvent.pointerDown(handle, { clientX: 272, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 330, pointerId: 1 });
    fireEvent.pointerUp(handle, { pointerId: 1 });

    expect((container.querySelector('.shell-rail') as HTMLElement).style.width).toBe('330px');
    expect(localStorage.getItem(SIDEBAR_WIDTH_KEY)).toBe('330');

    unmount();
    const restored = render(
      <WorkspaceNavSidebar workspaceMode><div /></WorkspaceNavSidebar>,
    );
    expect((restored.container.querySelector('.shell-rail') as HTMLElement).style.width)
      .toBe('330px');
  });

  it('shows Workspace settings as a normal workspace nav destination', () => {
    const onNavigate = vi.fn();
    render(
      <WorkspaceNavSidebar workspaceMode onNavigate={onNavigate}>
        <div />
      </WorkspaceNavSidebar>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Workspace settings' }));
    expect(onNavigate).toHaveBeenCalledWith('settings');
  });

  it('keeps the complete Home navigation above a single workspace', () => {
    const onNavigate = vi.fn();
    render(
      <WorkspaceNavSidebar workspaceMode onNavigate={onNavigate}>
        <div />
      </WorkspaceNavSidebar>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Home' }));
    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));
    expect(onNavigate.mock.calls).toEqual([['home'], ['workspaces']]);
    expect(screen.getByRole('button', { name: 'Pull requests' })).toBeTruthy();
  });

  it('folds to the chrome row when collapsed and the toggle fires onToggleCollapse', () => {
    const onToggleCollapse = vi.fn();
    const { container, rerender } = render(
      <WorkspaceNavSidebar onToggleCollapse={onToggleCollapse}>
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    // Expanded: nav items + body present.
    expect(container.querySelectorAll('.sb-nav-item').length).toBeGreaterThan(0);
    expect(screen.getByTestId('body')).toBeTruthy();

    rerender(
      <WorkspaceNavSidebar collapsed onToggleCollapse={onToggleCollapse}>
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    // Collapsed: rail flagged, workspace body gone, but the primary nav
    // icons stay reachable as a bare icon column.
    expect(container.querySelector('.shell-rail.sidebar-collapsed')).toBeTruthy();
    expect(container.querySelectorAll('.sb-nav-item').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('body')).toBeNull();
    fireEvent.click(container.querySelector('.sb-traffic .sb-toggle') as HTMLElement);
    expect(onToggleCollapse).toHaveBeenCalledOnce();
  });
});

describe('WorkspaceSwitcher', () => {
  it('shows the workspace context and fires onSwitch', () => {
    const onSwitch = vi.fn();
    const { container } = render(
      <WorkspaceSwitcher name="ByteQuay" sub="3 repos · 5 threads" onSwitch={onSwitch} />,
    );
    expect(screen.getByText('ByteQuay')).toBeTruthy();
    fireEvent.click(container.querySelector('.ws-switcher') as HTMLElement);
    expect(onSwitch).toHaveBeenCalledOnce();
  });
});

describe('ThreadList', () => {
  it('renders threads with trunk tiles + status dots and highlights the selected one', () => {
    const onOpen = vi.fn();
    const { container } = render(
      <ThreadList
        threads={[
          { id: 't1', name: 'Backend cleanup review', status: 'active' },
          { id: 't2', name: 'Fix Delta Lake timestamp', status: 'planning' },
        ]}
        selectedId="t1"
        onOpen={onOpen}
      />,
    );
    expect(container.querySelectorAll('.thread-item').length).toBe(2);
    expect(container.querySelector('.thread-item.active')?.textContent).toContain('Backend cleanup review');
    expect(container.querySelector('.v3-dot--planning')).toBeTruthy();
    fireEvent.click(screen.getByText('Fix Delta Lake timestamp'));
    expect(onOpen).toHaveBeenCalledWith('t2');
  });

  it('lists ALL the open thread\'s tasks (with dots + PR glyph) and jumps on click', () => {
    const onOpenTask = vi.fn();
    const { container } = render(
      <ThreadList
        threads={[
          { id: 't1', name: 'Backend cleanup review', status: 'active' },
          { id: 't2', name: 'Fix Delta Lake timestamp', status: 'planning' },
        ]}
        selectedId="t1"
        tasks={[
          { id: 'k1', label: 'Add cost meter', dot: 'done', pr: 'merged' },
          { id: 'k2', label: 'Drop dead config', dot: 'developing', pr: 'open' },
          { id: 'k3', label: 'Sketch the plan', dot: 'created' },
        ]}
        selectedTaskId="k2"
        onOpenTask={onOpenTask}
      />,
    );
    // ALL concurrent tasks render under the selected thread — not just one.
    const rows = container.querySelectorAll('.task-subhead');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Add cost meter');
    expect(rows[1].textContent).toContain('Drop dead config');
    // The selected task is highlighted. Each row leads with a single mark:
    // the PR glyph once a PR exists, else the pre-PR lifecycle dot.
    expect(container.querySelector('.task-subhead.active')?.textContent).toContain('Drop dead config');
    expect(container.querySelector('.task-subhead .pr-state-icon--merged')).toBeTruthy();
    expect(container.querySelector('.task-subhead .pr-state-icon--open')).toBeTruthy();
    // The PR'd rows show no dot; the pre-PR row leads with its created dot.
    expect(container.querySelector('.task-subhead .v3-dot--done')).toBeNull();
    expect(container.querySelector('.task-subhead .v3-dot--created')).toBeTruthy();
    fireEvent.click(screen.getByText('Add cost meter'));
    expect(onOpenTask).toHaveBeenCalledWith('k1');
  });

  it('hides the thread\'s tasks when the matching thread is not selected', () => {
    const { container } = render(
      <ThreadList
        threads={[{ id: 't1', name: 'A', status: 'active' }]}
        selectedId="t2"
        tasks={[{ id: 'k1', label: 'Task' }]}
      />,
    );
    expect(container.querySelector('.task-subhead')).toBeNull();
  });

  it('clicking the already-active trunk folds its task list, and clicking again unfolds it', () => {
    const onOpen = vi.fn();
    const { container } = render(
      <ThreadList
        threads={[{ id: 't1', name: 'A', status: 'active' }]}
        selectedId="t1"
        tasks={[{ id: 'k1', label: 'Task' }]}
        onOpen={onOpen}
      />,
    );
    const trunkRow = screen.getByText('A').closest('.thread-item') as HTMLElement;
    expect(container.querySelector('.task-subhead')).toBeTruthy();

    // First click on the already-open trunk folds the list — no re-navigation.
    fireEvent.click(trunkRow);
    expect(onOpen).not.toHaveBeenCalled();
    expect(container.querySelector('.task-subhead')).toBeNull();

    // Second click unfolds it again.
    fireEvent.click(trunkRow);
    expect(container.querySelector('.task-subhead')).toBeTruthy();
  });
});

describe('WorkspaceTopBar', () => {
  it('renders the workspace header + tab bar and switches tabs', () => {
    const onSelectTab = vi.fn();
    const { container } = render(
      <WorkspaceTopBar
        workspace={{ initials: 'BQ', color: 'purple', name: 'ByteQuay' }}
        repos={[{ initials: 'tr', color: 'pink' }, { initials: 'we', color: 'purple' }]}
        threadCount={5}
        activeTab="threads"
        onSelectTab={onSelectTab}
      />,
    );
    expect(container.querySelector('.ws-tab.active')?.textContent).toContain('Trunks');
    expect(screen.getByText('5')).toBeTruthy();
    fireEvent.click(screen.getByText('Memory'));
    expect(onSelectTab).toHaveBeenCalledWith('memory');
  });
});
