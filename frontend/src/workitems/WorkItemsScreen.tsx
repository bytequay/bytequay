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

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { LockKeyhole, Plus, RefreshCw, X } from 'lucide-react';
import type { WorkspaceDto } from '../types';
import type { WorkItemDto } from '../goals/contracts';
import { zoomReference } from '../githubReference';
import JiraScreen from '../jira/JiraScreen';
import type { JiraConnection } from '../jira/contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import { startEpicWork } from '../workspaces/epics';
import { workspaceAgentTaskRoute, workspaceIssueTaskRoute, workspaceRoute } from '../workspaces/model';
import { type Publication, type UnifiedWorkItem, type WorkSource, workItemRoute } from './contracts';
import './workitems.css';

const SOURCE: Record<WorkSource, string> = { LOCAL: 'Local', GITHUB: 'GitHub', JIRA: 'Jira' };
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
const selectedId = () => typeof window === 'undefined' ? null : new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('work');

const TABS = [['github', 'GitHub issues'], ['jira', 'Jira'], ['local', 'Local']] as const;

export default function WorkItemsScreen({ workspace, tab, github }: { workspace: WorkspaceDto; tab: 'issues' | 'jira'; github: ReactNode }) {
  const [hash, setHash] = useState(() => typeof window === 'undefined' ? '' : window.location.hash);
  useEffect(() => {
    const changed = () => setHash(window.location.hash);
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, []);
  const query = new URLSearchParams(hash.split('?')[1]);
  const active = hash ? hash.split('?')[0].endsWith('/jira') ? 'jira' : query.get('source') === 'local' || query.has('work') ? 'local' : 'github'
    : tab === 'jira' ? 'jira' : 'github';
  const select = (source: typeof TABS[number][0]) => {
    window.location.hash = source === 'local' ? workItemRoute(workspace.id) : workspaceRoute(workspace.id, source === 'jira' ? 'jira' : 'issues');
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
        }}>{label}</button>)}
    </div>
    <div className="work-sources__panel" id={`work-panel-${active}`} role="tabpanel" aria-labelledby={`work-source-${active}`}>
      {active === 'github' ? github : active === 'jira' ? <JiraScreen workspace={workspace} /> : <LocalWorkItems key={workspace.id} workspace={workspace} />}
    </div>
  </div>;
}

function LocalWorkItems({ workspace }: { workspace: WorkspaceDto }) {
  const [items, setItems] = useState<UnifiedWorkItem[]>([]);
  const [selected, setSelected] = useState<string | null>(selectedId);
  const [query, setQuery] = useState('');
  const [closed, setClosed] = useState(false);
  const [creating, setCreating] = useState(() => selectedId() === 'new');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(async () => setItems(await window.bridge.listUnifiedWorkItems(workspace.id)), [workspace.id]);
  useEffect(() => {
    let active = true;
    void window.bridge.listUnifiedWorkItems(workspace.id).then(value => { if (active) setItems(value); }).catch(cause => { if (active) setError(errorText(cause)); });
    return () => { active = false; };
  }, [workspace.id]);
  useEffect(() => {
    const changed = () => { setSelected(selectedId()); setCreating(selectedId() === 'new'); };
    window.addEventListener('hashchange', changed);
    return () => window.removeEventListener('hashchange', changed);
  }, []);
  const select = (id: string | null) => { setSelected(id); setCreating(id === 'new'); window.location.hash = workItemRoute(workspace.id, id ?? undefined); };
  const refresh = async () => {
    setBusy(true); setError(null);
    try { await reload(); }
    catch (cause) { setError(errorText(cause)); }
    finally { setBusy(false); }
  };
  const item = items.find(value => value.id === selected);
  const visible = items.filter(value => value.source === 'LOCAL' && (closed || !value.closed)
    && `${value.title} ${value.reference?.label ?? ''} ${value.description}`.toLowerCase().includes(query.toLowerCase()));
  return <div className="work-items">
    <header><div><h1>Local work</h1><p>Private on this Mac until you choose to publish.</p></div><div className="work-items__actions">
      <button className="bq-iconbtn" aria-label="Refresh work items" disabled={busy} onClick={() => void refresh()}><RefreshCw size={16} /></button>
      <button className="bq-button bq-button--primary" onClick={() => select('new')}><Plus size={14} />New work item</button></div></header>
    {error && <div className="ws-error" role="alert">{error}</div>}
    <div className="work-items__filters"><input type="search" aria-label="Search work items" placeholder="Search work items…" value={query} onChange={event => setQuery(event.target.value)} />
      <label><input type="checkbox" checked={closed} onChange={event => setClosed(event.target.checked)} />Include closed</label></div>
    <div className="work-items__panes"><section className="work-items__list" aria-label="Work items">
      {visible.map(value => <button key={value.id} className={`work-items__row${selected === value.id ? ' is-selected' : ''}`} onClick={() => select(value.id)} aria-pressed={selected === value.id}>
        <LockKeyhole size={16} /><span><strong>{value.title}</strong><small>{SOURCE[value.source]} · {value.reference?.label ?? 'Only on this Mac'} · {value.status.toLowerCase().replaceAll('_', ' ')}</small></span></button>)}
      {visible.length === 0 && <p className="work-items__empty">No matching local work items.</p>}
    </section>
    {creating ? <section className="work-items__detail"><WorkItemForm workspaceId={workspace.id} onClose={() => select(null)} onSaved={async value => { await reload(); select(value.id); }} /></section>
      : item ? <WorkItemDetail key={item.id} workspace={workspace} item={item} onClose={() => select(null)} onChanged={reload} />
        : <section className="work-items__empty">Select an item to read its context or start development.</section>}
    </div>
  </div>;
}

