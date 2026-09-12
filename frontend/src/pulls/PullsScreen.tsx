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
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type ReactNode } from 'react';
import { Bell, Check, CircleSlash, Diff, Ellipsis, Eye, GitPullRequest, Maximize, MessageCircle, Minimize, PanelRight, RefreshCw, UserRound, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { isFindShortcut } from '../FindBar';
import type { NavigationMode } from '../navigationHistory';
import Toast from '../Toast';
import type { Bridge, DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDetailRefreshDto, PullRequestDto, PullRequestPageDto, UserProfileDto } from '../types';
import AgentReviewTab, { type ReviewNavigationTarget } from './agent-review/AgentReviewTab';
import type { AgentReviewDto } from './agent-review/contracts';
import { pendingDraftCount, reviewStatus } from './agent-review/model';
import PullChanges, { type CommitRange } from './PullChanges';
import PullOverview, { isQueued, mergePullRequestWriteDetail, remoteErrorMessage, type PullDetailUpdater } from './PullOverview';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import {
  DETAIL_REFRESH_INTERVAL,
  mergePullRequestDetailStatus,
  pullRequestDetailRefreshDelay,
} from './usePullRequestDetail';
import { useRecentTouch } from '../recent/useRecentTouch';
import {
  PULL_TABS,
  reconcilePostedComment,
  reconcilePullRequestDetail,
  reconcileSubmittedReview,
  rowsForSearch,
  rowsForTab,
  tabForInitialPullRequest,
  visibleSelectedId,
  type PullRow,
  type PullTab,
} from './model';
import '../css/pulls.css';

type DetailTab = 'overview' | 'changes' | 'review';

type AgentReviewContext = {
  workspaceId: string | undefined;
  review: AgentReviewDto | null;
};

export async function loadAgentReviewContext(
  bridge: Pick<Bridge, 'listWorkspaces' | 'findAgentReview' | 'getAgentReview'>,
  repo: string,
  number: number,
  workspaceId?: string,
  reviewId?: string,
): Promise<AgentReviewContext> {
  if (reviewId !== undefined) {
    const review = await bridge.getAgentReview(reviewId);
    if (review.id !== reviewId || review.repo.toLowerCase() !== repo.toLowerCase() || review.number !== number
      || (workspaceId !== undefined && review.workspaceId !== workspaceId)) {
      throw new Error('This review does not belong to the selected pull request and workspace.');
    }
    return { workspaceId: review.workspaceId, review };
  }
  if (workspaceId !== undefined) {
    return { workspaceId, review: await bridge.findAgentReview(workspaceId, repo, number) };
  }
  const workspaces = (await bridge.listWorkspaces()).filter(workspace =>
    `${workspace.monitoredOwner}/${workspace.monitoredRepo}`.toLowerCase() === repo.toLowerCase());
  // Several fork workspaces can watch the same upstream. Reuse a review before offering a new one.
  for (const workspace of workspaces) {
    const review = await bridge.findAgentReview(workspace.id, repo, number);
    if (review !== null) return { workspaceId: workspace.id, review };
  }
  return { workspaceId: workspaces[0]?.id, review: null };
}

const DETAIL_MIN = 680;
const DETAIL_MAX = 1600;
const DETAIL_DEFAULT = 1120;
const PAGE_SIZE = 50;
export { mergePullRequestDetailStatus, pullRequestDetailRefreshDelay } from './usePullRequestDetail';

export function returnedFromInAppBrowser(wasOpen: boolean, isOpen: boolean) {
  return wasOpen && !isOpen;
}

export function showBlockingDetailLoading(loading: boolean, hasDetail: boolean) {
  return loading && !hasDetail;
}

export function pullRequestSyncNotice(failure: unknown): string | null {
  return remoteErrorMessage(failure).includes('A pull request refresh is already in progress')
    ? 'Pull requests are already refreshing.'
    : null;
}

/** Toolbar width under which the search placeholder shortens. */
const TOOLBAR_COMPACT_WIDTH = 440;

export function compactToolbar(width: number) {
  return width < TOOLBAR_COMPACT_WIDTH;
}

/** Tab an arrow, Home, or End press moves to, or null when the key is not a move. */
export function tabKeyTarget(key: string, index: number, count: number): number | null {
  if (key === 'ArrowLeft') return (index - 1 + count) % count;
  if (key === 'ArrowRight') return (index + 1) % count;
  if (key === 'Home') return 0;
  if (key === 'End') return count - 1;
  return null;
}

/** Filter a Command-digit press selects, or null. */
export function shortcutTab(event: Pick<KeyboardEvent, 'altKey' | 'ctrlKey' | 'key' | 'metaKey'>): PullTab | null {
  if (!event.metaKey || event.ctrlKey || event.altKey) return null;
  const index = Number(event.key) - 1;
  return Number.isInteger(index) && index >= 0 && index < PULL_TABS.length ? PULL_TABS[index].key : null;
}

/**
 * Tabs the strip has room for at a pane width. Four is a cap rather than a
 * fit: a fifth tab still fits a wide pane but stops being scannable.
 */
export function tabSlots(width: number) {
  return width < 320 ? 2 : width < 430 ? 3 : 4;
}

/**
 * The filters that get a tab, in the fixed order. The selection takes the
 * last slot when the order would otherwise hide it, so no filter is ever
 * selected and reachable only through the overflow menu.
 */
export function visibleTabs(tab: PullTab, width: number): PullTab[] {
  const slots = tabSlots(width);
  const keys = PULL_TABS.slice(0, slots).map(item => item.key);
  return keys.includes(tab) ? keys : [...keys.slice(0, slots - 1), tab];
}

const TAB_ICONS: Record<PullTab, LucideIcon> = {
  all: GitPullRequest,
  active: UserRound,
  req: Eye,
  monitored: Bell,
  reviewed: Check,
  done: CircleSlash,
};

/**
 * The pull-request list filters: as many tabs as the pane fits, the rest
 * behind an overflow menu. Its own component rather than the segmented
 * control the issue list still uses, because only this one has to decide how
 * many of its filters are on screen.
 */
export function PullFilterTabs({ tab, searching, counts, width, panelId, onSelect }: {
  tab: PullTab;
  /** A search overrides the filter, so no tab reads as selected while one runs. */
  searching: boolean;
  counts: string[];
  /** Of the list pane, not the window: the list also renders beside the detail pane. */
  width: number;
  panelId: string;
  onSelect: (tab: PullTab) => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const filters = useRef<HTMLDivElement | null>(null);
  const visible = visibleTabs(tab, width);
  const shown = PULL_TABS.filter(item => visible.includes(item.key));
  const hidden = PULL_TABS.filter(item => !visible.includes(item.key));

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    // Pointerdown, not click: the menu has to be gone before whatever was
    // clicked underneath it reacts.
    const closeOnOutsidePress = (event: PointerEvent) => {
      if (!filters.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    window.addEventListener('pointerdown', closeOnOutsidePress);
    return () => {
      window.removeEventListener('keydown', closeOnEscape);
      window.removeEventListener('pointerdown', closeOnOutsidePress);
    };
  }, [menuOpen]);

  const moveSelection = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const target = tabKeyTarget(event.key, shown.findIndex(item => item.key === tab), shown.length);
    if (target === null) return;
    event.preventDefault();
    onSelect(shown[target].key);
    (event.currentTarget.children[target] as HTMLElement | undefined)?.focus();
  };

  const pick = (next: PullTab) => {
    setMenuOpen(false);
    onSelect(next);
  };

  return (
    <div className="pulls-toolbar__filters" ref={filters}>
      <div className="pulls-toolbar__tabs" role="tablist" aria-label="Pull request filters" onKeyDown={moveSelection}>
        {shown.map(item => (
          <button
            key={item.key}
            id={`${panelId}-${item.key}`}
            className="pulls-tab"
            type="button"
            role="tab"
            aria-selected={!searching && item.key === tab}
            aria-controls={panelId}
            tabIndex={item.key === tab ? 0 : -1}
            onClick={() => pick(item.key)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <button
        className={`pulls-tab-more${menuOpen ? ' is-open' : ''}`}
        type="button"
        title="More filters"
        aria-label="More filters"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen(open => !open)}
      >
        <Ellipsis size={15} strokeWidth={1.8} aria-hidden="true" />
      </button>
      {menuOpen && (
        <div className="pulls-filter-menu" role="menu" aria-label="More filters">
          {hidden.map(item => {
            const index = PULL_TABS.indexOf(item);
            const Glyph = TAB_ICONS[item.key];
            const checked = !searching && item.key === tab;
            return (
              <button
                key={item.key}
                className="pulls-filter-menu__item"
                type="button"
                role="menuitemradio"
                aria-checked={checked}
                onClick={() => pick(item.key)}
              >
                <Glyph className="pulls-filter-menu__glyph" size={13} strokeWidth={1.7} aria-hidden="true" />
                <span className="pulls-filter-menu__label">{item.label}</span>
                {/* Counts live here rather than on the tabs: the menu has the
                    room to show them whole. */}
                <span className="pulls-filter-menu__count">{counts[index]}</span>
                <span className="pulls-filter-menu__key" aria-hidden="true">{`⌘${index + 1}`}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function PullsScreen({
  initialPr,
  initialTab,
  initialReviewId,
  repositoryScope,
  browserOpen = false,
  onSelectPullRequest,
  onClose,
}: {
  initialPr?: { repo: string; number: number };
  /** Filter tab to open on (from the Home review card). */
  initialTab?: PullTab;
  /** Opens an exact retained review from the workspace Tasks list. */
  initialReviewId?: string;
  repositoryScope?: { workspaceId: string; repository: string };
  browserOpen?: boolean;
  onSelectPullRequest?: (reference: { repo: string; number: number }, mode: NavigationMode) => void;
  /** Opens only initialPr over the current page, without list reads or navigation. */
  onClose?: () => void;
}) {
  const overlay = onClose !== undefined;
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [viewerLogin, setViewerLogin] = useState<string | null>();
  const [viewer, setViewer] = useState<UserProfileDto | null>(null);
  /** The open review of the selected pull request, loaded once per selection and kept live by its tab. */
  const [reviewContext, setReviewContext] = useState<(AgentReviewContext & { key: string; error: string | null }) | null>(null);
  const [reviewReload, setReviewReload] = useState(0);
  /** Where Changes should scroll, set when a finding asks to be shown at its diff location. */
  const [reviewNavigation, setReviewNavigation] = useState<ReviewNavigationTarget | null>(null);
  /** A finding the Changes tab sent back to Agent review, so its card opens and scrolls. */
  const [focusFindingId, setFocusFindingId] = useState<string | null>(null);
  const [tab, setTab] = useState<PullTab>(initialTab ?? 'all');
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>(initialReviewId === undefined ? 'overview' : 'review');
  const [diffs, setDiffs] = useState<DiffFileDto[] | null>(null);
  const [diffCommits, setDiffCommits] = useState<DiffCommitDto[]>([]);
  const [diffRange, setDiffRange] = useState<CommitRange>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [pendingReviewComments, setPendingReviewComments] = useState<InlineReviewCommentDto[]>([]);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  const [detailWidth, setDetailWidth] = useState(DETAIL_DEFAULT);
  const [loading, setLoading] = useState(!overlay);
  const [syncing, setSyncing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [monitorError, setMonitorError] = useState<string | null>(null);
  const [monitoringIds, setMonitoringIds] = useState<number[]>([]);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  // The toolbar, not the window: it also renders inside the narrower split pane.
  const [toolbarWidth, setToolbarWidth] = useState(Number.POSITIVE_INFINITY);
  const toolbar = useRef<HTMLElement | null>(null);
  const searchInput = useRef<HTMLInputElement | null>(null);
  const viewerLoginRef = useRef(viewerLogin);
  const rowsId = useId();
  const loadingMoreRequest = useRef(false);
  const loadMoreMarker = useRef<HTMLDivElement | null>(null);
  const openedInitialPr = useRef<string | null>(null);
  const routingInitialPr = useRef<string | null>(null);
  const detailSnapshot = useRef<string | null>(null);
  const detailState = useRef<PullRequestDetailDto | null>(null);
  const detailMutationVersion = useRef(0);
  const selectedPullRequest = useRef<{ id: number; key: string } | null>(null);
  const listRequest = useRef(0);
  const manualDetailRefreshRequest = useRef(0);
  const diffRequest = useRef(0);
  const toastId = useRef(0);
  const listContext = useRef({ workspaceId: repositoryScope?.workspaceId, query });
  const detailRefreshInFlight = useRef<Promise<PullRequestDetailRefreshDto> | null>(null);
  const browserWasOpen = useRef(browserOpen);
  const monitorRequests = useRef(new Set<number>());
  viewerLoginRef.current = viewerLogin;
  listContext.current = { workspaceId: repositoryScope?.workspaceId, query };

  useEffect(() => {
    if (!overlay) return;
    const previousFocus = document.activeElement;
    overlayRef.current?.focus();
    return () => {
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [overlay]);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getUserProfile()
      .then(profile => {
        if (cancelled) return;
        setViewerLogin(profile.login);
        setViewer(profile);
      })
      .catch(() => { if (!cancelled) setViewerLogin(null); });
    return () => { cancelled = true; };
  }, []);

  const applyDetail = useCallback((
    prId: number,
    value: PullRequestDetailDto,
    refreshDiff: boolean,
    requestMutationVersion?: number,
  ) => {
    if (requestMutationVersion !== undefined && requestMutationVersion !== detailMutationVersion.current) return false;
    const snapshot = JSON.stringify(value);
    if (detailSnapshot.current === snapshot) return false;
    detailSnapshot.current = snapshot;
    detailState.current = value;
    setDetail(value);
    setPrs(current => current.map(pr => pr.id === prId
      ? reconcilePullRequestDetail(pr, value, viewerLoginRef.current)
      : pr));
    if (refreshDiff) {
      diffRequest.current += 1;
      setDiffs(null);
      setDiffCommits([]);
      setDiffRange(null);
      setDiffLoading(false);
    }
    return true;
  }, []);

  const updateSelectedDetail: PullDetailUpdater = useCallback((repo, number, update) => {
    const key = `${repo.toLowerCase()}#${number}`;
    const selected = selectedPullRequest.current;
    const current = detailState.current;
    if (selected?.key !== key || current === null
        || `${current.repo.toLowerCase()}#${current.number}` !== key) return;
    const next = update(current);
    detailMutationVersion.current += 1;
    detailSnapshot.current = JSON.stringify(next);
    detailState.current = next;
    setDetail(next);
    setPrs(rows => rows.map(pr => pr.id === selected.id
      ? reconcilePullRequestDetail(pr, next, viewerLoginRef.current)
      : pr));
  }, []);

  const applyDetailRefresh = useCallback((
    prId: number,
    value: PullRequestDetailRefreshDto,
    requestMutationVersion: number,
  ) => {
    if (value.full) {
      applyDetail(prId, value.detail, true, requestMutationVersion);
      return;
    }
    const status = value.status;
    const current = detailState.current;
    if (current !== null) {
      const next = mergePullRequestDetailStatus(current, status);
      detailSnapshot.current = JSON.stringify(next);
      detailState.current = next;
      setDetail(next);
    }
    setPrs(current => current.map(pr => pr.id === prId
      ? { ...pr, ciStatus: status.ciStatus, mergeable: status.mergeable, mergeableState: status.mergeableState }
      : pr));
  }, [applyDetail]);

  const applyPage = useCallback((result: PullRequestPageDto) => {
    const selected = selectedPullRequest.current;
    const selectedDetail = detailState.current;
    const pullRequests = selected !== null && selectedDetail !== null
      && selected.key === `${selectedDetail.repo.toLowerCase()}#${selectedDetail.number}`
      ? result.pullRequests.map(pr => pr.id === selected.id
          ? reconcilePullRequestDetail(pr, {
              state: pr.state,
              updatedAt: pr.updatedAt,
              author: selectedDetail.author,
              labels: selectedDetail.labels,
              reviewers: selectedDetail.reviewers,
            }, viewerLoginRef.current)
          : pr)
      : result.pullRequests;
    setPrs(pullRequests);
    setPage(result.page);
    setHasMore(result.hasMore);
    setSelected(current => pullRequests.some(pr => pr.id === current)
      ? current
      : null);
  }, []);

  useEffect(() => {
    if (overlay) return;
    return window.bridge.onPullRequestsSynced(() => {
      const current = listContext.current;
      const request = ++listRequest.current;
      void window.bridge.fetchPrs(current.workspaceId, 0, PAGE_SIZE, current.query)
        .then(result => { if (request === listRequest.current) applyPage(result); })
        .catch(() => {});
    });
  }, [applyPage, overlay]);

  useEffect(() => {
    const updateAttention = (event: Event) => {
      const updates = new Map((event as CustomEvent<PullRequestDto[]>).detail.map(pr => [pr.id, pr]));
      setPrs(current => current.map(pr => updates.get(pr.id) ?? pr));
    };
    window.addEventListener('bytequay:pr-attention-updated', updateAttention);
    return () => window.removeEventListener('bytequay:pr-attention-updated', updateAttention);
  }, []);

  const refreshList = useCallback(async () => {
    const workspaceId = repositoryScope?.workspaceId;
    let request: number | null = null;
    setSyncing(true);
    setError(null);
    setSyncError(null);
    try {
      await window.bridge.syncPrs(workspaceId);
      const current = listContext.current;
      if (current.workspaceId !== workspaceId) return;
      request = ++listRequest.current;
      const result = await window.bridge.fetchPrs(current.workspaceId, 0, PAGE_SIZE, current.query);
      if (request === listRequest.current) applyPage(result);
    }
    catch (e) {
      if (listContext.current.workspaceId === workspaceId
        && (request === null || request === listRequest.current)) {
        const notice = pullRequestSyncNotice(e);
        if (notice !== null) {
          toastId.current += 1;
          setToast({ id: toastId.current, text: notice });
        } else {
          setSyncError(e instanceof Error ? e.message : String(e));
        }
      }
    }
    finally {
      setSyncing(false);
    }
  }, [applyPage, repositoryScope?.workspaceId]);

  useEffect(() => {
    if ((!detailZoomed && !overlay) || browserOpen) return;
    const exitFullScreen = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.key !== 'Escape') return;
      event.preventDefault();
      if (onClose) onClose();
      else setDetailZoomed(false);
    };
    window.addEventListener('keydown', exitFullScreen);
    return () => window.removeEventListener('keydown', exitFullScreen);
  }, [browserOpen, detailZoomed, onClose, overlay]);

  useEffect(() => {
    if (overlay) return;
    const request = ++listRequest.current;
    setLoading(true);
    setError(null);
    setSyncError(null);
    setPrs([]);
    setHasMore(false);
    setPage(0);
    window.bridge.fetchPrs(repositoryScope?.workspaceId, 0, PAGE_SIZE, query)
      .then(result => {
        if (request === listRequest.current) applyPage(result);
      })
      .catch(e => {
        if (request === listRequest.current) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (request === listRequest.current) setLoading(false);
      });
  }, [applyPage, overlay, query, repositoryScope?.workspaceId]);

  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(search.trim()), 250);
    return () => window.clearTimeout(timeout);
  }, [search]);

  const selectTab = useCallback((next: PullTab) => {
    setTab(next);
    setSearch('');
    setQuery('');
  }, []);

  useEffect(() => {
    const node = toolbar.current;
    if (node === null) return;
    const observer = new ResizeObserver(entries => setToolbarWidth(entries[0].borderBoxSize[0].inlineSize));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  // Capture phase: on this screen Command-F belongs to the list search, and
  // the window-level find bar would otherwise open on top of it.
  useEffect(() => {
    if (overlay) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (isFindShortcut(event)) {
        event.preventDefault();
        event.stopPropagation();
        searchInput.current?.select();
        return;
      }
      const shortcut = shortcutTab(event);
      if (shortcut === null) return;
      event.preventDefault();
      selectTab(shortcut);
    };
    window.addEventListener('keydown', handleKeyDown, true);
    return () => window.removeEventListener('keydown', handleKeyDown, true);
  }, [overlay, selectTab]);

  const loadMore = useCallback(async () => {
    if (!hasMore || loadingMoreRequest.current) return;
    const request = listRequest.current;
    loadingMoreRequest.current = true;
    setLoadingMore(true);
    try {
      const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, page + 1, PAGE_SIZE, query);
      if (request !== listRequest.current) return;
      setPrs(current => {
        const loaded = new Set(current.map(pr => pr.id));
        return [...current, ...result.pullRequests.filter(pr => !loaded.has(pr.id))];
      });
      setPage(result.page);
      setHasMore(result.hasMore);
    }
    catch (e) {
      if (request === listRequest.current) setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      loadingMoreRequest.current = false;
      setLoadingMore(false);
    }
  }, [hasMore, page, query, repositoryScope?.workspaceId]);

  useEffect(() => {
    const marker = loadMoreMarker.current;
    if (!marker || !hasMore) return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) void loadMore();
    }, { root: marker.parentElement, rootMargin: '240px' });
    observer.observe(marker);
    return () => observer.disconnect();
  }, [hasMore, loadMore]);

  useEffect(() => {
    if (!initialPr) {
      routingInitialPr.current = null;
      return;
    }
    if (loading || viewerLogin === undefined) return;
    const initialPrKey = `${initialPr.repo.toLowerCase()}#${initialPr.number}`;
    if (openedInitialPr.current === initialPrKey) return;
    openedInitialPr.current = initialPrKey;
    routingInitialPr.current = initialPrKey;
    const match = prs.find(pr => pr.repo.toLowerCase() === initialPr.repo.toLowerCase()
      && pr.number === initialPr.number);
    if (match) {
      setTab(current => tabForInitialPullRequest(match, current, viewerLogin));
      setSelected(match.id);
      return;
    }
    let cancelled = false;
    Promise.all([
      window.bridge.fetchPullRequestDetailByRef(initialPr.repo, initialPr.number),
      window.bridge.fetchAttentionPrs().catch((): PullRequestDto[] => []),
    ]).then(([value, tracked]) => {
      if (cancelled) return;
      const remote = tracked.find(pr => pr.id === value.id) ?? remotePullRequest(value, viewerLogin ?? undefined);
      setPrs(current => current.some(pr => pr.id === remote.id) ? current : [remote, ...current]);
      applyDetail(remote.id, value, false);
      setTab(current => tabForInitialPullRequest(remote, current, viewerLogin));
      setSelected(remote.id);
    }).catch(e => {
      if (!cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { cancelled = true; };
  }, [applyDetail, initialPr, loading, prs, viewerLogin]);

  const rows = useMemo(() => query ? rowsForSearch(prs) : rowsForTab(prs, tab, viewerLogin), [prs, query, tab, viewerLogin]);
  const compact = compactToolbar(toolbarWidth);
  // ponytail: one pass per tab over the loaded page; revisit only if the list
  // ever holds thousands of rows. Unloaded pages make each count a floor.
  const tabCounts = useMemo(
    () => PULL_TABS.map(item => `${rowsForTab(prs, item.key, viewerLogin).length}${hasMore ? '+' : ''}`),
    [hasMore, prs, viewerLogin],
  );
  const visibleSelected = overlay ? selected : visibleSelectedId(rows, selected);
  const selectedPr = prs.find(pr => pr.id === visibleSelected) ?? null;
  const selectedPrId = selectedPr?.id;
  const selectedPrNumber = selectedPr?.number;
  const selectedPrRepo = selectedPr?.repo;
  selectedPullRequest.current = selectedPr === null
    ? null
    : { id: selectedPr.id, key: `${selectedPr.repo.toLowerCase()}#${selectedPr.number}` };

  useEffect(() => {
    if (overlay) return;
    if (selectedPrId === undefined || selectedPrNumber === undefined || selectedPrRepo === undefined) return;
    const selectedKey = `${selectedPrRepo.toLowerCase()}#${selectedPrNumber}`;
    if (routingInitialPr.current !== null) {
      if (routingInitialPr.current === selectedKey) routingInitialPr.current = null;
      return;
    }
    if (selected === null) {
      setSelected(selectedPrId);
      return;
    }
    onSelectPullRequest?.({ repo: selectedPrRepo, number: selectedPrNumber }, 'replace');
  }, [onSelectPullRequest, overlay, selected, selectedPrId, selectedPrNumber, selectedPrRepo]);

  useEffect(() => {
    manualDetailRefreshRequest.current += 1;
    if (selectedPrId === undefined || selectedPrNumber === undefined || selectedPrRepo === undefined) {
      detailSnapshot.current = null;
      detailState.current = null;
      setDetail(null);
      return;
    }
    let cancelled = false;
    let refreshTimer: number | undefined;
    detailSnapshot.current = null;
    detailState.current = null;
    setDetail(null);
    setDetailLoading(true);
    setError(null);
    const fetchSelectedDetail = () =>
      window.bridge.fetchPullRequestDetailByRef(selectedPrRepo, selectedPrNumber);
    const refreshSelectedDetail = () =>
      window.bridge.refreshPullRequestDetailByRef(selectedPrRepo, selectedPrNumber);
    let polling = false;
    // A hidden window spends requests nobody is reading, so the loop stops
    // rather than idling. It has no pending timer while hidden, which is what
    // lets the visibility listener restart it without racing one.
    const schedule = (delay: number) => {
      if (cancelled || document.hidden || polling || refreshTimer !== undefined) return;
      refreshTimer = window.setTimeout((): void => {
        refreshTimer = undefined;
        void poll();
      }, delay);
    };
    const poll = async (): Promise<void> => {
      polling = true;
      let nextDelay = DETAIL_REFRESH_INTERVAL;
      let request: Promise<PullRequestDetailRefreshDto> | null = null;
      try {
        while (detailRefreshInFlight.current !== null) {
          const pending = detailRefreshInFlight.current;
          await pending.then((): undefined => undefined, (): undefined => undefined);
        }
        if (cancelled || document.hidden) return;
        const requestMutationVersion = detailMutationVersion.current;
        request = refreshSelectedDetail();
        detailRefreshInFlight.current = request;
        const value = await request;
        if (!cancelled) {
          applyDetailRefresh(selectedPrId, value, requestMutationVersion);
          setError(null);
        }
      }
      catch (e) {
        nextDelay = pullRequestDetailRefreshDelay(e);
        if (!cancelled) setError(remoteErrorMessage(e));
      }
      finally {
        if (detailRefreshInFlight.current === request) detailRefreshInFlight.current = null;
        polling = false;
        schedule(nextDelay);
      }
    };
    // Coming back deserves current data now, not up to five seconds from now.
    const resume = () => schedule(0);
    document.addEventListener('visibilitychange', resume);

    const initialMutationVersion = detailMutationVersion.current;
    fetchSelectedDetail()
      .then(value => {
        if (!cancelled) applyDetail(selectedPrId, value, false, initialMutationVersion);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) {
          setDetailLoading(false);
          schedule(0);
        }
      });
    return () => {
      cancelled = true;
      document.removeEventListener('visibilitychange', resume);
      if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
    };
  }, [applyDetail, applyDetailRefresh, selectedPrId, selectedPrNumber, selectedPrRepo]);

  const scopedWorkspaceId = repositoryScope?.workspaceId;
  const requestedReviewId = selectedPrRepo?.toLowerCase() === initialPr?.repo.toLowerCase() && selectedPrNumber === initialPr?.number
    ? initialReviewId : undefined;
  const reviewKey = selectedPrRepo === undefined || selectedPrNumber === undefined
    ? null
    : `${scopedWorkspaceId ?? ''}:${selectedPrRepo.toLowerCase()}#${selectedPrNumber}:${requestedReviewId ?? ''}`;
  const currentReviewContext = reviewContext?.key === reviewKey ? reviewContext : null;
  const workspaceId = currentReviewContext?.workspaceId;
  const review = currentReviewContext?.review ?? null;
  const reviewLoading = reviewKey !== null && currentReviewContext === null;
  const reviewError = currentReviewContext?.error ?? null;

  useEffect(() => {
    setReviewContext(null);
    setReviewNavigation(null);
    setFocusFindingId(null);
    if (reviewKey === null || selectedPrRepo === undefined || selectedPrNumber === undefined) return;
    let cancelled = false;
    void loadAgentReviewContext(window.bridge, selectedPrRepo, selectedPrNumber, scopedWorkspaceId, requestedReviewId)
      .then(context => {
        if (!cancelled) setReviewContext({ ...context, key: reviewKey, error: null });
      })
      .catch(failure => {
        if (!cancelled) setReviewContext({ key: reviewKey, workspaceId: undefined, review: null, error: remoteErrorMessage(failure) });
      });
    return () => { cancelled = true; };
  }, [scopedWorkspaceId, selectedPrRepo, selectedPrNumber, reviewKey, reviewReload, requestedReviewId]);

  const updateReview = useCallback((updated: AgentReviewDto) => {
    setReviewContext(current => current !== null && current.key === reviewKey
      && current.workspaceId === updated.workspaceId
      && updated.repo.toLowerCase() === selectedPrRepo?.toLowerCase() && updated.number === selectedPrNumber
      ? { ...current, review: updated }
      : current);
  }, [reviewKey, selectedPrRepo, selectedPrNumber]);

  useEffect(() => {
    if (detailTab !== 'changes' || !selectedPr || diffs !== null) return;
    const request = ++diffRequest.current;
    setDiffLoading(true);
    Promise.all([
      window.bridge.fetchPrDiffFiles(selectedPr.repo, selectedPr.number),
      window.bridge.fetchPrDiffCommits(selectedPr.repo, selectedPr.number),
    ])
      .then(([files, commits]) => {
        if (request !== diffRequest.current) return;
        setDiffs(files);
        setDiffCommits(commits);
      })
      .catch(e => {
        if (request === diffRequest.current) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (request === diffRequest.current) setDiffLoading(false);
      });
  }, [detailTab, selectedPr, diffs]);

  const pick = (row: PullRow) => {
    onSelectPullRequest?.({ repo: row.repo, number: row.num }, 'push');
    diffRequest.current += 1;
    setSelected(row.id);
    setDetailTab('overview');
    setDiffs(null);
    setDiffCommits([]);
    setDiffRange(null);
    setDiffLoading(false);
    setPendingReviewComments([]);
    setReviewNavigation(null);
    setFocusFindingId(null);
  };

  const toggleMonitor = async (row: PullRow) => {
    if (monitorRequests.current.has(row.id)) return;
    monitorRequests.current.add(row.id);
    setMonitoringIds(current => [...current, row.id]);
    setMonitorError(null);
    try {
      const updated = await window.bridge.setPrMonitored(row.id, !row.monitored);
      setPrs(current => current.map(pr => pr.id === updated.id ? updated : pr));
      window.dispatchEvent(new CustomEvent('bytequay:pr-monitor-changed'));
    }
    catch (failure) {
      setMonitorError(`Could not ${row.monitored ? 'stop monitoring' : 'monitor'} ${row.repo} #${row.num}. ${remoteErrorMessage(failure)}`);
    }
    finally {
      monitorRequests.current.delete(row.id);
      setMonitoringIds(current => current.filter(id => id !== row.id));
    }
  };

  const changeCommitRange = (range: CommitRange) => {
    if (!selectedPr) return;
    const start = range === null ? null : diffCommits[range.start];
    const end = range === null ? null : diffCommits[range.end];
    if (range !== null && (!start?.parentSha || !end?.sha)) {
      setError('This commit range does not have enough Git history to compare.');
      return;
    }
    setDiffRange(range);
    setDiffs([]);
    setPendingReviewComments([]);
    setDiffLoading(true);
    setError(null);
    const request = ++diffRequest.current;
    const filesRequest = range === null
      ? window.bridge.fetchPrDiffFiles(selectedPr.repo, selectedPr.number)
      : window.bridge.refreshPrDiffFiles(selectedPr.repo, selectedPr.number, start?.parentSha, end?.sha);
    filesRequest
      .then(files => {
        if (request === diffRequest.current) setDiffs(files);
      })
      .catch(e => {
        if (request === diffRequest.current) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (request === diffRequest.current) setDiffLoading(false);
      });
  };

  const refresh = useCallback(async () => {
    if (!selectedPr || detailLoading) return;
    const selectedKey = `${selectedPr.repo.toLowerCase()}#${selectedPr.number}`;
    if (selectedPullRequest.current?.key !== selectedKey) return;
    const request = ++manualDetailRefreshRequest.current;
    const listVersion = listRequest.current;
    const requestMutationVersion = detailMutationVersion.current;
    setDetailLoading(true);
    try {
      const value = await window.bridge.refreshAllPullRequestDetailByRef(
        selectedPr.repo, selectedPr.number);
      if (request !== manualDetailRefreshRequest.current
          || selectedPullRequest.current?.key !== selectedKey) return;
      applyDetail(selectedPr.id, value, true, requestMutationVersion);
      if (!overlay && listVersion === listRequest.current) {
        try {
          const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, 0, PAGE_SIZE, query);
          if (request === manualDetailRefreshRequest.current
              && selectedPullRequest.current?.key === selectedKey
              && listVersion === listRequest.current
              && requestMutationVersion === detailMutationVersion.current) applyPage(result);
        }
        catch (e) {
          if (listVersion === listRequest.current) throw e;
        }
      }
    }
    catch (e) {
      if (request === manualDetailRefreshRequest.current
          && selectedPullRequest.current?.key === selectedKey) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }
    finally {
      if (request === manualDetailRefreshRequest.current
          && selectedPullRequest.current?.key === selectedKey) setDetailLoading(false);
    }
  }, [applyDetail, applyPage, detailLoading, overlay, query, repositoryScope?.workspaceId, selectedPr]);

  const applyPostedDetail = useCallback((value: PullRequestDetailDto, kind: 'discussion' | 'review') => {
    const current = selectedPullRequest.current;
    if (current?.key === `${value.repo.toLowerCase()}#${value.number}`) {
      updateSelectedDetail(value.repo, value.number, detail => mergePullRequestWriteDetail(detail, value));
      setPrs(rows => rows.map(pr => pr.id === current.id
        ? reconcilePostedComment(
            pr, { state: value.state, updatedAt: value.updatedAt }, kind, new Date().toISOString())
        : pr));
    }
  }, [updateSelectedDetail]);

  const applySubmittedReview = useCallback((
    value: PullRequestDetailDto,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
  ) => {
    const current = selectedPullRequest.current;
    if (current?.key === `${value.repo.toLowerCase()}#${value.number}`) {
      updateSelectedDetail(value.repo, value.number, detail => mergePullRequestWriteDetail(detail, value));
      setPrs(rows => rows.map(pr => pr.id === current.id
        ? reconcileSubmittedReview(
            pr, { state: value.state, updatedAt: value.updatedAt }, event, new Date().toISOString())
        : pr));
    }
  }, [updateSelectedDetail]);

  useEffect(() => {
    const returned = returnedFromInAppBrowser(browserWasOpen.current, browserOpen);
    browserWasOpen.current = browserOpen;
    if (returned) void refresh();
  }, [browserOpen, refresh]);

  const resizeDetail = (event: ReactMouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = event.currentTarget.parentElement?.getBoundingClientRect().width ?? detailWidth;
    const move = (next: globalThis.MouseEvent) => {
      setDetailWidth(Math.max(DETAIL_MIN, Math.min(
        DETAIL_MAX,
        startWidth + startX - next.clientX,
      )));
    };
    const stop = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
  };

  // Recent records reading, not clicking: the dwell timer decides, and
  // opening the diff lands immediately because that is already intent.
  const recordDiffOpened = useRecentTouch(selectedPr === null ? null : {
    type: 'PULL_REQUEST',
    repo: selectedPr.repo,
    number: selectedPr.number,
    workspaceId: repositoryScope?.workspaceId ?? null,
  });

  const paneShown = paneOpen && selectedPr !== null;

  return (
    <div className={`pulls-page${overlay ? ' pulls-page--overlay' : ''}`}
      ref={overlayRef}
      role={overlay ? 'dialog' : undefined}
      aria-modal={overlay ? true : undefined}
      aria-label={overlay ? `Pull request ${initialPr?.repo} #${initialPr?.number}` : undefined}
      tabIndex={overlay ? -1 : undefined}
      onMouseDown={event => { if (overlay && event.target === event.currentTarget) onClose?.(); }}>
      {!overlay && <section className="pulls-list">
        <header className="pulls-toolbar" ref={toolbar}>
          <PullFilterTabs
            tab={tab}
            searching={query !== ''}
            counts={tabCounts}
            width={toolbarWidth}
            panelId={rowsId}
            onSelect={selectTab}
          />
          {/* The only element allowed to give: everything around it is
              flex: none, so the row can never scroll sideways. */}
          <div className="pulls-toolbar__spacer" />
          <button
            className={`pulls-toolbar-button${syncing ? ' is-syncing' : ''}`}
            type="button"
            title={syncing ? 'Syncing pull requests…' : 'Sync pull requests'}
            aria-label="Sync pull requests"
            aria-busy={syncing}
            disabled={loading || syncing}
            onClick={() => { void refreshList(); }}
          >
            <RefreshIcon />
          </button>
          <button
            className={`pulls-toolbar-button${paneShown ? ' is-active' : ''}`}
            type="button"
            title="Toggle pull request details"
            aria-label="Toggle pull request details"
            aria-pressed={paneShown}
            onClick={() => setPaneOpen(value => !value)}
          >
            <PaneIcon />
          </button>
        </header>
        <div className="pulls-search">
          <input
            ref={searchInput}
            type="search"
            value={search}
            placeholder={compact ? '⌕  Search pull requests' : '⌕  Search pull requests by title or #number'}
            aria-label="Search pull requests by title or number"
            onChange={event => setSearch(event.target.value)}
          />
          {repositoryScope && (
            <span className="pulls-scope" title={`Showing ${repositoryScope.repository}`}>
              {repositoryScope.repository}
            </span>
          )}
        </div>
        <div className="pulls-list__rows" id={rowsId} role="tabpanel" aria-labelledby={`${rowsId}-${tab}`}>
          {loading && <div className="hp-loading">Loading…</div>}
          {!loading && syncError && (
            <div className="pulls-sync-error" role="alert">
              Could not sync pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''}. {syncError}
            </div>
          )}
          {monitorError && <div className="pulls-sync-error" role="alert">{monitorError}</div>}
          {!loading && error && rows.length === 0 && (
            <div className="hp-empty" role="alert">
              Could not load pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''}. {error}
            </div>
          )}
          {!loading && !error && !syncError && rows.length === 0 && (
            <div className="hp-empty">
              {query
                ? `No pull requests match “${query}”.`
                : `No ${PULL_TABS.find(item => item.key === tab)?.empty ?? 'pull requests'}`
                  + `${repositoryScope ? ` for ${repositoryScope.repository}` : ''}.`}
            </div>
          )}
          {!loading && rows.map(row => (
            <PullRowItem
              key={row.id}
              row={row}
              wide={!paneShown}
              selected={row.id === visibleSelected}
              monitoring={monitoringIds.includes(row.id)}
              onPick={() => pick(row)}
              onMonitor={() => { void toggleMonitor(row); }}
            />
          ))}
          <div ref={loadMoreMarker} aria-hidden="true" />
          {loadingMore && <div className="hp-loading">Loading more…</div>}
        </div>
      </section>}

      {overlay && selectedPr === null && (
        <section className="pull-detail is-zoomed">
          <header className="pull-detail__header pull-detail__header--tabs">
            <div className="pull-detail__toolbar">
              <span>{initialPr?.repo} #{initialPr?.number}</span>
              <div className="pull-detail__header-actions">
                <button className="pull-detail__icon-action" type="button" aria-label="Close pull request details" title="Close (Esc)" onClick={onClose}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
            </div>
          </header>
          {error
            ? <div className="pull-detail__error" role="alert">{error}</div>
            : <div className="pull-detail__loading" role="status">Loading details…</div>}
        </section>
      )}

      {paneShown && selectedPr !== null && (
        <section className={`pull-detail${detailZoomed || overlay ? ' is-zoomed' : ''}`}
          style={detailZoomed || overlay ? undefined : { width: detailWidth }}>
          <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize pull request panes" onMouseDown={resizeDetail} title="Drag to resize" />
          <PullDetailHeader
            pr={selectedPr}
            detail={detail}
            tab={detailTab}
            review={review}
            onTab={next => {
              if (next === 'changes' && detailTab !== 'changes') recordDiffOpened();
              setDetailTab(next);
            }}
            onRefresh={refresh}
            onDetailUpdate={updateSelectedDetail}
            onReviewSubmitted={applySubmittedReview}
            refreshing={detailLoading}
            pendingComments={pendingReviewComments}
            onPendingCommentsChange={setPendingReviewComments}
            zoomed={detailZoomed || overlay}
            onZoom={() => setDetailZoomed(value => !value)}
            onClose={onClose}
          />
          {error && <div className="pull-detail__error">{error}</div>}
          {showBlockingDetailLoading(detailLoading, detail !== null) ? (
            <div className="pull-detail__loading">Loading details…</div>
          ) : detail === null ? (
            !error && <div className="pull-detail__loading">Select a pull request.</div>
          ) : detailTab === 'review' && (reviewLoading || reviewError !== null) ? (
            <div className="pull-detail__body pull-detail__review">
              {reviewLoading ? <div className="hp-loading" role="status">Loading agent review…</div> : (
                <div className="agr-empty">
                  <div className="agr-error" role="alert">Could not load agent review. {reviewError}</div>
                  <button type="button" className="agr-action" onClick={() => setReviewReload(value => value + 1)}>Retry</button>
                </div>
              )}
            </div>
          ) : (
            <DetailBody
              tab={detailTab}
              pr={selectedPr}
              detail={detail}
              workspaceId={workspaceId}
              viewer={viewer}
              review={review}
              reviewNavigation={reviewNavigation}
              focusFindingId={focusFindingId}
              onReviewChange={updateReview}
              onNavigateToChanges={target => {
                setReviewNavigation(target);
                setFocusFindingId(null);
                if (detailTab !== 'changes') {
                  recordDiffOpened();
                  setDetailTab('changes');
                }
              }}
              onOpenInReview={findingId => {
                setFocusFindingId(findingId);
                setDetailTab('review');
              }}
              diffs={diffs}
              diffCommits={diffCommits}
              diffRange={diffRange}
              diffLoading={diffLoading}
              pendingComments={pendingReviewComments}
              onCommitRangeChange={changeCommitRange}
              onPendingCommentsChange={setPendingReviewComments}
              contributorsWorkspaceId={repositoryScope?.workspaceId}
              onRefresh={refresh}
              onDetailUpdate={updateSelectedDetail}
              onDiscussionPosted={value => applyPostedDetail(value, 'discussion')}
              onReviewPosted={value => applyPostedDetail(value, 'review')}
            />
          )}
        </section>
      )}

      {toast && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
    </div>
  );
}

export function remotePullRequest(detail: PullRequestDetailDto, viewerLogin?: string): PullRequestDto {
  const origin = viewerLogin && detail.author?.toLowerCase() === viewerLogin.toLowerCase()
    ? 'AUTHORED'
    : isReviewRequestedForViewer(detail, viewerLogin ?? null) ? 'REVIEW_REQUESTED' : 'OTHER';
  return {
    id: detail.id,
    repo: detail.repo,
    number: detail.number,
    title: detail.title ?? `${detail.repo} #${detail.number}`,
    author: detail.author,
    htmlUrl: detail.htmlUrl ?? `https://github.com/${detail.repo}/pull/${detail.number}`,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt ?? detail.createdAt ?? new Date(0).toISOString(),
    origin,
    labels: detail.labels.map(label => label.name),
    labelColors: Object.fromEntries(detail.labels.map(label => [label.name, label.color ?? 'd0d7de'])),
    draft: detail.draft,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: detail.reviewers.map(reviewer => reviewer.login),
    ciStatus: detail.ciStatus,
    additions: detail.additions,
    deletions: detail.deletions,
    commentCount: detail.recentActivity.filter(item =>
      item.eventType === 'commented' || item.eventType === 'reviewed').length,
    attentionReason: null,
    state: detail.state,
    mergeable: detail.mergeable,
    mergeableState: detail.mergeableState,
    headPushedAt: null,
    headRef: detail.headRef,
    monitoredAt: null,
    activityAt: null,
    reviewRequestedAt: null,
    reviewDecision: null,
  };
}

export function PullDetailHeader({
  pr,
  detail,
  tab,
  review = null,
  agentTab,
  onTab,
  onRefresh,
  onDetailUpdate,
  onReviewSubmitted,
  refreshing,
  pendingComments,
  onPendingCommentsChange,
  zoomed,
  onZoom,
  zoomLabel,
  onClose,
}: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto | null;
  tab: DetailTab | null;
  /** The open Agent review, when the pull request has one; its status rides on the tab. */
  review?: AgentReviewDto | null;
  agentTab?: ReactNode;
  onTab: (tab: DetailTab) => void;
  onRefresh: () => Promise<void>;
  onDetailUpdate?: PullDetailUpdater;
  onReviewSubmitted: (
    detail: PullRequestDetailDto,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
  ) => void;
  refreshing: boolean;
  pendingComments: InlineReviewCommentDto[];
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  zoomed: boolean;
  onZoom?: () => void;
  zoomLabel?: string;
  onClose?: () => void;
}) {
  const [mergeQueue, setMergeQueue] = useState<boolean | null | undefined>(undefined);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const canReview = detail?.state?.toLowerCase() === 'open';
  // Promoted findings are outgoing comments too, so the count the button shows covers both.
  const pendingCommentCount = pendingComments.length + pendingDraftCount(review?.draft ?? null);
  const reviewChip = review === null ? null : reviewStatus(review);
  const draft = detail?.draft === true;
  const canOpenMerge = detail?.state?.toLowerCase() === 'open';
  const canMerge = canOpenMerge && !draft && detail.viewerCanWrite && !isQueued(detail.recentActivity);
  const conflicts = hasMergeConflicts(detail);
  const merge = detail ? mergeStatus(detail) : null;
  const mergeModeReady = typeof mergeQueue === 'boolean';
  const mergeBlocked = merge?.state === 'failed' || merge?.state === 'blocked';
  const mergeLoading = (canMerge && !mergeModeReady) || merge?.state === 'pending';
  const state = (detail?.state ?? pr.state)?.toLowerCase();
  const iconState = state === 'merged' || state === 'closed'
    ? state
    : (detail?.draft ?? pr.draft) ? 'draft' : 'open';
  const mergeLabel = conflicts
    ? 'Conflicts'
    : merge?.state === 'failed'
        ? 'Checks failed'
        : merge?.state === 'blocked'
          ? 'Merge blocked'
          : merge?.state === 'pending'
            ? 'Checks running'
            : draft || !canMerge
              ? merge?.checksReported ? 'Checks passed' : 'No checks reported'
              : mergeActionLabel(mergeQueue);

  useEffect(() => {
    setMergeOpen(false);
    setReviewOpen(false);
    setMergeQueue(undefined);
    if (!canMerge || conflicts || !detail?.baseRef) return;
    let cancelled = false;
    void window.bridge.fetchMergeMode(detail.repo, detail.baseRef)
      .then(mode => { if (!cancelled) setMergeQueue(mode.mergeQueue); })
      .catch(() => { if (!cancelled) setMergeQueue(null); });
    return () => { cancelled = true; };
  }, [canMerge, draft, conflicts, detail?.repo, detail?.number, detail?.baseRef]);

  return (
    <header className="pull-detail__header pull-detail__header--tabs">
      <div className="pull-detail__toolbar">
        <nav className="pull-detail__tabs" aria-label="Pull request detail">
          <button
            className={`pull-detail__pr-tab is-${iconState}${tab === 'overview' ? ' is-active' : ''}`}
            type="button"
            aria-pressed={tab === 'overview'}
            onClick={() => onTab('overview')}>
            <PullRequestIcon merged={iconState === 'merged'} /> PR #{pr.number}
          </button>
          <button
            className={tab === 'changes' ? 'is-active' : ''}
            type="button"
            aria-pressed={tab === 'changes'}
            onClick={() => onTab('changes')}>
            <Diff size={14} strokeWidth={1.8} aria-hidden="true" /> Changes
            <span className="pull-detail__change-count is-addition">+{detail?.additions ?? pr.additions}</span>
            <span className="pull-detail__change-count is-deletion">−{detail?.deletions ?? pr.deletions}</span>
          </button>
          {agentTab === undefined ? <button
            className={tab === 'review' ? 'is-active' : ''}
            type="button"
            aria-pressed={tab === 'review'}
            onClick={() => onTab('review')}>
            <CommentIcon /> Agent review
            {reviewChip?.chip != null && (
              <span className={`pull-detail__review-chip is-${reviewChip.tone}`}>
                {reviewChip.busy && <span className="agr-spinner" aria-hidden="true" />}
                {reviewChip.chip}
              </span>
            )}
          </button> : agentTab}
        </nav>
        <div className="pull-detail__header-actions">
          {onClose ? <button className="pull-detail__icon-action" type="button"
            title="Close (Esc)" aria-label="Close pull request details" onClick={onClose}>
            <X size={16} aria-hidden="true" />
          </button> : onZoom && <button className="pull-detail__icon-action" type="button"
            title={zoomLabel ?? (zoomed ? 'Exit full screen (Esc)' : 'Enter full screen')}
            aria-label={zoomLabel ?? (zoomed ? 'Exit full screen' : 'Enter full screen')}
            aria-pressed={zoomLabel ? undefined : zoomed} onClick={onZoom}>
            <ZoomIcon zoomed={zoomed} />
          </button>}
          <button className={`pull-detail__icon-action${refreshing ? ' is-refreshing' : ''}`} type="button"
            title={refreshing ? 'Refreshing pull request…' : 'Refresh'}
            aria-label="Refresh pull request"
            aria-busy={refreshing} disabled={refreshing}
            onClick={() => { void onRefresh(); }}>
            <RefreshIcon />
          </button>
          {canReview && (
            <button className="pull-detail__review-action" type="button"
              aria-label={pendingCommentCount === 0
                ? 'Submit review'
                : `Submit review, ${pendingCommentCount} pending review comment${pendingCommentCount === 1 ? '' : 's'}`}
              onClick={() => setReviewOpen(true)}>
              Submit review
              {pendingCommentCount > 0 && (
                <span className="pull-detail__review-count" aria-hidden="true">{pendingCommentCount}</span>
              )}
            </button>
          )}
          {canOpenMerge && (
            <button
              className={`pull-detail__merge-action${conflicts || mergeBlocked ? ' is-conflicts' : mergeLoading ? ' is-loading' : ''}`}
              type="button"
              disabled={refreshing}
              onClick={() => setMergeOpen(true)}>
              {conflicts || mergeBlocked
                ? <ConflictIcon />
                : mergeLoading
                  ? <MergeReadyIcon />
                  : <PullRequestIcon />} {mergeLabel}
            </button>
          )}
        </div>
      </div>
      {reviewOpen && detail && (
        <ReviewDialog pr={pr} detail={detail}
          pendingComments={pendingComments}
          onPendingCommentsChange={onPendingCommentsChange}
          onClose={() => setReviewOpen(false)} onReviewSubmitted={onReviewSubmitted} />
      )}
      {mergeOpen && detail && (
        <MergeDialog pr={pr} detail={detail} mergeQueue={mergeQueue}
          onClose={() => setMergeOpen(false)} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />
      )}
    </header>
  );
}

export function canSubmitReview(pr: PullRequestDto, detail: PullRequestDetailDto | null) {
  return pr.origin !== 'AUTHORED' && detail?.state?.toLowerCase() === 'open';
}

export function isReviewRequestedForViewer(detail: PullRequestDetailDto | null, viewerLogin: string | null) {
  return detail?.state?.toLowerCase() === 'open'
    && Boolean(viewerLogin && detail.reviewers.some(reviewer =>
      reviewer.login.toLowerCase() === viewerLogin.toLowerCase() && reviewer.state.toLowerCase() === 'pending'));
}

export function hasMergeConflicts(detail: PullRequestDetailDto | null) {
  return ['dirty', 'conflicting', 'conflicts'].includes(detail?.mergeableState?.toLowerCase() ?? '');
}

export function mergeActionLabel(mergeQueue: boolean | null | undefined, conflicts = false) {
  if (conflicts) return 'Conflicts';
  if (mergeQueue === undefined) return 'Loading merge status';
  if (mergeQueue === null) return 'Merge status unavailable';
  return mergeQueue ? 'Merge when ready' : 'Rebase and merge';
}

export function unresolvedReviewCount(detail: PullRequestDetailDto) {
  return detail.recentActivity.filter(activity =>
    activity.eventType === 'review_thread' && !activity.payload.resolved).length;
}

export function mergeStatus(detail: PullRequestDetailDto) {
  const counts = detail.checkRuns.reduce((result, check) => {
    result[check.category] = (result[check.category] ?? 0) + 1;
    return result;
  }, {} as Record<string, number>);
  const pending = counts.pending ?? 0;
  const failing = counts.failing ?? 0;
  const successful = counts.successful ?? 0;
  const unresolved = unresolvedReviewCount(detail);
  const state: 'failed' | 'pending' | 'blocked' | 'ready' = failing > 0
    ? 'failed'
    : pending > 0
      ? 'pending'
      : unresolved > 0
        ? 'blocked'
        : 'ready';
  return { pending, failing, successful, unresolved, checksReported: detail.checkRuns.length > 0, state };
}

type ReviewEvent = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';

export function ReviewDialog({
  pr,
  detail,
  pendingComments = [],
  onPendingCommentsChange = () => undefined,
  onReviewSubmitted,
  onClose,
}: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  pendingComments?: InlineReviewCommentDto[];
  onPendingCommentsChange?: (comments: InlineReviewCommentDto[]) => void;
  onReviewSubmitted: (
    detail: PullRequestDetailDto,
    event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
  ) => void;
  onClose: () => void;
}) {
  const [body, setBody] = useState('');
  const authored = pr.origin === 'AUTHORED';
  const [selectedEvent, setEvent] = useState<ReviewEvent>(authored ? 'COMMENT' : 'APPROVE');
  const event = authored ? 'COMMENT' : selectedEvent;
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bodyRequired = event !== 'APPROVE' && pendingComments.length === 0;

  useEffect(() => {
    const close = (keyboardEvent: KeyboardEvent) => {
      if (keyboardEvent.defaultPrevented || keyboardEvent.key !== 'Escape') return;
      keyboardEvent.preventDefault();
      if (!submitting) onClose();
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async () => {
    if (submitting || detail.state?.toLowerCase() !== 'open' || (bodyRequired && !body.trim())) return;
    setSubmitting(true);
    setError(null);
    try {
      const reviewed = await window.bridge.submitReview(
        pr.id,
        detail.repo,
        detail.number,
        event,
        body.trim(),
        pendingComments,
      );
      onPendingCommentsChange([]);
      // A review cannot move the diff, so the patched detail is enough and the
      // Changes tab keeps what it already loaded.
      onReviewSubmitted(reviewed, event);
      onClose();
    }
    catch (value) {
      setError(value instanceof Error ? value.message : String(value));
      setSubmitting(false);
    }
  };

  return (
    <div className="pr-sheet-overlay" onMouseDown={mouseEvent => {
      if (mouseEvent.target === mouseEvent.currentTarget && !submitting) onClose();
    }}>
    <section className="pr-review-sheet" role="dialog" aria-modal="true" aria-labelledby="pr-review-title">
      <div className="pr-review-sheet__header">
        <h2 id="pr-review-title">Submit review</h2>
        <button type="button" aria-label="Close review dialog" disabled={submitting} onClick={onClose}>×</button>
      </div>
      <div className="pr-review-sheet__content">
        <textarea
          autoFocus
          value={body}
          placeholder="Leave a comment on this pull request…"
          aria-label="Review comment"
          onChange={change => setBody(change.target.value)} />
        {pendingComments.length > 0 && (
          <div className="pr-review-sheet__pending">
            {pendingComments.map((comment, index) => (
              <div key={`${comment.path}:${comment.side}:${comment.startLine ?? comment.line}:${comment.line}:${index}`}>
                <span><code>{comment.path}</code> · {comment.startLine && comment.startLine !== comment.line
                  ? `lines ${comment.startLine}–${comment.line}`
                  : `line ${comment.line}`}</span>
                <p>{comment.body}</p>
                <button type="button" onClick={() => onPendingCommentsChange(
                  pendingComments.filter((_, commentIndex) => commentIndex !== index),
                )}>Remove</button>
              </div>
            ))}
          </div>
        )}
        <ReviewChoice
          checked={event === 'COMMENT'}
          title="Comment"
          description="Submit general feedback without explicit approval."
          onChange={() => setEvent('COMMENT')} />
        {!authored && <ReviewChoice
          checked={event === 'APPROVE'}
          title="Approve"
          description="Submit feedback and approve merging these changes."
          onChange={() => setEvent('APPROVE')} />}
        {!authored && <ReviewChoice
          checked={event === 'REQUEST_CHANGES'}
          title="Request Changes"
          description="Submit feedback that must be addressed before merging."
          onChange={() => setEvent('REQUEST_CHANGES')} />}
        {error && <div className="pr-review-sheet__error" role="alert">{error}</div>}
      </div>
      <footer className="pr-review-sheet__footer">
        <button type="button" disabled={submitting} onClick={onClose}>Cancel</button>
        <button className="is-primary" type="button"
          disabled={submitting || (bodyRequired && !body.trim())}
          onClick={() => { void submit(); }}>
          {submitting ? 'Submitting…' : 'Submit review'}
        </button>
      </footer>
    </section>
    </div>
  );
}

function ReviewChoice({ checked, title, description, onChange }: {
  checked: boolean;
  title: string;
  description: string;
  onChange: () => void;
}) {
  return (
    <label className="pr-review-choice">
      <input type="radio" name="review-event" checked={checked} onChange={onChange} />
      <span><strong>{title}</strong><small>{description}</small></span>
    </label>
  );
}

export function MergeDialog({ pr, detail, mergeQueue, onClose, onRefresh, onDetailUpdate = () => {} }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  mergeQueue: boolean | null | undefined;
  onClose: () => void;
  onRefresh: () => Promise<void>;
  onDetailUpdate?: PullDetailUpdater;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { pending, failing, successful, unresolved, checksReported, state } = mergeStatus(detail);
  const blocked = state === 'failed' || state === 'blocked';
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const submitted = useRef(false);
  const canMarkReady = detail.draft && detail.state?.toLowerCase() === 'open'
    && (detail.viewerCanWrite || pr.origin === 'AUTHORED');
  const canMerge = !detail.draft && detail.state?.toLowerCase() === 'open' && detail.viewerCanWrite && state === 'ready'
    && typeof mergeQueue === 'boolean' && !hasMergeConflicts(detail) && !isQueued(detail.recentActivity);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const close = (keyboardEvent: KeyboardEvent) => {
      if (keyboardEvent.defaultPrevented || keyboardEvent.key !== 'Escape') return;
      keyboardEvent.preventDefault();
      if (!submitting) onClose();
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async (action: 'ready' | 'merge') => {
    if (inFlight.current || submitted.current || (action === 'ready' ? !canMarkReady : !canMerge)) return;
    inFlight.current = true;
    setSubmitting(true);
    setError(null);
    try {
      if (action === 'ready') {
        await window.bridge.markPrReadyForReview(detail.repo, detail.number);
        onDetailUpdate(detail.repo, detail.number, current => ({ ...current, draft: false }));
      }
      else {
        await window.bridge.mergePr(pr.id, detail.repo, detail.number, mergeQueue ? 'queue' : 'rebase');
      }
      submitted.current = true;
      if (!mounted.current) return;
      onClose();
      await onRefresh();
    }
    catch (value) {
      if (mounted.current) {
        setError(remoteErrorMessage(value));
        setSubmitting(false);
      }
    }
    finally { inFlight.current = false; }
  };

  return (
    <div className="pr-sheet-overlay" onMouseDown={mouseEvent => {
      if (mouseEvent.target === mouseEvent.currentTarget && !submitting) onClose();
    }}>
    <section className="pr-merge-sheet" role="dialog" aria-modal="true" aria-labelledby="pr-merge-title">
      <div className="pr-merge-sheet__header">
        <h2 id="pr-merge-title">Merge pull request</h2>
        <button type="button" aria-label="Close merge dialog" autoFocus disabled={submitting} onClick={onClose}>×</button>
      </div>
      <div className="pr-merge-sheet__content">
        <div className={`pr-merge-status-card${blocked ? ' is-danger' : ''}`}>
          {blocked
            ? <ConflictIcon />
            : state === 'pending'
              ? <MergeReadyIcon />
              : <span className={`pr-merge-dot${mergeQueue ? ' is-queue' : ''}`} />}
          <strong>{blocked
            ? 'Merge blocked'
            : state === 'pending'
              ? 'Waiting for checks'
              : detail.draft ? 'Draft pull request'
                : !detail.viewerCanWrite ? 'Checks complete'
                  : typeof mergeQueue !== 'boolean' ? mergeActionLabel(mergeQueue)
                    : mergeQueue ? 'Merge queue is enabled' : 'Ready to rebase and merge'}</strong>
        </div>
        <div className="pr-merge-status-card is-stacked">
          <div>
            {failing > 0
                ? <ConflictIcon />
              : pending > 0
                ? <MergeReadyIcon />
                : checksReported
                  ? <MergeSuccessIcon />
                  : <span className="pr-merge-dot" aria-hidden="true" />}
            <strong>{failing > 0
                ? 'Some checks were not successful'
              : pending > 0
                ? 'Checks are running'
                : checksReported
                  ? 'All checks have passed'
                  : 'No checks reported'}</strong>
          </div>
          <div className="pr-merge-status-card__groups">
            {pending > 0 && <span>In progress ({pending})</span>}
            {failing > 0 && <span>Failing ({failing})</span>}
            {successful > 0 && <span>Successful ({successful})</span>}
            {detail.checkRuns.length === 0 && <span>No checks reported</span>}
          </div>
        </div>
        {unresolved > 0 && (
          <div className="pr-merge-status-card is-danger">
            <span aria-hidden="true">×</span>
            <strong>{unresolved} unresolved review comment{unresolved === 1 ? '' : 's'}</strong>
          </div>
        )}
        {error && <div className="pr-merge-sheet__error" role="alert">{error}</div>}
      </div>
      <footer className="pr-merge-sheet__footer">
        <button type="button" disabled={submitting} onClick={onClose}>Cancel</button>
        <button className="is-primary" type="button" disabled={submitting || !canMarkReady}
          onClick={() => { void submit('ready'); }}>
          <PullRequestIcon /> {submitting && detail.draft ? 'Marking ready…' : 'Ready for review'}
        </button>
        <button className="is-primary" type="button" disabled={submitting || !canMerge}
          onClick={() => { void submit('merge'); }}>
          <PullRequestIcon /> {submitting && !detail.draft ? 'Submitting…' : mergeQueue === false ? 'Rebase and merge' : 'Merge when ready'}
        </button>
      </footer>
    </section>
    </div>
  );
}

function MergeReadyIcon() {
  return <span className="pr-merge-ready-icon" aria-hidden="true"><i /></span>;
}

function MergeSuccessIcon() {
  return <span className="pr-merge-success-icon" aria-hidden="true"><Check size={11} strokeWidth={2.4} /></span>;
}

function ConflictIcon() {
  return <span className="pr-merge-conflict-icon" aria-hidden="true">×</span>;
}

function DetailBody({
  tab,
  pr,
  detail,
  workspaceId,
  viewer,
  review,
  reviewNavigation,
  focusFindingId,
  onReviewChange,
  onNavigateToChanges,
  onOpenInReview,
  diffs,
  diffCommits,
  diffRange,
  diffLoading,
  pendingComments,
  onCommitRangeChange,
  onPendingCommentsChange,
  contributorsWorkspaceId,
  onRefresh,
  onDetailUpdate,
  onDiscussionPosted,
  onReviewPosted,
}: {
  tab: DetailTab;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  workspaceId: string | undefined;
  viewer: UserProfileDto | null;
  review: AgentReviewDto | null;
  reviewNavigation: ReviewNavigationTarget | null;
  focusFindingId: string | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onNavigateToChanges: (target: ReviewNavigationTarget) => void;
  onOpenInReview: (findingId: string) => void;
  diffs: DiffFileDto[] | null;
  diffCommits: DiffCommitDto[];
  diffRange: CommitRange;
  diffLoading: boolean;
  pendingComments: InlineReviewCommentDto[];
  onCommitRangeChange: (range: CommitRange) => void;
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  contributorsWorkspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onDiscussionPosted: (detail: PullRequestDetailDto) => void;
  onReviewPosted: (detail: PullRequestDetailDto) => void;
}) {
  if (tab === 'review') {
    return <div className="pull-detail__body pull-detail__review"><AgentReviewTab
      key={`${workspaceId ?? ''}:${detail.repo.toLowerCase()}#${detail.number}`}
      detail={detail}
      workspaceId={workspaceId}
      viewer={viewer}
      review={review}
      onReviewChange={onReviewChange}
      onNavigateToChanges={onNavigateToChanges}
      focusFindingId={focusFindingId}
    /></div>;
  }
  if (tab === 'changes') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return <div className="pull-detail__body pull-detail__changes"><PullChanges
      repo={detail.repo}
      number={detail.number}
      workspaceId={contributorsWorkspaceId}
      files={diffs}
      commits={diffCommits}
      commitRange={diffRange}
      loading={diffLoading}
      canReview={canSubmitReview(pr, detail)}
      pendingComments={pendingComments}
      agentReview={review === null ? undefined : {
        review,
        viewer,
        navigation: reviewNavigation,
        onReviewChange,
        onOpenInReview,
      }}
      onCommitRangeChange={onCommitRangeChange}
      reviewThreads={{
        activities: detail.recentActivity,
        pr,
        workspaceId: contributorsWorkspaceId,
        unavailable: detail.unavailableSections.includes('reviewThreads'),
        onRefresh,
        onDetailUpdate,
      }}
      onPendingCommentsChange={onPendingCommentsChange}
      onDetailChange={onReviewPosted}
    /></div>;
  }
  return <div className="pull-detail__body"><PullOverview
    key={`${pr.repo.toLowerCase()}#${pr.number}`}
    pr={pr}
    detail={detail}
    contributorsWorkspaceId={contributorsWorkspaceId}
    onRefresh={onRefresh}
    onDetailUpdate={onDetailUpdate}
    onDetailChange={onDiscussionPosted}
  /></div>;
}

export function RefreshIcon() {
  return <RefreshCw size={15} strokeWidth={1.8} aria-hidden="true" />;
}

function ZoomIcon({ zoomed }: { zoomed: boolean }) {
  return zoomed
    ? <Minimize size={15} strokeWidth={1.8} aria-hidden="true" />
    : <Maximize size={15} strokeWidth={1.8} aria-hidden="true" />;
}

export function PaneIcon() {
  return <PanelRight size={15} strokeWidth={1.7} aria-hidden="true" />;
}

export function CommentIcon() {
  return <MessageCircle size={14} strokeWidth={1.8} aria-hidden="true" />;
}
