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
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ComponentProps, ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest } from '../types';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';
import { syncRun } from './syncRunFixture';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

const flush = () => act(async () => { await Promise.resolve(); });

type Props = ComponentProps<typeof WorkspaceSyncRunPage>;

function mount(run = syncRun(), rightPane?: ReactNode, extra?: Partial<Props>) {
  const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
    if (input.path.includes('/run?events=')) return run;
    return run.job;
  });
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi: request,
    // The live agent panel subscribes while the run is going; the stub returns
    // its unsubscribe so the effect cleans up like the real bridge.
    subscribeSyncRunStream: () => () => {},
  };
  render(<WorkspaceSyncRunPage workspaceId="fork" jobId="job-1" rightPane={rightPane}
    {...extra} />);
  return request;
}

describe('sync run view', () => {
  it('shows the queue, the command log, and what the run is doing now', async () => {
    mount();
    await flush();

    // The left column is the three phases, and phase 1 carries the picking.
    expect(screen.getByText('Local cherry-picks')).toBeTruthy();
    expect(screen.getByText('CI harness')).toBeTruthy();
    expect(screen.getByText('Review & merge')).toBeTruthy();
    expect(screen.getByText('phase 1 of 3')).toBeTruthy();
    expect(screen.getByText('2 of 5 settled')).toBeTruthy();

    // The centre column says what is happening, not that something is.
    expect(document.querySelector('.sr-now__copy')?.textContent)
      .toContain('Refactor expression visitors to a registry');

    // Every command carries its exit status; its output is one click away.
    const commands = document.querySelectorAll('.sr-cmd');
    expect(commands).toHaveLength(4);
    expect(document.querySelector('.sr-exit')?.textContent).toBe('exit 0');
    expect(document.querySelector('.sr-output')).toBeNull();
    fireEvent.click(commands[0]);
    expect(document.querySelector('.sr-output')?.textContent).toContain('1 file changed');

    // The conflicted pick reads as routine progress, not as an error.
    expect(screen.getByText("Committed git's three-way resolution")).toBeTruthy();
    expect(document.querySelector('.sr-pick.is-carried')).toBeTruthy();

    // The repair reads as the agent's own work, and names the commit it made.
    expect(document.querySelector('.sr-guidance.is-agent .sr-guidance__label')?.textContent)
      .toBe('AGENT');
    expect(document.querySelector('.sr-fixup code')?.textContent).toBe('5d1ae74');
    expect(document.querySelector('.sr-fixup span:last-child')?.textContent)
      .toContain('fixup! Extract CoordinatorModule config');
    expect(screen.getByText('Repaired — the fixup compiles beside its pick')).toBeTruthy();

  });

  it('opens phase 1 onto its most recent picks', async () => {
    const bumps = syncRun();
    bumps.commits = [
      ...bumps.commits.slice(0, 2),
      ...Array.from({ length: 8 }, (unused, offset) => ({
        index: 10 + offset,
        sha: `bump${offset}`,
        shortSha: `bump${offset}`,
        subject: `Bump some dependency ${offset}`,
        state: 'skipped' as const,
      })),
      ...bumps.commits.slice(2),
    ];
    bumps.job = { ...bumps.job, requestedCount: 13, skippedCount: 8 };
    mount(bumps);
    await flush();

    // Folded by default: the picks are the phase's detail, not its headline.
    expect(document.querySelectorAll('.st-pick')).toHaveLength(0);
    fireEvent.click(document.querySelector('.st-phase__head.is-button') as HTMLElement);
    expect(document.querySelectorAll('.st-pick')).toHaveLength(3);
    expect(screen.getByText('View all 13 picks')).toBeTruthy();
  });

  it('keeps the pick-by-pick conversation while the run is still picking', async () => {
    mount();
    await flush();

    // Folding phase 1 to "N settled" is right once the run has moved past it.
    // While it is still picking that summary is the whole page, and it would
    // hide the conflict repairs someone parked on a conflict came to read.
    expect(document.querySelector('.sf-group')).toBeNull();
    expect(document.querySelectorAll('.sr-pick__head').length).toBeGreaterThan(1);

    const pushed = syncRun();
    pushed.job = { ...pushed.job, status: 'COMPLETED', prNumber: 12 };
    cleanup();
    mount(pushed);
    await flush();

    // Past phase 1, it folds.
    expect(document.querySelector('.sf-group')).toBeTruthy();
    expect(document.querySelectorAll('.sr-pick__head')).toHaveLength(0);
  });

  it('offers a pause while running and never claims anything was pushed', async () => {
    const request = mount();
    await flush();

    expect(document.querySelector('.st-foot')?.textContent)
      .toContain('Isolated worktree');
    // No pull request yet, so nothing offers one. The old column showed a
    // permanently-disabled "PR / after push" tile, which was three numbers and
    // a dead button in a column of their own.
    expect(document.querySelector('.sr-rail')).toBeNull();
    expect(screen.queryByLabelText('Toggle the pull request panel')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Pause after this pick/ }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/pause')
      && input.method === 'POST')).toBe(true);
  });

  it('swaps pause for resume and offers a skip once the run is parked', async () => {
    const parked = syncRun();
    parked.job = { ...parked.job, status: 'PAUSED_CONFLICT', pauseRequested: true };
    const request = mount(parked);
    await flush();

    expect(screen.queryByRole('button', { name: /Pause after this pick/ })).toBeNull();
    expect(document.querySelector('.sr-phase')?.textContent).toBe('PARKED');

    fireEvent.click(screen.getByRole('button', { name: /Skip this commit/ }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/skip'))).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: /Resume/ }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/resume'))).toBe(true);
  });

  it('closes the run only after saying what a close destroys', async () => {
    const request = mount();
    await flush();

    fireEvent.click(screen.getByRole('button', { name: /Close run/ }));
    const dialog = screen.getByRole('dialog');
    // Everything a close now releases, named before the click asks for it.
    expect(dialog.textContent).toContain('isolated worktree');
    expect(dialog.textContent).toContain('session and stored transcripts');
    // This fixture never pushed, so its branch is the only copy and is kept.
    expect(dialog.textContent).toContain('upstream-2-31');
    expect(dialog.textContent).toContain('Nothing was pushed');
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/close'))).toBe(false);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close run' }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/close')
      && input.method === 'POST')).toBe(true);
  });

  it('deletes the run and leaves the page once it is gone', async () => {
    const request = mount();
    await flush();

    fireEvent.click(screen.getByRole('button', { name: /Delete run/ }));
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('its log is gone for good');
    expect(request.mock.calls.some(([input]) => input.method === 'DELETE')).toBe(false);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete run' }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.method === 'DELETE'
      && input.path.endsWith('/upstream/cherry-picks/job-1'))).toBe(true);
  });

  it('drops every action once the run is closed', async () => {
    const closed = syncRun();
    closed.job = { ...closed.job, closedAt: '2026-08-05T15:00:00Z' };
    mount(closed);
    await flush();

    expect(document.querySelector('.sr-phase')?.textContent).toBe('CLOSED');
    expect(screen.queryByRole('button', { name: /Pause after this pick/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Park now/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Close run/ })).toBeNull();
    expect(document.querySelector('.sr-now__label')?.textContent).toBe('CLOSED');
  });

  it('treats a historical watch id as inert after a pick-only run completes', async () => {
    const completed = syncRun();
    completed.job = {
      ...completed.job,
      status: 'COMPLETED',
      harnessWatchId: 'historical-watch',
      prNumber: 2,
    };
    const request = mount(completed);
    await flush();

    expect(document.querySelector('.sr-phase')?.textContent).toBe('COMPLETE');
    expect(document.querySelector('.sr-now__copy')?.textContent)
      .toContain('draft PR #2 parked for your review');
    const removedSegment = ['ci', 'harness'].join('-');
    expect(request.mock.calls.every(([input]) => !input.path.includes(removedSegment)))
      .toBe(true);
  });

  it('folds one pick’s conversation away without touching the others', async () => {
    mount();
    await flush();

    const heads = document.querySelectorAll('.sr-pick__head');
    const bodies = () => document.querySelectorAll('.sr-pick__body').length;
    const before = bodies();
    expect(heads.length).toBeGreaterThan(1);

    fireEvent.click(heads[0]);
    expect(bodies()).toBe(before - 1);
    expect(heads[0].getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(heads[0]);
    expect(bodies()).toBe(before);
  });

  it('shows the pull request beside the run rather than on github.com', async () => {
    const pushed = syncRun();
    pushed.job = {
      ...pushed.job, prNumber: 3, harnessWatchId: 'watch-1',
      prUrl: 'https://github.com/fork/repo/pull/3',
    };
    mount(pushed, <p>pull request #3</p>);
    await flush();

    // The pane is the pull request; the top bar's toggle hides and shows it,
    // and nothing here hands the PR to a browser.
    expect(screen.getByText('pull request #3')).toBeTruthy();
    const toggle = screen.getByLabelText('Toggle the pull request panel');
    fireEvent.click(toggle);
    expect(screen.queryByText('pull request #3')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByText('pull request #3')).toBeTruthy();

    // The session row carries the number, and reopens the pane too.
    expect(document.querySelector('.sr-session__stat')?.textContent)
      .toContain('elapsed');
    expect(screen.getByRole('button', { name: 'PR #3' })).toBeTruthy();
  });

  it('does not list the workspace\u2019s other runs inside one run', async () => {
    mount();
    await flush();

    // They live on the sync home page. Stacking them here put four other runs
    // in the column and left a pushed run nowhere to show its remote state.
    expect(document.querySelector('.sr-queue__syncs')).toBeNull();
    expect(document.querySelectorAll('.sync-nav__row')).toHaveLength(0);
  });

  it('builds phase 3\u2019s receipt from what the teardown recorded', async () => {
    const merged = syncRun();
    merged.job = {
      ...merged.job, status: 'COMPLETED', prNumber: 9, prResult: 'merged',
      closedAt: '2026-08-09T12:00:00Z',
    };
    merged.events = [
      ...merged.events,
      {
        id: 'c1', ordinal: 90, pickIndex: null, kind: 'cleanup',
        title: 'Removed the isolated worktree', detail: null,
        exitCode: null, durationMs: null, at: '2026-08-09T12:00:01Z',
      },
      {
        id: 'c2', ordinal: 91, pickIndex: null, kind: 'cleanup',
        title: 'Remote upstream-2-31 was not deleted', detail: 'already gone',
        exitCode: null, durationMs: null, at: '2026-08-09T12:00:02Z',
      },
    ];
    mount(merged);
    await flush();

    // The rail's phase 3 is a receipt, so it ticks only what actually happened —
    // a step the program skipped stays unticked rather than being claimed.
    const receipt = document.querySelector('.st-receipt') as HTMLElement;
    expect(receipt).toBeTruthy();
    expect(receipt.textContent).toContain('Pull request merged');
    expect(receipt.querySelector('.is-done')?.textContent)
      .toContain('Pull request merged');
    const rows = Array.from(receipt.querySelectorAll('li'));
    const skipped = rows.find(row => row.textContent?.includes('was not deleted'));
    expect(skipped?.classList.contains('is-done')).toBe(false);
  });

  it('records steering guidance on the run', async () => {
    const request = mount();
    await flush();

    fireEvent.change(screen.getByLabelText('Steer the run'), {
      target: { value: "prefer our fork's config names" },
    });
    fireEvent.click(screen.getByLabelText('Send guidance'));
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/guidance'));
    expect(call?.[0].body).toEqual({ text: "prefer our fork's config names" });
  });
});
