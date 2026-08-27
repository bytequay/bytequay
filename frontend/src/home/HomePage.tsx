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
import AttentionList from './AttentionList';
import {
  assignedIssues,
  attentionCandidates,
  loadHandled,
  saveHandled,
  stillHandled,
  type AttentionItem,
  type Handled,
} from './attention';
import ContributionCard from './ContributionCard';
import { getCachedFresh, setCached } from '../dataCache';
import WatchedReposGrid from './WatchedReposGrid';
import { pullRequestNeedsReview } from '../pulls/model';
import { relativeTime } from '../relativeTime';
import {
  workspaceIsUpdating,
  workspaceIssueRoute,
} from '../workspaces/model';

type Props = {
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onGoToMyPrs: () => void;
  onOpenWorkspace: (workspaceId: string) => void;
};

const ACTIVITY_TTL_MS = 5 * 60 * 1000;

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
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [events, setEvents] = useState<RecentEventDto[]>([]);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const [issueItems, setIssueItems] = useState<AttentionItem[]>([]);

  useEffect(() => {
    void load();
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
      setWorkspaces(reposResult.value);
    }
    else {
      setWorkspaceError(reposResult.reason instanceof Error
        ? reposResult.reason.message
        : String(reposResult.reason));
    }
    setLoading(false);

    window.bridge.fetchPrs(undefined, 0, 50)
      .then(page => { setPrs(page.pullRequests); })
      .catch(() => {});
  }

  // Issue snapshots are local reads per watched repository; keyed on the id
  // list so the 1.5s workspace poll doesn't refire the fan-out.
  const workspaceKey = workspaces.map(workspace => workspace.id).join(',');
  const login = profile?.login;

  // Both activity feeds cost a REST page plus a per-PR title fan-out, and Home
  // remounts on every tab switch, so they are served from the shared cache and
  // only re-fetched once an entry ages out.
  useEffect(() => {
    if (!login) return;
    const feeds = [
      { key: `activity:recent:${login}`, read: () => window.bridge.getRecentActivity(login), apply: setEvents },
      { key: `activity:following:${login}`, read: () => window.bridge.getFollowingActivity(login), apply: setFollowingEvents },
    ];
    let cancelled = false;
    // The tick asks for fresh data because the cache it just wrote is younger
    // than the interval and would otherwise stretch the refresh to two ticks.
    const refresh = (allowCached: boolean) => {
      for (const feed of feeds) {
        const cached = allowCached ? getCachedFresh<RecentEventDto[]>(feed.key, ACTIVITY_TTL_MS) : undefined;
        if (cached) {
          feed.apply(cached);
          continue;
        }
        feed.read()
          .then(enrichActivityPrTitles)
          .then(fetched => {
            setCached(feed.key, fetched);
            if (!cancelled) feed.apply(fetched);
          })
          .catch(() => {});
      }
    };
    refresh(true);
    const resume = () => { if (!document.hidden) refresh(true); };
    const timer = window.setInterval(() => { if (!document.hidden) refresh(false); }, ACTIVITY_TTL_MS);
    document.addEventListener('visibilitychange', resume);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', resume);
    };
  }, [login]);

  useEffect(() => {
    if (!login || workspaceKey === '') return;
    let cancelled = false;
    void Promise.all(workspaceKey.split(',').map(workspaceId =>
      window.bridge.fetchWorkspaceIssues(workspaceId, 0, 100)
        .then(snapshot => assignedIssues(snapshot, workspaceId, login))
        .catch(() => [] as AttentionItem[])))
      .then(groups => { if (!cancelled) setIssueItems(groups.flat()); });
    return () => { cancelled = true; };
  }, [login, workspaceKey]);

  const candidates = useMemo(
    () => attentionCandidates(prs ?? [], issueItems),
    [issueItems, prs]);

  const snoozedCount = useMemo(() => {
    const now = Date.now();
    return candidates.filter(item => {
      const entry = handled[item.key];
      return entry !== undefined && entry.kind === 'snooze' && stillHandled(entry, item, now);
    }).length;
  }, [candidates, handled]);

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


  function applyHandled(next: Handled) {
    setHandled(next);
    saveHandled(next);
  }

  function handleAdded(dto: WorkspaceDto) {
    setWorkspaces(previous => [dto, ...previous.filter(row => row.id !== dto.id)]
      .sort((left, right) => left.displayOrder - right.displayOrder));
  }

  return (
    <div className="home-page">
      <main className="home-main">

      {/* ── Contribution card: graph + bio ── */}
      <ContributionCard profile={profile} />

      {/* ── Start review bar ── */}
      <div className="home-start">
        <span className="home-start__icon" aria-hidden="true">✦</span>
        <span className="home-start__title">Start reviewing all my PRs</span>
        <span className="home-start__divider" aria-hidden="true" />
        <div className="home-start__counts">
          <span><b className={prInboxCounts?.awaiting ? '' : 'is-zero'}>{prInboxCounts?.awaiting ?? 0}</b> awaiting me</span>
          <span><b className={prInboxCounts?.mine ? '' : 'is-zero'}>{prInboxCounts?.mine ?? 0}</b> mine</span>
          <span><b className={prInboxCounts?.flagged ? '' : 'is-zero'}>{prInboxCounts?.flagged ?? 0}</b> flagged</span>
        </div>
        <div className="home-start__spacer" />
        {snoozedCount > 0 && <span className="home-start__snoozed">{snoozedCount} snoozed</span>}
        <button className="home-start__go" type="button" onClick={onGoToMyPrs}>
          Start review <span aria-hidden="true">→</span>
        </button>
      </div>

      <AttentionList
        candidates={candidates}
        handled={handled}
        onHandledChange={applyHandled}
        onOpen={item => {
          if (item.type === 'issue' && item.workspaceId !== null) {
            window.location.hash = workspaceIssueRoute(item.workspaceId, item.number);
            return;
          }
          const slash = item.repo.indexOf('/');
          onSelectRepo(item.repo.slice(0, slash), item.repo.slice(slash + 1), item.number);
        }}
        onSeeAll={onGoToMyPrs}
      />

      {/* ── Repos you watch ── */}
      {workspaceError && <div className="ws-error" role="alert">{workspaceError}</div>}
      <WatchedReposGrid
        workspaces={workspaces}
        loading={loading}
        prs={prs ?? []}
        onOpenWorkspace={onOpenWorkspace}
        onWatch={() => setShowModal(true)}
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
