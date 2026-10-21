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
import { useAsyncAction } from '../useAsyncAction';
import type { BackportChainPrDto, BackportChainViewDto, BackportChainRouteDto, GitCommitDto } from '../types';
import { workspaceAgentTaskRoute, workspaceCherryPickRoute, workspacePullRequestRoute } from '../workspaces/model';
import RepoAvatar from '../RepoAvatar';
import SelectRangeDialog from '../commits/SelectRangeDialog';
import '../css/linked-work.css';
import '../css/backport-chains.css';

export function useBackportChains(workspaceId?: string) {
  const [chains, setChains] = useState<BackportChainViewDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setChains([]); setError(null);
    if (!workspaceId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      try {
        const next = await window.bridge.getBackportChains(workspaceId);
        if (active) { setChains(next); setError(null); }
      } catch (failure) { if (active) setError(String(failure)); }
      finally { if (active) timer = setTimeout(poll, 5000); }
    };
    const poll = () => { if (!document.hidden) void read(); else timer = setTimeout(poll, 5000); };
    void read();
    return () => { active = false; clearTimeout(timer); };
  }, [workspaceId]);
  return { chains, error };
}

function PrLink({ pr, workspaceId }: { pr: BackportChainPrDto; workspaceId: string }) {
  return <a href={workspacePullRequestRoute(workspaceId, pr.number)}>PR #{pr.number} ›</a>;
}

const labels = { ACTIVE: 'Active', BLOCKED: 'Blocked', STOPPING: 'Stopping', STOPPED: 'Stopped', COMPLETED: 'Completed' };

