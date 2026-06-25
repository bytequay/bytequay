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
  ThreadList, WorkspaceList, WorkspaceNavSidebar, WorkspaceSwitcher, WorkspaceTopBar,
} from './index';

afterEach(() => { cleanup(); Reflect.deleteProperty(window, 'bridge'); });

describe('Logo', () => {
  it('applies the size + colour modifiers', () => {
    const { container } = render(<Logo initials="TR" color="pink" size="sm" />);
    expect(container.querySelector('.v3-logo')?.className).toBe('v3-logo v3-logo--sm v3-logo--pink');
    expect(container.querySelector('.v3-logo')?.textContent).toBe('TR');
  });
});

describe('WorkspaceNavSidebar', () => {
  it('renders the four nav items (no Search) and fires onNavigate', () => {
    const onNavigate = vi.fn();
    const { container } = render(
      <WorkspaceNavSidebar
        activeNav="workspaces"
        onNavigate={onNavigate}
        footer={{ initials: 'CJ', name: 'chenjian2664' }}
      >
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    const items = Array.from(container.querySelectorAll('.sb-nav-item')).map(b => b.textContent);
    expect(items.some(t => t?.includes('Home'))).toBe(true);
    expect(items.some(t => t?.includes('Workspaces'))).toBe(true);
    expect(items.some(t => t?.includes('My work'))).toBe(true);
    expect(items.some(t => t?.includes('Automations'))).toBe(true);
    expect(items.some(t => t?.includes('Search'))).toBe(false);
    expect(container.querySelector('.sb-nav-item.active')?.textContent).toContain('Workspaces');
    fireEvent.click(screen.getByText('Home'));
    expect(onNavigate).toHaveBeenCalledWith('home');
  });

  it('shows the back hint on Workspaces only when backHint is set', () => {
    const { rerender, queryByText } = render(
      <WorkspaceNavSidebar footer={{ initials: 'CJ', name: 'x' }}><div /></WorkspaceNavSidebar>,
    );
    expect(queryByText('← back')).toBeNull();
    rerender(
      <WorkspaceNavSidebar backHint footer={{ initials: 'CJ', name: 'x' }}><div /></WorkspaceNavSidebar>,
    );
    expect(queryByText('← back')).toBeTruthy();
  });

  it('flags fullscreen on the rail so the traffic-light dots show', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      getFullScreenState: vi.fn().mockResolvedValue(true),
      onFullScreenChange: vi.fn().mockReturnValue(() => {}),
    };
    const { container } = render(
      <WorkspaceNavSidebar footer={{ initials: 'CJ', name: 'x' }}><div /></WorkspaceNavSidebar>,
    );
    await waitFor(() => expect(container.querySelector('.shell-rail.is-fullscreen')).toBeTruthy());
    // The dots + the collapse toggle live in the chrome row.
    expect(container.querySelector('.sb-traffic .dots')).toBeTruthy();
    expect(container.querySelector('.sb-traffic .sb-toggle')).toBeTruthy();
  });

  it('stays windowed (no fullscreen flag) by default', () => {
    const { container } = render(
      <WorkspaceNavSidebar footer={{ initials: 'CJ', name: 'x' }}><div /></WorkspaceNavSidebar>,
    );
    expect(container.querySelector('.shell-rail.is-fullscreen')).toBeNull();
    expect(container.querySelector('.shell-rail')).toBeTruthy();
  });

  it('folds to the chrome row when collapsed and the toggle fires onToggleCollapse', () => {
    const onToggleCollapse = vi.fn();
    const { container, rerender } = render(
      <WorkspaceNavSidebar footer={{ initials: 'CJ', name: 'x' }} onToggleCollapse={onToggleCollapse}>
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    // Expanded: nav items + body present.
    expect(container.querySelectorAll('.sb-nav-item').length).toBeGreaterThan(0);
    expect(screen.getByTestId('body')).toBeTruthy();

    rerender(
      <WorkspaceNavSidebar collapsed footer={{ initials: 'CJ', name: 'x' }} onToggleCollapse={onToggleCollapse}>
        <div data-testid="body" />
      </WorkspaceNavSidebar>,
    );
    // Collapsed: rail flagged, nav body gone, only the toggle remains.
    expect(container.querySelector('.shell-rail.sidebar-collapsed')).toBeTruthy();
    expect(container.querySelectorAll('.sb-nav-item').length).toBe(0);
    expect(screen.queryByTestId('body')).toBeNull();
    fireEvent.click(container.querySelector('.sb-traffic .sb-toggle') as HTMLElement);
    expect(onToggleCollapse).toHaveBeenCalledOnce();
  });
});

