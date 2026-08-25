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
import type { WorkspaceDto } from '../types';
import {
  workspaceIsUpdating,
  workspacePhase,
  workspacePhaseLabel,
} from './model';
import '../css/workspaces.css';

export default function WorkspacesPage({ onOpen }: { onOpen: (workspaceId: string) => void }) {
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listWorkspaces()
      .then(rows => { if (!cancelled) setWorkspaces(rows); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!workspaces.some(workspaceIsUpdating)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.listWorkspaces()
        .then(setWorkspaces)
        .catch(() => {});
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [workspaces]);

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

      <WorkspaceCards
        workspaces={workspaces}
        onOpen={onOpen}
      />
    </div>
  );
}

export function WorkspaceCards({ workspaces, onOpen }: {
  workspaces: WorkspaceDto[];
  onOpen: (workspaceId: string) => void;
}) {
  return (
    <div className="ws-grid">
      {workspaces.map(workspace => {
        const phase = workspacePhase(workspace);
        return (
          <div
            key={workspace.id}
            data-workspace-id={workspace.id}
            className="ws-card"
            role="button"
            tabIndex={0}
            onClick={() => onOpen(workspace.id)}
            onKeyDown={event => {
              if (event.target !== event.currentTarget) return;
              if (event.key !== 'Enter' && event.key !== ' ') return;
              event.preventDefault();
              onOpen(workspace.id);
            }}
          >
            <div className="ws-card-title">
              <span className="ws-card-name">{workspace.name}</span>
              <span className={`ws-state is-${phase}`}>{workspacePhaseLabel(workspace)}</span>
            </div>
            <div className="ws-card-path">
              {workspace.selectedOwner}/{workspace.selectedRepo}
            </div>
            {workspace.topology === 'FORK' && (
              <div className="ws-card-route">
                follows {workspace.monitoredOwner}/{workspace.monitoredRepo}
              </div>
            )}
            <div className="ws-card-foot">
              branch {workspace.defaultBranch}
              <span>· {workspace.lastCodeSyncAt ? 'code available' : 'cloning code'}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
