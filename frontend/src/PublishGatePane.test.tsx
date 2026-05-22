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

    expect(approveNotification).toHaveBeenCalledWith('notif-push-1', null);
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
        'notif-comment-1', 'LGTM, ship after CI is green.');
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

    expect(discardNotification).toHaveBeenCalledWith('notif-push-1');
    expect(approveNotification).not.toHaveBeenCalled();
    await waitFor(() => expect(onResolved).toHaveBeenCalled());
  });

  it('shows the backend message and keeps the row when approve returns ok=false', async () => {
    const approveNotification = vi.fn(async (): Promise<PublishResultDto> => ({
      ok: false,
      resolution: 'failed',
      message: 'rejected: non-fast-forward',
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
      expect(screen.getByRole('alert').textContent).toContain('rejected: non-fast-forward');
    });
    // Failure leaves the row parked so the user can retry.
    expect(onResolved).not.toHaveBeenCalled();
  });

  it('renders a graceful fallback for unrecognised payloads', () => {
    installBridge({});
    render(
      <PublishGatePane
        notification={awaitingReview({
          id: 'notif-request-review',
          payloadJson: JSON.stringify({ summary: 'Done', source: 'mcp:request_review' }),
        })}
        onResolved={() => {}}
      />);

    expect(screen.getByText(/doesn't carry a push or comment payload/i)).toBeTruthy();
  });
});

function approvedResult(action: 'push' | 'post_comment'): PublishResultDto {
  return {
    ok: true,
    resolution: 'approved',
    message: action === 'push' ? 'Pushed feature/x.' : 'Posted comment on owner/repo#1.',
    action,
  };
}

function discardedResult(action: 'push' | 'post_comment'): PublishResultDto {
  return { ok: true, resolution: 'discarded', message: 'Discarded.', action };
}

function pushNotification(overrides: {
  diff: string | null;
  diffBase?: string;
  diffError?: string;
}): NotificationDto {
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
