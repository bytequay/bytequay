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
import type { RecentEventDto, RepoMetaDto, WatchedRepoDto } from '../types';
import Avatar from '../Avatar';

type Props = {
  repos: WatchedRepoDto[];
  loading: boolean;
  /** Recent GitHub events — powers the per-repo "active today" chip. */
  events: RecentEventDto[];
  onSelectRepo: (owner: string, repo: string) => void;
  onWatch: () => void;
  onRemove: (owner: string, repo: string) => void;
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
 *  language come from the backend's cached repo meta, fetched per repo
 *  (same pattern as the Repos page). */
function WatchedReposGrid({ repos, loading, events, onSelectRepo, onWatch, onRemove }: Props) {
  const [metas, setMetas] = useState<Record<string, RepoMetaDto>>({});

  useEffect(() => {
    let cancelled = false;
    for (const r of repos) {
      const key = `${r.owner}/${r.repo}`;
      if (metas[key] !== undefined) continue;
      window.bridge.getRepoMeta(r.owner, r.repo)
        .then(meta => {
          if (!cancelled) setMetas(prev => ({ ...prev, [key]: meta }));
        })
        .catch(() => { /* card renders without meta */ });
    }
    return () => { cancelled = true; };
    // metas deliberately omitted: re-running on every meta arrival would
    // refire the loop; the `!== undefined` guard needs only the repo list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repos]);

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
      ) : repos.length === 0 ? (
        <div className="hp-empty">No repos yet.</div>
      ) : (
        <div className="home-grid-2">
          {repos.map(r => {
            const fullName = `${r.owner}/${r.repo}`;
            const meta = metas[fullName];
            const lang = topLanguage(meta);
            const chip = activityChip(events, fullName);
            return (
              <div
                key={r.id}
                className="home-repo-tile"
                role="button"
                tabIndex={0}
                onClick={() => onSelectRepo(r.owner, r.repo)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelectRepo(r.owner, r.repo);
                  }
                }}
              >
                <div className="home-repo-tile__head">
                  <Avatar login={r.owner} size={26} className="home-repo-tile__avatar" />
                  <span className="home-repo-tile__name">{r.owner}/{r.repo}</span>
                  <button
                    className="home-repo-tile__unwatch"
                    type="button"
                    aria-label={`Unwatch ${fullName}`}
                    title="Unwatch this repo"
                    onClick={(e) => {
                      e.stopPropagation();
                      onRemove(r.owner, r.repo);
                    }}
                  >
                    ✕
                  </button>
                </div>
                <p className="home-repo-tile__desc">{meta?.description ?? ' '}</p>
                <div className="home-repo-tile__foot">
                  {meta && <span>★ {meta.stargazersCount}</span>}
                  {lang && <span>{lang}</span>}
                  {meta?.defaultBranch && <span>⎇ {meta.defaultBranch}</span>}
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
