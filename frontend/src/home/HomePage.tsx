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
import type { PullRequestDto, RecentEventDto, UserProfileDto, WorkspaceDto } from '../types';
import AddRepoModal from '../AddRepoModal';
import ActivityRow from '../ActivityRow';
import { groupRecentEvents } from '../activityNarrative';
import ContributionCard from './ContributionCard';
import WatchedReposGrid from './WatchedReposGrid';
import { relativeTime } from '../relativeTime';
import { pullRequestNeedsReview } from '../pulls/model';
import {
  workspaceFromDto,
  workspaceIsUpdating,
  type Workspace,
} from '../workspaces/model';

type Props = {
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onGoToMyPrs: () => void;
  onOpenWorkspace: (workspaceId: string) => void;
};

function openUrl(url: string) {
  void window.bridge.openInAppBrowser(url);
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

/** Fill the title omitted by some GitHub activity payloads using the
 * existing cached single-PR endpoint. Duplicate events share one lookup. */
export async function enrichActivityPrTitles(events: RecentEventDto[]): Promise<RecentEventDto[]> {
  const missing = new Map(events
    .filter(event => event.type.startsWith('PullRequest') && event.prNumber > 0 && !event.prTitle)
    .map(event => [`${event.repo}#${event.prNumber}`, event] as const));
  const titles = new Map<string, string>();
  await Promise.all([...missing].map(async ([key, event]) => {
    const slash = event.repo.indexOf('/');
    if (slash < 1) return;
    try {
      const pr = await window.bridge.getRepoPull(
        event.repo.slice(0, slash),
        event.repo.slice(slash + 1),
        event.prNumber,
      );
      titles.set(key, pr.title);
    }
    catch { /* Leave the compact #number fallback when offline. */ }
  }));
  return events.map(event => ({
    ...event,
    prTitle: event.prTitle ?? titles.get(`${event.repo}#${event.prNumber}`) ?? null,
  }));
}

function HomePage({
  onSelectRepo,
  onGoToMyPrs,
  onOpenWorkspace,
}: Props) {
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

  const [profile, setProfile] = useState<UserProfileDto | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [events, setEvents] = useState<RecentEventDto[]>([]);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!workspaces.some(workspaceIsUpdating)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.listWorkspaces()
        .then(rows => setWorkspaces(rows.map(workspaceFromDto)))
        .catch(() => {});
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [workspaces]);

  async function load() {
    // Workspaces are backend-owned; localStorage is never authoritative.
    const [profileResult, reposResult] = await Promise.allSettled([
      window.bridge.getUserProfile(),
      window.bridge.listWorkspaces(),
    ]);
    const loadedProfile = profileResult.status === 'fulfilled' ? profileResult.value : null;
    if (loadedProfile) {
      setProfile(loadedProfile);
    }
    if (reposResult.status === 'fulfilled') {
      setWorkspaces(reposResult.value.map(workspaceFromDto));
    }
    else {
      setWorkspaceError(reposResult.reason instanceof Error
        ? reposResult.reason.message
        : String(reposResult.reason));
    }
    setLoading(false);

    if (loadedProfile) {
      window.bridge.getRecentActivity(loadedProfile.login)
        .then(enrichActivityPrTitles)
        .then(setEvents)
        .catch(() => {});
      window.bridge.getFollowingActivity(loadedProfile.login)
        .then(enrichActivityPrTitles)
        .then(setFollowingEvents)
        .catch(() => {});
    }
    window.bridge.fetchPrs(undefined, 0, 50)
      .then(page => { setPrs(page.pullRequests); })
      .catch(() => {});
  }

  const prInboxCounts = useMemo(() => {
    if (!prs) return null;
    let mine = 0;
    let awaiting = 0;
    let flagged = 0;
    for (const pr of prs) {
      if (pr.state !== 'open') continue;
      if (pr.origin === 'AUTHORED') mine++;
      else if (pullRequestNeedsReview(pr)) awaiting++;
      if (pr.attentionReason !== null && pr.attentionReason !== 'MINE') flagged++;
    }
    return { mine, awaiting, flagged };
  }, [prs]);


  function handleAdded(dto: WorkspaceDto) {
    const added = workspaceFromDto(dto);
    setWorkspaces(previous => [added, ...previous.filter(row => row.id !== added.id)]
      .sort((left, right) => left.displayOrder - right.displayOrder));
  }

  async function handleRemove(workspace: Workspace) {
    if (!confirm(`Stop watching ${workspace.selectedOwner}/${workspace.selectedRepo}?`)) return;
    setWorkspaceError(null);
    try {
      await window.bridge.removeWorkspace(workspace.id);
      setWorkspaces(previous => previous.filter(row => row.id !== workspace.id));
    }
    catch (failure) {
      setWorkspaceError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  async function handleRetry(workspaceId: string) {
    setWorkspaceError(null);
    try {
      const updated = workspaceFromDto(await window.bridge.syncWorkspace(workspaceId));
      setWorkspaces(previous => previous.map(row => row.id === updated.id ? updated : row));
    }
    catch (failure) {
      setWorkspaceError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  return (
    <div className="home-page">
      <main className="home-main">

      {/* ── Contribution card: graph + bio ── */}
      <ContributionCard profile={profile} />

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

      <section className="home-section">
        <div className="home-section__header">
          <span className="home-section__title">Pull requests needing attention</span>
          <button className="home-section__action" type="button" onClick={onGoToMyPrs}>
            See all
          </button>
        </div>
        <div className="home-following-list">
          {(prs ?? [])
            .filter(pr => pr.state === 'open')
            .slice(0, 5)
            .map(pr => {
              const slash = pr.repo.indexOf('/');
              return (
                <button
                  type="button"
                  className="home-cta-card"
                  key={pr.id}
                  onClick={() => onSelectRepo(
                    pr.repo.slice(0, slash),
                    pr.repo.slice(slash + 1),
                    pr.number,
                  )}
                >
                  <span className="home-cta-card__body">
                    <span className="home-cta-card__title">{pr.title}</span>
                    <span className="home-cta-card__counts">
                      {pr.repo} · #{pr.number} · {relativeTime(pr.updatedAt)}
                    </span>
                  </span>
                  <span className="home-cta-card__open">Open <span aria-hidden="true">›</span></span>
                </button>
              );
            })}
          {prs?.length === 0 && <div className="hp-empty">No pull requests yet.</div>}
        </div>
      </section>

      {/* ── Repos you watch ── */}
      {workspaceError && <div className="ws-error" role="alert">{workspaceError}</div>}
      <WatchedReposGrid
        workspaces={workspaces}
        loading={loading}
        events={events}
        onOpenWorkspace={onOpenWorkspace}
        onWatch={() => setShowModal(true)}
        onRetry={workspaceId => { void handleRetry(workspaceId); }}
        onRemove={workspace => { void handleRemove(workspace); }}
      />

      </main>

      {/* ── Right panel: GitHub activity + Following activity ── */}
      <aside className="home-side">
        <section className="home-side-section home-side-section--recent-activity">
          <h3 className="home-side-section__title">GitHub activity</h3>
          {events.length === 0 ? (
            <div className="hp-empty">No recent activity yet.</div>
          ) : (
            <div className="home-following-list">
              {groupRecentEvents(events).slice(0, 7).map((e, i) => (
                <ActivityRow
                  key={i}
                  event={e}
                  actor={null}
                  showActorName={false}
                  formatTime={iso => relativeTime(iso, { suffix: false })}
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
                    ? {
                      login: e.actorLogin,
                      profileUrl: `https://github.com/${e.actorLogin}`,
                      avatarUrl: e.actorAvatarUrl,
                    }
                    : null}
                  showActorName={true}
                  formatTime={iso => relativeTime(iso, { suffix: false })}
                  onOpenUrl={handleActivityLink}
                />
              ))}
            </div>
          )}
        </section>
      </aside>

      {showModal && (
        <AddRepoModal
          onAdded={handleAdded}
          onOpenWorkspace={onOpenWorkspace}
          onClose={() => setShowModal(false)}
        />
      )}
    </div>
  );
}

export default HomePage;
