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
import { useEffect, useMemo, useState } from 'react';
import type { MyPrColumnSlug, PullRequestDto } from '../types';
import {
  MY_PR_COLUMNS,
  MY_PR_COLUMNS_TEAM,
  MY_PR_COLUMN_LABEL,
  TO_REVIEW_COLUMNS,
  TO_REVIEW_COLUMN_LABEL,
  type MyPrColumn,
  type ToReviewColumn,
  categorizeMyPr,
  categorizeToReview,
  groupMyPrs,
  groupToReview,
} from '../prBuckets';
import KanbanColumn, { type KanbanColumnKind } from './KanbanColumn';

const COLLAPSED_KEY = 'settings:kanban-collapsed-v2';
const LANE_KEY = 'settings:kanban-lane';
const REPO_FILTER_KEY = 'settings:kanban-repo-filter';

type Lane = 'mine' | 'to_review';

type CollapsedMap = Partial<Record<KanbanColumnKind, boolean>>;

// Columns that start collapsed by default — both ends of each lane host
// "ambient" PRs (drafts on the left, recently-cleared on the right) that
// don't usually need a full-width column.
const DEFAULT_COLLAPSED: CollapsedMap = {
  drafting: true,
  recently_merged: true,
  // Handled is the user's "set aside" pile — collapsed by default so
  // it doesn't dominate the team page; click to expand and reopen.
  handled: true,
  cleared_today: true,
};

function loadCollapsed(): CollapsedMap {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY);
    if (raw) return { ...DEFAULT_COLLAPSED, ...(JSON.parse(raw) as CollapsedMap) };
  } catch { /* fall through */ }
  return DEFAULT_COLLAPSED;
}

function loadLane(): Lane {
  const raw = localStorage.getItem(LANE_KEY);
  return raw === 'mine' || raw === 'to_review' ? raw : 'to_review';
}

function loadRepoFilter(): string {
  return localStorage.getItem(REPO_FILTER_KEY) ?? '';
}

type Props = {
  prs: PullRequestDto[];
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
  onReopen: (prId: number) => void;
  /** Optional callback invoked when the user clicks the "Teams" tab. The
   *  kanban itself doesn't render a teams view — the host page (typically
   *  PullRequestList) routes to its own Teams page. If absent, the tab
   *  is hidden. */
  onTeamsClick?: () => void;
  /** Optional count to render alongside the Teams tab badge. */
  teamsCount?: number;
  /** "inbox" (default) shows the My-PRs / To-review lane split with the
   *  morning briefing and Active/Recently-closed segment. "team" hides
   *  all of that and renders just the My-PRs column set under a single
   *  "PRs" header — the cards are PRs by team members in watched repos,
   *  so they always show author + repo avatar.
   *  See TeamDetailPage. */
  mode?: 'inbox' | 'team';
  /** When provided, the team kanban renders from pre-bucketed server
   *  data with per-column pagination. Each column shows totals[col]
   *  in the header, the loaded slice from columns[col], and clicking
   *  "+ N more" calls onLoadMoreColumn(col). Required when
   *  mode === 'team'. */
  teamData?: {
    columns: Record<MyPrColumnSlug, PullRequestDto[]>;
    totals: Record<MyPrColumnSlug, number>;
    onLoadMoreColumn: (column: MyPrColumnSlug) => Promise<void> | void;
  };
};

/**
 * Two-lane kanban driven by docs/design/kanban-refactor.md.
 *
 * - "My PRs" lane (origin = AUTHORED): Drafting / Waiting on review /
 *   Needs changes / Ready to merge / Recently merged.
 * - "To review" lane (origin = REVIEW_REQUESTED): Needs attention /
 *   In progress / Awaiting author / Cleared today.
 *
 * Inputs come straight from the v26-enriched PullRequestDto — see
 * categorizeMyPr / categorizeToReview in prBuckets.ts. The morning
 * briefing strip and per-lane filter chips are computed on the fly from
 * the current PR list.
 */
