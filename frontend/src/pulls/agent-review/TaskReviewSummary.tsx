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
import { useCallback, useEffect, useState } from 'react';
import { Check, Eye, Lock, PanelsTopLeft } from 'lucide-react';
import type { ReviewCardFocus } from '../../agents/ReviewRequestCard';
import { relativeTime } from '../../relativeTime';
import type { AgentTaskDto } from '../../types';
import { workspaceReviewTaskRoute } from '../../workspaces/model';
import { remoteErrorMessage } from '../PullOverview';
import type { WorktreeChangesDto } from '../code-tests/contracts';
import AgentReviewTab, { type ReviewNavigationTarget } from './AgentReviewTab';
import type { AgentReviewDto } from './contracts';
import { latestRun, openFindings, reviewRecoveryRequired, reviewRunning, reviewStatus } from './model';
import { TimelineEvent } from './SummaryTimeline';

/** What a development task's Summary needs to offer the task's own reviewer. */
export type TaskReviewOptions = {
  workspaceId: string;
  active?: boolean;
  onReviewChange?: (review: AgentReviewDto) => void;
  onNavigateToChanges: (target: ReviewNavigationTarget) => void;
  focusFindingId?: string | null;
  focusReviewCard?: ReviewCardFocus | null;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
};

export type TaskReview = ReturnType<typeof useTaskReview>;

