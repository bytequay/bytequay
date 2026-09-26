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

import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, CircleDot, ExternalLink, Flag, Columns3, List, LoaderCircle, MessageSquare, Play, RefreshCw, SquareCheck, X } from 'lucide-react';
import MarkdownBody from '../pulls/MarkdownBody';
import { relativeTime } from '../relativeTime';
import { TaskComposer, StartingAgentTask, useAgentTaskCreation } from '../agents/AgentTasksView';
import type { WorkspaceDto } from '../types';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { settingsRoute } from '../settings/routes';
import { jiraIssueLocation, jiraLocation, jiraRoute, notifyJiraChanged, type JiraBoard, type JiraConnection, type JiraField, type JiraIssue, type JiraIssueDetail, type JiraIssuePage, type JiraLocation, type JiraSprint } from './contracts';
import './jira.css';

const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const bridge = () => window.bridge;
const emptyPage: JiraIssuePage = { issues: [], fetchedAt: null, error: null, partial: false };

export function JiraIssueActions({ issue, busy = false, onGoal, onStart }: { issue: JiraIssue; busy?: boolean; onGoal: () => void; onStart: () => void }) {
  return <span className="jira-actions" role="group" aria-label={`Actions for ${issue.key}`}>
    <button className="bq-iconbtn" type="button" title="Open in Jira" aria-label={`Open ${issue.key} in Jira`}
      onClick={event => { event.stopPropagation(); void window.bridge.openInAppBrowser(issue.url); }}><ExternalLink size={15} /></button>
    {issue.task !== null ? <button className="bq-iconbtn" type="button" title="View task" aria-label={`View task for ${issue.key}`}
      onClick={event => { event.stopPropagation(); window.location.hash = workspaceAgentTaskRoute(issue.task?.workspaceId ?? '', issue.task?.id ?? ''); }}><SquareCheck size={15} /></button>
      : !issue.isGoal && !issue.hasChildren && issue.status.category !== 'done' && <button className="bq-iconbtn" type="button" title="Use agent to develop" aria-label={`Use agent to develop ${issue.key}`} disabled={busy}
        onClick={event => { event.stopPropagation(); onStart(); }}><Play size={15} /></button>}
    <button className={`bq-iconbtn${issue.isGoal ? ' jira-goal-active' : ''}`} type="button" title={issue.isGoal ? 'Unmark as Goal' : 'Mark as Goal'}
      aria-label={`${issue.isGoal ? 'Unmark' : 'Mark'} ${issue.key} as Goal`} aria-pressed={issue.isGoal} disabled={busy}
      onClick={event => { event.stopPropagation(); onGoal(); }}><Flag size={15} /></button>
  </span>;
}

