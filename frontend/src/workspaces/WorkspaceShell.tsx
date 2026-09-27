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
import AgentTasksView, { type TaskLaunch } from '../agents/AgentTasksView';
import ReviewTaskScreen from '../agents/ReviewTaskScreen';
import TaskOverview from '../agents/TaskOverview';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import ProgressView from '../commits/ProgressView';
import TasksView from '../commits/TasksView';
import { zoomReference } from '../githubReference';
import JiraScreen from '../jira/JiraScreen';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { settingsRoute } from '../settings/routes';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import type { CherryPickExecution, WorkspaceDto } from '../types';
import {
  workspaceAgentTaskRoute,
  workspaceIssueTaskRoute,
  workspaceHasReadableCode,
  workspaceIsUpdating,
  workspaceReviewTaskRoute,
  type WorkspaceTab,
} from './model';
import '../css/workspaces.css';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, initialPullRequestNumber, initialIssueNumber, sourceIssueNumber = null, initialAgentTaskId, initialAgentReviewId = null, browserOpen = false, onSelectAgentTask, onSelectPullRequest, onSelectIssue }: {
  workspace: WorkspaceDto;
  tab: WorkspaceTab;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: WorkspaceDto) => void;
  initialPullRequestNumber: number | null;
  initialIssueNumber: number | null;
  sourceIssueNumber?: number | null;
  /** Route-owned: which agent task is open, so a reload reopens the same one. */
  initialAgentTaskId: string | null;
  initialAgentReviewId?: string | null;
  browserOpen?: boolean;
  onSelectAgentTask: (taskId: string | null, mode: NavigationMode) => void;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onSelectIssue: (number: number, mode: NavigationMode) => void;
}) {
  const [openCherryPick, setOpenCherryPick] = useState<string | null>(null);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [commitSourceSelection, setCommitSourceSelection] = useState<CommitSourceSelection>();
  // Every agent screen can show every task as a window; ⇧⌘\ toggles it, Escape puts it away.
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [openReview, setOpenReview] = useState<{ id: string; repo: string; number: number } | null>(null);
  // The launch chosen when development was started from an issue; the composer seeds itself with it.
  const [startLaunch, setStartLaunch] = useState<TaskLaunch>();
  const monitoredRepository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const agentScreens = tab === 'home' || tab === 'tasks';
  const taskDetail = tab === 'tasks' && (initialAgentTaskId !== null || initialAgentReviewId !== null);
  // A cherry-pick execution zooms over the task list; an agent task owns the page instead.
  const zoomedCherryPick = tab === 'tasks' && openCherryPick !== null;
  const zoom = useZoom(useCallback(() => setOpenCherryPick(null), []), zoomedCherryPick);
  const openReviewWorkspace = (review: AgentReviewDto): void => {
    setOpenReview(null);
    window.location.hash = workspaceReviewTaskRoute(review.workspaceId, review.id);
  };
  useEffect(() => {
    if (!agentScreens || browserOpen) return;
    const onKey = (event: KeyboardEvent): void => {
      if (event.code === 'Backslash' && event.shiftKey && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        setOverviewOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [agentScreens, browserOpen]);
  useEffect(() => { if (!agentScreens) setOverviewOpen(false); }, [agentScreens]);
  const overviewToggle = (
    <button
      type="button"
      className={`agt-headbtn${taskDetail && !overviewOpen ? '' : ' agt-overview-toggle'}`}
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
  );
  const stage = (content: React.ReactNode) => (
    <div className="agt-stage">
      {content}
      {overviewOpen && overviewToggle}
      {overviewOpen && (
        <TaskOverview
          workspaceId={workspace.id}
          activeTaskId={initialAgentTaskId}
          onOpenTask={(taskId) => { setOverviewOpen(false); onSelectAgentTask(taskId, 'push'); }}
          onNewTask={() => { setOverviewOpen(false); onSelectTab('tasks'); }}
          onDismiss={() => setOverviewOpen(false)}
        />
      )}
      {openReview !== null && (
        <PullsScreen
          key={openReview.id}
          initialPr={openReview}
          initialReviewId={openReview.id}
          repositoryScope={{ workspaceId: workspace.id, repository: openReview.repo }}
          browserOpen={browserOpen}
          onClose={() => setOpenReview(null)}
        />
      )}
    </div>
  );
  const selectScopedPullRequest = useCallback((reference: { number: number }, mode: NavigationMode) => {
    onSelectPullRequest(reference.number, mode);
  }, [onSelectPullRequest]);

  // Commits browsed from a remote source name their own repository; everything
  // else zooms the workspace's monitored one.
  const zoomPullRequest = useCallback((number: number, repo?: string) => {
    zoomReference({ kind: 'pull', repo: repo ?? monitoredRepository, number, workspaceId: workspace.id });
  }, [monitoredRepository, workspace.id]);

  // Cross-tab jumps are one-shot: leaving the target tab forgets them, so the
  // next visit starts on that tab's default view again.
  useEffect(() => {
    if (tab !== 'commits') setInitialCommitsRepo(null);
    if (tab !== 'tasks') setOpenCherryPick(null);
  }, [tab]);

  useEffect(() => {
    if (!workspaceIsUpdating(workspace)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.getWorkspace(workspace.id)
        .then(onWorkspaceChanged)
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
        workspace={workspace}
        initialRepo={initialCommitsRepo ?? undefined}
        initialSourceSelection={commitSourceSelection}
        onSourceSelectionChange={setCommitSourceSelection}
        onOpenPullRequest={zoomPullRequest}
        onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
      />
    );
  }
  if (tab === 'prs') {
    return (
      <PullsScreen
        initialPr={initialPullRequestNumber === null
          ? undefined
          : { repo: monitoredRepository, number: initialPullRequestNumber }}
        repositoryScope={{ workspaceId: workspace.id, repository: monitoredRepository }}
        browserOpen={browserOpen}
        onSelectPullRequest={selectScopedPullRequest}
      />
    );
  }
  if (tab === 'issues') {
    return <WorkspaceIssuesScreen
      workspaceId={workspace.id}
      repository={monitoredRepository}
      initialIssueNumber={initialIssueNumber}
      onSelectIssue={onSelectIssue}
      onStartDevelopment={(number, launch) => { setStartLaunch(launch); window.location.hash = workspaceIssueTaskRoute(workspace.id, number); }}
      onViewTask={link => { window.location.hash = workspaceAgentTaskRoute(link.workspaceId, link.taskId); }}
    />;
  }
  if (tab === 'jira') {
    return <JiraScreen workspace={workspace} browserOpen={browserOpen} />;
  }
  if (tab === 'tasks') {
    if (initialAgentReviewId !== null) return stage(<ReviewTaskScreen
      key={initialAgentReviewId}
      workspaceId={workspace.id}
      reviewId={initialAgentReviewId}
      onBack={() => onSelectAgentTask(null, 'push')}
      headerAction={overviewOpen ? undefined : overviewToggle}
      browserOpen={browserOpen}
    />);
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
          headerAction={overviewOpen ? undefined : overviewToggle}
          onOpenPullRequest={zoomPullRequest}
          workspace={workspace}
          onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
        />,
      );
    }
    const cherryPicks = (
      <WorkspaceTasks
        workspace={workspace}
        embedded
        onOpenTask={execution => setOpenCherryPick(execution.executionId)}
        onNewCherryPick={() => {
          setInitialCommitsRepo('upstream');
          onSelectTab('commits');
        }}
      />
    );
    // The agent view owns the page: its composer stays anchored under both task sections.
    return stage(
      <>
        <AgentTasksView
          key={sourceIssueNumber ?? 'new-task'}
          workspaceId={workspace.id}
          sourceIssueNumber={sourceIssueNumber}
          launch={sourceIssueNumber === null ? undefined : startLaunch}
          onOpenCreatedTask={task => { window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); }}
          headerAction={overviewOpen || zoomedCherryPick ? undefined : overviewToggle}
          onOpenReview={review => review.number === null ? openReviewWorkspace(review) : setOpenReview({ id: review.id, repo: review.repo, number: review.number })}
          onOpenReviewWorkspace={openReviewWorkspace}
          onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
          onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
        >
          {cherryPicks}
        </AgentTasksView>
        {zoomedCherryPick && !zoom.full && <ZoomScrim onClose={zoom.close} />}
        {openCherryPick !== null && (
          <div className={zoom.cardClass}>
            <div className="cmt-root">
              <ProgressView
                key={openCherryPick}
                executionId={openCherryPick}
                workspaceId={workspace.id}
                upstreamSlug={workspace.upstreamRepositoryId === null ? null : `${workspace.upstreamOwner}/${workspace.upstreamRepo}`}
                onBackToTasks={zoom.close}
                headerAction={<ZoomActions {...zoom} label="cherry-pick" className="cmt-btn-plain" />}
                onStartAnotherRange={() => {
                  setOpenCherryPick(null);
                  setInitialCommitsRepo('upstream');
                  onSelectTab('commits');
                }}
              />
            </div>
          </div>
        )}
      </>,
    );
  }
  // The design's Home: the question, three starting points, the open tasks as windows.
  return stage(
    <AgentHome
      workspaceId={workspace.id}
      headerAction={overviewOpen ? undefined : overviewToggle}
      repository={monitoredRepository}
      onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
      onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
      onShowAll={() => setOverviewOpen(true)}
      onOpenPullRequests={() => onSelectTab('prs')}
      onOpenPullRequest={zoomPullRequest}
      onOpenIssue={(number) => zoomReference({ kind: 'issue', repo: monitoredRepository, number, workspaceId: workspace.id })}
      onOpenIssues={() => onSelectTab('issues')}
      onStartIssue={number => { window.location.hash = workspaceIssueTaskRoute(workspace.id, number); }}
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