/** The task's reviewer and the committed work it would read; without options there is no reviewer and nothing is read. */
export function useTaskReview(task: AgentTaskDto, options: TaskReviewOptions | undefined) {
  const workspaceId = options?.workspaceId;
  const active = options?.active ?? true;
  const onReviewChange = options?.onReviewChange;
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const [loading, setLoading] = useState(workspaceId !== undefined);
  const [pending, setPending] = useState<'start' | 'stop' | 'update' | 'resume' | 'end' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [source, setSource] = useState<WorktreeChangesDto | null>(null);
  const changeReview = useCallback((value: AgentReviewDto) => {
    setReview(value);
    onReviewChange?.(value);
  }, [onReviewChange]);
  useEffect(() => {
    if (workspaceId === undefined) return;
    let live = true;
    setLoading(true);
    setError(null);
    void Promise.resolve().then(() => window.bridge.findTaskAgentReview(workspaceId, task.id))
      .then(value => { if (live) { setReview(value); if (value !== null) onReviewChange?.(value); } })
      .catch(cause => { if (live) setError(remoteErrorMessage(cause)); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [workspaceId, task.id, retry, onReviewChange]);
  useEffect(() => {
    if (workspaceId === undefined || !active || task.checkoutRemovedAt !== null) return;
    let live = true;
    const read = () => {
      void Promise.resolve().then(() => window.bridge.readWorktreeChanges({ kind: 'task', taskId: task.id, workspaceId }, { scope: 'COMMITTED' }))
        .then(value => { if (live) setSource(value); })
        .catch(() => { if (live) setSource(null); });
    };
    read();
    const timer = window.setInterval(read, 5000);
    return () => { live = false; window.clearInterval(timer); };
  }, [active, task.id, task.version, task.checkoutRemovedAt, workspaceId]);
  const act = async (kind: NonNullable<typeof pending>, operation: (workspace: string) => Promise<AgentReviewDto>) => {
    if (pending !== null || workspaceId === undefined) return;
    setPending(kind);
    setError(null);
    try { changeReview(await operation(workspaceId)); }
    catch (cause) { setError(remoteErrorMessage(cause)); }
    finally { setPending(null); }
  };
  const reviewId = review?.id ?? '';
  return {
    review, loading, error, source, pending, changeReview,
    unavailable: task.checkoutRemovedAt !== null ? 'The development working copy was removed'
      : task.state !== 'OPEN' ? 'The development task is closed' : null,
    retry: () => setRetry(value => value + 1),
    start: () => act('start', workspace => window.bridge.startTaskAgentReview(workspace, task.id)),
    stop: () => act('stop', () => window.bridge.stopAgentReview(reviewId)),
    update: () => act('update', () => window.bridge.updateAgentReview(reviewId)),
    resume: () => act('resume', () => window.bridge.resumeAgentReview(reviewId)),
    end: () => act('end', () => window.bridge.endAgentReview(reviewId)),
  };
}

function commitCount(count: number): string {
  return `${count} ${count === 1 ? 'commit' : 'commits'}`;
}

/** Waits on a Code & Tests run the same way the review's own controls do. */
function blockedReason(review: AgentReviewDto): string | null {
  if (reviewRecoveryRequired(review)) return 'Recover the review runtime first';
  return review.task.mutationClaim === 'LOCAL_RUN' ? 'A run is using this working copy' : null;
}

/** The Summary strip's review control: what the reviewer is doing, and the one action that matters now. */
export function TaskReviewControls({ state, onShowReview }: { state: TaskReview; onShowReview: () => void }) {
  const { review, source, pending, unavailable, error } = state;
  if (review === null) {
    if (state.loading) return null;
    return <div className="agr-summary-strip__review">
      {unavailable !== null ? <span className="agr-summary-strip__note">{unavailable}</span>
        : source !== null && <span className="agr-summary-strip__note" title="Unpushed commits are included. Uncommitted changes are excluded.">
          {source.baseLabel} → {source.headSha.slice(0, 7)}
        </span>}
      <button type="button" className="agr-action agr-summary-strip__start" disabled={pending !== null || unavailable !== null || error !== null}
        title={unavailable ?? 'A separate reviewer inspects committed changes in its own working copy'} onClick={() => { void state.start(); }}>
        <Eye size={15} aria-hidden="true" />{pending === 'start' ? 'Starting reviewer…' : 'Review changes'}
      </button>
    </div>;
  }
  const status = reviewStatus(review);
  const run = latestRun(review);
  if (reviewRunning(review)) {
    return <div className="agr-summary-strip__review">
      <span className={`agr-summary-strip__status is-${status.tone}`}>
        {status.busy && <span className="agr-spinner" aria-hidden="true" />}
        {status.label === 'Reviewing' && run !== null ? `Reviewing ${commitCount(run.commits.length)}…` : status.chip ?? status.label}
      </span>
      <button type="button" className="agr-action is-danger" disabled={pending !== null} onClick={() => { void state.stop(); }}>Stop</button>
    </div>;
  }
  const blocked = blockedReason(review);
  return <div className="agr-summary-strip__review">
    <button type="button" className="agr-summary-strip__link" onClick={onShowReview}>
      Agent review{status.chip !== null && <span className={`pull-detail__review-chip is-${status.tone}`}>{status.chip}</span>}
    </button>
    {review.state === 'OPEN' && <button type="button" className="agr-action" disabled={pending !== null || blocked !== null}
      title={blocked ?? 'Review the latest committed changes'} onClick={() => { void state.update(); }}>
      {pending === 'update' ? 'Starting…' : 'Review again'}
    </button>}
  </div>;
}

/** What the reviewer did, in the words of a timeline event. */
function reviewEvent(review: AgentReviewDto): string {
  const status = reviewStatus(review);
  const run = latestRun(review);
  const findings = openFindings(review).length;
  if (status.status === 'running' || status.status === 'queued') {
    return run === null ? 'is starting'
      : status.label === 'Running' ? 'is answering your question' : `is reading ${commitCount(run.commits.length)} in its own working copy`;
  }
  if (status.status === 'approval') return 'is waiting for your approval';
  if (status.status === 'recovery') return 'needs its runtime recovered';
  if (status.status === 'stopped') return 'was stopped';
  if (status.status === 'failed') return 'could not finish';
  if (status.status === 'nofindings') return review.findings.length > 0 ? 'finished with no findings selected; recorded findings remain in Review details'
    : `found no issues in ${run === null ? 'the changes' : commitCount(run.commits.length)}`;
  return `left ${findings} local review ${findings === 1 ? 'comment' : 'comments'}`;
}

/** The reviewer's place on the Summary timeline: its event, then its findings as local review comments. */
export function TaskReviewSection({ options, state }: { options: TaskReviewOptions; state: TaskReview }) {
  const { review, source, loading, error, pending } = state;
  const run = review === null ? null : latestRun(review);
  const newer = source !== null && run !== null && source.headSha !== run.headSha;
  const running = review !== null && reviewRunning(review);
  const blocked = review === null ? null : blockedReason(review);
  const openWorkspace = () => { if (review !== null) window.location.hash = workspaceReviewTaskRoute(options.workspaceId, review.id); };
  return <section className="agr-task-review" aria-label="Review of development changes">
    {error !== null && <div className="agr-error" role="alert">{error}
      <button type="button" className="agr-action" onClick={state.retry}>Retry</button>
    </div>}
    {loading && review === null && <p className="agr-card__empty" role="status">Loading reviewer…</p>}
    {review !== null && <>
      <TimelineEvent avatar={running ? <span className="agr-spinner" aria-hidden="true" />
        : reviewStatus(review).tone === 'ready' ? <Check size={15} className="agr-event__done" aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
        actions={<span className="agr-event__actions">
          {review.state === 'OPEN' && !running && reviewStatus(review).status === 'stopped' && <button type="button" className="agr-event__action"
            disabled={pending !== null || blocked !== null} title={blocked ?? undefined} onClick={() => { void state.resume(); }}>
            {pending === 'resume' ? 'Starting…' : 'Resume'}
          </button>}
          {review.state === 'OPEN' && !running && <button type="button" className="agr-event__action is-quiet" disabled={pending !== null || blocked !== null}
            title={blocked ?? 'Completes the task and cleans the worktree; the summary, findings and discussion stay'}
            onClick={() => { void state.end(); }}>End review</button>}
          <button type="button" className="agr-event__icon" aria-label="View in workspace" title="View in workspace" onClick={openWorkspace}>
            <PanelsTopLeft size={16} aria-hidden="true" />
          </button>
        </span>}>
        <strong>Agent review</strong> {reviewEvent(review)}
        {run !== null && <span className="agr-event__time"> · {relativeTime(run.endedAt ?? run.createdAt)}</span>}
        {openFindings(review).length > 0 && <span className="agr-event__local"><Lock size={11} aria-hidden="true" />Local · never posted to GitHub</span>}
      </TimelineEvent>
      {newer && <div className="agr-banner is-warn" role="status">New commits since this review.
        {' '}The developer is at {source.headSha.slice(0, 7)}; this review stays on {run.headSha.slice(0, 7)}. Use Review again to assess the latest commits.
      </div>}
      <AgentReviewTab timeline workspaceId={options.workspaceId} viewer={null} review={review} onReviewChange={state.changeReview}
        ownStream={options.active ?? true} focusFindingId={options.focusFindingId ?? null} focusReviewCard={options.focusReviewCard}
        onSelectCommit={options.onSelectCommit} onNavigateToChanges={options.onNavigateToChanges} onOpenWorkspace={openWorkspace} />
    </>}
  </section>;
}
