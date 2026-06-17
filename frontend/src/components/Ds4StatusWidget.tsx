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
import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  Ds4MetricsDto,
  Ds4StateDto,
  Ds4StatusDto,
} from '../types';

type Props = {
  /** Open the Settings → Local AI page. The parent decides what
   *  "open" means (route push, modal, etc.). */
  onOpenManagement: () => void;
  /** When true the widget hides itself entirely — used by the
   *  immersive surfaces (thread-group view, full-screen diff). */
  hidden?: boolean;
};

/**
 * Always-visible floating chip for the local ds4 inference server.
 * Renders once in the app shell at fixed bottom-right. Collapsed
 * shows the live state + throughput + uptime; click expands to a
 * vitals popover with Stop / Restart; right-click skips the popover
 * with a quick menu.
 */
export function Ds4StatusWidget({ onOpenManagement, hidden }: Props) {
  const [status, setStatus] = useState<Ds4StatusDto | null>(null);
  const [metrics, setMetrics] = useState<Ds4MetricsDto | null>(null);
  const [open, setOpen] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<{ x: number; y: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await window.bridge.getDs4Status();
      setStatus(next);
      if (next.state === 'RUNNING' || next.state === 'STARTING') {
        try {
          setMetrics(await window.bridge.getDs4Metrics());
        }
        catch {
          // Metrics failure shouldn't blank the status chip.
        }
      }
    }
    catch {
      // Silent — the chip falls back to a neutral state until the
      // next tick succeeds; an error toast every 3s would be a
      // nightmare.
    }
  }, []);

  useEffect(() => {
    if (hidden) return;
    void refresh();
    const id = window.setInterval(refresh, 3_000);
    return () => window.clearInterval(id);
  }, [hidden, refresh]);

  useEffect(() => {
    if (!open && menuAnchor === null) return;
    const onMouseDown = (ev: MouseEvent) => {
      const popover = popoverRef.current;
      const trigger = triggerRef.current;
      const target = ev.target as Node | null;
      if (target === null) return;
      if (popover !== null && popover.contains(target)) return;
      if (trigger !== null && trigger.contains(target)) return;
      setOpen(false);
      setMenuAnchor(null);
    };
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        setOpen(false);
        setMenuAnchor(null);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, menuAnchor]);

  // Local AI switched off — don't float a chip for a subsystem the
  // user deliberately turned off; the re-enable lives in Settings.
  if (hidden || status === null || status.state === 'DISABLED') {
    return null;
  }

  const palette = paletteFor(status.state);
  const tps = metrics === null ? 0 : Math.round(metrics.throughput.currentTps);
  const memPct = metrics === null ? 0 : Math.round(metrics.memory.pct * 100);

  // Collapsed payload morphs with state — the chip footprint stays
  // constant so the floating element never jumps as the lifecycle
  // walks through transitions.
  let payload: React.ReactNode;
  let inlineAction: React.ReactNode = null;
  switch (status.state) {
    case 'RUNNING':
      payload = memPct >= 90
        ? <>mem {memPct}% · {formatUptime(status.uptimeSec)}</>
        : <>{tps} t/s · {formatUptime(status.uptimeSec)}</>;
      break;
    case 'STARTING':
      payload = <>starting…</>;
      break;
    case 'STOPPING':
      payload = <>stopping…</>;
      break;
    case 'CRASHED':
      payload = <>crashed · retry {status.restartAttempts}</>;
      inlineAction = (
        <button
          type="button"
          style={inlineActionStyle}
          onClick={(e) => { e.stopPropagation(); void window.bridge.restartDs4(); }}
        >↻ Restart</button>
      );
      break;
    case 'STOPPED':
      payload = <>stopped</>;
      inlineAction = (
        <button
          type="button"
          style={inlineActionStyle}
          onClick={(e) => { e.stopPropagation(); void window.bridge.startDs4(); }}
        >▶ Start</button>
      );
      break;
    case 'NOT_CONFIGURED':
      payload = <>configure ds4</>;
      break;
    default:
      payload = <>{status.state}</>;
  }

  return (
    <div style={wrapperStyle}>
      <button
        ref={triggerRef}
        type="button"
        style={{ ...chipStyle, background: palette.bg, color: palette.fg, borderColor: palette.border }}
        onClick={() => setOpen((p) => !p)}
        onContextMenu={(e) => {
          e.preventDefault();
          setMenuAnchor({ x: e.clientX, y: e.clientY });
        }}
        aria-haspopup="dialog"
        aria-expanded={open}
        title="Local ds4 inference server"
      >
        <span style={{ ...dotStyle, background: palette.dot }} aria-hidden />
        <span style={chipGlyphStyle}>◩</span>
        <span style={chipNameStyle}>ds4</span>
        <span style={chipPayloadStyle}>· {payload}</span>
        {inlineAction}
      </button>

      {open && (
        <div ref={popoverRef} style={popoverStyle} role="dialog" aria-label="ds4 status popover">
          <header style={popoverHeaderStyle}>
            <span style={{ ...statePillStyle, background: palette.bg, color: palette.fg, borderColor: palette.border }}>
              <span style={{ ...dotStyle, background: palette.dot }} aria-hidden />
              {status.state}
            </span>
            <span style={popoverNameStyle}>ds4</span>
            <span style={popoverUptimeStyle}>{formatUptime(status.uptimeSec)}</span>
          </header>

          {metrics !== null && status.state === 'RUNNING' && (
            <>
              <div style={memoryBarRowStyle}>
                <span style={memoryBarLabelStyle}>Memory</span>
                <div style={memoryBarTrackStyle} aria-hidden>
                  <div
                    style={{
                      ...memoryBarFillStyle,
                      width: `${Math.min(100, memPct)}%`,
                      background: memPct >= 90 ? '#f59e0b' : '#22c55e',
                    }}
                  />
                </div>
                <span style={memoryBarValueStyle}>
                  {formatGb(metrics.memory.weightsBytes + metrics.memory.kvCacheBytes)} /
                  {' '}{formatGb(metrics.memory.ceilingBytes)} · {memPct}%
                </span>
              </div>
              <div style={miniTilesStyle}>
                <MiniTile label="Generation" value={`${tps} t/s`} />
                <MiniTile
                  label="Recent calls"
                  value={`${metrics.recentRequests.length} last hr`}
                />
              </div>
            </>
          )}

          <div style={popoverActionsStyle}>
            {(status.state === 'RUNNING' || status.state === 'STARTING') && (
              <>
                <button
                  type="button"
                  style={popoverBtnStyle}
                  onClick={() => { void window.bridge.stopDs4(status.spawnedByUs === false).then(() => setOpen(false)); }}
                >■ Stop</button>
                <button
                  type="button"
                  style={popoverBtnStyle}
                  onClick={() => { void window.bridge.restartDs4().then(() => setOpen(false)); }}
                >↻ Restart</button>
              </>
            )}
            {(status.state === 'STOPPED' || status.state === 'CRASHED' || status.state === 'NOT_CONFIGURED') && (
              <button
                type="button"
                style={popoverBtnStyle}
                onClick={() => { void window.bridge.startDs4().then(() => setOpen(false)); }}
                disabled={status.state === 'NOT_CONFIGURED'}
                title={status.state === 'NOT_CONFIGURED' ? 'Configure binary first' : undefined}
              >▶ Start</button>
            )}
          </div>

          <footer style={popoverFooterStyle}>
            <button
              type="button"
              style={popoverLinkBtnStyle}
              onClick={() => { setOpen(false); onOpenManagement(); }}
            >
              ⚙ Open management →
            </button>
          </footer>
        </div>
      )}

      {menuAnchor !== null && (
        <div
          ref={popoverRef}
          style={{ ...menuStyle, left: menuAnchor.x, top: menuAnchor.y }}
          role="menu"
        >
          <button type="button" role="menuitem" style={menuItemStyle}
            onClick={() => { setMenuAnchor(null); void window.bridge.stopDs4(status.spawnedByUs === false); }}>
            Stop
          </button>
          <button type="button" role="menuitem" style={menuItemStyle}
            onClick={() => { setMenuAnchor(null); void window.bridge.restartDs4(); }}>
            Restart
          </button>
          <button type="button" role="menuitem" style={menuItemStyle}
            onClick={() => { setMenuAnchor(null); onOpenManagement(); }}>
            Open management
          </button>
        </div>
      )}
    </div>
  );
}