function KanbanBoard(props: Props) {
  const mode = props.mode ?? 'inbox';
  // In team mode the lane is forced to "mine" (the columns are the My-PRs
  // set, just labelled differently) because team PRs are PRs *authored
  // by* team members. The lane tabs, briefing, and Active/Recent segment
  // are all hidden — see the render below.
  const [lane, setLane] = useState<Lane>(() => mode === 'team' ? 'mine' : loadLane());
  const [collapsed, setCollapsed] = useState<CollapsedMap>(loadCollapsed);
  const [repoFilter, setRepoFilter] = useState<string>(loadRepoFilter);

  const setLanePersisted = (next: Lane) => {
    setLane(next);
    try { localStorage.setItem(LANE_KEY, next); } catch { /* ignore */ }
  };

  const setRepoFilterPersisted = (next: string) => {
    setRepoFilter(next);
    try { localStorage.setItem(REPO_FILTER_KEY, next); } catch { /* ignore */ }
  };

  const toggle = (kind: KanbanColumnKind) => {
    setCollapsed(prev => {
      const next = { ...prev, [kind]: !prev[kind] };
      try { localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  };

  // Repo set + counts for the per-lane filter chips. Computed before the
  // repo filter applies so the user always sees every repo they could
  // pick, with its full count.
  const repoOptions = useMemo(() => {
    const lanePrs = props.prs.filter(pr =>
      lane === 'mine' ? pr.origin === 'AUTHORED' : pr.origin === 'REVIEW_REQUESTED');
    const counts = new Map<string, number>();
    for (const pr of lanePrs) counts.set(pr.repo, (counts.get(pr.repo) ?? 0) + 1);
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  }, [props.prs, lane]);

  // If localStorage has a repoFilter that no current PR matches (e.g. the
  // user previously filtered to a repo that's since been unwatched, or
  // the chip storage format changed across versions), treat the filter
  // as unset for this render. Without this guard the columns silently
  // empty out while "All N" still shows the right total — the worst
  // possible UX, since no chip is highlighted to point at the cause.
  const repoFilterMatchesAnything = useMemo(() => {
    if (!repoFilter) return true;
    return props.prs.some(pr => pr.repo === repoFilter);
  }, [props.prs, repoFilter]);
  const effectiveRepoFilter = repoFilterMatchesAnything ? repoFilter : '';

  // Lane PRs after the repo filter. This is what feeds the columns.
  const filteredLanePrs = useMemo(() => {
    return props.prs.filter(pr => {
      const wantOrigin = lane === 'mine' ? 'AUTHORED' : 'REVIEW_REQUESTED';
      if (pr.origin !== wantOrigin) return false;
      if (effectiveRepoFilter && pr.repo !== effectiveRepoFilter) return false;
      return true;
    });
  }, [props.prs, lane, effectiveRepoFilter]);

  // Persist the cleanup so the filter doesn't keep failing on every
  // mount. Only fires when we actually had a stale filter to drop —
  // otherwise this is a no-op.
  useEffect(() => {
    if (repoFilter && !repoFilterMatchesAnything) {
      try { localStorage.removeItem(REPO_FILTER_KEY); } catch { /* ignore */ }
      setRepoFilter('');
    }
  }, [repoFilter, repoFilterMatchesAnything]);

  // Briefing counters cover BOTH lanes regardless of the active tab —
  // the morning briefing's whole job is to point you at whichever lane
  // has hot work, even if you're currently looking at the other one.
  const briefing = useMemo(() => buildBriefing(props.prs), [props.prs]);

  return (
    <div className="kanban-v2">
      {mode === 'inbox' && (
        <MorningBriefing briefing={briefing} activeLane={lane} onJumpToLane={setLanePersisted} />
      )}

      {/* Tab toolbar — left-side lane tabs (My PRs / To review / Teams),
          right-side segment (Active / Recently closed). The lane tabs
          have a small left padding so they don't sit flush against the
          page boundary, matching the mockup.
          In team mode this collapses to a single "PRs" header — there's
          only one lane (PRs by team members) so tabs are noise. */}
      {mode === 'team' ? (
        <div className="kanban-v2__tab-toolbar">
          <h3 className="kanban-v2__lane-header">
            PRs
            {/* Team mode: total comes from the columned response, not
                props.prs (which is empty in team mode). Sums every
                column INCLUDING handled so the count never drifts. */}
            <span className="kanban-v2__tab-count">
              {props.teamData
                ? Object.values(props.teamData.totals).reduce((s, n) => s + n, 0)
                : briefing.mineTotal}
            </span>
          </h3>
        </div>
      ) : (
      <div className="kanban-v2__tab-toolbar">
        <div className="kanban-v2__lane-tabs" role="tablist" aria-label="Kanban lane">
          <button
            type="button"
            role="tab"
            aria-selected={lane === 'mine'}
            className={`kanban-v2__tab${lane === 'mine' ? ' kanban-v2__tab--active' : ''}`}
            onClick={() => setLanePersisted('mine')}
          >
            <span className="kanban-v2__tab-icon" aria-hidden="true">🚀</span>
            <span>My PRs</span>
            <span className="kanban-v2__tab-count">{briefing.mineTotal}</span>
            {briefing.mineNeedsAction > 0 && (
              <span className="kanban-v2__tab-flag" title={`${briefing.mineNeedsAction} need you`}>
                {briefing.mineNeedsAction} need you
              </span>
            )}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={lane === 'to_review'}
            className={`kanban-v2__tab${lane === 'to_review' ? ' kanban-v2__tab--active' : ''}`}
            onClick={() => setLanePersisted('to_review')}
          >
            <span className="kanban-v2__tab-icon" aria-hidden="true">📥</span>
            <span>To review</span>
            <span className="kanban-v2__tab-count">{briefing.toReviewTotal}</span>
          </button>
          {props.onTeamsClick && (
            <button
              type="button"
              className="kanban-v2__tab"
              onClick={props.onTeamsClick}
              title="Open the Teams page"
            >
              <span className="kanban-v2__tab-icon" aria-hidden="true">👥</span>
              <span>Teams</span>
              {typeof props.teamsCount === 'number' && (
                <span className="kanban-v2__tab-count">{props.teamsCount}</span>
              )}
            </button>
          )}
        </div>
        {/* Right-side segmented control. For the To-review lane it splits
            Active vs. Recently-closed (cleared_today shows count of
            recently-cleared cards). For My PRs it splits Active vs.
            Recently merged. Today this is a UI-only state — the columns
            already reflect the same partition. Wired to the underlying
            collapsed-column state so toggling actually changes the view. */}
        <RecentSegment
          lane={lane}
          counts={{
            mineActive: briefing.mineTotal - countIn(props.prs, 'AUTHORED', categorizeMyPr_recentlyMerged),
            mineRecent: countIn(props.prs, 'AUTHORED', categorizeMyPr_recentlyMerged),
            toReviewActive: briefing.toReviewTotal - countIn(props.prs, 'REVIEW_REQUESTED', categorizeToReview_clearedToday),
            toReviewRecent: countIn(props.prs, 'REVIEW_REQUESTED', categorizeToReview_clearedToday),
          }}
          collapsed={collapsed}
          onToggle={toggle}
        />
      </div>
      )}

      {repoOptions.length > 1 && (
        <div className="kanban-v2__filters">
          <span className="kanban-v2__filters-label">Repo:</span>
          <button
            type="button"
            className={`kanban-v2__chip${!repoFilter ? ' kanban-v2__chip--active' : ''}`}
            onClick={() => setRepoFilterPersisted('')}
          >
            All
            <span className="kanban-v2__chip-count">{filteredLanePrs.length + (repoFilter ? -filteredLanePrs.length : 0) || repoOptions.reduce((s, [, c]) => s + c, 0)}</span>
          </button>
          {repoOptions.map(([repo, count]) => (
            <button
              key={repo}
              type="button"
              className={`kanban-v2__chip${repoFilter === repo ? ' kanban-v2__chip--active' : ''}`}
              onClick={() => setRepoFilterPersisted(repo === repoFilter ? '' : repo)}
              title={repo}
            >
              {repo.split('/').pop()}
              <span className="kanban-v2__chip-count">{count}</span>
            </button>
          ))}
        </div>
      )}

      {/* IMPORTANT: do NOT spread {...props} here — it would re-pass the
          unfiltered `prs` and clobber `filteredLanePrs`. Pass each callback
          explicitly so the filter actually applies. */}
      {mode === 'team' && props.teamData ? (
        <TeamKanbanBoard
          teamData={props.teamData}
          selectedId={props.selectedId}
          collapsed={collapsed}
          onToggle={toggle}
          onSelect={props.onSelect}
          onHandle={props.onHandle}
          onReopen={props.onReopen}
        />
      ) : lane === 'mine'
        ? (
          <MyPrsBoard
            prs={filteredLanePrs}
            selectedId={props.selectedId}
            collapsed={collapsed}
            onToggle={toggle}
            onSelect={props.onSelect}
            onHandle={props.onHandle}
            onReopen={props.onReopen}
            cardMode={mode}
          />
        )
        : (
          <ToReviewBoard
            prs={filteredLanePrs}
            selectedId={props.selectedId}
            collapsed={collapsed}
            onToggle={toggle}
            onSelect={props.onSelect}
            onHandle={props.onHandle}
            onReopen={props.onReopen}
            cardMode={mode}
          />
        )}
    </div>
  );
}

/** Server-paginated team kanban — renders from pre-bucketed columns
 *  and totals; each "+ N more" click fetches the next page from
 *  TeamDetailPage's onLoadMoreColumn callback. Does no client-side
 *  categorization (the backend already did it). */
function TeamKanbanBoard({
  teamData,
  selectedId,
  collapsed,
  onToggle,
  onSelect,
  onHandle,
  onReopen,
}: {
  teamData: NonNullable<Props['teamData']>;
  selectedId: number | null;
  collapsed: CollapsedMap;
  onToggle: (kind: KanbanColumnKind) => void;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
  onReopen: (prId: number) => void;
}) {
  // Team kanban uses the extended column list — adds the trailing
  // "Handled" column for dismissed cards.
  const gridTemplate = MY_PR_COLUMNS_TEAM.map(col => {
    const total = teamData.totals[col] ?? 0;
    return columnSize(col, collapsed[col] ?? false, total);
  }).join(' ');

  return (
    <div className="kanban-board kanban-board--mine" style={{ gridTemplateColumns: gridTemplate }}>
      {MY_PR_COLUMNS_TEAM.map(col => {
        const loaded = teamData.columns[col] ?? [];
        const total = teamData.totals[col] ?? 0;
        return (
          <KanbanColumn
            key={col}
            kind={col}
            label={MY_PR_COLUMN_LABEL[col]}
            prs={loaded}
            totalCount={total}
            onLoadMore={() => teamData.onLoadMoreColumn(col)}
            selectedId={selectedId}
            collapsed={collapsed[col] ?? false}
            yourMove={col === 'needs_changes' ? 'caution' : col === 'ready_to_merge' ? 'go' : undefined}
            onToggle={() => onToggle(col)}
            onSelect={onSelect}
            onHandle={onHandle}
            onReopen={onReopen}
            cardMode="team"
          />
        );
      })}
    </div>
  );
}

// ── Lane boards ────────────────────────────────────────────────────────────

type BoardProps = Omit<Props, 'prs'> & {
  prs: PullRequestDto[];
  collapsed: CollapsedMap;
  onToggle: (kind: KanbanColumnKind) => void;
  /** Forwarded to KanbanColumn → KanbanCard so cards can decide whether
   *  to show the repo avatar + author chip (team mode does, inbox doesn't
   *  since the user is implicitly the author of My-PRs cards). */
  cardMode?: 'inbox' | 'team';
};

function MyPrsBoard({ prs, selectedId, collapsed, onToggle, onSelect, onHandle, onReopen, cardMode }: BoardProps) {
  const groups = useMemo(() => groupMyPrs(prs), [prs]);
  const gridTemplate = MY_PR_COLUMNS.map(col => columnSize(col, collapsed[col] ?? false, groups[col].length)).join(' ');

  return (
    <div className="kanban-board kanban-board--mine" style={{ gridTemplateColumns: gridTemplate }}>
      {MY_PR_COLUMNS.map(col => (
        <KanbanColumn
          key={col}
          kind={col}
          label={MY_PR_COLUMN_LABEL[col]}
          prs={groups[col]}
          selectedId={selectedId}
          collapsed={collapsed[col] ?? false}
          yourMove={col === 'needs_changes' ? 'caution' : col === 'ready_to_merge' ? 'go' : undefined}
          onToggle={() => onToggle(col)}
          onSelect={onSelect}
          onHandle={onHandle}
          onReopen={onReopen}
          cardMode={cardMode}
        />
      ))}
    </div>
  );
}

function ToReviewBoard({ prs, selectedId, collapsed, onToggle, onSelect, onHandle, onReopen, cardMode }: BoardProps) {
  const groups = useMemo(() => groupToReview(prs), [prs]);
  const gridTemplate = TO_REVIEW_COLUMNS.map(col => columnSize(col, collapsed[col] ?? false, groups[col].length)).join(' ');

  return (
    <div className="kanban-board kanban-board--to-review" style={{ gridTemplateColumns: gridTemplate }}>
      {TO_REVIEW_COLUMNS.map(col => (
        <KanbanColumn
          key={col}
          kind={col}
          label={TO_REVIEW_COLUMN_LABEL[col]}
          prs={groups[col]}
          selectedId={selectedId}
          collapsed={collapsed[col] ?? false}
          yourMove={col === 'needs_attention' && groups[col].length > 0 ? 'caution' : undefined}
          onToggle={() => onToggle(col)}
          onSelect={onSelect}
          onHandle={onHandle}
          onReopen={onReopen}
          cardMode={cardMode}
        />
      ))}
    </div>
  );
}

function columnSize(kind: MyPrColumn | ToReviewColumn, collapsed: boolean, count: number): string {
  if (collapsed) return '38px';
  // Empty wide-purpose columns shrink to half a column so they don't
  // hog space, but still leave a visible header. The hot columns
  // (in_progress, needs_changes, ready_to_merge, needs_attention) get
  // extra weight when populated.
  const empty = count === 0;
  switch (kind) {
    case 'drafting':
    case 'recently_merged':
    case 'cleared_today':
    case 'handled':
      return empty ? '0.6fr' : '0.9fr';
    case 'in_progress':
    case 'needs_changes':
      return '1.4fr';
    case 'needs_attention':
      return '1.3fr';
    case 'ready_to_merge':
      return '1.2fr';
    default:
      return '1fr';
  }
}

// ── Morning briefing ───────────────────────────────────────────────────────

type Briefing = {
  mineTotal: number;
  mineNeedsAction: number;        // needs_changes + ready_to_merge
  mineReadyToMerge: number;
  mineNeedsChanges: number;
  toReviewTotal: number;
  toReviewNeedsAttention: number;
  toReviewInProgress: number;
};

function buildBriefing(prs: PullRequestDto[]): Briefing {
  const myPrs = prs.filter(p => p.origin === 'AUTHORED');
  const toReview = prs.filter(p => p.origin === 'REVIEW_REQUESTED');
  const myGroups = groupMyPrs(myPrs);
  const trGroups = groupToReview(toReview);
  return {
    mineTotal: Object.values(myGroups).reduce((s, l) => s + l.length, 0),
    mineNeedsAction: myGroups.needs_changes.length + myGroups.ready_to_merge.length,
    mineReadyToMerge: myGroups.ready_to_merge.length,
    mineNeedsChanges: myGroups.needs_changes.length,
    toReviewTotal: Object.values(trGroups).reduce((s, l) => s + l.length, 0),
    toReviewNeedsAttention: trGroups.needs_attention.length,
    toReviewInProgress: trGroups.in_progress.length,
  };
}

/** Counts PRs of a given origin that fall into `predicate`'s column. */
function countIn(
  prs: PullRequestDto[],
  origin: 'AUTHORED' | 'REVIEW_REQUESTED',
  predicate: (pr: PullRequestDto) => boolean,
): number {
  return prs.filter(p => p.origin === origin && predicate(p)).length;
}
const categorizeMyPr_recentlyMerged = (pr: PullRequestDto) =>
  categorizeMyPr(pr) === 'recently_merged';
const categorizeToReview_clearedToday = (pr: PullRequestDto) =>
  categorizeToReview(pr) === 'cleared_today';

/** Right-side segmented control. "Active" collapses the recent column;
 *  "Recently closed" expands it. Stays synced with the column's
 *  collapsed state so toggling either side flips both visually. */
function RecentSegment({
  lane,
  counts,
  collapsed,
  onToggle,
}: {
  lane: Lane;
  counts: { mineActive: number; mineRecent: number; toReviewActive: number; toReviewRecent: number };
  collapsed: CollapsedMap;
  onToggle: (kind: KanbanColumnKind) => void;
}) {
  const recentCol: KanbanColumnKind = lane === 'mine' ? 'recently_merged' : 'cleared_today';
  const recentExpanded = !(collapsed[recentCol] ?? true);
  const activeCount = lane === 'mine' ? counts.mineActive : counts.toReviewActive;
  const recentCount = lane === 'mine' ? counts.mineRecent : counts.toReviewRecent;
  const recentLabel = lane === 'mine' ? 'Recently merged' : 'Recently closed';
  return (
    <div className="kanban-v2__seg" role="tablist" aria-label="Show active or recently-closed PRs">
      <button
        type="button"
        role="tab"
        aria-selected={!recentExpanded}
        className={`kanban-v2__seg-btn${!recentExpanded ? ' kanban-v2__seg-btn--active' : ''}`}
        onClick={() => { if (recentExpanded) onToggle(recentCol); }}
      >
        Active
        <span className="kanban-v2__seg-count">{activeCount}</span>
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={recentExpanded}
        className={`kanban-v2__seg-btn${recentExpanded ? ' kanban-v2__seg-btn--active' : ''}`}
        onClick={() => { if (!recentExpanded) onToggle(recentCol); }}
      >
        {recentLabel}
        <span className="kanban-v2__seg-count">{recentCount}</span>
      </button>
    </div>
  );
}

function MorningBriefing({ briefing, activeLane, onJumpToLane }: {
  briefing: Briefing;
  activeLane: Lane;
  onJumpToLane: (lane: Lane) => void;
}) {
  const parts: string[] = [];
  if (briefing.mineNeedsChanges > 0) {
    parts.push(`${briefing.mineNeedsChanges} of your PR${briefing.mineNeedsChanges === 1 ? '' : 's'} need${briefing.mineNeedsChanges === 1 ? 's' : ''} changes`);
  }
  if (briefing.mineReadyToMerge > 0) {
    parts.push(`${briefing.mineReadyToMerge} ready to merge`);
  }
  if (briefing.toReviewNeedsAttention > 0) {
    parts.push(`${briefing.toReviewNeedsAttention} PR${briefing.toReviewNeedsAttention === 1 ? '' : 's'} need${briefing.toReviewNeedsAttention === 1 ? 's' : ''} your review`);
  }
  if (parts.length === 0 && briefing.toReviewInProgress > 0) {
    parts.push(`${briefing.toReviewInProgress} review${briefing.toReviewInProgress === 1 ? '' : 's'} in progress`);
  }
  const summary = parts.length === 0
    ? 'Inbox zero — nothing needs you right now.'
    : parts.join(' · ');

  return (
    <div className="kanban-v2__briefing" role="status">
      <span className="kanban-v2__briefing-icon" aria-hidden="true">☀</span>
      <span className="kanban-v2__briefing-text">{summary}</span>
      <div className="kanban-v2__briefing-actions">
        {briefing.mineNeedsAction > 0 && activeLane !== 'mine' && (
          <button
            type="button"
            className="kanban-v2__briefing-chip kanban-v2__briefing-chip--warn"
            onClick={() => onJumpToLane('mine')}
          >
            ⚠ Address feedback ({briefing.mineNeedsAction})
          </button>
        )}
        {briefing.toReviewNeedsAttention > 0 && activeLane !== 'to_review' && (
          <button
            type="button"
            className="kanban-v2__briefing-chip kanban-v2__briefing-chip--go"
            onClick={() => onJumpToLane('to_review')}
          >
            → Start review ({briefing.toReviewNeedsAttention})
          </button>
        )}
      </div>
    </div>
  );
}

export default KanbanBoard;
