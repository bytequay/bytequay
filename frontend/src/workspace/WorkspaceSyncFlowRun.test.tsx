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
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ThreadStreamEvent, WorkspaceApiRequest } from '../types';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';
import { syncRun } from './syncRunFixture';
import type { AgentToolApprovalDto, UpstreamCherryPickRunDto } from './workspaceApi';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

const flush = () => act(async () => { await Promise.resolve(); });

const RUN_ID = 'upstream-sync-run:6f1c0a';

/** The same run, as the greenfield flow reports it. */
function flowRun(): UpstreamCherryPickRunDto {
  const base = syncRun();
  return {
    ...base,
    job: {
      ...base.job,
      jobId: RUN_ID,
      source: 'flow',
      status: 'COMPLETED',
      // Bounded by repair turns rather than by a dollar ceiling.
      budgetMilliUsd: undefined,
      remainingRepairTurns: 48,
      roundCount: 0,
      prNumber: null,
    },
    rounds: [],
    fixups: [],
    compileProof: null,
    publishGate: {
      gateId: 'gate-1',
      revision: 3,
      subjectDigest: 'subject-digest',
      actionDigest: 'action-digest',
      state: 'OPEN',
      proposedHead: 'f21ac09aaaaaaaa',
      branchRef: 'bump-trino-41c9b02-to-b3d91e0',
      targetBaseRef: 'main',
    },
  };
}

function mount(run = flowRun(), approvals: AgentToolApprovalDto[] = []) {
  const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
    if (input.path.includes('/upstream/cherry-picks')) {
      throw new Error(`the retired path must not be read: ${input.path}`);
    }
    if (input.path.endsWith('/permissions')) return approvals;
    return run;
  });
  const subscriptions: string[] = [];
  let stream: ((event: ThreadStreamEvent) => void) | undefined;
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi: request,
    subscribeSyncRunStream: () => () => {
      throw new Error('the retired runner does not stream this run');
    },
    subscribeFlowSyncRunStream: (
      runId: string, onEvent: (event: ThreadStreamEvent) => void,
    ) => {
      subscriptions.push(runId);
      stream = onEvent;
      return () => {};
    },
  };
  render(<WorkspaceSyncRunPage workspaceId="fork" jobId={RUN_ID} />);
  return {
    request,
    subscriptions,
    emit: (data: Record<string, unknown>) => stream?.({ name: 'line', data }),
  };
}

