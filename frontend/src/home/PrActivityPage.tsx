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
import { useEffect, useState } from 'react';
import type { PullRequestDto } from '../types';
import Avatar from '../Avatar';
import { relativeTime } from '../notificationDisplay';

export type PrActivityKind = 'reviewed' | 'contributed';
export type PrActivityPeriod = 'today' | 'week' | 'month';

const PER_PAGE = 30;

/** ISO date (local) the period reaches back to. */
export function periodSince(period: PrActivityPeriod, now: Date = new Date()): string {
  const d = new Date(now);
  if (period === 'week') d.setDate(d.getDate() - 7);
  if (period === 'month') d.setMonth(d.getMonth() - 1);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function stateChip(pr: PullRequestDto): { label: string; cls: string } {
  if (pr.state === 'merged' || pr.mergedAt !== null) return { label: 'merged', cls: 'merged' };
  if (pr.state === 'closed') return { label: 'closed', cls: 'closed' };
  return { label: pr.draft ? 'draft' : 'open', cls: 'open' };
}

type Props = {
  initialKind: PrActivityKind;
  onBack: () => void;
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
};

/** Full list of the PRs the user reviewed / contributed, backed by a
 *  live GitHub search with a today / past-week / past-month window. */
function PrActivityPage({ initialKind, onBack, onOpenPr }: Props) {
  const [kind, setKind] = useState<PrActivityKind>(initialKind);
  const [period, setPeriod] = useState<PrActivityPeriod>('week');
  const [items, setItems] = useState<PullRequestDto[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    window.bridge.fetchPrActivity(kind, periodSince(period), 1, PER_PAGE)
      .then(result => {
        if (cancelled) return;
        setItems(result.items);
        setTotalCount(result.totalCount);
        setHasMore(result.hasMore);
        setPage(1);
      })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [kind, period]);

  const loadMore = () => {
    const next = page + 1;
    window.bridge.fetchPrActivity(kind, periodSince(period), next, PER_PAGE)
      .then(result => {
        setItems(prev => [...prev, ...result.items]);
        setHasMore(result.hasMore);
        setPage(next);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  };

  const openRow = (pr: PullRequestDto) => {
    const slash = pr.repo.indexOf('/');
    if (slash > 0) onOpenPr(pr.repo.slice(0, slash), pr.repo.slice(slash + 1), pr.number);
  };

  return (
    <div className="pr-activity">
      <header className="pr-activity__header">
        <button type="button" className="pr-activity__back" onClick={onBack} title="Back to home">
          ←
        </button>
        <h1 className="pr-activity__title">My PR activity</h1>
        {!loading && <span className="pr-activity__count">{totalCount} PRs</span>}
      </header>

      <div className="pr-activity__filters">
        <div className="pr-activity__seg" role="tablist" aria-label="Kind">
          {(['reviewed', 'contributed'] as PrActivityKind[]).map(k => (
            <button
              key={k}
              type="button"
              role="tab"
              aria-selected={kind === k}
              className={`pr-activity__seg-btn${kind === k ? ' pr-activity__seg-btn--on' : ''}`}
              onClick={() => setKind(k)}
            >
              {k === 'reviewed' ? 'Reviewed' : 'Contributed'}
            </button>
          ))}
        </div>
        <div className="pr-activity__seg" role="tablist" aria-label="Period">
          {(['today', 'week', 'month'] as PrActivityPeriod[]).map(p => (
            <button
              key={p}
              type="button"
              role="tab"
              aria-selected={period === p}
              className={`pr-activity__seg-btn${period === p ? ' pr-activity__seg-btn--on' : ''}`}
              onClick={() => setPeriod(p)}
            >
              {p === 'today' ? 'Today' : p === 'week' ? 'Past week' : 'Past month'}
            </button>
          ))}
        </div>
      </div>

      {error && <p className="pr-activity__error">{error}</p>}
      {loading ? (
        <p className="pr-activity__empty">Loading…</p>
      ) : items.length === 0 ? (
        <p className="pr-activity__empty">
          No PRs {kind} {period === 'today' ? 'today' : period === 'week' ? 'in the past week' : 'in the past month'}.
        </p>
      ) : (
        <div className="pr-activity__list">
          {items.map(pr => {
            const chip = stateChip(pr);
            return (
              <button
                key={pr.id}
                type="button"
                className="pr-activity__row"
                onClick={() => openRow(pr)}
              >
                <Avatar login={pr.repo.split('/')[0]} size={22} className="pr-activity__logo" />
                <span className="pr-activity__meta">
                  <span className="pr-activity__row-title">{pr.title}</span>
                  <span className="pr-activity__row-sub">
                    {pr.repo} #{pr.number}
                    {pr.author && kind === 'reviewed' ? ` · by ${pr.author}` : ''}
                  </span>
                </span>
                <span className={`pr-activity__chip pr-activity__chip--${chip.cls}`}>{chip.label}</span>
                <span className="pr-activity__time">{relativeTime(pr.updatedAt)}</span>
              </button>
            );
          })}
        </div>
      )}

      {!loading && hasMore && (
        <button type="button" className="pr-activity__more" onClick={loadMore}>
          Load more
        </button>
      )}
    </div>
  );
}

export default PrActivityPage;
