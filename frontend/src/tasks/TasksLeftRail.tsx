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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { TaskDto, TaskGroupDto, TaskStatusDto } from '../types';
import NewTaskGroupDialog from './NewTaskGroupDialog';

export type StatusFilter = TaskStatusDto | 'ALL';
/** {@code null} = no provider filter active; otherwise the
 *  lowercased provider key (e.g. {@code "claude-code"}). */
export type ProviderFilter = string | null;
/** {@code null} = no group filter; otherwise the group id. */
export type GroupFilter = string | null;
/** {@code null} = no repo filter; otherwise the canonical repo
 *  key derived from {@code workingDir} (last path segment, lowercased). */
export type RepoFilter = string | null;

type Props = {
  tasks: TaskDto[];
  /** Indexed memberships from the page-level fetch. The rail counts
   *  per group and pre-selects the just-created group when the user
   *  finishes the create-group dialog. Defaults to an empty map so
   *  callers can omit it without breaking. */
  groupIdsByTaskId?: Map<string, string[]>;
  /** Highlights the matching row in Recent. Pass when on the detail
   *  page so the user can see which task they're inside. */
  currentTaskId?: string;
  statusFilter: StatusFilter;
  onStatusFilter: (filter: StatusFilter) => void;
  providerFilter: ProviderFilter;
  onProviderFilter: (provider: ProviderFilter) => void;
  groupFilter: GroupFilter;
  onGroupFilter: (group: GroupFilter) => void;
  repoFilter: RepoFilter;
  onRepoFilter: (repo: RepoFilter) => void;
  onSelectTask: (taskId: string) => void;
  onNewTask: () => void;
  onOpenSettings: () => void;
};

type ProviderMeta = {
  key: string;
  label: string;
  glyph: string;
  bg: string;
};

/** Known providers get a designed label + glyph; anything else falls
 *  back to a derived label and a neutral slate glyph. */
function providerMeta(rawKey: string): ProviderMeta {
  const key = rawKey.toLowerCase();
  if (key === 'claude-code' || key.startsWith('claude')) {
    return { key, label: 'Claude Code', glyph: 'C',
      bg: 'linear-gradient(135deg, #d97706, #92400e)' };
  }
  if (key === 'codex' || key.startsWith('codex')) {
    return { key, label: 'Codex', glyph: 'X',
      bg: 'linear-gradient(135deg, #1f2937, #4b5563)' };
  }
  if (key.startsWith('deepseek')) {
    return { key, label: 'DeepSeek', glyph: 'D',
      bg: 'linear-gradient(135deg, #2563eb, #1e3a8a)' };
  }
  if (key === 'openai' || key.startsWith('gpt')) {
    return { key, label: 'OpenAI', glyph: 'G',
      bg: 'linear-gradient(135deg, #10b981, #047857)' };
  }
  if (key.startsWith('anthropic')) {
    return { key, label: 'Anthropic', glyph: 'A',
      bg: 'linear-gradient(135deg, #d97706, #92400e)' };
  }
  // Generic fallback — first char + title-case the key
  const glyph = (key.charAt(0) || '?').toUpperCase();
  const label = key
    .split(/[-_\s]/)
    .filter(Boolean)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ') || 'Unknown';
  return { key, label, glyph,
    bg: 'linear-gradient(135deg, #64748b, #334155)' };
}

