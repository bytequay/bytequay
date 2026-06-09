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
import type {
  Ds4ConfigDto,
  Ds4InstallStatusDto,
  Ds4MetricsDto,
  Ds4StateDto,
  Ds4StatusDto,
} from '../../types';

type Tab = 'management' | 'metrics';

/**
 * Settings → Local AI (ds4) page. Hosts a shared status header
 * (state pill + endpoint + uptime + Stop/Restart) above two tabs:
 * Management (lifecycle controls + config form + install affordance)
 * and Metrics (memory hero + throughput / latency tiles + recent
 * ByteQuay-only calls log). Switching tabs preserves the header so
 * the lifecycle actions are always one click away.
 */
export default function LocalAiPage() {
  const [tab, setTab] = useState<Tab>('management');
  const [status, setStatus] = useState<Ds4StatusDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.getDs4Status();
      setStatus(next);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
    // Cheap poll so the header reflects supervisor transitions
    // without the user reloading. 3s matches the floating widget's
    // cadence so the two never drift more than one tick apart.
    const id = window.setInterval(() => { void refresh(); }, 3_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  return (
    <div style={pageStyle}>
      <header style={headerWrapStyle}>
        <h1 style={titleStyle}>Local AI (ds4)</h1>
        <p style={subtitleStyle}>
          Run and manage the local DeepSeek V4 Flash inference server.
          The server is shared with external clients that may already
          be connected; Stop and Restart affect every consumer at once.
        </p>
      </header>

      {error !== null && (
        <div role="alert" style={alertStyle}>{error}</div>
      )}

      <Ds4StatusHeader status={status} onChanged={refresh} />

      <div role="tablist" style={tabBarStyle}>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'management'}
          onClick={() => setTab('management')}
          style={tabBtnStyle(tab === 'management')}
        >
          Management
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'metrics'}
          onClick={() => setTab('metrics')}
          style={tabBtnStyle(tab === 'metrics')}
        >
          Metrics
        </button>
      </div>

      {tab === 'management' && (
        <Ds4ManagementTab status={status} onChanged={refresh} />
      )}
      {tab === 'metrics' && (
        <Ds4MetricsTab visible />
      )}
    </div>
  );
}

function Ds4StatusHeader({
  status, onChanged,
}: {
  status: Ds4StatusDto | null;
  onChanged: () => Promise<void> | void;
}) {
  const onStop = useCallback(async () => {
    if (status === null) return;
    if (status.spawnedByUs === false) {
      const ok = window.confirm(
        'ds4 was started outside ByteQuay and is shared with external clients. Stop anyway?',
      );
      if (!ok) return;
    }
    await window.bridge.stopDs4(/* confirm */ status.spawnedByUs === false);
    await onChanged();
  }, [status, onChanged]);

  const onRestart = useCallback(async () => {
    await window.bridge.restartDs4();
    await onChanged();
  }, [onChanged]);

  const onStart = useCallback(async () => {
    await window.bridge.startDs4();
    await onChanged();
  }, [onChanged]);

  if (status === null) {
    return <div style={statusHeaderStyle}>Loading…</div>;
  }

  return (
    <section style={statusHeaderStyle} aria-label="ds4 status">
      <div style={statusRowStyle}>
        <StatePill state={status.state} />
        <span style={endpointStyle}>{status.endpoint}</span>
        {status.pid > 0 && (
          <span style={metaStyle}>PID {status.pid}</span>
        )}
        <span style={metaStyle}>{formatUptime(status.uptimeSec)}</span>
        <span style={metaStyle}>
          {status.spawnedByUs ? 'spawned by ByteQuay' : 'attached to external server'}
        </span>
        <div style={spacerStyle} />
        {status.state === 'STOPPED' || status.state === 'NOT_CONFIGURED' || status.state === 'CRASHED' ? (
          <button type="button" style={primaryBtnStyle} onClick={() => { void onStart(); }}>
            ▶ Start
          </button>
        ) : (
          <>
            <button type="button" style={secondaryBtnStyle} onClick={() => { void onStop(); }}>
              ■ Stop
            </button>
            <button type="button" style={secondaryBtnStyle} onClick={() => { void onRestart(); }}>
              ↻ Restart
            </button>
          </>
        )}
      </div>
      {status.lastError !== null && (
        <div style={lastErrorStyle}>{status.lastError}</div>
      )}
    </section>
  );
}

