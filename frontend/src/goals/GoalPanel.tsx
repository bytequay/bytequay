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

import React, { useEffect, useRef, useState } from 'react';
import { ChevronDown, Maximize2, Minimize2, Plus, Search, Trash2, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { GoalIcon, SourceIcon } from '../cards/Card';
import { relativeTime } from '../relativeTime';
import type { GoalDto, GoalItemDto, WorkItemDto, WorkItemDecision, CreateWorkItem, CompletionRule, WorkItemKind } from './contracts';
import {
  GOAL_SOURCES, STATE_GROUPS, addable, itemsIn, progressWidths, recentActivity, refKey, ringDash, summarize, itemNeedsYou, type GoalTab,
} from './model';

/** How much of a goal is finished, as a ring. */
export function GoalRing({ goal, size = 20 }: { goal: GoalDto | null; size?: number }): React.JSX.Element {
  const dash = goal === null ? '0 44' : ringDash(summarize(goal, new Set()));
  return (
    <svg className="goal-ring" width={size} height={size} viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="7" fill="none" stroke="#e3e3e6" strokeWidth="3" />
      <circle cx="10" cy="10" r="7" fill="none" stroke="#1a7f37" strokeWidth="3" strokeDasharray={dash} />
    </svg>
  );
}

function fraction(goal: GoalDto): string {
  const summary = summarize(goal, new Set());
  return `${summary.finished}/${summary.total}`;
}

export type GoalPanelProps = {
  mode: 'window' | 'board';
  /** "owner/repo", named where a search finds nothing. */
  repository: string;
  goals: GoalDto[];
  /** The goal shown: the pinned one, or null while the workspace has none. */
  goal: GoalDto | null;
  /** Items still showing in Needs attention, by kind and key. */
  needsYou: ReadonlySet<string>;
  now?: number;
  onClose: () => void;
  onExpand?: () => void;
  /** Shows another goal, which also makes it the one the pages follow. */
  onSwitch: (goalId: string) => void;
  onCreate: (title: string) => Promise<unknown>;
  onDelete: (goalId: string) => Promise<unknown>;
  onAdd: (item: GoalItemDto) => Promise<unknown>;
  workItems: WorkItemDto[];
  onCreateWork: (request: CreateWorkItem) => Promise<WorkItemDto>;
  onDecide: (item: WorkItemDto, command: WorkItemDecision) => Promise<WorkItemDto>;
  onLink: (item: WorkItemDto, reference: GoalItemDto) => Promise<WorkItemDto>;
  onOpenItem: (item: GoalItemDto) => void;
  search: (query: string) => Promise<GoalItemDto[]>;
};

/**
 * A goal's progress and items, listed by state or expanded into board columns. Every change here is local.
 */
export function GoalPanel(props: GoalPanelProps): React.JSX.Element {
  const { mode, goal, onClose, onExpand } = props;
  const [selected, setSelected] = useState<string | null>(null);
  const item = props.workItems.find((value) => value.id === selected) ?? goal?.items.find((value) => value.id === selected);
  return (
    <div className={`goal-panel is-${mode}`} role="dialog" aria-label={goal === null ? 'Goal' : `Goal: ${goal.title}`}>
      <div className="goal-panel__head">
        <div className="goal-panel__bar">
          <GoalSwitcher {...props} />
          {goal?.pinned && <span className="goal-pinned">Pinned</span>}
          <span className="goal-spacer" />
          {onExpand !== undefined && (
            <button type="button" className="goal-headbtn" title={mode === 'board' ? 'Restore window size' : 'Expand goal'} aria-label={mode === 'board' ? 'Restore goal window' : 'Expand goal'} onClick={onExpand}>
              {mode === 'board' ? <Minimize2 size={15} strokeWidth={1.8} aria-hidden="true" /> : <Maximize2 size={15} strokeWidth={1.8} aria-hidden="true" />}
            </button>
          )}
          <button type="button" className="goal-headbtn" title="Close (Esc)" aria-label="Close the goal" onClick={onClose}>
            <X size={15} strokeWidth={1.9} aria-hidden="true" />
          </button>
        </div>
        {goal !== null && (
          <>
            <div className="goal-panel__title">{goal.title}</div>
            {goal.description !== '' && <div className="goal-panel__desc">{goal.description}</div>}
          </>
        )}
      </div>
      {item !== undefined ? <WorkDetails key={item.id} {...props} item={item} onBack={() => setSelected(null)} />
        : goal === null ? <><NoGoal onCreate={props.onCreate} />{props.workItems.map((saved) => <button type="button" className="goal-work-card" key={saved.id} onClick={() => setSelected(saved.id)}>{saved.title} · {saved.state.toLowerCase()}</button>)}</> : <GoalWork {...props} goal={goal} onSelect={setSelected} />}
    </div>
  );
}

function GoalSwitcher({ goals, goal, repository, onSwitch, onCreate, onDelete }: GoalPanelProps): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [naming, setNaming] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const position = goal === null ? 0 : goals.findIndex((candidate) => candidate.id === goal.id) + 1;
  return (
    <>
      <button type="button" className="goal-switch" aria-expanded={open} onClick={() => { setOpen((value) => !value); setNaming(false); }}>
        <GoalIcon />
        <span className="goal-switch__label">GOAL</span>
        {goals.length > 0 && <span className="goal-switch__pos">{position} of {goals.length}</span>}
        <ChevronDown size={11} strokeWidth={2.4} aria-hidden="true" />
      </button>
      {open && (
        <div className="goal-menu" role="menu">
          <div className="goal-menu__label">GOALS IN {repository.toUpperCase()}</div>
          {goals.map((candidate) => (
            <button type="button" role="menuitem" key={candidate.id} className={`goal-menu__row${candidate.id === goal?.id ? ' is-current' : ''}`}
              onClick={() => { setOpen(false); onSwitch(candidate.id); }}>
              <GoalRing goal={candidate} size={18} />
              <span className="goal-menu__title">{candidate.title}</span>
              <span className="goal-mono">{fraction(candidate)}</span>
              {candidate.pinned && <span className="goal-menu__pinned">PINNED</span>}
            </button>
          ))}
          {goals.length > 0 && <div className="goal-menu__rule" />}
          {naming ? (
            <NameInput busy={busy} onCancel={() => setNaming(false)} onSubmit={(title) => {
              setBusy(true);
              void onCreate(title).then(() => { setNaming(false); setOpen(false); }, () => {}).finally(() => setBusy(false));
            }} />
          ) : (
            <button type="button" role="menuitem" className="goal-menu__action" onClick={() => setNaming(true)}>
              <Plus size={14} strokeWidth={2} aria-hidden="true" />New goal
            </button>
          )}
          {goal !== null && goal.source === null && (
            <button type="button" role="menuitem" className="goal-menu__action is-danger" onClick={() => { setOpen(false); setConfirm(true); }}>
              <Trash2 size={14} strokeWidth={1.8} aria-hidden="true" />Archive this goal
            </button>
          )}
        </div>
      )}
      {confirm && goal !== null && (
        <ConfirmDialog
          title="Archive this goal?"
          confirmLabel={busy ? 'Archiving…' : 'Archive goal'}
          busy={busy}
          destructive
          onConfirm={() => {
            setBusy(true);
            void onDelete(goal.id).finally(() => { setBusy(false); setConfirm(false); });
          }}
          onCancel={() => setConfirm(false)}
        >
          <p>“{goal.title}” leaves the active Goal list. Its work items, decisions and history remain saved.</p>
        </ConfirmDialog>
      )}
    </>
  );
}

