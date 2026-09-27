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

import React, { useEffect, useState } from 'react';
import { ChevronDown, Maximize2, Plus, Search, Trash2, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { SourceIcon } from '../home/AttentionCard';
import { relativeTime } from '../relativeTime';
import type { GoalDto, GoalItemDto, GoalItemState } from './contracts';
import {
  GOAL_SOURCES, STATE_GROUPS, addable, itemsIn, progressWidths, recentActivity, refKey, ringDash, summarize, type GoalTab,
} from './model';

/** The goal target, two rings, wherever a goal is named. */
export function GoalIcon({ size = 14 }: { size?: number }): React.JSX.Element {
  return (
    <svg className="goal-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="4.5" />
    </svg>
  );
}

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
  return `${goal.items.filter((item) => item.state === 'FINISHED').length}/${goal.items.length}`;
}

export type GoalPanelProps = {
  mode: 'dock' | 'drawer' | 'board';
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
  onRemove: (item: GoalItemDto) => void;
  onOpenItem: (item: GoalItemDto) => void;
  search: (query: string) => Promise<GoalItemDto[]>;
};

/**
 * A goal's progress and items. The dock and the drawer list items by state with recent activity;
 * the board lays them out as Idle, Working and Finished columns. Every change here is local.
 */
export function GoalPanel(props: GoalPanelProps): React.JSX.Element {
  const { mode, goal, onClose, onExpand } = props;
  return (
    <div className={`goal-panel is-${mode}`} role="dialog" aria-label={goal === null ? 'Goal' : `Goal: ${goal.title}`}>
      <div className="goal-panel__head">
        <div className="goal-panel__bar">
          <GoalSwitcher {...props} />
          {goal?.pinned && <span className="goal-pinned">Pinned</span>}
          <span className="goal-spacer" />
          {mode !== 'board' && goal !== null && onExpand !== undefined && (
            <button type="button" className="goal-headbtn" title="Open full view to manage" aria-label="Open the goal board" onClick={onExpand}>
              <Maximize2 size={15} strokeWidth={1.8} aria-hidden="true" />
            </button>
          )}
          <button type="button" className="goal-headbtn" title={mode === 'dock' ? 'Collapse to Goal pill' : mode === 'board' ? 'Close full view' : 'Close'}
            aria-label={mode === 'dock' ? 'Collapse the goal to its pill' : 'Close the goal'} onClick={onClose}>
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
      {goal === null ? <NoGoal onCreate={props.onCreate} /> : mode === 'board' ? <GoalBoard {...props} goal={goal} /> : <GoalList {...props} goal={goal} />}
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
              <Trash2 size={14} strokeWidth={1.8} aria-hidden="true" />Delete this goal
            </button>
          )}
        </div>
      )}
      {confirm && goal !== null && (
        <ConfirmDialog
          title="Delete this goal?"
          confirmLabel={busy ? 'Deleting…' : 'Delete goal'}
          busy={busy}
          destructive
          onConfirm={() => {
            setBusy(true);
            void onDelete(goal.id).finally(() => { setBusy(false); setConfirm(false); });
          }}
          onCancel={() => setConfirm(false)}
        >
          <p>“{goal.title}” and its list of items are removed from ByteQuay. The pull requests, issues, tickets and tasks stay as they are.</p>
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
      <div className="goal-empty__text">A goal groups pull requests, issues, Jira tickets and tasks toward one outcome. Marking an issue or ticket as a Goal also starts one.</div>
      <NameInput busy={busy} onCancel={() => {}} onSubmit={(title) => { setBusy(true); void onCreate(title).finally(() => setBusy(false)); }} />
    </div>
  );
}

