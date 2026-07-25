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
import { invalidate } from '../dataCache';
import type { DiffFileDto } from '../types';
import type { LocalPR, LocalPRBundle, LocalPRComment } from '../types/localPr';
import PullChanges from './PullChanges';
import PullReviewSidebar from './PullReviewSidebar';
import type { PullRow } from './model';

afterEach(() => {
  cleanup();
  invalidate('home:profile');
  delete (globalThis as { bridge?: unknown }).bridge;
});

function comment(over: Partial<LocalPRComment> = {}): LocalPRComment {
  return {
    id: 'finding-1', localPrId: 'pr-1', origin: 'local', scope: 'file-line',
    filePath: 'src/review/Guard.java', lineNumber: 42, side: 'RIGHT',
    startLine: 40, startSide: 'RIGHT', author: 'agent',
    body: '**Broken invariant:** the `close()` path can leak.\n\nThe final cleanup branch is never reached.',
    createdAt: Date.now(), resolvedAt: null, dismissedAt: null, strippedOnPushAt: null,
    parentCommentId: null, publishedAt: null, ...over,
  };
}

function pr(): LocalPR {
  return {
    id: 'pr-1', taskId: null, branchName: 'feature/review', baseBranch: 'main',
    title: 'Guard cleanup', description: '', status: 'remote-open', createdAt: Date.now(),
    pushedAt: Date.now(), remotePrNumber: 12, remotePrUrl: 'https://github.com/acme/widget/pull/12',
    mergedAt: null, closedAt: null, origin: 'external', repo: 'acme/widget', author: 'octocat',
    syncedAt: Date.now(), syncedAdditions: 3, syncedDeletions: 1, syncedMergeable: true,
    syncedMergeableState: 'clean', syncedMergeQueueEnabled: false, syncedMergeQueueState: null,
    branchDeletedAt: null,
  };
}

function row(): PullRow {
  return {
    id: 'pr-1', repo: 'acme/widget', num: 12, title: 'Guard cleanup', author: 'octocat',
    time: 'now', kind: 'pr', chips: [], status: 'passed', add: 3, del: 1, comments: 0,
    hasAgent: true, dto: {} as PullRow['dto'],
  };
}

function bundle(comments: LocalPRComment[]): LocalPRBundle {
  return { pr: pr(), commits: [], timeline: [], checks: [], comments };
}

describe('PullReviewSidebar', () => {
  it('uses the signed-in GitHub avatar in History when a task PR has no stored author', async () => {
    window.bridge = {
      getUserProfile: vi.fn().mockResolvedValue({
        login: 'octocat', avatarUrl: 'https://avatars.example/octocat.png',
      }),
    } as unknown as typeof window.bridge;
    const taskRow = row();
    taskRow.author = '';
    const taskBundle = bundle([]);
    taskBundle.pr.author = null;

    render(
      <PullReviewSidebar
        row={taskRow} bundle={taskBundle} files={null} pending={[]} activity={[]}
        width={320} login="you" onJump={vi.fn()} onResolve={vi.fn()} onDelete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'History' }));

    const avatar = await screen.findByRole('img', { name: 'octocat' });
    expect(avatar.getAttribute('src')).toBe('https://avatars.example/octocat.png');
  });

  it('renders every pending body as markdown and jumps from its exact file range', () => {
    const first = comment();
    const second = comment({
      id: 'finding-2', filePath: 'src/review/Retry.java', lineNumber: 77,
      startLine: null, startSide: null, body: 'Second finding with its complete ending.',
    });
    const published = comment({ id: 'published', publishedAt: Date.now(), body: 'Already published' });
    const onJump = vi.fn();
    const { container } = render(
      <PullReviewSidebar
        row={row()} bundle={bundle([first, second, published])} files={null}
        pending={[first, second]} activity={[]} width={320} login="you"
        onJump={onJump} onResolve={vi.fn()} onDelete={vi.fn()}
      />,
    );

    expect(screen.getByText('2 pending review comments')).not.toBeNull();
    expect(screen.queryByText(/Showing .* of .* comments/)).toBeNull();
    expect(screen.getByText('…/review/Guard.java')).not.toBeNull();
    expect(screen.getByText('R40 to R42')).not.toBeNull();
    expect(screen.getByText('Second finding with its complete ending.')).not.toBeNull();
    expect(container.querySelector('.pl-pending-comment-body strong')?.textContent).toBe('Broken invariant:');
    expect(container.querySelector('.pl-pending-comment-body code')?.textContent).toBe('close()');
    expect(container.querySelector('textarea.pl-pend-ta')).toBeNull();

    fireEvent.click(screen.getAllByTitle('Jump to line')[0]);
    expect(onJump).toHaveBeenCalledWith('src/review/Guard.java', 'RIGHT', 42);
  });

  it('shows the pending count on the changes toolbar toggle', async () => {
    const comments = [comment(), comment({ id: 'finding-2', lineNumber: 43, startLine: null, startSide: null })];
    window.bridge = {
      fetchPrDiffFiles: vi.fn().mockResolvedValue([]),
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
    } as unknown as typeof window.bridge;

    render(<PullChanges row={row()} bundle={bundle(comments)} refresh={vi.fn()} />);

    await waitFor(() => expect(window.bridge.fetchPrDiffFiles).toHaveBeenCalled());
    const toggle = screen.getByRole('button', { name: 'Toggle review comments panel (2 pending)' });
    expect(toggle.querySelector('.pl-review-toggle-count')?.textContent).toBe('2');
    expect(screen.queryByText('2 pending review comments')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('2 pending review comments')).not.toBeNull();
  });

  it('opens a local review composer on a pushed task PR so drafts can publish to GitHub', () => {
    const pushedTask = bundle([]);
    pushedTask.pr = {
      ...pushedTask.pr,
      taskId: 'task-1', origin: 'task', status: 'remote-open', author: null,
    };
    const file: DiffFileDto = {
      filename: 'src/review/Guard.java', status: 'modified', additions: 1, deletions: 1,
      patch: '@@ -1 +1 @@\n-old guard\n+new guard\n',
    };
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      addLocalPrComment: vi.fn(),
    } as unknown as typeof window.bridge;

    const { container } = render(
      <PullChanges
        row={row()} bundle={pushedTask} refresh={vi.fn()} filesOverride={[file]}
      />,
    );
    const addedLine = container.querySelector('[data-pl-anchor="src/review/Guard.java:RIGHT:1"]');
    if (addedLine === null) throw new Error('expected added diff line');
    expect(addedLine.getAttribute('title')).not.toBeNull();

    fireEvent.click(addedLine);

    expect(container.querySelector('.pl-code textarea[placeholder="Leave a comment"]')).not.toBeNull();
  });
});
