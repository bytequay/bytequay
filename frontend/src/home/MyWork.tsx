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
import { useState } from 'react';
import {
  ArrowRight, Check, ChevronRight, CircleDot, ExternalLink, GitPullRequest, GitPullRequestDraft, Hand, Hourglass, Laptop, LoaderCircle, MessageCircleQuestion,
  SquareKanban, X, type LucideIcon,
} from 'lucide-react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import Avatar from '../Avatar';
import { CardActions, CardAge, Card } from '../cards/Card';
import type { AgendaTargetDto } from '../types';
import { WORK_LANES, WORK_SOURCES, workPrStatus, type WorkItem, type WorkLane, type WorkPr, type WorkSource } from './work';
import { openTarget } from './TodayList';
import '../css/my-work.css';

const FILTERS: ('all' | WorkSource)[] = ['all', 'jira', 'issue', 'local', 'pr'];
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
const SOURCE_ICONS: Record<WorkSource, LucideIcon> = { local: Laptop, jira: SquareKanban, pr: GitPullRequest, issue: CircleDot };
const DECISION_ICONS: Record<string, LucideIcon> = { Approval: Hand, Question: MessageCircleQuestion };

const laneItems = (items: WorkItem[]) => WORK_LANES.map(lane => ({ ...lane, items: items.filter(item => item.lane === lane.id) }));

/**
 * Home's one line of My work: the four lanes and their counts. The line opens the board in the Assistant, and a lane with work opens it
 * with that lane in focus.
 */
export function MyWorkLine({ items, onOpen }: { items: WorkItem[]; onOpen: (lane: WorkLane | null) => void }) {
  return (
    <div className="mw-line" title="Open My work in the Assistant" onClick={() => onOpen(null)}>
      {laneItems(items).map(lane => lane.items.length === 0
        ? <span key={lane.id} className="mw-pill is-empty"><span className={`mw-dot is-${lane.id}`} aria-hidden="true" />{lane.name}</span>
        : (
          <button key={lane.id} type="button" className="mw-pill" title={`Open My work on ${lane.name}`}
            onClick={event => { event.stopPropagation(); onOpen(lane.id); }}>
            <span className={`mw-dot is-${lane.id}`} aria-hidden="true" />{lane.name}<span className="mw-pill__count">{lane.items.length}</span>
          </button>
        ))}
      <span className="mw-spacer" />
      <button type="button" className="mw-line__open" aria-label="Open My work" onClick={event => { event.stopPropagation(); onOpen(null); }}>
        <ChevronRight size={14} aria-hidden="true" />
      </button>
    </div>
  );
}

/**
 * Everything the viewer is working on, as a board of four lanes by where its pull requests stand. A card is a ticket, issue, local
 * work or pull request, with the agent working on it and the pull requests it delivers through. A lane in focus keeps the others
 * faded beside it; where the four lanes do not fit side by side they stack, the focused one first (my-work.css).
 */
