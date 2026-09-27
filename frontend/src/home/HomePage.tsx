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
import { useJiraAttention } from '../jira/attention';
import { jiraIssueLocation } from '../jira/contracts';
import { useEffect, useMemo, useState } from 'react';
import type { PullRequestDto, RecentEventDto, UserProfileDto, WorkspaceDto } from '../types';
import AddRepoModal from '../AddRepoModal';
import ActivityRow from '../ActivityRow';
import { groupRecentEvents } from '../activityNarrative';
import AttentionList from './AttentionList';
import {
  ATTENTION_SEED,
  assignedIssues,
  attentionCandidates,
  loadHandled,
  saveHandled,
  type AttentionItem,
  type Handled,
} from './attention';
import ContributionCard from './ContributionCard';
import { getCached, getCachedFresh, setCached } from '../dataCache';
import WatchedReposGrid from './WatchedReposGrid';
import { relativeTime } from '../relativeTime';
import { zoomReference } from '../githubReference';
import { workspaceIsUpdating } from '../workspaces/model';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';

type Props = {
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onOpenWorkspace: (workspaceId: string) => void;
  onStartIssue?: (workspaceId: string, issueNumber: number) => void;
};

const ACTIVITY_TTL_MS = 5 * 60 * 1000;

// Home unmounts on every tab switch, so returning to it re-runs the same local
// reads from an empty screen. These hold the last rows purely to paint the
// first frame; every mount still fetches and overwrites them, so they carry no
// expiry.
const SEED = {
  profile: ATTENTION_SEED.profile,
  workspaces: 'home:workspaces',
  prs: 'home:prs',
  attention: ATTENTION_SEED.prs,
  issues: ATTENTION_SEED.issues,
};

function openUrl(url: string) {
  void window.bridge.openInAppBrowser(url);
}

/** Match a github.com URL of the form {@code /<owner>/<repo>} or
 *  {@code /<owner>/<repo>/pull/<n>}. Returns null for anything else
 *  (profiles, issues, gists, raw, …). */
