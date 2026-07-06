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
import { PushDialog } from './PushDialog';
import type { LocalPR, LocalPRBundle, LocalPRComment, LocalPRCommit } from '../../types/localPr';

afterEach(cleanup);

const PR: LocalPR = {
  id: 'pr1', taskId: 't1', branchName: 'feat/cost-meter', baseBranch: 'main',
  title: 'Add cost-meter card', description: 'Adds a `CostMeterCard`.', status: 'local-open',
  createdAt: Date.now(), pushedAt: null, remotePrNumber: null, remotePrUrl: null,
  mergedAt: null, closedAt: null,
  origin: 'task', repo: null, author: null, syncedAt: null,
};

function commit(additions: number, deletions: number, i: number): LocalPRCommit {
  return {
    id: `c${i}`, localPrId: 'pr1', sha: `sha${i}`, message: 'm', additions, deletions,
    authoredAt: Date.now(), pushedAt: null,
  };
}

function bundle(over: Partial<LocalPRBundle> = {}): LocalPRBundle {
  return {
    pr: PR,
    commits: [commit(100, 8, 1), commit(80, 6, 2)],
    timeline: [], checks: [], comments: [], pendingStripCount: 2, ...over,
  };
}

describe('PushDialog', () => {
  it('summarises repo, branch flow and commit delta', () => {
    render(<PushDialog bundle={bundle()} repoLabel="chenjian2664/bytequay" onPush={() => {}} onCancel={() => {}} />);
    expect(screen.getByText('chenjian2664/bytequay')).toBeTruthy();
    expect(screen.getByText('feat/cost-meter')).toBeTruthy();
    expect(screen.getByText(/2 commits/)).toBeTruthy();
    expect(screen.getByText('+180')).toBeTruthy();
    expect(screen.getByText('−14')).toBeTruthy();
    // Draft is a stated row, not a checkbox.
    expect(screen.getByText(/Draft PR — flip to ready-for-review/)).toBeTruthy();
    expect(document.querySelector('input[type="checkbox"]')).toBeNull();
  });

  it('warns with the exact stripped-comment count from the bundle', () => {
    render(<PushDialog bundle={bundle({ pendingStripCount: 2 })} onPush={() => {}} onCancel={() => {}} />);
    expect(screen.getByText(/2 local review comments will NOT be pushed/)).toBeTruthy();
  });

  it('falls back to counting unstripped local comments when no count is supplied', () => {
    const comments: LocalPRComment[] = [
      { id: 'x1', localPrId: 'pr1', origin: 'local', scope: 'pr', filePath: null,
        lineNumber: null, author: 'you', body: 'a', createdAt: Date.now(), resolvedAt: null,
        dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null },
    ];
    render(<PushDialog bundle={bundle({ pendingStripCount: undefined, comments })} onPush={() => {}} onCancel={() => {}} />);
    expect(screen.getByText(/1 local review comment will NOT be pushed/)).toBeTruthy();
  });

  it('omits the warning when nothing would be stripped', () => {
    render(<PushDialog bundle={bundle({ pendingStripCount: 0 })} onPush={() => {}} onCancel={() => {}} />);
    expect(screen.queryByText(/will NOT be pushed/)).toBeNull();
  });

  it('fires onPush from the button and on ⌘↵', () => {
    const onPush = vi.fn();
    render(<PushDialog bundle={bundle()} onPush={onPush} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /Push to GitHub/ }));
    expect(onPush).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: 'Enter', metaKey: true });
    expect(onPush).toHaveBeenCalledTimes(2);
  });

  it('cancels on Escape, backdrop click, and the ✕ button', () => {
    const onCancel = vi.fn();
    render(<PushDialog bundle={bundle()} onPush={() => {}} onCancel={onCancel} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(document.querySelector('.push-dialog-overlay') as Element);
    expect(onCancel).toHaveBeenCalledTimes(4);
  });

  it('does not push while busy', () => {
    const onPush = vi.fn();
    render(<PushDialog bundle={bundle()} onPush={onPush} onCancel={() => {}} busy />);
    fireEvent.keyDown(window, { key: 'Enter', metaKey: true });
    expect(onPush).not.toHaveBeenCalled();
    expect((screen.getByRole('button', { name: /Push to GitHub/ }) as HTMLButtonElement).disabled).toBe(true);
  });
});
