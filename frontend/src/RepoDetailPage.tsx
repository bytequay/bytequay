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
import PullRequestPreview from './PullRequestPreview';
import ReviewScreen from './ReviewScreen';
import DiffViewerScreen from './DiffViewerScreen';
import ResizeHandle from './ResizeHandle';
import Avatar from './Avatar';
import LogoLoading from './LogoLoading';
import { getCached, setCached } from './dataCache';
import { decideDeepLinkSelection } from './repoDeepLink';

const repoPullsKey = (owner: string, repo: string) => `repo:${owner}/${repo}:pulls`;
const repoIssuesKey = (owner: string, repo: string) => `repo:${owner}/${repo}:issues`;

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
  /** Reverse nav from a PR back to its head branch's local-repo
   *  Commits tab. PullRequestPreview surfaces a button next to the
   *  head ref that calls this. App-level so the nav target lines up
   *  with the existing local-repo route. */
  onOpenLocalBranch?: (owner: string, repo: string, branch: string) => void;
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

function RepoDetailPage({ owner, repo, initialPrNumber, onOpenLocalBranch }: Props) {
  const [tab, setTab] = useState<Tab>('pulls');
  const [bucket, setBucket] = useState<Bucket>('inbox');
  const [scope, setScope] = useState<Scope>('mine');
  // Seed from the cache keyed by owner/repo so revisiting the same repo is
  // instant. Different repo → cache miss → we fall back to []/spinner.
  const [pulls, setPulls] = useState<PullRequestDto[]>(
    () => getCached<PullRequestDto[]>(repoPullsKey(owner, repo)) ?? [],
  );
  const [issues, setIssues] = useState<IssueDto[]>(
    () => getCached<IssueDto[]>(repoIssuesKey(owner, repo)) ?? [],
  );
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
    const cachedIssues = getCached<IssueDto[]>(repoIssuesKey(owner, repo));
    setPulls(cachedPulls ?? []);
    setIssues(cachedIssues ?? []);
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
      window.bridge.getRepoIssues(owner, repo),
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
        setIssues(issuesResult.value);
        setCached(repoIssuesKey(owner, repo), issuesResult.value);
      }
      setLoading(false);
      // If we never resolved the deep-link (PR genuinely not found via
      // either path), drop the loading state so the user sees the
      // HelpPanel rather than an indefinite spinner.
      setDeepLinkPending(false);
    });
    return () => { cancelled = true; };
  }, [owner, repo, initialPrNumber]);

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
      await window.bridge.mergePr(prId, prRepo, number, strategy);
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
            Issues <span className="v2-tab__count">{issues.length}</span>
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
          <div className="v2-list">
            {issues.length === 0 ? (
              <div className="v2-empty">No open issues</div>
            ) : issues.map(issue => (
              <div
                key={issue.id}
                className="v2-card v2-card--issue"
                role="button"
                tabIndex={0}
                onClick={() => void window.bridge.openExternal(issue.htmlUrl)}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') void window.bridge.openExternal(issue.htmlUrl); }}
                title="Open on GitHub"
              >
                <div className="v2-card__body">
                  <div className="v2-card__row">
                    <span className="v2-card__number">#{issue.number}</span>
                    <span className="v2-card__ts">· {formatRelative(issue.updatedAt)}</span>
                  </div>
                  <div className="v2-card__title">{issue.title}</div>
                  {issue.labels.length > 0 && (
                    <div className="v2-card__labels">
                      {issue.labels.slice(0, 4).map(label => (
                        <span key={label} className="v2-pill v2-pill--label">{label}</span>
                      ))}
                      {issue.labels.length > 4 && (
                        <span className="v2-pill v2-pill--label">+{issue.labels.length - 4}</span>
                      )}
                    </div>
                  )}
                  <div className="v2-card__meta">
                    {issue.author && (
                      <>
                        <Avatar login={issue.author} size={14} className="avatar--repo-small" />
                        <span>{issue.author}</span>
                      </>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
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
