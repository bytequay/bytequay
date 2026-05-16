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
import type { TaskDto, TaskStatusDto } from '../types';

export type StatusFilter = TaskStatusDto | 'ALL';

type Props = {
  tasks: TaskDto[];
  /** Highlights the matching row in Recent. Pass when on the detail
   *  page so the user can see which task they're inside. */
  currentTaskId?: string;
  statusFilter: StatusFilter;
  onStatusFilter: (filter: StatusFilter) => void;
  onSelectTask: (taskId: string) => void;
  onNewTask: () => void;
  onOpenSettings: () => void;
};

/** Status rows in the order the mockup lists them. */
const STATUS_ROWS: Array<{ filter: StatusFilter; label: string; dot: string }> = [
  { filter: 'ALL',       label: 'All tasks',     dot: '#cbd5e0' },
  { filter: 'RUNNING',   label: 'Running',       dot: '#047857' },
  { filter: 'AWAITING',  label: 'Awaiting input', dot: '#d97706' },
  { filter: 'IDLE',      label: 'Idle',          dot: '#9ca3af' },
  { filter: 'COMPLETED', label: 'Completed',     dot: '#047857' },
  { filter: 'ERRORED',   label: 'Errored',       dot: '#b91c4f' },
];

const PROVIDER_ROWS: Array<{ key: string; label: string; glyph: string; bg: string }> = [
  { key: 'claude-code', label: 'Claude Code', glyph: 'C',
    bg: 'linear-gradient(135deg, #d97706, #92400e)' },
  { key: 'codex',       label: 'Codex',       glyph: 'X',
    bg: 'linear-gradient(135deg, #1f2937, #4b5563)' },
];

/**
 * Persistent left rail shared by the Tasks list page and the
 * terminal-task detail page. Matches the layout in
 * {@code docs/mockups/design/tasks/tasks-list.png} and
 * {@code task-detail-terminal-light.png}.
 *
 * <p>Status rows drive the filter the list page applies; on the
 * detail page they navigate back to the list with that filter
 * pre-selected. Recent is a fast switcher for the five most-
 * recently-touched tasks.
 */
export default function TasksLeftRail({
  tasks,
  currentTaskId,
  statusFilter,
  onStatusFilter,
  onSelectTask,
  onNewTask,
  onOpenSettings,
}: Props) {
  const counts = useMemo(() => buildCounts(tasks), [tasks]);
  const providerCounts = useMemo(() => buildProviderCounts(tasks), [tasks]);
  const awaitingCount = counts.AWAITING ?? 0;
  const recent = useMemo(() => sortByUpdatedDesc(tasks).slice(0, 5), [tasks]);

  return (
    <aside style={railStyle}>
      <button type="button" onClick={onNewTask} style={newTaskBtnStyle}>
        <span style={plusStyle}>+</span>
        <span>New task</span>
        <span style={kbdHintStyle}>⌘N</span>
      </button>

      <Section>
        <SectionHeader label="Status" count={tasks.length} />
        {STATUS_ROWS.map(row => (
          <RailRow
            key={row.filter}
            active={statusFilter === row.filter}
            onClick={() => onStatusFilter(row.filter)}
          >
            <span style={{ ...dotStyle, background: row.dot }} />
            <span style={labelStyle}>{row.label}</span>
            <span style={countStyle}>
              {row.filter === 'ALL' ? tasks.length : (counts[row.filter] ?? 0)}
            </span>
            {row.filter === 'AWAITING' && awaitingCount > 0 && (
              <span style={urgentBadgeStyle}>!</span>
            )}
          </RailRow>
        ))}
      </Section>

      <Section>
        <SectionHeader label="Provider" />
        {PROVIDER_ROWS.map(row => (
          <RailRow key={row.key} disabled>
            <span style={{ ...glyphStyle, background: row.bg }}>{row.glyph}</span>
            <span style={labelStyle}>{row.label}</span>
            <span style={countStyle}>{providerCounts[row.key] ?? 0}</span>
          </RailRow>
        ))}
      </Section>

      <Section>
        <SectionHeader label="Recent" />
        {recent.length === 0 && (
          <div style={emptyHintStyle}>No tasks yet</div>
        )}
        {recent.map(t => (
          <RailRow
            key={t.id}
            active={t.id === currentTaskId}
            onClick={() => onSelectTask(t.id)}
          >
            <span style={{ ...recentDotStyle, background: statusDot(t.status) }} />
            <span style={recentTitleStyle}>{t.title}</span>
            <span style={recentTimeStyle}>{ageOf(t.updatedAt)}</span>
          </RailRow>
        ))}
      </Section>

      <div style={spacerStyle} />

      <Section>
        <RailRow onClick={onOpenSettings}>
          <span style={cogStyle}>⚙</span>
          <span style={labelStyle}>Defaults &amp; integrations</span>
        </RailRow>
      </Section>
    </aside>
  );
}

