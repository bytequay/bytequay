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
  ThreadList, WorkspaceList, WorkspaceNavSidebar, WorkspaceSwitcher,
} from '../ui/workspace';
import type { WsNavKey } from '../ui/workspace';
import { logoColorFor, monogram, useWorkspaceNav } from './useWorkspaceNav';

/**
 * The live workspace navigation sidebar: top nav + either the workspace
 * list (no workspace active) or the active workspace's switcher + thread
 * list. Wired to {@link useWorkspaceNav}; the host (App) supplies the
 * current selection + navigation callbacks. This is the element that
 * replaces the global top bar as the app's single left nav.
 */
export function WorkspaceNavShell({
  activeWorkspaceId, selectedThreadId, activeNav, footer, notificationCount,
  onNavigate, onEnterWorkspace, onOpenThread, onSwitchWorkspace,
  onNewWorkspace, onNewThread,
}: {
  activeWorkspaceId: string | null;
  selectedThreadId?: string;
  activeNav?: WsNavKey;
  footer: { initials: string; name: string; onChat?: () => void; onSettings?: () => void };
  notificationCount?: number;
  onNavigate?: (key: WsNavKey) => void;
  onEnterWorkspace?: (id: string) => void;
  onOpenThread?: (id: string) => void;
  /** The switcher ▾ — lateral switch / back to the overview. */
  onSwitchWorkspace?: () => void;
  onNewWorkspace?: () => void;
  onNewThread?: () => void;
}) {
  const data = useWorkspaceNav(activeWorkspaceId);
  const ws = data.activeWorkspace;

  const body = ws === null
    ? (
      <WorkspaceList
        workspaces={data.workspaces}
        activeId={activeWorkspaceId ?? undefined}
        onOpen={onEnterWorkspace}
        onNewWorkspace={onNewWorkspace}
      />
    )
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
          onOpen={onOpenThread}
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
    >
      {body}
    </WorkspaceNavSidebar>
  );
}
