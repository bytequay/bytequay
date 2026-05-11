import { useEffect, useMemo, useState } from 'react';
import type {
  PrAnalyticsKpiCardDto,
  PrAnalyticsScope,
  PrAnalyticsStaleAuthoredPrDto,
  PrAnalyticsSummaryDto,
} from './types';

const SCOPE_OPTIONS: { value: PrAnalyticsScope; label: string }[] = [
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
  { value: '90d', label: '90d' },
  { value: 'all', label: 'All' },
];

type Props = {
  /** Allows the parent to open a PR detail page from a stale-PR row.
   *  When omitted, rows are still clickable but route nowhere. */
  onOpenPr?: (repo: string, number: number) => void;
};

function PrAnalyticsPage({ onOpenPr }: Props) {
  const [scope, setScope] = useState<PrAnalyticsScope>('30d');
  const [data, setData] = useState<PrAnalyticsSummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    window.bridge.fetchPrAnalytics(scope)
      .then(result => {
        if (cancelled) return;
        setData(result);
      })
      .catch(e => {
        if (cancelled) return;
        setError(e?.message ?? 'Failed to load analytics');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [scope]);

  const scopeChipLabel = useMemo(() => {
    const n = data?.watchedRepoCount ?? 0;
    return `Scope: ${n} watched repo${n === 1 ? '' : 's'} · 90d cap for unwatched`;
  }, [data]);

  return (
    <div className="analytics-page">
      <header className="analytics-page__header">
        <div className="analytics-page__crumbs">
          <span className="analytics-page__crumb-back">Pull requests</span>
          <span className="analytics-page__crumb-sep" aria-hidden="true">›</span>
          <span className="analytics-page__crumb-current">Analytics</span>
        </div>
        <div className="analytics-page__controls">
          <span className="analytics-page__privacy-pill" title="All numbers are computed from data on your Mac only.">
            🔒 Local only — never leaves your computer
          </span>
          <span className="analytics-page__scope-chip">{scopeChipLabel}</span>
          <div className="analytics-page__scope-toggle" role="tablist" aria-label="Time scope">
            {SCOPE_OPTIONS.map(opt => (
              <button
                key={opt.value}
                type="button"
                role="tab"
                aria-selected={scope === opt.value}
                className={`analytics-page__scope-btn${scope === opt.value ? ' analytics-page__scope-btn--active' : ''}`}
                onClick={() => setScope(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <button
            type="button"
            className="analytics-page__export-btn"
            title="CSV export — coming with the review-mirror milestone."
            disabled
          >
            Export CSV
          </button>
        </div>
      </header>

      {loading && <div className="analytics-page__loading">Loading…</div>}
      {error && <div className="analytics-page__error">{error}</div>}

      {!loading && !error && data && (
        <div className="analytics-page__body">
          <KpiRow data={data} />

          <section className="analytics-page__panel analytics-page__panel--pending">
            <h2 className="analytics-page__panel-title">Daily review activity</h2>
            <p className="analytics-page__panel-empty">
              Stacked-bar chart — pending review mirror.
            </p>
          </section>

          <div className="analytics-page__grid analytics-page__grid--two">
            <section className="analytics-page__panel analytics-page__panel--pending">
              <h2 className="analytics-page__panel-title">Review outcomes</h2>
              <p className="analytics-page__panel-empty">
                Donut breakdown (approved / changes / commented) — pending review mirror.
              </p>
            </section>
            <section className="analytics-page__panel analytics-page__panel--pending">
              <h2 className="analytics-page__panel-title">PR size distribution</h2>
              <p className="analytics-page__panel-empty">
                Tiny / small / medium / large / huge buckets with median time-to-review — pending review mirror.
              </p>
            </section>
          </div>

          <section className="analytics-page__panel analytics-page__panel--pending">
            <h2 className="analytics-page__panel-title">When you review</h2>
            <p className="analytics-page__panel-empty">
              Day-of-week × hour-of-day heatmap — pending review mirror.
            </p>
          </section>

          <div className="analytics-page__grid analytics-page__grid--three">
            <section className="analytics-page__panel analytics-page__panel--pending">
              <h2 className="analytics-page__panel-title">Repos by review activity</h2>
              <p className="analytics-page__panel-empty">Pending review mirror.</p>
            </section>
            <section className="analytics-page__panel analytics-page__panel--pending">
              <h2 className="analytics-page__panel-title">Review network</h2>
              <p className="analytics-page__panel-empty">Pending review mirror.</p>
            </section>
            <StaleAuthoredCard prs={data.staleAuthoredPrs} onOpenPr={onOpenPr} />
          </div>

          <WhatsMeasuredHereCard />
        </div>
      )}
    </div>
  );
}

function KpiRow({ data }: { data: PrAnalyticsSummaryDto }) {
  return (
    <div className="analytics-page__kpi-row">
      <KpiCardView title="PRs reviewed" card={data.prsReviewed} />
      <KpiCardView title="Approval rate" card={data.approvalRate} />
      <KpiCardView title="Lines reviewed" card={data.linesReviewed} />
      <KpiCardView title="Response to review request" card={data.responseToReviewRequest} />
    </div>
  );
}

function KpiCardView({ title, card }: { title: string; card: PrAnalyticsKpiCardDto }) {
  const isPending = card.pendingNote !== null && card.pendingNote !== undefined;
  return (
    <div className={`analytics-kpi${isPending ? ' analytics-kpi--pending' : ''}`}>
      <div className="analytics-kpi__label">
        {title}
        {card.partial && !isPending && (
          <sup
            className="analytics-kpi__partial-marker"
            title="Computed from PRs with cached detail only. See 'What's measured here' below."
          >
            ¹
          </sup>
        )}
      </div>
      <div className="analytics-kpi__value">
        {isPending ? <span className="analytics-kpi__pending">{card.pendingNote}</span> : card.displayValue}
      </div>
    </div>
  );
}

function StaleAuthoredCard({
  prs,
  onOpenPr,
}: {
  prs: PrAnalyticsStaleAuthoredPrDto[];
  onOpenPr?: (repo: string, number: number) => void;
}) {
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">Wait-times you experience</h2>
      <p className="analytics-page__panel-subtitle">
        Open PRs you authored, sitting unreviewed for more than 7 days.
      </p>
      {prs.length === 0 ? (
        <p className="analytics-page__panel-empty">
          No stale PRs — nothing of yours is older than a week.
        </p>
      ) : (
        <ul className="analytics-stale-list">
          {prs.map(pr => (
            <li key={pr.id} className="analytics-stale-row">
              <button
                type="button"
                className="analytics-stale-row__link"
                onClick={() => onOpenPr?.(pr.repo, pr.number)}
                title={`${pr.repo} #${pr.number}`}
              >
                <span className="analytics-stale-row__age">{pr.ageDays}d</span>
                <span className="analytics-stale-row__title">{pr.title}</span>
                <span className="analytics-stale-row__repo">{pr.repo} #{pr.number}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function WhatsMeasuredHereCard() {
  return (
    <section className="analytics-page__panel analytics-page__panel--measured">
      <h2 className="analytics-page__panel-title">What's measured here</h2>
      <div className="analytics-measured__grid">
        <div className="analytics-measured__col">
          <h3 className="analytics-measured__col-title">What we count</h3>
          <ul>
            <li>
              <strong>PRs reviewed ¹</strong> — distinct PRs where you submitted at least one review,
              over PRs we have cached detail for.
            </li>
            <li>
              <strong>Approval rate ¹</strong> — of those PRs, the share whose latest verdict from you was
              <em> approved</em>.
            </li>
            <li>
              <strong>Lines reviewed ¹</strong> — total additions + deletions across those PRs.
            </li>
            <li>
              <strong>Wait-times you experience</strong> — open PRs you authored that haven't moved for
              more than 7 days. Computed from the local PR list — complete for the watched set.
            </li>
          </ul>
        </div>
        <div className="analytics-measured__col">
          <h3 className="analytics-measured__col-title">What we deliberately don't measure</h3>
          <ul>
            <li>
              <strong>Active focus time on reviews</strong> — we don't track which tab you're looking at.
              Capped elapsed wall-clock is the only proxy we'd ever use, and only for bucket comparisons.
            </li>
            <li>
              <strong>Reviews on unwatched repos older than ~90 days</strong> — GitHub's events API drops
              past that window. The scope chip shows which subset you're seeing.
            </li>
            <li>
              <strong>Comments on PRs we never fetched detail for</strong> — counts come from cached
              detail only. Marked partial (¹).
            </li>
            <li>
              <strong>"Did the reviewer actually look"</strong> — GitHub doesn't expose it. We don't guess.
            </li>
            <li>
              <strong>Approval-likelihood scores or per-reviewer rankings</strong> — out of scope.
            </li>
            <li>
              <strong>Cross-engineer benchmarks</strong> — your numbers stay on your machine.
            </li>
          </ul>
        </div>
      </div>
    </section>
  );
}

export default PrAnalyticsPage;
