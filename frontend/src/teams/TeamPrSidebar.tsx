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
import type { MyPrColumnSlug, PullRequestDto, TeamColumnsResponse } from '../types';
import { InboxCard, HandledCard } from '../PrBucketViews';
import { MY_PR_COLUMNS_TEAM, MY_PR_COLUMN_LABEL, byUpdatedAtDesc } from '../prBuckets';

type Props = {
  data: TeamColumnsResponse;
  /** Active per-repo filter. {@code null} → ALL (no filter); else the
   *  full {@code owner/repo} name. Filter is applied client-side over
   *  whichever pages of each column have already been loaded — backend
   *  pagination isn't filter-aware, so the "+ N more" affordance is
   *  hidden while a filter is active to avoid surfacing a count that
   *  doesn't match the visible items. */
  repoFilter: string | null;
  selectedId: number | null;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
  onReopen: (prId: number) => void;
  /** "+ N more" — fetches the next page of one column from the backend. */
  onLoadMore: (column: MyPrColumnSlug) => void;
};

/**
 * Sidebar PR list for the team detail page. Mirrors the categorized
 * sidebar shown on MY PRS, but groups by team-kanban columns instead of
 * inbox categories. Each section is collapsible; the section containing
 * the selected PR auto-expands.
 */
function TeamPrSidebar({ data, repoFilter, selectedId, onSelect, onHandle, onReopen, onLoadMore }: Props) {
  const activeColumn: MyPrColumnSlug | null = useMemo(() => {
    if (selectedId === null) return null;
    for (const col of MY_PR_COLUMNS_TEAM) {
      if (data.columns[col].some(p => p.id === selectedId)) return col;
    }
    return null;
  }, [data, selectedId]);

  const [open, setOpen] = useState<Record<MyPrColumnSlug, boolean>>(() => {
    const init: Record<MyPrColumnSlug, boolean> = {
      drafting: false,
      waiting_on_review: false,
      needs_changes: false,
      ready_to_merge: false,
      recently_merged: false,
      handled: false,
    };
    if (activeColumn) init[activeColumn] = true;
    else init.waiting_on_review = true;
    return init;
  });

  useEffect(() => {
    if (!activeColumn) return;
    setOpen(prev => {
      const next = { ...prev };
      for (const col of MY_PR_COLUMNS_TEAM) next[col] = col === activeColumn;
      return next;
    });
  }, [activeColumn]);

  // When a repo filter activates, open every column ONCE so the user
  // sees the full filtered result without expanding section-by-section.
  // After this seed-open, the user's manual fold/expand on the chevron
  // works normally — `open[col]` is the source of truth, no override
  // during render. Clearing the filter doesn't reset state on its own.
  useEffect(() => {
    if (repoFilter === null) return;
    setOpen({
      drafting: true,
      waiting_on_review: true,
      needs_changes: true,
      ready_to_merge: true,
      recently_merged: true,
      handled: true,
    });
  }, [repoFilter]);

  const toggle = (col: MyPrColumnSlug) =>
    setOpen(prev => ({ ...prev, [col]: !prev[col] }));

  return (
    <div className="categorized-list">
      {MY_PR_COLUMNS_TEAM.map(col => {
        const allItems = data.columns[col] ?? [];
        const filtered = repoFilter === null
          ? allItems
          : allItems.filter(p => p.repo === repoFilter);
        // Active columns sort by updatedAt DESC so the most-recently-
        // touched PR sits on top — matches the My-PRs / To-review
        // kanban convention. Handled / recently-merged stay in backend
        // order (already newest-first activity feeds).
        const items = (col === 'handled' || col === 'recently_merged')
          ? filtered
          : [...filtered].sort(byUpdatedAtDesc);
        // When a repo filter is active we only know the visible-page
        // count; backend pagination doesn't ship per-repo totals, so
        // surface that count and skip the "+ N more" affordance below.
        const total = repoFilter === null
          ? (data.totals[col] ?? allItems.length)
          : items.length;
        const isOpen = open[col];
        const dotClass = `cat-group--${col.replace(/_/g, '-')}`;
        return (
          <section key={col} className={`cat-group ${dotClass}`}>
            <button
              type="button"
              className="cat-group__header"
              onClick={() => toggle(col)}
              aria-expanded={isOpen}
            >
              <span className={`cat-group__chevron${isOpen ? '' : ' cat-group__chevron--collapsed'}`} aria-hidden="true">▾</span>
              <span className="cat-group__dot" aria-hidden="true" />
              <span className="cat-group__title">{MY_PR_COLUMN_LABEL[col]}</span>
              <span className="cat-group__count">{total}</span>
            </button>
            {isOpen && (
              <div className="cat-group__body">
                {items.length === 0 ? (
                  <div className="cat-group__empty">No PRs here.</div>
                ) : (
                  <>
                    {items.map(pr => (
                      col === 'handled' || col === 'recently_merged' ? (
                        <HandledCard
                          key={pr.id}
                          pr={pr}
                          selected={selectedId === pr.id}
                          onSelect={() => onSelect(pr)}
                          onReopen={() => onReopen(pr.id)}
                        />
                      ) : (
                        <InboxCard
                          key={pr.id}
                          pr={pr}
                          selected={selectedId === pr.id}
                          onSelect={() => onSelect(pr)}
                          onHandle={() => onHandle(pr.id)}
                        />
                      )
                    ))}
                    {repoFilter === null && items.length < total && (
                      <button
                        type="button"
                        className="cat-group__more"
                        onClick={() => onLoadMore(col)}
                      >
                        + {total - items.length} more
                      </button>
                    )}
                  </>
                )}
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}

export default TeamPrSidebar;
