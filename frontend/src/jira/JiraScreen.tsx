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

import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Bookmark, Bug, ChevronDown, ChevronUp, ChevronsDown, ChevronsUp, ExternalLink, Flag, Columns3, FolderGit2, List, LoaderCircle, MessageSquare, Minus, Play, RefreshCw, SquareCheck, UserRound, X, Zap } from 'lucide-react';
import MarkdownBody from '../pulls/MarkdownBody';
import { relativeTime } from '../relativeTime';
import { TaskComposer, StartingAgentTask, useAgentTaskCreation } from '../agents/AgentTasksView';
import type { WorkspaceDto } from '../types';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { settingsRoute } from '../settings/routes';
import { JIRA_CHANGED, jiraAccountLabel, jiraSiteDomain, jiraIssueLocation, jiraLocation, jiraRoute, notifyJiraChanged, type JiraBoard, type JiraConnection, type JiraField, type JiraIssue, type JiraIssueDetail, type JiraIssuePage, type JiraLocation, type JiraSprint } from './contracts';
import JiraIcon from './JiraIcon';
import { completedGroups, JIRA_WORKFLOW_GROUPS, jiraWorkflowGroup } from './presentation';
import './jira.css';

const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const bridge = () => window.bridge;
const emptyPage: JiraIssuePage = { issues: [], fetchedAt: null, error: null, partial: false };
const JiraBrowserOpen = createContext(false);

function useJiraDialog() {
  const dialog = useRef<HTMLDialogElement>(null);
  const browserOpen = useContext(JiraBrowserOpen);
  useLayoutEffect(() => {
    const element = dialog.current;
    // Native modal dialogs otherwise make the embedded browser's toolbar inert.
    if (!browserOpen && element) {
      // Restore the parent first so a nested workspace chooser stays above it.
      element.parentElement?.closest('dialog')?.showModal();
      element.showModal();
    }
    return () => element?.close();
  }, [browserOpen]);
  return dialog;
}

export function JiraIssueActions({ issue, busy = false, onGoal, onStart, onAssigned }: { issue: JiraIssue; busy?: boolean; onGoal: () => void; onStart: () => void; onAssigned?: (detail: JiraIssueDetail) => void }) {
  const [assigning, setAssigning] = useState(false);
  return <><span className="jira-actions" role="group" aria-label={`Actions for ${issue.key}`}>
    <button className="bq-iconbtn" type="button" title="Open in Jira" aria-label={`Open ${issue.key} in Jira`}
      onClick={event => { event.stopPropagation(); void window.bridge.openInAppBrowser(issue.url); }}><ExternalLink size={15} /></button>
    {issue.task !== null ? <button className="bq-iconbtn" type="button" title="View task" aria-label={`View task for ${issue.key}`}
      onClick={event => { event.stopPropagation(); window.location.hash = workspaceAgentTaskRoute(issue.task?.workspaceId ?? '', issue.task?.id ?? ''); }}><SquareCheck size={15} /></button>
      : !issue.isGoal && !issue.hasChildren && issue.status.category !== 'done' && <button className="bq-iconbtn" type="button" title="Use agent to develop" aria-label={`Use agent to develop ${issue.key}`} disabled={busy}
        onClick={event => { event.stopPropagation(); onStart(); }}><Play size={15} /></button>}
    <button className={`bq-iconbtn${issue.isGoal ? ' jira-goal-active' : ''}`} type="button" title={issue.isGoal ? 'Unmark as Goal' : 'Mark as Goal'}
      aria-label={`${issue.isGoal ? 'Unmark' : 'Mark'} ${issue.key} as Goal`} aria-pressed={issue.isGoal} disabled={busy}
      onClick={event => { event.stopPropagation(); if (!issue.isGoal && issue.workspace === null) setAssigning(true); else onGoal(); }}><Flag size={15} /></button>
  </span>{assigning && <JiraWorkspaceDialog issue={issue} onClose={() => setAssigning(false)} onAssigned={detail => { setAssigning(false); onAssigned?.(detail); onGoal(); }} />}</>;
}

