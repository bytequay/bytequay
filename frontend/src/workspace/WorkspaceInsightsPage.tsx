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
import type { ThreadDto } from '../types';

type InsightsWindow = '24h' | '7d' | '30d';

const WINDOWS: InsightsWindow[] = ['24h', '7d', '30d'];

/** Workspace Insights — KPI cards + a 7-day spend chart + the
 *  per-repo tasks-shipped breakdown. Counts pull from the existing
 *  thread store; spend + shipped charts use placeholder data per
 *  the Phase 6 scope agreement (real aggregation queries land
 *  later, behind a new backend endpoint). The 24h/7d/30d toggle
 *  re-renders the chart against different placeholder shapes so the
 *  affordance feels live even before real data hits. */
function WorkspaceInsightsPage() {
  const [threads, setThreads] = useState<ThreadDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [windowKey, setWindowKey] = useState<InsightsWindow>('7d');

  const refresh = useCallback(async () => {
    try {
      setThreads(await window.bridge.listTasks());
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const activeThreads = threads.filter(t =>
      t.status === 'PENDING' || t.status === 'RUNNING'
      || t.status === 'AWAITING' || t.status === 'IDLE').length;
  const tasksInFlight = threads
      .map(t => t.activeTask)
      .filter(t => t !== null).length;
  // Repos in workspace: distinct workingDirs from active tasks, since
  // there's no direct workspace-repos surface on the thread list. A
  // future commit can swap to the workspace_repos store directly.
  const reposInWorkspace = new Set(
      threads
          .map(t => t.activeTask?.workingDir)
          .filter((w): w is string => typeof w === 'string' && w.length > 0))
      .size || 0;
  const spentTodayMilli = threads
      .filter(t => isUpdatedToday(t.updatedAt))
      .reduce((sum, t) => sum + (t.costUsdMilli || 0), 0);

  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">Insights</h1>
          <div className="workspace-pageheader__meta">
            spend + shipped charts are placeholder until backend aggregation lands
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
        <KpiCard label={`Spent ${windowKey === '24h' ? 'today' : 'in window'}`}
                 icon="$" iconColor="#d97706"
                 value={loading ? '—' : formatMilliUsd(spentTodayMilli)} />
      </div>

      <div style={chartRowStyle}>
        <section className="workspace-card" style={{ flex: 2 }} aria-label="Spend">
          <div className="workspace-card__head">
            <div className="workspace-card__title">Spend</div>
            <div style={chartMetaStyle}>
              last 7 days · placeholder
            </div>
          </div>
          <SpendChart windowKey={windowKey} />
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

function SpendChart({ windowKey }: { windowKey: InsightsWindow }) {
  // Placeholder shapes vary by window so the toggle visibly does
  // *something* even before aggregation hits.
  const bars = windowKey === '24h' ? PLACEHOLDER_BARS_24H
      : windowKey === '7d' ? PLACEHOLDER_BARS_7D
      : PLACEHOLDER_BARS_30D;
  const max = Math.max(...bars.map(b => b.value), 1);
  return (
    <div style={chartCanvasStyle}>
      {bars.map((b, i) => {
        const pct = (b.value / max) * 100;
        return (
          <div key={i} style={chartColumnStyle}>
            <div style={chartBarLabelStyle}>${b.value.toFixed(1)}</div>
            <div style={{ ...chartBarStyle, height: `${Math.max(4, pct)}%` }} />
            <div style={chartTickStyle}>{b.label}</div>
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

const PLACEHOLDER_BARS_24H = Array.from({ length: 8 }, (_, i) => ({
  label: `${String(i * 3).padStart(2, '0')}h`,
  value: Math.round(40 + 80 * Math.sin(i * 0.7)) / 100,
}));
const PLACEHOLDER_BARS_7D = [
  { label: 'Mon', value: 1.5 },
  { label: 'Tue', value: 1.3 },
  { label: 'Wed', value: 2.0 },
  { label: 'Thu', value: 2.3 },
  { label: 'Fri', value: 1.6 },
  { label: 'Sat', value: 0.4 },
  { label: 'Today', value: 1.84 },
];
const PLACEHOLDER_BARS_30D = Array.from({ length: 10 }, (_, i) => ({
  label: `${i * 3 + 1}`,
  value: Math.round(150 + 200 * Math.cos(i * 0.5)) / 100,
}));

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

function isUpdatedToday(iso: string): boolean {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return false;
  const now = new Date();
  return then.getUTCFullYear() === now.getUTCFullYear()
      && then.getUTCMonth() === now.getUTCMonth()
      && then.getUTCDate() === now.getUTCDate();
}

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
