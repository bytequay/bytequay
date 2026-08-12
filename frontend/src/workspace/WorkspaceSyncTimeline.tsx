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
import { useState, type ReactNode } from 'react';
import { TrafficLights } from '../ui/shell';
import {
  CheckIcon, ChevronIcon, PauseIcon, PlayIcon, ShieldIcon, SyncIcon,
} from './WorkspaceSyncIcons';
import {
  elapsedLabel, isLiveSync, phaseOneEndedAt, syncPhaseNumber, syncProgress,
  syncQueue,
} from './syncRunModel';
import type {
  UpstreamCherryPickCommitDto,
  UpstreamCherryPickEventDto,
  UpstreamCherryPickJobDto,
} from './workspaceApi';

/** Picks shown inside phase 1 before "View all"; the design lists three. */
const RECENT_PICKS = 3;

/**
 * The run's left column: which of the three phases it is in, what each one did,
 * and what it is holding on disk.
 *
 * It used to be a flat commit queue with the workspace's other runs stacked on
 * top — so a run that had pushed had nowhere to show its remote state, and the
 * list of siblings ate the column. The siblings live on the sync home page now,
 * and this column is one run's own story.
 */
export default function WorkspaceSyncTimeline({
  job, commits, events, onBack, onRetryCi,
}: {
  job: UpstreamCherryPickJobDto;
  commits: UpstreamCherryPickCommitDto[];
  events: UpstreamCherryPickEventDto[];
  onBack?: () => void;
  /** Absent while nothing reports phase 2's state; the button stays out. */
  onRetryCi?: () => void;
}) {
  const [picksOpen, setPicksOpen] = useState(false);
  const queue = syncQueue(commits);
  const progress = syncProgress(job);
  const live = isLiveSync(job);
  const phase = syncPhaseNumber(job);
  const first = commits.at(0);
  const last = commits.at(-1);

  return (
    <aside className="st-rail">
      <div className="st-head">
        <TrafficLights hideNavArrows />
        <div className="st-back">
          <button type="button" className="st-back__icon" onClick={onBack}
            title="All upstream syncs" aria-label="All upstream syncs">
            <ChevronIcon direction="left" size={14} />
          </button>
          <button type="button" className="st-back__list" onClick={onBack}>
            <span className="st-back__glyph" aria-hidden><SyncIcon size={12} /></span>
            <span>Upstream syncs</span>
            <span className="st-back__up" aria-hidden>
              <ChevronIcon direction="up" size={11} />
            </span>
          </button>
        </div>

        <div className="st-identity">
          <span className="st-identity__badge" aria-hidden><SyncIcon size={13} /></span>
          <span className="st-identity__body">
            <span className="st-identity__row">
              <code>{first?.shortSha ?? '—'}</code>
              <strong title={first?.subject}>{first?.subject ?? 'no range'}</strong>
            </span>
            <span className="st-identity__span">
              <i aria-hidden />
              <span>{job.appliedCount} commits picked</span>
            </span>
            <span className="st-identity__row">
              <code>{last?.shortSha ?? '—'}</code>
              <strong title={last?.subject}>{last?.subject ?? ''}</strong>
            </span>
          </span>
        </div>
        <div className="st-branch">
          <code title={job.resultBranch}>{job.resultBranch}</code>
          {job.prNumber !== null && <span className="st-branch__pr">PR #{job.prNumber}</span>}
        </div>
      </div>

      <div className="st-rule" />

      <div className="st-timeline__head">
        <span>RUN TIMELINE</span>
        {live && <i className="st-live" aria-hidden />}
        <span className="st-timeline__phase">phase {phase} of 3</span>
      </div>

      <div className="st-phases">
        <Phase
          node={phase > 1
            ? <span className="st-node is-done"><CheckIcon size={9} /></span>
            : <span className="st-node is-live"><SyncIcon size={9} /></span>}
          connectAfter
          title="Local cherry-picks"
          note={elapsedLabel(job.createdAt, phaseOneEndedAt(events) ?? job.updatedAt)}
          onToggle={() => setPicksOpen(open => !open)}
          open={picksOpen}>
          <span className="st-bar">
            <i style={{ width: `${progress.percent}%` }} />
          </span>
          <span className="st-meta">
            <strong>{progress.done} of {progress.total} settled</strong>
            <em>
              {queue.cleanCount} clean · {queue.carriedCount} carried
              {' · '}{job.skippedCount} skipped
            </em>
          </span>
          {picksOpen && (
            <div className="st-picks">
              {queue.done.slice(-RECENT_PICKS).map(commit => (
                <div className="st-pick" key={commit.sha}>
                  <span className={`st-pick__mark is-${commit.state}`} aria-hidden>
                    {commit.state === 'skipped' ? '–' : <CheckIcon size={8} />}
                  </span>
                  <code>{commit.shortSha}</code>
                  <span title={commit.subject}>{commit.subject}</span>
                </div>
              ))}
              {queue.done.length > RECENT_PICKS && (
                <span className="st-picks__all">
                  View all {commits.length} picks
                </span>
              )}
            </div>
          )}
        </Phase>

        <Phase
          current={phase === 2}
          connectBefore
          connectAfter
          node={phaseTwoNode(job, phase)}
          title="CI harness"
          note={phaseTwoNote(job, phase)}
          noteTone={phase === 2 ? 'warn' : undefined}>
          {/* Fix rounds belong to CI Autofix once the range is pushed, and
              nothing reports them back to this run yet. An empty rail would
              read as "no rounds were needed", which is a different claim. */}
          <p className="st-pending">
            {phase < 2
              ? 'Starts when the range is pushed and the pull request opens.'
              : 'Round history is not reported to this view yet.'}
          </p>
        </Phase>

        <Phase
          connectBefore
          node={phase === 3
            ? <span className="st-node is-done"><CheckIcon size={9} /></span>
            : <span className="st-node is-idle" />}
          title={phase === 3 ? 'Cleanup' : 'Review & merge'}
          quiet={phase !== 3}
          note={phaseThreeNote(job, phase)}>
          {phase === 3 && <CleanupReceipt job={job} events={events} />}
        </Phase>
      </div>

      <StatusCard job={job} onRetryCi={onRetryCi} />

      <div className="st-foot">
        <span className="st-foot__glyph" aria-hidden><ShieldIcon size={13} /></span>
        <span>{job.closedAt === null ? 'Isolated worktree' : 'Worktree released'}</span>
        <code title={`branched from ${job.baseRef}`}>
          {job.baseRef.slice(0, 7)} +{job.appliedCount}
        </code>
      </div>
    </aside>
  );
}

/**
 * One rail entry: a gutter carrying the connector and the node, and a body.
 * The connector is drawn per-phase rather than as one line behind all three, so
 * it stops at the last node instead of trailing past it.
 */
function Phase({
  node, title, note, noteTone, children, current = false,
  connectBefore = false, connectAfter = false, onToggle, open, quiet = false,
}: {
  node: ReactNode;
  title: string;
  note?: string;
  noteTone?: 'warn';
  children?: ReactNode;
  current?: boolean;
  connectBefore?: boolean;
  connectAfter?: boolean;
  onToggle?: () => void;
  open?: boolean;
  /** A phase that has not started yet reads as a label, not a heading. */
  quiet?: boolean;
}) {
  const head = (
    <>
      <span className={`st-phase__title${quiet ? ' is-quiet' : ''}`}>{title}</span>
      {note !== undefined && note !== '' && (
        <span className={`st-phase__note${noteTone === 'warn' ? ' is-warn' : ''}`}>
          {note}
        </span>
      )}
      {onToggle !== undefined && (
        <span className={`sr-chevron${open === true ? ' is-open' : ''}`} aria-hidden>
          <ChevronIcon size={13} />
        </span>
      )}
    </>
  );
  return (
    <div className={`st-phase${current ? ' is-current' : ''}`}>
      <div className="st-gutter">
        {connectBefore && <span className="st-line is-before" aria-hidden />}
        {connectAfter && <span className="st-line is-after" aria-hidden />}
        {node}
      </div>
      <div className="st-phase__body">
        {onToggle === undefined
          ? <div className="st-phase__head">{head}</div>
          : (
            <button type="button" className="st-phase__head is-button"
              aria-expanded={open} onClick={onToggle}>
              {head}
            </button>
          )}
        {children}
      </div>
    </div>
  );
}

/**
 * What the run let go, read off what the teardown actually recorded rather than
 * assumed from the run being closed. A receipt that lists a step the program
 * skipped — a branch already deleted, a worktree that was gone — would be the
 * one part of this surface that lies.
 */
function CleanupReceipt({ job, events }: {
  job: UpstreamCherryPickJobDto;
  events: UpstreamCherryPickEventDto[];
}) {
  const cleanup = events.filter(event => event.kind === 'cleanup');
  return (
    <ul className="st-receipt">
      <li className="is-done">
        <span aria-hidden><CheckIcon size={8} /></span>
        {job.prResult === 'merged' ? 'Pull request merged' : 'Pull request closed'}
      </li>
      {cleanup.map(event => (
        <li key={event.id} className={event.title.includes('not') ? '' : 'is-done'}
          title={event.detail ?? undefined}>
          <span aria-hidden>
            {event.title.includes('not') ? '·' : <CheckIcon size={8} />}
          </span>
          {event.title}
        </li>
      ))}
      {cleanup.length === 0 && (
        <li className="is-pending">
          <span aria-hidden>·</span>
          Teardown has not reported yet
        </li>
      )}
    </ul>
  );
}

function StatusCard({ job, onRetryCi }: {
  job: UpstreamCherryPickJobDto;
  onRetryCi?: () => void;
}) {
  const parked = job.closedAt === null
    && (job.status === 'PAUSED_CONFLICT' || job.status === 'FAILED');
  const live = isLiveSync(job);
  return (
    <div className={`st-status${parked ? ' is-parked' : ''}`}>
      <div className="st-status__head">
        <span className={`st-status__dot${live ? ' is-live' : ''}`} aria-hidden />
        <strong>{statusTitle(job)}</strong>
        <span className="st-status__time">
          {elapsedLabel(job.updatedAt)} ago
        </span>
      </div>
      <p>{statusBody(job)}</p>
      {parked && onRetryCi !== undefined && (
        <button type="button" className="st-status__action" onClick={onRetryCi}>
          <PlayIcon />Retry failing checks
        </button>
      )}
    </div>
  );
}

function statusTitle(job: UpstreamCherryPickJobDto): string {
  if (job.closedAt !== null) return 'Run closed';
  if (job.status === 'FAILED') return 'Stopped';
  if (job.status === 'PAUSED_CONFLICT') return 'Parked for your review';
  if (job.status === 'COMPLETED') {
    return job.prNumber === null ? 'Range complete' : 'Parked for your review';
  }
  return job.pauseRequested ? 'Pausing' : 'Picking';
}

function statusBody(job: UpstreamCherryPickJobDto): string {
  if (job.closedAt !== null) {
    return 'The worktree is gone; the branch and this log are kept.';
  }
  if (job.status === 'FAILED') {
    return job.errorMessage ?? 'The run stopped. Durable progress is kept.';
  }
  if (job.status === 'PAUSED_CONFLICT') {
    return job.errorMessage
      ?? 'Nothing is pushed until you resume.';
  }
  if (job.status === 'COMPLETED') {
    return job.prNumber === null
      ? `${job.appliedCount} picks are on ${job.resultBranch}. Nothing was pushed.`
      : `Draft PR #${job.prNumber} is open and waiting for your review.`;
  }
  return `Picking ${job.appliedCount + job.skippedCount + 1} of ${job.requestedCount}.`;
}

function phaseTwoNode(job: UpstreamCherryPickJobDto, phase: number): ReactNode {
  if (phase > 2) return <span className="st-node is-done"><CheckIcon size={9} /></span>;
  if (phase < 2) return <span className="st-node is-idle" />;
  return <span className="st-node is-warn"><PauseIcon size={8} /></span>;
}

function phaseTwoNote(job: UpstreamCherryPickJobDto, phase: number): string {
  if (phase < 2) return 'not started';
  if (phase > 2) return 'done';
  return 'parked · waiting on you';
}

function phaseThreeNote(job: UpstreamCherryPickJobDto, phase: number): string {
  if (phase < 3) return 'waiting on you';
  return job.prResult === 'merged' ? 'merged · released' : 'closed · released';
}
