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
import { relativeTime } from '../relativeTime';
import {
  workspaceApi,
  type WorkspaceRelationCandidateDto,
  type WorkspaceRelationDto,
} from './workspaceApi';
import { message } from './WorkspaceRepoUi';

export const WORKSPACE_RELATION_CHANGED = 'bytequay-workspace-relation-changed';

export default function WorkspaceRelationsSettings({ workspaceId, repoName }: {
  workspaceId: string;
  repoName: string;
}) {
  const [relation, setRelation] = useState<WorkspaceRelationDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void workspaceApi.relation(workspaceId)
      .then(next => { if (!cancelled) setRelation(next); })
      .catch(reason => { if (!cancelled) setMessage(errorMessage(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  const notify = (next: WorkspaceRelationDto | null) => {
    setRelation(next);
    window.dispatchEvent(new CustomEvent(WORKSPACE_RELATION_CHANGED, {
      detail: { workspaceId },
    }));
  };

  const save = async (next: WorkspaceRelationDto, overrides: Partial<Pick<
    WorkspaceRelationDto, 'commitsEnabled' | 'tagsEnabled' | 'autoFetchIntervalMinutes'
  >>) => {
    setBusy(true);
    setMessage(null);
    try {
      notify(await workspaceApi.saveRelation(workspaceId, {
        upstreamWorkspaceId: next.upstreamWorkspaceId,
        commitsEnabled: overrides.commitsEnabled ?? next.commitsEnabled,
        tagsEnabled: overrides.tagsEnabled ?? next.tagsEnabled,
        autoFetchIntervalMinutes: overrides.autoFetchIntervalMinutes ?? next.autoFetchIntervalMinutes,
      }));
    }
    catch (reason) {
      setMessage(errorMessage(reason));
    }
    finally {
      setBusy(false);
    }
  };

  if (loading) return <div className="wu-relation-loading" role="status">Loading relation…</div>;

  return (
    <>
      {relation === null ? (
        <section className="wu-relation-empty">
          <span className="wu-relation-empty__icon" aria-hidden>⑂</span>
          <h2>No upstream linked</h2>
          <p>Link another workspace as a read-only upstream for commits and tags. The upstream workspace is never changed.</p>
          <button type="button" className="wu-primary-button" onClick={() => {
            setMessage(null);
            setPickerOpen(true);
          }}>
            Link upstream workspace…
          </button>
        </section>
      ) : (
        <section className="wu-relation-card">
          <header>
            <span className="wu-relation-card__glyph" aria-hidden>⑂</span>
            <div className="wu-relation-card__direction">
              <span><small>THIS WORKSPACE</small><strong>{repoName}</strong></span>
              <b><span aria-hidden>→</span> READS FROM</b>
              <span><small>UPSTREAM WORKSPACE</small><strong>{relation.upstreamWorkspaceName}</strong>
                <code>{relation.upstreamRepoFullName}</code></span>
            </div>
            <i>read-only</i>
            <button type="button" onClick={() => {
              window.location.hash = `#/workspace/${encodeURIComponent(relation.upstreamWorkspaceId)}/today`;
            }}>Open workspace</button>
          </header>
          <div className="wu-relation-card__rows">
            <RelationToggle label="Commits" detail="Browse and select upstream commit ranges."
              checked={relation.commitsEnabled} disabled={busy}
              onChange={value => { void save(relation, { commitsEnabled: value }); }} />
            <RelationToggle label="Tags" detail="Show releases and tags in upstream history."
              checked={relation.tagsEnabled} disabled={busy}
              onChange={value => { void save(relation, { tagsEnabled: value }); }} />
            <div className="wu-relation-setting-row">
              <span><strong>Auto-fetch</strong><small>Refreshes refs and the trailer index. Never pulls or pushes.</small></span>
              <select aria-label="Upstream auto-fetch interval" value={relation.autoFetchIntervalMinutes}
                disabled={busy} onChange={event => {
                  void save(relation, { autoFetchIntervalMinutes: Number(event.target.value) });
                }}>
                <option value={15}>Every 15 minutes</option>
                <option value={30}>Every 30 minutes</option>
                <option value={60}>Every hour</option>
                <option value={240}>Every 4 hours</option>
              </select>
            </div>
          </div>
          <footer>
            <span>
              {relation.lastFetchedAt === null ? 'Not fetched yet' : `Fetched ${relativeTime(relation.lastFetchedAt)}`}
              {' · '}{relation.indexedCommitCount.toLocaleString()} commits indexed
            </span>
            <button type="button" disabled={busy} onClick={() => {
              setBusy(true);
              setMessage(null);
              void workspaceApi.fetchRelation(workspaceId)
                .then(next => { notify(next); setMessage('Upstream refreshed.'); })
                .catch(reason => setMessage(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}>{busy ? 'Working…' : 'Fetch now'}</button>
            <button type="button" className="is-danger" disabled={busy} onClick={() => {
              setBusy(true);
              setMessage(null);
              void workspaceApi.unlinkRelation(workspaceId)
                .then(() => { notify(null); setMessage('Upstream unlinked.'); })
                .catch(reason => setMessage(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}>Unlink</button>
          </footer>
        </section>
      )}
      <p className="wu-relation-footnote">
        <span aria-hidden>⌾</span> {repoName} reads from the upstream clone. No reverse link or write capability is created.
      </p>
      {message !== null && <div className="wu-inline-message" role="status">{message}</div>}
      {pickerOpen && (
        <LinkUpstreamDialog workspaceId={workspaceId} onClose={() => setPickerOpen(false)}
          onLinked={next => {
            notify(next);
            setPickerOpen(false);
            setMessage('Upstream linked.');
          }} />
      )}
    </>
  );
}

/** Links an upstream workspace from anywhere. Managing an existing relation
 * stays in workspace settings; only the first link is offered inline. */
export function LinkUpstreamDialog({ workspaceId, onClose, onLinked }: {
  workspaceId: string;
  onClose: () => void;
  onLinked: (relation: WorkspaceRelationDto) => void;
}) {
  const [candidates, setCandidates] = useState<WorkspaceRelationCandidateDto[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.relationCandidates(workspaceId)
      .then(next => {
        if (cancelled) return;
        const rows = Array.isArray(next) ? next : [];
        setCandidates(rows);
        // Never preselect one the server would refuse.
        const usable = rows.filter(row => !isIneligible(row));
        setSelectedId(usable.find(row => row.suggested)?.workspaceId
          ?? usable[0]?.workspaceId ?? '');
      })
      .catch(reason => { if (!cancelled) setError(errorMessage(reason)); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  return (
    <RelationPicker
      candidates={candidates}
      selectedId={selectedId}
      busy={busy}
      error={error}
      onSelect={setSelectedId}
      onClose={onClose}
      onConfirm={() => {
        if (selectedId.length === 0) return;
        setBusy(true);
        setError(null);
        void workspaceApi.saveRelation(workspaceId, {
          upstreamWorkspaceId: selectedId,
          commitsEnabled: true,
          tagsEnabled: true,
          autoFetchIntervalMinutes: 30,
        }).then(onLinked)
          .catch(reason => setError(errorMessage(reason)))
          .finally(() => setBusy(false));
      }}
    />
  );
}

function RelationToggle({ label, detail, checked, disabled, onChange }: {
  label: string;
  detail: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className={`wu-relation-setting-row${disabled ? ' is-disabled' : ''}`}>
      <span><strong>{label}</strong><small>{detail}</small></span>
      <button type="button" role="switch" aria-label={label} aria-checked={checked}
        disabled={disabled} className={`wu-switch${checked ? ' on' : ''}`}
        onClick={() => onChange(!checked)}><i /></button>
    </div>
  );
}

function RelationPicker({ candidates, selectedId, busy, error, onSelect, onClose, onConfirm }: {
  candidates: WorkspaceRelationCandidateDto[];
  selectedId: string;
  busy: boolean;
  error: string | null;
  onSelect: (id: string) => void;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="wu-modal-backdrop wu-modal-backdrop--centered wu-relation-picker-backdrop"
      role="presentation" onMouseDown={onClose}>
      <section className="wu-relation-picker" role="dialog" aria-modal="true" aria-labelledby="relation-picker-title"
        onMouseDown={event => event.stopPropagation()}>
        <header><h2 id="relation-picker-title">Link upstream workspace</h2><button type="button" onClick={onClose}>×</button></header>
        <p>Choose a workspace to read commits and tags from. Suggested matches use the repository's fork metadata.</p>
        <div className="wu-relation-picker__list">
          {candidates.map(candidate => {
            const blocked = isIneligible(candidate);
            return (
              <label key={candidate.workspaceId}
                className={`${candidate.workspaceId === selectedId ? 'selected' : ''}${
                  blocked ? ' is-blocked' : ''}`}>
                <input type="radio" name="upstream-workspace" value={candidate.workspaceId}
                  disabled={blocked}
                  checked={candidate.workspaceId === selectedId}
                  onChange={() => onSelect(candidate.workspaceId)} />
                <span>
                  <strong>{candidate.name}</strong>
                  <code>{candidate.repoFullName}</code>
                  {blocked && <em>{candidate.ineligibleReason}</em>}
                </span>
                {candidate.suggested && !blocked && <i>suggested</i>}
              </label>
            );
          })}
          {candidates.length === 0 && (
            <span role="status">
              No eligible workspace found. An upstream has to be another workspace in
              this app that already has its repository cloned locally — add one first,
              then link it here.
            </span>
          )}
        </div>
        {error !== null && <span className="wu-form-error">{error}</span>}
        <footer>
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="button" className="wu-primary-button" disabled={busy || selectedId.length === 0}
            onClick={onConfirm}>{busy ? 'Linking…' : 'Link upstream'}</button>
        </footer>
      </section>
    </div>
  );
}

/** A relation is directional: linking back to a workspace that already
 *  reads from this one would close a loop, which the server refuses. */
function isIneligible(candidate: WorkspaceRelationCandidateDto): boolean {
  return typeof candidate.ineligibleReason === 'string'
    && candidate.ineligibleReason.length > 0;
}

/** Shared with the rest of the workspace surfaces, so a 422 shows the
 *  server's sentence rather than the IPC/HTTP wrapper around it. */
const errorMessage = message;
