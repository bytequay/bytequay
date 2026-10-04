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

import { Fragment, useCallback, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Bookmark, Bug, Check, ChevronDown, ChevronUp, ChevronsDown, ChevronsUp, ClipboardList, Columns3, CornerDownRight, Equal, ExternalLink, Flag, FolderGit2, List, LoaderCircle, Minus, Pencil, Play, RefreshCw, Reply, Search, UserRound, UsersRound, X, Zap } from 'lucide-react';
import MarkdownBody from '../pulls/MarkdownBody';
import BetterWordsButton from '../pulls/BetterWordsButton';
import { usePopoverDismiss } from '../pulls/code-tests/Outline';
import { ago, relativeTime } from '../relativeTime';
import { TaskComposer, StartingAgentTask, useAgentTaskCreation } from '../agents/AgentTasksView';
import { taskCardState, taskLinks } from '../agents/attention';
import { Card, Clickable, IconButton, Pill } from '../cards/Card';
import type { AgentTaskDto, WorkspaceDto } from '../types';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { laneLabel } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import { LayerCovered } from '../Zoom';
import { zoomReference } from '../githubReference';
import PinButton from '../home/PinButton';
import CopyLinkButton from '../CopyLinkButton';
import { useRecentTouch } from '../recent/useRecentTouch';
import { completeAttentionVisit, recordAttentionAction, useAttentionVisit } from '../home/useAttentionVisit';
import { jiraAttentionItem } from './attention';
import { JIRA_CHANGED, jiraAccountLabel, jiraSiteDomain, jiraIssueKey, jiraIssueLocation, jiraLocation, jiraRoute, notifyJiraChanged, type JiraBoard, type JiraComment, type JiraConnection, type JiraField, type JiraIssue, type JiraIssueDetail, type JiraIssuePage, type JiraLinkType, type JiraLocation, type JiraRelatedIssue, type JiraSite, type JiraSprint, type JiraStatus, type JiraUser } from './contracts';
import JiraIcon from './JiraIcon';
import { completedGroups, JIRA_WORKFLOW_GROUPS, jiraCommentThreads, jiraPriorityLevel, jiraWorkflowGroup } from './presentation';
import './jira.css';

const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const bridge = () => window.bridge;
const emptyPage: JiraIssuePage = { issues: [], fetchedAt: null, error: null, nextPageToken: null };
const GROUP_TONES: Record<string, string> = { 'To do': 'todo', 'In progress': 'progress', 'In review': 'review', Done: 'done' };
const PRIORITY_WORDS: Record<string, string> = { urgent: 'Urgent', high: 'High', medium: 'Medium', low: 'Low', lowest: 'Lowest' };
/** Where the page found a ticket: its board, and the sprint when the page lists it. */
type JiraScope = { board: string; sprint: string | null };
type JiraTransition = JiraIssueDetail['transitions'][number];

function useJiraDialog() {
  const dialog = useRef<HTMLDialogElement>(null);
  const covered = useContext(LayerCovered);
  useLayoutEffect(() => {
    const element = dialog.current;
    // A modal would also make the embedded browser's toolbar inert, so it waits closed until uncovered.
    if (!covered && element) {
      // Restore the parent first so a nested workspace chooser stays above it.
      element.parentElement?.closest('dialog')?.showModal();
      element.showModal();
    }
    return () => element?.close();
  }, [covered]);
  return dialog;
}

/** A menu under the control before it; an outside press or Escape closes it, never the ticket around it. */
function Popover({ label, className = '', onClose, children }: { label: string; className?: string; onClose: () => void; children: ReactNode }) {
  const host = usePopoverDismiss(onClose, true);
  return <div ref={host} className={`jira-pop ${className}`.trim()} role="dialog" aria-label={label}>{children}</div>;
}

function MenuItem({ selected, onPick, children }: { selected?: boolean; onPick: () => void; children: ReactNode }) {
  return <button type="button" className="jira-menu__item" aria-pressed={selected} onClick={onPick}>{children}{selected && <Check size={13} strokeWidth={2.6} className="jira-menu__check" aria-hidden="true" />}</button>;
}

function SprintState({ sprint }: { sprint: JiraSprint }) {
  return sprint.state === 'active' ? <Pill tone="green">Active</Pill> : sprint.state === 'future' ? <Pill>Future</Pill> : null;
}

/** The development tasks linked tickets name, read locally for the state their card and Details show. */
function useJiraTasks(issues: JiraIssue[], version: string | null): Record<string, AgentTaskDto> {
  const [tasks, setTasks] = useState<Record<string, AgentTaskDto>>({});
  const key = JSON.stringify(issues.flatMap(issue => issue.task === null ? [] : [[issue.task.workspaceId, issue.task.id]]).sort());
  useEffect(() => {
    const links = JSON.parse(key) as [string, string][];
    if (links.length === 0) return;
    let active = true;
    // A bridge without task reads leaves the line without a state instead of failing the page.
    void Promise.allSettled(links.map(([workspaceId, id]) => Promise.resolve().then(() => window.bridge.getAgentTask(id, workspaceId))))
      .then(results => { if (active) setTasks(Object.fromEntries(results.flatMap(result => result.status === 'fulfilled' ? [[result.value.id, result.value] as const] : []))); });
    return () => { active = false; };
  }, [key, version]);
  return tasks;
}

/** Goal and Start development: icons on a card, labelled buttons on a ticket. Without a workspace, Goal asks for one first. */
export function JiraIssueActions({ issue, busy = false, labelled = false, onGoal, onStart, onAssigned }: { issue: JiraIssue; busy?: boolean; labelled?: boolean; onGoal: () => void; onStart: () => void; onAssigned?: (detail: JiraIssueDetail) => void }) {
  const [assigning, setAssigning] = useState(false);
  const task = issue.task;
  const done = issue.status.category === 'done';
  const canStart = task === null && !issue.isGoal && !issue.hasChildren && !done;
  const goalLabel = `${issue.isGoal ? 'Unmark' : 'Mark'} ${issue.key} as Goal`;
  const goal = () => { if (!issue.isGoal && issue.workspace === null) setAssigning(true); else onGoal(); };
  const viewTask = () => { if (task !== null) window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); };
  const flag = <Flag size={13} fill={issue.isGoal ? 'currentColor' : 'none'} className={issue.isGoal ? 'jira-goal-active' : undefined} aria-hidden="true" />;
  return <><span className="jira-actions" role="group" aria-label={`Actions for ${issue.key}`}>{labelled ? <>
    {(!done || issue.isGoal) && <button type="button" className={`jira-btn${issue.isGoal ? ' is-goal' : ''}`} title={goalLabel} aria-pressed={issue.isGoal} disabled={busy} onClick={goal}>{flag}{issue.isGoal ? 'Goal' : 'Mark as goal'}</button>}
    {task !== null ? <button type="button" className="jira-btn is-link" onClick={viewTask}><Play size={12} aria-hidden="true" />View task</button>
      : canStart && <button type="button" className="jira-btn is-primary" disabled={busy} onClick={onStart}><Play size={12} fill="currentColor" aria-hidden="true" />Start development</button>}
  </> : <>
    {(!done || issue.isGoal) && <IconButton label={goalLabel} title={issue.isGoal ? 'Goal · click to unmark' : 'Mark as goal'} pressed={issue.isGoal} disabled={busy} onClick={goal}>{flag}</IconButton>}
    {task !== null ? <IconButton tone="active" label={`View task for ${issue.key}`} title="View task" onClick={viewTask}><Play size={12} aria-hidden="true" /></IconButton>
      : canStart && <IconButton label={`Use agent to develop ${issue.key}`} title="Start development" disabled={busy} onClick={onStart}><Play size={12} aria-hidden="true" /></IconButton>}
  </>}</span>
  {/* The chooser renders inside a card, whose click would otherwise open the ticket too. */}
  {assigning && <span className="jira-contents" onClick={event => event.stopPropagation()}><JiraWorkspaceDialog issue={issue} onClose={() => setAssigning(false)} onAssigned={detail => { setAssigning(false); onAssigned?.(detail); onGoal(); }} /></span>}</>;
}

