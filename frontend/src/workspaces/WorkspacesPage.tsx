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
import { workspaceFromDto, type Workspace } from './model';
import '../css/workspaces.css';

export default function WorkspacesPage({ onOpen }: { onOpen: (workspace: Workspace) => void }) {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listWorkspaces()
      .then(rows => { if (!cancelled) setWorkspaces(rows.map(workspaceFromDto)); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="ws-page">
      <div className="ws-head">
        <h1>Workspaces</h1>
        <span className="ws-count">
          {workspaces.length} watched repositor{workspaces.length === 1 ? 'y' : 'ies'}
        </span>
        <div className="ws-spacer" />
      </div>

      {error && <div className="ws-error" role="alert">{error}</div>}
      {loading && <div className="ws-empty">Loading workspaces…</div>}

      {!loading && workspaces.length === 0 && (
        <div className="ws-empty">
          Watch a GitHub repository from Home to create a workspace.
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
            <div className="ws-card-path">
              {workspace.selectedOwner}/{workspace.selectedRepo}
            </div>
            <div className="ws-card-foot">
              <span className="ws-card-dot" />
              {workspace.codeState.toLowerCase()} · branch {workspace.defaultBranch}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
