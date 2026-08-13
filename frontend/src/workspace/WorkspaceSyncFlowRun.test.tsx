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
import type { WorkspaceApiRequest } from '../types';
import WorkspaceSyncRunPage from './WorkspaceSyncRunPage';
import { syncRun } from './syncRunFixture';
import type { UpstreamCherryPickRunDto } from './workspaceApi';

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

function mount(run = flowRun()) {
  const request = vi.fn(async (input: WorkspaceApiRequest): Promise<unknown> => {
    if (input.path.includes('/upstream/cherry-picks')) {
      throw new Error(`the retired path must not be read: ${input.path}`);
    }
    return run;
  });
  const subscriptions: string[] = [];
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi: request,
    subscribeSyncRunStream: () => () => {
      throw new Error('the retired runner does not stream this run');
    },
    subscribeFlowSyncRunStream: (runId: string) => {
      subscriptions.push(runId);
      return () => {};
    },
  };
  render(<WorkspaceSyncRunPage workspaceId="fork" jobId={RUN_ID} />);
  return { request, subscriptions };
}

describe('a greenfield sync run', () => {
  it('is read from the path that owns it', async () => {
    const { request } = mount();
    await flush();

    expect(request.mock.calls[0][0].path)
      .toBe(`/api/workspaces/fork/upstream/syncs/${encodeURIComponent(RUN_ID)}`);
    expect(screen.getByText('RUN #1')).toBeTruthy();
  });

  it('offers no control the flow does not have', async () => {
    mount();
    await flush();

    // The retired path's pause, skip, resume, close and delete are its own.
    expect(screen.queryByRole('button', { name: /Close run/ })).toBeNull();
    expect(screen.queryByLabelText('Delete run')).toBeNull();
    // The composer stays, inert and saying why, rather than vanishing.
    const composer = screen.getByLabelText('Steer the run') as HTMLInputElement;
    expect(composer.disabled).toBe(true);
    expect(composer.placeholder).toBe('Steering this run is not wired yet');
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

  it('reports repair turns where a dollar ceiling does not exist', async () => {
    mount();
    await flush();

    expect(document.querySelector('.sr-session__stat[title]')?.getAttribute('title'))
      .toBe('48 conflict-repair turns left');
  });
});
