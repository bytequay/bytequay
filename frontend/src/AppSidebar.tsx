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
import { useState, type ReactNode } from 'react';
import { CircleDot, GitBranch, History, House, LayoutGrid, ListChecks, PanelLeft, Settings, SquareCheck } from 'lucide-react';
import Avatar from './Avatar';
import type { WorkspaceDto } from './types';
import { useResizablePane } from './useResizablePane';
import { workspaceHasReadableCode, workspacePhaseColor, workspacePhaseLabel, type WorkspaceTab } from './workspaces/model';

export type AppPage = 'home' | 'recent' | 'pulls' | 'workspaces' | 'settings';

type NavItem = { page: AppPage; label: string; icon: ReactNode };

const GLOBAL_NAV_ITEMS: NavItem[] = [
  { page: 'home', label: 'Home', icon: <HomeIcon /> },
  { page: 'recent', label: 'Recent', icon: <RecentIcon /> },
];

const APP_NAV_ITEMS: NavItem[] = [
  { page: 'pulls', label: 'Pull requests', icon: <PullsIcon /> },
  { page: 'workspaces', label: 'Workspaces', icon: <WorkspacesIcon /> },
];

const WORKSPACE_TABS: Array<{ tab: WorkspaceTab; label: string; icon: ReactNode }> = [
  { tab: 'home', label: 'Workspace home', icon: <HomeIcon /> },
  { tab: 'tasks', label: 'Tasks', icon: <TasksIcon /> },
  { tab: 'prs', label: 'Pull requests', icon: <PullsIcon /> },
  { tab: 'issues', label: 'Issues', icon: <IssuesIcon /> },
  { tab: 'commits', label: 'Commits', icon: <CommitsIcon /> },
];