function WorkItemForm({ workspaceId, item, onClose, onSaved }: { workspaceId: string; item?: WorkItemDto; onClose: () => void; onSaved: (value: WorkItemDto) => Promise<void> }) {
  const [id] = useState(() => item?.id ?? crypto.randomUUID());
  const [title, setTitle] = useState(item?.title ?? '');
  const [body, setBody] = useState(item?.acceptanceCriteria ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    setBusy(true); setError(null);
    try {
      const value = item ? await window.bridge.decideWorkItem(workspaceId, id, { operationId: crypto.randomUUID(), expectedRevision: item.revision, action: 'EDIT', title: title.trim(), acceptanceCriteria: body, detail: '' })
        : await window.bridge.createWorkItem(workspaceId, { id, goalId: null, title: title.trim(), kind: 'DEVELOP', acceptanceCriteria: body, completionRule: 'MERGED_DELIVERY' });
      await onSaved(value);
    }
    catch (cause) { setError(errorText(cause)); }
    finally { setBusy(false); }
  };
  return <form className="work-items__form" onSubmit={event => { event.preventDefault(); void save(); }}><h2>{item ? 'Edit local work' : 'New local work item'}</h2>
    <p><LockKeyhole size={13} />{item ? 'Changes to this context are saved only on this Mac.' : 'Only on this Mac until you choose Publish.'}</p>
    <label>Title<input autoFocus required maxLength={500} value={title} onChange={event => setTitle(event.target.value)} /></label>
    <label>Description and acceptance criteria<textarea rows={14} maxLength={65536} value={body} onChange={event => setBody(event.target.value)} placeholder="What needs to change, why, and how to know it is done. Include the context an agent will need." /></label>
    {error && <div className="ws-error" role="alert">{error}</div>}<footer><button type="button" className="bq-button" disabled={busy} onClick={onClose}>Cancel</button><button className="bq-button bq-button--primary" disabled={busy || !title.trim()}>{busy ? 'Saving…' : 'Save locally'}</button></footer>
  </form>;
}

