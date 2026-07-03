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
import {
  RecentList, ThreadList, WorkspaceList, WorkspaceNavSidebar, WorkspaceSwitcher,
} from '../ui/workspace';
import type { TaskNavRow, WsNavKey } from '../ui/workspace';
import type { FootprintStopDto } from '../types';
import { logoColorFor, monogram, useWorkspaceNav } from './useWorkspaceNav';

/**
 * The live workspace navigation sidebar: top nav + either the workspace
 * list (no workspace active) or the active workspace's switcher + thread
 * list. Wired to {@link useWorkspaceNav}; the host (App) supplies the
 * current selection + navigation callbacks. This is the element that
 * replaces the global top bar as the app's single left nav.
 */
export function WorkspaceNavShell({
  activeWorkspaceId, selectedThreadId, tasks, selectedTaskId,
  activeNav, footer, notificationCount,
  collapsed = false, onToggleCollapse,
  showRecent = false, onResumeVisit, onOpenPr,
  onNavigate, onEnterWorkspace, onOpenThread, onOpenTask, onSwitchWorkspace,
  onNewWorkspace, onNewThread, onDeleteWorkspace,
}: {
  activeWorkspaceId: string | null;
  selectedThreadId?: string;
  /** The open thread's tasks — sub-header rows under it. */
  tasks?: TaskNavRow[];
  selectedTaskId?: string;
  activeNav?: WsNavKey;
  footer: { initials: string; name: string; onChat?: () => void; onSettings?: () => void };
  notificationCount?: number;
  /** Fold the rail to a narrow strip. */
  collapsed?: boolean;
  onToggleCollapse?: () => void;
  /** Show the recently-visited list in place of the workspace list —
   *  set on the Home surface, where "what was I just doing?" beats a
   *  second copy of the workspace grid. */
  showRecent?: boolean;
  /** Resume a recently-visited surface (routes via footprint resume). */
  onResumeVisit?: (stop: FootprintStopDto) => void;
  /** Open a PR from the Today summary's "Reviewed" line. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  onNavigate?: (key: WsNavKey) => void;
  onEnterWorkspace?: (id: string) => void;
  onOpenThread?: (id: string) => void;
  onOpenTask?: (id: string) => void;
  /** The switcher ▾ — lateral switch / back to the overview. */
  onSwitchWorkspace?: () => void;
  onNewWorkspace?: () => void;
  onNewThread?: () => void;
  /** Delete a workspace from the overview list. The host confirms + calls
   *  the backend; the polled rail drops the row once it's gone. */
  onDeleteWorkspace?: (id: string, name: string) => void;
}) {
  const data = useWorkspaceNav(activeWorkspaceId);
  const ws = data.activeWorkspace;

  const body = ws === null
    ? (showRecent
      ? <RecentList onResume={onResumeVisit} onOpenPr={onOpenPr} />
      : (
        <WorkspaceList
          workspaces={data.workspaces}
          activeId={activeWorkspaceId ?? undefined}
          onOpen={onEnterWorkspace}
          onDelete={onDeleteWorkspace}
          onNewWorkspace={onNewWorkspace}
        />
      ))
    : (
      <>
        <WorkspaceSwitcher
          initials={monogram(ws.name).toUpperCase()}
          color={logoColorFor(ws.name)}
          name={ws.name}
          sub={`${ws.repos.length} repos · ${ws.activeThreadCount} threads`}
          onSwitch={onSwitchWorkspace}
        />
        <ThreadList
          threads={data.threads}
          selectedId={selectedThreadId}
          tasks={tasks}
          selectedTaskId={selectedTaskId}
          onOpen={onOpenThread}
          onOpenTask={onOpenTask}
          onNewThread={onNewThread}
        />
      </>
    );

  return (
    <WorkspaceNavSidebar
      activeNav={activeNav}
      onNavigate={onNavigate}
      backHint={activeWorkspaceId !== null}
      footer={footer}
      notificationCount={notificationCount}
      collapsed={collapsed}
      onToggleCollapse={onToggleCollapse}
    >
      {body}
    </WorkspaceNavSidebar>
  );
}
