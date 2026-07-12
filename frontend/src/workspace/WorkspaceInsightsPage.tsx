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

type Props = {
  workspaceId: string;
};

/** Workspace Insights — KPI cards + a per-window spend chart + the
 *  per-repo tasks-shipped breakdown. Everything pulls from
 *  {@code /api/workspaces/{id}/insights?window=…}. The per-repo card
 *  attributes PR-linked tasks to their repo via the {@code owner/repo#n}
 *  link ref (the only repo signal a Task carries today), so tasks with
 *  no linked PR don't appear in the breakdown. */
function WorkspaceInsightsPage({ workspaceId }: Props) {
  const [insights, setInsights] = useState<WorkspaceInsightsDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [windowKey, setWindowKey] = useState<InsightsWindow>('7d');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setInsights(await window.bridge.getWorkspaceInsights(workspaceId, windowKey));
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId, windowKey]);

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
        <KpiCard label="Active threads" icon="▢" iconColor="#0969da"
                 value={loading ? '—' : String(activeThreads)} />
        <KpiCard label="Tasks in flight" icon="↗" iconColor="#1a7f37"
                 value={loading ? '—' : String(tasksInFlight)} />
        <KpiCard label="Repos in workspace" icon="▣" iconColor="#7c3aed"
                 value={loading ? '—' : String(reposInWorkspace)} />
        <KpiCard label={spendCardLabel}
                 icon="$" iconColor="#c2632a"
                 value={loading ? '—' : formatMilliUsd(spendForCard)} />
      </div>

      <RateLimitCard rate={insights?.githubRateLimit ?? null} loading={loading} />

      <div style={chartRowStyle}>
        <section className="workspace-card" aria-label="Spend">
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

        <section className="workspace-card" style={{ minWidth: 0 }} aria-label="Tasks shipped">
          <div className="workspace-card__head">
            <div className="workspace-card__title">Tasks shipped</div>
            <div style={chartMetaStyle}>
              this {windowKey === '24h' ? 'day' : windowKey === '7d' ? 'week' : 'month'}
            </div>
          </div>
          <div style={shippedTotalStyle}>
            {loading ? '—' : (insights?.tasksShippedInWindow ?? 0)}
          </div>
          <div style={chartMetaStyle}>
            tasks with a linked PR · {windowKey} window
          </div>
          <div style={{ height: 1, background: 'var(--border, rgba(0,0,0,0.07))', margin: '16px 0' }} />
          {(insights?.tasksByRepo ?? []).length === 0 ? (
            <div style={chartMetaStyle}>No PR-linked tasks in this window yet.</div>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
              {(insights?.tasksByRepo ?? []).map(r => (
                <li key={r.repoFullName} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1, fontFamily: 'ui-monospace, SFMono-Regular, monospace' }}>
                    {r.repoFullName}
                  </span>
                  <span style={shippedPillStyle}>{r.tasksShipped} shipped</span>
                  <span style={openPillStyle}>{r.tasksOpen} open</span>
                </li>
              ))}
            </ul>
          )}
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
    <div className="workspace-card" style={{ padding: '16px 17px', display: 'flex', flexDirection: 'column', gap: 11 }}>
      <span style={kpiIconStyle(iconColor)}>{icon}</span>
      <div>
        <div style={kpiValueStyle}>{value}</div>
        <div style={kpiLabelStyle}>{label}</div>
      </div>
    </div>
  );
}

/** The GitHub API rate-limit card — remaining/limit, reset time, and a
 *  usage bar, laid out as its own full-width row under the KPI grid. */
