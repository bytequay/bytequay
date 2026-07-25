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
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DiffFileDto } from '../types';
import type { LocalPR, LocalPRBundle, LocalPRComment } from '../types/localPr';
import PullChanges from './PullChanges';
import type { PullRow } from './model';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

const FILE: DiffFileDto = {
  filename: 'src/review/Guard.java', status: 'modified', additions: 2, deletions: 0,
  patch: '@@ -0,0 +1,2 @@\n+first guard\n+second guard\n',
};

function pr(over: Partial<LocalPR> = {}): LocalPR {
  return {
    id: 'pr-1', taskId: null, branchName: 'feature/review', baseBranch: 'main',
    title: 'Guard cleanup', description: '', status: 'remote-open', createdAt: Date.now(),
    pushedAt: Date.now(), remotePrNumber: 12, remotePrUrl: 'https://github.com/acme/widget/pull/12',
    mergedAt: null, closedAt: null, origin: 'external', repo: 'acme/widget', author: 'octocat',
    syncedAt: Date.now(), syncedAdditions: 2, syncedDeletions: 0, syncedMergeable: true,
    syncedMergeableState: 'clean', syncedMergeQueueEnabled: false, syncedMergeQueueState: null,
    branchDeletedAt: null, ...over,
  };
}

function bundle(pull = pr(), comments: LocalPRComment[] = []): LocalPRBundle {
  return { pr: pull, commits: [], timeline: [], checks: [], comments };
}

function row(): PullRow {
  return {
    id: 'pr-1', repo: 'acme/widget', num: 12, title: 'Guard cleanup', author: 'octocat',
    time: 'now', kind: 'pr', chips: [], status: 'passed', add: 2, del: 0, comments: 0,
    hasAgent: false, dto: {} as PullRow['dto'],
  };
}

function createdComment(): LocalPRComment {
  return {
    id: 'comment-7', localPrId: 'pr-1', origin: 'local', scope: 'file-line',
    filePath: FILE.filename, lineNumber: 2, side: 'RIGHT', startLine: 1, startSide: 'RIGHT',
    author: 'you', body: 'Guard both paths.', createdAt: Date.now(), resolvedAt: null,
    dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: null,
  };
}

function openRangeComposer(container: HTMLElement) {
  const first = container.querySelector(`[data-pl-anchor="${FILE.filename}:RIGHT:1"]`);
  const second = container.querySelector(`[data-pl-anchor="${FILE.filename}:RIGHT:2"]`);
  if (first === null || second === null) throw new Error('expected both added diff lines');
  fireEvent.click(first);
  fireEvent.click(second, { shiftKey: true });
  const textarea = container.querySelector<HTMLTextAreaElement>('.pl-code textarea[placeholder="Leave a comment"]');
  if (textarea === null) throw new Error('expected inline comment textarea');
  const cell = textarea.closest('td');
  if (cell === null) throw new Error('expected inline composer cell');
  return { textarea, actions: within(cell) };
}