function StatePill({ state }: { state: Ds4StateDto }) {
  const palette = paletteFor(state);
  return (
    <span style={{ ...statePillStyle, background: palette.bg, color: palette.fg, borderColor: palette.border }}>
      <span style={{ ...stateDotStyle, background: palette.dot }} aria-hidden />
      {state}
    </span>
  );
}

function paletteFor(state: Ds4StateDto) {
  switch (state) {
    case 'RUNNING':
      return { bg: 'rgba(34,197,94,0.10)', fg: '#15803d', border: 'rgba(34,197,94,0.30)', dot: '#22c55e' };
    case 'STARTING':
    case 'STOPPING':
      return { bg: 'rgba(245,158,11,0.10)', fg: '#b45309', border: 'rgba(245,158,11,0.30)', dot: '#f59e0b' };
    case 'CRASHED':
      return { bg: 'rgba(239,68,68,0.10)', fg: '#b91c1c', border: 'rgba(239,68,68,0.30)', dot: '#ef4444' };
    case 'NOT_CONFIGURED':
      return { bg: 'var(--bg-elevated)', fg: 'var(--text-3)', border: 'var(--border)', dot: 'var(--text-3)' };
    default:
      return { bg: 'var(--bg-elevated)', fg: 'var(--text-2)', border: 'var(--border)', dot: 'var(--text-3)' };
  }
}

function formatUptime(sec: number): string {
  if (sec <= 0) return '—';
  const days = Math.floor(sec / 86400);
  const hours = Math.floor((sec % 86400) / 3600);
  const mins = Math.floor((sec % 3600) / 60);
  if (days > 0) return `up ${days}d ${hours}h`;
  if (hours > 0) return `up ${hours}h ${mins}m`;
  return `up ${mins}m`;
}

