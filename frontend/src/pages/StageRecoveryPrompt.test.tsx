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
import { afterEach, expect, it, vi } from 'vitest';
import { StageRecoveryPrompt } from './StageRecoveryPrompt';
import type { StageDetailData } from '../types/brainView';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

it('sends the projected CI episode instead of inferring a latest failure', async () => {
  const recoverV2Ci = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = { recoverV2Ci };
  const complete = vi.fn();

  render(<StageRecoveryPrompt
    taskId="task-exact"
    recovery={{
      ci: {
        episodeId: 'episode-exact',
        rerunCount: 1,
        rerunLimit: 1,
        fixAttemptCount: 2,
        fixAttemptLimit: 2,
        pushCount: 1,
        pushLimit: 1,
        actions: ['EXTEND_BUDGET', 'CONTINUE_WITH_PER_PUSH_APPROVAL'],
      },
      cleanup: null,
    }}
    onComplete={complete}
    onError={() => {}}
  />);

  fireEvent.click(screen.getByRole('button', { name: 'Extend budget' }));
  await waitFor(() => expect(recoverV2Ci).toHaveBeenCalledWith(
    'task-exact',
    'episode-exact',
    expect.objectContaining({
      action: 'EXTEND_BUDGET',
      rerunDelta: 1,
      fixDelta: 1,
      pushDelta: 1,
    }),
  ));
  expect(complete).toHaveBeenCalledOnce();
});

