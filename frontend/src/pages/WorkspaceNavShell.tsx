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
import { useState, type ReactNode } from 'react';
import { TrunkWorkspaceSidebar } from './TrunkWorkspaceSidebar';

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
  activeNav, notificationCount,
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
  onOpenTask?: (threadId: string, taskId: string) => void;
  /** The switcher ▾ — lateral switch / back to the overview. */
  onSwitchWorkspace?: () => void;
  onNewThread?: () => void;
}) {
  const data = useWorkspaceNav(activeWorkspaceId);
  const [expandedWorkspaceId, setExpandedWorkspaceId] = useState<string | null>(null);
  const ws = data.activeWorkspace;
  const counts = data.overview?.sidebarCounts;
  const visualFrame = typeof document === 'undefined'
    ? undefined
    : document.documentElement.dataset.workspaceVisualFrame;
  const sourceLegacyHub = visualFrame === '1c';
  const sourceSync = visualFrame === '6a';
  const sourceUsesThreadCopy = visualFrame === '1c' || visualFrame === '2b' || sourceSync;
  const developmentTrunks = data.threads.filter(thread => thread.flow !== 'review');
  const reviewTrunks = data.threads.filter(thread => thread.flow === 'review');
  const trunksExpanded = expandedWorkspaceId === activeWorkspaceId;
  const visibleDevelopmentTrunks = trunksExpanded
    ? developmentTrunks
    : developmentTrunks.slice(0, 3);
  const hiddenDevelopmentTrunks = developmentTrunks.length - visibleDevelopmentTrunks.length;
  const pinnedTrunks = (data.overview?.pinnedTrunks ?? [])
    .filter(thread => thread.kind !== 'review')
    .map(thread => ({
      id: thread.id,
      name: thread.title,
      status: threadStatus(thread.status),
      flow: thread.kind === 'review' ? 'review' as const : 'build' as const,
    }));
  const showingSelectedTrunk = selectedThreadId !== undefined;

  if (activeWorkspaceId !== null) {
    return (
      <TrunkWorkspaceSidebar
        workspaceName={ws?.name ?? 'Workspace'}
        repository={data.overview?.repository?.fullName
          ?? ws?.repository?.fullName
          ?? workspaceSlug(ws?.name ?? 'Workspace', ws?.repos[0])}
        threads={data.rawThreads.filter(thread => thread.flow !== 'review')}
        selectedThreadId={selectedThreadId}
        selectedTasks={tasks ?? []}
        counts={data.overview?.sidebarCounts}
        notificationCount={notificationCount ?? data.overview?.sidebarCounts.notifications}
        activeNav={activeNav}
        collapsed={collapsed}
        onToggleCollapse={onToggleCollapse}
        onBack={onBack}
        onForward={onForward}
        backEnabled={backEnabled}
        forwardEnabled={forwardEnabled}
        onNavigate={onNavigate}
        onOpenThread={onOpenThread}
        onOpenTask={onOpenTask}
        onSwitchWorkspace={onSwitchWorkspace}
        onNewThread={onNewThread}
      />
    );
  }

  const body = ws === null
    ? <RecentList onResume={onResumeVisit} onOpenPr={onOpenPr} />
    : (
      <>
        <WorkspaceSwitcher
          name={ws.name}
          sub={data.overview?.repository.fullName ?? workspaceSlug(ws.name, ws.repos[0])}
          onSwitch={onSwitchWorkspace}
        />
        <div className="ws-destinations">
          {!sourceLegacyHub && (
            <WorkspaceDestination
              navKey="today"
              label="Today"
              icon={<WorkspaceIcon kind="today" />}
              active={activeNav === 'today'}
              count={sourceSync ? undefined : counts?.todayNeedsYou ?? ws.needsAttentionCount}
              attention={!sourceSync}
              onNavigate={onNavigate}
            />
          )}
          <WorkspaceGroup label="Work">
            <WorkspaceDestination navKey="trunks" label={sourceUsesThreadCopy ? 'Threads' : 'Trunks'}
              icon={<WorkspaceIcon kind="trunks" />}
              active={activeNav === 'trunks'} count={sourceSync ? undefined : counts?.trunks ?? data.threads.length}
              trailing={sourceSync ? <WorkspaceCount>0</WorkspaceCount> : undefined}
              onNavigate={onNavigate} />
            {showingSelectedTrunk && (
              <div className="ws-selected-trunks">
                <ThreadList
                  threads={visibleDevelopmentTrunks}
                  selectedId={selectedThreadId}
                  tasks={tasks}
                  selectedTaskId={selectedTaskId}
                  onOpen={onOpenThread}
                  onOpenTask={taskId => {
                    if (selectedThreadId !== undefined) onOpenTask?.(selectedThreadId, taskId);
                  }}
                  heading=""
                  showActions={false}
                />
                {hiddenDevelopmentTrunks > 0 && (
                  <button type="button" className="ws-trunks-more"
                    onClick={() => setExpandedWorkspaceId(activeWorkspaceId)}>
                    {hiddenDevelopmentTrunks} more…
                  </button>
                )}
                {reviewTrunks.length > 0 && (
                  <div className="ws-review-trunks">
                    <span className="ws-review-trunks__heading">Reviews</span>
                    {reviewTrunks.slice(0, 1).map(thread => (
                      <div className="ws-review-trunk" key={thread.id}
                        role="button" tabIndex={0}
                        onClick={() => onOpenThread?.(thread.id)}
                        onKeyDown={event => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            onOpenThread?.(thread.id);
                          }
                        }}>
                        <ReviewBotIcon />
                        <span>{thread.name}</span>
                        <i />
                      </div>
                    ))}
                    <small>auto-archives when the PR closes</small>
                  </div>
                )}
              </div>
            )}
            <WorkspaceDestination navKey="pull-requests" label="Pull requests"
              icon={<WorkspaceIcon kind="pull-requests" />} active={activeNav === 'pull-requests'}
              count={sourceSync ? undefined : counts?.pullRequests}
              trailing={sourceSync ? <WorkspaceSyncMeta>14…</WorkspaceSyncMeta> : undefined}
              onNavigate={onNavigate} />
            <WorkspaceDestination navKey="issues" label="Issues" icon={<WorkspaceIcon kind="issues" />}
              active={activeNav === 'issues'} count={sourceSync ? undefined : counts?.issues}
              trailing={sourceSync ? <WorkspaceSyncMeta>queued</WorkspaceSyncMeta> : undefined}
              onNavigate={onNavigate} />
            <WorkspaceDestination navKey="backlog" label="Backlog" icon={<WorkspaceIcon kind="backlog" />}
              active={activeNav === 'backlog'} count={sourceSync ? undefined : counts?.backlog}
              trailing={sourceSync ? <WorkspaceCount>0</WorkspaceCount> : undefined}
              disabled disabledTitle="Backlog is managed inside each trunk"
              onNavigate={onNavigate} />
          </WorkspaceGroup>
          <WorkspaceGroup label="Repo">
            <WorkspaceDestination navKey="branches" label="Branches" icon={<WorkspaceIcon kind="branches" />}
              active={activeNav === 'branches'} count={sourceSync ? undefined : counts?.branches ?? ws.repos.length}
              trailing={sourceSync ? <WorkspaceCount>1</WorkspaceCount> : undefined}
              onNavigate={onNavigate} />
            <WorkspaceDestination navKey="commits" label="Commits" icon={<WorkspaceIcon kind="commits" />}
              active={activeNav === 'commits'} onNavigate={onNavigate} />
            <WorkspaceDestination navKey="sessions" label="Sessions" icon={<WorkspaceIcon kind="sessions" />}
              active={activeNav === 'sessions'} disabled onNavigate={onNavigate} />
          </WorkspaceGroup>
          <WorkspaceGroup label="Brain">
            <WorkspaceDestination navKey="memory" label="Memory" icon={<WorkspaceIcon kind="memory" />}
              active={activeNav === 'memory'} disabled onNavigate={onNavigate} />
            <WorkspaceDestination navKey="insights" label="Insights" icon={<WorkspaceIcon kind="insights" />}
              active={activeNav === 'insights'} onNavigate={onNavigate} />
          </WorkspaceGroup>
        </div>
        {!showingSelectedTrunk && !sourceLegacyHub && !sourceSync && (
          <ThreadList
            threads={pinnedTrunks.length > 0 ? pinnedTrunks : developmentTrunks.slice(0, 5)}
            selectedId={selectedThreadId}
            tasks={tasks}
            selectedTaskId={selectedTaskId}
            onOpen={onOpenThread}
            onOpenTask={taskId => {
              if (selectedThreadId !== undefined) onOpenTask?.(selectedThreadId, taskId);
            }}
            onNewThread={onNewThread}
            heading={sourceUsesThreadCopy ? 'Pinned threads' : 'Pinned trunks'}
            showActions={false}
          />
        )}
      </>
    );

  return (
    <WorkspaceNavSidebar
      activeNav={activeNav}
      onNavigate={onNavigate}
      notificationCount={notificationCount}
      collapsed={collapsed}
      onToggleCollapse={onToggleCollapse}
      onBack={onBack}
      onForward={onForward}
      backEnabled={backEnabled}
      forwardEnabled={forwardEnabled}
      workspaceMode={ws !== null}
      hideBottomNav={showingSelectedTrunk || sourceSync}
    >
      {body}
    </WorkspaceNavSidebar>
  );
}

function ReviewBotIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
      <rect x="5" y="9" width="14" height="10" rx="2" />
      <path d="M12 5v4" />
      <circle cx="12" cy="4" r="1" />
      <path d="M9 13.5h.01M15 13.5h.01" />
    </svg>
  );
}

function workspaceSlug(name: string, repo: string | undefined): string {
  if (name.includes('/')) return name;
  return repo === undefined ? name : repo;
}

function threadStatus(status: string): 'active' | 'planning' | 'done' | 'sleep' {
  switch (status.toUpperCase()) {
    case 'RUNNING': return 'active';
    case 'AWAITING_REVIEW':
    case 'NEEDS_ATTENTION':
    case 'PAUSED': return 'planning';
    case 'COMPLETED':
    case 'ARCHIVED':
    case 'DONE': return 'done';
    default: return 'sleep';
  }
}

function WorkspaceGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="ws-destination-group">
      <div className="ws-destination-group__label">{label}</div>
      {children}
    </div>
  );
}

function WorkspaceDestination({
  navKey, label, icon, active, count, attention = false, warning = false,
  running = false, trailing, disabled = false, disabledTitle, onNavigate,
}: {
  navKey: WsNavKey;
  label: string;
  icon: ReactNode;
  active: boolean;
  count?: number;
  attention?: boolean;
  warning?: boolean;
  running?: boolean;
  trailing?: ReactNode;
  disabled?: boolean;
  disabledTitle?: string;
  onNavigate?: (key: WsNavKey) => void;
}) {
  return (
    <div
      className={`ws-destination${active ? ' active' : ''}${disabled ? ' disabled' : ''}`}
      role="button"
      aria-disabled={disabled}
      title={disabled ? disabledTitle ?? 'Still in progress' : undefined}
      tabIndex={disabled ? -1 : 0}
      onClick={() => { if (!disabled) onNavigate?.(navKey); }}
      onKeyDown={event => {
        if (!disabled && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault();
          onNavigate?.(navKey);
        }
      }}
    >
      <span className="ws-destination__icon" aria-hidden>{icon}</span>
      <span>{label}</span>
      {trailing ?? ((running || warning) && count !== undefined && count > 0 ? (
        <span className="ws-destination__meta">
          <span className={running ? 'ws-destination__running' : 'ws-destination__warning'} aria-hidden />
          <span className="ws-destination__count">{count}</span>
        </span>
      ) : (
        <>
          {running && <span className="ws-destination__running" aria-hidden />}
          {warning && <span className="ws-destination__warning" aria-hidden />}
          {count !== undefined && count > 0 && (
            <span className={`ws-destination__count${attention ? ' attention' : ''}`}>{count}</span>
          )}
        </>
      ))}
    </div>
  );
}

