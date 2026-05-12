import { useCallback, useEffect, useMemo, useState } from 'react';
import MyActivityView from './MyActivityView';
import type {
  PrAnalyticsCoReviewerDto,
  PrAnalyticsDailyActivityDto,
  PrAnalyticsHeatmapCellDto,
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

type AnalyticsView = 'reviews' | 'activity';

type Props = {
  /** Allows the parent to open a PR detail page from a stale-PR row.
   *  When omitted, rows are still clickable but route nowhere. */
  onOpenPr?: (repo: string, number: number) => void;
};

function PrAnalyticsPage({ onOpenPr }: Props) {
  const [view, setView] = useState<AnalyticsView>('reviews');
  if (view === 'activity') {
    return <MyActivityView view={view} onChangeView={setView} onOpenPr={onOpenPr} />;
  }
  return <ReviewsAnalyticsView view={view} onChangeView={setView} onOpenPr={onOpenPr} />;
}

type ReviewsProps = Props & {
  view: AnalyticsView;
  onChangeView: (v: AnalyticsView) => void;
};

function ReviewsAnalyticsView({ onOpenPr, view, onChangeView }: ReviewsProps) {
  const [scope, setScope] = useState<PrAnalyticsScope>('30d');
  const [data, setData] = useState<PrAnalyticsSummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastLoadedAt, setLastLoadedAt] = useState<number | null>(null);

  const fetchAnalytics = useCallback(async (currentScope: PrAnalyticsScope) => {
    const tz = (() => {
      try { return Intl.DateTimeFormat().resolvedOptions().timeZone; }
      catch { return undefined; }
    })();
    return window.bridge.fetchPrAnalytics(currentScope, tz);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchAnalytics(scope)
      .then(result => {
        if (cancelled) return;
        setData(result);
        setLastLoadedAt(Date.now());
      })
      .catch(e => {
        if (cancelled) return;
        setError(e?.message ?? 'Failed to load analytics');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [scope, fetchAnalytics]);

  // Background-refresh path: when the window regains focus we
  // quietly re-fetch so the page tracks sync ticks that landed while
  // the user was elsewhere. Doesn't flip `loading` — that would yank
  // the panels mid-glance. Errors are swallowed; the next refresh
  // will retry.
  useEffect(() => {
    const refresh = () => {
      if (document.visibilityState !== 'visible') return;
      setRefreshing(true);
      fetchAnalytics(scope)
        .then(result => {
          setData(result);
          setLastLoadedAt(Date.now());
        })
        .catch(() => { /* best-effort */ })
        .finally(() => setRefreshing(false));
    };
    document.addEventListener('visibilitychange', refresh);
    window.addEventListener('focus', refresh);
    return () => {
      document.removeEventListener('visibilitychange', refresh);
      window.removeEventListener('focus', refresh);
    };
  }, [scope, fetchAnalytics]);

  const handleManualRefresh = () => {
    setRefreshing(true);
    fetchAnalytics(scope)
      .then(result => {
        setData(result);
        setLastLoadedAt(Date.now());
      })
      .catch(e => setError(e?.message ?? 'Failed to refresh'))
      .finally(() => setRefreshing(false));
  };

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
        <ViewToggle view={view} onChange={onChangeView} />
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
          <span className="analytics-page__freshness" title={freshnessTitle(lastLoadedAt)}>
            {refreshing ? 'Refreshing…' : freshnessLabel(lastLoadedAt)}
          </span>
          <button
            type="button"
            className="analytics-page__refresh-btn"
            title="Re-run the aggregation against the local store right now."
            onClick={handleManualRefresh}
            disabled={refreshing || loading}
          >
            ↻
          </button>
          <button
            type="button"
            className="analytics-page__export-btn"
            title="Download this page's numbers as CSV. Local only — no upload."
            disabled={!data}
            onClick={() => data && downloadCsv(data)}
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

          <DailyActivityCard days={data.dailyActivity} />

          <div className="analytics-page__grid analytics-page__grid--two">
            <ReviewOutcomesCard slices={data.reviewOutcomes} />
            <SizeDistributionCard buckets={data.sizeDistribution} />
          </div>

          <HeatmapCard cells={data.reviewHeatmap} />

          <div className="analytics-page__grid analytics-page__grid--three">
            <ReposByReviewCard rows={data.reposByReview} />
            <ReviewNetworkCard rows={data.reviewNetwork} />
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

export function KpiCardView({ title, card }: { title: string; card: PrAnalyticsKpiCardDto }) {
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

function DailyActivityCard({ days }: { days: PrAnalyticsDailyActivityDto[] }) {
  const max = useMemo(
    () => Math.max(
      1,
      ...days.map(d => d.approved + d.changesRequested + d.commented + d.dismissed),
    ),
    [days],
  );
  const hasAny = useMemo(
    () => days.some(d => (d.approved + d.changesRequested + d.commented + d.dismissed) > 0),
    [days],
  );
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        Daily review activity
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Each bar is one day; segments stack by review state.
      </p>
      {!hasAny ? (
        <p className="analytics-page__panel-empty">
          No timestamped reviews in this window yet — the mirror fills in as PRs re-sync.
        </p>
      ) : (
        <div className="analytics-daily">
          <div className="analytics-daily__bars" role="img" aria-label="Daily review activity">
            {days.map(d => {
              const total = d.approved + d.changesRequested + d.commented + d.dismissed;
              const heightPct = total === 0 ? 0 : (total / max) * 100;
              return (
                <div
                  key={d.date}
                  className="analytics-daily__col"
                  title={`${d.date}: ${total} review${total === 1 ? '' : 's'}`}
                >
                  <div className="analytics-daily__stack" style={{ height: `${heightPct}%` }}>
                    {d.approved > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--approved"
                        style={{ flexBasis: `${(d.approved / total) * 100}%` }}
                      />
                    )}
                    {d.changesRequested > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--changes"
                        style={{ flexBasis: `${(d.changesRequested / total) * 100}%` }}
                      />
                    )}
                    {d.commented > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--commented"
                        style={{ flexBasis: `${(d.commented / total) * 100}%` }}
                      />
                    )}
                    {d.dismissed > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--dismissed"
                        style={{ flexBasis: `${(d.dismissed / total) * 100}%` }}
                      />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
          <DailyLegend />
        </div>
      )}
    </section>
  );
}

function DailyLegend() {
  return (
    <ul className="analytics-daily__legend">
      <li><span className="analytics-daily__legend-swatch analytics-daily__seg--approved" />Approved</li>
      <li><span className="analytics-daily__legend-swatch analytics-daily__seg--changes" />Changes</li>
      <li><span className="analytics-daily__legend-swatch analytics-daily__seg--commented" />Commented</li>
      <li><span className="analytics-daily__legend-swatch analytics-daily__seg--dismissed" />Dismissed</li>
    </ul>
  );
}

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

function HeatmapCard({ cells }: { cells: PrAnalyticsHeatmapCellDto[] }) {
  const max = useMemo(() => Math.max(1, ...cells.map(c => c.count)), [cells]);
  const grid = useMemo(() => {
    const g: number[][] = Array.from({ length: 7 }, () => new Array(24).fill(0));
    for (const c of cells) g[c.dayOfWeek][c.hour] = c.count;
    return g;
  }, [cells]);
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        When you review
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Day-of-week × hour-of-day (your local time).
      </p>
      {cells.length === 0 ? (
        <p className="analytics-page__panel-empty">
          No timestamped reviews yet — the mirror fills in as PRs re-sync.
        </p>
      ) : (
        <div className="analytics-heatmap">
          <div className="analytics-heatmap__hour-labels">
            <span />
            {Array.from({ length: 24 }, (_, h) => (
              <span key={h} className="analytics-heatmap__hour-label">
                {h % 6 === 0 ? `${h}` : ''}
              </span>
            ))}
          </div>
          {grid.map((row, day) => (
            <div key={day} className="analytics-heatmap__row">
              <span className="analytics-heatmap__day-label">{DAY_LABELS[day]}</span>
              {row.map((count, hour) => {
                const intensity = count === 0 ? 0 : Math.max(0.15, count / max);
                return (
                  <span
                    key={hour}
                    className="analytics-heatmap__cell"
                    style={{ background: count === 0 ? undefined : `rgba(31, 106, 87, ${intensity})` }}
                    title={count > 0 ? `${DAY_LABELS[day]} ${hour}:00 — ${count} review${count === 1 ? '' : 's'}` : undefined}
                  />
                );
              })}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function ReviewNetworkCard({ rows }: { rows: PrAnalyticsCoReviewerDto[] }) {
  const max = Math.max(1, ...rows.map(r => r.count));
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">
        Review network
        <PartialMarker />
      </h2>
      <p className="analytics-page__panel-subtitle">
        Reviewers whose work overlaps yours — PRs you both touched.
      </p>
      {rows.length === 0 ? (
        <p className="analytics-page__panel-empty">No co-reviewers in this window yet.</p>
      ) : (
        <ul className="analytics-bars">
          {rows.map(r => (
            <li key={r.login} className="analytics-bars__row">
              <span className="analytics-bars__label analytics-bars__label--repo" title={r.login}>
                @{r.login}
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

export function ViewToggle({
  view,
  onChange,
}: {
  view: AnalyticsView;
  onChange: (v: AnalyticsView) => void;
}) {
  return (
    <div className="analytics-page__view-toggle" role="tablist" aria-label="Analytics view">
      <button
        type="button"
        role="tab"
        aria-selected={view === 'reviews'}
        className={`analytics-page__view-btn${view === 'reviews' ? ' analytics-page__view-btn--active' : ''}`}
        onClick={() => onChange('reviews')}
      >
        Reviews
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={view === 'activity'}
        className={`analytics-page__view-btn${view === 'activity' ? ' analytics-page__view-btn--active' : ''}`}
        onClick={() => onChange('activity')}
      >
        My activity
      </button>
    </div>
  );
}

export type AnalyticsViewName = AnalyticsView;

export function freshnessLabel(loadedAt: number | null): string {
  if (loadedAt == null) return '';
  const elapsedMs = Date.now() - loadedAt;
  if (elapsedMs < 30_000) return 'Up to date';
  const minutes = Math.floor(elapsedMs / 60_000);
  if (minutes < 1) return 'Updated just now';
  if (minutes < 60) return `Updated ${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `Updated ${hours}h ago`;
  return `Updated ${Math.floor(hours / 24)}d ago`;
}

export function freshnessTitle(loadedAt: number | null): string {
  if (loadedAt == null) return '';
  return `Last computed: ${new Date(loadedAt).toLocaleString()}`;
}

export const ANALYTICS_SCOPE_OPTIONS = SCOPE_OPTIONS;

function downloadCsv(data: PrAnalyticsSummaryDto) {
  const csv = buildCsv(data);
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const today = new Date().toISOString().slice(0, 10);
  const a = document.createElement('a');
  a.href = url;
  a.download = `bytequay-pr-review-analytics-${data.scope}-${today}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function buildCsv(data: PrAnalyticsSummaryDto): string {
  const lines: string[] = [];
  const exportedAt = new Date().toISOString();
  lines.push(csvRow(['# ByteQuay PR review analytics']));
  lines.push(csvRow([`# scope: ${data.scope}`]));
  lines.push(csvRow([`# watched_repos: ${data.watchedRepoCount}`]));
  lines.push(csvRow([`# login: ${data.currentLogin ?? ''}`]));
  lines.push(csvRow([`# exported_at: ${exportedAt}`]));

  lines.push('');
  lines.push(csvRow(['## KPIs']));
  lines.push(csvRow(['metric', 'value', 'partial', 'note']));
  const kpiRows: [string, PrAnalyticsKpiCardDto][] = [
    ['PRs reviewed', data.prsReviewed],
    ['Approval rate', data.approvalRate],
    ['Lines reviewed', data.linesReviewed],
    ['Response to review request (median)', data.responseToReviewRequest],
  ];
  for (const [label, kpi] of kpiRows) {
    lines.push(csvRow([
      label,
      kpi.pendingNote ? '' : kpi.displayValue,
      kpi.partial ? 'true' : 'false',
      kpi.pendingNote ?? '',
    ]));
  }

  lines.push('');
  lines.push(csvRow(['## Daily activity']));
  lines.push(csvRow(['date', 'approved', 'changes_requested', 'commented', 'dismissed']));
  for (const d of data.dailyActivity) {
    lines.push(csvRow([
      d.date,
      String(d.approved),
      String(d.changesRequested),
      String(d.commented),
      String(d.dismissed),
    ]));
  }

  lines.push('');
  lines.push(csvRow(['## Review outcomes']));
  lines.push(csvRow(['state', 'count']));
  for (const s of data.reviewOutcomes) {
    lines.push(csvRow([s.state, String(s.count)]));
  }

  lines.push('');
  lines.push(csvRow(['## PR size distribution']));
  lines.push(csvRow(['bucket', 'count']));
  for (const b of data.sizeDistribution) {
    lines.push(csvRow([b.label, String(b.count)]));
  }

  lines.push('');
  lines.push(csvRow(['## Repos by review activity']));
  lines.push(csvRow(['repo', 'count']));
  for (const r of data.reposByReview) {
    lines.push(csvRow([r.repo, String(r.count)]));
  }

  lines.push('');
  lines.push(csvRow(['## Heatmap (day-of-week × hour, local time, non-zero cells)']));
  lines.push(csvRow(['day_of_week', 'hour', 'count']));
  for (const c of data.reviewHeatmap) {
    lines.push(csvRow([String(c.dayOfWeek), String(c.hour), String(c.count)]));
  }

  lines.push('');
  lines.push(csvRow(['## Review network']));
  lines.push(csvRow(['login', 'count']));
  for (const r of data.reviewNetwork) {
    lines.push(csvRow([r.login, String(r.count)]));
  }

  lines.push('');
  lines.push(csvRow(['## Stale authored PRs (> 7 days, open)']));
  lines.push(csvRow(['repo', 'number', 'age_days', 'created_at', 'title']));
  for (const p of data.staleAuthoredPrs) {
    lines.push(csvRow([p.repo, String(p.number), String(p.ageDays), p.createdAt, p.title]));
  }

  return lines.join('\r\n') + '\r\n';
}

function csvRow(fields: string[]): string {
  return fields.map(csvEscape).join(',');
}

function csvEscape(value: string): string {
  if (/[",\r\n]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

export function PartialMarker() {
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
              <strong>Response to review request ¹</strong> — median elapsed time between a
              review being requested from you and your next review on that PR. Capped at 8 hours
              per pair so overnight pauses don't dominate the median.
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
