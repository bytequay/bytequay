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
        pr: <div data-testid="pr-tab">pr threads</div>,
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

  it('Dev leads with the PR tab (PR is the primary artifact)', () => {
    renderStage('dev', { tabs: {
      changes: <div data-testid="changes-tab">changes</div>,
      pr: <div data-testid="pr-tab">pr</div>,
    } });
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
    expect(screen.queryByTestId('changes-tab')).toBeNull();
  });

  it('Dev falls back to Code Diff when no PR tab is present', () => {
    renderStage('dev', { tabs: {
      changes: <div data-testid="changes-tab">changes</div>,
    } });
    expect(screen.getByTestId('changes-tab')).toBeTruthy();
  });

  it('Comments leads with the PR tab', () => {
    renderStage('comments');
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
  });

  it('shows no side pane when a stage has no PR/diff/CI content yet', () => {
    renderStage('plan', { tabs: {} });
    expect(document.querySelector('.body.with-pane')).toBeNull();
    expect(document.querySelector('.pane-tab')).toBeNull();
  });

  it('CI Fix surfaces the CI Status entry', () => {
    const onOpenCi = vi.fn();
    renderStage('ci-fix', { onOpenCi });
    // CI Status appears twice now: the top-bar button and the always-on
    // inline chip above the composer. Either fires onOpenCi.
    fireEvent.click(screen.getAllByRole('button', { name: 'CI Status' })[0]);
    expect(onOpenCi).toHaveBeenCalledOnce();
  });

  it('Cleanup leads with PR like every stage and has no CI Status entry', () => {
    renderStage('cleanup', { stageKind: 'cleanup', onOpenCi: vi.fn() });
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'CI Status' })).toBeNull();
  });

  it('pill label can be overridden (e.g. a stage number)', () => {
    renderStage('dev', { stage: { title: 'x', pillLabel: 'STAGE 3 · DEV' } });
    expect(document.querySelector('.v3-pill--stage')?.textContent).toBe('STAGE 3 · DEV');
  });

  const fullTabs = {
    pr: <div data-testid="pr-tab">pr threads</div>,
    changes: <div data-testid="changes-tab">changes</div>,
    ci: <div data-testid="ci-tab">ci run</div>,
  };

  it('renders the PR · Code Diff · CI strip (PR first), plus the Changes nav pill', () => {
    renderStage('ci-fix', { tabs: fullTabs });
    const labels = Array.from(document.querySelectorAll('.pane-tab')).map(b => b.textContent);
    expect(labels).toEqual(['PR', 'Code Diff', 'CI', '⊟Changes']);
  });

  it('CI Fix leads with the PR tab; the CI run has its own tab', () => {
    renderStage('ci-fix', { tabs: fullTabs });
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
    const ciTab = Array.from(document.querySelectorAll('.pane-tab')).find(b => b.textContent === 'CI');
    fireEvent.click(ciTab as Element);
    expect(screen.getByTestId('ci-tab')).toBeTruthy();
  });

  it('shows the pane meta-row on the Code Diff and CI tabs only', () => {
    renderStage('ci-fix', { tabs: fullTabs, paneMeta: { left: 'CI fix · iter 2', right: 'View on GitHub' } });
    // Starts on PR — no meta row there.
    expect(screen.queryByText('CI fix · iter 2')).toBeNull();
    const tab = (label: string) =>
      Array.from(document.querySelectorAll('.pane-tab')).find(b => b.textContent?.startsWith(label)) as Element;
    fireEvent.click(tab('CI'));
    expect(screen.getByText('CI fix · iter 2')).toBeTruthy();
    fireEvent.click(tab('PR'));
    expect(screen.queryByText('CI fix · iter 2')).toBeNull();
  });

  it('renders per-tab count badges', () => {
    renderStage('dev', { tabs: fullTabs, tabCounts: { changes: { count: 4, countColor: 'acc' }, pr: { count: 145, countColor: 'muted' } } });
    const changesTab = Array.from(document.querySelectorAll('.pane-tab')).find(b => b.textContent?.startsWith('Code Diff'));
    expect(changesTab?.querySelector('.count')?.textContent).toBe('4');
  });

  it('labels the changes tab "CI" on the CI-fix stage', () => {
    renderStage('ci-fix', { tabs: fullTabs });
    const labels = Array.from(document.querySelectorAll('.pane-tab')).map(b => b.textContent);
    expect(labels).toContain('CI');
    expect(labels).not.toContain('Changes');
  });

  it('inline pill closes the pane when clicked on the already-active tab', () => {
    renderStage('ci-fix', { tabs: fullTabs });
    const inlineCi = () => within(document.querySelector('.inline-chips') as HTMLElement)
      .getByRole('button', { name: 'CI' });
    // Pane starts open on the PR tab; the CI pill jumps to the CI tab.
    expect(document.querySelector('.body.with-pane')).toBeTruthy();
    fireEvent.click(inlineCi());
    expect(screen.getByTestId('ci-tab')).toBeTruthy();
    // Clicking the now-active tab's inline pill closes the pane…
    fireEvent.click(inlineCi());
    expect(document.querySelector('.body.with-pane')).toBeNull();
    // …and clicking it again reopens on that tab.
    fireEvent.click(inlineCi());
    expect(document.querySelector('.body.with-pane')).toBeTruthy();
  });

  it('shows the mark-ready reminder pill and routes it to onOpenChanges', () => {
    const onOpenChanges = vi.fn();
    renderStage('dev', { markReadyReminder: true, onOpenChanges });
    fireEvent.click(screen.getByText('Mark ready for review'));
    expect(onOpenChanges).toHaveBeenCalledOnce();
  });

  it('hides the mark-ready reminder pill when not pending', () => {
    renderStage('dev', { markReadyReminder: false });
    expect(screen.queryByText('Mark ready for review')).toBeNull();
  });
});
