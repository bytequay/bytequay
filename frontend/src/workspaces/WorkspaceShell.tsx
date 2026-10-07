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
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import AgentHome from '../agents/AgentHome';
import { useTaskAttention } from '../agents/attention';
import type { TaskListItem } from '../agents/taskList';
import AgentTaskScreen from '../agents/AgentTaskScreen';
import AgentTasksView, { type TaskLaunch } from '../agents/AgentTasksView';
import ReviewTaskScreen from '../agents/ReviewTaskScreen';
import TaskOverview from '../agents/TaskOverview';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import ProgressView from '../commits/ProgressView';
import { zoomReference } from '../githubReference';
import { GoalLayer } from '../goals/GoalLayer';
import { goalHas, goalPicked, goalRef, needsYouKeys, numberedKey } from '../goals/model';
import { useGoals } from '../goals/useGoals';
import type { GoalToggle } from '../home/AttentionList';
import { visibleAttention, type AttentionItem } from '../home/attention';
import JiraScreen, { JiraTicketLayer } from '../jira/JiraScreen';
import { jiraIssueLocation } from '../jira/contracts';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import MemoryPage from '../memory/MemoryPage';
import type { SpecCheckRequest } from '../repository-context/specChecks';
import { settingsRoute } from '../settings/routes';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import EpicDrawer from './EpicDrawer';
import { EPIC_START, epicIdentity, lendEpicWorkspace, takeEpicStart, useConcludedEpics } from './epics';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import { useWorkspaceAttention } from './workspaceAttention';
import type { WorkspaceDto } from '../types';
import {
  workspaceAgentTaskRoute,
  workspaceIssueTaskRoute,
  workspaceHasReadableCode,
  workspaceIsUpdating,
  workspaceReviewTaskRoute,
  workspaceRoute,
  type WorkspaceTab,
} from './model';
import '../css/workspaces.css';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, initialPullRequestNumber, initialIssueNumber, sourceIssueNumber = null, initialAgentTaskId, initialAgentReviewId = null, initialCherryPickId = null, browserOpen = false, onSelectAgentTask, onSelectPullRequest, onSelectIssue }: {
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
  initialCherryPickId?: string | null;
  browserOpen?: boolean;
  onSelectAgentTask: (taskId: string | null, mode: NavigationMode) => void;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onSelectIssue: (number: number, mode: NavigationMode) => void;
}) {
  const [openCherryPick, setOpenCherryPick] = useState<string | null>(null);
  // The Epic whose view is open over Home, by the identity its work names it with.
  const [openEpic, setOpenEpic] = useState<string | null>(null);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [commitSourceSelection, setCommitSourceSelection] = useState<CommitSourceSelection>();
  // Every agent screen can show every task as a window; ⇧⌘\ toggles it, Escape puts it away.
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [openReview, setOpenReview] = useState<{ id: string; repo: string; number: number } | null>(null);
  // The launch chosen when development was started from an issue; the composer seeds itself with it.
  const [startLaunch, setStartLaunch] = useState<TaskLaunch>();
  // The instruction a Goal's work card prepared for the issue's composer.
  const [startDraft, setStartDraft] = useState<string>();
  const [startReplaces, setStartReplaces] = useState<string>();
  // Epic work without an issue or ticket: the task composer opens with its title, and the task it creates is the work's attempt.
  const [startWork, setStartWork] = useState<{ itemId: string; draft: string }>();
  // An Epic's Run starts its work at once: the issue or work item whose composer sends that message itself, until the composer takes it.
  const [startNow, setStartNow] = useState<number | string>();
  const startTaken = useCallback(() => setStartNow(undefined), []);
  // A spec check a Goal card asked the Memory page to show.
  const [specCheck, setSpecCheck] = useState<SpecCheckRequest>();
  const specShown = useCallback(() => setSpecCheck(undefined), []);
  const monitoredRepository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const agentScreens = tab === 'home' || tab === 'tasks';
  const taskDetail = tab === 'tasks' && (initialAgentTaskId !== null || initialAgentReviewId !== null);
  // A cherry-pick execution zooms over the task list; an agent task owns the page instead.
  const zoomedCherryPick = tab === 'tasks' && openCherryPick !== null;
  const zoom = useZoom(useCallback(() => setOpenCherryPick(null), []), zoomedCherryPick);
  // Read once for every page of the workspace: Home lists what needs you, and the goal counts it.
  const taskWorkspaces = useMemo(() => [{ id: workspace.id, name: '', repository: monitoredRepository }], [monitoredRepository, workspace.id]);
  const taskAttention = useTaskAttention(taskWorkspaces);
  const attention = useWorkspaceAttention(workspace.id, monitoredRepository, taskAttention);
  const goals = useGoals(workspace.id);
  const needsYou = needsYouKeys(visibleAttention(attention.candidates, attention.handled, Date.now()));
  const epics = attention.candidates.filter(item => item.isEpic === true);
  const concludedEpics = useConcludedEpics(workspace.id, epics.map(epicIdentity).join(' '));
  // Unmarked while open, the Epic's view closes with it.
  const epic = openEpic === null ? undefined : epics.find(item => epicIdentity(item) === openEpic);
  // An Epic's Work tab opened anywhere, even in a layer over this page, reads these; keyed so an equal answer lends nothing new.
  const needsYouKey = [...needsYou].sort().join('\n');
  const epicTitlesKey = JSON.stringify(epics.map(item => [epicIdentity(item), item.title]));
  useEffect(() => {
    lendEpicWorkspace(workspace.id, {
      needsYou: new Set(needsYouKey === '' ? [] : needsYouKey.split('\n')),
      epicTitles: new Map(JSON.parse(epicTitlesKey) as [string, string][]),
      repository: monitoredRepository,
    });
    return () => lendEpicWorkspace(workspace.id, null);
  }, [epicTitlesKey, monitoredRepository, needsYouKey, workspace.id]);
  const goalToggle = useCallback((item: AttentionItem): GoalToggle | null => {
    const pinned = goals.pinned;
    const ref = goalRef(item);
    if (pinned === null || ref === null) return null;
    const inGoal = goalHas(pinned, ref);
    return { inGoal, toggle: () => (inGoal ? goals.removeItem(pinned.id, ref.kind, ref.key) : goals.add(pinned.id, ref.kind, ref.key)),
      picked: goalPicked(pinned, monitoredRepository, ref) };
  }, [goals, monitoredRepository]);
  // A task is in the goal itself, or through the pull request it works on.
  const itemInGoal = (item: TaskListItem): boolean => (item.task !== null && goalHas(goals.pinned, { kind: 'TASK', key: item.task.id }))
    || item.links.some((link) => link.source === 'pr' && goalHas(goals.pinned, { kind: 'PULL_REQUEST', key: numberedKey(monitoredRepository, Number(link.key.slice(1))) }));
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
    if (tab !== 'tasks') {
      setOpenCherryPick(null);
      setStartWork(undefined);
      setStartNow(undefined);
    }
    if (tab !== 'home') setOpenEpic(null);
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

  const startIssue = useCallback((number: number, instruction: string, replaces?: string): void => {
    setStartLaunch(undefined);
    setStartDraft(instruction);
    setStartReplaces(replaces);
    window.location.hash = workspaceIssueTaskRoute(workspace.id, number);
  }, [workspace.id]);

  // Work an Epic's Work tab starts, from Home or from a layer over any page; one waiting since before this page opened starts now.
  useEffect(() => {
    const take = (): void => {
      const start = takeEpicStart(workspace.id);
      if (start === null) return;
      const target = 'issue' in start ? start.issue : start.itemId;
      setStartNow(start.send === true ? target : undefined);
      if ('issue' in start) {
        startIssue(start.issue, start.instruction, start.replaces);
        return;
      }
      setStartWork({ itemId: start.itemId, draft: start.draft });
      window.location.hash = workspaceRoute(workspace.id, 'tasks');
    };
    take();
    window.addEventListener(EPIC_START, take);
    return () => window.removeEventListener(EPIC_START, take);
  }, [startIssue, workspace.id]);

  // Every page of the workspace carries the pinned goal; an open task or review keeps its own spot for the pill.
  return (
    <GoalLayer
      page={taskDetail ? 'task' : tab}
      workspaceId={workspace.id}
      repository={monitoredRepository}
      goals={goals}
      needsYou={needsYou}
      onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
      onStartIssue={startIssue}
      onSpecCheck={(spec) => {
        setSpecCheck(spec);
        window.location.hash = workspaceRoute(workspace.id, 'context');
      }}
    >
      {tab === 'commits' && !workspaceHasReadableCode(workspace)
        ? <WorkspaceUnavailable message="Repository code must be ready before this tab can open." />
        : renderPage()}
    </GoalLayer>
  );

  function renderPage(): React.JSX.Element {
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
        onStartDevelopment={(number, launch) => {
          setStartLaunch(launch); setStartDraft(undefined); setStartReplaces(undefined);
          window.location.hash = workspaceIssueTaskRoute(workspace.id, number);
        }}
        onViewTask={link => { window.location.hash = workspaceAgentTaskRoute(link.workspaceId, link.taskId); }}
      />;
    }
    if (tab === 'jira') {
      return <JiraScreen workspace={workspace} />;
    }
    if (tab === 'context') {
      return <MemoryPage workspace={workspace} spec={specCheck} onSpecShown={specShown} />;
    }
    if (tab === 'tasks') {
      if (initialCherryPickId !== null) return <div className="cmt-root"><ProgressView
        key={initialCherryPickId} executionId={initialCherryPickId} workspaceId={workspace.id}
        upstreamSlug={workspace.upstreamRepositoryId === null ? null : monitoredRepository}
        onBackToTasks={() => onSelectAgentTask(null, 'push')}
        onStartAnotherRange={() => onSelectTab('commits')} /></div>;
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
      // The agent view owns the page: every kind of task is listed there, above its composer.
      return stage(
        <>
          <AgentTasksView
            key={sourceIssueNumber ?? startWork?.itemId ?? 'new-task'}
            workspaceId={workspace.id}
            repository={monitoredRepository}
            sourceIssueNumber={sourceIssueNumber}
            launch={sourceIssueNumber === null ? undefined : startLaunch}
            initialDraft={sourceIssueNumber === null ? startWork?.draft : startDraft}
            replaces={sourceIssueNumber === null ? undefined : startReplaces}
            workItemId={sourceIssueNumber === null ? startWork?.itemId : undefined}
            autoStart={startNow !== undefined && startNow === (sourceIssueNumber ?? startWork?.itemId)}
            onAutoStart={startTaken}
            onOpenCreatedTask={task => {
              setStartWork(undefined);
              window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id);
            }}
            headerAction={overviewOpen || zoomedCherryPick ? undefined : overviewToggle}
            onOpenReview={review => review.number === null ? openReviewWorkspace(review) : setOpenReview({ id: review.id, repo: review.repo, number: review.number })}
            onOpenReviewWorkspace={openReviewWorkspace}
            onOpenCherryPick={execution => setOpenCherryPick(execution.executionId)}
            onOpenPullRequest={zoomPullRequest}
            onNewItem={type => {
              // A cherry-pick starts from a range of upstream commits; a review or CI fix from a pull request.
              if (type === 'cherry') setInitialCommitsRepo('upstream');
              onSelectTab(type === 'cherry' ? 'commits' : 'prs');
            }}
            taskAttention={taskAttention}
            inGoal={itemInGoal}
            onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
            onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
          />
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
    // The Epic drawer comes first so its trigger is the next tab stop after the sidebar.
    return stage(<>
      {epic === undefined
        ? <EpicDrawer items={attention.candidates} concluded={concludedEpics} work={goals.workItems} onOpen={item => setOpenEpic(epicIdentity(item))} />
        : epic.jira
          ? <JiraTicketLayer key={openEpic} location={jiraIssueLocation(epic.jira)} onClose={() => setOpenEpic(null)} openOnWork />
          : <WorkspaceIssuesScreen key={openEpic} workspaceId={workspace.id} repository={epic.repo} initialIssueNumber={epic.number} onClose={() => setOpenEpic(null)}
            openOnWork />}
      <AgentHome
        workspaceId={workspace.id}
        headerAction={overviewOpen ? undefined : overviewToggle}
        repository={monitoredRepository}
        taskAttention={taskAttention}
        attention={attention}
        goal={goalToggle}
        inGoal={(task) => goalHas(goals.pinned, { kind: 'TASK', key: task.id })}
        onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
        onOpenReview={openReviewWorkspace}
        onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
        onShowAll={() => setOverviewOpen(true)}
        onOpenPullRequest={zoomPullRequest}
        onOpenIssue={(number) => zoomReference({ kind: 'issue', repo: monitoredRepository, number, workspaceId: workspace.id })}
        onStartIssue={number => { window.location.hash = workspaceIssueTaskRoute(workspace.id, number); }}
      />
    </>);
  }
}

function WorkspaceUnavailable({ message }: { message: string }) {
  return <div className="ws-tab-page"><div className="ws-empty">{message}</div></div>;
}
