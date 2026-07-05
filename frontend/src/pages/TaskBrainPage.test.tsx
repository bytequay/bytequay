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
        pr: <div data-testid="pr-tab">pr content</div>,
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

  it('renders a clickable PR chip when the task is shipped', () => {
    const onOpen = vi.fn();
    renderBrain({ pr: { number: 1234, status: 'draft', onOpen } });
    const chip = screen.getByText('#1234').closest('button') as HTMLButtonElement;
    expect(chip.textContent).toContain('draft');
    fireEvent.click(chip);
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('shows the PR tab', () => {
    renderBrain();
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
  });

  it('shows no side pane when no PR tab is provided', () => {
    renderBrain({ tabs: {} });
    expect(document.querySelector('.body.with-pane')).toBeNull();
    expect(document.querySelector('.pane-tab')).toBeNull();
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
    // Two "Changes": the top-bar button and the always-visible inline chip.
    // Either fires onOpenChanges — click the first (top-bar) one.
    fireEvent.click(screen.getAllByRole('button', { name: 'Changes' })[0]);
    expect(onOpenChanges).toHaveBeenCalledOnce();
  });

  it('shows the mark-ready reminder pill and routes it to onOpenChanges', () => {
    const onOpenChanges = vi.fn();
    renderBrain({ markReadyReminder: true, onOpenChanges });
    fireEvent.click(screen.getByText('Mark ready for review'));
    expect(onOpenChanges).toHaveBeenCalledOnce();
  });

  it('hides the mark-ready reminder pill when not pending', () => {
    renderBrain({ markReadyReminder: false });
    expect(screen.queryByText('Mark ready for review')).toBeNull();
  });
});
