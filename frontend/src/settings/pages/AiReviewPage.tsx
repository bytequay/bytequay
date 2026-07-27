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
import type { AiDefaultsDto, AiLedgerDto, Ds4StateDto, WorkModelOptionsDto } from '../../types';
import {
  type AgentChoice,
  choiceClass,
  choiceGlyph,
  choicesFrom,
  choiceText,
  selectableChoice,
} from '../../workspace/agentChoices';
import SettingsPage, { type SettingsTab } from '../shared/SettingsPage';
import { ChevronDownIcon, InfoIcon } from '../shared/icons';
import LocalAiPage from './LocalAiPage';

export type AiTab = 'defaults' | 'backends' | 'local' | 'usage';

const TABS: SettingsTab<AiTab>[] = [
  { id: 'defaults', label: 'Defaults' },
  { id: 'backends', label: 'Backends' },
  { id: 'local', label: 'Local AI (ds4)', badge: 'EXP' },
  { id: 'usage', label: 'Usage' },
];

/** The four workspace session kinds, in the order the workspace Agents
 *  tab shows them. `key` is the field on {@link AiDefaultsDto}. */
const SESSION_KINDS: { key: keyof AiDefaultsDto; kind: string; bg: string; fg: string; title: string; sub: string }[] = [
  { key: 'plan', kind: 'plan', bg: '#fbefff', fg: '#8250df', title: 'Deep reasoning for specs & plans', sub: 'Trunk sessions — planning and task cutting' },
  { key: 'dev', kind: 'dev', bg: '#ddf4ff', fg: '#0969da', title: 'Code writing & tests', sub: 'Task sessions — edits, tests, gated pushes' },
  { key: 'review', kind: 'review', bg: '#dafbe1', fg: '#1a7f37', title: 'PR review rounds', sub: 'Reviewer and Lead seats on the panel' },
  { key: 'ciFix', kind: 'ci fix', bg: '#ffebe9', fg: '#cf222e', title: 'Cheap loops on red builds', sub: 'Headless auto-fix turns on failing CI' },
];

/** Account-wide roles. These have no workspace equivalent, so the value
 *  stored here is the one that actually runs. */
const GLOBAL_ROLES: typeof SESSION_KINDS = [
  { key: 'globalReview', kind: 'review', bg: '#dafbe1', fg: '#1a7f37', title: 'Global PR review', sub: 'Quick reviews for PRs whose repositories do not have a workspace yet' },
  { key: 'triage', kind: 'triage', bg: '#fff8c5', fg: '#9a6700', title: 'Issue triage', sub: 'Reads new issues and proposes labels and an owner — never comments without your click' },
  { key: 'perf', kind: 'perf', bg: '#f0f2f4', fg: '#454c54', title: 'Performance investigator', sub: 'Digs through slow paths and profiles before proposing a fix' },
];

/**
 * Settings → AI. Four tabs over one subject: which engine runs each kind
 * of agent work (Defaults), what is installed to run it (Backends), the
 * experimental local server (Local AI), and what it all cost (Usage).
 */
function AiReviewPage({ initialTab = 'defaults' }: { initialTab?: AiTab }) {
  const [tab, setTab] = useState<AiTab>(initialTab);
  useEffect(() => { setTab(initialTab); }, [initialTab]);

  return (
    <SettingsPage
      title="AI"
      width={1000}
      subtitle="Which engine runs each kind of agent work. Workspace session kinds inherit these values; global roles run without a workspace."
      tabs={TABS}
      activeTab={tab}
      onSelectTab={next => setTab(next)}
    >
      {tab === 'defaults' && <DefaultsTab />}
      {tab === 'backends' && <BackendsTab />}
      {tab === 'local' && <LocalAiPage embedded />}
      {tab === 'usage' && <UsageTab />}
    </SettingsPage>
  );
}

/** Shared probe of what can run agent work on this machine. */
function useEngines() {
  const [options, setOptions] = useState<WorkModelOptionsDto | null>(null);
  const [localState, setLocalState] = useState<Ds4StateDto | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const read = async (force: boolean) => {
    const [next, ds4] = await Promise.all([
      force ? window.bridge.refreshWorkModelOptions() : window.bridge.getWorkModelOptions(),
      window.bridge.getDs4Status(),
    ]);
    setOptions(next);
    setLocalState(ds4.state);
  };

  useEffect(() => { void read(false).catch(() => { /* rows fall back to "checking…" */ }); }, []);

  const refresh = async () => {
    setRefreshing(true);
    try {
      await read(true);
    } finally {
      setRefreshing(false);
    }
  };

  return {
    options,
    localState,
    refreshing,
    refresh,
    choices: choicesFrom(options, localState),
  };
}