describe('WorkspaceList', () => {
  it('renders workspace rows and opens one', () => {
    const onOpen = vi.fn();
    render(
      <WorkspaceList
        workspaces={[
          { id: 'bq', initials: 'BQ', color: 'purple', name: 'ByteQuay', sub: '3 repos · 5 open threads', count: 5 },
          { id: 'tr', initials: 'TR', color: 'teal', name: 'Trino', sub: '2 repos · 3 open threads', count: 3 },
        ]}
        onOpen={onOpen}
      />,
    );
    expect(screen.getByText('ByteQuay')).toBeTruthy();
    expect(screen.getByText('3 repos · 5 open threads')).toBeTruthy();
    fireEvent.click(screen.getByText('Trino'));
    expect(onOpen).toHaveBeenCalledWith('tr');
  });
});

describe('WorkspaceSwitcher', () => {
  it('shows the workspace context and fires onSwitch', () => {
    const onSwitch = vi.fn();
    const { container } = render(
      <WorkspaceSwitcher initials="BQ" color="purple" name="ByteQuay" sub="3 repos · 5 threads" onSwitch={onSwitch} />,
    );
    expect(screen.getByText('ByteQuay')).toBeTruthy();
    fireEvent.click(container.querySelector('.ws-switcher') as HTMLElement);
    expect(onSwitch).toHaveBeenCalledOnce();
  });
});

describe('ThreadList', () => {
  it('renders threads with repo logos + status dots and highlights the selected one', () => {
    const onOpen = vi.fn();
    const { container } = render(
      <ThreadList
        threads={[
          { id: 't1', initials: 'we', color: 'purple', name: 'Backend cleanup review', status: 'active' },
          { id: 't2', initials: 'tr', color: 'pink', name: 'Fix Delta Lake timestamp', status: 'planning' },
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

  it('nests the open thread\'s stages and jumps on click', () => {
    const onOpenStage = vi.fn();
    const { container } = render(
      <ThreadList
        threads={[
          { id: 't1', initials: 'we', color: 'purple', name: 'Backend cleanup review', status: 'active' },
          { id: 't2', initials: 'tr', color: 'pink', name: 'Fix Delta Lake timestamp', status: 'planning' },
        ]}
        selectedId="t1"
        stages={[
          { id: 's1', label: 'Plan', dot: 'done' },
          { id: 's2', label: 'Dev', dot: 'active' },
          { id: 's3', label: 'CI Fix' },
        ]}
        selectedStageId="s2"
        onOpenStage={onOpenStage}
      />,
    );
    // Stages render under the selected thread only.
    expect(container.querySelectorAll('.stage-subitem').length).toBe(3);
    expect(container.querySelector('.stage-subitem.active')?.textContent).toContain('Dev');
    fireEvent.click(screen.getByText('CI Fix'));
    expect(onOpenStage).toHaveBeenCalledWith('s3');
  });

  it('hides stages when the matching thread is not selected', () => {
    const { container } = render(
      <ThreadList
        threads={[{ id: 't1', initials: 'we', color: 'purple', name: 'A', status: 'active' }]}
        selectedId="t2"
        stages={[{ id: 's1', label: 'Plan' }]}
      />,
    );
    expect(container.querySelector('.stage-subitem')).toBeNull();
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
    expect(container.querySelector('.ws-tab.active')?.textContent).toContain('Threads');
    expect(screen.getByText('5')).toBeTruthy();
    fireEvent.click(screen.getByText('Memory'));
    expect(onSelectTab).toHaveBeenCalledWith('memory');
  });
});