/** Stored pull requests, issues, tickets and tasks to add, searched as the user types. */
function useCandidates(goal: GoalDto, search: GoalPanelProps['search'], open: boolean): { query: string; setQuery: (query: string) => void; results: GoalItemDto[] } {
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
      <span className={`goal-src is-${GOAL_SOURCES[item.kind]}`}><SourceIcon source={GOAL_SOURCES[item.kind]} size={14} /></span>
      <span className="goal-mono">{item.label}</span>
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
          <span className="goal-muted">of {summary.total} finished</span>
          <span className="goal-spacer" />
          <span className="goal-mono">{Math.round((summary.finished / Math.max(1, summary.total)) * 100)}%</span>
        </div>
        <div className="goal-progress"><span className="is-finished" style={{ width: widths.finished }} /><span className="is-working" style={{ width: widths.working }} /><span className="is-idle" /></div>
        <div className="goal-legend">
          <span><i className="is-finished" />Finished {summary.finished}</span>
          <span><i className="is-working" />Working {summary.working}</span>
          <span><i className="is-idle" />Idle {summary.idle}</span>
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

function ItemRow({ item, needsYou, onOpen, onRemove }: { item: GoalItemDto; needsYou: boolean; onOpen: () => void; onRemove: () => void }): React.JSX.Element {
  const source = GOAL_SOURCES[item.kind];
  return (
    <div className={`goal-row${item.state === 'FINISHED' ? ' is-finished' : ''}`} role="button" tabIndex={0} title={item.status}
      onClick={onOpen} onKeyDown={(event) => { if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onOpen(); } }}>
      <span className={`goal-src is-${source}`}><SourceIcon source={source} size={14} /></span>
      <span className="goal-mono">{item.label}</span>
      <span className="goal-row__title">{item.title}</span>
      {item.blocked && <span className="goal-blocked">Blocked</span>}
      {needsYou && <span className="goal-needs" title="Needs you" />}
      <button type="button" className="goal-remove" title="Remove from goal" aria-label={`Remove ${item.label} from the goal`}
        onClick={(event) => { event.stopPropagation(); onRemove(); }}>
        <X size={12} strokeWidth={2.2} aria-hidden="true" />
      </button>
    </div>
  );
}

