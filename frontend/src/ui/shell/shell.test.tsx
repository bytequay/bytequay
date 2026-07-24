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
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ClosedFolder, Composer, Main, Shell, Sidebar, SidebarNav, StageChips, StageItem,
  TaskItem, ThreadItem, TopBar, TopBarButton, useSidebarCollapsed,
} from './index';
import { SIDEBAR_WIDTH_KEY } from './useSidebarWidth';

afterEach(cleanup);

function sampleSidebar(collapsed = false) {
  return (
    <Shell collapsed={collapsed}>
      <Sidebar
        footer={{ initials: 'CJ', name: 'chenjian2664' }}
        activeNav="home"
        closed={<ClosedFolder count={14} />}
      >
        <ThreadItem label="Backend cleanup review" active expandable expanded>
          <TaskItem label="#142 · Add cost-meter card" expanded>
            <StageItem label="Plan" icon="📋" status="done" />
            <StageItem label="Dev" icon="🛠" status="active" current />
            <StageItem label="Cleanup" icon="🧹" status="future" future />
          </TaskItem>
        </ThreadItem>
        <ThreadItem label="Latest issues" />
      </Sidebar>
      <Main topBar={<TopBar><TopBarButton icon="▶">Run</TopBarButton></TopBar>}>
        <div className="body" />
      </Main>
    </Shell>
  );
}

describe('Shell', () => {
  beforeEach(() => localStorage.clear());

  it('toggles the collapsed modifier class', () => {
    const { container, rerender } = render(sampleSidebar(false));
    expect(container.querySelector('.shell')?.className).toBe('shell');
    rerender(sampleSidebar(true));
    expect(container.querySelector('.shell')?.className).toBe('shell sidebar-collapsed');
  });

  it('matches snapshot in default and collapsed states', () => {
    const { container: dflt } = render(sampleSidebar(false));
    expect(dflt).toMatchSnapshot('default');
    cleanup();
    const { container: collapsed } = render(sampleSidebar(true));
    expect(collapsed).toMatchSnapshot('collapsed');
  });

  it('restores and updates the shared left-navigation width', () => {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, '330');
    const { container } = render(
      <Shell>
        <aside />
        <main />
      </Shell>,
    );
    expect((container.querySelector('.shell') as HTMLElement).style.gridTemplateColumns)
      .toBe('330px minmax(0, 1fr)');

    const handle = screen.getByRole('separator', { name: 'Resize the sidebar' });
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 360 });
    fireEvent.mouseUp(window);

    expect((container.querySelector('.shell') as HTMLElement).style.gridTemplateColumns)
      .toBe('360px minmax(0, 1fr)');
    expect(localStorage.getItem(SIDEBAR_WIDTH_KEY)).toBe('360');
  });

  it('resets the former default width while preserving a custom legacy width', () => {
    localStorage.setItem('bq.rail-width', '250');
    const { container, unmount } = render(<Shell><aside /><main /></Shell>);
    expect((container.querySelector('.shell') as HTMLElement).style.gridTemplateColumns)
      .toBe('272px minmax(0, 1fr)');

    unmount();
    localStorage.clear();
    localStorage.setItem('bq.rail-width', '330');
    const migrated = render(<Shell><aside /><main /></Shell>);
    expect((migrated.container.querySelector('.shell') as HTMLElement).style.gridTemplateColumns)
      .toBe('330px minmax(0, 1fr)');
    expect(localStorage.getItem(SIDEBAR_WIDTH_KEY)).toBe('330');
  });
});

describe('Sidebar composition', () => {
  it('renders nav, threads, closed folder, footer, and the toggle bar', () => {
    const { container } = render(sampleSidebar());
    expect(container.querySelector('.sb-traffic')).toBeTruthy();
    expect(container.querySelector('.sb-toggle-row')).toBeTruthy();
    expect(container.querySelector('.sb-nav')).toBeTruthy();
    expect(container.querySelector('.sb-section')).toBeTruthy();
    expect(container.querySelector('.closed-folder')).toBeTruthy();
    expect(container.querySelector('.sb-footer')).toBeTruthy();
    expect(screen.getByText('chenjian2664')).toBeTruthy();
  });
});

describe('SidebarNav', () => {
  it('marks the active item and fires onNavigate', () => {
    const onNavigate = vi.fn();
    const { container } = render(<SidebarNav activeKey="my-work" onNavigate={onNavigate} />);
    const active = container.querySelector('.sb-nav-item.active');
    expect(active?.textContent).toContain('My work');
    fireEvent.click(screen.getByText('Automations'));
    expect(onNavigate).toHaveBeenCalledWith('automations');
  });
});

