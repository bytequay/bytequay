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
import { LinkedPRCard } from './LinkedPRCard';
import type { LinkedPrDto } from '../../types/brainView';

afterEach(cleanup);

function makePr(over: Partial<LinkedPrDto> = {}): LinkedPrDto {
  return {
    number: 5680,
    branch: 'jack/cost-meter',
    status: 'draft',
    ciStatus: 'failing',
    ciSummary: '1 failing',
    reviewersApproved: 2,
    reviewersTotal: 3,
    conflictsState: 'none',
    mergeable: false,
    ...over,
  };
}

describe('LinkedPRCard merge button', () => {
  it('is disabled and aria-disabled when the PR is not mergeable', () => {
    const onMerge = vi.fn();
    const { container } = render(<LinkedPRCard pr={makePr({ mergeable: false })} onMerge={onMerge} />);
    const btn = container.querySelector('.merge-btn') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect(btn.classList.contains('ready')).toBe(false);
    fireEvent.click(btn);
    expect(onMerge).not.toHaveBeenCalled();
  });

  it('is enabled, green (ready), and fires onMerge when mergeable', () => {
    const onMerge = vi.fn();
    const { container } = render(<LinkedPRCard pr={makePr({ mergeable: true })} onMerge={onMerge} />);
    const btn = container.querySelector('.merge-btn') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute('aria-disabled')).toBe('false');
    expect(btn.classList.contains('ready')).toBe(true);
    fireEvent.click(btn);
    expect(onMerge).toHaveBeenCalledTimes(1);
  });

  it('renders the status rows', () => {
    render(<LinkedPRCard pr={makePr()} onMerge={() => {}} />);
    expect(screen.getByText('#5680 · jack/cost-meter')).toBeTruthy();
    expect(screen.getByText('2/3 approved')).toBeTruthy();
    expect(screen.getByText('⚠ 1 failing')).toBeTruthy();
  });
});