export function MyWorkBoard({ items, lane, onLane, onOpen, onDecision, onAllWork }: {
  items: WorkItem[];
  lane: WorkLane | null;
  onLane: (lane: WorkLane | null) => void;
  /** Opens a card's item or pull request, after what covers the page has closed. */
  onOpen: (target: AgendaTargetDto) => void;
  /** Opens a task's decision, which counts in the Assistant's Decision rather than here. */
  onDecision: () => void;
  onAllWork: () => void;
}) {
  const [source, setSource] = useState<'all' | WorkSource>('all');
  const [openLanes, setOpenLanes] = useState<ReadonlySet<WorkLane>>(new Set());
  const lanes = laneItems(items.filter(item => source === 'all' || item.source === source));
  const focused = lanes.find(each => each.id === lane);

  return (
    <section className="mw mw-boardview" aria-label="My work">
      <div className="mw-head">
        <span className="mw-head__label">My work</span>
        <SourceTabs source={source} onSource={setSource} />
        {focused !== undefined && (
          <button type="button" className="mw-focus" title="Show all lanes" onClick={() => onLane(null)}>
            <span className={`mw-dot is-${focused.id}`} aria-hidden="true" />{focused.name}<X size={11} aria-hidden="true" />
          </button>
        )}
        <span className="mw-spacer" />
        <button type="button" className="mw-all" onClick={onAllWork}>All my work →</button>
      </div>
      <div className="mw-board">
        {lanes.map(each => (
          <div key={each.id} className={`mw-lane${lane === null ? '' : lane === each.id ? ' is-focused' : ' is-faded'}`} role="list" aria-label={each.name}>
            <div className="mw-lane__head">
              <span className={`mw-dot is-${each.id}`} aria-hidden="true" />
              <span className="mw-lane__name">{each.name}</span>
              {each.items.length > 0 && <span className="mw-lane__count">{each.items.length}</span>}
              <span className="mw-lane__hint">{each.hint}</span>
            </div>
            {each.items.length > 0 && (
              <div className="mw-lane__cards">
                {(openLanes.has(each.id) ? each.items : each.items.slice(0, LANE_LIMIT)).map(item => <WorkCard key={item.key} item={item} onOpen={onOpen} onDecision={onDecision} />)}
              </div>
            )}
            {each.items.length > LANE_LIMIT && (
              <button type="button" className="mw-lane__more" aria-expanded={openLanes.has(each.id)} onClick={() => setOpenLanes(previous => {
                const next = new Set(previous);
                if (!next.delete(each.id)) next.add(each.id);
                return next;
              })}>
                {openLanes.has(each.id) ? 'Show less' : `Show ${each.items.length - LANE_LIMIT} more`}
              </button>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

/** Every piece of the viewer's work, open or ended, newest first, narrowed by source, repository, agent and when it last moved. */
export function AllMyWork({ items, onDecision }: { items: WorkItem[]; onDecision: () => void }) {
  const [source, setSource] = useState<'all' | WorkSource>('all');
  const [repo, setRepo] = useState('');
  const [agent, setAgent] = useState('');
  const [days, setDays] = useState(0);
  const repos = [...new Set(items.flatMap(item => item.repos))].sort();
  const agents = [...new Set(items.flatMap(item => item.agents))].sort();
  const since = Date.now() - days * DAY_MS;
  const shown = items.filter(item => (source === 'all' || item.source === source) && (repo === '' || item.repos.includes(repo))
    && (agent === '' || item.agents.includes(agent)) && (days === 0 || Date.parse(item.updatedAt) >= since));

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
          {shown.map(item => <WorkCard key={item.key} item={item} onOpen={openTarget} onDecision={onDecision} />)}
        </div>
      )}
    </section>
  );
}

function SourceTabs({ source, onSource }: { source: 'all' | WorkSource; onSource: (source: 'all' | WorkSource) => void }) {
  return (
    <div className="mw-tabs" role="tablist" aria-label="Filter my work by source">
      {FILTERS.map(id => (
        <button key={id} type="button" role="tab" aria-selected={source === id}
          className={`mw-tab${source === id ? ' is-active' : ''}`} onClick={() => onSource(id)}>
          {id === 'all' ? 'All' : WORK_SOURCES[id].filter}
        </button>
      ))}
    </div>
  );
}

/** Where a card's work lives, as its top line names it: the ticket's key, the item's repository and number, or local work's repository. */
function cardLabel(item: WorkItem): string {
  if (item.source === 'jira') return item.label;
  if (item.source === 'local') return [item.repos[0], WORK_SOURCES.local.label].filter(Boolean).join(' · ');
  return item.target.repo === null || item.target.number === null ? item.label : `${item.target.repo}#${item.target.number}`;
}

/** The small circle a card's top line starts with: its lane's colour, gold while it waits on a decision, dashed for a draft. */
function statusClass(item: WorkItem): string {
  if (item.prs.length > 0 && item.prs.every(pr => pr.state === 'draft')) return 'is-draft';
  return item.decision !== null && item.lane === 'progress' ? 'is-decision' : `is-${item.lane}`;
}

function WorkCard({ item, onOpen, onDecision }: { item: WorkItem; onOpen: (target: AgendaTargetDto) => void; onDecision: () => void }) {
  const Source = SOURCE_ICONS[item.source];
  const DecisionIcon = item.decision === null ? null : DECISION_ICONS[item.decision] ?? Hourglass;
  const { running } = item;
  const label = cardLabel(item);
  return (
    <div role="listitem">
      <Card compact className="mw-card" label={`${label}: ${item.title}`} onOpen={() => onOpen(item.target)}>
        <div className="mw-card__head">
          <span className={`mw-status ${statusClass(item)}`} aria-hidden="true" />
          <Source size={13} className="mw-card__source" aria-label={WORK_SOURCES[item.source].label} />
          <span className="mw-card__label" title={label}>{label}</span>
          <CardActions><ExternalLink size={13} className="mw-card__open" aria-label="Open" /></CardActions>
          <CardAge at={item.updatedAt} />
        </div>
        <div className="mw-card__title" title={item.title}>{item.title}</div>
        {item.prs.map(pr => <PrBox key={`${pr.repo}#${pr.number}`} pr={pr} onOpen={onOpen} />)}
        {item.prs.length === 0 && item.ended !== null && <div className="mw-card__ended">{item.ended}</div>}
        {running !== null && (
          <div className="mw-run" title={`${running.agent} · ${running.text}`}>
            <LoaderCircle size={13} className="mw-run__spin" aria-label="Running" />
            <span className="mw-run__text">{running.text} · {relativeTime(running.since, { suffix: false })}</span>
          </div>
        )}
        {DecisionIcon !== null && (
          <div>
            <button type="button" className="mw-mark" title="Open in Decision" onClick={event => { event.stopPropagation(); onDecision(); }}>
              <DecisionIcon size={12} aria-hidden="true" />{item.decision}
            </button>
          </div>
        )}
        {item.next !== null && (
          <div className="mw-next">
            <ArrowRight size={13} aria-hidden="true" />
            <span className="mw-next__text">{item.next}</span>
            {/* ponytail: opens where the step is done; a one-click Merge here needs the head commit on My work's rows to bind it to. */}
            <CardActions>
              <button type="button" className="mw-next__act" onClick={event => { event.stopPropagation(); onOpen(item.target); }}>Open</button>
            </CardActions>
          </div>
        )}
      </Card>
    </div>
  );
}

/** One pull request a card delivers through: its number, its reviewers and checks, and where it stands, in its colour. */
function PrBox({ pr, onOpen }: { pr: WorkPr; onOpen: (target: AgendaTargetDto) => void }) {
  const status = workPrStatus(pr);
  const ci = pr.state === 'open' ? pr.row?.ciStatus ?? null : null;
  const Icon = pr.state === 'draft' ? GitPullRequestDraft : GitPullRequest;
  return (
    <button type="button" className="mw-pr" title={pr.title}
      onClick={event => { event.stopPropagation(); onOpen({ kind: 'PULL', repo: pr.repo, number: pr.number, workspaceId: null, id: null, connectionId: null, cloudId: null }); }}>
      <span className="mw-pr__line">
        <Icon size={13} className="mw-pr__icon" aria-hidden="true" />
        <span className="mw-pr__name">{pr.repo} #{pr.number}</span>
        {/* ponytail: the list payload names only reviewers still requested; per-reviewer verdicts need review states on the row. */}
        {pr.state === 'open' && (pr.row?.requestedReviewers ?? []).map(login => (
          <span key={login} className="mw-reviewer" title={`${login} · review pending`}><Avatar login={login} size={16} /></span>
        ))}
        {ci === 'PASSING' && <Check size={13} className="mw-ci is-passing" aria-label="Checks passing" />}
        {ci === 'FAILING' && <X size={13} className="mw-ci is-failing" aria-label="Checks failing" />}
      </span>
      <span className={`mw-pr__state is-${pr.state === 'draft' ? 'draft' : status.tone}`}>{status.text}</span>
    </button>
  );
}