/** Status rows in the order the mockup lists them. */
const STATUS_ROWS: Array<{ filter: StatusFilter; label: string; dot: string }> = [
  { filter: 'ALL',       label: 'All tasks',     dot: '#cbd5e0' },
  { filter: 'RUNNING',   label: 'Running',       dot: '#047857' },
  { filter: 'IDLE',      label: 'Idle',          dot: '#d97706' },
  { filter: 'COMPLETED', label: 'Completed',     dot: '#9ca3af' },
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
  groupIdsByTaskId,
  currentTaskId,
  statusFilter,
  onStatusFilter,
  providerFilter,
  onProviderFilter,
  groupFilter,
  onGroupFilter,
  repoFilter,
  onRepoFilter,
  onSelectTask,
  onNewTask,
  onOpenSettings,
}: Props) {
  const counts = useMemo(() => buildCounts(tasks), [tasks]);
  const providers = useMemo(() => buildProviderList(tasks), [tasks]);
  const repos = useMemo(() => buildRepoList(tasks), [tasks]);
  const recent = useMemo(() => sortByUpdatedDesc(tasks).slice(0, 5), [tasks]);

  const [groups, setGroups] = useState<TaskGroupDto[]>([]);
  const [showCreateGroup, setShowCreateGroup] = useState(false);

  const refreshGroups = useCallback(async () => {
    try {
      setGroups(await window.bridge.listTaskGroups());
    }
    catch {
      // The rail still renders without groups — non-fatal.
    }
  }, []);
  useEffect(() => { void refreshGroups(); }, [refreshGroups]);

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
          </RailRow>
        ))}
      </Section>

      {providers.length > 0 && (
        <Section>
          <SectionHeader label="Provider" />
          {providers.map(p => (
            <RailRow
              key={p.meta.key}
              active={providerFilter === p.meta.key}
              onClick={() =>
                onProviderFilter(providerFilter === p.meta.key ? null : p.meta.key)}
            >
              <span style={{ ...glyphStyle, background: p.meta.bg }}>{p.meta.glyph}</span>
              <span style={labelStyle}>{p.meta.label}</span>
              <span style={countStyle}>{p.count}</span>
            </RailRow>
          ))}
        </Section>
      )}

      {repos.length > 0 && (
        <Section>
          <SectionHeader label="Repo" />
          {repos.map(r => (
            <RailRow
              key={r.key}
              active={repoFilter === r.key}
              onClick={() => onRepoFilter(repoFilter === r.key ? null : r.key)}
            >
              <span style={{ ...glyphStyle, background: repoGlyphBg(r.key) }}>
                {r.glyph}
              </span>
              <span style={labelStyle} title={r.fullPath}>{r.label}</span>
              <span style={countStyle}>{r.count}</span>
            </RailRow>
          ))}
        </Section>
      )}

      <Section>
        <SectionHeader
          label="Groups"
          count={groups.length}
          action={
            <button
              type="button"
              onClick={() => setShowCreateGroup(true)}
              style={addBtnStyle}
              title="New group"
            >
              + new
            </button>
          }
        />
        {groups.length === 0 && (
          <div style={emptyHintStyle}>No groups yet</div>
        )}
        {groups.map(g => (
          <RailRow
            key={g.id}
            active={groupFilter === g.id}
            onClick={() => onGroupFilter(groupFilter === g.id ? null : g.id)}
          >
            <span style={{ ...glyphStyle, background: groupColorBg(g.color) }}>
              {g.glyph || '•'}
            </span>
            <span style={labelStyle}>{g.name}</span>
            <span style={countStyle}>{countTasksInGroup(tasks, g.id, groupIdsByTaskId)}</span>
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

      {showCreateGroup && (
        <NewTaskGroupDialog
          onClose={() => setShowCreateGroup(false)}
          availableTasks={tasks}
          onCreated={group => {
            setShowCreateGroup(false);
            setGroups(prev => [...prev, group]);
            onGroupFilter(group.id);
          }}
        />
      )}
    </aside>
  );
}

function Section({ children }: { children: React.ReactNode }) {
  return <div style={sectionStyle}>{children}</div>;
}

function SectionHeader({ label, count, action }: {
  label: string;
  count?: number;
  action?: React.ReactNode;
}) {
  return (
    <div style={sectionHeaderStyle}>
      <span>{label}</span>
      {count != null && <span style={sectionCountStyle}>{count}</span>}
      {action && <span style={{ marginLeft: 'auto' }}>{action}</span>}
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

/** Distinct providers across the task list, each with its metadata
 *  and a count. Sorted by descending count so the most-used provider
 *  surfaces first; empty providers are skipped. */
function buildProviderList(tasks: TaskDto[]): Array<{ meta: ProviderMeta; count: number }> {
  const counts = new Map<string, number>();
  for (const t of tasks) {
    const k = (t.provider || '').toLowerCase();
    if (!k) continue;
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([key, count]) => ({ meta: providerMeta(key), count }));
}

/** Canonical id for a task's repo — the last meaningful segment of
 *  the working directory, lowercased. Trailing slashes are tolerated
 *  so {@code /tmp/foo/} and {@code /tmp/foo} fold together. */
export function repoKey(workingDir: string | null | undefined): string {
  if (!workingDir) return '';
  const trimmed = workingDir.replace(/\/+$/, '');
  const idx = trimmed.lastIndexOf('/');
  const last = idx < 0 ? trimmed : trimmed.slice(idx + 1);
  return last.toLowerCase();
}

type RepoMeta = {
  /** Canonical key — what {@link RepoFilter} stores. */
  key: string;
  /** Cased display label (original last-segment). */
  label: string;
  /** Single-letter avatar — uppercased first non-symbol char. */
  glyph: string;
  /** Full working directory, for tooltips. */
  fullPath: string;
  count: number;
};

/** Distinct repos across the task list, sorted by descending count
 *  so the most-worked repo surfaces first. Empty / unknown working
 *  directories are skipped. */
function buildRepoList(tasks: TaskDto[]): RepoMeta[] {
  const acc = new Map<string, { label: string; fullPath: string; count: number }>();
  for (const t of tasks) {
    const k = repoKey(t.workingDir);
    if (!k) continue;
    const trimmed = (t.workingDir ?? '').replace(/\/+$/, '');
    const idx = trimmed.lastIndexOf('/');
    const display = idx < 0 ? trimmed : trimmed.slice(idx + 1);
    const existing = acc.get(k);
    if (existing) {
      existing.count++;
    }
    else {
      acc.set(k, { label: display, fullPath: trimmed, count: 1 });
    }
  }
  return Array.from(acc.entries())
    .sort((a, b) => b[1].count - a[1].count)
    .map(([key, v]) => ({
      key,
      label: v.label,
      glyph: (v.label.match(/[A-Za-z0-9]/)?.[0] ?? '?').toUpperCase(),
      fullPath: v.fullPath,
      count: v.count,
    }));
}

/** Deterministic colour swatch for a repo glyph — picked from a
 *  curated palette of the project's accent tones so distinct repos
 *  read as distinct without being garish. */
function repoGlyphBg(key: string): string {
  const palette = [
    'linear-gradient(135deg, #0ea5e9, #075985)',
    'linear-gradient(135deg, #14b8a6, #0f766e)',
    'linear-gradient(135deg, #6366f1, #3730a3)',
    'linear-gradient(135deg, #f97316, #c2410c)',
    'linear-gradient(135deg, #ec4899, #9d174d)',
    'linear-gradient(135deg, #84cc16, #4d7c0f)',
  ];
  // Cheap stable hash so the same repo key always picks the same
  // colour; no need for cryptographic strength.
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = ((h << 5) - h + key.charCodeAt(i)) | 0;
  }
  return palette[Math.abs(h) % palette.length];
}

function sortByUpdatedDesc(tasks: TaskDto[]): TaskDto[] {
  return [...tasks].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

function countTasksInGroup(
    tasks: TaskDto[],
    groupId: string,
    groupIdsByTaskId: Map<string, string[]> | undefined): number {
  if (groupIdsByTaskId === undefined) {
    return 0;
  }
  let n = 0;
  for (const t of tasks) {
    if ((groupIdsByTaskId.get(t.id) ?? []).includes(groupId)) n++;
  }
  return n;
}

/** Maps the small named-swatch set the create dialog uses to a CSS
 *  gradient. Unknown values fall back to {@code slate} so a future
 *  free-form color string still renders. */
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

function statusDot(s: TaskStatusDto): string {
  switch (s) {
    case 'RUNNING':   return '#10b981';
    case 'AWAITING':  return '#d97706';
    case 'IDLE':      return '#eab308';
    case 'PENDING':   return '#9ca3af';
    case 'COMPLETED': return '#9ca3af';
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
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
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
  background: 'var(--accent)',
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
  color: 'var(--text-3)',
};
const sectionCountStyle: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 11,
  color: 'var(--text-4)',
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
  color: 'var(--text-2)',
  fontSize: 13,
  lineHeight: 1.3,
  userSelect: 'none',
};
const rowActiveStyle: React.CSSProperties = {
  background: 'var(--accent-a10)',
  color: 'var(--accent-dark)',
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
  background: 'var(--accent)',
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
  color: 'var(--text-3)',
  fontVariantNumeric: 'tabular-nums',
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
  color: 'var(--text-4)',
  fontVariantNumeric: 'tabular-nums',
};
const cogStyle: React.CSSProperties = {
  width: 8,
  textAlign: 'center',
  fontSize: 13,
  color: 'var(--text-3)',
};
const spacerStyle: React.CSSProperties = { flex: 1, minHeight: 8 };
const emptyHintStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 12,
  color: 'var(--text-4)',
  fontStyle: 'italic',
};
const addBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: '1px solid var(--border-input)',
  borderRadius: 4,
  padding: '0 6px',
  fontSize: 10,
  fontWeight: 600,
  color: 'var(--accent)',
  cursor: 'pointer',
  lineHeight: '16px',
  textTransform: 'none',
  letterSpacing: 0,
};
