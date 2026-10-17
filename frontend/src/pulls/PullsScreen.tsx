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
import { shortSha } from '../displayText';
import type { LucideIcon } from 'lucide-react';
import { Bell, Bot, Check, CircleSlash, Code, Diff, Ellipsis, Eye, GitPullRequest, Maximize, MessageCircle, PanelRight, RefreshCw, UserRound, X } from 'lucide-react';
import { useCallback, useEffect, useId, useMemo, useRef, useState, type ComponentProps, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { findConversationReview } from '../agents/agentConversations';
import { useTaskCardState } from '../agents/attention';
import DevelopmentLearningPanel from '../agents/DevelopmentLearningPanel';
import type { ReviewCardFocus } from '../agents/ReviewRequestCard';
import TaskPaneDivider, { useTaskPane } from '../agents/TaskPaneDivider';
import { ConfirmDialog } from '../ConfirmDialog';
import '../css/pulls.css';
import '../css/review-tasks.css';
import { isFindShortcut } from '../FindBar';
import { pullRequestItem } from '../home/attention';
import PinButton from '../home/PinButton';
import { completeAttentionVisit, recordAttentionAction, useAttentionVisit } from '../home/useAttentionVisit';
import type { NavigationMode } from '../navigationHistory';
import { useRecentTouch } from '../recent/useRecentTouch';
import Toast from '../Toast';
import type { AgentTaskDto, Bridge, DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto, PullRequestPageDto, PullRequestTaskLink, UserProfileDto } from '../types';
import { usePopoverDismiss } from '../usePopoverDismiss';
import UserAvatar from '../UserAvatar';
import { useResizablePane } from '../useResizablePane';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { useZoom, ZoomActions, ZoomScrim, type ZoomControls } from '../Zoom';
import AgentReviewTab, { type ReviewNavigationTarget } from './agent-review/AgentReviewTab';
import type { AgentReviewDto, ReviewDraftDto, ReviewDraftItemDto, ReviewEvent } from './agent-review/contracts';
import { draftCommentCommit, mergeReviewFindings, pendingDraftCount, reviewStatus } from './agent-review/model';
import BetterWordsButton from './BetterWordsButton';
import CodeTestsTab from './code-tests/CodeTestsTab';
import DetailSkeleton from './DetailSkeleton';
import type { LocalReviewThreads } from './local-review/types';
import { useLocalReviewThreads } from './local-review/useLocalReviewThreads';
import MarkdownBody from './MarkdownBody';
import MarkdownEditor from './MarkdownEditor';
import {
  canSubmitReview,
  exactPullNumber,
  keepSelectedPullRequest,
  parsePullSearch,
  PULL_TABS,
  reconcilePostedComment,
  reconcilePullRequestDetail,
  reconcileSubmittedReview,
  rowsForPullSearch,
  rowsForTab,
  tabForInitialPullRequest,
  visibleSelectedId,
  type PullRow,
  type PullTab,
} from './model';
import { usePrivateThreads, type PrivateThreadSource } from './PrivateThread';
import PullChanges, { pullReviewThreads, updatePendingReviewComment, type CommitRange, type DiffAgentComment } from './PullChanges';
import PullOverview, { CommentedCodeLine, commentedCodeLines, isQueued, mergePullRequestWriteDetail, useViewer, type PullDetailUpdater } from './PullOverview';
import { remoteErrorMessage } from '../remoteError';
import PullRequestConflictFiles from './PullRequestConflictFiles';
import MergeQueueRemoval from './MergeQueueRemoval';
import { PullRequestStateIcon } from './PullRequestStateIcon';
import PullRequestTaskActions from './PullRequestTaskActions';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import { PendingReviewComments } from './ReviewDrafts';
import { ReviewThreadCard, ReviewThreadMessage } from './ReviewThreadCard';
import { usePrCommentCommand } from './usePrCommentCommand';
import { usePullCommentThreads } from './usePullCommentThreads';
import { pullRequestDiffRevision, usePullRequestChanges } from './usePullRequestChanges';
import { mergePullRequestDetailStatus, usePullRequestDetail } from './usePullRequestDetail';

/** The tabs PullDetailHeader draws itself; hosts add their own through its `agentTab` slot. */
export type HeaderTab = 'overview' | 'files' | 'changes' | 'review' | 'code';

/**
 * The panel tab an agent switch takes to the other agent's screen. Developer and Reviewer change the
 * conversation, so the screen they open starts on the tab the user was on, not on its own default.
 */
let carriedTab: { route: string; tab: HeaderTab } | null = null;
// Leaving the screen it was carried to drops it, so opening that screen another way gets the default again.
if (typeof window !== 'undefined') {
  window.addEventListener('hashchange', () => { if (carriedTab?.route !== window.location.hash) carriedTab = null; });
}

/** The tab a switch carried to the screen now opening; null when the user came another way. */
export function carriedPanelTab(): HeaderTab | null {
  return carriedTab !== null && carriedTab.route === window.location.hash ? carriedTab.tab : null;
}
type DetailTab = HeaderTab;

type AgentReviewContext = {
  workspaceId: string | undefined;
  review: AgentReviewDto | null;
  developerTask: PullRequestTaskLink | null;
};

export async function loadAgentReviewContext(
  bridge: Pick<Bridge, 'listWorkspaces' | 'findAgentReview' | 'getAgentReview' | 'findPullRequestTask' | 'findTaskAgentReview'>,
  repo: string,
  number: number,
  workspaceId?: string,
  reviewId?: string,
  findTask?: Bridge['findPullRequestTask'],
): Promise<AgentReviewContext> {
  const context = async (workspaceId: string, knownTask?: PullRequestTaskLink | null): Promise<AgentReviewContext> => {
    const developerTask = knownTask === undefined ? await bridge.findPullRequestTask(workspaceId, repo, number) : knownTask;
    return { workspaceId, developerTask, review: await findConversationReview(bridge, workspaceId, developerTask?.taskId, { repo, number }) };
  };
  if (reviewId !== undefined) {
    const review = await bridge.getAgentReview(reviewId);
    if (review.id !== reviewId || review.repo.toLowerCase() !== repo.toLowerCase() || review.number !== number
      || (workspaceId !== undefined && review.workspaceId !== workspaceId)) {
      throw new Error('This review does not belong to the selected pull request and workspace.');
    }
    return { workspaceId: review.workspaceId, review, developerTask: review.sourceTaskId == null
      ? await bridge.findPullRequestTask(review.workspaceId, repo, number)
      : { workspaceId: review.workspaceId, taskId: review.sourceTaskId } };
  }
  if (workspaceId !== undefined) {
    return context(workspaceId);
  }
  const workspaces = (await bridge.listWorkspaces()).filter(workspace =>
    `${workspace.monitoredOwner}/${workspace.monitoredRepo}`.toLowerCase() === repo.toLowerCase());
  if (findTask !== undefined) {
    for (const workspace of workspaces) {
      const task = await findTask(workspace.id, repo, number);
      if (task !== null) return context(workspace.id, task);
    }
  }
  // Several fork workspaces can watch the same upstream. Reuse a review before offering a new one.
  let first: AgentReviewContext = { workspaceId: undefined, review: null, developerTask: null };
  for (const workspace of workspaces) {
    const value = await context(workspace.id);
    if (value.review !== null) return value;
    if (first.workspaceId === undefined || (first.developerTask === null && value.developerTask !== null)) first = value;
  }
  return first;
}

/**
 * The development task whose worktree Tears of time reads instead of any review: the interactive task that owns the pull
 * request and still has its worktree. Both reads are local.
 */
export async function findCodeTask(bridge: Pick<Bridge, 'findPullRequestTask' | 'getAgentTask'>, workspaceId: string, repo: string, number: number) {
  const link = await bridge.findPullRequestTask(workspaceId, repo, number);
  if (link === null) return null;
  const task = await bridge.getAgentTask(link.taskId, link.workspaceId);
  return task.checkoutRemovedAt === null ? task : null;
}

const DETAIL_MIN = 680;
const DETAIL_MAX = 1600;
const DETAIL_DEFAULT = 1120;
const PAGE_SIZE = 50;
export { mergePullRequestDetailStatus,pullRequestDetailRefreshDelay } from './usePullRequestDetail';

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
  const visible = visibleTabs(tab, width);
  const shown = PULL_TABS.filter(item => visible.includes(item.key));
  const hidden = PULL_TABS.filter(item => !visible.includes(item.key));

  const filters = usePopoverDismiss(() => setMenuOpen(false), false, menuOpen);

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
  const scopedWorkspaceId = repositoryScope?.workspaceId;
  const scopedRepository = repositoryScope?.repository;
  const overlay = onClose !== undefined;
  const reviewPane = useTaskPane();
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [viewerLogin, setViewerLogin] = useState<string | null>();
  const [viewer, setViewer] = useState<UserProfileDto | null>(null);
  /** The open review of the selected pull request, loaded once per selection and kept live by its tab. */
  const [reviewContext, setReviewContext] = useState<(AgentReviewContext & { key: string; error: string | null }) | null>(null);
  // Looked up apart from the review, so a slow or failed task lookup never holds up Summary.
  const [codeTaskLookup, setCodeTaskLookup] = useState<{ key: string; task: AgentTaskDto | null } | null>(null);
  const [reviewReload, setReviewReload] = useState(0);
  /** Where Changes should scroll, set when a finding asks to be shown at its diff location. */
  const [reviewNavigation, setReviewNavigation] = useState<ReviewNavigationTarget | null>(null);
  const [changesToolbar, setChangesToolbar] = useState<HTMLElement | null>(null);
  const [summaryToolbar, setSummaryToolbar] = useState<HTMLElement | null>(null);
  const selectCommit = (commitSha: string): void => {
    setReviewNavigation(null);
    changes.selectCommit(commitSha);
    setDetailTab('files');
  };
  const navigateToChanges = (target: ReviewNavigationTarget): void => {
    changes.changeCommitRange(null);
    setReviewNavigation(target);
    setFocusFindingId(null);
    setReviewCardFocus(null);
    setDetailTab('files');
  };
  /** A finding the Changes tab sent back to Agent review, so its card opens and scrolls. */
  const [focusFindingId, setFocusFindingId] = useState<string | null>(null);
  const [reviewCardFocus, setReviewCardFocus] = useState<ReviewCardFocus | null>(null);
  const [tab, setTab] = useState<PullTab>(PULL_TABS[0].key);
  const [selected, setSelected] = useState<number | null>(null);
  // A review opens on Changes, or on the tab an agent switch carried here. Its Changes is the pull request's.
  const [initialDetailTab] = useState<DetailTab>(() => {
    if (initialReviewId === undefined) return 'overview';
    const carried = carriedPanelTab();
    return carried === null || carried === 'changes' ? 'files' : carried;
  });
  const [detailTab, setDetailTab] = useState<DetailTab | 'agents' | 'discussions'>(initialDetailTab);
  const coveredAgentTab = useRef<DetailTab>(initialDetailTab);
  if (detailTab !== 'agents' && detailTab !== 'discussions') coveredAgentTab.current = detailTab;
  const [agentTabTarget, setAgentTabTarget] = useState<HTMLSpanElement | null>(null);
  const [agentBodyTarget, setAgentBodyTarget] = useState<HTMLDivElement | null>(null);
  // Tears of time stays mounted after its first visit so drafts, open files and runs survive tab switches.
  const [codeTabKey, setCodeTabKey] = useState<string | null>(null);
  const [summaryTabKey, setSummaryTabKey] = useState<string | null>(null);
  // Unsaved Tears of time drafts live only in that tab; leaving the PR or closing the pane asks first.
  const codeUnsaved = useRef(0);
  const [discardDrafts, setDiscardDrafts] = useState<{ files: number; act: () => void } | null>(null);
  const withCodeDrafts = useCallback((act: () => void) => {
    if (codeUnsaved.current === 0) act();
    else setDiscardDrafts({ files: codeUnsaved.current, act });
  }, []);
  const closePane = useMemo(() => onClose === undefined ? undefined : () => withCodeDrafts(() => {
    const pr = selectedPullRequest.current?.pr;
    if (pr !== undefined) void completeAttentionVisit(`${pr.repo}#${pr.number}`);
    onClose();
  }), [withCodeDrafts, onClose]);
  const navigate = (route: string): void => withCodeDrafts(() => {
    onClose?.();
    window.location.hash = route;
  });
  const [pendingReviewComments, setPendingReviewComments] = useState<InlineReviewCommentDto[]>([]);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  // One layer for both hosts: the list zooms its own pane, a preview opens already zoomed.
  const zoomLayer = detailZoomed || overlay;
  const leaveZoom = useCallback(() => {
    if (closePane === undefined) {
      const pr = selectedPullRequest.current?.pr;
      if (pr !== undefined) void completeAttentionVisit(`${pr.repo}#${pr.number}`);
      setDetailZoomed(false);
    }
    else closePane();
  }, [closePane]);
  const zoom = useZoom(leaveZoom, zoomLayer);
  const zoomClass = zoomLayer ? ` is-zoomed ${zoom.cardClass}` : '';
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
  /** Ordinary searches filter loaded pages; a complete #number can look beyond them. */
  const searchTerms = useMemo(() => parsePullSearch(query), [query]);
  const exactNumber = exactPullNumber(query);
  const [numberLookup, setNumberLookup] = useState<{ repo: string; number: number; loading: boolean; error: string | null } | null>(null);
  const hasExactNumber = prs.some(pr => pr.number === exactNumber && pr.repo.toLowerCase() === scopedRepository?.toLowerCase());
  const currentNumberLookup = numberLookup?.repo === scopedRepository && numberLookup?.number === exactNumber && !hasExactNumber
    ? numberLookup : null;
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
  const listContext = useRef({ workspaceId: scopedWorkspaceId, page });
  const browserWasOpen = useRef(browserOpen);
  const monitorRequests = useRef(new Set<number>());
  viewerLoginRef.current = viewerLogin;
  listContext.current = { workspaceId: scopedWorkspaceId, page };

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
              updatedAt: selectedDetail.updatedAt,
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

  const readList = useCallback(async (throughPage?: number): Promise<PullRequestPageDto> => {
    const { workspaceId, page } = listContext.current;
    // Replace the loaded range together so refreshes never remove and reload the lower rows.
    const pages = await Promise.all(Array.from({ length: (throughPage ?? page) + 1 }, (_, index) => window.bridge.fetchPrs(workspaceId, index, PAGE_SIZE)));
    return { ...pages[pages.length - 1], pullRequests: [...new Map(pages.flatMap(result => result.pullRequests).map(pr => [pr.id, pr])).values()] };
  }, []);

  const loadList = useCallback(async (throughPage?: number) => {
    const request = ++listRequest.current;
    try {
      const result = await readList(throughPage);
      if (request === listRequest.current) {
        applyPage(result);
        setError(null);
      }
    }
    catch (failure) {
      if (request === listRequest.current) setError(remoteErrorMessage(failure));
    }
    finally {
      // Background updates can supersede the initial read and must finish its loading state too.
      if (request === listRequest.current) setLoading(false);
    }
  }, [applyPage, readList]);

  useEffect(() => {
    if (singlePullRequest) return;
    return window.bridge.onPullRequestsSynced(() => { void loadList(); });
  }, [loadList, singlePullRequest]);

  useEffect(() => {
    let knownIds: Set<number> | null = null;
    const updateAttention = (event: Event) => {
      const rows = (event as CustomEvent<PullRequestDto[]>).detail.filter(pr => scopedRepository === undefined
        || pr.repo.toLowerCase() === scopedRepository.toLowerCase());
      const updates = new Map(rows.map(pr => [pr.id, pr]));
      const discovered = knownIds === null || rows.some(pr => !knownIds?.has(pr.id));
      knownIds = new Set(updates.keys());
      setPrs(current => current.map(pr => updates.get(pr.id) ?? pr));
      if (singlePullRequest || !discovered) return;
      void loadList();
    };
    window.addEventListener('bytequay:pr-attention-updated', updateAttention);
    return () => window.removeEventListener('bytequay:pr-attention-updated', updateAttention);
  }, [loadList, scopedRepository, scopedWorkspaceId, singlePullRequest]);

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
      const result = await readList();
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
  }, [applyPage, readList, repositoryScope?.workspaceId]);

  useEffect(() => {
    if (singlePullRequest) return;
    setLoading(true);
    setError(null);
    setSyncError(null);
    setPrs([]);
    // Another workspace lists different pull requests; don't let applyPage keep the old one before the next render.
    selectedPullRequest.current = null;
    setHasMore(false);
    setPage(0);
    void loadList(0);
  }, [loadList, singlePullRequest, repositoryScope?.workspaceId]);

  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(search.trim()), 250);
    return () => window.clearTimeout(timeout);
  }, [search]);

  useEffect(() => {
    if (singlePullRequest || loading || error !== null || scopedRepository === undefined || exactNumber === null
      || search.trim() !== query || hasExactNumber) return;
    let cancelled = false;
    setNumberLookup({ repo: scopedRepository, number: exactNumber, loading: true, error: null });
    void window.bridge.lookupPullRequest(scopedRepository, exactNumber)
      .then(pr => {
        if (cancelled) return;
        setPrs(current => current.some(row => row.id === pr.id) ? current : [...current, pr]);
        setNumberLookup(null);
      })
      .catch(failure => {
        if (!cancelled) setNumberLookup({ repo: scopedRepository, number: exactNumber, loading: false, error: remoteErrorMessage(failure) });
      });
    return () => { cancelled = true; };
  }, [singlePullRequest, loading, error, scopedRepository, exactNumber, search, query, hasExactNumber]);

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
      const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, page + 1, PAGE_SIZE);
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
  }, [hasMore, page, repositoryScope?.workspaceId]);

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

  // Exact numbers cross tabs so a closed or unrelated PR is still reachable.
  // Text and author searches keep filtering the loaded rows within the selected tab.
  const rows = useMemo(
    () => rowsForPullSearch(prs, tab, query, viewerLogin),
    [prs, tab, query, viewerLogin],
  );
  const compact = compactToolbar(toolbarWidth);
  // ponytail: one pass per tab over the loaded page; revisit only if the list
  // ever holds thousands of rows. Unloaded pages make each count a floor. The
  // counts stay unfiltered, because rows are filtered below by a page-scoped search.
  const tabCounts = useMemo(
    () => PULL_TABS.map(item => `${rowsForTab(prs, item.key, viewerLogin).length}${hasMore ? '+' : ''}`),
    [hasMore, prs, viewerLogin],
  );
  // A route or a notification names a pull request the loaded page may not hold, and resolving
  // it costs a round trip. Standing the first row in meanwhile opens the wrong pull request.
  const awaitingInitialPr = initialPr !== undefined && selected === null;
  const visibleSelected = singlePullRequest || awaitingInitialPr ? selected : visibleSelectedId(rows, selected);
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
    active: paneOpen && detailTab === 'files' && detail !== null,
    revision: diffRevision,
  });
  const { files: diffs, commits: diffCommits, commitRange: diffRange, loading: diffLoading } = changes;
  const displayedDetailError = detailError ?? (detailTab === 'files' ? changes.error : null) ?? error;

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

  // A staged review is the reader's unsent writing, so it lasts as long as the pull request is
  // open here. Nothing the pull request itself does — a new head, a refresh, a narrowed commit
  // range — may throw it away; a comment written against an older head carries that head's SHA and
  // is refused at submission instead.
  useEffect(() => {
    setPendingReviewComments([]);
  }, [selectedPrNumber, selectedPrRepo]);

  const requestedReviewId = selectedPrRepo?.toLowerCase() === initialPr?.repo.toLowerCase() && selectedPrNumber === initialPr?.number
    ? initialReviewId : undefined;
  const reviewKey = selectedPrRepo === undefined || selectedPrNumber === undefined
    ? null
    : `${scopedWorkspaceId ?? ''}:${selectedPrRepo.toLowerCase()}#${selectedPrNumber}:${requestedReviewId ?? ''}`;
  const currentReviewContext = reviewContext?.key === reviewKey ? reviewContext : null;
  // The selection can change without pick() (history, Recent, a route); an open Tears of time follows it to the new PR.
  if (detailTab === 'code' && codeTabKey !== reviewKey) setCodeTabKey(reviewKey);
  if (detailTab === 'review' && summaryTabKey !== reviewKey) setSummaryTabKey(reviewKey);
  const workspaceId = currentReviewContext?.workspaceId;
  const review = currentReviewContext?.review ?? null;
  const reviewLoading = reviewKey !== null && currentReviewContext === null;
  const reviewError = currentReviewContext?.error ?? null;
  const authored = viewerLogin ? (detail?.author ?? selectedPr?.author)?.toLowerCase() === viewerLogin.toLowerCase() : selectedPr?.origin === 'AUTHORED';
  const codeTaskKey = reviewKey === null || workspaceId === undefined ? null : `${reviewKey}@${workspaceId}`;
  const codeTask = reviewWorkspace === undefined && codeTaskLookup !== null && codeTaskLookup.key === codeTaskKey ? codeTaskLookup.task : null;
  const codeTaskLoading = reviewWorkspace === undefined && codeTaskKey !== null && codeTaskLookup?.key !== codeTaskKey;
  // A watched repository can prepare a working copy before any agent review exists.
  const codeTabShown = workspaceId !== undefined;

  useEffect(() => {
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
        if (!cancelled) setReviewContext(current => current?.key === reviewKey
          ? { ...current, error: remoteErrorMessage(failure) }
          : { key: reviewKey, workspaceId: undefined, review: null, developerTask: null, error: remoteErrorMessage(failure) });
      });
    return () => { cancelled = true; };
  }, [scopedWorkspaceId, selectedPrRepo, selectedPrNumber, reviewKey, reviewReload, requestedReviewId, authored]);

  // The worktree is what Tears of time binds to, so a development task wins however the pull request was opened.
  useEffect(() => {
    if (codeTaskKey === null || workspaceId === undefined || selectedPrRepo === undefined || selectedPrNumber === undefined) return;
    let cancelled = false;
    void findCodeTask(window.bridge, workspaceId, selectedPrRepo, selectedPrNumber)
      // A failed lookup falls back to the review host; the task window still offers the task's own Tears of time.
      .catch((): null => null)
      .then(task => {
        if (!cancelled) setCodeTaskLookup({ key: codeTaskKey, task });
      });
    return () => { cancelled = true; };
  }, [codeTaskKey, workspaceId, selectedPrRepo, selectedPrNumber, reviewReload]);

  const updateReview = useCallback((updated: AgentReviewDto) => {
    setReviewContext(current => current !== null && current.key === reviewKey
      && current.workspaceId === updated.workspaceId
      && updated.repo.toLowerCase() === selectedPrRepo?.toLowerCase()
      && (updated.number === selectedPrNumber || (codeTask !== null && updated.sourceTaskId === codeTask.id))
      ? { ...current, review: mergeReviewFindings(current.review, updated) }
      : current);
  }, [reviewKey, selectedPrRepo, selectedPrNumber, codeTask]);

  // Summary and Tears of time each follow the review while shown. PR and Changes draw its findings and their
  // threads too; main keeps one reader per review, so this one runs only while neither of those tabs is shown.
  const followedReviewId = reviewWorkspace === undefined && review?.state === 'OPEN' && (detailTab === 'overview' || detailTab === 'files') ? review.id : null;
  useEffect(() => {
    if (followedReviewId === null) return;
    let live = true;
    let revision = 0;
    let settling: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      const request = ++revision;
      const updated = await window.bridge.getAgentReview(followedReviewId);
      if (!live || request !== revision) return;
      updateReview(updated);
      // The review observer settles after the task event. One follow-up covers a read that reached it first.
      if (updated.activeTurn === null && updated.task.mutationClaim === null && updated.runs.at(-1)?.state === 'RUNNING') {
        clearTimeout(settling); settling = setTimeout(() => { void read().catch(() => {}); }, 200);
      }
    };
    const stream = Promise.resolve().then(() => window.bridge.subscribeAgentReview(followedReviewId, 0, message => {
      if (message.kind === 'changed' || message.kind === 'events' || message.state === 'open') void read().catch(() => {});
    }, true));
    // A background follow stays silent; Refresh still reports.
    stream.catch(() => {});
    return () => { live = false; clearTimeout(settling); void stream.then(stop => stop(), () => {}); };
  }, [followedReviewId, updateReview]);

  const privateThreads = usePullCommentThreads({
    pr: selectedPr, workspaceId, review, task: codeTask, loading: reviewLoading || codeTaskLoading, error: reviewError,
    active: detailTab === 'overview', onReviewChange: updateReview, onReload: () => setReviewReload(value => value + 1),
  });

  const pick = (row: PullRow) => withCodeDrafts(() => {
    onSelectPullRequest?.({ repo: row.repo, number: row.num }, 'push');
    setSelected(row.id);
    setDetailTab('overview');
    setPendingReviewComments([]);
    setReviewNavigation(null);
    setFocusFindingId(null);
    setReviewCardFocus(null);
  });

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

  const refreshChanges = changes.refresh;
  const refresh = useCallback(async () => {
    if (!selectedPr || detailLoading) return;
    const selectedKey = `${selectedPr.repo.toLowerCase()}#${selectedPr.number}`;
    if (selectedPullRequest.current?.key !== selectedKey) return;
    const request = ++manualDetailRefreshRequest.current;
    let listVersion = listRequest.current;
    const requestMutationVersion = detailMutationVersion.current;
    await refreshDetail();
    if (request !== manualDetailRefreshRequest.current
        || selectedPullRequest.current?.key !== selectedKey) return;
    refreshChanges();
    if (singlePullRequest || listVersion !== listRequest.current) return;
    try {
      listVersion = ++listRequest.current;
      const result = await readList();
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
  }, [applyPage, readList, detailLoading, singlePullRequest, refreshChanges, refreshDetail, selectedPr]);

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

  const paneShown = paneOpen && (selectedPr !== null || awaitingInitialPr);
  const askAgent = detail === null ? undefined : codeTask !== null ? async (comment: DiffAgentComment) => {
    const current = await window.bridge.getAgentTask(codeTask.id, codeTask.workspaceId);
    await localReview.create(comment, { taskId: current.id, workspaceId: current.workspaceId,
      repo: detail.repo, number: detail.number, expectedTaskVersion: current.version });
  } : review?.state !== 'OPEN' || review.number === null ? undefined : async (comment: DiffAgentComment) => {
    const current = await window.bridge.getAgentReview(review.id);
    await localReview.create(comment, { taskId: current.task.id, workspaceId: current.workspaceId,
      repo: detail.repo, number: detail.number, expectedTaskVersion: current.task.version });
    updateReview(await window.bridge.getAgentReview(review.id));
  };

  return (
    <PendingReviewComments value={{ comments: pendingReviewComments, onChange: setPendingReviewComments }}>
    <div className={`pulls-page${overlay ? ' pulls-page--overlay' : ''}${reviewWorkspace === undefined ? '' : ' pulls-page--review-workspace'}`}
      ref={overlayRef}
      role={overlay ? 'dialog' : undefined}
      aria-modal={overlay ? true : undefined}
      aria-label={overlay ? `Pull request ${initialPr?.repo} #${initialPr?.number}` : undefined}
      tabIndex={overlay ? -1 : undefined}>
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
          {!loading && currentNumberLookup?.loading && <div className="hp-loading" role="status">Looking up #{exactNumber}…</div>}
          {!loading && currentNumberLookup?.error && (
            <div className="pulls-sync-error" role="alert">Could not load #{exactNumber} from {scopedRepository}. {currentNumberLookup.error}</div>
          )}
          {!loading && error && rows.length === 0 && (
            <div className="hp-empty" role="alert">
              Could not load pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''}. {error}
            </div>
          )}
          {!loading && !error && !syncError && !currentNumberLookup?.loading && !currentNumberLookup?.error && rows.length === 0 && (
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
          <div className="hp-loading pulls-list__loading" role="status" aria-hidden={!loadingMore}>
            <span className={loadingMore ? 'is-loading' : undefined}>Loading more…</span>
          </div>
        </div>
      </section>}

      {reviewWorkspace !== undefined && <section className="agr-workspace-conversation" aria-label="Review agent conversation">
        <header className="agt-screen-head">
          <button type="button" className="agt-back" aria-label="Back to tasks" onClick={reviewWorkspace.onBack}>‹ Tasks</button>
          <strong>{selectedPr?.title ?? 'WorkBench'}</strong>
          <span className="agt-spacer" />{reviewWorkspace.headerAction}
        </header>
        {detail !== null && review !== null ? <AgentReviewTab detail={detail} workspaceId={workspaceId} viewer={viewer}
          review={review} onReviewChange={updateReview} mode="conversation" localReview={localReview}
          composerContext={selectedPr === null ? undefined : <button type="button" className="agt-pr-chip" aria-pressed={paneShown && detailTab === 'overview'}
            title={`#${selectedPr.number} ${selectedPr.title}`} onClick={() => { setDetailTab('overview'); setPaneOpen(true); }}>
            <PullRequestStateIcon pullRequest={selectedPr} size={16} />
            PR <span className="agt-pr-chip__number">#{selectedPr.number}</span>
          </button>}
          conversationPanel={{ tabTarget: agentTabTarget, bodyTarget: agentBodyTarget, active: detailTab === 'agents' || detailTab === 'discussions' ? detailTab : null,
            onSelect: tab => { setDetailTab(tab); setPaneOpen(true); },
            onClose: tab => { if (detailTab === tab) setDetailTab(coveredAgentTab.current); } }}
          onOpenReview={target => {
            setReviewCardFocus(previous => ({ target, token: (previous?.token ?? 0) + 1 }));
            setFocusFindingId(null);
            setDetailTab('review');
            setPaneOpen(true);
          }}
          focusFindingId={null} onNavigateToChanges={navigateToChanges} />
          : <div className="agr-workspace-status" role={reviewError === null ? 'status' : 'alert'}>
            {reviewError === null && <span className="agr-spinner" aria-hidden="true" />}
            <span>{reviewError ?? 'Loading review conversation…'}</span>
            {reviewError !== null && <button type="button" className="agt-action" onClick={() => setReviewReload(value => value + 1)}>Retry</button>}
          </div>}
      </section>}

      {reviewWorkspace !== undefined && <TaskPaneDivider pane={reviewPane} />}

      {zoomLayer && !zoom.full && <ZoomScrim onClose={zoom.close} />}

      {selectedPr === null && (singlePullRequest || paneShown) && (
        <section className={`pull-detail${overlay ? zoomClass : ''}${reviewWorkspace === undefined ? '' : ' agt-panel'}`}
          style={singlePullRequest
            ? reviewWorkspace === undefined ? undefined : { width: reviewPane.width }
            : { width: detailPane.width }}>
          <header className="pull-detail__header pull-detail__header--tabs">
            <div className="pull-detail__toolbar">
              <span>{initialPr?.repo} #{initialPr?.number}</span>
              {singlePullRequest && (
                <div className="pull-detail__header-actions">
                  <button className="pull-detail__icon-action" type="button" aria-label="Close pull request details" title="Close (Esc)" onClick={zoom.close}>
                    <X size={16} aria-hidden="true" />
                  </button>
                </div>
              )}
            </div>
          </header>
          {error
            ? <div className="pull-detail__error" role="alert">{error}</div>
            : <div className="pull-detail__body"><DetailSkeleton label="Loading details…" /></div>}
        </section>
      )}

      {paneShown && selectedPr !== null && (
        <section className={`pull-detail${zoomLayer ? zoomClass : reviewWorkspace === undefined ? '' : ' agt-panel'}`}
          style={zoomLayer ? undefined : { width: reviewWorkspace === undefined ? detailPane.width : reviewPane.width }}>
          {reviewWorkspace === undefined && <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize pull request panes" onMouseDown={event => detailPane.startResize(event, event.currentTarget.parentElement?.getBoundingClientRect().width)} title="Drag to resize" />}
          <PullDetailHeader
            pr={selectedPr}
            detail={detail}
            tab={detailTab === 'agents' || detailTab === 'discussions' ? null : detailTab}
            agentTab={reviewWorkspace === undefined ? undefined : <span ref={setAgentTabTarget} />}
            review={review}
            developerTask={currentReviewContext?.developerTask}
            onReviewChange={updateReview}
            codeAvailable={codeTabShown || codeTaskLoading}
            onChangesToolbar={setChangesToolbar}
            onSummaryToolbar={setSummaryToolbar}
            onTab={next => {
              if (next === 'files' && detailTab !== 'files') recordDiffOpened();
              if (next === 'code') setCodeTabKey(reviewKey);
              setDetailTab(next);
            }}
            onRefresh={refresh}
            onDetailUpdate={updateSelectedDetail}
            onReviewSubmitted={applySubmittedReview}
            refreshing={detailLoading}
            pendingComments={pendingReviewComments}
            onPendingCommentsChange={setPendingReviewComments}
            zoom={zoomLayer ? zoom : undefined}
            onZoom={zoomLayer ? undefined : () => setDetailZoomed(true)}
            onNavigate={navigate}
            agent={reviewWorkspace === undefined ? undefined : 'reviewer'}
          />
          {reviewWorkspace !== undefined && <div className="agt-panel__scroll" ref={setAgentBodyTarget} hidden={detailTab !== 'agents' && detailTab !== 'discussions'} />}
          {displayedDetailError && <div className="pull-detail__error">{displayedDetailError}</div>}
          {showBlockingDetailLoading(detailLoading, detail !== null) ? (
            <div className="pull-detail__body"><DetailSkeleton label="Loading details…" /></div>
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
            // Waiting keeps Tears of time from opening the review and then switching to the task's worktree.
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
                    threads: pullReviewThreads(selectedPr, detail, repositoryScope?.workspaceId, refresh, updateSelectedDetail),
                    viewer,
                    pending: pendingReviewComments,
                    onPendingChange: setPendingReviewComments,
                    onDetailChange: value => applyPostedDetail(value, 'review'),
                    localReview,
                    onAskAgent: askAgent,
                    onOpenFindingInReview: findingId => {
                      setFocusFindingId(findingId);
                      setReviewCardFocus(null);
                      setDetailTab('review');
                    },
                  }} />
              </div>
            )}
            {summaryTabKey !== null && summaryTabKey === reviewKey && <div className="pull-detail__body pull-detail__review" hidden={detailTab !== 'review'}>
              {codeTask !== null ? <DevelopmentLearningPanel task={codeTask} repository={detail.repo} refreshKey={codeTask.version}
                refining={codeTask.developmentContinuationUnfinished} disabledReason={codeTask.state === 'OPEN' ? null : 'The development task is closed'}
                localReview={localReview} bridge={window.bridge} onSelectCommit={selectCommit}
                review={{ workspaceId: codeTask.workspaceId, active: detailTab === 'review', onReviewChange: updateReview, onSelectCommit: selectCommit,
                  focusFindingId, focusReviewCard: reviewCardFocus, onNavigateToChanges: navigateToChanges }} />
                : <AgentReviewTab key={`${detail.repo.toLowerCase()}#${detail.number}`} toolbarTarget={summaryToolbar} detail={detail} workspaceId={workspaceId} viewer={viewer} review={review} onReviewChange={updateReview}
                  onRepositoryWatched={() => setReviewReload(value => value + 1)}
                  ownStream={reviewWorkspace === undefined && detailTab === 'review'} localReview={localReview} onSelectCommit={selectCommit}
                  focusFindingId={focusFindingId} focusReviewCard={reviewCardFocus}
                  onOpenWorkspace={review === null || reviewWorkspace !== undefined ? undefined : () => {
                    onClose?.(); window.location.hash = workspaceReviewTaskRoute(review.workspaceId, review.id);
                  }}
                  onNavigateToChanges={navigateToChanges} />}
            </div>}
            {(detailTab === 'overview' || detailTab === 'files') && <DetailBody
              localReview={localReview}
              privateThreads={privateThreads}
              tab={detailTab}
              toolbarTarget={changesToolbar}
              defaultTreeOpen={zoomLayer}
              taskActions={reviewWorkspace === undefined ? <PullRequestTaskActions
                key={`${workspaceId ?? ''}:${detail.repo.toLowerCase()}#${detail.number}`}
                repo={detail.repo} number={detail.number}
                authored={viewerLogin ? detail.author?.toLowerCase() === viewerLogin.toLowerCase() : selectedPr.origin === 'AUTHORED'}
                workspaceId={workspaceId} loading={reviewLoading} contextError={reviewError} review={review}
                onRetryContext={() => setReviewReload(value => value + 1)}
                onNavigate={navigate} /> : undefined}
              pr={selectedPr}
              detail={detail}
              viewer={viewer}
              review={review}
              reviewNavigation={reviewNavigation}
              onReviewChange={updateReview}
              onAskAgent={askAgent}
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
              onCommitRangeChange={changes.changeCommitRange}
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
      {discardDrafts !== null && (
        <ConfirmDialog
          title={`Discard unsaved changes to ${discardDrafts.files} ${discardDrafts.files === 1 ? 'file' : 'files'}?`}
          confirmLabel="Discard"
          onConfirm={() => { const act = discardDrafts.act; setDiscardDrafts(null); act(); }}
          onCancel={() => setDiscardDrafts(null)}
        >
          <p>Your edits in Tears of time were never saved to the worktree.</p>
        </ConfirmDialog>
      )}
    </div>
    </PendingReviewComments>
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
  codeAvailable = true,
  onChangesToolbar,
  onSummaryToolbar,
  onTab,
  onRefresh,
  onDetailUpdate,
  onReviewSubmitted,
  refreshing,
  pendingComments,
  onPendingCommentsChange,
  onZoom,
  zoom,
  onClose,
  onNavigate,
  agent,
  developerTask: suppliedDeveloperTask,
}: {
  pr: PullRequestDto | null;
  detail: PullRequestDetailDto | null;
  /** Null while one of the host's own `agentTab` destinations is showing. */
  tab: HeaderTab | null;
  /** The open Agent review, when the pull request has one; its status rides on the tab. */
  review?: AgentReviewDto | null;
  onReviewChange?: (review: AgentReviewDto) => void;
  /** Host destinations drawn after PR and WorkBench. */
  agentTab?: ReactNode;
  codeAvailable?: boolean;
  onChangesToolbar?: (target: HTMLDivElement | null) => void;
  onSummaryToolbar?: (target: HTMLDivElement | null) => void;
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
  /** Opens the zoom layer; inside the layer its own controls take over. */
  onZoom?: () => void;
  zoom?: ZoomControls;
  onClose?: () => void;
  /** Leaves for another route. With it, the header names the pull request's development and review agents and switches between them. */
  onNavigate?: (route: string) => void;
  /** The agent whose own screen this header is on; its button stays in place, marked as the current one. */
  agent?: 'developer' | 'reviewer';
  /** Conversation identity survives independently of a reviewer or a retained worktree. */
  developerTask?: PullRequestTaskLink | null;
}) {
  useAttentionVisit(pr === null || detail === null ? null : pullRequestItem(pr));
  const [mergeQueue, setMergeQueue] = useState<boolean | null | undefined>(undefined);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const canReview = pr !== null && detail?.state?.toLowerCase() === 'open';
  // Promoted findings are outgoing comments too, so the count the button shows covers both.
  const pendingCommentCount = pendingComments.length + pendingDraftCount(review?.draft ?? null);
  const reviewChip = review === null ? null : reviewStatus(review);
  const developerTask = review?.sourceTaskId == null ? suppliedDeveloperTask
    : { workspaceId: review.workspaceId, taskId: review.sourceTaskId };
  const showDeveloper = onNavigate !== undefined && (agent === 'developer' || developerTask != null);
  const showReviewer = onNavigate !== undefined && (agent === 'reviewer' || review !== null);
  // The development task's own screen already shows its state, so only a link to it reads the task.
  const developer = useTaskCardState(developerTask?.workspaceId ?? null, showDeveloper && agent !== 'developer' ? developerTask?.taskId ?? null : null);
  const switchAgent = (route: string): void => {
    // Utility tabs belong to one conversation; still keep the destination's panel open.
    carriedTab = { route, tab: tab ?? 'changes' };
    onNavigate?.(route);
  };
  // An idle or finished developer needs no chip.
  const developerTone = developer?.label === 'Needs you' ? 'warn' : developer?.label === 'Running' ? 'running' : developer?.label === 'Failed' ? 'danger' : null;
  const prSelected = tab === 'overview';
  const workbenchSelected = tab !== null && !prSelected;
  const changesSelected = tab === 'files' || tab === 'changes';
  const lastWorkbenchTab = useRef<HeaderTab | null>(null);
  if (workbenchSelected) lastWorkbenchTab.current = tab;
  const draft = detail?.draft === true;
  const canOpenMerge = pr !== null && detail?.state?.toLowerCase() === 'open';
  const queued = detail !== null && isQueued(detail.recentActivity);
  const canMerge = canOpenMerge && !draft && detail.viewerCanWrite && !queued;
  const conflicts = hasMergeConflicts(detail);
  const merge = detail ? mergeStatus(detail) : null;
  const mergeBlocked = merge?.state === 'failed' || merge?.state === 'blocked';
  const mergeLoading = !queued && ((canMerge && mergeQueue === undefined) || merge?.state === 'pending');
  const state = (detail?.state ?? pr?.state)?.toLowerCase();
  const iconState = state === 'merged' || state === 'closed'
    ? state
    : (detail?.draft ?? pr?.draft) ? 'draft' : 'open';
  const mergeLabel = queued ? 'In merge queue' : conflicts
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
    setMergeQueue(null);
    if (!canMerge || conflicts || !detail?.baseRef) return;
    setMergeQueue(undefined);
    let cancelled = false;
    void window.bridge.fetchMergeMode(detail.repo, detail.baseRef)
      .then(mode => { if (!cancelled) setMergeQueue(mode.mergeQueue); })
      .catch(() => { if (!cancelled) setMergeQueue(null); });
    return () => { cancelled = true; };
  }, [canMerge, draft, conflicts, detail?.repo, detail?.number, detail?.baseRef]);

  return (
    <header className="pull-detail__header pull-detail__header--tabs">
      <div className="pull-detail__toolbar">
        <nav className="pull-detail__tabs" aria-label="Task destinations">
          <button className={`pull-detail__pr-tab is-${iconState}${prSelected ? ' is-active' : ''}`}
            type="button" aria-pressed={prSelected} onClick={() => onTab('overview')}>
            <PullRequestIcon merged={iconState === 'merged'} /> PR{pr === null ? '' : ` #${pr.number}`}
          </button>
          <button className={workbenchSelected ? 'is-active' : ''} type="button" aria-pressed={workbenchSelected}
            onClick={() => onTab(lastWorkbenchTab.current ?? (pr === null ? 'changes' : 'files'))}>
            <Code size={14} strokeWidth={1.8} aria-hidden="true" /> WorkBench
            {reviewChip?.chip != null && <span className={`pull-detail__review-chip is-${reviewChip.tone}`}>
              {reviewChip.busy && <span className="agr-spinner" aria-hidden="true" />}{reviewChip.chip}
            </span>}
          </button>
          {(showDeveloper || showReviewer) && <span className="pull-detail__agents" role="group" aria-label="Agents">
            {showDeveloper && <button type="button" aria-current={agent === 'developer' ? 'true' : undefined}
              title={agent === 'developer' ? 'This is the development task'
                : `Open the development task${developer === null ? '' : ` · ${developer.reason ?? developer.label}`}`}
              onClick={agent === 'developer' || developerTask == null ? undefined
                : () => switchAgent(workspaceAgentTaskRoute(developerTask.workspaceId, developerTask.taskId))}>
              <Bot size={14} strokeWidth={1.8} aria-hidden="true" /> Developer
              {developer !== null && developerTone !== null && <span className={`pull-detail__review-chip is-${developerTone}`}>
                {developer.spinning && <span className="agr-spinner" aria-hidden="true" />}{developer.label}
              </span>}
            </button>}
            {showReviewer && <button type="button" aria-current={agent === 'reviewer' ? 'true' : undefined}
              title={agent === 'reviewer' ? 'This is the review task' : 'Open the review task'}
              onClick={agent === 'reviewer' || review === null ? undefined : () => switchAgent(workspaceReviewTaskRoute(review.workspaceId, review.id))}>
              <Eye size={14} strokeWidth={1.8} aria-hidden="true" /> Reviewer
            </button>}
          </span>}
          {agentTab}
        </nav>
        <div className="pull-detail__header-actions">
          {pr !== null && <PinButton className="pull-detail__icon-action" item={{ key: `${pr.repo}#${pr.number}`, title: detail?.title ?? pr.title,
            reference: `${pr.repo} #${pr.number}`, target: { kind: 'pull', repo: pr.repo, number: pr.number } }} />}
          {zoom
            ? <ZoomActions {...zoom} label="pull request details" className="pull-detail__icon-action" />
            : onClose ? <button className="pull-detail__icon-action" type="button"
              title="Close (Esc)" aria-label="Close pull request details" onClick={onClose}>
              <X size={16} aria-hidden="true" />
            </button> : onZoom && <button className="pull-detail__icon-action" type="button"
              title="Zoom" aria-label="Zoom pull request details" onClick={onZoom}>
              <Maximize size={15} strokeWidth={1.8} aria-hidden="true" />
            </button>}
          {pr !== null && <button className={`pull-detail__icon-action${refreshing ? ' is-refreshing' : ''}`} type="button"
            title={refreshing ? 'Refreshing pull request…' : 'Refresh'}
            aria-label="Refresh pull request"
            aria-busy={refreshing} disabled={refreshing}
            onClick={() => { void onRefresh(); }}>
            <RefreshIcon />
          </button>}
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
      {workbenchSelected && <div className="pull-detail__subtoolbar">
        <nav className="pull-detail__tabs pull-detail__subtabs" aria-label="WorkBench views">
          <button type="button" className={changesSelected ? 'is-active' : ''} aria-pressed={changesSelected}
            onClick={() => onTab(pr !== null ? 'files' : 'changes')}><Diff size={14} aria-hidden="true" /> Changes</button>
          <button type="button" className={tab === 'review' ? 'is-active' : ''} aria-pressed={tab === 'review'} onClick={() => onTab('review')}><CommentIcon /> Summary</button>
          <button type="button" className={tab === 'code' ? 'is-active' : ''} aria-pressed={tab === 'code'} onClick={() => onTab('code')} disabled={!codeAvailable}
            title={codeAvailable ? undefined : 'Watch this repository to explore its code'}><Code size={14} aria-hidden="true" /> Tears of time</button>
        </nav>
        {tab === 'review' && <div className="pull-detail__summary-toolbar" ref={onSummaryToolbar} />}
        {changesSelected && <div className="pull-detail__changes-tools">
          <div className="pull-detail__changes-toolbar" ref={onChangesToolbar} />
        </div>}
      </div>}
      {reviewOpen && detail && pr && (
        <ReviewDialog pr={pr} detail={detail} review={review} onReviewChange={onReviewChange}
          pendingComments={pendingComments}
          onPendingCommentsChange={onPendingCommentsChange}
          onClose={() => setReviewOpen(false)} onReviewSubmitted={onReviewSubmitted} />
      )}
      {mergeOpen && detail && pr && (
        <MergeDialog pr={pr} detail={detail} mergeQueue={mergeQueue}
          onClose={() => setMergeOpen(false)} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />
      )}
    </header>
  );
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


function PullActionSheet({ kind, busy, onClose, children, footer }: {
  kind: 'review' | 'merge';
  busy: boolean;
  onClose: () => void | Promise<void>;
  children: ReactNode;
  footer: ReactNode;
}) {
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.key !== 'Escape') return;
      event.preventDefault();
      void onClose();
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [onClose]);

  const className = `pr-${kind}-sheet`;
  return <div className="pr-sheet-overlay" onMouseDown={event => {
    if (event.target === event.currentTarget) void onClose();
  }}>
    <section className={className} role="dialog" aria-modal="true" aria-labelledby={`pr-${kind}-title`}>
      <div className={`${className}__header`}>
        <h2 id={`pr-${kind}-title`}>{kind === 'review' ? 'Submit review' : 'Merge pull request'}</h2>
        <button type="button" aria-label={`Close ${kind} dialog`} autoFocus={kind === 'merge'} disabled={busy} onClick={() => { void onClose(); }}>×</button>
      </div>
      <div className={`${className}__content`}>{children}</div>
      <footer className={`${className}__footer`}>{footer}</footer>
    </section>
  </div>;
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
  // Without an agent review the sheet loads the draft itself: every review leaves through it, so a
  // send whose outcome is unknown stays recorded and cannot go out twice.
  const [loadedDraft, setLoadedDraft] = useState<ReviewDraftDto | null>(null);
  const draft = review?.draft ?? loadedDraft;
  const [body, setBody] = useState(draft?.body ?? '');
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const commentRef = useRef<HTMLTextAreaElement>(null);
  const [commentPreview, setCommentPreview] = useState(false);
  const savedDraft = useRef(draft);
  const edited = useRef(false);
  const command = usePrCommentCommand({ repository: detail.repo, number: detail.number, value: body,
    onChange: text => { edited.current = true; setBody(text); }, textareaRef: bodyRef });
  const inFlight = useRef(false);
  const authored = pr.origin === 'AUTHORED';
  const [selectedEvent, setEvent] = useState<ReviewEvent>(authored ? 'COMMENT' : draft?.event ?? 'COMMENT');
  const event = authored ? 'COMMENT' : selectedEvent;
  const [submitting, setSubmitting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [unsavedClose, setUnsavedClose] = useState(false);
  const [commentEdit, setCommentEdit] = useState<{ key: string; body: string; version: number } | null>(null);
  const busy = submitting || saving || command.loading;
  const [error, setError] = useState<string | null>(null);
  const promoted = draft?.items.filter(item => !item.published && (item.included || item.publication !== null)) ?? [];
  // Review text in a send stays as sent until the user says whether it reached GitHub.
  const reviewSend = draft?.reviewPublication?.state ?? null;
  const unconfirmed = reviewSend !== null || (draft?.items.some(item => item.publication?.state === 'UNCERTAIN' || item.publication?.state === 'RESERVED') ?? false);
  const bodyRequired = event !== 'APPROVE' && pendingComments.length === 0 && promoted.length === 0;
  const failed = useCallback((value: unknown) => setError(remoteErrorMessage(value)), []);
  const hasReviewDraft = Boolean(review?.draft);

  useEffect(() => {
    if (hasReviewDraft) return;
    let live = true;
    window.bridge.getReviewDraft(detail.repo, detail.number).then(value => { if (live) setLoadedDraft(value); }, failed);
    return () => { live = false; };
  }, [hasReviewDraft, detail.repo, detail.number, failed]);

  // A draft write can leave the draft changed even when it fails, so the sheet re-reads it.
  const reloadReview = useCallback(() => {
    if (review !== null) void window.bridge.getAgentReview(review.id).then(onReviewChange, () => {});
    else void window.bridge.getReviewDraft(detail.repo, detail.number).then(setLoadedDraft, () => {});
  }, [review, onReviewChange, detail.repo, detail.number]);

  const showDraft = (updated: ReviewDraftDto) => {
    if (review !== null) onReviewChange({ ...review, draft: updated });
    else setLoadedDraft(updated);
  };

  useEffect(() => {
    const incoming = draft;
    const saved = savedDraft.current;
    if (incoming && saved === null && !edited.current) {
      savedDraft.current = incoming;
      setBody(incoming.body);
      setEvent(authored ? 'COMMENT' : incoming.event);
    }
    // Text typed before an empty draft arrived overwrites nothing, so it stays.
    else if (incoming && saved === null && incoming.body === '' && incoming.reviewPublication === null) savedDraft.current = incoming;
    // Item edits can advance the version; another editor's review prose must still conflict.
    if (incoming && saved && incoming.version >= saved.version && incoming.body === saved.body && incoming.event === saved.event) savedDraft.current = incoming;
  }, [draft, authored]);

  const close = useCallback(async () => {
    if (inFlight.current) return;
    if (commentEdit !== null) {
      setError('Save or cancel the comment edit before closing the review.');
      return;
    }
    const saved = savedDraft.current;
    // Locked text cannot have changed, though it may differ from the stored copy by trimmed whitespace.
    if (saved === null || reviewSend !== null || (body === saved.body && event === saved.event)) {
      onClose();
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setUnsavedClose(false);
    setError(null);
    try {
      savedDraft.current = await window.bridge.saveReviewDraft(detail.repo, detail.number, saved.version, body, event);
      reloadReview();
      onClose();
    }
    catch (value) {
      failed(value);
      setUnsavedClose(true);
      reloadReview();
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  }, [body, event, detail.repo, detail.number, onClose, reloadReview, failed, commentEdit, reviewSend]);

  const removePromoted = async (itemId: string) => {
    if (draft === null || inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.removeReviewDraftComment(detail.repo, detail.number, savedDraft.current?.version ?? draft.version, itemId);
      if (updated.body === savedDraft.current?.body && updated.event === savedDraft.current.event) savedDraft.current = updated;
    }
    catch (value) {
      failed(value);
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
    reloadReview();
  };

  const confirmDelivery = async (itemId: string, posted: boolean) => {
    if (draft === null || inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      const version = savedDraft.current?.version ?? draft.version;
      const updated = posted
        ? await window.bridge.confirmReviewDraftCommentPosted(detail.repo, detail.number, version, itemId)
        : await window.bridge.confirmReviewDraftCommentNotPosted(detail.repo, detail.number, version, itemId);
      if (updated.body === savedDraft.current?.body && updated.event === savedDraft.current.event) savedDraft.current = updated;
      showDraft(updated);
    }
    catch (value) {
      failed(value);
      reloadReview();
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  const confirmReview = async (posted: boolean) => {
    if (draft === null || inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.confirmReviewDraftReview(detail.repo, detail.number, savedDraft.current?.version ?? draft.version, posted);
      // The locked box showed the sent text; posted clears it, so Submit cannot send it again.
      savedDraft.current = updated;
      setBody(updated.body);
      setEvent(authored ? 'COMMENT' : updated.event);
      showDraft(updated);
    }
    catch (value) {
      failed(value);
      reloadReview();
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  const outgoing = [
    ...pendingComments.map((comment, index) => ({
      key: `${comment.path}:${comment.side}:${comment.startLine ?? comment.line}:${comment.line}:${index}`,
      comment,
      remove: () => onPendingCommentsChange(pendingComments.filter((_, commentIndex) => commentIndex !== index)),
      save: (text: string) => onPendingCommentsChange(updatePendingReviewComment(pendingComments, comment, text)),
    })),
    ...promoted.map(item => ({ key: item.id, comment: { ...item, commitId: draftCommentCommit(item, review?.findings ?? []) },
      remove: () => { void removePromoted(item.id); },
      save: async (text: string, version: number) => {
        const updated = await window.bridge.editReviewDraftComment(detail.repo, detail.number, version, item.id, text, item.included);
        if (updated.body === savedDraft.current?.body && updated.event === savedDraft.current.event) savedDraft.current = updated;
        showDraft(updated);
      } })),
  ];
  const cancelCommentEdit = () => { setCommentEdit(null); setError(null); };
  const saveComment = async (save: (text: string, version: number) => void | Promise<void>) => {
    if (inFlight.current || commentEdit === null || !commentEdit.body.trim()) return;
    inFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await save(commentEdit.body.trim(), commentEdit.version);
      setCommentEdit(null);
    }
    catch (value) {
      failed(value);
      reloadReview();
    }
    finally {
      inFlight.current = false;
      setSaving(false);
    }
  };
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
  const codeLines = (comment: InlineReviewCommentDto | ReviewDraftItemDto) => {
    if (snapshot === null || comment.commitId === null || comment.commitId !== snapshot.headSha) return [];
    const file = snapshot.files.find(value => value.filename === comment.path);
    return file ? commentedCodeLines(file.patch, comment.side, comment.startLine, comment.line) : [];
  };

  const submit = async () => {
    if (inFlight.current || command.loading || unconfirmed || commentEdit !== null || detail.state?.toLowerCase() !== 'open' || (bodyRequired && !body.trim())) return;
    inFlight.current = true;
    setSubmitting(true);
    setError(null);
    try {
      // Everything leaves through the draft, which reserves each comment and the review text, so
      // Post comment now or a retry after an unknown outcome cannot send them again. Written
      // comments retain their commits in the same draft; the backend sends one batch per commit.
      const saved = savedDraft.current;
      if (saved === null) throw new Error('The review draft loaded after you started editing. Close and reopen to review its latest text.');
      savedDraft.current = await window.bridge.saveReviewDraft(detail.repo, detail.number, saved.version, body, event);
      let remaining = pendingComments;
      let version = savedDraft.current.version;
      for (const comment of remaining) {
        savedDraft.current = await window.bridge.addReviewDraftComment(detail.repo, detail.number, version, {
          path: comment.path, side: comment.side, line: comment.line, startLine: comment.startLine, body: comment.body, commitId: comment.commitId, replyToCommentId: comment.replyToCommentId,
        });
        version = savedDraft.current.version;
        remaining = remaining.filter(value => value !== comment);
        onPendingCommentsChange(remaining);
      }
      await window.bridge.submitReviewDraft(detail.repo, detail.number, version, body.trim(), event);
      recordAttentionAction(`${detail.repo}#${detail.number}`, event === 'COMMENT' ? 'comment' : 'review',
        event === 'COMMENT' ? Math.max(1, pendingComments.length + promoted.length + Number(body.trim().length > 0)) : 1);
      reloadReview();
      // The draft answers with itself alone; the stored detail already carries the new review.
      void window.bridge.fetchPullRequestDetailByRef(detail.repo, detail.number)
        .then(reviewed => onReviewSubmitted(reviewed, event), () => {});
      onClose();
    }
    catch (value) {
      failed(value);
      setSubmitting(false);
      reloadReview();
    }
    finally {
      inFlight.current = false;
    }
  };

  return (
    <PullActionSheet kind="review" busy={busy} onClose={close} footer={<>
        {unsavedClose && <button type="button" disabled={busy} onClick={() => { reloadReview(); onClose(); }}>Close without saving</button>}
        <button type="button" disabled={busy} onClick={() => { void close(); }}>Cancel</button>
        <button className="is-primary" type="button"
          disabled={busy || unconfirmed || commentEdit !== null || (bodyRequired && !body.trim())}
          title={commentEdit !== null ? 'Save or cancel the comment edit before submitting.' : undefined}
          onClick={() => { void submit(); }}>
          {submitting ? 'Submitting…' : 'Submit review'}
        </button>
    </>}>
        <textarea
          ref={bodyRef}
          autoFocus
          disabled={submitting || saving || reviewSend !== null}
          value={body}
          placeholder="Leave a comment on this pull request…"
          aria-label="Review comment"
          {...command.inputProps}
          onBlur={command.dismiss}
          onKeyDown={command.keyDown}
          onChange={command.change} />
        {command.suggestion}
        {reviewSend !== null && <div className="pr-review-sheet__review-delivery">
          <span className="pull-diff-pending__badge">{reviewSend === 'UNCERTAIN' ? 'Delivery unconfirmed' : 'Sending…'}</span>
          {reviewSend === 'UNCERTAIN' && <>
            <button className="pr-review-sheet__edit" type="button" disabled={busy || commentEdit !== null}
              title="I found this review on GitHub. Mark it posted so it is never sent again."
              onClick={() => { void confirmReview(true); }}>Confirmed posted</button>
            <button className="pr-review-sheet__edit" type="button" disabled={busy || commentEdit !== null}
              title="I checked GitHub and this review is not there. Unlock it without sending it."
              onClick={() => { void confirmReview(false); }}>Confirmed not posted</button>
          </>}
        </div>}
        <BetterWordsButton text={body} textareaRef={bodyRef} repository={detail.repo} workspaceId={review?.workspaceId}
          disabled={busy || reviewSend !== null} onError={value => setError(value === null ? null : remoteErrorMessage(value))} />
        {outgoing.length > 0 && (
          <div className="pr-review-sheet__pending">
            {outgoing.map(({ key, comment, remove, save }) => {
              const lines = comment.replyToCommentId == null ? codeLines(comment) : [];
              const publication = 'publication' in comment ? comment.publication : null;
              const locked = publication !== null;
              return <ReviewThreadCard key={key} resolved={false} controls={null}
                title={<span className="lrc__title"><code title={comment.path}>{comment.path}</code>
                  {comment.replyToCommentId != null && <span> · Reply to thread</span>}
                  {comment.replyToCommentId == null && comment.commitId !== null && <span> · commit {shortSha(comment.commitId)}</span>}</span>}
                code={lines.length > 0 && <div className="lrc__code">
                  {lines.map((line, index) => <CommentedCodeLine key={line.lineNumber ?? index} line={line} />)}
                </div>}>
                <ReviewThreadMessage timestamp={null}
                  author={<>{viewer && <UserAvatar login={viewer.login} avatarUrl={viewer.avatarUrl} size={22} showLogin />}
                    <span className="pull-diff-pending__badge">{publication?.state === 'UNCERTAIN' ? 'Delivery unconfirmed'
                      : publication?.state === 'RESERVED' ? 'Sending…' : 'Pending'}</span></>}
                  menu={<>
                    {publication?.state === 'UNCERTAIN' && <>
                      <button className="pr-review-sheet__edit" type="button" disabled={busy || commentEdit !== null}
                        title="I found this comment on GitHub. Mark it posted so it is never sent again."
                        onClick={() => { void confirmDelivery(key, true); }}>Confirmed posted</button>
                      <button className="pr-review-sheet__edit" type="button" disabled={busy || commentEdit !== null}
                        title="I checked GitHub and this comment is not there. Return it to pending without sending it."
                        onClick={() => { void confirmDelivery(key, false); }}>Confirmed not posted</button>
                    </>}
                    <button className="pr-review-sheet__edit" type="button" disabled={busy || locked || commentEdit !== null}
                      onClick={() => { setError(null); setCommentPreview(false); setCommentEdit({ key, body: comment.body, version: draft?.version ?? 0 }); }}>Edit</button>
                    <button className="pr-review-sheet__remove" type="button" disabled={busy || locked || commentEdit !== null} onClick={remove}>Remove</button>
                  </>}>
                  {commentEdit?.key === key ? <MarkdownEditor
                    textareaRef={commentRef} preview={commentPreview} onPreviewChange={setCommentPreview}
                    value={commentEdit.body} onChange={text => setCommentEdit({ ...commentEdit, body: text })}
                    ariaLabel="Edit pending review comment" repository={detail.repo} busy={busy} autoFocus
                    onSubmit={() => { void saveComment(save); }} onCancel={cancelCommentEdit}>
                    <BetterWordsButton text={commentEdit.body} textareaRef={commentRef} repository={detail.repo} workspaceId={review?.workspaceId}
                      disabled={busy || commentPreview} onError={value => setError(value === null ? null : remoteErrorMessage(value))} />
                    <button type="button" disabled={busy} onClick={cancelCommentEdit}>Cancel</button>
                    <button type="button" className="is-primary" disabled={busy || !commentEdit.body.trim()}
                      onClick={() => { void saveComment(save); }}>{saving ? 'Saving…' : 'Save'}</button>
                  </MarkdownEditor> : <MarkdownBody body={comment.body} repository={detail.repo} />}
                </ReviewThreadMessage>
              </ReviewThreadCard>;
            })}
          </div>
        )}
        <ReviewChoice
          disabled={busy || reviewSend !== null}
          checked={event === 'COMMENT'}
          title="Comment"
          description="Submit general feedback without explicit approval."
          onChange={() => { edited.current = true; setEvent('COMMENT'); }} />
        {!authored && <ReviewChoice
          disabled={busy || reviewSend !== null}
          checked={event === 'APPROVE'}
          title="Approve"
          description="Submit feedback and approve merging these changes."
          onChange={() => { edited.current = true; setEvent('APPROVE'); }} />}
        {!authored && <ReviewChoice
          disabled={busy || reviewSend !== null}
          checked={event === 'REQUEST_CHANGES'}
          title="Request Changes"
          description="Submit feedback that must be addressed before merging."
          onChange={() => { edited.current = true; setEvent('REQUEST_CHANGES'); }} />}
        {unconfirmed && <div className="pr-review-sheet__error" role="alert">
          An earlier comment or review is still sending or its delivery is unconfirmed. Check the <a href={`https://github.com/${detail.repo}/pull/${detail.number}`}>pull request on GitHub</a>.
          {' '}For each unconfirmed one, choose <strong>Confirmed posted</strong> if it is there, or <strong>Confirmed not posted</strong> to return it to pending, then submit your review.
        </div>}
        {error && <div className="pr-review-sheet__error" role="alert">{error}</div>}
    </PullActionSheet>
  );
}

function ReviewChoice({ checked, title, description, disabled = false, onChange }: {
  checked: boolean;
  title: string;
  description: string;
  disabled?: boolean;
  onChange: () => void;
}) {
  return (
    <label className="pr-review-choice">
      <input type="radio" name="review-event" checked={checked} disabled={disabled} onChange={onChange} />
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
  const conflicts = hasMergeConflicts(detail);
  const blocked = conflicts || state === 'failed' || state === 'blocked';
  const mounted = useRef(true);
  const inFlight = useRef(false);
  const submitted = useRef(false);
  const canMarkReady = detail.draft && detail.state?.toLowerCase() === 'open'
    && (detail.viewerCanWrite || pr.origin === 'AUTHORED');
  const queued = isQueued(detail.recentActivity);
  const lastRemoval = detail.recentActivity.filter(activity => activity.eventType === 'removed_from_merge_queue').at(-1);
  const canRemoveFromQueue = queued && detail.state?.toLowerCase() === 'open' && detail.viewerCanWrite;
  const canMerge = !detail.draft && detail.state?.toLowerCase() === 'open' && detail.viewerCanWrite && state === 'ready'
    && typeof mergeQueue === 'boolean' && !!detail.headSha && !conflicts && !queued;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const close = useCallback(() => { if (!submitting) onClose(); }, [onClose, submitting]);

  const submit = async (action: 'ready' | 'merge' | 'remove') => {
    const allowed = action === 'ready' ? canMarkReady : action === 'remove' ? canRemoveFromQueue : canMerge;
    if (inFlight.current || submitted.current || !allowed) return;
    inFlight.current = true;
    setSubmitting(true);
    setError(null);
    try {
      if (action === 'ready') {
        await window.bridge.markPrReadyForReview(detail.repo, detail.number);
        onDetailUpdate(detail.repo, detail.number, current => ({ ...current, draft: false }));
      }
      else if (action === 'remove') {
        await window.bridge.removePrFromMergeQueue(detail.repo, detail.number);
      }
      else {
        if (!detail.headSha) throw new Error('Refresh the pull request before merging.');
        await window.bridge.mergePr(pr.id, detail.repo, detail.number, mergeQueue ? 'queue' : 'rebase', detail.headSha);
      }
      recordAttentionAction(`${detail.repo}#${detail.number}`, 'update');
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
    <PullActionSheet kind="merge" busy={submitting} onClose={close} footer={<>
        <button type="button" disabled={submitting} onClick={onClose}>Cancel</button>
        {!queued && <button className="is-primary" type="button" disabled={submitting || !canMarkReady}
          onClick={() => { void submit('ready'); }}>
          <PullRequestIcon /> {submitting && detail.draft ? 'Marking ready…' : 'Ready for review'}
        </button>}
        <button className={queued ? undefined : 'is-primary'} type="button" disabled={submitting || !(queued ? canRemoveFromQueue : canMerge)}
          onClick={() => { void submit(queued ? 'remove' : 'merge'); }}>
          {queued ? <X size={14} aria-hidden="true" /> : <PullRequestIcon />}
          {queued ? submitting ? 'Removing…' : 'Remove from merge queue'
            : submitting && !detail.draft ? 'Submitting…' : mergeQueue === false ? 'Rebase and merge' : 'Merge when ready'}
        </button>
    </>}>
        <div className={`pr-merge-status-card${blocked && !queued ? ' is-danger' : ''}`}>
          {queued
            ? <span className="pr-merge-dot is-queue" aria-hidden="true" />
            : blocked
            ? <ConflictIcon />
            : state === 'pending'
              ? <MergeReadyIcon />
              : <span className={`pr-merge-dot${mergeQueue ? ' is-queue' : ''}`} />}
          <strong>{queued
            ? 'In merge queue'
            : conflicts
            ? <>Conflicts with target branch{detail.baseRef && <> <code>{detail.baseRef}</code></>}</>
            : blocked
            ? 'Merge blocked'
            : state === 'pending'
              ? 'Waiting for checks'
              : detail.draft ? 'Draft pull request'
                : !detail.viewerCanWrite ? 'Checks complete'
                  : typeof mergeQueue !== 'boolean' ? mergeActionLabel(mergeQueue)
                    : mergeQueue ? 'Merge queue is enabled' : 'Ready to rebase and merge'}</strong>
        </div>
        {conflicts && !queued && <PullRequestConflictFiles key={`${detail.repo}#${detail.number}:${detail.baseRef}:${detail.updatedAt}`}
          repo={detail.repo} number={detail.number} />}
        {!queued && lastRemoval && <section className="pr-merge-queue-removal" aria-label="Last merge queue removal">
          <strong>Last removed from the merge queue</strong>
          <MergeQueueRemoval activity={lastRemoval} repo={detail.repo} number={detail.number} />
        </section>}
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
                  ? 'All pull-request checks have passed'
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
    </PullActionSheet>
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
  tab, localReview, privateThreads, taskActions, toolbarTarget, defaultTreeOpen,
  pr,
  detail,
  viewer,
  review,
  reviewNavigation,
  onReviewChange,
  onAskAgent,
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
  tab: 'overview' | 'files';
  toolbarTarget?: HTMLElement | null;
  defaultTreeOpen?: boolean;
  localReview?: LocalReviewThreads;
  /** The private threads of the conversation's host; absent when the pull request has none. */
  privateThreads?: PrivateThreadSource;
  taskActions?: ReactNode;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  viewer: UserProfileDto | null;
  review: AgentReviewDto | null;
  reviewNavigation: ReviewNavigationTarget | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
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
  if (tab === 'files') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return <div className="pull-detail__body pull-detail__changes"><PullChanges
      toolbarTarget={toolbarTarget} defaultTreeOpen={defaultTreeOpen}
      localReview={localReview}
      onAskAgent={onAskAgent}
      onAskAgentDisabled={review?.task.mutationClaim === 'LOCAL_RUN' ? 'A run is using this working copy' : null}
      repo={detail.repo} number={detail.number} workspaceId={contributorsWorkspaceId}
      canReview={canSubmitReview(pr, detail)}
      reviewThreads={pullReviewThreads(pr, detail, contributorsWorkspaceId, onRefresh, onDetailUpdate)}
      files={diffs}
      commits={diffCommits}
      commitRange={diffRange}
      loading={diffLoading}
      pendingComments={pendingComments}
      agentReview={review === null ? undefined : {
        review,
        viewer,
        navigation: reviewNavigation,
        onReviewChange,
        onOpenInReview,
      }}
      onCommitRangeChange={onCommitRangeChange}
      onPendingCommentsChange={onPendingCommentsChange}
      onDetailChange={onReviewPosted}
    /></div>;
  }
  return <div className="pull-detail__body"><PullConversation
    source={privateThreads}
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

/** The conversation and its private threads, whose view state the key resets for each pull request. */
function PullConversation({ source, ...overview }: { source?: PrivateThreadSource } & Omit<ComponentProps<typeof PullOverview>, 'privateThreads'>) {
  const privateThreads = usePrivateThreads(source, `${overview.pr.repo.toLowerCase()}#${overview.pr.number}`);
  return <PullOverview {...overview} privateThreads={privateThreads} />;
}

export function RefreshIcon() {
  return <RefreshCw size={15} strokeWidth={1.8} aria-hidden="true" />;
}

export function PaneIcon() {
  return <PanelRight size={15} strokeWidth={1.7} aria-hidden="true" />;
}

export function CommentIcon() {
  return <MessageCircle size={14} strokeWidth={1.8} aria-hidden="true" />;
}
