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
import React, { Activity, useCallback, useEffect, useRef, useState } from 'react';
import AgentHome from '../agents/AgentHome';
import type { TaskAttention } from '../agents/attention';
import AgentTaskScreen from '../agents/AgentTaskScreen';
import AgentTasksView, { type TaskLaunch } from '../agents/AgentTasksView';
import ReviewTaskScreen from '../agents/ReviewTaskScreen';
import TaskOverview from '../agents/TaskOverview';
import ArtifactsPage from '../artifacts/ArtifactsPage';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import CherryPickTaskScreen from '../commits/CherryPickTaskScreen';
import { zoomReference } from '../githubReference';
import { needsYouKeys } from '../workitems/model';
import { visibleAttention } from '../home/attention';
import { JiraTicketLayer } from '../jira/JiraScreen';
import { jiraIssueLocation } from '../jira/contracts';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import { PullRequestWorkspaceProvider } from '../pulls/PullRequestWorkspace';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import MemoryPage from '../memory/MemoryPage';
import { settingsRoute } from '../settings/routes';
import { useZoom, ZoomActions, ZoomScrim } from '../Zoom';
import CreateEpicDialog from './CreateEpicDialog';
import EpicDrawer from './EpicDrawer';
import { EPIC_START, epicIdentity, lendEpicWorkspace, takeEpicStart, useConcludedEpics, useWorkItems } from './epics';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import { RelatedWorkspaceView } from './RelatedWorkspaceView';
import WorkItemsScreen from '../workitems/WorkItemsScreen';
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

type TaskPage = { kind: 'cherry-pick' | 'review' | 'task'; id: string };
// ponytail: the open task page and the two before it stay mounted; raise it if switching among more tasks matters more than their memory.
const KEPT_TASK_PAGES = 3;

type Props = {
  workspace: WorkspaceDto;
  taskAttention: TaskAttention;
  tab: WorkspaceTab;
  relatedWorkspaceId?: string | null;
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
};

export default function WorkspaceShell(props: Props) {
  const repository = `${props.workspace.monitoredOwner}/${props.workspace.monitoredRepo}`;
  if (props.tab === 'prs') return <PullsScreen key={props.workspace.id}
    repositoryScope={{ workspaceId: props.workspace.id, repository, server: props.workspace.hostingServer ?? undefined }}
    initialPr={props.initialPullRequestNumber === null ? undefined : { repo: repository, number: props.initialPullRequestNumber }}
    browserOpen={props.browserOpen}
    onSelectPullRequest={(reference, mode) => props.onSelectPullRequest(reference.number, mode)} />;
  return <WorkspaceContent {...props} />;
}

