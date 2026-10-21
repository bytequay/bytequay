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
import { useAsyncAction } from '../useAsyncAction';
import { useEffect, useState } from 'react';
import type { LinkedWorkspaceWorkDto } from '../types';
import { settingsRoute } from '../settings/routes';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import RepoAvatar from '../RepoAvatar';
import { renderMarkdown } from './markdown';
import '../css/linked-work.css';

const labels: Record<string, string> = { STARTING: 'Starting', RUNNING: 'Running', STOPPING: 'Stopping', NEEDS_YOU: 'Needs you',
  FAILED: 'Failed', UNAVAILABLE: 'Unavailable', FINISHED: 'Finished', STOPPED: 'Stopped' };

export function useLinkedWorkspaceWork(workspaceId?: string, taskId?: string) {
  const [links, setLinks] = useState<LinkedWorkspaceWorkDto[]>([]);
  const [incoming, setIncoming] = useState<LinkedWorkspaceWorkDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setLinks([]); setIncoming(null); setError(null);
    if (!workspaceId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      try {
        const [next, request] = await Promise.all([window.bridge.getLinkedWorkspaceWork(workspaceId),
          taskId ? window.bridge.getRelatedWorkspaceRequest(workspaceId, taskId) : Promise.resolve(null)]);
        if (active) { setLinks(next); setIncoming(request); setError(null); }
      } catch (failure) { if (active) setError(String(failure)); }
      finally { if (active) timer = setTimeout(poll, 5000); }
    };
    const poll = () => { if (!document.hidden) void read(); else timer = setTimeout(poll, 5000); };
    void read();
    return () => { active = false; clearTimeout(timer); };
  }, [workspaceId, taskId]);
  return { links, incoming, error };
}

export function LinkedWorkCard({ work, source = false }: { work: LinkedWorkspaceWorkDto; source?: boolean }) {
  const { busy, error, run } = useAsyncAction(String);
  const [owner, repo] = work.repository.split('/');
  const result = work.result?.split(/^#{1,6} Commands and limitations\s*$/m, 2);
  const target = work.targetTaskId ? workspaceAgentTaskRoute(work.targetWorkspaceId, work.targetTaskId) : null;
  const stop = async () => {
    if (!work.targetTaskId) return;
    return run(async () => {
      const impact = await window.bridge.getAgentRuntimeStopImpact(work.targetTaskId, work.targetWorkspaceId);
      await window.bridge.stopAgentRuntime(work.targetTaskId, work.targetWorkspaceId, impact.generation);
    });
  };
  return <div className="linked-work-entry">
    {source && <div className="linked-work-source">Requested in <a href={workspaceAgentTaskRoute(work.sourceWorkspaceId, work.sourceTaskId)}>“{work.sourceTitle}”</a> in {work.sourceRepository}</div>}
    <article className="linked-work-card">
      <div className="linked-work-head"><span className="linked-work-purpose">{work.purpose === 'INVESTIGATE' ? 'Investigation' : 'Fix'}</span><span>in</span>
        <RepoAvatar owner={owner} repo={repo} size={16} radius={3} /><strong>{target ? <a href={target}>{work.repository}</a> : work.repository}</strong>
        <span className="linked-work-kind">{work.kind === 'FORK' ? 'Fork' : 'Dependency'}</span>
        <span className={`linked-work-state is-${work.state.toLowerCase()}`}>{labels[work.state] ?? work.state}</span>
      </div>
      <div className="linked-work-body"><p>{work.question}</p>
        <div className="linked-work-meta"><code title={work.commit ?? undefined}>{work.revision} · {work.commit?.slice(0, 12) ?? 'unresolved'}</code>
          {target && <a href={target} title={`${work.repository} · ${work.targetTitle}`}>Open conversation ›</a>}</div>
        {work.limitation && <p className="linked-work-limitation">{work.limitation}</p>}
        {result && <div className="agt-markdown linked-work-result">{renderMarkdown(result[0], work.repository)}
          {result[1] && <details><summary>Commands and limitations</summary>{renderMarkdown(result[1], work.repository)}</details>}
        </div>}
        {work.state === 'UNAVAILABLE' && <a className="setw-btn" href={settingsRoute('ws-repository', work.sourceWorkspaceId)}>Open workspace settings</a>}
        {work.state === 'NEEDS_YOU' && target && <a className="setw-btn" href={target}>Answer in conversation</a>}
        {(work.state === 'RUNNING' || work.state === 'NEEDS_YOU') && <button type="button" className="setw-btn" disabled={busy} onClick={() => void stop()}>Stop</button>}
        {error && <div role="alert" className="linked-work-limitation">{error}</div>}
      </div>
      {work.purpose === 'INVESTIGATE' && <div className="linked-work-foot">Investigation only — PR, CI fix and review-summary automation are off.</div>}
    </article>
  </div>;
}

export function LinkedWorkRequest({ work }: { work: LinkedWorkspaceWorkDto }) {
  return <div className="agt-user__bubble linked-work-request">
    <div className="agt-user__attribution">ByteQuay · Request from {work.sourceRepository} · {work.purpose === 'INVESTIGATE' ? 'investigation' : 'fix'} at {work.revision}
      {work.commit && <code title={work.commit}> ({work.commit.slice(0, 12)})</code>}</div>
    <p>{work.question}</p>
    <a href={workspaceAgentTaskRoute(work.sourceWorkspaceId, work.sourceTaskId)}>From “{work.sourceTitle}” in {work.sourceRepository} ›</a>
  </div>;
}

export function LinkedWorkList({ links, error }: { links: LinkedWorkspaceWorkDto[]; error: string | null }) {
  const groups = [
    { label: 'Needs you', states: ['NEEDS_YOU', 'FAILED', 'UNAVAILABLE'] },
    { label: 'Running', states: ['STARTING', 'RUNNING', 'STOPPING'] },
    { label: 'Finished', states: ['FINISHED'] }, { label: 'Stopped', states: ['STOPPED'] },
  ];
  return <div className="linked-work-list">
    {error && <div role="alert">Could not load linked work: {error}</div>}
    {!error && links.length === 0 && <p>No linked work yet. Ask in a conversation to investigate or fix something in a related workspace.</p>}
    {groups.map(group => {
      const items = links.filter(work => group.states.includes(work.state));
      return items.length > 0 && <section key={group.label} aria-label={group.label}><h3>{group.label} <span>{items.length}</span></h3>
        {items.map(work => <LinkedWorkCard key={`${work.sourceTaskId}:${work.createdAt}`} work={work} source />)}</section>;
    })}
  </div>;
}
