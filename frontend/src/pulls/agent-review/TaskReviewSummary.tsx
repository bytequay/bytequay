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
import type { AgentTaskDto } from '../../types';
import { workspaceReviewTaskRoute } from '../../workspaces/model';
import { remoteErrorMessage } from '../PullOverview';
import type { WorktreeChangesDto } from '../code-tests/contracts';
import AgentReviewTab, { type ReviewNavigationTarget } from './AgentReviewTab';
import type { AgentReviewDto } from './contracts';
import { latestRun } from './model';
import type { ReviewCardFocus } from '../../agents/ReviewRequestCard';

export default function TaskReviewSummary({ task, workspaceId, active = true, onReviewChange, onNavigateToChanges,
  focusFindingId = null, focusReviewCard, onSelectCommit }: {
  task: AgentTaskDto;
  workspaceId: string;
  active?: boolean;
  onReviewChange?: (review: AgentReviewDto) => void;
  onNavigateToChanges: (target: ReviewNavigationTarget) => void;
  focusFindingId?: string | null;
  focusReviewCard?: ReviewCardFocus | null;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
}) {
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [source, setSource] = useState<WorktreeChangesDto | null>(null);
  const changeReview = useCallback((value: AgentReviewDto) => {
    setReview(value);
    onReviewChange?.(value);
  }, [onReviewChange]);
  useEffect(() => {
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
    if (!active || task.checkoutRemovedAt !== null) return;
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
  const start = async () => {
    if (starting) return;
    setStarting(true);
    setError(null);
    try { changeReview(await window.bridge.startTaskAgentReview(workspaceId, task.id)); }
    catch (cause) { setError(remoteErrorMessage(cause)); }
    finally { setStarting(false); }
  };
  const run = review === null ? null : latestRun(review);
  const newer = source !== null && run !== null && source.headSha !== run.headSha;
  const unavailable = task.checkoutRemovedAt !== null ? 'The development working copy was removed'
    : task.state !== 'OPEN' ? 'The development task is closed' : null;
  return <section className="agr-task-review" aria-label="Review of development changes">
    <div className="agr-findings__head"><h2>Agent review</h2></div>
    {error !== null && <div className="agr-error" role="alert">{error}
      <button type="button" className="agr-action" onClick={() => setRetry(value => value + 1)}>Retry</button>
    </div>}
    {loading && <p className="agr-card__empty" role="status">Loading reviewer…</p>}
    {!loading && review === null && <div className="agr-empty">
      <div className="agr-empty__title">Review your changes</div>
      <p>A separate reviewer uses the configured Review provider and model to inspect committed changes in its own working copy.
        Questions go to the reviewer; fix requests go to your development agent.</p>
      <p>Unpushed commits are included. Uncommitted changes are excluded.</p>
      {source !== null && <p className="agr-empty__scope">{source.commits.length} commits · {source.baseLabel} → {source.headSha.slice(0, 7)}</p>}
      <button type="button" className="agr-action is-primary" disabled={starting || unavailable !== null || error !== null}
        title={unavailable ?? undefined} onClick={() => { void start(); }}>{starting ? 'Starting reviewer…' : 'Review changes'}</button>
      {unavailable && <p className="agr-card__empty">{unavailable}</p>}
    </div>}
    {review !== null && <>
      <div className="agr-learning-cards__scope">Reviewer · {review.task.laneLabel} {review.task.model ?? ''}
        {run !== null && <> · {run.state === 'ACCEPTED' ? 'reviewed' : 'target'} <code>{run.headSha.slice(0, 7)}</code></>}
        {' · '}Uncommitted changes excluded
      </div>
      {newer && <div className="agr-banner is-warn" role="status">New changes since this review.
        {' '}The developer is at {source.headSha.slice(0, 7)}; this assessment stays on {run.headSha.slice(0, 7)}. Use Update review to assess the latest commits.
      </div>}
      <AgentReviewTab workspaceId={workspaceId} viewer={null} review={review} onReviewChange={changeReview}
        ownStream={active} focusFindingId={focusFindingId} focusReviewCard={focusReviewCard} onSelectCommit={onSelectCommit}
        onNavigateToChanges={onNavigateToChanges}
        onOpenWorkspace={() => { window.location.hash = workspaceReviewTaskRoute(workspaceId, review.id); }} />
    </>}
  </section>;
}
