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
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import ResizeHandle from '../ResizeHandle';
import { usePaneWidth } from '../ui/shell';
import { useSidebarWidth } from '../ui/shell/useSidebarWidth';
import { ConfirmDialog } from './ConfirmDialog';
import {
  ParkIcon, PauseIcon, PlayIcon, PullRequestIcon, SendIcon, SkipIcon,
} from './WorkspaceSyncIcons';
import WorkspaceSyncRunLog, { TranscriptTool } from './WorkspaceSyncRunLog';
import WorkspaceSyncRunQueue from './WorkspaceSyncRunQueue';
import {
  elapsedLabel, fixupsByPick, isClosedSync, isLiveSync, isWatchingSync, money, syncNowLine,
  syncPhase, syncQueue, sessionTranscriptPath, transcriptEntries,
  type TranscriptEntry,
} from './syncRunModel';
import {
  workspaceApi,
  type CiHarnessWatchSnapshotDto,
  type UpstreamCherryPickJobDto,
  type UpstreamCherryPickRunDto,
} from './workspaceApi';

const REFRESH_MS = 2_000;
/** The run's own columns, so a narrow pull request pane can be widened. */
const QUEUE_WIDTH_KEY = 'bytequay.syncRun.queueWidth';
const PR_WIDTH_KEY = 'bytequay.syncRun.prPaneWidth';
const PR_WIDTH_DEFAULT = 460;
const PR_WIDTH_MIN = 340;
const PR_WIDTH_MAX = 1000;
/** A long turn can run hundreds of tool calls; the panel shows the tail. */
const MAX_LIVE_ENTRIES = 40;

/**
 * The cockpit for one upstream sync run: the commit queue on the left, what the
 * run is doing plus every command it executed in the centre, and the pull
 * request it ends at on the right.
 */