describe('a greenfield sync run', () => {
  it('is read from the path that owns it', async () => {
    const { request } = mount();
    await flush();

    expect(request.mock.calls[0][0].path)
      .toBe(`/api/workspaces/fork/upstream/syncs/${encodeURIComponent(RUN_ID)}`);
    expect(screen.getByText('RUN #1')).toBeTruthy();
  });

  it('is titled by its pull request, not by the shape of the range', async () => {
    const run = flowRun();
    // The title the user typed when they confirmed the range; the draft's own
    // once the run names it. Either way it is what this work is called.
    run.job.prTitle = 'Bump Trino to 482';
    mount(run);
    await flush();

    expect(document.querySelector('.sr-topbar__title')?.textContent)
      .toBe('Bump Trino to 482');
  });

  it('falls back to the range when nothing has named the pull request', async () => {
    mount();
    await flush();

    expect(document.querySelector('.sr-topbar__title')?.textContent)
      .toContain('Cherry-pick');
  });

  it('parks a running range at its own path', async () => {
    const run = flowRun();
    run.job.status = 'RUNNING';
    const { request } = mount(run);
    await flush();

    fireEvent.click(screen.getByRole('button', { name: /Park now/ }));
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/park'));
    expect(call?.[0].path)
      .toBe(`/api/workspaces/fork/upstream/syncs/${encodeURIComponent(RUN_ID)}/park`);
    expect(call?.[0].method).toBe('POST');
  });

  it('says a park it has already asked for is on its way', async () => {
    const run = flowRun();
    run.job.status = 'RUNNING';
    run.job.pauseRequested = true;
    mount(run);
    await flush();

    const pause = screen.getByRole('button', { name: /Pausing/ }) as HTMLButtonElement;
    expect(pause.disabled).toBe(true);
  });

  it('offers no control the flow does not have', async () => {
    mount();
    await flush();

    // The retired path's skip and resume are its own.
    expect(screen.queryByRole('button', { name: /Skip this commit/ })).toBeNull();
    // The composer stays, inert and saying why, rather than vanishing.
    const composer = screen.getByLabelText('Steer the run') as HTMLInputElement;
    expect(composer.disabled).toBe(true);
    expect(composer.placeholder).toBe('Steering this run is not wired yet');
  });

  it('closes at its own path once the user confirms', async () => {
    const { request } = mount();
    await flush();

    fireEvent.click(screen.getByRole('button', { name: /Close run/ }));
    // The dialog's own confirm carries the same label as the control that
    // opened it, and is the one that renders second.
    fireEvent.click(screen.getAllByRole('button', { name: /Close run/ }).at(-1)!);
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/close'));
    expect(call?.[0].path)
      .toBe(`/api/workspaces/fork/upstream/syncs/${encodeURIComponent(RUN_ID)}/close`);
    expect(call?.[0].method).toBe('POST');
  });

  it('deletes the run and leaves the page it was on', async () => {
    const run = flowRun();
    const request = vi.fn(
      async (input: WorkspaceApiRequest): Promise<unknown> => {
        void input;
        return run;
      });
    (window as unknown as { bridge: unknown }).bridge = {
      workspaceApi: request,
      subscribeFlowSyncRunStream: () => () => {},
    };
    const onBack = vi.fn();
    render(<WorkspaceSyncRunPage workspaceId="fork" jobId={RUN_ID} onBack={onBack} />);
    await flush();

    fireEvent.click(screen.getByLabelText('Delete run'));
    fireEvent.click(screen.getAllByRole('button', { name: /Delete run/ }).at(-1)!);
    await flush();

    const call = request.mock.calls.find(([input]) => input.method === 'DELETE');
    expect(call?.[0].path)
      .toBe(`/api/workspaces/fork/upstream/syncs/${encodeURIComponent(RUN_ID)}`);
    expect(onBack).toHaveBeenCalled();
  });

  it('publishes only on the exact revision it displayed', async () => {
    const { request } = mount();
    await flush();

    expect(screen.getByText('Ready to publish — nothing is pushed yet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Authorize the first push' }));
    await flush();

    const call = request.mock.calls.find(([input]) => input.path.endsWith('/publish'));
    expect(call?.[0].body).toEqual({
      revision: 3,
      subjectDigest: 'subject-digest',
      actionDigest: 'action-digest',
    });
  });

  it('does not offer a push the gate has not opened', async () => {
    const run = flowRun();
    mount({
      ...run,
      publishGate: run.publishGate === undefined || run.publishGate === null
        ? null : { ...run.publishGate, state: 'AUTHORIZED' },
    });
    await flush();

    expect(screen.queryByRole('button', { name: 'Authorize the first push' }))
      .toBeNull();
  });

  it('watches its own turns rather than the retired runner\u2019s', async () => {
    // A picking run is live, so the live panel subscribes — to the path that
    // actually carries this run's output.
    const { subscriptions } = mount({
      ...flowRun(),
      job: { ...flowRun().job, status: 'RUNNING' },
    });
    await flush();

    expect(subscriptions).toEqual([RUN_ID]);
  });

  it('shows the exact permission request and names the waiting state', async () => {
    const run = flowRun();
    run.job.status = 'RUNNING';
    const command = 'git hash-object core/trino-spi/pom.xml';
    const { emit } = mount(run, [{
      approvalId: 'approval-1', runId: RUN_ID, toolName: 'Bash',
      inputJson: JSON.stringify({ command }), requestedAtEpochMilli: 1,
    }]);
    await flush();
    act(() => emit({
      type: 'assistant',
      message: { content: [{
        type: 'tool_use', name: 'Read', input: { file_path: 'core/trino-spi/pom.xml' },
      }] },
    }));

    expect(await screen.findByText(command)).toBeTruthy();
    expect(screen.getByText(command).tagName).toBe('PRE');
    expect(screen.getByRole('button', { name: 'Approve once' })).toBeTruthy();
    expect(screen.getByText('Agent waiting for permission')).toBeTruthy();

    act(() => emit({ type: 'result', num_turns: 1, total_cost_usd: 0 }));
    expect(screen.getByText('Agent log')).toBeTruthy();
    expect(screen.queryByText('core/trino-spi/pom.xml')).toBeNull();
  });

  it('reports repair turns where a dollar ceiling does not exist', async () => {
    mount();
    await flush();

    expect(document.querySelector('.sr-session__stat[title]')?.getAttribute('title'))
      .toBe('48 conflict-repair turns left');
  });
});
