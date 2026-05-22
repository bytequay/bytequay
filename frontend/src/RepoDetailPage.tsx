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
import type { IssueDto, PullRequestDto, UserProfileDto } from './types';
import IssueDetailScreen from './IssueDetailScreen';
import PullRequestPreview from './PullRequestPreview';
import ReviewScreen from './ReviewScreen';
import DiffViewerScreen from './DiffViewerScreen';
import ResizeHandle from './ResizeHandle';
import Avatar from './Avatar';
import LogoLoading from './LogoLoading';
import { getCached, setCached } from './dataCache';
import { decideDeepLinkSelection } from './repoDeepLink';

const repoPullsKey = (owner: string, repo: string) => `repo:${owner}/${repo}:pulls`;
/** Per-state cache key — open and closed are fetched on different
 *  triggers (open eagerly on mount, closed on first tab click) so we
 *  cache them separately to avoid clobbering. */
const repoIssuesKey = (owner: string, repo: string, state: IssueState) =>
  `repo:${owner}/${repo}:issues:${state}`;

type IssueState = 'open' | 'closed';

const SIDEBAR_WIDTH_KEY = 'settings:pr-sidebar-width';
const SIDEBAR_COLLAPSED_KEY = 'settings:pr-sidebar-collapsed';
const SIDEBAR_WIDTH_MIN = 260;
const SIDEBAR_WIDTH_MAX = 600;
const SIDEBAR_WIDTH_DEFAULT = 380;
const SIDEBAR_RAIL_WIDTH = 36;

function loadSidebarWidth(): number {
  const raw = localStorage.getItem(SIDEBAR_WIDTH_KEY);
  const n = raw ? parseInt(raw, 10) : NaN;
  if (!Number.isFinite(n)) return SIDEBAR_WIDTH_DEFAULT;
  return Math.max(SIDEBAR_WIDTH_MIN, Math.min(SIDEBAR_WIDTH_MAX, n));
}
import {
  byUpdatedAtDesc,
  formatRelative,
  groupHandledByTime,
  markHandledPatch,
  mergedPatch,
  patchPr,
  syncCachesAfterPrChange,
  reopenPatch,
  sortHandled,
  sortSnoozed,
  splitByBucket,
  unmergedPatch,
} from './prBuckets';
import { HandledTimeline, InboxCard, SnoozedList } from './PrBucketViews';

type Tab = 'pulls' | 'issues';
type Bucket = 'inbox' | 'snoozed' | 'handled';
type Scope = 'mine' | 'review' | 'other';

type Props = {
  owner: string;
  repo: string;
  /** When set, the page tries to auto-select the matching PR after the
   *  initial pulls fetch lands. Used by deep-links from the home-page
   *  activity feed (clicking #1234 jumps straight to the PR detail). */
  initialPrNumber?: number;
  /** Sidebar tab to land on. Defaults to `'pulls'`. The Repository
   *  home's Issues tab passes `'issues'` so clicking through doesn't
   *  drop the user back on the PR list. */
  initialTab?: 'pulls' | 'issues';
  /** When set together with {@link #initialPrNumber}, the page jumps
   *  past the PR conversation view straight into the DiffViewer at
   *  the given commit SHA. Email-injected "↗ ByteQuay" buttons use
   *  this so the user lands on the code diff page they care about. */
  initialDiffCommitSha?: string;
  /** Reverse nav from a PR back to its head branch's local-repo
   *  Commits tab. PullRequestPreview surfaces a button next to the
   *  head ref that calls this. App-level so the nav target lines up
   *  with the existing local-repo route. */
  onOpenLocalBranch?: (owner: string, repo: string, branch: string) => void;
  /** Cross-domain jump: PR detail → thread detail. The header shows a
   *  chip for every thread whose `linkedPrNumber` matches the PR; the
   *  click dispatches up to the app shell to flip nav. */
  onOpenThread?: (threadId: string) => void;
  /** Forwarded to PullRequestPreview's "AI panel review" button; the
   *  app shell routes the returned threadId into the review-thread
   *  page. */
  onStartReview?: (threadId: string) => void;
};

/** Right-pane placeholder shown while a deep-link's PR fetch is in
 *  flight. Replaces the HelpPanel for the (typically) 1–2 seconds
 *  between the click on a home-page activity link and the single-PR
 *  fetch resolving — without it the user sees the generic Inbox help
 *  text and assumes the click did nothing. */
function DeepLinkLoading({ owner, repo, number }: { owner: string; repo: string; number: number }) {
  return (
    <div className="v2-help v2-help--loading">
      <LogoLoading size={64} label={`Loading ${owner}/${repo}#${number}`} />
      <h1 className="v2-help__title">
        Loading {owner}/{repo} <span className="v2-help__pr-num">#{number}</span>
      </h1>
      <p className="v2-help__subtitle">
        Fetching the pull request — this is normal for PRs that aren't already in your watched list.
      </p>
    </div>
  );
}