export default function JiraScreen({ workspace }: { workspace?: Pick<WorkspaceDto, 'id' | 'name'> }) {
  const initial = jiraLocation(window.location.hash);
  const savedSite = (() => {
    try { const value = JSON.parse(localStorage.getItem('bytequay:jira-site') ?? 'null') as { connectionId?: unknown; cloudId?: unknown } | null; return typeof value?.connectionId === 'string' && typeof value?.cloudId === 'string' ? { connectionId: value.connectionId, cloudId: value.cloudId } : null; }
    catch { return null; }
  })();
  const [connections, setConnections] = useState<JiraConnection[]>([]);
  const [connectionId, setConnectionId] = useState(initial?.connectionId ?? savedSite?.connectionId ?? '');
  const [cloudId, setCloudId] = useState(initial?.cloudId ?? savedSite?.cloudId ?? '');
  const [boards, setBoards] = useState<JiraBoard[]>([]);
  const [boardId, setBoardId] = useState<number | null>(null);
  const [sprints, setSprints] = useState<JiraSprint[]>([]);
  const [sprintId, setSprintId] = useState<number | null>(null);
  const [mine, setMine] = useState(true);
  const [layout, setLayout] = useState<'list' | 'board'>('board');
  const [view, setView] = useState<'current' | 'completed'>('current');
  const [query, setQuery] = useState('');
  // A workflow group, as the board's columns name them; empty shows every status.
  const [status, setStatus] = useState('');
  const [menu, setMenu] = useState<'scope' | 'status' | null>(null);
  const [page, setPage] = useState<JiraIssuePage>(emptyPage);
  const [selected, setSelected] = useState<JiraLocation | null>(initial);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [launch, setLaunch] = useState<{ issue: JiraIssue; onChanged?: (detail: JiraIssueDetail) => void } | null>(null);
  const search = useRef<HTMLInputElement>(null);
  const covered = useContext(LayerCovered);

  const generation = useRef(0);
  const connection = connections.find(value => value.id === connectionId && value.sites.length > 0) ?? connections.find(value => value.state === 'connected' && value.sites.length > 0) ?? connections.find(value => value.sites.length > 0);
  const site = connection?.sites.find(value => value.id === cloudId) ?? connection?.sites[0];
  const actualConnectionId = connection?.id ?? '';
  const actualCloudId = site?.id ?? '';

  const applyConnections = useCallback((values: JiraConnection[]) => { setConnections(values); }, []);
  useEffect(() => {
    let active = true;
    const load = () => { void window.bridge.listJiraConnections().then(value => { if (active) applyConnections(value); }).catch(cause => { if (active) setError(message(cause)); }); };
    load(); window.addEventListener(JIRA_CHANGED, load);
    return () => { active = false; window.removeEventListener(JIRA_CHANGED, load); };
  }, [applyConnections]);
  useEffect(() => {
    if (actualConnectionId && actualCloudId) { try { localStorage.setItem('bytequay:jira-site', JSON.stringify({ connectionId: actualConnectionId, cloudId: actualCloudId })); } catch { /* Optional view preference. */ } }
  }, [actualConnectionId, actualCloudId]);
  useEffect(() => {
    const changed = () => {
      const location = jiraLocation(window.location.hash);
      setSelected(location);
      if (location !== null) { setConnectionId(location.connectionId); setCloudId(location.cloudId); }
    };
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, []);
  useEffect(() => {
    let active = true;
    generation.current += 1;
    setBoards([]); setBoardId(null); setSprints([]); setSprintId(null); setPage(emptyPage); setStatus('');
    if (!actualConnectionId || !actualCloudId) return;
    setLoading(true);
    const apply = (values: JiraBoard[]) => {
      if (!active) return;
      let preferred = null;
      try { preferred = Number(localStorage.getItem(`bytequay:jira-board:${actualConnectionId}/${actualCloudId}`)); } catch { /* Optional view preference. */ }
      setBoards(values); setBoardId(current => values.find(board => board.id === current)?.id ?? values.find(board => board.id === preferred)?.id ?? values[0]?.id ?? null);
    };
    void (async () => {
      try { apply(await window.bridge.listJiraBoards(actualConnectionId, actualCloudId)); } catch { /* Remote refresh can recover an empty cache. */ }
      try { const values = await window.bridge.listJiraBoards(actualConnectionId, actualCloudId, true); apply(values); if (active) setError(null); }
      catch (cause) { if (active) setError(message(cause)); }
      finally { if (active) setLoading(false); }
    })();
    return () => { active = false; };
  }, [actualConnectionId, actualCloudId, connection?.state]);
  useEffect(() => {
    let active = true;
    generation.current += 1;
    setSprints([]); setSprintId(null); setPage(emptyPage);
    if (!actualConnectionId || !actualCloudId || boardId === null) return;
    setLoading(true);
    let initialized = false;
    const apply = (values: JiraSprint[]) => {
      if (!active) return;
      setSprints(values);
      if (!initialized) { setSprintId(values.find(sprint => sprint.state === 'active')?.id ?? null); initialized = values.length > 0; }
      else setSprintId(current => current === null || values.some(sprint => sprint.id === current) ? current : values.find(sprint => sprint.state === 'active')?.id ?? null);
    };
    void (async () => {
      try { apply(await window.bridge.listJiraSprints(actualConnectionId, actualCloudId, boardId)); } catch { /* Remote refresh can recover an empty cache. */ }
      try { const values = await window.bridge.listJiraSprints(actualConnectionId, actualCloudId, boardId, true); apply(values); if (active) setError(null); }
      catch (cause) { if (active) setError(message(cause)); }
      finally { if (active) setLoading(false); }
    })();
    return () => { active = false; };
  }, [actualConnectionId, actualCloudId, boardId]);
  const reload = useCallback(async (refresh = true, nextPageToken?: string) => {
    if (!actualConnectionId || !actualCloudId || boardId === null) return;
    const request = ++generation.current;
    setLoading(true);
    setError(null);
    const scope = { connectionId: actualConnectionId, cloudId: actualCloudId, boardId, sprintId, mine, completed: view === 'completed', nextPageToken };
    if (refresh && nextPageToken === undefined) {
      try {
        const cached = await window.bridge.listJiraIssues(scope);
        if (request === generation.current && cached.fetchedAt !== null) setPage(cached);
      }
      catch { /* Keep any displayed snapshot if its cache read fails. */ }
    }
    if (request !== generation.current) return;
    try {
      const value = await window.bridge.listJiraIssues(scope, refresh);
      if (request !== generation.current) return;
      setPage(value); setError(value.error); notifyJiraChanged();
    }
    catch (cause) { if (request === generation.current) setError(message(cause)); }
    finally { if (request === generation.current) setLoading(false); }
  }, [actualConnectionId, actualCloudId, boardId, sprintId, mine, view]);
  useEffect(() => { setPage(emptyPage); void reload(); return () => { generation.current += 1; }; }, [reload]);
  useEffect(() => {
    if (covered || selected !== null || launch !== null) return;
    // Slash jumps to search unless someone is typing it.
    const focusSearch = (event: KeyboardEvent) => {
      const target = event.target instanceof HTMLElement ? event.target : null;
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey || event.defaultPrevented || target?.isContentEditable || target?.closest('input, textarea, select')) return;
      event.preventDefault();
      search.current?.focus();
    };
    window.addEventListener('keydown', focusSearch);
    return () => window.removeEventListener('keydown', focusSearch);
  }, [covered, selected, launch]);
  const refreshSprint = async () => {
    if (boardId === null) return;
    if (view === 'completed') { await reload(); return; }
    const request = ++generation.current;
    setLoading(true);
    try {
      const values = await window.bridge.listJiraSprints(actualConnectionId, actualCloudId, boardId, true);
      if (request !== generation.current) return;
      setSprints(values);
      if (sprintId !== null && !values.some(value => value.id === sprintId && value.state !== 'closed')) {
        setSprintId(values.find(value => value.state === 'active')?.id ?? null);
      }
      else await reload();
    }
    catch (cause) { if (request === generation.current) setError(message(cause)); }
    finally { if (request === generation.current) setLoading(false); }
  };
  const update = (detail: JiraIssueDetail, refresh = true) => {
    setPage(value => ({ ...value, issues: value.issues.map(issue => issue.id === detail.issue.id && issue.cloudId === detail.issue.cloudId ? detail.issue : issue) }));
    notifyJiraChanged();
    void reload(refresh);
  };
  const toggleGoal = async (issue: JiraIssue) => {
    try { update(await window.bridge.setJiraGoal(jiraIssueLocation(issue), !issue.isGoal)); }
    catch (cause) { setError(message(cause)); }
  };
  const closeMenu = useCallback(() => setMenu(null), []);
  const toggleMenu = (next: 'scope' | 'status') => setMenu(current => current === next ? null : next);
  const chooseView = (next: 'current' | 'completed') => { setMenu(null); setView(next); setStatus(''); };
  const chooseSite = (next: JiraConnection, item: JiraSite) => {
    setMenu(null);
    if (next.id === actualConnectionId && item.id === actualCloudId) return;
    setConnectionId(next.id); setCloudId(item.id); setSelected(null); setLaunch(null); window.location.hash = jiraRoute(undefined, workspace?.id);
  };
  const chooseBoard = (value: JiraBoard) => {
    setMenu(null); setBoardId(value.id); setStatus('');
    try { localStorage.setItem(`bytequay:jira-board:${actualConnectionId}/${actualCloudId}`, String(value.id)); } catch { /* Optional view preference. */ }
  };
  const chooseSprint = (value: number | null) => { setMenu(null); setSprintId(value); };
  const open = (issue: JiraIssue) => { window.location.hash = jiraRoute(jiraIssueLocation(issue), workspace?.id); };
  const openParent = (issue: JiraIssue) => { window.location.hash = jiraRoute({ ...jiraIssueLocation(issue), issueId: issue.parent?.id ?? '' }, workspace?.id); };
  const board = boards.find(value => value.id === boardId) ?? null;
  const sprint = sprints.find(value => value.id === sprintId) ?? null;
  const scopedIssues = workspace ? page.issues.filter(issue => issue.workspace?.id === workspace.id) : page.issues;
  const needle = query.trim().toLowerCase();
  const matching = scopedIssues.filter(issue => (view !== 'completed' || issue.status.category === 'done') && `${issue.key} ${issue.title} ${issue.labels.join(' ')}`.toLowerCase().includes(needle));
  const shown = matching.filter(issue => !status || jiraWorkflowGroup(issue) === status);
  const workflowGroups = [...JIRA_WORKFLOW_GROUPS, ...new Set(scopedIssues.map(jiraWorkflowGroup).filter(value => !JIRA_WORKFLOW_GROUPS.some(group => group === value)))];
  const columns = workflowGroups.map(group => ({ label: group, tone: GROUP_TONES[group] ?? 'other', issues: shown.filter(issue => jiraWorkflowGroup(issue) === group) }));
  const groups = view === 'completed' ? completedGroups(shown).map(group => ({ ...group, tone: 'done' })) : columns.filter(column => column.issues.length > 0);
  const tasks = useJiraTasks(scopedIssues, page.fetchedAt);
  const sameSite = selected !== null && selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId;
  const listed = sameSite && page.issues.some(issue => issue.id === selected.issueId);
  const detailScope: JiraScope | null = sameSite && board !== null ? { board: board.name, sprint: listed ? view === 'completed' ? 'Completed' : sprint?.name ?? 'Backlog' : null } : null;
  const count = `${shown.length} ticket${shown.length === 1 ? '' : 's'}${workspace ? ` assigned to ${workspace.name}` : ''}${view === 'completed' ? ' · across sprints' : ''}`;
  const card = (issue: JiraIssue, group: string) => {
    const differs = issue.status.name.toLowerCase() !== group.toLowerCase();
    return <Card key={issue.id} compact selected={selected?.issueId === issue.id} className="jira-card" label={`${issue.key}: ${issue.title}`} onOpen={() => open(issue)}>
      <span className="jira-card__title">{issue.title}</span>
      {(differs || issue.parent !== null || issue.labels.length > 0 || issue.workspace !== null) && <span className="jira-card__chips">
        {differs && <StatusPill status={issue.status} />}
        {issue.parent && <JiraParent parent={issue.parent} onOpen={() => openParent(issue)} />}
        {issue.labels.map(label => <span className="jira-label" key={label}>{label}</span>)}
        <JiraWorkspaceBadge workspace={issue.workspace} />
      </span>}
      {issue.task !== null && <JiraTaskLine task={tasks[issue.task.id]} />}
      <span className="jira-card__foot"><JiraTypeIcon type={issue.type} /><span className="card-key">{issue.key}</span><JiraPriority priority={issue.priority} /><span className="jira-spacer" />
        <JiraIssueActions issue={issue} onAssigned={detail => update(detail, false)} onGoal={() => { void toggleGoal(issue); }} onStart={() => setLaunch({ issue })} />
        <JiraAvatar key={issue.assignee?.accountId ?? 'unassigned'} person={issue.assignee} label={`Assignee: ${issue.assignee?.displayName ?? 'Unassigned'}`} /></span>
    </Card>;
  };
  const row = (issue: JiraIssue) => <Clickable key={issue.id} className={`card-row jira-row${selected?.issueId === issue.id ? ' is-selected' : ''}`} label={`${issue.key}: ${issue.title}`} onOpen={() => open(issue)}>
    <JiraTypeIcon type={issue.type} /><span className="card-key">{issue.key}</span><span className="jira-row__title">{issue.title}</span>
    <span className="jira-row__meta">
      {view === 'current' && <StatusPill status={issue.status} />}
      {issue.parent && <JiraParent parent={issue.parent} onOpen={() => openParent(issue)} />}
      {issue.labels.length > 0 && <span className="jira-label">{issue.labels[0]}</span>}
      {issue.labels.length > 1 && <span className="jira-row__more" title={issue.labels.slice(1).join(', ')}>+{issue.labels.length - 1}</span>}
      <span className="jira-row__when">{view === 'completed'
        ? issue.completedAt !== null && Number.isFinite(Date.parse(issue.completedAt))
          ? <time dateTime={issue.completedAt} title={`Completed ${new Date(issue.completedAt).toLocaleString()}`}>{new Date(issue.completedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</time>
          : <span title="Completion date unavailable">—</span>
        : <time dateTime={issue.updatedAt} title={`Updated ${new Date(issue.updatedAt).toLocaleString()}`}>{ago(issue.updatedAt)}</time>}</span>
    </span>
    <JiraAvatar key={issue.assignee?.accountId ?? 'unassigned'} person={issue.assignee} label={`Assignee: ${issue.assignee?.displayName ?? 'Unassigned'}`} />
  </Clickable>;
  return <div className="jira-screen">
    <header className="jira-header">
      <h1><JiraIcon size={18} />Jira</h1>
      {site && <nav className="jira-tabs" aria-label="Jira views">
        <button type="button" aria-current={view === 'current' ? 'page' : undefined} onClick={() => chooseView('current')}>Current sprint{view === 'current' && page.fetchedAt !== null && <span className="jira-tabs__count">{scopedIssues.length}</span>}</button>
        <button type="button" aria-current={view === 'completed' ? 'page' : undefined} onClick={() => chooseView('completed')}>Completed</button>
      </nav>}
      <span className="jira-spacer" />
      {site && <span className="jira-muted jira-header__updated">{loading ? 'Updating…' : page.fetchedAt === null ? '' : `Updated ${relativeTime(page.fetchedAt)}`}</span>}
      {site && <button type="button" className="bq-iconbtn" aria-label="Refresh tickets" title="Refresh tickets" disabled={loading || boardId === null} onClick={() => { void refreshSprint(); }}><RefreshCw size={15} className={loading ? 'jira-spin' : ''} /></button>}
      {site && boardId !== null && <button type="button" className="jira-btn" title="Open board in Jira" onClick={() => { void window.bridge.openInAppBrowser(`${site.url.replace(/\/$/, '')}/secure/RapidBoard.jspa?rapidView=${boardId}`); }}>Open in Jira<ExternalLink size={13} aria-hidden="true" /></button>}
    </header>
    {!site && <div className="jira-connect"><h2>Connect your Jira account</h2><p>Add an Atlassian account in Settings to choose a site and board.</p><button type="button" className="jira-btn" onClick={() => { window.location.hash = settingsRoute('jira'); }}>Open Jira settings</button></div>}
    {site && <div className="jira-toolbar">
      <div className="jira-anchor">
        <button type="button" className="jira-scope" aria-haspopup="dialog" aria-expanded={menu === 'scope'} title="Site, board and sprint" onClick={() => toggleMenu('scope')}>
          <span className="jira-scope__board">{board?.name ?? 'Choose a board'}</span><span className="jira-scope__sep" aria-hidden="true">/</span>
          <span>{view === 'completed' ? 'All sprints' : sprint?.name ?? 'Backlog'}</span>
          {view === 'current' && sprint?.state === 'active' && <span className="jira-scope__state">Active</span>}<ChevronDown size={12} strokeWidth={2.2} aria-hidden="true" />
        </button>
        {menu === 'scope' && <Popover label="Site, board and sprint" className="jira-scope-menu" onClose={closeMenu}>
          <section className="jira-menu__section" aria-label="Site"><h3>Site</h3>
            {connections.flatMap(value => value.sites.map(item => <MenuItem key={`${value.id}/${item.id}`} selected={value.id === actualConnectionId && item.id === actualCloudId} onPick={() => chooseSite(value, item)}>
              <JiraIcon size={14} /><span className="jira-menu__label">{jiraSiteDomain(item)}</span><span className="jira-muted">{jiraAccountLabel(value)}</span>
            </MenuItem>))}</section>
          <section className="jira-menu__section" aria-label="Board"><h3>Board</h3>
            {boards.map(value => <MenuItem key={value.id} selected={value.id === boardId} onPick={() => chooseBoard(value)}><span className="jira-menu__label">{value.name}</span></MenuItem>)}
            {boards.length === 0 && <p className="jira-menu__empty">{loading ? 'Loading boards…' : 'No boards on this site.'}</p>}</section>
          {view === 'current' && <section className="jira-menu__section" aria-label="Sprint"><h3>Sprint</h3>
            {sprints.map(value => <MenuItem key={value.id} selected={value.id === sprintId} onPick={() => chooseSprint(value.id)}><span className="jira-menu__label">{value.name}</span><SprintState sprint={value} /></MenuItem>)}
            <MenuItem selected={sprintId === null} onPick={() => chooseSprint(null)}><span className="jira-menu__label">Backlog</span></MenuItem></section>}
          <footer className="jira-menu__foot"><button type="button" className="jira-link" onClick={() => { setMenu(null); window.location.hash = settingsRoute('jira'); }}>Manage accounts…</button><span className="jira-spacer" /><span className="jira-muted">Boards are remembered per site</span></footer>
        </Popover>}
      </div>
      <span className="jira-toolbar__rule" aria-hidden="true" />
      <button type="button" className="jira-chip-button jira-mine" aria-pressed={mine} onClick={() => setMine(value => !value)}>{mine && <Check size={12} strokeWidth={2.6} aria-hidden="true" />}Assigned to me</button>
      <div className="jira-anchor">
        <button type="button" className="jira-chip-button" aria-haspopup="dialog" aria-expanded={menu === 'status'} onClick={() => toggleMenu('status')}><span className="jira-chip-button__name">Status</span><span className="jira-chip-button__value">{status || 'All'}</span><ChevronDown size={11} strokeWidth={2.4} aria-hidden="true" /></button>
        {menu === 'status' && <Popover label="Filter status" className="jira-status-filter" onClose={closeMenu}>
          {['', ...workflowGroups].map(value => <MenuItem key={value} selected={status === value} onPick={() => { setMenu(null); setStatus(value); }}>{value || 'All'}</MenuItem>)}
        </Popover>}
      </div>
      <label className="jira-search"><Search size={13} strokeWidth={2.2} aria-hidden="true" /><input ref={search} type="search" aria-label="Search tickets" placeholder="Search tickets" value={query} onChange={event => setQuery(event.target.value)} /><kbd aria-hidden="true">/</kbd></label>
      <span className="jira-spacer" />
      <span className="jira-muted jira-count-label">{count}</span>
      {view === 'current' && <div className="jira-layout" role="group" aria-label="Ticket layout">
        <button type="button" aria-label="List view" title="List" aria-pressed={layout === 'list'} onClick={() => setLayout('list')}><List size={14} /></button>
        <button type="button" aria-label="Board view" title="Board" aria-pressed={layout === 'board'} onClick={() => setLayout('board')}><Columns3 size={14} /></button>
      </div>}
    </div>}
    {site && connection?.state !== 'connected' && <div className="jira-notice">{connection?.state === 'pending' ? 'Jira sign-in is waiting to finish.' : 'This Jira account needs attention.'} Manage accounts in Settings to {connection?.authType === 'api_token' ? 'replace the API token' : 'reconnect'}.</div>}
    {error && <div className="ws-error jira-error" role="alert">{error}</div>}
    {page.nextPageToken !== null && <div className="jira-notice">More tickets are available. Search and status filters apply to loaded tickets.</div>}
    {site && <div className="jira-content" aria-busy={loading}>
      {view === 'current' && layout === 'board'
        ? <div className="jira-board">{columns.map(column => <section className="jira-column" key={column.label} aria-label={column.label}>
          <GroupHead label={column.label} tone={column.tone} count={column.issues.length} />
          {column.issues.map(issue => card(issue, column.label))}
          {column.issues.length === 0 && <p className="jira-column__empty">No tickets</p>}
        </section>)}</div>
        : groups.length > 0 && <div className="jira-list">{groups.map(group => <section className="jira-list__group" key={group.label} aria-label={group.label}>
          <GroupHead label={group.label} tone={group.tone} count={group.issues.length} />
          <div className="jira-list__rows">{group.issues.map(row)}</div>
        </section>)}</div>}
      {shown.length === 0 && <div className="jira-empty">{loading ? <><LoaderCircle size={18} className="jira-spin" /> Loading tickets…</>
        : boardId === null ? 'Select a board to load tickets.'
          : page.fetchedAt === null && error ? 'Tickets could not be loaded. Refresh tickets to try again.'
            : page.nextPageToken !== null ? 'No loaded tickets match these filters.' : 'No tickets match these filters.'}</div>}
      {page.nextPageToken !== null && <div className="jira-load-more"><button type="button" className="jira-btn" disabled={loading} onClick={() => { if (page.nextPageToken !== null) void reload(true, page.nextPageToken); }}>{loading ? 'Loading…' : 'Load more'}</button></div>}
    </div>}
    {selected && <JiraDetail key={`${selected.connectionId}/${selected.cloudId}/${selected.issueId}`} location={selected} scope={detailScope} boardId={sameSite ? boardId : null} sprints={sameSite ? sprints : []} onClose={() => { window.location.hash = jiraRoute(undefined, workspace?.id); }} onChanged={update} onStart={(issue, onChanged) => setLaunch({ issue, onChanged })} />}
    {launch && <JiraLaunch issue={launch.issue} onChanged={detail => { update(detail, false); launch.onChanged?.(detail); }} onClose={() => setLaunch(null)} />}
  </div>;
}

function GroupHead({ label, tone, count }: { label: string; tone: string; count: number }) {
  return <h2 className="jira-group-head"><span className={`jira-dot is-${tone}`} aria-hidden="true" /><span className="jira-group-head__name">{label}</span><span className="jira-group-head__count">{count}</span></h2>;
}

function JiraWorkspaceBadge({ workspace }: { workspace: JiraIssue['workspace'] }) {
  return workspace && <span className="jira-workspace" title={`ByteQuay workspace: ${workspace.name}`}><FolderGit2 size={11} aria-hidden="true" />{workspace.name}</span>;
}

function JiraParent({ parent, onOpen }: { parent: NonNullable<JiraIssue['parent']>; onOpen: () => void }) {
  return <button type="button" className="jira-parent" title={`Parent: ${parent.key} · ${parent.title}`} onClick={event => { event.stopPropagation(); onOpen(); }}><Zap size={10} aria-hidden="true" /><span>{parent.title}</span></button>;
}

function StatusPill({ status }: { status: JiraStatus }) {
  return <Pill tone={status.category === 'done' ? 'green' : status.category === 'indeterminate' ? 'blue' : 'neutral'} className="is-square jira-status">{status.name}</Pill>;
}

/** A linked development task as a dot and a line: its pull request once there is one, otherwise its state. */
function JiraTaskLine({ task }: { task: AgentTaskDto | undefined }) {
  const state = task === undefined ? null : taskCardState(task, null);
  const pullRequest = task === undefined ? undefined : taskLinks(task).find(link => link.source === 'pr');
  const busy = state !== null && state.label !== 'Idle' && state.label !== 'Completed' ? `task ${state.label.toLowerCase()}` : null;
  const head = pullRequest ? `PR ${pullRequest.key}` : state === null ? 'Task' : `Task ${state.label.toLowerCase()}`;
  const tail = pullRequest ? [pullRequest.status?.toLowerCase(), busy] : task === undefined ? [] : [laneLabel(task.laneId), relativeTime(task.createdAt, { suffix: false })];
  const rest = tail.filter(Boolean).join(' · ');
  return <span className="jira-task-line" title={task?.title}>
    <span className={`jira-dot is-${pullRequest?.tone ?? state?.tone ?? 'neutral'}`} aria-hidden="true" />
    <span className="jira-task-line__head">{head}</span>{rest && <span className="jira-task-line__rest">· {rest}</span>}
  </span>;
}

function JiraTypeIcon({ type, size = 14 }: { type: string; size?: number }) {
  const name = type.toLowerCase().replace(/[^a-z]/g, '');
  const kind = name.includes('subtask') ? 'subtask' : ['bug', 'story', 'epic', 'task'].includes(name) ? name : 'other';
  // Outline shapes only: a boxed tick reads as a checkbox.
  const Icon = kind === 'bug' ? Bug : kind === 'story' ? Bookmark : kind === 'epic' ? Zap : kind === 'task' ? ClipboardList : kind === 'subtask' ? CornerDownRight : JiraIcon;
  return <span className={`jira-type is-${kind}`} title={type} role="img" aria-label={type}><Icon size={size} aria-hidden="true" /></span>;
}

function JiraPriority({ priority, detailed = false }: { priority: JiraIssue['priority']; detailed?: boolean }) {
  if (priority === null) return null;
  const level = jiraPriorityLevel(priority.name);
  const Icon = level === 'urgent' ? ChevronsUp : level === 'high' ? ChevronUp : level === 'medium' ? Equal : level === 'low' ? ChevronDown : level === 'lowest' ? ChevronsDown : Minus;
  // A P-number also says in words how urgent it is; a named priority already does.
  const word = detailed && /^p\d$/i.test(priority.name.trim()) ? PRIORITY_WORDS[level] : undefined;
  return <span className={`jira-priority is-${level}`} role="img" title={`Priority: ${priority.name}`} aria-label={`Priority: ${priority.name}`}>
    <Icon size={12} strokeWidth={2.6} aria-hidden="true" /><span>{priority.name}</span>{word && <span className="jira-priority__word">{word}</span>}
  </span>;
}

function JiraAvatar({ person, size = 20, label = person?.displayName ?? 'Unassigned' }: { person: JiraUser | null; size?: number; label?: string }) {
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const source = person?.avatarUrl?.trim();
  const initials = (person?.displayName ?? '').trim().split(/\s+/).map(part => part[0]).filter(Boolean);
  return <span className="jira-avatar" title={label} role="img" aria-label={label} style={{ width: size, height: size, fontSize: Math.round(size * 0.4) }}>
    {source && source !== failedSource ? <img src={source} alt="" width={size} height={size} referrerPolicy="no-referrer" onError={() => setFailedSource(source)} />
      : person ? <span>{initials.length > 1 ? `${initials[0]}${initials[initials.length - 1]}` : initials[0]}</span> : <UserRound size={Math.round(size * 0.6)} aria-hidden="true" />}
  </span>;
}

function JiraCommentRow({ value, nested = false, children }: { value: JiraComment; nested?: boolean; children: ReactNode }) {
  const created = new Date(value.created);
  return <article className="jira-comment" aria-label={`${value.parentId === null ? 'Comment' : 'Reply'} by ${value.author.displayName}`}>
    <JiraAvatar person={value.author} size={nested ? 22 : 28} />
    <div className="jira-comment__content"><header><span className="jira-comment__author">{value.author.displayName}</span>
      <time dateTime={value.created} title={created.toLocaleString(undefined, { dateStyle: 'full', timeStyle: 'short' })}>{created.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</time>
      {value.parentId !== null && !nested && <span className="jira-muted">Reply to a comment that is not loaded</span>}</header>{children}</div>
  </article>;
}

const submitOnCommandEnter = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); }
};