export function BackportChainCard({ view, source = false }: { view: BackportChainViewDto; source?: boolean }) {
  const [picker, setPicker] = useState<{ source: BackportChainRouteDto; hopIndex: number; commits: GitCommitDto[]; version: number } | null>(null);
  const [updated, setUpdated] = useState<BackportChainViewDto | null>(null);
  const { busy, error, setError, run } = useAsyncAction(String);
  const current = updated && updated.chain.id === view.chain.id && updated.chain.version > view.chain.version ? updated : view;
  const { chain, progress } = current;
  const change = (action: 'stop' | 'resume') => run(async () => {
    setUpdated(await (action === 'stop' ? window.bridge.stopBackportChain : window.bridge.resumeBackportChain)(chain.workspaceId, chain.id));
  });
  const selectCommits = (sourceRoute: BackportChainRouteDto, hopIndex: number) => run(async () => {
    const result = await window.bridge.gitLog(sourceRoute.workspaceId, sourceRoute.remote === 'upstream' ? 'UPSTREAM' : 'WORKSPACE', sourceRoute.branch, 300);
    if (result.refresh.state === 'FAILED') throw new Error(result.refresh.error ?? 'Commit history is unavailable');
    setPicker({ source: sourceRoute, hopIndex, commits: result.commits, version: chain.version });
  });
  const saveSelection = async ([oldest, newest]: [string, string]) => {
    if (!picker || busy) return;
    return run(async () => {
      try {
        setUpdated(await window.bridge.selectBackportSource(chain.workspaceId, chain.id, { version: picker.version, hopIndex: picker.hopIndex, oldest, newest }));
        setPicker(null);
      } catch (failure) { setError(String(failure)); setPicker(null); }
    });
  };
  const repoName = (item: BackportChainRouteDto) => `${item.repository.owner}/${item.repository.repo}`;
  const sequential = chain.hops.every((hop, index) => hop.sourceHop === index - 1);
  const route = sequential ? [chain.source, ...chain.hops.map(hop => hop.route)].map(repoName).join(' → ')
    : chain.hops.map(hop => `${repoName(hop.sourceHop < 0 ? chain.source : chain.hops[hop.sourceHop].route)} → ${repoName(hop.route)}`).join(' · ');
  const sourceMerged = chain.sourceMerged;
  return <div className="linked-work-entry">
    {picker && <SelectRangeDialog commits={picker.commits} scope={`${picker.source.repository.owner}/${picker.source.repository.repo} · ${picker.source.branch}`}
      pullRequestRepo={`${picker.source.repository.owner}/${picker.source.repository.repo}`}
      onOpenPullRequest={number => { window.location.hash = workspacePullRequestRoute(picker.source.workspaceId, number); }}
      onCancel={() => setPicker(null)} onApply={anchors => void saveSelection(anchors)} />}
    {source && (chain.requestingTaskId === null
      ? <div className="linked-work-source">Started from Commits in {current.requestingRepository}</div>
      : <div className="linked-work-source">Requested in <a href={workspaceAgentTaskRoute(chain.workspaceId, chain.requestingTaskId)}>“{current.title}”</a> in {current.requestingRepository}</div>)}
    <article className="linked-work-card backport-chain">
      <div className="linked-work-head"><strong>Backport chain</strong><span className="backport-route">· {route}</span>
        <span className={`linked-work-state is-${chain.state.toLowerCase()}`}>{labels[chain.state]}</span>
        {(chain.state === 'BLOCKED' || chain.state === 'STOPPED') && <button className="setw-btn" disabled={busy} onClick={() => void change('resume')}>Resume</button>}
        {(chain.state === 'ACTIVE' || chain.state === 'BLOCKED') && <button className="setw-btn" disabled={busy} onClick={() => void change('stop')}>Stop</button>}
      </div>
      <ol className="backport-steps">
        <li className={sourceMerged ? 'is-complete' : 'is-current'}>
          <div className="backport-step-title"><span className="backport-marker" aria-hidden="true">{sourceMerged ? '✓' : '○'}</span>
            <RepoAvatar owner={chain.source.repository.owner} repo={chain.source.repository.repo} size={16} radius={3} />
            <strong>{chain.source.repository.owner}/{chain.source.repository.repo}</strong><span>Source PR</span>
            <span className="backport-links">{chain.sourceTaskId && <a href={workspaceAgentTaskRoute(chain.source.workspaceId, chain.sourceTaskId)}>Conversation ›</a>}
              {chain.sourcePullRequest && <PrLink pr={chain.sourcePullRequest} workspaceId={chain.source.workspaceId} />}</span>
          </div>
          <p>{sourceMerged ? 'PR merged' : chain.sourcePullRequest ? `Waiting for #${chain.sourcePullRequest.number} to merge` : 'Waiting for the conversation’s published PR'}</p>
        </li>
        {chain.hops.map((hop, index) => {
          const state = progress[index];
          const previous = hop.sourceHop < 0 ? chain.source : chain.hops[hop.sourceHop].route;
          const currentHop = !hop.completed && (hop.sourceHop < 0 || chain.hops[hop.sourceHop].completed);
          const caption = hop.noOp ? 'Already present — no PR needed' : hop.completed ? 'Backport merged'
            : currentHop && chain.state === 'STOPPED' ? 'Stopped — work kept'
            : currentHop && chain.state === 'BLOCKED' ? hop.selection ? 'Selected commits ready' : 'Needs your attention'
            : hop.pullRequest ? `Waiting for #${hop.pullRequest.number} to merge`
            : state?.status === 'RUNNING' ? 'Cherry-pick in progress'
            : state?.status === 'PAUSED' ? 'Cherry-pick needs your input'
            : state?.status === 'FINISHED' ? 'Cherry-pick finished'
            : currentHop && (hop.landedSource || hop.selection) ? 'Preparing cherry-pick' : `Starts after ${repoName(previous)} merges`;
          return <li key={hop.route.workspaceId} className={hop.completed ? 'is-complete' : currentHop ? 'is-current' : ''}>
            <div className="backport-step-title"><span className="backport-marker" aria-hidden="true">{hop.completed ? '✓' : chain.state === 'STOPPED' && currentHop ? '■' : '○'}</span>
              <RepoAvatar owner={hop.route.repository.owner} repo={hop.route.repository.repo} size={16} radius={3} />
              <strong>{hop.route.repository.owner}/{hop.route.repository.repo}</strong><span>Backport</span>
              <span className="backport-links">{hop.executionId && <a href={workspaceCherryPickRoute(hop.route.workspaceId, hop.executionId)}>Cherry-pick ›</a>}
                {hop.pullRequest && <PrLink pr={hop.pullRequest} workspaceId={hop.route.workspaceId} />}</span>
            </div>
            <p>From {repoName(previous)} · {caption}</p>
            {currentHop && chain.reason && <div className="backport-block"><p>{chain.reason}</p>
              {chain.state === 'BLOCKED' && !hop.executionId && !hop.landedSource && <button className="setw-btn" disabled={busy} onClick={() => void selectCommits(previous, index)}>Select commits</button>}
              {state?.ciRunId && hop.pullRequest && <a className="setw-btn" href={workspacePullRequestRoute(hop.route.workspaceId, hop.pullRequest.number)}>Open CI run</a>}
            </div>}
            {currentHop && state?.summary && <p className="backport-summary">{state.summary}</p>}
          </li>;
        })}
      </ol>
      {error && <p role="alert" className="cmt-error">{error}</p>}
      <div className="linked-work-foot">{chain.state === 'STOPPED' ? 'Stopped. Existing work and PRs are kept. Resume rechecks current facts.'
        : chain.state === 'BLOCKED' ? 'Paused. Resume rechecks relationships, routing and PR state before continuing.'
        : chain.state === 'COMPLETED' ? 'Complete. Every backport merged or was already present.'
        : 'Each step waits for its source PR to merge. Independent destinations run in parallel. ByteQuay never merges PRs.'}</div>
    </article>
  </div>;
}

export function BackportChainList({ chains, error }: { chains: BackportChainViewDto[]; error: string | null }) {
  const groups = [
    ['Needs you', chains.filter(view => view.chain.state === 'BLOCKED')],
    ['Active', chains.filter(view => view.chain.state === 'ACTIVE' || view.chain.state === 'STOPPING')],
    ['Stopped', chains.filter(view => view.chain.state === 'STOPPED')],
    ['Completed', chains.filter(view => view.chain.state === 'COMPLETED')],
  ] as const;
  return <div className="linked-work-list">
    <div className="backport-intro"><strong>Backport chains</strong>
      <p>Pick a merged pull request in History and choose Backport to carry it down the repo line, or ask an agent with @backport. Each step waits for the one before it to merge.</p>
    </div>
    {error && <p role="alert" className="cmt-error">{error}</p>}
    {groups.map(([label, rows]) => rows.length > 0 && <section key={label}><h3>{label} <span>{rows.length}</span></h3>
      {rows.map(view => <BackportChainCard key={view.chain.id} view={view} source />)}</section>)}
  </div>;
}
