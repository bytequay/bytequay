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
import type { RecentEventDto, RepoMetaDto, WorkspaceDto } from '../types';
import Avatar from '../Avatar';
import {
  workspacePhase,
  workspacePhaseLabel,
} from '../workspaces/model';

type Props = {
  workspaces: WorkspaceDto[];
  loading: boolean;
  /** Recent GitHub events — powers the per-repo "active today" chip. */
  events: RecentEventDto[];
  onOpenWorkspace: (workspaceId: string) => void;
  onWatch: () => void;
  onRemove: (workspace: WorkspaceDto) => void;
};

function topLanguage(meta: RepoMetaDto | undefined): string | null {
  if (!meta) return null;
  let best: string | null = null;
  let bestBytes = 0;
  for (const [lang, bytes] of Object.entries(meta.languages)) {
    if (bytes > bestBytes) {
      best = lang;
      bestBytes = bytes;
    }
  }
  return best;
}

function activityChip(events: RecentEventDto[], fullName: string): string | null {
  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  let commits = 0;
  let active = false;
  for (const e of events) {
    if (e.repo !== fullName) continue;
    if (new Date(e.createdAt).getTime() >= todayStart.getTime()) {
      active = true;
      if (e.type === 'PushEvent') commits += e.commitCount || 1;
    }
  }
  if (commits > 0) return `${commits} new commit${commits !== 1 ? 's' : ''} today`;
  return active ? 'Active today' : null;
}

/** "Repos you watch" as a two-column card grid. Description, stars and
 *  language come from the backend's cached monitored-repo metadata
 *  (same pattern as the Repos page). */
function WatchedReposGrid({
  workspaces,
  loading,
  events,
  onOpenWorkspace,
  onWatch,
  onRemove,
}: Props) {
  const [metas, setMetas] = useState<Record<string, RepoMetaDto>>({});

  useEffect(() => {
    let cancelled = false;
    for (const workspace of workspaces) {
      const key = workspace.id;
      if (metas[key] !== undefined) continue;
      window.bridge.getRepoMeta(workspace.monitoredOwner, workspace.monitoredRepo)
        .then(meta => {
          if (!cancelled) setMetas(prev => ({ ...prev, [key]: meta }));
        })
        .catch(() => { /* card renders without meta */ });
    }
    return () => { cancelled = true; };
    // metas deliberately omitted: re-running on every meta arrival would
    // refire the loop; the `!== undefined` guard needs only the repo list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaces]);

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
        <div className="home-grid-2">
          {workspaces.map(workspace => {
            const fullName = `${workspace.selectedOwner}/${workspace.selectedRepo}`;
            const monitored = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
            const meta = metas[workspace.id];
            const lang = topLanguage(meta);
            const chip = activityChip(events, monitored);
            const phase = workspacePhase(workspace);
            return (
              <div
                key={workspace.id}
                data-workspace-id={workspace.id}
                className="home-repo-tile"
                role="button"
                tabIndex={0}
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
                  <Avatar login={workspace.selectedOwner} size={26} className="home-repo-tile__avatar" />
                  <span className="home-repo-tile__name">{fullName}</span>
                  <span className={`ws-state is-${phase}`}>{workspacePhaseLabel(workspace)}</span>
                  <button
                    className="home-repo-tile__unwatch"
                    type="button"
                    aria-label={`Unwatch ${fullName}`}
                    title="Unwatch this repo"
                    onClick={(e) => {
                      e.stopPropagation();
                      onRemove(workspace);
                    }}
                  >
                    ✕
                  </button>
                </div>
                <p className="home-repo-tile__desc">{meta?.description ?? ' '}</p>
                <div className="home-repo-tile__foot">
                  {meta && <span>★ {meta.stargazersCount}</span>}
                  {lang && <span>{lang}</span>}
                  <span>⎇ {workspace.defaultBranch}</span>
                  {workspace.topology === 'FORK' && <span>⑂ fork</span>}
                  {chip && <span className="home-repo-tile__chip">{chip}</span>}
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
