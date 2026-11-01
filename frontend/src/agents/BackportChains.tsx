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
import { Check, Clock, GitMerge, GitPullRequest, Pause, Play, RotateCcw, Square, X } from 'lucide-react';
import { Pill, type CardTone } from '@bytequay/ui/cards';
import { useAsyncAction } from '../useAsyncAction';
import type { BackportChainPrDto, BackportChainViewDto, BackportChainRouteDto, GitCommitDto } from '../types';
import { workspaceAgentTaskRoute, workspaceCherryPickRoute, workspacePullRequestRoute } from '../workspaces/model';
import SelectRangeDialog from '../commits/SelectRangeDialog';
import { Spinner } from './AgentConversation';
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

type Status = 'waiting' | 'running' | 'stopped' | 'blocked' | 'done';
const TONE: Record<Status, CardTone> = { waiting: 'neutral', running: 'blue', stopped: 'amber', blocked: 'red', done: 'green' };

function StatusIcon({ status }: { status: Status }) {
  if (status === 'running') return <Spinner />;
  if (status === 'stopped') return <Pause fill="currentColor" stroke="none" aria-hidden="true" />;
  const Icon = { waiting: Clock, blocked: X, done: Check }[status];
  return <Icon aria-hidden="true" />;
}

/** One repository on the route: its state is the dot, then its name, its PR or branch, and what is happening there. */
function RouteNode({ dot, route, href, pr, note, status }: {
  dot: ReactNode; route: BackportChainRouteDto; href?: string; pr: BackportChainPrDto | null; note: string; status?: Status;
}) {
  const name = `${route.repository.owner}/${route.repository.repo}`;
  return <li className="backport-node">
    {dot}
    <div className="backport-node__body">
      <div className="backport-node__line">
        {href ? <a className="backport-node__repo" href={href} title={name}>{name}</a> : <span className="backport-node__repo" title={name}>{name}</span>}
        {pr ? <a className="backport-node__pr" href={workspacePullRequestRoute(route.workspaceId, pr.number)}>#{pr.number}</a>
          : <code className="backport-node__branch">{route.branch}</code>}
      </div>
      <span className={`backport-node__note${status === undefined ? '' : ` is-${status}`}`}>{note}</span>
    </div>
  </li>;
}

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
  const stopped = chain.state === 'STOPPED' || chain.state === 'STOPPING';
  const hops = chain.hops.map((hop, index) => {
    const previous = hop.sourceHop < 0 ? chain.source : chain.hops[hop.sourceHop].route;
    const currentHop = !hop.completed && (hop.sourceHop < 0 || chain.hops[hop.sourceHop].completed);
    const step = progress[index]?.status;
    const [status, note]: [Status, string] = hop.noOp ? ['done', 'Already present — no PR needed']
      : hop.completed ? ['done', 'Backport merged']
      : currentHop && stopped ? ['stopped', 'Stopped · work and PRs kept']
      : currentHop && chain.state === 'BLOCKED' ? ['blocked', chain.reason ?? 'Needs your attention']
      : hop.pullRequest ? ['done', 'Opened backport PR']
      : step === 'RUNNING' ? ['running', `Cherry-picking onto ${hop.route.branch}`]
      : step === 'PAUSED' ? ['blocked', 'Cherry-pick needs your input']
      : step === 'FINISHED' ? ['running', 'Cherry-pick finished']
      : currentHop && (hop.landedSource || hop.selection) ? ['running', 'Preparing cherry-pick']
      : ['waiting', hop.sourceHop < 0 && !chain.sourceMerged ? 'Starts automatically after merge' : `Starts after ${repoName(previous)} merges`];
    return { hop, index, previous, currentHop, status, note: sequential ? note : `From ${repoName(previous)} · ${note}` };
  });
  const [status, label]: [Status, string] = chain.state === 'COMPLETED' ? ['done', 'Done']
    : stopped ? ['stopped', chain.state === 'STOPPING' ? 'Stopping' : 'Stopped']
    : chain.state === 'BLOCKED' ? ['blocked', 'Blocked']
    : hops.some(item => item.status === 'running') ? ['running', 'Running'] : ['waiting', 'Queued'];
  const blocked = chain.state === 'BLOCKED' ? hops.find(item => item.currentHop) : undefined;
  const action = (text: string, icon: ReactNode, onClick: () => void) =>
    <button type="button" className="bq-button backport-chain__action" disabled={busy} onClick={onClick}>{icon}{text}</button>;
  return <div className="linked-work-entry">
    {picker && <SelectRangeDialog commits={picker.commits} scope={`${picker.source.repository.owner}/${picker.source.repository.repo} · ${picker.source.branch}`}
      pullRequestRepo={`${picker.source.repository.owner}/${picker.source.repository.repo}`}
      onOpenPullRequest={number => { window.location.hash = workspacePullRequestRoute(picker.source.workspaceId, number); }}
      onCancel={() => setPicker(null)} onApply={anchors => void saveSelection(anchors)} />}
    {source && (chain.requestingTaskId === null
      ? <div className="linked-work-source">Started from Commits in {current.requestingRepository}</div>
      : <div className="linked-work-source">Requested in <a href={workspaceAgentTaskRoute(chain.workspaceId, chain.requestingTaskId)}>“{current.title}”</a> in {current.requestingRepository}</div>)}
    <article className="backport-chain">
      <div className="backport-chain__head">
        <span className="backport-chain__title">Backport</span>
        <Pill tone={TONE[status]}><StatusIcon status={status} />{label}</Pill>
        <span className="backport-chain__actions">
          {chain.state === 'STOPPED' && action('Resume', <Play fill="currentColor" aria-hidden="true" />, () => void change('resume'))}
          {blocked && !blocked.hop.executionId && !blocked.hop.landedSource && !blocked.hop.selection
            ? action('Select commits', null, () => void selectCommits(blocked.previous, blocked.index))
            : chain.state === 'BLOCKED' && (blocked?.hop.selection
              ? action('Resume', <Play fill="currentColor" aria-hidden="true" />, () => void change('resume'))
              : action('Retry', <RotateCcw aria-hidden="true" />, () => void change('resume')))}
          {/* Stop stays on a queued or blocked chain too: it is the only way to end one. */}
          {(chain.state === 'ACTIVE' || chain.state === 'BLOCKED') && action('Stop', <Square fill="currentColor" aria-hidden="true" />, () => void change('stop'))}
        </span>
      </div>
      <ol className={`backport-rail${status === 'waiting' || status === 'stopped' ? ' is-dashed' : ''}`}>
        <RouteNode route={chain.source} pr={chain.sourcePullRequest}
          href={chain.sourceTaskId ? workspaceAgentTaskRoute(chain.source.workspaceId, chain.sourceTaskId) : undefined}
          dot={<Pill className="is-dot" tone={chain.sourceMerged ? 'purple' : chain.sourcePullRequest ? 'green' : 'neutral'}>
            {chain.sourceMerged ? <GitMerge aria-hidden="true" /> : <GitPullRequest aria-hidden="true" />}</Pill>}
          note={chain.sourceMerged ? 'Merged' : chain.sourcePullRequest ? 'Open · waiting to merge' : 'Waiting for the conversation’s published PR'} />
        {hops.map(item => <RouteNode key={item.hop.route.workspaceId} route={item.hop.route} pr={item.hop.pullRequest}
          href={item.hop.executionId ? workspaceCherryPickRoute(item.hop.route.workspaceId, item.hop.executionId) : undefined}
          dot={<Pill className="is-dot" tone={TONE[item.status]}><StatusIcon status={item.status} /></Pill>}
          note={item.note} status={item.status} />)}
      </ol>
      {error && <p role="alert" className="cmt-error">{error}</p>}
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