function parseGithubUrl(url: string): { owner: string; repo: string; prNumber?: number } | null {
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
async function enrichActivityPrTitles(events: RecentEventDto[]): Promise<RecentEventDto[]> {
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
  onOpenWorkspace,
  onStartIssue,
}: Props) {
  /** PR links open over Home; other GitHub links use the embedded browser or repository list. */
  const handleActivityLink = (url: string) => {
    const parsed = parseGithubUrl(url);
    if (parsed) {
      onSelectRepo(parsed.owner, parsed.repo, parsed.prNumber);
      return;
    }
    openUrl(url);
  };

  const [profile, setProfile] = useState<UserProfileDto | null>(() => getCached<UserProfileDto>(SEED.profile) ?? null);
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>(() => getCached<WorkspaceDto[]>(SEED.workspaces) ?? []);
  const [events, setEvents] = useState<RecentEventDto[]>([]);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(() => getCached<PullRequestDto[]>(SEED.prs) ?? null);
  const [attentionPrs, setAttentionPrs] = useState<PullRequestDto[] | null>(
    () => getCached<PullRequestDto[]>(SEED.attention) ?? null);
  const [attentionError, setAttentionError] = useState<string | null>(null);
  const jira = useJiraAttention();
  const [issueAttentionError, setIssueAttentionError] = useState<string | null>(null);
  const [loading, setLoading] = useState(() => getCached(SEED.workspaces) === undefined);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const [issueItems, setIssueItems] = useState<AttentionItem[]>(() => getCached<AttentionItem[]>(SEED.issues) ?? []);

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    let active = true;
    const reload = () => {
      void window.bridge.fetchAttentionPrs().then(rows => {
        setCached(SEED.attention, rows);
        if (active) { setAttentionPrs(rows); setAttentionError(null); }
      }).catch(error => {
        if (active) setAttentionError(error instanceof Error ? error.message : String(error));
      });
    };
    const updated = (event: Event) => {
      const rows = (event as CustomEvent<PullRequestDto[]>).detail;
      setCached(SEED.attention, rows);
      setAttentionPrs(rows);
      setAttentionError(null);
    };
    const handledChanged = () => setHandled(loadHandled());
    reload();
    const stopSync = window.bridge.onPullRequestsSynced(reload);
    window.addEventListener('bytequay:pr-attention-updated', updated);
    window.addEventListener('bytequay:pr-monitor-changed', reload);
    window.addEventListener('bytequay:attention-handled-changed', handledChanged);
    return () => {
      active = false;
      stopSync();
      window.removeEventListener('bytequay:pr-attention-updated', updated);
      window.removeEventListener('bytequay:pr-monitor-changed', reload);
      window.removeEventListener('bytequay:attention-handled-changed', handledChanged);
    };
  }, []);

  useEffect(() => {
    if (!workspaces.some(workspaceIsUpdating)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.listWorkspaces()
        .then(rows => { setCached(SEED.workspaces, rows); setWorkspaces(rows); })
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
      setCached(SEED.profile, loadedProfile);
      setProfile(loadedProfile);
    }
    if (reposResult.status === 'fulfilled') {
      setCached(SEED.workspaces, reposResult.value);
      setWorkspaces(reposResult.value);
    }
    else {
      setWorkspaceError(reposResult.reason instanceof Error
        ? reposResult.reason.message
        : String(reposResult.reason));
    }
    setLoading(false);

    window.bridge.fetchPrs(undefined, 0, 50)
      .then(page => { setCached(SEED.prs, page.pullRequests); setPrs(page.pullRequests); })
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
    if (!login || workspaceKey === '') {
      setIssueAttentionError(null);
      setIssueItems([]);
      setCached(SEED.issues, []);
      return;
    }
    let cancelled = false;
    let request = 0;
    const reload = () => {
      const current = ++request;
      void Promise.allSettled(workspaceKey.split(',').map(workspaceId =>
        window.bridge.fetchWorkspaceIssueAttention(workspaceId)
          .then(snapshot => assignedIssues(snapshot, workspaceId, login))))
        .then(results => {
          if (cancelled || current !== request) return;
          const items = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
          setCached(SEED.issues, items);
          setIssueItems(items);
          setIssueAttentionError(results.some(result => result.status === 'rejected')
            ? 'Some work items could not be loaded. Open the workspace’s Issues tab to retry.' : null);
        });
    };
    const changed = () => { setIssueItems([]); reload(); };
    reload();
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    // Reuse the desktop's existing attention refresh cadence; these calls only read the local cache.
    window.addEventListener('bytequay:pr-attention-updated', reload);
    return () => {
      cancelled = true;
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
      window.removeEventListener('bytequay:pr-attention-updated', reload);
    };
  }, [login, workspaceKey]);

  const candidates = useMemo(
    () => attentionCandidates(attentionPrs ?? [], issueItems.map<AttentionItem>(item => ({
      ...item, workspaceName: workspaces.find(workspace => workspace.id === item.workspaceId)?.name,
    })).concat(jira.items), login),
    [attentionPrs, issueItems, jira.items, login, workspaces]);

  function applyHandled(next: Handled) {
    setHandled(next);
    saveHandled(next);
  }

  function handleAdded(dto: WorkspaceDto) {
    setWorkspaces(previous => {
      const next = [dto, ...previous.filter(row => row.id !== dto.id)]
        .sort((left, right) => left.displayOrder - right.displayOrder);
      setCached(SEED.workspaces, next);
      return next;
    });
  }

  return (
    <div className="home-page">
      <main className="home-main">
      {/* ── Contribution card: graph + bio ── */}
      <ContributionCard profile={profile} />

      {attentionError && <div className="ws-error" role="alert">Unable to load PR attention: {attentionError}</div>}
      {jira.error && <div className="ws-error" role="alert">{jira.error}</div>}
      {issueAttentionError && <div className="ws-error" role="alert">{issueAttentionError}</div>}
      <AttentionList
        candidates={candidates}
        handled={handled}
        viewerLogin={login ?? null}
        onHandledChange={applyHandled}
        onOpen={item => {
          if (item.jira) { zoomReference({ kind: 'jira', ...jiraIssueLocation(item.jira) }); return; }
          if (item.type === 'issue' && item.workspaceId !== null) {
            zoomReference({ kind: 'issue', repo: item.repo, number: item.number, workspaceId: item.workspaceId });
            return;
          }
          const slash = item.repo.indexOf('/');
          onSelectRepo(item.repo.slice(0, slash), item.repo.slice(slash + 1), item.number);
        }}
        onStartIssue={item => {
          if (item.jira) { zoomReference({ kind: 'jira', ...jiraIssueLocation(item.jira), start: true }); return; }
          if (item.workspaceId !== null) onStartIssue?.(item.workspaceId, item.number);
        }}
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
