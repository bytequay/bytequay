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
import { useEffect, useState, type ReactNode } from 'react';
import type {
  DiffFileDto, ThreadCommitDto, ThreadMessageDto, WorkUnitTaskDto,
} from '../types';
import type { BrainFeedRow, StageConversationRow, StageDto } from '../types/brainView';
import type { LocalPRBundle } from '../types/localPr';
import { PullDetailBody } from '../pulls/PullDetailPane';
import { pullRowFromLocal } from '../pulls/localRow';
import { StageDetailPage } from '../pages/StageDetailPage';
import { TaskBrainPage } from '../pages/TaskBrainPage';
import { TaskChangedFilesCard } from '../pages/TaskChangedFilesCard';
import { stageFeed } from '../pages/stageConversationRow';
import { WorkspaceNavShell } from '../pages/WorkspaceNavShell';
import { TrunkPage } from '../pages/TrunkPage';
import { TrunkWorkspaceSidebar } from '../pages/TrunkWorkspaceSidebar';
import { TrunkFeed } from '../threads/TrunkFeed';
import { BrainFeed } from '../threads/brain/BrainFeed';
import { toTaskCard } from '../threads/taskCardData';
import { ActivityStrip, Conv, Headline, Spine, SpineNode, WorkFold } from '../ui/conv';
import { TaskSidebar } from '../ui/shell/TaskSidebar';
import type { LivePlanNode, LivePlanPhaseNode } from '../ui/shell/livePlanModel';
import type { TaskNavRow, WsNavKey } from '../ui/workspace';
import type { InboxItem } from '../home/inboxItems';
import InboxCard, { type InboxHandlers } from '../home/InboxCard';
import WorkspacesLandingPage from './WorkspacesLandingPage';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';
import WorkspaceCreationToasts from './WorkspaceCreationToasts';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceMemoryPage from './WorkspaceMemoryPage';
import WorkspaceNotificationsPage from './WorkspaceNotificationsPage';
import PullRequestBoardList from './PullRequestBoardList';
import WorkspaceRepoPage from './WorkspaceRepoPage';
import WorkspaceSessionsPage from './WorkspaceSessionsPage';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';
import WorkspaceShell, { type WorkspaceSection } from './WorkspaceShell';
import { WorkModelPill } from './WorkModelPill';
import {
  VISUAL_BACKLOG_KEY,
  VISUAL_BRANCH_NAME,
  VISUAL_DETAIL_PR_NUMBER,
  VISUAL_ISSUE_NUMBER,
  VISUAL_SESSION_ID,
  VISUAL_TRUNK_ID,
  VISUAL_WORKSPACE_ID,
  visualDashboardPrs,
  visualCreationReady,
  visualPullRequests,
  visualTasks,
  visualThreadMessages,
  visualThreads,
  visualWorkspaces,
} from './workspaceVisualFixtureData';

type Props = {
  frame: string;
};

const LOCKED_PAGE_FRAMES = new Set(['7a', '7b', '7c']);

const STUDY_WIDTH: Record<string, number> = {
  '3a': 1060,
  '3b': 1060,
  '3c': 1060,
  '3d': 1060,
  '3e': 1060,
  '3f': 1060,
  '3g': 1060,
  '3h': 1060,
  '3i': 1060,
  '3j': 1060,
  '4a': 1060,
  '4b': 1060,
  '4c': 1060,
  '4d': 1060,
  '4e': 1060,
  '4f': 900,
  '5b': 460,
  '5c': 1060,
  '5d': 900,
  '5e': 1240,
  '5f': 460,
  '6b': 1100,
  '6c': 1060,
  '6d': 460,
};

const STUDY_HEIGHT: Record<string, number> = {
  '3a': 523,
  '3b': 457,
  '3c': 628.046875,
  '3d': 390.5,
  '3e': 409.5,
  '3f': 215.5,
  '3g': 297.5,
  '3h': 334.9453125,
  '3i': 390.5,
  '3j': 333,
  '4a': 395.6875,
  '4b': 246.5,
  '4c': 370,
  '4d': 340.5,
  '4e': 483.9453125,
  '4f': 640,
  '5b': 355.09375,
  '5c': 363,
  '5d': 660,
  '5e': 849.5,
  '5f': 206,
  '6b': 460.5,
  '6c': 566.5,
  '6d': 183,
};

/**
 * Development-only rendering catalog used by the dependency-free Electron
 * visual gate. Every entry mounts the real production component with the
 * static DTOs from the matching design frame; the archived HTML is never
 * mounted on the production side of a comparison.
 */
export default function WorkspaceVisualFixture({ frame }: Props) {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      document.documentElement.dataset.workspaceVisualReady = 'true';
      setReady(true);
    }, frame === '6d' ? 1_250 : 450);
    return () => window.clearTimeout(timer);
  }, [frame]);

  const content = renderFrame(frame);
  const studyWidth = STUDY_WIDTH[frame];
  return (
    <div
      className={`workspace-visual-canvas workspace-redesign workspace-visual-frame-${frame}`}
      style={LOCKED_PAGE_FRAMES.has(frame) ? { width: 1600, height: 980 } : undefined}
    >
      {studyWidth === undefined ? content : (
        <div
          className="workspace-visual-study"
          style={{ width: studyWidth, height: STUDY_HEIGHT[frame] }}
        >
          {content}
        </div>
      )}
      <span data-workspace-visual-ready={ready ? 'true' : undefined} />
    </div>
  );
}

