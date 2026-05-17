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
import type { TaskDto, TaskGroupDto, TaskGroupMembershipDto, TaskStatusDto } from '../types';
import GroupMenu from './GroupMenu';
import GroupSettingsDialog from './GroupSettingsDialog';
import GroupTaskGrid from './GroupTaskGrid';
import NewTaskDialog from './NewTaskDialog';
import TasksGroupPage from './TasksGroupPage';
import TasksLeftRail, {
  repoKey,
  type GroupFilter,
  type ProviderFilter,
  type RepoFilter,
  type StatusFilter,
} from './TasksLeftRail';

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

const IMMERSIVE_KEY = 'bytequay.tasks.groupImmersive';
function loadImmersive(): boolean {
  try { return window.localStorage.getItem(IMMERSIVE_KEY) === '1'; }
  catch { return false; }
}

type Props = {
  /** Status filter the left rail is highlighting; drives which tasks
   *  appear in the main pane. */
  filter: StatusFilter;
  onFilterChange: (filter: StatusFilter) => void;
  /** Provider filter — narrows the list to a single agent provider
   *  (e.g. {@code "claude-code"}). {@code null} means no filter. */
  provider: ProviderFilter;
  onProviderChange: (provider: ProviderFilter) => void;
  /** Group filter — narrows the list to a single user-defined group.
   *  {@code null} means show every task across every group. */
  groupId: GroupFilter;
  onGroupChange: (group: GroupFilter) => void;
  /** Repo filter — narrows the list to tasks whose working directory's
   *  last segment matches this canonical key. */
  repo: RepoFilter;
  onRepoChange: (repo: RepoFilter) => void;
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
  filter, onFilterChange, provider, onProviderChange,
  groupId, onGroupChange,
  repo, onRepoChange,
  onSelectTask, onOpenSettings,
}: Props) {
  const [tasks, setTasks] = useState<TaskDto[] | null>(null);
  const [groups, setGroups] = useState<TaskGroupDto[]>([]);
  const [memberships, setMemberships] = useState<TaskGroupMembershipDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [activeGroup, setActiveGroup] = useState<TaskGroupDto | null>(null);
  const [showGroupSettings, setShowGroupSettings] = useState(false);
  const [immersive, setImmersive] = useState<boolean>(loadImmersive);

  useEffect(() => {
    try { window.localStorage.setItem(IMMERSIVE_KEY, immersive ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, [immersive]);

  // Falling out of group view (back to All Tasks etc.) drops the
  // immersive flag so the wider page chrome shows up again. The user
  // keeps the bit only while inside the group page.
  useEffect(() => {
    if (groupId === null && immersive) setImmersive(false);
  }, [groupId, immersive]);

  const refresh = useCallback(async () => {
    try {
      const [list, gs, ms] = await Promise.all([
        window.bridge.listTasks(),
        window.bridge.listTaskGroups().catch(() => [] as TaskGroupDto[]),
        window.bridge.listTaskGroupMemberships().catch(() => [] as TaskGroupMembershipDto[]),
      ]);
      setTasks(list);
      setGroups(gs);
      setMemberships(ms);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const toggleTaskInGroup = useCallback(
    async (taskId: string, nextGroupId: string, present: boolean) => {
      try {
        if (present) {
          await window.bridge.addTaskToGroup(nextGroupId, taskId);
        }
        else {
          await window.bridge.removeTaskFromGroup(nextGroupId, taskId);
        }
        await refresh();
      }
      catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, [refresh]);

  // Index memberships by taskId so child components can ask "which
  // groups is task X in?" in O(1) instead of scanning the flat list.
  const groupIdsByTaskId = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const m of memberships) {
      const list = map.get(m.taskId);
      if (list === undefined) {
        map.set(m.taskId, [m.groupId]);
      }
      else {
        list.push(m.groupId);
      }
    }
    return map;
  }, [memberships]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // ⌘N / Ctrl+N opens the New task dialog. Skip while a text field
  // has focus so a literal "n" keystroke inside the search box / a
  // task title still types a character. The left-rail button shows
  // a "⌘N" hint, so this binding is the contract.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.shiftKey || e.altKey) return;
      if (e.key !== 'n' && e.key !== 'N') return;
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
      if (showCreate) return;
      e.preventDefault();
      setShowCreate(true);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [showCreate]);

  // Pull the selected group's metadata so the header can render its
  // glyph + name. The rail keeps its own copy for the listing.
  useEffect(() => {
    if (!groupId) {
      setActiveGroup(null);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const all = await window.bridge.listTaskGroups();
        if (cancelled) return;
        setActiveGroup(all.find(g => g.id === groupId) ?? null);
      }
      catch {
        if (!cancelled) setActiveGroup(null);
      }
    })();
    return () => { cancelled = true; };
  }, [groupId]);

  const filtered = useMemo(() => {
    if (!tasks) return [];
    return tasks.filter(t => {
      if (filter !== 'ALL' && t.status !== filter) return false;
      if (provider && (t.provider || '').toLowerCase() !== provider) return false;
      if (groupId && !(groupIdsByTaskId.get(t.id) ?? []).includes(groupId)) return false;
      if (repo && repoKey(t.workingDir) !== repo) return false;
      return true;
    });
  }, [tasks, filter, provider, groupId, repo, groupIdsByTaskId]);

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

  /** Tile order: active first (RUNNING / AWAITING), then PENDING /
   *  IDLE, terminal last — so the operator scans live work first. */
  const tilesOrdered = useMemo(() => {
    const rank: Record<TaskStatusDto, number> = {
      RUNNING:   0,
      AWAITING:  1,
      PENDING:   2,
      IDLE:      3,
      COMPLETED: 4,
      ERRORED:   5,
    };
    return [...filtered].sort((a, b) => {
      const r = rank[a.status] - rank[b.status];
      return r !== 0 ? r : b.updatedAt.localeCompare(a.updatedAt);
    });
  }, [filtered]);

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

  // Per-tile interactions for the group view — pure pass-throughs to
  // the bridge so each tile can act like a mini detail page without
  // owning its own polling logic. The grid already refreshes message
  // previews on a 4s cadence, so a successful send / decide will
  // surface in the tile within that window.
  const onTileSend = useCallback(async (taskId: string, input: string) => {
    try {
      await window.bridge.sendTaskMessage(taskId, input);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  const onTileInterrupt = useCallback(async (taskId: string) => {
    try {
      await window.bridge.interruptTask(taskId);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  const onTileDecide = useCallback(
    async (
      taskId: string,
      callId: string,
      decision: 'ALLOW' | 'DENY',
      preApprove?: { toolName: string; count: number },
    ) => {
      try {
        await window.bridge.decideTaskPermission(taskId, callId, decision, preApprove);
      }
      catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, []);

  // Inside a group → render the redesigned group page (compact
  // sidebar + tmux-style tile grid). Falls through to the default
  // list view when no group is selected or while the initial fetch
  // is still in flight.
  if (activeGroup && tasks !== null) {
    return (
      <>
        <TasksGroupPage
          group={activeGroup}
          groups={groups}
          tasks={tilesOrdered}
          groupIdsByTaskId={groupIdsByTaskId}
          busyId={busyId}
          onSelectTask={onSelectTask}
          onToggleGroup={toggleTaskInGroup}
          onStop={onStop}
          onSend={onTileSend}
          onInterrupt={onTileInterrupt}
          onDecide={onTileDecide}
          onAddTask={() => setShowCreate(true)}
          onOpenGroupSettings={() => setShowGroupSettings(true)}
          onRefresh={refresh}
          immersive={immersive}
          onChangeImmersive={setImmersive}
        />

        {error && (
          <div style={errorBannerStyle}>
            <strong>Couldn't load tasks.</strong> {error}
          </div>
        )}

        {showCreate && (
          <NewTaskDialog
            onClose={() => setShowCreate(false)}
            initialGroupId={groupId}
            onCreated={async () => {
              setShowCreate(false);
              await refresh();
            }}
          />
        )}

        {showGroupSettings && activeGroup && (
          <GroupSettingsDialog
            group={activeGroup}
            pinnedTaskCount={filteredCount}
            onClose={() => setShowGroupSettings(false)}
            onSaved={updated => {
              setShowGroupSettings(false);
              setActiveGroup(updated);
              void refresh();
            }}
            onDeleted={() => {
              setShowGroupSettings(false);
              onGroupChange(null);
              void refresh();
            }}
          />
        )}
      </>
    );
  }

  return (
    <section style={layoutStyle}>
      <TasksLeftRail
        tasks={tasks ?? []}
        groupIdsByTaskId={groupIdsByTaskId}
        statusFilter={filter}
        onStatusFilter={onFilterChange}
        providerFilter={provider}
        onProviderFilter={onProviderChange}
        groupFilter={groupId}
        onGroupFilter={onGroupChange}
        repoFilter={repo}
        onRepoFilter={onRepoChange}
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
                      groups={groups}
                      currentGroupIds={groupIdsByTaskId.get(t.id) ?? []}
                      busy={busyId === t.id}
                      onOpen={() => onSelectTask(t.id)}
                      onStop={() => void onStop(t.id)}
                      onToggleGroup={toggleTaskInGroup}
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
          initialGroupId={groupId}
          onCreated={async () => {
            setShowCreate(false);
            await refresh();
          }}
        />
      )}

      {showGroupSettings && activeGroup && (
        <GroupSettingsDialog
          group={activeGroup}
          pinnedTaskCount={filteredCount}
          onClose={() => setShowGroupSettings(false)}
          onSaved={next => {
            setActiveGroup(next);
            setShowGroupSettings(false);
          }}
          onDeleted={() => {
            setShowGroupSettings(false);
            // Pop back to the all-tasks view — the group is gone, the
            // tasks are now ungrouped.
            onGroupChange(null);
            void refresh();
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

function TaskRow({ task, groups, currentGroupIds, busy, onOpen, onStop, onToggleGroup }: {
  task: TaskDto;
  groups: TaskGroupDto[];
  currentGroupIds: string[];
  busy: boolean;
  onOpen: () => void;
  onStop: () => void;
  onToggleGroup: (taskId: string, groupId: string, present: boolean) => void | Promise<void>;
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
        <GroupMenu task={task} groups={groups} currentGroupIds={currentGroupIds} onToggle={onToggleGroup} />
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
    COMPLETED: { fg: '#ffffff', bg: '#64748b' },
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
const titleStyle: React.CSSProperties = {
  margin: 0, fontSize: 24, fontWeight: 700, color: 'var(--text-1)',
};
const subtitleStyle: React.CSSProperties = {
  margin: '4px 0 0', color: 'var(--text-3)', maxWidth: 600,
};
const headerActionsStyle: React.CSSProperties = { display: 'flex', gap: 8, alignItems: 'center' };
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--bg-btn-secondary)',
  color: 'var(--text-2)',
  border: '1px solid var(--border-input)',
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
  border: '1px dashed var(--border)',
  borderRadius: 8,
  color: 'var(--text-2)',
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4, color: 'var(--text-1)' };
const mutedTextStyle: React.CSSProperties = { color: 'var(--text-3)', fontSize: 13 };
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
  color: 'var(--text-3)',
};
const groupCountStyle: React.CSSProperties = {
  background: 'var(--bg-elevated)',
  color: 'var(--text-2)',
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
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  cursor: 'pointer',
  color: 'var(--text-1)',
};
const rowMainStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0, flex: 1 };
const rowTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  color: 'var(--text-1)',
};
const rowMetaStyle: React.CSSProperties = { display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' };
const rowSideStyle: React.CSSProperties = { display: 'flex', gap: 12, alignItems: 'center', flexShrink: 0 };
const branchPillStyle: React.CSSProperties = {
  padding: '2px 8px',
  background: 'var(--accent-a10)',
  color: 'var(--accent-dark)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 4,
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};
