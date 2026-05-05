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
  groupMyPrs,
  groupToReview,
} from '../prBuckets';
import KanbanColumn, { type KanbanColumnKind } from './KanbanColumn';
import KanbanPrCard from './KanbanPrCard';

const COLLAPSED_KEY = 'settings:kanban-collapsed-v2';
export const LANE_KEY = 'settings:kanban-lane';
const REPO_FILTER_KEY = 'settings:kanban-repo-filter';

export type Lane = 'mine' | 'to_review';

type CollapsedMap = Partial<Record<KanbanColumnKind, boolean>>;

// Columns that start collapsed by default — both ends of each lane host
// "ambient" PRs (drafts on the left, recently-cleared on the right) that
// don't usually need a full-width column.
const DEFAULT_COLLAPSED: CollapsedMap = {
  drafting: true,
  // Handled is the user's "set aside" pile — collapsed by default so
  // it doesn't dominate the team page; click to expand and reopen.
  handled: true,
  // recently_merged + cleared_today used to default-collapsed because the
  // page-level Active/Recently-merged toggle was the primary way to
  // expand them. That toggle is gone; the kanban now shows the column
  // by default with its own internal windowing (cap + "+N more").
};

function loadCollapsed(): CollapsedMap {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY);
    if (raw) return { ...DEFAULT_COLLAPSED, ...(JSON.parse(raw) as CollapsedMap) };
  } catch { /* fall through */ }
  return DEFAULT_COLLAPSED;
}

export function loadLane(): Lane {
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
  onSnooze?: (prId: number, untilIso: string) => void;
  /** Controlled lane. The page header (PullRequestList) renders the
   *  My PRs / To review tabs, owns the persistence, and tells the
   *  kanban which lane to render. Team mode ignores this prop and
   *  forces 'mine'. */
  lane?: Lane;
  // onLaneChange went away with the morning-briefing strip — the page
  // header is the only thing that switches lanes now, and it owns the
  // setter directly.
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
  // Team mode is always "mine"; otherwise honour the controlled prop
  // from the page header. Falls back to localStorage for back-compat
  // with any caller that hasn't been migrated to the controlled API.
  const lane: Lane = mode === 'team' ? 'mine' : (props.lane ?? loadLane());
  const [collapsed, setCollapsed] = useState<CollapsedMap>(loadCollapsed);
  const [repoFilter, setRepoFilter] = useState<string>(loadRepoFilter);

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

  // Briefing was used by the now-removed MorningBriefing strip and the
  // team-mode header total. The team header still wants a count when no
  // teamData is provided (rare fallback for the legacy non-paginated
  // path); keep a minimal computation just for that single number.
  const minePrCount = useMemo(
    () => props.prs.filter(p => p.origin === 'AUTHORED').length,
    [props.prs],
  );

  return (
    <div className="kanban-v2">
      {/* The morning-briefing strip ("☀ N of your PRs need changes · M
          PRs need your review · → Start review") was removed: the
          Focus band below it surfaces the same urgent PRs as cards,
          and the red-dot alert on the page header's My PRs tab covers
          the same "act here" signal — three places saying the same
          thing was noise, not info. */}
      {mode === 'inbox' && (
        <FocusBand prs={props.prs} onSelect={props.onSelect} />
      )}

      {/* Team mode keeps a tiny "PRs" header. Inbox mode renders no
          toolbar of its own — the lane tabs (My PRs / To review /
          Teams) live in the page header (PullRequestList). */}
      {mode === 'team' && (
        <div className="kanban-v2__tab-toolbar">
          <h3 className="kanban-v2__lane-header">
            PRs
            <span className="kanban-v2__tab-count">
              {props.teamData
                ? Object.values(props.teamData.totals).reduce((s, n) => s + n, 0)
                : minePrCount}
            </span>
          </h3>
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
            onSnooze={props.onSnooze}
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
            onSnooze={props.onSnooze}
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
            yourMove={
              col === 'needs_changes' && total > 0 ? 'caution'
                : col === 'ready_to_merge' && total > 0 ? 'go'
                  : undefined
            }
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

function MyPrsBoard({ prs, selectedId, collapsed, onToggle, onSelect, onHandle, onReopen, onSnooze, cardMode }: BoardProps) {
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
          // URGENT (caution) and YOUR MOVE (go) flags only fire when
          // the column actually has work — design spec: "URGENT flag
          // pill when non-empty", "YOUR MOVE flag pill when non-empty".
          yourMove={
            col === 'needs_changes' && groups[col].length > 0 ? 'caution'
              : col === 'ready_to_merge' && groups[col].length > 0 ? 'go'
                : undefined
          }
          onToggle={() => onToggle(col)}
          onSelect={onSelect}
          onHandle={onHandle}
          onReopen={onReopen}
          onSnooze={onSnooze}
          cardMode={cardMode}
          // RECENTLY MERGED column gets a "View full merge history →"
          // CTA pinned to the bottom. The history page itself is a
          // deferred follow-up — render the CTA disabled for now so
          // the slot is in place once the page lands.
          footerCta={
            col === 'recently_merged'
              ? {
                  label: 'View full merge history →',
                  subtitle: 'all time · filterable',
                  disabled: true,
                }
              : undefined
          }
        />
      ))}
    </div>
  );
}