export default function AppSidebar({
  page,
  onNavigate,
  canGoBack,
  canGoForward,
  onBack,
  onForward,
  workspace,
  workspaceTab,
  onWorkspaceTab,
}: {
  page: AppPage;
  onNavigate: (page: AppPage) => void;
  canGoBack: boolean;
  canGoForward: boolean;
  onBack: () => void;
  onForward: () => void;
  workspace: WorkspaceDto | null;
  workspaceTab: WorkspaceTab;
  onWorkspaceTab: (tab: WorkspaceTab) => void;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const sidebar = useResizablePane(222, 200, 420);

  const historyControls = (
    <div className="app-sidebar__history" role="group" aria-label="View history">
      <button type="button" aria-label="Back" title="Back (⌘[)" disabled={!canGoBack} onClick={onBack}>‹</button>
      <button type="button" aria-label="Forward" title="Forward (⌘])" disabled={!canGoForward} onClick={onForward}>›</button>
    </div>
  );

  const navItem = ({ page: destination, label, icon }: NavItem) => (
    <button
      key={destination}
      type="button"
      className={`app-sidebar__item${page === destination ? ' is-active' : ''}`}
      aria-label={label}
      title={label}
      onClick={() => onNavigate(destination)}
    >
      <span aria-hidden="true">{icon}</span>
      <span className="app-sidebar__label">{label}</span>
    </button>
  );

  const disabledReason = (tab: WorkspaceTab): string | null => {
    if (!workspace) return null;
    if (tab === 'commits' && !workspaceHasReadableCode(workspace)) {
      return 'Available after the repository is cloned.';
    }
    return null;
  };

  const workspaceItem = ({ tab, label, icon }: typeof WORKSPACE_TABS[number]) => {
    const reason = disabledReason(tab);
    return (
      <button
        key={tab}
        type="button"
        className={`app-sidebar__item${workspaceTab === tab ? ' is-active' : ''}`}
        aria-label={label}
        title={reason ?? label}
        disabled={reason !== null}
        onClick={() => onWorkspaceTab(tab)}
      >
        <span aria-hidden="true">{icon}</span>
        <span className="app-sidebar__label">{label}</span>
      </button>
    );
  };

  return (
    <aside
      className={`app-sidebar${collapsed ? ' is-collapsed' : ''}${sidebar.resizing ? ' is-resizing' : ''}`}
      style={collapsed ? undefined : { width: sidebar.width, flexBasis: sidebar.width }}
    >
      <div className="app-sidebar__traffic">
        <div className="app-sidebar__lights" aria-label="Window controls">
          <button type="button" aria-label="Close" onClick={() => { void window.bridge.controlWindow('close'); }} />
          <button type="button" aria-label="Minimize" onClick={() => { void window.bridge.controlWindow('minimize'); }} />
          <button type="button" aria-label="Toggle fullscreen" onClick={() => { void window.bridge.controlWindow('zoom'); }} />
        </div>
        <button
          type="button"
          className="app-sidebar__toggle"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          onClick={() => setCollapsed(value => !value)}
        >
          <PanelIcon />
        </button>
        <div className="app-sidebar__spacer" />
        {!collapsed && historyControls}
        {workspace !== null && (
          <>
            <button
              type="button"
              className="app-sidebar__traffic-btn"
              aria-label="App home"
              title="App home"
              onClick={() => onNavigate('home')}
            >
              <HomeIcon />
            </button>
            <button
              type="button"
              className="app-sidebar__traffic-btn"
              aria-label="All workspaces"
              title="All workspaces"
              onClick={() => onNavigate('workspaces')}
            >
              ▦
            </button>
          </>
        )}
      </div>
      {collapsed && historyControls}
      <nav className="app-sidebar__nav" aria-label="Global navigation">
        {GLOBAL_NAV_ITEMS.map(navItem)}
        <div className="app-sidebar__rule" />
      </nav>
      {workspace === null ? (
        <>
          <nav className="app-sidebar__nav" aria-label="Application navigation">
            {APP_NAV_ITEMS.map(navItem)}
          </nav>
          <div className="app-sidebar__spacer" />
          <nav className="app-sidebar__nav app-sidebar__nav--bottom" aria-label="Application settings">
            {navItem({ page: 'settings', label: 'Settings', icon: <SettingsIcon /> })}
          </nav>
        </>
      ) : (
        <>
          <button
            type="button"
            className="app-sidebar__ws-head"
            aria-label={`${workspace.name} workspace home`}
            title={workspace.localRepositoryPath}
            onClick={() => onWorkspaceTab('home')}
          >
            <Avatar login={workspace.selectedOwner} size={18} />
            <span
              className="app-sidebar__ws-dot"
              style={{ background: workspacePhaseColor(workspace) }}
              role="img"
              aria-label={`Workspace ${workspacePhaseLabel(workspace).toLowerCase()}`}
              title={`Workspace ${workspacePhaseLabel(workspace).toLowerCase()}`}
            />
            <span className="app-sidebar__ws-name app-sidebar__label" title={workspace.name}>{workspace.name}</span>
          </button>
          <nav className="app-sidebar__nav" aria-label="Workspace navigation">
            {WORKSPACE_TABS.map(workspaceItem)}
          </nav>
          <div className="app-sidebar__spacer" />
          <nav className="app-sidebar__nav app-sidebar__nav--bottom" aria-label="Workspace settings">
            {workspaceItem({ tab: 'settings', label: 'Workspace settings', icon: <SettingsIcon /> })}
          </nav>
        </>
      )}
      {!collapsed && (
        <div
          className="app-sidebar__resize"
          role="separator"
          aria-label="Resize sidebar"
          aria-orientation="vertical"
          title="Drag to resize"
          onMouseDown={sidebar.startResize}
        />
      )}
    </aside>
  );
}

function HomeIcon() {
  return <House size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function RecentIcon() {
  return <History size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function PullsIcon() {
  return <ListChecks size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function WorkspacesIcon() {
  return <LayoutGrid size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function TasksIcon() {
  return <SquareCheck size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function IssuesIcon() {
  return <CircleDot size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function CommitsIcon() {
  return <GitBranch size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function SettingsIcon() {
  return <Settings size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function PanelIcon() {
  return <PanelLeft size={18} strokeWidth={1.5} aria-hidden="true" />;
}