function JiraWorkItemRow({ item, onOpen }: { item: JiraRelatedIssue; onOpen: () => void }) {
  return <button type="button" className="jira-work-item" aria-label={`${item.key}: ${item.title}`} title={`${item.key}: ${item.title}`} onClick={onOpen}>
    <JiraTypeIcon type={item.type} size={12} /><span className="card-key">{item.key}</span><span className="jira-work-item__title">{item.title}</span><StatusPill status={item.status} />
  </button>;
}

/** Subtasks, or an epic's child work items, linked work items and the parent; each row opens its ticket over this one. */
function JiraWorkItems({ detail, location, busy, mutate }: { detail: JiraIssueDetail; location: JiraLocation; busy: boolean; mutate: (action: () => Promise<JiraIssueDetail>, success?: () => void) => Promise<void> }) {
  const [form, setForm] = useState<'child' | 'link' | null>(null);
  const [childTypes, setChildTypes] = useState<{ id: string; name: string }[] | null>(null);
  const [linkTypes, setLinkTypes] = useState<JiraLinkType[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [typeId, setTypeId] = useState('');
  const [summary, setSummary] = useState('');
  const [relation, setRelation] = useState('');
  const [key, setKey] = useState('');
  const issue = detail.issue;
  const epic = detail.hierarchyLevel > 0;
  const noun = epic ? 'child work item' : 'subtask';
  const title = epic ? 'Child work items' : 'Subtasks';
  const target = jiraIssueKey(key);
  const relations = (linkTypes ?? []).flatMap(type => type.inward === type.outward ? [{ value: `${type.id}:outward`, label: type.outward }]
    : [{ value: `${type.id}:outward`, label: type.outward }, { value: `${type.id}:inward`, label: type.inward }]);
  const relationLabel = relations.find(value => value.value === relation)?.label;
  const linkError = key.trim() !== '' && target === null ? 'Enter a ticket key like ENG-1234 or a https://…/browse/KEY link'
    : target === issue.key ? 'A ticket can’t link to itself'
      : target !== null && detail.links.some(link => link.relation === relationLabel && link.issue.key === target) ? 'Already linked with this relationship' : null;
  const open = (item: { id: string }) => zoomReference({ kind: 'jira', ...location, issueId: item.id });
  const show = (next: 'child' | 'link') => {
    setForm(next); setLoadError(null);
    // Work types and link types come from Jira only when a form needs them.
    if (next === 'child' && childTypes === null) void window.bridge.listJiraChildTypes(location).then(value => { setChildTypes(value); setTypeId(value[0]?.id ?? ''); }).catch(cause => setLoadError(message(cause)));
    if (next === 'link' && linkTypes === null) void window.bridge.listJiraLinkTypes(location.connectionId, location.cloudId).then(setLinkTypes).catch(cause => setLoadError(message(cause)));
  };
  // Escape closes the form, not the ticket.
  const escape = (event: React.KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); setForm(null); } };
  const create = () => { const text = summary.trim(); if (text && typeId) void mutate(() => window.bridge.createJiraChild(location, typeId, text), () => setSummary('')); };
  const link = () => { const [id, side] = relation.split(':'); if (target && id && linkError === null) void mutate(() => window.bridge.linkJiraIssue(location, id, side === 'outward', target), () => { setKey(''); setForm(null); }); };
  const typeName = childTypes?.find(value => value.id === typeId)?.name ?? noun;
  const childNote = loadError ?? (childTypes === null ? 'Loading work types…' : childTypes.length === 0 ? `Jira offers no work type for a ${noun} here.` : null);
  const linkNote = loadError ?? linkError;
  return <div className="jira-work">
    {detail.hierarchyLevel >= 0 && <section aria-label={title}>
      <div className="jira-work__head"><h3>{title}</h3><span className="jira-count">{detail.children.length}</span>{detail.children.length === 0 && <span className="jira-muted">None yet</span>}<span className="jira-spacer" />
        {form !== 'child' && <button type="button" className="jira-add" disabled={busy} onClick={() => show('child')}>+ Add {noun}</button>}</div>
      {detail.children.map(child => <JiraWorkItemRow key={child.id} item={child} onOpen={() => open(child)} />)}
      {detail.childrenTruncated && <p className="jira-work__note">Only the first {detail.children.length} are shown. Open Jira to see the rest.</p>}
      {form === 'child' && <form className="jira-work__form" onKeyDown={escape} onSubmit={event => { event.preventDefault(); create(); }}>
        <div className="jira-work__fields">
          {childTypes !== null && childTypes.length > 1 && <select className="jira-field" aria-label="Work type" value={typeId} disabled={busy} onChange={event => setTypeId(event.target.value)}>{childTypes.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select>}
          <input className="jira-field" aria-label={`New ${noun}`} placeholder={`${epic ? 'Child work item' : 'Subtask'} summary`} maxLength={255} autoFocus value={summary} onChange={event => setSummary(event.target.value)} />
          <button type="submit" className="jira-btn is-primary" disabled={busy || !typeId || !summary.trim()}>Create</button>
          <button type="button" className="jira-btn" disabled={busy} onClick={() => setForm(null)}>Cancel</button>
        </div>
        <p className={`jira-work__hint${loadError ? ' is-error' : ''}`}>{childNote ?? `Creates a ${typeName} under ${issue.key} in Jira. ↵ to create · Esc to cancel`}</p>
      </form>}
    </section>}
    <section aria-label="Linked work items">
      <div className="jira-work__head"><h3>Linked work items</h3><span className="jira-count">{detail.links.length}</span>{detail.links.length === 0 && <span className="jira-muted">None yet</span>}<span className="jira-spacer" />
        {form !== 'link' && <button type="button" className="jira-add" disabled={busy} onClick={() => show('link')}>+ Link ticket</button>}</div>
      {[...new Set(detail.links.map(value => value.relation))].map(value => <Fragment key={value}><h4>{value}</h4>
        {detail.links.filter(item => item.relation === value).map(item => <JiraWorkItemRow key={item.id} item={item.issue} onOpen={() => open(item.issue)} />)}</Fragment>)}
      {form === 'link' && <form className="jira-work__form" onKeyDown={escape} onSubmit={event => { event.preventDefault(); link(); }}>
        <div className="jira-work__fields">
          <span className="jira-work__lead">This ticket</span>
          <select className="jira-field" aria-label="Link relationship" value={relation} disabled={busy || linkTypes === null} onChange={event => setRelation(event.target.value)}>
            <option value="" disabled>{linkTypes === null ? 'Loading…' : 'Relationship…'}</option>{relations.map(value => <option key={value.value} value={value.value}>{value.label}</option>)}</select>
          <input className="jira-field" aria-label="Work item to link" placeholder="ENG-1234 or ticket link" autoFocus value={key} onChange={event => setKey(event.target.value)} />
          <button type="submit" className="jira-btn is-primary" disabled={busy || !relation || target === null || linkError !== null}>Link</button>
          <button type="button" className="jira-btn" disabled={busy} onClick={() => setForm(null)}>Cancel</button>
        </div>
        {relationLabel && target !== null && linkError === null && <div className="jira-work__preview"><span className="jira-work__key">{issue.key}</span><span>{relationLabel}</span><span className="jira-work__key">{target}</span><span className="jira-muted">Will be checked in Jira</span></div>}
        <p className={`jira-work__hint${linkNote ? ' is-error' : ''}`}>{linkNote ?? 'Relationships come from Jira. ↵ to link · Esc to cancel'}</p>
      </form>}
    </section>
  </div>;
}

