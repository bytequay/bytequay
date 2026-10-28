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
import { getCached } from '../dataCache';
import type { PullRequestDto, WorkspaceDto, WorkspaceHostingAttentionDto } from '../types';
import { pullRequestNeedsReview } from '../pulls/model';
import RepoAvatar from '../RepoAvatar';
import { syncStateColor, workspaceSyncState } from '../workspaces/model';

type Props = {
  workspaces: WorkspaceDto[];
  loading: boolean;
  onOpenWorkspace: (workspaceId: string) => void;
  onWatch: () => void;
};

function repoCounts(prs: PullRequestDto[], fullName: string) {
  let open = 0;
  let needsYou = 0;
  for (const pr of prs) {
    if (pr.repo !== fullName || pr.state !== 'open') continue;
    open++;
    if (pullRequestNeedsReview(pr)) needsYou++;
  }
  return { open, needsYou };
}

/** "Repos you watch": one compact tile per workspace — status, name, and the
 *  two numbers that decide whether to open it. */
function WatchedReposGrid({ workspaces, loading, onOpenWorkspace, onWatch }: Props) {
  const [pulls, setPulls] = useState<Record<string, PullRequestDto[]>>(() => Object.fromEntries(workspaces.map(workspace =>
    [workspace.id, getCached<WorkspaceHostingAttentionDto>(`workspace-attention:${workspace.id}`)?.pullRequests ?? []])));
  const workspaceKey = workspaces.map(workspace => workspace.id).join(',');
  useEffect(() => {
    let active = true;
    const refresh = () => {
      for (const id of workspaceKey.split(',').filter(Boolean)) {
        void window.bridge.fetchWorkspaceHostingAttention(id).then(snapshot => {
          if (active) setPulls(current => ({ ...current, [id]: snapshot.pullRequests }));
        }).catch(() => {});
      }
    };
    refresh();
    const stop = window.bridge.onPullRequestsSynced(refresh);
    return () => { active = false; stop(); };
  }, [workspaceKey]);
  return (
    <div className="home-section">
      <div className="home-section__header">
        <span className="home-section__title">Repos you watch</span>
        <button className="home-section__action" onClick={onWatch} type="button">
          + Watch
        </button>
      </div>
      {loading ? (
        <div className="hp-loading">Loading…</div>
      ) : workspaces.length === 0 ? (
        <div className="hp-empty">No repos yet.</div>
      ) : (
        <div className="home-repo-grid">
          {workspaces.map(workspace => {
            const fullName = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
            const counts = repoCounts(pulls[workspace.id] ?? [], fullName);
            const state = workspaceSyncState(workspace);
            return (
              <div
                key={workspace.id}
                data-workspace-id={workspace.id}
                className="home-repo-tile"
                role="button"
                tabIndex={0}
                title={`${fullName} — ${state}`}
                onClick={() => onOpenWorkspace(workspace.id)}
                onKeyDown={(e) => {
                  if (e.target !== e.currentTarget) return;
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onOpenWorkspace(workspace.id);
                  }
                }}
              >
                <div className="home-repo-tile__head">
                  <RepoAvatar github={workspace.hostingServer?.provider !== 'GITLAB'}
                    owner={workspace.monitoredOwner}
                    repo={workspace.monitoredRepo}
                    size={18}
                  />
                  <span className="home-repo-tile__name">{fullName}</span>
                  <span
                    className="home-repo-tile__dot"
                    style={{ background: syncStateColor(state) }}
                    aria-hidden="true"
                  />
                </div>
                <div className="home-repo-tile__stats">
                  <span><b>{counts.open}</b> open</span>
                  <span><b>{counts.needsYou}</b> need you</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default WatchedReposGrid;