function ToReviewBoard({ prs, selectedId, collapsed, onToggle, onSelect, onHandle, onReopen, onSnooze, cardMode }: BoardProps) {
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
          onSnooze={onSnooze}
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


/**
 * Picking algorithm for the focus band — "what should I touch first".
 * Per docs/mockups/v2/pr-dashboard/SUMMARY.md:
 *   NEEDS CHANGES > CI failing > Merge conflict > stale (>7d no review)
 *   > YOUR-MOVE ready-to-merge
 * Cap 4. Each tier filters down candidates of any origin (My PRs and
 * To Review both compete for the slots — the band is about urgency,
 * not lane). Stable order within a tier = oldest-updatedAt first
 * (most stale = most urgent within the same kind).
 */
function pickFocusCards(prs: PullRequestDto[], now: number = Date.now()): PullRequestDto[] {
  const STALE_MS = 7 * 24 * 60 * 60 * 1000;
  const seen = new Set<number>();
  const out: PullRequestDto[] = [];
  const pushUnique = (pr: PullRequestDto) => {
    if (seen.has(pr.id) || out.length >= 4) return;
    seen.add(pr.id);
    out.push(pr);
  };

  // Skip merged / closed / draft / handled — they're not actionable now.
  const eligible = prs.filter(pr =>
    !pr.mergedAt
    && pr.state !== 'closed'
    && !pr.draft
    && pr.handledAction !== 'MERGED'
    && pr.handledAction !== 'DISMISSED'
    && pr.handledAction !== 'MANUAL');

  const byUpdatedAsc = (a: PullRequestDto, b: PullRequestDto) =>
    new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime();

  // Tier 1: NEEDS CHANGES — author has work to do (My PRs only).
  eligible
    .filter(pr =>
      pr.origin === 'AUTHORED'
      && Object.values(pr.reviewerVerdicts ?? {}).includes('CHANGES_REQUESTED'))
    .sort(byUpdatedAsc)
    .forEach(pushUnique);

  // Tier 2: CI failing.
  eligible
    .filter(pr => pr.attentionReason === 'CI_FAILING' || pr.ciStatus === 'FAILING')
    .sort(byUpdatedAsc)
    .forEach(pushUnique);

  // Tier 3: merge conflict — explicit attention reason or mergeable=false.
  eligible
    .filter(pr => pr.attentionReason === 'MERGE_CONFLICT' || pr.mergeable === false)
    .sort(byUpdatedAsc)
    .forEach(pushUnique);

  // Tier 4: stale — no reviewer verdicts AND last update >7 days ago.
  eligible
    .filter(pr => {
      const stale = now - new Date(pr.updatedAt).getTime() > STALE_MS;
      const noVerdicts = Object.keys(pr.reviewerVerdicts ?? {}).length === 0;
      return stale && noVerdicts;
    })
    .sort(byUpdatedAsc)
    .forEach(pushUnique);

  // Tier 5: YOUR-MOVE — ready-to-merge on My PRs (approval + CI green
  // + not blocked). Same conditions as categorizeMyPr's ready_to_merge.
  eligible
    .filter(pr => {
      if (pr.origin !== 'AUTHORED') return false;
      const verdicts = Object.values(pr.reviewerVerdicts ?? {});
      const hasApproval = verdicts.includes('APPROVED');
      const hasChanges = verdicts.includes('CHANGES_REQUESTED');
      return hasApproval && !hasChanges && pr.ciStatus === 'PASSING' && pr.mergeable !== false;
    })
    .sort(byUpdatedAsc)
    .forEach(pushUnique);

  return out;
}

/**
 * The 🔥 focus band — a curated horizontal row above the kanban that
 * answers "what should I touch first" before the user has scrolled.
 * Same card style as the kanban below; this is visual duplication on
 * purpose. Hidden when no card qualifies.
 *
 * The "Customize…" link is a placeholder — we'll wire it when the
 * snooze feature ships and per-card snooze becomes available from
 * the band.
 */
function FocusBand({ prs, onSelect }: {
  prs: PullRequestDto[];
  onSelect: (pr: PullRequestDto) => void;
}) {
  const cards = useMemo(() => pickFocusCards(prs), [prs]);
  if (cards.length === 0) return null;
  return (
    <section className="focus-band" aria-label="Needs your urgent attention">
      <header className="focus-band__head">
        <span className="focus-band__title">🔥 Needs your urgent attention</span>
        <span className="focus-band__count">{cards.length}</span>
        <span className="focus-band__subtitle">
          most blocking first · same cards live in the kanban below
        </span>
        <button
          type="button"
          className="focus-band__customize"
          disabled
          title="Coming with the snooze feature"
        >
          Customize…
        </button>
      </header>
      <div className="focus-band__row">
        {cards.map(pr => (
          <KanbanPrCard
            key={pr.id}
            pr={pr}
            // 'needs_attention' triggers the urgent banner styling on
            // the card; the picking algorithm already filters to
            // genuinely-blocking work.
            column="needs_attention"
            mode="inbox"
            selected={false}
            onSelect={() => onSelect(pr)}
          />
        ))}
      </div>
    </section>
  );
}

export default KanbanBoard;
