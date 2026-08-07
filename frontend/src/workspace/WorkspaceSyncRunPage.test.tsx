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

function mount(run = syncRun(), harness?: unknown, rightPane?: ReactNode, extra?: Partial<Props>) {
  const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
    if (input.path.includes('/run?events=')) return run;
    if (input.path.includes('/ci-harness/watches/')) return harness;
    return run.job;
  });
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi: request,
    // The live agent panel subscribes while the run is going; the stub returns
    // its unsubscribe so the effect cleans up like the real bridge.
    subscribeSyncRunStream: () => () => {},
    subscribeHarnessStream: () => () => {},
  };
  render(<WorkspaceSyncRunPage workspaceId="fork" jobId="job-1" rightPane={rightPane}
    {...extra} />);
  return request;
}

describe('sync run view', () => {
  it('shows the queue, the command log, and what the run is doing now', async () => {
    mount();
    await flush();

    // Queue: done collapses behind a count, the pick in flight has its own card.
    expect(document.querySelector('.sr-queue__section-label')?.textContent)
      .toBe('DONE · 2');
    expect(document.querySelector('.sr-queue__current-subject')?.textContent)
      .toBe('Refactor expression visitors to a registry');
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

    // The queue names that fixup too — knowing a pick once conflicted is not
    // something the reader can act on; knowing which commit repaired it is.
    expect(document.querySelector('.sr-queue__note-sha')?.textContent).toBe('5d1ae74');
  });

  it('offers a pause while running and never claims anything was pushed', async () => {
    const request = mount();
    await flush();

    expect(document.querySelector('.sr-queue__safety-copy strong')?.textContent)
      .toContain('nothing pushed');
    expect(document.querySelector('.sr-rail__item.is-idle small')?.textContent)
      .toBe('after push');

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
    expect(document.querySelector('.sr-phase')?.textContent).toBe('PHASE 1 · PARKED');

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
    expect(dialog.textContent).toContain('cached CI logs');
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

  it('says what phase 2 is doing once the picks have landed', async () => {
    const watching = syncRun();
    watching.job = {
      ...watching.job, status: 'COMPLETED', harnessWatchId: 'watch-1', prNumber: 2,
    };
    mount(watching, {
      watchId: 'watch-1', status: 'watching', activeCycle: null,
      cycles: [{ ordinal: 1, startedAtMs: Date.now() - 60_000, finishedAtMs: null }],
      runStatusTail: 'core / test — in progress',
      handoff: null, runStatusTailAt: null,
    });
    await flush();

    // A completed range is not a finished run — the harness is still driving it.
    const phase2 = document.querySelector('.sr-queue__phase2');
    expect(phase2?.querySelector('strong')?.textContent).toBe('Waiting for CI to finish');
    expect(phase2?.className).toContain('is-wait');
    expect(phase2?.textContent).toContain('core / test — in progress');
    expect(document.querySelector('.sr-now__copy')?.textContent)
      .toBe('Phase 2 — waiting for ci to finish');
  });

  it('can stop waiting for the board and fix what has already failed', async () => {
    const watching = syncRun();
    watching.job = {
      ...watching.job, status: 'COMPLETED', harnessWatchId: 'watch-1', prNumber: 2,
    };
    const request = mount(watching, {
      watchId: 'watch-1', status: 'watching', activeCycle: null, cycles: [],
      runStatusTail: 'pt (default, suite-iceberg): in progress', handoff: null,
    });
    await flush();

    fireEvent.click(screen.getByRole('button', { name: /Fix what has failed so far/ }));
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/run'));
    expect(call?.[0].method).toBe('POST');
    expect(call?.[0].body).toEqual({ fixNow: true });
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
    mount(pushed, { watchId: 'watch-1', status: 'watching', activeCycle: null, cycles: [],
      runStatusTail: null, handoff: null }, <p>pull request #3</p>);
    await flush();

    // The pane is the pull request; the rail button hides and shows it, and
    // nothing here hands the PR to a browser.
    expect(screen.getByText('pull request #3')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /PR/ }));
    expect(screen.queryByText('pull request #3')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /PR/ }));
    expect(screen.getByText('pull request #3')).toBeTruthy();
  });

  it('lists the workspace\u2019s runs at the top of the run\u2019s own column', async () => {
    const other = { ...syncRun().job, jobId: 'job-2', resultBranch: 'upstream-2-32' };
    const onOpenSync = vi.fn();
    mount(syncRun(), undefined, undefined, {
      syncs: [syncRun().job, other], onOpenSync,
    });
    await flush();

    const rows = document.querySelectorAll('.sr-queue__syncs .sync-nav__row');
    expect(rows).toHaveLength(2);
    // The list doubles as this column's title, so the open run is marked.
    expect(rows[0].getAttribute('aria-current')).toBe('true');
    expect(rows[1].getAttribute('aria-current')).toBeNull();

    fireEvent.click(rows[1]);
    expect(onOpenSync).toHaveBeenCalledWith('job-2');

    // It folds away — four other runs are not what someone came here to read.
    fireEvent.click(screen.getByRole('button', { name: /Syncs/ }));
    expect(document.querySelectorAll('.sr-queue__syncs .sync-nav__row')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: /Syncs/ }));
    expect(document.querySelectorAll('.sr-queue__syncs .sync-nav__row')).toHaveLength(2);
  });

  it('says why a stopped harness stopped, and offers to restart it', async () => {
    const stalled = syncRun();
    stalled.job = {
      ...stalled.job, status: 'COMPLETED', harnessWatchId: 'watch-1', prNumber: 2,
    };
    const request = mount(stalled, {
      watchId: 'watch-1', status: 'needs_attention', activeCycle: null, cycles: [],
      runStatusTail: 'build-success: failure',
      // `reason` is the machine code; the sentence is in `detail`.
      handoff: { reason: 'needs_attention', failureId: null, command: null,
        detail: 'No actionable log was available for the failed checks' },
    });
    await flush();

    const phase2 = document.querySelector('.sr-queue__phase2');
    expect(phase2?.querySelector('strong')?.textContent)
      .toBe('Stopped — nothing runs until you restart it');
    expect(phase2?.textContent).toContain('No actionable log was available');
    expect(phase2?.textContent).not.toContain('needs_attention');

    // Nothing polls a stopped watch, so the restart has to be reachable here.
    fireEvent.click(screen.getByRole('button', { name: /Try again on what is failing/ }));
    await flush();
    const call = request.mock.calls.find(([input]) => input.path.endsWith('/run'));
    expect(call?.[0].body).toEqual({ fixNow: true });
  });

  it('reads a harness round back in the log and steers it from the composer', async () => {
    const watching = syncRun();
    watching.job = {
      ...watching.job, status: 'COMPLETED', harnessWatchId: 'watch-1', prNumber: 2,
    };
    const turn = JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Bumped the plugin to match upstream.' }] },
    });
    const request = mount(watching, {
      watchId: 'watch-1', status: 'running', activeCycle: { phase: 'fix' }, cycles: [],
      runStatusTail: null, handoff: null,
      milestones: [
        { id: 1, cycleId: 'c1', phase: 'fix', kind: 'phase_started',
          message: 'Handing 6 failure(s) to the agent', detailJson: null,
          createdAtMs: Date.parse('2026-08-06T17:20:00Z') },
        { id: 2, cycleId: 'c1', phase: 'fix', kind: 'agent_log',
          message: turn, detailJson: null,
          createdAtMs: Date.parse('2026-08-06T17:22:00Z') },
      ],
    });
    await flush();

    // The round is in the same log as the picks, transcript and all — it used
    // to be a black box between "handing over" and a one-line verdict.
    expect(screen.getByText('Handing 6 failure(s) to the agent')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Agent transcript/ }));
    expect(screen.getByText('Bumped the plugin to match upstream.')).toBeTruthy();

    // Steering reaches phase 2's agent rather than the picks' guidance field.
    fireEvent.change(screen.getByLabelText('Steer the run'), {
      target: { value: 'skip the flaky iceberg suite' },
    });
    fireEvent.click(screen.getByLabelText('Send guidance'));
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/run'));
    expect(call?.[0].body).toEqual({ fixNow: true, steeringText: 'skip the flaky iceberg suite' });
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/guidance'))).toBe(false);
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
