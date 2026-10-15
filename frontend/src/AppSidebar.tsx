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
import { RelatedWorkspaceNavigation } from './workspaces/RelatedWorkspaces';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import {
  BookMarked, Bot, ChevronDown, ChevronLeft, ChevronRight, CircleDot, CircleUser, FlaskConical, GitBranch, Globe, History, House, KeyRound,
  LayoutDashboard, LayoutGrid, ListChecks, PanelLeft, Search, Settings, SquareCheck, SquareLibrary,
  Pin, Sparkles, Sun, Tags, TriangleAlert, Users,
} from 'lucide-react';
import UsageMeter from './usage/UsageMeter';
import JiraIcon from './jira/JiraIcon';
import RepoAvatar from './RepoAvatar';
import type { PullRequestDto, WorkspaceDto } from './types';
import { PullRequestStateIcon } from './pulls/PullRequestStateIcon';
import { useResizablePane } from './useResizablePane';
import {
  SETTINGS_ENTRIES,
  SETTINGS_GROUPS,
  type SettingsSection,
  matchesSearch,
} from './settings/routes';
import { workspaceHasReadableCode, type WorkspaceTab } from './workspaces/model';
import PinButton from './home/PinButton';
import { useMemoryNeeds } from './memory/needs';
import { useTasksWaiting } from './agents/attention';
import { openPinned, usePinnedItems } from './home/pinned';
import { useLabLink } from './labLink';

/** What the sidebar needs to be the settings window's nav. Absent on every other page. */
export type SettingsNav = {
  section: SettingsSection;
  workspaces: WorkspaceDto[];
  workspaceId: string | null;
  onSection: (section: SettingsSection) => void;
  onWorkspace: (workspaceId: string) => void;
  onExit: () => void;
};

const SETTINGS_ICONS: Record<SettingsSection, ReactNode> = {
  general: <Settings size={16} strokeWidth={1.7} aria-hidden="true" />,
  appearance: <Sun size={16} strokeWidth={1.7} aria-hidden="true" />,
  browser: <Globe size={16} strokeWidth={1.7} aria-hidden="true" />,
  models: <Sparkles size={16} strokeWidth={1.7} aria-hidden="true" />,
  workflow: <ListChecks size={16} strokeWidth={1.7} aria-hidden="true" />,
  skills: <SquareLibrary size={16} strokeWidth={1.7} aria-hidden="true" />,
  jira: <JiraIcon size={16} />,
  google: <Globe size={16} aria-hidden="true" />,
  lab: <LabIcon />,
  github: <CircleUser size={16} strokeWidth={1.7} aria-hidden="true" />,
  'ws-repository': <Tags size={16} strokeWidth={1.7} aria-hidden="true" />,
  'ws-agents': <Bot size={16} strokeWidth={1.7} aria-hidden="true" />,
  'ws-token': <KeyRound size={16} strokeWidth={1.7} aria-hidden="true" />,
  'ws-advanced': <TriangleAlert size={16} strokeWidth={1.7} aria-hidden="true" />,
};

/** The window buttons, in whichever sidebar is currently flush with the window's left edge. */
export function WindowControls() {
  return (
    <div className="app-sidebar__lights" aria-label="Window controls">
      <button type="button" aria-label="Close" onClick={() => { void window.bridge.controlWindow('close'); }} />
      <button type="button" aria-label="Minimize" onClick={() => { void window.bridge.controlWindow('minimize'); }} />
      <button type="button" aria-label="Toggle fullscreen" onClick={() => { void window.bridge.controlWindow('zoom'); }} />
    </div>
  );
}

export type AppPage = 'home' | 'recent' | 'pulls' | 'workspaces' | 'teams' | 'jira' | 'google' | 'settings';

type NavItem = { page: AppPage; label: string; icon: ReactNode };

