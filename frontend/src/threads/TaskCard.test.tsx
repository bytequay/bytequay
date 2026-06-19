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
import { TaskCard } from './ThreadTrunkPage';
import type { WorkUnitTaskDto } from '../types';

afterEach(cleanup);

function task(over: Partial<WorkUnitTaskDto> = {}): WorkUnitTaskDto {
  return {
    id: 'k1',
    seq: 1,
    name: null,
    branchName: 'dev/test',
    prNumber: null,
    prState: null,
    status: 'IDLE',
    phase: 'IMPLEMENTING',
    createdAt: new Date(Date.UTC(2026, 5, 14, 12, 0, 0)).toISOString(),
    ...over,
  } as unknown as WorkUnitTaskDto;
}

function renderCard() {
  const onSelect = vi.fn();
  const onOpen = vi.fn();
  render(
    <ul>
      <TaskCard
        task={task()}
        selected={false}
        isForeground={false}
        onSelect={onSelect}
        onOpen={onOpen}
      />
    </ul>,
  );
  return { onSelect, onOpen, card: screen.getByRole('button') };
}

describe('TaskCard click behavior', () => {
  it('single click selects without opening the task', () => {
    const { onSelect, onOpen, card } = renderCard();
    fireEvent.click(card);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('double click opens (and selects) the task', () => {
    const { onSelect, onOpen, card } = renderCard();
    fireEvent.doubleClick(card);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalled();
  });

  it('Enter opens, Space only selects', () => {
    const { onSelect, onOpen, card } = renderCard();
    fireEvent.keyDown(card, { key: 'Enter' });
    expect(onOpen).toHaveBeenCalledTimes(1);

    onOpen.mockClear();
    onSelect.mockClear();
    fireEvent.keyDown(card, { key: ' ' });
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onOpen).not.toHaveBeenCalled();
  });
});