function RepoDetailPage({ owner, repo, initialPrNumber, initialTab, initialDiffCommitSha, onOpenLocalBranch, onOpenThread, onStartReview }: Props) {
  const [tab, setTab] = useState<Tab>(initialTab ?? 'pulls');
  const [bucket, setBucket] = useState<Bucket>('inbox');
  const [scope, setScope] = useState<Scope>('mine');
  // Seed from the cache keyed by owner/repo so revisiting the same repo is
  // instant. Different repo → cache miss → we fall back to []/spinner.
  const [pulls, setPulls] = useState<PullRequestDto[]>(
    () => getCached<PullRequestDto[]>(repoPullsKey(owner, repo)) ?? [],
  );
  // Open and closed issues live in separate slots so the user can flip
  // tabs without one bucket clobbering the other. Both are seeded from
  // dataCache so revisiting a repo paints instantly. Closed is loaded
  // lazily on first click — keeps the rate-limit hit small for repos
  // with thousands of closed issues.
  const [openIssues, setOpenIssues] = useState<IssueDto[] | null>(
    () => getCached<IssueDto[]>(repoIssuesKey(owner, repo, 'open')) ?? null,
  );
  const [closedIssues, setClosedIssues] = useState<IssueDto[] | null>(
    () => getCached<IssueDto[]>(repoIssuesKey(owner, repo, 'closed')) ?? null,
  );
  const [issueState, setIssueState] = useState<IssueState>('open');
  const [issueSearch, setIssueSearch] = useState('');
  const [issueLoadError, setIssueLoadError] = useState<string | null>(null);
  const [issueLoading, setIssueLoading] = useState(false);
  /** Number of the issue currently previewed in the right pane (when
   *  the Issues tab is active). Mirrors selectedPr's role for the PR
   *  flow — clicking a row sets this; the right pane's IssueDetail
   *  component fetches its own payload from the number alone. */
  const [selectedIssueNumber, setSelectedIssueNumber] = useState<number | null>(null);
  const [loading, setLoading] = useState(
    getCached(repoPullsKey(owner, repo)) === undefined,
  );
  const [error, setError] = useState<string | null>(null);
  const [selectedPr, setSelectedPr] = useState<PullRequestDto | null>(null);
  /** Whether a deep-link selection is still resolving — used to render
   *  a "Loading PR #N…" placeholder in the right pane instead of the
   *  HelpPanel, so the user knows the click is being honoured. Cleared
   *  once a selection lands or all in-flight fetches give up. */
  const [deepLinkPending, setDeepLinkPending] = useState(false);
  const [reviewingPr, setReviewingPr] = useState<PullRequestDto | null>(null);
  const [diffViewerPr, setDiffViewerPr] = useState<PullRequestDto | null>(null);
  // When the user opened the diff viewer by clicking a commit SHA chip in
  // the timeline, that SHA is stashed here so DiffViewerScreen can land
  // on the single-commit view instead of the cumulative PR diff.
  const [diffViewerCommitSha, setDiffViewerCommitSha] = useState<string | null>(null);
  const [currentUser, setCurrentUser] = useState<UserProfileDto | null>(
    () => getCached<UserProfileDto>('home:profile') ?? null,
  );
  const [sidebarWidth, setSidebarWidth] = useState<number>(loadSidebarWidth);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(() => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1');
  const pageRef = useRef<HTMLDivElement>(null);

  const handleSidebarResize = (clientX: number) => {
    const rect = pageRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(SIDEBAR_WIDTH_MIN, Math.min(SIDEBAR_WIDTH_MAX, clientX - rect.left));
    setSidebarWidth(next);
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(next));
  };

  useEffect(() => {
    void window.bridge.getUserProfile()
      .then(p => { setCurrentUser(p); setCached('home:profile', p); })
      .catch(() => { /* non-fatal */ });
  }, []);

  useEffect(() => {
    // Switching repos: reseed from cache for the new repo. If the cache has
    // data we keep the old lists on-screen for one frame (they're about to
    // be replaced), otherwise start empty + show spinner.
    const cachedPulls = getCached<PullRequestDto[]>(repoPullsKey(owner, repo));
    const cachedOpenIssues = getCached<IssueDto[]>(repoIssuesKey(owner, repo, 'open'));
    const cachedClosedIssues = getCached<IssueDto[]>(repoIssuesKey(owner, repo, 'closed'));
    setPulls(cachedPulls ?? []);
    setOpenIssues(cachedOpenIssues ?? null);
    setClosedIssues(cachedClosedIssues ?? null);
    setIssueState('open');
    setIssueSearch('');
    setIssueLoadError(null);
    setSelectedIssueNumber(null);
    // Try to auto-select from the cache synchronously — if the user
    // visited this repo before, the deep-link should land on the PR
    // immediately without waiting for the fetch.
    const seedSelected = initialPrNumber != null && cachedPulls
      ? cachedPulls.find(p => p.number === initialPrNumber) ?? null
      : null;
    setSelectedPr(seedSelected);
    setReviewingPr(null);
    setDiffViewerPr(null);
    setDiffViewerCommitSha(null);
    setLoading(cachedPulls === undefined);
    setError(null);
    // Sidebar default behaviour:
    //   - Landed on a repo with no PR selected ⇒ expand (user wants
    //     to scan the list).
    //   - Landed on a repo with a PR pre-selected (deep-link or cache
    //     hit) ⇒ fold so the detail pane gets the room.
    // Manual clicks on a PR card route through selectPr below, which
    // folds explicitly. Manual chevron clicks still win over the
    // default — see expandSidebar / collapseSidebar below.
    const shouldFold = initialPrNumber != null || seedSelected !== null;
    setSidebarCollapsed(shouldFold);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, shouldFold ? '1' : '0');
    // The deep-link request is invalidated by an owner/repo/prNumber
    // change — if the user navigates away while a fetch is in flight,
    // the cancel-flag stops the late setState from clobbering whatever
    // the new render set up.
    let cancelled = false;

    // Whether the deep-link selection is still resolving. We flip this
    // on whenever we don't already have the PR in cache, then off once
    // either the list-fetch path or the single-PR path lands.
    const needsDeepLink = initialPrNumber != null && !seedSelected;
    setDeepLinkPending(needsDeepLink);

    // Parallel single-PR fetch: instead of waiting for getRepoPulls to
    // resolve and *then* discovering the PR isn't in the (50-row)
    // response, we kick off the single-PR fetch immediately. Whichever
    // path lands the PR first wins; setSelectedPr's prev?? guard makes
    // the second resolution a no-op.
    if (needsDeepLink) {
      void window.bridge.getRepoPull(owner, repo, initialPrNumber)
        .then(single => {
          if (cancelled) return;
          setSelectedPr(prev => prev ?? single);
          setPulls(prev => prev.some(p => p.id === single.id) ? prev : [single, ...prev]);
          setDeepLinkPending(false);
        })
        .catch(() => {
          // Best-effort. If the single-PR fetch fails (deleted, 403,
          // etc.) we still let the list-fetch path try its luck below;
          // pending only clears for-real once both paths have run.
        });
    }

    Promise.allSettled([
      window.bridge.getRepoPulls(owner, repo),
      window.bridge.getRepoIssues(owner, repo, 'open'),
    ]).then(([pullsResult, issuesResult]) => {
      if (cancelled) return;
      if (pullsResult.status === 'fulfilled') {
        const fresh = pullsResult.value;
        setPulls(prev => {
          // Preserve any single-PR row spliced in by the parallel
          // fetch above — without this we'd overwrite it with the
          // 50-row list and lose the deep-linked PR from the sidebar.
          const dedupe = new Map(fresh.map(p => [p.id, p]));
          for (const p of prev) {
            if (!dedupe.has(p.id)) dedupe.set(p.id, p);
          }
          return [...dedupe.values()];
        });
        setCached(repoPullsKey(owner, repo), fresh);
        const decision = decideDeepLinkSelection(fresh, initialPrNumber);
        if (decision.kind === 'select') {
          setSelectedPr(prev => prev ?? decision.pr);
          setDeepLinkPending(false);
        }
      } else {
        setError(pullsResult.reason instanceof Error ? pullsResult.reason.message : 'Failed to load PRs');
      }
      if (issuesResult.status === 'fulfilled') {
        setOpenIssues(issuesResult.value);
        setCached(repoIssuesKey(owner, repo, 'open'), issuesResult.value);
      } else {
        setIssueLoadError(issuesResult.reason instanceof Error ? issuesResult.reason.message : 'Failed to load issues');
      }
      setLoading(false);
      // If we never resolved the deep-link (PR genuinely not found via
      // either path), drop the loading state so the user sees the
      // HelpPanel rather than an indefinite spinner.
      setDeepLinkPending(false);
    });
    return () => { cancelled = true; };
  }, [owner, repo, initialPrNumber]);

  // Email "↗ ByteQuay" deep-link target: when we land with a commit SHA
  // alongside a PR number, auto-open the DiffViewer at that commit once
  // the PR resolves (synchronous cache seed or async deep-link fetch).
  // Guarded by a ref so manually closing the DiffViewer doesn't bounce
  // the user right back into it.
  const diffOpenedRef = useRef<string | null>(null);
  useEffect(() => {
    diffOpenedRef.current = null;
  }, [initialPrNumber, initialDiffCommitSha, owner, repo]);
  useEffect(() => {
    if (!initialDiffCommitSha) return;
    if (initialPrNumber == null) return;
    if (!selectedPr || selectedPr.number !== initialPrNumber) return;
    const key = `${owner}/${repo}#${initialPrNumber}@${initialDiffCommitSha}`;
    if (diffOpenedRef.current === key) return;
    diffOpenedRef.current = key;
    setDiffViewerCommitSha(initialDiffCommitSha);
    setDiffViewerPr(selectedPr);
  }, [selectedPr, initialPrNumber, initialDiffCommitSha, owner, repo]);

  // Lazy-load the closed-issues bucket on first toggle. Skips re-fetch
  // when we already have a cached result (per-repo, per-state cache).
  useEffect(() => {
    if (issueState !== 'closed') return;
    if (closedIssues !== null) return;
    if (issueLoading) return;
    let cancelled = false;
    setIssueLoading(true);
    setIssueLoadError(null);
    window.bridge.getRepoIssues(owner, repo, 'closed')
      .then(rows => {
        if (cancelled) return;
        setClosedIssues(rows);
        setCached(repoIssuesKey(owner, repo, 'closed'), rows);
      })
      .catch(e => {
        if (!cancelled) setIssueLoadError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => { if (!cancelled) setIssueLoading(false); });
    return () => { cancelled = true; };
  }, [issueState, owner, repo, closedIssues, issueLoading]);

  const reloadPulls = async () => {
    try {
      const fresh = await window.bridge.getRepoPulls(owner, repo);
      setPulls(fresh);
      setCached(repoPullsKey(owner, repo), fresh);
    } catch { /* best-effort */ }
  };

  const selectPr = (pr: PullRequestDto | null) => {
    setSelectedPr(pr);
    setReviewingPr(null);
    setDiffViewerPr(null);
    setDiffViewerCommitSha(null);
    // Auto-fold the PR list when picking a card so the detail pane
    // gets the full pane width. Clearing the selection (pr === null)
    // re-expands so the user can pick another. Manual chevron clicks
    // still take precedence afterwards.
    const fold = pr !== null;
    setSidebarCollapsed(fold);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, fold ? '1' : '0');
  };

  const selectIssue = (number: number | null) => {
    setSelectedIssueNumber(number);
    // Mirror selectPr's sidebar behaviour — folding when picking a row
    // gives the right pane the room it needs for the issue body +
    // comments + right rail.
    const fold = number !== null;
    setSidebarCollapsed(fold);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, fold ? '1' : '0');
  };

  const handleBackFromReview = () => {
    setReviewingPr(null);
    void window.bridge.triggerSync().catch(() => { /* best-effort */ });
    void reloadPulls();
  };

  const login = currentUser?.login ?? null;

  const { inboxPrs, snoozedPrs, handledPrs } = useMemo(() => {
    const split = splitByBucket(pulls);
    split.inbox.sort(byUpdatedAtDesc);
    return {
      inboxPrs: split.inbox,
      snoozedPrs: sortSnoozed(split.snoozed),
      handledPrs: sortHandled(split.handled),
    };
  }, [pulls]);

  const { myPrs, forReview, otherPrs } = useMemo(() => {
    if (login === null) {
      return { myPrs: [] as PullRequestDto[], forReview: [] as PullRequestDto[], otherPrs: inboxPrs };
    }
    const mine: PullRequestDto[] = [];
    const rev: PullRequestDto[] = [];
    const other: PullRequestDto[] = [];
    for (const pr of inboxPrs) {
      if (pr.author === login) mine.push(pr);
      else if (pr.requestedReviewers.includes(login)) rev.push(pr);
      else other.push(pr);
    }
    return { myPrs: mine, forReview: rev, otherPrs: other };
  }, [inboxPrs, login]);

  const scopedPrs = scope === 'mine' ? myPrs : scope === 'review' ? forReview : otherPrs;
  const scopeLabel = scope === 'mine' ? 'your PRs' : scope === 'review' ? 'PRs for your review' : 'other PRs';

  // Auto-select the first "My PR" once the list resolves, so landing
  // on this page from the Repository home's Pull Requests tab shows a
  // useful preview instead of an empty right pane. Only on the Pulls
  // tab — landing on Issues shouldn't quietly preload a PR in the
  // right pane. Skipped when the user deep-linked to a specific PR
  // (initialPrNumber) or already picked one. Tracked per-repo so
  // clearing the selection later doesn't keep re-picking the same PR
  // behind the user's back.
  const autoSelectedRepoRef = useRef<string | null>(null);
  useEffect(() => {
    const key = `${owner}/${repo}`;
    if (autoSelectedRepoRef.current === key) return;
    if (tab !== 'pulls') return;
    if (initialPrNumber != null) return;
    if (selectedPr != null) return;
    if (loading) return;
    if (myPrs.length === 0) return;
    autoSelectedRepoRef.current = key;
    setSelectedPr(myPrs[0]);
  }, [owner, repo, tab, initialPrNumber, selectedPr, loading, myPrs]);

  const handledGroups = useMemo(() => groupHandledByTime(handledPrs), [handledPrs]);

  const fullRepo = `${owner}/${repo}`;

  const handleMarkHandled = async (prId: number) => {
    const patch = markHandledPatch('MANUAL');
    setPulls(prev => patchPr(prev, prId, patch));
    syncCachesAfterPrChange(prId, patch, fullRepo);
    try {
      await window.bridge.markPrHandled(prId, 'MANUAL');
    } catch (e) {
      console.warn('markPrHandled failed; rolling back', e);
      const rollback = reopenPatch();
      setPulls(prev => patchPr(prev, prId, rollback));
      syncCachesAfterPrChange(prId, rollback, fullRepo);
    }
  };

  const handleApprove = async (prId: number, prRepo: string, number: number) => {
    const patch = markHandledPatch('APPROVED');
    setPulls(prev => patchPr(prev, prId, patch));
    syncCachesAfterPrChange(prId, patch, prRepo);
    try {
      await window.bridge.approvePr(prId, prRepo, number);
    } catch (e) {
      const rollback = reopenPatch();
      setPulls(prev => patchPr(prev, prId, rollback));
      syncCachesAfterPrChange(prId, rollback, prRepo);
      throw e;
    }
  };

  const handleMerge = async (prId: number, prRepo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => {
    const previous = pulls.find(p => p.id === prId);
    const previousState = previous?.state ?? null;
    const previousMergedAt = previous?.mergedAt ?? null;
    const patch = mergedPatch();
    setPulls(prev => patchPr(prev, prId, patch));
    // selectedPr is the snapshot the preview pane reads as `pr`; it lives
    // in its own useState and doesn't auto-track `pulls`. If we don't
    // patch it too, the OPEN pill and the merge bar (gated on
    // !pr.mergedAt) won't update after a successful merge.
    setSelectedPr(prev => (prev && prev.id === prId ? { ...prev, ...patch } : prev));
    syncCachesAfterPrChange(prId, patch, prRepo);
    try {
      const result = await window.bridge.mergePr(prId, prRepo, number, strategy);
      if (result?.queued) {
        // Queue accepted the PR but the merge hasn't happened yet —
        // undo the optimistic "merged" patch so the row reflects
        // reality. MergeBar reads queued state from the same result.
        const rollback = unmergedPatch(previousState, previousMergedAt);
        setPulls(prev => patchPr(prev, prId, rollback));
        setSelectedPr(prev => (prev && prev.id === prId ? { ...prev, ...rollback } : prev));
        syncCachesAfterPrChange(prId, rollback, prRepo);
      }
      return result;
    } catch (e) {
      const rollback = unmergedPatch(previousState, previousMergedAt);
      setPulls(prev => patchPr(prev, prId, rollback));
      setSelectedPr(prev => (prev && prev.id === prId ? { ...prev, ...rollback } : prev));
      syncCachesAfterPrChange(prId, rollback, prRepo);
      throw e;
    }
  };

  const handleReopen = async (prId: number) => {
    const previous = pulls.find(p => p.id === prId);
    setPulls(prev => patchPr(prev, prId, reopenPatch()));
    syncCachesAfterPrChange(prId, reopenPatch(), fullRepo);
    try {
      await window.bridge.reopenPr(prId);
    } catch (e) {
      console.warn('reopenPr failed; rolling back', e);
      if (previous) {
        const rollback = { reviewedAt: previous.reviewedAt, handledAction: previous.handledAction };
        setPulls(prev => patchPr(prev, prId, rollback));
        syncCachesAfterPrChange(prId, rollback, fullRepo);
      }
    }
  };

  // Snooze plumbing — same shape as PullRequestList's. Snoozed PRs
  // leave the inbox bucket via splitByBucket; the Snoozed tab renders
  // the SnoozedList component.
  const handleSnooze = async (prId: number, untilIso: string) => {
    const previous = pulls.find(p => p.id === prId);
    const patch: Partial<PullRequestDto> = { snoozedUntil: untilIso, snoozeWakeReason: null };
    setPulls(prev => patchPr(prev, prId, patch));
    syncCachesAfterPrChange(prId, patch, fullRepo);
    try {
      await window.bridge.snoozePr(prId, untilIso);
    } catch (e) {
      console.warn('snoozePr failed; rolling back', e);
      if (previous) {
        const rollback: Partial<PullRequestDto> = { snoozedUntil: previous.snoozedUntil, snoozeWakeReason: previous.snoozeWakeReason };
        setPulls(prev => patchPr(prev, prId, rollback));
        syncCachesAfterPrChange(prId, rollback, fullRepo);
      }
    }
  };

  const handleUnsnooze = async (prId: number) => {
    const previous = pulls.find(p => p.id === prId);
    const patch: Partial<PullRequestDto> = { snoozedUntil: null, snoozeWakeReason: null };
    setPulls(prev => patchPr(prev, prId, patch));
    syncCachesAfterPrChange(prId, patch, fullRepo);
    try {
      await window.bridge.unsnoozePr(prId);
    } catch (e) {
      console.warn('unsnoozePr failed; rolling back', e);
      if (previous) {
        const rollback: Partial<PullRequestDto> = { snoozedUntil: previous.snoozedUntil, snoozeWakeReason: previous.snoozeWakeReason };
        setPulls(prev => patchPr(prev, prId, rollback));
        syncCachesAfterPrChange(prId, rollback, fullRepo);
      }
    }
  };

  const selectedId = selectedPr?.id ?? null;

  const expandSidebar = () => {
    setSidebarCollapsed(false);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, '0');
  };
  const collapseSidebar = () => {
    setSidebarCollapsed(true);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, '1');
  };

  return (
    <div className="v2-page" ref={pageRef}>
      {sidebarCollapsed ? (
        <aside className="v2-sidebar v2-sidebar--collapsed" style={{ width: SIDEBAR_RAIL_WIDTH }}>
          <button
            type="button"
            className="v2-sidebar__rail-toggle"
            onClick={expandSidebar}
            title="Expand PR list"
          >
            ▶
          </button>
          <div className="v2-sidebar__rail-label" aria-hidden="true">{owner}/{repo}</div>
        </aside>
      ) : (
      <aside className="v2-sidebar" style={{ width: sidebarWidth }}>
        <button
          type="button"
          className="v2-sidebar__collapse-btn"
          onClick={collapseSidebar}
          title="Collapse PR list to a rail"
        >
          ◀
        </button>
        <div className="v2-sidebar__tabs">
          <button className={`v2-tab${tab === 'pulls' ? ' v2-tab--active' : ''}`} onClick={() => setTab('pulls')}>
            Pull Requests <span className="v2-tab__count">{pulls.length}</span>
          </button>
          <button className={`v2-tab${tab === 'issues' ? ' v2-tab--active' : ''}`} onClick={() => setTab('issues')}>
            Issues
            {openIssues != null && (
              <span className="v2-tab__count">{openIssues.length}</span>
            )}
          </button>
        </div>

        {tab === 'pulls' && (
          <>
            <div className="v2-subtabs">
              <button
                className={`v2-subtab${bucket === 'inbox' ? ' v2-subtab--active' : ''}`}
                onClick={() => { setBucket('inbox'); selectPr(null); }}
              >
                Inbox
              </button>
              <button
                className={`v2-subtab${bucket === 'snoozed' ? ' v2-subtab--active' : ''}`}
                onClick={() => { setBucket('snoozed'); selectPr(null); }}
                title="PRs you've parked until a later time."
              >
                Snoozed{snoozedPrs.length > 0 && (
                  <span className="v2-subtab__count">{snoozedPrs.length}</span>
                )}
              </button>
              <button
                className={`v2-subtab${bucket === 'handled' ? ' v2-subtab--active' : ''}`}
                onClick={() => { setBucket('handled'); selectPr(null); }}
              >
                Handled
              </button>
            </div>
            {/* Scope tab bar — replaces the old MY PRS / FOR MY REVIEW /
                OTHER accordion sections per docs/design/pr-dashboard/repo-prs.png.
                Counts stay because they help the user pick where to look. */}
            {bucket === 'inbox' && (
              <>
                <div className="v2-scopebar" role="tablist" aria-label="PR scope">
                  <button
                    role="tab"
                    aria-selected={scope === 'mine'}
                    className={`v2-scopebar__tab${scope === 'mine' ? ' v2-scopebar__tab--active' : ''}`}
                    onClick={() => { setScope('mine'); selectPr(null); }}
                  >
                    My PRs <span className="v2-scopebar__count">{myPrs.length}</span>
                  </button>
                  <button
                    role="tab"
                    aria-selected={scope === 'review'}
                    className={`v2-scopebar__tab${scope === 'review' ? ' v2-scopebar__tab--active' : ''}`}
                    onClick={() => { setScope('review'); selectPr(null); }}
                  >
                    For my review <span className="v2-scopebar__count">{forReview.length}</span>
                  </button>
                  <button
                    role="tab"
                    aria-selected={scope === 'other'}
                    className={`v2-scopebar__tab${scope === 'other' ? ' v2-scopebar__tab--active' : ''}`}
                    onClick={() => { setScope('other'); selectPr(null); }}
                  >
                    Other <span className="v2-scopebar__count">{otherPrs.length}</span>
                  </button>
                </div>
                <div className="v2-scopebar__meta">
                  {scopedPrs.length} {scopeLabel} in this repo · sort: recent
                </div>
              </>
            )}
          </>
        )}

        {loading && (
          <div className="repo-loading">
            <LogoLoading size={56} />
          </div>
        )}
        {error && <div className="repo-error">{error}</div>}

        {!loading && tab === 'pulls' && bucket === 'inbox' && (
          <div className="v2-list">
            {inboxPrs.length === 0 ? (
              <div className="v2-empty">Inbox zero — nothing needs your attention.</div>
            ) : scopedPrs.length === 0 ? (
              <div className="v2-empty">
                {scope === 'mine'
                  ? `No PRs of yours in ${owner}/${repo} right now.`
                  : scope === 'review'
                    ? `No PRs in ${owner}/${repo} are waiting for your review.`
                    : `No other PRs in ${owner}/${repo}'s inbox.`}
              </div>
            ) : (
              scopedPrs.map(pr => (
                <InboxCard
                  key={pr.id}
                  pr={pr}
                  selected={selectedId === pr.id}
                  onSelect={() => selectPr(pr)}
                  onHandle={() => handleMarkHandled(pr.id)}
                  onSnooze={(untilIso) => handleSnooze(pr.id, untilIso)}
                />
              ))
            )}
          </div>
        )}

        {!loading && tab === 'pulls' && bucket === 'snoozed' && (
          <div className="v2-list">
            <SnoozedList
              prs={snoozedPrs}
              selectedId={selectedId}
              onSelect={selectPr}
              onUnsnooze={handleUnsnooze}
              onEditSnooze={handleSnooze}
            />
          </div>
        )}

        {!loading && tab === 'pulls' && bucket === 'handled' && (
          <div className="v2-list">
            {handledPrs.length === 0 ? (
              <div className="v2-empty">No handled PRs yet.</div>
            ) : (
              <HandledTimeline groups={handledGroups} selectedId={selectedId} onSelect={selectPr} onReopen={handleReopen} />
            )}
          </div>
        )}

        {!loading && tab === 'issues' && (
          <IssueListPane
            state={issueState}
            onChangeState={setIssueState}
            search={issueSearch}
            onChangeSearch={setIssueSearch}
            openIssues={openIssues}
            closedIssues={closedIssues}
            loading={issueLoading}
            error={issueLoadError}
            onSelectIssue={selectIssue}
            selectedIssueNumber={selectedIssueNumber}
          />
        )}
      </aside>
      )}

      {!sidebarCollapsed && (
        <ResizeHandle onResize={handleSidebarResize} ariaLabel="Resize pull-request list" />
      )}

      <main className="v2-main v2-main--screen">
        {reviewingPr ? (
          <ReviewScreen
            pr={reviewingPr}
            onBack={handleBackFromReview}
          />
        ) : diffViewerPr ? (
          <DiffViewerScreen
            pr={diffViewerPr}
            onBack={() => { setDiffViewerPr(null); setDiffViewerCommitSha(null); }}
            onApprove={handleApprove}
            initialCommitSha={diffViewerCommitSha}
          />
        ) : tab === 'issues' && selectedIssueNumber != null ? (
          <IssueDetailScreen
            owner={owner}
            repo={repo}
            number={selectedIssueNumber}
            embedded
          />
        ) : selectedPr ? (
          <PullRequestPreview
            pr={selectedPr}
            onOpenReview={() => setReviewingPr(selectedPr)}
            onInspectDiffs={(sha) => {
              // Only strings; reject e.g. a forwarded MouseEvent.
              setDiffViewerCommitSha(typeof sha === 'string' ? sha : null);
              setDiffViewerPr(selectedPr);
            }}
            onMarkHandled={handleMarkHandled}
            onMerge={handleMerge}
            onOpenLocalBranch={onOpenLocalBranch}
            onOpenThread={onOpenThread}
            onStartReview={onStartReview}
          />
        ) : deepLinkPending && initialPrNumber != null ? (
          <DeepLinkLoading owner={owner} repo={repo} number={initialPrNumber} />
        ) : (
          <NoSelectionPlaceholder tab={tab} repo={`${owner}/${repo}`} />
        )}
      </main>
    </div>
  );
}