function renderFrame(frame: string): ReactNode {
  switch (frame) {
    case '1c':
      return <FullWorkspaceFrame section="trunks" activeNav="trunks" />;
    case '2a':
      return (
        <GlobalFrame activeNav="workspaces">
          <WorkspacesLandingPage
            currentWorkspaceId={VISUAL_WORKSPACE_ID}
            onEnterWorkspace={() => {}}
          />
        </GlobalFrame>
      );
    case '2b':
      return <FullWorkspaceFrame section="today" activeNav="today" />;
    case '3a':
      return (
        <PullRequestBoardList
          title="Pull requests"
          rows={visualPullRequests.slice(0, 6)}
          loading={false}
          error={null}
          showRepository={false}
          countOverride={{ review: 3, mine: 2, open: 7 }}
          onOpen={() => {}}
          onRefresh={() => {}}
        />
      );
    case '3b':
      return <RepoFrame section="issues" />;
    case '3c':
      return <RepoFrame section="issues" selectedNumber={VISUAL_ISSUE_NUMBER} />;
    case '3d':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          listPresentation="status"
          dailySpendOverride={1_400}
          dailyTokensOverride={96_000}
        />
      );
    case '4b':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          listPresentation="provider"
          featuredSessionIds={[
            VISUAL_SESSION_ID,
            'session-plan-done',
            'session-ci-error',
          ]}
          dailySpendOverride={1_400}
          showFilters={false}
        />
      );
    case '3e':
      return (
        <WorkspaceBacklogPage
          workspaceId={VISUAL_WORKSPACE_ID}
          threadNames={new Map([
            [VISUAL_TRUNK_ID, 'Codex v2'],
            ['trunk-clean-code', 'Clean code v2'],
          ])}
        />
      );
    case '3f':
      return <RepoFrame section="branches" />;
    case '3g':
    case '4a':
      return <RepoFrame section="commits" />;
    case '3h':
    case '4e':
    case '4f':
      return <WorkspaceMemoryPage workspaceId={VISUAL_WORKSPACE_ID} />;
    case '3i':
      return <div className="surface"><WorkspaceInsightsPage workspaceId={VISUAL_WORKSPACE_ID} /></div>;
    case '5f':
      return (
        <div className="surface">
          <WorkspaceInsightsPage workspaceId={VISUAL_WORKSPACE_ID} presentation="provider-card" />
        </div>
      );
    case '3j':
      return <WorkspaceNotificationsPage workspaceId={VISUAL_WORKSPACE_ID} />;
    case '4c':
      return (
        <PullRequestBoardList
          title="Pull requests"
          rows={visualPullRequests.slice(6)}
          loading={false}
          error={null}
          showRepository={false}
          initialView="list"
          initialFilter="mine"
          initialIncludeClosed
          onOpen={() => {}}
          onRefresh={() => {}}
        />
      );
    case '4d':
      return <RepoFrame section="branches" selectedBranch={VISUAL_BRANCH_NAME} />;
    case '5a':
      return (
        <GlobalFrame
          activeNav="trunks"
          activeWorkspace
          selectedThreadId={VISUAL_TRUNK_ID}
        >
          <TrunkPage
            threadId={VISUAL_TRUNK_ID}
            thread={{
              title: 'Codex v2',
              status: 'RUNNING',
              branch: VISUAL_BRANCH_NAME,
            }}
            conversation={<VisualTrunkConversation />}
            conversationFooter={<VisualTrunkComposer />}
            hideConversationPrompts
            composer={{
              value: '',
              onChange: () => {},
              onSubmit: () => {},
            }}
            tasks={{ active: [], closed: [] }}
            onOpenTask={() => {}}
          />
        </GlobalFrame>
      );
    case '5b':
      return <RepoFrame section="issues" selectedNumber={VISUAL_ISSUE_NUMBER} />;
    case '5c':
      return (
        <WorkspaceSessionsPage
          workspaceId={VISUAL_WORKSPACE_ID}
          selectedSessionId={VISUAL_SESSION_ID}
        />
      );
    case '5d':
      return (
        <WorkspaceBacklogPage
          workspaceId={VISUAL_WORKSPACE_ID}
          selectedKey={VISUAL_BACKLOG_KEY}
          threadNames={new Map([[VISUAL_TRUNK_ID, 'Codex v2']])}
        />
      );
    case '5e':
      return <RepoFrame section="pull-requests" selectedNumber={VISUAL_DETAIL_PR_NUMBER} />;
    case '6a':
      return <FullWorkspaceFrame section="today" activeNav="today" />;
    case '6b':
      return <InboxStudy />;
    case '6c':
      return (
        <WorkspaceSettingsPage
          workspace={visualWorkspaces[0]}
          workspaceId={VISUAL_WORKSPACE_ID}
          section="agents"
        />
      );
    case '6d':
      return <CreationToastStudy />;
    case '7a':
      return <LockedTrunkFrame />;
    case '7b':
      return <LockedTaskBrainFrame />;
    case '7c':
      return <LockedStageFrame />;
    default:
      return <div className="wu-body-message">Unknown workspace visual frame {frame}</div>;
  }
}

const LOCKED_TASK_TITLE = 'Remove unused daily quote feature';
const LOCKED_TASK_BRANCH = 'dev/remove-unused-daily-quote-feature';
const LOCKED_TASK_ID = 'visual-task-daily-quote';
const LOCKED_TRUNK_ID = 'trunk-clean-code';
const HOUR_MS = 60 * 60_000;
const lockedTaskAt = Date.now() - 20 * HOUR_MS;
const lockedIso = (offsetMs = 0) => new Date(lockedTaskAt + offsetMs).toISOString();

