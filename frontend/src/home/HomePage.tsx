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
import { jiraIssueLocation } from '../jira/contracts';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ChevronRight, X } from 'lucide-react';
import type { AgendaDto, RecentEventDto } from '../types';
import AddRepoModal from '../AddRepoModal';
import ActivityRow from '../ActivityRow';
import { groupRecentEvents } from '../activityNarrative';
import AttentionList from './AttentionList';
import HomeComposer from '../assistant/HomeComposer';
import { openAssistant } from '../assistant/model';
import {
  loadHandled,
  pullReferenceRoute,
  pullRoute,
  saveHandled,
  visibleAttention,
  type AttentionItem,
  type Handled,
} from './attention';
import ContributionCard from './ContributionCard';
import DoneList from './DoneList';
import { isoDay } from '../recent/model';
import { AllMyWork, MyWorkLine } from './MyWork';
import { publishShownWork, useMyWork } from './useMyWork';
import { isOwnWork } from './work';
import TodayList, { useWideWindow } from './TodayList';
import { showsToday, todayKeys, todayRows } from './today';
import { useTodayAgenda } from './useTodayAgenda';
import { getCachedFresh, setCached } from '../dataCache';
import WatchedReposGrid from './WatchedReposGrid';
import CirclesGrid from './CirclesGrid';
import { useTeams } from '../teams/useTeams';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { openActivityLink, zoomReference } from '../githubReference';
import { workspaceAgentTaskRoute } from '../workspaces/model';

type Props = {
  onSelectRepo: (owner: string, repo: string, prNumber?: number) => void;
  onOpenWorkspace: (workspaceId: string) => void;
  onStartIssue?: (workspaceId: string, issueNumber: number) => void;
  /** Shows All my work in place of Home: the same reads, so moving between the two re-reads nothing. */
  allWork?: boolean;
  /** The Assistant can be messaged, so its composer shows under My work. */
  assistantChat?: boolean;
};

const ACTIVITY_TTL_MS = 5 * 60 * 1000;

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
  assistantChat = false,
}: Props) {
  const { profile, workspaces, loading, workspaceError, addWorkspace, candidates, work, jiraError, attentionError, issueAttentionError } = useMyWork({ ended: allWork });
  useEffect(() => publishShownWork(allWork ? null : work), [allWork, work]);
  useEffect(() => () => publishShownWork(null), []);
  const [followingEvents, setFollowingEvents] = useState<RecentEventDto[]>([]);
  const teams = useTeams();
  const [showModal, setShowModal] = useState(false);
  // Below the wide width the rail is a drawer and Today moves above My work.
  const wide = useWideWindow();
  const [drawer, setDrawer] = useState(false);
  const [handled, setHandled] = useState<Handled>(loadHandled);
  const today = useTodayAgenda();
  const onToday = useMemo(() => todayKeys(today.agenda), [today.agenda]);
  // The day Done shows; unset follows today, so a Home left open past midnight moves on with the clock.
  const [doneDay, setDoneDay] = useState<string | null>(null);

  useEffect(() => {
    const handledChanged = () => setHandled(loadHandled());
    window.addEventListener('bytequay:attention-handled-changed', handledChanged);
    return () => window.removeEventListener('bytequay:attention-handled-changed', handledChanged);
  }, []);

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

  const todayList = <TodayList today={today} scope="global" facts={candidates} />;
  const doneList = <DoneList day={doneDay ?? isoDay(new Date())} onDay={setDoneDay} work={work} />;

  if (allWork) {
    return (
      <div className="home-page">
        <main className="home-main">
          {jiraError && <div className="ws-error" role="alert">{jiraError}</div>}
          {attentionError && <div className="ws-error" role="alert">Unable to load PR attention: {attentionError}</div>}
          {issueAttentionError && <div className="ws-error" role="alert">{issueAttentionError}</div>}
          <AllMyWork items={work} onDecision={() => openAssistant({ view: 'decision' })} />
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
      <ContributionCard profile={profile} onSelectDay={setDoneDay} />
      {!wide && todayList}
      {!wide && doneList}

      {jiraError && <div className="ws-error" role="alert">{jiraError}</div>}
      <div className="mw-home">
        <MyWorkLine items={work} onOpen={lane => openAssistant({ view: 'mywork', ...(lane === null ? {} : { lane }) })} />
        {assistantChat && <HomeComposer work={work} />}
      </div>

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
          if (item.type === 'pr' && pullRoute(item) !== undefined) {
            zoomReference({ kind: 'pull', repo: item.repo, number: item.number, ...pullReferenceRoute(item) });
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
        onOpenWorkspace={onOpenWorkspace}
        onWatch={() => setShowModal(true)}
      />
      <CirclesGrid
        key={teams.directories?.[0]?.accountId ?? ''}
        directories={teams.directories}
        attention={visibleAttention(candidates, handled, Date.now())}
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
          {wide && <>{todayList}<hr className="home-side__divider" />{doneList}<hr className="home-side__divider" /></>}
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
          onAdded={addWorkspace}
          onOpenWorkspace={onOpenWorkspace}
          onClose={() => setShowModal(false)}
        />
      )}
    </div>
  );
}

export default HomePage;
