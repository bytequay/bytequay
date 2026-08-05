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
import { TrafficLights } from '../ui/shell';
import { CheckIcon, ChevronIcon, ShieldIcon, SyncIcon } from './WorkspaceSyncIcons';
import {
  elapsedLabel,
  isLiveSync,
  syncProgress,
  syncQueue,
  syncTitle,
  type SyncQueue,
} from './syncRunModel';
import type {
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

const COLLAPSED_DONE_ROWS = 6;

/**
 * The run's left column: what has been picked, what is in flight, and what is
 * waiting. A range can be hundreds of commits long, so neither end is an
 * unbounded list — "done" collapses and "next" is a window.
 */
export default function WorkspaceSyncRunQueue({
  job, commits, onBack,
}: {
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  onBack?: () => void;
}) {
  const [doneOpen, setDoneOpen] = useState(true);
  const [allDone, setAllDone] = useState(false);
  const queue = syncQueue(commits);
  const progress = syncProgress(job);
  const doneRows = allDone ? queue.done : queue.done.slice(-COLLAPSED_DONE_ROWS);
  const hiddenDone = queue.done.length - doneRows.length;

  return (
    <aside className="sr-queue">
      <div className="sr-queue__top">
        <TrafficLights hideNavArrows />
        <button type="button" className="sr-queue__back" onClick={onBack}>
          <ChevronIcon direction="left" size={13} />
          <span>Back to workspace</span>
        </button>
        <div className="sr-queue__identity">
          <span className="sr-queue__badge" aria-hidden><SyncIcon size={13} /></span>
          <strong>{syncTitle(job)}</strong>
        </div>
        <div className="sr-queue__range">
          <code>{commits.at(0)?.shortSha ?? '—'}…{commits.at(-1)?.shortSha ?? '—'}</code>
          <span>{job.requestedCount} selected · {job.skippedCount} skipped</span>
        </div>
        <div className="sr-queue__progress">
          <span className="sr-progress-bar">
            <i style={{ width: `${progress.percent}%` }} />
          </span>
          <span className="sr-queue__progress-meta">
            <strong>{progress.done} of {progress.total} picked</strong>
            <em>{elapsedLabel(job.createdAt, isLiveSync(job) ? undefined : job.updatedAt)}</em>
          </span>
        </div>
      </div>

      <div className="sr-queue__rule" />

      <div className="sr-queue__scroll">
        <button type="button" className="sr-queue__section" aria-expanded={doneOpen}
          onClick={() => setDoneOpen(open => !open)}>
          <span className={`sr-chevron${doneOpen ? ' is-open' : ''}`} aria-hidden>
            <ChevronIcon direction="right" size={11} />
          </span>
          <span className="sr-queue__section-label">DONE · {queue.done.length}</span>
          <span className="sr-queue__section-meta">
            {queue.cleanCount} clean · {queue.carriedCount} carried
          </span>
        </button>
        {doneOpen && (
          <div className="sr-queue__done">
            {hiddenDone > 0 && (
              <button type="button" className="sr-queue__more" onClick={() => setAllDone(true)}>
                View all {queue.done.length}…
              </button>
            )}
            {doneRows.map(commit => <DoneRow key={commit.sha} commit={commit} />)}
          </div>
        )}

        {queue.current !== null && (
          <div className={`sr-queue__current${job.status === 'PAUSED_CONFLICT' ? ' is-parked' : ''}`}>
            <div className="sr-queue__current-head">
              <span className="sr-queue__current-dot" aria-hidden />
              <code>{queue.current.shortSha}</code>
              <span>pick {queue.current.index + 1}</span>
            </div>
            <div className="sr-queue__current-subject">{queue.current.subject}</div>
            <div className="sr-queue__current-state">{currentState(job)}</div>
          </div>
        )}

        <div className="sr-queue__section is-static">
          <span className="sr-queue__section-label">NEXT</span>
          <span className="sr-queue__section-meta">
            {queue.next.length + queue.moreCount} waiting
          </span>
        </div>
        {queue.next.map(commit => (
          <div className="sr-queue__row is-waiting" key={commit.sha}>
            <span className="sr-queue__pending" aria-hidden />
            <code>{commit.shortSha}</code>
            <span title={commit.subject}>{commit.subject}</span>
          </div>
        ))}
        {queue.next.length === 0 && (
          <p className="sr-queue__empty">Nothing left to pick.</p>
        )}
        {queue.moreCount > 0 && (
          <div className="sr-queue__tail">
            <span>+{queue.moreCount} more · ends</span>
            <code>{queue.last?.shortSha}</code>
          </div>
        )}
      </div>

      <SafetyFooter job={job} queue={queue} />
    </aside>
  );
}

function DoneRow({ commit }: { commit: UpstreamCherryPickCommitDto }) {
  if (commit.state === 'skipped') {
    return (
      <div className="sr-queue__row is-skipped">
        <span className="sr-queue__skip" aria-hidden>–</span>
        <code>{commit.shortSha}</code>
        <span title={commit.subject}>{commit.subject}</span>
      </div>
    );
  }
  const carried = commit.state === 'conflicted';
  return (
    <>
      <div className={`sr-queue__row${carried ? ' is-carried' : ' is-clean'}`}>
        <span className="sr-queue__check" aria-hidden><CheckIcon size={8} /></span>
        <code>{commit.shortSha}</code>
        <span title={commit.subject}>{commit.subject}</span>
      </div>
      {carried && (
        <div className="sr-queue__note">
          <span className="sr-queue__elbow" aria-hidden />
          <code>conflict</code>
          <span>resolution carried in the pick</span>
        </div>
      )}
    </>
  );
}

function SafetyFooter({ job, queue }: { job: UpstreamCherryPickJobDto; queue: SyncQueue }) {
  const pushed = job.prNumber !== null;
  return (
    <div className="sr-queue__safety">
      <span className="sr-queue__safety-icon" aria-hidden><ShieldIcon size={13} /></span>
      <span className="sr-queue__safety-copy">
        <strong>
          Isolated worktree · {pushed
            ? `pushed to ${job.resultBranch}`
            : 'nothing pushed'}
        </strong>
        <code title={`branched from ${job.baseRef}`}>
          base {job.baseRef.slice(0, 7)}
          {queue.done.length > 0 ? ` · ${queue.done.length} commits on top` : ''}
        </code>
      </span>
    </div>
  );
}

function currentState(job: UpstreamCherryPickJobDto): string {
  if (job.status === 'PAUSED_CONFLICT') {
    return job.pauseRequested
      ? 'parked at your request · resume when ready'
      : 'parked · git cannot finish this pick';
  }
  if (job.status === 'FAILED') return 'stopped · retry to continue';
  if (job.status === 'QUEUED') return 'queued';
  return job.pauseRequested ? 'picking · pausing after this one' : 'picking…';
}
