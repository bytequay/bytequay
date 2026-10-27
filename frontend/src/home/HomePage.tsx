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
import { linkTasks, useTaskAttention } from '../agents/attention';
import { jiraIssueLocation } from '../jira/contracts';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ChevronRight, X } from 'lucide-react';
import type { AgendaDto, AuthoredIssueDto, PullRequestDto, RecentEventDto, UserProfileDto, WorkspaceDto } from '../types';
import AddRepoModal from '../AddRepoModal';
import ActivityRow from '../ActivityRow';
import { groupRecentEvents } from '../activityNarrative';
import AttentionList from './AttentionList';
import {
  ATTENTION_SEED,
  ISSUE_ATTENTION_REFRESH_MS,
  issueAttentionItems,
  attentionCandidates,
  loadHandled,
  saveHandled,
  visibleAttention,
  type AttentionItem,
  type Handled,
} from './attention';
import ContributionCard from './ContributionCard';
import MyWork, { AllMyWork, useLocalWork } from './MyWork';
import { isOwnWork, myWorkItems } from './work';
import TodayList, { useWideWindow } from './TodayList';
import { showsToday, todayKeys, todayRows } from './today';
import { useTodayAgenda } from './useTodayAgenda';
import { getCached, getCachedFresh, setCached } from '../dataCache';
import WatchedReposGrid from './WatchedReposGrid';
import CirclesGrid from './CirclesGrid';
import { useTeams } from '../teams/useTeams';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { openActivityLink, zoomReference } from '../githubReference';
import { workspaceAgentTaskRoute, workspaceIsUpdating } from '../workspaces/model';
import { ISSUE_DEVELOPMENT_CHANGED } from '../workspaces/issueDevelopment';

type Props = {
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onOpenWorkspace: (workspaceId: string) => void;
  onStartIssue?: (workspaceId: string, issueNumber: number) => void;
  /** Shows All my work in place of Home: the same reads, so moving between the two re-reads nothing. */
  allWork?: boolean;
};

const ACTIVITY_TTL_MS = 5 * 60 * 1000;
// The backend searches GitHub for the viewer's issues at most this often; asking sooner only rereads its answer.
const AUTHORED_ISSUES_REFRESH_MS = 5 * 60 * 1000;

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
  authoredIssues: 'home:authored-issues',
};

/** Reserve Today's rows even when folded, so expanding it stays within the shared 15-entry limit. */
export function followingActivityRows(events: RecentEventDto[], agenda: AgendaDto | null): RecentEventDto[] {
  const todayCount = showsToday(agenda, 'global') ? todayRows(agenda.items).length : 0;
  const recapCount = todayCount > 0 && agenda?.recap != null ? 1 : 0;
  return groupRecentEvents(events).slice(0, Math.max(0, 15 - todayCount - recapCount));
}

