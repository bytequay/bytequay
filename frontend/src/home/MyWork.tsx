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
import { ArrowRight, Check, Circle, GitPullRequest, LoaderCircle, X } from 'lucide-react';
import { plural } from '@bytequay/ui/displayText';
import { relativeTime } from '@bytequay/ui/relativeTime';
import Avatar from '../Avatar';
import { Card, CardAge } from '../cards/Card';
import { ProviderIcon } from '../agents/ProviderIcon';
import { getCached, setCached } from '../dataCache';
import { pollVisible } from '../ide/pollVisible';
import type { AgentTaskChangesDto } from '../types';
import type { WorkItemDto } from '../workitems/contracts';
import { ISSUE_ATTENTION_REFRESH_MS } from './attention';
import { WORK_LANES, WORK_SOURCES, workPrStatus, type WorkItem, type WorkLane, type WorkPr, type WorkSource } from './work';
import { openTarget } from './TodayList';
import '../css/my-work.css';

const COLLAPSED_KEY = 'bytequay.myWork.collapsed';
const FILTERS: ('all' | WorkSource)[] = ['all', 'jira', 'issue', 'local', 'pr'];
const LOCAL_WORK_SEED = 'home:local-work';
/** A lane shows this many cards until asked for the rest, so one busy lane does not stretch the board. */
const LANE_LIMIT = 3;
const DAY_MS = 24 * 60 * 60 * 1000;
const DATES = [
  { days: 0, label: 'Any time' },
  { days: 1, label: 'Past day' },
  { days: 7, label: 'Past week' },
  { days: 30, label: 'Past month' },
  { days: 90, label: 'Past 3 months' },
];

function loadCollapsed(): boolean {
  try { return window.localStorage.getItem(COLLAPSED_KEY) === 'true'; } catch { return false; }
}

/** Local work items of the listed workspaces, ids joined by commas; read on Home's issue clock, since they change about as often. */
export function useLocalWork(workspaceIds: string): WorkItemDto[] {
  const [work, setWork] = useState<WorkItemDto[]>(() => getCached<WorkItemDto[]>(LOCAL_WORK_SEED) ?? []);
  useEffect(() => {
    if (workspaceIds === '') {
      setWork([]);
      return;
    }
    let active = true;
    // A workspace that cannot be read drops out until the next read rather than failing the others.
    const stop = pollVisible(() => Promise.allSettled(workspaceIds.split(',').map(id => Promise.resolve().then(() => window.bridge.listWorkItems(id))))
      .then(results => {
        if (!active) return;
        const found = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
        setCached(LOCAL_WORK_SEED, found);
        setWork(shown => JSON.stringify(shown) === JSON.stringify(found) ? shown : found);
      }), ISSUE_ATTENTION_REFRESH_MS);
    return () => { active = false; stop(); };
  }, [workspaceIds]);
  return work;
}

/** What the checkouts of tasks still without a pull request hold, read again only when that set of tasks changes. */
function useBranchChanges(items: WorkItem[]): Record<string, AgentTaskChangesDto> {
  const [changes, setChanges] = useState<Record<string, AgentTaskChangesDto>>({});
  const tasks = JSON.stringify(items.flatMap(item => item.branch === null || item.task === null ? [] : [[item.task.workspaceId, item.task.id]])
    .sort(([leftWorkspace, leftTask], [rightWorkspace, rightTask]) => leftWorkspace.localeCompare(rightWorkspace) || leftTask.localeCompare(rightTask)));
  useEffect(() => {
    const listed = JSON.parse(tasks) as [string, string][];
    if (listed.length === 0) return;
    const workspaces = [...new Set(listed.map(([workspace]) => workspace))];
    let active = true;
    void Promise.allSettled(workspaces.map(id => Promise.resolve().then(() => window.bridge.listAgentTaskChanges(id,
      [...new Set(listed.filter(([workspace]) => workspace === id).map(([, task]) => task))])))).then(results => {
      if (active) setChanges(Object.assign({}, ...results.map(result => result.status === 'fulfilled' ? result.value : {})));
    });
    return () => { active = false; };
  }, [tasks]);
  return changes;
}

/**
 * Everything the viewer is working on, as a board of four lanes by where its pull requests stand. A card is a ticket, issue, local
 * work or pull request, with the agent working on it and the pull requests it delivers through.
 */
