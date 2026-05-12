import { useEffect, useMemo, useState } from 'react';
import type {
  PrAnalyticsKpiCardDto,
  PrAnalyticsOutcomeSliceDto,
  PrAnalyticsRepoReviewCountDto,
  PrAnalyticsScope,
  PrAnalyticsSizeBucketDto,
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
            <ReviewOutcomesCard slices={data.reviewOutcomes} />
            <SizeDistributionCard buckets={data.sizeDistribution} />
          </div>

          <section className="analytics-page__panel analytics-page__panel--pending">
            <h2 className="analytics-page__panel-title">When you review</h2>
            <p className="analytics-page__panel-empty">
              Day-of-week × hour-of-day heatmap — pending review mirror.
            </p>
          </section>

          <div className="analytics-page__grid analytics-page__grid--three">
            <ReposByReviewCard rows={data.reposByReview} />
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

const OUTCOME_LABELS: Record<string, string> = {
  APPROVED: 'Approved',
  CHANGES_REQUESTED: 'Changes requested',
  COMMENTED: 'Commented',
  DISMISSED: 'Dismissed',
};

const OUTCOME_COLORS: Record<string, string> = {
  APPROVED: '#2f9e6e',
  CHANGES_REQUESTED: '#b3261e',
  COMMENTED: '#7a705d',
  DISMISSED: '#9aa0a6',
};

function ReviewOutcomesCard({ slices }: { slices: PrAnalyticsOutcomeSliceDto[] }) {
  const total = useMemo(() => slices.reduce((acc, s) => acc + s.count, 0), [slices]);
  if (total === 0) {
    return (
      <section className="analytics-page__panel">
        <h2 className="analytics-page__panel-title">
          Review outcomes
          <PartialMarker />
        </h2>
        <p className="analytics-page__panel-empty">No reviews in this window yet.</p>
      </section>
    );
  }
  const cumulative: { state: string; from: number; to: number; count: number }[] = [];
  let running = 0;
  for (const slice of slices) {
    if (slice.count === 0) continue;
    cumulative.push({
      state: slice.state,
      from: running / total,
      to: (running + slice.count) / total,
      count: slice.count,
    });
    running += slice.count;
  }
  const radius = 48;
  const inner = 30;
  const circumference = 2 * Math.PI * radius;
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        Review outcomes
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Latest verdict per PR you reviewed.
      </p>
      <div className="analytics-donut">
        <svg viewBox="-60 -60 120 120" aria-label="Review outcomes donut">
          {cumulative.map(seg => {
            const offset = -seg.from * circumference;
            const dash = (seg.to - seg.from) * circumference;
            return (
              <circle
                key={seg.state}
                r={radius}
                cx={0}
                cy={0}
                fill="none"
                stroke={OUTCOME_COLORS[seg.state] ?? '#7a705d'}
                strokeWidth={radius - inner}
                strokeDasharray={`${dash} ${circumference - dash}`}
                strokeDashoffset={offset}
                transform="rotate(-90)"
              />
            );
          })}
          <text x={0} y={4} textAnchor="middle" className="analytics-donut__total">
            {total}
          </text>
        </svg>
        <ul className="analytics-donut__legend">
          {slices.filter(s => s.count > 0).map(slice => {
            const pct = Math.round((slice.count / total) * 100);
            return (
              <li key={slice.state} className="analytics-donut__legend-row">
                <span
                  className="analytics-donut__legend-swatch"
                  style={{ background: OUTCOME_COLORS[slice.state] ?? '#7a705d' }}
                  aria-hidden="true"
                />
                <span className="analytics-donut__legend-label">
                  {OUTCOME_LABELS[slice.state] ?? slice.state}
                </span>
                <span className="analytics-donut__legend-count">
                  {slice.count} <span className="analytics-donut__legend-pct">({pct}%)</span>
                </span>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}

function SizeDistributionCard({ buckets }: { buckets: PrAnalyticsSizeBucketDto[] }) {
  const max = Math.max(1, ...buckets.map(b => b.count));
  const total = buckets.reduce((acc, b) => acc + b.count, 0);
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        PR size distribution
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Of PRs you reviewed, total lines changed (additions + deletions).
      </p>
      {total === 0 ? (
        <p className="analytics-page__panel-empty">No reviews in this window yet.</p>
      ) : (
        <ul className="analytics-bars">
          {buckets.map(b => (
            <li key={b.label} className="analytics-bars__row">
              <span className="analytics-bars__label">{b.label}</span>
              <div className="analytics-bars__track">
                <div
                  className="analytics-bars__fill"
                  style={{ width: `${(b.count / max) * 100}%` }}
                />
              </div>
              <span className="analytics-bars__count">{b.count}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ReposByReviewCard({ rows }: { rows: PrAnalyticsRepoReviewCountDto[] }) {
  const max = Math.max(1, ...rows.map(r => r.count));
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        Repos by review activity
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Where your reviews land — top {rows.length || 'repos'}.
      </p>
      {rows.length === 0 ? (
        <p className="analytics-page__panel-empty">No reviews in this window yet.</p>
      ) : (
        <ul className="analytics-bars">
          {rows.map(r => (
            <li key={r.repo} className="analytics-bars__row">
              <span className="analytics-bars__label analytics-bars__label--repo" title={r.repo}>
                {r.repo}
              </span>
              <div className="analytics-bars__track">
                <div
                  className="analytics-bars__fill"
                  style={{ width: `${(r.count / max) * 100}%` }}
                />
              </div>
              <span className="analytics-bars__count">{r.count}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function PartialMarker() {
  return (
    <sup
      className="analytics-kpi__partial-marker"
      title="Computed from PRs with cached detail only. See 'What's measured here' below."
    >
      ¹
    </sup>
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
