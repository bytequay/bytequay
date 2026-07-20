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
import { ShipReviewPrompt } from './ShipReviewPrompt';
import { usePendingShipProposal } from './usePendingShipProposal';

afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

describe('ShipReviewPrompt', () => {
  it('explains the parked review and fires onReview', () => {
    const onReview = vi.fn();
    render(<ShipReviewPrompt onReview={onReview} />);
    expect(screen.getByText('Ready for review')).toBeTruthy();
    fireEvent.click(screen.getByText('changes'));
    expect(onReview).toHaveBeenCalledOnce();
  });

  it('fires the inline gate actions when provided', () => {
    const onApprove = vi.fn();
    const onReviewChanges = vi.fn();
    render(
      <ShipReviewPrompt
        onReview={vi.fn()}
        onApprove={onApprove}
        onReviewChanges={onReviewChanges}
      />,
    );
    fireEvent.click(screen.getByText('Approve & ship'));
    fireEvent.click(screen.getByText('Review changes'));
    expect(onApprove).toHaveBeenCalledOnce();
    expect(onReviewChanges).toHaveBeenCalledOnce();
  });

  it('disables the gate buttons and surfaces the note while busy', () => {
    render(
      <ShipReviewPrompt onReview={vi.fn()} onApprove={vi.fn()} busy note="boom" />,
    );
    expect((screen.getByText('Shipping…').closest('button') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('boom')).toBeTruthy();
  });
});

function notif(over: Record<string, unknown> = {}) {
  return {
    id: 'n1', kind: 'AWAITING_REVIEW', taskId: 'task-1', status: 'UNREAD',
    payloadJson: JSON.stringify({ action: 'ship_task' }), ...over,
  };
}

function Harness({ threadId = 't1', taskId = 'task-1' }: { threadId?: string; taskId?: string }) {
  const { proposal } = usePendingShipProposal(threadId, taskId);
  return <div>{proposal !== null ? `proposal:${proposal.id}` : 'none'}</div>;
}

describe('usePendingShipProposal', () => {
  it('detects a parked ship_task proposal for the task', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      listNotificationsForThread: vi.fn().mockResolvedValue([notif()]),
    };
    render(<Harness />);
    await waitFor(() => expect(screen.getByText('proposal:n1')).toBeTruthy());
  });

  it('ignores notifications for other tasks, kinds, or actions', async () => {
    (window as unknown as { bridge: unknown }).bridge = {
      listNotificationsForThread: vi.fn().mockResolvedValue([
        notif({ id: 'a', taskId: 'other' }),
        notif({ id: 'b', kind: 'INFO' }),
        notif({ id: 'c', payloadJson: JSON.stringify({ action: 'open_pr' }) }),
        notif({ id: 'd', status: 'RESOLVED' }),
      ]),
    };
    render(<Harness />);
    // Give the effect a tick; the only matches are filtered out.
    await waitFor(() => expect(screen.getByText('none')).toBeTruthy());
  });
});
