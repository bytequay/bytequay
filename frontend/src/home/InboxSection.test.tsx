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

afterEach(() => { cleanup(); Reflect.deleteProperty(window, 'bridge'); });

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
  it('opens and marks a plain AWAITING_REVIEW notification read', async () => {
    const bridge = mockBridge([
      notif({ payload: { repoFullName: 'chenjian2664/ByteQuay', prNumber: 29 } }),
    ]);
    const onOpenPr = vi.fn();
    render(
      <InboxSection prs={[]} onOpenPr={onOpenPr} onSeeAll={() => {}} onPrsChanged={() => {}} />,
    );
    fireEvent.click(await screen.findByText('Awaiting your review'));
    await waitFor(() => expect(bridge.markNotificationRead).toHaveBeenCalledWith('n1'));
    expect(onOpenPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
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
