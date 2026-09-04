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
import React, { useCallback, useEffect, useState } from 'react';
import AgentHome from '../agents/AgentHome';
import AgentTaskScreen from '../agents/AgentTaskScreen';
import AgentTasksView from '../agents/AgentTasksView';
import TaskOverview from '../agents/TaskOverview';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import TasksView from '../commits/TasksView';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import WorkspaceSettings from './WorkspaceSettings';
import type { CherryPickExecution, WorkspaceDto } from '../types';
import {
  workspaceHasReadableCode,
  workspaceIsUpdating,
  type WorkspaceTab,
} from './model';
import '../css/workspaces.css';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, initialPullRequestNumber, initialIssueNumber, initialAgentTaskId, browserOpen = false, onSelectAgentTask, onSelectPullRequest, onSelectIssue, onRemoved }: {
  workspace: WorkspaceDto;
  tab: WorkspaceTab;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: WorkspaceDto) => void;
  initialPullRequestNumber: number | null;
  initialIssueNumber: number | null;
  /** Route-owned: which agent task is open, so a reload reopens the same one. */
  initialAgentTaskId: string | null;
  browserOpen?: boolean;
  onSelectAgentTask: (taskId: string | null, mode: NavigationMode) => void;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onSelectIssue: (number: number, mode: NavigationMode) => void;
  onRemoved: () => void;
}) {
  const [openExecutionId, setOpenExecutionId] = useState<string | null>(null);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [commitSourceSelection, setCommitSourceSelection] = useState<CommitSourceSelection>({ source: null, viewingSource: false });
  // Every agent screen can show every task as a window; ⇧⌘\ toggles it, Escape puts it away.
  const [overviewOpen, setOverviewOpen] = useState(false);
  const agentScreens = tab === 'home' || tab === 'tasks';
  useEffect(() => {
    if (!agentScreens) return;
    const onKey = (event: KeyboardEvent): void => {
      if (event.code === 'Backslash' && event.shiftKey && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        setOverviewOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [agentScreens]);
  useEffect(() => { if (!agentScreens) setOverviewOpen(false); }, [agentScreens]);
  const stage = (content: React.ReactNode) => (
    <div className="agt-stage">
      {content}
      <button
        type="button"
        className="agt-headbtn agt-overview-toggle"
        aria-pressed={overviewOpen}
        aria-label="Show all tasks"
        title={'Show all tasks (⇧⌘\\)'}
        onClick={() => setOverviewOpen((open) => !open)}
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <rect x="2" y="5" width="9" height="9" rx="1.6" stroke="currentColor" strokeWidth="1.3" />
          <path d="M5.5 5V3.6A1.6 1.6 0 0 1 7.1 2h5.3A1.6 1.6 0 0 1 14 3.6v5.3a1.6 1.6 0 0 1-1.6 1.6H11" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      </button>
      {overviewOpen && (
        <TaskOverview
          workspaceId={workspace.id}
          activeTaskId={initialAgentTaskId}
          onOpenTask={(taskId) => { setOverviewOpen(false); onSelectAgentTask(taskId, 'push'); }}
          onNewTask={() => { setOverviewOpen(false); onSelectTab('tasks'); }}
          onDismiss={() => setOverviewOpen(false)}
        />
      )}
    </div>
  );
  const selectScopedPullRequest = useCallback((reference: { number: number }, mode: NavigationMode) => {
    onSelectPullRequest(reference.number, mode);
  }, [onSelectPullRequest]);

  // Cross-tab jumps are one-shot: leaving the target tab forgets them, so the
  // next visit starts on that tab's default view again.
  useEffect(() => {
    if (tab !== 'commits') {
      setOpenExecutionId(null);
      setInitialCommitsRepo(null);
    }
  }, [tab]);

  useEffect(() => {
    if (!workspaceIsUpdating(workspace)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.getWorkspace(workspace.id)
        .then(details => onWorkspaceChanged(details.summary))
        .catch(() => {});
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [workspace, onWorkspaceChanged]);

  if (tab === 'commits' && !workspaceHasReadableCode(workspace)) {
    return <WorkspaceUnavailable message="Repository code must be ready before this tab can open." />;
  }
  if (tab === 'commits') {
    return (
      <CommitsTab
        key={openExecutionId ?? 'commits'}
        workspace={workspace}
        initialExecutionId={openExecutionId}
        initialRepo={initialCommitsRepo ?? undefined}
        initialSourceSelection={commitSourceSelection}
        onSourceSelectionChange={setCommitSourceSelection}
        onOpenPullRequest={number => {
          onSelectPullRequest(number, 'push');
        }}
        onOpenSettings={() => onSelectTab('settings')}
      />
    );
  }
  if (tab === 'prs') {
    const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
    return (
      <PullsScreen
        initialPr={initialPullRequestNumber === null
          ? undefined
          : { repo: repository, number: initialPullRequestNumber }}
        repositoryScope={{ workspaceId: workspace.id, repository }}
        browserOpen={browserOpen}
        onSelectPullRequest={selectScopedPullRequest}
      />
    );
  }
  if (tab === 'issues') {
    return <WorkspaceIssuesScreen
      workspaceId={workspace.id}
      repository={`${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
      initialIssueNumber={initialIssueNumber}
      onSelectIssue={onSelectIssue}
    />;
  }
  if (tab === 'tasks') {
    if (initialAgentTaskId !== null) {
      // Not wrapped in ws-tab-page: the screen scrolls its transcript, not the whole page.
      // Keyed by task: moving between two open tasks must not carry one task's transcript and
      // sequence cursor into the other, which would drop the second task's early events as
      // duplicates.
      return stage(
        <AgentTaskScreen
          key={initialAgentTaskId}
          workspaceId={workspace.id}
          taskId={initialAgentTaskId}
          onBack={() => onSelectAgentTask(null, 'push')}
          onOpenPullRequest={(number) => onSelectPullRequest(number, 'push')}
          onOpenSettings={() => onSelectTab('settings')}
        />,
      );
    }
    const cherryPicks = (
      <WorkspaceTasks
        workspace={workspace}
        embedded
        onOpenTask={execution => {
          setOpenExecutionId(execution.executionId);
          onSelectTab('commits');
        }}
        onNewCherryPick={() => {
          setOpenExecutionId(null);
          setInitialCommitsRepo('upstream');
          onSelectTab('commits');
        }}
      />
    );
    // The agent view owns the page: its composer stays anchored under both task sections.
    return stage(
      <AgentTasksView
        workspaceId={workspace.id}
        onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
        onOpenSettings={() => onSelectTab('settings')}
      >
        {cherryPicks}
      </AgentTasksView>,
    );
  }
  if (tab === 'settings') {
    return (
      <WorkspaceSettings
        workspace={workspace}
        onWorkspaceChanged={onWorkspaceChanged}
        onRemoved={onRemoved}
      />
    );
  }
  // The design's Home: the question, three starting points, the open tasks as windows.
  return stage(
    <AgentHome
      workspaceId={workspace.id}
      repository={`${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
      onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
      onOpenSettings={() => onSelectTab('settings')}
      onShowAll={() => setOverviewOpen(true)}
    />,
  );
}

function WorkspaceUnavailable({ message }: { message: string }) {
  return <div className="ws-tab-page"><div className="ws-empty">{message}</div></div>;
}

function WorkspaceTasks({ workspace, embedded = false, onOpenTask, onNewCherryPick }: {
  workspace: WorkspaceDto;
  embedded?: boolean;
  onOpenTask: (execution: CherryPickExecution) => void;
  onNewCherryPick: () => void;
}) {
  return (
    <TasksView
      workspaceId={workspace.id}
      embedded={embedded}
      onOpenTask={onOpenTask}
      onNewCherryPick={onNewCherryPick}
    />
  );
}
