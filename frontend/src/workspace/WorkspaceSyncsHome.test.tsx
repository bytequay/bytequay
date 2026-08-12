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
import WorkspaceSyncsHome from './WorkspaceSyncsHome';
import { syncRun } from './syncRunFixture';
import type { UpstreamCherryPickJobDto } from './workspaceApi';

afterEach(cleanup);

function job(over: Partial<UpstreamCherryPickJobDto> = {}): UpstreamCherryPickJobDto {
  return { ...syncRun().job, ...over };
}

describe('WorkspaceSyncsHome', () => {
  it('splits runs into what is still moving and what is over', () => {
    render(<WorkspaceSyncsHome runs={[
      job({ jobId: 'live', runNumber: 13, status: 'RUNNING' }),
      job({ jobId: 'over', runNumber: 11, closedAt: '2026-08-09T10:00:00Z',
        prNumber: 4201, prResult: 'merged' }),
    ]} />);

    expect(screen.getByText('RUN #13')).toBeTruthy();
    expect(screen.getByText('PHASE 1 · PICKING')).toBeTruthy();
    // The finished one is a table row, not a card.
    expect(screen.getByText('#11')).toBeTruthy();
    expect(screen.getByText('Merged')).toBeTruthy();
  });

  it('shows manually closed runs as closed in the finished list', () => {
    render(<WorkspaceSyncsHome runs={[job({
      closedAt: '2026-08-09T10:00:00Z', prNumber: 4244,
    })]} />);

    expect(screen.getByText('Closed')).toBeTruthy();
  });

  it('renders the range endpoints and commit count the run was started with', () => {
    render(<WorkspaceSyncsHome runs={[job({
      rangeFromSha: 'f04d2220000000000000000000000000000000aa',
      rangeToSha: '37baa410000000000000000000000000000000bb',
      requestedCount: 124,
    })]} />);

    expect(screen.getByText('f04d222')).toBeTruthy();
    expect(screen.getByText('37baa41')).toBeTruthy();
    expect(screen.getByText('124 commits')).toBeTruthy();
  });

  it('shows the upstream and fork in the header', () => {
    render(<WorkspaceSyncsHome runs={[]}
      upstreamRepo="trinodb/trino" targetRepo="acme/trino-fork" />);

    expect(screen.getByText('trinodb/trino → acme/trino-fork')).toBeTruthy();
  });

  it('opens a run rather than listing the others inside it', () => {
    const onOpenSync = vi.fn();
    render(<WorkspaceSyncsHome onOpenSync={onOpenSync}
      runs={[job({ jobId: 'job-7', runNumber: 7 })]} />);

    fireEvent.click(screen.getByText('RUN #7'));
    expect(onOpenSync).toHaveBeenCalledWith('job-7');
  });

  it('parks a completed run with a pull request for review, not as done', () => {
    render(<WorkspaceSyncsHome runs={[
      job({ status: 'COMPLETED', prNumber: 4244 }),
    ]} />);

    expect(screen.getByText('PARKED FOR YOUR REVIEW')).toBeTruthy();
  });

  it('reports a completed run with no pull request as range complete', () => {
    render(<WorkspaceSyncsHome runs={[
      job({ status: 'COMPLETED', prNumber: null }),
    ]} />);

    expect(screen.getByText('RANGE COMPLETE')).toBeTruthy();
  });

  it('holds finished rows back behind "view all" and then shows them', () => {
    // Finished rows order by when they finished, so #1 finished longest ago and
    // is the one the preview holds back.
    const runs = Array.from({ length: 7 }, (ignored, index) => job({
      jobId: `done-${index}`,
      runNumber: index + 1,
      closedAt: `2026-08-0${index + 1}T10:00:00Z`,
      updatedAt: `2026-08-0${index + 1}T10:00:00Z`,
    }));
    render(<WorkspaceSyncsHome runs={runs} />);

    expect(screen.queryByText('#1')).toBeNull();
    expect(screen.getByText('#7')).toBeTruthy();
    fireEvent.click(screen.getByText('View all 7 finished runs'));
    expect(screen.getByText('#1')).toBeTruthy();
  });

  it('narrows the finished list to the chosen window', () => {
    const recent = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString();
    const old = new Date(Date.now() - 60 * 24 * 60 * 60 * 1000).toISOString();
    render(<WorkspaceSyncsHome runs={[
      job({ jobId: 'r', runNumber: 9, closedAt: recent, updatedAt: recent }),
      job({ jobId: 'o', runNumber: 2, closedAt: old, updatedAt: old }),
    ]} />);

    expect(screen.getByText('#2')).toBeTruthy();
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '7d' } });
    expect(screen.queryByText('#2')).toBeNull();
    expect(screen.getByText('#9')).toBeTruthy();
  });

  it('shows the rounds column as unreported rather than as zero', () => {
    render(<WorkspaceSyncsHome runs={[
      job({ closedAt: '2026-08-09T10:00:00Z', prNumber: 4201, prResult: 'merged' }),
    ]} />);

    const row = screen.getByText('Merged').closest('button');
    expect(row).toBeTruthy();
    // A zero would read as "no rounds were needed"; nothing reports them yet.
    expect(within(row as HTMLElement).getByTitle(
      'Fix rounds are not reported to this list yet').textContent).toBe('—');
  });
});
