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
import { useCallback, useEffect, useState } from 'react';
import type { WorkspaceInsightsDto } from '../types';

type InsightsWindow = '24h' | '7d' | '30d';

const WINDOWS: InsightsWindow[] = ['24h', '7d', '30d'];
const WORKSPACE_ID = 'ws-default';

/** Workspace Insights — KPI cards + a per-window spend chart + the
 *  per-repo tasks-shipped breakdown. KPI counts, today/window spend,
 *  and the per-day spend series pull from
 *  {@code /api/workspaces/{id}/insights?window=…} so the numbers are
 *  real. The tasks-shipped-per-repo card stays placeholder for now —
 *  the work-unit {@code Task} doesn't carry an owner/repo column,
 *  and parsing the {@code workingDir} path is fragile; the
 *  follow-up wires it once the column lands. */
function WorkspaceInsightsPage() {
  const [insights, setInsights] = useState<WorkspaceInsightsDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [windowKey, setWindowKey] = useState<InsightsWindow>('7d');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setInsights(await window.bridge.getWorkspaceInsights(WORKSPACE_ID, windowKey));
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [windowKey]);

  useEffect(() => { void refresh(); }, [refresh]);

  const activeThreads = insights?.activeThreads ?? 0;
  const tasksInFlight = insights?.tasksInFlight ?? 0;
  const reposInWorkspace = insights?.reposInWorkspace ?? 0;
  const spendForCard = windowKey === '24h'
      ? (insights?.spendTodayMilli ?? 0)
      : (insights?.spendInWindowMilli ?? 0);
  const spendCardLabel = windowKey === '24h' ? 'Spent today' : 'Spent in window';

  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Insights</h1>
          <div className="workspace-pageheader__meta">
            workspace insights · counts + spend chart are live
          </div>
        </div>
        <div role="tablist" style={windowToggleStyle}>
          {WINDOWS.map(w => (
            <button
              key={w}
              type="button"
              role="tab"
              aria-selected={windowKey === w}
              onClick={() => setWindowKey(w)}
              style={windowButtonStyle(windowKey === w)}
            >
              {w}
            </button>
          ))}
        </div>
      </header>

      {error !== null && <div style={errorStyle} role="alert">{error}</div>}

      <div style={kpiGridStyle}>
        <KpiCard label="Active threads" icon="▢" iconColor="#7c3aed"
                 value={loading ? '—' : String(activeThreads)} />
        <KpiCard label="Tasks in flight" icon="↗" iconColor="#16a34a"
                 value={loading ? '—' : String(tasksInFlight)} />
        <KpiCard label="Repos in workspace" icon="▣" iconColor="#0066cc"
                 value={loading ? '—' : String(reposInWorkspace)} />
        <KpiCard label={spendCardLabel}
                 icon="$" iconColor="#d97706"
                 value={loading ? '—' : formatMilliUsd(spendForCard)} />
      </div>

      <div style={chartRowStyle}>
        <section className="workspace-card" style={{ flex: 2 }} aria-label="Spend">
          <div className="workspace-card__head">
            <div className="workspace-card__title">Spend</div>
            <div style={chartMetaStyle}>
              window: {windowKey}
              {insights !== null && (
                <> · ${(insights.spendInWindowMilli / 1000).toFixed(2)} total</>
              )}
            </div>
          </div>
          <SpendChart series={insights?.spendByDay ?? []} />
        </section>

        <section className="workspace-card" style={{ flex: 1, minWidth: 0 }} aria-label="Tasks shipped">
          <div className="workspace-card__head">
            <div className="workspace-card__title">Tasks shipped</div>
            <div style={chartMetaStyle}>
              this {windowKey === '24h' ? 'day' : windowKey === '7d' ? 'week' : 'month'}
              {' · '}placeholder
            </div>
          </div>
          <ShippedByRepo windowKey={windowKey} />
          <div style={{ ...chartMetaStyle, marginTop: 10 }}>
            Avg time to PR <span style={{ float: 'right' }}>14m / task</span>
          </div>
        </section>
      </div>
    </>
  );
}

