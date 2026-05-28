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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PublishGatePane from './PublishGatePane';
import type { Bridge, NotificationDto, PublishResultDto } from './types';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('PublishGatePane', () => {
  it('renders the unified diff and approves a push without sending editedBody', async () => {
    const approveNotification = vi.fn(async () => approvedResult('push'));
    installBridge({ approveNotification });
    const onResolved = vi.fn();

    render(
      <PublishGatePane
        notification={pushNotification({
          diff: 'diff --git a/foo.ts b/foo.ts\n@@ -1,1 +1,2 @@\n+added line\n existing line\n',
          diffBase: 'origin/main',
        })}
        onResolved={onResolved}
      />);

    // Diff lines render as separate divs inside the <pre> so the +/-
    // colouring lands on each one.
    expect(screen.getByLabelText('proposed-diff').textContent).toContain('+added line');
    // No editable textarea for push.
    expect(screen.queryByLabelText('comment-body')).toBeNull();

    await act(async () => {
      fireEvent.click(screen.getByText('Approve & push'));
    });

    expect(approveNotification).toHaveBeenCalledWith('notif-push-1', null, 'push');
    await waitFor(() => expect(onResolved).toHaveBeenCalled());
  });

  it('renders a missing-diff explanation when the parked payload has diffError', async () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={pushNotification({
          diff: null,
          diffError: 'no base ref available to diff against',
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/Couldn't compute a diff/i).textContent)
        .toContain('no base ref available to diff against');
    // Even without a diff the buttons remain — the audit row matters
    // more than the preview.
    expect(screen.getByText('Approve & push')).toBeTruthy();
  });

  it('renders an empty diff as no changes rather than a preview error', () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={pushNotification({ diff: '' })}
        onResolved={() => {}}
      />);

    expect(screen.getByText('No changes to show.')).toBeTruthy();
    expect(screen.queryByText(/Couldn't compute a diff/i)).toBeNull();
  });

  it('treats a malformed diff field as an unavailable preview', () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={awaitingReview({
          payloadJson: JSON.stringify({
            action: 'push',
            branch: 'feature/x',
            worktreePath: '/tmp/wt/x',
            diff: 0,
            source: 'mcp:push',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/Couldn't compute a diff/i)).toBeTruthy();
  });

  it('approves a post_comment with the edited body', async () => {
    const approveNotification = vi.fn(async () => approvedResult('post_comment'));
    installBridge({ approveNotification });
    const onResolved = vi.fn();

    render(
      <PublishGatePane
        notification={postCommentNotification('LGTM, ship it.')}
        onResolved={onResolved}
      />);

    const textarea = screen.getByLabelText('comment-body') as HTMLTextAreaElement;
    expect(textarea.value).toBe('LGTM, ship it.');

    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'LGTM, ship after CI is green.' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByText('Post comment'));
    });

    expect(approveNotification).toHaveBeenCalledWith(
        'notif-comment-1', 'LGTM, ship after CI is green.', 'post_comment');
    await waitFor(() => expect(onResolved).toHaveBeenCalled());
  });

  it('disables the approve button when the comment body is blanked out', async () => {
    installBridge({});

    render(
      <PublishGatePane
        notification={postCommentNotification('Body')}
        onResolved={() => {}}
      />);

    const textarea = screen.getByLabelText('comment-body') as HTMLTextAreaElement;
    await act(async () => {
      fireEvent.change(textarea, { target: { value: '   ' } });
    });

    const approve = screen.getByText('Post comment') as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
  });

  it('discards a parked push without invoking approve', async () => {
    const approveNotification = vi.fn(async () => approvedResult('push'));
    const discardNotification = vi.fn(async () => discardedResult('push'));
    installBridge({ approveNotification, discardNotification });
    const onResolved = vi.fn();

    render(
      <PublishGatePane
        notification={pushNotification({ diff: 'diff --git a/x b/x\n' })}
        onResolved={onResolved}
      />);

    await act(async () => {
      fireEvent.click(screen.getByText('Discard'));
    });

    expect(discardNotification).toHaveBeenCalledWith('notif-push-1', 'push');
    expect(approveNotification).not.toHaveBeenCalled();
    await waitFor(() => expect(onResolved).toHaveBeenCalled());
  });

  it('shows an interrupted response as a local-only recovery action', async () => {
    const approveNotification = vi.fn(async (): Promise<PublishResultDto> => ({
      ok: false,
      resolution: 'interrupted',
      message: 'Check remote state, then choose Finish locally.',
      action: 'push',
    }));
    installBridge({ approveNotification });
    const onResolved = vi.fn();

    render(
      <PublishGatePane
        notification={pushNotification({ diff: 'diff --git a/x b/x\n' })}
        onResolved={onResolved}
      />);

    await act(async () => {
      fireEvent.click(screen.getByText('Approve & push'));
    });

    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain('Check remote state');
    });
    expect(screen.getByText('Finish locally')).toBeTruthy();
    // An interrupted attempt stays surfaced for local-only resolution.
    expect(onResolved).not.toHaveBeenCalled();
  });

  it('accepts request_review locally without sending an edited body', async () => {
    const approveNotification = vi.fn(async () => approvedResult('request_review'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-review-1',
          payloadJson: JSON.stringify({
            action: 'request_review',
            summary: 'Ready for a human pass.',
            draftReply: 'Please take a look.',
            source: 'mcp:request_review',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText('Ready for a human pass.')).toBeTruthy();
    expect(screen.getByText(/without publishing remotely/i)).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByText('Accept review'));
    });

    expect(approveNotification).toHaveBeenCalledWith('notif-review-1', null, 'request_review');
  });

  it('accepts legacy request_review payloads that predate action tagging', () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={awaitingReview({
          payloadJson: JSON.stringify({
            summary: 'Historical parked work.',
            source: 'mcp:request_review',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText('Historical parked work.')).toBeTruthy();
    expect(screen.getByText('Accept review')).toBeTruthy();
  });

  it('shows a gated next_task proposal before advancing', async () => {
    const approveNotification = vi.fn(async () => approvedResult('next_task'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-next-1',
          payloadJson: JSON.stringify({
            action: 'next_task',
            branch: 'feature/current',
            baseBranch: 'main',
            worktreePath: '/tmp/wt/current',
            nextTitle: 'Follow-up',
            baseMode: 'main',
            diff: 'diff --git a/x b/x\n+done\n',
            source: 'mcp:next_task',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByLabelText('proposed-diff').textContent).toContain('+done');
    await act(async () => {
      fireEvent.click(screen.getByText('Approve & start next'));
    });

    expect(approveNotification).toHaveBeenCalledWith('notif-next-1', null, 'next_task');
  });

  it('shows a gated ship_task proposal before closing the task', async () => {
    const approveNotification = vi.fn(async () => approvedResult('ship_task'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-ship-1',
          payloadJson: JSON.stringify({
            action: 'ship_task',
            branch: 'feature/finished',
            baseBranch: 'main',
            worktreePath: '/tmp/wt/finished',
            nextTitle: 'After ship',
            baseMode: 'main',
            diff: 'diff --git a/x b/x\n+shipped\n',
            source: 'mcp:ship_task',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/Ship and close/i)).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByText('Approve & ship'));
    });

    expect(approveNotification).toHaveBeenCalledWith('notif-ship-1', null, 'ship_task');
  });

  it('surfaces interrupted approvals as local-only finalization', async () => {
    const approveNotification = vi.fn(async () => approvedResult('push'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={pushNotification({ diff: 'diff --git a/x b/x\n' }, 'RESOLVING')}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/will not repeat the publish action/i)).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByText('Finish locally'));
    });
    expect(approveNotification).toHaveBeenCalledWith('notif-push-1', null, 'push');
  });

  it('renders a graceful fallback for unrecognised payloads', () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-unknown',
          payloadJson: JSON.stringify({ action: 'unknown', summary: 'Done' }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/doesn't carry a supported review payload/i)).toBeTruthy();
  });

  it('renders a generic review card for merge_pr and approves on click', async () => {
    const approveNotification = vi.fn(async () => approvedResult('merge_pr'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-merge-1',
          payloadJson: JSON.stringify({
            action: 'merge_pr',
            pr: { owner: 'acme', repo: 'widget', number: 7 },
            strategy: 'squash',
            source: 'mcp:merge_pr',
          }),
        })}
        onResolved={() => {}}
      />);

    // The action label renders inside the summary header; the Approve
    // button has a distinct "Approve & merge" label.
    expect(screen.getByText('Merge PR')).toBeTruthy();
    expect(screen.getByText('acme/widget#7')).toBeTruthy();
    // merge_pr has no editable body — no textarea should render.
    expect(screen.queryByLabelText(/merge_pr-body/)).toBeNull();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Approve & merge' }));
    });
    expect(approveNotification).toHaveBeenCalledWith('notif-merge-1', null, 'merge_pr');
  });

  it('exposes an editable body for approve_pr and forwards the user edit', async () => {
    const approveNotification = vi.fn(async () => approvedResult('approve_pr'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-approve-1',
          payloadJson: JSON.stringify({
            action: 'approve_pr',
            body: 'Looks good',
            pr: { owner: 'acme', repo: 'widget', number: 12 },
            source: 'mcp:approve_pr',
          }),
        })}
        onResolved={() => {}}
      />);

    const textarea = screen.getByLabelText('approve_pr-body') as HTMLTextAreaElement;
    expect(textarea.value).toBe('Looks good');
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'Looks good — ship after CI.' } });
    });
    await act(async () => {
      // labelForAction returns "Approve PR" for approve_pr — that
      // collides with the action-label text in the summary header.
      // Match by button role specifically.
      fireEvent.click(screen.getByRole('button', { name: 'Approve PR' }));
    });
    expect(approveNotification).toHaveBeenCalledWith(
        'notif-approve-1', 'Looks good — ship after CI.', 'approve_pr');
  });

  it('forwards an explicit blank for approve_pr when the user clears the textarea', async () => {
    // The user's intent of "approve with no comment" must reach the
    // backend as an empty string, not get silently replaced with the
    // parked body. The bodyForApprove path sends editedBody verbatim
    // so the backend can distinguish null (no override) from "".
    const approveNotification = vi.fn(async () => approvedResult('approve_pr'));
    installBridge({ approveNotification });

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-approve-2',
          payloadJson: JSON.stringify({
            action: 'approve_pr',
            body: 'Looks good',
            pr: { owner: 'acme', repo: 'widget', number: 13 },
            source: 'mcp:approve_pr',
          }),
        })}
        onResolved={() => {}}
      />);

    const textarea = screen.getByLabelText('approve_pr-body') as HTMLTextAreaElement;
    await act(async () => {
      fireEvent.change(textarea, { target: { value: '' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Approve PR' }));
    });
    expect(approveNotification).toHaveBeenCalledWith('notif-approve-2', '', 'approve_pr');
  });

  it('renders title + head→base preview for an open_pr proposal', () => {
    installBridge({});

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-open-1',
          payloadJson: JSON.stringify({
            action: 'open_pr',
            title: 'Add cache layer',
            head: 'feature/cache',
            base: 'main',
            body: 'Initial cache implementation.',
            repo: { owner: 'acme', repo: 'widget' },
            source: 'mcp:open_pr',
          }),
        })}
        onResolved={() => {}}
      />);

    // Action label appears in BOTH the summary header and the
    // primary button — assert there are at least two matches.
    expect(screen.getAllByText('Open PR').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('acme/widget')).toBeTruthy();
    // open_pr's title + head/base preview is rendered as its own line.
    expect(screen.getByText(/Add cache layer/)).toBeTruthy();
    expect(screen.getByText('feature/cache')).toBeTruthy();
    expect(screen.getByText('main')).toBeTruthy();
    // open_pr exposes the body for the user to edit before publishing.
    const textarea = screen.getByLabelText('open_pr-body') as HTMLTextAreaElement;
    expect(textarea.value).toBe('Initial cache implementation.');
  });

  it('surfaces the file/line anchor for a create_review_comment proposal', () => {
    // The reviewer must see where the inline comment lands before
    // authorizing the publish — not just the PR ref and the body.
    installBridge({});

    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-inline-1',
          payloadJson: JSON.stringify({
            action: 'create_review_comment',
            body: 'This branch is unreachable.',
            pr: { owner: 'acme', repo: 'widget', number: 9 },
            filePath: 'src/main/Foo.java',
            line: 42,
            side: 'RIGHT',
            commitId: 'abc123',
            source: 'mcp:create_review_comment',
          }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText('src/main/Foo.java')).toBeTruthy();
    expect(screen.getByText(/:42/)).toBeTruthy();
    expect(screen.getByText(/RIGHT/)).toBeTruthy();
    // The body stays editable.
    const textarea = screen.getByLabelText('create_review_comment-body') as HTMLTextAreaElement;
    expect(textarea.value).toBe('This branch is unreachable.');
  });
});