it('offers only the exact Cleanup actions projected by the owner', async () => {
  const recoverV2Cleanup = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = { recoverV2Cleanup };

  render(<StageRecoveryPrompt
    taskId="task-exact"
    recovery={{
      ci: null,
      cleanup: {
        stepId: 'cleanup-step-exact',
        kind: 'DELETE_REMOTE_BRANCH',
        requirement: 'OPTIONAL',
        attemptCount: 3,
        attemptLimit: 3,
        error: 'GitHub was unavailable',
        actions: ['WAIVE_OPTIONAL'],
      },
    }}
    onComplete={() => {}}
    onError={() => {}}
  />);

  expect(screen.queryByRole('button', { name: 'Retry cleanup step' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Waive optional step' }));
  await waitFor(() => expect(recoverV2Cleanup).toHaveBeenCalledWith(
    'task-exact',
    'cleanup-step-exact',
    expect.objectContaining({ action: 'WAIVE_OPTIONAL' }),
  ));
});

it('approves the projected local publish blocker without a generic CI command', async () => {
  const approve = vi.fn().mockResolvedValue({
    taskId: 'task-1',
    stageId: 'local-stage-1',
    blockerId: 'base-sync-blocker-1',
    episodeId: 'base-sync-episode-1',
    operationId: 'base-sync-operation-1',
  });
  (window as unknown as { bridge: unknown }).bridge = {
    approveV2LocalPublishBaseSync: approve,
  };
  const onComplete = vi.fn();
  const recovery: NonNullable<StageDetailData['recovery']> = {
    ci: null,
    cleanup: null,
    replacement: null,
    failure: null,
    localPublishBaseSync: {
      blockerId: 'base-sync-blocker-1',
      blockerType: 'LOCAL_PUBLISH_BASE_SYNC_REQUIRED',
      episodeId: null,
      sourceBaseSha: 'base-old',
      targetBaseSha: 'base-new',
      attemptNo: null,
      attemptLimit: null,
      message: 'The remote base moved before the first push',
    },
  };

  render(<StageRecoveryPrompt
    taskId="task-1"
    recovery={recovery}
    onComplete={onComplete}
    onError={vi.fn()}
  />);

  expect(screen.getByText('Local publish needs a decision')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Approve base sync' }));

  await waitFor(() => expect(approve).toHaveBeenCalledWith(
    'task-1', 'base-sync-blocker-1'));
  expect(onComplete).toHaveBeenCalledOnce();
});

it('extends only the exact exhausted local base-sync episode by one attempt', async () => {
  const extend = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = {
    extendV2LocalPublishBaseSync: extend,
  };
  const onComplete = vi.fn();

  render(<StageRecoveryPrompt
    taskId="task-1"
    recovery={{
      ci: null,
      cleanup: null,
      replacement: null,
      failure: null,
      localPublishBaseSync: {
        blockerId: 'base-sync-exhausted-1',
        blockerType: 'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED',
        episodeId: 'base-sync-episode-3',
        sourceBaseSha: 'base-old',
        targetBaseSha: 'base-new',
        attemptNo: 3,
        attemptLimit: 3,
        message: 'Local base-sync attempts are exhausted',
      },
    }}
    onComplete={onComplete}
    onError={vi.fn()}
  />);

  fireEvent.click(screen.getByRole(
    'button', { name: 'Extend by one attempt' }));
  await waitFor(() => expect(extend).toHaveBeenCalledWith(
    'task-1', 'base-sync-episode-3', 'base-sync-exhausted-1',
    expect.objectContaining({
      commandId: expect.any(String),
      reason: expect.stringContaining('one-attempt'),
    }),
  ));
  expect(onComplete).toHaveBeenCalledOnce();
});

it('routes exhausted BranchSync controls through the BranchSync owner', async () => {
  const recoverV2BranchSync = vi.fn().mockResolvedValue({});
  const recoverV2Ci = vi.fn();
  (window as unknown as { bridge: unknown }).bridge = {
    recoverV2BranchSync,
    recoverV2Ci,
  };

  render(<StageRecoveryPrompt
    taskId="task-branch"
    recovery={{
      ci: null,
      cleanup: null,
      branchSync: {
        episodeId: 'branch-episode-1',
        blockerId: 'branch-blocker-1',
        message: 'Branch synchronization attempts are exhausted',
        attemptCount: 8,
        attemptLimit: 8,
        actions: ['MANUAL_TAKEOVER', 'STOP_AUTOMATION'],
      },
    }}
    onComplete={vi.fn()}
    onError={vi.fn()}
  />);

  expect(screen.getByText(/attempts 8\/8/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Stop automation' }));
  await waitFor(() => expect(recoverV2BranchSync).toHaveBeenCalledWith(
    'task-branch',
    'branch-episode-1',
    expect.objectContaining({
      blockerId: 'branch-blocker-1',
      action: 'STOP_AUTOMATION',
      commandId: expect.any(String),
    }),
  ));
  expect(recoverV2Ci).not.toHaveBeenCalled();
});

it('requests only the projected exact worktree quarantine repair', async () => {
  const recoverV2Worktree = vi.fn().mockResolvedValue({});
  (window as unknown as { bridge: unknown }).bridge = { recoverV2Worktree };
  const onComplete = vi.fn();

  render(<StageRecoveryPrompt
    taskId="task-worktree"
    recovery={{
      ci: null,
      cleanup: null,
      worktreeQuarantine: {
        quarantineId: 'quarantine-1',
        blockerId: 'blocker-1',
        sourceOperationId: 'old-stage-operation',
        taskEpoch: 3,
        stageId: 'stage-current',
        stageGeneration: 5,
        worktreePath: '/worktrees/task-worktree',
        expectedBranchName: 'dev/task-worktree',
        expectedCodeFingerprint: 'fingerprint-1',
        expectedHeadSha: 'head-1',
        expectedBaseSha: 'base-1',
        repairOperationId: null,
        repairStatus: null,
        message: 'The exact source could not be restored',
        actions: ['REPAIR_WORKTREE'],
      },
    }}
    onComplete={onComplete}
    onError={vi.fn()}
  />);

  fireEvent.click(screen.getByRole('button', { name: 'Repair worktree' }));
  await waitFor(() => expect(recoverV2Worktree).toHaveBeenCalledWith(
    'task-worktree',
    'quarantine-1',
    expect.objectContaining({
      blockerId: 'blocker-1',
      taskEpoch: 3,
      stageId: 'stage-current',
      stageGeneration: 5,
      worktreePath: '/worktrees/task-worktree',
      expectedBranchName: 'dev/task-worktree',
      expectedCodeFingerprint: 'fingerprint-1',
      expectedHeadSha: 'head-1',
      expectedBaseSha: 'base-1',
      action: 'REPAIR_WORKTREE',
      commandId: expect.any(String),
    }),
  ));
  expect(onComplete).toHaveBeenCalledOnce();
});

it('shows only quarantine repair when other recovery actions coexist', () => {
  (window as unknown as { bridge: unknown }).bridge = {};

  render(<StageRecoveryPrompt
    taskId="task-worktree"
    recovery={{
      ci: {
        episodeId: 'ci-episode',
        rerunCount: 1,
        rerunLimit: 1,
        fixAttemptCount: 1,
        fixAttemptLimit: 1,
        pushCount: 1,
        pushLimit: 1,
        actions: ['EXTEND_BUDGET'],
      },
      cleanup: {
        stepId: 'cleanup-step',
        kind: 'REMOVE_WORKTREE',
        requirement: 'REQUIRED',
        attemptCount: 1,
        attemptLimit: 1,
        error: 'cleanup failed',
        actions: ['RETRY'],
      },
      localPublishBaseSync: {
        blockerId: 'base-sync-blocker',
        blockerType: 'LOCAL_PUBLISH_BASE_SYNC_REQUIRED',
        episodeId: null,
        sourceBaseSha: 'base-old',
        targetBaseSha: 'base-new',
        attemptNo: null,
        attemptLimit: null,
        message: 'base moved',
      },
      branchSync: {
        episodeId: 'branch-episode',
        blockerId: 'branch-blocker',
        message: 'branch sync exhausted',
        attemptCount: 8,
        attemptLimit: 8,
        actions: ['MANUAL_TAKEOVER'],
      },
      worktreeQuarantine: {
        quarantineId: 'quarantine-1',
        blockerId: 'worktree-blocker',
        sourceOperationId: 'source-operation',
        taskEpoch: 3,
        stageId: 'stage-current',
        stageGeneration: 5,
        worktreePath: '/worktrees/task-worktree',
        expectedBranchName: 'dev/task-worktree',
        expectedCodeFingerprint: 'fingerprint-1',
        expectedHeadSha: 'head-1',
        expectedBaseSha: 'base-1',
        repairOperationId: null,
        repairStatus: null,
        message: 'worktree is quarantined',
        actions: ['REPAIR_WORKTREE'],
      },
    }}
    onComplete={vi.fn()}
    onError={vi.fn()}
  />);

  expect(screen.getByText('Task worktree is quarantined')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Repair worktree' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Extend budget' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Retry cleanup step' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Approve base sync' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Take over manually' })).toBeNull();
});
