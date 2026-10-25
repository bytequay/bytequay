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
import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { Gauge, RefreshCw, X } from 'lucide-react';
import type { ProviderUsageDto, ProviderUsageWindowDto, UsageAccountDto } from '../types';
import { resetLabel, usagePace } from './pace';
import './usage.css';

export default function UsageMeter() {
  const id = useId();
  const popover = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState('codex');
  const [position, setPosition] = useState<CSSProperties>({});
  return <>
    <button type="button" className={`app-sidebar__item${open ? ' is-active' : ''}`} title="Account usage"
      aria-label="Account usage" aria-expanded={open} aria-controls={id} aria-haspopup="dialog" popoverTarget={id}
      onClick={event => {
        const rect = event.currentTarget.getBoundingClientRect();
        setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - 352)), bottom: window.innerHeight - rect.top + 8,
          maxHeight: Math.max(120, rect.top - 20) });
      }}>
      <span aria-hidden="true"><Gauge size={16} strokeWidth={1.7} /></span>
      <span className="app-sidebar__label">Usage</span>
    </button>
    <div ref={popover} id={id} popover="auto" role="dialog" aria-label="Account usage" className="usage-popover" style={position}
      onToggle={event => setOpen(event.currentTarget.matches(':popover-open'))}>
      {open && <UsagePanel selected={selected} onSelect={setSelected} onClose={() => popover.current?.hidePopover()} />}
    </div>
  </>;
}

function UsagePanel({ selected, onSelect, onClose }: { selected: string; onSelect: (value: string) => void; onClose: () => void }) {
  const [accounts, setAccounts] = useState<UsageAccountDto[] | null>(null);
  const [error, setError] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setError(false);
    void window.bridge.getUsageAccounts().then(result => {
      if (!cancelled) setAccounts(result);
    }).catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [revision]);
  const account = accounts?.find(value => value.id === selected) ?? accounts?.[0];
  return <>
    <header className="usage-header"><strong>Usage</strong><button type="button" className="usage-icon-button" aria-label="Close usage" onClick={onClose}><X size={15} /></button></header>
    {error ? <div className="usage-empty" role="status">Couldn’t load accounts. <button type="button" onClick={() => setRevision(value => value + 1)}>Try again</button></div>
      : accounts === null ? <p className="usage-empty" role="status">Loading accounts…</p>
      : account === undefined ? <p className="usage-empty">No accounts available.</p>
      : <>
          <select className="usage-account" aria-label="Usage account" value={account.id} onChange={event => onSelect(event.target.value)}>
            {accounts.map(value => <option key={value.id} value={value.id}>{value.label}</option>)}
          </select>
          <AccountUsage key={account.id} accountId={account.id} />
        </>}
  </>;
}

function AccountUsage({ accountId }: { accountId: string }) {
  const [data, setData] = useState<ProviderUsageDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [revision, setRevision] = useState(0);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 15_000);
    const poll = window.setInterval(() => setRevision(value => value + 1), 60_000);
    return () => { window.clearInterval(timer); window.clearInterval(poll); };
  }, []);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void window.bridge.getProviderUsage(accountId).then(result => {
      if (result.accountId !== accountId) throw new Error('Usage account mismatch');
      if (!cancelled) { setData(result); setNow(Date.now()); setError(false); }
    }).catch(() => { if (!cancelled) setError(true); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [accountId, revision]);
  const stale = error || data !== null && now - Date.parse(data.checkedAt) > 90_000;
  return <>
    {data?.plan && <div className="usage-plan">{data.plan.replaceAll('_', ' ')} plan</div>}
    <div className="usage-windows" aria-busy={loading}>
      {data === null && loading ? <p className="usage-empty" role="status">Checking usage…</p>
        : data?.windows.map(window => <UsageRow key={window.id} window={window} now={now} stale={stale} />)}
      {error && <p className="usage-empty" role="status">Couldn’t refresh usage. Try again.</p>}
      {data?.message && <p className="usage-empty" role="status">{data.message}</p>}
    </div>
    {data !== null && data.windows.length > 0 && <div className="usage-legend" aria-label="Bar legend">
      <span><i className="usage-used" />Used</span><span><i className="usage-room" />Room</span><span><i className="usage-over" />Over pace</span>
    </div>}
    <footer className="usage-footer">
      <span>{data === null ? 'Account-wide usage' : `Checked ${new Date(data.checkedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}</span>
      <button type="button" className="usage-icon-button" aria-label="Refresh usage" title="Refresh usage (cached for up to a minute)" disabled={loading}
        onClick={() => setRevision(value => value + 1)}><span className={`bq-spinner${loading ? ' is-spinning' : ''}`}><RefreshCw size={14} /></span></button>
    </footer>
  </>;
}

export function UsageRow({ window, now, stale = false }: { window: ProviderUsageWindowDto; now: number; stale?: boolean }) {
  const pace = usagePace(window, now, stale);
  const percentage = `${Math.round(window.usedPercent)}% used`;
  const amount = window.amount;
  const format = amount === null ? null : new Intl.NumberFormat(undefined, { style: 'currency', currency: amount.currency, maximumFractionDigits: 2 });
  const value = pace.expired ? '—' : amount !== null && format !== null ? `${format.format(amount.used)} / ${format.format(amount.total)}` : percentage;
  const description = `${window.label}: ${percentage}. ${pace.guidance}.${pace.expected === null ? '' : ` Expected usage ${Math.round(pace.expected)}%.`} ${resetLabel(window.resetsAt, now)}.`;
  return <section className="usage-row">
    <div className="usage-row-title"><span>{window.label}</span><strong>{value}</strong></div>
    <div className="usage-track" role="img" aria-label={description} title={description}>
      <span className="usage-used" style={{ width: `${pace.gray}%` }} /><span className="usage-room" style={{ width: `${pace.green}%` }} /><span className="usage-over" style={{ width: `${pace.red}%` }} />
    </div>
    <div className="usage-row-detail"><span>{resetLabel(window.resetsAt, now)}</span><span>{pace.guidance}</span></div>
  </section>;
}