describe('inline diff comment actions', () => {
  it('shows the exact local actions and submits one anchored comment to Development', async () => {
    const addLocalPrComment = vi.fn().mockResolvedValue(createdComment());
    const submitReview = vi.fn().mockResolvedValue({ submitted: 1, turnId: null });
    const refresh = vi.fn();
    window.bridge = { addLocalPrComment, submitReview } as unknown as typeof window.bridge;
    const local = bundle(pr({
      taskId: 'task-1', origin: 'task', status: 'local-open', pushedAt: null,
      remotePrNumber: null, remotePrUrl: null, repo: null, author: null,
    }));

    const { container } = render(
      <PullChanges row={row()} bundle={local} refresh={refresh} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);

    expect(actions.getAllByRole('button').map(button => button.textContent)).toEqual([
      'Cancel', 'Submit to agent', 'Add to review',
    ]);
    expect(actions.getByRole('button', { name: 'Add to review' }).style.background).toBe('rgb(31, 136, 61)');
    expect(actions.getByRole('button', { name: 'Submit to agent' }).style.background).toBe('rgb(255, 255, 255)');

    fireEvent.change(textarea, { target: { value: '  Guard both paths.  ' } });
    fireEvent.click(actions.getByRole('button', { name: 'Submit to agent' }));

    await waitFor(() => expect(submitReview).toHaveBeenCalledWith('task-1', {
      verdict: 'COMMENT', commentIds: ['comment-7'],
    }));
    expect(addLocalPrComment).toHaveBeenCalledWith('pr-1', {
      scope: 'file-line', filePath: FILE.filename, lineNumber: 2, side: 'RIGHT',
      startLine: 1, startSide: 'RIGHT', body: 'Guard both paths.',
    });
    expect(addLocalPrComment.mock.invocationCallOrder[0]).toBeLessThan(submitReview.mock.invocationCallOrder[0]);
    expect(refresh).toHaveBeenCalled();
  });

  it('shows the exact remote actions and posts a range against the fetched full head SHA', async () => {
    const createInlineReviewComment = vi.fn().mockResolvedValue(undefined);
    const polishCommentText = vi.fn().mockResolvedValue('Remote concern.');
    const refresh = vi.fn();
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      refreshPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      createInlineReviewComment, polishCommentText, addLocalPrComment: vi.fn(),
    } as unknown as typeof window.bridge;

    const { container } = render(
      <PullChanges row={row()} bundle={bundle()} refresh={refresh} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);

    expect(actions.getAllByRole('button').map(button => button.textContent)).toEqual([
      'Cancel', 'Comment', 'Add to review', 'Better words',
    ]);
    expect(actions.getByRole('button', { name: 'Add to review' }).style.background).toBe('rgb(31, 136, 61)');
    expect(actions.getByRole('button', { name: 'Comment' }).style.background).toBe('rgb(255, 255, 255)');
    expect(actions.getByRole('button', { name: 'Better words' }).style.background).toBe('rgb(255, 255, 255)');

    fireEvent.change(textarea, { target: { value: 'Could you maybe guard this remote path?' } });
    fireEvent.click(actions.getByRole('button', { name: 'Better words' }));
    await waitFor(() => expect(textarea.value).toBe('Remote concern.'));
    expect(polishCommentText).toHaveBeenCalledWith('Could you maybe guard this remote path?');
    expect(actions.getAllByRole('button').map(button => button.textContent)).toEqual([
      'Cancel', 'Comment', 'Add to review', 'Better words',
    ]);
    fireEvent.click(actions.getByRole('button', { name: 'Comment' }));

    await waitFor(() => expect(createInlineReviewComment).toHaveBeenCalledWith(
      'acme/widget', 12, 'Remote concern.', FILE.filename, 2, 'RIGHT', 1, 'RIGHT',
    ));
    expect(refresh).toHaveBeenCalled();
  });

  it('keeps the draft and composer open with a visible action error', async () => {
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      createInlineReviewComment: vi.fn().mockRejectedValue(new Error('GitHub unavailable')),
    } as unknown as typeof window.bridge;
    const { container } = render(
      <PullChanges row={row()} bundle={bundle()} refresh={vi.fn()} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);
    fireEvent.change(textarea, { target: { value: 'Keep this draft.' } });
    fireEvent.click(actions.getByRole('button', { name: 'Comment' }));

    expect((await screen.findByRole('alert')).textContent).toContain('GitHub unavailable');
    expect(textarea.value).toBe('Keep this draft.');
    expect((actions.getByRole('button', { name: 'Comment' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('reuses the persisted local comment when Submit to agent is retried', async () => {
    const addLocalPrComment = vi.fn().mockResolvedValue(createdComment());
    const submitReview = vi.fn()
      .mockRejectedValueOnce(new Error('Agent dispatch unavailable'))
      .mockResolvedValueOnce({ submitted: 1, turnId: null });
    window.bridge = { addLocalPrComment, submitReview } as unknown as typeof window.bridge;
    const local = bundle(pr({
      taskId: 'task-1', origin: 'task', status: 'local-open', pushedAt: null,
      remotePrNumber: null, remotePrUrl: null, repo: null, author: null,
    }));
    const { container } = render(
      <PullChanges row={row()} bundle={local} refresh={vi.fn()} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);
    fireEvent.change(textarea, { target: { value: 'Keep one durable draft.' } });
    fireEvent.click(actions.getByRole('button', { name: 'Submit to agent' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Agent dispatch unavailable');
    expect(textarea.value).toBe('Keep one durable draft.');
    expect(addLocalPrComment).toHaveBeenCalledOnce();
    expect(submitReview).toHaveBeenCalledOnce();
    expect(textarea.readOnly).toBe(true);
    fireEvent.change(textarea, { target: { value: 'Do not silently submit different text.' } });
    expect(textarea.value).toBe('Keep one durable draft.');

    fireEvent.click(actions.getByRole('button', { name: 'Submit to agent' }));

    await waitFor(() => expect(submitReview).toHaveBeenCalledTimes(2));
    expect(submitReview).toHaveBeenLastCalledWith('task-1', {
      verdict: 'COMMENT', commentIds: ['comment-7'],
    });
    expect(addLocalPrComment).toHaveBeenCalledOnce();
  });

  it('blocks closing and posting actions while Better words is polishing', async () => {
    let finishPolish: ((value: string) => void) | undefined;
    const polishCommentText = vi.fn().mockReturnValue(new Promise<string>(resolve => {
      finishPolish = resolve;
    }));
    const addLocalPrComment = vi.fn();
    const createInlineReviewComment = vi.fn();
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      polishCommentText, addLocalPrComment, createInlineReviewComment,
    } as unknown as typeof window.bridge;
    const { container } = render(
      <PullChanges row={row()} bundle={bundle()} refresh={vi.fn()} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);
    fireEvent.change(textarea, { target: { value: 'Pre-polish wording.' } });
    fireEvent.click(actions.getByRole('button', { name: 'Better words' }));

    await waitFor(() => expect(actions.getByRole('button', { name: 'Polishing…' })).not.toBeNull());
    const cancel = actions.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement;
    const comment = actions.getByRole('button', { name: 'Comment' }) as HTMLButtonElement;
    const add = actions.getByRole('button', { name: 'Add to review' }) as HTMLButtonElement;
    expect(cancel.disabled).toBe(true);
    expect(comment.disabled).toBe(true);
    expect(add.disabled).toBe(true);

    fireEvent.click(cancel);
    fireEvent.click(comment);
    fireEvent.click(add);
    fireEvent.keyDown(textarea, { key: 'Escape' });
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true });
    expect(textarea.isConnected).toBe(true);
    expect(createInlineReviewComment).not.toHaveBeenCalled();
    expect(addLocalPrComment).not.toHaveBeenCalled();

    await act(async () => { finishPolish?.('Polished wording.'); });
    await waitFor(() => expect(textarea.value).toBe('Polished wording.'));
    expect((actions.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(false);
    expect((actions.getByRole('button', { name: 'Comment' }) as HTMLButtonElement).disabled).toBe(false);
    expect((actions.getByRole('button', { name: 'Add to review' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps Add to review local on a remote pull request for batch submission', async () => {
    const addLocalPrComment = vi.fn().mockResolvedValue(createdComment());
    const fetchPrCommits = vi.fn();
    const createInlineReviewComment = vi.fn();
    window.bridge = {
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
      addLocalPrComment, fetchPrCommits, createInlineReviewComment,
    } as unknown as typeof window.bridge;
    const { container } = render(
      <PullChanges row={row()} bundle={bundle()} refresh={vi.fn()} filesOverride={[FILE]} />,
    );
    const { textarea, actions } = openRangeComposer(container);
    fireEvent.change(textarea, { target: { value: 'Batch remotely.' } });
    fireEvent.click(actions.getByRole('button', { name: 'Add to review' }));

    await waitFor(() => expect(addLocalPrComment).toHaveBeenCalledWith('pr-1', {
      scope: 'file-line', filePath: FILE.filename, lineNumber: 2, side: 'RIGHT',
      startLine: 1, startSide: 'RIGHT', body: 'Batch remotely.',
    }));
    expect(fetchPrCommits).not.toHaveBeenCalled();
    expect(createInlineReviewComment).not.toHaveBeenCalled();
  });

  it('disables composing on a historical commit diff whose anchors do not match the live head', async () => {
    const remote = bundle();
    remote.commits = [{
      id: 'commit-1', localPrId: 'pr-1', sha: 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef',
      message: 'Older commit', additions: 2, deletions: 0, authoredAt: Date.now(), pushedAt: Date.now(),
    }];
    const fetchPrCommitDiff = vi.fn().mockResolvedValue([FILE]);
    window.bridge = {
      fetchPrDiffFiles: vi.fn().mockResolvedValue([FILE]),
      fetchPrCommitDiff,
      fetchPullRequestDetail: vi.fn().mockResolvedValue({ recentActivity: [], reviewThreads: [] }),
    } as unknown as typeof window.bridge;

    const { container } = render(
      <PullChanges row={row()} bundle={remote} refresh={vi.fn()} />,
    );
    await waitFor(() => expect(container.querySelector(`[data-pl-anchor="${FILE.filename}:RIGHT:1"]`)).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: /All commits/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Older commit/ }));

    await waitFor(() => expect(fetchPrCommitDiff).toHaveBeenCalledWith(
      'acme/widget', 12, 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef',
    ));
    expect(screen.getByRole('note').textContent).toContain('Select All commits');
    const anchor = container.querySelector(`[data-pl-anchor="${FILE.filename}:RIGHT:1"]`);
    if (anchor === null) throw new Error('expected historical diff line');
    fireEvent.click(anchor);
    expect(container.querySelector('.pl-code textarea[placeholder="Leave a comment"]')).toBeNull();
  });

  it('maps the safe keyboard shortcut to Add to review', async () => {
    const addLocalPrComment = vi.fn().mockResolvedValue(createdComment());
    const submitReview = vi.fn();
    window.bridge = { addLocalPrComment, submitReview } as unknown as typeof window.bridge;
    const local = bundle(pr({
      taskId: 'task-1', origin: 'task', status: 'local-open', pushedAt: null,
      remotePrNumber: null, remotePrUrl: null, repo: null, author: null,
    }));
    const { container } = render(
      <PullChanges row={row()} bundle={local} refresh={vi.fn()} filesOverride={[FILE]} />,
    );
    const { textarea } = openRangeComposer(container);
    fireEvent.change(textarea, { target: { value: 'Batch this.' } });
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true });

    await waitFor(() => expect(addLocalPrComment).toHaveBeenCalledOnce());
    expect(addLocalPrComment).toHaveBeenCalledWith('pr-1', {
      scope: 'file-line', filePath: FILE.filename, lineNumber: 2, side: 'RIGHT',
      startLine: 1, startSide: 'RIGHT', body: 'Batch this.',
    });
    expect(submitReview).not.toHaveBeenCalled();
  });

  it('excludes an actively submitted local root from the pending count and sidebar', () => {
    const local = bundle(pr({
      taskId: 'task-1', origin: 'task', status: 'local-open', pushedAt: null,
      remotePrNumber: null, remotePrUrl: null, repo: null, author: null,
    }), [createdComment()]);
    local.timeline = [{
      id: 'submitted-1', localPrId: 'pr-1', eventType: 'review', actor: 'you',
      createdAt: Date.now(), payload: { reviewEvent: 'submitted', commentIds: ['comment-7'] },
      isLocalOnly: true, strippedOnPushAt: null,
    }];
    window.bridge = {} as typeof window.bridge;

    render(<PullChanges row={row()} bundle={local} refresh={vi.fn()} filesOverride={[]} />);

    const toggle = screen.getByRole('button', { name: 'Toggle review comments panel (0 pending)' });
    expect(screen.queryByText('No pending review comments.')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('No pending review comments.')).not.toBeNull();
  });
});