function approvedResult(action: string): PublishResultDto {
  return {
    ok: true,
    resolution: 'approved',
    message: action === 'push' ? 'Pushed feature/x.' : 'Approved.',
    action,
  };
}

function discardedResult(action: string): PublishResultDto {
  return { ok: true, resolution: 'discarded', message: 'Discarded.', action };
}

function pushNotification(overrides: {
  diff: string | null;
  diffBase?: string;
  diffError?: string;
}, status: NotificationDto['status'] = 'UNREAD'): NotificationDto {
  const payload = {
    action: 'push',
    branch: 'feature/x',
    baseBranch: 'main',
    worktreePath: '/tmp/wt/feature-x',
    ...overrides,
    source: 'mcp:push',
  };
  return awaitingReview({
    id: 'notif-push-1',
    status,
    payloadJson: JSON.stringify(payload),
  });
}

function postCommentNotification(body: string): NotificationDto {
  const payload = {
    action: 'post_comment',
    body,
    pr: { owner: 'acme', repo: 'widget', number: 42 },
    source: 'mcp:post_comment',
  };
  return awaitingReview({
    id: 'notif-comment-1',
    payloadJson: JSON.stringify(payload),
  });
}

function awaitingReview(overrides: Partial<NotificationDto>): NotificationDto {
  return {
    id: 'notif-x',
    kind: 'AWAITING_REVIEW',
    threadId: 'thread-1',
    taskId: 'task-1',
    status: 'UNREAD',
    payloadJson: '{}',
    createdAt: '2026-05-22T12:00:00Z',
    readAt: null,
    ...overrides,
  };
}

function installBridge(overrides: Partial<Bridge>) {
  const defaults = {
    approveNotification: vi.fn(async () => approvedResult('push')),
    discardNotification: vi.fn(async () => discardedResult('push')),
  } as unknown as Pick<Bridge, 'approveNotification' | 'discardNotification'>;
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    ...defaults,
    ...overrides,
  };
}