function NameInput({ busy, onSubmit, onCancel }: { busy: boolean; onSubmit: (title: string) => void; onCancel: () => void }): React.JSX.Element {
  const [title, setTitle] = useState('');
  return (
    <form className="goal-name" onSubmit={(event) => { event.preventDefault(); if (title.trim() !== '') onSubmit(title.trim()); }}>
      <input autoFocus value={title} disabled={busy} placeholder="Goal name" aria-label="Goal name"
        onChange={(event) => setTitle(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') onCancel(); }} />
      <button type="submit" className="goal-add" disabled={busy || title.trim() === ''}>Create</button>
    </form>
  );
}

function NoGoal({ onCreate }: { onCreate: (title: string) => Promise<unknown> }): React.JSX.Element {
  const [busy, setBusy] = useState(false);
  return (
    <div className="goal-panel__body goal-empty">
      <div className="goal-empty__title">No goal yet</div>
      <div className="goal-empty__text">A goal groups accepted work toward an outcome. Each work item can link issues, tickets, tasks and pull requests. Marking an issue or ticket as a Goal also starts one.</div>
      <NameInput busy={busy} onCancel={() => {}} onSubmit={(title) => { setBusy(true); void onCreate(title).finally(() => setBusy(false)); }} />
    </div>
  );
}

/** Stored pull requests, issues, tickets and tasks to add, searched as the user types. */
function useCandidates(goal: Pick<GoalDto, 'items'>, search: GoalPanelProps['search'], open: boolean): { query: string; setQuery: (query: string) => void; results: GoalItemDto[] } {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<GoalItemDto[]>([]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    const timer = window.setTimeout(() => {
      void search(query).then((results) => { if (active) setFound(results); }, () => { if (active) setFound([]); });
    }, 150);
    return () => { active = false; window.clearTimeout(timer); };
  }, [open, query, search]);
  return { query, setQuery, results: addable(found, goal) };
}

function Candidate({ item, onAdd, board }: { item: GoalItemDto; onAdd: (item: GoalItemDto) => Promise<unknown>; board?: boolean }): React.JSX.Element {
  return (
    <div className="goal-result">
      <SourceIcon source={GOAL_SOURCES[item.kind]} tinted />
      <span className="card-key">{item.label}</span>
      <span className="goal-result__title">{item.title}</span>
      {board && <span className="goal-result__sub">{item.status}</span>}
      <button type="button" className="goal-link" onClick={() => { void onAdd(item).catch(() => {}); }}>Add</button>
    </div>
  );
}

function Stats({ goal, needsYou, onTab }: { goal: GoalDto; needsYou: ReadonlySet<string>; onTab: (tab: GoalTab) => void }): React.JSX.Element {
  const summary = summarize(goal, needsYou);
  const widths = progressWidths(summary);
  return (
    <>
      <div>
        <div className="goal-progress__head">
          <span className="goal-progress__count">{summary.finished}</span>
          <span className="goal-muted">of {summary.total} done</span>
          <span className="goal-spacer" />
          <span className="goal-mono">{summary.total === 0 ? '—' : `${Math.round((summary.finished / Math.max(1, summary.total)) * 100)}%`}</span>
        </div>
        <div className="goal-progress"><span className="is-finished" style={{ width: widths.finished }} /><span className="is-working" style={{ width: widths.working }} /><span className="is-idle" /></div>
        <div className="goal-legend">
          <span><i className="is-finished" />Done {summary.finished}</span>
          <span><i className="is-working" />Working {summary.working}</span>
          <span><i className="is-idle" />Planned {summary.idle}</span>
          {summary.dropped > 0 && <span>Dropped {summary.dropped}</span>}
        </div>
      </div>
      <div className="goal-tiles">
        <div className="goal-tile">
          <div className="goal-tile__label">Pull requests</div>
          <div className="goal-tile__value">{summary.prsMerged} <span>merged</span></div>
          <div className="goal-tile__sub">{summary.prsOpen} open</div>
        </div>
        <button type="button" className="goal-tile" onClick={() => onTab('needs')}>
          <div className="goal-tile__label">Needs you</div>
          <div className="goal-tile__value is-needs">{summary.needs}</div>
          <div className="goal-tile__sub">in attention</div>
        </button>
        <button type="button" className="goal-tile" onClick={() => onTab('blocked')}>
          <div className="goal-tile__label">Blocked</div>
          <div className="goal-tile__value is-blocked">{summary.blocked}</div>
          <div className="goal-tile__sub">on others</div>
        </button>
      </div>
    </>
  );
}

function GoalWork(props: GoalPanelProps & { goal: GoalDto; onSelect: (id: string) => void }): React.JSX.Element {
  const { goal, needsYou, mode, onSelect } = props;
  const [tab, setTab] = useState<GoalTab>('all');
  const [adding, setAdding] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const candidates = useCandidates(goal, props.search, adding);
  const items = itemsIn(goal, tab, needsYou);
  const activity = recentActivity(goal);
  return <div className={`goal-panel__body${mode === 'board' ? ' is-board' : ''}`}>
    <Stats goal={goal} needsYou={needsYou} onTab={setTab} />
    <div className="goal-tabsrow">
      <div className="goal-tabs" role="tablist" aria-label="Goal work">
        {(['all', 'needs', 'blocked'] as const).map((value) => <button type="button" role="tab" aria-selected={tab === value} key={value}
          className={`goal-tab${tab === value ? ' is-active' : ''}`} onClick={() => setTab(value)}>{value === 'all' ? 'All work' : value === 'needs' ? 'Needs you' : 'Blocked'}</button>)}
      </div>
      <button type="button" className="goal-add" onClick={() => setCreating((value) => !value)}>New work item</button>
      <button type="button" className="goal-add" onClick={() => setAdding((value) => !value)}>Add existing</button>
    </div>
    {error !== null && <p role="alert">{error}</p>}
    {creating && <NewWork goal={goal} onCreate={props.onCreateWork} onDone={(item) => { setCreating(false); onSelect(item.id); }} />}
    {adding && <div className="goal-search">
      <label className="goal-search__field"><Search size={14} aria-hidden="true" /><input aria-label="Search items to add" value={candidates.query}
        placeholder="Search issues, tickets, tasks and PRs" onChange={(event) => candidates.setQuery(event.target.value)} /></label>
      {candidates.results.map((item) => <Candidate key={refKey(item)} item={item} onAdd={(reference) => props.onAdd(reference).catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : 'Could not add work');
      })} />)}
      {props.workItems.filter((item) => item.goalId !== goal.id).map((item) => <div className="goal-result" key={item.id}>
        <button type="button" className="goal-link goal-result__title" onClick={() => onSelect(item.id)}>{item.title}</button><button type="button" className="goal-link" onClick={() => {
          void props.onDecide(item, { operationId: crypto.randomUUID(), expectedRevision: item.revision, action: 'MOVE', goalId: goal.id, detail: '' })
            .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not move work'));
        }}>{item.goalId === null ? 'Add work item' : 'Move here'}</button>
      </div>)}
    </div>}
    <div className={mode === 'board' ? 'goal-columns' : 'goal-work-list'}>
      {STATE_GROUPS.map(({ state, label }) => {
        const rows = items.filter((item) => item.state === state);
        return <div className={mode === 'board' ? 'goal-column' : 'goal-work-group'} key={state}>
          <div className="goal-group"><span className="goal-group__label">{label}</span><span className="goal-group__count">{rows.length}</span></div>
          {rows.map((item) => <button type="button" className="goal-work-card" key={item.id} onClick={() => onSelect(item.id)}>
            <span className="goal-work-card__title">{item.title}</span>
            <span className="goal-muted">{item.kind.toLowerCase()} · {item.links.length} linked references</span>
            {item.state !== 'DONE' && item.state !== 'DROPPED' && item.links.some((link) => link.status === 'Failed') && <span>Attempt failed — outcome still open</span>}
            {item.links.some((link) => link.state === 'UNKNOWN') && <span>Some evidence is unavailable</span>}
            {itemNeedsYou(item, needsYou) && <span>Needs you</span>}
          </button>)}
        </div>;
      })}
    </div>
    {goal.items.length === 0 && <p className="goal-muted">Create a piece of work or add an existing source. Each outcome counts once.</p>}
    {activity.length > 0 && <div><div className="goal-group">Recent decisions</div>{activity.map(({ item, text, at }, index) =>
      <div className="goal-activity__row" key={`${item.id}-${index}`}><span className="goal-activity__text">{text}</span><span>{relativeTime(at, { now: props.now })}</span></div>)}</div>}
  </div>;
}

