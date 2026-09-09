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

import { useEffect, useState } from 'react';
import { ChevronRight, GitPullRequest, LoaderCircle } from 'lucide-react';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { reviewStatus } from '../pulls/agent-review/model';
import { relativeTime } from '../relativeTime';
import { displayModelName } from '../settings/model';
import '../css/review-tasks.css';

export function ReviewTasksView({ workspaceId, onOpenReview }: {
  workspaceId: string;
  onOpenReview: (review: AgentReviewDto) => void;
}): React.JSX.Element {
  const [reviews, setReviews] = useState<AgentReviewDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let mounted = true;
    let timer: ReturnType<typeof setTimeout>;
    setReviews([]);
    setError(null);
    setLoading(true);
    const load = async (): Promise<void> => {
      try {
        const found = await window.bridge.listAgentReviews(workspaceId);
        if (!mounted) return;
        setReviews(found);
        setError(null);
      }
      catch (failure) {
        if (!mounted) return;
        setError(failure instanceof Error ? failure.message : 'Unable to list review tasks');
      }
      finally {
        if (mounted) {
          setLoading(false);
          timer = setTimeout(() => { void load(); }, 5000);
        }
      }
    };
    void load();
    return () => { mounted = false; clearTimeout(timer); };
  }, [workspaceId, retry]);

  return <ReviewTaskList reviews={reviews} loading={loading} error={error} onRetry={() => setRetry(current => current + 1)} onOpenReview={onOpenReview} />;
}

const STATUS_CLASSES = { running: 'is-running', warn: 'is-waiting', ready: 'is-idle', danger: 'is-failed', neutral: '' };

export function ReviewTaskList({ reviews, loading, error, onRetry, onOpenReview, now = Date.now() }: {
  reviews: AgentReviewDto[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onOpenReview: (review: AgentReviewDto) => void;
  now?: number;
}): React.JSX.Element {
  return (
    <section className="agt-section" aria-label="Review tasks">
      <div className="agt-head">
        <span className="agt-section-label">Review tasks</span>
        <span className="agt-spacer" />
        <span className="agt-section-hint">Open a review to see its findings and activity</span>
      </div>
      {error !== null && (
        <div className="cmt-error agt-review-error" role="alert">
          <span>{error}</span>
          <button type="button" className="agt-selbar__btn" disabled={loading} onClick={onRetry}>Retry</button>
        </div>
      )}
      {loading && <div className="agt-loading" role="status">Loading review tasks…</div>}
      {!loading && error === null && reviews.length === 0 && (
        <div className="agt-empty-hero">
          <div className="agt-empty-hero__title">No review tasks yet</div>
          <div className="agt-muted">Start an agent review from a pull request to see it here.</div>
        </div>
      )}
      {reviews.length > 0 && (
        <div className="agt-groups">
          <div className="agt-group">
            <div className="agt-group__head">
              <span className="agt-group__label">Pull request reviews</span>
              <span className="agt-spacer" />
              <span className="agt-group__count">{reviews.length} {reviews.length === 1 ? 'review' : 'reviews'}</span>
            </div>
            {reviews.map(review => {
              const status = reviewStatus(review);
              return (
                <button type="button" className="agt-trow agt-review-row" key={review.id} onClick={() => onOpenReview(review)}>
                  <GitPullRequest className="agt-review-row__icon" size={16} aria-hidden="true" />
                  <span className="agt-trow__title" title={`${review.repo} #${review.number}`}>{review.repo} #{review.number}</span>
                  <span className="agt-mono agt-trow__meta">
                    {review.task.laneLabel}{review.task.model === null ? '' : ` · ${displayModelName(review.task.model)}`}
                    {' · started '}<time dateTime={review.createdAt} title={new Date(review.createdAt).toLocaleString()}>{relativeTime(review.createdAt, { now })}</time>
                  </span>
                  <span className={`agt-state ${STATUS_CLASSES[status.tone]}`} title={status.detail}>
                    {status.busy && <LoaderCircle className="agt-spin" aria-hidden="true" />}
                    {status.label}
                  </span>
                  <ChevronRight className="agt-trow__chev" size={14} aria-hidden="true" />
                </button>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}
