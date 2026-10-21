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
import { useId, useState, useEffect } from 'react';
import { ChevronRight, Pencil, Trash2 } from 'lucide-react';
import type { RelatedWorkspaceDto, RepoLineNodeDto, WorkspaceDto, WorkspaceRelationshipDto, WorkspaceRelationshipsDto } from '../types';
import { Badge, Segmented, SelectPill, SettingsCard } from '../settings/primitives';
import RepoAvatar from '../RepoAvatar';
import { relatedWorkspaceRoute, workspaceHasReadableCode, type WorkspaceTab } from './model';
import '../css/related-workspaces.css';

const changed = 'bytequay:relationships-changed';

export function useRelationships(workspaceId: string) {
  const [data, setData] = useState<WorkspaceRelationshipsDto & { workspaces: WorkspaceDto[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    let request = 0;
    setData(null);
    setError(null);
    const refresh = () => {
      const current = ++request;
      Promise.resolve().then(() => Promise.all([window.bridge.getWorkspaceRelationships(workspaceId), window.bridge.listWorkspaces()]))
        .then(([relationships, workspaces]) => { if (active && current === request) { setData({ ...relationships, workspaces }); setError(null); } })
        .catch(failure => { if (active && current === request) setError(String(failure)); });
    };
    refresh();
    window.addEventListener(changed, refresh);
    return () => { active = false; window.removeEventListener(changed, refresh); };
  }, [workspaceId]);
  return { data, error };
}

export function unavailableReason(link: { unavailableReason?: string | null }, workspace?: WorkspaceDto) {
  return link.unavailableReason ?? (!workspace ? 'Workspace unavailable' : !workspaceHasReadableCode(workspace) ? 'Workspace code is not ready' : null);
}

export const positionLabel: Record<RelatedWorkspaceDto['position'], string> = {
  UPSTREAM: 'Upstream', DOWNSTREAM: 'Downstream', DEPENDENCY: 'Dependency', DEPENDENT: 'Dependent',
};

export function workspaceName(workspaces: WorkspaceDto[], id: string) {
  return workspaces.find(item => item.id === id)?.name ?? id;
}

/** True when the node continues from the one before it; a downstream branch instead names its parent. */
export function continuesLine(line: RepoLineNodeDto[], index: number) {
  return index > 0 && line[index].parentWorkspaceId === line[index - 1].workspaceId;
}

/** The repo line oldest upstream first, with the current workspace marked. */
export function RepoLine({ line, workspaces, current }: { line: RepoLineNodeDto[]; workspaces: WorkspaceDto[]; current: string }) {
  return <ol className="related-line" aria-label="Repo line">
    {line.map((node, index) => <li key={node.workspaceId} className={continuesLine(line, index) ? 'is-joined' : undefined}>
      {index > 0 && !continuesLine(line, index) && node.parentWorkspaceId !== null
        && <span className="related-line-branch">{workspaceName(workspaces, node.parentWorkspaceId)} →</span>}
      <span className="related-line-repo" aria-current={node.workspaceId === current ? 'true' : undefined}>{workspaceName(workspaces, node.workspaceId)}</span>
    </li>)}
  </ol>;
}

function ViewLinks({ workspaceId, relatedWorkspaceId, unavailable, activeTab }: {
  workspaceId: string; relatedWorkspaceId: string; unavailable: string | null; activeTab?: WorkspaceTab;
}) {
  return <span className="related-links">{(['commits', 'context'] as const).map(view => unavailable
    ? <span key={view} role="link" aria-disabled="true" title={unavailable}>{view === 'commits' ? 'Commits' : 'Memory'}</span>
    : <a key={view} href={relatedWorkspaceRoute(workspaceId, relatedWorkspaceId, view)} aria-current={activeTab === view ? 'page' : undefined}>
      {view === 'commits' ? 'Commits' : 'Memory'}</a>)}</span>;
}

export function RelatedWorkspaceNavigation({ workspaceId, activeWorkspaceId, activeTab }: {
  workspaceId: string; activeWorkspaceId?: string | null; activeTab?: WorkspaceTab;
}) {
  const { data, error } = useRelationships(workspaceId);
  if (error !== null) return <div className="related-nav" role="status">Related workspaces unavailable</div>;
  if (!data?.related.length) return null;
  return <nav className="related-nav" aria-label="Related workspaces">
    <div className="related-heading">Related</div>
    {data.related.map(link => {
      const workspace = data.workspaces.find(item => item.id === link.workspaceId);
      const unavailable = unavailableReason(link, workspace);
      const guidance = data.links.find(item => item.workspaceId === link.workspaceId)?.guidance;
      return <details className={`related-nav-row${unavailable ? ' is-unavailable' : ''}`} key={link.workspaceId}>
        <summary className="related-nav-title" title={unavailable ?? guidance}>
          <ChevronRight className="related-chevron" size={12} aria-hidden="true" />
          {workspace && <RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={16} radius={4} />}
          <span className="related-name">{workspace?.name ?? link.workspaceId}</span>
          <span className="related-kind">{unavailable ? 'Unavailable' : positionLabel[link.position]}</span>
        </summary>
        <ViewLinks workspaceId={workspaceId} relatedWorkspaceId={link.workspaceId} unavailable={unavailable}
          activeTab={activeWorkspaceId === link.workspaceId ? activeTab : undefined} />
      </details>;
    })}
  </nav>;
}

