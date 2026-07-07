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
import { TodayGroupRows } from './RecentList';
import type { PullRequestDto } from '../../types';

afterEach(cleanup);

function pr(over: Partial<PullRequestDto>): PullRequestDto {
  return { id: 1, repo: 'acme/widget', number: 1, title: 'A PR', ...over } as PullRequestDto;
}

describe('TodayGroupRows', () => {
  it('renders one row per PR, not just the first', () => {
    render(
      <TodayGroupRows
        label="Working on"
        prs={[pr({ id: 1, number: 1, title: 'First' }), pr({ id: 2, number: 2, title: 'Second' })]}
        onOpen={() => {}}
      />);

    expect(screen.getByText('First #1')).toBeTruthy();
    expect(screen.getByText('Second #2')).toBeTruthy();
  });

  it('shows the group label only once, on the first row', () => {
    render(
      <TodayGroupRows
        label="Reviewed"
        prs={[pr({ id: 1, number: 1 }), pr({ id: 2, number: 2 }), pr({ id: 3, number: 3 })]}
        onOpen={() => {}}
      />);

    expect(screen.getAllByText('Reviewed')).toHaveLength(1);
  });

  it('caps the rendered rows so one very busy day can\'t take over the sidebar', () => {
    const prs = Array.from({ length: 12 }, (_, i) => pr({ id: i, number: i, title: `PR ${i}` }));

    render(<TodayGroupRows label="Merged" prs={prs} onOpen={() => {}} />);

    expect(screen.getAllByRole('button')).toHaveLength(5);
  });

  it('opens the clicked PR, not just the first', () => {
    const onOpen = vi.fn();
    const second = pr({ id: 2, number: 2, title: 'Second' });
    render(
      <TodayGroupRows label="Working on" prs={[pr({ id: 1, number: 1, title: 'First' }), second]} onOpen={onOpen} />);

    fireEvent.click(screen.getByText('Second #2'));

    expect(onOpen).toHaveBeenCalledWith(second);
  });
});