function RateLimitCard({ rate, loading }: {
  rate: WorkspaceInsightsDto['githubRateLimit'];
  loading: boolean;
}) {
  const pct = rate == null || rate.limit === 0 ? 0 : Math.round((rate.remaining / rate.limit) * 100);
  return (
    <div className="workspace-card" style={rateCardStyle} aria-label="GitHub API">
      <span style={kpiIconStyle('#0969da')}>◴</span>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--ws-text-1)' }}>
          {loading || rate == null ? '—' : (
            <>
              {rate.remaining.toLocaleString()}
              {' '}
              <span style={{ color: 'var(--ws-text-3)', fontWeight: 500, fontSize: 13 }}>
                / {rate.limit.toLocaleString()}
              </span>
            </>
          )}
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--ws-text-3)', marginTop: 1 }}>
          GitHub API
          {rate != null && ` · resets ${new Date(rate.resetAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}
        </div>
      </div>
      <div style={rateTrackStyle}>
        <div style={{ ...rateFillStyle, width: `${pct}%` }} />
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
        // Peak bar gets the strong gradient, idle days a faint stub —
        // matches the redesign's spend chart treatment.
        const isPeak = s.costUsdMilli === max;
        const pct = s.costUsdMilli === 0 ? 3 : 6 + (s.costUsdMilli / max) * 94;
        const bg = isPeak
          ? 'linear-gradient(180deg, #a78bfa, #7c3aed)'
          : s.costUsdMilli === 0 ? 'rgba(0, 0, 0, 0.08)' : 'rgba(139, 92, 246, 0.35)';
        return (
          <div key={i} style={chartColumnStyle}>
            <div style={chartBarLabelStyle}>${dollars.toFixed(1)}</div>
            <div style={{ ...chartBarStyle, height: `${pct}%`, background: bg }} />
            <div style={chartTickStyle}>{s.label}</div>
          </div>
        );
      })}
    </div>
  );
}

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
  marginBottom: 12,
};

/** Icon tile — the glyph on a soft tint of its own color. */
function kpiIconStyle(color: string): React.CSSProperties {
  return {
    width: 32,
    height: 32,
    borderRadius: 9,
    background: `color-mix(in srgb, ${color} 10%, transparent)`,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    color,
    fontSize: 15,
    fontWeight: 700,
    flexShrink: 0,
  };
}

const kpiValueStyle: React.CSSProperties = {
  fontSize: 23,
  fontWeight: 700,
  letterSpacing: '-0.02em',
  color: 'var(--ws-text-1)',
  lineHeight: 1,
};

const kpiLabelStyle: React.CSSProperties = {
  fontSize: 11.5,
  color: 'var(--ws-text-3)',
  marginTop: 5,
};

const rateCardStyle: React.CSSProperties = {
  padding: '14px 17px',
  display: 'flex',
  alignItems: 'center',
  gap: 14,
  marginBottom: 16,
};

const rateTrackStyle: React.CSSProperties = {
  flex: 1,
  maxWidth: 340,
  marginLeft: 'auto',
  height: 7,
  background: 'var(--bg-hover, rgba(0,0,0,0.07))',
  borderRadius: 999,
  overflow: 'hidden',
};

const rateFillStyle: React.CSSProperties = {
  height: '100%',
  background: 'linear-gradient(90deg, #60a5fa, #2563eb)',
  borderRadius: 999,
};

const chartRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1.7fr 1fr',
  gap: 12,
};

const shippedPillStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: '#1a7f37',
  background: '#dafbe1',
  borderRadius: 999,
  padding: '2px 8px',
  whiteSpace: 'nowrap',
};

const openPillStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: '#9a6700',
  background: '#fff8c5',
  borderRadius: 999,
  padding: '2px 8px',
  whiteSpace: 'nowrap',
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
  height: 180,
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
  maxWidth: 44,
  borderRadius: '7px 7px 3px 3px',
  marginTop: 'auto',
  transition: 'height 140ms ease',
};

const chartTickStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--ws-text-3)',
  marginTop: 4,
};

const shippedTotalStyle: React.CSSProperties = {
  fontSize: 44,
  fontWeight: 700,
  letterSpacing: '-0.02em',
  color: 'var(--ws-text-1)',
  lineHeight: 1,
  marginBottom: 8,
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