export default function JiraScreen() {
  const initial = jiraLocation(window.location.hash);
  const [connections, setConnections] = useState<JiraConnection[]>([]);
  const [connectionId, setConnectionId] = useState(initial?.connectionId ?? '');
  const [cloudId, setCloudId] = useState(initial?.cloudId ?? '');
  const [boards, setBoards] = useState<JiraBoard[]>([]);
  const [boardId, setBoardId] = useState<number | null>(null);
  const [sprints, setSprints] = useState<JiraSprint[]>([]);
  const [sprintId, setSprintId] = useState<number | null>(null);
  const [mine, setMine] = useState(true);
  const [layout, setLayout] = useState<'list' | 'board'>('list');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState<JiraIssuePage>(emptyPage);
  const [selected, setSelected] = useState<JiraLocation | null>(initial);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [launch, setLaunch] = useState<JiraIssue | null>(null);
  const [settings, setSettings] = useState(false);

  const generation = useRef(0);
  const connection = connections.find(value => value.id === connectionId) ?? connections[0];
  const site = connection?.sites.find(value => value.id === cloudId) ?? connection?.sites[0];
  const actualConnectionId = connection?.id ?? '';
  const actualCloudId = site?.id ?? '';

  useEffect(() => {
    if (selected === null || new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('start') !== '1') return;
    let active = true;
    void window.bridge.getJiraIssue(selected, true).then(value => {
      if (!active) return;
      if (value.issue.task) window.location.hash = workspaceAgentTaskRoute(value.issue.task.workspaceId, value.issue.task.id);
      else if (!value.issue.isGoal && !value.issue.hasChildren && value.issue.status.category !== 'done') setLaunch(value.issue);
      else setError('Choose a leaf ticket to start development.');
    }).catch(cause => { if (active) setError(message(cause)); });
    return () => { active = false; };
  }, [selected]);

  const applyConnections = useCallback((values: JiraConnection[]) => { setConnections(values); setError(null); }, []);
  useEffect(() => {
    let active = true;
    void window.bridge.listJiraConnections().then(value => { if (active) applyConnections(value); }).catch(cause => { if (active) setError(message(cause)); });
    return () => { active = false; };
  }, [applyConnections]);
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
  }, [actualConnectionId, actualCloudId]);
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
    const scope = { connectionId: actualConnectionId, cloudId: actualCloudId, boardId, sprintId, mine };
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
  }, [actualConnectionId, actualCloudId, boardId, sprintId, mine]);
  useEffect(() => { setPage(emptyPage); void reload(); return () => { generation.current += 1; }; }, [reload]);
  const refreshSprint = async () => {
    if (boardId === null) return;
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
  const update = (detail: JiraIssueDetail) => {
    setPage(value => ({ ...value, issues: value.issues.map(issue => issue.id === detail.issue.id && issue.cloudId === detail.issue.cloudId ? detail.issue : issue) }));
    notifyJiraChanged();
    void reload();
  };
  const toggleGoal = async (issue: JiraIssue) => {
    try { update(await window.bridge.setJiraGoal(jiraIssueLocation(issue), !issue.isGoal)); }
    catch (cause) { setError(message(cause)); }
  };
  const open = (issue: JiraIssue) => { window.location.hash = jiraRoute(jiraIssueLocation(issue)); };
  const shown = page.issues.filter(issue => (!status || issue.status.id === status) && `${issue.key} ${issue.title} ${issue.labels.join(' ')}`.toLowerCase().includes(query.toLowerCase()));
  const statuses = [...new Map(page.issues.map(issue => [issue.status.id, issue.status])).values()];
  const card = (issue: JiraIssue) => <article key={issue.id} className={`jira-ticket${selected?.issueId === issue.id ? ' is-selected' : ''}`}>
    <button className="jira-ticket__title" type="button" onClick={() => open(issue)}><CircleDot size={15} /><strong>{issue.title}</strong></button>
    <div className="jira-ticket__meta"><span>{issue.key}</span><span>{issue.type}</span><span className={`jira-status is-${issue.status.category}`}>{issue.status.name}</span></div>
    {issue.parent && <button type="button" className="jira-parent" onClick={() => { window.location.hash = jiraRoute({ ...jiraIssueLocation(issue), issueId: issue.parent?.id ?? '' }); }}>{issue.parent.key} · {issue.parent.title}</button>}
    {issue.labels.length > 0 && <div className="jira-labels">{issue.labels.map(label => <span key={label}>{label}</span>)}</div>}
    <div className="jira-ticket__meta"><span>{issue.assignee?.displayName ?? 'Unassigned'}</span><span>·</span><time dateTime={issue.updatedAt}>{relativeTime(issue.updatedAt)}</time>
      <JiraIssueActions issue={issue} onGoal={() => { void toggleGoal(issue); }} onStart={() => setLaunch(issue)} /></div>
  </article>;
  return <div className="jira-screen">
    <header className="jira-header"><div><h1>My sprint</h1><p>Your Jira tickets, ready for development.</p></div><span className="jira-spacer" />
      <button type="button" className="bq-button" onClick={() => setSettings(value => !value)}>Connections</button>
      <button type="button" className="bq-iconbtn" aria-label="Refresh sprint" title="Refresh sprint" disabled={loading || boardId === null} onClick={() => { void refreshSprint(); }}><RefreshCw size={17} className={loading ? 'jira-spin' : ''} /></button>
    </header>
    {(settings || connections.length === 0 || !site) && <JiraConnections connections={connections} onChanged={applyConnections} />}
    {error && <div className="ws-error" role="alert">{error}</div>}
    {site && <><div className="jira-toolbar">
      <select aria-label="Jira site" value={`${actualConnectionId}/${actualCloudId}`} onChange={event => { const [c, s] = event.target.value.split('/'); setConnectionId(c); setCloudId(s); setSelected(null); setLaunch(null); window.location.hash = jiraRoute(); }}>
        {connections.flatMap(value => value.sites.map(item => <option key={`${value.id}/${item.id}`} value={`${value.id}/${item.id}`}>{item.name}</option>))}</select>
      <select aria-label="Jira board" value={boardId ?? ''} onChange={event => { setBoardId(Number(event.target.value)); setStatus(''); try { localStorage.setItem(`bytequay:jira-board:${actualConnectionId}/${actualCloudId}`, event.target.value); } catch { /* Optional view preference. */ } }}><option value="" disabled>Select board</option>{boards.map(board => <option value={board.id} key={board.id}>{board.name}</option>)}</select>
      <select aria-label="Sprint" value={sprintId ?? ''} onChange={event => setSprintId(event.target.value === '' ? null : Number(event.target.value))}><option value="">Backlog</option>{sprints.map(sprint => <option value={sprint.id} key={sprint.id}>{sprint.name}{sprint.state === 'active' ? ' · Active' : ''}</option>)}</select>
      <label className="jira-mine"><input type="checkbox" checked={mine} onChange={event => setMine(event.target.checked)} />Assigned to me</label>
      <span className="jira-spacer" /><div className="jira-layout" role="group" aria-label="Ticket layout"><button className="bq-iconbtn" type="button" aria-label="List view" aria-pressed={layout === 'list'} onClick={() => setLayout('list')}><List size={16} /></button><button className="bq-iconbtn" type="button" aria-label="Board view" aria-pressed={layout === 'board'} onClick={() => setLayout('board')}><Columns3 size={16} /></button></div>
    </div><div className="jira-toolbar jira-toolbar--secondary"><input type="search" aria-label="Search tickets" placeholder="Search tickets" value={query} onChange={event => setQuery(event.target.value)} />
      <select aria-label="Filter status" value={status} onChange={event => setStatus(event.target.value)}><option value="">All statuses</option>{statuses.map(value => <option value={value.id} key={value.id}>{value.name}</option>)}</select>
      <span className="jira-muted">{shown.length} tickets{page.fetchedAt && ` · Updated ${relativeTime(page.fetchedAt)}`}</span>
    </div>
    {page.partial && <div className="jira-notice">Only part of this sprint is loaded. Open Jira to see all tickets.</div>}
    <div className={`jira-content${selected !== null ? ' has-detail' : ''}`}><div className={`jira-items is-${layout}`} aria-busy={loading}>
      {layout === 'board' ? statuses.filter(value => !status || status === value.id).map(value => <section className="jira-column" key={value.id}><h2>{value.name}<span>{shown.filter(issue => issue.status.id === value.id).length}</span></h2>{shown.filter(issue => issue.status.id === value.id).map(card)}</section>) : shown.map(card)}
      {shown.length === 0 && <div className="jira-empty">{loading ? <><LoaderCircle size={18} className="jira-spin" /> Loading tickets…</> : page.fetchedAt === null ? 'Select a board and sprint to load tickets.' : 'No tickets match this view.'}</div>}
    </div>{selected && <JiraDetail key={`${selected.connectionId}/${selected.cloudId}/${selected.issueId}`} location={selected} boardId={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? boardId : null} sprints={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? sprints : []} onClose={() => { window.location.hash = jiraRoute(); }} onChanged={update} onStart={setLaunch} />}</div></>}
    {!site && selected && <JiraDetail key={`${selected.connectionId}/${selected.cloudId}/${selected.issueId}`} location={selected} boardId={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? boardId : null} sprints={selected.connectionId === actualConnectionId && selected.cloudId === actualCloudId ? sprints : []} onClose={() => { window.location.hash = jiraRoute(); }} onChanged={update} onStart={setLaunch} />}
    {launch && <JiraLaunch issue={launch} onClose={() => setLaunch(null)} />}
  </div>;
}