const lockedTaskFiles: DiffFileDto[] = [
  ['backend/src/main/java/com/bytequay/app/service/DailyCardService.java', 118],
  ['backend/src/main/java/com/bytequay/app/web/DailyCardController.java', 64],
  ['electron/preload.ts', 22],
  ['backend/src/main/java/com/bytequay/app/domain/DailyCard.java', 41],
  ['backend/src/main/java/com/bytequay/app/config/RestClientConfig.java', 28],
  ['backend/src/main/java/com/bytequay/app/client/ZenQuotesClient.java', 39],
  ['electron/ipc.ts', 12],
  ['frontend/src/types/dailyCard.ts', 7],
].map(([filename, deletions]): DiffFileDto => ({
  filename: String(filename),
  status: 'removed',
  additions: 0,
  deletions: Number(deletions),
  patch: null,
}));

const lockedTrunkFiles: DiffFileDto[] = [
  ['frontend/src/workspace/WorkspaceRepoPage.tsx', 18, 22],
  ['frontend/src/workspace/WorkspaceShell.tsx', 14, 9],
  ['frontend/src/App.tsx', 12, 6],
  ['frontend/src/pages/WorkspaceNavShell.tsx', 10, 5],
  ['frontend/src/workspace/workspaceRoutes.ts', 9, 4],
  ['frontend/src/workspace/WorkspaceRepoPage.test.tsx', 13, 3],
  ['frontend/src/pages/WorkspaceNavShell.test.tsx', 11, 2],
  ['frontend/src/workspace/workspaceRoutes.test.ts', 7, 1],
].map(([filename, additions, deletions]): DiffFileDto => ({
  filename: String(filename),
  status: 'modified',
  additions: Number(additions),
  deletions: Number(deletions),
  patch: null,
}));

const lockedTrunkCommits: ThreadCommitDto[] = [{
  sha: 'd4aae82f42667d9a',
  shortSha: 'd4aae82f',
  authorName: 'chenjian2664',
  authorEmail: 'jack@example.com',
  authoredAt: lockedIso(-19 * HOUR_MS),
  subject: 'Remove stale repository pages',
}];

const lockedTaskPrBundle: LocalPRBundle = {
  pr: {
    id: 'visual-local-pr-37',
    taskId: LOCKED_TASK_ID,
    branchName: LOCKED_TASK_BRANCH,
    baseBranch: 'main',
    title: LOCKED_TASK_TITLE,
    description: [
      '## Summary',
      '',
      '- remove the unused daily quote service and endpoint',
      '- remove the Electron IPC and renderer type plumbing',
      '- verify there are no remaining ZenQuotes references',
      '',
      '## Validation',
      '',
      '- `mvn verify` — 1,685 tests passed',
      '- `npm test` — 1,206 tests passed',
    ].join('\n'),
    status: 'merged',
    createdAt: lockedTaskAt - HOUR_MS,
    pushedAt: lockedTaskAt - 45 * 60_000,
    remotePrNumber: 37,
    remotePrUrl: 'https://github.com/chenjian2664/ByteQuay/pull/37',
    mergedAt: lockedTaskAt,
    closedAt: null,
    origin: 'task',
    repo: 'chenjian2664/ByteQuay',
    author: 'chenjian2664',
    syncedAt: lockedTaskAt,
    syncedAdditions: 0,
    syncedDeletions: 331,
    syncedMergeable: true,
    syncedMergeableState: 'clean',
    syncedMergeQueueEnabled: false,
    syncedMergeQueueState: null,
    branchDeletedAt: lockedTaskAt + 2 * 60_000,
  },
  commits: [
    {
      id: 'visual-pr-37-commit-1', localPrId: 'visual-local-pr-37', sha: '1c1f4b96a74d',
      message: 'Remove unused daily quote feature', additions: 0, deletions: 331,
      authoredAt: lockedTaskAt - 50 * 60_000, pushedAt: lockedTaskAt - 45 * 60_000,
    },
  ],
  timeline: [
    {
      id: 'visual-pr-37-timeline-commit', localPrId: 'visual-local-pr-37', eventType: 'commit',
      actor: 'chenjian2664', isLocalOnly: false, strippedOnPushAt: null,
      createdAt: lockedTaskAt - 50 * 60_000,
      payload: { sha: '1c1f4b96a74d', message: 'Remove unused daily quote feature' },
    },
    {
      id: 'visual-pr-37-timeline-review', localPrId: 'visual-local-pr-37', eventType: 'review',
      actor: 'codex-reviewer[bot]', isLocalOnly: false, strippedOnPushAt: null,
      createdAt: lockedTaskAt - 18 * 60_000,
      payload: { verdict: 'APPROVED', body: 'Deletion-only cleanup looks safe. All references and checks are clean.' },
    },
  ],
  checks: Array.from({ length: 22 }, (_, index) => ({
    id: `visual-pr-37-check-${index + 1}`,
    localPrId: 'visual-local-pr-37',
    kind: 'remote' as const,
    name: `ByteQuay CI / ${['backend', 'frontend', 'lint', 'package'][index % 4]} (${index + 1})`,
    status: 'passed' as const,
    durationMs: 28_000 + index * 1_000,
    startedAt: lockedTaskAt - 40 * 60_000,
    finishedAt: lockedTaskAt - 12 * 60_000 + index * 100,
    runId: `visual-ci-${index + 1}`,
  })),
  comments: [{
    id: 'visual-pr-37-comment-1', localPrId: 'visual-local-pr-37', origin: 'remote', scope: 'pr',
    filePath: null, lineNumber: null, side: 'RIGHT', startLine: null, startSide: null,
    author: 'codex-reviewer[bot]', body: 'Verified the feature is unreachable from the renderer. Nice cleanup.',
    createdAt: lockedTaskAt - 15 * 60_000, resolvedAt: lockedTaskAt - 10 * 60_000,
    dismissedAt: null, strippedOnPushAt: null, parentCommentId: null, publishedAt: lockedTaskAt - 15 * 60_000,
  }],
  pendingStripCount: 0,
};

