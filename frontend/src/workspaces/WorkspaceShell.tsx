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
import { useCallback, useEffect, useMemo, useState } from 'react';
import AgentTaskScreen from '../agents/AgentTaskScreen';
import AgentTasksView from '../agents/AgentTasksView';
import { createPreviewBridge } from '../agents/preview';
import Avatar from '../Avatar';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import TasksView from '../commits/TasksView';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import type { PullTab } from '../pulls/model';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import WorkspaceSettings from './WorkspaceSettings';
import type { CherryPickExecution, WorkspaceDto } from '../types';
import { relativeTime } from '../relativeTime';
import {
  workspaceHasReadableCode,
  workspaceIsUpdating,
  workspacePhaseColor,
  workspacePhaseLabel,
  type WorkspaceTab,
} from './model';
import '../css/workspaces.css';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, initialPullRequestNumber, initialIssueNumber, initialAgentTaskId, developmentBuild = false, browserOpen = false, onSelectAgentTask, onSelectPullRequest, onSelectIssue, onRemoved }: {
  workspace: WorkspaceDto;
  tab: WorkspaceTab;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: WorkspaceDto) => void;
  initialPullRequestNumber: number | null;
  initialIssueNumber: number | null;
  /** Route-owned: which agent task is open, so a reload reopens the same one. */
  initialAgentTaskId: string | null;
  developmentBuild?: boolean;
  browserOpen?: boolean;
  onSelectAgentTask: (taskId: string | null, mode: NavigationMode) => void;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onSelectIssue: (number: number, mode: NavigationMode) => void;
  onRemoved: () => void;
}) {
  // Fixture-backed and created once: the preview must never reach a real provider.
  const agentPreviewBridge = useMemo(() => createPreviewBridge(workspace.id), [workspace.id]);
  const [openExecutionId, setOpenExecutionId] = useState<string | null>(null);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [commitSourceSelection, setCommitSourceSelection] = useState<CommitSourceSelection>({ source: null, viewingSource: false });
  const [initialPullsTab, setInitialPullsTab] = useState<PullTab | null>(null);
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
    if (tab !== 'prs') setInitialPullsTab(null);
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
        initialTab={initialPullsTab ?? undefined}
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
    if (developmentBuild && initialAgentTaskId !== null) {
      return (
        <div className="ws-tab-page">
          <AgentTaskScreen
            workspaceId={workspace.id}
            taskId={initialAgentTaskId}
            bridge={agentPreviewBridge}
            onBack={() => onSelectAgentTask(null, 'push')}
          />
        </div>
      );
    }
    return (
      <div className="ws-tab-page">
        <WorkspaceTasks
          workspace={workspace}
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
        {developmentBuild && (
          <AgentTasksView
            workspaceId={workspace.id}
            bridge={agentPreviewBridge}
            onOpenTask={(taskId) => onSelectAgentTask(taskId, 'push')}
          />
        )}
      </div>
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
  return (
    <WorkspaceHome
      workspace={workspace}
      onCherryPickUpstream={() => {
        setInitialCommitsRepo('upstream');
        onSelectTab('commits');
      }}
      onReviewRequests={() => {
        setInitialPullsTab('req');
        onSelectTab('prs');
      }}
      onOpenIssues={() => onSelectTab('issues')}
    />
  );
}

function WorkspaceUnavailable({ message }: { message: string }) {
  return <div className="ws-tab-page"><div className="ws-empty">{message}</div></div>;
}

function WorkspaceTasks({ workspace, onOpenTask, onNewCherryPick }: {
  workspace: WorkspaceDto;
  onOpenTask: (execution: CherryPickExecution) => void;
  onNewCherryPick: () => void;
}) {
  return (
    <TasksView
      workspaceId={workspace.id}
      onOpenTask={onOpenTask}
      onNewCherryPick={onNewCherryPick}
    />
  );
}

const HOME_CARDS = [
  { icon: '⇢', color: '#8250df', title: 'Cherry-pick from upstream', action: 'cherry-pick' },
  { icon: '⑃', color: '#1a7f37', title: 'Review a pull request', action: 'review' },
  { icon: '◉', color: '#bc4c00', title: 'Fix issues and failures', action: 'issues' },
] as const;

export function WorkspaceHome({ workspace, onCherryPickUpstream, onReviewRequests, onOpenIssues }: {
  workspace: WorkspaceDto;
  onCherryPickUpstream: () => void;
  onReviewRequests: () => void;
  onOpenIssues: () => void;
}) {
  const status = [
    `${workspace.name} ${workspacePhaseLabel(workspace).toLowerCase()}`,
    workspace.lastCodeSyncAt ? `synced ${relativeTime(workspace.lastCodeSyncAt)}` : 'not synced yet',
    ...(workspace.upstreamOwner && workspace.upstreamRepo
      ? [`upstream ${workspace.upstreamOwner}/${workspace.upstreamRepo} linked`]
      : []),
  ].join(' · ');

  return (
    <div className="ws-home">
      <div className="ws-home-col">
        <div className="ws-hero-tile">⑂</div>
        <h1>What should we do?</h1>
        <div className="ws-hero-status">
          <Avatar login={workspace.selectedOwner} size={18} />
          <span className="ws-hero-dot" style={{ background: workspacePhaseColor(workspace) }} aria-hidden="true" />
          {status}
        </div>
        <div className="ws-action-cards">
          {HOME_CARDS.map(card => (
            <button
              key={card.title}
              type="button"
              className="ws-action-card"
              onClick={() => {
                if (card.action === 'cherry-pick') onCherryPickUpstream();
                else if (card.action === 'review') onReviewRequests();
                else onOpenIssues();
              }}
            >
              <div className="ws-action-icon" style={{ color: card.color }} aria-hidden="true">{card.icon}</div>
              <div className="ws-action-title">{card.title}</div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