function JiraConnections({ connections, onChanged }: { connections: JiraConnection[]; onChanged: (values: JiraConnection[]) => void }) {
  const [brokerUrl, setBrokerUrl] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const act = async (action: () => Promise<unknown>) => { setBusy(true); setError(null); try { await action(); onChanged(await window.bridge.listJiraConnections()); } catch (cause) { setError(message(cause)); } finally { setBusy(false); } };
  return <section className="jira-connect"><h2>Connect Jira</h2><p>Sign in with Atlassian through your ByteQuay authentication service. Your browser session and API connection are separate.</p>
    {connections.map(connection => <div className="jira-connection" key={connection.id}><strong>{connection.sites.map(site => site.name).join(', ') || 'Connection awaiting sign-in'}</strong><span>{connection.state.toLowerCase()}</span>
      {connection.pairingCode && <div>Enter this pairing code on the sign-in page: <strong className="jira-pairing-code">{connection.pairingCode}</strong></div>}
      {connection.authorizationUrl && <button className="bq-button" type="button" disabled={busy} onClick={() => { void window.bridge.openExternal(connection.authorizationUrl ?? '').catch(cause => setError(message(cause))); }}>Open sign-in page <ExternalLink size={14} /></button>}
      <button className="bq-button" type="button" disabled={busy} onClick={() => { void act(() => window.bridge.refreshJiraConnection(connection.id)); }}>Check connection</button>
      <button className="bq-button" type="button" disabled={busy} onClick={() => { void act(() => window.bridge.disconnectJira(connection.id)); }}>Disconnect</button>
      {connection.error && <div role="alert" className="ws-error">{connection.error}</div>}
    </div>)}
    <form onSubmit={event => { event.preventDefault(); void act(async () => { const connection = await window.bridge.connectJira(brokerUrl.trim()); if (connection.authorizationUrl) await window.bridge.openExternal(connection.authorizationUrl); }); }}>
      <label>Authentication service URL<input type="url" required placeholder="https://jira-auth.your-company.com" value={brokerUrl} onChange={event => setBrokerUrl(event.target.value)} /></label>
      <button className="bq-button bq-button--primary" disabled={busy || !brokerUrl.trim()} type="submit">Connect Jira</button>
    </form>{error && <div className="ws-error" role="alert">{error}</div>}
  </section>;
}

