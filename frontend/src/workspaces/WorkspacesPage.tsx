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
import { useState } from 'react';
import type { GitOverviewDto } from '../types';
import {
  githubSlug,
  guessUpstreamRemote,
  loadWorkspaces,
  upsertWorkspace,
  type Workspace,
} from './model';
import '../css/workspaces.css';

export default function WorkspacesPage({ onOpen }: { onOpen: (workspace: Workspace) => void }) {
  const [workspaces, setWorkspaces] = useState<Workspace[]>(loadWorkspaces);
  const [draft, setDraft] = useState<Workspace | null>(null);
  const [draftRemotes, setDraftRemotes] = useState<GitOverviewDto['remotes']>([]);
  const [error, setError] = useState<string | null>(null);

  const addWorkspace = async () => {
    setError(null);
    const path = await window.bridge.chooseDirectory('Choose a local checkout');
    if (!path) return;
    try {
      const overview = await window.bridge.gitOverview(path);
      const origin = overview.remotes.find(remote => remote.name === 'origin')
        ?? overview.remotes[0];
      const originSlug = origin ? githubSlug(origin.url) : null;
      setDraftRemotes(overview.remotes);
      setDraft({
        id: crypto.randomUUID(),
        name: originSlug ?? path.split('/').pop() ?? path,
        path,
        defaultBranch: overview.currentBranch,
        originRemote: origin?.name ?? 'origin',
        upstreamRemote: guessUpstreamRemote(overview.remotes),
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  };

  const saveDraft = () => {
    if (!draft) return;
    setWorkspaces(upsertWorkspace(draft));
    setDraft(null);
  };

  return (
    <div className="ws-page">
      <div className="ws-head">
        <h1>Workspaces</h1>
        <span className="ws-count">
          {workspaces.length} local checkout{workspaces.length === 1 ? '' : 's'}
        </span>
        <div className="ws-spacer" />
        <button type="button" className="ws-add-btn" onClick={() => { void addWorkspace(); }}>
          + Add workspace
        </button>
      </div>

      {error && <div className="ws-error" role="alert">{error}</div>}

      {workspaces.length === 0 && (
        <div className="ws-empty">
          Add a local checkout to browse its commits and cherry-pick from its linked upstream.
        </div>
      )}

      <div className="ws-grid">
        {workspaces.map(workspace => (
          <button
            type="button"
            key={workspace.id}
            className="ws-card"
            onClick={() => onOpen(workspace)}
          >
            <div className="ws-card-title">
              <span className="ws-card-name">{workspace.name}</span>
              {workspace.upstreamRemote && (
                <span className="ws-card-upstream">↞ {workspace.upstreamRemote}</span>
              )}
            </div>
            <div className="ws-card-path">{workspace.path}</div>
            <div className="ws-card-foot">
              <span className="ws-card-dot" />
              branch {workspace.defaultBranch}
            </div>
          </button>
        ))}
      </div>

      {draft && (
        <div className="ws-modal-overlay" onClick={() => setDraft(null)}>
          <div className="ws-modal" onClick={event => event.stopPropagation()}>
            <h2>Add workspace</h2>
            <label>
              Workspace name
              <input
                value={draft.name}
                onChange={event => setDraft({ ...draft, name: event.target.value })}
              />
            </label>
            <label>
              Local path
              <input value={draft.path} readOnly className="ws-mono" />
            </label>
            <label>
              Default branch
              <input
                value={draft.defaultBranch}
                className="ws-mono"
                onChange={event => setDraft({ ...draft, defaultBranch: event.target.value })}
              />
            </label>
            <label>
              Linked upstream (read-only cherry-pick source)
              <select
                value={draft.upstreamRemote ?? ''}
                onChange={event => setDraft({
                  ...draft,
                  upstreamRemote: event.target.value === '' ? null : event.target.value,
                })}
              >
                <option value="">None</option>
                {draftRemotes.map(remote => (
                  <option key={remote.name} value={remote.name}>
                    {remote.name} — {githubSlug(remote.url) ?? remote.url}
                  </option>
                ))}
              </select>
            </label>
            <div className="ws-modal-actions">
              <button type="button" onClick={() => setDraft(null)}>Cancel</button>
              <button
                type="button"
                className="ws-btn-primary"
                disabled={!draft.name.trim()}
                onClick={saveDraft}
              >
                Add workspace
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