function WorkItemDetail({ workspace, item, onClose, onChanged }: { workspace: WorkspaceDto; item: UnifiedWorkItem; onClose: () => void; onChanged: () => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const work = item.work;
  const concluded = work !== null && ['DONE', 'DROPPED'].includes(work.state);
  const openSource = (start = false) => {
    const ref = item.reference;
    if (ref?.kind === 'JIRA') {
      const [connectionId, cloudId, issueId] = ref.key.split(':');
      zoomReference({ kind: 'jira', connectionId, cloudId, issueId, ...(start ? { start: true } : {}) });
    }
    else if (ref?.kind === 'ISSUE') {
      const number = Number(ref.key.slice(ref.key.lastIndexOf('#') + 1));
      if (start) window.location.hash = workspaceIssueTaskRoute(workspace.id, number);
      else zoomReference({ kind: 'issue', repo: repository, number, workspaceId: workspace.id });
    }
  };
  const begin = () => {
    if (!work || item.source !== 'LOCAL') openSource(true);
    else startEpicWork({ workspaceId: workspace.id, itemId: work.id, draft: `Develop this work item: ${work.title}\n\nRead its full context using work_items READ before starting.` });
  };
  const decide = async (action: 'DROP' | 'REOPEN') => {
    if (!work) return;
    setBusy(true); setError(null);
    try { await window.bridge.decideWorkItem(workspace.id, work.id, { operationId: crypto.randomUUID(), expectedRevision: work.revision, action, detail: action === 'DROP' ? 'Closed by the user' : 'Reopened by the user' }); await onChanged(); }
    catch (cause) { setError(errorText(cause)); }
    finally { setBusy(false); }
  };
  if (editing && work) return <section className="work-items__detail"><WorkItemForm workspaceId={workspace.id} item={work} onClose={() => setEditing(false)} onSaved={async () => { await onChanged(); setEditing(false); }} /></section>;
  return <section className="work-items__detail" aria-label="Work item details"><div className="work-items__actions"><span className="work-items__badge">{SOURCE[item.source]}</span><span>{item.status.toLowerCase().replaceAll('_', ' ')}</span><button className="bq-iconbtn" aria-label="Close work item" onClick={onClose}><X size={16} /></button></div>
    <h2>{item.title}</h2><div className="work-items__actions">
      {!item.closed && !concluded && <button className="bq-button bq-button--primary" onClick={begin}>Start development</button>}
      {item.reference && <button className="bq-button" onClick={() => openSource()}>Open {SOURCE[item.source]} details</button>}
      {work && !concluded && <button className="bq-button" onClick={() => setEditing(true)}>Edit local context</button>}
      {work && <button className="bq-button" onClick={() => setPublishing(value => !value)}>Publish…</button>}
      {work && (item.source === 'LOCAL' || concluded) && <button className="bq-button" disabled={busy} onClick={() => void decide(concluded ? 'REOPEN' : 'DROP')}>{concluded ? 'Reopen local work' : 'Close locally'}</button>}
    </div>
    {error && <div className="ws-error" role="alert">{error}</div>}
    {publishing && work && <PublishWorkItem workspace={workspace} item={work} onChanged={onChanged} onClose={() => setPublishing(false)} />}
    {item.source === 'LOCAL' && <p className="work-items__privacy"><LockKeyhole size={13} />Private local work. Development can deliver a pull request without creating an issue or ticket.</p>}
    <MarkdownBody body={item.description || 'No description yet.'} />
    {work && item.source !== 'LOCAL' && work.acceptanceCriteria && work.acceptanceCriteria !== item.description && <><h3>Local context and acceptance criteria</h3><MarkdownBody body={work.acceptanceCriteria} /></>}
    {work?.epicId && <p>Epic: <code>{work.epicId}</code></p>}
    {!!work?.links.length && <><h3>Sources and development</h3>{work.links.map(link => <button className="work-items__link" key={`${link.kind}:${link.key}`} onClick={() => {
      if (link.kind === 'TASK') window.location.hash = workspaceAgentTaskRoute(workspace.id, link.key);
      else if (link.url) void window.bridge.openInAppBrowser(link.url);
    }}>{link.label} · {link.title} · {link.status}</button>)}</>}
    {!!work?.history.length && <details><summary>History · {work.history.length}</summary>{work.history.map(event => <div key={event.operationId}><strong>{event.action.toLowerCase().replaceAll('_', ' ')}</strong><pre>{event.detail}</pre></div>)}</details>}
  </section>;
}

function PublishWorkItem({ workspace, item, onChanged, onClose }: { workspace: WorkspaceDto; item: WorkItemDto; onChanged: () => Promise<void>; onClose: () => void }) {
  const [source, setSource] = useState<'GITHUB' | 'JIRA'>('GITHUB');
  const [connections, setConnections] = useState<JiraConnection[]>([]);
  const [site, setSite] = useState('');
  const [project, setProject] = useState('');
  const [types, setTypes] = useState<{ id: string; name: string }[]>([]);
  const [type, setType] = useState('');
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { void window.bridge.listJiraConnections().then(setConnections).catch(cause => setError(errorText(cause))); }, []);
  const [connectionId, cloudId] = site.split('/');
  const pending = item.history.some(event => event.action === 'PUBLISH_STARTED' && !item.history.some(done => done.operationId === `${event.operationId}:settled`));
  const already = item.links.some(link => link.kind === (source === 'GITHUB' ? 'ISSUE' : 'JIRA'));
  const destination: Publication = { source, expectedRevision: item.revision, ...(source === 'JIRA' ? { connectionId, cloudId, project, issueTypeId: type } : {}) };
  const loadTypes = async () => {
    setBusy(true); setError(null);
    try { const values = await window.bridge.workItemJiraTypes(workspace.id, connectionId, cloudId, project); setTypes(values); setType(values[0]?.id ?? ''); }
    catch (cause) { setError(errorText(cause)); }
    finally { setBusy(false); }
  };
  const publish = async (reconcile: boolean) => {
    setBusy(true); setError(null);
    try {
      if (reconcile) await window.bridge.reconcileWorkItem(workspace.id, item.id, destination, key.trim());
      else await window.bridge.publishWorkItem(workspace.id, item.id, destination);
      await onChanged(); onClose();
    }
    catch (cause) { setError(errorText(cause)); await onChanged().catch(() => {}); }
    finally { setBusy(false); }
  };
  return <div className="work-items__publish"><h3>Publish this work item</h3>
    <p>The title and local description below will become visible to people with access to the destination. Task conversations and local history stay here.</p>
    <label>Destination<select value={source} disabled={busy} onChange={event => setSource(event.target.value as 'GITHUB' | 'JIRA')}><option value="GITHUB">GitHub · {workspace.monitoredOwner}/{workspace.monitoredRepo}</option><option value="JIRA">Jira</option></select></label>
    {source === 'JIRA' && <><label>Site<select value={site} disabled={busy} onChange={event => { setSite(event.target.value); setTypes([]); setType(''); }}><option value="">Choose a connected Jira site</option>{connections.filter(value => value.state === 'connected').flatMap(connection => connection.sites.map(value => <option key={`${connection.id}/${value.id}`} value={`${connection.id}/${value.id}`}>{value.name} · {connection.account?.displayName}</option>))}</select></label>
      <label>Project key<input value={project} disabled={busy} placeholder="ENG" onChange={event => { setProject(event.target.value.toUpperCase()); setTypes([]); setType(''); }} /></label>
      <button className="bq-button" disabled={busy || !site || !project} onClick={() => void loadTypes()}>Load work types</button>
      {types.length > 0 && <label>Work type<select value={type} disabled={busy} onChange={event => setType(event.target.value)}>{types.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>}</>}
    <details open><summary>Publication preview</summary><strong>{item.title}</strong><MarkdownBody body={item.acceptanceCriteria || 'No description.'} /></details>
    {already && <p>This item already has a {SOURCE[source]} source.</p>}
    {pending && <p role="alert">The previous publication may have succeeded. Find the existing issue or ticket and link it below; ByteQuay will not create another copy.</p>}
    {error && <div className="ws-error" role="alert">{error}</div>}
    <div className="work-items__actions"><button className="bq-button" disabled={busy} onClick={onClose}>Cancel</button><button className="bq-button bq-button--primary" disabled={busy || already || pending || (source === 'JIRA' && !type)} onClick={() => void publish(false)}>{busy ? 'Working…' : `Publish to ${SOURCE[source]}`}</button></div>
    <details><summary>Link an existing issue or ticket</summary><label>{source === 'GITHUB' ? 'Issue number in this repository' : 'Ticket key on the selected site'}<input value={key} onChange={event => setKey(event.target.value)} /></label>
      <button className="bq-button" disabled={busy || already || !key.trim() || (source === 'JIRA' && !site)} onClick={() => void publish(true)}>Link existing</button></details>
  </div>;
}