function KpiCard({ label, icon, iconColor, value }: {
  label: string;
  icon: string;
  iconColor: string;
  value: string;
}) {
  return (
    <div className="workspace-card" style={{ padding: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={kpiIconStyle(iconColor)}>{icon}</span>
        <div style={{ flex: 1 }}>
          <div style={kpiValueStyle}>{value}</div>
          <div style={kpiLabelStyle}>{label}</div>
        </div>
      </div>
    </div>
  );
}

function SpendChart({ series }: { series: WorkspaceInsightsDto['spendByDay'] }) {
  // Real spend series — one entry per day in the window, dollars
  // computed from costUsdMilli at render time. Empty days still
  // appear (zero-height bar) so the x-axis matches the window
  // length and the user sees their idle days as a gap.
  if (series.length === 0) {
    return (
      <div style={{ ...chartCanvasStyle, alignItems: 'center', justifyContent: 'center', color: '#7a7388', fontSize: 12 }}>
        No spend in this window.
      </div>
    );
  }
  const max = Math.max(...series.map(s => s.costUsdMilli), 1);
  return (
    <div style={chartCanvasStyle}>
      {series.map((s, i) => {
        const dollars = s.costUsdMilli / 1000;
        const pct = (s.costUsdMilli / max) * 100;
        return (
          <div key={i} style={chartColumnStyle}>
            <div style={chartBarLabelStyle}>${dollars.toFixed(1)}</div>
            <div style={{ ...chartBarStyle, height: `${Math.max(2, pct)}%` }} />
            <div style={chartTickStyle}>{s.label}</div>
          </div>
        );
      })}
    </div>
  );
}

function ShippedByRepo({ windowKey }: { windowKey: InsightsWindow }) {
  const rows = windowKey === '30d' ? PLACEHOLDER_SHIPPED_30D : PLACEHOLDER_SHIPPED_7D;
  const max = Math.max(...rows.map(r => r.count), 1);
  return (
    <ul style={shippedListStyle}>
      {rows.map(r => {
        const pct = (r.count / max) * 100;
        return (
          <li key={r.repo} style={shippedRowStyle}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={shippedDotStyle(r.color)} aria-hidden />
              <span style={shippedRepoStyle}>{r.repo}</span>
            </div>
            <div style={shippedBarTrackStyle}>
              <div style={{ ...shippedBarFillStyle, width: `${pct}%`, background: r.color }} />
            </div>
            <span style={shippedCountStyle}>{r.count}</span>
          </li>
        );
      })}
    </ul>
  );
}

/* ── placeholder data ───────────────────────────────────────── */

// Tasks-shipped breakdown stays placeholder until Task carries an
// owner/repo column — see WorkspaceInsightsService for the matching
// backend note. Drop these constants when the breakdown wires.
const PLACEHOLDER_SHIPPED_7D = [
  { repo: 'ByteQuay', count: 7, color: '#7c3aed' },
  { repo: 'bytequay-infra', count: 2, color: '#0066cc' },
];
const PLACEHOLDER_SHIPPED_30D = [
  { repo: 'ByteQuay', count: 24, color: '#7c3aed' },
  { repo: 'bytequay-infra', count: 9, color: '#0066cc' },
  { repo: 'observability', count: 3, color: '#16a34a' },
];

/* ── helpers ────────────────────────────────────────────────── */

function formatMilliUsd(milli: number): string {
  return `$${(milli / 1000).toFixed(2)}`;
}

/* ── styles ─────────────────────────────────────────────────── */

const windowToggleStyle: React.CSSProperties = {
  display: 'inline-flex',
  background: 'rgba(255, 255, 255, 0.6)',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 8,
  padding: 2,
};

function windowButtonStyle(active: boolean): React.CSSProperties {
  return {
    padding: '4px 10px',
    fontSize: 11,
    fontWeight: 600,
    border: 'none',
    borderRadius: 6,
    background: active ? '#fff' : 'transparent',
    color: active ? 'var(--ws-text-1)' : 'var(--ws-text-3)',
    cursor: 'pointer',
    transition: 'background var(--ws-fast), color var(--ws-fast)',
    boxShadow: active ? '0 1px 3px rgba(67, 56, 202, 0.07)' : undefined,
  };
}

const kpiGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(4, 1fr)',
  gap: 12,
  marginBottom: 14,
};

function kpiIconStyle(color: string): React.CSSProperties {
  return {
    width: 32,
    height: 32,
    borderRadius: 8,
    background: 'rgba(255, 255, 255, 0.8)',
    border: '1px solid var(--ws-card-border)',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    color,
    fontSize: 14,
    fontWeight: 700,
  };
}

const kpiValueStyle: React.CSSProperties = {
  fontSize: 20,
  fontWeight: 700,
  letterSpacing: '-0.02em',
  color: 'var(--ws-text-1)',
  lineHeight: 1.1,
};

const kpiLabelStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
};

const chartRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 14,
};

const chartMetaStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const chartCanvasStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 12,
  height: 160,
  padding: '0 4px',
};

const chartColumnStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  height: '100%',
};

const chartBarLabelStyle: React.CSSProperties = {
  fontSize: 9,
  color: 'var(--ws-text-3)',
  marginBottom: 2,
};

const chartBarStyle: React.CSSProperties = {
  width: '70%',
  background: 'linear-gradient(180deg, #a78bfa, #7c3aed)',
  borderRadius: '6px 6px 0 0',
  marginTop: 'auto',
  transition: 'height 140ms ease',
};

const chartTickStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
  marginTop: 4,
};

const shippedListStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const shippedRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(0, auto) 1fr auto',
  alignItems: 'center',
  gap: 8,
  fontSize: 12,
};

function shippedDotStyle(color: string): React.CSSProperties {
  return {
    width: 8,
    height: 8,
    borderRadius: 2,
    background: color,
    flexShrink: 0,
  };
}

const shippedRepoStyle: React.CSSProperties = {
  color: 'var(--ws-text-2)',
  whiteSpace: 'nowrap',
};

const shippedBarTrackStyle: React.CSSProperties = {
  height: 6,
  background: 'rgba(124, 58, 237, 0.08)',
  borderRadius: 999,
  overflow: 'hidden',
};

const shippedBarFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 140ms ease',
};

const shippedCountStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-1)',
  fontWeight: 600,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 12,
};

export default WorkspaceInsightsPage;
