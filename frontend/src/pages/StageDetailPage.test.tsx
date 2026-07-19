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
import { StageDetailPage, type StageKind } from './StageDetailPage';

afterEach(cleanup);

function stage(stageKind: StageKind, overrides: Partial<Parameters<typeof StageDetailPage>[0]> = {}) {
  return (
    <StageDetailPage
      stageKind={stageKind}
      stage={{ title: 'Local Development', branch: 'feat/cost' }}
      taskNumber={142}
      taskTitle="Implement the meter"
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">stage feed</div>}
      composer={{
        value: '', onChange: () => {}, onSubmit: () => {},
        modePill: <span>Claude Opus</span>, meta: 'Stage 2 of 4 · 15m 23s',
      }}
      pr={{ number: 1234, status: 'open' }}
      changes={{ additions: 8, deletions: 2 }}
      tabs={{ pr: <div data-testid="pr-body">real PR body</div> }}
      {...overrides}
    />
  );
}

describe('StageDetailPage locked frame', () => {
  it('renders the task breadcrumb, stage badge, title, and stage metadata', () => {
    const { container } = render(stage('dev'));
    expect(container.querySelector('.workspace-task-header__badge')?.textContent).toBe('DEV STAGE');
    expect(screen.getByRole('button', { name: 'Task #142' })).toBeTruthy();
    expect(screen.getByText('Local Development')).toBeTruthy();
    expect(screen.getByText('Stage 2 of 4 · 15m 23s')).toBeTruthy();
  });

  it('uses the resizable PR column and ignores legacy code/CI pane nodes', () => {
    const { container } = render(stage('ci-fix', {
      tabs: {
        pr: <div data-testid="pr-body">real PR body</div>,
        ci: <div data-testid="ci-body">legacy CI</div>,
        code: <div data-testid="code-body">legacy code</div>,
      },
    }));
    expect(screen.getByTestId('pr-body')).toBeTruthy();
    expect(screen.queryByTestId('ci-body')).toBeNull();
    expect(screen.queryByTestId('code-body')).toBeNull();
    expect(container.querySelector('.pane-tab')).toBeNull();
    expect(screen.getByRole('separator', { name: 'Resize pull request panel' })).toBeTruthy();
  });

  it('shows Changes, PR, and Stage task-page pills', () => {
    render(stage('dev'));
    expect(screen.getByRole('button', { name: /Changes/ }).textContent).toContain('+8');
    expect(screen.getByRole('button', { name: /PR #1234/ })).toBeTruthy();
    expect(screen.getByText('dev', { selector: '.workspace-task-artifact-pill .is-muted' })).toBeTruthy();
  });

  it('omits the panel and toggle when no real PR body is supplied', () => {
    const { container } = render(stage('plan', { tabs: {}, pr: undefined }));
    expect(container.querySelector('.workspace-task-v2__body.with-pr')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Toggle PR panel' })).toBeNull();
  });

  it('keeps the plan reminder in the task-pill toolbar', () => {
    render(stage('dev', { planReminder: 'locked', onRevealPlan: vi.fn() }));
    expect(screen.getByRole('button', { name: /Plan finalized/ })).toBeTruthy();
  });

  it('uses the locked closed-stage composer copy verbatim', () => {
    render(stage('dev', {
      composer: {
        value: '', onChange: () => {}, onSubmit: () => {},
        closedNote: 'This stage is closed — ask about what happened here…',
      },
    }));
    expect(screen.getByPlaceholderText('This stage is closed — ask about what happened here…')).toBeTruthy();
  });

  it('opens and submits the review drawer', async () => {
    const onSubmitReview = vi.fn();
    render(stage('dev', { onSubmitReview }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));
    const dialog = screen.getByRole('dialog', { name: 'Submit review' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Submit review' }));
    expect(onSubmitReview).toHaveBeenCalledWith('', 'COMMENT');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
