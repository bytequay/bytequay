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
import { useCallback, useEffect, useRef, useState } from 'react';
import { ConfirmDialog } from './ConfirmDialog';
import {
  LocalBuildIcon, ParkIcon, PauseIcon, PlayIcon, PullRequestIcon,
  SendIcon, ShieldIcon, SkipIcon,
} from './WorkspaceSyncIcons';
import WorkspaceSyncRunLog from './WorkspaceSyncRunLog';
import WorkspaceSyncRunQueue from './WorkspaceSyncRunQueue';
import {
  elapsedLabel, isClosedSync, isLiveSync, money, syncNowLine, syncPhase, syncQueue,
  transcriptEntries, worktreeLabel, type TranscriptEntry,
} from './syncRunModel';
import { workspaceApi, type UpstreamCherryPickRunDto } from './workspaceApi';

const REFRESH_MS = 2_000;
/** A long turn can run hundreds of tool calls; the panel shows the tail. */
const MAX_LIVE_ENTRIES = 40;

/**
 * The cockpit for one upstream sync run: the commit queue on the left, what the
 * run is doing plus every command it executed in the centre, and the pull
 * request it ends at on the right.
 */
export default function WorkspaceSyncRunPage({
  workspaceId, jobId, onBack, onOpenHarness,
}: {
  workspaceId: string;
  jobId: string;
  onBack?: () => void;
  onOpenHarness?: (watchId: string) => void;
}) {
  const [run, setRun] = useState<UpstreamCherryPickRunDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [guidance, setGuidance] = useState('');
  const [closing, setClosing] = useState(false);
  const streamRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);
  /** What the current agent turn has said and run, as it arrives. */
  const [agentLive, setAgentLive] = useState<TranscriptEntry[]>([]);

  const load = useCallback(
    () => workspaceApi.upstreamCherryPickRun(workspaceId, jobId).then(setRun),
    [jobId, workspaceId],
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void load()
      .catch(reason => { if (!cancelled) setError(errorMessage(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [load]);

  const live = run !== null && (isLiveSync(run.job) || run.job.pauseRequested);
  useEffect(() => {
    if (!live) return undefined;
    const timer = window.setInterval(() => {
      // A dropped poll keeps the last complete run on screen rather than
      // blanking a view someone may have walked away from.
      void load().catch(() => {});
    }, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [live, load]);

  // The turn in flight. The run log only gains a line when a turn ends, so
  // without this a pick that compiles for minutes looks like a stalled run.
  useEffect(() => {
    if (!live) {
      setAgentLive([]);
      return undefined;
    }
    return window.bridge.subscribeSyncRunStream(jobId, event => {
      const entries = transcriptEntries(event.data);
      if (entries.length === 0) return;
      // A result line ends the turn; the durable transcript takes over from
      // there, so the live panel clears rather than showing it twice.
      if (entries.some(entry => entry.kind === 'result')) {
        setAgentLive([]);
        return;
      }
      setAgentLive(current => [...current, ...entries].slice(-MAX_LIVE_ENTRIES));
    });
  }, [live, jobId]);

  // Follow the log only while the reader is already at the end, so scrolling
  // back through a three-hour run is not yanked forward by the next command.
  useEffect(() => {
    const stream = streamRef.current;
    if (stream === null || !atBottomRef.current) return;
    stream.scrollTop = stream.scrollHeight;
  }, [run, agentLive]);

  const act = (call: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    void call()
      .then(() => load())
      .catch(reason => setError(errorMessage(reason)))
      .finally(() => setBusy(false));
  };

  if (loading) return <div className="sr-loading" role="status">Loading sync run…</div>;
  if (run === null) {
    return (
      <div className="sr-loading" role="alert">
        {error ?? 'This sync run is no longer available.'}
        <button type="button" onClick={onBack}>Back to workspace</button>
      </div>
    );
  }

  const { job } = run;
  const queue = syncQueue(run.commits);
  const closed = isClosedSync(job);
  const parked = !closed && job.status === 'PAUSED_CONFLICT';
  const failed = !closed && job.status === 'FAILED';
  const running = isLiveSync(job);
  // The agent bounds itself now, so the budget is the only hard stop — and one a
  // park offers to lift rather than one that ends the run. Raising by what the
  // run started with keeps the step proportional to what the user chose.
  const outOfBudget = parked && job.spentMilliUsd >= job.budgetMilliUsd;
  const budgetStep = Math.max(100, job.budgetMilliUsd);

  return (
    <div className="sr-page">
      <WorkspaceSyncRunQueue job={job} commits={run.commits} onBack={onBack} />
      <div className="sr-main">
        <header className="sr-topbar">
          <span className="sr-topbar__badge">SYNC RUN</span>
          <strong>{job.sourceBranch} → {run.baseBranch} · {job.resultBranch}</strong>
          <span className="sr-topbar__grow" />
          <span className={`sr-phase is-${phaseTone(job.status)}`}>{syncPhase(job)}</span>
          {job.worktreePath !== null && (
            <code className="sr-topbar__worktree" title={job.worktreePath}>
              {worktreeLabel(job.worktreePath)}
            </code>
          )}
          {running && (
            <button type="button" className="sr-topbar__action" disabled={busy || job.pauseRequested}
              onClick={() => act(() => workspaceApi.pauseUpstreamCherryPick(workspaceId, jobId))}>
              <PauseIcon />
              {job.pauseRequested ? 'Pausing…' : 'Pause after this pick'}
            </button>
          )}
          {parked && outOfBudget && (
            <button type="button" className="sr-topbar__action is-primary" disabled={busy}
              title="Raise the ceiling and carry on in the same agent session"
              onClick={() => act(() => workspaceApi.raiseUpstreamCherryPickBudget(
                workspaceId, jobId, budgetStep))}>
              <PlayIcon />Add {money(budgetStep)} and resume
            </button>
          )}
          {parked && (
            <button
              type="button"
              className={`sr-topbar__action${outOfBudget ? '' : ' is-primary'}`}
              disabled={busy}
              onClick={() => act(() => workspaceApi.resumeUpstreamCherryPick(workspaceId, jobId))}>
              <PlayIcon />Resume
            </button>
          )}
          {failed && (
            <button type="button" className="sr-topbar__action is-primary" disabled={busy}
              onClick={() => act(() => workspaceApi.retryUpstreamCherryPick(workspaceId, jobId))}>
              <PlayIcon />Retry
            </button>
          )}
          {!closed && (
            <button type="button" className="sr-topbar__action" disabled={busy}
              title="Stop the run, stop its watch, and remove its worktree"
              onClick={() => setClosing(true)}>
              <CloseIcon />Close run
            </button>
          )}
        </header>

        <div className="sr-body">
          <div className="sr-stream">
            <div className={`sr-now${parked || failed || closed ? ' is-parked' : ''}`}>
              <span className="sr-now__dot" aria-hidden />
              <span className="sr-now__label">
                {closed ? 'CLOSED' : parked || failed ? 'PARKED' : 'NOW'}
              </span>
              <span className="sr-now__copy">{syncNowLine(job, queue)}</span>
              <span className="sr-now__meta">
                {job.appliedCount} picked · {elapsedLabel(
                  job.createdAt, running ? undefined : job.updatedAt)}
              </span>
            </div>

            <div className="sr-stream__scroll" ref={streamRef}
              onScroll={event => {
                const element = event.currentTarget;
                atBottomRef.current = element.scrollHeight - element.scrollTop
                  - element.clientHeight < 40;
              }}>
              <WorkspaceSyncRunLog events={run.events} commits={run.commits} />
              {agentLive.length > 0 && (
                // The turn in flight. Without this a pick that compiles for
                // minutes reads as a stalled run — the log's next line only
                // arrives when the turn is already over.
                <section className="sr-live" aria-live="polite">
                  <header>
                    <span className="sr-live__dot" aria-hidden />
                    Agent working
                  </header>
                  {agentLive.map((entry, index) => {
                    if (entry.kind === 'say') {
                      return <p key={index} className="sr-transcript__say">{entry.text}</p>;
                    }
                    if (entry.kind === 'tool') {
                      return (
                        <div key={index} className="sr-transcript__tool">
                          <b>{entry.name}</b><code>{entry.summary}</code>
                        </div>
                      );
                    }
                    return (
                      <p key={index}
                        className={`sr-transcript__result${entry.failed ? ' is-failed' : ''}`}>
                        {entry.failed ? 'Turn failed' : 'Turn complete'} · {entry.turns} turns
                      </p>
                    );
                  })}
                </section>
              )}
            </div>

            <div className="sr-composer">
              <div className="sr-composer__row">
                <span className={`sr-composer__state${parked || failed || closed ? ' is-parked' : ''}`}>
                  <i aria-hidden />
                  {closed
                    ? 'Closed · the worktree is gone; the branch and this log are kept'
                    : running
                      ? 'Session live · guidance applies from the next action'
                      : outOfBudget
                        ? `Parked · ${money(job.spentMilliUsd)} of ${
                          money(job.budgetMilliUsd)} spent — raise it to carry on`
                        : parked ? 'Parked · nothing is pushed until you resume'
                        : failed ? 'Stopped · durable progress is kept'
                          : 'Run complete · parked for your review'}
                </span>
                <span className="sr-topbar__grow" />
                {parked && queue.current !== null && (
                  <button type="button" className="sr-pill" disabled={busy}
                    onClick={() => act(
                      () => workspaceApi.skipUpstreamCherryPickCommit(workspaceId, jobId))}>
                    <SkipIcon />Skip this commit
                  </button>
                )}
                {running && (
                  <button type="button" className="sr-pill is-warn" disabled={busy || job.pauseRequested}
                    onClick={() => act(
                      () => workspaceApi.pauseUpstreamCherryPick(workspaceId, jobId))}>
                    <ParkIcon />Park now
                  </button>
                )}
              </div>
              <form className="sr-composer__box"
                onSubmit={event => {
                  event.preventDefault();
                  const text = guidance.trim();
                  if (text.length === 0) return;
                  setGuidance('');
                  act(() => workspaceApi.guideUpstreamCherryPick(workspaceId, jobId, text)
                    .catch(reason => {
                      setGuidance(text);
                      throw reason;
                    }));
                }}>
                <input value={guidance} onChange={event => setGuidance(event.target.value)}
                  aria-label="Steer the run"
                  placeholder={'Steer the run — e.g. "prefer our fork’s config names when conflicts touch them"…'} />
                <button type="submit" aria-label="Send guidance"
                  disabled={busy || closed || guidance.trim().length === 0}>
                  <SendIcon />
                </button>
              </form>
              {error !== null && <p className="sr-error" role="alert">{error}</p>}
            </div>
          </div>

          <aside className="sr-rail">
            <button type="button"
              className={`sr-rail__item${job.prNumber === null ? ' is-idle' : ''}`}
              disabled={job.prUrl === null}
              title={job.prNumber === null
                ? 'The draft PR opens once the range is picked and pushed'
                : `Open draft PR #${job.prNumber}`}
              onClick={() => {
                if (job.prUrl !== null) void window.bridge.openInAppBrowser(job.prUrl);
              }}>
              <PullRequestIcon />
              <span>PR</span>
              <small>{job.prNumber === null ? 'after push' : `#${job.prNumber}`}</small>
            </button>
            <span className="sr-rail__rule" />
            <span className={`sr-rail__item is-static${job.localGateUnavailable ? ' is-idle' : ''}`}
              title={job.localGateUnavailable
                ? 'The local compile could not run here — CI carries the verdict'
                : "Each conflicted pick is compiled locally, scoped to its module"}>
              <LocalBuildIcon />
              <span>{job.localGateUnavailable ? 'CI' : 'LOCAL'}</span>
            </span>
            <span className="sr-rail__item is-safe"
              title="Isolated worktree · your checkout is never touched">
              <ShieldIcon size={16} />
              <span>SAFE</span>
            </span>
            {job.harnessWatchId !== null && (
              <button type="button" className="sr-rail__item is-watch"
                title="Open the CI Harness watch driving this PR green"
                onClick={() => onOpenHarness?.(job.harnessWatchId as string)}>
                <PlayIcon size={16} />
                <span>CI</span>
              </button>
            )}
            <span className="sr-topbar__grow" />
            <span className="sr-rail__stat">
              <code>{elapsedLabel(job.createdAt, running ? undefined : job.updatedAt)}</code>
              <small>ELAPSED</small>
            </span>
            <span className="sr-rail__stat"
              title={`${money(job.spentMilliUsd)} of ${money(job.budgetMilliUsd)} budget`}>
              <code>{money(job.spentMilliUsd)}</code>
              <small>SPEND</small>
            </span>
          </aside>
        </div>
      </div>
      {closing && (
        <ConfirmDialog
          title="Close this sync run?"
          body={'The picker stops at the next commit boundary'
            + (job.harnessWatchId === null ? '' : ', its CI Harness watch is stopped')
            + ', and its isolated worktree is removed.'
            + '\n\n'
            + `Anything it committed is kept: ${job.resultBranch}`
            + (job.prNumber === null ? '' : ` and draft PR #${job.prNumber}`)
            + ' and this run\u2019s log all survive. A conflict you were resolving by hand in'
            + ' the worktree does not.'}
          confirmLabel={busy ? 'Closing…' : 'Close run'}
          destructive
          busy={busy}
          onCancel={() => setClosing(false)}
          onConfirm={() => {
            setClosing(false);
            act(() => workspaceApi.closeUpstreamCherryPick(workspaceId, jobId));
          }}
        />
      )}
    </div>
  );
}

function CloseIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden><path d="M6 6l12 12M18 6 6 18" /></svg>
  );
}

function phaseTone(status: UpstreamCherryPickRunDto['job']['status']): string {
  if (status === 'PAUSED_CONFLICT') return 'parked';
  if (status === 'FAILED') return 'failed';
  if (status === 'COMPLETED') return 'done';
  return 'live';
}

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
