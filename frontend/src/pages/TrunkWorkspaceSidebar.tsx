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
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { FolderIcon } from '../diffTreeIcons';
import type { ThreadDto, WorkUnitTaskDto } from '../types';
import { WorkspaceNavSidebar } from '../ui/workspace';
import type { TaskNavRow, WsNavKey } from '../ui/workspace';
import {
  ChevronIcon,
  PullRequestBranchIcon,
  SidebarRow,
  TrunkLineIcon,
  WorkspaceSwitcherCard,
} from '../ui/workspace/WorkspacePageChrome';
import { taskLabel } from '../threads/taskLabel';

const EXPANSION_KEY = 'byq.trunkExpanded.v1';

type ExpansionStore = {
  a?: Record<string, boolean>;
  b?: Record<string, boolean>;
  workspaceOpen?: boolean;
};

export function TrunkWorkspaceSidebar({
  workspaceName,
  repository,
  threads,
  selectedThreadId,
  selectedTasks,
  counts,
  notificationCount,
  activeNav,
  collapsed,
  onToggleCollapse,
  onBack,
  onForward,
  backEnabled,
  forwardEnabled,
  onNavigate,
  onOpenThread,
  onOpenTask,
  onSwitchWorkspace,
  onNewThread,
}: {
  workspaceName: string;
  repository: string;
  threads: ThreadDto[];
  selectedThreadId?: string;
  selectedTasks: TaskNavRow[];
  counts?: {
    todayNeedsYou: number;
    pullRequests: number;
    issues: number | null;
    backlog: number;
    branches: number | null;
    sessions: number;
  };
  notificationCount?: number;
  activeNav?: WsNavKey;
  collapsed: boolean;
  onToggleCollapse?: () => void;
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onNavigate?: (key: WsNavKey) => void;
  onOpenThread?: (id: string) => void;
  onOpenTask?: (threadId: string, taskId: string) => void;
  onSwitchWorkspace?: () => void;
  onNewThread?: () => void;
}) {
  const [expanded, setExpanded] = useState<Record<string, boolean> | null>(() => readExpansion().b ?? null);
  const [workspaceOpen, setWorkspaceOpen] = useState(() => readExpansion().workspaceOpen ?? false);
  const [tasksByThread, setTasksByThread] = useState<Record<string, WorkUnitTaskDto[]>>({});

  const effectiveExpanded = useMemo(() => {
    const selected = threads.find(thread => thread.id === selectedThreadId);
    if (selected === undefined) return expanded ?? {};
    if (expanded === null) return { [selected.title]: true };
    return selected.title in expanded ? expanded : { ...expanded, [selected.title]: true };
  }, [expanded, selectedThreadId, threads]);
  const expandedIds = threads
    .filter(thread => effectiveExpanded[thread.title] === true)
    .map(thread => thread.id);
  const taskIdsToLoad = threads
    .filter(thread => expandedIds.includes(thread.id)
      || (thread.taskCount === undefined && tasksByThread[thread.id] === undefined))
    .map(thread => thread.id);
  const taskIdsKey = taskIdsToLoad.join('\u0000');

  useEffect(() => {
    const ids = taskIdsKey.length === 0 ? [] : taskIdsKey.split('\u0000');
    if (ids.length === 0 || window.bridge?.listTasksForThread === undefined) return undefined;
    let cancelled = false;
    const load = async () => {
      const rows = await Promise.all(ids.map(async id => {
        try {
          return [id, await window.bridge.listTasksForThread(id)] as const;
        }
        catch {
          return null;
        }
      }));
      if (cancelled) return;
      setTasksByThread(current => {
        const next = { ...current };
        for (const row of rows) if (row !== null) next[row[0]] = row[1];
        return next;
      });
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 8000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
    // A stable string avoids restarting the poll when the parent replaces the
    // thread array with an equivalent response on each workspace refresh.
  }, [taskIdsKey]);

  const toggleThread = (thread: ThreadDto, hasTasks: boolean) => {
    if (thread.id !== selectedThreadId) onOpenThread?.(thread.id);
    if (!hasTasks) return;
    const next = { ...effectiveExpanded, [thread.title]: !effectiveExpanded[thread.title] };
    setExpanded(next);
    writeExpansion({ b: next });
  };

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
      workspaceMode
    >
      <WorkspaceSwitcherCard name={workspaceName} repository={repository} onSwitch={onSwitchWorkspace} />
      <div className="trunk-page-v2-nav__today">
        <SidebarRow
          icon={<TodayIcon />}
          trailing={counts !== undefined && counts.todayNeedsYou > 0
            ? <span className="trunk-page-v2-nav__attention">{counts.todayNeedsYou}</span>
            : undefined}
          onClick={() => onNavigate?.('today')}
        >Today</SidebarRow>
      </div>

      <div className="trunk-page-v2-nav__section-head">
        <strong>TRUNKS</strong>
        <span>{threads.length}</span>
        <button type="button" aria-label="New trunk" title="New trunk" onClick={onNewThread}>
          <PlusIcon />
        </button>
      </div>
      <div className="trunk-page-v2-nav__tree">
        {threads.map(thread => {
          const liveTasks = tasksByThread[thread.id];
          const fallbackTasks = thread.id === selectedThreadId ? selectedTasks : [];
          const taskCount = liveTasks?.length ?? thread.taskCount ?? fallbackTasks.length;
          const hasTasks = taskCount > 0;
          const open = effectiveExpanded[thread.title] === true && hasTasks;
          const active = thread.id === selectedThreadId;
          return (
            <div className="trunk-page-v2-nav__trunk" key={thread.id}>
              <button
                type="button"
                className={active ? 'is-active' : ''}
                onClick={() => toggleThread(thread, hasTasks)}
                aria-expanded={hasTasks ? open : undefined}
              >
                <span className="trunk-page-v2-nav__directory">
                  <FolderIcon open={open} />
                </span>
                <span className="trunk-page-v2-nav__trunk-name">{thread.title}</span>
                <span className={`trunk-page-v2-nav__trunk-icon${thread.status === 'RUNNING' ? ' is-running' : ''}`}>
                  <TrunkLineIcon />
                </span>
                {!open && thread.unread === true && <i title="Needs you" />}
              </button>
              {open && (
                <div className="trunk-page-v2-nav__tasks">
                  {liveTasks !== undefined
                    ? liveTasks.map(task => (
                        <TaskRow key={task.id} task={task} onOpen={() => onOpenTask?.(thread.id, task.id)} />
                      ))
                    : fallbackTasks.map(task => (
                        <FallbackTaskRow key={task.id} task={task} onOpen={() => onOpenTask?.(thread.id, task.id)} />
                      ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="trunk-page-v2-nav__workspace">
        <button type="button" className="trunk-page-v2-nav__workspace-toggle"
          aria-expanded={workspaceOpen} onClick={() => setWorkspaceOpen(open => {
            const next = !open;
            writeExpansion({ workspaceOpen: next });
            return next;
          })}>
          <span className={workspaceOpen ? 'is-open' : ''}><ChevronIcon size={11} /></span>
          <strong>WORKSPACE</strong>
        </button>
        {workspaceOpen && (
          <div className="trunk-page-v2-nav__workspace-items">
            <WorkspaceItem icon={<PullRequestsIcon />} label="Pull requests"
              onClick={() => onNavigate?.('pull-requests')} />
            <WorkspaceItem icon={<IssueIcon />} label="Issues"
              onClick={() => onNavigate?.('issues')} />
            <WorkspaceItem icon={<BacklogIcon />} label="Backlog" disabled
              disabledTitle="Backlog is managed inside each trunk"
              onClick={() => onNavigate?.('backlog')} />
            <WorkspaceItem icon={<BranchIcon />} label="Branches"
              onClick={() => onNavigate?.('branches')} />
            <WorkspaceItem icon={<CommitIcon />} label="Commits" onClick={() => onNavigate?.('commits')} />
            <WorkspaceItem icon={<SessionIcon />} label="Sessions" disabled
              onClick={() => onNavigate?.('sessions')} />
            <WorkspaceItem icon={<MemoryIcon />} label="Memory" disabled
              onClick={() => onNavigate?.('memory')} />
            <WorkspaceItem icon={<InsightsIcon />} label="Insights" onClick={() => onNavigate?.('insights')} />
          </div>
        )}
      </div>
    </WorkspaceNavSidebar>
  );
}

function TaskRow({ task, onOpen }: { task: WorkUnitTaskDto; onOpen: () => void }) {
  const state = taskState(task);
  return (
    <button type="button" onClick={onOpen}>
      <span className={`is-${state}`} aria-hidden>{taskStateIcon(state)}</span>
      <span>{taskLabel(task)}</span>
    </button>
  );
}

function FallbackTaskRow({ task, onOpen }: { task: TaskNavRow; onOpen: () => void }) {
  const state = task.pr === 'merged' ? 'merged'
    : task.pr !== undefined ? 'review'
      : task.dot === 'active' || task.dot === 'developing' ? 'running'
        : 'quiet';
  return (
    <button type="button" onClick={onOpen}>
      <span className={`is-${state}`} aria-hidden>{taskStateIcon(state)}</span>
      <span>{task.label}</span>
    </button>
  );
}

function taskState(task: WorkUnitTaskDto): 'merged' | 'review' | 'running' | 'error' | 'quiet' {
  if (task.status === 'COMPLETED' || task.prState?.toUpperCase() === 'MERGED') return 'merged';
  if (task.status === 'ERRORED') return 'error';
  if (task.prNumber !== null || task.status === 'IN_REVIEW' || task.status === 'AWAITING_REVIEW') return 'review';
  if (task.status === 'RUNNING') return 'running';
  return 'quiet';
}

function taskStateIcon(state: ReturnType<typeof taskState> | 'quiet'): ReactNode {
  const shared = {
    width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
    strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const,
  };
  if (state === 'merged') return <PullRequestBranchIcon size={12} />;
  if (state === 'review') return <svg {...shared}><path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12Z" /><circle cx="12" cy="12" r="2.6" /></svg>;
  if (state === 'running') return <svg {...shared} strokeWidth={2.2}><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></svg>;
  if (state === 'error') return <svg {...shared} strokeWidth={2.4}><path d="m6 6 12 12M18 6 6 18" /></svg>;
  return <span className="trunk-page-v2-nav__task-dot" />;
}

function WorkspaceItem({
  icon, label, disabled = false, disabledTitle, onClick,
}: {
  icon: ReactNode;
  label: string;
  disabled?: boolean;
  disabledTitle?: string;
  onClick: () => void;
}) {
  return (
    <SidebarRow icon={icon} disabled={disabled}
      title={disabled ? disabledTitle ?? 'Still in progress' : undefined} onClick={onClick}>
      {label}
    </SidebarRow>
  );
}

function readExpansion(): ExpansionStore {
  try {
    const raw = window.localStorage.getItem(EXPANSION_KEY);
    return raw === null ? {} : JSON.parse(raw) as ExpansionStore;
  }
  catch {
    return {};
  }
}

function writeExpansion(patch: Partial<ExpansionStore>) {
  try {
    const current = readExpansion();
    window.localStorage.setItem(EXPANSION_KEY, JSON.stringify({ ...current, ...patch }));
  }
  catch {
    // Expansion persistence is convenience only.
  }
}

function TodayIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2" /></svg>;
}

function PlusIcon() {
  return <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>;
}

function PullRequestsIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="5.5" r="2.4" /><circle cx="6" cy="18.5" r="2.4" /><circle cx="18" cy="18.5" r="2.4" /><path d="M6 8v8" /><path d="M11.5 5.5H15a3 3 0 0 1 3 3V16" /></svg>;
}

function IssueIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" /></svg>;
}

function BacklogIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></svg>;
}

function BranchIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" /><circle cx="18" cy="6" r="2.4" /><path d="M6 8.5v7" /><path d="M18 8.5a7 7 0 0 1-7 7" /></svg>;
}

function CommitIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><circle cx="12" cy="12" r="3.5" /><path d="M2 12h6.5M15.5 12H22" /></svg>;
}

function SessionIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="m4 17 6-6-6-6" /><path d="M12 19h8" /></svg>;
}

function MemoryIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="5" y="5" width="14" height="14" rx="2" /><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3" /></svg>;
}

function InsightsIcon() {
  return <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"><path d="M3 20h18M7 16v-5M12 16V8M17 16v-3" /></svg>;
}
