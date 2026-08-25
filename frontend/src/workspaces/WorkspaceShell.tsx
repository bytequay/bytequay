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
import { useCallback, useEffect, useState } from 'react';
import Avatar from '../Avatar';
import CommitsTab, { type CommitSourceSelection } from '../commits/CommitsTab';
import TasksView from '../commits/TasksView';
import type { NavigationMode } from '../navigationHistory';
import PullsScreen from '../pulls/PullsScreen';
import type { PullTab } from '../pulls/model';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import WorkspaceSettings from './WorkspaceSettings';
import type { CherryPickExecution } from '../types';
import { relativeTime } from '../relativeTime';
import {
  workspaceFromDto,
  workspaceHasReadableCode,
  workspaceIsUpdating,
  workspacePhaseColor,
  workspacePhaseLabel,
  type Workspace,
  type WorkspaceTab,
} from './model';
import '../css/workspaces.css';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, initialPullRequestNumber, initialIssueNumber, browserOpen = false, onSelectPullRequest, onSelectIssue, onRemoved }: {
  workspace: Workspace;
  tab: WorkspaceTab;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: Workspace) => void;
  initialPullRequestNumber: number | null;
  initialIssueNumber: number | null;
  browserOpen?: boolean;
  onSelectPullRequest: (number: number, mode: NavigationMode) => void;
  onSelectIssue: (number: number, mode: NavigationMode) => void;
  onRemoved: () => void;
}) {
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
        .then(details => onWorkspaceChanged(workspaceFromDto(details.summary)))
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
  if (tab === 'memory') {
    return (
      <div className="ws-tab-page">
        <h1>Memory</h1>
        <div className="ws-empty">
          Nothing has been remembered about this workspace yet.
        </div>
      </div>
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
  workspace: Workspace;
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
  { icon: '⌕', color: '#0969da', title: 'Explore and understand code', action: 'wip' },
  { icon: '⇢', color: '#8250df', title: 'Cherry-pick from upstream', action: 'cherry-pick' },
  { icon: '⑃', color: '#1a7f37', title: 'Review a pull request', action: 'review' },
  { icon: '◉', color: '#bc4c00', title: 'Fix issues and failures', action: 'issues' },
] as const;

// ponytail: dummy counts until real workspace stats are wired
const HOME_LINKS = [
  '4 open pull requests',
  '2 active tasks',
  '7 open issues',
  '12 commits ahead of upstream',
];

export function WorkspaceHome({ workspace, onCherryPickUpstream, onReviewRequests, onOpenIssues }: {
  workspace: Workspace;
  onCherryPickUpstream: () => void;
  onReviewRequests: () => void;
  onOpenIssues: () => void;
}) {
  const [wip, setWip] = useState<string | null>(null);

  if (wip !== null) {
    return (
      <div className="ws-home">
        <div className="ws-wip">
          <div className="ws-hero-tile">…</div>
          <h1>{wip}</h1>
          <div className="ws-wip-note">Still work in progress.</div>
          <button type="button" className="ws-add-btn" onClick={() => setWip(null)}>
            ‹ Back
          </button>
        </div>
      </div>
    );
  }

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
                else if (card.action === 'issues') onOpenIssues();
                else setWip(card.title);
              }}
            >
              <div className="ws-action-icon" style={{ color: card.color }} aria-hidden="true">{card.icon}</div>
              <div className="ws-action-title">{card.title}</div>
            </button>
          ))}
        </div>
        <div className="ws-home-flex" />
        <div className="ws-composer-wrap">
          <div className="ws-composer-branch">⑂ {workspace.defaultBranch}</div>
          <div className="ws-composer">
            <input
              placeholder="Do anything in this workspace…"
              onKeyDown={event => { if (event.key === 'Enter') setWip('Agent composer'); }}
            />
            <div className="ws-composer-row">
              <button type="button" className="ws-composer-add" title="Attach"
                onClick={() => setWip('Agent composer')}>+</button>
              <div className="ws-spacer" />
              <button type="button" className="ws-composer-send" title="Send"
                onClick={() => setWip('Agent composer')}>↑</button>
            </div>
          </div>
          <div className="ws-quick-links">
            {HOME_LINKS.map(link => (
              <button key={link} type="button" onClick={() => setWip(link)}>{link}</button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
