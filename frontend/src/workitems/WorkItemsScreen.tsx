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
import { useAgentAvailability } from '../agents/availability';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ChevronRight, CircleCheck, CircleDot, CircleDotDashed, CircleX, ExternalLink, Flag, GitPullRequest, History, Link2, ListTodo, LockKeyhole, Pencil, Play, Plus,
  RotateCcw, Upload, X } from 'lucide-react';
import { LinkKey, LocalBadge, Pill, SourceIcon } from '../cards/Card';
import type { WorkspaceDto } from '../types';
import { workItemRoute, type CompletionRule, type WorkItemDto, type WorkItemKind, type WorkItemPriority, type GoalItemDto, type Publication, type UnifiedWorkItem } from './contracts';
import { GOAL_SOURCES, isOpen, progressWidths, summarize, waitingOn } from './model';
import { GOALS_CHANGED } from './changes';
import { zoomReference } from '../githubReference';
import type { AttentionTone } from '../home/attention';
import JiraScreen from '../jira/JiraScreen';
import type { JiraConnection } from '../jira/contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import { PaneIcon, RefreshIcon } from '../pulls/PullsScreen';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { useResizablePane } from '../useResizablePane';
import { epicWork, localEpicId, startEpicWork, useEpicGoals, useEpicLend } from '../workspaces/epics';
import { EpicTabs, workProgress } from '../workspaces/EpicTabs';
import { EpicWork } from '../workspaces/EpicView';
import { useUnmarkEpic } from '../workspaces/useUnmarkEpic';
import { workspaceIssueTaskRoute, workspaceRoute } from '../workspaces/model';
import WorkItemContextLinks from './WorkItemContextLinks';
import { openGoalItem } from './open';
import '../css/issues.css';
import '../css/epic-view.css';
import './workitems.css';

/** A work item kept on this Mac; one pushed to GitHub or Jira stays here, linked to its remote copy. */
type LocalItem = UnifiedWorkItem & { work: WorkItemDto };
type Group = 'open' | 'done' | 'closed';
/** An Epic as a work item names it: its key, and its key and title together. */
type EpicName = { id: string; label: string; title: string };

const REMOTE = { GITHUB: 'GitHub', JIRA: 'Jira' } as const;
const KIND: Record<WorkItemKind, string> = { DEVELOP: 'Develop', INVESTIGATE: 'Investigate', LEARN: 'Learn' };
const RULE: Record<CompletionRule, string> = { MERGED_DELIVERY: 'Merged delivery', ACCEPTED_RESULT: 'Accepted result' };
const PRIORITY: Record<WorkItemPriority, string> = { HIGH: 'High', NORMAL: 'Normal', LOW: 'Low' };
const GROUPS: [Group, string][] = [['open', 'Open'], ['done', 'Done'], ['closed', 'Closed']];
/** How each state reads here, the tab it lists under, and the state pill it borrows from pull requests. */
const STATE: Record<WorkItemDto['state'], { label: string; group: Group; pill: string; order: number }> = {
  WORKING: { label: 'Working', group: 'open', pill: ' is-queued', order: 0 },
  PLANNED: { label: 'Planned', group: 'open', pill: '', order: 1 },
  DONE: { label: 'Finished', group: 'done', pill: ' is-merged', order: 2 },
  DROPPED: { label: 'Closed', group: 'closed', pill: ' is-draft', order: 3 },
};
const LINK_KIND: Record<GoalItemDto['kind'], string> = { ISSUE: 'GitHub issue', JIRA: 'Jira ticket', TASK: 'Agent task', PULL_REQUEST: 'Pull request' };
const LINK_TONE: Record<GoalItemDto['state'], AttentionTone> = { WORKING: 'blue', MERGED: 'purple', IDLE: 'neutral', ENDED: 'neutral', UNKNOWN: 'neutral' };
const CLOSED_BY_USER = 'Closed by the user';
const FINISHED_BY_USER = 'Marked finished by user';
const REOPENED_BY_USER = 'Reopened by the user';
/** What each history action says and the icon it shows; any other action is named as recorded. */
const EVENTS: Record<string, [string, typeof Plus, string]> = {
  CREATED: ['Created this work item', Plus, ''],
  EDIT: ['Edited the local context', Pencil, ''],
  DROP: ['Closed this locally', CircleX, ' is-danger'],
  REOPEN: ['Reopened this', RotateCcw, ' is-success'],
  BEGIN: ['Started work', Play, ' is-warning'],
  ACCEPT: ['Marked this done', CircleCheck, ' is-purple'],
  FINISH: ['Marked this finished', CircleCheck, ' is-purple'],
  DELIVERED: ['Done on merge', CircleCheck, ' is-purple'],
  PUBLISHED: ['Pushed this to remote', Upload, ' is-link'],
  LINKED: ['Linked', Link2, ''],
  CONTEXT_UPDATED: ['Updated context links', Link2, ''],
};
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
const selectedId = () => typeof window === 'undefined' ? null : new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('work');
const remoteOf = (work: WorkItemDto) => work.links.find(link => link.kind === 'ISSUE' || link.kind === 'JIRA');
const pullOf = (work: WorkItemDto) => work.links.filter(link => link.kind === 'PULL_REQUEST').at(-1);
/** The attempt under way; another starts only once it ends. */
const taskOf = (work: WorkItemDto) => work.links.filter(link => link.kind === 'TASK' && link.state !== 'ENDED').at(-1);
const settledWord = (work: WorkItemDto) => `${work.state === 'DONE' ? 'finished' : work.state === 'DROPPED' ? 'closed' : 'updated'} ${relativeTime(work.updatedAt)}`;

