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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MergeDialog } from './MergeDialog';
import type { LocalPR } from '../../types/localPr';

afterEach(cleanup);

const PR: LocalPR = {
  id: 'pr1', taskId: 't1', branchName: 'feat/cost-meter', baseBranch: 'main',
  title: 'Add cost-meter card', description: '', status: 'remote-open',
  createdAt: Date.now(), pushedAt: Date.now(), remotePrNumber: 145,
  remotePrUrl: 'https://github.com/o/r/pull/145', mergedAt: null, closedAt: null,
  origin: 'task', repo: null, author: null, syncedAt: null,
  syncedAdditions: null, syncedDeletions: null,
};

describe('MergeDialog', () => {
  it('shows the PR number, branch flow and defaults to squash', () => {
    render(<MergeDialog pr={PR} repoLabel="o/r" onMerge={() => {}} onCancel={() => {}} />);
    expect(screen.getByText(/Merge pull request #145/)).toBeTruthy();
    expect(screen.getByText('feat/cost-meter')).toBeTruthy();
    expect(screen.getByRole('radio', { name: /Squash and merge/ }).getAttribute('aria-checked')).toBe('true');
  });

  it('merges with the chosen method on click', () => {
    const onMerge = vi.fn();
    render(<MergeDialog pr={PR} onMerge={onMerge} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('radio', { name: /Rebase and merge/ }));
    fireEvent.click(screen.getByRole('button', { name: /Merge pull request/ }));
    expect(onMerge).toHaveBeenCalledWith('rebase');
  });

  it('confirms with the default method on ⌘↵', () => {
    const onMerge = vi.fn();
    render(<MergeDialog pr={PR} onMerge={onMerge} onCancel={() => {}} />);
    fireEvent.keyDown(window, { key: 'Enter', metaKey: true });
    expect(onMerge).toHaveBeenCalledWith('squash');
  });

  it('cancels on Escape and backdrop', () => {
    const onCancel = vi.fn();
    render(<MergeDialog pr={PR} onMerge={() => {}} onCancel={onCancel} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(document.querySelector('.push-dialog-overlay') as Element);
    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it('does not merge while busy', () => {
    const onMerge = vi.fn();
    render(<MergeDialog pr={PR} onMerge={onMerge} onCancel={() => {}} busy />);
    fireEvent.keyDown(window, { key: 'Enter', metaKey: true });
    expect(onMerge).not.toHaveBeenCalled();
  });
});
