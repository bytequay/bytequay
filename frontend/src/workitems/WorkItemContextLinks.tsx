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
import { Link2, X } from 'lucide-react';
import type { WorkItemDto, UnifiedWorkItem, WorkItemContextLink, WorkSource } from './contracts';
import { GOALS_CHANGED } from './changes';
import { zoomReference } from '../githubReference';

const SOURCE: Record<WorkSource, string> = { LOCAL: 'Local', GITHUB: 'GitHub', JIRA: 'Jira' };
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);

export default function WorkItemContextLinks({ work, catalog, onChanged }: {
  work: WorkItemDto; catalog: UnifiedWorkItem[]; onChanged: () => Promise<void>;
}) {
  const [links, setLinks] = useState<WorkItemContextLink[] | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [picking, setPicking] = useState(false);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    setLinks(null); setReadError(null);
    if (work.contextLinks.length === 0) setLinks([]);
    else void window.bridge.listWorkItemContextLinks(work.workspaceId, work.id)
      .then(values => { if (active) setLinks(values); })
      .catch(cause => { if (active) setReadError(errorText(cause)); });
    return () => { active = false; };
  }, [work.workspaceId, work.id, work.revision, work.contextLinks.length, catalog, retry]);

  const change = async (targetId: string, remove: boolean) => {
    setBusy(true); setError(null);
    try {
      const mutate = remove ? window.bridge.unlinkWorkItemContext : window.bridge.linkWorkItemContext;
      await mutate(work.workspaceId, work.id, work.revision, targetId);
      await onChanged();
      window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: work.workspaceId }));
    }
    catch (cause) { setError(errorText(cause)); }
    finally { setBusy(false); }
  };
  const open = (link: WorkItemContextLink) => {
    // A resolved alias is local even if the catalog was read before the target was imported or published.
    const local = catalog.find(item => item.id === link.id)?.work;
    if (local || link.source === 'LOCAL' || link.id !== link.targetId) zoomReference({ kind: 'work', workspaceId: work.workspaceId, itemId: local?.id ?? link.id });
    else if (link.reference?.kind === 'ISSUE') {
      const split = link.reference.key.lastIndexOf('#');
      zoomReference({ kind: 'issue', workspaceId: work.workspaceId, repo: link.reference.key.slice(0, split), number: Number(link.reference.key.slice(split + 1)) });
    }
    else if (link.reference?.kind === 'JIRA') {
      const [connectionId, cloudId, issueId] = link.reference.key.split(':');
      zoomReference({ kind: 'jira', connectionId, cloudId, issueId });
    }
  };
  const linked = new Set([...work.contextLinks.map(link => link.targetId), ...(links ?? []).map(link => link.id), work.id]);
  const search = query.trim().toLowerCase();
  const choices = catalog.filter(item => !linked.has(item.id)
    && `${item.title} ${item.reference?.label ?? ''} ${item.reference?.key ?? ''}`.toLowerCase().includes(search));
  return <section className="work-items__context-links" aria-label="Linked work">
    <header className="work-items__context-head">
      <strong>Linked work</strong>
      <button type="button" className="work-items__quiet" aria-expanded={picking} onClick={() => setPicking(!picking)}>
        {picking ? <X size={14} aria-hidden="true" /> : <Link2 size={14} aria-hidden="true" />}{picking ? 'Done linking' : 'Link work item'}
      </button>
    </header>
    {work.contextLinks.length === 0 && !picking && <p className="work-items__context-note">Link work that helps explain this request.</p>}
    {work.contextLinks.map(saved => {
      const link = links?.find(value => value.targetId === saved.targetId);
      return <div className="work-items__dev-row" key={saved.targetId}>
        <Link2 size={15} aria-hidden="true" />
        <span>{link?.available
          ? <button type="button" className="work-items__link" onClick={() => open(link)}>{link.title}</button>
          : <span>{link?.title ?? saved.title}</span>}
          {link?.reference && <span className="card-key">{link.reference.label}</span>}
          <small>{link ? `${SOURCE[link.source]} · ${link.status}` : readError ? 'Could not load linked work' : 'Loading…'}</small>
        </span>
        <button type="button" className="work-items__quiet" aria-label={`Unlink ${link?.title ?? saved.title}`} title="Unlink context" disabled={busy}
          onClick={() => void change(saved.targetId, true)}><X size={14} aria-hidden="true" /></button>
      </div>;
    })}
    {readError && <div className="work-items__context-note" role="alert">{readError} <button type="button" className="work-items__link" onClick={() => setRetry(value => value + 1)}>Retry</button></div>}
    {error && <div className="work-items__context-note" role="alert">{error}</div>}
    {picking && <div className="work-items__context-picker">
      <input type="search" autoFocus aria-label="Search work to link" placeholder="Search local work, GitHub, or Jira" value={query} onChange={event => setQuery(event.target.value)} />
      {choices.slice(0, 20).map(item => <div className="work-items__dev-row" key={item.id}>
        <Link2 size={15} aria-hidden="true" />
        <span>{item.title}{item.reference && <span className="card-key">{item.reference.label}</span>}<small>{SOURCE[item.source]} · {item.status}</small></span>
        <button type="button" className="work-items__plain" aria-label={`Link ${item.title}`} disabled={busy || links === null} onClick={() => void change(item.id, false)}>Link</button>
      </div>)}
      {choices.length === 0 && <p className="work-items__context-note">{search ? 'No matching work items.' : 'No other work items to link.'}</p>}
      {choices.length > 20 && <p className="work-items__context-note">Search to narrow {choices.length} work items.</p>}
    </div>}
  </section>;
}
