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
  it('offers View PR + the PR title on an Awaiting-your-review publish gate', () => {
    const handlers = makeHandlers({ prTitle: () => 'Add the cost-meter card' });
    const item = notificationToInboxItem(notif({ payload: MERGE_GATE }));
    render(<InboxCard item={item} handlers={handlers} />);
    fireEvent.click(screen.getByText('Awaiting your review'));
    expect(screen.getByText('Add the cost-meter card')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'View PR' }));
    expect(handlers.openPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
  });

  it('expands an audit row on click, surfacing View PR, and marks it read', () => {
    const handlers = makeHandlers();
    // An "Approved" audit row carries the PR as a nested object, same as the gate.
    const item = notificationToInboxItem(notif({
      kind: 'AUTO_FIX_DONE',
      payload: { publishResolution: 'approved', action: 'merge_pr', message: 'Marked #29 ready', pr: MERGE_GATE.pr },
    }));
    render(<InboxCard item={item} handlers={handlers} />);
    // Before the click the detail is collapsed.
    expect(screen.queryByRole('button', { name: 'View PR' })).toBeNull();
    fireEvent.click(screen.getByText('Approved'));
    // It expands (View PR shows) AND engagement is reported so the row clears.
    fireEvent.click(screen.getByRole('button', { name: 'View PR' }));
    expect(handlers.openPr).toHaveBeenCalledWith('chenjian2664', 'ByteQuay', 29);
    expect(handlers.opened).toHaveBeenCalledOnce();
  });
});
