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
import Avatar from '../Avatar';
import { getCached, setCached } from '../dataCache';
import type { TeamDto, TeamSummaryDto } from '../types';
import TeamEditorModal from './TeamEditorModal';

const TEAM_KEY = (id: number) => `team:${id}`;
/** Members shown in the per-card avatar stack before "+N" overflow. */
const HERO_AVATAR_COUNT = 3;

type Props = {
  /** Open the per-team home page. */
  onOpenTeam: (id: number) => void;
  /** Back to the prior surface (Pull requests). */
  onBack: () => void;
};

function TeamsManagePage({ onOpenTeam, onBack }: Props) {
  const [teams, setTeams] = useState<TeamSummaryDto[]>([]);
  /** {teamId → first few member logins}. Populated lazily after listTeams
   *  via a parallel fan-out so the avatar stack on each card can render
   *  real GitHub avatars. The summary endpoint only ships memberCount. */
  const [memberLogins, setMemberLogins] = useState<Record<number, string[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<TeamDto | 'new' | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listTeams();
      setTeams(list);
      // Seed the avatar stacks from cache immediately, then top up from
      // the network. Each getTeam result is also cached so future visits
      // (or other pages that read team:N) stay in sync.
      const seeded: Record<number, string[]> = {};
      for (const t of list) {
        const cached = getCached<TeamDto>(TEAM_KEY(t.id));
        if (cached) seeded[t.id] = cached.members;
      }
      if (Object.keys(seeded).length > 0) setMemberLogins(prev => ({ ...prev, ...seeded }));
      await Promise.all(list.map(async t => {
        try {
          const full = await window.bridge.getTeam(t.id);
          setCached(TEAM_KEY(t.id), full);
          setMemberLogins(prev => ({ ...prev, [t.id]: full.members }));
        } catch {
          // Best-effort — a single team failing to enrich shouldn't kill
          // the whole grid; the card just shows no avatars.
        }
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const startManage = async (id: number) => {
    try {
      const full = await window.bridge.getTeam(id);
      setEditing(full);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="teams-manage">
      <header className="teams-manage__head">
        <nav className="team-home__breadcrumb">
          <button type="button" className="team-home__crumb" onClick={onBack}>
            ← Pull requests
          </button>
          <span className="team-home__sep">/</span>
          <span className="team-home__crumb-current">Teams</span>
        </nav>

        <div className="teams-manage__title-row">
          <div>
            <h1 className="teams-manage__title">Teams</h1>
            <p className="teams-manage__subtitle">
              Group people whose PRs you care about. Each team has its own kanban filtered to PRs they
              authored, plus a team home page that shows current work and members.
            </p>
          </div>
          <button
            type="button"
            className="button button--primary teams-manage__cta"
            onClick={() => setEditing('new')}
          >
            + New team
          </button>
        </div>
      </header>

      <div className="teams-manage__body">
        {loading && <div className="repo-loading">Loading…</div>}
        {error && <div className="repo-error">{error}</div>}

        {!loading && (
          <div className="teams-grid">
            {teams.map(t => (
              <article key={t.id} className="team-card">
                <header className="team-card__head">
                  <span
                    className={`team-avatar team-card__avatar team-avatar--${t.color}`}
                    aria-hidden="true"
                  >
                    {t.avatar}
                  </span>
                  <div className="team-card__head-text">
                    <h3 className="team-card__name">{t.name}</h3>
                    {t.description && (
                      <p className="team-card__desc">{t.description}</p>
                    )}
                  </div>
                </header>

                <div className="team-card__meta">
                  {(() => {
                    const logins = memberLogins[t.id] ?? [];
                    const visible = logins.slice(0, HERO_AVATAR_COUNT);
                    const overflow = Math.max(0, t.memberCount - visible.length);
                    if (visible.length === 0) return null;
                    return (
                      <div className="team-card__avatars" aria-hidden="true">
                        {visible.map(login => (
                          <span key={login} className="team-card__av">
                            <Avatar login={login} size={20} />
                          </span>
                        ))}
                        {overflow > 0 && (
                          <span className="team-card__av team-card__av--more">+{overflow}</span>
                        )}
                      </div>
                    );
                  })()}
                  <span><strong>{t.memberCount}</strong> member{t.memberCount === 1 ? '' : 's'}</span>
                  <span className="team-home__sep">·</span>
                  <span><strong>{t.inboxCount}</strong> in inbox</span>
                </div>

                <footer className="team-card__actions">
                  <button
                    type="button"
                    className="button button--primary"
                    onClick={() => onOpenTeam(t.id)}
                  >
                    Open team home
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => void startManage(t.id)}
                  >
                    Manage
                  </button>
                </footer>
              </article>
            ))}

            <button
              type="button"
              className="team-card team-card--new"
              onClick={() => setEditing('new')}
            >
              <span className="team-card-new__plus" aria-hidden="true">+</span>
              <span className="team-card-new__label">New team</span>
              <span className="team-card-new__hint">
                Group people whose PRs you want to track together.
              </span>
            </button>
          </div>
        )}
      </div>

      {editing && (
        <TeamEditorModal
          team={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      )}
    </section>
  );
}

export default TeamsManagePage;