function JiraDetail({ location, sprints, boardId, onClose, onChanged, onStart }: { location: JiraLocation; sprints: JiraSprint[]; boardId: number | null; onClose: () => void; onChanged: (detail: JiraIssueDetail) => void; onStart: (issue: JiraIssue) => void }) {
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
      void window.bridge.getJiraIssue(location, true).then(value => {
        if (!active) return;
        setDetail(value); onChangedRef.current(value);
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
  return <aside className="jira-detail" aria-label="Ticket details"><div className="jira-detail__toolbar"><button type="button" className="bq-iconbtn" aria-label="Close ticket" onClick={onClose}><ArrowLeft size={18} /></button><span className="jira-spacer" />
    <button type="button" className="bq-iconbtn" aria-label="Refresh ticket" title="Refresh ticket" disabled={busy || loading} onClick={() => { void mutate(() => window.bridge.getJiraIssue(location, true)); }}><RefreshCw size={16} /></button></div>
    {error && <div className="ws-error" role="alert">{error}</div>}
    {!issue || !detail ? <div className="jira-empty">{loading ? 'Loading ticket…' : 'This ticket could not be loaded.'}</div> : <>
      <p className="jira-muted">{issue.key} · {issue.type}</p><h2>{issue.title}</h2>
      <div className="jira-ticket__meta"><span className={`jira-status is-${issue.status.category}`}>{issue.status.name}</span><span>Updated {relativeTime(issue.updatedAt)}</span>
        <JiraIssueActions issue={issue} busy={busy || loading} onGoal={() => { void mutate(() => window.bridge.setJiraGoal(location, !issue.isGoal)); }} onStart={() => onStart(issue)} /></div>
      {issue.hasChildren && <p className="jira-notice">This ticket has children. Start development from an individual child ticket.</p>}
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
    </>}
  </aside>;
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

function JiraLaunch({ issue: initialIssue, onClose }: { issue: JiraIssue; onClose: () => void }) {
  const [issue, setIssue] = useState(initialIssue);
  const [refreshing, setRefreshing] = useState(false);
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { dialog.current?.showModal(); }, []);
  useEffect(() => { let active = true; void window.bridge.listWorkspaces().then(value => { if (active) setWorkspaces(value); }).catch(cause => { if (active) setError(message(cause)); }); return () => { active = false; }; }, []);
  const refresh = async () => {
    setRefreshing(true); setError(null);
    try {
      const detail = await window.bridge.getJiraIssue(jiraIssueLocation(issue), true);
      setIssue(detail.issue); setError(detail.error);
      if (detail.issue.task !== null) window.location.hash = workspaceAgentTaskRoute(detail.issue.task.workspaceId, detail.issue.task.id);
    }
    catch (cause) { setError(message(cause)); }
    finally { setRefreshing(false); }
  };
  return <dialog ref={dialog} aria-label={`Develop ${issue.key}`} className="jira-launch" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header><h2>Develop {issue.key}</h2><span className="jira-spacer" /><button type="button" className="bq-iconbtn" aria-label="Refresh development source" title="Refresh ticket context" disabled={busy || refreshing} onClick={() => { void refresh(); }}><RefreshCw size={16} /></button><button type="button" className="bq-iconbtn" aria-label="Close development" disabled={busy} onClick={onClose}><X size={18} /></button></header>
    <p>{issue.title}</p><label>Workspace<select disabled={busy} value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}><option value="" disabled>Choose the repository for this ticket</option>{workspaces.map(value => <option value={value.id} key={value.id}>{value.name} · {value.monitoredOwner}/{value.monitoredRepo}</option>)}</select></label>
    {error && <div className="ws-error" role="alert">{error}</div>}
    {workspaceId && <JiraLaunchComposer key={workspaceId} issue={issue} workspaceId={workspaceId} refreshing={refreshing} onBusy={setBusy} />}
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
