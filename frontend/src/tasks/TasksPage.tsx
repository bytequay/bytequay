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
import type { TaskDto, TaskStatusDto } from '../types';
import NewTaskDialog from './NewTaskDialog';
import TasksLeftRail, { type StatusFilter } from './TasksLeftRail';

/** Order in which the four buckets are rendered. Active sessions
 *  (RUNNING / AWAITING) at the top so the user can resume them with
 *  one click; PENDING and IDLE ride below; terminal (COMPLETED /
 *  ERRORED) at the bottom. */
const STATUS_GROUPS: Array<{ key: 'active' | 'queued' | 'idle' | 'done'; label: string; statuses: TaskStatusDto[] }> = [
  { key: 'active', label: 'Active', statuses: ['RUNNING', 'AWAITING'] },
  { key: 'queued', label: 'Queued', statuses: ['PENDING'] },
  { key: 'idle', label: 'Idle', statuses: ['IDLE'] },
  { key: 'done', label: 'Completed', statuses: ['COMPLETED', 'ERRORED'] },
];

type Props = {
  /** Status filter the left rail is highlighting; drives which tasks
   *  appear in the main pane. */
  filter: StatusFilter;
  onFilterChange: (filter: StatusFilter) => void;
  /** Routes the user to the task detail / live conversation page. */
  onSelectTask: (taskId: string) => void;
  /** Routes to Settings → Integrations from the rail's footer row. */
  onOpenSettings: () => void;
};

/**
 * AI coding tasks — left rail (status / provider / recent), main
 * pane with filtered, status-grouped cards. Layout mirrors
 * {@code docs/mockups/design/tasks/tasks-list.png}.
 */
