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
import { isOpenTask } from '../commits/model';
import PullsScreen from '../pulls/PullsScreen';
import WorkspaceIssuesScreen from './WorkspaceIssuesScreen';
import type { CherryPickExecution, RepositoryMetadataDto, WorkspaceDto } from '../types';
import { relativeTime } from '../relativeTime';
import {
  workspaceFromDto,
  workspaceIsUpdating,
  workspaceStateLabel,
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

  // A task jump is one-shot: leaving the Commits tab forgets it, so the next
  // visit starts on the commit browser again.
  useEffect(() => {
    if (tab !== 'commits') setOpenExecutionId(null);
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
      onSelectTab={onSelectTab}
      onWorkspaceChanged={onWorkspaceChanged}
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

export function WorkspaceHome({ workspace, onSelectTab, onWorkspaceChanged }: {
  workspace: Workspace;
  onSelectTab: (tab: WorkspaceTab) => void;
  onWorkspaceChanged: (workspace: Workspace) => void;
}) {
  const [tasks, setTasks] = useState<CherryPickExecution[]>([]);
  const [openPrs, setOpenPrs] = useState<number | null>(null);
  const [metadata, setMetadata] = useState<RepositoryMetadataDto | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.listCherryPickExecutions(200)
      .then(executions => { if (!cancelled) setTasks(executions); })
      .catch(() => {});
    void window.bridge.fetchPrs(workspace.id)
      .then(prs => {
        if (cancelled) return;
        setOpenPrs(prs.filter(pr =>
          pr.state?.toLowerCase() !== 'closed').length);
      })
      .catch(() => {});
    void window.bridge.getWorkspaceMetadata(workspace.id)
      .then(value => { if (!cancelled) setMetadata(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [workspace.id]);

  const workspaceTasks = tasks.filter(task => task.workspaceId === workspace.id);
  const activeTasks = workspaceTasks.filter(isOpenTask);
  const codeReady = workspace.codeState === 'READY';
  const developmentBase = workspace.upstreamWorkspaceId
    ? `upstream/${workspace.upstreamDefaultBranch}`
    : `origin/${workspace.defaultBranch}`;
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

  return (
    <div className="ws-tab-page">
      <div className="ws-home-title">
        <div>
          <h1>{workspace.name}</h1>
          <div className="ws-home-sub">{workspace.path}</div>
        </div>
        {retryable && (
          <button
            type="button"
            className="ws-retry-btn"
            disabled={retrying}
            onClick={() => { void retry(); }}
          >
            {retrying ? 'Retrying…' : 'Retry preparation'}
          </button>
        )}
      </div>
      {retryError && <div className="ws-error" role="alert">{retryError}</div>}

      <div className="ws-home-grid">
        <section className="ws-home-card">
          <h2>Repository routing</h2>
          <div className="ws-routing-list">
            <div><span>Selected repository</span><b>{workspace.selectedOwner}/{workspace.selectedRepo} · origin</b></div>
            {workspace.upstreamOwner && workspace.upstreamRepo && (
              <div><span>Linked upstream workspace</span><b>{workspace.upstreamOwner}/{workspace.upstreamRepo} · upstream</b></div>
            )}
            <div><span>Followed for updates</span><b>{workspace.monitoredOwner}/{workspace.monitoredRepo}</b></div>
            <div><span>Default branch</span><b>{workspace.defaultBranch}</b></div>
            <div><span>Development base</span><b>{developmentBase}</b></div>
          </div>
        </section>
        <section className="ws-home-card">
          <h2>Readiness</h2>
          <WorkspaceReadiness
            label="Repository code"
            state={workspace.codeState}
            lastSyncAt={workspace.lastCodeSyncAt}
            error={workspace.codeError}
          />
          <WorkspaceReadiness
            label="Repository metadata"
            state={workspace.metadataState}
            lastSyncAt={workspace.lastMetadataSyncAt}
            error={workspace.metadataError}
            detail={metadata && (
              `${metadata.contributors.length} contributors (${metadata.contributorsSyncedAt
                ? relativeTime(metadata.contributorsSyncedAt)
                : 'not synced'}) · ${metadata.labels.length} labels and ${metadata.topics.length} topics (${metadata.labelsTopicsSyncedAt
                ? relativeTime(metadata.labelsTopicsSyncedAt)
                : 'not synced'})`
            )}
          />
        </section>
      </div>
      <div className="ws-stat-row">
        <button
          type="button"
          className="ws-stat-card"
          title={`Open pull requests for ${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
          onClick={() => onSelectTab('prs')}
        >
          <div className="ws-stat-value">{openPrs ?? '—'}</div>
          <div className="ws-stat-label">Open pull requests</div>
        </button>
        <button type="button" className="ws-stat-card" title="Open workspace tasks"
          onClick={() => onSelectTab('tasks')}>
          <div className="ws-stat-value">{activeTasks.length}</div>
          <div className="ws-stat-label">Active tasks</div>
        </button>
        <button type="button" className="ws-stat-card" title="Open workspace tasks"
          onClick={() => onSelectTab('tasks')}>
          <div className="ws-stat-value">{workspaceTasks.length - activeTasks.length}</div>
          <div className="ws-stat-label">Finished tasks</div>
        </button>
        <button type="button" className="ws-stat-card" disabled={!codeReady}
          title={codeReady ? 'Browse commits' : 'Available when repository code is ready.'}
          onClick={() => onSelectTab('commits')}>
          <div className="ws-stat-value">⑂</div>
          <div className="ws-stat-label">Browse commits</div>
        </button>
      </div>
      {activeTasks.length > 0 && (
        <>
          <div className="ws-section-label">RECENT ACTIVITY</div>
          <div className="ws-activity-card">
            {activeTasks.slice(0, 5).map(task => (
              <div key={task.executionId} className="ws-activity-row">
                <span className="ws-activity-icon">⑂</span>
                <span className="ws-activity-text">
                  Cherry-pick {task.executionId.slice(0, 8)} — {task.status === 'PAUSED'
                    ? 'paused, needs your input'
                    : `item ${task.currentPosition ?? '?'} of ${task.items.length}`}
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function WorkspaceReadiness({ label, state, lastSyncAt, error, detail }: {
  label: string;
  state: Workspace['codeState'] | Workspace['metadataState'];
  lastSyncAt: string | null;
  error: string | null;
  detail?: string | null;
}) {
  return (
    <div className="ws-readiness">
      <div className="ws-readiness-head">
        <b>{label}</b>
        <span className={`ws-state is-${state.toLowerCase()}`}>{workspaceStateLabel(state)}</span>
      </div>
      <div className="ws-readiness-meta">
        {lastSyncAt ? `Last synchronized ${relativeTime(lastSyncAt)}` : 'Not synchronized yet'}
      </div>
      {detail && <div className="ws-readiness-meta">{detail}</div>}
      {error && <div className="ws-readiness-error">{error}</div>}
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