export default function WorkspaceSyncRunPage({
  workspaceId, jobId, syncs = [], onBack, onOpenSync, onNewSync, rightPane,
}: {
  workspaceId: string;
  jobId: string;
  /** The workspace's other runs, listed at the top of the queue column. */
  syncs?: UpstreamCherryPickJobDto[];
  onBack?: () => void;
  onOpenSync?: (jobId: string) => void;
  onNewSync?: () => void;
  /** Rendered beside the cockpit. The CI Harness passes its pull request pane
   *  here so one run reads as one page across both phases. */
  rightPane?: ReactNode;
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
  /** Phase 2's watch, so the run says what it is doing after the picks land. */
  const [harness, setHarness] = useState<CiHarnessWatchSnapshotDto | null>(null);
  const [prOpen, setPrOpen] = useState(true);
  const { sidebarWidth: queueWidth, shellRef, onResize: onQueueResize } =
    useSidebarWidth(QUEUE_WIDTH_KEY, 300);
  const { paneWidth, bodyRef, onResize: onPaneResize } =
    usePaneWidth(PR_WIDTH_KEY, PR_WIDTH_DEFAULT, PR_WIDTH_MIN, PR_WIDTH_MAX);
  // Both drags measure the same element — the queue from its left edge, the
  // pane from its right — so the two hooks share one ref.
  const pageRef = useCallback((node: HTMLDivElement | null) => {
    if (node === null) return;
    shellRef.current = node;
    bodyRef.current = node;
  }, [bodyRef, shellRef]);

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
  // Phase 2 runs on after the picks land, so the refresh outlives phase 1.
  const watchId = run !== null && isWatchingSync(run.job) ? run.job.harnessWatchId : null;
  // A stopped watch is the one status nothing more happens under. Green is not:
  // the harness keeps looking, and a branch can go red again under a green.
  const harnessStatus = harness?.status ?? null;
  const moving = live || (watchId !== null && harnessStatus !== 'stopped');
  useEffect(() => {
    if (!moving) return undefined;
    const timer = window.setInterval(() => {
      // A dropped poll keeps the last complete run on screen rather than
      // blanking a view someone may have walked away from.
      void load().catch(() => {});
    // Phase 2 adds nothing to this log and can last hours; re-reading the whole
    // run every two seconds through it is work nobody sees.
    }, live ? REFRESH_MS : REFRESH_MS * 5);
    return () => window.clearInterval(timer);
  }, [moving, live, load]);

  // What phase 2 is doing. Its own cadence is a cycle every five minutes, so
  // this is deliberately slower than the run poll — the status word is all it
  // feeds, and the live stream carries anything happening between cycles.
  useEffect(() => {
    if (watchId === null) {
      setHarness(null);
      return undefined;
    }
    let cancelled = false;
    const read = () => workspaceApi.harnessWatch(workspaceId, watchId)
      .then(next => { if (!cancelled) setHarness(next); })
      .catch(() => { /* keep the last status rather than blanking it */ });
    void read();
    if (harnessStatus === 'stopped') return () => { cancelled = true; };
    const timer = window.setInterval(() => { void read(); }, REFRESH_MS * 2);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [watchId, workspaceId, harnessStatus]);

  // The turn in flight. The run log only gains a line when a turn ends, so
  // without this a pick that compiles for minutes looks like a stalled run.
  useEffect(() => {
    if (!live) return undefined;
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

  // The same, for a harness round. Its turns are the ones a reader has had no
  // way to watch: the round used to say "handing over" and then nothing.
  useEffect(() => {
    if (watchId === null) return undefined;
    return window.bridge.subscribeHarnessStream(watchId, event => {
      const entries = transcriptEntries(event.data);
      if (entries.length === 0) return;
      if (entries.some(entry => entry.kind === 'result')) {
        setAgentLive([]);
        return;
      }
      setAgentLive(current => [...current, ...entries].slice(-MAX_LIVE_ENTRIES));
    });
  }, [watchId]);

  // Nothing is running under either key, so the panel has nothing to show.
  useEffect(() => {
    if (!live && watchId === null) setAgentLive([]);
  }, [live, watchId]);

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
  const transcriptPath = sessionTranscriptPath(job.worktreePath, job.agentSessionId);
  // The pull request belongs beside the run, never in a browser tab.
  const canOpenPr = rightPane !== undefined;
  const showRight = rightPane !== undefined && prOpen;

  return (
    <div
      ref={pageRef}
      className={`sr-page${showRight ? ' with-right' : ''}`}
      style={{
        gridTemplateColumns: showRight
          ? `${queueWidth}px minmax(0, 1fr) ${paneWidth}px`
          : `${queueWidth}px minmax(0, 1fr)`,
      }}>
      <WorkspaceSyncRunQueue job={job} commits={run.commits} onBack={onBack}
        fixups={fixupsByPick(run.events)} harness={harness}
        syncs={syncs} onOpenSync={onOpenSync} onNewSync={onNewSync}
        onFixNow={watchId === null ? undefined : () => act(
          () => workspaceApi.runHarnessWatch(workspaceId, watchId, true))} />
      <ResizeHandle className="sr-resize" ariaLabel="Resize the commit queue"
        onResize={onQueueResize} style={{ left: queueWidth - 2 }} />
      <div className="sr-main">
        <header className="sr-topbar">
          {/* The branch and the worktree path are already the queue column's
              title and footer; repeating them here only ate the top bar. */}
          <span className="sr-topbar__badge">SYNC RUN</span>
          <span className="sr-topbar__grow" />
          <span className={`sr-phase is-${phaseTone(job.status)}`}>{syncPhase(job)}</span>
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

        {job.agentSessionId !== null && (
          // The session the whole run shares. `claude --resume` continues a
          // conversation rather than attaching to a live one, so the way to
          // watch from a terminal is to tail the session's own transcript.
          <div className="sr-session">
            <span>AGENT SESSION</span>
            <code title={job.agentSessionId}>{job.agentSessionId}</code>
            {transcriptPath !== null && (
              <button type="button" className="sr-session__copy"
                onClick={() => { void navigator.clipboard.writeText(`tail -f ${transcriptPath}`); }}>
                Copy tail command
              </button>
            )}
          </div>
        )}

        <div className="sr-body">
          <div className="sr-stream">
            <div className={`sr-now${parked || failed || closed ? ' is-parked' : ''}`}>
              <span className="sr-now__dot" aria-hidden />
              <span className="sr-now__label">
                {closed ? 'CLOSED' : parked || failed ? 'PARKED' : 'NOW'}
              </span>
              <span className="sr-now__copy">{syncNowLine(job, queue, harness)}</span>
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
              <WorkspaceSyncRunLog events={run.events} commits={run.commits}
                harness={harness?.milestones} />
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
                      return <TranscriptTool key={index} entry={entry} />;
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
                  // Phase 2's agent takes steering through its own round; the
                  // job's guidance field is only read while the picks run.
                  act(() => (watchId === null
                    ? workspaceApi.guideUpstreamCherryPick(workspaceId, jobId, text)
                    : workspaceApi.runHarnessWatch(workspaceId, watchId, true, text))
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
              className={`sr-rail__item${job.prNumber === null ? ' is-idle' : ''}${
                prOpen ? ' is-on' : ''}`}
              disabled={!canOpenPr}
              title={prTitle(job.prNumber, canOpenPr, prOpen)}
              onClick={() => setPrOpen(open => !open)}>
              <PullRequestIcon />
              <span>PR</span>
              <small>{job.prNumber === null ? 'after push' : `#${job.prNumber}`}</small>
            </button>
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
      {showRight && (
        <>
          <ResizeHandle className="sr-resize" ariaLabel="Resize the pull request panel"
            onResize={onPaneResize} style={{ right: paneWidth - 2 }} />
          <aside className="sr-right">{rightPane}</aside>
        </>
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

function prTitle(prNumber: number | null, canOpen: boolean, open: boolean): string {
  if (prNumber === null) return 'The draft pull request opens once the range is pushed';
  if (!canOpen) return `Pull request #${prNumber} — not ready to show yet`;
  return open ? `Hide pull request #${prNumber}` : `Show pull request #${prNumber} beside the run`;
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
