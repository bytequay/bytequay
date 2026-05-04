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
import { useEffect, useRef, useState } from 'react';
import type { ColumnPageDto, MyPrColumnSlug, PullRequestDto, TeamColumnsResponse, TeamDto } from '../types';
import KanbanBoard from '../kanban/KanbanBoard';
import PullRequestPreview from '../PullRequestPreview';
import ReviewScreen from '../ReviewScreen';
import DiffViewerScreen from '../DiffViewerScreen';
import ResizeHandle from '../ResizeHandle';
import TeamEditorModal from './TeamEditorModal';
import TeamPrSidebar from './TeamPrSidebar';
import { MY_PR_COLUMNS_TEAM } from '../prBuckets';
import { getCached, setCached } from '../dataCache';

type Props = {
  teamId: number;
  /** Sends the user back to Settings → Teams. */
  onBack: () => void;
};

// SWR cache keys. The columns response captures per-column slices +
// totals so a remount paints instantly without a re-fan-out.
const TEAM_KEY = (id: number) => `team:${id}`;
const COLUMNS_KEY = (id: number) => `team:${id}:columns`;
/** First-paint page size. Matches the kanban's INITIAL_SHOWN. */
const PER_COLUMN = 5;
/** "+ N more" page size. */
const PAGE_LIMIT = 5;
const EMPTY_COLUMNS: TeamColumnsResponse = {
  columns: { drafting: [], waiting_on_review: [], needs_changes: [], ready_to_merge: [], recently_merged: [], handled: [] },
  totals: { drafting: 0, waiting_on_review: 0, needs_changes: 0, ready_to_merge: 0, recently_merged: 0, handled: 0 },
  repoTotals: {},
};

// Sidebar sizing — kept separate from the inbox sidebar so the user can
// have different widths per page.
const SIDEBAR_WIDTH_KEY = 'settings:team-sidebar-width';
const SIDEBAR_COLLAPSED_KEY = 'settings:team-sidebar-collapsed';
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