export default function TasksPage({
  filter, onFilterChange, onSelectTask, onOpenSettings,
}: Props) {
  const [tasks, setTasks] = useState<TaskDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTasks();
      setTasks(list);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const filtered = useMemo(() => {
    if (!tasks) return [];
    if (filter === 'ALL') return tasks;
    return tasks.filter(t => t.status === filter);
  }, [tasks, filter]);

  const grouped = useMemo(() => {
    const map = new Map<TaskStatusDto, TaskDto[]>();
    for (const t of filtered) {
      const arr = map.get(t.status) ?? [];
      arr.push(t);
      map.set(t.status, arr);
    }
    return STATUS_GROUPS.map(group => ({
      ...group,
      rows: group.statuses.flatMap(s => map.get(s) ?? []),
    }));
  }, [filtered]);

  const totalCount = tasks?.length ?? 0;
  const filteredCount = filtered.length;

  const onStop = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await window.bridge.stopTask(id);
      await refresh();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setBusyId(null);
    }
  }, [refresh]);

  return (
    <section style={layoutStyle}>
      <TasksLeftRail
        tasks={tasks ?? []}
        statusFilter={filter}
        onStatusFilter={onFilterChange}
        onSelectTask={onSelectTask}
        onNewTask={() => setShowCreate(true)}
        onOpenSettings={onOpenSettings}
      />

      <div style={mainStyle}>
        <header style={headerStyle}>
          <div>
            <h1 style={titleStyle}>{filterLabel(filter)}</h1>
            <p style={subtitleStyle}>
              {filter === 'ALL'
                ? 'Delegated AI coding runs. Pick a status on the left to focus.'
                : `Tasks in ${filter.toLowerCase()} state.`}
            </p>
          </div>
          <div style={headerActionsStyle}>
            <button
              type="button"
              onClick={() => void refresh()}
              style={secondaryBtnStyle}
              title="Refresh list"
            >
              Refresh
            </button>
          </div>
        </header>

        {error && (
          <div style={errorBannerStyle}>
            <strong>Couldn't load tasks.</strong> {error}
          </div>
        )}

        {tasks === null && !error && (
          <div style={mutedTextStyle}>Loading…</div>
        )}

        {tasks !== null && totalCount === 0 && (
          <div style={emptyStateStyle}>
            <div style={emptyTitleStyle}>No tasks yet</div>
            <div style={mutedTextStyle}>
              Tasks are agent runs you delegate from the app. Use{' '}
              <strong>+ New task</strong> on the left to start one.
            </div>
          </div>
        )}

        {tasks !== null && totalCount > 0 && filteredCount === 0 && (
          <div style={emptyStateStyle}>
            <div style={emptyTitleStyle}>Nothing in {filter.toLowerCase()}</div>
            <div style={mutedTextStyle}>
              Switch to <strong>All tasks</strong> on the left, or pick
              another status.
            </div>
          </div>
        )}

        {filteredCount > 0 && (
          <div style={listStyle}>
            {grouped.filter(g => g.rows.length > 0).map(group => (
              <div key={group.key} style={groupStyle}>
                <div style={groupHeaderStyle}>
                  <span>{group.label}</span>
                  <span style={groupCountStyle}>{group.rows.length}</span>
                </div>
                <div style={groupRowsStyle}>
                  {group.rows.map(t => (
                    <TaskRow
                      key={t.id}
                      task={t}
                      busy={busyId === t.id}
                      onOpen={() => onSelectTask(t.id)}
                      onStop={() => void onStop(t.id)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {showCreate && (
        <NewTaskDialog
          onClose={() => setShowCreate(false)}
          onCreated={async () => {
            setShowCreate(false);
            await refresh();
          }}
        />
      )}
    </section>
  );
}

function filterLabel(filter: StatusFilter): string {
  if (filter === 'ALL') return 'Tasks';
  return filter.charAt(0) + filter.slice(1).toLowerCase();
}

function TaskRow({ task, busy, onOpen, onStop }: {
  task: TaskDto;
  busy: boolean;
  onOpen: () => void;
  onStop: () => void;
}) {
  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';
  return (
    <div
      style={rowStyle}
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={e => { if (e.key === 'Enter') onOpen(); }}
    >
      <div style={rowMainStyle}>
        <div style={rowTitleStyle}>{task.title}</div>
        <div style={rowMetaStyle}>
          <StatusPill status={task.status} />
          <span style={mutedTextStyle}>{task.workingDir}</span>
          {task.branchName && (
            <span style={branchPillStyle}>{task.branchName}</span>
          )}
          <span style={mutedTextStyle}>{task.model}</span>
        </div>
      </div>
      <div style={rowSideStyle}>
        <span style={mutedTextStyle}>{formatCost(task.costUsdMilli)}</span>
        <span style={mutedTextStyle}>{formatTime(task.updatedAt)}</span>
        {!isTerminal && (
          <button
            type="button"
            onClick={e => { e.stopPropagation(); onStop(); }}
            disabled={busy}
            style={dangerBtnStyle}
            title="Stop and release the agent"
          >
            {busy ? 'Stopping…' : 'Stop'}
          </button>
        )}
      </div>
    </div>
  );
}

function StatusPill({ status }: { status: TaskStatusDto }) {
  const palette: Record<TaskStatusDto, { fg: string; bg: string }> = {
    RUNNING:   { fg: '#ffffff', bg: '#7C3AED' },
    AWAITING:  { fg: '#ffffff', bg: '#D97706' },
    PENDING:   { fg: '#1F2937', bg: '#E5E7EB' },
    IDLE:      { fg: '#374151', bg: '#F3F4F6' },
    COMPLETED: { fg: '#ffffff', bg: '#10B981' },
    ERRORED:   { fg: '#ffffff', bg: '#DC2626' },
  };
  const { fg, bg } = palette[status];
  return (
    <span style={{
      display: 'inline-block',
      padding: '2px 8px',
      borderRadius: 999,
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.3,
      color: fg,
      background: bg,
    }}>{status}</span>
  );
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = Date.now();
  const ms = now - d.getTime();
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return d.toLocaleString();
}

const layoutStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
  minHeight: 'calc(100vh - 56px)',
};
const mainStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  padding: '24px 32px 64px',
};
const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 16,
  marginBottom: 24,
};
const titleStyle: React.CSSProperties = { margin: 0, fontSize: 24, fontWeight: 700 };
const subtitleStyle: React.CSSProperties = { margin: '4px 0 0', color: '#6B7280', maxWidth: 600 };
const headerActionsStyle: React.CSSProperties = { display: 'flex', gap: 8, alignItems: 'center' };
const primaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: '#7C3AED',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'transparent',
  color: '#374151',
  border: '1px solid #D1D5DB',
  borderRadius: 6,
  cursor: 'pointer',
};
const dangerBtnStyle: React.CSSProperties = {
  padding: '4px 10px',
  background: 'transparent',
  color: '#DC2626',
  border: '1px solid #FCA5A5',
  borderRadius: 4,
  fontSize: 12,
  cursor: 'pointer',
};
const errorBannerStyle: React.CSSProperties = {
  padding: '12px 16px',
  background: '#FEF2F2',
  color: '#991B1B',
  border: '1px solid #FCA5A5',
  borderRadius: 6,
  marginBottom: 16,
};
const emptyStateStyle: React.CSSProperties = {
  padding: '40px 24px',
  textAlign: 'center',
  border: '1px dashed #D1D5DB',
  borderRadius: 8,
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4 };
const mutedTextStyle: React.CSSProperties = { color: '#6B7280', fontSize: 13 };
const listStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 24 };
const groupStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 8 };
const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '0 4px',
  fontSize: 12,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: 0.6,
  color: '#6B7280',
};
const groupCountStyle: React.CSSProperties = {
  background: '#F3F4F6',
  color: '#374151',
  padding: '2px 8px',
  borderRadius: 999,
  fontSize: 11,
};
const groupRowsStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 };
const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 16,
  padding: '12px 16px',
  background: '#fff',
  border: '1px solid #E5E7EB',
  borderRadius: 8,
  cursor: 'pointer',
};
const rowMainStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0, flex: 1 };
const rowTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
};
const rowMetaStyle: React.CSSProperties = { display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' };
const rowSideStyle: React.CSSProperties = { display: 'flex', gap: 12, alignItems: 'center', flexShrink: 0 };
const branchPillStyle: React.CSSProperties = {
  padding: '2px 8px',
  background: '#EFF6FF',
  color: '#1E40AF',
  border: '1px solid #BFDBFE',
  borderRadius: 4,
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};