export default function JiraScreen({ browserOpen = false, workspace }: { browserOpen?: boolean; workspace?: Pick<WorkspaceDto, 'id' | 'name'> }) {
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
  const [status, setStatus] = useState('');
  const [page, setPage] = useState<JiraIssuePage>(emptyPage);
  const [selected, setSelected] = useState<JiraLocation | null>(initial);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [launch, setLaunch] = useState<{ issue: JiraIssue; onChanged?: (detail: JiraIssueDetail) => void } | null>(null);

  const generation = useRef(0);
  const connection = connections.find(value => value.id === connectionId && value.sites.length > 0) ?? connections.find(value => value.state === 'connected' && value.sites.length > 0) ?? connections.find(value => value.sites.length > 0);
  const site = connection?.sites.find(value => value.id === cloudId) ?? connection?.sites[0];
  const actualConnectionId = connection?.id ?? '';
  const actualCloudId = site?.id ?? '';

  useEffect(() => {
    if (selected === null || new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('start') !== '1') return;
    let active = true;
    void window.bridge.getJiraIssue(selected, true).then(value => {
      if (!active) return;
      if (value.issue.task) window.location.hash = workspaceAgentTaskRoute(value.issue.task.workspaceId, value.issue.task.id);
      else if (!value.issue.isGoal && !value.issue.hasChildren && value.issue.status.category !== 'done') setLaunch({ issue: value.issue });
      else setError('Choose a leaf ticket to start development.');
    }).catch(cause => { if (active) setError(message(cause)); });
    return () => { active = false; };
  }, [selected]);

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
  const reload = useCallback(async (refresh = true) => {
    if (!actualConnectionId || !actualCloudId || boardId === null) return;
    const request = ++generation.current;
    setLoading(true);
    const scope = { connectionId: actualConnectionId, cloudId: actualCloudId, boardId, sprintId, mine, completed: view === 'completed' };
    if (refresh) {
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
  const open = (issue: JiraIssue) => { window.location.hash = jiraRoute(jiraIssueLocation(issue), workspace?.id); };
  const scopedIssues = workspace ? page.issues.filter(issue => issue.workspace?.id === workspace.id) : page.issues;
  const shown = scopedIssues.filter(issue => (view !== 'completed' || issue.status.category === 'done') && (!status || issue.status.id === status) && `${issue.key} ${issue.title} ${issue.labels.join(' ')}`.toLowerCase().includes(query.toLowerCase()));
  const statuses = [...new Map(scopedIssues.map(issue => [issue.status.id, issue.status])).values()];
  const workflowGroups = [...JIRA_WORKFLOW_GROUPS, ...new Set(shown.map(jiraWorkflowGroup).filter(value => !JIRA_WORKFLOW_GROUPS.some(group => group === value)))];
  const history = completedGroups(shown);
  const card = (issue: JiraIssue) => <article key={issue.id} className={`jira-ticket${selected?.issueId === issue.id ? ' is-selected' : ''}`} aria-label={`${issue.key}: ${issue.title}`}
    onClick={event => {
      if (event.target instanceof Element && event.target.closest('button, dialog')) return;
      event.currentTarget.querySelector<HTMLButtonElement>('.jira-ticket__title')?.focus();
      open(issue);
    }}>
    <button className="jira-ticket__title" type="button" aria-haspopup="dialog" onClick={() => open(issue)}><strong>{issue.title}</strong></button>
    <div className="jira-ticket__tags"><span className={`jira-status is-${issue.status.category}`}>{issue.status.name}</span><JiraWorkspaceBadge workspace={issue.workspace} />{issue.labels.map(label => <span className="jira-ticket__label" key={label}>{label}</span>)}</div>
    {issue.parent && <button type="button" className="jira-parent" title={`${issue.parent.key} · ${issue.parent.title}`} onClick={() => { window.location.hash = jiraRoute({ ...jiraIssueLocation(issue), issueId: issue.parent?.id ?? '' }, workspace?.id); }}><Zap size={12} />{issue.parent.title}</button>}
    <div className="jira-ticket__footer"><button type="button" className="jira-ticket__key" onClick={() => open(issue)}><JiraTypeIcon type={issue.type} /><span>{issue.key}</span></button>
      <span className="jira-spacer" /><JiraPriority priority={issue.priority} /><JiraAssignee key={issue.assignee?.accountId ?? 'unassigned'} assignee={issue.assignee} />
    </div>
    <div className="jira-ticket__time">{view === 'completed' ? issue.completedAt !== null && Number.isFinite(Date.parse(issue.completedAt))
      ? <time dateTime={issue.completedAt} title={new Date(issue.completedAt).toLocaleString()}>Completed {new Date(issue.completedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}</time>
      : 'Completion date unavailable'
      : <time dateTime={issue.updatedAt} title={new Date(issue.updatedAt).toLocaleString()}>Updated {relativeTime(issue.updatedAt)}</time>}<span className="jira-spacer" /><JiraIssueActions issue={issue} onAssigned={detail => update(detail, false)} onGoal={() => { void toggleGoal(issue); }} onStart={() => setLaunch({ issue })} /></div>
  </article>;
  return <JiraBrowserOpen.Provider value={browserOpen}><div className="jira-screen">
    <header className="jira-header"><div><h1><JiraIcon size={25} />Jira</h1><p>{workspace ? `Tickets assigned to ${workspace.name}.` : 'Your sprint, development, and completed work.'}</p></div><span className="jira-spacer" />
      {site && boardId !== null && <button type="button" className="bq-button jira-open-board" onClick={() => { void window.bridge.openInAppBrowser(`${site.url.replace(/\/$/, '')}/secure/RapidBoard.jspa?rapidView=${boardId}`); }}>Open board in Jira <ExternalLink size={14} /></button>}
      <button type="button" className="bq-button" onClick={() => { window.location.hash = settingsRoute('jira'); }}>Manage accounts</button>
      <button type="button" className="bq-iconbtn" aria-label="Refresh tickets" title="Refresh tickets" disabled={loading || boardId === null} onClick={() => { void refreshSprint(); }}><RefreshCw size={17} className={loading ? 'jira-spin' : ''} /></button>
    </header>
    {!site && <div className="jira-connect"><h2>Connect your Jira account</h2><p>Add an Atlassian account in Settings to choose a site and board.</p><button type="button" className="bq-button" onClick={() => { window.location.hash = settingsRoute('jira'); }}>Open Jira settings</button></div>}
    {site && connection?.state !== 'connected' && <div className="jira-notice">{connection?.state === 'pending' ? 'Jira sign-in is waiting to finish.' : 'This Jira account needs attention.'} Manage accounts in Settings to {connection?.authType === 'api_token' ? 'replace the API token' : 'reconnect'}.</div>}
    {error && <div className="ws-error" role="alert">{error}</div>}
    {site && <><nav className="jira-views" aria-label="Jira views">
      <button type="button" aria-current={view === 'current' ? 'page' : undefined} onClick={() => { setView('current'); setStatus(''); }}>Current sprint</button>
      <button type="button" aria-current={view === 'completed' ? 'page' : undefined} onClick={() => { setView('completed'); setStatus(''); }}>Completed</button>
    </nav><div className="jira-toolbar">
      <select aria-label="Jira site" value={`${actualConnectionId}/${actualCloudId}`} onChange={event => { const [c, s] = event.target.value.split('/'); setConnectionId(c); setCloudId(s); setSelected(null); setLaunch(null); window.location.hash = jiraRoute(undefined, workspace?.id); }}>
        {connections.flatMap(value => value.sites.map(item => <option key={`${value.id}/${item.id}`} value={`${value.id}/${item.id}`}>{item.name} · {jiraSiteDomain(item)} · {jiraAccountLabel(value)}</option>))}</select>
      <select aria-label="Jira board" value={boardId ?? ''} onChange={event => { setBoardId(Number(event.target.value)); setStatus(''); try { localStorage.setItem(`bytequay:jira-board:${actualConnectionId}/${actualCloudId}`, event.target.value); } catch { /* Optional view preference. */ } }}><option value="" disabled>Select board</option>{boards.map(board => <option value={board.id} key={board.id}>{board.name}</option>)}</select>
      {view === 'current' && <select aria-label="Sprint" value={sprintId ?? ''} onChange={event => setSprintId(event.target.value === '' ? null : Number(event.target.value))}><option value="">Backlog</option>{sprints.map(sprint => <option value={sprint.id} key={sprint.id}>{sprint.name}{sprint.state === 'active' ? ' · Active' : ''}</option>)}</select>}
      <label className="jira-mine"><input type="checkbox" checked={mine} onChange={event => setMine(event.target.checked)} />Assigned to me</label>
      <span className="jira-spacer" />{view === 'current' && <div className="jira-layout" role="group" aria-label="Ticket layout"><button className="bq-iconbtn" type="button" aria-label="List view" aria-pressed={layout === 'list'} onClick={() => setLayout('list')}><List size={16} /></button><button className="bq-iconbtn" type="button" aria-label="Board view" aria-pressed={layout === 'board'} onClick={() => setLayout('board')}><Columns3 size={16} /></button></div>}
    </div><div className="jira-toolbar jira-toolbar--secondary"><input type="search" aria-label="Search tickets" placeholder="Search tickets" value={query} onChange={event => setQuery(event.target.value)} />
      <select aria-label="Filter status" value={status} onChange={event => setStatus(event.target.value)}><option value="">All statuses</option>{statuses.map(value => <option value={value.id} key={value.id}>{value.name}</option>)}</select>
      <span className="jira-muted">{shown.length} tickets{page.fetchedAt && ` · Updated ${relativeTime(page.fetchedAt)}`}</span>
    </div>
    {view === 'completed' && <p className="jira-history-scope">Completed tickets matching this board, across sprints{mine ? ', assigned to you' : ''}. Jira’s board filters and mapped statuses apply.</p>}
    {page.partial && <div className="jira-notice">Only part of this {view === 'completed' ? 'completed view' : 'sprint'} is loaded. Open Jira to see all matching tickets.</div>}
    <div className="jira-content"><div className={`jira-items is-${view === 'completed' ? 'history' : layout}`} aria-busy={loading}>
      {view === 'completed' ? history.map(group => <section className="jira-history-group" key={group.label}><h2>{group.label}<span>{group.issues.length}</span></h2><div className="jira-history__items">{group.issues.map(card)}</div></section>)
        : layout === 'board' ? workflowGroups.map(group => <section className="jira-column" key={group}><h2>{group}<span>{shown.filter(issue => jiraWorkflowGroup(issue) === group).length}</span></h2>{shown.filter(issue => jiraWorkflowGroup(issue) === group).map(card)}{!shown.some(issue => jiraWorkflowGroup(issue) === group) && <p className="jira-column__empty">No tickets</p>}</section>) : shown.map(card)}
      {shown.length === 0 && <div className="jira-empty">{loading ? <><LoaderCircle size={18} className="jira-spin" /> Loading tickets…</> : page.fetchedAt === null ? 'Select a board to load tickets.' : 'No tickets match this view.'}</div>}
    </div></div></>}
    {selected && <JiraDetail key={`${selected.connectionId}/${selected.cloudId}/${selected.issueId}`} location={selected} boardId={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? boardId : null} sprints={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? sprints : []} onClose={() => { window.location.hash = jiraRoute(undefined, workspace?.id); }} onChanged={update} onStart={(issue, onChanged) => setLaunch({ issue, onChanged })} />}
    {launch && <JiraLaunch issue={launch.issue} onChanged={detail => { update(detail, false); launch.onChanged?.(detail); }} onClose={() => setLaunch(null)} />}
  </div></JiraBrowserOpen.Provider>;
}

function JiraWorkspaceBadge({ workspace }: { workspace: JiraIssue['workspace'] }) {
  return workspace && <span className="jira-workspace" title={`ByteQuay workspace: ${workspace.name}`}><FolderGit2 size={13} aria-hidden="true" />{workspace.name}</span>;
}

function JiraTypeIcon({ type }: { type: string }) {
  const name = type.toLowerCase();
  const Icon = name === 'bug' ? Bug : name === 'story' ? Bookmark : name === 'epic' ? Zap : /^(task|sub-task|subtask)$/.test(name) ? SquareCheck : JiraIcon;
  return <span className={`jira-type is-${name.replace(/[^a-z]/g, '')}`} title={type} aria-label={type}><Icon size={15} /></span>;
}
function JiraPriority({ priority }: { priority: JiraIssue['priority'] }) {
  if (priority === null) return null;
  const name = priority.name.toLowerCase();
  const Icon = /^(highest|critical|blocker)$/.test(name) ? ChevronsUp : name === 'high' ? ChevronUp : name === 'low' ? ChevronDown : name === 'lowest' ? ChevronsDown : Minus;
  const level = /^(highest|critical|blocker|high)$/.test(name) ? 'high' : /^(low|lowest)$/.test(name) ? 'low' : 'neutral';
  return <span className={`jira-priority is-${level}`} title={`Priority: ${priority.name}`} aria-label={`Priority: ${priority.name}`}><Icon size={16} /><span>{priority.name}</span></span>;
}
function JiraAssignee({ assignee }: { assignee: JiraIssue['assignee'] }) {
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const source = assignee?.avatarUrl?.trim();
  const name = assignee?.displayName ?? 'Unassigned';
  const initials = name.trim().split(/\s+/).map(part => part[0]).filter(Boolean);
  return <span className="jira-assignee" title={`Assignee: ${name}`} role="img" aria-label={`Assignee: ${name}`}>
    {source && source !== failedSource ? <img src={source} alt="" width={25} height={25} referrerPolicy="no-referrer" onError={() => setFailedSource(source)} />
      : assignee ? <span>{initials.length > 1 ? `${initials[0]}${initials[initials.length - 1]}` : initials[0]}</span> : <UserRound size={14} />}
  </span>;
}

function JiraDetail({ location, sprints, boardId, onClose, onChanged, onStart }: { location: JiraLocation; sprints: JiraSprint[]; boardId: number | null; onClose: () => void; onChanged: (detail: JiraIssueDetail, refresh?: boolean) => void; onStart: (issue: JiraIssue, onChanged: (detail: JiraIssueDetail) => void) => void }) {
  const dialog = useJiraDialog();
  const [detail, setDetail] = useState<JiraIssueDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [comment, setComment] = useState('');
  const commentRequest = useRef<{ body: string; id: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const [editVersion, setEditVersion] = useState('');
  const [transitionVersion, setTransitionVersion] = useState('');
  const [moveVersion, setMoveVersion] = useState('');
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const [summary, setSummary] = useState('');
  const [labels, setLabels] = useState('');
  const [assignee, setAssignee] = useState('');
  const [priority, setPriority] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [transitionId, setTransitionId] = useState('');
  const [transitionFields, setTransitionFields] = useState<Record<string, string>>({});
  const [moveTo, setMoveTo] = useState('');
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
    if (busy || editing || transitionId || moveTo) return;
    let active = true;
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void window.bridge.getJiraIssue(location, true, true).then(value => {
        if (!active) return;
        setDetail(value); onChangedRef.current(value, false);
      }).catch(cause => { if (active) setError(`Unable to refresh ticket: ${message(cause)}`); });
    }, 60_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [busy, editing, location, transitionId, moveTo]);
  const mutate = async (action: () => Promise<JiraIssueDetail>, success?: () => void) => {
    if (busy) return;
    setBusy(true); setError(null);
    try { const value = await action(); setDetail(value); setError(value.error); onChanged(value); success?.(); }
    catch (cause) { setError(message(cause)); }
    finally { setBusy(false); }
  };
  const issue = detail?.issue;
  const transition = detail?.transitions.find(value => value.id === transitionId);
  const field = (id: string) => detail?.editableFields.find(value => value.id === id);
  const startEditing = () => { if (!issue) return; setSummary(issue.title); setLabels(issue.labels.join(', ')); setAssignee(issue.assignee?.accountId ?? ''); setPriority(issue.priority?.id ?? ''); setDueDate(issue.dueDate ?? ''); setEditVersion(issue.updatedAt); setEditing(true); };
  const save = () => {
    if (!issue) return;
    const fields: Record<string, unknown> = {};
    if (field('summary')) fields.summary = summary.trim();
    if (field('labels')) fields.labels = [...new Set(labels.split(',').map(value => value.trim()).filter(Boolean))];
    if (field('assignee')) fields.assignee = assignee || null;
    if (field('priority')) fields.priority = priority || null;
    if (field('duedate')) fields.duedate = dueDate || null;
    void mutate(() => window.bridge.updateJiraIssue(location, fields, editVersion), () => setEditing(false));
  };
  return <dialog ref={dialog} className="jira-detail" aria-label="Ticket details" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}><div className="jira-detail__toolbar">
    {issue && <span className="jira-detail__reference"><JiraTypeIcon type={issue.type} />{issue.key}<span className="jira-muted">{issue.type}</span></span>}<span className="jira-spacer" />
    <button type="button" className="bq-iconbtn" aria-label="Refresh ticket" title="Refresh ticket" disabled={busy || loading} onClick={() => { void mutate(() => window.bridge.getJiraIssue(location, true)); }}><RefreshCw size={16} /></button>
    <button type="button" className="bq-iconbtn" aria-label="Close ticket" title="Close ticket" disabled={busy} onClick={onClose}><X size={18} /></button></div>
    {error && <div className="ws-error" role="alert">{error}</div>}
    {!issue || !detail ? <div className="jira-empty">{loading ? 'Loading ticket…' : 'This ticket could not be loaded.'}</div> : <>
      <h2>{issue.title}</h2>
      <div className="jira-ticket__meta"><span className={`jira-status is-${issue.status.category}`}>{issue.status.name}</span><JiraWorkspaceBadge workspace={issue.workspace} /><span>Updated {relativeTime(issue.updatedAt)}</span>
        <JiraIssueActions issue={issue} busy={busy || loading} onAssigned={value => { setDetail(value); onChanged(value, false); }} onGoal={() => { void mutate(() => window.bridge.setJiraGoal(location, !issue.isGoal)); }} onStart={() => onStart(issue, setDetail)} /></div>
      {issue.hasChildren && <p className="jira-notice">This ticket has children. Start development from an individual child ticket.</p>}
      <div className="jira-detail__body"><div className="jira-detail__main">
      <section className="jira-description"><h3>Description</h3><MarkdownBody body={issue.body || 'No description.'} /></section>
      <section className="jira-comments"><h3><MessageSquare size={16} /> Comments <span>{detail.comments.length}</span></h3>
        {detail.comments.map(value => <article key={value.id}><header><strong>{value.author.displayName}</strong><time dateTime={value.created}>{relativeTime(value.created)}</time></header><MarkdownBody body={value.body} /></article>)}
        {detail.commentsTruncated && <p className="jira-notice">Some comments are not loaded. Open Jira to read the full discussion.</p>}
        {detail.comments.length === 0 && !detail.commentsTruncated && <p className="jira-muted">No comments yet.</p>}
        <form onSubmit={event => { event.preventDefault(); const body = comment.trim(); if (!body) return; if (commentRequest.current?.body !== body) commentRequest.current = { body, id: crypto.randomUUID() }; const request = commentRequest.current; void mutate(() => window.bridge.commentJiraIssue(location, request.body, request.id), () => { setComment(''); commentRequest.current = null; }); }}>
          <label>Add a comment<textarea rows={4} value={comment} onChange={event => setComment(event.target.value)} placeholder="Write a reply…" /></label>
          <button type="submit" className="bq-button bq-button--primary" disabled={busy || !comment.trim()}>Send comment</button>
        </form>
      </section>
      </div><aside className="jira-detail__sidebar" aria-label="Ticket fields"><h3>Details</h3>
      <div className="jira-detail__fields"><span>Assignee <strong>{issue.assignee?.displayName ?? 'Unassigned'}</strong></span><span>Priority <strong>{issue.priority?.name ?? 'None'}</strong></span><span>Due date <strong>{issue.dueDate ?? 'None'}</strong></span>
        <div className="jira-labels">{issue.labels.length ? issue.labels.map(label => <span key={label}>{label}</span>) : <span>No labels</span>}</div>
        {!editing && <button type="button" className="bq-button" onClick={startEditing} disabled={loading || busy || detail.editableFields.length === 0}>Edit fields</button>}
      </div>
      {editing && <form className="jira-edit" onSubmit={event => { event.preventDefault(); save(); }}>
        {field('summary') && <label>Summary<input required value={summary} onChange={event => setSummary(event.target.value)} /></label>}
        {field('labels') && <label>Labels<input value={labels} onChange={event => setLabels(event.target.value)} placeholder="Comma-separated labels" /></label>}
        {field('assignee') && <label>Assignee<select value={assignee} onChange={event => setAssignee(event.target.value)}><option value="">Unassigned</option>{[...new Map([...(issue.assignee ? [issue.assignee] : []), ...detail.assignees].map(value => [value.accountId, value])).values()].map(value => <option key={value.accountId} value={value.accountId}>{value.displayName}</option>)}</select></label>}
        {field('priority') && <label>Priority<select value={priority} onChange={event => setPriority(event.target.value)}><option value="">None</option>{field('priority')?.allowedValues.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>}
        {field('duedate') && <label>Due date<input type="date" value={dueDate} onChange={event => setDueDate(event.target.value)} /></label>}
        <div className="jira-actions"><button className="bq-button bq-button--primary" type="submit" disabled={busy}>Save changes</button><button className="bq-button" type="button" disabled={busy} onClick={() => setEditing(false)}>Cancel</button></div>
      </form>}
      <div className="jira-edit"><label>Change status<select value={transitionId} disabled={busy || loading || detail.transitions.length === 0} onChange={event => { setTransitionId(event.target.value); setTransitionVersion(issue.updatedAt); setTransitionFields({}); }}><option value="">Choose transition…</option>{detail.transitions.map(value => <option key={value.id} value={value.id}>{value.name} → {value.to.name}</option>)}</select></label>
        {transition && <form onSubmit={event => { event.preventDefault(); void mutate(() => window.bridge.transitionJiraIssue(location, transition.id, transitionPayload(transition.fields, transitionFields), transitionVersion), () => { setTransitionId(''); setTransitionFields({}); }); }}>
          {transition.fields.some(value => value.required && !supportedTransitionField(value)) && <p className="jira-notice">This transition requires fields that must be completed in Jira. <button type="button" className="bq-button" onClick={() => { void window.bridge.openInAppBrowser(issue.url); }}>Open in Jira</button></p>}
          {transition.fields.filter(supportedTransitionField).map(value => <TransitionField key={value.id} field={value} value={transitionFields[value.id] ?? ''} onChange={text => setTransitionFields(current => ({ ...current, [value.id]: text }))} />)}
          <button type="submit" className="bq-button" disabled={busy || transition.fields.some(value => value.required && (!supportedTransitionField(value) || !transitionFields[value.id]))}>{transition.name}</button>
        </form>}
        {boardId !== null && <form onSubmit={event => { event.preventDefault(); void mutate(() => window.bridge.moveJiraIssue(location, moveTo === 'backlog' ? null : Number(moveTo), boardId, moveVersion), () => { setMoveTo(''); }); }}>
          <label>Move to sprint<select value={moveTo} disabled={busy || loading} onChange={event => { setMoveTo(event.target.value); setMoveVersion(issue.updatedAt); }}><option value="">Choose destination…</option><option value="backlog">Backlog</option>{sprints.map(value => <option value={value.id} key={value.id}>{value.name}</option>)}</select></label>
          {moveTo && <button type="submit" className="bq-button" disabled={busy}>Move ticket</button>}
        </form>}
      </div>
      </aside></div>
    </>}
  </dialog>;
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
  return <label>{field.name}{field.required ? ' *' : ''}{field.allowedValues.length > 0 ? <select required={field.required} value={value} onChange={event => onChange(event.target.value)}><option value="">Choose…</option>{field.allowedValues.map(option => <option key={option.id} value={option.id}>{option.name}</option>)}</select>
    : <input required={field.required} value={value} type={field.schemaType === 'date' ? 'date' : field.schemaType === 'number' ? 'number' : 'text'} step={field.schemaType === 'number' ? 'any' : undefined} onChange={event => onChange(event.target.value)} />}</label>;
}

function JiraWorkspaceDialog({ issue, onClose, onAssigned }: { issue: JiraIssue; onClose: () => void; onAssigned: (detail: JiraIssueDetail) => void }) {
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
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
    if (busy || !workspaceId) return;
    setBusy(true); setError(null);
    try { onAssigned(await window.bridge.assignJiraWorkspace(jiraIssueLocation(issue), workspaceId)); }
    catch (cause) { setError(message(cause)); setBusy(false); }
  };
  return <dialog ref={dialog} aria-label={`Assign workspace for ${issue.key}`} className="jira-launch jira-assignment" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Assign a workspace</h2><button type="button" className="bq-iconbtn" aria-label="Close workspace assignment" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>Choose the ByteQuay workspace for <strong>{issue.key}</strong>. This choice is saved for this ticket.</p>
    <form onSubmit={event => { event.preventDefault(); void assign(); }}>
      <label>Workspace<select required disabled={busy || loading} value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}><option value="" disabled>{loading ? 'Loading workspaces…' : 'Choose the repository for this ticket'}</option>{workspaces.map(value => <option value={value.id} key={value.id}>{value.name} · {value.monitoredOwner}/{value.monitoredRepo}</option>)}</select></label>
      {!loading && !error && workspaces.length === 0 && <p className="jira-muted">Watch a repository from Home to create a ByteQuay workspace, then try again.</p>}
      {error && <div className="ws-error" role="alert">{error}</div>}
      <footer className="jira-actions"><button type="button" className="bq-button" disabled={busy} onClick={onClose}>Cancel</button><button type="submit" className="bq-button bq-button--primary" disabled={busy || !workspaceId}>{busy ? 'Assigning…' : 'Assign workspace'}</button></footer>
    </form>
  </dialog>;
}

function JiraLaunch({ issue: initialIssue, onClose, onChanged }: { issue: JiraIssue; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void }) {
  const [issue, setIssue] = useState(initialIssue);
  return issue.workspace === null
    ? <JiraWorkspaceDialog issue={issue} onClose={onClose} onAssigned={detail => { setIssue(detail.issue); onChanged(detail); }} />
    : <JiraLaunchContent issue={issue} workspaceId={issue.workspace.id} onClose={onClose} onChanged={detail => { setIssue(detail.issue); onChanged(detail); }} />;
}

function JiraLaunchContent({ issue, workspaceId, onClose, onChanged }: { issue: JiraIssue; workspaceId: string; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void }) {
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialog = useJiraDialog();
  const refresh = async () => {
    setRefreshing(true); setError(null);
    try {
      const detail = await window.bridge.getJiraIssue(jiraIssueLocation(issue), true);
      setError(detail.error); onChanged(detail);
      if (detail.issue.task !== null) window.location.hash = workspaceAgentTaskRoute(detail.issue.task.workspaceId, detail.issue.task.id);
    }
    catch (cause) { setError(message(cause)); }
    finally { setRefreshing(false); }
  };
  return <dialog ref={dialog} aria-label={`Develop ${issue.key}`} className="jira-launch" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Develop {issue.key}</h2><span className="jira-spacer" /><button type="button" className="bq-iconbtn" aria-label="Refresh development source" title="Refresh ticket context" disabled={busy || refreshing} onClick={() => { void refresh(); }}><RefreshCw size={16} /></button><button type="button" className="bq-iconbtn" aria-label="Close development" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>{issue.title}</p><JiraWorkspaceBadge workspace={issue.workspace} />
    {error && <div className="ws-error" role="alert">{error}</div>}
    <JiraLaunchComposer key={workspaceId} issue={issue} workspaceId={workspaceId} refreshing={refreshing} onBusy={setBusy} />
  </dialog>;
}
function JiraLaunchComposer({ issue, workspaceId, onBusy, refreshing }: { issue: JiraIssue; workspaceId: string; onBusy: (busy: boolean) => void; refreshing: boolean }) {
  const onOpenTask = useCallback((id: string) => { window.location.hash = workspaceAgentTaskRoute(workspaceId, id); }, [workspaceId]);
  const creation = useAgentTaskCreation({ workspaceId, bridge, sourceJira: issue, onOpenTask, onOpenCreatedTask: task => { window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); } });
  useEffect(() => { onBusy(creation.busy); }, [creation.busy, onBusy]);
  return <>{creation.error && <div className="ws-error" role="alert">{creation.error}</div>}{creation.busy && creation.startup !== null
    ? <StartingAgentTask draft={creation.draft} images={creation.images} laneId={creation.laneId} providers={creation.catalog?.providers ?? []} models={creation.models} selection={creation.selection} startup={creation.startup} />
    : <TaskComposer {...creation.composer} blocker={refreshing ? 'Refreshing ticket context…' : creation.blocker} issueAttachment={creation.issueAttachment} onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspaceId); }} />}</>;
}