/** Right-pane placeholder shown when no PR/issue is selected. The
 *  prior surface (RepoOverviewPanel) duplicated metadata that already
 *  lives on the unified Repository home, so the right pane stays empty
 *  here until the user picks a row from the sidebar. */
/** Issues sidebar pane — Open/Closed tabs + a search box on top of the
 *  row list. Closed bucket loads lazily on first toggle (see the
 *  effect in RepoDetailPage); the empty/loading states cover both
 *  the "haven't fetched yet" and "GitHub said zero" cases. */
function IssueListPane({
  state,
  onChangeState,
  search,
  onChangeSearch,
  openIssues,
  closedIssues,
  loading,
  error,
  onSelectIssue,
  selectedIssueNumber,
}: {
  state: IssueState;
  onChangeState: (next: IssueState) => void;
  search: string;
  onChangeSearch: (next: string) => void;
  openIssues: IssueDto[] | null;
  closedIssues: IssueDto[] | null;
  loading: boolean;
  error: string | null;
  onSelectIssue?: (number: number) => void;
  /** Highlights the currently-previewed row. Mirrors selectedId on
   *  the PR list so the user keeps their place after click. */
  selectedIssueNumber?: number | null;
}) {
  const issues = state === 'open' ? openIssues : closedIssues;
  const filtered = useMemo(() => {
    if (!issues) return null;
    const needle = search.trim().toLowerCase();
    if (!needle) return issues;
    return issues.filter(i => i.title.toLowerCase().includes(needle));
  }, [issues, search]);

  return (
    <>
      <div className="issue-pane__filters">
        <div className="issue-pane__state-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={state === 'open'}
            className={`issue-pane__state-tab${state === 'open' ? ' issue-pane__state-tab--active' : ''}`}
            onClick={() => onChangeState('open')}
          >
            <span className="issue-row__status issue-row__status--open" aria-hidden="true" />
            Open
            {openIssues != null && (
              <span className="issue-pane__state-count">{openIssues.length}</span>
            )}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={state === 'closed'}
            className={`issue-pane__state-tab${state === 'closed' ? ' issue-pane__state-tab--active' : ''}`}
            onClick={() => onChangeState('closed')}
          >
            <span className="issue-row__status issue-row__status--closed" aria-hidden="true" />
            Closed
            {closedIssues != null && (
              <span className="issue-pane__state-count">{closedIssues.length}</span>
            )}
          </button>
        </div>
        <input
          type="search"
          className="issue-pane__search"
          placeholder="Search issues…"
          value={search}
          onChange={e => onChangeSearch(e.target.value)}
        />
      </div>
      {error && <div className="repo-error">{error}</div>}
      {loading && filtered === null && (
        <div className="repo-loading">
          <LogoLoading size={48} />
        </div>
      )}
      <ul className="issue-list">
        {filtered != null && filtered.length === 0 && (
          <li className="v2-empty">
            {search.trim()
              ? 'No issues match that search.'
              : state === 'open' ? 'No open issues' : 'No closed issues'}
          </li>
        )}
        {filtered?.map(issue => (
          <IssueRow
            key={issue.id}
            issue={issue}
            onSelect={onSelectIssue}
            selected={issue.number === selectedIssueNumber}
          />
        ))}
      </ul>
    </>
  );
}

