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
import { beforeAll, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import type { Bridge } from './types';

const electron = vi.hoisted(() => ({
  exposeInMainWorld: vi.fn(),
  invoke: vi.fn(),
  on: vi.fn(),
  removeListener: vi.fn(),
}));

vi.mock('electron', () => ({
  contextBridge: { exposeInMainWorld: electron.exposeInMainWorld },
  ipcRenderer: {
    invoke: electron.invoke,
    on: electron.on,
    removeListener: electron.removeListener,
  },
}));

let bridge!: Bridge;

beforeAll(async () => {
  await import('./preload');
  bridge = electron.exposeInMainWorld.mock.calls.find(([key]) => key === 'bridge')?.[1] as Bridge;
});

beforeEach(() => electron.invoke.mockReset());

describe('Development Brain recovery preload bridge', () => {
  it('forwards only the exact local publish base-sync blocker over IPC', async () => {
    electron.invoke.mockResolvedValue({
      taskId: 'task-1',
      stageId: 'local-stage-1',
      blockerId: 'base-sync-blocker-1',
      episodeId: 'base-sync-episode-1',
      operationId: 'base-sync-operation-1',
    });

    await bridge.approveV2LocalPublishBaseSync(
      'task-1', 'base-sync-blocker-1');

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:local-publish-base-sync:approve',
      { taskId: 'task-1', blockerId: 'base-sync-blocker-1' },
    );
  });

  it('forwards one exact local publish base-sync extension over IPC', async () => {
    const command = {
      commandId: 'base-sync-extension-1',
      reason: 'Explicit one-attempt local base-sync extension',
    };
    electron.invoke.mockResolvedValue({ retryEpisodeId: 'episode-4' });

    await bridge.extendV2LocalPublishBaseSync(
      'task-1', 'episode-3', 'blocker-3', command);

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:local-publish-base-sync:extend',
      {
        taskId: 'task-1',
        episodeId: 'episode-3',
        blockerId: 'blocker-3',
        command,
      },
    );
  });

  it('forwards the exact failed TaskTurn and idempotent command over IPC', async () => {
    const command = {
      blockerId: 'blocker-1',
      commandId: 'command-1',
      reason: 'Explicit Retry Development Brain review action',
    };
    electron.invoke.mockResolvedValue({ replacementTurnId: 'turn-2' });

    await bridge.recoverV2DevelopmentBrainReview('task-1', 'turn-1', command);

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:development-brain:recover',
      { taskId: 'task-1', failedTurnId: 'turn-1', command },
    );
  });

  it('forwards an exact failed BranchSync Brain TaskTurn over IPC', async () => {
    const command = {
      blockerId: 'remote-blocker-1',
      commandId: 'remote-command-1',
      reason: 'Explicit Retry Branch sync Brain review action',
    };
    electron.invoke.mockResolvedValue({ replacementTurnId: 'turn-2' });

    await bridge.recoverV2BranchSyncBrainReview(
      'task-1', 'turn-1', command);

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:branch-sync-brain:recover',
      { taskId: 'task-1', failedTurnId: 'turn-1', command },
    );
  });

  it('forwards an exact exhausted BranchSync command over IPC', async () => {
    const command = {
      blockerId: 'branch-blocker-1',
      commandId: 'branch-command-1',
      action: 'STOP_AUTOMATION' as const,
      reason: 'Explicit stop',
    };
    electron.invoke.mockResolvedValue({ status: 'SUPPRESSED' });

    await bridge.recoverV2BranchSync(
      'task-1', 'branch-episode-1', command);

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:branch-sync:recover',
      {
        taskId: 'task-1',
        episodeId: 'branch-episode-1',
        command,
      },
    );
  });

  it('forwards an exact worktree quarantine repair command over IPC', async () => {
    const command = {
      blockerId: 'worktree-blocker-1',
      taskEpoch: 3,
      stageId: 'stage-current',
      stageGeneration: 5,
      worktreePath: '/worktrees/task-1',
      expectedBranchName: 'dev/task-1',
      expectedCodeFingerprint: 'fingerprint-1',
      expectedHeadSha: 'head-1',
      expectedBaseSha: 'base-1',
      commandId: 'worktree-command-1',
      action: 'REPAIR_WORKTREE' as const,
      reason: 'Explicit exact repair',
    };
    electron.invoke.mockResolvedValue({ operationId: 'repair-operation-1' });

    await bridge.recoverV2Worktree('task-1', 'quarantine-1', command);

    expect(electron.invoke).toHaveBeenCalledWith(
      'development-flow:worktree:recover',
      { taskId: 'task-1', quarantineId: 'quarantine-1', command },
    );
  });

  it('keeps the public bridge command type exact', () => {
    expectTypeOf<Bridge['approveV2LocalPublishBaseSync']>().toEqualTypeOf<(
      taskId: string,
      blockerId: string,
    ) => Promise<{
      taskId: string;
      stageId: string;
      blockerId: string;
      episodeId: string;
      operationId: string;
    }>>();
    expectTypeOf<Bridge['extendV2LocalPublishBaseSync']>().toEqualTypeOf<(
      taskId: string,
      episodeId: string,
      blockerId: string,
      command: { commandId: string; reason: string },
    ) => Promise<{
      taskId: string;
      stageId: string;
      exhaustedEpisodeId: string;
      blockerId: string;
      commandId: string;
      retryEpisodeId: string;
      operationId: string;
      attemptNo: number;
      attemptLimit: number;
    }>>();
    expectTypeOf<Bridge['recoverV2DevelopmentBrainReview']>().toEqualTypeOf<(
      taskId: string,
      failedTurnId: string,
      command: { blockerId: string; commandId: string; reason: string },
    ) => Promise<unknown>>();
    expectTypeOf<Bridge['recoverV2BranchSyncBrainReview']>().toEqualTypeOf<(
      taskId: string,
      failedTurnId: string,
      command: { blockerId: string; commandId: string; reason: string },
    ) => Promise<unknown>>();
    expectTypeOf<Bridge['recoverV2BranchSync']>().toEqualTypeOf<(
      taskId: string,
      episodeId: string,
      command: {
        blockerId: string;
        commandId: string;
        action: 'MANUAL_TAKEOVER' | 'STOP_AUTOMATION';
        reason: string;
      },
    ) => Promise<unknown>>();
    expectTypeOf<Bridge['recoverV2Worktree']>().toEqualTypeOf<(
      taskId: string,
      quarantineId: string,
      command: {
        blockerId: string;
        taskEpoch: number;
        stageId: string;
        stageGeneration: number;
        worktreePath: string;
        expectedBranchName: string;
        expectedCodeFingerprint: string;
        expectedHeadSha: string;
        expectedBaseSha: string;
        commandId: string;
        action: 'REPAIR_WORKTREE';
        reason: string;
      },
    ) => Promise<unknown>>();
  });
});
