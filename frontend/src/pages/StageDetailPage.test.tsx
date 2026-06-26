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
    // CI Status appears twice now: the top-bar button and the always-on
    // inline chip above the composer. Either fires onOpenCi.
    fireEvent.click(screen.getAllByRole('button', { name: 'CI Status' })[0]);
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

  const fullTabs = {
    plan: <div data-testid="plan-tab">plan</div>,
    changes: <div data-testid="changes-tab">changes</div>,
    pr: <div data-testid="pr-tab">pr threads</div>,
    files: <div data-testid="files-tab">files</div>,
    details: <div data-testid="details-tab">details</div>,
  };

  it('renders the full Plan · Changes · PR · Files · Details strip', () => {
    renderStage('dev', { tabs: fullTabs });
    const labels = Array.from(document.querySelectorAll('.pane-tab')).map(b => b.textContent);
    expect(labels).toEqual(['Plan', 'Changes', 'PR', 'Files', 'Details']);
  });

  it('CI Fix leads with the Changes tab when one is provided', () => {
    renderStage('ci-fix', { tabs: fullTabs });
    expect(screen.getByTestId('changes-tab')).toBeTruthy();
    expect(screen.queryByTestId('plan-tab')).toBeNull();
  });

  it('shows the pane meta-row only on the Changes tab', () => {
    renderStage('ci-fix', { tabs: fullTabs, paneMeta: { left: 'CI fix · iter 2', right: 'View on GitHub' } });
    expect(screen.getByText('CI fix · iter 2')).toBeTruthy();
    // Switching to Details (the pane tab, not the inline chip) hides the row.
    const detailsTab = Array.from(document.querySelectorAll('.pane-tab')).find(b => b.textContent === 'Details');
    fireEvent.click(detailsTab as Element);
    expect(screen.queryByText('CI fix · iter 2')).toBeNull();
  });

  it('renders per-tab count badges', () => {
    renderStage('ci-fix', { tabs: fullTabs, tabCounts: { changes: { count: 4, countColor: 'acc' }, pr: { count: 145, countColor: 'muted' } } });
    const changesTab = Array.from(document.querySelectorAll('.pane-tab')).find(b => b.textContent?.startsWith('Changes'));
    expect(changesTab?.querySelector('.count')?.textContent).toBe('4');
  });
});