function Ds4ManagementTab({
  status, onChanged,
}: {
  status: Ds4StatusDto | null;
  onChanged: () => Promise<void> | void;
}) {
  const [config, setConfig] = useState<Ds4ConfigDto | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Ds4ConfigDto | null>(null);
  const [restartBanner, setRestartBanner] = useState(false);
  const [installStatus, setInstallStatus] = useState<Ds4InstallStatusDto | null>(null);

  useEffect(() => {
    void window.bridge.getDs4Config().then(setConfig);
    void window.bridge.getDs4InstallStatus().then(setInstallStatus);
  }, []);

  const beginEdit = () => {
    if (config === null) return;
    setDraft({ ...config });
    setEditing(true);
  };

  const saveDraft = useCallback(async (restartNow: boolean) => {
    if (draft === null) return;
    const resp = await window.bridge.setDs4Config(draft, restartNow);
    setConfig(resp.config);
    setEditing(false);
    setRestartBanner(resp.restartRequired);
    await onChanged();
  }, [draft, onChanged]);

  const onInstall = useCallback(async () => {
    const next = await window.bridge.installDs4();
    setInstallStatus(next);
  }, []);

  return (
    <div style={tabBodyStyle}>
      <Card title="Lifecycle">
        <p style={mutedStyle}>
          Start / Stop / Restart drive the supervisor in the same way the
          buttons above the tabs do. The toggles below decide how the
          supervisor behaves when ByteQuay boots, when the server
          crashes, and when an external client already runs the server.
        </p>
        {config !== null && (
          <div style={toggleListStyle}>
            <Toggle
              label="Auto-restart on crash"
              hint="1s / 2s / 5s / 15s back-off; gives up after 5 attempts."
              value={config.autoRestartOnCrash}
              onChange={(v) => persistFlag(config, setConfig, 'autoRestartOnCrash', v)}
            />
            <Toggle
              label="Attach to a running server"
              hint="Skip spawning a duplicate when another client is already serving on the port."
              value={config.attachIfRunning}
              onChange={(v) => persistFlag(config, setConfig, 'attachIfRunning', v)}
            />
            <Toggle
              label="Start ds4 with ByteQuay"
              hint="Auto-Start when the app boots and no healthy server is attached."
              value={config.autoStartOnBoot}
              onChange={(v) => persistFlag(config, setConfig, 'autoStartOnBoot', v)}
            />
          </div>
        )}
      </Card>

      <Card title="Launch config" right={editing ? (
        <button type="button" style={secondaryBtnStyle} onClick={() => setEditing(false)}>
          Cancel
        </button>
      ) : (
        <button type="button" style={secondaryBtnStyle} onClick={beginEdit}>
          Edit
        </button>
      )}>
        {restartBanner && (
          <div style={infoBannerStyle}>
            <strong>Applies on restart.</strong> The server has no hot-reload —
            the change is saved, but the next turn keeps using the previously-
            spawned process until you Restart.
          </div>
        )}
        {(editing ? draft : config) === null ? (
          <p style={mutedStyle}>Loading…</p>
        ) : (
          <ConfigPanel
            config={(editing ? draft : config) as Ds4ConfigDto}
            readOnly={!editing}
            onChange={(next) => setDraft(next)}
          />
        )}
        {editing && draft !== null && (
          <div style={editFooterStyle}>
            <button type="button" style={secondaryBtnStyle} onClick={() => { void saveDraft(false); }}>
              Save
            </button>
            <button type="button" style={primaryBtnStyle} onClick={() => { void saveDraft(true); }}>
              Save &amp; Restart
            </button>
          </div>
        )}
      </Card>

      <Card title="Install ds4">
        <p style={mutedStyle}>
          ByteQuay can download the ds4 binary into the app-owned
          install path. The lifecycle service auto-points{' '}
          <code>binaryPath</code> at the downloaded file on success;
          a fresh install path will drop the state out of{' '}
          <strong>NOT_CONFIGURED</strong> without further action.
        </p>
        {installStatus !== null && installStatus.phase !== 'IDLE' && (
          <div style={infoBannerStyle}>
            <strong>{installStatus.phase}</strong>
            {installStatus.destination !== null && (
              <> · {installStatus.destination}</>
            )}
            {installStatus.error !== null && (
              <div style={{ color: '#b91c1c', marginTop: 4 }}>{installStatus.error}</div>
            )}
          </div>
        )}
        <button type="button" style={primaryBtnStyle} onClick={() => { void onInstall(); }}>
          Download ds4
        </button>
      </Card>

      <Card title="Wired into work model">
        <p style={mutedStyle}>
          The locally-served <code>deepseek-v4-flash</code> model
          variant is part of the existing DeepSeek provider — the
          API logic loop and review path are unchanged. The
          readiness gate for this model is{' '}
          {status?.state === 'RUNNING'
            ? <strong style={{ color: '#15803d' }}>● server running</strong>
            : <strong style={{ color: 'var(--text-3)' }}>○ server not running</strong>}
          .
        </p>
      </Card>
    </div>
  );
}

function persistFlag<K extends keyof Ds4ConfigDto>(
  current: Ds4ConfigDto,
  setLocal: (next: Ds4ConfigDto) => void,
  key: K,
  value: Ds4ConfigDto[K],
) {
  const next = { ...current, [key]: value };
  setLocal(next);
  void window.bridge.setDs4Config(next, false);
}