type Draft = Omit<WorkspaceRelationshipDto, 'kind'> & { kind: WorkspaceRelationshipDto['kind'] | '' };

export function RelatedWorkspaceSettings({ workspace }: { workspace: WorkspaceDto }) {
  const { data, error: loadError } = useRelationships(workspace.id);
  const [editing, setEditing] = useState<Draft | null>(null);
  const [existing, setExisting] = useState(false);
  const { busy, error, setError, run } = useAsyncAction();
  const guidanceId = useId();
  const apply = (operation: () => Promise<WorkspaceRelationshipDto[]>) => run(async () => {
    await operation();
    setEditing(null);
    window.dispatchEvent(new Event(changed));
  });
  const addButton = <button type="button" className="setw-btn" disabled={data === null || busy}
    onClick={() => { setEditing({ workspaceId: '', kind: '', guidance: '', backportByDefault: false }); setExisting(false); setError(null); }}>+ Add related workspace</button>;
  // Links other workspaces saved to this one; deeper downstream repos only show in the line.
  const linkedHere = data?.related.filter(item => (item.position === 'DOWNSTREAM' || item.position === 'DEPENDENT')
    && (data.line.find(node => node.workspaceId === item.workspaceId)?.parentWorkspaceId ?? workspace.id) === workspace.id) ?? [];
  return <div className="related-settings">
    <SettingsCard footer={<div className="setw-foot-row"><span>Links are saved here and also show in the other workspace. A Fork lets Commits read and cherry-pick from it. PR routing doesn’t change.</span></div>}>
      <div className="related-card-heading"><strong>Related workspaces</strong>
        {!!data?.links.length && <Badge>{data.links.length}</Badge>}
        {!!(data?.links.length || linkedHere.length) && editing === null && addButton}
      </div>
      {data !== null && data.line.length > 1 && <RepoLine line={data.line} workspaces={data.workspaces} current={workspace.id} />}
      {(loadError ?? error) !== null && <div className="setw-error" role="alert">{loadError ?? error}</div>}
      {data === null && loadError === null && <div className="setw-row-empty">Loading related workspaces…</div>}
      {data?.links.length === 0 && linkedHere.length === 0 && editing === null && <div className="related-empty">
        <strong>No related workspaces</strong><p>Add a Fork or Dependency so agents in {workspace.name} know where else to look.</p>{addButton}
      </div>}
      {data?.links.filter(link => !existing || link.workspaceId !== editing?.workspaceId).map(link => {
        const destination = data.workspaces.find(item => item.id === link.workspaceId);
        const unavailable = unavailableReason(link, destination);
        const name = destination?.name ?? link.workspaceId;
        return <div className="related-row" key={link.workspaceId}>
          {destination && <RepoAvatar owner={destination.monitoredOwner} repo={destination.monitoredRepo} size={28} />}
          <div className="related-row-main">
            <div className="related-row-title"><strong>{name}</strong><span className={`related-badge is-${link.kind.toLowerCase()}`}>{link.kind === 'FORK' ? 'Fork' : 'Dependency'}</span>
              {unavailable && <span className="related-badge is-unavailable">Unavailable</span>}</div>
            {link.backportByDefault && <div className="related-guidance">Upstream in {workspace.name}’s repo line</div>}
            {link.guidance && <div className="related-guidance">{link.guidance}</div>}
            {unavailable && <div className="related-unavailable">{unavailable}. New work there won’t start.</div>}
          </div>
          <div className="related-row-actions"><ViewLinks workspaceId={workspace.id} relatedWorkspaceId={link.workspaceId} unavailable={unavailable} />
            <span className="related-edit-actions">
              <button type="button" aria-label={`Edit ${name}`} title="Edit" disabled={busy}
                onClick={() => { setEditing(link); setExisting(true); setError(null); }}><Pencil size={13} /></button>
              <button type="button" aria-label={`Remove ${name}`} title="Remove" disabled={busy}
                onClick={() => void apply(() => window.bridge.removeWorkspaceRelationship(workspace.id, link.workspaceId))}><Trash2 size={13} /></button>
            </span>
          </div>
        </div>;
      })}
      {linkedHere.map(link => {
        const other = data?.workspaces.find(item => item.id === link.workspaceId);
        const unavailable = unavailableReason(link, other);
        const name = other?.name ?? link.workspaceId;
        return <div className="related-row is-incoming" key={link.workspaceId}>
          {other && <RepoAvatar owner={other.monitoredOwner} repo={other.monitoredRepo} size={28} />}
          <div className="related-row-main">
            <div className="related-row-title"><strong>{name}</strong><span className="related-badge">{positionLabel[link.position]}</span>
              {unavailable && <span className="related-badge is-unavailable">Unavailable</span>}</div>
            <div className="related-guidance">{link.position === 'DOWNSTREAM' ? `Takes changes from ${workspace.name}` : `Uses ${workspace.name}`}. Edit it in {name}.</div>
          </div>
          <div className="related-row-actions"><ViewLinks workspaceId={workspace.id} relatedWorkspaceId={link.workspaceId} unavailable={unavailable} /></div>
        </div>;
      })}
      {editing !== null && <form className="related-form" onSubmit={event => {
        event.preventDefault();
        if (editing.workspaceId && editing.kind && !busy) void apply(() => window.bridge.saveWorkspaceRelationship(workspace.id,
          { workspaceId: editing.workspaceId, kind: editing.kind as WorkspaceRelationshipDto['kind'], guidance: editing.guidance, backportByDefault: editing.backportByDefault }));
      }}>
        <span className="related-field-label">Workspace</span>
        <SelectPill ariaLabel="Related workspace" value={editing.workspaceId} disabled={busy || existing} align="left" wide
          label={data?.workspaces.find(candidate => candidate.id === editing.workspaceId)?.name ?? (editing.workspaceId || 'Choose a watched workspace')}
          options={(data?.workspaces ?? []).map(candidate => {
            const same = candidate.id === workspace.id || candidate.monitoredRepositoryId === workspace.monitoredRepositoryId;
            const linked = data?.links.some(link => link.workspaceId === candidate.id) && candidate.id !== editing.workspaceId;
            const unavailable = !workspaceHasReadableCode(candidate);
            const reason = same ? 'Same project' : linked ? 'Already related' : unavailable ? 'Code unavailable' : undefined;
            return { value: candidate.id, label: candidate.name, disabled: !!reason, note: reason, disabledReason: reason };
          })} onSelect={value => setEditing({ ...editing, workspaceId: value })} />
        <span className="related-field-label">Type</span><div>
          <Segmented label="Relationship type" value={editing.kind}
            options={[{ value: 'FORK', label: 'Fork', disabled: busy }, { value: 'DEPENDENCY', label: 'Dependency', disabled: busy }]}
            onChange={kind => setEditing({ ...editing, kind: kind as Draft['kind'], backportByDefault: kind === 'FORK' && editing.backportByDefault })} />
          <p className="related-hint">{editing.kind === 'FORK' ? 'Our code builds on this project. Read, investigate, and backport when requested.'
            : editing.kind === 'DEPENDENCY' ? 'We use this library. Read and investigate; no cherry-picks or backports between these projects.'
              : `Choose how ${workspace.name} relates to it.`}</p>
        </div>
        {editing.kind === 'FORK' && <><span className="related-field-label">Repo line</span><div>
          <label><input type="checkbox" checked={editing.backportByDefault} disabled={busy}
            onChange={event => setEditing({ ...editing, backportByDefault: event.target.checked })} /> Use as {workspace.name}’s upstream</label>
          <p className="related-hint">Commits and backports follow the repo line: changes flow from here into {workspace.name}, then to the repos that take
            changes from {workspace.name}. With only one Fork, it’s the upstream automatically. Saving this setting does not start work.</p>
        </div></>}
        <label className="related-field-label" htmlFor={guidanceId}>Guidance</label><div>
          <textarea id={guidanceId} className="setw-input" aria-label="Relationship guidance" maxLength={500} rows={3}
            placeholder={'When should the agent consult it? Add version hints, e.g. “The dependency version is declared in pom.xml.”'}
            value={editing.guidance} disabled={busy} onChange={event => setEditing({ ...editing, guidance: event.target.value })} />
          <div className="related-guidance-help"><span>Read by the agent every turn.</span><span>{editing.guidance.length}/500</span></div>
        </div>
        <div className="related-form-actions">
          <button type="button" className="setw-btn" disabled={busy} onClick={() => { setEditing(null); setError(null); }}>Cancel</button>
          <button type="submit" className="setw-btn-primary" disabled={busy || !editing.workspaceId || !editing.kind}>{busy ? 'Saving…' : existing ? 'Save' : 'Add'}</button>
        </div>
      </form>}
    </SettingsCard>
  </div>;
}