function NewWork({ goal, onCreate, onDone }: { goal: GoalDto; onCreate: GoalPanelProps['onCreateWork']; onDone: (item: WorkItemDto) => void }): React.JSX.Element {
  const [id] = useState(() => crypto.randomUUID());
  const [title, setTitle] = useState('');
  const [criteria, setCriteria] = useState('');
  const [kind, setKind] = useState<WorkItemKind>('DEVELOP');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return <form className="goal-work-form" onSubmit={(event) => {
    event.preventDefault(); setBusy(true); setError(null);
    void onCreate({ id, goalId: goal.id, title: title.trim(), kind, acceptanceCriteria: criteria.trim(),
      completionRule: kind === 'DEVELOP' ? 'MERGED_DELIVERY' : 'ACCEPTED_RESULT' }).then(onDone)
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Could not save work')).finally(() => setBusy(false));
  }}>
    <label>Work item<input required maxLength={500} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
    <label>Kind<select value={kind} onChange={(event) => setKind(event.target.value as WorkItemKind)}>
      <option value="DEVELOP">Develop</option><option value="LEARN">Learn</option><option value="INVESTIGATE">Investigate</option>
    </select></label>
    <label>What will count as success?<textarea required maxLength={10000} value={criteria} onChange={(event) => setCriteria(event.target.value)} /></label>
    <p className="goal-muted">{kind === 'DEVELOP' ? 'Completion requires merged PR evidence and your acceptance of the covered scope.' : 'Completion requires your acceptance of a recorded result.'}</p>
    {error !== null && <p role="alert">{error}</p>}
    <button type="submit" className="goal-add" disabled={busy || title.trim() === '' || criteria.trim() === ''}>{busy ? 'Saving…' : 'Save work item'}</button>
  </form>;
}