function Ds4MetricsTab({ visible }: { visible: boolean }) {
  const [metrics, setMetrics] = useState<Ds4MetricsDto | null>(null);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    const load = async () => {
      try {
        const next = await window.bridge.getDs4Metrics();
        if (!cancelled) setMetrics(next);
      }
      catch {
        // The polling tile shows zeros until the next attempt;
        // a per-error banner here would mostly be noise on a
        // background reload.
      }
    };
    void load();
    const id = window.setInterval(load, 5_000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [visible]);

  if (metrics === null) {
    return <div style={tabBodyStyle}><p style={mutedStyle}>Loading metrics…</p></div>;
  }

  return (
    <div style={tabBodyStyle}>
      <Card title="Memory">
        <div style={memoryHeroStyle}>
          {formatBytes(metrics.memory.weightsBytes + metrics.memory.kvCacheBytes)} /
          {' '}{formatBytes(metrics.memory.ceilingBytes)}
          {' '}({Math.round(metrics.memory.pct * 100)}%)
        </div>
      </Card>

      <div style={tileGridStyle}>
        <Tile
          label="Generation"
          value={`${formatTps(metrics.throughput.currentTps)} t/s`}
          sub={`avg 1m ${formatTps(metrics.throughput.avg1mTps)} · peak today ${formatTps(metrics.throughput.peakTodayTps)}`}
        />
        <Tile
          label="First-token latency"
          value={`${metrics.latency.firstTokenMs} ms`}
          sub={`avg 1m ${metrics.latency.avg1mMs} ms`}
        />
        <Tile
          label="KV cache on disk"
          value={`${formatBytes(metrics.kvOnDisk.usedBytes)} / ${formatBytes(metrics.kvOnDisk.budgetBytes)}`}
          sub={`${Math.round(metrics.kvOnDisk.pct * 100)}% of budget`}
          warn={metrics.kvOnDisk.pct >= 0.6}
        />
        <Tile
          label="Requests today"
          value={`${metrics.requestsToday.count}`}
          sub={`in ${formatTokens(metrics.requestsToday.tokensIn)} · out ${formatTokens(metrics.requestsToday.tokensOut)}`}
        />
      </div>

      <Card title="Recent requests" right={<span style={pillStyle}>ByteQuay calls only</span>}>
        <p style={mutedStyle}>
          External clients sharing this server aren't shown here in v1.
          The front-door proxy that would also capture their traffic is
          a documented follow-up.
        </p>
        {metrics.recentRequests.length === 0 ? (
          <p style={mutedStyle}>No recorded calls yet.</p>
        ) : (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Time</th>
                <th style={thStyle}>Caller</th>
                <th style={thStyle}>Route</th>
                <th style={thStyle}>Tokens in/out</th>
                <th style={thStyle}>t/s</th>
                <th style={thStyle}>Status</th>
              </tr>
            </thead>
            <tbody>
              {metrics.recentRequests.slice(-20).reverse().map((r) => (
                <tr key={r.tsMs}>
                  <td style={tdStyle}>{new Date(r.tsMs).toLocaleTimeString()}</td>
                  <td style={tdStyle}>{r.caller}</td>
                  <td style={tdStyle}>{r.route}</td>
                  <td style={tdStyle}>{r.tokensIn} / {r.tokensOut}</td>
                  <td style={tdStyle}>{formatTps(r.tps)}</td>
                  <td style={tdStyle}>{r.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}

function Card({ title, right, children }: {
  title: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section style={cardStyle}>
      <header style={cardHeaderStyle}>
        <h2 style={cardTitleStyle}>{title}</h2>
        <div style={{ flex: 1 }} />
        {right}
      </header>
      <div style={cardBodyStyle}>{children}</div>
    </section>
  );
}

function Toggle({
  label, hint, value, onChange,
}: {
  label: string;
  hint: string;
  value: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <label style={toggleRowStyle}>
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        <span style={{ fontWeight: 600 }}>{label}</span>
        <span style={mutedStyle}>{hint}</span>
      </div>
    </label>
  );
}

function ConfigPanel({
  config, readOnly, onChange,
}: {
  config: Ds4ConfigDto;
  readOnly: boolean;
  onChange: (next: Ds4ConfigDto) => void;
}) {
  return (
    <div style={configGridStyle}>
      <KV label="Binary path">
        {readOnly ? (config.binaryPath ?? '— not set —') : (
          <input
            value={config.binaryPath ?? ''}
            onChange={(e) => onChange({ ...config, binaryPath: e.target.value })}
            style={inputStyle}
            placeholder="/usr/local/bin/ds4-server"
          />
        )}
      </KV>
      <KV label="Port">
        {readOnly ? config.port : (
          <input
            type="number" value={config.port}
            onChange={(e) => onChange({ ...config, port: Number(e.target.value) })}
            style={inputStyle}
          />
        )}
      </KV>
      <KV label="Model">{config.model}</KV>
      <KV label="Quant">
        {readOnly ? config.quant : (
          <input
            value={config.quant}
            onChange={(e) => onChange({ ...config, quant: e.target.value })}
            style={inputStyle}
          />
        )}
      </KV>
      <KV label="Context tokens">
        {readOnly ? config.contextTokens : (
          <input
            type="number" value={config.contextTokens}
            onChange={(e) => onChange({ ...config, contextTokens: Number(e.target.value) })}
            style={inputStyle}
          />
        )}
      </KV>
      <KV label="KV cache dir">
        {readOnly ? config.kvCacheDir : (
          <input
            value={config.kvCacheDir}
            onChange={(e) => onChange({ ...config, kvCacheDir: e.target.value })}
            style={inputStyle}
          />
        )}
      </KV>
      <KV label="KV disk budget (MB)">
        {readOnly ? config.kvDiskBudgetMb : (
          <input
            type="number" value={config.kvDiskBudgetMb}
            onChange={(e) => onChange({ ...config, kvDiskBudgetMb: Number(e.target.value) })}
            style={inputStyle}
          />
        )}
      </KV>
      <KV label="Install URL">
        {readOnly ? (config.installUrl || '— not set —') : (
          <input
            value={config.installUrl}
            onChange={(e) => onChange({ ...config, installUrl: e.target.value })}
            style={inputStyle}
          />
        )}
      </KV>
    </div>
  );
}

function KV({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={kvRowStyle}>
      <span style={kvLabelStyle}>{label}</span>
      <span style={kvValueStyle}>{children}</span>
    </div>
  );
}

function Tile({
  label, value, sub, warn,
}: {
  label: string;
  value: string;
  sub?: string;
  warn?: boolean;
}) {
  return (
    <div style={{ ...tileStyle, borderColor: warn ? 'rgba(245,158,11,0.45)' : 'var(--border)' }}>
      <div style={tileLabelStyle}>{label}</div>
      <div style={tileValueStyle}>{value}</div>
      {sub !== undefined && <div style={tileSubStyle}>{sub}</div>}
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const idx = Math.min(units.length - 1, Math.floor(Math.log10(Math.max(bytes, 1)) / 3));
  const value = bytes / Math.pow(1000, idx);
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[idx]}`;
}

function formatTokens(n: number): string {
  if (n < 1_000) return `${n}`;
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

function formatTps(n: number): string {
  if (n === 0) return '0';
  return n >= 10 ? `${Math.round(n)}` : n.toFixed(1);
}

/* ── styles ─────────────────────────────────────────────────────── */

const pageStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
  padding: '20px 28px',
  maxWidth: 920,
};

const headerWrapStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const titleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 22,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const subtitleStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-3)',
  fontSize: 13,
  lineHeight: 1.5,
};

const alertStyle: React.CSSProperties = {
  padding: '8px 12px',
  fontSize: 12,
  color: '#b91c1c',
  background: 'rgba(239,68,68,0.08)',
  border: '1px solid rgba(239,68,68,0.20)',
  borderRadius: 8,
};

const statusHeaderStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  padding: 14,
  border: '1px solid var(--border)',
  borderRadius: 12,
  background: 'var(--bg-elevated)',
};

const statusRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  flexWrap: 'wrap',
};

const statePillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 10px',
  fontSize: 11,
  fontWeight: 700,
  borderRadius: 999,
  border: '1px solid',
  letterSpacing: '0.04em',
};

const stateDotStyle: React.CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: 999,
};

const endpointStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  color: 'var(--text-2)',
};

const metaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const spacerStyle: React.CSSProperties = {
  flex: 1,
};

const primaryBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  fontSize: 12,
  fontWeight: 600,
  border: '1px solid rgba(124,58,237,0.55)',
  background: 'rgba(124,58,237,0.10)',
  color: '#5b21b6',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const secondaryBtnStyle: React.CSSProperties = {
  padding: '6px 12px',
  fontSize: 12,
  border: '1px solid var(--border)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const lastErrorStyle: React.CSSProperties = {
  padding: '6px 10px',
  fontSize: 12,
  color: '#b45309',
  background: 'rgba(245,158,11,0.08)',
  border: '1px solid rgba(245,158,11,0.20)',
  borderRadius: 6,
};

const tabBarStyle: React.CSSProperties = {
  display: 'inline-flex',
  gap: 4,
  padding: 4,
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  alignSelf: 'flex-start',
};

function tabBtnStyle(active: boolean): React.CSSProperties {
  return {
    padding: '6px 14px',
    fontSize: 13,
    fontWeight: active ? 600 : 500,
    border: 'none',
    background: active ? 'rgba(124,58,237,0.12)' : 'transparent',
    color: active ? '#5b21b6' : 'var(--text-2)',
    borderRadius: 8,
    cursor: 'pointer',
    fontFamily: 'inherit',
  };
}

const tabBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const cardStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 12,
  background: '#fff',
  overflow: 'hidden',
};

const cardHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '10px 14px',
  borderBottom: '1px solid var(--border)',
};

const cardTitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 13,
  fontWeight: 700,
  letterSpacing: '0.04em',
  color: 'var(--text-1)',
};

const cardBodyStyle: React.CSSProperties = {
  padding: 14,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const mutedStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 12,
  lineHeight: 1.5,
  margin: 0,
};

const toggleListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
};

const toggleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  fontSize: 12,
  color: 'var(--text-2)',
};

const infoBannerStyle: React.CSSProperties = {
  padding: '8px 10px',
  fontSize: 12,
  color: '#1f5fbf',
  background: 'rgba(31,95,191,0.08)',
  border: '1px solid rgba(31,95,191,0.20)',
  borderRadius: 6,
};

const editFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
};

const configGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '180px 1fr',
  rowGap: 8,
  columnGap: 12,
  fontSize: 12,
};

const kvRowStyle: React.CSSProperties = {
  display: 'contents',
};

const kvLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontWeight: 600,
};

const kvValueStyle: React.CSSProperties = {
  color: 'var(--text-1)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  wordBreak: 'break-all',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '4px 8px',
  fontSize: 12,
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: '#fff',
  color: 'var(--text-1)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const tileGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, 1fr)',
  gap: 10,
};

const tileStyle: React.CSSProperties = {
  padding: 12,
  border: '1px solid var(--border)',
  borderRadius: 10,
  background: '#fff',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const tileLabelStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
};

const tileValueStyle: React.CSSProperties = {
  fontSize: 22,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const tileSubStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const memoryHeroStyle: React.CSSProperties = {
  fontSize: 22,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const pillStyle: React.CSSProperties = {
  display: 'inline-block',
  padding: '2px 8px',
  fontSize: 10,
  fontWeight: 700,
  borderRadius: 999,
  background: 'rgba(31,95,191,0.10)',
  color: '#1f5fbf',
};

const tableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 12,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  fontSize: 10,
  fontWeight: 700,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  borderBottom: '1px solid var(--border)',
};

const tdStyle: React.CSSProperties = {
  padding: '6px 8px',
  borderBottom: '1px solid var(--border)',
  color: 'var(--text-2)',
};