function StateIcon({ state, size = 16 }: { state: WorkItemDto['state']; size?: number }) {
  const Icon = state === 'DONE' ? CircleCheck : state === 'DROPPED' ? CircleX : state === 'WORKING' ? CircleDotDashed : CircleDot;
  return <Icon size={size} strokeWidth={1.6} aria-hidden="true" />;
}

const TABS = [['local', 'Local'], ['github', 'GitHub issues'], ['jira', 'Jira']] as const;

export default function WorkItemsScreen({ workspace, tab, github }: { workspace: WorkspaceDto; tab: 'issues' | 'jira'; github: ReactNode }) {
  const [hash, setHash] = useState(() => typeof window === 'undefined' ? '' : window.location.hash);
  useEffect(() => {
    const changed = () => setHash(window.location.hash);
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, []);
  const [path, search] = hash.split('?');
  const query = new URLSearchParams(search);
  const active = hash ? path.endsWith('/jira') ? 'jira' : query.get('source') === 'github' || /\/issues\/\d+$/.test(path) ? 'github' : 'local'
    : tab === 'jira' ? 'jira' : 'local';
  const select = (source: typeof TABS[number][0]) => {
    window.location.hash = source === 'local' ? workItemRoute(workspace.id)
      : source === 'jira' ? workspaceRoute(workspace.id, 'jira') : `${workspaceRoute(workspace.id, 'issues')}?source=github`;
  };
  return <div className="work-sources">
    <div className="work-sources__tabs" role="tablist" aria-label="Work item sources">
      {TABS.map(([source, label], index) => <button key={source} id={`work-source-${source}`} type="button" role="tab"
        aria-selected={active === source} aria-controls={`work-panel-${source}`} tabIndex={active === source ? 0 : -1}
        onClick={() => select(source)} onKeyDown={event => {
          const next = event.key === 'ArrowRight' ? (index + 1) % TABS.length : event.key === 'ArrowLeft' ? (index + TABS.length - 1) % TABS.length
            : event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1 : null;
          if (next === null) return;
          event.preventDefault(); select(TABS[next][0]);
          event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
        }}>{source === 'github' && workspace.hostingServer?.provider === 'GITLAB' ? 'GitLab issues' : label}</button>)}
    </div>
    <div className="work-sources__panel" id={`work-panel-${active}`} role="tabpanel" aria-labelledby={`work-source-${active}`}>
      {active === 'github' ? github : active === 'jira' ? <JiraScreen workspace={workspace} /> : <LocalWorkItems key={workspace.id} workspace={workspace} />}
    </div>
  </div>;
}

export function LocalWorkItems({ workspace, zoomedItemId, headerAction }: { workspace: WorkspaceDto; zoomedItemId?: string; headerAction?: ReactNode }) {
  const [items, setItems] = useState<UnifiedWorkItem[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [selected, setSelected] = useState<string | null>(() => zoomedItemId ?? selectedId());
  const [query, setQuery] = useState('');
  const [group, setGroup] = useState<Group>('open');
  const [epic, setEpic] = useState('');
  const [kind, setKind] = useState('');
  const [where, setWhere] = useState('');
  const [paneOpen, setPaneOpen] = useState(true);
  const detailPane = useResizablePane(0, 360, 1600, 'start');
  const [publishing, setPublishing] = useState(false);
  const { busy, error, setError, run } = useAsyncAction(errorText);
  const lend = useEpicLend(workspace.id);
  const [issueTitles, setIssueTitles] = useState<ReadonlyMap<string, string>>(new Map());
  const reload = useCallback(async () => setItems(await window.bridge.listUnifiedWorkItems(workspace.id, true)), [workspace.id]);
  useEffect(() => {
    let active = true;
    const read = (): void => { void window.bridge.listUnifiedWorkItems(workspace.id, true).then(value => { if (active) { setItems(value); setLoaded(true); } }).catch(cause => { if (active) setError(errorText(cause)); }); };
    // An Epic's Work tab can change this work from a layer over the page.
    const changed = (event: Event) => { if ((event as CustomEvent<string>).detail === workspace.id) read(); };
    read();
    window.addEventListener(GOALS_CHANGED, changed);
    return () => { active = false; window.removeEventListener(GOALS_CHANGED, changed); };
  }, [workspace.id, setError]);
  useEffect(() => {
    if (zoomedItemId !== undefined) return;
    const changed = () => setSelected(selectedId());
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, [zoomedItemId]);
  const select = (id: string | null, publish = false) => {
    setSelected(id); setPublishing(publish); setPaneOpen(true);
    if (zoomedItemId === undefined) window.location.hash = workItemRoute(workspace.id, id ?? undefined);
  };
  const refresh = () => run(reload);
  const local = items.filter((value): value is LocalItem => value.work !== null);
  const all = local.map(value => value.work);
  const epicName = (id: string): EpicName => {
    const source = items.find(value => value.id === (id.startsWith('LOCAL:') ? id.slice(6) : id));
    const label = source?.reference?.label;
    const title = source?.title || lend.epicTitles.get(id) || issueTitles.get(id);
    return { id, label: label ? `Epic ${label}` : 'Epic', title: [label, title].filter(Boolean).join(' ') || 'Epic' };
  };
  const epics = [...new Set([...lend.epicTitles.keys(), ...all.filter(work => work.isEpic).map(work => localEpicId(work.id)),
    ...all.flatMap(work => work.epicId ? [work.epicId] : [])])].map(epicName);
  const text = query.trim().toLowerCase();
  const filtering = text !== '' || epic !== '' || kind !== '' || where !== '';
  const matching = local.filter(value => `${value.title} ${value.description} ${value.work.acceptanceCriteria} ${value.reference?.label ?? ''} ${value.work.epicId ? epicName(value.work.epicId).title : ''}`
    .toLowerCase().includes(text)
    && (epic === '' || (epic === 'none' ? !value.work.epicId : value.work.epicId === epic))
    && (kind === '' || value.work.kind === kind)
    && (where === '' || value.source === where));
  const count = (value: Group) => matching.filter(candidate => STATE[candidate.work.state].group === value).length;
  const visible = matching.filter(value => STATE[value.work.state].group === group).sort((left, right) => STATE[left.work.state].order - STATE[right.work.state].order);
  const item = local.find(value => value.id === selected);
  // The issue cache may not hold an Epic's issue, which the catalog then names by number only; GitHub names it for the open work item.
  const epicId = item?.work.epicId ?? null;
  const unnamedEpic = items.find(value => value.id === epicId && value.title === '' && value.reference?.kind === 'ISSUE')?.reference?.key ?? null;
  const epicNamed = epicId !== null && (lend.epicTitles.has(epicId) || issueTitles.has(epicId));
  useEffect(() => {
    if (epicId === null || unnamedEpic === null || epicNamed) return;
    let active = true;
    const [repository, number] = unnamedEpic.split('#');
    const [owner, repo] = repository.split('/');
    window.bridge.fetchGitHubIssuePreview(owner, repo, Number(number))
      .then(issue => { if (active) setIssueTitles(current => new Map(current).set(epicId, issue.title)); }, (): void => undefined);
    return () => { active = false; };
  }, [epicId, unnamedEpic, epicNamed]);
  const creating = selected === 'new';
  const begin = (value: LocalItem) => {
    if (value.work.isEpic) return;
    if (value.source !== 'LOCAL') openReference(workspace, value.reference, true);
    else startEpicWork({ workspaceId: workspace.id, itemId: value.work.id, send: true,
      draft: `Develop this work item: ${value.work.title}\n\nCall read_issue_context before starting to read this item's full context, linked work items, and its parent Epic, if any. Deliver only this work item.` });
  };
  const viewTask = (value: LocalItem) => { const task = taskOf(value.work); if (task) zoomReference({ kind: 'task', workspaceId: workspace.id, taskId: task.key }); };
  return <div className="pulls-page issues-page work-items">
    {zoomedItemId === undefined && <section className="pulls-list issues-list" aria-label="Local work items">
      <header className="pulls-list__toolbar">
        <span className="pulls-scope work-items__scope" title="Local work items are private until you push them"><LockKeyhole size={11} aria-hidden="true" />Local</span>
        <nav className="pulls-tabs" aria-label="Work item states">
          {GROUPS.map(([value, label]) => <button key={value} type="button" className={group === value ? 'is-active' : ''} aria-pressed={group === value}
            onClick={() => setGroup(value)}>{label} <span className="issues-list__count">{count(value)}</span></button>)}
        </nav>
        <button className="pulls-toolbar-button work-items__new" type="button" title="New local work item" aria-label="New local work item" onClick={() => select('new')}>
          <Plus size={15} aria-hidden="true" />New
        </button>
        <button className="pulls-toolbar-button" type="button" title="Refresh work items" aria-label="Refresh work items" disabled={busy} onClick={() => void refresh()}><RefreshIcon /></button>
        <button className={`pulls-toolbar-button${paneOpen ? ' is-active' : ''}`} type="button" title="Toggle work item details" aria-label="Toggle work item details"
          aria-pressed={paneOpen} onClick={() => setPaneOpen(value => !value)}><PaneIcon /></button>
      </header>
      <div className="pulls-search issues-search">
        <input type="search" aria-label="Search work items by title, context, or Epic" placeholder="⌕  Search title, context, or epic" value={query} onChange={event => setQuery(event.target.value)} />
        <select aria-label="Filter work items by Epic" value={epic} onChange={event => setEpic(event.target.value)}>
          <option value="">All epics</option>
          {epics.map(value => <option key={value.id} value={value.id}>{value.title}</option>)}
          <option value="none">No epic</option>
        </select>
        <select aria-label="Filter work items by kind" value={kind} onChange={event => setKind(event.target.value)}>
          <option value="">All kinds</option>
          {Object.entries(KIND).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <select aria-label="Filter work items by where they are" value={where} onChange={event => setWhere(event.target.value)}>
          <option value="">Local + remote</option>
          <option value="LOCAL">Local only</option>
          <option value="GITHUB">On GitHub</option>
          <option value="JIRA">On Jira</option>
        </select>
      </div>
      <div className="issues-freshness" role="status">
        {group === 'open' ? `${count('open')} open · ${matching.filter(value => value.work.state === 'WORKING').length} in development` : `${count(group)} ${group}`} · saved locally
      </div>
      {error && <div className="issues-error" role="alert">{error}</div>}
      <div className="pulls-list__rows">
        {visible.map(value => <WorkRow key={value.id} item={value} all={all} epic={value.work.epicId ? epicName(value.work.epicId) : null} selected={selected === value.id}
          onPick={() => select(value.id)} onStart={() => begin(value)} onViewTask={() => viewTask(value)} onPublish={() => select(value.id, true)} />)}
        {visible.length === 0 && <div className="work-items__empty">
          {filtering ? 'No local work items match your filters.' : group === 'open' ? 'No open local work. Create one with New.' : `No ${group} local work yet.`}
        </div>}
      </div>
    </section>}
    {paneOpen && <section className="pull-detail issues-detail" aria-label="Work item details" style={{ width: zoomedItemId === undefined ? detailPane.width || '58%' : '100%' }}>
      {zoomedItemId === undefined && <div className="pull-detail__resize" role="separator" aria-orientation="vertical" aria-label="Resize work item panes" title="Drag to resize"
        onMouseDown={event => detailPane.startResize(event, event.currentTarget.parentElement?.getBoundingClientRect().width)} />}
      {creating ? <WorkItemForm workspaceId={workspace.id} epics={epics} onClose={() => select(null)} onSaved={async value => { await reload(); select(value.id); }} />
        : item ? <WorkItemDetail key={item.id} workspace={workspace} item={item} all={all} catalog={items} epic={item.work.epicId ? epicName(item.work.epicId) : null}
          publishing={publishing} onPublishing={setPublishing} onStart={() => begin(item)} onViewTask={() => viewTask(item)}
          onOpenEpic={() => {
            const id = item.work.epicId;
            if (id?.startsWith('LOCAL:')) zoomReference({ kind: 'work', workspaceId: workspace.id, itemId: id.slice(6) });
            else openReference(workspace, items.find(value => value.id === id)?.reference ?? null);
          }} onChanged={reload} headerAction={headerAction} />
          : <>
            <DetailHeader label="Local work">{headerAction}</DetailHeader>
            <div className="pull-detail__body"><div className="work-items__empty">{zoomedItemId === undefined ? 'Select a work item to read its context or start development.' : error ?? (loaded ? 'Work item not found.' : 'Loading work item…')}</div></div>
          </>}
    </section>}
  </div>;
}

/** Opens the issue or ticket where it lives; `start` opens it ready to start development. */
function openReference(workspace: WorkspaceDto, reference: UnifiedWorkItem['reference'], start = false) {
  if (reference?.kind === 'JIRA') {
    const [connectionId, cloudId, issueId] = reference.key.split(':');
    zoomReference({ kind: 'jira', connectionId, cloudId, issueId, ...(start ? { start: true } : {}) });
  }
  else if (reference?.kind === 'ISSUE') {
    const number = Number(reference.key.slice(reference.key.lastIndexOf('#') + 1));
    if (start) window.location.hash = workspaceIssueTaskRoute(workspace.id, number);
    else zoomReference({ kind: 'issue', repo: `${workspace.monitoredOwner}/${workspace.monitoredRepo}`, number, workspaceId: workspace.id });
  }
}

function WorkRow({ item, all, epic, selected, onPick, onStart, onViewTask, onPublish }: {
  item: LocalItem; all: WorkItemDto[]; epic: EpicName | null; selected: boolean; onPick: () => void; onStart: () => void; onViewTask: () => void; onPublish: () => void;
}) {
  const availability = useAgentAvailability(item.work.workspaceId);
  const work = item.work;
  const remote = remoteOf(work);
  const task = taskOf(work);
  const pull = pullOf(work);
  const waits = waitingOn(work, all).length;
  return <div className={`pull-row issues-row${selected ? ' is-selected' : ''}`}>
    <button type="button" className="issues-row__select" aria-pressed={selected} onClick={onPick}>
      <span className="pull-row__title">
        <span className={`pull-row__state work-items__state is-${work.state.toLowerCase()}`} title={STATE[work.state].label}><StateIcon state={work.state} /></span>
        <span>{item.title}</span>
      </span>
    </button>
    <span className="pull-row__labels">
      {work.isEpic ? <span className="issues-epic-badge"><Flag size={11} aria-hidden="true" />Epic</span> : <span>{KIND[work.kind]}</span>}
      {work.priority === 'HIGH' && <span className="work-items__high">High priority</span>}
      {epic && <span className="issues-epic-badge" title={epic.title}><Flag size={11} aria-hidden="true" />{epic.label}</span>}
      {waits > 0 && <span>Waiting on {waits}</span>}
    </span>
    {(task || pull) && <span className="pull-row__stats">
      {task && <span className="pull-row__ci is-pending"><ListTodo size={13} aria-hidden="true" />Task · {task.status.toLowerCase()}</span>}
      {pull && <span className={`pull-row__ci${pull.state === 'MERGED' ? ' pull-row__review' : pull.state === 'WORKING' ? ' is-passing' : ''}`}>
        <GitPullRequest size={13} aria-hidden="true" />{pull.label} {pull.status.toLowerCase()}
      </span>}
    </span>}
    <div className="issues-row__footer">
      <button type="button" className="issues-row__select" aria-label={`Open ${item.title}`} onClick={onPick}>
        <span className="pull-row__meta">
          <LocalBadge />
          {remote && <LinkKey source={GOAL_SOURCES[remote.kind]} name={remote.label} size={12} title={`Pushed to ${remote.kind === 'JIRA' ? 'Jira as' : 'GitHub as issue'} ${remote.label}`} />}
          <span>·</span>
          <span className="pull-row__meta-time">{settledWord(work)}</span>
          <span>·</span>
          <span className="pull-row__comments" title="History events"><History size={13} aria-hidden="true" />{work.history.length}</span>
        </span>
      </button>
      <span className="issues-development-actions">
        {availability.any && isOpen(work) && !work.isEpic && !task && <button className="pulls-toolbar-button" type="button" title="Use agent to develop" aria-label={`Start development of ${item.title}`} onClick={onStart}>
          <Play size={16} aria-hidden="true" /></button>}
        {task && <button className="pulls-toolbar-button" type="button" title="View task" aria-label={`View the task for ${item.title}`} onClick={onViewTask}><ListTodo size={16} aria-hidden="true" /></button>}
        {!remote && work.state !== 'DROPPED' && <button className="pulls-toolbar-button" type="button" title="Push to GitHub or Jira…" aria-label={`Push ${item.title} to GitHub or Jira`}
          onClick={onPublish}><Upload size={16} aria-hidden="true" /></button>}
      </span>
    </div>
  </div>;
}

function DetailHeader({ label, tabs, children }: { label: string; tabs?: ReactNode; children?: ReactNode }) {
  return <header className="pull-detail__header pull-detail__header--tabs">
    <div className="pull-detail__toolbar">
      {tabs ?? <span className="work-items__label"><LocalBadge />{label}</span>}
      {children && <div className="pull-detail__header-actions">{children}</div>}
    </div>
  </header>;
}

function WorkItemForm({ workspaceId, item, epics = [], onClose, onSaved, headerAction }: {
  workspaceId: string; item?: WorkItemDto; epics?: EpicName[]; headerAction?: ReactNode; onClose: () => void; onSaved: (value: WorkItemDto) => Promise<void>;
}) {
  const goals = useEpicGoals(workspaceId);
  const [id] = useState(() => item?.id ?? crypto.randomUUID());
  const [title, setTitle] = useState(item?.title ?? '');
  const [body, setBody] = useState(item?.acceptanceCriteria ?? '');
  const [kind, setKind] = useState<WorkItemKind>('DEVELOP');
  const [epic, setEpic] = useState('');
  const { busy, error, run } = useAsyncAction(errorText);
  const save = () => run(async () => {
    const value = item ? await goals.decideWork(item, { operationId: crypto.randomUUID(), expectedRevision: item.revision, action: 'EDIT', title: title.trim(), acceptanceCriteria: body, detail: '' })
      : await goals.createWork({ id, title: title.trim(), kind, acceptanceCriteria: body, completionRule: kind === 'DEVELOP' ? 'MERGED_DELIVERY' : 'ACCEPTED_RESULT',
        ...(epic === '' ? {} : { epicId: epic }) });
    await onSaved(value);
  });
  return <>
    <DetailHeader label={item ? 'Editing work item' : 'New work item'}>{headerAction}</DetailHeader>
    <div className="pull-detail__body">
      <form className="pr-overview work-items__form" onSubmit={event => { event.preventDefault(); void save(); }}>
        <div>
          <h1>{item ? 'Edit local work' : 'New local work item'}</h1>
          <p className="work-items__note"><LockKeyhole size={13} aria-hidden="true" />{item ? 'Changes to this context are saved locally only.' : 'Stays local until you choose Push to remote.'}</p>
        </div>
        <div className="pr-overview__rule" />
        <label>Title<input autoFocus required maxLength={500} placeholder="What should change?" value={title} onChange={event => setTitle(event.target.value)} /></label>
        {/* Kind and Epic are chosen when the work is created; an Epic's Work tab moves work between Epics. */}
        {!item && <div className="work-items__fields">
          <label>Kind<select value={kind} onChange={event => setKind(event.target.value as WorkItemKind)}>
            {Object.entries(KIND).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label>
          <label className="is-wide">Epic<select value={epic} onChange={event => setEpic(event.target.value)}>
            <option value="">No epic</option>
            {epics.map(value => <option key={value.id} value={value.id}>{value.title}</option>)}
          </select></label>
        </div>}
        <label>Context and acceptance criteria<textarea rows={12} maxLength={65536} value={body} onChange={event => setBody(event.target.value)}
          placeholder="What needs to change, why, and how to know it is done. Include the context an agent will need." /></label>
        {error && <div className="issues-error" role="alert">{error}</div>}
        <div className="pr-description-editor__actions">
          <button type="button" disabled={busy} onClick={onClose}>Cancel</button>
          <button className="is-primary" disabled={busy || !title.trim()}>{busy ? 'Saving…' : 'Save locally'}</button>
        </div>
      </form>
    </div>
  </>;
}

function WorkItemDetail({ workspace, item, all, catalog, epic, publishing, onPublishing, onStart, onViewTask, onOpenEpic, onChanged, headerAction }: {
  workspace: WorkspaceDto; item: LocalItem; all: WorkItemDto[]; catalog: UnifiedWorkItem[]; epic: EpicName | null; publishing: boolean; onPublishing: (value: boolean) => void;
  onStart: () => void; onViewTask: () => void; onOpenEpic: () => void; onChanged: () => Promise<void>; headerAction?: ReactNode;
}) {
  const goals = useEpicGoals(workspace.id);
  const [editing, setEditing] = useState(false);
  const { busy, error, setError, run } = useAsyncAction(errorText);
  const availability = useAgentAvailability(item.work.workspaceId);
  const work = item.work;
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const [workShown, setWorkShown] = useState(work.isEpic);
  const unmark = useUnmarkEpic();
  const open = isOpen(work);
  const remote = remoteOf(work);
  const task = taskOf(work);
  const development = work.links.filter(link => link.kind === 'TASK' || link.kind === 'PULL_REQUEST');
  const canStart = availability.any && open && !work.isEpic && !task;
  // One item may be pushed to both GitHub and Jira; its links keep both.
  const canPublish = work.state !== 'DROPPED' && !(work.links.some(link => link.kind === 'ISSUE') && work.links.some(link => link.kind === 'JIRA'));
  const blockers = waitingOn(work, all);
  const progress = epic === null ? null : summarize({ items: epicWork(all, epic.id) }, new Set());
  const toggleEpic = () => run(async () => {
    await window.bridge.setLocalWorkItemEpic(workspace.id, work.id, !work.isEpic);
    setWorkShown(!work.isEpic);
    await onChanged();
    window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspace.id }));
  });
  const decide = (command: { action: 'FINISH' | 'DROP' | 'REOPEN' | 'PRIORITIZE' | 'EDIT'; detail: string; priority?: WorkItemPriority; completionRule?: CompletionRule }) => run(async () => {
    await goals.decideWork(work, { operationId: crypto.randomUUID(), expectedRevision: work.revision, ...command }); await onChanged();
  });
  const openLink = (link: GoalItemDto) => openGoalItem(link, workspace.id, taskId => zoomReference({ kind: 'task', workspaceId: workspace.id, taskId }));
  if (editing) return <WorkItemForm workspaceId={workspace.id} item={work} onClose={() => setEditing(false)} onSaved={async () => { await onChanged(); setEditing(false); }} headerAction={headerAction} />;
  return <>
    <DetailHeader label={remote ? `Work item · on ${REMOTE[item.source === 'JIRA' ? 'JIRA' : 'GITHUB']} ${remote.label}` : 'Work item'}
      tabs={work.isEpic ? <EpicTabs source={<><LocalBadge /> Context</>} progress={workProgress(all, localEpicId(work.id))} work={workShown && !publishing}
        onWork={shown => { setWorkShown(shown); if (shown) onPublishing(false); }} /> : undefined}>
      {remote && <button className="pull-detail__icon-action" type="button" title={`Open ${remote.label}`} aria-label={`Open ${remote.label}`} onClick={() => openLink(remote)}>
        <ExternalLink size={15} aria-hidden="true" /></button>}
      <button className="pull-detail__icon-action" type="button" title="Refresh" aria-label="Refresh" disabled={busy} onClick={() => { void onChanged().catch(cause => setError(errorText(cause))); }}>
        <RefreshIcon /></button>
      {(open || work.isEpic) && <button className="work-items__header-action is-epic" type="button" aria-label={work.isEpic ? 'Unmark as Epic' : 'Mark as Epic'} aria-pressed={work.isEpic}
        title={!work.isEpic && work.epicId ? 'Remove this item from its Epic before marking it as an Epic.' : work.isEpic ? 'Unmark as Epic' : 'Mark as Epic'}
        disabled={busy || (!work.isEpic && !!work.epicId)} onClick={() => {
          if (work.isEpic) unmark.request(workspace.id, localEpicId(work.id), () => { void toggleEpic(); });
          else void toggleEpic();
        }}><Flag size={14} aria-hidden="true" />{work.isEpic ? 'Epic' : 'Mark as Epic'}</button>}
      {canPublish && <button className="work-items__header-action is-publish" type="button" aria-expanded={publishing} onClick={() => { setWorkShown(false); onPublishing(!publishing); }}><Upload size={14} aria-hidden="true" />Push to remote…</button>}
      {canStart && <button className="pull-detail__review-action" type="button" onClick={onStart}><Play size={13} aria-hidden="true" />Start development</button>}
      {task && <button className="work-items__header-action is-task" type="button" onClick={onViewTask}><ListTodo size={14} aria-hidden="true" />View task</button>}
      {work.state !== 'DONE' && <button className="work-items__header-action is-finish" type="button" disabled={busy} onClick={() => void decide({ action: 'FINISH', detail: FINISHED_BY_USER })}>
        <CircleCheck size={14} aria-hidden="true" />Mark finished</button>}
      {headerAction}
    </DetailHeader>
    {error && <div className="pull-detail__error" role="alert">{error}</div>}
    {work.isEpic && workShown && !publishing
      ? <EpicWork epic={work} workspaceId={workspace.id} repository={repository} work={all} onShowSource={() => setWorkShown(false)} />
      : <div className="pull-detail__body">
      <div className="pr-overview work-items__overview">
        <section className="pr-overview__summary">
          <div className="pr-overview__title">
            <h1>{item.title}</h1>
            {open && <button type="button" title="Edit title and context" aria-label="Edit title and context" onClick={() => setEditing(true)}><Pencil size={15} aria-hidden="true" /></button>}
          </div>
          <div className="pr-overview__branches">
            <span className={`pr-state${STATE[work.state].pill}`}><StateIcon state={work.state} size={15} /> {STATE[work.state].label}</span>
            <LocalBadge />
            <span>created {relativeTime(work.createdAt)}</span>
            <span>· {settledWord(work)}</span>
          </div>
        </section>
        <div className="pr-overview__rule" />
        <div className="pr-meta">
          <span><small>Visibility</small>{remote
            ? <>Local + <SourceIcon source={GOAL_SOURCES[remote.kind]} size={13} />{REMOTE[remote.kind === 'JIRA' ? 'JIRA' : 'GITHUB']} {remote.label}</>
            : <><LockKeyhole size={13} aria-hidden="true" />Local only</>}</span>
          <span><small>Kind</small>{KIND[work.kind]}</span>
          <label className={`pr-meta__chip issue-type-chip${work.priority === 'HIGH' ? ' is-high' : ''}`}><small>Priority</small>
            <select aria-label="Priority" value={work.priority} disabled={busy} onChange={event => void decide({ action: 'PRIORITIZE', detail: '', priority: event.target.value as WorkItemPriority })}>
              {Object.entries(PRIORITY).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
          <label className="pr-meta__chip issue-type-chip"><small>Done when</small>
            <select aria-label="Done when" value={work.completionRule} disabled={busy || !open} onChange={event => void decide({ action: 'EDIT', detail: '', completionRule: event.target.value as CompletionRule })}>
              {Object.entries(RULE).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
        </div>
        {publishing && canPublish && <PublishWorkItem workspace={workspace} item={work} onChanged={onChanged} onClose={() => onPublishing(false)} />}
        <div className="work-items__cards">
          <article className="pr-card pr-description">
            <header className="pr-card__header">
              <strong>Context</strong><span>· edited {relativeTime(work.updatedAt)}</span>
              {open && <div className="pr-card__menu"><button type="button" className="work-items__quiet" title="Edit local context" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" />Edit</button></div>}
            </header>
            <MarkdownBody body={item.description || 'No description yet.'} repository={repository} />
            {item.source !== 'LOCAL' && work.acceptanceCriteria && work.acceptanceCriteria !== item.description && <>
              <h3 className="work-items__subhead">Local context and acceptance criteria</h3>
              <MarkdownBody body={work.acceptanceCriteria} repository={repository} />
            </>}
            <WorkItemContextLinks work={work} catalog={catalog} onChanged={onChanged} />
            <footer className="work-items__card-foot"><LockKeyhole size={12} aria-hidden="true" />{remote
              ? `Pushed to ${remote.kind === 'JIRA' ? 'Jira as' : 'GitHub as issue'} ${remote.label}. Local history and task conversations stay local.`
              : 'Private local work. Development can deliver a pull request without creating an issue or ticket.'}</footer>
          </article>
          <article className="pr-card">
            <header className="pr-card__header">
              <strong>Development</strong>
              <span>· {development.length === 0 ? 'not started' : ['TASK', 'PULL_REQUEST'].flatMap(value => {
                const total = development.filter(link => link.kind === value).length;
                return total === 0 ? [] : [`${total} ${value === 'TASK' ? 'task' : 'pull request'}${total === 1 ? '' : 's'}`];
              }).join(' · ')}</span>
            </header>
            {development.length === 0 && <div className="work-items__dev-row is-empty">
              <span>No task yet. An agent reads this context and can deliver a pull request without creating an issue or ticket.</span>
              {canStart && <button type="button" className="work-items__plain" onClick={onStart}>Start development</button>}
            </div>}
            {work.links.map(link => <div className="work-items__dev-row" key={`${link.kind}:${link.key}`}>
              <SourceIcon source={GOAL_SOURCES[link.kind]} size={16} tinted ended={link.state === 'MERGED' ? 'merged' : null} />
              <span><button type="button" className="work-items__link" onClick={() => openLink(link)}>{link.title}</button><span className="card-key">{link.label}</span>
                <small>{LINK_KIND[link.kind]}</small></span>
              <Pill tone={LINK_TONE[link.state]} className="is-square">{link.status}</Pill>
            </div>)}
            {epic !== null && progress !== null && <div className="work-items__dev-row">
              <Flag size={15} aria-hidden="true" />
              <span><small>Epic</small><button type="button" className="work-items__link" onClick={onOpenEpic}>{epic.title}</button></span>
              <span className="work-items__progress">
                <span className="epic-progress" aria-hidden="true"><span className="is-done" style={{ width: progressWidths(progress).finished }} /></span>
                {progress.finished} of {progress.total} done
              </span>
            </div>}
            {blockers.length > 0 && <div className="work-items__dev-row">
              <Link2 size={15} aria-hidden="true" />
              <span><small>Waiting on</small>{blockers.join(', ')}</span>
            </div>}
          </article>
          <details className="work-items__history" open>
            <summary><ChevronRight size={14} aria-hidden="true" />History<span>{work.history.length}</span></summary>
            <div className="pr-timeline">
              {work.history.toReversed().map(event => {
                const [words, Icon, tone] = EVENTS[event.action] ?? [`${event.action.charAt(0)}${event.action.slice(1).toLowerCase().replaceAll('_', ' ')}`, CircleDot, ''];
                const canned = event.detail === CLOSED_BY_USER || event.detail === FINISHED_BY_USER || event.detail === REOPENED_BY_USER;
                // Creation wraps the original Markdown in labels; keep them outside its headings, lists and code fences.
                const detail = event.action === 'CREATED'
                  ? event.detail.replace(/^(Work item accepted: [\s\S]*?)\nSuccess: ([\s\S]*)\n(Completion: [^\n]+)$/, '$1\n\nSuccess:\n\n$2\n\n$3') : event.detail;
                return <div className="pr-event work-items__event" key={event.operationId}>
                  <span className={`issues-event-marker${tone}`}><Icon size={13} aria-hidden="true" /></span>
                  <div>
                    {words} <time>· {relativeTime(event.at)}</time>
                    {detail && !canned && <MarkdownBody className="work-items__event-detail" body={detail} repository={repository} />}
                  </div>
                </div>;
              })}
            </div>
          </details>
          <div className="pr-description-editor__actions work-items__actions">
            {open
              ? <button type="button" disabled={busy} onClick={() => void decide({ action: 'DROP', detail: CLOSED_BY_USER })}><CircleX size={14} aria-hidden="true" />Close locally</button>
              : <button type="button" disabled={busy} onClick={() => void decide({ action: 'REOPEN', detail: REOPENED_BY_USER })}><RotateCcw size={14} aria-hidden="true" />Reopen local work</button>}
          </div>
        </div>
      </div>
    </div>}
    {unmark.dialog}
  </>;
}

function PublishWorkItem({ workspace, item, onChanged, onClose }: { workspace: WorkspaceDto; item: WorkItemDto; onChanged: () => Promise<void>; onClose: () => void }) {
  const [source, setSource] = useState<'GITHUB' | 'JIRA'>(() => item.links.some(link => link.kind === 'ISSUE') ? 'JIRA' : 'GITHUB');
  const [connections, setConnections] = useState<JiraConnection[]>([]);
  const [site, setSite] = useState('');
  const [project, setProject] = useState('');
  const [types, setTypes] = useState<{ id: string; name: string }[]>([]);
  const [type, setType] = useState('');
  const [key, setKey] = useState('');
  const { busy, error, setError, run } = useAsyncAction(errorText);
  useEffect(() => { void window.bridge.listJiraConnections().then(setConnections).catch(cause => setError(errorText(cause))); }, [setError]);
  const [connectionId, cloudId] = site.split('/');
  const pending = item.history.some(event => event.action === 'PUBLISH_STARTED' && !item.history.some(done => done.operationId === `${event.operationId}:settled`));
  const already = item.links.some(link => link.kind === (source === 'GITHUB' ? 'ISSUE' : 'JIRA'));
  const destination: Publication = { source, expectedRevision: item.revision, ...(source === 'JIRA' ? { connectionId, cloudId, project, issueTypeId: type } : {}) };
  const loadTypes = () => run(async () => {
    const values = await window.bridge.workItemJiraTypes(workspace.id, connectionId, cloudId, project); setTypes(values); setType(values[0]?.id ?? '');
  });
  const publish = (reconcile: boolean) => run(async () => {
    try {
      if (reconcile) await window.bridge.reconcileWorkItem(workspace.id, item.id, destination, key.trim());
      else await window.bridge.publishWorkItem(workspace.id, item.id, destination);
      await onChanged(); onClose();
    }
    catch (cause) { setError(errorText(cause)); await onChanged().catch(() => {}); }
  });
  const destinations = [['GITHUB', 'GitHub issue', `${workspace.monitoredOwner}/${workspace.monitoredRepo}`, 'issue'], ['JIRA', 'Jira ticket', 'A connected Jira site', 'jira']] as const;
  return <article className="pr-card work-items__publish" aria-label="Push to GitHub or Jira">
    <header className="pr-card__header">
      <Upload size={15} aria-hidden="true" /><strong>Push to GitHub or Jira</strong>
      <div className="pr-card__menu"><button type="button" className="work-items__quiet" title="Cancel" aria-label="Close Push to GitHub or Jira" onClick={onClose}><X size={15} aria-hidden="true" /></button></div>
    </header>
    <div className="work-items__publish-body">
      <p>Push this work item to GitHub as an issue or to Jira as a ticket. It stays in Local, linked to the remote copy; the title and context become visible to people with
        access to the destination. Task conversations and local history stay local.</p>
      <div className="work-items__dests">
        {destinations.map(([value, label, sub, icon]) => <button key={value} type="button" aria-pressed={source === value} disabled={busy} onClick={() => setSource(value)}>
          <span><SourceIcon source={icon} size={14} tinted />{label}</span><small>{sub}</small>
        </button>)}
      </div>
      {source === 'JIRA' && <div className="work-items__fields">
        <label className="is-wide">Site<select value={site} disabled={busy} onChange={event => { setSite(event.target.value); setTypes([]); setType(''); }}>
          <option value="">Choose a connected Jira site</option>
          {connections.filter(value => value.state === 'connected').flatMap(connection => connection.sites.map(value =>
            <option key={`${connection.id}/${value.id}`} value={`${connection.id}/${value.id}`}>{value.name} · {connection.account?.displayName}</option>))}
        </select></label>
        <label>Project key<input value={project} disabled={busy} placeholder="ENG" onChange={event => { setProject(event.target.value.toUpperCase()); setTypes([]); setType(''); }} /></label>
        {types.length > 0
          ? <label>Work type<select value={type} disabled={busy} onChange={event => setType(event.target.value)}>{types.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>
          : <button type="button" className="work-items__plain" disabled={busy || !site || !project} onClick={() => void loadTypes()}>Load work types</button>}
      </div>}
      <div className="work-items__preview">
        <small>Preview</small>
        <strong>{item.title}</strong>
        <MarkdownBody body={item.acceptanceCriteria || 'No description.'} repository={`${workspace.monitoredOwner}/${workspace.monitoredRepo}`} />
      </div>
      {already && <p>This item already has a {REMOTE[source]} source.</p>}
      {pending && <p role="alert">The previous publication may have succeeded. Find the existing issue or ticket and link it below; ByteQuay will not create another copy.</p>}
      {error && <div className="issues-error" role="alert">{error}</div>}
      <div className="pr-description-editor__actions work-items__publish-actions">
        <button type="button" className="is-primary" disabled={busy || already || pending || (source === 'JIRA' && !type)} onClick={() => void publish(false)}>
          {busy ? 'Working…' : source === 'GITHUB' ? 'Create GitHub issue' : 'Create Jira ticket'}
        </button>
        <button type="button" disabled={busy} onClick={onClose}>Cancel</button>
        <span>or link existing</span>
        <input aria-label={source === 'GITHUB' ? 'Issue number in this repository' : 'Ticket key on the selected site'} placeholder={source === 'GITHUB' ? 'Issue #' : 'ENG-123'}
          value={key} onChange={event => setKey(event.target.value)} />
        <button type="button" disabled={busy || already || !key.trim() || (source === 'JIRA' && !site)} onClick={() => void publish(true)}><Link2 size={14} aria-hidden="true" />Link</button>
      </div>
    </div>
  </article>;
}