function WorkspaceContent({ workspace, taskAttention, tab, relatedWorkspaceId = null, onSelectTab, onWorkspaceChanged, initialIssueNumber, sourceIssueNumber = null, initialAgentTaskId, initialAgentReviewId = null, initialCherryPickId = null, browserOpen = false, onSelectAgentTask, onSelectIssue }: Props) {
  const [openCherryPick, setOpenCherryPick] = useState<string | null>(null);
  // The Epic whose view is open over Home, by the identity its work names it with.
  const [openEpic, setOpenEpic] = useState<string | null>(null);
  const [creatingEpic, setCreatingEpic] = useState(false);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [commitSourceSelection, setCommitSourceSelection] = useState<CommitSourceSelection>();
  // Every agent screen can show every task as a window; ⇧⌘\ toggles it, Escape puts it away.
  const [overviewOpen, setOverviewOpen] = useState(false);
  const [openReview, setOpenReview] = useState<{ id: string; repo: string; number: number } | null>(null);
  // The launch chosen when development was started from an issue; the composer seeds itself with it.
  const [startLaunch, setStartLaunch] = useState<TaskLaunch>();
  // The instruction an Epic's work prepared for the issue's composer.
  const [startDraft, setStartDraft] = useState<string>();
  const [startReplaces, setStartReplaces] = useState<string>();
  // Epic work without an issue or ticket: the task composer opens with its title, and the task it creates is the work's attempt. A draft
  // with no work item gets a key of its own, so a composer already on screen opens again with it.
  const [startWork, setStartWork] = useState<{ itemId?: string; draft: string; key: string }>();
  const draftStarts = useRef(0);
  // An Epic's Run starts its work at once: the issue or work item whose composer sends that message itself, until the composer takes it.
  const [startNow, setStartNow] = useState<number | string>();
  const startTaken = useCallback(() => setStartNow(undefined), []);
  const monitoredRepository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
  const agentScreens = tab === 'home' || tab === 'tasks';
  const taskDetail = tab === 'tasks' && (initialAgentTaskId !== null || initialAgentReviewId !== null);
  const openTaskPage: TaskPage | null = tab !== 'tasks' ? null
    : initialCherryPickId !== null ? { kind: 'cherry-pick', id: initialCherryPickId }
    : initialAgentReviewId !== null ? { kind: 'review', id: initialAgentReviewId }
    : initialAgentTaskId !== null ? { kind: 'task', id: initialAgentTaskId } : null;
  const openTaskKey = openTaskPage === null ? null : `${openTaskPage.kind}:${openTaskPage.id}`;
  const [composerOpened, setComposerOpened] = useState(false);
  const keepComposer = tab === 'tasks' && sourceIssueNumber === null && startWork === undefined && (openTaskPage === null || composerOpened);
  if (keepComposer !== composerOpened) setComposerOpened(keepComposer);
  const [keptTaskPages, setKeptTaskPages] = useState<TaskPage[]>([]);
  if (openTaskPage !== null && (keptTaskPages[0] === undefined || `${keptTaskPages[0].kind}:${keptTaskPages[0].id}` !== openTaskKey)) {
    setKeptTaskPages(pages => [openTaskPage, ...pages.filter(page => page.kind !== openTaskPage.kind || page.id !== openTaskPage.id)].slice(0, KEPT_TASK_PAGES));
  }
  // A cherry-pick execution zooms over the task list; an agent task owns the page instead.
  const zoomedCherryPick = tab === 'tasks' && openCherryPick !== null;
  const zoom = useZoom(useCallback(() => setOpenCherryPick(null), []), zoomedCherryPick);
  const attention = useWorkspaceAttention(workspace.id, monitoredRepository, taskAttention);
  const workItems = useWorkItems(workspace.id);
  const needsYou = needsYouKeys(visibleAttention(attention.candidates, attention.handled, Date.now()));
  const epics = [...attention.candidates.filter(item => item.isEpic === true), ...workItems.filter(item => item.isEpic)];
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
  // Commits browsed from another repository name their own; everything
  // else zooms the workspace's monitored one.
  const zoomPullRequest = useCallback((number: number, repo?: string) => {
    zoomReference({ kind: 'pull', repo: repo ?? monitoredRepository, number, workspaceId: workspace.id });
  }, [monitoredRepository, workspace.id]);

  // Cross-tab jumps are one-shot: leaving the target tab forgets them, so the
  // next visit starts on that tab's default view again.
  useEffect(() => {
    if (tab !== 'commits') setInitialCommitsRepo(null);
    if (tab !== 'tasks') {
      setKeptTaskPages([]);
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
      if ('issue' in start) {
        setStartNow(start.send === true ? start.issue : undefined);
        startIssue(start.issue, start.instruction, start.replaces);
        return;
      }
      const key = start.itemId ?? `draft-${++draftStarts.current}`;
      setStartNow(start.send === true ? key : undefined);
      setStartWork({ itemId: start.itemId, draft: start.draft, key });
      window.location.hash = workspaceRoute(workspace.id, 'tasks');
    };
    take();
    window.addEventListener(EPIC_START, take);
    return () => window.removeEventListener(EPIC_START, take);
  }, [startIssue, workspace.id]);

  const page = relatedWorkspaceId !== null && (tab === 'commits' || tab === 'context')
    ? <RelatedWorkspaceView key={relatedWorkspaceId} workspaceId={workspace.id} relatedWorkspaceId={relatedWorkspaceId} tab={tab} />
    : tab === 'commits' && !workspaceHasReadableCode(workspace)
    ? <WorkspaceUnavailable message="Repository code must be ready before this tab can open." />
    : renderPage();

  return <PullRequestWorkspaceProvider workspaceId={workspace.id} server={workspace.hostingServer ?? undefined} repository={monitoredRepository}>{page}</PullRequestWorkspaceProvider>;

  function renderPage(): React.JSX.Element {
    if (tab === 'commits') {
      return (
        <CommitsTab
          workspace={workspace}
          initialRepo={initialCommitsRepo ?? undefined}
          initialSourceSelection={commitSourceSelection}
          onSourceSelectionChange={setCommitSourceSelection}
          onOpenPullRequest={zoomPullRequest}
          onOpenSettings={() => { window.location.hash = settingsRoute('ws-repository', workspace.id); }}
        />
      );
    }
    if (tab === 'issues' || tab === 'jira') {
      return <WorkItemsScreen workspace={workspace} tab={tab} github={<WorkspaceIssuesScreen
        workspaceId={workspace.id}
        repository={monitoredRepository}
        initialIssueNumber={initialIssueNumber}
        onSelectIssue={onSelectIssue}
        onStartDevelopment={(number, launch) => {
          setStartLaunch(launch); setStartDraft(undefined); setStartReplaces(undefined);
          window.location.hash = workspaceIssueTaskRoute(workspace.id, number);
        }}
        onViewTask={link => { window.location.hash = workspaceAgentTaskRoute(link.workspaceId, link.taskId); }}
      />} />;
    }
    if (tab === 'artifacts') return <ArtifactsPage key={workspace.id} workspaceId={workspace.id} />;
    if (tab === 'context') {
      return <MemoryPage workspace={workspace} />;
    }
    if (tab === 'tasks') {
      // Recently opened task pages stay mounted but hidden, so switching back shows them at once; their effects
      // stop while hidden and run again when shown, which refreshes them.
      // Keyed by page: moving between two open tasks must not carry one task's transcript and
      // sequence cursor into the other, which would drop the second task's early events as
      // duplicates.
      const taskPages = keptTaskPages.map(page => {
        const key = `${page.kind}:${page.id}`;
        return <Activity key={key} mode={key === openTaskKey ? 'visible' : 'hidden'}>{renderTaskPage(page)}</Activity>;
      });
      // The agent view owns the page: every kind of task is listed there, above its composer.
      return stage(
        <>
          {taskPages}
          {/* Activity disconnects effects, which would discard the composer's unsent image attachments. */}
          {(openTaskPage === null || keepComposer) && <div style={{ display: openTaskPage === null ? 'contents' : 'none' }}>
          <AgentTasksView
            key={sourceIssueNumber ?? startWork?.key ?? 'new-task'}
            workspaceId={workspace.id}
            active={openTaskPage === null}
            repository={monitoredRepository}
            sourceIssueNumber={sourceIssueNumber}
            launch={sourceIssueNumber === null ? undefined : startLaunch}
            initialDraft={sourceIssueNumber === null ? startWork?.draft : startDraft}
            replaces={sourceIssueNumber === null ? undefined : startReplaces}
            workItemId={sourceIssueNumber === null ? startWork?.itemId : undefined}
            autoStart={startNow !== undefined && startNow === (sourceIssueNumber ?? startWork?.key)}
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
            onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
            onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }}
          />
          {zoomedCherryPick && !zoom.full && <ZoomScrim onClose={zoom.close} />}
          {openCherryPick !== null && (
            <div className={zoom.cardClass}>
              <CherryPickTaskScreen key={openCherryPick} executionId={openCherryPick} onBack={zoom.close}
                headerAction={<ZoomActions {...zoom} label="cherry-pick" className="agt-headbtn" />} onOpenPullRequest={zoomPullRequest} />
            </div>
          )}
          </div>}
        </>,
      );
    }
    // The design's Home: the question, three starting points, the open tasks as windows.
    // The Epic drawer comes first so its trigger is the next tab stop after the sidebar.
    return stage(<>
      {epic === undefined
        ? <EpicDrawer items={epics} concluded={concludedEpics} work={workItems} onOpen={item => {
          if ('acceptanceCriteria' in item) zoomReference({ kind: 'work', workspaceId: workspace.id, itemId: item.id });
          else setOpenEpic(epicIdentity(item));
        }} onCreate={() => setCreatingEpic(true)} />
        : 'acceptanceCriteria' in epic ? null : epic.jira
          ? <JiraTicketLayer key={openEpic} location={jiraIssueLocation(epic.jira)} onClose={() => setOpenEpic(null)} openOnWork />
          : <WorkspaceIssuesScreen key={openEpic} workspaceId={workspace.id} repository={epic.repo} initialIssueNumber={epic.number} onClose={() => setOpenEpic(null)}
            openOnWork />}
      {creatingEpic && <CreateEpicDialog workspaceId={workspace.id} marked={new Set(epics.map(epicIdentity))}
        jiraConnected={attention.jiraConnected} onClose={() => setCreatingEpic(false)} onCreated={(created) => {
          setCreatingEpic(false);
          // A marked issue or ticket opens once Home's list knows it as an Epic.
          if (created.kind === 'local') zoomReference({ kind: 'work', workspaceId: workspace.id, itemId: created.itemId });
          else if (created.epicId !== null) setOpenEpic(created.epicId);
        }} />}
      <AgentHome
        workspaceId={workspace.id}
        headerAction={overviewOpen ? undefined : overviewToggle}
        repository={monitoredRepository}
        taskAttention={taskAttention}
        attention={attention}
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

  function renderTaskPage(page: TaskPage): React.JSX.Element {
    const onBack = () => onSelectAgentTask(null, 'push');
    const headerAction = overviewOpen ? undefined : overviewToggle;
    if (page.kind === 'cherry-pick') return <CherryPickTaskScreen executionId={page.id} onBack={onBack} headerAction={headerAction} onOpenPullRequest={zoomPullRequest} />;
    if (page.kind === 'review') return <ReviewTaskScreen workspaceId={workspace.id} reviewId={page.id} onBack={onBack} headerAction={headerAction} browserOpen={browserOpen} />;
    // Not wrapped in ws-tab-page: the screen scrolls its transcript, not the whole page.
    return <AgentTaskScreen workspaceId={workspace.id} taskId={page.id} onBack={onBack} headerAction={headerAction} onOpenPullRequest={zoomPullRequest}
      workspace={workspace} onOpenSettings={() => { window.location.hash = settingsRoute('ws-agents', workspace.id); }} />;
  }
}

function WorkspaceUnavailable({ message }: { message: string }) {
  return <div className="ws-tab-page"><div className="ws-empty">{message}</div></div>;
}
