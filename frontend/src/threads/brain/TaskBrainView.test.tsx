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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TaskBrainView from './TaskBrainView';
import { buildMockBrainView } from './brainViewFixture';

// Freeze the wall clock so both the mock hook (which anchors fixture
// timestamps to Date.now()) and the page's relative-time rendering see
// the same instant — making "14 minutes ago" / "now" deterministic.
const FROZEN = Date.parse('2026-06-20T12:00:00.000Z');

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(FROZEN); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

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
    // Aggregate strip + cost-breakdown card both surface the total.
    expect(screen.getAllByText('$1.47').length).toBeGreaterThan(0);
    expect(screen.getByText('CI FIX RUNNING')).toBeTruthy();
    // Cost breakdown card.
    expect(screen.getByText('Cost breakdown')).toBeTruthy();
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

  it('offers a panel review when the task is panelSpawnable and opens the seated panel', async () => {
    const view = buildMockBrainView(FROZEN);
    const spawnable = {
      ...view,
      rightRail: { ...view.rightRail, panelSpawnable: true, parentStageId: 'stage-parent' },
    };
    const getBrainView = vi.fn().mockResolvedValue(spawnable);
    const spawnReview = vi.fn().mockResolvedValue({
      reviewStageId: 'rs-1', reviewPassId: 'rp-1', reviewThreadId: 'rt-9',
    });
    (window as unknown as { bridge: unknown }).bridge = { getBrainView, spawnReview };
    const onOpenReviewThread = vi.fn();

    renderView({ onOpenReviewThread });

    const button = await screen.findByRole('button', { name: '⚖ Get a panel review' });
    fireEvent.click(button);

    expect(spawnReview).toHaveBeenCalledWith('stage-parent');
    await waitFor(() => expect(onOpenReviewThread).toHaveBeenCalledWith('rt-9'));
  });

  it('hides the panel-review affordance when the task is not panelSpawnable', () => {
    // The default fixture has panelSpawnable: false.
    renderView();
    expect(screen.queryByRole('button', { name: '⚖ Get a panel review' })).toBeNull();
  });

  it('posts a brain message and shows the optimistic YOU bubble', () => {
    const sendBrainMessage = vi.fn().mockResolvedValue({ turnId: 't', brainThreadId: 'b' });
    const getBrainView = vi.fn().mockRejectedValue(new Error('offline in test'));
    (window as unknown as { bridge: unknown }).bridge = { sendBrainMessage, getBrainView };

    renderView();
    fireEvent.change(screen.getByRole('textbox', { name: 'Message the brain agent' }), {
      target: { value: 'How many pushes have we done?' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(sendBrainMessage).toHaveBeenCalledWith('task-2', 'How many pushes have we done?');
    // Optimistic bubble appears immediately, before any round-trip.
    expect(screen.getByText('How many pushes have we done?')).toBeTruthy();
  });

  // ── lifecycle / action buttons (formerly silent stubs) ──────────────

  function brainWith(
    taskOver: Record<string, unknown> = {},
    railOver: Record<string, unknown> = {},
  ) {
    const v = buildMockBrainView(FROZEN);
    return {
      ...v,
      task: { ...v.task, id: 'task-9', ...taskOver },
      rightRail: { ...v.rightRail, ...railOver },
    };
  }

  it('wires the Pause button to the pause endpoint', async () => {
    const pauseTask = vi.fn().mockResolvedValue({});
    const getBrainView = vi.fn().mockResolvedValue(brainWith());
    (window as unknown as { bridge: unknown }).bridge = { getBrainView, pauseTask };

    renderView();
    fireEvent.click(await screen.findByRole('button', { name: '⏸ Pause task' }));

    await waitFor(() => expect(pauseTask).toHaveBeenCalledWith('thread-1', 'task-9'));
  });

  it('shows Resume on a paused task and wires it to the resume endpoint', async () => {
    const resumePausedTask = vi.fn().mockResolvedValue({});
    const getBrainView = vi.fn().mockResolvedValue(brainWith({ paused: true }));
    (window as unknown as { bridge: unknown }).bridge = { getBrainView, resumePausedTask };

    renderView();
    fireEvent.click(await screen.findByRole('button', { name: '▶ Resume task' }));

    await waitFor(() => expect(resumePausedTask).toHaveBeenCalledWith('thread-1', 'task-9'));
  });

  it('confirms before closing, then cancels the task and navigates back', async () => {
    const cancelTask = vi.fn().mockResolvedValue({});
    const getBrainView = vi.fn().mockResolvedValue(brainWith());
    (window as unknown as { bridge: unknown }).bridge = { getBrainView, cancelTask };
    const onBack = vi.fn();

    renderView({ onBack });
    fireEvent.click(await screen.findByRole('button', { name: '⏹ Close task' }));
    // Destructive → a confirm dialog gates the cancel.
    fireEvent.click(await screen.findByRole('button', { name: 'Close task' }));

    await waitFor(() => expect(cancelTask).toHaveBeenCalledWith('thread-1', 'task-9'));
    await waitFor(() => expect(onBack).toHaveBeenCalled());
  });

  it('routes the Merge button to the in-app PR page', async () => {
    const base = buildMockBrainView(FROZEN);
    const getBrainView = vi.fn().mockResolvedValue(
      brainWith({}, { linkedPr: { ...base.rightRail.linkedPr, mergeable: true } }));
    (window as unknown as { bridge: unknown }).bridge = { getBrainView };
    const onOpenPr = vi.fn();

    renderView({ onOpenPr });
    fireEvent.click(await screen.findByRole('button', { name: /Merge — finalize PR/ }));

    expect(onOpenPr).toHaveBeenCalledWith('trinodb', 'trino', 5680);
  });

  it('routes View code diff to the in-app PR page', async () => {
    const getBrainView = vi.fn().mockResolvedValue(brainWith());
    (window as unknown as { bridge: unknown }).bridge = { getBrainView };
    const onOpenPr = vi.fn();

    renderView({ onOpenPr });
    fireEvent.click(await screen.findByRole('button', { name: /View code diff/ }));

    expect(onOpenPr).toHaveBeenCalledWith('trinodb', 'trino', 5680);
  });

  it('opens the prompt context inspector from View full context', async () => {
    const getBrainView = vi.fn().mockResolvedValue(brainWith());
    const getTaskContext = vi.fn().mockRejectedValue(new Error('no context in test'));
    (window as unknown as { bridge: unknown }).bridge = { getBrainView, getTaskContext };

    renderView();
    fireEvent.click(await screen.findByRole('button', { name: /View full context/ }));

    expect(await screen.findByLabelText('Prompt context inspector')).toBeTruthy();
  });
});
