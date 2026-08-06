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
import { usePersistentToggle } from '../ui/shell/usePersistentToggle';
import { SyncNavRow } from './WorkspaceSyncCards';
import {
  CheckIcon, ChevronIcon, PlusIcon, ShieldIcon, SyncIcon,
} from './WorkspaceSyncIcons';
import {
  elapsedLabel,
  harnessLine,
  isLiveSync,
  syncProgress,
  syncQueue,
  syncTitle,
  type SyncQueue,
} from './syncRunModel';
import type {
  CiHarnessWatchSnapshotDto,
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
  job, commits, fixups, harness, syncs, onOpenSync, onNewSync, onFixNow, onBack,
}: {
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  /** The fixup commit each repaired pick produced, by pick index. */
  fixups: Map<number, string>;
  /** Phase 2's watch, once there is one — null while phase 1 is still picking. */
  harness: CiHarnessWatchSnapshotDto | null;
  /** Every sync run in the workspace, so this column is also the way between them. */
  syncs: UpstreamCherryPickJobDto[];
  onOpenSync?: (jobId: string) => void;
  onNewSync?: () => void;
  /** Stop waiting for the board to settle and fix what has already failed. */
  onFixNow?: () => void;
  onBack?: () => void;
}) {
  const [doneOpen, setDoneOpen] = useState(true);
  const [allDone, setAllDone] = useState(false);
  // Folded state outlives the page: someone working one run does not want the
  // other four back every time they open it.
  const { value: syncsOpen, toggle: toggleSyncs } =
    usePersistentToggle('bytequay.syncRun.syncsOpen', true);
  const queue = syncQueue(commits);
  const progress = syncProgress(job);
  const doneRows = allDone ? queue.done : queue.done.slice(-COLLAPSED_DONE_ROWS);
  const hiddenDone = queue.done.length - doneRows.length;

  return (
    <aside className="sr-queue">
      <div className={`sr-queue__top${syncs.length > 0 ? ' has-syncs' : ''}`}>
        <TrafficLights hideNavArrows />
        <button type="button" className="sr-queue__back" onClick={onBack}>
          <ChevronIcon direction="left" size={13} />
          <span>Back to workspace</span>
        </button>
        {syncs.length > 0 ? (
          // The list is this run's title as well as the way to its siblings, so
          // the identity row below would only say the selected row again.
          <div className={`sr-queue__syncs${syncsOpen ? '' : ' is-folded'}`}>
            <div className="sync-nav__head">
              <button type="button" className="sr-queue__syncs-toggle"
                aria-expanded={syncsOpen} onClick={toggleSyncs}>
                <span className={`sr-chevron${syncsOpen ? ' is-open' : ''}`} aria-hidden>
                  <ChevronIcon size={11} />
                </span>
                <strong>Syncs</strong>
                <small>{syncs.length}</small>
              </button>
              {onNewSync !== undefined && (
                <button type="button" className="sync-nav__add"
                  title="New sync run — pick an upstream range"
                  aria-label="New sync run" onClick={onNewSync}><PlusIcon /></button>
              )}
            </div>
            {syncsOpen && syncs.map(row => (
              <SyncNavRow key={row.jobId} job={row} selected={row.jobId === job.jobId}
                onOpen={() => onOpenSync?.(row.jobId)} />
            ))}
          </div>
        ) : (
          <div className="sr-queue__identity">
            <span className="sr-queue__badge" aria-hidden><SyncIcon size={13} /></span>
            <strong>{syncTitle(job)}</strong>
          </div>
        )}
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
            {doneRows.map(commit => (
              <DoneRow key={commit.sha} commit={commit} fixup={fixups.get(commit.index)} />
            ))}
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

      {harness !== null && <HarnessFooter harness={harness} onFixNow={onFixNow} />}
      <SafetyFooter job={job} queue={queue} />
    </aside>
  );
}

/**
 * Phase 2, in the one place the reader is already watching. The picks stop
 * moving once the range lands, and without this the run looks finished while
 * the harness is still driving the pull request green.
 */
function HarnessFooter({ harness, onFixNow }: {
  harness: CiHarnessWatchSnapshotDto;
  onFixNow?: () => void;
}) {
  const line = harnessLine(harness);
  // Two states worth a nudge: waiting on a suite that runs for an hour while
  // other checks are already red, and stopped short — which nothing polls, so
  // the run sits there until a person restarts it.
  const waiting = harness.status === 'watching' || harness.status === 'handoff';
  const stopped = harness.status === 'needs_attention';
  return (
    <div className={`sr-queue__phase2 is-${line.tone}`}>
      <div className="sr-queue__phase2-head">
        <span className="sr-queue__phase2-dot" aria-hidden />
        <span>PHASE 2 · CI HARNESS</span>
        {line.checkedAtMs !== null && (
          <em>checked {elapsedLabel(new Date(line.checkedAtMs).toISOString())} ago</em>
        )}
      </div>
      <strong>{line.label}</strong>
      {line.detail !== null && line.detail.length > 0 && <span>{line.detail}</span>}
      {(waiting || stopped) && onFixNow !== undefined && (
        <button type="button" className="sr-queue__phase2-now" onClick={onFixNow}
          title={stopped
            ? 'Start another round on the checks that are red now'
            : 'Do not wait for the checks still running — fix what has already failed'}>
          {stopped ? 'Try again on what is failing' : 'Fix what has failed so far'}
        </button>
      )}
    </div>
  );
}

function DoneRow({ commit, fixup }: {
  commit: UpstreamCherryPickCommitDto;
  fixup?: string;
}) {
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
      {carried && fixup !== undefined && (
        // The repair's own commit. A repair that was a no-op made none, and
        // naming the conflict instead told the reader nothing they could use.
        <div className="sr-queue__note">
          <span className="sr-queue__elbow" aria-hidden />
          <code>fixup</code>
          <code className="sr-queue__note-sha">{fixup}</code>
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