function MiniTile({ label, value }: { label: string; value: string }) {
  return (
    <div style={miniTileStyle}>
      <span style={miniTileLabelStyle}>{label}</span>
      <span style={miniTileValueStyle}>{value}</span>
    </div>
  );
}

function paletteFor(state: Ds4StateDto) {
  switch (state) {
    case 'RUNNING':
      return { bg: 'rgba(34,197,94,0.92)', fg: '#fff', border: 'rgba(34,197,94,0.55)', dot: '#15803d' };
    case 'STARTING':
    case 'STOPPING':
      return { bg: 'rgba(245,158,11,0.92)', fg: '#fff', border: 'rgba(245,158,11,0.55)', dot: '#b45309' };
    case 'CRASHED':
      return { bg: 'rgba(239,68,68,0.92)', fg: '#fff', border: 'rgba(239,68,68,0.55)', dot: '#b91c1c' };
    case 'NOT_CONFIGURED':
    case 'STOPPED':
      return { bg: 'rgba(120,120,128,0.85)', fg: '#fff', border: 'rgba(120,120,128,0.4)', dot: 'rgba(255,255,255,0.85)' };
    default:
      return { bg: 'rgba(120,120,128,0.85)', fg: '#fff', border: 'rgba(120,120,128,0.4)', dot: '#fff' };
  }
}

