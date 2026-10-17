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
import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import AddRepoModal from '../AddRepoModal';
import { useTaskDiscussions } from '../agents/discussions/useTaskDiscussions';
import { providerAvailable } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import type { AgentTaskDto, PullRequestDto } from '../types';
import type { AgentReviewDto } from './agent-review/contracts';
import { privateThreadSource, reviewThreadBridge, type PrivateThreadSource } from './PrivateThread';
import { remoteErrorMessage } from '../remoteError';

function WatchCommentRepository({ repo, onWatched }: { repo: string; onWatched: () => void }) {
  const [repositoryId, setRepositoryId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const watch = async () => {
    setBusy(true);
    setError(null);
    try {
      const repositories = await window.bridge.searchRepositories(repo, 30);
      const match = repositories.find(candidate => candidate.fullName.toLowerCase() === repo.toLowerCase());
      if (!match) throw new Error(`Could not find ${repo}. Check your GitHub access and try again.`);
      setRepositoryId(match.id);
    }
    catch (failure) { setError(remoteErrorMessage(failure)); }
    finally { setBusy(false); }
  };
  const added = () => { setRepositoryId(null); onWatched(); };
  return <>
    <button type="button" disabled={busy} onClick={() => { void watch(); }}>{busy ? 'Loading repository…' : 'Watch repository'}</button>
    {error !== null && <span role="alert">{error}</span>}
    {repositoryId !== null && createPortal(<AddRepoModal initialRepositoryId={repositoryId} onAdded={added}
      onOpenWorkspace={added} onClose={() => setRepositoryId(null)} />, document.body)}
  </>;
}

/** Comment discussions can prepare their working copy on the first question, without running a review. */
export function usePullCommentThreads({ pr, workspaceId, review, task, loading, error, active, onReviewChange, onReload }: {
  pr: PullRequestDto | null;
  workspaceId: string | undefined;
  review: AgentReviewDto | null;
  task: AgentTaskDto | null;
  loading: boolean;
  error: string | null;
  active: boolean;
  onReviewChange: (review: AgentReviewDto) => void;
  onReload: () => void;
}): PrivateThreadSource | undefined {
  const scope = pr === null ? null : `${workspaceId ?? ''}:${pr.repo.toLowerCase()}#${pr.number}`;
  const current = useRef(scope);
  current.current = scope;
  const preparing = useRef(new Set<string>());
  const [preparingScope, setPreparingScope] = useState<string | null>(null);
  // Do not temporarily show the review's threads while discovering the development task.
  const threadReview = task !== null || loading || review?.replay !== undefined || review?.number === null ? null : review;
  const host = task !== null ? { taskId: task.id, workspaceId: task.workspaceId }
    : threadReview === null ? null : { taskId: threadReview.task.id, workspaceId: threadReview.workspaceId };
  const discussions = useTaskDiscussions(host, {
    active,
    bridge: threadReview === null ? undefined : reviewThreadBridge(threadReview.id),
    disabledReason: threadReview?.state === 'CLOSED' ? 'This review has ended, so its threads are read-only' : null,
  });
  if (pr === null || scope === null || (task === null && (review?.replay !== undefined || review?.number === null))) return undefined;
  const source = privateThreadSource(discussions, pr, threadReview === null ? undefined : 'The agent is answering another question on this review');
  const sendDisabledReason = loading ? 'Loading PR context…' : error !== null ? `PR context unavailable: ${error}`
    : host === null && workspaceId === undefined ? `Watch ${pr.repo} to ask the agent about its code.` : null;
  return {
    ...source,
    busy: source.busy || preparingScope === scope,
    busyReason: preparingScope === scope ? 'Preparing PR context…' : source.busyReason,
    sendDisabledReason,
    sendingLabel: preparingScope === scope ? 'Preparing PR context…' : undefined,
    setup: error !== null ? <button type="button" onClick={onReload}>Retry context lookup</button>
      : !loading && host === null && workspaceId === undefined ? <WatchCommentRepository key={scope} repo={pr.repo} onWatched={onReload} /> : undefined,
    modelSettingsHref: settingsRoute('models'),
    ask: async (key, question) => {
      if (sendDisabledReason !== null) throw new Error(sendDisabledReason);
      if (preparing.current.has(scope)) throw new Error('Preparing PR context…');
      if (host !== null) return source.ask(key, question);
      if (workspaceId === undefined) throw new Error('Watch this repository to ask the agent.');
      preparing.current.add(scope);
      setPreparingScope(scope);
      try {
        // Check before preparing: a task retains its model choice, including after a failed first question.
        const settings = await window.bridge.getModelSettings(workspaceId);
        const model = settings.agents.find(candidate => candidate.type === 'REVIEW');
        const provider = settings.providers.find(candidate => candidate.id === model?.configId);
        if (!model?.model || provider === undefined) throw new Error('Choose a Review model in Model settings to ask the agent.');
        if (!providerAvailable(provider.readiness)) throw new Error(provider.diagnostic || `Sign in to ${provider.label} in Model settings.`);
        const prepared = await window.bridge.prepareReviewWorktree(workspaceId, pr.repo, pr.number);
        if (current.current === scope) onReviewChange(prepared);
        await window.bridge.askReviewCommentThread(prepared.id, key, question);
        if (current.current === scope) await discussions.refresh();
      }
      finally {
        preparing.current.delete(scope);
        setPreparingScope(value => value === scope ? null : value);
      }
    },
  };
}
