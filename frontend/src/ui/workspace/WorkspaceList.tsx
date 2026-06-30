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
import { Logo } from '../primitives';
import type { LogoColor } from '../primitives';

/** One workspace row in the sidebar list. */
export type WorkspaceRow = {
  id: string;
  initials: string;
  color: LogoColor;
  name: string;
  /** "3 repos · 5 open threads". */
  sub: string;
  /** Open-thread count badge. */
  count?: number;
};

/**
 * The sidebar's workspace list, shown when no workspace is active. Each
 * row drills into its workspace; when {@code onDelete} is wired a row also
 * exposes a delete affordance (revealed on row hover) so a workspace can be
 * removed without a trip to the Workspaces grid. Nothing is pre-selected.
 */
export function WorkspaceList({ workspaces, activeId, onOpen, onDelete, onNewWorkspace }: {
  workspaces: WorkspaceRow[];
  activeId?: string;
  onOpen?: (id: string) => void;
  /** Delete a workspace from its row. The host confirms + calls the backend. */
  onDelete?: (id: string, name: string) => void;
  onNewWorkspace?: () => void;
}) {
  return (
    <div className="sb-section">
      <div className="sb-section-h">
        <span className="nm">All workspaces</span>
        <span className="actions">
          <span role="button" tabIndex={0} aria-label="Filter">⛚</span>
          <span role="button" tabIndex={0} aria-label="New workspace" onClick={onNewWorkspace}>+</span>
        </span>
      </div>
      <div className="ws-list">
        {workspaces.map(w => (
          <div key={w.id} className="ws-item-wrap">
            <button
              type="button"
              className={w.id === activeId ? 'ws-item active' : 'ws-item'}
              onClick={() => onOpen?.(w.id)}
            >
              <Logo initials={w.initials} color={w.color} size="lg" />
              <span className="ws-meta">
                <span className="ws-name">{w.name}</span>
                <span className="ws-sub">{w.sub}</span>
              </span>
              {w.count !== undefined && <span className="ws-count">{w.count}</span>}
            </button>
            {onDelete !== undefined && (
              <button
                type="button"
                className="ws-item__delete"
                aria-label={`Delete workspace ${w.name}`}
                title="Delete workspace"
                onClick={e => { e.stopPropagation(); onDelete(w.id, w.name); }}
              >
                ⌫
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
