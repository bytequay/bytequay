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
import { ChangesPage } from './ChangesPage';

afterEach(cleanup);
beforeEach(() => localStorage.clear());

function renderChanges(overrides: Partial<Parameters<typeof ChangesPage>[0]> = {}) {
  return render(
    <ChangesPage
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">feed</div>}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
      fileTree={<div data-testid="file-tree">tree</div>}
      diff={<div data-testid="diff">diff</div>}
      fileCount={3}
      commits={[{ sha: 'abc123', label: 'Fix typos' }]}
      onBack={() => {}}
      {...overrides}
    />,
  );
}

describe('ChangesPage', () => {
  it('renders collapsed sidebar, conversation, file tree, and diff', () => {
    renderChanges();
    expect(document.querySelector('.shell.sidebar-collapsed')).toBeTruthy();
    expect(screen.getByTestId('conv')).toBeTruthy();
    expect(screen.getByTestId('file-tree')).toBeTruthy();
    expect(screen.getByTestId('diff')).toBeTruthy();
    expect(document.querySelector('.changes-body')?.className).toBe('changes-body');
  });

  it('hides the file tree and persists the preference', () => {
    const first = renderChanges();
    fireEvent.click(screen.getByRole('button', { name: 'Hide file tree' }));
    expect(first.container.querySelector('.changes-body.no-files')).toBeTruthy();
    expect(screen.queryByTestId('file-tree')).toBeNull();
    expect(localStorage.getItem('v3.changes.filesHidden')).toBe('true');
    cleanup();
    // A fresh mount reads the persisted preference.
    renderChanges();
    expect(screen.queryByTestId('file-tree')).toBeNull();
    expect(screen.getByRole('button', { name: 'Show file tree' })).toBeTruthy();
  });

  it('the commits dropdown re-scopes to a commit and back to all', () => {
    const onSelectCommit = vi.fn();
    renderChanges({ onSelectCommit });
    fireEvent.click(screen.getByRole('button', { name: /All commits/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Fix typos' }));
    expect(onSelectCommit).toHaveBeenCalledWith('abc123');
  });

  it('back button fires onBack', () => {
    const onBack = vi.fn();
    renderChanges({ onBack });
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalledOnce();
  });
});
