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
import TeamEditorModal from './TeamEditorModal';
import type {
  MyPrColumnSlug,
  PullRequestDto,
  TeamColumnsResponse,
  TeamDto,
} from '../types';

type Props = {
  teamId: number;
  /** Open the existing kanban view for this team. */
  onOpenKanban: () => void;
  /** Open a PR in the in-app PR detail view. The team's PRs are already
   *  tracked through watched-repo sync so they all open in-app — no need
   *  to fall back to github.com. */
  onSelectPr: (owner: string, repo: string, prNumber: number) => void;
  /** Back to whatever surface launched the team page (settings, home, etc.). */
  onBack: () => void;
};

const TEAM_KEY = (id: number) => `team:${id}`;
const COLUMNS_KEY = (id: number) => `team:${id}:columns`;
const IN_FLIGHT_LIMIT = 6;
const PER_COLUMN = 8;

const EMPTY_COLUMNS: TeamColumnsResponse = {
  columns: { drafting: [], waiting_on_review: [], needs_changes: [], ready_to_merge: [], recently_merged: [], handled: [] },
  totals: { drafting: 0, waiting_on_review: 0, needs_changes: 0, ready_to_merge: 0, recently_merged: 0, handled: 0 },
  repoTotals: {},
};

/** Active columns shown in "Currently in flight" — handled / recently_merged
 *  are excluded since the panel is about open work. */
const FLIGHT_COLUMNS: MyPrColumnSlug[] = ['needs_changes', 'waiting_on_review', 'ready_to_merge', 'drafting'];

type FlightPill = { label: string; tone: 'changes' | 'opened' | 'approved' | 'draft' };
const PILL_BY_COLUMN: Record<MyPrColumnSlug, FlightPill | null> = {
  needs_changes: { label: 'CHANGES', tone: 'changes' },
  ready_to_merge: { label: 'APPROVED', tone: 'approved' },
  drafting: { label: 'DRAFT', tone: 'draft' },
  waiting_on_review: { label: 'OPENED', tone: 'opened' },
  recently_merged: null,
  handled: null,
};

const BANNER_BY_TONE: Record<FlightPill['tone'], string> = {
  changes: 'red',
  opened: 'blue',
  approved: 'green',
  draft: 'gray',
};

function shortRepoName(full: string): string {
  return full.includes('/') ? full.split('/')[1] : full;
}

function formatCreated(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
}

type Flight = {
  pr: PullRequestDto;
  pill: FlightPill;
  banner: string;
};

function pickFlightPRs(data: TeamColumnsResponse): Flight[] {
  const out: Flight[] = [];
  for (const col of FLIGHT_COLUMNS) {
    const pill = PILL_BY_COLUMN[col];
    if (!pill) continue;
    for (const pr of data.columns[col] ?? []) {
      out.push({ pr, pill, banner: BANNER_BY_TONE[pill.tone] });
      if (out.length >= IN_FLIGHT_LIMIT) return out;
    }
  }
  return out;
}

