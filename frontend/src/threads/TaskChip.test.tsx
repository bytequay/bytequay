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
import type { TaskPhaseGroupDto } from '../types';
import { TaskChip } from './TaskChip';
import { ReviewChip, reviewPhaseLabel } from './ReviewChip';

afterEach(cleanup);

describe('TaskChip', () => {
  const groups: TaskPhaseGroupDto[] = ['IN_PROGRESS', 'AWAITING_YOU', 'IDLE', 'DONE'];

  it.each(groups)('renders the %s group label', (group) => {
    const labels: Record<TaskPhaseGroupDto, string> = {
      IN_PROGRESS: 'In progress',
      AWAITING_YOU: 'Awaiting you',
      IDLE: 'Idle',
      DONE: 'Done',
    };
    render(<TaskChip title="Fix the parser" group={group} onOpen={() => {}} />);
    expect(screen.getByText('Fix the parser')).toBeTruthy();
    expect(screen.getByText(labels[group])).toBeTruthy();
  });

  it('fires onOpen on click', () => {
    const onOpen = vi.fn();
    render(<TaskChip title="t" group="IN_PROGRESS" onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('button'));
    expect(onOpen).toHaveBeenCalledOnce();
  });
});

describe('ReviewChip phase label', () => {
  it('reads "DEBATE r 2 / 3" while running', () => {
    expect(reviewPhaseLabel('DEBATE', 2, 3)).toBe('DEBATE r 2 / 3');
  });

  it('reads "PUBLISHED · 5 AGREED" at terminal', () => {
    expect(reviewPhaseLabel('PUBLISHED', 0, 3, 5)).toBe('PUBLISHED · 5 AGREED');
  });

  it('renders the running label in the chip', () => {
    render(<ReviewChip phase="DEBATE" round={2} roundCap={3} onOpen={() => {}} />);
    expect(screen.getByText('DEBATE r 2 / 3')).toBeTruthy();
    expect(screen.getByText('Multi-agent review')).toBeTruthy();
  });
});
