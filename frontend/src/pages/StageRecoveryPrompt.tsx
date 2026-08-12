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
import { useState } from 'react';
import type { StageDetailData } from '../types/brainView';
import { WarnTriangleIcon } from '../ui/TaskBrainDesignIcons';

type Recovery = NonNullable<StageDetailData['recovery']>;
type CleanupAction = Recovery['cleanup'] extends infer T
  ? T extends { actions: Array<infer A> } ? A : never
  : never;
type BranchAction = NonNullable<Recovery['branchSync']>['actions'][number];
type WorktreeAction = NonNullable<Recovery['worktreeQuarantine']>['actions'][number];

const CLEANUP_LABEL: Record<CleanupAction, string> = {
  RETRY: 'Retry cleanup step',
  WAIVE_OPTIONAL: 'Waive optional step',
};

const BRANCH_LABEL: Record<BranchAction, string> = {
  MANUAL_TAKEOVER: 'Take over manually',
  STOP_AUTOMATION: 'Stop automation',
};

const WORKTREE_LABEL: Record<WorktreeAction, string> = {
  REPAIR_WORKTREE: 'Repair worktree',
};

/** Exact V2 recovery controls; every button carries the projected owner id. */
export function StageRecoveryPrompt({ taskId, recovery, onComplete, onError }: {
  taskId: string;
  recovery: Recovery;
  onComplete: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);

  const runCleanup = async (action: CleanupAction) => {
    const cleanup = recovery.cleanup;
    if (cleanup === null) return;
    setBusy(action);
    onError('');
    try {
      await window.bridge.recoverV2Cleanup(taskId, cleanup.stepId, {
        commandId: crypto.randomUUID(),
        action,
        reason: `Explicit ${CLEANUP_LABEL[action]} action from the Stage recovery card`,
      });
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error ? reason.message : 'Could not recover Cleanup');
    }
    finally {
      setBusy(null);
    }
  };

  const approveLocalPublishBaseSync = async () => {
    const baseSync = recovery.localPublishBaseSync ?? null;
    if (baseSync === null) return;
    setBusy('LOCAL_PUBLISH_BASE_SYNC');
    onError('');
    try {
      if (baseSync.blockerType === 'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED') {
        if (baseSync.episodeId === null) {
          throw new Error('Exhausted base sync lacks its exact episode');
        }
        await window.bridge.extendV2LocalPublishBaseSync(
          taskId, baseSync.episodeId, baseSync.blockerId, {
            commandId: crypto.randomUUID(),
            reason: 'Explicit one-attempt local base-sync extension',
          });
      }
      else {
        await window.bridge.approveV2LocalPublishBaseSync(
          taskId, baseSync.blockerId);
      }
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error
        ? reason.message : 'Could not approve local publish base sync');
    }
    finally {
      setBusy(null);
    }
  };

  const runBranchSync = async (action: BranchAction) => {
    const branchSync = recovery.branchSync ?? null;
    if (branchSync === null) return;
    setBusy(`BRANCH_SYNC:${action}`);
    onError('');
    try {
      await window.bridge.recoverV2BranchSync(
        taskId, branchSync.episodeId, {
          blockerId: branchSync.blockerId,
          commandId: crypto.randomUUID(),
          action,
          reason: `Explicit ${BRANCH_LABEL[action]} action from the Stage recovery card`,
        });
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error
        ? reason.message : 'Could not recover BranchSync');
    }
    finally {
      setBusy(null);
    }
  };

  const runWorktreeRepair = async (action: WorktreeAction) => {
    const quarantine = recovery.worktreeQuarantine ?? null;
    if (quarantine === null) return;
    setBusy(action);
    onError('');
    try {
      await window.bridge.recoverV2Worktree(
        taskId, quarantine.quarantineId, {
          blockerId: quarantine.blockerId,
          taskEpoch: quarantine.taskEpoch,
          stageId: quarantine.stageId,
          stageGeneration: quarantine.stageGeneration,
          worktreePath: quarantine.worktreePath,
          expectedBranchName: quarantine.expectedBranchName,
          expectedCodeFingerprint: quarantine.expectedCodeFingerprint,
          expectedHeadSha: quarantine.expectedHeadSha,
          expectedBaseSha: quarantine.expectedBaseSha,
          commandId: crypto.randomUUID(),
          action,
          reason: 'Explicit exact worktree restoration from the Stage recovery card',
        });
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error
        ? reason.message : 'Could not repair the Task worktree');
    }
    finally {
      setBusy(null);
    }
  };

  const baseSync = recovery.localPublishBaseSync ?? null;
  const branchSync = recovery.branchSync ?? null;
  const quarantine = recovery.worktreeQuarantine ?? null;
  if (recovery.cleanup === null && baseSync === null && branchSync === null
      && quarantine === null) {
    return null;
  }

  const title = quarantine !== null ? 'Task worktree is quarantined'
    : branchSync !== null ? 'Branch sync needs a decision'
    : baseSync !== null ? 'Local publish needs a decision'
    : 'Cleanup needs a decision';
  const detail = quarantine !== null ? quarantine.message
    : branchSync !== null
      ? `${branchSync.message} · attempts ${branchSync.attemptCount}/${branchSync.attemptLimit}`
    : baseSync !== null ? baseSync.message
    : `${recovery.cleanup!.kind.replaceAll('_', ' ').toLowerCase()} · attempts ${recovery.cleanup!.attemptCount}/${recovery.cleanup!.attemptLimit}`;

  return (
    <div className="review-callout" data-testid="stage-recovery-card">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">{title}</div>
        <div className="review-callout__tx">{detail}</div>
        {quarantine === null && recovery.cleanup?.error != null && (
          <div className="review-callout__note">{recovery.cleanup.error}</div>
        )}
        {quarantine === null && branchSync !== null && (
          <div className="review-callout__note">
            This stops only the exhausted BranchSync episode; it does not reset its budget or start CI work.
          </div>
        )}
        {quarantine !== null && (
          <div className="review-callout__note">
            Normal writers remain blocked until the durable repair proves the frozen HEAD, clean state, and code fingerprint.
          </div>
        )}
        <div className="review-callout__actions">
          {quarantine === null && <>
            {baseSync !== null && (
              <button
                type="button"
                className="review-callout__btn"
                disabled={busy !== null}
                onClick={() => { void approveLocalPublishBaseSync(); }}
              >
                {busy === 'LOCAL_PUBLISH_BASE_SYNC'
                  ? 'Working…'
                  : baseSync.blockerType === 'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED'
                    ? 'Extend by one attempt'
                    : baseSync.blockerType === 'LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED'
                      ? 'Approve one retry'
                      : 'Approve base sync'}
              </button>
            )}
            {recovery.cleanup?.actions.map(action => (
              <button
                key={action}
                type="button"
                className="review-callout__btn"
                disabled={busy !== null}
                onClick={() => { void runCleanup(action); }}
              >
                {busy === action ? 'Working…' : CLEANUP_LABEL[action]}
              </button>
            ))}
            {branchSync?.actions.map(action => (
              <button
                key={action}
                type="button"
                className="review-callout__btn"
                disabled={busy !== null}
                onClick={() => { void runBranchSync(action); }}
              >
                {busy === `BRANCH_SYNC:${action}`
                  ? 'Working…' : BRANCH_LABEL[action]}
              </button>
            ))}
          </>}
          {quarantine?.actions.map(action => (
            <button
              key={action}
              type="button"
              className="review-callout__btn"
              disabled={busy !== null}
              onClick={() => { void runWorktreeRepair(action); }}
            >
              {busy === action ? 'Working…' : WORKTREE_LABEL[action]}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
