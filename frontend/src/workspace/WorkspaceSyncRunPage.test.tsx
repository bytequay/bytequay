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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest } from '../types';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';
import { syncRun } from './syncRunFixture';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

const flush = () => act(async () => { await Promise.resolve(); });

function mount(run = syncRun()) {
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
  render(<WorkspaceSyncRunPage workspaceId="fork" jobId="job-1" />);
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
    expect(screen.getByText('2 of 5 picked')).toBeTruthy();

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

    // The repair reads as a proposal the program applied, and the fixup it
    // produced sits beside its pick.
    expect(document.querySelector('.sr-guidance.is-agent .sr-guidance__label')?.textContent)
      .toBe('AGENT');
    expect(document.querySelector('.sr-fixup code')?.textContent)
      .toBe('fixup! Extract CoordinatorModule config into CoordinatorConfig');
    expect(document.querySelector('.sr-fixup span:last-child')?.textContent)
      .toContain('program applied and committed');
    expect(screen.getByText('Repaired — the fixup compiles beside its pick')).toBeTruthy();
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
    expect(dialog.textContent).toContain('worktree is removed');
    // The branch it built is kept — say so before asking for the click.
    expect(dialog.textContent).toContain('upstream-2-31');
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/close'))).toBe(false);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close run' }));
    await flush();
    expect(request.mock.calls.some(([input]) => input.path.endsWith('/close')
      && input.method === 'POST')).toBe(true);
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