/**
 * A ticket a link opened over its page. It has no board, so moving between sprints stays on the Jira page. A start opens its development
 * composer, filled with the draft when one came with it.
 */
export function JiraTicketLayer({ location, start = false, draft, replaces, onClose }: {
  location: JiraLocation; start?: boolean; draft?: string; replaces?: string; onClose: () => void;
}) {
  const [launch, setLaunch] = useState<{ issue: JiraIssue; draft?: string; replaces?: string; onChanged?: (detail: JiraIssueDetail) => void } | null>(null);
  useEffect(() => {
    if (!start) return;
    let active = true;
    // A ticket that already has a task reopens it, unless this is another attempt over that ended task; only a leaf ticket that is not done starts one.
    void window.bridge.getJiraIssue(location, true).then(({ issue }) => {
      if (!active) return;
      if (issue.task && issue.task.id !== replaces) window.location.hash = workspaceAgentTaskRoute(issue.task.workspaceId, issue.task.id);
      else if (!issue.isGoal && !issue.hasChildren && issue.status.category !== 'done') setLaunch({ issue, draft, replaces });
    }).catch(() => { /* The ticket view reports its own refresh failure. */ });
    return () => { active = false; };
  }, [draft, location, replaces, start]);
  return <>
    <JiraDetail location={location} scope={null} boardId={null} sprints={[]} onClose={onClose} onChanged={notifyJiraChanged} onStart={(issue, onChanged) => setLaunch({ issue, onChanged })} />
    {launch && <JiraLaunch issue={launch.issue} draft={launch.draft} replaces={launch.replaces} onChanged={detail => { notifyJiraChanged(); launch.onChanged?.(detail); }} onClose={() => setLaunch(null)} />}
  </>;
}