function formatUptime(sec: number): string {
  if (sec <= 0) return '—';
  const days = Math.floor(sec / 86400);
  const hours = Math.floor((sec % 86400) / 3600);
  const mins = Math.floor((sec % 3600) / 60);
  if (days > 0) return `up ${days}d`;
  if (hours > 0) return `up ${hours}h`;
  return `up ${mins}m`;
}

function formatGb(bytes: number): string {
  if (bytes <= 0) return '0';
  return `${(bytes / 1_000_000_000).toFixed(0)} GB`;
}

/* ── styles ─────────────────────────────────────────────────────── */

const wrapperStyle: React.CSSProperties = {
  position: 'fixed',
  bottom: 16,
  right: 16,
  zIndex: 90,
  pointerEvents: 'auto',
};

const chipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 12px',
  borderRadius: 999,
  border: '1px solid',
  fontSize: 12,
  fontWeight: 500,
  fontFamily: 'inherit',
  cursor: 'pointer',
  backdropFilter: 'blur(8px)',
  boxShadow: '0 6px 16px rgba(0,0,0,0.18)',
  color: '#fff',
};

const dotStyle: React.CSSProperties = {
  width: 7,
  height: 7,
  borderRadius: 999,
};

const chipGlyphStyle: React.CSSProperties = {
  fontSize: 11,
};

const chipNameStyle: React.CSSProperties = {
  fontWeight: 700,
};

const chipPayloadStyle: React.CSSProperties = {
  opacity: 0.92,
};

const inlineActionStyle: React.CSSProperties = {
  marginLeft: 8,
  padding: '2px 8px',
  borderRadius: 999,
  fontSize: 10,
  fontWeight: 700,
  border: '1px solid rgba(255,255,255,0.45)',
  background: 'rgba(255,255,255,0.18)',
  color: '#fff',
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const popoverStyle: React.CSSProperties = {
  position: 'absolute',
  bottom: 'calc(100% + 8px)',
  right: 0,
  width: 312,
  padding: 14,
  background: '#fff',
  border: '1px solid var(--border)',
  borderRadius: 12,
  boxShadow: '0 16px 40px rgba(0,0,0,0.20)',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  color: 'var(--text-1)',
};

const popoverHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const popoverNameStyle: React.CSSProperties = {
  fontWeight: 700,
};

const popoverUptimeStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 11,
  color: 'var(--text-3)',
};

const statePillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '3px 9px',
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.04em',
  borderRadius: 999,
  border: '1px solid',
};

const memoryBarRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 11,
  color: 'var(--text-2)',
};

const memoryBarLabelStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--text-3)',
  width: 56,
  flexShrink: 0,
};

const memoryBarTrackStyle: React.CSSProperties = {
  flex: 1,
  height: 6,
  background: 'var(--bg-elevated)',
  borderRadius: 4,
  overflow: 'hidden',
};

const memoryBarFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 240ms ease-out',
};

const memoryBarValueStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 10,
  color: 'var(--text-3)',
};

const miniTilesStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 8,
};

const miniTileStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: 8,
  border: '1px solid var(--border)',
  borderRadius: 8,
};

const miniTileLabelStyle: React.CSSProperties = {
  fontSize: 9,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-3)',
};

const miniTileValueStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
};

const popoverActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
};

const popoverBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '6px 12px',
  fontSize: 11,
  fontWeight: 600,
  border: '1px solid var(--border)',
  background: '#fff',
  color: 'var(--text-2)',
  borderRadius: 999,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const popoverFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  paddingTop: 6,
  borderTop: '1px solid var(--border)',
};

const popoverLinkBtnStyle: React.CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  fontSize: 11,
  color: '#5b21b6',
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const menuStyle: React.CSSProperties = {
  position: 'fixed',
  background: '#fff',
  border: '1px solid var(--border)',
  borderRadius: 8,
  padding: 4,
  display: 'flex',
  flexDirection: 'column',
  zIndex: 100,
  boxShadow: '0 8px 20px rgba(0,0,0,0.18)',
};

const menuItemStyle: React.CSSProperties = {
  background: 'none',
  border: 'none',
  padding: '6px 14px',
  fontSize: 12,
  color: 'var(--text-1)',
  textAlign: 'left',
  cursor: 'pointer',
  fontFamily: 'inherit',
};
