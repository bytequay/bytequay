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
import type { PullRequestDto } from './types';
import KanbanPrCard from './kanban/KanbanPrCard';
import LogoLoading from './LogoLoading';
import { formatRelative } from './prBuckets';

type Props = {
  onBack: () => void;
  onSelect: (pr: PullRequestDto) => void;
};

type StateFilter = 'all' | 'merged' | 'closed';

const PER_PAGE = 30;

/**
 * Full-history view of the user's merged + closed PRs. Hits GitHub
 * search live (paged) — the local sync only persists the last 7 days
 * of closed PRs, so anything older lives only at the source.
 *
 * Filters are client-side over the loaded pages: state (all / merged /
 * closed-without-merge) and per-repo chips. "Load more" pulls the
 * next page; we don't pre-fetch since each request burns search-API
 * quota.
 */
function MergeHistoryPage({ onBack, onSelect }: Props) {
  const [items, setItems] = useState<PullRequestDto[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stateFilter, setStateFilter] = useState<StateFilter>('all');
  const [repoFilter, setRepoFilter] = useState<string | null>(null);

  // First-page fetch on mount.
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    window.bridge.fetchPrHistory(1, PER_PAGE)
      .then(result => {
        if (cancelled) return;
        setItems(result.items);
        setHasMore(result.hasMore);
        setTotalCount(result.totalCount);
        setPage(result.page);
      })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const loadMore = async () => {
    if (loading || !hasMore) return;
    setLoading(true);
    setError(null);
    try {
      const next = await window.bridge.fetchPrHistory(page + 1, PER_PAGE);
      setItems(prev => [...prev, ...next.items]);
      setHasMore(next.hasMore);
      setTotalCount(next.totalCount);
      setPage(next.page);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  };

  // State filter: merged = mergedAt set; closed = state==='closed' && !mergedAt.
  const stateFiltered = useMemo(() => {
    if (stateFilter === 'all') return items;
    if (stateFilter === 'merged') return items.filter(pr => pr.mergedAt !== null);
    return items.filter(pr => pr.mergedAt === null);
  }, [items, stateFilter]);

  // Repo chips list — counted off the state-filtered set so chip totals
  // reflect what's actually visible after the state filter.
  const repoOptions = useMemo(() => {
    const counts = new Map<string, number>();
    for (const pr of stateFiltered) counts.set(pr.repo, (counts.get(pr.repo) ?? 0) + 1);
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  }, [stateFiltered]);

  const visible = useMemo(() => (
    repoFilter === null ? stateFiltered : stateFiltered.filter(p => p.repo === repoFilter)
  ), [stateFiltered, repoFilter]);

  const grouped = useMemo(() => groupByMonth(visible), [visible]);

  return (
    <div className="merge-history calm-page">
      <header className="merge-history__header">
        <button type="button" className="merge-history__back" onClick={onBack} title="Back to the kanban">
          ← Back
        </button>
        <h1 className="merge-history__title">Merge history</h1>
        <span className="merge-history__count">
          {items.length} loaded{totalCount > items.length ? ` of ~${totalCount}` : ''}
        </span>
      </header>

      <div className="merge-history__filters">
        <div className="merge-history__filter-group" role="tablist" aria-label="State filter">
          {(['all', 'merged', 'closed'] as StateFilter[]).map(f => (
            <button
              key={f}
              type="button"
              role="tab"
              aria-selected={stateFilter === f}
              className={`merge-history__filter${stateFilter === f ? ' merge-history__filter--active' : ''}`}
              onClick={() => setStateFilter(f)}
            >
              {f === 'all' ? 'All' : f === 'merged' ? 'Merged' : 'Closed'}
            </button>
          ))}
        </div>

        {repoOptions.length > 1 && (
          <div className="merge-history__chips">
            <span className="merge-history__chips-label">Repo:</span>
            <button
              type="button"
              className={`merge-history__chip${repoFilter === null ? ' merge-history__chip--active' : ''}`}
              onClick={() => setRepoFilter(null)}
            >
              All <span className="merge-history__chip-count">{stateFiltered.length}</span>
            </button>
            {repoOptions.map(([repo, count]) => (
              <button
                key={repo}
                type="button"
                className={`merge-history__chip${repoFilter === repo ? ' merge-history__chip--active' : ''}`}
                onClick={() => setRepoFilter(repo)}
              >
                {repo.split('/').pop()}{' '}
                <span className="merge-history__chip-count">{count}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {error && <div className="merge-history__error">{error}</div>}

      {grouped.map(([label, prs]) => (
        <section key={label} className="merge-history__group">
          <header className="merge-history__group-head">
            <h2 className="merge-history__group-title">{label}</h2>
            <span className="merge-history__group-count">{prs.length}</span>
          </header>
          <div className="merge-history__row">
            {prs.map(pr => (
              <article
                key={pr.id}
                className="merge-history__card"
                onClick={() => onSelect(pr)}
              >
                <KanbanPrCard
                  pr={pr}
                  column={pr.mergedAt ? 'recently_merged' : 'handled'}
                  mode="inbox"
                  selected={false}
                  onSelect={() => onSelect(pr)}
                />
                <div className="merge-history__card-meta">
                  {pr.mergedAt
                    ? `merged ${formatRelative(pr.mergedAt)}`
                    : `closed ${formatRelative(pr.closedAt)}`}
                </div>
              </article>
            ))}
          </div>
        </section>
      ))}

      {/* First-page load: page is empty, show the branded spinner full-frame
          so the user sees the same loading mark used elsewhere in the app. */}
      {loading && items.length === 0 && (
        <div className="merge-history__loading-full">
          <LogoLoading size={72} label="Loading merge history" />
        </div>
      )}

      {visible.length === 0 && !loading && !error && (
        <div className="merge-history__empty">
          {items.length === 0
            ? 'No closed PRs found in your history.'
            : 'No PRs match the current filters.'}
        </div>
      )}

      <footer className="merge-history__footer">
        {hasMore ? (
          loading ? (
            // Subsequent-page load: show the spinner inline at the bottom
            // where the next batch will appear, instead of swapping the
            // button label to "Loading…".
            <div className="merge-history__loading-inline">
              <LogoLoading size={40} label="Loading more" />
            </div>
          ) : (
            <button
              type="button"
              className="merge-history__more"
              onClick={() => void loadMore()}
            >
              Load more (page {page + 1})
            </button>
          )
        ) : items.length > 0 ? (
          <span className="merge-history__end">— end of history —</span>
        ) : null}
      </footer>
    </div>
  );
}

/** Bucket PRs by "Mon YYYY" of their close-date (mergedAt or closedAt).
 *  Preserves the input order within each group, and groups appear in
 *  the order their first member appears (so the first month-bucket is
 *  always the most recent one given the DESC server sort). */
function groupByMonth(prs: PullRequestDto[]): Array<[string, PullRequestDto[]]> {
  const out = new Map<string, PullRequestDto[]>();
  for (const pr of prs) {
    const ts = pr.mergedAt ?? pr.closedAt;
    const label = ts
      ? new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'long' })
      : 'Unknown date';
    if (!out.has(label)) out.set(label, []);
    out.get(label)!.push(pr);
  }
  return Array.from(out.entries());
}

export default MergeHistoryPage;
