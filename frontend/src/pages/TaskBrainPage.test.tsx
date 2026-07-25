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
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TaskBrainPage } from './TaskBrainPage';

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

function brain(overrides: Partial<Parameters<typeof TaskBrainPage>[0]> = {}) {
  return (
    <TaskBrainPage
      task={{
        pillLabel: 'TASK #142', taskNumber: 142,
        title: 'Add cost-meter card', branch: 'feat/cost', finished: true,
      }}
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">brain feed</div>}
      composer={{
        value: '', onChange: () => {}, onSubmit: () => {},
        modePill: <span>Claude Opus</span>, meta: 'Task #142 · 23m · $0.42',
      }}
      pr={{ number: 1234, status: 'merged', onOpen: () => {} }}
      changes={{ additions: 7, deletions: 3 }}
      tabs={{ pr: <div data-testid="pr-body">real PR body</div> }}
      {...overrides}
    />
  );
}

describe('TaskBrainPage locked frame', () => {
  it('uses the shared resizable nav width, brain badge, task header and composer metadata', () => {
    window.localStorage.setItem('bq.rail-width', '330');
    const { container } = render(brain());
    expect((container.querySelector('.shell') as HTMLElement).style.gridTemplateColumns)
      .toBe('330px minmax(0, 1fr)');
    expect(screen.getByRole('separator', { name: 'Resize the sidebar' })).toBeTruthy();
    expect(container.querySelector('.workspace-task-header__badge')?.textContent).toBe('BRAIN');
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('Claude Opus')).toBeTruthy();
    expect(screen.getByText('Task #142 · 23m · $0.42')).toBeTruthy();
  });

  it('mounts one resizable PR column with no legacy tab strip', () => {
    const { container } = render(brain());
    expect(screen.getByTestId('pr-body')).toBeTruthy();
    expect(container.querySelector('.workspace-task-v2__body.with-pr')).toBeTruthy();
    expect(container.querySelector('.workspace-task-v2__pr')).toBeTruthy();
    expect(screen.getByRole('separator', { name: 'Resize pull request panel' })).toBeTruthy();
    expect(container.querySelector('.pane-tab')).toBeNull();
  });

  it('uses the Pull Requests pane width and clamps drag resizing', () => {
    const { container } = render(brain());
    const body = container.querySelector('.workspace-task-v2__body') as HTMLElement;
    const pane = container.querySelector('.workspace-task-v2__pr') as HTMLElement;
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({ right: 1600 } as DOMRect);

    expect(pane.style.width).toBe('940px');
    const handle = screen.getByRole('separator', { name: 'Resize pull request panel' });
    fireEvent.mouseDown(handle);
    fireEvent.mouseMove(window, { clientX: 1500 });
    expect(pane.style.width).toBe('460px');
    fireEvent.mouseMove(window, { clientX: 0 });
    expect(pane.style.width).toBe('1150px');
    fireEvent.mouseUp(window);
    expect(window.localStorage.getItem('bq.taskPrPaneWidth')).toBe('1150');
  });

  it('starts an asynchronously arriving PR pane open', () => {
    const view = render(brain({ tabs: {}, pr: undefined }));
    expect(view.container.querySelector('.workspace-task-v2__body.with-pr')).toBeNull();
    view.rerender(brain());
    expect(screen.getByTestId('pr-body')).toBeTruthy();
  });

  it('collapses and restores the PR column from the header control', () => {
    const { container } = render(brain());
    const toggle = screen.getByRole('button', { name: 'Toggle PR panel' });
    fireEvent.click(toggle);
    expect(container.querySelector('.workspace-task-v2__body.with-pr')).toBeNull();
    expect(screen.queryByRole('separator', { name: 'Resize pull request panel' })).toBeNull();
    fireEvent.click(toggle);
    expect(container.querySelector('.workspace-task-v2__body.with-pr')).toBeTruthy();
  });

  it('renders task-only Changes and PR pills', () => {
    render(brain());
    expect(screen.getByRole('button', { name: /Changes/ }).textContent).toContain('+7');
    expect(screen.getByRole('button', { name: /Changes/ }).textContent).toContain('−3');
    expect(screen.getByRole('button', { name: /PR #1234/ })).toBeTruthy();
  });

  it('uses the locked closed-task composer copy verbatim', () => {
    render(brain({
      composer: {
        value: '', onChange: () => {}, onSubmit: () => {},
        closedNote: 'This task is closed — ask the brain, or reopen to continue…',
      },
    }));
    expect(screen.getByPlaceholderText('This task is closed — ask the brain, or reopen to continue…')).toBeTruthy();
  });

  it('shows the task state and exposes task-scoped Resume beside Close task', () => {
    const onClose = vi.fn();
    const onResume = vi.fn();
    render(brain({
      task: { pillLabel: 'TASK #142', title: 'Needs help', finished: false },
      run: { paused: true, statusLabel: 'needs attention', statusDetail: 'Brain review failed', onResume, onClose },
    }));
    const resume = screen.getByRole('button', { name: 'Resume · NEEDS ATTENTION' });
    expect(resume.getAttribute('title')).toBe('Brain review failed');
    fireEvent.click(resume);
    expect(onResume).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: /Close task/ })).toBeTruthy();
  });

  it('surfaces polling and action failures without replacing stale content', () => {
    render(brain({ error: 'Could not refresh task state' }));
    expect(screen.getByRole('alert').textContent).toBe('Could not refresh task state');
    expect(screen.getByTestId('conv')).toBeTruthy();
  });

  it('opens and submits the review drawer', async () => {
    const onSubmitReview = vi.fn();
    render(brain({ onSubmitReview }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit review • 0' }));
    const dialog = screen.getByRole('dialog', { name: 'Submit review' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Submit review' }));
    expect(onSubmitReview).toHaveBeenCalledWith('', 'COMMENT');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