const lockedTaskPullRow = (() => {
  const row = pullRowFromLocal(lockedTaskPrBundle.pr, 'chenjian2664/ByteQuay', 37);
  return {
    ...row,
    status: 'passed' as const,
    comments: 1,
    hasAgent: true,
    chips: [{ t: 'cleanup', bg: '#f3e8ff', fg: '#6f42c1' }],
    dto: {
      ...row.dto,
      labels: ['cleanup'],
      labelColors: { cleanup: 'd4c5f9' },
      requestedReviewers: ['codex-reviewer'],
      ciStatus: 'PASSING' as const,
      additions: 0,
      deletions: 331,
      commentCount: 1,
      reviewerVerdicts: { 'codex-reviewer': 'APPROVED' },
      reviewState: 'done' as const,
    },
  };
})();

function LockedTrunkFrame() {
  const thread = visualThreads.find(value => value.id === LOCKED_TRUNK_ID);
  if (thread === undefined) return null;
  const workTasks = visualTasks.filter(task => task.threadId === LOCKED_TRUNK_ID);
  const latestWorkTask = workTasks.reduce<WorkUnitTaskDto | null>(
    (latest, task) => latest === null || task.seq > latest.seq ? task : latest,
    null,
  );
  const taskCards = workTasks.map(task => toTaskCard(task, false));
  return (
    <TrunkPage
      threadId={thread.id}
      thread={{
        title: thread.title,
        status: thread.status,
        branch: null,
        workspaceId: VISUAL_WORKSPACE_ID,
        repository: 'chenjian2664/ByteQuay',
      }}
      sidebar={(
        <TrunkWorkspaceSidebar
          workspaceName="bytequay-v3-test"
          repository="chenjian2664/ByteQuay"
          threads={visualThreads.filter(value => value.flow !== 'review').slice(0, 6)}
          selectedThreadId={thread.id}
          selectedTasks={workTasks.map(task => ({
            id: task.id,
            label: task.name ?? task.branchName,
            pr: 'merged',
          }))}
          counts={{ todayNeedsYou: 3, pullRequests: 4, issues: 2, backlog: 7, branches: 3, sessions: 1 }}
          notificationCount={8}
          collapsed={false}
          onNavigate={() => {}}
          onOpenThread={() => {}}
          onOpenTask={() => {}}
          onSwitchWorkspace={() => {}}
          onNewThread={() => {}}
        />
      )}
      conversation={(
        <Conv>
          <TrunkFeed
            messages={lockedTrunkMessages()}
            tasks={workTasks}
            density="focused"
            onOpenTask={() => {}}
            artifactsByTaskId={latestWorkTask === null ? undefined : new Map([[
              latestWorkTask.id,
              {
                files: lockedTrunkFiles,
                commits: lockedTrunkCommits,
                onReview: () => {},
                onUndo: () => {},
              },
            ]])}
          />
        </Conv>
      )}
      composer={{
        value: '',
        onChange: () => {},
        onSubmit: () => {},
        placeholder: 'Message the thread…',
        modePill: <WorkModelPill variant="workspace-v2" scope={{ kind: 'thread', threadId: thread.id }} />,
      }}
      tasks={{ active: [], closed: taskCards }}
      onOpenTask={() => {}}
      hideConversationPrompts
    />
  );
}

function LockedTaskBrainFrame() {
  return (
    <TaskBrainPage
      task={{
        pillLabel: 'TASK #1', title: LOCKED_TASK_TITLE, branch: LOCKED_TASK_BRANCH,
        finished: true, taskNumber: 1, trunkLabel: 'Clean code v2',
      }}
      pr={{ number: 37, status: 'merged', onOpen: () => {} }}
      sidebar={<LockedTaskSidebar />}
      conversation={<LockedBrainConversation />}
      composer={lockedTaskComposer(
        'This task is closed — ask the brain, or reopen to continue…',
        'Task #1 · 23m 24s · $0.42',
      )}
      tabs={{ pr: <LockedPrPanel /> }}
      changes={{ additions: 0, deletions: 331, onOpen: () => {} }}
      onOpenTrunk={() => {}}
      onSubmitReview={async () => {}}
    />
  );
}

function LockedStageFrame() {
  return (
    <StageDetailPage
      stageKind="dev"
      stage={{ title: 'Local Development', branch: LOCKED_TASK_BRANCH, pillLabel: 'DEV STAGE' }}
      taskTitle={LOCKED_TASK_TITLE}
      taskNumber={1}
      trunkLabel="Clean code v2"
      pr={{ number: 37, status: 'merged', onOpen: () => {} }}
      sidebar={<LockedTaskSidebar activeDevelopment />}
      conversation={<LockedStageConversation />}
      composer={lockedTaskComposer(
        'This stage is closed — ask about what happened here…',
        'Stage 2 of 4 · 15m 23s',
      )}
      run={{ statusLabel: 'completed', terminal: true }}
      tabs={{ pr: <LockedPrPanel /> }}
      changes={{ additions: 0, deletions: 331, onOpen: () => {} }}
      onOpenTrunk={() => {}}
      onOpenTask={() => {}}
      onSubmitReview={async () => {}}
    />
  );
}

