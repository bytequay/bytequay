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
  ParkIcon, PauseIcon, PlayIcon, SendIcon, SkipIcon,
} from './WorkspaceSyncIcons';
import WorkspaceSyncFeed from './WorkspaceSyncFeed';
import WorkspaceSyncPublishCard from './WorkspaceSyncPublishCard';
import { TranscriptTool } from './WorkspaceSyncRunLog';
import WorkspaceSyncTimeline from './WorkspaceSyncTimeline';
import {
  elapsedLabel, isClosedSync, isFlowRun, isLiveSync, money, syncNowLine,
  syncPhase, syncQueue, sessionTranscriptPath, transcriptEntries,
  type TranscriptEntry,
} from './syncRunModel';
import {
  workspaceApi,
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
/** The composer is inert on a greenfield run rather than quietly absent. */
const STEERING_UNAVAILABLE = 'Steering this run is not wired yet';

/**
 * The cockpit for one upstream sync run: the commit queue on the left, what the
 * run is doing plus every command it executed in the centre, and the pull
 * request it ends at on the right.
 */
export default function WorkspaceSyncRunPage({
  workspaceId, jobId, onBack, rightPane,
}: {
  workspaceId: string;
  jobId: string;
  onBack?: () => void;
  /** Rendered beside the cockpit so one run and its draft PR read as one page. */
  rightPane?: ReactNode;
}) {
  const [run, setRun] = useState<UpstreamCherryPickRunDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [guidance, setGuidance] = useState('');
  const [closing, setClosing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const streamRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);
  /** What the current agent turn has said and run, as it arrives. */
  const [agentLive, setAgentLive] = useState<TranscriptEntry[]>([]);
  const [prOpen, setPrOpen] = useState(true);
  const { sidebarWidth: queueWidth, shellRef, onResize: onQueueResize } =
    useSidebarWidth(QUEUE_WIDTH_KEY, 292);
  const { paneWidth, bodyRef, onResize: onPaneResize } =
    usePaneWidth(PR_WIDTH_KEY, PR_WIDTH_DEFAULT, PR_WIDTH_MIN, PR_WIDTH_MAX);
  // Both drags measure the same element — the queue from its left edge, the
  // pane from its right — so the two hooks share one ref.
  const pageRef = useCallback((node: HTMLDivElement | null) => {
    if (node === null) return;
    shellRef.current = node;
    bodyRef.current = node;
  }, [bodyRef, shellRef]);

  // A run is read from the path that owns it. The id carries its own domain,
  // so this resolves before any list has loaded.
  const flow = isFlowRun(jobId);
  const load = useCallback(
    () => (flow
      ? workspaceApi.upstreamSyncRun(workspaceId, jobId)
      : workspaceApi.upstreamCherryPickRun(workspaceId, jobId)).then(setRun),
    [flow, jobId, workspaceId],
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
  // An authorized publish is program work the run view has to watch: the run
  // itself is parked, and the pull request appears without it moving.
  const publishing = run?.publishGate != null
    && run.publishGate.state !== 'CONSUMED';
  useEffect(() => {
    if (!live && !publishing) return undefined;
    const timer = window.setInterval(() => {
      // A dropped poll keeps the last complete run on screen rather than
      // blanking a view someone may have walked away from.
      void load().catch(() => {});
    }, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [live, publishing, load]);

  // The turn in flight. The run log only gains a line when a turn ends, so
  // without this a pick that compiles for minutes looks like a stalled run.
  useEffect(() => {
    // The live turn stream belongs to the retired runner. A greenfield run's
    // turns are the flow runtime's, and nothing streams them here yet.
    if (!live || flow) return undefined;
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
  }, [flow, live, jobId]);

  useEffect(() => {
    if (!live) setAgentLive([]);
  }, [live]);

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
  // A greenfield run is bounded by conflict-repair turns instead, so it has no
  // ceiling to raise and never reads as out of budget.
  const budget = job.budgetMilliUsd;
  const outOfBudget = parked && budget !== undefined
    && job.spentMilliUsd >= budget;
  const budgetStep = Math.max(100, budget ?? 0);
  const transcriptPath = sessionTranscriptPath(job.worktreePath, job.agentSessionId);
  // The park before the first push is the publish gate. Nothing leaves the
  // machine until the user authorizes exactly the revision they were shown.
  const gate = run.publishGate !== undefined && run.publishGate !== null
    && run.publishGate.state === 'OPEN' ? run.publishGate : null;
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
      <WorkspaceSyncTimeline job={job} commits={run.commits} events={run.events}
        rounds={run.rounds} onBack={onBack} />
      <ResizeHandle className="sr-resize" ariaLabel="Resize the commit queue"
        onResize={onQueueResize} style={{ left: queueWidth - 2 }} />
      <div className="sr-main">
        <header className="sr-topbar">
          {/* "SYNC RUN" said nothing the surface had not already said. The run's
              number and what it is doing are what a reader needs here. */}
          <span className="sr-topbar__run">RUN #{job.runNumber}</span>
          <span className="sr-topbar__title" title={runTitle(job, run.commits.length)}>
            {runTitle(job, run.commits.length)}
          </span>
          <span className="sr-topbar__grow" />
          <span className={`sr-phase is-${phaseTone(job.status)}`}>{syncPhase(job)}</span>
          {running && !flow && (
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
          {parked && !flow && (
            <button
              type="button"
              className={`sr-topbar__action${outOfBudget ? '' : ' is-primary'}`}
              disabled={busy}
              onClick={() => act(() => workspaceApi.resumeUpstreamCherryPick(workspaceId, jobId))}>
              <PlayIcon />Resume
            </button>
          )}
          {failed && !flow && (
            <button type="button" className="sr-topbar__action is-primary" disabled={busy}
              onClick={() => act(() => workspaceApi.retryUpstreamCherryPick(workspaceId, jobId))}>
              <PlayIcon />Retry
            </button>
          )}
          {!closed && !flow && (
            <button type="button" className="sr-topbar__action" disabled={busy}
              title="Stop the run and release everything it holds"
              onClick={() => setClosing(true)}>
              <CloseIcon />Close run
            </button>
          )}
          {!flow && (
            <button type="button" className="sr-topbar__icon" disabled={busy}
              title="Close the run and remove it from the list" aria-label="Delete run"
              onClick={() => setDeleting(true)}>
              <TrashIcon />
            </button>
          )}
          {canOpenPr && (
            <button type="button"
              className={`sr-topbar__icon${prOpen ? ' is-on' : ''}`}
              title={prTitle(job.prNumber, canOpenPr, prOpen)}
              aria-label="Toggle the pull request panel"
              onClick={() => setPrOpen(open => !open)}>
              <PanelIcon />
            </button>
          )}
        </header>

        {/* The session the whole run shares, and what it has cost. `claude
            --resume` continues a conversation rather than attaching to a live
            one, so tailing the session's own transcript is how you watch from a
            terminal. Elapsed and spend live here rather than in a column of
            their own, which read as three unrelated numbers. */}
        <div className="sr-session">
          {job.agentSessionId !== null && (
            <>
              <span>SESSION</span>
              <code title={job.agentSessionId}>{job.agentSessionId}</code>
              {transcriptPath !== null && (
                <button type="button" className="sr-session__copy"
                  onClick={() => {
                    void navigator.clipboard.writeText(`tail -f ${transcriptPath}`);
                  }}>
                  Copy tail command
                </button>
              )}
            </>
          )}
          <span className="sr-topbar__grow" />
          <span className="sr-session__stat">
            <ClockIcon />
            {elapsedLabel(job.createdAt, running ? undefined : job.updatedAt)} elapsed
          </span>
          <span className="sr-session__dot" aria-hidden>·</span>
          <span className="sr-session__stat" title={budget === undefined
            ? `${job.remainingRepairTurns ?? 0} conflict-repair turns left`
            : `${money(job.spentMilliUsd)} of ${money(budget)} budget`}>
            {money(job.spentMilliUsd)} spent
          </span>
          {job.prNumber !== null && (
            <>
              <span className="sr-session__dot" aria-hidden>·</span>
              <button type="button" className="sr-session__pr" disabled={!canOpenPr}
                onClick={() => setPrOpen(true)}>PR #{job.prNumber}</button>
            </>
          )}
        </div>

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
              <WorkspaceSyncFeed job={job} commits={run.commits} events={run.events}
                rounds={run.rounds} fixups={run.fixups}
                compileProof={run.compileProof}
                onOpenPr={canOpenPr ? () => setPrOpen(true) : undefined} />
              {gate !== null && (
                <WorkspaceSyncPublishCard gate={gate} branch={job.resultBranch} busy={busy}
                  onAuthorize={() => act(
                    () => workspaceApi.authorizeUpstreamSyncPublish(
                      workspaceId, jobId, {
                        revision: gate.revision,
                        subjectDigest: gate.subjectDigest,
                        actionDigest: gate.actionDigest,
                      }))} />
              )}
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
                          money(budget ?? 0)} spent — raise it to carry on`
                        : parked ? 'Parked · nothing is pushed until you resume'
                        : failed ? 'Stopped · durable progress is kept'
                          : 'Run complete · parked for your review'}
                </span>
                <span className="sr-topbar__grow" />
                {parked && !flow && queue.current !== null && (
                  <button type="button" className="sr-pill" disabled={busy}
                    onClick={() => act(
                      () => workspaceApi.skipUpstreamCherryPickCommit(workspaceId, jobId))}>
                    <SkipIcon />Skip this commit
                  </button>
                )}
                {running && !flow && (
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
                  aria-label="Steer the run" disabled={flow}
                  title={flow ? STEERING_UNAVAILABLE : undefined}
                  placeholder={flow
                    ? STEERING_UNAVAILABLE
                    : 'Steer the run — e.g. "prefer our fork’s config names when conflicts touch them"…'} />
                <button type="submit" aria-label="Send guidance"
                  disabled={busy || closed || flow || guidance.trim().length === 0}>
                  <SendIcon />
                </button>
              </form>
              {error !== null && <p className="sr-error" role="alert">{error}</p>}
            </div>
          </div>

        </div>
      </div>
      {closing && (
        <ConfirmDialog
          title="Close this sync run?"
          body={'The picker stops at the next commit boundary, and everything it holds'
            + ' locally is released: its isolated worktree and the agent\u2019s session'
            + ' and stored transcripts.'
            + '\n\n'
            + closeKeeps(job)
            + ' A conflict you were resolving by hand in the worktree does not survive.'}
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
      {deleting && (
        <ConfirmDialog
          title="Delete this sync run?"
          body={'Everything closing the run does, and then the run itself: it leaves the'
            + ' sync list and its log is gone for good.'
            + '\n\n'
            + closeKeeps(job)
            + ' Nothing on the remote is touched.'}
          confirmLabel={busy ? 'Deleting…' : 'Delete run'}
          destructive
          busy={busy}
          onCancel={() => setDeleting(false)}
          onConfirm={() => {
            setDeleting(false);
            setBusy(true);
            setError(null);
            void workspaceApi.deleteUpstreamCherryPick(workspaceId, jobId)
              .then(() => onBack?.())
              .catch(reason => setError(errorMessage(reason)))
              .finally(() => setBusy(false));
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

/** The design's PR-panel toggle: a pane with a divided right edge. */
function PanelIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" aria-hidden>
      <rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 3" />
    </svg>
  );
}

/**
 * What the run is, in the words the top bar has room for. The range's own shas
 * are the left column's title, so this says the shape of the work instead.
 */
function runTitle(job: UpstreamCherryPickJobDto, commits: number): string {
  if (commits === 0) return `Sync run on ${job.resultBranch}`;
  return `Cherry-pick ${job.requestedCount} commit${
    job.requestedCount === 1 ? '' : 's'} from ${job.sourceBranch}`;
}

function CloseIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden><path d="M6 6l12 12M18 6 6 18" /></svg>
  );
}

function TrashIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden>
      <path d="M4 7h16M10 7V5h4v2M6 7l1 13h10l1-13M10 11v6M14 11v6" />
    </svg>
  );
}

/**
 * What survives the teardown. A run that pushed keeps only what is on the
 * remote — its local branch goes with the rest. One that never pushed keeps
 * its branch, because that is then the only copy of the picks.
 */
function closeKeeps(job: UpstreamCherryPickJobDto): string {
  return job.prNumber === null
    ? `Nothing was pushed, so ${job.resultBranch} is kept — it holds the only copy`
      + ' of what was picked.'
    : `Draft PR #${job.prNumber} and its pushed commits are kept. The local`
      + ` ${job.resultBranch} is deleted; the remote copy is the one that matters.`;
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
