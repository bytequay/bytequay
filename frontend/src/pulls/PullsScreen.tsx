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
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { Bell, Check, CircleSlash, Code, Diff, Ellipsis, Eye, GitPullRequest, Maximize, MessageCircle, Minimize, PanelRight, RefreshCw, UserRound, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { isFindShortcut } from '../FindBar';
import type { NavigationMode } from '../navigationHistory';
import Toast from '../Toast';
import type { AgentTaskDto, Bridge, DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto, PullRequestPageDto, UserProfileDto } from '../types';
import AgentReviewTab, { type ReviewNavigationTarget } from './agent-review/AgentReviewTab';
import CodeTestsTab from './code-tests/CodeTestsTab';
import PullRequestTaskActions from './PullRequestTaskActions';
import type { ReviewCardFocus } from '../agents/ReviewRequestCard';
import type { AgentReviewDto, ReviewDraftItemDto, ReviewEvent } from './agent-review/contracts';
import { pendingDraftCount, reviewStatus } from './agent-review/model';
import { useLocalReviewThreads } from './local-review/useLocalReviewThreads';
import type { LocalReviewThreads } from './local-review/types';
import PullChanges, { type CommitRange, type DiffAgentComment } from './PullChanges';
import MarkdownBody from './MarkdownBody';
import PullOverview, { CommentedCodeLine, isQueued, mergePullRequestWriteDetail, patchCommentLine, remoteErrorMessage, useViewer, type PullDetailUpdater } from './PullOverview';
import { ReviewThreadCard, ReviewThreadMessage } from './ReviewThreadCard';
import UserAvatar from '../UserAvatar';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import { mergePullRequestDetailStatus, usePullRequestDetail } from './usePullRequestDetail';
import { pullRequestDiffRevision, usePullRequestChanges } from './usePullRequestChanges';
import { useRecentTouch } from '../recent/useRecentTouch';
import { workspaceReviewTaskRoute } from '../workspaces/model';
import TaskPaneDivider, { useTaskPane } from '../agents/TaskPaneDivider';
import { useResizablePane } from '../useResizablePane';
import '../css/review-tasks.css';
import {
  filterPullRequests,
  keepSelectedPullRequest,
  parsePullSearch,
  PULL_TABS,
  reconcilePostedComment,
  reconcilePullRequestDetail,
  reconcileSubmittedReview,
  rowsForTab,
  tabForInitialPullRequest,
  visibleSelectedId,
  type PullRow,
  type PullTab,
} from './model';
import '../css/pulls.css';

/** The tabs PullDetailHeader draws itself; hosts add their own through its `agentTab` slot. */
type HeaderTab = 'overview' | 'changes' | 'review';
type DetailTab = HeaderTab | 'code';

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
  findTask?: Bridge['findPullRequestTask'],
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
  if (findTask !== undefined) {
    for (const workspace of workspaces) {
      if (await findTask(workspace.id, repo, number) !== null) {
        return { workspaceId: workspace.id, review: await bridge.findAgentReview(workspace.id, repo, number) };
      }
    }
  }
  // Several fork workspaces can watch the same upstream. Reuse a review before offering a new one.
  for (const workspace of workspaces) {
    const review = await bridge.findAgentReview(workspace.id, repo, number);
    if (review !== null) return { workspaceId: workspace.id, review };
  }
  return { workspaceId: workspaces[0]?.id, review: null };
}

/**
 * The development task whose worktree Code & Tests reads instead of any review: the interactive task that owns the pull
 * request and still has its worktree. Both reads are local.
 */