function WorkDetails(props: GoalPanelProps & { item: WorkItemDto; onBack: () => void }): React.JSX.Element {
  const { item } = props;
  const [title, setTitle] = useState(item.title);
  const [criteria, setCriteria] = useState(item.acceptanceCriteria);
  const [rule, setRule] = useState<CompletionRule>(item.completionRule);
  const [draftRevision, setDraftRevision] = useState(item.revision);
  const [detail, setDetail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [targetGoal, setTargetGoal] = useState(item.goalId ?? '');
  const requests = useRef(new Map<string, string>());
  const candidates = useCandidates({ items: [item] }, props.search, adding);
  const concluded = item.state === 'DONE' || item.state === 'DROPPED';
  const change = (action: WorkItemDecision['action']): void => {
    const payload = { expectedRevision: action === 'EDIT' ? draftRevision : item.revision, action, detail,
      ...(action === 'EDIT' ? { title, acceptanceCriteria: criteria, completionRule: rule } : {}),
      ...(action === 'MOVE' && targetGoal !== '' ? { goalId: targetGoal } : {}) };
    const key = JSON.stringify(payload);
    const operationId = requests.current.get(key) ?? crypto.randomUUID();
    requests.current.set(key, operationId);
    setBusy(true); setError(null);
    void props.onDecide(item, { ...payload, operationId }).then((updated) => {
      if (action === 'EDIT') setDraftRevision(updated.revision);
      setDetail('');
      if (action === 'MOVE') props.onBack();
    }).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Decision was not saved')).finally(() => setBusy(false));
  };
  return <div className="goal-panel__body goal-work-form">
    <button type="button" className="goal-link" onClick={props.onBack}>Back to work</button>
    <strong>{item.title}</strong><span>{item.state.toLowerCase()} · {item.kind.toLowerCase()}</span>
    {error !== null && <p role="alert">{error}</p>}
    <label>Title<input maxLength={500} disabled={concluded || busy} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
    <label>Acceptance criteria<textarea maxLength={10000} disabled={concluded || busy} value={criteria} onChange={(event) => setCriteria(event.target.value)} /></label>
    <label>Completion rule<select disabled={concluded || busy} value={rule} onChange={(event) => setRule(event.target.value as CompletionRule)}>
      <option value="MERGED_DELIVERY">Merged delivery</option><option value="ACCEPTED_RESULT">User accepted result</option>
    </select></label>
    {!concluded && <div className="goal-work-actions"><button type="button" className="goal-add" disabled={busy} onClick={() => change('EDIT')}>Save scope</button>
      {draftRevision !== item.revision && <button type="button" className="goal-link" disabled={busy} onClick={() => {
        setTitle(item.title); setCriteria(item.acceptanceCriteria); setRule(item.completionRule); setDraftRevision(item.revision);
      }}>Reload saved scope</button>}</div>}
    <div className="goal-group">Sources, attempts and delivery</div>
    {item.links.map((link) => <button type="button" className="goal-result goal-work-link" key={refKey(link)} onClick={() => props.onOpenItem(link)}>
      <SourceIcon source={GOAL_SOURCES[link.kind]} /><span>{link.label}</span><span className="goal-result__title">{link.title}</span><span>{link.status}</span>
    </button>)}
    {!concluded && <button type="button" className="goal-link" onClick={() => setAdding((value) => !value)}>Link source, task or PR</button>}
    {adding && !concluded && <div><input aria-label="Search supporting work" value={candidates.query} onChange={(event) => candidates.setQuery(event.target.value)} />
      {candidates.results.map((reference) => <Candidate key={refKey(reference)} item={reference} onAdd={(link) => props.onLink(item, link).catch((failure: unknown) => {
        setError(failure instanceof Error ? failure.message : 'Could not link work');
      })} />)}</div>}
    <label>{concluded ? 'Reason to reopen' : 'Result, evidence and scope covered, or reason to drop'}
      <textarea maxLength={10000} value={detail} onChange={(event) => setDetail(event.target.value)} /></label>
    <div className="goal-work-actions">
      {item.state === 'PLANNED' && <button type="button" className="goal-add" disabled={busy} onClick={() => change('BEGIN')}>Mark working</button>}
      {!concluded && <button type="button" className="goal-add" disabled={busy || detail.trim() === '' || item.acceptanceCriteria.trim() === ''}
        onClick={() => change('ACCEPT')}>Accept result</button>}
      {!concluded && <button type="button" className="goal-link" disabled={busy || detail.trim() === ''} onClick={() => change('DROP')}>Drop from scope</button>}
      {concluded && <button type="button" className="goal-add" disabled={busy || detail.trim() === ''} onClick={() => change('REOPEN')}>Reopen</button>}
    </div>
    <p className="goal-muted">Accept result confirms all saved criteria are covered. Task completion and ticket closure alone do not complete this item.</p>
    <label>Goal<select value={targetGoal} onChange={(event) => setTargetGoal(event.target.value)}>
      <option value="">No Goal</option>
      {item.goalId !== null && !props.goals.some((goal) => goal.id === item.goalId) && <option value={item.goalId}>Archived Goal</option>}
      {props.goals.map((goal) => <option key={goal.id} value={goal.id}>{goal.title}</option>)}
    </select></label>
    <button type="button" className="goal-link" disabled={busy || targetGoal === (item.goalId ?? '')} onClick={() => change('MOVE')}>Move work item</button>
    <div className="goal-group">History</div>
    {[...item.history].reverse().map((event) => <div className="goal-work-history" key={event.operationId}>
      <span className="goal-muted">{relativeTime(event.at, { now: props.now })}</span><p>{event.detail}</p>
    </div>)}
  </div>;
}