function Section({ children }: { children: React.ReactNode }) {
  return <div style={sectionStyle}>{children}</div>;
}

function SectionHeader({ label, count }: { label: string; count?: number }) {
  return (
    <div style={sectionHeaderStyle}>
      <span>{label}</span>
      {count != null && <span style={sectionCountStyle}>{count}</span>}
    </div>
  );
}

function RailRow({
  active,
  disabled,
  onClick,
  children,
}: {
  active?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  children: React.ReactNode;
}) {
  return (
    <div
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onClick={disabled ? undefined : onClick}
      onKeyDown={e => { if (onClick && !disabled && e.key === 'Enter') onClick(); }}
      style={{
        ...rowStyle,
        ...(active ? rowActiveStyle : null),
        ...(disabled ? rowDisabledStyle : null),
        cursor: !onClick || disabled ? 'default' : 'pointer',
      }}
    >
      {active && <span style={rowActiveBarStyle} />}
      {children}
    </div>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

function buildCounts(tasks: TaskDto[]): Partial<Record<TaskStatusDto, number>> {
  const out: Partial<Record<TaskStatusDto, number>> = {};
  for (const t of tasks) {
    out[t.status] = (out[t.status] ?? 0) + 1;
  }
  return out;
}

function buildProviderCounts(tasks: TaskDto[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const t of tasks) {
    const k = (t.provider || '').toLowerCase();
    out[k] = (out[k] ?? 0) + 1;
  }
  return out;
}

function sortByUpdatedDesc(tasks: TaskDto[]): TaskDto[] {
  return [...tasks].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

function statusDot(s: TaskStatusDto): string {
  switch (s) {
    case 'RUNNING':   return '#047857';
    case 'AWAITING':  return '#d97706';
    case 'IDLE':      return '#9ca3af';
    case 'PENDING':   return '#9ca3af';
    case 'COMPLETED': return '#047857';
    case 'ERRORED':   return '#b91c4f';
  }
}

function ageOf(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.round(h / 24)}d`;
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const railStyle: React.CSSProperties = {
  width: 232,
  flexShrink: 0,
  padding: '14px 12px 12px',
  background: '#f7f8fa',
  borderRight: '1px solid #e5e7eb',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  alignSelf: 'stretch',
};

const newTaskBtnStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 12px',
  background: '#7c3aed',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontSize: 13,
  fontWeight: 600,
  cursor: 'pointer',
  width: '100%',
};
const plusStyle: React.CSSProperties = { fontSize: 16, lineHeight: 1 };
const kbdHintStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 10,
  background: 'rgba(255,255,255,0.18)',
  padding: '1px 6px',
  borderRadius: 3,
  fontFamily: '"SF Mono", Menlo, monospace',
  fontWeight: 500,
};

const sectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};
const sectionHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  padding: '0 8px 4px',
  fontSize: 10.5,
  fontWeight: 700,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: '#6b7280',
};
const sectionCountStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 11,
  color: '#9ca3af',
  fontWeight: 500,
  textTransform: 'none',
  letterSpacing: 0,
};

const rowStyle: React.CSSProperties = {
  position: 'relative',
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '5px 8px',
  borderRadius: 4,
  color: '#374151',
  fontSize: 13,
  lineHeight: 1.3,
  userSelect: 'none',
};
const rowActiveStyle: React.CSSProperties = {
  background: '#ede9fe',
  color: '#5b21b6',
  fontWeight: 600,
};
const rowDisabledStyle: React.CSSProperties = { opacity: 0.8 };
const rowActiveBarStyle: React.CSSProperties = {
  position: 'absolute',
  left: 0,
  top: 6,
  bottom: 6,
  width: 3,
  borderRadius: 2,
  background: '#7c3aed',
};
const dotStyle: React.CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: '50%',
  flexShrink: 0,
};
const labelStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const countStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#6b7280',
  fontVariantNumeric: 'tabular-nums',
};
const urgentBadgeStyle: React.CSSProperties = {
  marginLeft: 4,
  background: '#dc2626',
  color: '#fff',
  borderRadius: 10,
  padding: '0 6px',
  fontSize: 10,
  fontWeight: 700,
  lineHeight: '14px',
};
const glyphStyle: React.CSSProperties = {
  width: 18,
  height: 18,
  borderRadius: 4,
  color: '#fff',
  fontSize: 10,
  fontWeight: 700,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
};
const recentDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: '50%',
  flexShrink: 0,
};
const recentTitleStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 12.5,
};
const recentTimeStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: '#9ca3af',
  fontVariantNumeric: 'tabular-nums',
};
const cogStyle: React.CSSProperties = {
  width: 8,
  textAlign: 'center',
  fontSize: 13,
  color: '#6b7280',
};
const spacerStyle: React.CSSProperties = { flex: 1, minHeight: 8 };
const emptyHintStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 12,
  color: '#9ca3af',
  fontStyle: 'italic',
};
