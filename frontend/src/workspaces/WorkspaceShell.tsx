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
import { useEffect, useState } from 'react';
import CommitsTab from '../commits/CommitsTab';
import TasksView from '../commits/TasksView';
import PullsScreen from '../pulls/PullsScreen';
import type { PullTab } from '../pulls/model';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import type { CherryPickExecution, WorkspaceDto } from '../types';
import { relativeTime } from '../relativeTime';
import {
  workspaceFromDto,
  workspaceIsUpdating,
  workspacePhaseColor,
  workspacePhaseLabel,
  type Workspace,
} from './model';
import '../css/workspaces.css';

export type WorkspaceTab =
  | 'home' | 'tasks' | 'prs' | 'issues' | 'commits' | 'memory' | 'settings';

export default function WorkspaceShell({ workspace, tab, onSelectTab, onWorkspaceChanged, onRemoved }: {
  workspace: Workspace;
  tab: WorkspaceTab;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: Workspace) => void;
  onRemoved: () => void;
}) {
  const [openExecutionId, setOpenExecutionId] = useState<string | null>(null);
  const [openPullRequestNumber, setOpenPullRequestNumber] = useState<number | null>(null);
  const [initialCommitsRepo, setInitialCommitsRepo] = useState<'workspace' | 'upstream' | null>(null);
  const [initialPullsTab, setInitialPullsTab] = useState<PullTab | null>(null);

  // Home-card jumps are one-shot: leaving the target tab forgets them, so the
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

  if (tab === 'commits' && workspace.codeState !== 'READY') {
    return <WorkspaceUnavailable message="Repository code must be ready before this tab can open." />;
  }
  if (tab === 'commits') {
    return (
      <CommitsTab
        key={openExecutionId ?? 'commits'}
        workspace={workspace}
        initialExecutionId={openExecutionId}
        initialRepo={initialCommitsRepo ?? undefined}
        onOpenPullRequest={number => {
          setOpenPullRequestNumber(number);
          onSelectTab('prs');
        }}
        onOpenSettings={() => onSelectTab('settings')}
      />
    );
  }
  if (tab === 'prs') {
    const repository = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
    return (
      <PullsScreen
        initialPr={openPullRequestNumber === null
          ? undefined
          : { repo: repository, number: openPullRequestNumber }}
        initialTab={initialPullsTab ?? undefined}
        repositoryScope={{ workspaceId: workspace.id, repository }}
      />
    );
  }
  if (tab === 'issues') {
    return <WorkspaceIssuesScreen
      workspaceId={workspace.id}
      repository={`${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
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
            onSelectTab(workspace.upstreamWorkspaceId ? 'commits' : 'settings');
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
      onWorkspaceChanged={onWorkspaceChanged}
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

export function WorkspaceHome({ workspace, onWorkspaceChanged, onCherryPickUpstream, onReviewRequests, onOpenIssues }: {
  workspace: Workspace;
  onWorkspaceChanged: (workspace: Workspace) => void;
  onCherryPickUpstream: () => void;
  onReviewRequests: () => void;
  onOpenIssues: () => void;
}) {
  const [wip, setWip] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);

  const retryable = workspace.codeState === 'FAILED' || workspace.metadataState === 'FAILED';

  const retry = async () => {
    setRetrying(true);
    setRetryError(null);
    try {
      onWorkspaceChanged(workspaceFromDto(await window.bridge.syncWorkspace(workspace.id)));
    }
    catch (failure) {
      setRetryError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setRetrying(false);
    }
  };

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
          <span className="ws-hero-dot" style={{ background: workspacePhaseColor(workspace) }} aria-hidden="true" />
          {status}
        </div>
        {retryable && (
          <button
            type="button"
            className="ws-retry-btn ws-hero-retry"
            disabled={retrying}
            onClick={() => { void retry(); }}
          >
            {retrying ? 'Retrying…' : 'Retry preparation'}
          </button>
        )}
        {retryError && <div className="ws-error" role="alert">{retryError}</div>}
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

function WorkspaceSettings({ workspace, onWorkspaceChanged, onRemoved }: {
  workspace: Workspace;
  onWorkspaceChanged: (workspace: Workspace) => void;
  onRemoved: () => void;
}) {
  const draft = workspace;
  const [candidates, setCandidates] = useState<WorkspaceDto[]>([]);
  const [upstreamWorkspaceId, setUpstreamWorkspaceId] = useState(workspace.upstreamWorkspaceId ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listWorkspaces()
      .then(values => {
        if (!cancelled) setCandidates(values.filter(value => value.id !== workspace.id));
      })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      });
    return () => { cancelled = true; };
  }, [workspace.id]);

  useEffect(() => {
    setUpstreamWorkspaceId(workspace.upstreamWorkspaceId ?? '');
  }, [workspace.upstreamWorkspaceId]);

  const saveUpstream = async () => {
    setSaving(true);
    setError(null);
    try {
      const changed = await window.bridge.setWorkspaceUpstream(
        workspace.id,
        upstreamWorkspaceId || null,
      );
      onWorkspaceChanged(workspaceFromDto(changed));
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
    finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!window.confirm(`Remove ${draft.name} from ByteQuay?`)) return;
    setError(null);
    try {
      await window.bridge.removeWorkspace(draft.id);
      onRemoved();
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  };

  return (
    <div className="ws-tab-page">
      <h1>Workspace settings</h1>
      <div className="ws-settings">
        {error && <div className="ws-error" role="alert">{error}</div>}
        <label>
          Workspace name
          <input
            value={draft.name}
            readOnly
          />
        </label>
        <label>
          Local path
          <input value={draft.path} readOnly className="ws-mono" />
        </label>
        <label>
          Default branch
          <input
            value={draft.defaultBranch}
            className="ws-mono ws-narrow"
            readOnly
          />
        </label>

        <div className="ws-upstream-card">
          <div className="ws-upstream-head">Linked upstream workspace</div>
          <div className="ws-upstream-body">
            <div className="ws-upstream-main">
              <div className="ws-upstream-name">
                {draft.upstreamOwner && draft.upstreamRepo
                  ? `${draft.upstreamOwner}/${draft.upstreamRepo}`
                  : 'None'}
                {draft.upstreamWorkspaceId && <span className="ws-card-upstream">read-only</span>}
              </div>
              <div className="ws-upstream-sub">
                Choose another watched workspace. Its local checkout supplies commits, pull requests,
                issues, and cherry-pick source branches.
              </div>
            </div>
            <select
              value={upstreamWorkspaceId}
              disabled={saving}
              onChange={event => setUpstreamWorkspaceId(event.target.value)}
            >
              <option value="">None</option>
              {candidates.map(candidate => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name} — {candidate.selectedOwner}/{candidate.selectedRepo}
                </option>
              ))}
            </select>
            <button
              type="button"
              disabled={saving || upstreamWorkspaceId === (workspace.upstreamWorkspaceId ?? '')}
              onClick={() => { void saveUpstream(); }}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>

        <div className="ws-danger-card">
          <div className="ws-upstream-main">
            <div className="ws-upstream-name">Remove workspace</div>
            <div className="ws-upstream-sub">
              Stops watching this repository and removes its workspace record.
            </div>
          </div>
          <button
            type="button"
            className="ws-btn-danger"
            onClick={() => { void remove(); }}
          >
            Remove…
          </button>
        </div>
      </div>
    </div>
  );
}
