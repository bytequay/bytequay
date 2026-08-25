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
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';
import type { NavigationMode } from '../navigationHistory';
import type { DiffCommitDto, DiffFileDto, InlineReviewCommentDto, PullRequestDetailDto, PullRequestDetailRefreshDto, PullRequestDto, PullRequestPageDto, PullRequestStatusRefreshDto } from '../types';
import PullChanges, { type CommitRange } from './PullChanges';
import PullOverview, { isQueued, remoteErrorMessage } from './PullOverview';
import PullRowItem, { PullRequestIcon } from './PullRowItem';
import {
  PULL_TABS,
  pullRequestNeedsReview,
  reconcilePostedComment,
  reconcilePullRequestDetail,
  rowsForSearch,
  rowsForTab,
  tabForInitialPullRequest,
  visibleSelectedId,
  type PullRow,
  type PullTab,
} from './model';
import '../css/pulls.css';

type DetailTab = 'overview' | 'changes';

const DETAIL_MIN = 680;
const DETAIL_MAX = 1600;
const DETAIL_DEFAULT = 1120;
const PAGE_SIZE = 50;
const DETAIL_REFRESH_INTERVAL = 6_000;

export function mergePullRequestDetailStatus<T>(current: T, status: PullRequestStatusRefreshDto): T & PullRequestStatusRefreshDto {
  return { ...current, ...status };
}

export function pullRequestDetailRefreshDelay(failure: unknown, now = Date.now()) {
  const match = remoteErrorMessage(failure).match(/GitHub \S+ API rate limit reached; retry after (\S+)/);
  const resetAt = match ? Date.parse(match[1]) : Number.NaN;
  return Number.isNaN(resetAt)
    ? DETAIL_REFRESH_INTERVAL
    : Math.max(DETAIL_REFRESH_INTERVAL, resetAt - now + 1_000);
}

export function returnedFromInAppBrowser(wasOpen: boolean, isOpen: boolean) {
  return wasOpen && !isOpen;
}

export function showBlockingDetailLoading(loading: boolean, hasDetail: boolean) {
  return loading && !hasDetail;
}