/** Activity titles may reuse stored PRs, but must never import followed people's work into attention. */
export async function enrichActivityPrTitles(events: RecentEventDto[]): Promise<RecentEventDto[]> {
  const missing = new Map(events
    .filter(event => event.type.startsWith('PullRequest') && event.prNumber > 0 && !event.prTitle)
    .map(event => [`${event.repo}#${event.prNumber}`, event] as const));
  const titles = new Map<string, string>();
  await Promise.all([...missing].map(async ([key, event]) => {
    const slash = event.repo.indexOf('/');
    if (slash < 1) return;
    try {
      const pr = await window.bridge.findStoredPullRequest(event.repo, event.prNumber);
      if (pr !== null) titles.set(key, pr.title);
    }
    catch { /* Preserve the #number fallback when no stored title can be read. */ }
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
  allWork = false,
}: Props) {
  const [profile, setProfile] = useState<UserProfileDto | null>(() => getCached<UserProfileDto>(SEED.profile) ?? null);
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>(() => getCached<WorkspaceDto[]>(SEED.workspaces) ?? []);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(() => getCached<PullRequestDto[]>(SEED.prs) ?? null);
  const [attentionPrs, setAttentionPrs] = useState<PullRequestDto[] | null>(
    () => getCached<PullRequestDto[]>(SEED.attention) ?? null);
  const [attentionError, setAttentionError] = useState<string | null>(null);
  const jira = useJiraAttention();
  const teams = useTeams(profile?.login ?? null);
  const [issueAttentionError, setIssueAttentionError] = useState<string | null>(null);
  const [loading, setLoading] = useState(() => getCached(SEED.workspaces) === undefined);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  // Below the wide width the rail is a drawer and Today moves above My work.
  const wide = useWideWindow();
  const [drawer, setDrawer] = useState(false);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const today = useTodayAgenda();
  const onToday = useMemo(() => todayKeys(today.agenda), [today.agenda]);
  const [issueItems, setIssueItems] = useState<AttentionItem[]>(() => getCached<AttentionItem[]>(SEED.issues) ?? []);
  const [authoredIssues, setAuthoredIssues] = useState<AuthoredIssueDto[]>(() => getCached<AuthoredIssueDto[]>(SEED.authoredIssues) ?? []);

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    let active = true;
    let request = 0;
    const reload = () => {
      const current = ++request;
      void window.bridge.fetchAttentionPrs().then(rows => {
        if (!active || current !== request) return;
        setCached(SEED.attention, rows);
        setAttentionPrs(rows); setAttentionError(null);
      }).catch(error => {
        if (active && current === request) setAttentionError(error instanceof Error ? error.message : String(error));
      });
    };
    const updated = (event: Event) => {
      request++;
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

  const updating = workspaces.some(workspaceIsUpdating);
  useEffect(() => {
    if (!updating) return;
    // A clone that never finishes keeps this going, so an unchanged read keeps the shown list and Home does not re-render.
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void window.bridge.listWorkspaces()
        .then(rows => { setCached(SEED.workspaces, rows); setWorkspaces(shown => JSON.stringify(shown) === JSON.stringify(rows) ? shown : rows); })
        .catch(() => {});
    }, 1500);
    return () => window.clearInterval(timer);
  }, [updating]);

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

  // Home remounts on every tab switch, so share the activity feed cache until it
  // ages out. Missing titles only consult the local PR store.
  useEffect(() => {
    if (!login) return;
    const feeds = [
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
    // Each workspace's last read: an unchanged one answers without its issues, which stay as they were.
    const reads = new Map<string, { etag: string | null; items: AttentionItem[] }>();
    const reload = () => {
      const current = ++request;
      const ids = workspaceKey.split(',');
      void Promise.allSettled(ids.map(workspaceId =>
        window.bridge.fetchWorkspaceIssueAttention(workspaceId, reads.get(workspaceId)?.etag ?? null)
          .then(read => {
            if (read.snapshot === null || current !== request) return false;
            reads.set(workspaceId, { etag: read.etag, items: issueAttentionItems(read.snapshot, workspaceId, login) });
            return true;
          }, (failure: unknown) => {
            if (current === request) reads.delete(workspaceId);
            throw failure;
          })))
        .then(results => {
          if (cancelled || current !== request) return;
          setIssueAttentionError(results.some(result => result.status === 'rejected')
            ? 'Some work items could not be loaded. Open the workspace’s Issues tab to retry.' : null);
          if (!results.some(result => result.status === 'rejected' || result.value)) return;
          const items = ids.flatMap(workspaceId => reads.get(workspaceId)?.items ?? []);
          setCached(SEED.issues, items);
          setIssueItems(items);
        });
    };
    const changed = () => { reads.clear(); setIssueItems([]); reload(); };
    reload();
    // Its own clock: pull request updates are sent only when a row changes, and say nothing about issues.
    const timer = window.setInterval(() => { if (!document.hidden) reload(); }, ISSUE_ATTENTION_REFRESH_MS);
    window.addEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      window.removeEventListener(ISSUE_DEVELOPMENT_CHANGED, changed);
    };
  }, [login, workspaceKey]);

  // A failed read keeps the last list: My Work's background refreshes stay quiet.
  useEffect(() => {
    if (!login) return;
    let cancelled = false;
    const refresh = () => {
      window.bridge.fetchAuthoredIssues()
        .then(issues => {
          setCached(SEED.authoredIssues, issues);
          if (!cancelled) setAuthoredIssues(issues);
        })
        .catch(() => {});
    };
    refresh();
    const timer = window.setInterval(() => { if (!document.hidden) refresh(); }, AUTHORED_ISSUES_REFRESH_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [login]);

  const taskWorkspaces = useMemo(() => workspaces.map(workspace => ({
    id: workspace.id, name: workspace.name, repository: `${workspace.monitoredOwner}/${workspace.monitoredRepo}`,
  })), [workspaces]);
  const tasks = useTaskAttention(taskWorkspaces);

  const candidates = useMemo(
    () => linkTasks(attentionCandidates(attentionPrs ?? [], issueItems.map<AttentionItem>(item => ({
      ...item, workspaceName: workspaces.find(workspace => workspace.id === item.workspaceId)?.name,
    })).concat(jira.items, tasks.items), login), tasks.tasks),
    [attentionPrs, issueItems, jira.items, login, tasks.items, tasks.tasks, workspaces]);

  const localWork = useLocalWork(workspaceKey);
  const work = useMemo(() => myWorkItems({
    candidates, tasks: tasks.tasks, activity: tasks.activity, work: localWork, viewerLogin: login ?? null, authored: authoredIssues,
  }), [authoredIssues, candidates, localWork, login, tasks.activity, tasks.tasks]);
  const everything = useMemo(() => allWork
    ? myWorkItems({ candidates, tasks: tasks.tasks, activity: tasks.activity, work: localWork, viewerLogin: login ?? null, authored: authoredIssues, ended: true })
    : [],
  [allWork, authoredIssues, candidates, localWork, login, tasks.activity, tasks.tasks]);
  // Needs attention keeps what others ask of you; your own work, and anything My work already lists, stays there.
  const skipOwn = useMemo(() => {
    const listed = new Set(work.flatMap(item => item.keys));
    return (item: AttentionItem) => isOwnWork(item, login ?? null) || listed.has(item.key.toLowerCase());
  }, [login, work]);

  useEffect(() => {
    if (!drawer) return;
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') setDrawer(false); };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [drawer]);
  const closeDrawer = useCallback(() => setDrawer(false), []);

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

  const todayList = <TodayList today={today} scope="global" facts={candidates} />;

  if (allWork) {
    return (
      <div className="home-page">
        <main className="home-main">
          {jira.error && <div className="ws-error" role="alert">{jira.error}</div>}
          {attentionError && <div className="ws-error" role="alert">Unable to load PR attention: {attentionError}</div>}
          {issueAttentionError && <div className="ws-error" role="alert">{issueAttentionError}</div>}
          <AllMyWork items={everything} todayKeys={onToday} />
        </main>
      </div>
    );
  }

  return (
    <div className="home-page">
      <main className="home-main">
      {!wide && (
        <div className="home-main__bar">
          <button type="button" className="atn-approved__toggle" aria-expanded={drawer} onClick={() => setDrawer(value => !value)}>
            Activity <ChevronRight size={12} aria-hidden="true" />
          </button>
        </div>
      )}
      {/* ── Contribution card: graph + bio ── */}
      <ContributionCard profile={profile} />
      {!wide && todayList}

      {jira.error && <div className="ws-error" role="alert">{jira.error}</div>}
      <MyWork items={work} todayKeys={onToday} />

      {attentionError && <div className="ws-error" role="alert">Unable to load PR attention: {attentionError}</div>}
      {issueAttentionError && <div className="ws-error" role="alert">{issueAttentionError}</div>}
      <AttentionList
        candidates={candidates}
        skip={skipOwn}
        todayKeys={onToday}
        handled={handled}
        viewerLogin={login ?? null}
        onHandledChange={applyHandled}
        onOpen={item => {
          if (item.task) { window.location.hash = workspaceAgentTaskRoute(item.task.dto.workspaceId, item.task.dto.id); return; }
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
      <CirclesGrid
        key={teams.directory?.accountLogin ?? ''}
        directory={teams.directory}
        attention={visibleAttention(candidates, handled, Date.now())}
        viewerLogin={login ?? null}
      />

      </main>

      {/* ── Right rail: Today, then activity from people you follow; a drawer on narrower windows ── */}
      {(wide || drawer) && (
        <aside className={`home-side${wide ? '' : ' is-drawer'}`} aria-label={wide ? undefined : 'Activity'}>
          {!wide && (
            <div className="home-side__close">
              <button type="button" className="card-iconbtn" aria-label="Close activity" title="Close" onClick={closeDrawer}><X size={14} aria-hidden="true" /></button>
            </div>
          )}
          {wide && <>{todayList}<hr className="home-side__divider" /></>}
          <section className="home-side-section">
            <h3 className="home-side-section__title">From people you follow</h3>
            {followingEvents.length === 0 ? (
              <div className="hp-empty">No recent activity from people you follow.</div>
            ) : (
              <div className="home-following-list">
                {followingActivityRows(followingEvents, today.agenda).map((e, i) => (
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
                    onOpenUrl={openActivityLink}
                  />
                ))}
              </div>
            )}
          </section>
        </aside>
      )}

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