const GLOBAL_NAV_ITEMS: NavItem[] = [
  { page: 'home', label: 'Home', icon: <HomeIcon /> },
  { page: 'workspaces', label: 'Workspaces', icon: <LayoutGrid size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { page: 'teams', label: 'Circles', icon: <Users size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { page: 'recent', label: 'Recent', icon: <History size={16} strokeWidth={1.7} aria-hidden="true" /> },
];

const APP_NAV_ITEMS: NavItem[] = [
  { page: 'pulls', label: 'Pull requests', icon: <PullsIcon /> },
  { page: 'jira', label: 'Jira', icon: <JiraIcon size={16} /> },
  { page: 'google', label: 'Google Apps', icon: <Globe size={16} aria-hidden="true" /> },
];

const WORKSPACE_TABS: Array<{ tab: WorkspaceTab; label: string; icon: ReactNode }> = [
  { tab: 'home', label: 'Workspace home', icon: <LayoutDashboard size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { tab: 'tasks', label: 'Tasks', icon: <SquareCheck size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { tab: 'prs', label: 'Pull requests', icon: <PullsIcon /> },
  { tab: 'issues', label: 'Work items', icon: <CircleDot size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { tab: 'commits', label: 'Commits', icon: <GitBranch size={16} strokeWidth={1.7} aria-hidden="true" /> },
  { tab: 'context', label: 'Memory', icon: <BookMarked size={16} strokeWidth={1.7} aria-hidden="true" /> },
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
  relatedWorkspaceId = null,
  onWorkspaceTab,
  jiraConnected = false,
  googleConnected = false,
  browserRailVisible,
  onToggleBrowserRail,
  settings,
}: {
  page: AppPage;
  onNavigate: (page: AppPage) => void;
  canGoBack: boolean;
  canGoForward: boolean;
  onBack: () => void;
  onForward: () => void;
  workspace: WorkspaceDto | null;
  workspaceTab: WorkspaceTab;
  relatedWorkspaceId?: string | null;
  onWorkspaceTab: (tab: WorkspaceTab) => void;
  jiraConnected?: boolean;
  googleConnected?: boolean;
  browserRailVisible: boolean;
  onToggleBrowserRail: () => void;
  /** Present only while settings is open, when the sidebar becomes its category nav. */
  settings?: SettingsNav;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [query, setQuery] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pinsExpanded, setPinsExpanded] = useState(true);
  const pinnedItems = usePinnedItems();
  const lab = useLabLink();
  const pickerMenu = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (pickerOpen) pickerMenu.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [pickerOpen]);
  const sidebar = useResizablePane(222, 200, 420);
  const memoryNeeds = useMemoryNeeds(workspace?.id ?? null, workspaceTab) ?? 0;
  const tasksWaiting = useTasksWaiting(workspace?.id ?? null);

  const historyControls = (
    <div className="app-sidebar__history" role="group" aria-label="View history">
      <button type="button" aria-label="Back" title="Back (⌘[)" disabled={!canGoBack} onClick={onBack}>‹</button>
      <button type="button" aria-label="Forward" title="Forward (⌘])" disabled={!canGoForward} onClick={onForward}>›</button>
    </div>
  );

  /** Every sidebar row, whichever shape renders it. */
  const row = ({ key, label, icon, active, disabled, title, badge, onClick }: {
    key: string;
    label: string;
    icon: ReactNode;
    active: boolean;
    disabled?: boolean;
    title?: string;
    badge?: { count: number; label: string };
    onClick: () => void;
  }) => (
    <button
      key={key}
      type="button"
      className={`app-sidebar__item${active ? ' is-active' : ''}`}
      aria-label={badge === undefined ? label : `${label}, ${badge.label}`}
      aria-current={active ? 'page' : undefined}
      title={title ?? label}
      disabled={disabled}
      onClick={onClick}
    >
      <span aria-hidden="true">{icon}</span>
      <span className="app-sidebar__label">{label}</span>
      {badge !== undefined && <span className="app-sidebar__badge" aria-hidden="true">{badge.count}</span>}
    </button>
  );

  const navItem = ({ page: destination, label, icon }: NavItem) =>
    row({ key: destination, label, icon, active: page === destination && workspace === null, onClick: () => onNavigate(destination) });

  const disabledReason = (tab: WorkspaceTab): string | null => {
    if (!workspace) return null;
    if (tab === 'commits' && !workspaceHasReadableCode(workspace)) {
      return 'Available after the repository is cloned.';
    }
    return null;
  };

  const workspaceItem = ({ tab, label, icon }: typeof WORKSPACE_TABS[number]) => {
    const reason = disabledReason(tab);
    return row({
      key: tab,
      label,
      icon,
      active: relatedWorkspaceId === null && (workspaceTab === tab || tab === 'issues' && workspaceTab === 'jira'),
      disabled: reason !== null,
      title: reason ?? label,
      badge: tab === 'context' && memoryNeeds > 0 ? { count: memoryNeeds, label: `${memoryNeeds} need you` }
        : tab === 'tasks' && tasksWaiting > 0 ? { count: tasksWaiting, label: `${tasksWaiting} need you` } : undefined,
      onClick: () => onWorkspaceTab(tab),
    });
  };

  const settingsBody = settings === undefined ? null : (() => {
    const visible = SETTINGS_ENTRIES.filter(entry => matchesSearch(entry, query));
    const picked = settings.workspaces.find(candidate => candidate.id === settings.workspaceId)
      ?? settings.workspaces[0]
      ?? null;
    return (
      <>
        {row({
          key: 'back',
          label: 'Back to app',
          icon: <ChevronLeft size={16} strokeWidth={1.7} aria-hidden="true" />,
          active: false,
          onClick: settings.onExit,
        })}
        {!collapsed && (
          <div className="app-sidebar__search">
            <Search size={13} strokeWidth={2.2} aria-hidden="true" />
            <input
              value={query}
              placeholder="Search settings"
              aria-label="Search settings"
              onChange={event => setQuery(event.target.value)}
            />
          </div>
        )}
        <nav className="app-sidebar__nav app-sidebar__nav--scroll" aria-label="Settings navigation">
          {SETTINGS_GROUPS.map(group => {
            const entries = visible.filter(entry => entry.group === group);
            if (entries.length === 0) return null;
            return (
              <div key={group}>
                {collapsed ? <div className="app-sidebar__rule" /> : <div className="app-sidebar__brand">{group}</div>}
                {group === 'Workspace' && !collapsed && (
                  <div className="app-sidebar__picker">
                    <button
                      type="button"
                      className="setw-ws-button"
                      aria-label="Workspace being edited"
                      aria-expanded={pickerOpen}
                      disabled={settings.workspaces.length === 0}
                      onClick={() => setPickerOpen(open => !open)}
                    >
                      {picked === null
                        ? <span className="setw-avatar" aria-hidden="true">?</span>
                        : <RepoAvatar owner={picked.selectedOwner} repo={picked.selectedRepo} size={19} />}
                      <span className="setw-ws-name">
                        {picked === null ? 'No workspace' : `${picked.selectedOwner}/${picked.selectedRepo}`}
                      </span>
                      <ChevronDown size={12} strokeWidth={2.4} aria-hidden="true" />
                    </button>
                    {pickerOpen && (
                      <div className="setw-menu is-left is-full" role="listbox" ref={pickerMenu}>
                        {settings.workspaces.map(candidate => (
                          <button
                            type="button"
                            key={candidate.id}
                            role="option"
                            aria-selected={candidate.id === picked?.id}
                            className="setw-menu-item"
                            onClick={() => { setPickerOpen(false); settings.onWorkspace(candidate.id); }}
                          >
                            <RepoAvatar owner={candidate.selectedOwner} repo={candidate.selectedRepo} size={19} />
                            <span className="setw-menu-label">{candidate.selectedOwner}/{candidate.selectedRepo}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
                {entries.map(entry => row({
                  key: entry.section,
                  label: entry.label,
                  icon: SETTINGS_ICONS[entry.section],
                  active: entry.section === settings.section,
                  onClick: () => settings.onSection(entry.section),
                }))}
              </div>
            );
          })}
          {visible.length === 0 && !collapsed && (
            <div className="app-sidebar__empty">Nothing matches “{query}”.</div>
          )}
        </nav>
      </>
    );
  })();

  return (
    <aside
      className={`app-sidebar${collapsed ? ' is-collapsed' : ''}${sidebar.resizing ? ' is-resizing' : ''}`}
      style={collapsed ? undefined : { width: sidebar.width, flexBasis: sidebar.width }}
    >
      <div className="app-sidebar__traffic">
        <WindowControls />
        <button
          type="button"
          className="app-sidebar__toggle"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          onClick={() => setCollapsed(value => !value)}
        >
          <PanelLeft size={14} strokeWidth={1.7} aria-hidden="true" />
        </button>
        <div className="app-sidebar__spacer" />
        {!collapsed && historyControls}
      </div>
      {collapsed && historyControls}
      {settingsBody !== null ? settingsBody : <>
      <nav className="app-sidebar__nav" aria-label="Global navigation">
        {GLOBAL_NAV_ITEMS.map(navItem)}
        <div className="app-sidebar__rule" />
      </nav>
      {workspace === null ? (
        <nav className="app-sidebar__nav" aria-label="Application navigation">
          {APP_NAV_ITEMS.filter(item => (item.page !== 'jira' || jiraConnected) && (item.page !== 'google' || googleConnected)).map(navItem)}
        </nav>
      ) : (
        <>
          <button
            type="button"
            className="app-sidebar__ws-head"
            aria-label={`${workspace.name} workspace home`}
            title={workspace.localRepositoryPath}
            onClick={() => onWorkspaceTab('home')}
          >
            <RepoAvatar owner={workspace.monitoredOwner} repo={workspace.monitoredRepo} size={18} />
            <span className="app-sidebar__ws-name app-sidebar__label" title={workspace.name}>{workspace.name}</span>
          </button>
          <nav className="app-sidebar__nav" aria-label="Workspace navigation">
            {WORKSPACE_TABS.filter(item => item.tab !== 'jira' || jiraConnected).map(workspaceItem)}
          </nav>
          <RelatedWorkspaceNavigation key={workspace.id} workspaceId={workspace.id} activeWorkspaceId={relatedWorkspaceId} activeTab={workspaceTab} />
        </>
      )}
      {pinnedItems.length > 0 && <section className="app-sidebar__pinned" aria-label="Pinned">
        <button type="button" className="app-sidebar__pinned-heading" aria-label="Pinned items"
          aria-expanded={pinsExpanded} aria-controls="pinned-items" onClick={() => setPinsExpanded(value => !value)}>
          <Pin size={14} aria-hidden="true" />
          <span className="app-sidebar__label">Pinned</span>
          {pinsExpanded ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}
        </button>
        {pinsExpanded && <nav id="pinned-items" className="app-sidebar__nav app-sidebar__pinned-list" aria-label="Pinned navigation">
          {pinnedItems.map(item => <div key={item.key} className="app-sidebar__pinned-row">
            {row({
              key: item.key,
              label: item.title,
              title: item.reference ? `${item.reference} · ${item.title}` : item.title,
              icon: item.target.kind === 'pull' ? <PinnedPullRequestIcon repo={item.target.repo} number={item.target.number} /> : item.target.kind === 'jira' ? <JiraIcon size={16} />
                : item.target.kind === 'task' || item.target.kind === 'review' ? <SquareCheck size={16} /> : <CircleDot size={16} />,
              active: false,
              onClick: () => openPinned(item),
            })}
            <PinButton item={item} />
          </div>)}
        </nav>}
      </section>}
      </>}
      {settingsBody === null && <div className="app-sidebar__spacer" />}
      <nav className="app-sidebar__nav app-sidebar__nav--bottom" aria-label="Application settings">
        <UsageMeter />
        {lab.enabled && row({
          key: 'lab',
          label: 'Lab',
          title: 'Open the lab dashboard',
          icon: <LabIcon />,
          active: false,
          onClick: () => { void window.bridge.openInAppBrowser(lab.address); },
        })}
        <button type="button" className={`app-sidebar__item${browserRailVisible ? ' is-active' : ''}`}
          aria-label="Web pages" aria-pressed={browserRailVisible} aria-controls="browser-rail"
          title={browserRailVisible ? 'Hide web pages column' : 'Show web pages column'} onClick={onToggleBrowserRail}>
          <span aria-hidden="true"><Globe size={16} strokeWidth={1.7} /></span>
          <span className="app-sidebar__label">Web pages</span>
        </button>
        {settingsBody === null && navItem({ page: 'settings', label: 'Settings', icon: <SettingsIcon /> })}
      </nav>
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

function PinnedPullRequestIcon({ repo, number }: { repo: string; number: number }) {
  const [pullRequest, setPullRequest] = useState<PullRequestDto | null>(null);
  useEffect(() => {
    let version = 0;
    const reload = () => {
      const request = ++version;
      void window.bridge.findStoredPullRequest(repo, number).then(pr => {
        if (request === version) setPullRequest(pr);
      }).catch(() => {});
    };
    const updated = (event: Event) => {
      const pr = (event as CustomEvent<PullRequestDto[]>).detail.find(candidate => candidate.repo.toLowerCase() === repo.toLowerCase() && candidate.number === number);
      if (pr === undefined) return;
      version++;
      setPullRequest(pr);
    };
    reload();
    const stopSync = window.bridge.onPullRequestsSynced(reload);
    window.addEventListener('bytequay:pr-attention-updated', updated);
    return () => {
      version++;
      stopSync();
      window.removeEventListener('bytequay:pr-attention-updated', updated);
    };
  }, [repo, number]);
  return <PullRequestStateIcon pullRequest={pullRequest} size={16} />;
}

function HomeIcon() {
  return <House size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function PullsIcon() {
  return <ListChecks size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function SettingsIcon() {
  return <Settings size={16} strokeWidth={1.7} aria-hidden="true" />;
}

function LabIcon() {
  return <FlaskConical size={16} strokeWidth={1.7} aria-hidden="true" />;
}
