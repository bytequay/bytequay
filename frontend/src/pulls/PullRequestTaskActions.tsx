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
import { useEffect, useRef, useState } from 'react';
import { Bot, LoaderCircle, PanelsTopLeft, RefreshCw } from 'lucide-react';
import type { PullRequestTaskLink } from '../types';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { remoteErrorMessage } from './PullOverview';
import type { AgentReviewDto } from './agent-review/contracts';

export default function PullRequestTaskActions({ repo, number, authored, workspaceId, loading, contextError, review, onNavigate, onRetryContext }: {
  repo: string;
  number: number;
  authored: boolean;
  workspaceId?: string;
  loading: boolean;
  contextError: string | null;
  review: AgentReviewDto | null;
  onNavigate: (route: string) => void;
  onRetryContext: () => void;
}) {
  const [link, setLink] = useState<PullRequestTaskLink | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const operation = useRef(false);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    setLink(null);
    setError(null);
    if (authored && workspaceId !== undefined) {
      void window.bridge.findPullRequestTask(workspaceId, repo, number)
        .then(value => { if (current === generation.current) setLink(value); })
        .catch(failure => { if (current === generation.current) setError(remoteErrorMessage(failure)); });
    }
    return () => { generation.current = current + 1; };
  }, [authored, workspaceId, repo, number]);

  const open = async () => {
    if (workspaceId === undefined || operation.current) return;
    operation.current = true;
    setBusy(true);
    setError(null);
    const current = generation.current;
    try {
      let route: string;
      if (authored) {
        // Opening is idempotent and leaves agent execution to the user's next message.
        const task = await window.bridge.assignPullRequestAgent(workspaceId, repo, number);
        route = workspaceAgentTaskRoute(task.workspaceId, task.taskId);
      }
      else {
        const task = review ?? await window.bridge.startAgentReview(workspaceId, repo, number);
        route = workspaceReviewTaskRoute(task.workspaceId, task.id);
      }
      if (current === generation.current) onNavigate(route);
    }
    catch (failure) {
      if (current === generation.current) setError(remoteErrorMessage(failure));
    }
    finally {
      operation.current = false;
      if (current === generation.current) setBusy(false);
    }
  };
  const blocked = loading ? 'Loading workspace…' : contextError !== null ? `Workspace unavailable: ${contextError}`
    : workspaceId === undefined ? 'Add a workspace for this repository to use agent tasks' : null;
  const assignLabel = link === null ? 'Assign agent' : 'Open assigned agent';
  const viewLabel = authored ? link === null ? 'Assign agent and view task' : 'View in task'
    : review === null ? 'Start review and view task' : 'View in task';
  return <>
    {authored && <button type="button" aria-label={assignLabel} title={blocked ?? assignLabel}
      disabled={blocked !== null || busy} onClick={() => { void open(); }}>
      {busy ? <LoaderCircle className="pr-task-spinner" size={18} aria-hidden="true" /> : <Bot size={18} aria-hidden="true" />}
    </button>}
    <button type="button" aria-label="View in task" title={blocked ?? viewLabel}
      disabled={blocked !== null || busy} onClick={() => { void open(); }}>
      {!authored && busy ? <LoaderCircle className="pr-task-spinner" size={18} aria-hidden="true" /> : <PanelsTopLeft size={18} aria-hidden="true" />}
    </button>
    {busy && <span className="pr-task-message" role="status">{authored ? 'Opening PR task…' : 'Starting review…'}</span>}
    {contextError !== null && <span className="pr-task-message" role="alert">{contextError}
      <button type="button" aria-label="Retry workspace lookup" title="Retry workspace lookup" onClick={onRetryContext}>
        <RefreshCw size={16} aria-hidden="true" />
      </button>
    </span>}
    {error !== null && <span className="pr-task-message" role="alert">{error}</span>}
  </>;
}
