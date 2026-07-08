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
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import TaskCodePage from './TaskCodePage';
import type { NotificationDto } from '../types';

// jsdom doesn't implement scrollIntoView; the shared ContinuousDiff calls it
// when the active file changes.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

const CUMULATIVE = [{
  filename: 'src/Foo.ts', status: 'modified', additions: 1, deletions: 1,
  patch: '@@ -1,2 +1,2 @@\n context\n-old line\n+new line\n',
}];
const PER_COMMIT = [{
  filename: 'src/Bar.ts', status: 'added', additions: 1, deletions: 0,
  patch: '@@ -0,0 +1,1 @@\n+brand new\n',
}];

function mockBridge(overrides: Record<string, unknown> = {}) {
  const bridge = {
    listTasksForThread: vi.fn().mockResolvedValue([
      { id: 'task-1', seq: 1, name: 'Fix typos', branchName: 'jack/fix' },
    ]),
    listTaskCommits: vi.fn().mockResolvedValue([
      { sha: 'abc123def', shortSha: 'abc123d', authorName: 'me', authorEmail: 'm@e', authoredAt: '2026-06-20T10:00:00Z', subject: 'Fix typos in docs' },
    ]),
    getTaskCumulativeDiff: vi.fn().mockResolvedValue(CUMULATIVE),
    getTaskCommitDiffFiles: vi.fn().mockResolvedValue(PER_COMMIT),
    // Review-mode bridge surface — default to "no proposal / no comments"
    // so the existing read-only tests stay unchanged.
    listNotificationsForThread: vi.fn().mockResolvedValue([]),
    listReviewComments: vi.fn().mockResolvedValue([]),
    addReviewComment: vi.fn().mockResolvedValue({}),
    resolveReviewComment: vi.fn().mockResolvedValue(undefined),
    reopenReviewComment: vi.fn().mockResolvedValue(undefined),
    submitReview: vi.fn().mockResolvedValue({ submitted: 1, turnId: 't1' }),
    setShipDescription: vi.fn().mockResolvedValue({}),
    approveNotification: vi.fn().mockResolvedValue({ ok: true, resolution: 'approved', message: '', action: 'ship_task' }),
    ...overrides,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

const SHIP_PROPOSAL: NotificationDto = {
  id: 'notif-1',
  kind: 'AWAITING_REVIEW',
  threadId: 'thread-1',
  taskId: 'task-1',
  status: 'UNREAD',
  payloadJson: JSON.stringify({
    action: 'ship_task',
    prTitle: 'Fix the typos',
    prBody: 'Body of the PR description.',
    diff: CUMULATIVE[0].patch,
    diffBase: 'main',
  }),
  createdAt: '2026-06-20T10:00:00Z',
  readAt: null,
};

const OPEN_COMMENT = {
  id: 'rc-1', taskId: 'task-1', file: 'src/Foo.ts', line: 1,
  body: 'please rename this', createdAt: 1, source: 'LOCAL_USER', resolved: false,
};

describe('TaskCodePage', () => {
  it('renders the cumulative diff via the shared continuous renderer', async () => {
    const bridge = mockBridge();
    const { container } = render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    // Toolbar + title.
    expect(await screen.findByRole('button', { name: '← Back' })).toBeTruthy();
    // The task id must reach the bridge — a thread can carry more than one
    // task, and omitting it made the backend resolve the wrong task's diff.
    await waitFor(() => expect(bridge.getTaskCumulativeDiff).toHaveBeenCalledWith('thread-1', 'task-1'));
    expect(bridge.listTaskCommits).toHaveBeenCalledWith('thread-1', 'task-1');
    // Continuous diff body (default Files tab): the changed file header +
    // actual diff rows from its patch (the shared renderer parsed the hunks).
    expect((await screen.findAllByText('src/Foo.ts')).length).toBeGreaterThan(0);
    await waitFor(() => expect(container.querySelectorAll('.diff-row--add').length).toBeGreaterThan(0));
    expect(container.querySelectorAll('.diff-row--del').length).toBeGreaterThan(0);
    // Commits now live under the Commits tab of the middle column.
    fireEvent.click(screen.getByRole('tab', { name: /Commits/ }));
    expect(await screen.findByText(/All 1 commit/)).toBeTruthy();
    expect(await screen.findByText('Fix typos in docs')).toBeTruthy();
  });

  it('scopes to a single commit when a commit row is clicked', async () => {
    const bridge = mockBridge();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    fireEvent.click(await screen.findByRole('tab', { name: /Commits/ }));
    fireEvent.click(await screen.findByText('Fix typos in docs'));
    await waitFor(() => expect(bridge.getTaskCommitDiffFiles).toHaveBeenCalledWith('thread-1', 'abc123def', 'task-1'));
    // The per-commit diff replaces the cumulative one.
    expect((await screen.findAllByText('src/Bar.ts')).length).toBeGreaterThan(0);
  });

  it('back button fires onBack', async () => {
    mockBridge();
    const onBack = vi.fn();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={onBack} />);
    fireEvent.click(await screen.findByRole('button', { name: '← Back' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('embedded hides the Back button/title toolbar but still renders the diff', async () => {
    mockBridge();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" embedded />);
    expect((await screen.findAllByText('src/Foo.ts')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '← Back' })).toBeNull();
    expect(screen.queryByText('Fix typos')).toBeNull();
  });

  it('embedded drops its own conversation column — the host page already shows one', async () => {
    mockBridge();
    const { container } = render(<TaskCodePage threadId="thread-1" taskId="task-1" embedded />);
    await screen.findAllByText('src/Foo.ts');
    expect(container.querySelector('.diff-viewer__chat')).toBeNull();
    // Files + diff still render.
    expect(screen.getByText('Changed files')).toBeTruthy();
  });

  it('no pending proposal → no PR panel and no review actions (read-only)', async () => {
    mockBridge();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);
    await screen.findAllByText('src/Foo.ts');
    expect(screen.queryByText('Pull request description')).toBeNull();
    expect(screen.queryByRole('button', { name: /Approve & ship/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Submit review' })).toBeNull();
  });

  it('review mode: renders the PR title/body and disables Approve while a comment is unresolved', async () => {
    mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([OPEN_COMMENT]),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    // One open comment → Approve & ship (in the toolbar) disabled with the hint.
    // Wait for the comments to load (they gate the button) before asserting.
    const approve = await screen.findByRole('button', { name: /Approve & ship/ });
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(true));
    expect(approve.getAttribute('title')).toBe('resolve the open review comments first');

    // The PR description lives under the Pull request tab, seeded from the payload.
    fireEvent.click(screen.getByRole('tab', { name: 'Pull request' }));
    expect(await screen.findByText('Pull request description')).toBeTruthy();
    await waitFor(() =>
      expect((screen.getByLabelText('Pull request title') as HTMLInputElement).value).toBe('Fix the typos'));
  });

  it('embedded hides the open-comments/ship actions too, even in review mode', async () => {
    mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([OPEN_COMMENT]),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" embedded />);
    await screen.findAllByText('src/Foo.ts');
    expect(screen.queryByText(/open comment/)).toBeNull();
    expect(screen.queryByRole('button', { name: /Approve & ship/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Submit review' })).toBeNull();
  });

  it('defaults to the Code tab; Pull request tab shows a placeholder with no proposal', async () => {
    mockBridge();
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);
    // Code is the default — the diff renders.
    expect((await screen.findAllByText('src/Foo.ts')).length).toBeGreaterThan(0);
    // No ship proposal → the Pull request tab explains there's no PR yet.
    fireEvent.click(screen.getByRole('tab', { name: 'Pull request' }));
    expect(await screen.findByText(/No pull request yet/)).toBeTruthy();
  });

  it('review mode: Approve becomes enabled once the comment is resolved', async () => {
    const resolved = { ...OPEN_COMMENT, resolved: true };
    mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([resolved]),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    const approve = await screen.findByRole('button', { name: /Approve & ship/ });
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(false));
  });

  it('review mode: an interrupted approve shows the reason and does not navigate', async () => {
    const resolved = { ...OPEN_COMMENT, resolved: true };
    const onBack = vi.fn();
    mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([resolved]),
      approveNotification: vi.fn().mockResolvedValue(
        { ok: false, resolution: 'interrupted', message: 'did not finish cleanly', action: 'ship_task' }),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={onBack} />);

    const approve = await screen.findByRole('button', { name: /Approve & ship/ });
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(approve);
    await screen.findByText('did not finish cleanly');
    expect(onBack).not.toHaveBeenCalled();
  });

  it('review mode: approving refetches the task so the Shipped pill replaces the toolbar after "Stay here"', async () => {
    const resolved = { ...OPEN_COMMENT, resolved: true };
    const bridge = mockBridge({
      listTasksForThread: vi.fn()
        .mockResolvedValueOnce([{ id: 'task-1', seq: 1, name: 'Fix typos', branchName: 'jack/fix' }])
        .mockResolvedValue([{ id: 'task-1', seq: 1, name: 'Fix typos', branchName: 'jack/fix', status: 'IN_REVIEW' }]),
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([resolved]),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    const approve = await screen.findByRole('button', { name: /Approve & ship/ });
    await waitFor(() => expect((approve as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(approve);

    // Approve succeeds → the confirmation dialog appears; dismiss with "Stay here".
    fireEvent.click(await screen.findByRole('button', { name: 'Stay here' }));

    // The toolbar's Approve button is gone (review mode ended) — the task
    // refetch must have already landed so the Shipped pill takes its place
    // instead of leaving an empty toolbar.
    await waitFor(() => expect(bridge.listTasksForThread).toHaveBeenCalledTimes(2));
    expect(await screen.findByTitle('This task has been shipped')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve & ship/ })).toBeNull();
  });

  it('mark-ready gate: still renders after the notification is merely read, not resolved', async () => {
    // Viewing a mark_ready gate (e.g. glancing at it from the inbox) flips
    // its status UNREAD -> READ but does not resolve it — the gate must
    // keep showing here so the user still has a way to act on it.
    const READ_MARK_READY: NotificationDto = {
      id: 'notif-2',
      kind: 'AWAITING_REVIEW',
      threadId: 'thread-1',
      taskId: 'task-1',
      status: 'READ',
      payloadJson: JSON.stringify({
        action: 'mark_ready',
        pr: { owner: 'acme', repo: 'widgets', number: 30 },
      }),
      createdAt: '2026-06-20T10:00:00Z',
      readAt: '2026-06-20T10:05:00Z',
    };
    mockBridge({ listNotificationsForThread: vi.fn().mockResolvedValue([READ_MARK_READY]) });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    fireEvent.click(await screen.findByRole('tab', { name: 'Pull request' }));
    expect(await screen.findByText('Ready for review')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'acme/widgets#30 ↗' })).toBeTruthy();
  });

  it('review mode: "Submit review" calls submitReview', async () => {
    const bridge = mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([OPEN_COMMENT]),
    });
    render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Submit review' }));
    await waitFor(() => expect(bridge.submitReview).toHaveBeenCalledWith('task-1'));
  });

  it('review mode: clicking a diff line and saving calls addReviewComment', async () => {
    const bridge = mockBridge({
      listNotificationsForThread: vi.fn().mockResolvedValue([SHIP_PROPOSAL]),
      listReviewComments: vi.fn().mockResolvedValue([]),
    });
    const { container } = render(<TaskCodePage threadId="thread-1" taskId="task-1" onBack={() => {}} />);

    // Wait for review mode + the diff to render, then click a commentable
    // new-side row (the added line in CUMULATIVE anchors RIGHT:1). The diff
    // is the default Code tab; review mode is signalled by the Approve button.
    await screen.findByRole('button', { name: /Approve & ship/ });
    const row = await waitFor(() => {
      const el = container.querySelector('.diff-row--add.diff-row--commentable');
      if (!el) throw new Error('no commentable row yet');
      return el;
    });
    fireEvent.click(row);

    // Composer opens; type a body and save.
    const textarea = await screen.findByPlaceholderText(/Leave a review comment/);
    fireEvent.change(textarea, { target: { value: 'nit: rename' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // The added line `+new line` is new-side line 2 in the patch.
    await waitFor(() => expect(bridge.addReviewComment).toHaveBeenCalledWith('task-1', 'src/Foo.ts', 2, 'nit: rename'));
  });
});