/** One row in the redesigned Issues list. Mirrors the GitHub-style
 *  layout from docs/mockups/design/repository/repository-issues.png:
 *  status circle + title with inline label chips + a small meta line.
 *  Click still routes to github.com — the in-app detail page is I3. */
function IssueRow({
  issue,
  onSelect,
  selected,
}: {
  issue: IssueDto;
  onSelect?: (number: number) => void;
  selected?: boolean;
}) {
  const isClosed = issue.state === 'closed';
  const statusLabel = isClosed ? 'Closed' : 'Open';
  // Prefer the in-pane preview when the parent wires onSelect (the
  // RepoDetailPage flow). Falls back to opening github.com so the
  // row stays useful for any caller that hasn't plumbed selection.
  const open = (): void => {
    if (onSelect) {
      onSelect(issue.number);
    }
    else {
      void window.bridge.openExternal(issue.htmlUrl);
    }
  };
  return (
    <li
      className={`issue-row${selected ? ' issue-row--selected' : ''}`}
      role="button"
      tabIndex={0}
      onClick={open}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } }}
      title={onSelect ? 'Open issue' : 'Open on GitHub'}
    >
      <span
        className={`issue-row__status issue-row__status--${isClosed ? 'closed' : 'open'}`}
        aria-label={statusLabel}
        title={statusLabel}
      />
      <div className="issue-row__main">
        <div className="issue-row__title-line">
          <span className="issue-row__title">{issue.title}</span>
          {issue.labels.slice(0, 4).map(label => (
            <span key={label} className="issue-row__label">{label}</span>
          ))}
          {issue.labels.length > 4 && (
            <span className="issue-row__label issue-row__label--more">+{issue.labels.length - 4}</span>
          )}
        </div>
        <div className="issue-row__meta">
          <span className="issue-row__num">#{issue.number}</span>
          {issue.author && <> · opened by <span className="issue-row__author">@{issue.author}</span></>}
          {issue.updatedAt && <> · last activity {formatRelative(issue.updatedAt)}</>}
        </div>
      </div>
      {issue.author && (
        <Avatar login={issue.author} size={20} className="issue-row__avatar" />
      )}
    </li>
  );
}

function NoSelectionPlaceholder({ tab, repo }: { tab: Tab; repo: string }) {
  return (
    <div className="v2-help v2-help--idle">
      <h1 className="v2-help__title">{repo}</h1>
      <p className="v2-help__subtitle">
        {tab === 'pulls'
          ? 'Pick a pull request from the list to preview it here.'
          : 'Pick an issue to open it on GitHub.'}
      </p>
    </div>
  );
}

export default RepoDetailPage;
