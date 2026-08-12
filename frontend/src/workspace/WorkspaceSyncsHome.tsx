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
import { useMemo, useState } from 'react';
import { CheckIcon, PlusIcon, PullRequestIcon } from './WorkspaceSyncIcons';
import {
  elapsedLabel, money, splitSyncRuns, syncChip, syncDetailLine, syncPhaseLabel,
  syncProgress, syncResult,
} from './syncRunModel';
import type { UpstreamCherryPickJobDto } from './workspaceApi';

/** Finished rows shown before "View all"; the design lists five. */
const FINISHED_PREVIEW = 5;

type Window = '7d' | '30d' | 'all';

const WINDOWS: { value: Window; label: string; days: number | null }[] = [
  { value: 'all', label: 'All time', days: null },
  { value: '30d', label: 'Last 30 days', days: 30 },
  { value: '7d', label: 'Last 7 days', days: 7 },
];

/**
 * Every sync run in the workspace: what is still moving, and what is over.
 *
 * This is the surface the nav lands on. The run cockpit used to be, which meant
 * arriving at whichever run happened to be newest and finding the other four
 * listed inside it — so the list now lives here and the cockpit shows one run.
 */
export default function WorkspaceSyncsHome({
  runs, upstreamRepo, targetRepo, onOpenSync, onNewSync,
}: {
  runs: UpstreamCherryPickJobDto[];
  /** `trinodb/trino` — the linked upstream, absent until a relation is resolved. */
  upstreamRepo?: string;
  /** `trino-fork` — this workspace's own repository. */
  targetRepo?: string;
  onOpenSync?: (jobId: string) => void;
  onNewSync?: () => void;
}) {
  const [window, setWindow] = useState<Window>('all');
  const [allFinished, setAllFinished] = useState(false);
  const { running, finished } = useMemo(() => splitSyncRuns(runs), [runs]);

  const days = WINDOWS.find(entry => entry.value === window)?.days ?? null;
  const inWindow = useMemo(() => {
    if (days === null) return finished;
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
    return finished.filter(job => Date.parse(job.updatedAt) >= cutoff);
  }, [days, finished]);
  const rows = allFinished ? inWindow : inWindow.slice(0, FINISHED_PREVIEW);
  const hidden = inWindow.length - rows.length;

  return (
    <div className="sh-page">
      <header className="sh-topbar">
        <h1>Upstream syncs</h1>
        {upstreamRepo !== undefined && (
          <span className="sh-topbar__repos">
            {upstreamRepo} → {targetRepo ?? 'this workspace'}
          </span>
        )}
        <span className="sh-grow" />
        {onNewSync !== undefined && (
          <button type="button" className="sh-primary" onClick={onNewSync}>
            <PlusIcon />New sync run
          </button>
        )}
      </header>

      <div className="sh-scroll">
        <div className="sh-column">
          <div className="sh-section is-running">
            <span className="sh-section__label">RUNNING</span>
            <span className="sh-rule" />
          </div>
          {running.length === 0 ? (
            <p className="sh-empty">
              No sync run is in flight.
              {onNewSync !== undefined && (
                <button type="button" onClick={onNewSync}>Pick an upstream range</button>
              )}
            </p>
          ) : (
            <div className="sh-cards">
              {running.map(job => (
                <RunCard key={job.jobId} job={job}
                  onOpen={() => onOpenSync?.(job.jobId)} />
              ))}
            </div>
          )}

          <div className="sh-section">
            <span className="sh-section__label is-quiet">FINISHED</span>
            <span className="sh-rule" />
            <label className="sh-window">
              <span className="sh-visually-hidden">Finished runs window</span>
              <select value={window}
                onChange={event => {
                  setWindow(event.target.value as Window);
                  setAllFinished(false);
                }}>
                {WINDOWS.map(entry => (
                  <option key={entry.value} value={entry.value}>{entry.label}</option>
                ))}
              </select>
            </label>
          </div>
          <FinishedTable rows={rows} hidden={hidden} total={inWindow.length}
            onOpenSync={onOpenSync} onShowAll={() => setAllFinished(true)} />
        </div>
      </div>
    </div>
  );
}

