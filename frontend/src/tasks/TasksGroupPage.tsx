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
import GroupTaskGrid from './GroupTaskGrid';
import TasksGroupSidebar from './TasksGroupSidebar';
import TaskZoomModal from './TaskZoomModal';
import type { TaskDto, TaskGroupDto } from '../types';
import type { PendingPermission } from './ConversationPane';

/**
 * Tasks-group page shell. Replaces the regular {@code TasksPage}
 * chrome (left rail / breadcrumb / header / layout selector) with
 * a compact 200px sidebar plus a chrome-free, tmux-style tile grid
 * that fills the rest of the viewport.
 *
 * <p>Follows {@code docs/mockups/design/tasks/tasks-group.png}
 * (default) and {@code tasks-group-immersive.png} (immersive mode
 * collapses both the topnav and the rail down to 4px slivers; Esc
 * exits). The zoom modal in {@code tasks-group-zoom.png} is a
 * follow-up and not wired here yet.
 */
export type TasksGroupPageProps = {
  group: TaskGroupDto;
  groups: TaskGroupDto[];
  /** Tasks belonging to this group. The parent has already filtered
   *  to group membership before passing them in. */
  tasks: TaskDto[];
  groupIdsByTaskId: Map<string, string[]>;
  busyId: string | null;
  onSelectTask: (taskId: string) => void;
  onToggleGroup: (taskId: string, groupId: string, present: boolean) => void | Promise<void>;
  onStop: (taskId: string) => void | Promise<void>;
  onSend: (taskId: string, input: string) => void | Promise<void>;
  onInterrupt: (taskId: string) => void | Promise<void>;
  onDecide: (
    taskId: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  onAddTask: () => void;
  onOpenGroupSettings: () => void;
  onRefresh: () => void | Promise<void>;
  /** Toggled by the sidebar's ⛶ button. The parent owns the state
   *  so it can also lift the topnav into immersive mode (which lives
   *  outside this shell). Esc inside the page exits. */
  immersive: boolean;
  onChangeImmersive: (next: boolean) => void;
};

const GROUP_MAX_MEMBERS = 4;

export default function TasksGroupPage(props: TasksGroupPageProps) {
  const {
    group, groups, tasks, groupIdsByTaskId, busyId,
    onSelectTask, onToggleGroup, onStop, onSend, onInterrupt, onDecide,
    onAddTask, onOpenGroupSettings, onRefresh,
    immersive, onChangeImmersive,
  } = props;

  const [zoomedTaskId, setZoomedTaskId] = useState<string | null>(null);
  const zoomedTask = useMemo(
    () => zoomedTaskId === null ? null : tasks.find(t => t.id === zoomedTaskId) ?? null,
    [zoomedTaskId, tasks]);

  // Drop the zoom whenever the underlying task disappears from the
  // group — keeps a stale modal from lingering after a stop+delete.
  useEffect(() => {
    if (zoomedTaskId !== null && zoomedTask === null) {
      setZoomedTaskId(null);
    }
  }, [zoomedTaskId, zoomedTask]);

  // PermissionRequested pre-rendering — the tiles read pendingPermission
  // from the messages they fetch internally; we don't surface it at the
  // shell level.
  void ([] as PendingPermission[]);

  // Esc exits immersive — global handler so the user can press it
  // from anywhere on the page, not just over the rail.
  useEffect(() => {
    if (!immersive) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onChangeImmersive(false);
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [immersive, onChangeImmersive]);

  return (
    <section style={shellStyle}>
      {immersive ? (
        <button
          type="button"
          title="Show sidebar (Esc to exit immersive)"
          onClick={() => onChangeImmersive(false)}
          style={collapsedRailStyle}
          aria-label="Exit immersive mode"
        />
      ) : (
        <TasksGroupSidebar
          group={group}
          tasks={tasks}
          canAddTask={tasks.length < GROUP_MAX_MEMBERS}
          onAddTask={onAddTask}
          onOpenGroupSettings={onOpenGroupSettings}
          onRefresh={() => void onRefresh()}
          onToggleImmersive={() => onChangeImmersive(!immersive)}
          immersive={immersive}
        />
      )}
      <main style={mainStyle}>
        <GroupTaskGrid
          tasks={tasks}
          groups={groups}
          groupIdsByTaskId={groupIdsByTaskId}
          busyId={busyId}
          // Tiles open the zoom modal — the design intentionally
          // hides the direct path to the full detail page from the
          // tile level. The modal's ⛶ button is the only way to
          // navigate into /tasks/:id.
          onOpen={taskId => setZoomedTaskId(taskId)}
          onToggleGroup={onToggleGroup}
          onStop={onStop}
          onSend={onSend}
          onInterrupt={onInterrupt}
          onDecide={onDecide}
        />
      </main>

      {zoomedTask !== null && (
        <TaskZoomModal
          task={zoomedTask}
          onClose={() => setZoomedTaskId(null)}
          onExpandToDetail={onSelectTask}
        />
      )}
    </section>
  );
}

const shellStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'row',
  alignItems: 'stretch',
  height: 'calc(100vh - 56px)',
  background: 'var(--bg-base)',
};

const mainStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  // No padding — the mockup is explicit that tiles butt right up
  // against the rail and screen edges like tmux panes.
};

// 4px sliver shown in place of the sidebar when immersive mode is
// on. Click anywhere (or press Esc) to bring the rail back.
const collapsedRailStyle: React.CSSProperties = {
  flex: '0 0 4px',
  width: 4,
  background: 'var(--bg-elevated)',
  borderRight: '1px solid var(--border)',
  opacity: 0.6,
  border: 'none',
  cursor: 'ew-resize',
  padding: 0,
};
