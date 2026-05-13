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
import type { PullRequestDto } from './types';
import PullRequestPreview from './PullRequestPreview';
import ReviewScreen from './ReviewScreen';
import DiffViewerScreen from './DiffViewerScreen';
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
  mergedPatch,
  patchPr,
  reopenPatch,
  sortHandled,
  sortSnoozed,
  splitByBucket,
  syncCachesAfterPrChange,
  unmergedPatch,
} from './prBuckets';
import { CategorizedList, HandledTimeline, SnoozedList } from './PrBucketViews';
import KanbanBoard, { LANE_KEY, loadLane, type Lane } from './kanban/KanbanBoard';
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
};

function PullRequestList({ onGoToTeams, onOpenLocalBranch }: Props) {
  const cachedPrs = getCached<PullRequestDto[]>(PRS_CACHE_KEY);
  const [prs, setPrs] = useState<PullRequestDto[] | null>(cachedPrs ?? null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(cachedPrs === undefined);
  const [selected, setSelected] = useState<PullRequestDto | null>(null);
  const [reviewingPr, setReviewingPr] = useState<PullRequestDto | null>(null);
  const [diffViewerPr, setDiffViewerPr] = useState<PullRequestDto | null>(null);
  // The "← Back to kanban" affordance portals into the global topbar
  // (App.tsx renders the slot). We resolve the target node after mount
  // so the first render doesn't see a null DOM.
  const [topbarExtraNode, setTopbarExtraNode] = useState<HTMLElement | null>(null);
  useEffect(() => {
    setTopbarExtraNode(document.getElementById('global-topbar-extra'));
  }, []);
  // Lets the timeline's clickable SHA chips open the diff viewer pointed
  // at a specific commit. Stays in sync with diffViewerPr — cleared
  // whenever the viewer closes or the user navigates away.
  const [diffViewerCommitSha, setDiffViewerCommitSha] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [activeTab, setActiveTab] = useState<'inbox' | 'snoozed' | 'handled' | 'analytics'>('inbox');
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
  const [lastReviewingPrId, setLastReviewingPrId] = useState<number | null>(loadLastReviewingId);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState<number>(loadSidebarWidth);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(() => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1');
  const pageRef = useRef<HTMLDivElement>(null);

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
    setDiffViewerPr(null);
    setDiffViewerCommitSha(null);
  };

  const updatePrState = (prId: number, repo: string | undefined, patch: Partial<PullRequestDto>) => {
    setPrs((prev) => (prev ? patchPr(prev, prId, patch) : prev));
    // `selected` holds its own snapshot of the PR (taken when the user
    // clicked the row), so it does NOT auto-update when `prs` changes.
    // Patch it too — otherwise the right-pane preview keeps reading the
    // pre-patch values (state='open', mergedAt=null) and the merge bar
    // stays active even after a successful merge.
    setSelected(prev => (prev && prev.id === prId ? { ...prev, ...patch } : prev));
    syncCachesAfterPrChange(prId, patch, repo);
  };

  const markViewedOptimistically = (pr: PullRequestDto) => {
    if (pr.viewedAt !== null) {
      return;
    }

    updatePrState(pr.id, pr.repo, { viewedAt: new Date().toISOString() });
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
      // Manual refresh = "I want fresh data". Kick the backend's
      // GitHub sync first, then read the local DB. Without this
      // the button only re-reads the same cached row that was
      // already in memory.
      if (isManualRefresh) {
        await window.bridge.triggerSync().catch(() => { /* best-effort */ });
      }
      const data = await window.bridge.fetchPrs();
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
        const data = await window.bridge.fetchPrs();
        setPrs(data);
        setCached(PRS_CACHE_KEY, data);
        if (data.length > 0) return;
        let attempts = 0;
        pollRef.current = setInterval(async () => {
          attempts++;
          try {
            const fresh = await window.bridge.fetchPrs();
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
  const tabPrs = activeTab === 'inbox' ? inbox
    : activeTab === 'snoozed' ? snoozedSorted
    : activeTab === 'handled' ? handledSorted
    : [];
  // Briefing drives the red-dot alert on the My PRs scope tab. The
  // kanban renders its own copy too — we recompute here so the page
  // header doesn't have to reach into the child for state.
  const briefing = useMemo(() => buildBriefing(prs ?? []), [prs]);

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
      void window.bridge.markPrViewed(next.id);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [tabPrs, selected]);

  const handleSelect = (pr: PullRequestDto) => {
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
    void window.bridge.markPrViewed(pr.id);
    // Opening a just-woke PR is the implicit acknowledgement — clear the
    // wake mark so the focus band doesn't keep flagging it.
    if (pr.snoozeWakeReason) handleClearSnoozeWakeReason(pr.id);
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
    void window.bridge.markPrViewed(continueReviewPr.id);
  };

  const handleBackFromReview = () => {
    setReviewingPr(null);
    setSidebarCollapsedPersist(false);
    void window.bridge.triggerSync().catch(() => { /* best-effort */ });
    void reload(false);
  };

  const handleMarkHandled = async (prId: number) => {
    const target = (prs ?? []).find(p => p.id === prId);
    const patch = markHandledPatch('MANUAL');
    updatePrState(prId, target?.repo, patch);
    try {
      await window.bridge.markPrHandled(prId, 'MANUAL');
    } catch (e) {
      console.warn('markPrHandled failed; rolling back', e);
      const rollback = reopenPatch();
      updatePrState(prId, target?.repo, rollback);
    }
  };

  const handleApprove = async (prId: number, repo: string, number: number) => {
    const patch = markHandledPatch('APPROVED');
    updatePrState(prId, repo, patch);
    try {
      await window.bridge.approvePr(prId, repo, number);
    } catch (e) {
      const rollback = reopenPatch();
      updatePrState(prId, repo, rollback);
      throw e;
    }
  };

  const handleMerge = async (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => {
    const previous = (prs ?? []).find(p => p.id === prId);
    const previousState = previous?.state ?? null;
    const previousMergedAt = previous?.mergedAt ?? null;
    updatePrState(prId, repo, mergedPatch());
    try {
      const result = await window.bridge.mergePr(prId, repo, number, strategy);
      if (result?.queued) {
        // Merge queue picked it up — PR is still open until its slot
        // drains. Roll back the optimistic "merged" patch so the row
        // doesn't lie; the MergeBar in the preview pane surfaces the
        // queued state from the same result.
        updatePrState(prId, repo, unmergedPatch(previousState, previousMergedAt));
      }
      return result;
    } catch (e) {
      updatePrState(prId, repo, unmergedPatch(previousState, previousMergedAt));
      throw e;
    }
  };

  const handleReopen = async (prId: number) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, previous?.repo, reopenPatch());
    try {
      await window.bridge.reopenPr(prId);
    } catch (e) {
      console.warn('reopenPr failed; rolling back', e);
      if (previous) {
        const rollback = { reviewedAt: previous.reviewedAt, handledAction: previous.handledAction };
        updatePrState(prId, previous.repo, rollback);
      }
    }
  };

  const selectedId = selected?.id ?? null;

  const today = useMemo(
    () => new Date().toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }),
    [],
  );

  const switchTab = (tab: 'inbox' | 'snoozed' | 'handled' | 'analytics') => {
    setActiveTab(tab);
    setSelected(null);
    clearActiveScreen();
  };

  const handleSnooze = async (prId: number, untilIso: string) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, previous?.repo, { snoozedUntil: untilIso, snoozeWakeReason: null });
    try {
      await window.bridge.snoozePr(prId, untilIso);
    } catch (e) {
      console.warn('snoozePr failed; rolling back', e);
      if (previous) {
        updatePrState(prId, previous.repo, {
          snoozedUntil: previous.snoozedUntil,
          snoozeWakeReason: previous.snoozeWakeReason,
        });
      }
    }
  };

  const handleUnsnooze = async (prId: number) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, previous?.repo, { snoozedUntil: null, snoozeWakeReason: null });
    try {
      await window.bridge.unsnoozePr(prId);
    } catch (e) {
      console.warn('unsnoozePr failed; rolling back', e);
      if (previous) {
        updatePrState(prId, previous.repo, {
          snoozedUntil: previous.snoozedUntil,
          snoozeWakeReason: previous.snoozeWakeReason,
        });
      }
    }
  };

  const handleClearSnoozeWakeReason = (prId: number) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, previous?.repo, { snoozeWakeReason: null });
    void window.bridge.clearSnoozeWakeReason(prId).catch(() => { /* best-effort */ });
  };

  // Drag-drop on the My-PRs kanban: dragging across the
  // Drafting ↔ Waiting-on-review boundary toggles the GitHub draft
  // state. The optimistic patch flips `draft` locally so the card
  // jumps columns immediately; the bridge call syncs it to GitHub.
  const handleSetDraft = async (prId: number, repo: string, number: number, draft: boolean) => {
    const previous = (prs ?? []).find(p => p.id === prId);
    updatePrState(prId, repo, { draft });
    try {
      await window.bridge.setPrDraft(repo, number, draft);
    }
    catch (e) {
      console.warn('setPrDraft failed; rolling back', e);
      if (previous) updatePrState(prId, repo, { draft: previous.draft });
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
   *  PR and the tabs would just crowd the narrow column. */
  const renderHeader = (withTabs: boolean) => (
    <div className="pr-list-header">
      <div className="pr-list-header__title">
        <span className="pr-list-header__brand">Today's review</span>
        <span className="pr-list-header__subtitle">{today}</span>
      </div>
      {withTabs && tabsStrip}
      <input
        className="pr-list-header__filter"
        type="text"
        placeholder="Filter PRs…"
        value={filter}
        onChange={e => setFilter(e.target.value)}
      />
      <button className="pr-list-header__btn" onClick={() => void reload(true)} type="button">
        Refresh
      </button>
    </div>
  );

  const screen = reviewingPr ? (
    <ReviewScreen pr={reviewingPr} onBack={handleBackFromReview} />
  ) : diffViewerPr ? (
    <DiffViewerScreen
      pr={diffViewerPr}
      onBack={() => {
        setDiffViewerPr(null);
        setDiffViewerCommitSha(null);
        setSidebarCollapsedPersist(false);
      }}
      onApprove={handleApprove}
      initialCommitSha={diffViewerCommitSha}
    />
  ) : selected ? (
    <PullRequestPreview
      pr={selected}
      onOpenReview={() => {
        setReviewingPr(selected);
        setSidebarCollapsedPersist(true);
      }}
      onInspectDiffs={(sha) => {
        // Belt-and-braces: only accept a string. A bare onClick={onInspectDiffs}
        // somewhere would otherwise route the MouseEvent into this slot.
        setDiffViewerCommitSha(typeof sha === 'string' ? sha : null);
        setDiffViewerPr(selected);
        setSidebarCollapsedPersist(true);
      }}
      onMarkHandled={handleMarkHandled}
      onMerge={handleMerge}
      onOpenLocalBranch={onOpenLocalBranch}
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
            handleSelect(pr);
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
              ▶
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
              ◀
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
          {topbarExtraNode && !reviewingPr && !diffViewerPr && createPortal(
            <button
              type="button"
              onClick={() => setSelected(null)}
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
    <div className="kanban-page" ref={pageRef}>
      {renderHeader(true)}
      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}
      {!loading && !error && activeTab === 'inbox' && (
        <>
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
              selectedId={selectedId}
              onSelect={handleSelect}
              onHandle={handleMarkHandled}
              onReopen={handleReopen}
              onSnooze={handleSnooze}
              onShowMergeHistory={() => setMergeHistoryOpen(true)}
              onSetDraft={handleSetDraft}
            />
          )}
        </>
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
  );
}

export default PullRequestList;