function LockedTaskSidebar({ activeDevelopment = false }: { activeDevelopment?: boolean }) {
  return (
    <TaskSidebar
      task={{
        title: LOCKED_TASK_TITLE,
        branch: LOCKED_TASK_BRANCH,
        taskNumber: 1,
        repository: 'chenjian2664/ByteQuay',
        workspaceName: 'bytequay-v3-test',
        finished: true,
      }}
      threadLabel="Clean code v2"
      nodes={lockedTaskNodes(activeDevelopment)}
      highlightActiveStage={activeDevelopment}
      notificationCount={8}
      onOpenTrunk={() => {}}
      onOpenStage={() => {}}
      onOpenBrain={() => {}}
      onNavigateGlobal={() => {}}
      onSwitchWorkspace={() => {}}
    />
  );
}

function LockedPrPanel() {
  return (
    <PullDetailBody
      row={lockedTaskPullRow}
      bundle={lockedTaskPrBundle}
      refresh={() => {}}
      onComment={async () => {}}
    />
  );
}

function lockedTaskComposer(closedNote: string, meta: string) {
  return {
    value: '',
    onChange: () => {},
    onSubmit: () => {},
    closedNote,
    modePill: (
      <WorkModelPill
        variant="workspace-v2"
        scope={{ kind: 'thread' as const, threadId: LOCKED_TRUNK_ID }}
      />
    ),
    usage: { planPercent: 4, sessionLabel: '827 AI credits' },
    meta,
  };
}

function lockedTaskNodes(activeDevelopment: boolean): LivePlanNode[] {
  const phase = (key: string, label: string, meta?: string): LivePlanPhaseNode => ({
    key, label, meta, status: 'done', glyph: '✓', nav: { kind: 'none' },
  });
  return [
    {
      key: 'plan', label: 'Plan', status: 'done', glyph: '✓', meta: '1m 45s · brain',
      placement: 'full', activeView: false, nav: { kind: 'brain' }, nodeType: 'stage',
      phases: [
        phase('plan-scope', 'Scope'), phase('plan-removal', 'Removal plan'),
        phase('plan-validation', 'Validation'), phase('plan-rollback', 'Rollback notes'),
      ],
    },
    {
      key: 'local-development', label: 'Local Development', status: 'done', glyph: '✓',
      meta: '15m 23s · 8 files · 1c1f4b96', placement: 'full', activeView: activeDevelopment,
      nav: { kind: 'stage', stageId: 'visual-dev-stage' }, nodeType: 'stage',
      phases: [
        phase('dev-implementing', 'Implementing'), phase('dev-validation', 'Validation'),
        phase('dev-brain-review', 'Brain review'), phase('dev-local-review', 'Local review', 'approved'),
        phase('dev-push', 'Push / PR', 'PR #37'),
      ],
    },
    {
      key: 'remote-development', label: 'Remote Development', status: 'done', glyph: '✓',
      meta: '7m 16s · PR #37 · 22 checks', placement: 'full', activeView: false,
      nav: { kind: 'stage', stageId: 'visual-remote-stage' }, nodeType: 'stage',
    },
    {
      key: 'cleanup', label: 'Cleanup', status: 'done', glyph: '✓',
      meta: 'branch deleted · refs clean', placement: 'full', activeView: false,
      nav: { kind: 'none' }, nodeType: 'auto',
    },
  ];
}

function LockedBrainConversation() {
  const stages = lockedBrainStages();
  return (
    <Conv>
      <BrainFeed
        feed={lockedBrainRows(stages)}
        stages={stages}
        density="focused"
        foldClosedStages={false}
        onOpenStage={() => {}}
        developmentArtifact={<TaskChangedFilesCard files={lockedTaskFiles} onReview={() => {}} />}
        spineTrailer={(
          <>
            <WorkFold label="Worked for 22s" meta="· 2 steps">
              <ActivityStrip
                groups={[{ kind: 'Read', rows: [
                  { label: 'Scanned renderer imports for getDailyCard — none' },
                  { label: 'Checked IPC registrations for home:dailyCard' },
                ] }]}
              />
            </WorkFold>
            <SpineNode mark="◆" color="purple" name="MERGE / CLOSE" state="merged · PR #37" meta="20h ago" />
            <Headline
              bare
              body="Cleanup verified — no remaining references. Task closed with **11 of 11** plan steps done."
            />
          </>
        )}
      />
    </Conv>
  );
}

function LockedStageConversation() {
  return (
    <Conv>
      <Spine>
        <div className="workspace-task-stage-log__stamp">DEV STAGE · 20h ago</div>
        {stageFeed(lockedStageRows(), undefined, undefined, false, true)}
        <TaskChangedFilesCard files={lockedTaskFiles} onReview={() => {}} />
        <Headline
          bare
          body="Commit `1c1f4b96` is ready with a clean worktree. Handed to Remote Development for PR #37, CI, and review comments."
        />
      </Spine>
    </Conv>
  );
}

function lockedBrainStages(): StageDto[] {
  return [
    {
      id: 'visual-plan-stage', taskId: LOCKED_TASK_ID, type: 'PLAN_STAGE', state: 'CLOSED',
      openedAt: lockedIso(-HOUR_MS), closedAt: lockedIso(-HOUR_MS + 105_000), callerStageId: null,
      summary: 'Removal plan approved', loopIteration: 0,
    },
    {
      id: 'visual-dev-stage', taskId: LOCKED_TASK_ID, type: 'DEVELOPMENT_STAGE', state: 'CLOSED',
      openedAt: lockedIso(-45 * 60_000), closedAt: lockedIso(-45 * 60_000 + 923_000), callerStageId: null,
      summary: '8-file deletion-only cleanup', loopIteration: 0,
    },
  ];
}

