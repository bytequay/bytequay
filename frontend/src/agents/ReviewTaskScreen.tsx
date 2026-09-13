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
import { useEffect, useState, type ReactNode } from 'react';
import PullsScreen from '../pulls/PullsScreen';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { remoteErrorMessage } from '../pulls/PullOverview';
import '../css/review-tasks.css';

export default function ReviewTaskScreen({ workspaceId, reviewId, onBack, headerAction, browserOpen }: {
  workspaceId: string;
  reviewId: string;
  onBack: () => void;
  headerAction?: ReactNode;
  browserOpen: boolean;
}): React.JSX.Element {
  const [review, setReview] = useState<AgentReviewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setError(null);
    void window.bridge.getAgentReview(reviewId).then(value => {
      if (value.id !== reviewId || value.workspaceId !== workspaceId) throw new Error('This review does not belong to the selected workspace task.');
      if (active) setReview(value);
    }).catch(failure => { if (active) setError(remoteErrorMessage(failure)); });
    return () => { active = false; };
  }, [reviewId, workspaceId, retry]);

  if (review === null) return <section className="agt-screen" aria-label="Review task">
    <header className="agt-screen-head">
      <button type="button" className="agt-back" onClick={onBack}>‹ Tasks</button>
      <strong>Review &amp; Learning</strong><span className="agt-spacer" />{headerAction}
    </header>
    <div className="agt-panel__empty" role={error === null ? 'status' : 'alert'}>
      {error ?? 'Loading review task…'}
      {error !== null && <button type="button" className="agt-action" onClick={() => setRetry(value => value + 1)}>Retry</button>}
    </div>
  </section>;
  return <PullsScreen initialPr={review} initialReviewId={review.id}
    repositoryScope={{ workspaceId, repository: review.repo }} browserOpen={browserOpen}
    reviewWorkspace={{ onBack, headerAction }} />;
}
