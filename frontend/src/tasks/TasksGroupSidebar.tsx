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
import type { TaskDto, TaskGroupDto } from '../types';

/**
 * Compact left rail for the tasks-group page. Replaces the
 * full {@code TasksLeftRail} (status sections, provider, recent,
 * settings) with a focused 200px column showing the group's
 * identity, three at-a-glance status counts (RUN / WAIT / IDLE),
 * and aggregated vitals across the tasks in the group.
 *
 * <p>Follows {@code docs/mockups/design/tasks/tasks-group.png} —
 * the "+ Add task" CTA and the bottom row of three icon buttons
 * (immersive toggle, group settings, refresh) round out the rail.
 */
export type TasksGroupSidebarProps = {
  group: TaskGroupDto;
  /** Tasks that belong to this group. The sidebar derives all
   *  status counts and aggregated metrics from this list — the
   *  page already filters out other groups before passing in. */
  tasks: TaskDto[];
  /** Whether the group can accept another task (server-side cap is
   *  4). Drives the disabled state of the + Add task button. */
  canAddTask: boolean;
  onAddTask: () => void;
  onOpenGroupSettings: () => void;
  onRefresh: () => void;
  onToggleImmersive: () => void;
  immersive: boolean;
  /** Back to the full task list (clears the group filter). Surfaced
   *  as a small breadcrumb above the group identity row so the user
   *  always has an in-rail exit from the group view. */
  onBackToAll: () => void;
};

export default function TasksGroupSidebar({
  group, tasks, canAddTask,
  onAddTask, onOpenGroupSettings, onRefresh,
  onToggleImmersive, immersive, onBackToAll,
}: TasksGroupSidebarProps) {
  const counts = useMemo(() => deriveStatusCounts(tasks), [tasks]);
  const vitals = useMemo(() => deriveAggregates(tasks), [tasks]);
  return (
    <aside style={sidebarStyle}>
      <button
        type="button"
        onClick={onBackToAll}
        style={backLinkStyle}
        title="Back to all tasks"
      >
        ← All tasks
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
            {tasks.length} {tasks.length === 1 ? 'task' : 'tasks'}
          </span>
        </div>
        <div style={vitalsStyle}>
          <VitalsRow label="Runtime"       value={vitals.runtime} live={vitals.anyLive} />
          <VitalsRow label="Cost"          value={vitals.cost} />
          <VitalsRow label="Tokens"        value={`${vitals.tokensIn} → ${vitals.tokensOut}`} />
          <VitalsRow label="Burn rate"     value={vitals.burnRate} />
        </div>
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
          ? 'Start a new task pinned to this group (⌘N)'
          : 'Group is full (4 tasks). Remove one before adding another.'}
      >
        + Add task
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

function deriveStatusCounts(tasks: TaskDto[]): StatusCounts {
  let running = 0;
  let awaiting = 0;
  let idle = 0;
  for (const t of tasks) {
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

function deriveAggregates(tasks: TaskDto[]): Aggregates {
  let runtimeMs = 0;
  let costMilli = 0;
  let tokensIn = 0;
  let tokensOut = 0;
  let anyLive = false;
  const now = Date.now();
  for (const t of tasks) {
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
    tokensIn:  formatTokens(tokensIn),
    tokensOut: formatTokens(tokensOut),
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
function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
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