function lockedBrainRows(stages: StageDto[]): BrainFeedRow[] {
  const row = (
    id: string,
    type: BrainFeedRow['type'],
    body: string,
    stage: StageDto | null,
    offsetMs: number,
  ): BrainFeedRow => ({
    id, type, body, stageId: stage?.id ?? null, stageType: stage?.type ?? null,
    referencedStageId: null, messageSeq: null, images: [], managedSkills: [], ts: lockedIso(offsetMs),
  });
  const [plan, dev] = stages;
  const ciStage: StageDto = {
    id: 'visual-ci-stage', taskId: LOCKED_TASK_ID, type: 'CI_FIXING_STAGE', state: 'CLOSED',
    openedAt: lockedIso(-28 * 60_000), closedAt: lockedIso(-26 * 60_000), callerStageId: null,
    summary: 'Remote CI recovered', loopIteration: 3,
  };
  return [
    row('brain-discovery', 'BRAIN_AGENT_RESPONSE',
      '`DailyCardService` is wired but unused by the UI. The endpoint, IPC handler, preload method, and renderer type are safe cleanup candidates.', null, -2 * HOUR_MS),
    { ...row('brain-user', 'USER_MESSAGE', "Let's remove it", null, -2 * HOUR_MS + 60_000), messageSeq: 1 },
    row('brain-cut', 'BRAIN_AGENT_RESPONSE',
      'Plan approved. Cutting one focused development task with removal scope and validation requirements.', null, -2 * HOUR_MS + 82_000),
    row('brain-plan-open', 'STAGE_OPENED', '', plan ?? null, -HOUR_MS),
    row('brain-plan-summary', 'BRAIN_AGENT_RESPONSE',
      'The execution plan covers all eight dead-code files, clean builds, identifier searches, and rollback notes.', plan ?? null, -HOUR_MS + 90_000),
    row('brain-plan-close', 'STAGE_CLOSED', '', plan ?? null, -HOUR_MS + 105_000),
    row('brain-dev-open', 'STAGE_OPENED', '', dev ?? null, -45 * 60_000),
    row('brain-dev-summary', 'BRAIN_AGENT_RESPONSE',
      'Confident this is a **dead-code-only removal**: no renderer references remain, and all frontend and backend checks pass locally.', dev ?? null, -30 * 60_000),
    row('brain-dev-close', 'STAGE_CLOSED', '', dev ?? null, -29 * 60_000),
    row('brain-ci-segment', 'STAGE_OPENED', '', null, -28 * 60_000),
    row('brain-ci-failure', 'NEEDS_ATTENTION',
      'round 3 awakened\nmvn verify › TestApplicationContextSmoke\nNoSuchBeanDefinitionException: no bean named dailyCardService (stale context import)',
      ciStage, -27 * 60_000),
  ];
}

function lockedStageRows(): StageConversationRow[] {
  const row = (
    id: string,
    kind: StageConversationRow['kind'],
    text: string | null,
    offsetMs: number,
    tool?: { tag: string; label: string; detail: string; result?: string },
  ): StageConversationRow => ({
    id, kind, text, messageSeq: null, ts: lockedIso(offsetMs), images: [], managedSkills: [],
    toolTag: tool?.tag ?? null, toolLabel: tool?.label ?? null, toolDetail: tool?.detail ?? null,
    toolResult: tool?.result ?? null, toolError: false, toolDiff: null, iterationNumber: null, callId: null,
  });
  return [
    row('stage-intro', 'agent',
      'Frontend validation is clean. I’m doing one clean backend build plus the context smoke test so removed Java classes cannot be masked by stale compiled output.', 0),
    row('stage-tsc', 'tool_call', null, 1_000,
      { tag: 'Run', label: 'TypeScript', detail: 'npx tsc --noEmit', result: 'clean' }),
    row('stage-tests', 'tool_call', null, 20_000,
      { tag: 'Run', label: 'Tests', detail: 'npx vitest run --no-cache', result: '1,206 passed' }),
    row('stage-lint', 'tool_call', null, 44_000,
      { tag: 'Run', label: 'Lint', detail: 'npm run lint', result: 'zero errors' }),
    row('stage-backend', 'agent',
      'Clean backend compilation and the Spring context smoke test passed. Running every sandbox-safe backend test as the final verification.', 60_000),
    row('stage-maven', 'tool_call', null, 61_000,
      { tag: 'Run', label: 'Maven', detail: 'mvn clean -Dtest=TestApplicationContextSmoke verify', result: 'passed' }),
    row('stage-review', 'agent',
      'Final review shows an **8-file, deletion-only** source diff with no stale identifiers or compiled daily-card classes.', 120_000),
    row('stage-diff', 'tool_call', null, 121_000,
      { tag: 'Read', label: 'Diff', detail: '8 files · +0 −331', result: 'deletion-only' }),
    row('stage-search', 'tool_call', null, 150_000,
      { tag: 'Read', label: 'Search', detail: 'dailyCard|ZenQuotes', result: 'no references remain' }),
    row('stage-verify', 'tool_call', null, 180_000,
      { tag: 'Run', label: 'Maven', detail: 'mvn verify', result: '1,685 tests passed' }),
    row('stage-done', 'agent',
      'The source work is complete. The focused commit and PR description are ready for review.', 181_000),
  ];
}

