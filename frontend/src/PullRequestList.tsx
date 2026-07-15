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
import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { UserProfileDto } from './types';
import type { DashboardPR } from './types/dashboardPr';
import { PrDetailsView, type AgentReviewNavTarget } from './pr/localpr/PrDetailsView';
import ReviewScreen from './ReviewScreen';
import ResizeHandle from './ResizeHandle';
import { getCached, setCached } from './dataCache';
import {
  clampSidebarWidth,
  getNextKeyboardSelection,
  isTextEntryTarget,
  loadLastReviewingId,
  loadSidebarWidth,
  persistLastReviewingId,
} from './pullRequestListHelpers';
import {
  buildBriefing,
  bucketize,
  groupHandledByTime,
  markHandledPatch,
  patchDashboardCache,
  patchPr,
  reopenPatch,
  sortHandled,
  sortSnoozed,
  splitByBucket,
} from './prBuckets';
import { CategorizedList, HandledTimeline, SnoozedList } from './PrBucketViews';
import KanbanBoard, { LANE_KEY, loadLane, pickFocusCards, type Lane } from './kanban/KanbanBoard';
import KanbanPrCard from './kanban/KanbanPrCard';
import MergeHistoryPage from './MergeHistoryPage';
import PrAnalyticsPage from './PrAnalyticsPage';

const PRS_CACHE_KEY = 'prs:list';
const SIDEBAR_COLLAPSED_KEY = 'settings:pr-sidebar-collapsed';
const SIDEBAR_RAIL_WIDTH = 36;

type Props = {
  /** Optional handler that navigates the app to the Teams section, used
   *  by the kanban's Teams tab. If not provided, the tab is hidden. */
  onGoToTeams?: () => void;
  /** Click on the PR's head-ref chip routes here so the user can jump
   *  from the dashboard preview to the local-repo Commits tab. App
   *  wires this to setNav({view:'local-repo', initialBranch}). */
  onOpenLocalBranch?: (owner: string, repo: string, branch: string) => void;
  /** Side nav's "Settings" item routes here. App wires this to
   *  setNav({view:'settings'}). When not provided, the side nav's
   *  Settings button is hidden. */
  onOpenSettings?: () => void;
  /** Navigate to a freshly-started review thread (its threadId) — wired
   *  to the diff page's "Review with agent" launch. */
  onStartReview?: (threadId: string) => void;
  /** Open the workspace-owned route for a standalone PR agent review. */
  onOpenAgentReview?: (target: AgentReviewNavTarget) => void;
  /** Active workspace the review panel lands in. */
  workspaceId?: string | null;
};

