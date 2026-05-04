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
  bucketize,
  formatRelative,
  groupHandledByTime,
  markHandledPatch,
  mergedPatch,
  patchPr,
  syncCachesAfterPrChange,
  reopenPatch,
  sortHandled,
  unmergedPatch,
} from './prBuckets';
import { HandledTimeline, InboxGroup } from './PrBucketViews';

type Tab = 'pulls' | 'issues';
type Bucket = 'inbox' | 'handled';

type Props = {
  owner: string;
  repo: string;
  /** When set, the page tries to auto-select the matching PR after the
   *  initial pulls fetch lands. Used by deep-links from the home-page
   *  activity feed (clicking #1234 jumps straight to the PR detail). */
  initialPrNumber?: number;
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

function HelpPanel({ bucket, inboxCount, handledCount }: { bucket: Bucket; inboxCount: number; handledCount: number }) {
  return (
    <div className="v2-help">
      <h1 className="v2-help__title">{bucket === 'inbox' ? 'Inbox' : 'Handled'}</h1>
      <p className="v2-help__subtitle">
        {bucket === 'inbox'
          ? `${inboxCount} PR${inboxCount === 1 ? '' : 's'} need your attention. Hover any card to reveal the ✓ Handled action, or click a card to open it.`
          : `${handledCount} PR${handledCount === 1 ? "'s" : "s you've"} dealt with, newest first. Hover any card to ↗ Reopen it back into your Inbox.`}
      </p>

      <section className="v2-help__card">
        <h3 className="v2-help__card-title">How a PR gets marked as Handled</h3>
        <p>
          <strong>Automatically</strong>, when you leave a review — Approve, Request changes, or Comment. Also automatically when your own PR is merged or closed.
        </p>
        <p>
          <strong>Manually</strong>, by clicking <span className="inline-pill">✓ Handled</span> on any card (hover to reveal) or from the detail view. Useful for PRs you're waiting on but don't want cluttering your queue.
        </p>
      </section>

      <section className="v2-help__card">
        <h3 className="v2-help__card-title">What brings it back to the Inbox</h3>
        <p>
          A new commit, a new review request, or an <code>@mention</code>. When that happens, the card returns with an orange dot and an <span className="inline-pill inline-pill--resurfaced">Updated since your review</span> badge — so you can see at a glance that it isn't a duplicate of something you already did.
        </p>
        <p className="v2-help__note">
          Approved and merged PRs stay in Handled even if they receive new activity.
        </p>
      </section>

      <section className="v2-help__card">
        <h3 className="v2-help__card-title">Legend on the cards</h3>
        <ul className="v2-help__legend">
          <li><span className="legend-dot legend-dot--blue" /> <strong>Blue dot</strong> — unread: you haven't opened this PR yet</li>
          <li><span className="legend-dot legend-dot--orange" /> <strong>Orange dot</strong> — re-surfaced: handled before, updated since</li>
          <li><span className="legend-dot legend-dot--seen" /> <strong>Hollow ring</strong> — opened but not yet handled</li>
        </ul>
      </section>
    </div>
  );
}

function RepoDetailPage({ owner, repo, initialPrNumber }: Props) {
  const [tab, setTab] = useState<Tab>('pulls');
  const [bucket, setBucket] = useState<Bucket>('inbox');
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

  const { inboxPrs, handledPrs } = useMemo(() => {
    const inbox: PullRequestDto[] = [];
    const handled: PullRequestDto[] = [];
    for (const pr of pulls) {
      if (bucketize(pr) === 'inbox') inbox.push(pr);
      else handled.push(pr);
    }
    return { inboxPrs: inbox, handledPrs: sortHandled(handled) };
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
    syncCachesAfterPrChange(prId, patch, prRepo);
    try {
      await window.bridge.mergePr(prId, prRepo, number, strategy);
    } catch (e) {
      const rollback = unmergedPatch(previousState, previousMergedAt);
      setPulls(prev => patchPr(prev, prId, rollback));
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
          <div className="v2-subtabs">
            <button
              className={`v2-subtab${bucket === 'inbox' ? ' v2-subtab--active' : ''}`}
              onClick={() => { setBucket('inbox'); selectPr(null); }}
            >
              Inbox <span className="v2-subtab__count">{inboxPrs.length}</span>
            </button>
            <button
              className={`v2-subtab${bucket === 'handled' ? ' v2-subtab--active' : ''}`}
              onClick={() => { setBucket('handled'); selectPr(null); }}
            >
              Handled <span className="v2-subtab__count">{handledPrs.length}</span>
            </button>
          </div>
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
            ) : (
              <>
                <InboxGroup title="MY PRS" color="blue" prs={myPrs} selectedId={selectedId} onSelect={selectPr} onHandle={handleMarkHandled} />
                <InboxGroup title="FOR MY REVIEW" color="orange" prs={forReview} selectedId={selectedId} onSelect={selectPr} onHandle={handleMarkHandled} />
                <InboxGroup title="OTHER" color="grey" prs={otherPrs} selectedId={selectedId} onSelect={selectPr} onHandle={handleMarkHandled} />
              </>
            )}
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
          />
        ) : deepLinkPending && initialPrNumber != null ? (
          <DeepLinkLoading owner={owner} repo={repo} number={initialPrNumber} />
        ) : (
          <HelpPanel bucket={bucket} inboxCount={inboxPrs.length} handledCount={handledPrs.length} />
        )}
      </main>
    </div>
  );
}

export default RepoDetailPage;
