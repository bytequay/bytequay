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
import type { AiLedgerDto } from '../../types';

// decision pending: model/provider selection moves to the config
// cascade. The Credentials and Skills inner tabs used to live here;
// each moved to its own top-level Settings section. Active-provider/
// active-model selection belongs to a later config-cascade page and
// isn't rebuilt on this surface.

/** Last 12 months as YYYY-MM, newest first, for the picker. */
function recentMonths(now: Date): string[] {
  const out: string[] = [];
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
  }
  return out;
}

/**
 * The "AI" settings surface. Reduced to Usage now that Credentials
 * and Skills each have their own sections in the sidebar; left in
 * place so existing deep-links keep landing somewhere and the Usage
 * placeholder has a home.
 */
function AiReviewPage() {
  const months = useMemo(() => recentMonths(new Date()), []);
  const [month, setMonth] = useState(months[0]);
  const [ledger, setLedger] = useState<AiLedgerDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getAiLedger === undefined) { setLoading(false); return; }
    bridge.getAiLedger(month)
      .then(l => { if (!cancelled) { setLedger(l); setLoading(false); } })
      .catch((e: unknown) => { if (!cancelled) { setError(e instanceof Error ? e.message : String(e)); setLoading(false); } });
    return () => { cancelled = true; };
  }, [month]);

  const cents = (c: number) => `$${(c / 100).toFixed(2)}`;

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">AI</h2>
          <div className="settings-shell-page__subtitle">
            Provider keys live under Settings → Credentials. Reusable
            skills live under Settings → Skills. This page is the monthly
            spend / call ledger.
          </div>
        </div>
        <label style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
          Month
          <select value={month} onChange={e => setMonth(e.target.value)} aria-label="Ledger month">
            {months.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>

      {error !== null && <div className="repo-error">{error}</div>}
      {loading && <div className="settings-loading">Loading…</div>}
      {!loading && ledger !== null && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div style={{ display: 'flex', gap: 24 }}>
            <Stat label="Total spend" value={cents(ledger.totalCents)} />
            <Stat label="Calls" value={String(ledger.totalCalls)} />
          </div>
          <LedgerTable
            title="By provider"
            rows={ledger.byProvider.map(p => [p.provider, p.callsCount, cents(p.costCents)])}
          />
          <LedgerTable
            title="By work type"
            rows={ledger.byTaskType.map(t => [t.type, t.callsCount, cents(t.costCents)])}
          />
          {ledger.totalCalls === 0 && (
            <div style={{ fontSize: 12, color: 'var(--text-3)' }}>No AI spend recorded for {month}.</div>
          )}
        </div>
      )}
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ fontSize: 11, color: 'var(--text-4)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 700 }}>{value}</div>
    </div>
  );
}

function LedgerTable({ title, rows }: { title: string; rows: [string, number, string][] }) {
  return (
    <div>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>{title}</div>
      {rows.length === 0
        ? <div style={{ fontSize: 12, color: 'var(--text-3)' }}>—</div>
        : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ color: 'var(--text-4)', textAlign: 'left' }}>
                <th style={{ padding: '4px 6px' }}>Name</th>
                <th style={{ padding: '4px 6px', textAlign: 'right' }}>Calls</th>
                <th style={{ padding: '4px 6px', textAlign: 'right' }}>Cost</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(([name, calls, cost]) => (
                <tr key={name} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '4px 6px' }}>{name}</td>
                  <td style={{ padding: '4px 6px', textAlign: 'right' }}>{calls}</td>
                  <td style={{ padding: '4px 6px', textAlign: 'right' }}>{cost}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </div>
  );
}

export default AiReviewPage;
