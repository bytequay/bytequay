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
import InboxCard, { type InboxHandlers } from './InboxCard';
import { notificationToInboxItem } from './inboxItems';
import type { NotificationDto } from '../types';

afterEach(cleanup);

function notif(over: Partial<NotificationDto> & { payload: object }): NotificationDto {
  const { payload, ...rest } = over;
  return {
    id: 'n1', threadId: null, taskId: null, kind: 'AWAITING_REVIEW', status: 'UNREAD',
    payloadJson: JSON.stringify(payload), createdAt: new Date().toISOString(), readAt: null,
    ...rest,
  } as NotificationDto;
}

function makeHandlers(over: Partial<InboxHandlers> = {}): InboxHandlers {
  return {
    openPr: vi.fn(), openTask: vi.fn(), dismiss: vi.fn(),
    approve: vi.fn().mockResolvedValue(undefined), resolved: vi.fn(), opened: vi.fn(), ...over,
  };
}

const MERGE_GATE = { action: 'merge_pr', pr: { owner: 'chenjian2664', repo: 'ByteQuay', number: 29 } };

describe('InboxCard', () => {
  it('opens a remote publish gate directly in global Reviews', () => {
    const handlers = makeHandlers({ openRemoteReview: vi.fn() });
    const item = notificationToInboxItem(notif({ payload: MERGE_GATE }));
    render(<InboxCard item={item} handlers={handlers} />);
    expect(screen.getByText('Open in Reviews')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Awaiting your review/ }));
    expect(handlers.openRemoteReview).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
  });

  it('opens an audit row in one click and reports engagement', () => {
    const handlers = makeHandlers();
    const item = notificationToInboxItem(notif({
      kind: 'AUTO_FIX_DONE',
      payload: { publishResolution: 'approved', action: 'merge_pr', message: 'Marked #29 ready', pr: MERGE_GATE.pr },
    }));
    render(<InboxCard item={item} handlers={handlers} />);
    fireEvent.click(screen.getByRole('button', { name: /Approved/ }));
    expect(handlers.openPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
    expect(handlers.opened).toHaveBeenCalledOnce();
  });

  it('prefers the owning workspace and renders its workspace chip', () => {
    const handlers = makeHandlers({
      openWorkspacePr: vi.fn(),
      workspaceForRepo: () => ({ workspaceId: 'ws-1', name: 'ByteQuay' }),
    });
    const item = notificationToInboxItem(notif({ payload: MERGE_GATE }));
    const { container } = render(<InboxCard item={item} handlers={handlers} />);
    expect(screen.getByText('ByteQuay')).toBeTruthy();
    const workspaceIcon = container.querySelector('.home-inbox-card__workspace-icon');
    expect(workspaceIcon?.getAttribute('src'))
      .toContain('github.com/chenjian2664.png');
    fireEvent.error(workspaceIcon as HTMLImageElement);
    expect(container.querySelector('.home-inbox-card__workspace-icon')?.tagName).toBe('svg');
    expect(screen.getByText('Review →')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Awaiting your review/ }));
    expect(handlers.openWorkspacePr).toHaveBeenCalledWith('ws-1', 29);
    expect(handlers.openPr).not.toHaveBeenCalled();
  });
});
