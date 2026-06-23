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
import { PendingApprovalToast } from './PendingApprovalToast';
import type { NotificationDto } from '../types';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

function pushNotif(status: NotificationDto['status'] = 'UNREAD'): NotificationDto {
  return {
    id: 'notif-push-1', kind: 'AWAITING_REVIEW', threadId: 'thread-1', taskId: 'task-1',
    status, createdAt: '2026-06-23T10:00:00Z', readAt: null,
    payloadJson: JSON.stringify({
      action: 'push', branch: 'feature/x', baseBranch: 'main',
      worktreePath: '/tmp/wt', diff: '@@ -1 +1 @@\n-a\n+b\n', source: 'mcp:push',
    }),
  };
}

function mockBridge(list: NotificationDto[]) {
  (window as unknown as { bridge: unknown }).bridge = {
    listNotificationsForThread: vi.fn().mockResolvedValue(list),
  };
}

describe('PendingApprovalToast', () => {
  it('shows the banner for a pending push and reveals the gate to approve', async () => {
    mockBridge([pushNotif()]);
    render(<PendingApprovalToast threadId="thread-1" />);

    expect(await screen.findByText(/waiting for your approval to/)).toBeTruthy();
    // The action label reads from the payload.
    expect(screen.getByText(/push to the remote/)).toBeTruthy();
    // Clicking reveals the shared publish gate (its Approve control).
    fireEvent.click(screen.getByRole('button', { name: /Review & approve/ }));
    expect(await screen.findByRole('button', { name: /Approve/ })).toBeTruthy();
  });

  it('renders nothing when there is no pending proposal', async () => {
    mockBridge([]);
    const { container } = render(<PendingApprovalToast threadId="thread-1" />);
    // Give the async fetch a tick; the toast stays empty.
    await waitFor(() => expect(window.bridge.listNotificationsForThread).toHaveBeenCalled());
    expect(container.querySelector('.approval-toast')).toBeNull();
  });

  it('renders nothing (no crash) when the bridge lacks the list method', () => {
    (window as unknown as { bridge: unknown }).bridge = {};
    const { container } = render(<PendingApprovalToast threadId="thread-1" />);
    expect(container.querySelector('.approval-toast')).toBeNull();
  });
});
