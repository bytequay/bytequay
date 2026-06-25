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
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskBrainPage } from './TaskBrainPage';

afterEach(cleanup);

function renderBrain(overrides: Partial<Parameters<typeof TaskBrainPage>[0]> = {}) {
  return render(
    <TaskBrainPage
      task={{ pillLabel: 'TASK #142', title: 'Add cost-meter card', branch: 'feat/cost' }}
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">brain feed</div>}
      stageChips={[
        { label: 'Plan', dot: 'done' },
        { label: 'Dev', dot: 'active', current: true },
      ]}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {}, modePill: <span>Dev → claude</span> }}
      tabs={{
        plan: <div data-testid="plan-tab">plan content</div>,
        details: <div data-testid="details-tab">details content</div>,
      }}
      {...overrides}
    />,
  );
}

describe('TaskBrainPage', () => {
  it('renders the task pill, title, branch, stage chips, and the model pill', () => {
    renderBrain();
    expect(screen.getByText('TASK #142')).toBeTruthy();
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('feat/cost')).toBeTruthy();
    expect(screen.getByText('Dev → claude')).toBeTruthy();
    // Stage chip strip with the current chip.
    expect(document.querySelector('.stage-chips .chip.current')?.textContent).toContain('Dev');
  });

  it('shows the first available tab (Plan) and switches to Details', () => {
    renderBrain();
    expect(screen.getByTestId('plan-tab')).toBeTruthy();
    expect(screen.queryByTestId('details-tab')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByTestId('details-tab')).toBeTruthy();
  });

  it('omits the Plan tab when no plan is provided', () => {
    renderBrain({ tabs: { details: <div data-testid="details-tab">d</div> } });
    const paneTabLabels = Array.from(document.querySelectorAll('.pane-tab')).map(b => b.textContent);
    expect(paneTabLabels).not.toContain('Plan');
    expect(paneTabLabels).toContain('Details');
    expect(screen.getByTestId('details-tab')).toBeTruthy();
  });

  it('top bar exposes Close (confirmed); toggling the pane reveals inline chips', () => {
    const onClose = vi.fn();
    const onOpenChanges = vi.fn();
    renderBrain({ run: { onClose, onPause: () => {} }, onOpenChanges });
    // Close is a direct top-bar button now, with a confirm step.
    fireEvent.click(screen.getByRole('button', { name: 'Close task' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close task' }));
    expect(onClose).toHaveBeenCalledOnce();
    // Close the pane → an inline Changes chip joins the top-bar button.
    fireEvent.click(screen.getByRole('button', { name: 'Toggle right pane' }));
    expect(document.querySelector('.inline-chips')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Changes' }).length).toBe(2);
  });

  it('the top-bar Changes button fires onOpenChanges', () => {
    const onOpenChanges = vi.fn();
    renderBrain({ onOpenChanges });
    // Two "Changes": the top-bar button (pane open). Click it.
    fireEvent.click(screen.getByRole('button', { name: 'Changes' }));
    expect(onOpenChanges).toHaveBeenCalledOnce();
  });
});
