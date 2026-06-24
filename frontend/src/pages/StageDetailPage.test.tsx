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
import { StageDetailPage, type StageKind } from './StageDetailPage';

afterEach(cleanup);

function renderStage(stageKind: StageKind, overrides: Partial<Parameters<typeof StageDetailPage>[0]> = {}) {
  return render(
    <StageDetailPage
      stageKind={stageKind}
      stage={{ title: 'Implement the meter', branch: 'feat/cost' }}
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">feed</div>}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {}, modePill: <span>Dev → claude-code · CLI</span> }}
      tabs={{
        plan: <div data-testid="plan-tab">plan</div>,
        pr: <div data-testid="pr-tab">pr threads</div>,
        details: <div data-testid="details-tab">details</div>,
      }}
      onOpenChanges={() => {}}
      {...overrides}
    />,
  );
}

describe('StageDetailPage', () => {
  it('shows the stage pill and the composer agent pill', () => {
    renderStage('dev');
    expect(document.querySelector('.v3-pill--stage')?.textContent).toBe('DEV');
    expect(screen.getByText('Dev → claude-code · CLI')).toBeTruthy();
  });

  it('Dev leads with the Plan tab', () => {
    renderStage('dev');
    expect(screen.getByTestId('plan-tab')).toBeTruthy();
    expect(screen.queryByTestId('details-tab')).toBeNull();
  });

  it('Comments leads with the PR tab', () => {
    renderStage('comments');
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
    expect(screen.queryByTestId('plan-tab')).toBeNull();
  });

  it('CI Fix leads with Details and surfaces the CI Status entry', () => {
    const onOpenCi = vi.fn();
    renderStage('ci-fix', { onOpenCi });
    expect(screen.getByTestId('details-tab')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'CI Status' }));
    expect(onOpenCi).toHaveBeenCalledOnce();
  });

  it('Cleanup leads with Details and has no CI Status entry', () => {
    renderStage('cleanup', { stageKind: 'cleanup', onOpenCi: vi.fn() });
    expect(screen.getByTestId('details-tab')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'CI Status' })).toBeNull();
  });

  it('pill label can be overridden (e.g. a stage number)', () => {
    renderStage('dev', { stage: { title: 'x', pillLabel: 'STAGE 3 · DEV' } });
    expect(document.querySelector('.v3-pill--stage')?.textContent).toBe('STAGE 3 · DEV');
  });
});