function RunCard({ job, onOpen }: {
  job: UpstreamCherryPickJobDto;
  onOpen: () => void;
}) {
  const chip = syncChip(job);
  const progress = syncProgress(job);
  const live = job.status === 'QUEUED' || job.status === 'RUNNING';
  return (
    <button type="button" className={`sh-card is-${chip.tone}`} onClick={onOpen}>
      <span className="sh-card__head">
        <span className="sh-run">RUN #{job.runNumber}</span>
        <span className="sh-range">
          {job.rangeFromSha === null ? (
            <span className="sh-range__none">no range</span>
          ) : (
            <>
              <code>{short(job.rangeFromSha)}</code>
              <ArrowIcon />
              <code>{short(job.rangeToSha)}</code>
            </>
          )}
          <span className="sh-range__count">{job.requestedCount} commits</span>
        </span>
        <span className="sh-grow" />
        <span className={`sh-chip is-${chip.tone}`}>
          <i className={chip.live ? 'is-live' : undefined} aria-hidden />
          {chip.label}
        </span>
      </span>
      <span className="sh-card__progress">
        <span className="sh-card__phase">{syncPhaseLabel(job)}</span>
        <span className="sr-progress-bar">
          <i style={{ width: `${progress.percent}%` }} />
        </span>
        <span className="sh-card__count">
          {progress.done} of {progress.total} settled
        </span>
      </span>
      <span className="sh-card__foot">
        <span className="sh-card__detail">{syncDetailLine(job)}</span>
        <span className="sh-grow" />
        {job.prNumber !== null && (
          <>
            <span className="sh-pr">PR #{job.prNumber}</span>
            <span className="sh-dot" aria-hidden>·</span>
          </>
        )}
        <span>{elapsedLabel(job.createdAt, live ? undefined : job.updatedAt)}
          {live ? ' elapsed' : ' active'}</span>
        <span className="sh-dot" aria-hidden>·</span>
        <span>{money(job.spentMilliUsd)}</span>
      </span>
    </button>
  );
}

function FinishedTable({ rows, hidden, total, onOpenSync, onShowAll }: {
  rows: UpstreamCherryPickJobDto[];
  hidden: number;
  total: number;
  onOpenSync?: (jobId: string) => void;
  onShowAll: () => void;
}) {
  return (
    <div className="sh-table">
      <div className="sh-table__head">
        <span>RUN</span>
        <span>RANGE</span>
        <span>RESULT</span>
        <span>PR</span>
        <span>ROUNDS</span>
        <span>COST</span>
        <span className="sh-right">FINISHED</span>
      </div>
      {rows.length === 0 && <p className="sh-empty is-table">Nothing finished in this window.</p>}
      {rows.map(job => {
        const result = syncResult(job);
        return (
          <button type="button" className="sh-table__row" key={job.jobId}
            onClick={() => onOpenSync?.(job.jobId)}>
            <span className="sh-table__run">#{job.runNumber}</span>
            <span className="sh-range">
              {job.rangeFromSha === null
                ? <span className="sh-range__none">no range</span>
                : <code>{short(job.rangeFromSha)}→{short(job.rangeToSha)}</code>}
              <span className="sh-range__count">{job.requestedCount} commits</span>
            </span>
            <span className={`sh-result is-${result.tone}`}>
              <ResultIcon tone={result.tone} />{result.label}
            </span>
            <span className="sh-pr">
              {job.prNumber === null ? '—' : `#${job.prNumber}`}
            </span>
            {/* CI fix rounds are owned by CI Autofix once the range is pushed, and
                nothing reports them back to this list yet — so the column is
                honest about having no number rather than showing a wrong one. */}
            <span className="sh-pending" title="Fix rounds are not reported to this list yet">—</span>
            <span className="sh-table__cost">{money(job.spentMilliUsd)}</span>
            <span className="sh-right sh-table__when">{whenLabel(job.updatedAt)}</span>
          </button>
        );
      })}
      {hidden > 0 && (
        <div className="sh-table__more">
          <button type="button" onClick={onShowAll}>
            View all {total} finished runs
          </button>
        </div>
      )}
    </div>
  );
}

function ResultIcon({ tone }: { tone: string }) {
  if (tone === 'merged') return <PullRequestIcon size={12} />;
  if (tone === 'done') return <CheckIcon size={11} />;
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.6" strokeLinecap="round" aria-hidden>
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

function ArrowIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 12h14" /><path d="m13 6 6 6-6 6" />
    </svg>
  );
}

function short(sha: string | null): string {
  if (sha === null) return '—';
  return sha.length <= 7 ? sha : sha.slice(0, 7);
}

/** Recent runs read better as "3d ago"; older ones as a date. */
function whenLabel(iso: string): string {
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return '';
  const days = Math.floor((Date.now() - parsed) / (24 * 60 * 60 * 1000));
  if (days <= 0) return 'today';
  if (days < 7) return `${days}d ago`;
  return new Date(parsed).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
