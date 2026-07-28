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
type CiAction = Recovery['ci'] extends infer T
  ? T extends { actions: Array<infer A> } ? A : never
  : never;
type CleanupAction = Recovery['cleanup'] extends infer T
  ? T extends { actions: Array<infer A> } ? A : never
  : never;

const CI_LABEL: Record<CiAction, string> = {
  EXTEND_BUDGET: 'Extend budget',
  CONTINUE_WITH_PER_PUSH_APPROVAL: 'Approve each push',
  MANUAL_TAKEOVER: 'Take over manually',
  STOP_AUTOMATION: 'Stop automation',
};

const CLEANUP_LABEL: Record<CleanupAction, string> = {
  RETRY: 'Retry cleanup step',
  WAIVE_OPTIONAL: 'Waive optional step',
};

/** Exact V2 recovery controls; every button carries the projected owner id. */
export function StageRecoveryPrompt({ taskId, recovery, onComplete, onError }: {
  taskId: string;
  recovery: Recovery;
  onComplete: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);

  const runCi = async (action: CiAction) => {
    const ci = recovery.ci;
    if (ci === null) return;
    setBusy(action);
    onError('');
    try {
      const extend = action === 'EXTEND_BUDGET';
      await window.bridge.recoverV2Ci(taskId, ci.episodeId, {
        commandId: crypto.randomUUID(),
        action,
        rerunDelta: extend ? 1 : 0,
        fixDelta: extend ? 1 : 0,
        pushDelta: extend ? 1 : 0,
        reason: `Explicit ${CI_LABEL[action]} action from the Stage recovery card`,
      });
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error ? reason.message : 'Could not recover CI');
    }
    finally {
      setBusy(null);
    }
  };

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

  if (recovery.ci === null && recovery.cleanup === null) return null;

  const title = recovery.ci !== null ? 'CI automation needs a decision' : 'Cleanup needs a decision';
  const detail = recovery.ci !== null
    ? `Reruns ${recovery.ci.rerunCount}/${recovery.ci.rerunLimit} · fixes ${recovery.ci.fixAttemptCount}/${recovery.ci.fixAttemptLimit} · pushes ${recovery.ci.pushCount}/${recovery.ci.pushLimit}`
    : `${recovery.cleanup!.kind.replaceAll('_', ' ').toLowerCase()} · attempts ${recovery.cleanup!.attemptCount}/${recovery.cleanup!.attemptLimit}`;

  return (
    <div className="review-callout" data-testid="stage-recovery-card">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">{title}</div>
        <div className="review-callout__tx">{detail}</div>
        {recovery.cleanup?.error != null && (
          <div className="review-callout__note">{recovery.cleanup.error}</div>
        )}
        <div className="review-callout__actions">
          {recovery.ci?.actions.map(action => (
            <button
              key={action}
              type="button"
              className="review-callout__btn"
              disabled={busy !== null}
              onClick={() => { void runCi(action); }}
            >
              {busy === action ? 'Working…' : CI_LABEL[action]}
            </button>
          ))}
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
        </div>
      </div>
    </div>
  );
}
