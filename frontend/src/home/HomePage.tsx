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
import type { RecentEventDto, TeamSummaryDto, UserProfileDto, WatchedRepoDto } from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import AddRepoModal from '../AddRepoModal';
import ActivityRow from '../ActivityRow';
import { groupRecentEvents } from '../activityNarrative';
import { bucketize } from '../prBuckets';
import { getCached, setCached } from '../dataCache';
import ContributionCard from './ContributionCard';
import InboxSection from './InboxSection';
import TeamsGrid from './TeamsGrid';
import WatchedReposGrid from './WatchedReposGrid';

// The GitHub-sourced flows (profile, recent/following events, orgs) used
// to be cached client-side via getCached/setCached for instant-paint on
// tab return. The backend now persists those in SQLite via
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
  /** Inbox "View task" — opens the task detail page. */
  onOpenTask?: (threadId: string, taskId: string) => void;
  /** Inbox "See all" — opens the notification center. */
  onOpenNotifications?: () => void;
  /** Contribution-strip "See all" — opens the PR-activity view. */
  onSeeAllActivity?: (kind: 'reviewed' | 'contributed') => void;
};

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

function HomePage({ onSelectRepo, onGoToMyPrs, onOpenTeam, onGoToTeams, onOpenTask, onOpenNotifications, onSeeAllActivity }: Props) {
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
  // the module cache so a return to this tab renders instantly. The
  // GitHub-sourced flows below (profile, events, following) start empty
  // and populate from the backend's DB cache on the load() call.
  const cachedWatched = getCached<WatchedRepoDto[]>(KEY_WATCHED);
  const cachedPrs = getCached<DashboardPR[]>(KEY_PRS);
  const cachedTeams = getCached<TeamSummaryDto[]>(KEY_TEAMS);

  const [profile, setProfile] = useState<UserProfileDto | null>(null);
  const [repos, setRepos] = useState<WatchedRepoDto[]>(cachedWatched ?? []);
  const [events, setEvents] = useState<RecentEventDto[]>([]);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [prs, setPrs] = useState<DashboardPR[] | null>(cachedPrs ?? null);
  const [teams, setTeams] = useState<TeamSummaryDto[]>(cachedTeams ?? []);
  // Only show the first-load spinner when we have nothing to paint yet.
  const [loading, setLoading] = useState(cachedWatched === undefined);
  const [showModal, setShowModal] = useState(false);

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
    }
    // Teams + PR list still live in localStorage (DB-backed on the backend
    // already, but reads aren't free; the cache makes tab return instant).
    window.bridge.listTeams()
      .then(v => { setTeams(v); setCached(KEY_TEAMS, v); })
      .catch(() => {});
    window.bridge.fetchDashboardPrs()
      .then(v => { setPrs(v); setCached(KEY_PRS, v); })
      .catch(() => {});
  }

  const prInboxCounts = useMemo(() => {
    if (!prs) return null;
    let mine = 0;
    let awaiting = 0;
    let flagged = 0;
    for (const pr of prs) {
      if (bucketize(pr) !== 'inbox') continue;
      if (pr.origin === 'AUTHORED') mine++;
      else if (pr.origin === 'REVIEW_REQUESTED') awaiting++;
      if (pr.attentionReason !== null && pr.attentionReason !== 'MINE') flagged++;
    }
    return { mine, awaiting, flagged };
  }, [prs]);


  async function handleAdded() {
    // The modal already watched + cloned the repo; re-read the list so the
    // new (now clone-backed) row lands with its server-assigned fields.
    const fresh = await window.bridge.getWatchedRepos();
    setRepos(fresh);
    setCached(KEY_WATCHED, fresh);
  }

  async function handleRemove(owner: string, repo: string) {
    await window.bridge.removeWatchedRepo(owner, repo).catch(() => {});
    setRepos(prev => {
      const next = prev.filter(r => !(r.owner === owner && r.repo === repo));
      setCached(KEY_WATCHED, next);
      return next;
    });
  }

  return (
    <div className="home-page">
      <main className="home-main">

      {/* ── Contribution card: graph + bio + reviewed/contributed strip ── */}
      <ContributionCard
        profile={profile}
        prs={prs}
        onOpenPr={(owner, repo, prNumber) => onSelectRepo(owner, repo, prNumber)}
        onSeeAllActivity={onSeeAllActivity}
      />

      {/* ── Review CTA banner ── */}
      <button
        className="home-cta-card"
        onClick={onGoToMyPrs}
        type="button"
      >
        <div className="home-cta-card__icon" aria-hidden="true">✦</div>
        <div className="home-cta-card__body">
          <div className="home-cta-card__title">Start reviewing all my PRs</div>
          {prInboxCounts && (
            <div className="home-cta-card__counts">
              <span className="home-cta-card__count">
                <b>{prInboxCounts.awaiting}</b> awaiting my review
              </span>
              <span className="home-cta-card__count">
                <b>{prInboxCounts.mine}</b> of my PRs
              </span>
              <span className="home-cta-card__count">
                <b>{prInboxCounts.flagged}</b> flagged
              </span>
            </div>
          )}
        </div>
        <span className="home-cta-card__open">Open <span aria-hidden="true">›</span></span>
      </button>

      {/* ── Inbox: app notifications + PRs that need the user ── */}
      <InboxSection
        prs={prs}
        onOpenPr={(owner, repo, prNumber) => onSelectRepo(owner, repo, prNumber)}
        onOpenTask={onOpenTask}
        onSeeAll={() => onOpenNotifications?.()}
        onPrsChanged={v => { setPrs(v); setCached(KEY_PRS, v); }}
      />

      {/* ── Repos you watch ── */}
      <WatchedReposGrid
        repos={repos}
        loading={loading}
        events={events}
        onSelectRepo={(owner, repo) => onSelectRepo(owner, repo)}
        onWatch={() => setShowModal(true)}
        onRemove={(owner, repo) => { void handleRemove(owner, repo); }}
      />

      {/* ── Teams you track ── */}
      <TeamsGrid teams={teams} onOpenTeam={onOpenTeam} onGoToTeams={onGoToTeams} />

      </main>

      {/* ── Right panel: Your recent activity + Following activity ── */}
      <aside className="home-side">
        <section className="home-side-section home-side-section--recent-activity">
          <h3 className="home-side-section__title">Recent activity</h3>
          {events.length === 0 ? (
            <div className="hp-empty">No recent activity yet.</div>
          ) : (
            <div className="home-following-list">
              {groupRecentEvents(events).slice(0, 10).map((e, i) => (
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
        </section>

        <section className="home-side-section">
          <h3 className="home-side-section__title">From people you follow</h3>
          {followingEvents.length === 0 ? (
            <div className="hp-empty">No recent activity from people you follow.</div>
          ) : (
            <div className="home-following-list">
              {groupRecentEvents(followingEvents).map((e, i) => (
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
        </section>
      </aside>

      {showModal && (
        <AddRepoModal
          watchedRepos={repos}
          onAdded={() => { void handleAdded(); }}
          onClose={() => setShowModal(false)}
        />
      )}
    </div>
  );
}

export default HomePage;