export async function findCodeTask(bridge: Pick<Bridge, 'findPullRequestTask' | 'getAgentTask'>, workspaceId: string, repo: string, number: number) {
  const link = await bridge.findPullRequestTask(workspaceId, repo, number);
  if (link === null) return null;
  const task = await bridge.getAgentTask(link.taskId, link.workspaceId);
  return task.interactive === true && task.checkoutRemovedAt === null ? task : null;
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
  initialReviewId,
  repositoryScope,
  browserOpen = false,
  onSelectPullRequest,
  onClose,
  reviewWorkspace,
}: {
  initialPr?: { repo: string; number: number };
  /** Opens an exact retained review from the workspace Tasks list. */
  initialReviewId?: string;
  repositoryScope?: { workspaceId: string; repository: string };
  browserOpen?: boolean;
  onSelectPullRequest?: (reference: { repo: string; number: number }, mode: NavigationMode) => void;
  /** Opens only initialPr over the current page, without list reads or navigation. */
  onClose?: () => void;
  reviewWorkspace?: { onBack: () => void; headerAction?: ReactNode };
}) {
  const singlePullRequest = onClose !== undefined || reviewWorkspace !== undefined;
  const overlay = onClose !== undefined;
  const reviewPane = useTaskPane();
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [viewerLogin, setViewerLogin] = useState<string | null>();
  const [viewer, setViewer] = useState<UserProfileDto | null>(null);
  /** The open review of the selected pull request, loaded once per selection and kept live by its tab. */
  const [reviewContext, setReviewContext] = useState<(AgentReviewContext & { key: string; error: string | null }) | null>(null);
  // Looked up apart from the review, so a slow or failed task lookup never holds up Review & Learning.
  const [codeTaskLookup, setCodeTaskLookup] = useState<{ key: string; task: AgentTaskDto | null } | null>(null);
  const [reviewReload, setReviewReload] = useState(0);
  /** Where Changes should scroll, set when a finding asks to be shown at its diff location. */
  const [reviewNavigation, setReviewNavigation] = useState<ReviewNavigationTarget | null>(null);
  /** A finding the Changes tab sent back to Agent review, so its card opens and scrolls. */
  const [focusFindingId, setFocusFindingId] = useState<string | null>(null);
  const [reviewCardFocus, setReviewCardFocus] = useState<ReviewCardFocus | null>(null);
  const [tab, setTab] = useState<PullTab>(PULL_TABS[0].key);
  const [selected, setSelected] = useState<number | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>(initialReviewId === undefined ? 'overview' : 'review');
  // Code & Tests stays mounted after its first visit so drafts, open files and runs survive tab switches.
  const [codeTabKey, setCodeTabKey] = useState<string | null>(null);
  // Unsaved Code & Tests drafts live only in that tab; leaving the PR or closing the pane asks first.
  const codeUnsaved = useRef(0);
  const keepCodeDrafts = useCallback(() => codeUnsaved.current === 0
    || window.confirm(`Discard unsaved changes to ${codeUnsaved.current} ${codeUnsaved.current === 1 ? 'file' : 'files'}?`), []);
  const closePane = useMemo(() => onClose === undefined ? undefined : () => {
    if (keepCodeDrafts()) onClose();
  }, [keepCodeDrafts, onClose]);
  const [pendingReviewComments, setPendingReviewComments] = useState<InlineReviewCommentDto[]>([]);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  const detailPane = useResizablePane(DETAIL_DEFAULT, DETAIL_MIN, DETAIL_MAX, 'start');
  const [loading, setLoading] = useState(!singlePullRequest);
  const [syncing, setSyncing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [monitorError, setMonitorError] = useState<string | null>(null);
  const [monitoringIds, setMonitoringIds] = useState<number[]>([]);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  /** The debounced query split into its author and text terms; the list filters what it has. */
  const searchTerms = useMemo(() => parsePullSearch(query), [query]);
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
  const detailState = useRef<PullRequestDetailDto | null>(null);
  const detailMutationVersion = useRef(0);
  const selectedPullRequest = useRef<{ id: number; key: string; pr: PullRequestDto } | null>(null);
  const listRequest = useRef(0);
  const manualDetailRefreshRequest = useRef(0);
  const toastId = useRef(0);
  const listContext = useRef({ workspaceId: repositoryScope?.workspaceId, query });
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
    const listed = keepSelectedPullRequest(pullRequests, selected?.pr ?? null);
    setPrs(listed);
    setPage(result.page);
    setHasMore(result.hasMore);
    setSelected(current => listed.some(pr => pr.id === current)
      ? current
      : null);
  }, []);

  useEffect(() => {
    if (singlePullRequest) return;
    return window.bridge.onPullRequestsSynced(() => {
      const current = listContext.current;
      const request = ++listRequest.current;
      void window.bridge.fetchPrs(current.workspaceId, 0, PAGE_SIZE, current.query)
        .then(result => { if (request === listRequest.current) applyPage(result); })
        .catch(() => {});
    });
  }, [applyPage, singlePullRequest]);

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
      if (event.target instanceof Element && event.target.closest('.cm-editor, .xterm') !== null) return;
      event.preventDefault();
      if (closePane) closePane();
      else setDetailZoomed(false);
    };
    window.addEventListener('keydown', exitFullScreen);
    return () => window.removeEventListener('keydown', exitFullScreen);
  }, [browserOpen, closePane, detailZoomed, overlay]);

  useEffect(() => {
    if (singlePullRequest) return;
    const request = ++listRequest.current;
    setLoading(true);
    setError(null);
    setSyncError(null);
    setPrs([]);
    // A new search or workspace lists different pull requests; don't let applyPage keep the old one before the next render.
    selectedPullRequest.current = null;
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
  }, [applyPage, singlePullRequest, query, repositoryScope?.workspaceId]);

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
    if (singlePullRequest || browserOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof Element && event.target.closest('.cm-editor, .xterm') !== null) return;
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
  }, [singlePullRequest, selectTab, browserOpen]);

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
    // Not an effect cleanup: this effect re-runs on every prs change (the first row's
    // own detail load is one), which would abandon the lookup and leave that row showing.
    const stillRouting = () => routingInitialPr.current === initialPrKey;
    Promise.all([
      window.bridge.fetchPullRequestDetailByRef(initialPr.repo, initialPr.number),
      window.bridge.fetchAttentionPrs().catch((): PullRequestDto[] => []),
    ]).then(([value, tracked]) => {
      if (!stillRouting()) return;
      const remote = tracked.find(pr => pr.id === value.id) ?? remotePullRequest(value, viewerLogin ?? undefined);
      setPrs(current => current.some(pr => pr.id === remote.id) ? current : [remote, ...current]);
      setTab(current => tabForInitialPullRequest(remote, current, viewerLogin));
      setSelected(remote.id);
    }).catch(e => {
      if (stillRouting()) setError(e instanceof Error ? e.message : String(e));
    });
  }, [initialPr, loading, prs, viewerLogin]);

  // A search still respects the selected filter: `@alice` lists the pull
  // requests alice authored within whichever tab is open, from the loaded page.
  const rows = useMemo(
    () => rowsForTab(filterPullRequests(prs, searchTerms), tab, viewerLogin),
    [prs, searchTerms, tab, viewerLogin],
  );
  const compact = compactToolbar(toolbarWidth);
  // ponytail: one pass per tab over the loaded page; revisit only if the list
  // ever holds thousands of rows. Unloaded pages make each count a floor. The
  // counts stay unfiltered, because rows are filtered below by a page-scoped search.
  const tabCounts = useMemo(
    () => PULL_TABS.map(item => `${rowsForTab(prs, item.key, viewerLogin).length}${hasMore ? '+' : ''}`),
    [hasMore, prs, viewerLogin],
  );
  const visibleSelected = singlePullRequest ? selected : visibleSelectedId(rows, selected);
  const selectedPr = prs.find(pr => pr.id === visibleSelected) ?? null;
  const selectedPrId = selectedPr?.id;
  const selectedPrNumber = selectedPr?.number;
  const selectedPrRepo = selectedPr?.repo;
  selectedPullRequest.current = selectedPr === null
    ? null
    : { id: selectedPr.id, key: `${selectedPr.repo.toLowerCase()}#${selectedPr.number}`, pr: selectedPr };

  useEffect(() => {
    if (singlePullRequest) return;
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
  }, [onSelectPullRequest, singlePullRequest, selected, selectedPrId, selectedPrNumber, selectedPrRepo]);

  const localReview = useLocalReviewThreads(selectedPr === null ? null : {
    repo: selectedPr.repo, number: selectedPr.number, workspaceId: repositoryScope?.workspaceId,
  }, { active: paneOpen });

  const {
    detail,
    loading: detailLoading,
    error: detailError,
    refresh: refreshDetail,
    update: updateDetail,
  } = usePullRequestDetail(selectedPr, {
    active: paneOpen,
    onDetail: value => {
      detailState.current = value;
      setPrs(current => current.map(pr => pr.id === value.id
        ? reconcilePullRequestDetail(pr, value, viewerLoginRef.current)
        : pr));
    },
    onStatus: status => {
      if (detailState.current !== null) {
        detailState.current = mergePullRequestDetailStatus(detailState.current, status);
      }
      const selected = selectedPullRequest.current;
      setPrs(current => current.map(pr => pr.id === selected?.id
        ? { ...pr, ciStatus: status.ciStatus, mergeable: status.mergeable, mergeableState: status.mergeableState }
        : pr));
    },
  });
  detailState.current = detail;
  const diffRevision = pullRequestDiffRevision(detail, selectedPr?.headPushedAt);
  const changes = usePullRequestChanges(selectedPr, {
    active: paneOpen && detailTab === 'changes' && detail !== null,
    revision: diffRevision,
  });
  const { files: diffs, commits: diffCommits, commitRange: diffRange, loading: diffLoading } = changes;
  const displayedDetailError = detailError ?? (detailTab === 'changes' ? changes.error : null) ?? error;

  const updateSelectedDetail = useCallback<PullDetailUpdater>((repo, number, update) => {
    updateDetail(repo, number, current => {
      const next = update(current);
      detailMutationVersion.current += 1;
      detailState.current = next;
      setPrs(rows => rows.map(pr => pr.id === next.id
        ? reconcilePullRequestDetail(pr, next, viewerLoginRef.current)
        : pr));
      return next;
    });
  }, [updateDetail]);

  useEffect(() => {
    manualDetailRefreshRequest.current += 1;
    setError(null);
  }, [selectedPrNumber, selectedPrRepo]);

  useEffect(() => {
    setPendingReviewComments([]);
  }, [diffRevision, selectedPrNumber, selectedPrRepo]);

  const scopedWorkspaceId = repositoryScope?.workspaceId;
  const requestedReviewId = selectedPrRepo?.toLowerCase() === initialPr?.repo.toLowerCase() && selectedPrNumber === initialPr?.number
    ? initialReviewId : undefined;
  const reviewKey = selectedPrRepo === undefined || selectedPrNumber === undefined
    ? null
    : `${scopedWorkspaceId ?? ''}:${selectedPrRepo.toLowerCase()}#${selectedPrNumber}:${requestedReviewId ?? ''}`;
  const currentReviewContext = reviewContext?.key === reviewKey ? reviewContext : null;
  // The selection can change without pick() (history, Recent, a route); an open Code & Tests follows it to the new PR.
  if (detailTab === 'code' && codeTabKey !== reviewKey) setCodeTabKey(reviewKey);
  const workspaceId = currentReviewContext?.workspaceId;
  const review = currentReviewContext?.review ?? null;
  const reviewLoading = reviewKey !== null && currentReviewContext === null;
  const reviewError = currentReviewContext?.error ?? null;
  const authored = viewerLogin ? (detail?.author ?? selectedPr?.author)?.toLowerCase() === viewerLogin.toLowerCase() : selectedPr?.origin === 'AUTHORED';
  if (authored && detailTab === 'review' && reviewWorkspace === undefined) setDetailTab('overview');
  const codeTaskKey = reviewKey === null || workspaceId === undefined ? null : `${reviewKey}@${workspaceId}`;
  const codeTask = codeTaskLookup !== null && codeTaskLookup.key === codeTaskKey ? codeTaskLookup.task : null;
  const codeTaskLoading = codeTaskKey !== null && codeTaskLookup?.key !== codeTaskKey;
  // The user's own pull request has Code & Tests only while a development task or a review holds a worktree for it.
  const codeTabShown = !authored || codeTask !== null || review !== null;
  if (!codeTabShown && detailTab === 'code' && currentReviewContext !== null && currentReviewContext.error === null && !codeTaskLoading) {
    setDetailTab('overview');
  }

  useEffect(() => {
    setReviewContext(null);
    setReviewNavigation(null);
    setFocusFindingId(null);
    setReviewCardFocus(null);
    if (reviewKey === null || selectedPrRepo === undefined || selectedPrNumber === undefined) return;
    let cancelled = false;
    void loadAgentReviewContext(window.bridge, selectedPrRepo, selectedPrNumber, scopedWorkspaceId, requestedReviewId,
      authored ? window.bridge.findPullRequestTask : undefined)
      .then(context => {
        if (!cancelled) setReviewContext({ ...context, key: reviewKey, error: null });
      })
      .catch(failure => {
        if (!cancelled) setReviewContext({ key: reviewKey, workspaceId: undefined, review: null, error: remoteErrorMessage(failure) });
      });
    return () => { cancelled = true; };
  }, [scopedWorkspaceId, selectedPrRepo, selectedPrNumber, reviewKey, reviewReload, requestedReviewId, authored]);

  // The worktree is what Code & Tests binds to, so a development task wins however the pull request was opened.
  useEffect(() => {
    if (codeTaskKey === null || workspaceId === undefined || selectedPrRepo === undefined || selectedPrNumber === undefined) return;
    let cancelled = false;
    void findCodeTask(window.bridge, workspaceId, selectedPrRepo, selectedPrNumber)
      // A failed lookup falls back to the review host; the task window still offers the task's own Code & Tests.
      .catch((): null => null)
      .then(task => {
        if (!cancelled) setCodeTaskLookup({ key: codeTaskKey, task });
      });
    return () => { cancelled = true; };
  }, [codeTaskKey, workspaceId, selectedPrRepo, selectedPrNumber, reviewReload]);

  const updateReview = useCallback((updated: AgentReviewDto) => {
    setReviewContext(current => current !== null && current.key === reviewKey
      && current.workspaceId === updated.workspaceId
      && updated.repo.toLowerCase() === selectedPrRepo?.toLowerCase() && updated.number === selectedPrNumber
      ? { ...current, review: updated }
      : current);
  }, [reviewKey, selectedPrRepo, selectedPrNumber]);

  const pick = (row: PullRow) => {
    if (!keepCodeDrafts()) return;
    onSelectPullRequest?.({ repo: row.repo, number: row.num }, 'push');
    setSelected(row.id);
    setDetailTab('overview');
    setPendingReviewComments([]);
    setReviewNavigation(null);
    setFocusFindingId(null);
    setReviewCardFocus(null);
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
    changes.changeCommitRange(range);
    setPendingReviewComments([]);
  };

  const refreshChanges = changes.refresh;
  const refresh = useCallback(async () => {
    if (!selectedPr || detailLoading) return;
    const selectedKey = `${selectedPr.repo.toLowerCase()}#${selectedPr.number}`;
    if (selectedPullRequest.current?.key !== selectedKey) return;
    const request = ++manualDetailRefreshRequest.current;
    const listVersion = listRequest.current;
    const requestMutationVersion = detailMutationVersion.current;
    await refreshDetail();
    if (request !== manualDetailRefreshRequest.current
        || selectedPullRequest.current?.key !== selectedKey) return;
    refreshChanges();
    setPendingReviewComments([]);
    if (singlePullRequest || listVersion !== listRequest.current) return;
    try {
      const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, 0, PAGE_SIZE, query);
      if (request === manualDetailRefreshRequest.current
          && selectedPullRequest.current?.key === selectedKey
          && listVersion === listRequest.current
          && requestMutationVersion === detailMutationVersion.current) applyPage(result);
    }
    catch (failure) {
      if (request === manualDetailRefreshRequest.current
          && selectedPullRequest.current?.key === selectedKey
          && listVersion === listRequest.current) setError(remoteErrorMessage(failure));
    }
  }, [applyPage, detailLoading, singlePullRequest, query, refreshChanges, refreshDetail, repositoryScope?.workspaceId, selectedPr]);

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
    <div className={`pulls-page${overlay ? ' pulls-page--overlay' : ''}${reviewWorkspace === undefined ? '' : ' pulls-page--review-workspace'}`}
      ref={overlayRef}
      role={overlay ? 'dialog' : undefined}
      aria-modal={overlay ? true : undefined}
      aria-label={overlay ? `Pull request ${initialPr?.repo} #${initialPr?.number}` : undefined}
      tabIndex={overlay ? -1 : undefined}
      onMouseDown={event => { if (overlay && event.target === event.currentTarget) onClose?.(); }}>
      {!singlePullRequest && <section className="pulls-list">
        <header className="pulls-toolbar" ref={toolbar}>
          <PullFilterTabs
            tab={tab}
            searching={searchTerms !== null}
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
            placeholder={compact ? '⌕  Search pull requests' : '⌕  Search by title, #number, or @author'}
            aria-label="Search pull requests by title, number, or author"
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

      {reviewWorkspace !== undefined && <section className="agr-workspace-conversation" aria-label="Review agent conversation">
        <header className="agt-screen-head">
          <button type="button" className="agt-back" aria-label="Back to tasks" onClick={reviewWorkspace.onBack}>‹ Tasks</button>
          <strong>{selectedPr?.title ?? 'Review & Learning'}</strong>
          <span className="agt-spacer" />{reviewWorkspace.headerAction}
        </header>
        {detail !== null && review !== null ? <AgentReviewTab detail={detail} workspaceId={workspaceId} viewer={viewer}
          review={review} onReviewChange={updateReview} mode="conversation" localReview={localReview}
          onOpenReview={target => {
            setReviewCardFocus(previous => ({ target, token: (previous?.token ?? 0) + 1 }));
            setFocusFindingId(null);
            setDetailTab('review');
            setPaneOpen(true);
          }}
          focusFindingId={null} onNavigateToChanges={target => { setReviewNavigation(target); setDetailTab('changes'); }} />
          : <div className="agt-panel__empty" role={reviewError === null ? 'status' : 'alert'}>
            {reviewError ?? 'Loading review conversation…'}
            {reviewError !== null && <button type="button" className="agt-action" onClick={() => setReviewReload(value => value + 1)}>Retry</button>}
          </div>}
      </section>}

      {reviewWorkspace !== undefined && <TaskPaneDivider pane={reviewPane} />}

      {singlePullRequest && selectedPr === null && (
        <section className={`pull-detail${overlay ? ' is-zoomed' : ''}${reviewWorkspace === undefined ? '' : ' agt-panel'}`}
          style={reviewWorkspace === undefined ? undefined : { width: reviewPane.width }}>
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
        <section className={`pull-detail${detailZoomed || overlay ? ' is-zoomed' : reviewWorkspace === undefined ? '' : ' agt-panel'}`}
          style={detailZoomed || overlay ? undefined : { width: reviewWorkspace === undefined ? detailPane.width : reviewPane.width }}>
          {reviewWorkspace === undefined && <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize pull request panes" onMouseDown={event => detailPane.startResize(event, event.currentTarget.parentElement?.getBoundingClientRect().width)} title="Drag to resize" />}
          <PullDetailHeader
            pr={selectedPr}
            detail={detail}
            tab={detailTab === 'code' ? null : detailTab}
            review={review}
            showReview={!authored}
            onReviewChange={updateReview}
            agentTab={codeTabShown || detailTab === 'code' ? (
              <button className={detailTab === 'code' ? 'is-active' : ''} type="button" aria-pressed={detailTab === 'code'}
                onClick={() => {
                  setCodeTabKey(reviewKey);
                  setDetailTab('code');
                }}>
                <Code size={14} strokeWidth={1.8} aria-hidden="true" /> Code &amp; Tests
              </button>
            ) : undefined}
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
            onClose={closePane}
          />
          {displayedDetailError && <div className="pull-detail__error">{displayedDetailError}</div>}
          {showBlockingDetailLoading(detailLoading, detail !== null) ? (
            <div className="pull-detail__loading">Loading details…</div>
          ) : detail === null ? (
            !displayedDetailError && <div className="pull-detail__loading">Select a pull request.</div>
          ) : (detailTab === 'review' || detailTab === 'code') && (reviewLoading || reviewError !== null) ? (
            <div className="pull-detail__body pull-detail__review">
              {reviewLoading ? <div className="hp-loading" role="status">Loading agent review…</div> : (
                <div className="agr-empty">
                  <div className="agr-error" role="alert">Could not load agent review. {reviewError}</div>
                  <button type="button" className="agr-action" onClick={() => setReviewReload(value => value + 1)}>Retry</button>
                </div>
              )}
            </div>
          ) : detailTab === 'code' && codeTaskLoading ? (
            // Waiting keeps Code & Tests from opening the review and then switching to the task's worktree.
            <div className="pull-detail__body pull-detail__review">
              <div className="hp-loading" role="status">Loading working copy…</div>
            </div>
          ) : (
            <>
            {localReview.error !== null && <div className="agr-error" role="alert">Local review comments: {localReview.error}</div>}
            {codeTabKey !== null && codeTabKey === reviewKey && (
              <div className="pull-detail__code" hidden={detailTab !== 'code'}>
                <CodeTestsTab
                  key={codeTabKey}
                  detail={detail}
                  workspaceId={workspaceId}
                  review={review}
                  task={codeTask}
                  onReviewChange={updateReview}
                  active={detailTab === 'code'}
                  ownReviewStream={reviewWorkspace === undefined}
                  onUnsavedChange={count => { codeUnsaved.current = count; }}
                  comments={{
                    repo: detail.repo,
                    number: detail.number,
                    workspaceId: repositoryScope?.workspaceId,
                    canReview: canSubmitReview(selectedPr, detail),
                    viewer,
                    pending: pendingReviewComments,
                    onPendingChange: setPendingReviewComments,
                    threads: {
                      activities: detail.recentActivity,
                      pr: selectedPr,
                      workspaceId: repositoryScope?.workspaceId,
                      unavailable: detail.unavailableSections.includes('reviewThreads'),
                      onRefresh: refresh,
                      onDetailUpdate: updateSelectedDetail,
                    },
                    onDetailChange: value => applyPostedDetail(value, 'review'),
                    localReview,
                    onAskAgent: codeTask !== null ? async comment => {
                      const current = await window.bridge.getAgentTask(codeTask.id, codeTask.workspaceId);
                      await localReview.create(comment, { taskId: current.id, workspaceId: current.workspaceId,
                        repo: detail.repo, number: detail.number, expectedTaskVersion: current.version });
                    } : review?.state !== 'OPEN' ? undefined : async comment => {
                      const current = await window.bridge.getAgentReview(review.id);
                      await localReview.create(comment, { taskId: current.task.id, workspaceId: current.workspaceId,
                        repo: current.repo, number: current.number, expectedTaskVersion: current.task.version });
                      updateReview(await window.bridge.getAgentReview(review.id));
                    },
                    // The user's own pull request hides Review & Learning outside a review workspace, so there is nowhere to open.
                    onOpenFindingInReview: authored && reviewWorkspace === undefined ? undefined : findingId => {
                      setFocusFindingId(findingId);
                      setReviewCardFocus(null);
                      setDetailTab('review');
                    },
                  }} />
              </div>
            )}
            {detailTab !== 'code' && <DetailBody
              localReview={localReview}
              ownReviewStream={reviewWorkspace === undefined}
              tab={detailTab}
              taskActions={reviewWorkspace === undefined ? <PullRequestTaskActions
                key={`${workspaceId ?? ''}:${detail.repo.toLowerCase()}#${detail.number}`}
                repo={detail.repo} number={detail.number}
                authored={viewerLogin ? detail.author?.toLowerCase() === viewerLogin.toLowerCase() : selectedPr.origin === 'AUTHORED'}
                workspaceId={workspaceId} loading={reviewLoading} contextError={reviewError} review={review}
                onRetryContext={() => setReviewReload(value => value + 1)}
                onNavigate={route => {
                  if (!keepCodeDrafts()) return;
                  onClose?.();
                  window.location.hash = route;
                }} /> : undefined}
              pr={selectedPr}
              detail={detail}
              workspaceId={workspaceId}
              viewer={viewer}
              review={review}
              reviewNavigation={reviewNavigation}
              focusFindingId={focusFindingId}
              reviewCardFocus={reviewCardFocus}
              onReviewChange={updateReview}
              onOpenWorkspace={review === null || reviewWorkspace !== undefined ? undefined : () => {
                onClose?.();
                window.location.hash = workspaceReviewTaskRoute(review.workspaceId, review.id);
              }}
              onAskAgent={review?.state !== 'OPEN' ? undefined : async comment => {
                const current = await window.bridge.getAgentReview(review.id);
                await localReview.create(comment, { taskId: current.task.id, workspaceId: current.workspaceId,
                  repo: current.repo, number: current.number, expectedTaskVersion: current.task.version });
                updateReview(await window.bridge.getAgentReview(review.id));
              }}
              onNavigateToChanges={target => {
                setReviewNavigation(target);
                setFocusFindingId(null);
                setReviewCardFocus(null);
                if (detailTab !== 'changes') {
                  recordDiffOpened();
                  setDetailTab('changes');
                }
              }}
              onOpenInReview={findingId => {
                setFocusFindingId(findingId);
                setReviewCardFocus(null);
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
            />}
            </>
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
  onReviewChange,
  agentTab,
  showReview = pr.origin !== 'AUTHORED',
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
  tab: HeaderTab | null;
  /** The open Agent review, when the pull request has one; its status rides on the tab. */
  review?: AgentReviewDto | null;
  onReviewChange?: (review: AgentReviewDto) => void;
  agentTab?: ReactNode;
  showReview?: boolean;
  onTab: (tab: HeaderTab) => void;
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
          {showReview && <button
            className={tab === 'review' ? 'is-active' : ''}
            type="button"
            aria-pressed={tab === 'review'}
            onClick={() => onTab('review')}>
            <CommentIcon /> Review &amp; Learning
            {reviewChip?.chip != null && (
              <span className={`pull-detail__review-chip is-${reviewChip.tone}`}>
                {reviewChip.busy && <span className="agr-spinner" aria-hidden="true" />}
                {reviewChip.chip}
              </span>
            )}
          </button>}
          {agentTab}
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
        <ReviewDialog pr={pr} detail={detail} review={review} onReviewChange={onReviewChange}
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


export function ReviewDialog({
  pr,
  detail,
  review = null,
  onReviewChange = () => undefined,
  pendingComments = [],
  onPendingCommentsChange = () => undefined,
  onReviewSubmitted,
  onClose,
}: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  /** Findings the user added to their review wait in its draft and go out with this review. */
  review?: AgentReviewDto | null;
  onReviewChange?: (review: AgentReviewDto) => void;
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
  const promoted = review?.draft?.items.filter(item => item.included && !item.published) ?? [];
  const bodyRequired = event !== 'APPROVE' && pendingComments.length === 0 && promoted.length === 0;
  const failed = (value: unknown) => setError(value instanceof Error ? value.message : String(value));

  // A draft write can leave the draft changed even when it fails, so the sheet re-reads it.
  const reloadReview = () => {
    if (review !== null) void window.bridge.getAgentReview(review.id).then(onReviewChange, () => {});
  };

  const removePromoted = async (itemId: string) => {
    if (!review?.draft) return;
    setError(null);
    try {
      await window.bridge.removeReviewDraftComment(detail.repo, detail.number, review.draft.version, itemId);
    }
    catch (value) {
      failed(value);
    }
    reloadReview();
  };

  const outgoing = [
    ...pendingComments.map((comment, index) => ({
      key: `${comment.path}:${comment.side}:${comment.startLine ?? comment.line}:${comment.line}:${index}`,
      comment,
      remove: () => onPendingCommentsChange(pendingComments.filter((_, commentIndex) => commentIndex !== index)),
    })),
    ...promoted.map(item => ({ key: item.id, comment: item, remove: () => { void removePromoted(item.id); } })),
  ];
  const viewer = useViewer(outgoing.length > 0);
  const [snapshot, setSnapshot] = useState<{ files: DiffFileDto[]; headSha: string | null } | null>(null);
  const hasOutgoing = outgoing.length > 0;

  useEffect(() => {
    if (!hasOutgoing) return;
    let live = true;
    Promise.all([window.bridge.fetchPrDiffFiles(detail.repo, detail.number), window.bridge.fetchPrDiffCommits(detail.repo, detail.number)])
      .then(([files, commits]) => { if (live) setSnapshot({ files, headSha: commits.at(-1)?.sha ?? null }); })
      .catch(() => { /* the cards just omit the commented line */ });
    return () => { live = false; };
  }, [detail.repo, detail.number, hasOutgoing]);

  // Line numbers of a comment written on selected commits belong to that range, not to the stored diff.
  const codeLine = (comment: InlineReviewCommentDto | ReviewDraftItemDto) => {
    if (snapshot === null || ('commitId' in comment && comment.commitId !== null && comment.commitId !== snapshot.headSha)) return null;
    const file = snapshot.files.find(value => value.filename === comment.path);
    return file ? patchCommentLine(file.patch, comment.line, comment.side) : null;
  };

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
      if (review?.draft && promoted.length > 0) {
        // Added findings can only leave through the draft, which reserves each one so Post comment
        // now cannot send it again. Written comments join the draft to share the review, but the
        // draft sends everything against the current head, where a comment written on selected
        // commits would land on the wrong lines.
        if (pendingComments.some(comment => comment.commitId !== null && comment.commitId !== review.observedHeadSha)) {
          throw new Error('Comments written on selected commits cannot be sent with agent findings. Remove them or write them on all changes.');
        }
        let version = review.draft.version;
        for (const [index, comment] of pendingComments.entries()) {
          version = (await window.bridge.addReviewDraftComment(detail.repo, detail.number, version, {
            path: comment.path, side: comment.side, line: comment.line, startLine: comment.startLine, body: comment.body,
          })).version;
          onPendingCommentsChange(pendingComments.slice(index + 1));
        }
        await window.bridge.submitReviewDraft(detail.repo, detail.number, version, body.trim(), event);
        reloadReview();
        // The draft answers with itself alone; the stored detail already carries the new review.
        void window.bridge.fetchPullRequestDetailByRef(detail.repo, detail.number)
          .then(reviewed => onReviewSubmitted(reviewed, event), () => {});
      }
      else {
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
      }
      onClose();
    }
    catch (value) {
      failed(value);
      setSubmitting(false);
      if (promoted.length > 0) reloadReview();
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
        {outgoing.length > 0 && (
          <div className="pr-review-sheet__pending">
            {outgoing.map(({ key, comment, remove }) => {
              const line = codeLine(comment);
              return <ReviewThreadCard key={key} resolved={false} controls={null}
                location={<code title={comment.path}>{comment.path}</code>}
                code={line && <CommentedCodeLine line={line} />}>
                <ReviewThreadMessage timestamp={null}
                  author={<>{viewer && <UserAvatar login={viewer.login} avatarUrl={viewer.avatarUrl} size={22} showLogin />}
                    <span className="pull-diff-pending__badge">Pending</span></>}
                  menu={<button className="pr-review-sheet__remove" type="button" disabled={submitting} onClick={remove}>Remove</button>}>
                  <MarkdownBody body={comment.body} repository={detail.repo} />
                </ReviewThreadMessage>
              </ReviewThreadCard>;
            })}
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
  tab, localReview, ownReviewStream, taskActions,
  pr,
  detail,
  workspaceId,
  viewer,
  review,
  reviewNavigation,
  focusFindingId, reviewCardFocus,
  onReviewChange,
  onOpenWorkspace,
  onAskAgent,
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
  localReview?: LocalReviewThreads;
  /** False in the review workspace, whose conversation pane already streams this review. */
  ownReviewStream: boolean;
  taskActions?: ReactNode;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  workspaceId: string | undefined;
  viewer: UserProfileDto | null;
  review: AgentReviewDto | null;
  reviewNavigation: ReviewNavigationTarget | null;
  focusFindingId: string | null;
  reviewCardFocus?: ReviewCardFocus | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onOpenWorkspace?: () => void;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
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
      ownStream={ownReviewStream}
      localReview={localReview}
      onOpenWorkspace={onOpenWorkspace}
      onNavigateToChanges={onNavigateToChanges}
      focusFindingId={focusFindingId}
      focusReviewCard={reviewCardFocus}
    /></div>;
  }
  if (tab === 'changes') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return <div className="pull-detail__body pull-detail__changes"><PullChanges
      localReview={localReview}
      onAskAgent={onAskAgent}
      onAskAgentDisabled={review?.task.mutationClaim === 'LOCAL_RUN' ? 'A run is using this working copy' : null}
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
    taskActions={taskActions}
    localReview={localReview}
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