function JiraDetail({ location, scope, sprints, boardId, onClose, onChanged, onStart }: { location: JiraLocation; scope: JiraScope | null; sprints: JiraSprint[]; boardId: number | null; onClose: () => void; onChanged: (detail: JiraIssueDetail, refresh?: boolean) => void; onStart: (issue: JiraIssue, onChanged: (detail: JiraIssueDetail) => void) => void }) {
  const dialog = useJiraDialog();
  const [detail, setDetail] = useState<JiraIssueDetail | null>(null);
  const visit = useAttentionVisit(detail === null ? null : jiraAttentionItem(detail.issue));
  const close = () => {
    if (detail !== null) void completeAttentionVisit(`jira:${detail.issue.cloudId}:${detail.issue.id}`);
    onClose();
  };
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [comment, setComment] = useState('');
  const [commentRevision, setCommentRevision] = useState(0);
  const commentRef = useRef<HTMLTextAreaElement>(null);
  const replyRef = useRef<HTMLTextAreaElement>(null);
  const commentEditRef = useRef<HTMLTextAreaElement>(null);
  const commentRequest = useRef<{ body: string; id: string } | null>(null);
  const [reply, setReply] = useState<{ threadId: string; parentId: string; to: string; body: string } | null>(null);
  const replyRequest = useRef<{ body: string; parentId: string; id: string } | null>(null);
  const [commentEdit, setCommentEdit] = useState<{ id: string; body: string; updated: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const [assigningWorkspace, setAssigningWorkspace] = useState(false);
  const [menu, setMenu] = useState<'status' | 'sprint' | null>(null);
  const [transitionId, setTransitionId] = useState('');
  const [transitionVersion, setTransitionVersion] = useState('');
  const [transitionFields, setTransitionFields] = useState<Record<string, string>>({});
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const issue = detail?.issue;
  const tasks = useJiraTasks(issue ? [issue] : [], detail?.fetchedAt ?? null);
  useEffect(() => {
    let active = true;
    let refreshed = false;
    // Keep cached context visible when a refresh is offline, but never invent permissions from it.
    void window.bridge.getJiraIssue(location).then(value => { if (active && !refreshed) setDetail(value); }).catch(() => {});
    void window.bridge.getJiraIssue(location, true).then(value => { refreshed = true; if (active) { setDetail(value); setError(value.error); } })
      .catch(cause => { if (active) setError(message(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [location]);
  useEffect(() => {
    if (busy || editing || assigningWorkspace || menu !== null) return;
    let active = true;
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void window.bridge.getJiraIssue(location, true, true).then(value => {
        if (!active) return;
        setDetail(value); onChangedRef.current(value, false);
      }).catch(cause => { if (active) setError(`Unable to refresh ticket: ${message(cause)}`); });
    }, 60_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [busy, editing, assigningWorkspace, location, menu]);
  const covered = useContext(LayerCovered);
  const wasCovered = useRef(covered);
  useRecentTouch(issue && issue.id === location.issueId && !covered ? { type: 'JIRA', location } : null);
  useEffect(() => {
    if (wasCovered.current === covered) return;
    wasCovered.current = covered;
    if (covered) return;
    let active = true;
    // A view of the same ticket in a layer that was above this one may have changed it.
    void window.bridge.getJiraIssue(location).then(value => { if (active) { setDetail(value); onChangedRef.current(value, false); } }).catch(() => {});
    return () => { active = false; };
  }, [covered, location]);
  const mutate = async (action: () => Promise<JiraIssueDetail>, success?: () => void, handled: 'comment' | 'update' | null = 'update') => {
    if (busy) return;
    setBusy(true); setError(null);
    try {
      const value = await action();
      if (handled !== null) visit.onAction(handled, 1, jiraAttentionItem(value.issue));
      setDetail(value); setError(value.error); onChanged(value); success?.();
    }
    catch (cause) { setError(message(cause)); }
    finally { setBusy(false); }
  };
  const closeMenu = useCallback(() => { setMenu(null); setTransitionId(''); setTransitionFields({}); }, []);
  const refresh = () => { setRefreshing(true); void mutate(() => window.bridge.getJiraIssue(location, true), undefined, null).finally(() => setRefreshing(false)); };
  const transition = detail?.transitions.find(value => value.id === transitionId);
  const chooseTransition = (value: JiraTransition) => {
    if (!issue) return;
    // A transition with a screen asks for its fields first; any other one moves the ticket at once.
    if (value.fields.some(field => field.required || supportedTransitionField(field))) { setTransitionId(value.id); setTransitionVersion(issue.updatedAt); setTransitionFields({}); return; }
    closeMenu();
    void mutate(() => window.bridge.transitionJiraIssue(location, value.id, {}, issue.updatedAt));
  };
  const moveTo = (sprintId: number | null) => {
    if (!issue || boardId === null) return;
    closeMenu();
    void mutate(() => window.bridge.moveJiraIssue(location, sprintId, boardId, issue.updatedAt));
  };
  const sendComment = () => {
    const body = comment.trim();
    if (!body) return;
    if (commentRequest.current?.body !== body) commentRequest.current = { body, id: crypto.randomUUID() };
    const request = commentRequest.current;
    void mutate(() => window.bridge.commentJiraIssue(location, request.body, request.id), () => { setComment(''); setCommentRevision(value => value + 1); commentRequest.current = null; }, 'comment');
  };
  const onRewriteError = (failure: unknown | null) => setError(failure === null ? null : message(failure));
  // Jira threads are one level deep: a reply to a reply still belongs to the thread's first comment.
  const commentRow = (value: JiraComment, threadId: string, parentId: string, nested = false) => <JiraCommentRow key={value.id} value={value} nested={nested}>
    {commentEdit?.id === value.id
      ? <form className="jira-comment-edit" onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); setCommentEdit(null); } }} onSubmit={event => { event.preventDefault(); const body = commentEdit.body.trim(); if (body) void mutate(() => window.bridge.editJiraComment(location, value.id, body, commentEdit.updated), () => setCommentEdit(null)); }}>
        <textarea ref={commentEditRef} className="jira-field" aria-label="Edit comment" rows={4} autoFocus value={commentEdit.body} onChange={event => setCommentEdit({ ...commentEdit, body: event.target.value })} onKeyDown={submitOnCommandEnter} />
        <div className="jira-form-actions"><BetterWordsButton text={commentEdit.body} textareaRef={commentEditRef} workspaceId={issue?.workspace?.id} disabled={busy} onError={onRewriteError} />
          <button type="button" className="jira-btn" disabled={busy} onClick={() => setCommentEdit(null)}>Cancel</button><button type="submit" className="jira-btn is-primary" disabled={busy || !commentEdit.body.trim()}>Save</button></div>
      </form>
      : <><MarkdownBody body={value.body} /><div className="jira-comment__actions">
        <button type="button" className="bq-iconbtn" aria-label={`Reply to ${value.author.displayName}`} title="Reply" onClick={() => setReply(current => ({ threadId, parentId, to: value.author.displayName, body: current?.threadId === threadId ? current.body : '' }))}><Reply size={14} /></button>
        {value.mine && <button type="button" className="bq-iconbtn" aria-label={value.editableText === null ? 'Edit comment in Jira' : 'Edit comment'} title={value.editableText === null ? 'Edit in Jira: this comment has formatting ByteQuay cannot edit' : 'Edit'}
          onClick={() => { if (value.editableText !== null) setCommentEdit({ id: value.id, body: value.editableText, updated: value.updated }); else if (issue) void window.bridge.openInAppBrowser(`${issue.url}?focusedCommentId=${encodeURIComponent(value.id)}`); }}><Pencil size={13} /></button>}
      </div></>}
  </JiraCommentRow>;
  const done = issue?.status.category === 'done';
  return <dialog ref={dialog} className="jira-detail" aria-label="Ticket details" onCancel={event => { event.preventDefault(); if (!busy) close(); }}>
    <header className="jira-detail__head">
      <div className="jira-detail__crumbs">
        {issue && <nav className="jira-detail__path" aria-label="Ticket location">
          {detail?.project && <><span className="jira-detail__project">{detail.project}</span><span className="jira-detail__sep" aria-hidden="true">/</span></>}
          {issue.parent && <><button type="button" className="jira-detail__crumb" title={`${issue.parent.key} · ${issue.parent.title}`} onClick={() => { if (issue.parent) zoomReference({ kind: 'jira', ...location, issueId: issue.parent.id }); }}>
            <JiraTypeIcon type={issue.parent.type ?? 'Parent'} />{issue.parent.key}</button><span className="jira-detail__sep" aria-hidden="true">/</span></>}
          <span className="jira-detail__crumb"><JiraTypeIcon type={issue.type} /><span className="jira-detail__key">{issue.key}</span></span>
          <CopyLinkButton value={issue.url} label="ticket link" />
        </nav>}
        <span className="jira-spacer" />
        {issue && <PinButton className="bq-iconbtn" item={{ key: `jira:${issue.cloudId}:${issue.id}`, title: issue.title, reference: issue.key, target: { kind: 'jira', ...jiraIssueLocation(issue) } }} />}
        <button type="button" className="bq-iconbtn" aria-label="Refresh ticket" title="Refresh ticket" disabled={busy || loading} onClick={refresh}><RefreshCw size={15} className={loading || refreshing ? 'jira-spin' : ''} /></button>
        <button type="button" className="bq-iconbtn" aria-label="Close ticket" title="Close (Esc)" disabled={busy} onClick={close}><X size={17} /></button>
      </div>
      {issue && detail && <><h2>{issue.title}</h2>
        <div className="jira-detail__bar">
          <div className="jira-anchor">
            <button type="button" className={`jira-status-button is-${issue.status.category}`} title="Change status" aria-haspopup="dialog" aria-expanded={menu === 'status'} disabled={busy || loading || detail.transitions.length === 0}
              onClick={() => { const opening = menu !== 'status'; closeMenu(); if (opening) setMenu('status'); }}>{issue.status.name}<ChevronDown size={11} strokeWidth={2.6} aria-hidden="true" /></button>
            {menu === 'status' && <Popover label="Change status" className="jira-status-menu" onClose={closeMenu}>{transition
              ? <form className="jira-transition-form" onSubmit={event => { event.preventDefault(); void mutate(() => window.bridge.transitionJiraIssue(location, transition.id, transitionPayload(transition.fields, transitionFields), transitionVersion), closeMenu); }}>
                <div className="jira-menu__title">{transition.name}</div>
                {transition.fields.some(value => value.required && !supportedTransitionField(value)) && <p className="jira-notice">This transition requires fields that must be completed in Jira. <button type="button" className="jira-link" onClick={() => { void window.bridge.openInAppBrowser(issue.url); }}>Open in Jira</button></p>}
                {transition.fields.filter(supportedTransitionField).map(value => <TransitionField key={value.id} field={value} value={transitionFields[value.id] ?? ''} onChange={text => setTransitionFields(current => ({ ...current, [value.id]: text }))} />)}
                <div className="jira-form-actions"><button type="button" className="jira-btn" onClick={() => { setTransitionId(''); setTransitionFields({}); }}>Back</button>
                  <button type="submit" className="jira-btn is-primary" disabled={busy || transition.fields.some(value => value.required && (!supportedTransitionField(value) || !transitionFields[value.id]))}>{transition.name}</button></div>
              </form>
              : <><div className="jira-menu__title">Transitions</div>{detail.transitions.map(value => <MenuItem key={value.id} onPick={() => chooseTransition(value)}><StatusPill status={value.to} />{value.name !== value.to.name && <span className="jira-muted">{value.name}</span>}</MenuItem>)}</>}
            </Popover>}
          </div>
          <span className="jira-muted jira-detail__updated">Updated {ago(issue.updatedAt)}</span>
          <span className="jira-spacer" />
          <JiraIssueActions labelled issue={issue} busy={busy || loading} onAssigned={value => { setDetail(value); onChanged(value, false); }} onGoal={() => { void mutate(() => window.bridge.setJiraGoal(location, !issue.isGoal)); }} onStart={() => onStart(issue, setDetail)} />
          <button type="button" className="bq-iconbtn" aria-label={`Open ${issue.key} in Jira`} title="Open in Jira" onClick={() => { void window.bridge.openInAppBrowser(issue.url); }}><ExternalLink size={14} /></button>
        </div></>}
    </header>
    {error && <div className="ws-error" role="alert">{error}</div>}
    {!issue || !detail ? <div className="jira-empty">{loading ? 'Loading ticket…' : 'This ticket could not be loaded.'}</div> : <div className="jira-detail__body">
      <div className="jira-detail__main">
        {issue.hasChildren && <p className="jira-notice">This ticket has children. Start development from an individual child ticket.</p>}
        <section className="jira-description" aria-label="Description"><h3>Description</h3><MarkdownBody body={issue.body || 'No description.'} /></section>
        {detail.richTextFields.map(field => <section className="jira-description" key={field.id} aria-label={field.name}><h3>{field.name}</h3><MarkdownBody body={field.body} /></section>)}
        <JiraWorkItems detail={detail} location={location} busy={busy} mutate={mutate} />
        <section className="jira-comments" aria-label="Comments">
          <h3>Comments<span className="jira-count">{detail.comments.length}</span></h3>
          <form className="jira-composer" onSubmit={event => { event.preventDefault(); sendComment(); }}>
            <textarea ref={commentRef} aria-label="Add a comment" rows={5} placeholder="Write a comment…" value={comment} onChange={event => setComment(event.target.value)} onKeyDown={submitOnCommandEnter} />
            <div className="jira-composer__foot"><BetterWordsButton key={commentRevision} text={comment} textareaRef={commentRef} workspaceId={issue.workspace?.id} disabled={busy} onError={onRewriteError} />
              <span className="jira-kbd" aria-hidden="true">⌘↵</span><button type="submit" className="jira-btn is-primary" disabled={busy || !comment.trim()}>Send</button></div>
          </form>
          {detail.comments.length > 0 && <div className="jira-threads">{jiraCommentThreads(detail.comments).map(({ comment: root, replies }) => {
            // A reply whose own parent is not loaded still answers that parent.
            const parentId = root.parentId ?? root.id;
            const replying = reply?.threadId === root.id ? reply : null;
            return <div className="jira-thread" key={root.id}>
              {commentRow(root, root.id, parentId)}
              {(replies.length > 0 || replying) && <div className="jira-comment-replies">
                {replies.map(value => commentRow(value, root.id, parentId, true))}
                {replying && <form className="jira-reply-form" onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); setReply(null); } }} onSubmit={event => { event.preventDefault(); const body = replying.body.trim(); if (!body) return; if (replyRequest.current?.body !== body || replyRequest.current.parentId !== replying.parentId) replyRequest.current = { body, parentId: replying.parentId, id: crypto.randomUUID() }; const request = replyRequest.current; void mutate(() => window.bridge.commentJiraIssue(location, request.body, request.id, request.parentId), () => { setReply(null); replyRequest.current = null; }, 'comment'); }}>
                  <textarea ref={replyRef} aria-label={`Reply to ${replying.to}`} rows={3} autoFocus placeholder={`Reply to ${replying.to}…`} value={replying.body} onChange={event => setReply({ ...replying, body: event.target.value })} onKeyDown={submitOnCommandEnter} />
                  <div className="jira-reply-form__foot"><BetterWordsButton text={replying.body} textareaRef={replyRef} workspaceId={issue.workspace?.id} disabled={busy} onError={onRewriteError} />
                    <button type="button" className="jira-btn" disabled={busy} onClick={() => setReply(null)}>Cancel</button><button type="submit" className="jira-btn is-primary" disabled={busy || !replying.body.trim()}>Reply</button></div>
                </form>}
              </div>}
            </div>;
          })}</div>}
          {detail.commentsTruncated && <p className="jira-notice">Some comments are not loaded. Open Jira to read the full discussion.</p>}
          {detail.comments.length === 0 && !detail.commentsTruncated && <p className="jira-muted">No comments yet.</p>}
        </section>
      </div>
      <aside className="jira-side" aria-label="Ticket fields">
        {editing ? <JiraFieldEditor detail={detail} busy={busy} onCancel={() => setEditing(false)} onSave={(fields, version) => { void mutate(() => window.bridge.updateJiraIssue(location, fields, version), () => setEditing(false)); }} />
          : <section aria-label="Details">
            <div className="jira-side__head"><h3>Details</h3><span className="jira-spacer" /><button type="button" className="jira-add" disabled={loading || busy || detail.editableFields.length === 0} onClick={() => { closeMenu(); setEditing(true); }}>Edit fields</button></div>
            <div className="jira-fields">
              <span className="jira-fields__label">Assignee</span><span className="jira-fields__person"><JiraAvatar person={issue.assignee} /><span className={issue.assignee ? '' : 'jira-dim'}>{issue.assignee?.displayName ?? 'Unassigned'}</span></span>
              {detail.peopleFields.map(field => <Fragment key={field.id}><span className="jira-fields__label">{field.name}</span><span className="jira-fields__people">{field.people.map(person =>
                <span className="jira-fields__person" key={person.accountId}><JiraAvatar person={person} /><span>{person.displayName}</span></span>)}</span></Fragment>)}
              <span className="jira-fields__label">Reporter</span><span className="jira-fields__person"><JiraAvatar person={detail.reporter} /><span className={detail.reporter ? '' : 'jira-dim'}>{detail.reporter?.displayName ?? 'None'}</span></span>
              {detail.teamFields.map(field => <Fragment key={field.id}><span className="jira-fields__label">{field.name}</span><span className="jira-fields__team"><UsersRound size={13} aria-hidden="true" /><span>{field.team}</span></span></Fragment>)}
              <span className="jira-fields__label">Priority</span>{issue.priority ? <JiraPriority priority={issue.priority} detailed /> : <span className="jira-dim">None</span>}
              <span className="jira-fields__label">Due date</span>{issue.dueDate ? <span>{new Date(`${issue.dueDate}T12:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}</span> : <span className="jira-dim">None</span>}
              <span className="jira-fields__label">Labels</span><span className="jira-fields__labels">{issue.labels.length > 0 ? issue.labels.map(label => <span className="jira-label" key={label}>{label}</span>) : <span className="jira-dim">None</span>}</span>
              {boardId !== null && <><span className="jira-fields__label">Sprint</span><div className="jira-anchor">
                <button type="button" className="jira-field-button" title="Move to sprint" aria-haspopup="dialog" aria-expanded={menu === 'sprint'} disabled={busy || loading} onClick={() => { const opening = menu !== 'sprint'; closeMenu(); if (opening) setMenu('sprint'); }}><span>{scope?.sprint ?? 'Move to sprint…'}</span><ChevronDown size={11} strokeWidth={2.4} aria-hidden="true" /></button>
                {menu === 'sprint' && <Popover label="Move to sprint" className="jira-sprint-menu" onClose={closeMenu}><div className="jira-menu__title">Move to</div>
                  {sprints.filter(value => value.state !== 'closed').map(value => <MenuItem key={value.id} onPick={() => moveTo(value.id)}><span className="jira-menu__label">{value.name}</span><SprintState sprint={value} /></MenuItem>)}
                  <MenuItem onPick={() => moveTo(null)}><span className="jira-menu__label">Backlog</span></MenuItem></Popover>}
              </div></>}
              {issue.parent && <><span className="jira-fields__label">Parent</span><JiraParent parent={issue.parent} onOpen={() => { if (issue.parent) zoomReference({ kind: 'jira', ...location, issueId: issue.parent.id }); }} /></>}
            </div>
          </section>}
        <section className="jira-side__bytequay" aria-label="ByteQuay">
          <div className="jira-side__head"><h3>ByteQuay</h3></div>
          <div className="jira-fields">
            <span className="jira-fields__label">Workspace</span>{issue.task
              ? <span className="jira-fields__stack"><span>{issue.workspace?.name ?? 'Unavailable'}</span><span className="jira-muted">Set by the linked development task.</span></span>
              : <button type="button" className={`jira-field-button${issue.workspace ? '' : ' is-empty'}`} aria-label={issue.workspace ? `Change ByteQuay workspace: ${issue.workspace.name}` : undefined} aria-haspopup="dialog" disabled={busy || loading} onClick={() => { closeMenu(); setAssigningWorkspace(true); }}>
                <FolderGit2 size={12} aria-hidden="true" /><span>{issue.workspace?.name ?? 'Assign workspace'}</span><ChevronDown size={11} strokeWidth={2.4} aria-hidden="true" /></button>}
            <span className="jira-fields__label">Task</span>{issue.task ? <JiraTaskLine task={tasks[issue.task.id]} /> : <span className="jira-dim">Not started</span>}
            <span className="jira-fields__label">Goal</span>{issue.isGoal ? <span className="jira-fields__goal">Yes{issue.workspace ? ` · ${issue.workspace.name}` : ''}</span> : <span className="jira-dim">No</span>}
          </div>
          {issue.workspace === null && !done && <p className="jira-side__note">Start development and Goal ask for a workspace first.</p>}
        </section>
      </aside>
      {assigningWorkspace && <JiraWorkspaceDialog issue={issue} onClose={() => setAssigningWorkspace(false)} onAssigned={value => { setDetail(value); setError(value.error); setAssigningWorkspace(false); onChanged(value, false); }} />}
    </div>}
  </dialog>;
}

/** Details as a form: only the fields Jira lets you edit, and only the ones you change are sent. */
function JiraFieldEditor({ detail, busy, onCancel, onSave }: { detail: JiraIssueDetail; busy: boolean; onCancel: () => void; onSave: (fields: Record<string, unknown>, version: string) => void }) {
  const issue = detail.issue;
  const editable = (id: string) => detail.editableFields.some(value => value.id === id);
  // The ticket the form opened on stays its baseline, so a refresh underneath cannot hide a change.
  const [base] = useState(() => ({ summary: issue.title, labels: issue.labels, assignee: issue.assignee?.accountId ?? '', priority: issue.priority?.id ?? '', dueDate: issue.dueDate ?? '', version: issue.updatedAt }));
  const [summary, setSummary] = useState(base.summary);
  const [labels, setLabels] = useState(base.labels);
  const [label, setLabel] = useState('');
  const [assignee, setAssignee] = useState(base.assignee);
  const [priority, setPriority] = useState(base.priority);
  const [dueDate, setDueDate] = useState(base.dueDate);
  const [people, setPeople] = useState<{ query: string; index: number } | null>(null);
  const peopleInput = useRef<HTMLInputElement>(null);
  const peopleList = useId();
  // Jira labels hold no spaces; a label still being typed counts, so Save never drops it.
  const pending = label.trim().replace(/\s+/g, '-');
  const allLabels = pending && !labels.includes(pending) ? [...labels, pending] : labels;
  const changed = {
    summary: editable('summary') && summary.trim() !== base.summary,
    assignee: editable('assignee') && assignee !== base.assignee,
    priority: editable('priority') && priority !== base.priority,
    duedate: editable('duedate') && dueDate !== base.dueDate,
    labels: editable('labels') && allLabels.join('\n') !== base.labels.join('\n'),
  };
  const values = { summary: summary.trim(), assignee: assignee || null, priority: priority || null, duedate: dueDate || null, labels: allLabels };
  const fields = Object.fromEntries(Object.entries(values).filter(([id]) => changed[id as keyof typeof changed]));
  const count = Object.keys(fields).length;
  const canSave = count > 0 && !(changed.summary && values.summary === '') && !busy;
  const save = () => { if (canSave) onSave(fields, base.version); };
  const addLabel = () => { if (pending && !labels.includes(pending)) setLabels([...labels, pending]); setLabel(''); };
  const choices = [...new Map([...(issue.assignee ? [issue.assignee] : []), ...detail.assignees].map(value => [value.accountId, value])).values()];
  const current = choices.find(value => value.accountId === assignee) ?? null;
  const needle = people?.query.trim().toLowerCase() ?? '';
  const matches: (JiraUser | null)[] = [...choices.filter(value => value.displayName.toLowerCase().includes(needle)), ...('unassigned'.includes(needle) ? [null] : [])];
  const active = Math.min(people?.index ?? 0, Math.max(matches.length - 1, 0));
  const pick = (value: JiraUser | null) => { setAssignee(value?.accountId ?? ''); peopleInput.current?.blur(); };
  const dot = (value: boolean) => value && <span className="jira-changed" title="Changed" />;
  return <section className="jira-edit" aria-label="Edit fields" onKeyDown={event => {
    if (event.key === 'Escape') { event.preventDefault(); onCancel(); }
    else if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); save(); }
  }}>
    <div className="jira-side__head"><h3>Edit fields</h3><span className="jira-spacer" /><span className="jira-muted">Esc to cancel</span></div>
    {editable('summary') && <div className="jira-edit__field"><span className="jira-edit__label">Summary{dot(changed.summary)}</span>
      <textarea className="jira-field" aria-label="Summary" rows={4} autoFocus value={summary} onChange={event => setSummary(event.target.value)} /></div>}
    {editable('assignee') && <div className="jira-edit__field"><span className="jira-edit__label">Assignee{dot(changed.assignee)}</span>
      <div className="jira-people">
        <div className="jira-people__input"><JiraAvatar person={current} size={18} />
          <input ref={peopleInput} role="combobox" aria-label="Assignee" aria-expanded={people !== null} aria-controls={peopleList} aria-autocomplete="list" placeholder="Search people…"
            value={people ? people.query : current?.displayName ?? 'Unassigned'} onFocus={() => setPeople({ query: '', index: 0 })} onBlur={() => setPeople(null)} onChange={event => setPeople({ query: event.target.value, index: 0 })}
            onKeyDown={event => {
              if (people === null) return;
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setPeople({ ...people, index: Math.max(0, Math.min(active + (event.key === 'ArrowDown' ? 1 : -1), matches.length - 1)) }); }
              else if (event.key === 'Enter' && !event.metaKey && !event.ctrlKey) { event.preventDefault(); if (matches.length > 0) pick(matches[active]); }
              // Escape closes the list; the next one cancels the edit.
              else if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); peopleInput.current?.blur(); }
            }} /><Search size={13} strokeWidth={2.2} aria-hidden="true" /></div>
        {people && <div className="jira-pop jira-people__list" id={peopleList} role="listbox" aria-label="People">
          {matches.map((value, index) => <div key={value?.accountId ?? ''} role="option" aria-selected={index === active} className="jira-people__option" onMouseDown={event => { event.preventDefault(); pick(value); }} onMouseEnter={() => setPeople({ ...people, index })}>
            <JiraAvatar person={value} /><span className="jira-people__name">{value?.displayName ?? 'Unassigned'}</span>{(value?.accountId ?? '') === assignee && <Check size={13} strokeWidth={2.6} className="jira-menu__check" aria-hidden="true" />}
          </div>)}
          {matches.length === 0 && <p className="jira-menu__empty">No one matches “{people.query}”</p>}
          <p className="jira-people__hint">People assignable in {issue.key.split('-')[0]} · ↑↓ ↵</p>
        </div>}
      </div></div>}
    {editable('priority') && <div className="jira-edit__field"><span className="jira-edit__label">Priority{dot(changed.priority)}</span>
      <div className="jira-priority-grid" role="group" aria-label="Priority">{detail.editableFields.find(value => value.id === 'priority')?.allowedValues.map(value =>
        <button type="button" key={value.id} className={`jira-priority-option is-${jiraPriorityLevel(value.name)}`} aria-pressed={priority === value.id} onClick={() => setPriority(value.id)}><JiraPriority priority={value} detailed /></button>)}</div></div>}
    {editable('duedate') && <div className="jira-edit__field"><span className="jira-edit__label">Due date{dot(changed.duedate)}</span>
      <div className="jira-edit__row"><input className="jira-field" type="date" aria-label="Due date" value={dueDate} onChange={event => setDueDate(event.target.value)} />{dueDate && <button type="button" className="jira-btn" title="Clear due date" onClick={() => setDueDate('')}>Clear</button>}</div></div>}
    {editable('labels') && <div className="jira-edit__field"><span className="jira-edit__label">Labels{dot(changed.labels)}</span>
      <div className="jira-labels-input">{labels.map(value => <span className="jira-label-chip" key={value}>{value}<button type="button" aria-label={`Remove ${value}`} title="Remove" onClick={() => setLabels(labels.filter(item => item !== value))}><X size={10} strokeWidth={2.6} /></button></span>)}
        <input aria-label="Labels" placeholder="Add label…" value={label} onChange={event => setLabel(event.target.value)} onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ',') { event.preventDefault(); addLabel(); }
          else if (event.key === 'Backspace' && label === '' && labels.length > 0) setLabels(labels.slice(0, -1));
        }} /></div>
      <p className="jira-edit__hint">↵ to add · no spaces in Jira labels</p></div>}
    <p className="jira-edit__note">Only changed fields are sent. If someone changed this ticket in Jira since you opened it, you’ll be asked to refresh first. Other fields: <button type="button" className="jira-link" onClick={() => { void window.bridge.openInAppBrowser(issue.url); }}>Open in Jira</button></p>
    <div className="jira-edit__foot"><span className="jira-edit__count">{busy ? 'Saving…' : count > 0 ? `${count} field${count === 1 ? '' : 's'} changed` : 'No changes'}</span><span className="jira-spacer" />
      <button type="button" className="jira-btn" disabled={busy} onClick={onCancel}>Cancel</button><button type="button" className="jira-btn is-primary" disabled={!canSave} onClick={save}>Save</button></div>
  </section>;
}

export function supportedTransitionField(field: JiraField): boolean {
  if (['description', 'environment', 'comment'].includes(field.id) || ['array', 'user'].includes(field.schemaType)) return false;
  return field.allowedValues.length > 0 || ['string', 'number', 'date'].includes(field.schemaType);
}
export function transitionPayload(fields: JiraField[], values: Record<string, string>): Record<string, unknown> {
  return Object.fromEntries(fields.filter(supportedTransitionField).filter(field => values[field.id]?.trim()).map(field => [field.id,
    field.schemaType === 'number' && field.allowedValues.length === 0 ? Number(values[field.id]) : values[field.id]]));
}

function TransitionField({ field, value, onChange }: { field: JiraField; value: string; onChange: (value: string) => void }) {
  return <label>{field.name}{field.required ? ' *' : ''}{field.allowedValues.length > 0 ? <select className="jira-field" required={field.required} value={value} onChange={event => onChange(event.target.value)}><option value="">Choose…</option>{field.allowedValues.map(option => <option key={option.id} value={option.id}>{option.name}</option>)}</select>
    : <input className="jira-field" required={field.required} value={value} type={field.schemaType === 'date' ? 'date' : field.schemaType === 'number' ? 'number' : 'text'} step={field.schemaType === 'number' ? 'any' : undefined} onChange={event => onChange(event.target.value)} />}</label>;
}

function JiraWorkspaceDialog({ issue, onClose, onAssigned }: { issue: JiraIssue; onClose: () => void; onAssigned: (detail: JiraIssueDetail) => void }) {
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [workspaceId, setWorkspaceId] = useState(issue.workspace?.id ?? '');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialog = useJiraDialog();
  useEffect(() => {
    let active = true;
    void window.bridge.listWorkspaces().then(value => { if (active) setWorkspaces(value); })
      .catch(cause => { if (active) setError(message(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const assign = async () => {
    if (busy || loading || !workspaceId || workspaceId === issue.workspace?.id) return;
    setBusy(true); setError(null);
    try {
      const value = await window.bridge.assignJiraWorkspace(jiraIssueLocation(issue), workspaceId);
      recordAttentionAction(`jira:${issue.cloudId}:${issue.id}`, 'update', 1, jiraAttentionItem(value.issue));
      onAssigned(value);
    }
    catch (cause) { setError(message(cause)); setBusy(false); }
  };
  return <dialog ref={dialog} aria-label={`Assign workspace for ${issue.key}`} className="jira-launch jira-assignment" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Assign a workspace</h2><button type="button" className="bq-iconbtn" aria-label="Close workspace assignment" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>Choose the ByteQuay workspace for <strong>{issue.key}</strong>. This choice is saved for this ticket.</p>
    <form onSubmit={event => { event.preventDefault(); void assign(); }}>
      <label>Workspace<select required disabled={busy || loading} value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}><option value="" disabled>{loading ? 'Loading workspaces…' : 'Choose the repository for this ticket'}</option>{workspaces.map(value => <option value={value.id} key={value.id}>{value.name} · {value.monitoredOwner}/{value.monitoredRepo}</option>)}</select></label>
      {!loading && !error && workspaces.length === 0 && <p className="jira-muted">Watch a repository from Home to create a ByteQuay workspace, then try again.</p>}
      {error && <div className="ws-error" role="alert">{error}</div>}
      <footer className="jira-actions"><button type="button" className="bq-button" disabled={busy} onClick={onClose}>Cancel</button><button type="submit" className="bq-button bq-button--primary" disabled={busy || loading || !workspaceId || workspaceId === issue.workspace?.id}>{busy ? 'Assigning…' : 'Assign workspace'}</button></footer>
    </form>
  </dialog>;
}

function JiraLaunch({ issue: initialIssue, draft, replaces, onClose, onChanged }: {
  issue: JiraIssue; draft?: string; replaces?: string; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void;
}) {
  const [issue, setIssue] = useState(initialIssue);
  return issue.workspace === null
    ? <JiraWorkspaceDialog issue={issue} onClose={onClose} onAssigned={detail => { setIssue(detail.issue); onChanged(detail); }} />
    : <JiraLaunchContent issue={issue} workspaceId={issue.workspace.id} draft={draft} replaces={replaces} onClose={onClose} onChanged={detail => { setIssue(detail.issue); onChanged(detail); }} />;
}

function JiraLaunchContent({ issue, workspaceId, draft, replaces, onClose, onChanged }: {
  issue: JiraIssue; workspaceId: string; draft?: string; replaces?: string; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void;
}) {
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialog = useJiraDialog();
  const refresh = async () => {
    setRefreshing(true); setError(null);
    try {
      const detail = await window.bridge.getJiraIssue(jiraIssueLocation(issue), true);
      setError(detail.error); onChanged(detail);
      if (detail.issue.task !== null && detail.issue.task.id !== replaces) window.location.hash = workspaceAgentTaskRoute(detail.issue.task.workspaceId, detail.issue.task.id);
    }
    catch (cause) { setError(message(cause)); }
    finally { setRefreshing(false); }
  };
  return <dialog ref={dialog} aria-label={`Develop ${issue.key}`} className="jira-launch" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Develop {issue.key}</h2><span className="jira-spacer" /><button type="button" className="bq-iconbtn" aria-label="Refresh development source" title="Refresh ticket context" disabled={busy || refreshing} onClick={() => { void refresh(); }}><RefreshCw size={16} /></button><button type="button" className="bq-iconbtn" aria-label="Close development" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>{issue.title}</p><JiraWorkspaceBadge workspace={issue.workspace} />
    {error && <div className="ws-error" role="alert">{error}</div>}
    <JiraLaunchComposer key={workspaceId} issue={issue} workspaceId={workspaceId} draft={draft} replaces={replaces} refreshing={refreshing} onBusy={setBusy} />
  </dialog>;
}
function JiraLaunchComposer({ issue, workspaceId, draft, replaces, onBusy, refreshing }: {
  issue: JiraIssue; workspaceId: string; draft?: string; replaces?: string; onBusy: (busy: boolean) => void; refreshing: boolean;
}) {
  const onOpenTask = useCallback((id: string) => { window.location.hash = workspaceAgentTaskRoute(workspaceId, id); }, [workspaceId]);
  const creation = useAgentTaskCreation({ workspaceId, bridge, sourceJira: issue, initialDraft: draft, replaces, onOpenTask, onOpenCreatedTask: task => {
    recordAttentionAction(`jira:${issue.cloudId}:${issue.id}`, 'prompt');
    window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id);
  } });
  useEffect(() => { onBusy(creation.busy); }, [creation.busy, onBusy]);
  return <>{creation.error && <div className="ws-error" role="alert">{creation.error}</div>}{creation.busy && creation.startup !== null
    ? <StartingAgentTask draft={creation.draft} images={creation.images} laneId={creation.laneId} providers={creation.catalog?.providers ?? []} models={creation.models} selection={creation.selection} startup={creation.startup} />
    : <TaskComposer {...creation.composer} blocker={refreshing ? 'Refreshing ticket context…' : creation.blocker} issueAttachment={creation.issueAttachment} onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspaceId); }} />}</>;
}