function TeamHomePage({ teamId, onOpenKanban, onSelectPr, onBack }: Props) {
  const [team, setTeam] = useState<TeamDto | null>(() => getCached<TeamDto>(TEAM_KEY(teamId)) ?? null);
  const [columnsData, setColumnsData] = useState<TeamColumnsResponse>(() =>
    getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) ?? EMPTY_COLUMNS);
  const [loading, setLoading] = useState(() =>
    getCached<TeamDto>(TEAM_KEY(teamId)) === undefined
    && getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) === undefined);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  /** Re-fetch the team record only — no need to re-fan out the kanban
   *  columns when the user just edited name/avatar/colour/description. */
  const refreshTeam = async () => {
    try {
      const t = await window.bridge.getTeam(teamId);
      setTeam(t);
      setCached(TEAM_KEY(teamId), t);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    let cancelled = false;
    const hasCached = getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) !== undefined;
    if (!hasCached) setLoading(true);
    setError(null);
    (async () => {
      try {
        const [t, cols] = await Promise.all([
          window.bridge.getTeam(teamId),
          window.bridge.getTeamPullsByColumn(teamId, PER_COLUMN, false),
        ]);
        if (cancelled) return;
        setTeam(t);
        setColumnsData(cols);
        setCached(TEAM_KEY(teamId), t);
        setCached(COLUMNS_KEY(teamId), cols);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [teamId]);

  const totals = columnsData.totals;
  const inFlight = totals.needs_changes + totals.waiting_on_review + totals.ready_to_merge + totals.drafting;
  const needReview = totals.waiting_on_review;
  const mergedThisWeek = totals.recently_merged;

  const flightPRs = pickFlightPRs(columnsData);

  // Watching-repos panel is derived from repoTotals — distinct repos with at
  // least one open PR. Sorted by PR count desc so the busiest repo shows on
  // top, matching the visual hierarchy in the mockup.
  const repoEntries = Object.entries(columnsData.repoTotals ?? {})
    .filter(([, n]) => n > 0)
    .sort(([, a], [, b]) => b - a);

  const memberPreview = team?.members ?? [];
  const memberCount = memberPreview.length;
  const heroAvatars = memberPreview.slice(0, 4);
  const heroOverflow = Math.max(0, memberCount - heroAvatars.length);
  const createdLabel = formatCreated(team?.createdAt);

  return (
    <section className="team-home">
      <header className="team-home__head">
        <nav className="team-home__breadcrumb">
          <button type="button" className="team-home__crumb" onClick={onBack}>
            ← Pull requests
          </button>
          <span className="team-home__sep">/</span>
          <button type="button" className="team-home__crumb" onClick={onBack}>
            Teams
          </button>
          <span className="team-home__sep">/</span>
          <span className="team-home__crumb-current">{team?.name ?? '…'}</span>
        </nav>
      </header>

      <div className="team-home__body">
        {loading && !team && <div className="repo-loading">Loading…</div>}
        {error && <div className="repo-error">{error}</div>}

        {team && (
          <>
            <div className="team-hero">
              <span
                className={`team-avatar team-hero__avatar team-avatar--${team.color}`}
                aria-hidden="true"
              >
                {team.avatar}
              </span>
              <div className="team-hero__main">
                <h1 className="team-hero__name">{team.name}</h1>
                {team.description && (
                  <p className="team-hero__desc">{team.description}</p>
                )}
                <div className="team-hero__meta">
                  {memberCount > 0 && (
                    <div className="team-hero__avatars">
                      {heroAvatars.map(login => (
                        <span key={login} className="team-hero__av">
                          <Avatar login={login} size={22} />
                        </span>
                      ))}
                      {heroOverflow > 0 && (
                        <span className="team-hero__av team-hero__av--more">+{heroOverflow}</span>
                      )}
                    </div>
                  )}
                  <span><strong>{memberCount}</strong> member{memberCount === 1 ? '' : 's'}</span>
                  {repoEntries.length > 0 && (
                    <>
                      <span className="team-home__sep">·</span>
                      <span>watching <strong>{repoEntries.length}</strong> repo{repoEntries.length === 1 ? '' : 's'}</span>
                    </>
                  )}
                  {createdLabel && (
                    <>
                      <span className="team-home__sep">·</span>
                      <span>created {createdLabel}</span>
                    </>
                  )}
                </div>
              </div>
              <div className="team-hero__actions">
                <button type="button" className="button button--secondary" onClick={() => setEditing(true)}>
                  Edit team
                </button>
                <button type="button" className="button button--primary" onClick={onOpenKanban}>
                  Open team kanban →
                </button>
              </div>
            </div>

            <div className="team-home__stats">
              <div className="team-stat">
                <div className="team-stat__num">{inFlight}</div>
                <div className="team-stat__lbl">PRs in flight</div>
              </div>
              <div className="team-stat">
                <div className={`team-stat__num${needReview > 0 ? ' team-stat__num--alert' : ''}`}>{needReview}</div>
                <div className="team-stat__lbl">Need your review</div>
              </div>
              <div className="team-stat">
                <div className={`team-stat__num${mergedThisWeek > 0 ? ' team-stat__num--success' : ''}`}>{mergedThisWeek}</div>
                <div className="team-stat__lbl">Merged this week</div>
              </div>
            </div>

            <div className="team-home__content">
              <div className="team-home__main">
                <div className="team-panel">
                  <div className="team-panel__head">
                    <h3>Currently in flight</h3>
                    <span className="team-panel__count">{inFlight}</span>
                    <button type="button" className="team-panel__link" onClick={onOpenKanban}>
                      View all in kanban →
                    </button>
                  </div>
                  {flightPRs.length === 0 ? (
                    <div className="team-panel__empty">
                      No open PRs from <b>{team.name}</b>'s members in your watched repos right now.
                    </div>
                  ) : (
                    <div className="team-flight">
                      {flightPRs.map(({ pr, pill, banner }) => (
                        <button
                          key={pr.id}
                          type="button"
                          className="pr-mini"
                          onClick={() => {
                            const slash = pr.repo.indexOf('/');
                            if (slash <= 0) return;
                            onSelectPr(pr.repo.slice(0, slash), pr.repo.slice(slash + 1), pr.number);
                          }}
                          title={pr.title}
                        >
                          <span className={`pr-mini__banner pr-mini__banner--${banner}`} aria-hidden="true" />
                          <span className="pr-mini__num">{shortRepoName(pr.repo)} #{pr.number}</span>
                          <span className="pr-mini__title">{pr.title}</span>
                          {pr.author && <span className="pr-mini__author">@{pr.author}</span>}
                          <span className={`pr-mini__state pr-mini__state--${pill.tone}`}>{pill.label}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              <aside className="team-home__side">
                <div className="team-panel team-panel--emphasis">
                  <div className="team-panel__head">
                    <h3>Members</h3>
                    <span className="team-panel__count">{memberCount}</span>
                  </div>
                  {memberCount === 0 ? (
                    <div className="team-panel__empty">No members yet.</div>
                  ) : (
                    <div className="team-members">
                      {memberPreview.map(login => (
                        <div key={login} className="team-member">
                          <Avatar login={login} size={32} className="team-member__av" />
                          <div className="team-member__info">
                            <div className="team-member__name">@{login}</div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {repoEntries.length > 0 && (
                  <div className="team-panel">
                    <div className="team-panel__head">
                      <h3>Watching repos</h3>
                      <span className="team-panel__count">{repoEntries.length}</span>
                    </div>
                    <div className="team-repos">
                      {repoEntries.map(([fullName, count]) => (
                        <div key={fullName} className="team-repo">
                          <span className="team-repo__name">{fullName}</span>
                          <span className="team-repo__count">{count} PR{count === 1 ? '' : 's'}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </aside>
            </div>
          </>
        )}
      </div>

      {editing && team && (
        <TeamEditorModal
          team={team}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await refreshTeam();
          }}
        />
      )}
    </section>
  );
}

export default TeamHomePage;
