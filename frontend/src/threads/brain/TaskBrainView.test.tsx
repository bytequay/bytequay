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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TaskBrainView from './TaskBrainView';

// Freeze the wall clock so both the mock hook (which anchors fixture
// timestamps to Date.now()) and the page's relative-time rendering see
// the same instant — making "14 minutes ago" / "now" deterministic.
const FROZEN = Date.parse('2026-06-20T12:00:00.000Z');

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(FROZEN); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function renderView(over: Partial<Parameters<typeof TaskBrainView>[0]> = {}) {
  return render(
    <TaskBrainView
      taskId="task-2"
      threadId="thread-1"
      onBack={() => {}}
      onOpenThread={() => {}}
      {...over}
    />,
  );
}

describe('TaskBrainView', () => {
  it('renders the three zones and the mocked cost-meter task', () => {
    renderView();
    // Identity bar
    expect(screen.getByText('● TASK 2')).toBeTruthy();
    expect(screen.getByText('Cost-meter widget · workspace sidebar')).toBeTruthy();
    // Aggregate strip
    expect(screen.getByText('$1.47')).toBeTruthy();
    expect(screen.getByText('CI FIX RUNNING')).toBeTruthy();
    // Brain feed: user question + brain reply + time dividers
    expect(screen.getByText('Are all the changes covered by tests?')).toBeTruthy();
    expect(screen.getByText('14 minutes ago')).toBeTruthy();
    expect(screen.getAllByText('now').length).toBeGreaterThan(0);
    // Right rail
    expect(screen.getByText('Approval needed')).toBeTruthy();
    expect(screen.getByText('#5680 · jack/cost-meter')).toBeTruthy();
  });

  it('lays out the body as a 252 / fluid / 308 grid', () => {
    const { container } = renderView();
    const body = container.querySelector('.tbv-body') as HTMLElement;
    const cols = body.style.gridTemplateColumns;
    expect(cols).toContain('252px');
    expect(cols).toContain('minmax(0, 1fr)');
    expect(cols).toContain('308px');
  });

  it('matches the snapshot for the mock fixture', () => {
    const { container } = renderView();
    expect(container).toMatchSnapshot();
  });

  describe('accessibility', () => {
    it('renders stage tag chips as links', () => {
      renderView();
      // Each feed row with a stage carries a role=link chip.
      expect(screen.getAllByRole('link').length).toBeGreaterThanOrEqual(5);
    });

    it('gives the merge button aria-disabled when the PR is not mergeable', () => {
      const { container } = renderView();
      const merge = container.querySelector('.merge-btn') as HTMLButtonElement;
      expect(merge.getAttribute('aria-disabled')).toBe('true');
    });

    it('gives the key controls accessible names', () => {
      renderView();
      expect(screen.getByRole('button', { name: 'Back' })).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Send' })).toBeTruthy();
      expect(screen.getByRole('button', { name: 'More actions' })).toBeTruthy();
    });
  });

  it('drills into a stage when a stage tag is clicked', () => {
    const onOpenStage = vi.fn();
    renderView({ onOpenStage });
    fireEvent.click(screen.getAllByRole('link')[0]);
    expect(onOpenStage).toHaveBeenCalledTimes(1);
  });

  it('opens the linked PR from the identity bar', () => {
    const onOpenPr = vi.fn();
    renderView({ onOpenPr });
    fireEvent.click(screen.getByRole('button', { name: /^PR #5680/ }));
    expect(onOpenPr).toHaveBeenCalledWith('trinodb', 'trino', 5680);
  });
});