function DefaultsTab() {
  const { options, localState, refreshing, refresh, choices } = useEngines();
  const [defaults, setDefaults] = useState<AiDefaultsDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    window.bridge.getAiDefaults()
      .then(setDefaults)
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const save = async (key: keyof AiDefaultsDto, value: string) => {
    if (defaults === null) return;
    const next = { ...defaults, [key]: value };
    setDefaults(next);
    try {
      setDefaults(await window.bridge.setAiDefaults(next));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const rows = (group: typeof SESSION_KINDS) => group.map(row => (
    <EngineRow
      key={row.key}
      row={row}
      choices={choices}
      value={defaults === null ? null : selectableChoice(defaults[row.key], choices)}
      onChange={value => { void save(row.key, value); }}
    />
  ));

  return (
    <>
      <div className="sv2-note sv2-note--info">
        <span className="sv2-note__icon"><InfoIcon size={15} /></span>
        <span>
          These are account-level defaults. Workspace session kinds seed the
          <strong> Agents</strong> tab; global roles are used directly when work has no workspace.
          Workspace overrides are never written back here.
        </span>
      </div>

      {error !== null && <div className="sv2-error" role="alert">{error}</div>}

      <div className="sv2-card">
        <div className="sv2-card__head">
          <span className="sv2-card__title">Defaults per session kind</span>
          <span className="sv2-card__hint">threads can override per run</span>
          <button
            className="sv2-btn sv2-btn--sm"
            type="button"
            style={{ marginLeft: 'auto' }}
            disabled={refreshing}
            onClick={() => { void refresh(); }}
          >
            {refreshing ? 'Checking…' : 'Check'}
          </button>
        </div>
        {rows(SESSION_KINDS)}
      </div>

      <div className="sv2-card">
        <div className="sv2-card__head">
          <span className="sv2-card__title">Global roles</span>
          <span className="sv2-card__hint">account-wide agents — no workspace equivalent</span>
        </div>
        {rows(GLOBAL_ROLES)}
      </div>

      {options === null && localState === null && <div className="sv2-loading">Probing engines…</div>}
    </>
  );
}

function EngineRow({ row, choices, value, onChange }: {
  row: (typeof SESSION_KINDS)[number];
  choices: AgentChoice[];
  value: string | null;
  onChange: (value: string) => void;
}) {
  const selected = choices.find(c => c.value === value);
  const available = selected !== undefined && selected.disabled !== true;
  return (
    <div className="sv2-ai__row">
      <span className="sv2-ai__kind" style={{ background: row.bg, color: row.fg }}>{row.kind}</span>
      <span style={{ minWidth: 0 }}>
        <span className="sv2-ai__title">{row.title}</span>
        <span className="sv2-ai__sub">{row.sub}</span>
      </span>
      <span className="sv2-ai__pick">
        <span className={`sv2-ai__glyph sv2-ai__glyph--${choiceClass(value ?? 'local')}`}>
          {choiceGlyph(value ?? 'local')}
        </span>
        <span className="sv2-ai__select">
          <span>{selected === undefined ? (value ?? '…') : choiceText(selected)}</span>
          <ChevronDownIcon size={12} />
          <select
            aria-label={`${row.title} engine`}
            value={value ?? ''}
            disabled={value === null}
            onChange={e => onChange(e.target.value)}
          >
            {choices.map(choice => (
              <option key={choice.value} value={choice.value} disabled={choice.disabled}>
                {choiceText(choice)}
              </option>
            ))}
          </select>
        </span>
        <span className={'sv2-ai__status' + (available ? ' sv2-ai__status--ok' : '')}>
          {selected === undefined ? 'checking…' : available ? 'reachable' : selected.detail}
        </span>
      </span>
    </div>
  );
}

function BackendsTab() {
  const { options, localState, refreshing, refresh } = useEngines();

  const rows = [
    ...(options?.cliAgents ?? []).map(agent => ({
      tag: agent.id === 'codex' ? 'x' : 'c',
      tagBg: agent.id === 'codex' ? '#dafbe1' : '#ffe7d1',
      tagFg: agent.id === 'codex' ? '#1a7f37' : '#bc4c00',
      name: agent.displayName,
      detail: agent.defaultModel,
      available: agent.installed && agent.authed,
      status: !agent.installed ? 'not installed' : agent.authed ? 'available' : 'signed out',
    })),
    ...(options?.apiProviders ?? []).map(provider => ({
      tag: 'a',
      tagBg: '#ddf4ff',
      tagFg: '#0969da',
      name: `${provider.displayName} API`,
      detail: `${provider.defaultModel} · ${provider.accounts.length} key${provider.accounts.length === 1 ? '' : 's'} in vault`,
      available: provider.accounts.length > 0,
      status: provider.accounts.length > 0 ? 'available' : 'no key',
    })),
    {
      tag: 'l',
      tagBg: '#fbefff',
      tagFg: '#8250df',
      name: 'Local AI (ds4)',
      detail: 'http://127.0.0.1:8000',
      available: localState === 'RUNNING',
      status: localState === null ? 'checking…' : localState.toLowerCase(),
    },
  ];

  return (
    <>
      <div className="sv2-card">
        <div className="sv2-card__head">
          <span className="sv2-card__title">Engines on this Mac</span>
          <span className="sv2-card__hint">the CLIs, API providers and local server ByteQuay can call</span>
          <button
            className="sv2-btn sv2-btn--sm"
            type="button"
            style={{ marginLeft: 'auto' }}
            disabled={refreshing}
            onClick={() => { void refresh(); }}
          >
            {refreshing ? 'Checking…' : 'Check'}
          </button>
        </div>
        {options === null && <div className="sv2-loading" style={{ padding: '10px 18px 16px' }}>Probing engines…</div>}
        {rows.map(row => (
          <div className="sv2-ai__backend" key={row.name}>
            <span className="sv2-ai__backend-tag" style={{ background: row.tagBg, color: row.tagFg }}>{row.tag}</span>
            <span className="sv2-ai__backend-main">
              <span className="sv2-ai__backend-name">
                {row.name}
                <span className={'sv2-ai__avail ' + (row.available ? 'sv2-ai__avail--on' : 'sv2-ai__avail--off')}>
                  <i />{row.status}
                </span>
              </span>
              <span className="sv2-ai__backend-detail">{row.detail}</span>
            </span>
          </div>
        ))}
      </div>
      <div style={{ fontSize: 12.5, color: 'var(--sv2-text-4)' }}>
        API keys live in Credentials. CLI engines use whatever you are already signed into on this machine.
      </div>
    </>
  );
}

/** Last 12 months as YYYY-MM, newest first, for the picker. */
function recentMonths(now: Date): string[] {
  const out: string[] = [];
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
  }
  return out;
}

function UsageTab() {
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
      <div className="sv2-ai__stats">
        <span className="sv2-ai__stat">
          <span>TOTAL SPEND</span>
          <strong>{ledger === null ? '—' : cents(ledger.totalCents)}</strong>
        </span>
        <span className="sv2-ai__stat">
          <span>CALLS</span>
          <strong>{ledger === null ? '—' : String(ledger.totalCalls)}</strong>
        </span>
        <label style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--sv2-text-3)' }}>
          Month
          <select
            className="sv2-input"
            style={{ height: 30, width: 'auto' }}
            value={month}
            onChange={e => setMonth(e.target.value)}
            aria-label="Ledger month"
          >
            {months.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>

      {error !== null && <div className="sv2-error" role="alert">{error}</div>}
      {loading && <div className="sv2-loading">Loading…</div>}

      {!loading && ledger !== null && (
        <>
          <div className="sv2-ai__ledger">
            <LedgerCard
              title="By provider"
              color="#0969da"
              rows={ledger.byProvider.map(p => ({ name: p.provider, calls: p.callsCount, cents: p.costCents }))}
            />
            <LedgerCard
              title="By work type"
              color="#8250df"
              rows={ledger.byTaskType.map(t => ({ name: t.type, calls: t.callsCount, cents: t.costCents }))}
            />
          </div>
          {ledger.totalCalls === 0 && (
            <div style={{ fontSize: 12.5, color: 'var(--sv2-text-4)' }}>No AI spend recorded for {month}.</div>
          )}
        </>
      )}
    </>
  );
}

function LedgerCard({ title, color, rows }: {
  title: string;
  color: string;
  rows: { name: string; calls: number; cents: number }[];
}) {
  const peak = rows.reduce((max, r) => Math.max(max, r.cents), 0);
  return (
    <div className="sv2-card">
      <div className="sv2-card__head"><span className="sv2-card__title">{title}</span></div>
      {rows.length === 0 && <div className="sv2-loading" style={{ padding: '0 16px 14px' }}>—</div>}
      {rows.map(row => (
        <div className="sv2-ai__bar-row" key={row.name}>
          <div className="sv2-ai__bar-head">
            <b>{row.name}</b>
            <i>{row.calls} calls</i>
            <em>${(row.cents / 100).toFixed(2)}</em>
          </div>
          <div className="sv2-ai__bar">
            <span style={{
              width: `${peak === 0 ? 2 : Math.max(2, Math.round((row.cents / peak) * 100))}%`,
              background: color,
            }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export default AiReviewPage;