function lockedTrunkMessages(): ThreadMessageDto[] {
  const [base] = visualThreadMessages;
  if (base === undefined) return [];
  const message = (
    id: string,
    seq: number,
    role: string,
    type: string,
    content: Record<string, unknown>,
    agoMs: number,
    durationMs: number | null = null,
  ): ThreadMessageDto => ({
    ...base,
    id,
    threadId: LOCKED_TRUNK_ID,
    taskId: null,
    seq,
    role,
    type,
    contentJson: JSON.stringify(content),
    durationMs,
    ts: new Date(Date.now() - agoMs).toISOString(),
  });
  const workTasks = visualTasks.filter(task => task.threadId === LOCKED_TRUNK_ID);
  return [
    message('locked-trunk-user', 1, 'user', 'text', {
      text: 'Remove the stale repository routes and renderer entry points, then cut focused tasks and verify both sides.',
    }, 26 * HOUR_MS),
    message('locked-trunk-think', 2, 'assistant', 'thinking', {
      summary: 'Mapped the route registrations, imports, tests, and IPC exposure before splitting the cleanup.',
    }, 26 * HOUR_MS - 60_000, 70_000),
    message('locked-trunk-tool', 3, 'assistant', 'tool_call', {
      toolName: 'Search', input: { pattern: 'WorkspaceRepoPage|legacy renderer routes' },
    }, 26 * HOUR_MS - 90_000, 60_000),
    message('locked-trunk-headline', 4, 'assistant', 'text', {
      text: 'Found two independent cleanup paths. I cut one task for route normalization and one for the stale repository pages so they can run in parallel.',
    }, 26 * HOUR_MS - 130_000),
    ...workTasks.map((task, index) => message(
      `locked-trunk-summary-${task.id}`,
      5 + index,
      'assistant',
      'task_summary',
      {
        text: index === workTasks.length - 1
          ? 'Removed the stale repository pages, tightened the navigation fallbacks, and verified the complete frontend suite.'
          : 'Normalized renderer routes and kept every existing deep link behavior intact.',
        taskId: task.id,
        taskSeq: task.seq,
      },
      22 * HOUR_MS - index * 60_000,
    )),
  ];
}

