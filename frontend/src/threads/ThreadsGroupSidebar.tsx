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
import { useMemo } from 'react';
import type { ThreadDto, ThreadGroupDto } from '../types';
import { threadCompactNumber } from './threadDisplay';

/**
 * Compact left rail for the threads-group page. Replaces the
 * full {@code ThreadsLeftRail} (status sections, provider, recent,
 * settings) with a focused 200px column showing the group's
 * identity, three at-a-glance status counts (RUN / WAIT / IDLE),
 * and aggregated vitals across the threads in the group.
 *
 * <p>Follows {@code docs/mockups/design/threads/threads-group.png} —
 * the "+ Add thread" CTA and the bottom row of three icon buttons
 * (immersive toggle, group settings, refresh) round out the rail.
 */
export type ThreadsGroupSidebarProps = {
  /** Currently-selected tile id, so the rail's matching nav row can
   *  highlight. Null when no tile is selected. */
  selectedThreadId?: string | null;
  /** Select a tile from the rail. Mirrors what clicking the tile in
   *  the grid does (focuses its input, ready for type-to-reply). */
  onSelectThread?: (threadId: string) => void;
  /** Zoom into a thread from the rail (double-click style). Opens
   *  the modal embedding the trunk / task-detail. */
  onZoomThread?: (threadId: string) => void;
  group: ThreadGroupDto;
  /** Threads that belong to this group. The sidebar derives all
   *  status counts and aggregated metrics from this list — the
   *  page already filters out other groups before passing in. */
  threads: ThreadDto[];
  /** Whether the group can accept another thread (server-side cap is
   *  4). Drives the disabled state of the + Add thread button. */
  canAddTask: boolean;
  onAddTask: () => void;
  onOpenGroupSettings: () => void;
  onRefresh: () => void;
  onToggleImmersive: () => void;
  immersive: boolean;
  /** Back to the full thread list (clears the group filter). Surfaced
   *  as a small breadcrumb above the group identity row so the user
   *  always has an in-rail exit from the group view. */
  onBackToAll: () => void;
};