function GoalList({ goal, needsYou, repository, now = Date.now(), search, onAdd, onRemove, onOpenItem }: GoalPanelProps & { goal: GoalDto }): React.JSX.Element {
  const [tab, setTab] = useState<GoalTab>('all');
  const [adding, setAdding] = useState(false);
  const [showFinished, setShowFinished] = useState(false);
  const candidates = useCandidates(goal, search, adding);
  const summary = summarize(goal, needsYou);
  const items = itemsIn(goal, tab, needsYou);
  const activity = recentActivity(goal);
  return (
    <div className="goal-panel__body">
      <Stats goal={goal} needsYou={needsYou} onTab={setTab} />
      <div>
        <div className="goal-tabsrow">
          <div className="goal-tabs" role="tablist" aria-label="Goal items">
            {([['all', 'All', summary.total], ['needs', 'Needs you', summary.needs], ['blocked', 'Blocked', summary.blocked]] as const).map(([id, label, count]) => (
              <button key={id} type="button" role="tab" aria-selected={tab === id} className={`goal-tab${tab === id ? ' is-active' : ''}`} onClick={() => setTab(id)}>
                {label} {count}
              </button>
            ))}
          </div>
          <span className="goal-spacer" />
          <button type="button" className="goal-add" aria-expanded={adding} onClick={() => { setAdding((value) => !value); candidates.setQuery(''); }}>
            <Plus size={12} strokeWidth={2.4} aria-hidden="true" />Add
          </button>
        </div>
        {adding && (
          <div className="goal-search">
            <div className="goal-search__field">
              <Search size={14} strokeWidth={2} aria-hidden="true" />
              <input autoFocus value={candidates.query} placeholder="Search PRs, issues, Jira tickets, tasks…" aria-label="Search items to add"
                onChange={(event) => candidates.setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') setAdding(false); }} />
            </div>
            <div className="goal-search__results">
              {candidates.results.map((item) => <Candidate key={refKey(item)} item={item} onAdd={onAdd} />)}
              {candidates.results.length === 0 && <div className="goal-search__none">No matching items in {repository}</div>}
            </div>
          </div>
        )}
      </div>
      {STATE_GROUPS.map(({ state, label }) => {
        const rows = items.filter((item) => item.state === state);
        if (rows.length === 0) return null;
        const folded = state === 'FINISHED' && tab === 'all';
        return (
          <div key={state}>
            <div className="goal-group">
              <span className="goal-group__label">{label.toUpperCase()}</span>
              <span className="goal-group__count">{rows.length}</span>
              <span className="goal-spacer" />
              {folded && <button type="button" className="goal-link" onClick={() => setShowFinished((value) => !value)}>{showFinished ? 'Hide' : `Show ${rows.length}`}</button>}
            </div>
            {(!folded || showFinished) && rows.map((item) => (
              <ItemRow key={refKey(item)} item={item} needsYou={needsYou.has(refKey(item))} onOpen={() => onOpenItem(item)} onRemove={() => onRemove(item)} />
            ))}
          </div>
        );
      })}
      {goal.items.length === 0 && <div className="goal-muted goal-none">Add pull requests, issues, Jira tickets or tasks to follow them here.</div>}
      {activity.length > 0 && (
        <div>
          <div className="goal-group"><span className="goal-group__label">RECENT ACTIVITY</span></div>
          <div className="goal-activity">
            {activity.map(({ item, text, at }) => (
              <div className="goal-activity__row" key={`${refKey(item)} ${at}`}>
                <span className={`goal-src is-${GOAL_SOURCES[item.kind]}`}><SourceIcon source={GOAL_SOURCES[item.kind]} size={14} /></span>
                <span className="goal-activity__text">{text}</span>
                <span className="goal-activity__age">{relativeTime(at, { suffix: false, now })}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

const COLUMNS: { state: GoalItemState; label: string }[] = [
  { state: 'IDLE', label: 'IDLE' },
  { state: 'WORKING', label: 'WORKING' },
  { state: 'FINISHED', label: 'FINISHED' },
];

function GoalBoard({ goal, needsYou, repository, search, onAdd, onRemove, onOpenItem }: GoalPanelProps & { goal: GoalDto }): React.JSX.Element {
  const [searching, setSearching] = useState(false);
  const candidates = useCandidates(goal, search, searching);
  const summary = summarize(goal, needsYou);
  const widths = progressWidths(summary);
  return (
    <div className="goal-panel__body is-board">
      <div className="goal-board__summary">
        <div className="goal-board__progress">
          <div className="goal-progress"><span className="is-finished" style={{ width: widths.finished }} /><span className="is-working" style={{ width: widths.working }} /><span className="is-idle" /></div>
          <span className="goal-muted"><b>{summary.finished}</b> of {summary.total} finished</span>
        </div>
        <span className="goal-muted">PRs <b>{summary.prsMerged}</b> merged · {summary.prsOpen} open</span>
        <span className="goal-board__needs"><b>{summary.needs}</b> need you</span>
        <span className="goal-board__blocked"><b>{summary.blocked}</b> blocked</span>
      </div>
      <div className="goal-board__search">
        <div className="goal-search__field is-board">
          <Search size={14} strokeWidth={2} aria-hidden="true" />
          <input value={candidates.query} placeholder="Add a PR, issue, Jira ticket or task to this goal…" aria-label="Search items to add"
            onFocus={() => setSearching(true)} onChange={(event) => { candidates.setQuery(event.target.value); setSearching(true); }}
            onKeyDown={(event) => { if (event.key === 'Escape') setSearching(false); }} />
        </div>
        {searching && (
          <div className="goal-board__results">
            {candidates.results.map((item) => <Candidate key={refKey(item)} item={item} onAdd={onAdd} board />)}
            {candidates.results.length === 0 && <div className="goal-search__none">No matching items in {repository}</div>}
            <div className="goal-board__done"><button type="button" className="goal-link is-muted" onClick={() => setSearching(false)}>Done</button></div>
          </div>
        )}
      </div>
      <div className="goal-columns">
        {COLUMNS.map(({ state, label }) => {
          const rows = goal.items.filter((item) => item.state === state);
          return (
            <div className="goal-column" key={state}>
              <div className="goal-column__head"><i className={`is-${state.toLowerCase()}`} />{label}<span className="goal-group__count">{rows.length}</span></div>
              {rows.map((item) => {
                const source = GOAL_SOURCES[item.kind];
                return (
                  <div className="goal-card" key={refKey(item)} role="button" tabIndex={0} onClick={() => onOpenItem(item)}
                    onKeyDown={(event) => { if (event.target === event.currentTarget && event.key === 'Enter') onOpenItem(item); }}>
                    <div className="goal-card__head">
                      <span className={`goal-src is-${source}`}><SourceIcon source={source} size={14} /></span>
                      <span className="goal-mono">{item.label}</span>
                      <span className="goal-spacer" />
                      {item.blocked && <span className="goal-blocked">Blocked</span>}
                      {needsYou.has(refKey(item)) && <span className="goal-needs" title="Needs you" />}
                      <button type="button" className="goal-remove" title="Remove from goal" aria-label={`Remove ${item.label} from the goal`}
                        onClick={(event) => { event.stopPropagation(); onRemove(item); }}>
                        <X size={11} strokeWidth={2.2} aria-hidden="true" />
                      </button>
                    </div>
                    <div className="goal-card__title">{item.title}</div>
                    <div className="goal-card__sub">{item.status}</div>
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
}