function VisualTrunkConversation() {
  return (
    <div className="wu-trunk-reference-conversation">
      <div className="wu-trunk-reference-conversation__carryover">
        Conversation unchanged — carried over as-is
      </div>
      <div className="wu-trunk-reference-conversation__user">
        Let&apos;s replace the hand-rolled bounds checks with Math.clamp, and keep NaN behavior identical.
      </div>
      <div className="wu-trunk-reference-conversation__agent">
        <VisualAgentAvatar />
        <div className="wu-trunk-reference-conversation__bubble">
          Agreed — I scanned <code>functions/math</code> and found 4 call sites. Plan: swap to Math.clamp,
          add boundary tests, wire the suite into CI. Cutting task #14 for the first two steps.
        </div>
      </div>
      <div className="wu-trunk-reference-conversation__agent">
        <VisualAgentAvatar />
        <div className="wu-trunk-reference-conversation__bubble is-question">
          <span>
            Question before I touch serialization: keep the legacy field order in <code>toMessage</code>?
            Downstream consumers may rely on it.
          </span>
          <div className="wu-trunk-reference-conversation__answers">
            <button type="button">Keep order</button>
            <button type="button">Reorder freely</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function VisualAgentAvatar() {
  return (
    <span className="wu-trunk-reference-conversation__avatar" aria-hidden>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect x="5" y="9" width="14" height="10" rx="2" />
        <path d="M12 5v4" />
        <circle cx="12" cy="4" r="1" />
        <path d="M9 13.5h.01" />
        <path d="M15 13.5h.01" />
      </svg>
    </span>
  );
}

function VisualTrunkComposer() {
  return (
    <div className="wu-trunk-reference-composer">
      <div>Message the thread…</div>
      <button type="button">Send</button>
    </div>
  );
}

function GlobalFrame({
  children,
  activeNav,
  activeWorkspace = false,
  selectedThreadId,
}: {
  children: ReactNode;
  activeNav: WsNavKey;
  activeWorkspace?: boolean;
  selectedThreadId?: string;
}) {
  return (
    <div className="app-shell workspace-redesign workspace-visual-full-shell">
      <WorkspaceNavShell
        activeWorkspaceId={activeWorkspace ? VISUAL_WORKSPACE_ID : null}
        activeNav={activeNav}
        selectedThreadId={selectedThreadId}
        notificationCount={activeNav === 'workspaces' ? 28 : 8}
        tasks={selectedThreadId === VISUAL_TRUNK_ID ? visualTaskNavRows() : []}
        onNavigate={() => {}}
        onOpenThread={() => {}}
        onOpenTask={() => {}}
        onSwitchWorkspace={() => {}}
        onNewThread={() => {}}
      />
      <div className="app-content">{children}</div>
    </div>
  );
}

function visualTaskNavRows(): TaskNavRow[] {
  return visualTasks
    .filter(task => task.threadId === VISUAL_TRUNK_ID)
    .map(task => ({
      id: task.id,
      label: task.name ?? task.branchName,
      ...(task.prState === 'MERGED'
        ? { pr: 'merged' as const }
        : { dot: task.status === 'RUNNING' ? 'active' as const : 'sleep' as const }),
    }));
}

function FullWorkspaceFrame({
  section,
  activeNav,
}: {
  section: WorkspaceSection;
  activeNav: WsNavKey;
}) {
  return (
    <GlobalFrame activeNav={activeNav} activeWorkspace>
      <WorkspaceShell
        section={section}
        workspaceId={VISUAL_WORKSPACE_ID}
        onSelectSection={() => {}}
        threadsFilter="ALL"
        threadsProvider={null}
        threadsGroupId={null}
        threadsRepo={null}
        onThreadsFilterChange={() => {}}
        onThreadsProviderChange={() => {}}
        onThreadsGroupChange={() => {}}
        onThreadsRepoChange={() => {}}
        onOpenPr={() => {}}
        onOpenIssue={() => {}}
        onOpenBranch={() => {}}
        onOpenSession={() => {}}
        onOpenBacklog={() => {}}
        onOpenSettings={() => {}}
        immersive={false}
        onChangeImmersive={() => {}}
        hideRail
      />
    </GlobalFrame>
  );
}

function RepoFrame({
  section,
  selectedNumber,
  selectedBranch,
}: {
  section: 'pull-requests' | 'issues' | 'branches' | 'commits';
  selectedNumber?: number;
  selectedBranch?: string;
}) {
  return (
    <WorkspaceRepoPage
      workspaceId={VISUAL_WORKSPACE_ID}
      section={section}
      selectedNumber={selectedNumber}
      selectedBranch={selectedBranch}
      onOpenPr={() => {}}
      onOpenIssue={() => {}}
      onOpenBranch={() => {}}
      onOpenTrunk={() => {}}
      onBackToList={() => {}}
    />
  );
}

function InboxStudy() {
  const upstreamReview = visualDashboardPrs.find(value => value.number === 29586)!;
  const remoteReview = {
    ...upstreamReview,
    id: 'acme/widget#4062',
    repo: 'acme/widget',
    number: 4062,
    title: '[Cherry Pick] Skip Iceberg Glue column comment caching when content is invalid',
    updatedAt: new Date(Date.now() - 60 * 60_000).toISOString(),
  };
  const mention = {
    ...remoteReview,
    id: 'tuannvm/mcp-trino#88',
    repo: 'tuannvm/mcp-trino',
    number: 88,
  };
  const merged = {
    ...remoteReview,
    id: 'apache/gateway#30948',
    repo: 'apache/gateway',
    number: 30948,
  };
  const bytequay = {
    ...remoteReview,
    id: 'chenjian2664/ByteQuay#148',
    repo: 'chenjian2664/ByteQuay',
    number: 148,
  };
  const items: InboxItem[] = [
    {
      id: 'visual-review-trino',
      type: 'review',
      title: 'Review requested on #29586',
      sub: 'Fix Scan failure due to dropped column used in an equality delete',
      time: new Date(Date.now() - 17 * 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: upstreamReview },
    },
    {
      id: 'visual-review-remote',
      type: 'review',
      title: 'Review requested on #4062',
      sub: '[Cherry Pick] Skip Iceberg Glue column comment caching when content is invalid · acme/widget',
      time: remoteReview.updatedAt!,
      read: false,
      source: { kind: 'pr', pr: remoteReview },
    },
    {
      id: 'visual-question',
      type: 'info',
      title: 'Agent question in Codex v2',
      sub: '"Keep legacy field order in toMessage?" — session paused, waiting on you',
      time: new Date(Date.now() - 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: bytequay },
    },
    {
      id: 'visual-ci',
      type: 'blocked',
      title: `CI failed on ${VISUAL_BRANCH_NAME}`,
      sub: 'clamp boundary suite · 2 failures · task #14',
      time: new Date(Date.now() - 3 * 60 * 60_000).toISOString(),
      read: false,
      source: { kind: 'pr', pr: bytequay },
    },
    {
      id: 'visual-mention',
      type: 'mention',
      title: '@ebyhr mentioned you',
      sub: 'tuannvm/mcp-trino #88 — "curious how ByteQuay handles this"',
      time: new Date(Date.now() - 16 * 60 * 60_000).toISOString(),
      read: true,
      source: { kind: 'pr', pr: mention },
    },
    {
      id: 'visual-merged',
      type: 'done',
      title: '#30948 merged',
      sub: 'Add shared-credentials shared component',
      time: new Date(Date.now() - 6 * 60 * 60_000).toISOString(),
      read: true,
      source: { kind: 'pr', pr: merged },
    },
  ];
  const handlers: InboxHandlers = {
    openPr: () => {},
    openWorkspacePr: () => {},
    openRemoteReview: () => {},
    workspaceForRepo: (owner, repo) => {
      const fullName = `${owner}/${repo}`.toLowerCase();
      if (fullName === 'trinodb/trino') {
        return { workspaceId: 'workspace-trino', name: 'trino' };
      }
      if (fullName === 'chenjian2664/bytequay') {
        return { workspaceId: VISUAL_WORKSPACE_ID, name: 'bytequay' };
      }
      if (fullName === 'apache/gateway') {
        return { workspaceId: 'workspace-gateway', name: 'gateway' };
      }
      return null;
    },
    dismiss: () => {},
    approve: async () => {},
    resolved: () => {},
  };
  return (
    <div className="workspace-visual-inbox-study">
      <div className="workspace-visual-inbox-note">
        Home · contribution graph and review banner above, unchanged — this is the Inbox section
      </div>
      <div className="home-inbox">
        <div className="home-inbox__header">
          <div className="home-inbox__heading">
            <span className="home-inbox__title">Inbox</span>
            <span className="home-inbox__badge">56</span>
          </div>
          <div className="home-inbox__controls">
            <div role="button" tabIndex={0} className="home-inbox__filter home-inbox__filter--on">
              <span className="home-inbox__filter-dot" aria-hidden />
              Unread only
            </div>
            <a role="button" tabIndex={0} className="home-inbox__seeall">See all</a>
          </div>
        </div>
        <div className="home-inbox__list">
          {items.map(item => <InboxCard key={item.id} item={item} handlers={handlers} />)}
        </div>
      </div>
    </div>
  );
}

function CreationToastStudy() {
  useEffect(() => {
    const timer = window.setTimeout(() => {
      window.dispatchEvent(new CustomEvent('bytequay:workspace-creation-started', {
        detail: visualCreationReady,
      }));
    }, 50);
    return () => window.clearTimeout(timer);
  }, []);
  return (
    <div className="workspace-visual-toast-study">
      <WorkspaceCreationToasts onOpenWorkspace={() => {}} />
    </div>
  );
}