function PullRequestList({ onGoToTeams, onOpenLocalBranch, onOpenSettings, onStartReview, onOpenAgentReview, workspaceId }: Props) {
  const cachedPrs = getCached<DashboardPR[]>(PRS_CACHE_KEY);
  const [prs, setPrs] = useState<DashboardPR[] | null>(cachedPrs ?? null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(cachedPrs === undefined);
  const [selected, setSelected] = useState<DashboardPR | null>(null);
  const [reviewingPr, setReviewingPr] = useState<DashboardPR | null>(null);
  // The "← Back to kanban" affordance portals into the global topbar
  // (App.tsx renders the slot). We resolve the target node after mount
  // so the first render doesn't see a null DOM.
  const [topbarExtraNode, setTopbarExtraNode] = useState<HTMLElement | null>(null);
  useEffect(() => {
    setTopbarExtraNode(document.getElementById('global-topbar-extra'));
  }, []);
  const [filter, setFilter] = useState('');
  const [activeTab, setActiveTab] = useState<'inbox' | 'urgent' | 'snoozed' | 'handled' | 'analytics'>('inbox');
  // Full closed-PR history page (merged + closed-without-merge). Opened
  // from the kanban's "View full merge history →" footer CTA on the
  // Recently Merged column. Replaces the kanban view while open.
  const [mergeHistoryOpen, setMergeHistoryOpen] = useState(false);
  // Lane state for the kanban view (My PRs / To review). Lifted out of
  // KanbanBoard so the page header can render the scope tabs alongside
  // the Inbox/Handled toggle. Persistence stays at the same localStorage
  // key the kanban used to own.
  const [lane, setLaneState] = useState<Lane>(loadLane);
  const setLane = (next: Lane) => {
    setLaneState(next);
    try { localStorage.setItem(LANE_KEY, next); } catch { /* ignore */ }
  };
  const [lastReviewingPrId, setLastReviewingPrId] = useState<string | null>(loadLastReviewingId);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState<number>(loadSidebarWidth);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(() => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1');
  // Current GitHub login — gates the To-review "Needs attention" column
  // (categorizeToReview / isMyReviewTurn). Seed from the shared profile
  // cache so a warm cache renders the gated columns on first paint;
  // refresh in the background below.
  const [currentUserLogin, setCurrentUserLogin] = useState<string | null>(
    () => getCached<UserProfileDto>('home:profile')?.login ?? null,
  );
  const pageRef = useRef<HTMLDivElement>(null);
  const filterInputRef = useRef<HTMLInputElement>(null);

  // ⌘F focuses the filter input — pairs with the kbd hint in the field.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'f') {
        e.preventDefault();
        filterInputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const persistSidebarWidth = (width: number) => {
    setSidebarWidth(width);
    localStorage.setItem('settings:pr-sidebar-width', String(width));
  };

  const setSidebarCollapsedPersist = (collapsed: boolean) => {
    setSidebarCollapsed(collapsed);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0');
  };

  const clearActiveScreen = () => {
    setReviewingPr(null);
  };

  const updatePrState = (prId: string, patch: Partial<DashboardPR>) => {
    setPrs((prev) => (prev ? patchPr(prev, prId, patch) : prev));
    // `selected` holds its own snapshot of the PR (taken when the user
    // clicked the row), so it does NOT auto-update when `prs` changes.
    // Patch it too — otherwise the right-pane preview keeps reading the
    // pre-patch values (state='open', mergedAt=null) and the merge bar
    // stays active even after a successful merge.
    setSelected(prev => (prev && prev.id === prId ? { ...prev, ...patch } : prev));
    patchDashboardCache(prId, patch);
  };

  const markViewedOptimistically = (pr: DashboardPR) => {
    if (pr.viewedAt !== null) {
      return;
    }

    updatePrState(pr.id, { viewedAt: new Date().toISOString() });
  };

  const handleSidebarResize = (clientX: number) => {
    const rect = pageRef.current?.getBoundingClientRect();
    if (!rect) return;
    persistSidebarWidth(clampSidebarWidth(clientX - rect.left));
  };

  const stopPolling = () => {
    if (pollRef.current !== null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const reload = async (isManualRefresh = false) => {
    stopPolling();
    if (prs === null) setLoading(true);
    setError(null);
    try {
      // Manual refresh = "I want fresh data" — sync-list sweeps GitHub
      // first, then returns the freshly-synced rows in the same call.
      // A passive reload just re-reads the last synced rows.
      const data = isManualRefresh
        ? await window.bridge.syncDashboardPrs()
        : await window.bridge.fetchDashboardPrs();
      setPrs(data);
      setCached(PRS_CACHE_KEY, data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const initialLoad = async () => {
      if (prs === null) setLoading(true);
      setError(null);
      try {
        const data = await window.bridge.fetchDashboardPrs();
        setPrs(data);
        setCached(PRS_CACHE_KEY, data);
        if (data.length > 0) return;
        let attempts = 0;
        pollRef.current = setInterval(async () => {
          attempts++;
          try {
            const fresh = await window.bridge.fetchDashboardPrs();
            if (fresh.length > 0) {
              stopPolling();
              setPrs(fresh);
              setCached(PRS_CACHE_KEY, fresh);
            } else if (attempts >= 10) {
              stopPolling();
            }
          } catch {
            stopPolling();
          }
        }, 3_000);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setLoading(false);
      }
    };
    void initialLoad();
    return stopPolling;
  }, []);

  // Resolve the current GitHub login once so the kanban can tell whether
  // a review-requested PR is actually the user's turn. Cache it under the
  // shared profile key for other pages, and degrade silently to null (no
  // gating) if the lookup fails.
  useEffect(() => {
    let cancelled = false;
    window.bridge.getUserProfile()
      .then(profile => {
        if (cancelled) return;
        setCurrentUserLogin(profile.login);
        setCached('home:profile', profile);
      })
      .catch(() => { /* leave null — categorizer keeps showing all */ });
    return () => { cancelled = true; };
  }, []);

  const needle = filter.trim().toLowerCase();
  const filtered = useMemo(() => {
    const all = prs ?? [];
    if (!needle) return all;
    return all.filter(pr =>
      pr.title.toLowerCase().includes(needle) ||
      pr.repo.toLowerCase().includes(needle) ||
      (pr.author ?? '').toLowerCase().includes(needle),
    );
  }, [prs, needle]);

  const { inbox, snoozed, handled } = useMemo(() => splitByBucket(filtered), [filtered]);
  const handledSorted = useMemo(() => sortHandled(handled), [handled]);
  const handledGroups = useMemo(() => groupHandledByTime(handledSorted), [handledSorted]);
  const snoozedSorted = useMemo(() => sortSnoozed(snoozed), [snoozed]);
  // Urgent slice — picked locally (pickFocusCards' tiered rules: just-
  // woke, ready-to-merge, needs-changes, CI-failing, merge-conflict,
  // stale). The unified dashboard has no server-side equivalent of the
  // legacy UrgentPrFilter yet, so this is the one definition for now.
  const urgentCards = useMemo(() => pickFocusCards(filtered), [filtered]);
  const tabPrs = activeTab === 'inbox' ? inbox
    : activeTab === 'snoozed' ? snoozedSorted
    : activeTab === 'handled' ? handledSorted
    : [];
  // Briefing drives the red-dot alert on the My PRs scope tab. The
  // kanban renders its own copy too — we recompute here so the page
  // header doesn't have to reach into the child for state.
  const briefing = useMemo(() => buildBriefing(prs ?? []), [prs]);
  // Per-lane inbox counts for the side nav's My PRs / To review items.
  const laneCounts = useMemo(() => ({
    mine: inbox.filter(pr => pr.origin === 'AUTHORED').length,
    toReview: inbox.filter(pr => pr.origin === 'REVIEW_REQUESTED').length,
  }), [inbox]);

  // Keyboard j/k style nav over the currently-visible tab's PRs.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tabPrs.length === 0) return;
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      if (isTextEntryTarget(e.target)) return;
      e.preventDefault();
      const next = getNextKeyboardSelection(tabPrs, selected?.id ?? null, e.key);
      if (!next) return;
      setSelected(next);
      clearActiveScreen();
      // Same optimistic patch as handleSelect — see comment there for why.
      markViewedOptimistically(next);
      void window.bridge.markDashboardPrViewed(next.id);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [tabPrs, selected]);

  // The kanban opens PR detail as local state (nav stays on the kanban), so
  // App's nav-driven footprint capture never sees it. Record the visit here
  // when a PR is selected — same PR surfaceId as the nav layer — so the
  // opened PR lands in the home Recent list. Fire-and-forget like the nav path.
  useEffect(() => {
    if (selected === null) return;
    void window.bridge.recordSurfaceVisit({
      surfaceType: 'PR',
      surfaceId: `${selected.repo}#${selected.number}`,
      title: `${selected.title} #${selected.number}`,
      context: selected.repo,
    })
      .then(() => window.dispatchEvent(new Event('footprint-recorded')))
      .catch(() => { /* fire-and-forget */ });
  }, [selected?.repo, selected?.number]);

  const handleSelect = (pr: DashboardPR) => {
    setSelected(pr);
    clearActiveScreen();
    setLastReviewingPrId(pr.id);
    persistLastReviewingId(pr.id);
    // Optimistically patch viewedAt locally so the kanban can demote the
    // card from "Needs attention" to "In progress" immediately. Without
    // this the backend ack lands but the in-memory prs list keeps the
    // old null viewedAt and the card stays where it was. The cache sync
    // means this also propagates to repo / kanban caches on other pages.
    markViewedOptimistically(pr);
    void window.bridge.markDashboardPrViewed(pr.id);
    // Opening a just-woke PR is the implicit acknowledgement — clear the
    // wake mark so the focus band doesn't keep flagging it.
    if (pr.snoozeWakeReason) handleClearSnoozeWakeReason(pr.id);
  };

  const handleAgentReview = (pr: DashboardPR) => {
    setPrs(current => current?.map(row => row.id === pr.id ? { ...row, reviewState: 'running' } : row) ?? null);
    void (async () => {
      try {
        if (pr.reviewState !== undefined && pr.reviewState !== 'none') {
          const review = await window.bridge.getAgentReview(pr.id);
          if (review !== null) {
            if (review.rounds.some(round => round.status === 'RUNNING')) return;
            await window.bridge.continueAgentReview(review.review.id, {
              kind: pr.reviewState === 'stale' ? 're-review' : 'continue',
            });
            return;
          }
        }
        await window.bridge.startAgentReview(pr.id, { workspaceId: workspaceId ?? undefined });
      }
      catch (cause) {
        setPrs(current => current?.map(row => row.id === pr.id ? { ...row, reviewState: pr.reviewState ?? 'none' } : row) ?? null);
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    })();
  };

  // The PR the user was last reviewing, surfaced as a "Continue review"
  // shortcut on the Handled tab so they can jump back to it after browsing
  // their handled queue.
  const continueReviewPr = useMemo(() => {
    if (lastReviewingPrId === null || !prs) return null;
    return prs.find(p => p.id === lastReviewingPrId) ?? null;
  }, [lastReviewingPrId, prs]);

  const handleContinueReview = () => {
    if (!continueReviewPr) return;
    setSelected(continueReviewPr);
    clearActiveScreen();
    // Move to whichever tab the PR now lives in so the sidebar list matches.
    setActiveTab(bucketize(continueReviewPr));
    void window.bridge.markDashboardPrViewed(continueReviewPr.id);
  };

  const handleBackFromReview = () => {
    setReviewingPr(null);
    setSidebarCollapsedPersist(false);
    void reload(true);
  };

  const handleBackToKanban = () => {
    setSelected(null);
    void reload(true);
  };

  const handleMarkHandled = async (prId: string) => {
    const patch = markHandledPatch('MANUAL');
    updatePrState(prId, patch);
    try {
      await window.bridge.markDashboardPrHandled(prId, 'MANUAL');
    } catch (e) {
      console.warn('markDashboardPrHandled failed; rolling back', e);
      updatePrState(prId, reopenPatch());
    }
  };

  const handleReopen = async (prId: string) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, reopenPatch());
    try {
      await window.bridge.reopenDashboardPr(prId);
    } catch (e) {
      console.warn('reopenDashboardPr failed; rolling back', e);
      if (previous) {
        updatePrState(prId, { reviewedAt: previous.reviewedAt, handledAction: previous.handledAction });
      }
    }
  };

  const selectedId = selected?.id ?? null;

  const today = useMemo(
    () => new Date().toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }),
    [],
  );

  const switchTab = (tab: 'inbox' | 'urgent' | 'snoozed' | 'handled' | 'analytics') => {
    setActiveTab(tab);
    setSelected(null);
    clearActiveScreen();
  };

  const handleSnooze = async (prId: string, untilIso: string) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, { snoozedUntil: untilIso, snoozeWakeReason: null });
    try {
      await window.bridge.snoozeDashboardPr(prId, untilIso);
    } catch (e) {
      console.warn('snoozeDashboardPr failed; rolling back', e);
      if (previous) {
        updatePrState(prId, {
          snoozedUntil: previous.snoozedUntil,
          snoozeWakeReason: previous.snoozeWakeReason,
        });
      }
    }
  };

  const handleUnsnooze = async (prId: string) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, { snoozedUntil: null, snoozeWakeReason: null });
    try {
      await window.bridge.unsnoozeDashboardPr(prId);
    } catch (e) {
      console.warn('unsnoozeDashboardPr failed; rolling back', e);
      if (previous) {
        updatePrState(prId, {
          snoozedUntil: previous.snoozedUntil,
          snoozeWakeReason: previous.snoozeWakeReason,
        });
      }
    }
  };

  const handleClearSnoozeWakeReason = (prId: string) => {
    updatePrState(prId, { snoozeWakeReason: null });
    void window.bridge.clearDashboardPrSnoozeWakeReason(prId).catch(() => { /* best-effort */ });
  };

  // Drag-drop on the My-PRs kanban: dragging across the
  // Drafting ↔ Waiting-on-review boundary toggles the GitHub draft
  // state. The optimistic patch flips `draft` locally so the card
  // jumps columns immediately; the bridge call syncs it to GitHub.
  const handleSetDraft = async (prId: string, repo: string, number: number, draft: boolean) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, { draft });
    try {
      await window.bridge.setPrDraft(repo, number, draft);
    }
    catch (e) {
      console.warn('setPrDraft failed; rolling back', e);
      if (previous) updatePrState(prId, { draft: previous.draft });
    }
  };

  // Tab strip lives in the full-width kanban header only. When a PR is
  // selected, the sidebar shrinks to a list of peers and the tab/scope
  // controls would just clutter the narrow column — the user has
  // already drilled in.
  const tabsStrip = (
    <div className="pr-list-header__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'inbox'}
        className={`pr-list-tab${activeTab === 'inbox' ? ' pr-list-tab--active' : ''}`}
        onClick={() => switchTab('inbox')}
      >
        Inbox
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'snoozed'}
        className={`pr-list-tab${activeTab === 'snoozed' ? ' pr-list-tab--active' : ''}`}
        onClick={() => switchTab('snoozed')}
        title="PRs you've parked until a later time."
      >
        Snoozed
        {snoozedSorted.length > 0 && (
          <span className="pr-list-tab__count" aria-label={`${snoozedSorted.length} snoozed`}>
            {snoozedSorted.length}
          </span>
        )}
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'handled'}
        className={`pr-list-tab${activeTab === 'handled' ? ' pr-list-tab--active' : ''}`}
        onClick={() => switchTab('handled')}
      >
        Handled
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'analytics'}
        className={`pr-list-tab${activeTab === 'analytics' ? ' pr-list-tab--active' : ''}`}
        onClick={() => switchTab('analytics')}
        title="Personal review stats. Local data only."
      >
        Analytics
      </button>
      {/* Scope tabs (My PRs / To review / Teams). Only relevant on
          the Inbox tab — the Handled tab shows a flat timeline. */}
      {activeTab === 'inbox' && (
        <>
          <span className="pr-list-tabs__divider" aria-hidden="true" />
          <button
            type="button"
            role="tab"
            aria-selected={lane === 'mine'}
            className={`pr-list-scope-tab${lane === 'mine' ? ' pr-list-scope-tab--active' : ''}`}
            onClick={() => setLane('mine')}
          >
            <span aria-hidden="true">🚀</span> My PRs
            {/* Red-dot alert (not a count) when at least one of your
                PRs needs you. Stays a dot regardless of count — the
                user clicks in to see what. */}
            {briefing.mineNeedsAction > 0 && (
              <span
                className="pr-list-scope-tab__alert"
                title={`${briefing.mineNeedsAction} need you`}
                aria-label={`${briefing.mineNeedsAction} need you`}
              />
            )}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={lane === 'to_review'}
            className={`pr-list-scope-tab${lane === 'to_review' ? ' pr-list-scope-tab--active' : ''}`}
            onClick={() => setLane('to_review')}
          >
            <span aria-hidden="true">📥</span> To review
          </button>
          {onGoToTeams && (
            <button
              type="button"
              className="pr-list-scope-tab"
              onClick={onGoToTeams}
              title="Open the Teams page"
            >
              <span aria-hidden="true">👥</span> Teams
            </button>
          )}
        </>
      )}
      {activeTab === 'handled' && continueReviewPr && (
        <button
          type="button"
          className="pr-list-back-review"
          onClick={handleContinueReview}
          title={`Jump back to ${continueReviewPr.repo} #${continueReviewPr.number}.`}
        >
          ← Back to review
        </button>
      )}
    </div>
  );

  /** Shared header. `withTabs=false` (sidebar mode) hides the
   *  Inbox/Handled + scope tab strip — the user has drilled into a
   *  PR and the tabs would just crowd the narrow column. The full-
   *  screen kanban mode also passes false because the v2 side nav
   *  carries those controls now. */
  const renderHeader = (withTabs: boolean) => (
    <div className="pr-list-header">
      <div className="pr-list-header__title">
        <span className="pr-list-header__brand">
          {/* Page title tracks the active side-nav item rather than the
              old "Today's review" brand. The brand identity now lives
              in the side nav itself, so the page header is free to
              reflect what the user is actually looking at. */}
          {activeTab === 'inbox' ? (lane === 'mine' ? 'My PRs' : 'To review')
            : activeTab === 'urgent' ? 'Urgent'
              : activeTab === 'snoozed' ? 'Snoozed'
                : activeTab === 'handled' ? 'Handled'
                  : 'Analytics'}
        </span>
        <span className="pr-list-header__subtitle">{today}</span>
      </div>
      {withTabs && tabsStrip}
      <div className="pr-list-header__search">
        <input
          ref={filterInputRef}
          className="pr-list-header__filter"
          type="text"
          placeholder="Filter PRs…"
          value={filter}
          onChange={e => setFilter(e.target.value)}
        />
        <kbd className="pr-list-header__kbd">⌘F</kbd>
      </div>
      <button className="pr-list-header__btn" onClick={() => void reload(true)} type="button">
        ⟳ Refresh
      </button>
    </div>
  );

  /** Persistent left side nav for the full-screen kanban view. Carries
   *  the Inbox/Snoozed/Handled/Analytics switch and the My PRs / Team
   *  Review lane toggle, matching the visual structure of
   *  docs/mockups/v2/pr-dashboard/re-design/pr-kanban.png. Settings
   *  pins to the bottom and routes out via the App callback. */
  const sideNav = (
    <aside className="kanban-sidenav">
      <nav className="kanban-sidenav__nav">
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'urgent' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => switchTab('urgent')}
          title="PRs that need your attention right now — blocked, failing CI, stale, or just-woke."
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">◈</span>
          <span className="kanban-sidenav__item-label">Urgent</span>
          {urgentCards.length > 0 && (
            <span className="kanban-sidenav__count kanban-sidenav__count--urgent">{urgentCards.length}</span>
          )}
        </button>
        {/* The Inbox's two lanes are first-class nav items — replaces
            the old segmented lane toggle above the board. */}
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'inbox' && lane === 'mine' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => { switchTab('inbox'); setLane('mine'); }}
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">▤</span>
          <span className="kanban-sidenav__item-label">
            My PRs
            {briefing.mineNeedsAction > 0 && (
              <span
                className="pr-list-scope-tab__alert"
                title={`${briefing.mineNeedsAction} need you`}
                aria-label={`${briefing.mineNeedsAction} need you`}
              />
            )}
          </span>
          {laneCounts.mine > 0 && <span className="kanban-sidenav__count">{laneCounts.mine}</span>}
        </button>
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'inbox' && lane === 'to_review' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => { switchTab('inbox'); setLane('to_review'); }}
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">⇄</span>
          <span className="kanban-sidenav__item-label">To review</span>
          {laneCounts.toReview > 0 && <span className="kanban-sidenav__count">{laneCounts.toReview}</span>}
        </button>
        {onGoToTeams && (
          <button
            type="button"
            className="kanban-sidenav__item"
            onClick={onGoToTeams}
            title="Open the Teams page."
          >
            <span className="kanban-sidenav__item-icon" aria-hidden="true">◫</span>
            <span className="kanban-sidenav__item-label">Teams</span>
          </button>
        )}
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'snoozed' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => switchTab('snoozed')}
          title="PRs you've parked until a later time."
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">◔</span>
          <span className="kanban-sidenav__item-label">Snoozed</span>
          {snoozedSorted.length > 0 && <span className="kanban-sidenav__count">{snoozedSorted.length}</span>}
        </button>
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'handled' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => switchTab('handled')}
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">✓</span>
          <span className="kanban-sidenav__item-label">Handled</span>
          {handledSorted.length > 0 && <span className="kanban-sidenav__count">{handledSorted.length}</span>}
        </button>
        <button
          type="button"
          className={`kanban-sidenav__item${activeTab === 'analytics' ? ' kanban-sidenav__item--active' : ''}`}
          onClick={() => switchTab('analytics')}
          title="Personal review stats. Local data only."
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">◧</span>
          <span className="kanban-sidenav__item-label">Analytics</span>
        </button>
      </nav>
      <div className="kanban-sidenav__spacer" />
      {onOpenSettings && (
        <button
          type="button"
          className="kanban-sidenav__item kanban-sidenav__item--footer"
          onClick={onOpenSettings}
        >
          <span className="kanban-sidenav__item-icon" aria-hidden="true">⚙</span>
          <span className="kanban-sidenav__item-label">Settings</span>
        </button>
      )}
    </aside>
  );

  const screen = reviewingPr ? (
    <ReviewScreen pr={reviewingPr} onBack={handleBackFromReview} />
  ) : selected ? (
    <PrDetailsView
      pr={selected}
      onStartReview={onStartReview}
      onOpenAgentReview={onOpenAgentReview}
      workspaceId={workspaceId}
      onOpenReview={() => {
        setReviewingPr(selected);
        setSidebarCollapsedPersist(true);
      }}
      onMarkHandled={handleMarkHandled}
    />
  ) : null;

  // Merge-history overlay — full-page view of closed PRs. Lives outside
  // the kanban / sidebar branches so it can take the whole screen.
  if (mergeHistoryOpen) {
    return (
      <div className="kanban-page" ref={pageRef}>
        <MergeHistoryPage
          onBack={() => setMergeHistoryOpen(false)}
          onSelect={(pr) => {
            setMergeHistoryOpen(false);
            // Merge history is a live GitHub search (types.ts's
            // PullRequestHistoryPageDto) — a different id space than the
            // unified dashboard's string PR id, and the row may not even
            // be in the local `pr` table (older than the sync window).
            // Re-key it onto the dashboard shape so the existing
            // selection/detail-view plumbing works unchanged; viewed/
            // triage writes against the wrong numeric-derived id are a
            // harmless no-op on the backend if this PR isn't watched.
            handleSelect({ ...pr, id: String(pr.id) });
          }}
        />
      </div>
    );
  }

  // Selected mode: sidebar (categorised for Inbox, timeline for Handled) +
  // detail pane on the right.
  if (selected) {
    return (
      <div className="v2-page" ref={pageRef}>
        {sidebarCollapsed ? (
          <aside className="v2-sidebar v2-sidebar--collapsed" style={{ width: SIDEBAR_RAIL_WIDTH }}>
            <button
              type="button"
              className="v2-sidebar__rail-toggle"
              onClick={() => setSidebarCollapsedPersist(false)}
              title="Expand PR list"
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path d="M6.22 3.22a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06-1.06L9.94 8 6.22 4.28a.75.75 0 0 1 0-1.06Z" />
              </svg>
            </button>
            <div className="v2-sidebar__rail-label" aria-hidden="true">Today's review</div>
          </aside>
        ) : (
          <aside className="v2-sidebar" style={{ width: sidebarWidth }}>
            {/* Sidebar mode = a PR is open. Hide the tab strip — the
                user has drilled in; the narrow column should focus on
                the peer-PR list, not navigation widgets. */}
            {renderHeader(false)}
            <button
              type="button"
              className="v2-sidebar__collapse-btn"
              onClick={() => setSidebarCollapsedPersist(true)}
              title="Collapse PR list to a rail"
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path d="M9.78 12.78a.75.75 0 0 1-1.06 0L4.47 8.53a.75.75 0 0 1 0-1.06l4.25-4.25a.75.75 0 0 1 1.06 1.06L6.06 8l3.72 3.72a.75.75 0 0 1 0 1.06Z" />
              </svg>
            </button>
            <div className="v2-list">
              {loading && <div className="repo-loading">Loading…</div>}
              {error && <div className="repo-error">{error}</div>}
              {!loading && !error && activeTab === 'inbox' && (
                <CategorizedList
                  prs={inbox}
                  selectedId={selectedId}
                  onSelect={handleSelect}
                  onHandle={handleMarkHandled}
                  onReopen={handleReopen}
                  onSnooze={handleSnooze}
                />
              )}
              {!loading && !error && activeTab === 'urgent' && (
                urgentCards.length === 0 ? (
                  <div className="v2-empty">No urgent PRs.</div>
                ) : (
                  <div className="urgent-sidebar-list">
                    {urgentCards.map(pr => (
                      <KanbanPrCard
                        key={pr.id}
                        pr={pr}
                        column="needs_attention"
                        mode="team"
                        selected={selectedId === pr.id}
                        onSelect={() => handleSelect(pr)}
                        onHandle={() => handleMarkHandled(pr.id)}
                        onSnooze={(untilIso) => handleSnooze(pr.id, untilIso)}
                        onAgentReview={() => handleAgentReview(pr)}
                        reviewState={pr.reviewState}
                      />
                    ))}
                  </div>
                )
              )}
              {!loading && !error && activeTab === 'snoozed' && (
                <SnoozedList
                  prs={snoozedSorted}
                  selectedId={selectedId}
                  onSelect={handleSelect}
                  onUnsnooze={handleUnsnooze}
                  onEditSnooze={handleSnooze}
                  onClearWakeReason={handleClearSnoozeWakeReason}
                />
              )}
              {!loading && !error && activeTab === 'handled' && (
                handledSorted.length === 0 ? (
                  <div className="v2-empty">No handled PRs yet.</div>
                ) : (
                  <HandledTimeline
                    groups={handledGroups}
                    selectedId={selectedId}
                    onSelect={handleSelect}
                    onReopen={handleReopen}
                  />
                )
              )}
            </div>
          </aside>
        )}
        {!sidebarCollapsed && (
          <ResizeHandle onResize={handleSidebarResize} ariaLabel="Resize pull-request list" />
        )}
        {/* The --screen modifier flips v2-main into flex-column +
            no-padding + overflow:hidden so the screen child gets a real
            height to fill. The previous :has() approach was unreliable
            depending on what's rendered. */}
        <main className="v2-main v2-main--screen">
          {/* The "← Back to kanban" / "Back to list" affordance now
              portals into the global topbar (App.tsx's
              #global-topbar-extra slot) instead of sitting in its own
              band above the screen. Saves a vertical strip and groups
              the back action with the existing repo breadcrumb. */}
          {topbarExtraNode && !reviewingPr && createPortal(
            <button
              type="button"
              onClick={handleBackToKanban}
              title={activeTab === 'inbox'
                ? 'Return to the kanban board.'
                : 'Return to the handled list.'}
            >
              ← {activeTab === 'inbox' ? 'Back to kanban' : 'Back to list'}
            </button>,
            topbarExtraNode,
          )}
          <div className="v2-main__screen">{screen}</div>
        </main>
      </div>
    );
  }

  // No selection: full-width view. Inbox → kanban. Handled → timeline.
  return (
    <div className="kanban-page kanban-page--with-sidenav" ref={pageRef}>
      {sideNav}
      <div className="kanban-page__content">
      {renderHeader(false)}
      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}
      {!loading && !error && activeTab === 'inbox' && (
        <>
          {/* The My PRs / To review lane switch lives in the side nav
              now — no toggle row above the board. */}
          {/* The "My open PRs" summary panel that used to live above the
              kanban is now redundant — the kanban's "My PRs" lane shows
              authored PRs in proper columns with richer signals. */}
          {inbox.length === 0 ? (
            <div className="kanban-empty">
              {prs && prs.length === 0
                ? 'Inbox zero — nothing needs your attention right now.'
                : 'No PRs match the filter.'}
            </div>
          ) : (
            <KanbanBoard
              // `filtered` is `prs` after the page-header filter
              // input is applied. The kanban needs the post-filter
              // list so typing into the box actually narrows the
              // columns; passing `prs` ignored the filter entirely.
              prs={filtered}
              lane={lane}
              currentUserLogin={currentUserLogin}
              selectedId={selectedId}
              onSelect={handleSelect}
              onHandle={handleMarkHandled}
              onReopen={handleReopen}
              onSnooze={handleSnooze}
              onAgentReview={handleAgentReview}
              onShowMergeHistory={() => setMergeHistoryOpen(true)}
              onSetDraft={handleSetDraft}
            />
          )}
        </>
      )}
      {!loading && !error && activeTab === 'urgent' && (
        urgentCards.length === 0 ? (
          <div className="kanban-empty">
            Nothing urgent right now — your inbox is in good shape.
          </div>
        ) : (
          <div className="urgent-page">
            <p className="urgent-page__subtitle">
              {urgentCards.length} {urgentCards.length === 1 ? 'PR needs' : 'PRs need'} your attention.
              Most blocking first — newly-failed CI, requested changes, merge conflicts, and stale reviews.
            </p>
            <div className="urgent-page__grid">
              {urgentCards.map(pr => (
                <KanbanPrCard
                  key={pr.id}
                  pr={pr}
                  column="needs_attention"
                  mode="team"
                  selected={selectedId === pr.id}
                  onSelect={() => handleSelect(pr)}
                  onHandle={() => handleMarkHandled(pr.id)}
                  onSnooze={(untilIso) => handleSnooze(pr.id, untilIso)}
                  onAgentReview={() => handleAgentReview(pr)}
                  reviewState={pr.reviewState}
                />
              ))}
            </div>
          </div>
        )
      )}
      {!loading && !error && activeTab === 'snoozed' && (
        <div className="snoozed-page">
          <SnoozedList
            prs={snoozedSorted}
            selectedId={selectedId}
            onSelect={handleSelect}
            onUnsnooze={handleUnsnooze}
            onEditSnooze={handleSnooze}
            onClearWakeReason={handleClearSnoozeWakeReason}
          />
        </div>
      )}
      {!loading && !error && activeTab === 'handled' && (
        handledSorted.length === 0 ? (
          <div className="kanban-empty">No handled PRs yet.</div>
        ) : (
          <div className="handled-page">
            <HandledTimeline
              groups={handledGroups}
              selectedId={selectedId}
              onSelect={handleSelect}
              onReopen={handleReopen}
            />
          </div>
        )
      )}
      {activeTab === 'analytics' && (
        <PrAnalyticsPage
          onOpenPr={(repo, number) => {
            const target = (prs ?? []).find(p => p.repo === repo && p.number === number);
            if (target) handleSelect(target);
          }}
        />
      )}
      </div>
    </div>
  );
}

export default PullRequestList;
