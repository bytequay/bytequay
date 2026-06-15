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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ThreadDto, WorkUnitTaskDto } from '../types';
import { QueuedTaskView } from './QueuedTaskView';

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
});

function queuedTask(overrides: Partial<WorkUnitTaskDto> = {}): WorkUnitTaskDto {
  return {
    id: 't1.k3',
    threadId: 't1',
    seq: 3,
    status: 'PENDING',
    branchName: 'jack/cost-meter',
    worktreePath: '/wt/t1.k3',
    baseBranch: 'main',
    workingDir: '/clone',
    prNumber: null,
    prState: null,
    ciState: null,
    taskType: 'DEVELOP',
    linkedPrNumber: null,
    linkedIssueNumber: null,
    pushedAt: null,
    phase: 'QUEUED',
    agendaJson: null,
    consecutiveAutoPushes: 0,
    linkedPrRef: null,
    openingPrompt: 'Wire the cost meter into the sidebar.',
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: '2026-06-15T12:00:00Z',
    name: 'Wire the cost meter into the sidebar',
    workModel: null,
    ...overrides,
  };
}

function thread(): ThreadDto {
  return {
    id: 't1',
    kind: 'LOGIC_LOOP',
    provider: 'anthropic',
    agentSessionId: null,
    title: 'Cost & tokens',
    status: 'IDLE',
    flow: 'build',
    model: 'claude',
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: '2026-06-15T12:00:00Z',
    updatedAt: '2026-06-15T12:00:00Z',
    endedAt: null,
    errorMessage: null,
    workspaceId: 'ws-default',
    workModel: null,
    activeTask: null,
    queue: [{
      position: 1,
      title: 'Wire the cost meter into the sidebar',
      branchBase: 'MAIN',
      initialPrompt: 'Wire the cost meter into the sidebar.',
      status: 'MATERIALIZED',
      materializedTaskId: 't1.k3',
      createdAt: '2026-06-15T12:00:00Z',
    }],
    parallelSlots: 1,
  };
}

describe('QueuedTaskView', () => {
  it('shows the QUEUED phase chip and the opening-prompt preview', () => {
    render(
      <QueuedTaskView
        threadId="t1"
        task={queuedTask()}
        thread={thread()}
        siblingTasks={[]}
        onBackToTrunk={() => {}}
        onChanged={() => {}}
      />,
    );
    expect(screen.getAllByText('QUEUED').length).toBeGreaterThan(0);
    expect(screen.getByText(/Wire the cost meter into the sidebar\./)).toBeTruthy();
  });

  it('writes the composer input to the opening prompt, never the conversation', async () => {
    const setOpeningPrompt = vi.fn().mockResolvedValue(queuedTask());
    const sendTaskMessage = vi.fn().mockResolvedValue(undefined);
    // @ts-expect-error partial bridge for the test
    window.bridge = { setOpeningPrompt, sendTaskMessage };
    const onChanged = vi.fn();

    render(
      <QueuedTaskView
        threadId="t1"
        task={queuedTask()}
        thread={thread()}
        siblingTasks={[]}
        onBackToTrunk={() => {}}
        onChanged={onChanged}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('Add to the opening prompt…'), {
      target: { value: 'Also add a drill-in arrow.' },
    });
    fireEvent.click(screen.getByText('↵ Set as opening'));

    await waitFor(() => expect(setOpeningPrompt).toHaveBeenCalledTimes(1));
    expect(setOpeningPrompt).toHaveBeenCalledWith('t1', 't1.k3', 'Also add a drill-in arrow.', 'append');
    expect(sendTaskMessage).not.toHaveBeenCalled();
    expect(onChanged).toHaveBeenCalled();
  });
});