export default function ThreadsGroupSidebar({
  group, threads, canAddTask,
  onAddTask, onOpenGroupSettings, onRefresh,
  onToggleImmersive, immersive, onBackToAll,
  selectedThreadId = null, onSelectThread, onZoomThread,
}: ThreadsGroupSidebarProps) {
  const counts = useMemo(() => deriveStatusCounts(threads), [threads]);
  const vitals = useMemo(() => deriveAggregates(threads), [threads]);
  return (
    <aside style={sidebarStyle}>
      <button
        type="button"
        onClick={onBackToAll}
        style={backLinkStyle}
        title="Back to all threads"
      >
        ← All threads
      </button>
      <div style={identityRowStyle}>
        <span style={{ ...identityIconStyle, background: groupColorBg(group.color) }}>
          {group.glyph || '•'}
        </span>
        <span style={identityNameStyle} title={group.name}>{group.name}</span>
      </div>

      <div style={statusRowStyle}>
        <StatusCell label="Run"  value={counts.running}  tone="run" />
        <StatusCell label="Wait" value={counts.awaiting} tone="wait" />
        <StatusCell label="Idle" value={counts.idle}     tone="idle" />
      </div>

      <div>
        <div style={sectionHeaderStyle}>
          <span>Aggregated</span>
          <span style={sectionHeaderRightStyle}>
            {threads.length} {threads.length === 1 ? 'thread' : 'threads'}
          </span>
        </div>
        <div style={vitalsStyle}>
          <VitalsRow label="Runtime"       value={vitals.runtime} live={vitals.anyLive} />
          <VitalsRow label="Cost"          value={vitals.cost} />
          <VitalsRow label="Tokens"        value={`${vitals.tokensIn} → ${vitals.tokensOut}`} />
          <VitalsRow label="Burn rate"     value={vitals.burnRate} />
        </div>
      </div>

      <div>
        <div style={sectionHeaderStyle}>
          <span>In this group</span>
          <span style={sectionHeaderRightStyle}>
            {threads.length}/4
          </span>
        </div>
        <ul style={threadNavListStyle}>
          {threads.length === 0 && (
            <li style={threadNavEmptyStyle}>
              No threads pinned yet. + Add thread below.
            </li>
          )}
          {threads.map((t, idx) => (
            <li key={t.id}>
              <button
                type="button"
                onClick={() => onSelectThread?.(t.id)}
                onDoubleClick={() => onZoomThread?.(t.id)}
                style={threadNavRowStyle(t.id === selectedThreadId)}
                title={`${t.title}${t.activeTask?.branchName != null
                    ? ` · ${t.activeTask.branchName}` : ''}`}
              >
                <span
                  style={threadNavDotStyle(t.status)}
                  aria-label={t.status.toLowerCase()}
                  aria-hidden
                />
                <span style={threadNavSlotStyle}>{idx + 1}</span>
                <span style={threadNavTitleStyle}>{t.title}</span>
                <span style={threadNavStatusStyle(t.status)}>
                  {humanizeNavStatus(t.status)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>

      <div style={{ flex: 1 }} />

      <button
        type="button"
        onClick={onAddTask}
        disabled={!canAddTask}
        style={{
          ...addBtnStyle,
          opacity: canAddTask ? 1 : 0.5,
          cursor: canAddTask ? 'pointer' : 'not-allowed',
        }}
        title={canAddTask
          ? 'Start a new thread pinned to this group (⌘N)'
          : 'Group is full (4 threads). Remove one before adding another.'}
      >
        + Add thread
      </button>

      <div style={toolsRowStyle}>
        <ToolBtn
          label={immersive ? '⛶ Exit' : '⛶'}
          active={immersive}
          title={immersive ? 'Exit immersive mode (Esc)' : 'Immersive mode — hide topnav + rail'}
          onClick={onToggleImmersive}
        />
        <ToolBtn label="⚙" title="Group settings" onClick={onOpenGroupSettings} />
        <ToolBtn label="↻" title="Refresh group" onClick={onRefresh} />
      </div>
    </aside>
  );
}

function StatusCell({ label, value, tone }: {
  label: string;
  value: number;
  tone: 'run' | 'wait' | 'idle';
}) {
  const palette = STATUS_TONES[tone];
  return (
    <div style={{
      ...statusCellStyle,
      background: palette.bg,
      borderColor: palette.border,
    }}>
      <span style={{ ...statusCellNumStyle, color: palette.fg }}>{value}</span>
      <span style={statusCellLabelStyle}>{label}</span>
    </div>
  );
}

function humanizeNavStatus(status: string): string {
  if (status === 'AWAITING_REVIEW') return 'awaiting';
  if (status === 'NEEDS_ATTENTION') return 'needs you';
  if (status === 'RUNNING') return 'running';
  if (status === 'IDLE') return 'idle';
  if (status === 'COMPLETED') return 'done';
  if (status === 'ERRORED') return 'errored';
  return status.toLowerCase();
}

function VitalsRow({ label, value, live }: { label: string; value: string; live?: boolean }) {
  return (
    <div style={vitalsRowStyle}>
      <span style={vitalsLabelStyle}>{label}</span>
      <span style={{ ...vitalsValueStyle, color: live ? '#047857' : 'var(--text-1)' }}>
        {value}
      </span>
    </div>
  );
}

function ToolBtn({ label, title, onClick, active }: {
  label: string;
  title: string;
  onClick: () => void;
  active?: boolean;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      style={{
        ...toolBtnStyle,
        ...(active ? toolBtnActiveStyle : null),
      }}
    >
      {label}
    </button>
  );
}

// ─── Aggregations ───────────────────────────────────────────────────

type StatusCounts = { running: number; awaiting: number; idle: number };

function deriveStatusCounts(threads: ThreadDto[]): StatusCounts {
  let running = 0;
  let awaiting = 0;
  let idle = 0;
  for (const t of threads) {
    if (t.status === 'RUNNING') running++;
    else if (t.status === 'AWAITING') awaiting++;
    // Lump PENDING and IDLE under "Idle" — both are non-active, and
    // the mockup only surfaces three buckets in the compact rail.
    else if (t.status === 'IDLE' || t.status === 'PENDING') idle++;
    // COMPLETED / ERRORED are deliberately not counted here.
  }
  return { running, awaiting, idle };
}

type Aggregates = {
  runtime: string;
  cost: string;
  tokensIn: string;
  tokensOut: string;
  burnRate: string;
  anyLive: boolean;
};

function deriveAggregates(threads: ThreadDto[]): Aggregates {
  let runtimeMs = 0;
  let costMilli = 0;
  let tokensIn = 0;
  let tokensOut = 0;
  let anyLive = false;
  const now = Date.now();
  for (const t of threads) {
    const start = Date.parse(t.createdAt);
    const end = t.endedAt != null ? Date.parse(t.endedAt) : now;
    if (Number.isFinite(start) && Number.isFinite(end)) {
      runtimeMs += Math.max(0, end - start);
    }
    costMilli += t.costUsdMilli;
    tokensIn  += t.tokensIn;
    tokensOut += t.tokensOut;
    if (t.status === 'RUNNING' || t.status === 'AWAITING') anyLive = true;
  }
  return {
    runtime: formatDuration(runtimeMs),
    cost: formatCost(costMilli),
    tokensIn:  threadCompactNumber(tokensIn),
    tokensOut: threadCompactNumber(tokensOut),
    burnRate: formatBurnRate(costMilli, runtimeMs),
    anyLive,
  };
}

function formatDuration(ms: number): string {
  if (ms <= 0) return '0s';
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}
function formatCost(milli: number): string {
  const dollars = milli / 1000;
  if (dollars >= 100) return `$${dollars.toFixed(0)}`;
  if (dollars >= 10)  return `$${dollars.toFixed(1)}`;
  return `$${dollars.toFixed(2)}`;
}
function formatBurnRate(costMilli: number, runtimeMs: number): string {
  if (runtimeMs <= 0) return '—';
  const dollarsPerMin = (costMilli / 1000) / (runtimeMs / 60_000);
  if (dollarsPerMin >= 10) return `$${dollarsPerMin.toFixed(0)}/min`;
  if (dollarsPerMin >= 1)  return `$${dollarsPerMin.toFixed(1)}/min`;
  return `$${dollarsPerMin.toFixed(2)}/min`;
}

function groupColorBg(color: string): string {
  switch ((color || '').toLowerCase()) {
    case 'violet': return 'linear-gradient(135deg, #7c3aed, #4c1d95)';
    case 'amber':  return 'linear-gradient(135deg, #d97706, #92400e)';
    case 'green':  return 'linear-gradient(135deg, #10b981, #047857)';
    case 'blue':   return 'linear-gradient(135deg, #2563eb, #1e3a8a)';
    case 'rose':   return 'linear-gradient(135deg, #e11d48, #9f1239)';
    default:       return 'linear-gradient(135deg, #64748b, #334155)';
  }
}

// ─── Styles ─────────────────────────────────────────────────────────

const sidebarStyle: React.CSSProperties = {
  width: 200,
  flexShrink: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: '12px 10px',
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
  maxHeight: 'calc(100vh - 56px)',
  overflowY: 'auto',
  scrollbarWidth: 'thin',
};

const backLinkStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  alignSelf: 'flex-start',
  padding: '2px 4px',
  background: 'transparent',
  border: 'none',
  color: 'var(--text-3)',
  fontSize: 11,
  fontWeight: 500,
  cursor: 'pointer',
  letterSpacing: '-0.005em',
};
const identityRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '2px 4px 6px',
  borderBottom: '1px solid var(--border-hairline)',
};
const identityIconStyle: React.CSSProperties = {
  width: 22, height: 22,
  borderRadius: 5,
  color: '#fff',
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  fontSize: 11, fontWeight: 700,
  flexShrink: 0,
};
const identityNameStyle: React.CSSProperties = {
  fontSize: 12.5, fontWeight: 700,
  color: 'var(--text-1)',
  letterSpacing: '-0.01em',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};

const statusRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr 1fr',
  gap: 4,
};
const statusCellStyle: React.CSSProperties = {
  textAlign: 'center',
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  padding: '6px 0 5px',
  lineHeight: 1,
  background: 'var(--bg-card)',
};
const statusCellNumStyle: React.CSSProperties = {
  fontSize: 16, fontWeight: 700,
  display: 'block',
  fontVariantNumeric: 'tabular-nums',
};
const statusCellLabelStyle: React.CSSProperties = {
  fontSize: 9,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  fontWeight: 600,
  marginTop: 3,
  display: 'block',
};

const STATUS_TONES = {
  run:  { fg: '#047857', bg: 'rgba(4,120,87,0.06)',  border: 'rgba(4,120,87,0.18)' },
  wait: { fg: '#b45309', bg: 'rgba(217,119,6,0.06)', border: 'rgba(217,119,6,0.20)' },
  idle: { fg: 'var(--text-2)', bg: 'var(--bg-card)', border: 'var(--border-hairline)' },
};

const sectionHeaderStyle: React.CSSProperties = {
  fontSize: 9, fontWeight: 700,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  padding: '4px 4px 2px',
  display: 'flex', alignItems: 'baseline',
};
const sectionHeaderRightStyle: React.CSSProperties = {
  marginLeft: 'auto',
  color: 'var(--text-3)',
  fontWeight: 500,
  letterSpacing: 0,
  textTransform: 'none',
  fontSize: 10,
};
const vitalsStyle: React.CSSProperties = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  padding: '2px 10px',
};
const threadNavListStyle: React.CSSProperties = {
  margin: '6px 0 0',
  padding: 0,
  listStyle: 'none',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const threadNavEmptyStyle: React.CSSProperties = {
  padding: '8px 10px',
  fontSize: 11,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};

function threadNavRowStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    padding: '6px 8px',
    border: '1px solid ' + (active ? 'rgba(124,58,237,0.40)' : 'transparent'),
    background: active
        ? 'rgba(124, 58, 237, 0.10)'
        : 'transparent',
    borderRadius: 6,
    fontSize: 12,
    color: active ? '#6d28d9' : 'var(--text-1)',
    cursor: 'pointer',
    textAlign: 'left',
    fontFamily: 'inherit',
    fontWeight: active ? 600 : 500,
  };
}

