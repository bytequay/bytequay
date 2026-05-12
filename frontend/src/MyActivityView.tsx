import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ANALYTICS_SCOPE_OPTIONS,
  KpiCardView,
  PartialMarker,
  ViewToggle,
  freshnessLabel,
  freshnessTitle,
  type AnalyticsViewName,
} from './PrAnalyticsPage';
import type {
  MyActivityDailyAuthoredDto,
  MyActivityRepoActivityCountDto,
  MyActivitySummaryDto,
  PrAnalyticsKpiCardDto,
  PrAnalyticsScope,
} from './types';

type Props = {
  view: AnalyticsViewName;
  onChangeView: (v: AnalyticsViewName) => void;
};

function MyActivityView({ view, onChangeView }: Props) {
  const [scope, setScope] = useState<PrAnalyticsScope>('30d');
  const [data, setData] = useState<MyActivitySummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastLoadedAt, setLastLoadedAt] = useState<number | null>(null);

  const fetchActivity = useCallback(async (currentScope: PrAnalyticsScope) => {
    const tz = (() => {
      try { return Intl.DateTimeFormat().resolvedOptions().timeZone; }
      catch { return undefined; }
    })();
    return window.bridge.fetchMyActivity(currentScope, tz);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchActivity(scope)
      .then(result => {
        if (cancelled) return;
        setData(result);
        setLastLoadedAt(Date.now());
      })
      .catch(e => {
        if (cancelled) return;
        setError(e?.message ?? 'Failed to load activity');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [scope, fetchActivity]);

  useEffect(() => {
    const refresh = () => {
      if (document.visibilityState !== 'visible') return;
      setRefreshing(true);
      fetchActivity(scope)
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
  }, [scope, fetchActivity]);

  const handleManualRefresh = () => {
    setRefreshing(true);
    fetchActivity(scope)
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
            {ANALYTICS_SCOPE_OPTIONS.map(opt => (
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
            onClick={() => data && downloadActivityCsv(data)}
          >
            Export CSV
          </button>
        </div>
      </header>

      {loading && <div className="analytics-page__loading">Loading…</div>}
      {error && <div className="analytics-page__error">{error}</div>}

      {!loading && !error && data && (
        <div className="analytics-page__body">
          <div className="analytics-page__kpi-row">
            <KpiCardView title="PRs opened" card={data.prsOpened} />
            <KpiCardView title="PRs merged" card={data.prsMerged} />
            <KpiCardView title="Commits made" card={data.commitsMade} />
            <KpiCardView title="Comments posted" card={data.commentsPosted} />
          </div>

          <DailyAuthoredCard days={data.dailyAuthored} />

          <div className="analytics-page__grid analytics-page__grid--two">
            <ReposByActivityCard rows={data.reposByActivity} />
            <StreakCard current={data.currentStreakDays} longest={data.longestStreakDays} />
          </div>

          <WhatsMeasuredHereActivityCard />
        </div>
      )}
    </div>
  );
}

function DailyAuthoredCard({ days }: { days: MyActivityDailyAuthoredDto[] }) {
  const max = useMemo(
    () => Math.max(1, ...days.map(d => d.opened + d.merged)),
    [days],
  );
  const hasAny = useMemo(
    () => days.some(d => (d.opened + d.merged) > 0),
    [days],
  );
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">Daily authoring activity</h2>
      <p className="analytics-page__panel-subtitle">
        PRs you opened and merged each day in the active scope.
      </p>
      {!hasAny ? (
        <p className="analytics-page__panel-empty">
          No authored PRs in this window.
        </p>
      ) : (
        <div className="analytics-daily">
          <div className="analytics-daily__bars" role="img" aria-label="Daily authoring activity">
            {days.map(d => {
              const total = d.opened + d.merged;
              const heightPct = total === 0 ? 0 : (total / max) * 100;
              return (
                <div
                  key={d.date}
                  className="analytics-daily__col"
                  title={`${d.date}: ${d.opened} opened · ${d.merged} merged`}
                >
                  <div className="analytics-daily__stack" style={{ height: `${heightPct}%` }}>
                    {d.opened > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--opened"
                        style={{ flexBasis: `${(d.opened / total) * 100}%` }}
                      />
                    )}
                    {d.merged > 0 && (
                      <div
                        className="analytics-daily__seg analytics-daily__seg--merged"
                        style={{ flexBasis: `${(d.merged / total) * 100}%` }}
                      />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
          <ul className="analytics-daily__legend">
            <li><span className="analytics-daily__legend-swatch analytics-daily__seg--opened" />Opened</li>
            <li><span className="analytics-daily__legend-swatch analytics-daily__seg--merged" />Merged</li>
          </ul>
        </div>
      )}
    </section>
  );
}

function ReposByActivityCard({ rows }: { rows: MyActivityRepoActivityCountDto[] }) {
  const max = Math.max(1, ...rows.map(r => r.prsOpened + r.prsMerged));
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">Repos by your activity</h2>
      <p className="analytics-page__panel-subtitle">
        Where you ship — by total PRs opened + merged.
      </p>
      {rows.length === 0 ? (
        <p className="analytics-page__panel-empty">No authored PRs in this window.</p>
      ) : (
        <ul className="analytics-bars">
          {rows.map(r => {
            const total = r.prsOpened + r.prsMerged;
            return (
              <li key={r.repo} className="analytics-bars__row">
                <span className="analytics-bars__label analytics-bars__label--repo" title={r.repo}>
                  {r.repo}
                </span>
                <div className="analytics-bars__track">
                  <div
                    className="analytics-bars__fill"
                    style={{ width: `${(total / max) * 100}%` }}
                  />
                </div>
                <span
                  className="analytics-bars__count"
                  title={`${r.prsOpened} opened · ${r.prsMerged} merged`}
                >
                  {total}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function StreakCard({
  current,
  longest,
}: {
  current: number | null;
  longest: number | null;
}) {
  if (current == null && longest == null) {
    return (
      <section className="analytics-page__panel">
        <h2 className="analytics-page__panel-title">Contribution streak</h2>
        <p className="analytics-page__panel-empty">
          PAT required to read GitHub's contribution graph.
        </p>
      </section>
    );
  }
  return (
    <section className="analytics-page__panel">
      <h2 className="analytics-page__panel-title">Contribution streak</h2>
      <p className="analytics-page__panel-subtitle">
        From GitHub's contribution graph — covers everything you've committed to.
      </p>
      <div className="analytics-streak__row">
        <div className="analytics-streak__cell">
          <div className="analytics-streak__label">Current</div>
          <div className="analytics-streak__value">
            {current ?? 0} <span className="analytics-streak__unit">day{current === 1 ? '' : 's'}</span>
          </div>
        </div>
        <div className="analytics-streak__cell">
          <div className="analytics-streak__label">Longest (year)</div>
          <div className="analytics-streak__value">
            {longest ?? 0} <span className="analytics-streak__unit">day{longest === 1 ? '' : 's'}</span>
          </div>
        </div>
      </div>
    </section>
  );
}

function WhatsMeasuredHereActivityCard() {
  return (
    <section className="analytics-page__panel analytics-page__panel--measured">
      <h2 className="analytics-page__panel-title">What's measured here</h2>
      <div className="analytics-measured__grid">
        <div className="analytics-measured__col">
          <h3 className="analytics-measured__col-title">What we count</h3>
          <ul>
            <li>
              <strong>PRs opened</strong> — PRs you authored whose <em>createdAt</em> falls in
              the active scope window. Complete for the watched set.
            </li>
            <li>
              <strong>PRs merged</strong> — PRs you authored whose <em>mergedAt</em> falls in
              the active scope window. Complete for the watched set.
            </li>
            <li>
              <strong>Repos by your activity</strong> — top repos ranked by combined opened +
              merged count.
            </li>
            <li>
              <strong>Comments posted ¹</strong> — top-level PR comments + per-line review
              comments authored by you, across PRs we have cached detail for. Partial; the
              number under-counts comments on PRs ByteQuay has never fetched detail for.
            </li>
            <li>
              <strong>Commits made</strong> and <strong>contribution streak</strong> — pulled
              live from GitHub's contribution graph (cached for 5 minutes in this app
              process). Covers commits across all repos you have access to — not just the
              watched set — and reaches back about a year.
            </li>
          </ul>
        </div>
        <div className="analytics-measured__col">
          <h3 className="analytics-measured__col-title">What we deliberately don't measure</h3>
          <ul>
            <li>
              <strong>Finer-grained activity events</strong> (reactions, mentions,
              cross-references) — pending the activity-events mirror. We don't approximate
              from cached data because the gaps would be misleading.
            </li>
            <li>
              <strong>Activity on unwatched repos</strong> — the local store only carries PRs
              from repos you've explicitly added. Unwatched repos show up only after you add
              them.
            </li>
            <li>
              <strong>Productivity scores or per-engineer benchmarks</strong> — out of scope.
              Your numbers stay on your machine.
            </li>
          </ul>
        </div>
      </div>
    </section>
  );
}

function downloadActivityCsv(data: MyActivitySummaryDto) {
  const csv = buildActivityCsv(data);
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const today = new Date().toISOString().slice(0, 10);
  const a = document.createElement('a');
  a.href = url;
  a.download = `bytequay-my-activity-${data.scope}-${today}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function buildActivityCsv(data: MyActivitySummaryDto): string {
  const lines: string[] = [];
  const exportedAt = new Date().toISOString();
  lines.push(csvRow(['# ByteQuay my activity']));
  lines.push(csvRow([`# scope: ${data.scope}`]));
  lines.push(csvRow([`# watched_repos: ${data.watchedRepoCount}`]));
  lines.push(csvRow([`# login: ${data.currentLogin ?? ''}`]));
  lines.push(csvRow([`# exported_at: ${exportedAt}`]));

  lines.push('');
  lines.push(csvRow(['## KPIs']));
  lines.push(csvRow(['metric', 'value', 'partial', 'note']));
  const kpis: [string, PrAnalyticsKpiCardDto][] = [
    ['PRs opened', data.prsOpened],
    ['PRs merged', data.prsMerged],
    ['Commits made', data.commitsMade],
    ['Comments posted', data.commentsPosted],
  ];
  for (const [label, kpi] of kpis) {
    lines.push(csvRow([
      label,
      kpi.pendingNote ? '' : kpi.displayValue,
      kpi.partial ? 'true' : 'false',
      kpi.pendingNote ?? '',
    ]));
  }
  lines.push(csvRow(['Current streak (days)', String(data.currentStreakDays ?? ''), 'false', '']));
  lines.push(csvRow(['Longest streak (days, year)', String(data.longestStreakDays ?? ''), 'false', '']));

  lines.push('');
  lines.push(csvRow(['## Daily authoring activity']));
  lines.push(csvRow(['date', 'opened', 'merged']));
  for (const d of data.dailyAuthored) {
    lines.push(csvRow([d.date, String(d.opened), String(d.merged)]));
  }

  lines.push('');
  lines.push(csvRow(['## Repos by your activity']));
  lines.push(csvRow(['repo', 'prs_opened', 'prs_merged']));
  for (const r of data.reposByActivity) {
    lines.push(csvRow([r.repo, String(r.prsOpened), String(r.prsMerged)]));
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

export default MyActivityView;
