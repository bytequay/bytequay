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
import { useEffect, useMemo, useState } from 'react';
import type { PullRequestDto, RecentEventDto, StatPeriods, TeamSummaryDto, UserOrgDto, UserProfileDto, UserStatsDto, WatchedRepoDto } from './types';
import Avatar from './Avatar';
import AddRepoModal from './AddRepoModal';
import ActivityRow from './ActivityRow';
import DailyCardSection from './DailyCardSection';
import YearInCodeHeatmap from './YearInCodeHeatmap';
import { bucketize } from './prBuckets';
import { getCached, setCached } from './dataCache';

// The five GitHub-sourced flows (profile, recent/following events, stats,
// orgs) used to be cached client-side via getCached/setCached for
// instant-paint on tab return. The backend now persists those in SQLite via
// GithubHomeCacheRefreshJob, so the frontend reads straight from the
// already-cached backend on every mount and the localStorage layer is gone
// for those keys. The remaining keys back DB-sourced flows that didn't
// move — keeping their localStorage cache preserves the instant-paint UX.
const KEY_WATCHED = 'home:watchedRepos';
const KEY_PRS = 'prs:list';
const KEY_TEAMS = 'home:teams';

type Props = {
  /** Navigate into the repo page in-app. {@code prNumber} is honoured by
   *  RepoDetailPage to auto-select that PR after the pulls load. */
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onGoToMyPrs: () => void;
  /** Set by App.tsx — opens the team detail page for the given id. */
  onOpenTeam?: (teamId: number) => void;
  /** Set by App.tsx — jumps to Settings → Teams (to create a new team). */
  onGoToTeams?: () => void;
};

type StatPeriod = 'today' | 'week' | 'month';

function openUrl(url: string) {
  void window.bridge.openExternal(url);
}

/** Match a github.com URL of the form {@code /<owner>/<repo>} or
 *  {@code /<owner>/<repo>/pull/<n>}. Returns null for anything else
 *  (profiles, issues, gists, raw, …). */
export function parseGithubUrl(url: string): { owner: string; repo: string; prNumber?: number } | null {
  try {
    const u = new URL(url);
    if (u.hostname !== 'github.com') return null;
    // Drop leading slash and split. Path shapes:
    //   /<owner>/<repo>             → repo
    //   /<owner>/<repo>/pull/<n>    → PR
    const parts = u.pathname.replace(/^\//, '').split('/');
    if (parts.length < 2) return null;
    const [owner, repo] = parts;
    if (!owner || !repo) return null;
    if (parts.length === 2) return { owner, repo };
    if (parts.length >= 4 && parts[2] === 'pull') {
      const n = Number(parts[3]);
      if (Number.isFinite(n) && n > 0) return { owner, repo, prNumber: n };
    }
    return null;
  }
  catch {
    return null;
  }
}

function formatRelativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  if (hrs < 48) return 'Yesterday';
  return `${Math.round(hrs / 24)} days ago`;
}

function statValue(periods: StatPeriods, period: StatPeriod): number {
  if (period === 'today') return periods.today;
  if (period === 'week') return periods.thisWeek;
  return periods.thisMonth;
}

function repoStatus(events: RecentEventDto[], owner: string, repo: string): { text: string; variant: 'active' | 'commits' | 'none' } {
  const fullName = `${owner}/${repo}`;
  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  const todayStartMs = todayStart.getTime();
  let commitCount = 0;
  let hasActivity = false;
  for (const e of events) {
    if (e.repo !== fullName) continue;
    if (new Date(e.createdAt).getTime() >= todayStartMs) {
      hasActivity = true;
      if (e.type === 'PushEvent') commitCount += e.commitCount || 1;
    }
  }
  if (commitCount > 0) return { text: `${commitCount} new commit${commitCount !== 1 ? 's' : ''} today`, variant: 'commits' };
  if (hasActivity) return { text: 'Active today', variant: 'active' };
  return { text: 'No recent activity', variant: 'none' };
}