describe('SidebarTree', () => {
  it('ThreadItem reveals its children only when expanded', () => {
    const { queryByText, rerender } = render(
      <ThreadItem label="T" expandable expanded={false}><div>child</div></ThreadItem>,
    );
    expect(queryByText('child')).toBeNull();
    rerender(<ThreadItem label="T" expandable expanded><div>child</div></ThreadItem>);
    expect(queryByText('child')).toBeTruthy();
  });

  it('StageItem renders a status dot and dims a future stage', () => {
    const { container } = render(<StageItem label="Cleanup" icon="🧹" status="future" future />);
    expect(container.querySelector('.session-item.future')).toBeTruthy();
    expect(container.querySelector('.v3-dot--future')).toBeTruthy();
  });

  it('TaskItem shows a status dot when collapsed, not when expanded', () => {
    const { container, rerender } = render(<TaskItem label="#1" status="active" />);
    expect(container.querySelector('.v3-dot')).toBeTruthy();
    rerender(<TaskItem label="#1" status="active" expanded><span>s</span></TaskItem>);
    expect(container.querySelector('.v3-dot')).toBeNull();
  });
});

describe('ClosedFolder', () => {
  it('shows the count and toggles its children', () => {
    const onToggle = vi.fn();
    const { rerender, queryByText } = render(
      <ClosedFolder count={3} onToggle={onToggle}><div>row</div></ClosedFolder>,
    );
    expect(screen.getByText('3')).toBeTruthy();
    expect(queryByText('row')).toBeNull();
    fireEvent.click(screen.getByRole('button'));
    expect(onToggle).toHaveBeenCalledOnce();
    rerender(<ClosedFolder count={3} expanded><div>row</div></ClosedFolder>);
    expect(queryByText('row')).toBeTruthy();
  });
});

describe('TopBar parts', () => {
  it('StageChips marks the current chip', () => {
    const { container } = render(
      <StageChips chips={[
        { label: 'Plan', dot: 'done' },
        { label: 'Dev', dot: 'active', current: true },
      ]}
      />,
    );
    const current = container.querySelector('.chip.current');
    expect(current?.textContent).toContain('Dev');
    expect(container.querySelectorAll('.chip').length).toBe(2);
  });

  it('TopBarButton applies the submit variant', () => {
    const onClick = vi.fn();
    render(<TopBarButton variant="submit" onClick={onClick}>Submit review</TopBarButton>);
    const btn = screen.getByRole('button', { name: /Submit review/ });
    expect(btn.className).toBe('btn submit');
    fireEvent.click(btn);
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('Composer', () => {
  it('Enter submits, Shift+Enter does not', () => {
    const onSubmit = vi.fn();
    render(<Composer value="hello" onChange={() => {}} onSubmit={onSubmit} />);
    const ta = screen.getByRole('textbox');
    fireEvent.keyDown(ta, { key: 'Enter', shiftKey: true });
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.keyDown(ta, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledOnce();
  });

  it('blocks send when empty or busy', () => {
    const onSubmit = vi.fn();
    const { rerender } = render(<Composer value="" onChange={() => {}} onSubmit={onSubmit} />);
    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
    rerender(<Composer value="hi" onChange={() => {}} onSubmit={onSubmit} busy />);
    const send = screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    expect(send.className).toBe('send spinning');
  });

  it('renders the mode pill slot', () => {
    render(
      <Composer value="" onChange={() => {}} onSubmit={() => {}} modePill={<span>Dev → claude</span>} />,
    );
    expect(screen.getByText('Dev → claude')).toBeTruthy();
  });
});

describe('useSidebarCollapsed', () => {
  beforeEach(() => localStorage.clear());

  function Harness() {
    const { collapsed, toggle } = useSidebarCollapsed();
    return <button type="button" onClick={toggle}>{collapsed ? 'collapsed' : 'expanded'}</button>;
  }

  it('defaults to expanded and persists a toggle to localStorage', () => {
    render(<Harness />);
    expect(screen.getByRole('button').textContent).toBe('expanded');
    act(() => { fireEvent.click(screen.getByRole('button')); });
    expect(screen.getByRole('button').textContent).toBe('collapsed');
    expect(localStorage.getItem('v3.sidebar.collapsed')).toBe('true');
  });

  it('reads the persisted preference on mount', () => {
    localStorage.setItem('v3.sidebar.collapsed', 'true');
    render(<Harness />);
    expect(screen.getByRole('button').textContent).toBe('collapsed');
  });
});
