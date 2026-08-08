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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Composer, Main, Shell } from './index';
import { SIDEBAR_WIDTH_KEY } from './useSidebarWidth';

afterEach(cleanup);

function sampleSidebar(collapsed = false) {
  return (
    <Shell collapsed={collapsed}>
      <aside>Backend cleanup review</aside>
      <Main topBar={<div>Run</div>}>
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