export default function MyWork({ items, todayKeys }: { items: WorkItem[]; todayKeys: ReadonlySet<string> }) {
  const [source, setSource] = useState<'all' | WorkSource>('all');
  const [collapsed, setCollapsed] = useState(loadCollapsed);
  const [openLanes, setOpenLanes] = useState<ReadonlySet<WorkLane>>(new Set());
  const shown = items.filter(item => source === 'all' || item.source === source);
  const lanes = WORK_LANES.map(lane => ({ ...lane, items: shown.filter(item => item.lane === lane.id) }));
  const changes = useBranchChanges(collapsed ? [] : lanes.flatMap(lane => openLanes.has(lane.id) ? lane.items : lane.items.slice(0, LANE_LIMIT)));
  const collapse = (next: boolean) => {
    setCollapsed(next);
    try { window.localStorage.setItem(COLLAPSED_KEY, String(next)); } catch { /* the choice still holds for this visit */ }
  };

  return (
    <section className="mw" aria-label="My work">
      {/* The Collapse button stays the keyboard control; the bar's empty space and label are a larger mouse target for it. */}
      <div className={`atn-head${items.length > 0 ? ' mw-head' : ''}`} onClick={event => {
        if (items.length > 0 && event.target instanceof Element && !event.target.closest('button, a, .atn-filters')) collapse(!collapsed);
      }}>
        <span className="atn-head__label">My work</span>
        {items.length > 0 && <span className="atn-count">{items.length}</span>}
        <SourceTabs source={source} onSource={setSource} />
        {items.length > 0 && (
          <button type="button" className="atn-approved__toggle" aria-expanded={!collapsed} onClick={() => collapse(!collapsed)}>
            {collapsed ? 'Expand' : 'Collapse'}
          </button>
        )}
        <a className="mw-all" href="#/work">All my work<ArrowRight size={12} aria-hidden="true" /></a>
      </div>
      {items.length === 0 ? <div className="atn-clear">Nothing in progress.</div>
        : collapsed ? (
          <div className="mw-pills">
            {lanes.map(lane => (
              <button key={lane.id} type="button" className="mw-pill" onClick={() => collapse(false)}>
                <span className={`mw-dot is-${lane.id}`} aria-hidden="true" />{lane.name}<span className="mw-pill__count">{lane.items.length}</span>
              </button>
            ))}
          </div>
        ) : (
          <div className="mw-board">
            {lanes.map(lane => (
              <div key={lane.id} className="mw-lane" role="list" aria-label={lane.name}>
                <div className="mw-lane__head">
                  <span className={`mw-dot is-${lane.id}`} aria-hidden="true" />
                  <span className="mw-lane__name">{lane.name}</span>
                  <span className="mw-lane__count">{lane.items.length}</span>
                  <span className="mw-lane__hint">{lane.hint}</span>
                </div>
                {(openLanes.has(lane.id) ? lane.items : lane.items.slice(0, LANE_LIMIT)).map(item => (
                  <WorkCard key={item.key} item={item} onToday={item.keys.some(key => todayKeys.has(key))}
                    commits={item.task === null ? 0 : changes[item.task.id]?.commits ?? 0} />
                ))}
                {lane.items.length > LANE_LIMIT && (
                  <button type="button" className="mw-lane__more" aria-expanded={openLanes.has(lane.id)} onClick={() => setOpenLanes(previous => {
                    const next = new Set(previous);
                    if (!next.delete(lane.id)) next.add(lane.id);
                    return next;
                  })}>
                    {openLanes.has(lane.id) ? 'Show less' : `Show ${lane.items.length - LANE_LIMIT} more`}
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
    </section>
  );
}

/** Every piece of the viewer's work, open or ended, newest first, narrowed by source, repository, agent and when it last moved. */
export function AllMyWork({ items, todayKeys }: { items: WorkItem[]; todayKeys: ReadonlySet<string> }) {
  const [source, setSource] = useState<'all' | WorkSource>('all');
  const [repo, setRepo] = useState('');
  const [agent, setAgent] = useState('');
  const [days, setDays] = useState(0);
  const repos = [...new Set(items.flatMap(item => item.repos))].sort();
  const agents = [...new Set(items.flatMap(item => item.agents))].sort();
  const since = Date.now() - days * DAY_MS;
  const shown = items.filter(item => (source === 'all' || item.source === source) && (repo === '' || item.repos.includes(repo))
    && (agent === '' || item.agents.includes(agent)) && (days === 0 || Date.parse(item.updatedAt) >= since));
  const changes = useBranchChanges(shown);

  return (
    <section className="mw" aria-label="All my work">
      <div className="atn-head mw-filters">
        <span className="atn-head__label">All my work</span>
        <span className="atn-count">{shown.length}</span>
        <SourceTabs source={source} onSource={setSource} />
        <select className="mw-select" aria-label="Filter by repository" value={repo} onChange={event => setRepo(event.target.value)}>
          <option value="">All repositories</option>
          {repos.map(each => <option key={each} value={each}>{each}</option>)}
        </select>
        <select className="mw-select" aria-label="Filter by agent" value={agent} onChange={event => setAgent(event.target.value)}>
          <option value="">All agents</option>
          {agents.map(each => <option key={each} value={each}>{each}</option>)}
        </select>
        <select className="mw-select" aria-label="Filter by last update" value={days} onChange={event => setDays(Number(event.target.value))}>
          {DATES.map(each => <option key={each.days} value={each.days}>{each.label}</option>)}
        </select>
      </div>
      {shown.length === 0 ? <div className="atn-clear">{items.length === 0 ? 'No work yet.' : 'No work matches these filters.'}</div> : (
        <div className="mw-list" role="list" aria-label="Work">
          {shown.map(item => (
            <WorkCard key={item.key} item={item} onToday={item.keys.some(key => todayKeys.has(key))}
              commits={item.task === null ? 0 : changes[item.task.id]?.commits ?? 0} />
          ))}
        </div>
      )}
    </section>
  );
}

function SourceTabs({ source, onSource }: { source: 'all' | WorkSource; onSource: (source: 'all' | WorkSource) => void }) {
  return (
    <div className="atn-filters" role="tablist" aria-label="Filter my work by source">
      {FILTERS.map(id => (
        <button key={id} type="button" role="tab" aria-selected={source === id}
          className={`atn-filter${source === id ? ' is-active' : ''}`} onClick={() => onSource(id)}>
          {id === 'all' ? 'All' : WORK_SOURCES[id].filter}
        </button>
      ))}
    </div>
  );
}

function WorkCard({ item, onToday, commits }: { item: WorkItem; onToday: boolean; commits: number }) {
  const source = WORK_SOURCES[item.source];
  const { running } = item;
  return (
    <div role="listitem">
      <Card compact className="mw-card" label={`${[source.label, item.label].filter(Boolean).join(' ')}: ${item.title}`} onOpen={() => openTarget(item.target)}>
        <div className="mw-card__head">
          <span className={`mw-src is-${item.source}`} aria-hidden="true" />
          {item.label !== '' && <span className="mw-card__key">{item.label}</span>}
          <span className="mw-card__source">{source.label}</span>
          <span className="mw-spacer" />
          {onToday && <span className="today-tag">Today</span>}
          <CardAge at={item.updatedAt} />
        </div>
        <div className="mw-card__title" title={item.title}>{item.title}</div>
        {running !== null && (
          <div className="mw-run">
            <span className="mw-run__avatar" title={running.agent}><ProviderIcon id={running.laneId} size={11} /></span>
            <span className="mw-run__text"><span className="mw-run__agent">{running.agent}</span> · {running.text}</span>
            <LoaderCircle size={12} className="today-spin" aria-label="Running" />
            <time dateTime={running.since}>{relativeTime(running.since, { suffix: false })}</time>
          </div>
        )}
        {item.prs.length === 0 && (
          <div className="mw-nopr">{item.ended ?? ['No PR yet', item.branch, commits === 0 ? null : plural(commits, 'local commit')].filter(Boolean).join(' · ')}</div>
        )}
        {item.prs.length > 0 && <div className="mw-prs">{item.prs.map(pr => <PrLine key={`${pr.repo}#${pr.number}`} pr={pr} />)}</div>}
        {item.next !== null && <div className="mw-next"><ArrowRight size={12} aria-hidden="true" /><span>{item.next}</span></div>}
      </Card>
    </div>
  );
}

function PrLine({ pr }: { pr: WorkPr }) {
  const status = workPrStatus(pr);
  const ci = pr.state === 'open' ? pr.row?.ciStatus ?? null : null;
  return (
    <button type="button" className="mw-pr" title={pr.title}
      onClick={event => { event.stopPropagation(); openTarget({ kind: 'PULL', repo: pr.repo, number: pr.number, workspaceId: null, id: null, connectionId: null, cloudId: null }); }}>
      <span className="mw-pr__line">
        <GitPullRequest size={13} className={`mw-pr__icon is-${pr.state}`} aria-hidden="true" />
        <span className="mw-pr__name">{pr.repo.slice(pr.repo.indexOf('/') + 1)} #{pr.number}</span>
        <span className="mw-spacer" />
        {ci === 'PASSING' && <Check size={13} className="mw-ci is-passing" aria-label="CI passing" />}
        {ci === 'FAILING' && <X size={13} className="mw-ci is-failing" aria-label="CI failing" />}
        {ci === 'PENDING' && <Circle size={8} fill="currentColor" strokeWidth={0} className="mw-ci is-pending" aria-label="CI pending" />}
        {/* ponytail: the list payload names only reviewers still requested, so every ring is pending; per-reviewer verdicts need review states on the row. */}
        {pr.state === 'open' && (pr.row?.requestedReviewers ?? []).map(login => (
          <span key={login} className="mw-reviewer" title={`${login} · review pending`}><Avatar login={login} size={15} /></span>
        ))}
      </span>
      <span className={`mw-pr__state is-${status.tone}`}>{status.text}</span>
    </button>
  );
}