function WorkspaceCount({ children }: { children: ReactNode }) {
  return <span className="ws-destination__count">{children}</span>;
}

function WorkspaceSyncMeta({ children }: { children: ReactNode }) {
  return (
    <span className="ws-destination__meta">
      <span className="ws-destination__syncing" aria-hidden />
      <span className="ws-destination__count">{children}</span>
    </span>
  );
}

function WorkspaceIcon({ kind }: {
  kind: 'today' | 'trunks' | 'pull-requests' | 'issues' | 'backlog'
    | 'branches' | 'commits' | 'sessions' | 'memory' | 'insights';
}) {
  const path: Record<typeof kind, ReactNode> = {
    today: <path d="M22 12h-4l-3 9L9 3l-3 9H2" />,
    trunks: <><circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" /><path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" /></>,
    'pull-requests': <><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" /></>,
    issues: <><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="2.6"
      fill="currentColor" stroke="none" /></>,
    backlog: <><path d="M22 12h-6l-2 3h-4l-2-3H2" />
      <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></>,
    branches: <><path d="M6 3v12" /><circle cx="18" cy="6" r="2.6" />
      <circle cx="6" cy="18" r="2.6" /><path d="M18 9a9 9 0 0 1-9 9" /></>,
    commits: <><circle cx="12" cy="12" r="3" /><path d="M3 12h6M15 12h6" /></>,
    sessions: <><path d="m4 17 6-6-6-6M12 19h8" /></>,
    memory: <><path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />
      <path d="M12 3v18" /></>,
    insights: <><path d="M6 20v-8M12 20V4M18 20v-6" /></>,
  };
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={kind === 'memory' ? '1.6' : kind === 'today' || kind === 'insights' ? '1.8' : '1.7'}
      strokeLinecap="round" strokeLinejoin="round">
      {path[kind]}
    </svg>
  );
}