const STAT_CARDS: {
  key: keyof Omit<UserStatsDto, 'updatedAt'>;
  icon: string;
  iconBg: string;
  iconColor: string;
  label: string;
}[] = [
  { key: 'pushes', icon: '↑', iconBg: 'rgba(251,191,36,0.18)', iconColor: '#d97706', label: 'Pushes' },
  { key: 'prsCreated', icon: '✓', iconBg: 'rgba(34,197,94,0.18)', iconColor: '#16a34a', label: 'PR opened' },
  { key: 'prsReviewed', icon: '◎', iconBg: 'rgba(250,204,21,0.18)', iconColor: '#ca8a04', label: 'PRs reviewed' },
  { key: 'comments', icon: '✉', iconBg: 'rgba(236,72,153,0.18)', iconColor: '#be185d', label: 'Comments left' },
];

function EditProfileModal({
  profile,
  onSave,
  onClose,
}: {
  profile: UserProfileDto;
  onSave: (name: string, bio: string, location: string) => Promise<void>;
  onClose: () => void;
}) {
  const [name, setName] = useState(profile.name ?? '');
  const [bio, setBio] = useState(profile.bio ?? '');
  const [location, setLocation] = useState(profile.location ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave(name, bio, location);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal__header">
          <h2 className="modal__title">Edit profile</h2>
          <button className="modal__close" onClick={onClose}>✕</button>
        </div>
        <div className="edit-profile-form">
          <label className="edit-profile-label">Name</label>
          <input className="edit-profile-input" value={name} onChange={e => setName(e.target.value)} placeholder="Your name" />
          <label className="edit-profile-label">Bio</label>
          <textarea className="edit-profile-textarea" value={bio} onChange={e => setBio(e.target.value)} rows={3} placeholder="A short bio" />
          <label className="edit-profile-label">Location</label>
          <input className="edit-profile-input" value={location} onChange={e => setLocation(e.target.value)} placeholder="City, Country" />
          {error && <p className="edit-profile-error">{error}</p>}
          <div className="edit-profile-actions">
            <button className="button button--primary" onClick={handleSave} disabled={saving}>{saving ? 'Saving…' : 'Save'}</button>
            <button className="button button--secondary" onClick={onClose}>Cancel</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function HomePage({ onSelectRepo, onGoToMyPrs, onOpenTeam, onGoToTeams }: Props) {
  /** Smart router for activity-row link clicks: keep github.com repo and
   *  PR links inside the app (RepoDetailPage will auto-select the PR via
   *  initialPrNumber), and only fall out to the system browser for
   *  things we can't render in-app (issues, profiles, gists, etc.). */
  const handleActivityLink = (url: string) => {
    const parsed = parseGithubUrl(url);
    if (parsed) {
      onSelectRepo(parsed.owner, parsed.repo, parsed.prNumber);
      return;
    }
    openUrl(url);
  };

  // Seed the still-client-cached state (watched repos, teams, PR list) from
  // the module cache so a return to this tab renders instantly. The five
  // GitHub-sourced flows below (profile, events, following, stats, orgs)
  // start empty and populate from the backend's DB cache on the load() call.
  const cachedWatched = getCached<WatchedRepoDto[]>(KEY_WATCHED);
  const cachedPrs = getCached<PullRequestDto[]>(KEY_PRS);
  const cachedTeams = getCached<TeamSummaryDto[]>(KEY_TEAMS);

  const [profile, setProfile] = useState<UserProfileDto | null>(null);
  const [repos, setRepos] = useState<WatchedRepoDto[]>(cachedWatched ?? []);
  const [events, setEvents] = useState<RecentEventDto[]>([]);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [stats, setStats] = useState<UserStatsDto | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(cachedPrs ?? null);
  const [teams, setTeams] = useState<TeamSummaryDto[]>(cachedTeams ?? []);
  const [orgs, setOrgs] = useState<UserOrgDto[]>([]);
  // Only show the first-load spinner when we have nothing to paint yet.
  const [loading, setLoading] = useState(cachedWatched === undefined);
  const [period, setPeriod] = useState<StatPeriod>('week');
  const [showModal, setShowModal] = useState(false);
  const [showEditProfile, setShowEditProfile] = useState(false);
  const [refreshingStats, setRefreshingStats] = useState(false);

  useEffect(() => {
    void load();
  }, []);

  async function load() {
    // Silently refresh the profile + watched-repos pair first.
    const [profileResult, reposResult] = await Promise.allSettled([
      window.bridge.getUserProfile(),
      window.bridge.getWatchedRepos(),
    ]);
    const loadedProfile = profileResult.status === 'fulfilled' ? profileResult.value : null;
    if (loadedProfile) {
      setProfile(loadedProfile);
    }
    if (reposResult.status === 'fulfilled') {
      setRepos(reposResult.value);
      setCached(KEY_WATCHED, reposResult.value);
    }
    setLoading(false);

    if (loadedProfile) {
      window.bridge.getRecentActivity(loadedProfile.login)
        .then(setEvents)
        .catch(() => {});
      window.bridge.getFollowingActivity(loadedProfile.login)
        .then(setFollowingEvents)
        .catch(() => {});
      window.bridge.getUserStats(loadedProfile.login)
        .then(setStats)
        .catch(() => {});
    }
    // Orgs read from the backend's DB cache regardless of profile — the
    // backend keys orgs by the stored GITHUB_LOGIN, so the call works even
    // before profile lands on screen.
    window.bridge.getUserOrgs()
      .then(setOrgs)
      .catch(() => {});
    // Teams + PR list still live in localStorage (DB-backed on the backend
    // already, but reads aren't free; the cache makes tab return instant).
    window.bridge.listTeams()
      .then(v => { setTeams(v); setCached(KEY_TEAMS, v); })
      .catch(() => {});
    window.bridge.fetchPrs()
      .then(v => { setPrs(v); setCached(KEY_PRS, v); })
      .catch(() => {});
  }

  // Force-refresh the stats — pulls fresh GitHub events past the 5-min
  // backend cache. Wired to the "↻" button next to the period toggle so
  // users who just pushed don't have to wait for the cache to age out.
  async function refreshStats() {
    if (refreshingStats || !profile) return;
    setRefreshingStats(true);
    try {
      const v = await window.bridge.getUserStats(profile.login, true);
      setStats(v);
    } catch {
      // Best-effort: leave the existing stats showing, no error toast yet.
    } finally {
      setRefreshingStats(false);
    }
    window.bridge.fetchPrs()
      .then(v => { setPrs(v); setCached(KEY_PRS, v); })
      .catch(() => {});
    window.bridge.listTeams()
      .then(v => { setTeams(v); setCached(KEY_TEAMS, v); })
      .catch(() => {});
    window.bridge.getUserOrgs()
      .then(setOrgs)
      .catch(() => {});
  }

  const prInboxCounts = useMemo(() => {
    if (!prs) return null;
    let mine = 0;
    let awaiting = 0;
    for (const pr of prs) {
      if (bucketize(pr) !== 'inbox') continue;
      if (pr.origin === 'AUTHORED') mine++;
      else if (pr.origin === 'REVIEW_REQUESTED') awaiting++;
    }
    return { mine, awaiting };
  }, [prs]);


  async function handleAdd(owner: string, repo: string) {
    const added = await window.bridge.addWatchedRepo(owner, repo);
    setRepos(prev => {
      const next = [...prev, added];
      setCached(KEY_WATCHED, next);
      return next;
    });
  }

  async function handleRemove(owner: string, repo: string) {
    await window.bridge.removeWatchedRepo(owner, repo).catch(() => {});
    setRepos(prev => {
      const next = prev.filter(r => !(r.owner === owner && r.repo === repo));
      setCached(KEY_WATCHED, next);
      return next;
    });
  }

  async function handleSaveProfile(name: string, bio: string, location: string) {
    const updated = await window.bridge.updateProfile(name, bio, location);
    setProfile(updated);
  }

  return (
    <div className="home-page calm-page">
      <div className="home-main">

      {/* ── Top row: profile card + year-in-code chart ── */}
      <div className="home-top-row">
        <div className="home-card home-profile-card">
          {profile ? (
            <>
              <div className="hp-profile-header">
                <button className="hp-avatar-btn" onClick={() => openUrl(profile.htmlUrl)}>
                  <Avatar login={profile.login} size={52} className="avatar--profile" />
                </button>
                <div className="hp-profile-meta">
                  <div className="hp-name-row">
                    <span className="hp-name">{profile.name ?? profile.login}</span>
                    <button className="hp-edit-btn" onClick={() => setShowEditProfile(true)} title="Edit profile">✎</button>
                  </div>
                  <div className="hp-login">@{profile.login}</div>
                </div>
              </div>
              {profile.bio && (
                <div className="hp-bio">{profile.bio}</div>
              )}
              {profile.location && (
                <div className="hp-location">
                  <span className="hp-location-dot">📍</span>
                  {profile.location}
                </div>
              )}
              <div className="hp-stats-row">
                <div className="hp-stat">
                  <span className="hp-stat-val">{profile.publicRepos}</span>
                  <span className="hp-stat-label">REPOS</span>
                </div>
                <div className="hp-stat">
                  <span className="hp-stat-val">{profile.followers}</span>
                  <span className="hp-stat-label">FOLLOWERS</span>
                </div>
                <div className="hp-stat">
                  <span className="hp-stat-val">{profile.following}</span>
                  <span className="hp-stat-label">FOLLOWING</span>
                </div>
              </div>
              {(profile.company || profile.email || orgs.length > 0 || profile.hasSponsors) && (
                <div className="hp-contact">
                  {profile.company && (
                    <div className="hp-contact-row">
                      <span className="hp-contact-icon" aria-hidden="true">🏢</span>
                      <span className="hp-contact-value">{profile.company}</span>
                    </div>
                  )}
                  {profile.email && (
                    <div className="hp-contact-row">
                      <span className="hp-contact-icon" aria-hidden="true">✉</span>
                      <a className="hp-contact-value hp-contact-email" href={`mailto:${profile.email}`}>
                        {profile.email}
                      </a>
                    </div>
                  )}
                  {orgs.length > 0 && (
                    <div className="hp-contact-row hp-contact-row--orgs">
                      <span className="hp-contact-icon" aria-hidden="true">👥</span>
                      <div className="hp-org-chips">
                        {orgs.map(o => (
                          <a
                            key={o.login}
                            className="hp-org-chip"
                            href={o.htmlUrl}
                            target="_blank"
                            rel="noreferrer"
                            title={o.description || o.login}
                          >
                            <img
                              src={o.avatarUrl}
                              alt={o.login}
                              className="hp-org-chip__avatar"
                              loading="lazy"
                            />
                          </a>
                        ))}
                      </div>
                    </div>
                  )}
                  {profile.hasSponsors && (
                    <div className="hp-contact-row">
                      <span className="hp-contact-icon" aria-hidden="true">💖</span>
                      <a
                        className="hp-contact-value hp-contact-email"
                        href={`https://github.com/sponsors/${profile.login}`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Sponsors page
                      </a>
                    </div>
                  )}
                </div>
              )}
            </>
          ) : loading ? (
            <div className="hp-loading">Loading profile…</div>
          ) : null}
        </div>

        {/* Right column of the top row: year-in-code on top, daily card
            beneath it. Stacking them inside a flex column lets the two
            cards together match the profile card's height — without
            either one having to grow with awkward whitespace. */}
        <div className="home-top-right">
          <div className="home-card home-year-card">
            <div className="home-year-card__header">
              <div>
                <div className="home-year-card__title">Your year in code</div>
              </div>
              <span className="home-year-card__badge">Last 12 months</span>
            </div>
            {profile && <YearInCodeHeatmap login={profile.login} />}
          </div>
          <DailyCardSection />
        </div>
      </div>

      {/* ── Middle (primary): Start reviewing + Watched repos ── */}
      <div className="home-middle-row">
        <button
          className="home-cta-card"
          onClick={onGoToMyPrs}
          type="button"
        >
          <div className="home-cta-card__icon" aria-hidden="true">▶</div>
          <div className="home-cta-card__body">
            <div className="home-cta-card__title">Start reviewing all my PRs</div>
            <div className="home-cta-card__subtitle">Open the Inbox — your authored PRs and review requests in one place.</div>
            {prInboxCounts && (
              <div className="home-cta-card__counts">
                <span className="home-cta-card__count">
                  <span className="home-cta-card__count-num">{prInboxCounts.awaiting}</span>
                  awaiting my review
                </span>
                <span className="home-cta-card__count-sep">·</span>
                <span className="home-cta-card__count">
                  <span className="home-cta-card__count-num">{prInboxCounts.mine}</span>
                  of my PRs
                </span>
              </div>
            )}
          </div>
          <span className="home-cta-card__chevron" aria-hidden="true">→</span>
        </button>

        <div className="home-card home-repos-card home-repos-card--primary">
          <div className="home-card__header">
            <span className="home-card__title">Repos you watch</span>
            <button className="home-watch-btn" onClick={() => setShowModal(true)} type="button">
              + Watch a repo
            </button>
          </div>
          {loading ? (
            <div className="hp-loading">Loading…</div>
          ) : repos.length === 0 ? (
            <div className="hp-empty">No repos yet.</div>
          ) : (
            <div className="home-repo-list">
              {repos.map(r => {
                const status = repoStatus(events, r.owner, r.repo);
                const open = () => onSelectRepo(r.owner, r.repo);
                return (
                  <div
                    key={r.id}
                    className="home-repo-item"
                    role="button"
                    tabIndex={0}
                    onClick={open}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        open();
                      }
                    }}
                  >
                    <Avatar login={r.owner} size={24} className="home-repo-item__avatar" />
                    <div className="home-repo-item__info">
                      <span className="home-repo-item__name">{r.repo}</span>
                      <span className="home-repo-item__owner">{r.owner}</span>
                    </div>
                    {status.variant !== 'none' && (
                      <span className={`home-repo-status home-repo-status--${status.variant}`}>
                        {status.variant === 'active' && <span className="home-repo-status__dot" />}
                        {status.variant === 'commits' && <span className="home-repo-status__plus">⊕</span>}
                        {status.text}
                      </span>
                    )}
                    <button
                      className="home-repo-item__unwatch"
                      type="button"
                      aria-label={`Unwatch ${r.owner}/${r.repo}`}
                      title="Unwatch this repo"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleRemove(r.owner, r.repo);
                      }}
                    >
                      ✕
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* ── Teams you track ── */}
      <div className="home-teams-row">
        <div className="home-card home-teams-card">
          <div className="home-card__header">
            <span className="home-card__title">Teams you track</span>
            {onGoToTeams && (
              <button className="home-watch-btn" onClick={onGoToTeams} type="button">
                + Manage teams
              </button>
            )}
          </div>
          {teams.length === 0 ? (
            <div className="hp-empty">
              No teams yet.{' '}
              {onGoToTeams && (
                <button
                  type="button"
                  className="hp-empty__link"
                  onClick={onGoToTeams}
                >
                  Create one
                </button>
              )}{' '}to filter the Kanban by author.
            </div>
          ) : (
            <div className="home-team-list">
              {teams.map(t => (
                <div
                  key={t.id}
                  className="home-team-item"
                  role="button"
                  tabIndex={0}
                  onClick={() => onOpenTeam?.(t.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onOpenTeam?.(t.id);
                    }
                  }}
                >
                  <span
                    className={`team-avatar team-avatar--${t.color}`}
                    aria-hidden="true"
                  >
                    {t.avatar}
                  </span>
                  <div className="home-team-item__info">
                    <span className="home-team-item__name">{t.name}</span>
                    <span className="home-team-item__meta">
                      {t.memberCount} member{t.memberCount === 1 ? '' : 's'}
                    </span>
                  </div>
                  <span
                    className={`home-team-item__inbox${t.inboxCount > 0 ? ' home-team-item__inbox--active' : ''}`}
                    title={`${t.inboxCount} open PR${t.inboxCount === 1 ? '' : 's'} in inbox`}
                  >
                    {t.inboxCount}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* ── At a glance (title reflects the selected period) ── */}
      {stats && (() => {
        const glanceTitle = period === 'today'
          ? 'Today at a glance'
          : period === 'week'
            ? 'This week at a glance'
            : 'This month at a glance';
        // Sub-value pairs complementary context next to the main number.
        const subContext = (periods: StatPeriods) => {
          if (period === 'today') return { val: periods.thisWeek, label: 'this week' };
          if (period === 'week') return { val: periods.thisMonth, label: 'this month' };
          return { val: periods.thisWeek, label: 'this week' };
        };
        return (
          <div className="home-glance-section">
            <div className="home-glance-header">
              <span className="home-glance-title">{glanceTitle}</span>
              <div className="home-period-toggle">
                {(['today', 'week', 'month'] as StatPeriod[]).map(p => (
                  <button
                    key={p}
                    className={`home-period-btn${period === p ? ' home-period-btn--active' : ''}`}
                    onClick={() => setPeriod(p)}
                    type="button"
                  >
                    {p === 'today' ? 'Today' : p === 'week' ? 'Week' : 'Month'}
                  </button>
                ))}
                <button
                  type="button"
                  className="home-period-btn home-period-btn--refresh"
                  onClick={() => void refreshStats()}
                  disabled={refreshingStats}
                  title="Refresh stats from GitHub now (bypasses the cache)"
                  aria-label="Refresh stats"
                >
                  {refreshingStats ? '…' : '↻'}
                </button>
              </div>
            </div>
            <div className="home-stat-cards">
              {STAT_CARDS.map(card => {
                const periods = stats[card.key] as StatPeriods;
                const value = statValue(periods, period);
                const sub = subContext(periods);
                return (
                  <div key={card.key} className="home-stat-card">
                    <div className="home-stat-card__icon" style={{ background: card.iconBg, color: card.iconColor }}>
                      {card.icon}
                    </div>
                    <div className="home-stat-card__value">{value}</div>
                    <div className="home-stat-card__label">{card.label}</div>
                    <div className="home-stat-card__sub">
                      {sub.val > 0 ? `${sub.val} ${sub.label}` : `0 ${sub.label}`}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
      })()}

      </div>

      {/* ── Right sidebar: Your recent activity + Following activity ── */}
      <aside className="home-side">
        <div className="home-card home-mine-card">
          <div className="home-card__header">
            <span className="home-card__title">Your recent activity</span>
          </div>
          {events.length === 0 ? (
            <div className="hp-empty">No recent activity yet.</div>
          ) : (
            <div className="home-following-list">
              {events.slice(0, 6).map((e, i) => (
                <ActivityRow
                  key={i}
                  event={e}
                  actor={profile ? { login: profile.login, profileUrl: profile.htmlUrl } : null}
                  showActorName={false}
                  formatTime={formatRelativeTime}
                  onOpenUrl={handleActivityLink}
                />
              ))}
            </div>
          )}
        </div>

        <div className="home-card home-following-card">
          <div className="home-card__header">
            <span className="home-card__title">From people you follow</span>
          </div>
          {followingEvents.length === 0 ? (
            <div className="hp-empty">No recent activity from people you follow.</div>
          ) : (
            <div className="home-following-list">
              {followingEvents.map((e, i) => (
                <ActivityRow
                  key={i}
                  event={e}
                  actor={e.actorLogin
                    ? { login: e.actorLogin, profileUrl: `https://github.com/${e.actorLogin}` }
                    : null}
                  showActorName={true}
                  formatTime={formatRelativeTime}
                  onOpenUrl={handleActivityLink}
                />
              ))}
            </div>
          )}
        </div>
      </aside>

      {showModal && (
        <AddRepoModal
          watchedRepos={repos}
          onAdd={handleAdd}
          onClose={() => setShowModal(false)}
        />
      )}
      {showEditProfile && profile && (
        <EditProfileModal
          profile={profile}
          onSave={handleSaveProfile}
          onClose={() => setShowEditProfile(false)}
        />
      )}
    </div>
  );
}

export default HomePage;
