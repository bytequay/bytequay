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
import InboxSection from './InboxSection';
import type { NotificationDto } from '../types';

afterEach(() => {
  cleanup();
  localStorage.clear();
  Reflect.deleteProperty(window, 'bridge');
});

function notif(over: Partial<NotificationDto> & { payload: object }): NotificationDto {
  const { payload, ...rest } = over;
  return {
    id: 'n1', threadId: null, taskId: null, kind: 'AWAITING_REVIEW', status: 'UNREAD',
    payloadJson: JSON.stringify(payload), createdAt: new Date().toISOString(), readAt: null,
    ...rest,
  } as NotificationDto;
}

function mockBridge(notifications: NotificationDto[]) {
  const bridge = {
    listNotifications: vi.fn().mockResolvedValue(notifications),
    markNotificationRead: vi.fn().mockResolvedValue(undefined),
    dismissNotification: vi.fn().mockResolvedValue(undefined),
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

describe('InboxSection', () => {
  it('opens a plain AWAITING_REVIEW notification without acknowledging its live gate', async () => {
    const bridge = mockBridge([
      notif({ payload: { repoFullName: 'chenjian2664/ByteQuay', prNumber: 29 } }),
    ]);
    const onOpenPr = vi.fn();
    render(
      <InboxSection prs={[]} onOpenPr={onOpenPr} onSeeAll={() => {}} onPrsChanged={() => {}} />,
    );
    fireEvent.click(await screen.findByText('Awaiting your review'));
    expect(onOpenPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
    expect(bridge.markNotificationRead).not.toHaveBeenCalled();
  });

  it('acks an informational row in place without navigating', async () => {
    const bridge = mockBridge([
      notif({
        kind: 'AUTO_FIX_DONE',
        payload: {
          publishResolution: 'approved', action: 'push', message: 'Pushed the branch',
          pr: { owner: 'chenjian2664', repo: 'ByteQuay', number: 29, title: 'Inbox acknowledgement' },
        },
      }),
    ]);
    const onOpenPr = vi.fn();
    render(<InboxSection prs={[]} onOpenPr={onOpenPr} onPrsChanged={() => {}} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Ack' }));

    expect(bridge.markNotificationRead).toHaveBeenCalledWith('n1');
    expect(onOpenPr).not.toHaveBeenCalled();
    expect(screen.queryByText('Inbox acknowledgement')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'See all' }));
    expect(await screen.findByText('Acked')).toBeTruthy();
    expect(screen.getByText('Inbox acknowledgement').closest('.home-inbox-card')?.classList
      .contains('home-inbox-card--read')).toBe(true);
  });

  it('Ack all marks only FYI rows read and leaves live review requests unread', async () => {
    const bridge = mockBridge([
      notif({ id: 'fyi-1', kind: 'AUTO_FIX_DONE', payload: { message: 'Task done' } }),
      notif({ id: 'fyi-2', kind: 'PASSIVE', payload: { message: 'PR merged' } }),
      notif({
        id: 'review-1',
        kind: 'AWAITING_REVIEW',
        payload: { repoFullName: 'chenjian2664/ByteQuay', prNumber: 29 },
      }),
    ]);
    const { container } = render(
      <InboxSection prs={[]} onOpenPr={() => {}} onPrsChanged={() => {}} />,
    );
    await screen.findByText('Ack all');
    expect(container.querySelector('.home-inbox__badge')?.textContent).toBe('3');

    fireEvent.click(screen.getByRole('button', { name: 'Ack all' }));

    await waitFor(() => expect(bridge.markNotificationRead).toHaveBeenCalledTimes(2));
    expect(bridge.markNotificationRead).toHaveBeenCalledWith('fyi-1');
    expect(bridge.markNotificationRead).toHaveBeenCalledWith('fyi-2');
    expect(bridge.markNotificationRead).not.toHaveBeenCalledWith('review-1');
    expect(container.querySelector('.home-inbox__badge')?.textContent).toBe('1');
  });

  it('resolves a task notification to its repository logo', async () => {
    const bridge = mockBridge([
      notif({
        kind: 'AUTO_FIX_DONE',
        threadId: 'thread-1',
        taskId: 'task-1',
        payload: { message: 'Task done' },
      }),
    ]);
    Object.assign(bridge, {
      listTasksForThread: vi.fn().mockResolvedValue([{
        id: 'task-1', name: 'Task done', branchName: 'dev/task-done',
        workingDir: '/repos/ByteQuay',
      }]),
      listLocalRepos: vi.fn().mockResolvedValue([{
        owner: 'chenjian2664', repo: 'ByteQuay', localClonePath: '/repos/ByteQuay',
      }]),
      getRepoMeta: vi.fn().mockResolvedValue({
        ownerAvatarUrl: 'https://avatars.githubusercontent.com/u/1?v=4',
      }),
    });

    const { container } = render(
      <InboxSection prs={[]} onOpenPr={() => {}} onPrsChanged={() => {}} />,
    );

    await waitFor(() => expect(
      container.querySelector('.home-inbox-card__scope img')?.getAttribute('src'),
    ).toBe('https://avatars.githubusercontent.com/u/1?v=4'));
  });

  it('opens a publish gate without clearing its approval state', async () => {
    const bridge = mockBridge([
      notif({ payload: { action: 'merge_pr', pr: { owner: 'chenjian2664', repo: 'ByteQuay', number: 29 } } }),
    ]);
    const onOpenPr = vi.fn();
    render(
      <InboxSection prs={[]} onOpenPr={onOpenPr} onSeeAll={() => {}} onPrsChanged={() => {}} />,
    );
    fireEvent.click(await screen.findByText('Awaiting your review'));
    expect(onOpenPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
    expect(bridge.markNotificationRead).not.toHaveBeenCalled();
  });
});