function threadNavDotStyle(status: string): React.CSSProperties {
  let bg = '#94a3b8';
  if (status === 'RUNNING') bg = '#22c55e';
  else if (status === 'AWAITING_REVIEW') bg = '#d97706';
  else if (status === 'NEEDS_ATTENTION') bg = '#dc2626';
  else if (status === 'COMPLETED') bg = '#0ea5e9';
  else if (status === 'ERRORED') bg = '#dc2626';
  return {
    width: 6,
    height: 6,
    borderRadius: 999,
    background: bg,
    flexShrink: 0,
  };
}

const threadNavSlotStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 600,
  color: 'var(--text-4)',
  fontVariantNumeric: 'tabular-nums',
  width: 12,
  flexShrink: 0,
};

const threadNavTitleStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

function threadNavStatusStyle(status: string): React.CSSProperties {
  let color = 'var(--text-4)';
  if (status === 'RUNNING') color = '#15803d';
  else if (status === 'AWAITING_REVIEW') color = '#9a3412';
  else if (status === 'NEEDS_ATTENTION') color = '#991b1b';
  return {
    fontSize: 9,
    color,
    fontWeight: 600,
    letterSpacing: '0.02em',
    flexShrink: 0,
  };
}

const vitalsRowStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'baseline',
  padding: '5px 0',
  borderBottom: '1px dashed var(--border-hairline)',
  fontSize: 11.5,
};
const vitalsLabelStyle: React.CSSProperties = {
  color: 'var(--text-3)',
  fontSize: 10.5,
};
const vitalsValueStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11,
};

const addBtnStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
  padding: '7px 10px',
  background: 'var(--accent)',
  border: '1px solid var(--accent)',
  color: '#fff',
  borderRadius: 6,
  fontSize: 12, fontWeight: 600,
  width: '100%',
  boxShadow: '0 1px 2px rgba(124,92,255,0.18)',
};
const toolsRowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr 1fr',
  gap: 4,
};
const toolBtnStyle: React.CSSProperties = {
  padding: '5px 6px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  fontSize: 11,
  color: 'var(--text-2)',
  cursor: 'pointer',
  textAlign: 'center',
  lineHeight: 1.2,
};
const toolBtnActiveStyle: React.CSSProperties = {
  color: 'var(--accent)',
  borderColor: 'var(--accent)',
  background: 'var(--accent-a10)',
  fontWeight: 600,
};
