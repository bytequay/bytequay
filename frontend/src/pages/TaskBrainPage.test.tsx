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
      composer={{ value: '', onChange: () => {}, onSubmit: () => {}, modePill: <span>Dev → claude</span> }}
      tabs={{
        pr: <div data-testid="pr-tab">pr content</div>,
      }}
      {...overrides}
    />,
  );
}

describe('TaskBrainPage', () => {
  it('renders the task pill, title, branch, and the model pill', () => {
    renderBrain();
    expect(screen.getByText('TASK #142')).toBeTruthy();
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('feat/cost')).toBeTruthy();
    expect(screen.getByText('Dev → claude')).toBeTruthy();
  });

  it('renders a clickable PR chip when the task is shipped', () => {
    const onOpen = vi.fn();
    renderBrain({ pr: { number: 1234, status: 'draft', onOpen } });
    const chip = screen.getByText('#1234').closest('button') as HTMLButtonElement;
    expect(chip.textContent).toContain('draft');
    fireEvent.click(chip);
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('shows the PR pane without a tab strip', () => {
    renderBrain();
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
    expect(document.querySelector('.pane-tab')).toBeNull();
    expect(document.querySelector('.pane-content--flush')).not.toBeNull();
  });

  it('shows no side pane when no PR tab is provided', () => {
    renderBrain({ tabs: {} });
    expect(document.querySelector('.body.with-pane')).toBeNull();
    expect(document.querySelector('.pane-tab')).toBeNull();
  });

  it('top bar exposes Close (confirmed); pane chips include PR and Code', () => {
    const onClose = vi.fn();
    renderBrain({
      run: { onClose, onPause: () => {} },
      tabs: { pr: <div data-testid="pr-tab">pr content</div>, code: <div data-testid="code-tab">code content</div> },
    });
    // Close is a direct top-bar button now, with a confirm step.
    fireEvent.click(screen.getByRole('button', { name: 'Close task' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close task' }));
    expect(onClose).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: 'Toggle right pane' }));
    expect(document.querySelector('.inline-chips')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'PR' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Code' })).toBeTruthy();
  });

  it('the inline pane chips switch to Code and fold the active pane', () => {
    renderBrain({
      tabs: { pr: <div data-testid="pr-tab">pr content</div>, code: <div data-testid="code-tab">code content</div> },
    });
    const inlineChips = document.querySelector('.inline-chips') as HTMLElement;
    fireEvent.click(within(inlineChips).getByRole('button', { name: 'Code' }));
    expect(screen.getByTestId('code-tab')).toBeTruthy();
    expect(document.querySelector('.pane-content--flush')).not.toBeNull();
    fireEvent.click(within(inlineChips).getByRole('button', { name: 'Code' }));
    expect(document.querySelector('.body.with-pane')).toBeNull();
    fireEvent.click(within(inlineChips).getByRole('button', { name: 'PR' }));
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
  });

  it('hides the mark-ready reminder pill when there is no Changes tab to open', () => {
    renderBrain({ markReadyReminder: true });
    expect(screen.queryByText('Mark ready for review')).toBeNull();
  });

  it('hides the mark-ready reminder pill when not pending', () => {
    renderBrain({ markReadyReminder: false });
    expect(screen.queryByText('Mark ready for review')).toBeNull();
  });

  it('the top-bar Submit review button opens the drawer; submitting it fires onSubmitReview', () => {
    renderBrain();
    expect(screen.queryByRole('button', { name: 'Submit review' })).toBeNull();
    const onSubmitReview = vi.fn();
    renderBrain({ onSubmitReview });
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));
    const dialog = screen.getByRole('dialog', { name: 'Submit review' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Submit review' }));
    expect(onSubmitReview).toHaveBeenCalledWith('', 'COMMENT');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
