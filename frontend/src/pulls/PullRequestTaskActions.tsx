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
import { useAgentAvailability } from '../agents/availability';
import { useEffect, useRef, useState } from 'react';
import { Bot, LoaderCircle, RefreshCw } from 'lucide-react';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { errorMessage } from '../errorMessage';
import { findConversationReview } from '../agents/agentConversations';
import type { AgentReviewDto } from './agent-review/contracts';

export default function PullRequestTaskActions({ repo, number, authored, workspaceId, loading, contextError, existingTask, review, onNavigate, onRetryContext }: {
  repo: string;
  number: number;
  authored: boolean;
  workspaceId?: string;
  loading: boolean;
  contextError: string | null;
  existingTask: boolean;
  review: AgentReviewDto | null;
  onNavigate: (route: string) => void;
  onRetryContext: () => void;
}) {
  const availability = useAgentAvailability(workspaceId);
  const [busy, setBusy] = useState<'development' | 'review' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const operation = useRef(false);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    operation.current = false;
    setBusy(null);
    setError(null);
    return () => { generation.current = current + 1; };
  }, [authored, workspaceId, repo, number]);

  const open = async (kind: 'development' | 'review') => {
    if (workspaceId === undefined || operation.current) return;
    operation.current = true;
    setBusy(kind);
    setError(null);
    const current = generation.current;
    try {
      let route: string;
      // Leaving the PR suppresses navigation, not the task creation requested by the click.
      const link = await window.bridge.findPullRequestTask(workspaceId, repo, number);
      if (kind === 'development') {
        if (link === null && !availability.development) return;
        const task = link ?? await window.bridge.assignPullRequestAgent(workspaceId, repo, number);
        route = workspaceAgentTaskRoute(task.workspaceId, task.taskId);
      }
      else {
        // A reviewer started before publication can be linked only to the development task.
        const linkedReview = await findConversationReview(window.bridge, workspaceId, link?.taskId, { repo, number });
        if (linkedReview === null && review === null && !availability.review) return;
        const task = linkedReview ?? review ?? await window.bridge.startAgentReview(workspaceId, repo, number);
        route = workspaceReviewTaskRoute(task.workspaceId, task.id);
      }
      if (current === generation.current) onNavigate(route);
    }
    catch (failure) {
      if (current === generation.current) setError(errorMessage(failure));
    }
    finally {
      if (current === generation.current) {
        operation.current = false;
        setBusy(null);
      }
    }
  };
  const blocked = loading ? 'Loading workspace…' : contextError !== null ? `Workspace unavailable: ${contextError}`
    : workspaceId === undefined ? 'Add a workspace for this repository to use agent tasks' : null;
  return <>
    {authored && (availability.development || existingTask) && <button type="button" className={`pr-task-action pr-task-action--development${existingTask ? ' is-assigned' : ''}`} aria-label="Development"
      aria-description={existingTask ? 'Task assigned' : undefined} title={blocked ?? (existingTask ? 'Open assigned development task' : 'Create development task')}
      disabled={blocked !== null || busy !== null} onClick={() => { void open('development'); }}>
      {busy === 'development' ? <span className="bq-spinner is-spinning"><LoaderCircle className="pr-task-spinner" size={16} aria-hidden="true" /></span> : <Bot size={16} aria-hidden="true" />}
      Development
    </button>}
    {(availability.review || review !== null) && <button type="button" className={`pr-task-action pr-task-action--review${review !== null ? ' is-assigned' : ''}`} aria-label="Review"
      aria-description={review !== null ? 'Task assigned' : undefined} title={blocked ?? (review !== null ? 'Open assigned review task' : 'Start review task')}
      disabled={blocked !== null || busy !== null} onClick={() => { void open('review'); }}>
      {busy === 'review' ? <span className="bq-spinner is-spinning"><LoaderCircle className="pr-task-spinner" size={16} aria-hidden="true" /></span> : <Bot size={16} aria-hidden="true" />}
      Review
    </button>}
    {busy !== null && <span className="pr-task-message" role="status">{busy === 'development' ? 'Opening development task…' : 'Opening review task…'}</span>}
    {contextError !== null && <span className="pr-task-message" role="alert">{contextError}
      <button type="button" aria-label="Retry workspace lookup" title="Retry workspace lookup" onClick={onRetryContext}>
        <RefreshCw size={16} aria-hidden="true" />
      </button>
    </span>}
    {error !== null && <span className="pr-task-message" role="alert">{error}</span>}
  </>;
}
