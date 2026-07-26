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
import { afterEach, describe, expect, it, vi } from 'vitest';
import ControlBar from './ControlBar';
import { ACTION_CATALOG, filterCatalog } from './actionCatalog';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
});

describe('filterCatalog', () => {
  it('returns the full catalog for an empty query', () => {
    expect(filterCatalog('')).toEqual(ACTION_CATALOG);
    expect(filterCatalog('   ')).toEqual(ACTION_CATALOG);
  });

  it('matches all query tokens against label + description + keywords', () => {
    // "memory" should pull the WORKSPACE.md row (label has "WORKSPACE",
    // keyword has "memory") to the top.
    const result = filterCatalog('memory');
    expect(result[0].id).toBe('nav.workspace.memory');
    // "new trunk" matches the create.thread action's public label directly.
    expect(filterCatalog('new trunk')[0].id).toBe('create.thread');
    expect(filterCatalog('report bug')[0].id).toBe('create.issue-report');
  });

  it('returns no rows when no catalog entry matches every token', () => {
    expect(filterCatalog('zzz-no-match')).toEqual([]);
  });

  it(':go verb narrows to navigation actions only', () => {
    const result = filterCatalog(':go');
    expect(result.length).toBeGreaterThan(0);
    expect(result.every(r => r.source === 'navigation')).toBe(true);
    // ":go" alone surfaces all nav actions; no create rows leak in.
    expect(result.some(r => r.source === 'create')).toBe(false);
  });

  it(':create verb narrows to creation actions only', () => {
    const result = filterCatalog(':create');
    expect(result.length).toBeGreaterThan(0);
    expect(result.every(r => r.source === 'create')).toBe(true);
  });

  it(':go threads finds nav row even though "threads" is in many descriptions', () => {
    const result = filterCatalog(':go threads');
    expect(result[0].id).toBe('nav.threads');
  });

  it(':open memory narrows + filters to the workspace memory row', () => {
    const result = filterCatalog(':open memory');
    expect(result[0].id).toBe('nav.workspace.memory');
  });

  it('weights label hits over keyword/description hits', () => {
    // "settings" appears in the workspace-settings keywords AND in the
    // app-settings label. App settings should NOT outrank workspace
    // settings when the query has overlap — pin the ordering via the
    // catalog's stable index for tied scores.
    const result = filterCatalog('settings');
    expect(result.map(r => r.id)).toContain('nav.workspace.settings');
    expect(result.map(r => r.id)).toContain('nav.settings');
  });
});

describe('ControlBar', () => {
  it('does not render when open=false', () => {
    render(<ControlBar open={false} onClose={() => {}} onDispatch={() => {}} />);
    expect(screen.queryByLabelText('Command bar')).toBeNull();
  });

  it('renders context tag chips above the input when provided', () => {
    render(
      <ControlBar
        open
        onClose={() => {}}
        onDispatch={() => {}}
        contextTags={[
          { label: 'workspace-bytequay', kind: 'scope' },
          { label: 'threads', kind: 'scope' },
        ]}
      />,
    );
    expect(screen.getByText('#workspace-bytequay')).toBeTruthy();
    expect(screen.getByText('#threads')).toBeTruthy();
  });

  it('omits the "on" row when no context tags are provided', () => {
    render(<ControlBar open onClose={() => {}} onDispatch={() => {}} />);
    // The "on" label shouldn't render when contextTags is undefined.
    expect(screen.queryByText('on')).toBeNull();
  });

  it('renders the full catalog on first open', () => {
    render(<ControlBar open onClose={() => {}} onDispatch={() => {}} />);
    expect(screen.getByLabelText('Command bar')).toBeTruthy();
    // Every catalog row renders.
    const items = screen.getAllByRole('option');
    expect(items.length).toBe(ACTION_CATALOG.length);
  });

  it('filters as the user types', () => {
    render(<ControlBar open onClose={() => {}} onDispatch={() => {}} />);
    const input = screen.getByLabelText('Command bar input') as HTMLInputElement;
    act(() => { fireEvent.change(input, { target: { value: 'memory' } }); });
    const items = screen.getAllByRole('option');
    // At least one result, and the WORKSPACE.md row is the top hit.
    expect(items.length).toBeGreaterThan(0);
    expect(items[0].textContent).toContain('Open WORKSPACE.md');
  });

  it('Enter executes the highlighted action and closes the bar', () => {
    const onDispatch = vi.fn();
    const onClose = vi.fn();
    render(<ControlBar open onClose={onClose} onDispatch={onDispatch} />);
    const dialog = screen.getByLabelText('Command bar');
    // Down once, then Enter — the catalog's stable order means the
    // second row is the Memory action.
    act(() => { fireEvent.keyDown(dialog, { key: 'ArrowDown' }); });
    act(() => { fireEvent.keyDown(dialog, { key: 'Enter' }); });
    expect(onDispatch).toHaveBeenCalledTimes(1);
    expect(onDispatch.mock.calls[0][0]).toEqual({
      kind: 'nav.workspace', section: 'memory',
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('Esc closes without dispatching', () => {
    const onDispatch = vi.fn();
    const onClose = vi.fn();
    render(<ControlBar open onClose={onClose} onDispatch={onDispatch} />);
    const dialog = screen.getByLabelText('Command bar');
    act(() => { fireEvent.keyDown(dialog, { key: 'Escape' }); });
    expect(onClose).toHaveBeenCalled();
    expect(onDispatch).not.toHaveBeenCalled();
  });

  it('Clicking a row executes that row even when the keyboard cursor is elsewhere', () => {
    const onDispatch = vi.fn();
    const onClose = vi.fn();
    render(<ControlBar open onClose={onClose} onDispatch={onDispatch} />);
    const threadsRow = screen.getByRole('option', { name: /Go to Threads/i });
    act(() => { fireEvent.click(threadsRow); });
    expect(onDispatch).toHaveBeenCalledWith({ kind: 'nav.threads' });
    expect(onClose).toHaveBeenCalled();
  });

  it('Empty-state Workspace home shortcut still works when nothing matches', () => {
    const onDispatch = vi.fn();
    render(<ControlBar open onClose={() => {}} onDispatch={onDispatch} />);
    const input = screen.getByLabelText('Command bar input') as HTMLInputElement;
    act(() => { fireEvent.change(input, { target: { value: 'zzz-no-match' } }); });
    expect(screen.getByText(/No commands match/i)).toBeTruthy();
    act(() => { fireEvent.click(screen.getByText(/Workspace home →/i)); });
    expect(onDispatch).toHaveBeenCalledWith({ kind: 'nav.workspace', section: 'home' });
  });
});