function TeamDetailPage({ teamId, onBack }: Props) {
  const [team, setTeam] = useState<TeamDto | null>(() => getCached<TeamDto>(TEAM_KEY(teamId)) ?? null);
  const [columnsData, setColumnsData] = useState<TeamColumnsResponse>(() =>
    getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) ?? EMPTY_COLUMNS,
  );
  // Only show the first-paint spinner when we have nothing to render.
  const [loading, setLoading] = useState(() =>
    getCached<TeamDto>(TEAM_KEY(teamId)) === undefined
    && getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) === undefined,
  );
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [editing, setEditing] = useState(false);
  const [selectedPr, setSelectedPr] = useState<PullRequestDto | null>(null);
  const [reviewingPr, setReviewingPr] = useState<PullRequestDto | null>(null);
  const [diffViewerPr, setDiffViewerPr] = useState<PullRequestDto | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState<number>(loadSidebarWidth);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(
    () => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1',
  );
  /** Active per-repo chip filter. {@code null} → ALL (no filter); else
   *  the full {@code owner/repo} name to keep in the sidebar. Backend
   *  pagination is not filter-aware, so the filter is applied client-
   *  side on whichever pages have already loaded. */
  const [repoFilter, setRepoFilter] = useState<string | null>(null);
  const pageRef = useRef<HTMLDivElement>(null);

  const handleSidebarResize = (clientX: number) => {
    const rect = pageRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(SIDEBAR_WIDTH_MIN, Math.min(SIDEBAR_WIDTH_MAX, clientX - rect.left));
    setSidebarWidth(next);
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(next));
  };

  // Same effect as the user clicking the ◀ / ▶ chevron in the sidebar.
  // Used to auto-fold when entering review/diff and auto-expand when
  // returning to the PR detail.
  const collapseSidebarPersist = () => {
    setSidebarCollapsed(true);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, '1');
  };
  const expandSidebarPersist = () => {
    setSidebarCollapsed(false);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, '0');
  };

  const load = async (manual = false) => {
    const hasCached = getCached<TeamColumnsResponse>(COLUMNS_KEY(teamId)) !== undefined;
    if (manual) setRefreshing(true);
    else if (!hasCached) setLoading(true);
    setError(null);
    try {
      const [t, cols] = await Promise.all([
        window.bridge.getTeam(teamId),
        // force=true on manual refresh busts the per-team TTL cache on
        // the backend so the user sees fresh GitHub data.
        window.bridge.getTeamPullsByColumn(teamId, PER_COLUMN, manual),
      ]);
      setTeam(t);
      setColumnsData(cols);
      setCached(TEAM_KEY(teamId), t);
      setCached(COLUMNS_KEY(teamId), cols);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => { void load(false); }, [teamId]);

  // Latest columnsData snapshot for the auto-load effect below — needs
  // to read the freshest state across awaits without re-running on
  // every change to the data itself.
  const columnsDataRef = useRef(columnsData);
  useEffect(() => { columnsDataRef.current = columnsData; }, [columnsData]);

  /** "+ N more" handler. Asks the backend for the next page of one
   *  column and appends to local state. Backend serves from its TTL
   *  cache so the round-trip is cheap; no re-fan-out per click.
   *  {@code limit} overrides PAGE_LIMIT — the auto-load-on-filter path
   *  passes the full remaining count to drain a column in one call. */
  const loadMoreColumn = async (column: MyPrColumnSlug, limit: number = PAGE_LIMIT) => {
    const current = columnsDataRef.current.columns[column] ?? [];
    const offset = current.length;
    try {
      const page: ColumnPageDto = await window.bridge.getTeamColumnPage(
        teamId, column, offset, limit,
      );
      setColumnsData(prev => {
        const next: TeamColumnsResponse = {
          columns: { ...prev.columns, [column]: [...(prev.columns[column] ?? []), ...page.items] },
          // The backend's total may have shifted (if a sync ran during
          // pagination); take the fresh value from the response.
          totals: { ...prev.totals, [column]: page.total },
          // Preserve repoTotals — only /pulls/by-column ships it; the
          // /pulls/column page response doesn't, so we'd lose the per-
          // repo chip counts on the second pagination otherwise.
          repoTotals: prev.repoTotals,
        };
        setCached(COLUMNS_KEY(teamId), next);
        return next;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  // When the user activates a per-repo chip, drain every column's
  // remaining pages so the visible list matches the chip's repo total.
  // The backend's TeamColumnsResponse only sends the first page of each
  // column up-front, so client-side filtering otherwise shows a tiny
  // fraction of the repo's PRs (chip says 7, list shows 1, etc.).
  // Pagination is cache-served so this fans out cheap reads, in
  // parallel across columns with a full-remaining limit each — one
  // round-trip per column suffices.
  useEffect(() => {
    if (repoFilter === null) return;
    let cancelled = false;
    (async () => {
      const data = columnsDataRef.current;
      await Promise.all(MY_PR_COLUMNS_TEAM.map(col => {
        const loaded = (data.columns[col] ?? []).length;
        const total = data.totals[col] ?? loaded;
        const remaining = total - loaded;
        if (remaining <= 0 || cancelled) return Promise.resolve();
        return loadMoreColumn(col, remaining);
      }));
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repoFilter]);

  // Clicking a card opens the in-app PR detail page. The embedded review
  // (Open on Remote) and native diff viewer are still reachable from there;
  // openExternal stays only for explicit "open on github.com" actions
  // elsewhere on the card.
  const handleSelect = (pr: PullRequestDto) => {
    void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
    setSelectedPr(pr);
  };

  /** Optimistic remove of one PR from a column. Used by the
   *  mark-handled flow so the card vanishes immediately; rollback
   *  re-inserts it on failure. */
  const removePrFromColumns = (prId: number) => {
    let removed: { column: MyPrColumnSlug; index: number; pr: PullRequestDto } | null = null;
    setColumnsData(prev => {
      const nextColumns = { ...prev.columns };
      const nextTotals = { ...prev.totals };
      for (const c of Object.keys(prev.columns) as MyPrColumnSlug[]) {
        const list = prev.columns[c];
        const idx = list.findIndex(p => p.id === prId);
        if (idx >= 0) {
          removed = { column: c, index: idx, pr: list[idx] };
          nextColumns[c] = [...list.slice(0, idx), ...list.slice(idx + 1)];
          nextTotals[c] = Math.max(0, prev.totals[c] - 1);
          break;
        }
      }
      const next: TeamColumnsResponse = {
        columns: nextColumns,
        totals: nextTotals,
        repoTotals: prev.repoTotals,
      };
      setCached(COLUMNS_KEY(teamId), next);
      return next;
    });
    return removed;
  };

  const restorePrToColumns = (removed: { column: MyPrColumnSlug; index: number; pr: PullRequestDto }) => {
    setColumnsData(prev => {
      const list = prev.columns[removed.column] ?? [];
      const at = Math.min(removed.index, list.length);
      const restored = [...list.slice(0, at), removed.pr, ...list.slice(at)];
      const next: TeamColumnsResponse = {
        columns: { ...prev.columns, [removed.column]: restored },
        totals: { ...prev.totals, [removed.column]: prev.totals[removed.column] + 1 },
        repoTotals: prev.repoTotals,
      };
      setCached(COLUMNS_KEY(teamId), next);
      return next;
    });
  };

  const handleMarkHandled = async (prId: number) => {
    const removed = removePrFromColumns(prId);
    if (!removed) return;
    // Move the PR into the Handled column at the top (most-recently-
    // handled first). Patch handledAction + reviewedAt locally so the
    // card renders with its dismissed state immediately.
    const handledPr: PullRequestDto = {
      ...removed.pr,
      handledAction: 'MANUAL',
      reviewedAt: new Date().toISOString(),
    };
    setColumnsData(prev => {
      const next: TeamColumnsResponse = {
        columns: { ...prev.columns, handled: [handledPr, ...(prev.columns.handled ?? [])] },
        totals: { ...prev.totals, handled: (prev.totals.handled ?? 0) + 1 },
        repoTotals: prev.repoTotals,
      };
      setCached(COLUMNS_KEY(teamId), next);
      return next;
    });
    // Keep the open detail in sync so PullRequestPreview reflects the
    // handled state (and the sidebar highlight stays on the same row).
    if (selectedPr?.id === prId) setSelectedPr(handledPr);
    try {
      await window.bridge.markPrHandled(prId, 'MANUAL');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      // Roll back: remove from handled, restore to the original column.
      setColumnsData(prev => {
        const next: TeamColumnsResponse = {
          columns: { ...prev.columns, handled: (prev.columns.handled ?? []).filter(p => p.id !== prId) },
          totals: { ...prev.totals, handled: Math.max(0, (prev.totals.handled ?? 0) - 1) },
          repoTotals: prev.repoTotals,
        };
        setCached(COLUMNS_KEY(teamId), next);
        return next;
      });
      restorePrToColumns(removed);
    }
  };

  /** Optimistic Approve: stamp handledAction=APPROVED + reviewedAt locally
   *  on the selected PR (so the diff viewer's title pill reflects it),
   *  call the GitHub-side approvePr, then force-refresh so the kanban
   *  re-categorizes. Errors roll the local state back. */
  const handleApprove = async (prId: number, repo: string, number: number) => {
    const prev = selectedPr;
    if (selectedPr?.id === prId) {
      setSelectedPr({
        ...selectedPr,
        handledAction: 'APPROVED',
        reviewedAt: new Date().toISOString(),
      });
    }
    try {
      await window.bridge.approvePr(prId, repo, number);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (prev && prev.id === prId) setSelectedPr(prev);
      throw e;
    }
  };

  /** Optimistic Merge: same shape as handleApprove but the PR also
   *  moves to the Handled column on the next refresh. */
  const handleMerge = async (prId: number, repo: string, number: number, strategy?: 'rebase' | 'squash' | 'merge') => {
    const prev = selectedPr;
    if (selectedPr?.id === prId) {
      setSelectedPr({
        ...selectedPr,
        handledAction: 'MERGED',
        reviewedAt: new Date().toISOString(),
        mergedAt: new Date().toISOString(),
        state: 'closed',
      });
    }
    try {
      await window.bridge.mergePr(prId, repo, number, strategy);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (prev && prev.id === prId) setSelectedPr(prev);
      throw e;
    }
  };

  const handleReopen = async (prId: number) => {
    // Find the PR in the handled column. Optimistically remove it from
    // there and force a refresh so the backend re-categorizes (the
    // categorizer doesn't know which active column to put it in
    // without re-evaluating verdicts/state).
    const removed = removePrFromColumns(prId);
    if (selectedPr?.id === prId) {
      setSelectedPr(prev => (prev ? { ...prev, handledAction: null, reviewedAt: null } : prev));
    }
    try {
      await window.bridge.reopenPr(prId);
      // Bypass the TTL cache so the freshly-reopened PR gets its
      // proper column on the next render.
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (removed) restorePrToColumns(removed);
    }
  };

  // PR detail / review / diff-viewer overlay. Mirrors PullRequestList's
  // selected-mode layout: a left sidebar (team PR list grouped by column)
  // + a right pane that hosts the preview, review, or diff viewer.
  if (selectedPr || reviewingPr || diffViewerPr) {
    const enterReview = () => {
      setReviewingPr(selectedPr);
      collapseSidebarPersist();
    };
    const enterDiff = () => {
      setDiffViewerPr(selectedPr);
      collapseSidebarPersist();
    };
    const exitReview = () => {
      setReviewingPr(null);
      expandSidebarPersist();
    };
    const exitDiff = () => {
      setDiffViewerPr(null);
      expandSidebarPersist();
    };

    const screen = reviewingPr ? (
      <ReviewScreen pr={reviewingPr} onBack={exitReview} />
    ) : diffViewerPr ? (
      <DiffViewerScreen
        pr={diffViewerPr}
        onBack={exitDiff}
        onApprove={handleApprove}
      />
    ) : selectedPr ? (
      // Wrap in a positioned container — PullRequestPreview's Classic mode
      // root uses position:absolute/inset:0, so it needs a positioned
      // ancestor to anchor against.
      <div className="team-detail-pr-wrap">
        <PullRequestPreview
          pr={selectedPr}
          onOpenReview={enterReview}
          onInspectDiffs={enterDiff}
          onMarkHandled={handleMarkHandled}
          onMerge={handleMerge}
        />
      </div>
    ) : null;

    const teamLabel = team?.name ?? 'Team';

    return (
      <div className="v2-page" ref={pageRef}>
        {sidebarCollapsed ? (
          <aside className="v2-sidebar v2-sidebar--collapsed" style={{ width: SIDEBAR_RAIL_WIDTH }}>
            <button
              type="button"
              className="v2-sidebar__rail-toggle"
              onClick={expandSidebarPersist}
              title="Expand PR list"
            >
              ▶
            </button>
            <div className="v2-sidebar__rail-label" aria-hidden="true">{teamLabel}</div>
          </aside>
        ) : (
          <aside className="v2-sidebar" style={{ width: sidebarWidth }}>
            <div className="pr-list-header">
              <div className="pr-list-header__title">
                <span className="pr-list-header__brand">{teamLabel}</span>
              </div>
              {/* Per-repo chips replace the old "N PRs" subtitle. The
                  leading "ALL N" chip stays first; per-repo chips follow
                  in encounter-order from the backend response. Repo
                  names are shown as "name" (drop the owner/) to keep
                  chips compact — owner is constant per repo so the
                  prefix mostly adds noise. */}
              {(() => {
                // Chip counts come from loaded data, NOT the backend's
                // repoTotals — that way the number on every chip exactly
                // matches the items the sidebar actually has and can
                // filter to. As eager-load drains in extra pages the
                // chip count rises in step with the list. A trailing
                // " / N" reveals the backend total when it's higher
                // than what's loaded so the user knows more PRs exist
                // upstream.
                const allLoaded = Object.values(columnsData.columns).flat();
                const totalAll = allLoaded.length;
                if (totalAll === 0) return null;
                const loadedByRepo = new Map<string, number>();
                for (const pr of allLoaded) {
                  loadedByRepo.set(pr.repo, (loadedByRepo.get(pr.repo) ?? 0) + 1);
                }
                const repoEntries = Array.from(loadedByRepo.entries())
                  .sort(([, a], [, b]) => b - a);
                const backendTotals = columnsData.repoTotals ?? {};
                // Sum of every repo's upstream total — i.e. the count
                // of PRs that exist for the team across every page,
                // including ones not yet loaded into the sidebar.
                // Drives the trailing "/N" on the ALL chip so it
                // matches the loaded/upstream pattern on each per-repo
                // chip below.
                const upstreamAll = Object.values(backendTotals)
                  .reduce((s, n) => s + n, 0);
                const showAllRemainder = upstreamAll > totalAll;
                return (
                  <div className="team-repo-chips">
                    <button
                      type="button"
                      className={`team-repo-chip team-repo-chip--all${repoFilter === null ? ' team-repo-chip--active' : ''}`}
                      onClick={() => setRepoFilter(null)}
                      title={showAllRemainder
                        ? `${totalAll} of ${upstreamAll} PRs loaded across every repo`
                        : 'Show PRs from every repo'}
                    >
                      <span className="team-repo-chip__name">ALL</span>
                      <span className="team-repo-chip__count">
                        {totalAll}{showAllRemainder ? `/${upstreamAll}` : ''}
                      </span>
                    </button>
                    {repoEntries.map(([fullName, loaded]) => {
                      const shortName = fullName.includes('/') ? fullName.split('/')[1] : fullName;
                      const active = repoFilter === fullName;
                      const upstream = backendTotals[fullName] ?? loaded;
                      const showRemainder = upstream > loaded;
                      return (
                        <button
                          key={fullName}
                          type="button"
                          className={`team-repo-chip${active ? ' team-repo-chip--active' : ''}`}
                          onClick={() => setRepoFilter(active ? null : fullName)}
                          title={showRemainder
                            ? `${fullName} — ${loaded} of ${upstream} loaded; click to filter (loads the rest)`
                            : `Show only PRs from ${fullName}`}
                        >
                          <span className="team-repo-chip__name">{shortName}</span>
                          <span className="team-repo-chip__count">
                            {loaded}{showRemainder ? `/${upstream}` : ''}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                );
              })()}
            </div>
            <button
              type="button"
              className="v2-sidebar__collapse-btn"
              onClick={collapseSidebarPersist}
              title="Collapse PR list to a rail"
            >
              ◀
            </button>
            <div className="v2-list">
              <TeamPrSidebar
                data={columnsData}
                repoFilter={repoFilter}
                selectedId={selectedPr?.id ?? reviewingPr?.id ?? diffViewerPr?.id ?? null}
                onSelect={handleSelect}
                onHandle={handleMarkHandled}
                onReopen={handleReopen}
                onLoadMore={loadMoreColumn}
              />
            </div>
          </aside>
        )}
        {!sidebarCollapsed && (
          <ResizeHandle onResize={handleSidebarResize} ariaLabel="Resize team PR list" />
        )}
        <main className="v2-main v2-main--screen">
          {!reviewingPr && !diffViewerPr && (
            <div className="v2-main__nav">
              <button
                type="button"
                className="v2-back-btn"
                onClick={() => setSelectedPr(null)}
                title={`Return to ${teamLabel}'s kanban.`}
              >
                ← Back to {teamLabel}
              </button>
            </div>
          )}
          <div className="v2-main__screen">{screen}</div>
        </main>
      </div>
    );
  }

  return (
    <section className="team-detail">
      <header className="team-detail__head">
        <nav className="team-detail__breadcrumb">
          <button type="button" className="team-detail__crumb" onClick={onBack}>← Teams</button>
        </nav>
        {team && (
          <div className="team-detail__row">
            <div className="team-detail__title">
              <span className={`team-avatar team-avatar--${team.color}`} aria-hidden="true">{team.avatar}</span>
              <div>
                <h1 className="team-detail__name">{team.name}</h1>
                <div className="team-detail__sub">
                  {team.members.length} member{team.members.length === 1 ? '' : 's'}
                  {team.members.length > 0 && ' · ' + team.members.slice(0, 3).join(', ')}
                  {team.members.length > 3 && ` and ${team.members.length - 3} more`}
                </div>
              </div>
            </div>
            <div className="team-detail__actions">
              <button type="button" className="button button--secondary" onClick={() => setEditing(true)}>
                ⚙ Edit team
              </button>
              <button type="button" className="button button--secondary" onClick={() => void load(true)} disabled={refreshing}>
                {refreshing ? 'Refreshing…' : '↻ Refresh'}
              </button>
            </div>
          </div>
        )}
      </header>

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {(() => {
        const totalPrs = Object.values(columnsData.totals).reduce((s, n) => s + n, 0);
        if (loading) return null;
        if (team && totalPrs === 0) {
          return (
            <div className="kanban-empty">
              No open PRs from <b>{team.name}</b>'s members in your watched repos right now.
            </div>
          );
        }
        if (totalPrs === 0) return null;
        return (
          <KanbanBoard
            // Inbox-mode props the type still requires (the team-mode
            // path inside KanbanBoard ignores them). teamData is what
            // actually drives the render.
            prs={[]}
            selectedId={null}
            onSelect={handleSelect}
            onHandle={handleMarkHandled}
            onReopen={handleReopen}
            mode="team"
            teamData={{
              columns: columnsData.columns,
              totals: columnsData.totals,
              onLoadMoreColumn: loadMoreColumn,
            }}
          />
        );
      })()}

      {editing && team && (
        <TeamEditorModal
          team={team}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await load(false);
          }}
        />
      )}
    </section>
  );
}

export default TeamDetailPage;
