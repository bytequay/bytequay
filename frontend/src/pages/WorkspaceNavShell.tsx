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
  RecentList, ThreadList, WorkspaceNavSidebar, WorkspaceSwitcher,
} from '../ui/workspace';
import type { TaskNavRow, WsNavKey } from '../ui/workspace';
import type { FootprintStopDto } from '../types';
import { useWorkspaceNav } from './useWorkspaceNav';

/**
 * The live workspace navigation sidebar: top nav + either the
 * recently-visited list (no workspace active) or the active workspace's
 * switcher + thread list. Wired to {@link useWorkspaceNav}; the host
 * (App) supplies the current selection + navigation callbacks. This is
 * the element that replaces the global top bar as the app's single
 * left nav.
 */
export function WorkspaceNavShell({
  activeWorkspaceId, selectedThreadId, tasks, selectedTaskId,
  activeNav, footer, notificationCount,
  collapsed = false, onToggleCollapse,
  onResumeVisit, onOpenPr,
  onBack, onForward, backEnabled, forwardEnabled,
  onNavigate, onOpenThread, onOpenTask, onSwitchWorkspace, onNewThread,
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
  /** Resume a recently-visited surface (routes via footprint resume). */
  onResumeVisit?: (stop: FootprintStopDto) => void;
  /** Open a PR from the Today summary's "Reviewed" line. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  /** Browser-style history navigation for the chrome-row arrows. */
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onNavigate?: (key: WsNavKey) => void;
  onOpenThread?: (id: string) => void;
  onOpenTask?: (id: string) => void;
  /** The switcher ▾ — lateral switch / back to the overview. */
  onSwitchWorkspace?: () => void;
  onNewThread?: () => void;
}) {
  const data = useWorkspaceNav(activeWorkspaceId);
  const ws = data.activeWorkspace;

  const body = ws === null
    ? <RecentList onResume={onResumeVisit} onOpenPr={onOpenPr} />
    : (
      <>
        <WorkspaceSwitcher
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
      onBack={onBack}
      onForward={onForward}
      backEnabled={backEnabled}
      forwardEnabled={forwardEnabled}
    >
      {body}
    </WorkspaceNavSidebar>
  );
}
