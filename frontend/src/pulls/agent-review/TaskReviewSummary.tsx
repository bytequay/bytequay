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
import { shortSha } from '@bytequay/ui/displayText';
import { useCallback, useEffect, useState } from 'react';
import { Eye } from 'lucide-react';
import type { ReviewCardFocus } from '../../agents/ReviewRequestCard';
import type { AgentTaskDto } from '../../types';
import { workspaceReviewTaskRoute } from '../../workspaces/model';
import { remoteErrorMessage } from '../../remoteError';
import type { WorktreeChangesDto } from '../code-tests/contracts';
import AgentReviewTab, { type ReviewNavigationTarget } from './AgentReviewTab';
import type { AgentReviewDto } from './contracts';
import { mergeReviewFindings } from './model';

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

/** The task's reviewer and the working-copy changes it would read; without options there is no reviewer and nothing is read. */
export function useTaskReview(task: AgentTaskDto, options: TaskReviewOptions | undefined) {
  const workspaceId = options?.workspaceId;
  const active = options?.active ?? true;
  const onReviewChange = options?.onReviewChange;
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const [loading, setLoading] = useState(workspaceId !== undefined);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [startFailed, setStartFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [source, setSource] = useState<WorktreeChangesDto | null>(null);
  const changeReview = useCallback((value: AgentReviewDto) => {
    setReview(current => mergeReviewFindings(current, value));
    onReviewChange?.(value);
  }, [onReviewChange]);
  useEffect(() => {
    if (workspaceId === undefined) return;
    let live = true;
    setLoading(true);
    setError(null);
    setStartFailed(false);
    void Promise.resolve().then(() => window.bridge.findTaskAgentReview(workspaceId, task.id))
      .then(value => { if (live) { setReview(current => value === null ? null : mergeReviewFindings(current, value)); if (value !== null) onReviewChange?.(value); } })
      .catch(cause => { if (live) setError(remoteErrorMessage(cause)); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [workspaceId, task.id, retry, onReviewChange]);
  useEffect(() => {
    if (workspaceId === undefined || !active || task.checkoutRemovedAt !== null) return;
    let live = true;
    const read = () => {
      void Promise.resolve().then(() => window.bridge.readWorktreeChanges({ kind: 'task', taskId: task.id, workspaceId }, { scope: 'ALL' }))
        .then(value => { if (live) setSource(value); })
        .catch(() => { if (live) setSource(null); });
    };
    read();
    const timer = window.setInterval(read, 5000);
    return () => { live = false; window.clearInterval(timer); };
  }, [active, task.id, task.version, task.checkoutRemovedAt, workspaceId]);
  const start = async () => {
    if (starting || workspaceId === undefined) return;
    setStarting(true);
    setError(null);
    setStartFailed(false);
    try { changeReview(await window.bridge.startTaskAgentReview(workspaceId, task.id)); }
    catch (cause) { setError(remoteErrorMessage(cause)); setStartFailed(true); }
    finally { setStarting(false); }
  };
  return {
    review, loading, error, source, starting, changeReview, start,
    unavailable: task.checkoutRemovedAt !== null ? 'The development working copy was removed'
      : task.state !== 'OPEN' ? 'The development task is closed' : null,
    // Retry repeats what failed: a failed start starts again rather than only reloading.
    retry: () => { if (startFailed) void start(); else setRetry(value => value + 1); },
  };
}

/** The task's reviewer on its Summary: the offer to start one, then the same review panel the reviewer's own page shows. */
export function TaskReviewSection({ options, state, explain = false }: {
  options: TaskReviewOptions; state: TaskReview;
  /** Shows the reviewer's explanation as What changed, for a task without its own summary. */
  explain?: boolean;
}) {
  const { review, source, loading, error, starting, unavailable } = state;
  const openWorkspace = () => { if (review !== null) window.location.hash = workspaceReviewTaskRoute(options.workspaceId, review.id); };
  return <section className="agr-task-review" aria-label="Review of development changes">
    {error !== null && <div className="agr-error" role="alert">{error}
      <button type="button" className="agr-action" onClick={state.retry}>Retry</button>
    </div>}
    {loading && review === null && <p className="agr-card__empty" role="status">Loading reviewer…</p>}
    {!loading && review === null && <header className="agr-review__head">
      <h2>Agent review</h2>
      {unavailable !== null ? <span className="agr-review__note">{unavailable}</span>
        : source !== null && <span className="agr-review__note" title="Includes committed, staged, unstaged, and new files in this working copy.">
          {source.baseLabel} → {shortSha(source.headSha)}
        </span>}
      <span className="agr-spacer" />
      <button type="button" className="agr-action" disabled={starting || unavailable !== null || error !== null}
        title={unavailable ?? 'A reviewer inspects all changes in this task’s working copy'} onClick={() => { void state.start(); }}>
        <Eye size={15} aria-hidden="true" />{starting ? 'Starting reviewer…' : 'Review changes'}
      </button>
    </header>}
    {review !== null && <AgentReviewTab timeline explain={explain} workspaceId={options.workspaceId} viewer={null} review={review} onReviewChange={state.changeReview}
      ownStream={options.active ?? true} focusFindingId={options.focusFindingId ?? null} focusReviewCard={options.focusReviewCard} headSha={source?.headSha}
      onSelectCommit={options.onSelectCommit} onNavigateToChanges={options.onNavigateToChanges} onOpenWorkspace={openWorkspace} />}
  </section>;
}