export default function PullsScreen({
  initialPr,
  initialTab,
  repositoryScope,
  browserOpen = false,
  onSelectPullRequest,
}: {
  initialPr?: { repo: string; number: number };
  /** Filter tab to open on (from the Home review card). */
  initialTab?: PullTab;
  repositoryScope?: { workspaceId: string; repository: string };
  browserOpen?: boolean;
  onSelectPullRequest: (reference: { repo: string; number: number }, mode: NavigationMode) => void;
}) {
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [tab, setTab] = useState<PullTab>(initialTab ?? 'all');
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [detailTab, setDetailTab] = useState<DetailTab>('overview');
  const [diffs, setDiffs] = useState<DiffFileDto[] | null>(null);
  const [diffCommits, setDiffCommits] = useState<DiffCommitDto[]>([]);
  const [diffRange, setDiffRange] = useState<CommitRange>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [pendingReviewComments, setPendingReviewComments] = useState<InlineReviewCommentDto[]>([]);
  const [paneOpen, setPaneOpen] = useState(true);
  const [detailZoomed, setDetailZoomed] = useState(false);
  const [detailWidth, setDetailWidth] = useState(DETAIL_DEFAULT);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const loadingMoreRequest = useRef(false);
  const loadMoreMarker = useRef<HTMLDivElement | null>(null);
  const openedInitialPr = useRef<string | null>(null);
  const routingInitialPr = useRef<string | null>(null);
  const detailSnapshot = useRef<string | null>(null);
  const selectedPullRequest = useRef<{ id: number; key: string } | null>(null);
  const listRequest = useRef(0);
  const diffRequest = useRef(0);
  const detailRefreshInFlight = useRef<Promise<PullRequestDetailRefreshDto> | null>(null);
  const browserWasOpen = useRef(browserOpen);

  const applyDetail = useCallback((prId: number, value: PullRequestDetailDto, refreshDiff: boolean) => {
    const snapshot = JSON.stringify(value);
    if (detailSnapshot.current === snapshot) return false;
    detailSnapshot.current = snapshot;
    setDetail(value);
    setPrs(current => current.map(pr => pr.id === prId
      ? reconcilePullRequestDetail(pr, value)
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

  const applyDetailRefresh = useCallback((prId: number, value: PullRequestDetailRefreshDto) => {
    if (value.full) {
      applyDetail(prId, value.detail, true);
      return;
    }
    const status = value.status;
    setDetail(current => {
      if (current === null) return current;
      const next = mergePullRequestDetailStatus(current, status);
      detailSnapshot.current = JSON.stringify(next);
      return next;
    });
    setPrs(current => current.map(pr => pr.id === prId
      ? { ...pr, ciStatus: status.ciStatus, mergeable: status.mergeable, mergeableState: status.mergeableState }
      : pr));
  }, [applyDetail]);

  const applyPage = useCallback((result: PullRequestPageDto) => {
    setPrs(result.pullRequests);
    setPage(result.page);
    setHasMore(result.hasMore);
    setSelected(current => result.pullRequests.some(pr => pr.id === current)
      ? current
      : null);
  }, []);

  const refreshList = useCallback(async () => {
    const request = ++listRequest.current;
    setLoading(true);
    setError(null);
    try {
      await window.bridge.syncPrs(repositoryScope?.workspaceId);
      if (request !== listRequest.current) return;
      const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, 0, PAGE_SIZE, query);
      if (request === listRequest.current) applyPage(result);
    }
    catch (e) {
      if (request === listRequest.current) setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      if (request === listRequest.current) setLoading(false);
    }
  }, [applyPage, query, repositoryScope?.workspaceId]);

  useEffect(() => {
    const request = ++listRequest.current;
    setLoading(true);
    setError(null);
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
  }, [applyPage, query, repositoryScope?.workspaceId]);

  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(search.trim()), 250);
    return () => window.clearTimeout(timeout);
  }, [search]);

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
    if (loading) return;
    const initialPrKey = `${initialPr.repo.toLowerCase()}#${initialPr.number}`;
    if (openedInitialPr.current === initialPrKey) return;
    openedInitialPr.current = initialPrKey;
    routingInitialPr.current = initialPrKey;
    const match = prs.find(pr => pr.repo.toLowerCase() === initialPr.repo.toLowerCase()
      && pr.number === initialPr.number);
    if (match) {
      setTab(current => tabForInitialPullRequest(match, current));
      setSelected(match.id);
      return;
    }
    let cancelled = false;
    Promise.all([
      window.bridge.fetchPullRequestDetailByRef(initialPr.repo, initialPr.number),
      window.bridge.getUserProfile().catch((): null => null),
    ]).then(([value, profile]) => {
      if (cancelled) return;
      const remote = remotePullRequest(value, profile?.login);
      setPrs(current => current.some(pr => pr.id === remote.id) ? current : [remote, ...current]);
      applyDetail(remote.id, value, false);
      setTab(current => tabForInitialPullRequest(remote, current));
      setSelected(remote.id);
    }).catch(e => {
      if (!cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { cancelled = true; };
  }, [applyDetail, initialPr, loading, prs]);

  const rows = useMemo(() => query ? rowsForSearch(prs) : rowsForTab(prs, tab), [prs, query, tab]);
  const visibleSelected = visibleSelectedId(rows, selected);
  const selectedPr = prs.find(pr => pr.id === visibleSelected) ?? null;
  const selectedPrId = selectedPr?.id;
  const selectedPrNumber = selectedPr?.number;
  const selectedPrRepo = selectedPr?.repo;
  selectedPullRequest.current = selectedPr === null
    ? null
    : { id: selectedPr.id, key: `${selectedPr.repo.toLowerCase()}#${selectedPr.number}` };

  useEffect(() => {
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
    onSelectPullRequest({ repo: selectedPrRepo, number: selectedPrNumber }, 'replace');
  }, [onSelectPullRequest, selected, selectedPrId, selectedPrNumber, selectedPrRepo]);

  useEffect(() => {
    if (selectedPrId === undefined || selectedPrNumber === undefined || selectedPrRepo === undefined) {
      detailSnapshot.current = null;
      setDetail(null);
      return;
    }
    let cancelled = false;
    let refreshTimer: number | undefined;
    detailSnapshot.current = null;
    setDetail(null);
    setDetailLoading(true);
    setError(null);
    const fetchSelectedDetail = () => selectedPrId > 0
      ? window.bridge.fetchPullRequestDetail(selectedPrId)
      : window.bridge.fetchPullRequestDetailByRef(selectedPrRepo, selectedPrNumber);
    const refreshSelectedDetail = () => selectedPrId > 0
      ? window.bridge.refreshPullRequestDetail(selectedPrId)
      : window.bridge.refreshPullRequestDetailByRef(selectedPrRepo, selectedPrNumber);
    const poll = async (): Promise<void> => {
      let nextDelay = DETAIL_REFRESH_INTERVAL;
      let request: Promise<PullRequestDetailRefreshDto> | null = null;
      try {
        while (detailRefreshInFlight.current !== null) {
          const pending = detailRefreshInFlight.current;
          await pending.then((): undefined => undefined, (): undefined => undefined);
        }
        if (cancelled) return;
        request = refreshSelectedDetail();
        detailRefreshInFlight.current = request;
        const value = await request;
        if (!cancelled) {
          applyDetailRefresh(selectedPrId, value);
          setError(null);
        }
      }
      catch (e) {
        nextDelay = pullRequestDetailRefreshDelay(e);
        if (!cancelled) setError(remoteErrorMessage(e));
      }
      finally {
        if (detailRefreshInFlight.current === request) detailRefreshInFlight.current = null;
        if (!cancelled) refreshTimer = window.setTimeout((): void => { void poll(); }, nextDelay);
      }
    };

    fetchSelectedDetail()
      .then(value => {
        if (!cancelled) applyDetail(selectedPrId, value, false);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) {
          setDetailLoading(false);
          refreshTimer = window.setTimeout((): void => { void poll(); }, 0);
        }
      });
    return () => {
      cancelled = true;
      if (refreshTimer !== undefined) window.clearTimeout(refreshTimer);
    };
  }, [applyDetail, applyDetailRefresh, selectedPrId, selectedPrNumber, selectedPrRepo]);

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
    onSelectPullRequest({ repo: row.repo, number: row.num }, 'push');
    diffRequest.current += 1;
    setSelected(row.id);
    setDetailTab('overview');
    setDiffs(null);
    setDiffCommits([]);
    setDiffRange(null);
    setDiffLoading(false);
    setPendingReviewComments([]);
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
    if (!selectedPr) return;
    const listVersion = listRequest.current;
    setDetailLoading(true);
    try {
      const value = selectedPr.id > 0
        ? await window.bridge.refreshAllPullRequestDetail(selectedPr.id)
        : await window.bridge.refreshAllPullRequestDetailByRef(selectedPr.repo, selectedPr.number);
      applyDetail(selectedPr.id, value, true);
      if (listVersion === listRequest.current) {
        try {
          const result = await window.bridge.fetchPrs(repositoryScope?.workspaceId, 0, PAGE_SIZE, query);
          if (listVersion === listRequest.current) applyPage(result);
        }
        catch (e) {
          if (listVersion === listRequest.current) throw e;
        }
      }
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setDetailLoading(false);
    }
  }, [applyDetail, applyPage, query, repositoryScope?.workspaceId, selectedPr]);

  const applyPostedDetail = useCallback((value: PullRequestDetailDto, kind: 'discussion' | 'review') => {
    const current = selectedPullRequest.current;
    if (current?.key === `${value.repo.toLowerCase()}#${value.number}`) {
      applyDetail(current.id, value, false);
      setPrs(rows => rows.map(pr => pr.id === current.id
        ? reconcilePostedComment(pr, value, kind, new Date().toISOString())
        : pr));
    }
  }, [applyDetail]);

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

  const paneShown = paneOpen && selectedPr !== null;

  return (
    <div className="pulls-page">
      <section className="pulls-list">
        <header className="pulls-list__toolbar">
          {repositoryScope && (
            <span className="pulls-scope" title={`Showing ${repositoryScope.repository}`}>
              {repositoryScope.repository}
            </span>
          )}
          <nav className="pulls-tabs" aria-label="Pull request filters">
          {PULL_TABS.map(item => (
            <button
              key={item.key}
              type="button"
              className={!query && tab === item.key ? 'is-active' : ''}
              aria-pressed={!query && tab === item.key}
              onClick={() => { setTab(item.key); setSearch(''); setQuery(''); }}
            >
              {item.label}
            </button>
          ))}
          </nav>
          <button
            className="pulls-toolbar-button"
            type="button"
            title="Sync pull requests"
            aria-label="Sync pull requests"
            disabled={loading}
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
          <SearchIcon />
          <input
            type="search"
            value={search}
            placeholder="Search pull requests by title or #number"
            aria-label="Search pull requests by title or number"
            onChange={event => setSearch(event.target.value)}
          />
        </div>
        <div className="pulls-list__rows">
          {loading && <div className="hp-loading">Loading…</div>}
          {!loading && error && rows.length === 0 && (
            <div className="hp-empty" role="alert">
              Could not load pull requests{repositoryScope ? ` for ${repositoryScope.repository}` : ''}. {error}
            </div>
          )}
          {!loading && !error && rows.length === 0 && (
            <div className="hp-empty">
              {query
                ? `No pull requests match “${query}”.`
                : `No pull requests${repositoryScope ? ` for ${repositoryScope.repository}` : ''} in this view.`}
            </div>
          )}
          {!loading && rows.map(row => (
            <PullRowItem
              key={row.id}
              row={row}
              wide={!paneShown}
              selected={row.id === visibleSelected}
              onPick={() => pick(row)}
            />
          ))}
          <div ref={loadMoreMarker} aria-hidden="true" />
          {loadingMore && <div className="hp-loading">Loading more…</div>}
        </div>
      </section>

      {paneShown && selectedPr !== null && (
        <section className={`pull-detail${detailZoomed ? ' is-zoomed' : ''}`}
          style={detailZoomed ? undefined : { width: detailWidth }}>
          <div className="pull-detail__resize" role="separator" aria-orientation="vertical"
            aria-label="Resize pull request panes" onMouseDown={resizeDetail} title="Drag to resize" />
          <PullDetailHeader
            pr={selectedPr}
            detail={detail}
            tab={detailTab}
            onTab={setDetailTab}
            onRefresh={refresh}
            pendingComments={pendingReviewComments}
            onPendingCommentsChange={setPendingReviewComments}
            zoomed={detailZoomed}
            onZoom={() => setDetailZoomed(value => !value)}
          />
          {error && <div className="pull-detail__error">{error}</div>}
          {showBlockingDetailLoading(detailLoading, detail !== null) ? (
            <div className="pull-detail__loading">Loading details…</div>
          ) : detail === null ? (
            !error && <div className="pull-detail__loading">Select a pull request.</div>
          ) : (
            <DetailBody
              tab={detailTab}
              pr={selectedPr}
              detail={detail}
              diffs={diffs}
              diffCommits={diffCommits}
              diffRange={diffRange}
              diffLoading={diffLoading}
              pendingComments={pendingReviewComments}
              onCommitRangeChange={changeCommitRange}
              onPendingCommentsChange={setPendingReviewComments}
              contributorsWorkspaceId={repositoryScope?.workspaceId}
              onRefresh={refresh}
              onDiscussionPosted={value => applyPostedDetail(value, 'discussion')}
              onReviewPosted={value => applyPostedDetail(value, 'review')}
            />
          )}
        </section>
      )}
    </div>
  );
}

