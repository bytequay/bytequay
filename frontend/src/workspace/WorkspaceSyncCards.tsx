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
import { CheckIcon, ChevronIcon, SyncIcon } from './WorkspaceSyncIcons';
import {
  elapsedLabel, isLiveSync, syncProgress, syncTitle,
} from './syncRunModel';
import type { UpstreamCherryPickJobDto } from './workspaceApi';

/** The same run as a Today row — one card per section it belongs to. */
export function SyncTodayCard({ job, tone, onOpen }: {
  job: UpstreamCherryPickJobDto;
  tone: 'attention' | 'running' | 'done';
  onOpen: () => void;
}) {
  const progress = syncProgress(job);
  return (
    <button type="button" className={`sync-today is-${tone === 'attention' ? 'attention' : tone}`}
      onClick={onOpen}>
      <span className="sync-today__icon" aria-hidden>
        {tone === 'done' ? <CheckIcon size={15} /> : <SyncIcon size={15} />}
      </span>
      <span className="sync-today__copy">
        <strong>{syncTitle(job)}</strong>
        <span className="sync-today__meta">
          {tone === 'running' && <i aria-hidden />}
          <span>{todayMeta(job, tone)}</span>
        </span>
      </span>
      {tone === 'running' && (
        <span className="sync-today__progress">
          <span className="sr-progress-bar"><i style={{ width: `${progress.percent}%` }} /></span>
          <small>{progress.done} of {progress.total}</small>
        </span>
      )}
      {tone === 'attention' && (
        <span className="sync-today__chip">
          {job.status === 'FAILED' ? 'STOPPED' : 'PARKED'} · {elapsedLabel(job.updatedAt)}
        </span>
      )}
      {tone === 'done' && (
        <span className="sync-today__time">{clock(job.updatedAt)}</span>
      )}
      <span className="sync-today__go" aria-hidden><ChevronIcon size={16} /></span>
    </button>
  );
}

function todayMeta(job: UpstreamCherryPickJobDto, tone: string): string {
  if (tone === 'running') {
    return `picking ${job.appliedCount + 1} of ${job.requestedCount} · running ${
      elapsedLabel(job.createdAt)}`;
  }
  if (tone === 'attention') {
    const pick = job.appliedCount + job.skippedCount + 1;
    const reason = job.errorMessage ?? 'needs you';
    return `${job.status === 'FAILED' ? 'stopped' : 'parked'} on pick ${pick} · ${
      reason} · nothing is pushed`;
  }
  const carried = job.conflictedCount === 0
    ? ''
    : ` · ${job.conflictedCount} conflict${job.conflictedCount === 1 ? '' : 's'} carried`;
  return `green · ${job.appliedCount} picks${carried}${
    job.prNumber === null ? '' : ` · PR #${job.prNumber} parked for your review`}`;
}

function clock(iso: string): string {
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return '';
  return new Date(parsed).toLocaleTimeString(undefined, {
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
}
