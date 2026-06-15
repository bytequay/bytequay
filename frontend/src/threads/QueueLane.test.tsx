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
import type { QueuedTaskDto } from '../types';
import { QueueLane } from './QueueLane';

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
});

function entry(overrides: Partial<QueuedTaskDto>): QueuedTaskDto {
  return {
    position: 1,
    title: 'entry',
    branchBase: 'MAIN',
    initialPrompt: null,
    status: 'PENDING',
    materializedTaskId: null,
    createdAt: '2026-06-15T12:00:00Z',
    ...overrides,
  };
}

const queue: QueuedTaskDto[] = [
  entry({ position: 1, title: 'head', status: 'MATERIALIZED', materializedTaskId: 't1.k1' }),
  entry({ position: 2, title: 'second', status: 'PENDING' }),
  entry({ position: 3, title: 'third', status: 'PENDING', branchBase: 'STACKED_ON_PREVIOUS' }),
];

describe('QueueLane', () => {
  it('renders nothing when there are no live entries', () => {
    const { container } = render(
      <QueueLane threadId="t1" queue={[]} parallelSlots={1} slotsInUse={0} onChanged={() => {}} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders pills: QUEUED for the materialized head, PENDING for the rest', () => {
    render(
      <QueueLane threadId="t1" queue={queue} parallelSlots={1} slotsInUse={1} onChanged={() => {}} />,
    );
    expect(screen.getByText('QUEUED · pos 1')).toBeTruthy();
    expect(screen.getByText('PENDING · pos 2')).toBeTruthy();
    expect(screen.getByText('stacked on previous')).toBeTruthy();
  });

  it('drops a PENDING entry through the bridge', async () => {
    const queueDrop = vi.fn().mockResolvedValue(entry({ position: 2, status: 'DROPPED' }));
    const onChanged = vi.fn();
    // @ts-expect-error partial bridge for the test
    window.bridge = { queueDrop };
    render(
      <QueueLane threadId="t1" queue={queue} parallelSlots={1} slotsInUse={1} onChanged={onChanged} />,
    );
    // The first PENDING entry (pos 2) — drop is the ✕ button.
    fireEvent.click(screen.getAllByTitle('Drop')[0]);
    await waitFor(() => expect(queueDrop).toHaveBeenCalledWith('t1', 2));
    expect(onChanged).toHaveBeenCalled();
  });

  it('reorders PENDING entries via the move-down control', async () => {
    const queueReorder = vi.fn().mockResolvedValue(queue);
    // @ts-expect-error partial bridge for the test
    window.bridge = { queueReorder };
    render(
      <QueueLane threadId="t1" queue={queue} parallelSlots={1} slotsInUse={1} onChanged={() => {}} />,
    );
    // Move the first PENDING (pos 2) down → desired PENDING order [3, 2].
    fireEvent.click(screen.getAllByTitle('Move down')[0]);
    await waitFor(() => expect(queueReorder).toHaveBeenCalledWith('t1', [3, 2]));
  });
});
