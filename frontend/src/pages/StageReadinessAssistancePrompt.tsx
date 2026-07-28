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
import { useEffect, useState } from 'react';
import type {
  ReadinessAssistanceAvailability,
  ReadinessAssistanceKind,
} from '../types';
import { WarnTriangleIcon } from '../ui/TaskBrainDesignIcons';

const DEFAULT_NUDGE = 'This pull request is ready to merge. Could a maintainer please merge it?';

/** Manual-only controls for an exact ready PR the authenticated viewer cannot merge. */
export function StageReadinessAssistancePrompt({ taskId, stageId, onComplete, onError }: {
  taskId: string;
  stageId: string;
  onComplete: () => void;
  onError: (message: string) => void;
}) {
  const [availability, setAvailability] = useState<ReadinessAssistanceAvailability | null>(null);
  const [mode, setMode] = useState<ReadinessAssistanceKind | null>(null);
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setAvailability(null);
    setMode(null);
    setSent(null);
    const getAvailability = window.bridge.getV2ReadinessAssistance;
    if (typeof getAvailability !== 'function') return () => { active = false; };
    void getAvailability(taskId, stageId)
      .then(result => { if (active) setAvailability(result); })
      .catch(reason => {
        if (active) onError(reason instanceof Error
          ? reason.message : 'Could not load merge assistance');
      });
    return () => { active = false; };
  }, [onError, stageId, taskId]);

  const choose = (kind: ReadinessAssistanceKind) => {
    setMode(kind);
    setValue(kind === 'POST_MAINTAINER_NUDGE' ? DEFAULT_NUDGE : '');
  };

  const submit = async () => {
    if (availability === null || mode === null || value.trim().length === 0) return;
    setBusy(true);
    onError('');
    try {
      const authorize = window.bridge.authorizeV2ReadinessAssistance;
      if (typeof authorize !== 'function') {
        throw new Error('Merge assistance is unavailable in this app version');
      }
      await authorize(taskId, stageId, {
        commandId: crypto.randomUUID(),
        taskEpoch: availability.taskEpoch,
        stageGeneration: availability.stageGeneration,
        snapshotId: availability.snapshotId,
        readinessId: availability.readinessId,
        policyId: availability.policyId,
        headSha: availability.headSha,
        baseSha: availability.baseSha,
        kind: mode,
        externalTarget: mode === 'REQUEST_REVIEWER' ? value.trim() : null,
        payload: value.trim(),
      });
      setSent(mode === 'REQUEST_REVIEWER'
        ? `Reviewer request approved for @${value.trim()}.`
        : 'Maintainer nudge approved.');
      setAvailability(null);
      onComplete();
    }
    catch (reason: unknown) {
      onError(reason instanceof Error ? reason.message : 'Could not approve merge assistance');
    }
    finally {
      setBusy(false);
    }
  };

  if (availability === null && sent === null) return null;

  return (
    <div className="review-callout" data-testid="readiness-assistance-card">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">Ready, but you can’t merge</div>
        <div className="review-callout__tx">
          Nothing is posted automatically. Approve one exact GitHub action below.
        </div>
        {sent !== null ? (
          <div className="review-callout__note">{sent}</div>
        ) : mode === null ? (
          <div className="review-callout__actions">
            {availability?.actions.includes('POST_MAINTAINER_NUDGE') === true && (
              <button type="button" className="review-callout__btn"
                onClick={() => choose('POST_MAINTAINER_NUDGE')}>
                Nudge a maintainer
              </button>
            )}
            {availability?.actions.includes('REQUEST_REVIEWER') === true && (
              <button type="button" className="review-callout__btn"
                onClick={() => choose('REQUEST_REVIEWER')}>
                Request reviewer
              </button>
            )}
          </div>
        ) : (
          <>
            <label className="review-callout__note">
              {mode === 'REQUEST_REVIEWER' ? 'GitHub username' : 'Message'}
              {mode === 'REQUEST_REVIEWER' ? (
                <input aria-label="GitHub username" value={value}
                  onChange={event => setValue(event.target.value)} />
              ) : (
                <textarea aria-label="Maintainer nudge" value={value}
                  onChange={event => setValue(event.target.value)} />
              )}
            </label>
            <div className="review-callout__actions">
              <button type="button" className="review-callout__btn"
                disabled={busy || value.trim().length === 0}
                onClick={() => { void submit(); }}>
                {busy ? 'Approving…' : mode === 'REQUEST_REVIEWER'
                  ? 'Request reviewer' : 'Post nudge'}
              </button>
              <button type="button" className="review-callout__btn"
                disabled={busy} onClick={() => setMode(null)}>Cancel</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