export function remotePullRequest(detail: PullRequestDetailDto, viewerLogin?: string): PullRequestDto {
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  let hash = 0;
  for (const character of key) hash = ((hash * 31) + character.charCodeAt(0)) | 0;
  const origin = viewerLogin && detail.author?.toLowerCase() === viewerLogin.toLowerCase()
    ? 'AUTHORED'
    : isReviewRequestedForViewer(detail, viewerLogin ?? null) ? 'REVIEW_REQUESTED' : 'OTHER';
  return {
    id: -(Math.abs(hash) || 1),
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
  };
}

export function PullDetailHeader({
  pr,
  detail,
  tab,
  onTab,
  onRefresh,
  pendingComments,
  onPendingCommentsChange,
  zoomed,
  onZoom,
}: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto | null;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  onRefresh: () => Promise<void>;
  pendingComments: InlineReviewCommentDto[];
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  zoomed: boolean;
  onZoom: () => void;
}) {
  const [mergeQueue, setMergeQueue] = useState<boolean | null | undefined>(undefined);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [viewerLogin, setViewerLogin] = useState<string | null>(null);
  const canReview = canSubmitReview(pr, detail);
  const reviewRequested = isReviewRequestedForViewer(detail, viewerLogin);
  const canMerge = canReview
    && detail !== null
    && detail.state?.toLowerCase() === 'open'
    && !detail.draft
    && detail.viewerCanWrite
    && !isQueued(detail.recentActivity);
  const conflicts = hasMergeConflicts(detail);
  const merge = detail ? mergeStatus(detail) : null;
  const mergeModeReady = typeof mergeQueue === 'boolean';
  const mergeBlocked = merge?.state === 'failed' || merge?.state === 'blocked';
  const mergeLoading = mergeQueue === undefined || mergeQueue === null || merge?.state === 'pending';
  const mergeLabel = conflicts
    ? 'Conflicts'
    : mergeQueue === undefined || mergeQueue === null
      ? mergeActionLabel(mergeQueue)
      : merge?.state === 'failed'
        ? 'Checks failed'
        : merge?.state === 'blocked'
          ? 'Merge blocked'
          : merge?.state === 'pending'
            ? 'Checks running'
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
  }, [canMerge, conflicts, detail?.repo, detail?.number, detail?.baseRef]);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getUserProfile()
      .then(profile => { if (!cancelled) setViewerLogin(profile.login); })
      .catch(() => { if (!cancelled) setViewerLogin(null); });
    return () => { cancelled = true; };
  }, []);

  return (
    <header className="pull-detail__header">
      <div className="pull-detail__title-row">
        <h1>{pr.title}</h1>
        <div className="pull-detail__header-actions">
          <button type="button" title={zoomed ? 'Restore pull request details' : 'Zoom pull request details'}
            aria-label={zoomed ? 'Restore pull request details' : 'Zoom pull request details'}
            aria-pressed={zoomed} onClick={onZoom}>
            <ZoomIcon zoomed={zoomed} />
          </button>
          <button type="button" title="Refresh" aria-label="Refresh" onClick={() => { void onRefresh(); }}>
            <RefreshIcon />
          </button>
          <button className="pull-detail__open-github" type="button" onClick={() => { void window.bridge.openInAppBrowser(pr.htmlUrl); }}>
            Open on GitHub <span aria-hidden="true">↗</span>
          </button>
          {canReview && (
            <button className="pull-detail__review-action" type="button" onClick={() => setReviewOpen(true)}>
              Submit review
            </button>
          )}
          {canMerge && (
            <button
              className={`pull-detail__merge-action${conflicts || mergeBlocked ? ' is-conflicts' : mergeLoading ? ' is-loading' : ''}`}
              type="button"
              disabled={!mergeModeReady || conflicts}
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
      {reviewRequested && detail && (
        <ReviewRequestNotice pr={pr} detail={detail} onReview={() => onTab('changes')} />
      )}
      <nav className="pull-detail__tabs" aria-label="Pull request detail">
        <button className={tab === 'overview' ? 'is-active' : ''} type="button" onClick={() => onTab('overview')}>
          <CommentIcon /> Overview
          <span>{pr.commentCount}</span>
        </button>
        <button className={tab === 'changes' ? 'is-active' : ''} type="button" onClick={() => onTab('changes')}>
          <ChangesIcon /> Changes
          <span className="is-addition">+{detail?.additions ?? pr.additions}</span>
          <span className="is-deletion">−{detail?.deletions ?? pr.deletions}</span>
        </button>
      </nav>
      {reviewOpen && detail && (
        <ReviewDialog pr={pr} detail={detail}
          pendingComments={pendingComments}
          onPendingCommentsChange={onPendingCommentsChange}
          onClose={() => setReviewOpen(false)} onRefresh={onRefresh} />
      )}
      {mergeOpen && detail && typeof mergeQueue === 'boolean' && (
        <MergeDialog pr={pr} detail={detail} mergeQueue={mergeQueue}
          onClose={() => setMergeOpen(false)} onRefresh={onRefresh} />
      )}
    </header>
  );
}

export function ReviewRequestNotice({ pr, detail, onReview }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onReview: () => void;
}) {
  const requester = detail.recentActivity.findLast(activity =>
    activity.eventType === 'review_requested' && activity.actor?.trim())?.actor
    ?? pr.author
    ?? 'Someone';
  return (
    <aside className="pull-detail__review-request" aria-label="Review requested">
      <ReviewRequestIcon />
      <span><strong>{requester}</strong> requested your review on this pull request.</span>
      <button type="button" onClick={onReview}>Add your review</button>
    </aside>
  );
}

export function canSubmitReview(pr: PullRequestDto, detail: PullRequestDetailDto | null) {
  return pullRequestNeedsReview(pr) && detail?.state?.toLowerCase() === 'open';
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
  onClose,
  onRefresh,
}: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  pendingComments?: InlineReviewCommentDto[];
  onPendingCommentsChange?: (comments: InlineReviewCommentDto[]) => void;
  onClose: () => void;
  onRefresh: () => Promise<void>;
}) {
  const [body, setBody] = useState('');
  const [event, setEvent] = useState<ReviewEvent>('APPROVE');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bodyRequired = event !== 'APPROVE' && pendingComments.length === 0;

  useEffect(() => {
    const close = (keyboardEvent: KeyboardEvent) => {
      if (keyboardEvent.key === 'Escape' && !submitting) onClose();
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await window.bridge.submitReview(
        pr.id,
        detail.repo,
        detail.number,
        event,
        body.trim(),
        pendingComments,
      );
      onPendingCommentsChange([]);
      await onRefresh();
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
        <ReviewChoice
          checked={event === 'APPROVE'}
          title="Approve"
          description="Submit feedback and approve merging these changes."
          onChange={() => setEvent('APPROVE')} />
        <ReviewChoice
          checked={event === 'REQUEST_CHANGES'}
          title="Request Changes"
          description="Submit feedback that must be addressed before merging."
          onChange={() => setEvent('REQUEST_CHANGES')} />
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

export function MergeDialog({ pr, detail, mergeQueue, onClose, onRefresh }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  mergeQueue: boolean;
  onClose: () => void;
  onRefresh: () => Promise<void>;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { pending, failing, successful, unresolved, checksReported, state } = mergeStatus(detail);
  const blocked = state === 'failed' || state === 'blocked';

  useEffect(() => {
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape' && !submitting) onClose(); };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, [onClose, submitting]);

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await window.bridge.mergePr(pr.id, detail.repo, detail.number, mergeQueue ? 'queue' : 'rebase');
      await onRefresh();
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
    <section className="pr-merge-sheet" role="dialog" aria-modal="true" aria-labelledby="pr-merge-title">
      <div className="pr-merge-sheet__header">
        <h2 id="pr-merge-title">Merge pull request</h2>
        <button type="button" aria-label="Close merge dialog" disabled={submitting} onClick={onClose}>×</button>
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
        <button className="is-primary" type="button" disabled={submitting || state !== 'ready'} onClick={() => { void submit(); }}>
          <PullRequestIcon /> {submitting ? 'Submitting…' : mergeQueue ? 'Queue when ready' : 'Rebase and merge'}
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
  return <span className="pr-merge-success-icon" aria-hidden="true">
    <svg viewBox="0 0 16 16" width="11" height="11" fill="none" stroke="currentColor"
      strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.4">
      <path d="m3.5 8.2 2.8 2.8 6.2-6" />
    </svg>
  </span>;
}

function ConflictIcon() {
  return <span className="pr-merge-conflict-icon" aria-hidden="true">×</span>;
}

function ReviewRequestIcon() {
  return <svg className="pull-detail__review-request-icon" viewBox="0 0 16 16" width="16" height="16"
    fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" aria-hidden="true">
    <path d="M8 1.8 15 14H1L8 1.8Z" /><path d="M8 5.5v4" /><circle cx="8" cy="12" r=".7" fill="currentColor" stroke="none" />
  </svg>;
}

function DetailBody({
  tab,
  pr,
  detail,
  diffs,
  diffCommits,
  diffRange,
  diffLoading,
  pendingComments,
  onCommitRangeChange,
  onPendingCommentsChange,
  contributorsWorkspaceId,
  onRefresh,
  onDiscussionPosted,
  onReviewPosted,
}: {
  tab: DetailTab;
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  diffs: DiffFileDto[] | null;
  diffCommits: DiffCommitDto[];
  diffRange: CommitRange;
  diffLoading: boolean;
  pendingComments: InlineReviewCommentDto[];
  onCommitRangeChange: (range: CommitRange) => void;
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  contributorsWorkspaceId?: string;
  onRefresh: () => Promise<void>;
  onDiscussionPosted: (detail: PullRequestDetailDto) => void;
  onReviewPosted: (detail: PullRequestDetailDto) => void;
}) {
  if (tab === 'changes') {
    if (diffs === null) return <div className="hp-loading">Loading files…</div>;
    return <div className="pull-detail__body pull-detail__changes"><PullChanges
      repo={detail.repo}
      number={detail.number}
      files={diffs}
      commits={diffCommits}
      commitRange={diffRange}
      loading={diffLoading}
      canReview={canSubmitReview(pr, detail)}
      pendingComments={pendingComments}
      onCommitRangeChange={onCommitRangeChange}
      onPendingCommentsChange={onPendingCommentsChange}
      onDetailChange={onReviewPosted}
    /></div>;
  }
  return <div className="pull-detail__body"><PullOverview
    pr={pr}
    detail={detail}
    contributorsWorkspaceId={contributorsWorkspaceId}
    onRefresh={onRefresh}
    onDetailChange={onDiscussionPosted}
  /></div>;
}

export function RefreshIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 7v5h-5M4 17v-5h5" /><path d="M7.1 7.1a7 7 0 0 1 11.4 2.3M16.9 16.9a7 7 0 0 1-11.4-2.3" />
    </svg>
  );
}

function ZoomIcon({ zoomed }: { zoomed: boolean }) {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {zoomed
        ? <path d="M4 9h5V4M20 9h-5V4M4 15h5v5M20 15h-5v5" />
        : <path d="M9 4H4v5M15 4h5v5M4 15v5h5M20 15v5h-5" />}
    </svg>
  );
}

function SearchIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" />
    </svg>
  );
}

export function PaneIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M15 4v16" />
    </svg>
  );
}

export function CommentIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.3-.7L3 21l1.8-5.7a8.4 8.4 0 1 1 16.2-3.8z" />
    </svg>
  );
}

function ChangesIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h3M16 3h3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3M12 8v8M8 12h8" />
    </svg>
  );
}
