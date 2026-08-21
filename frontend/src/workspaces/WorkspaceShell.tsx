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
import type { CherryPickExecution, GitOverviewDto } from '../types';
import { githubSlug, type Workspace } from './model';
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

  // A task jump is one-shot: leaving the Commits tab forgets it, so the next
  // visit starts on the commit browser again.
  useEffect(() => {
    if (tab !== 'commits') setOpenExecutionId(null);
  }, [tab]);

  if (tab === 'commits') {
    return (
      <CommitsTab
        key={openExecutionId ?? 'commits'}
        workspace={workspace}
        initialExecutionId={openExecutionId}
      />
    );
  }
  if (tab === 'prs') return <PullsScreen />;
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
  if (tab === 'issues' || tab === 'memory') {
    return (
      <div className="ws-tab-page">
        <h1>{tab === 'issues' ? 'Issues' : 'Memory'}</h1>
        <div className="ws-empty">
          {tab === 'issues'
            ? 'Issue tracking for this workspace is not wired up yet.'
            : 'Nothing has been remembered about this workspace yet.'}
        </div>
      </div>
    );
  }
  return <WorkspaceHome workspace={workspace} onSelectTab={onSelectTab} />;
}

function WorkspaceTasks({ workspace, onOpenTask, onNewCherryPick }: {
  workspace: Workspace;
  onOpenTask: (execution: CherryPickExecution) => void;
  onNewCherryPick: () => void;
}) {
  const [slug, setSlug] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    window.bridge.gitOverview(workspace.path)
      .then(overview => {
        if (cancelled) return;
        const origin = overview.remotes.find(remote => remote.name === workspace.originRemote);
        setSlug(origin ? githubSlug(origin.url) : null);
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setReady(true); });
    return () => { cancelled = true; };
  }, [workspace.path, workspace.originRemote]);

  if (!ready) return <div className="ws-empty">Loading…</div>;
  return (
    <TasksView
      repositorySlug={slug}
      onOpenTask={onOpenTask}
      onNewCherryPick={onNewCherryPick}
    />
  );
}

function WorkspaceHome({ workspace, onSelectTab }: {
  workspace: Workspace;
  onSelectTab: (tab: WorkspaceTab) => void;
}) {
  const [overview, setOverview] = useState<GitOverviewDto | null>(null);
  const [tasks, setTasks] = useState<CherryPickExecution[]>([]);
  const [openPrs, setOpenPrs] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.gitOverview(workspace.path)
      .then(value => { if (!cancelled) setOverview(value); })
      .catch(() => {});
    void window.bridge.listCherryPickExecutions(200)
      .then(executions => { if (!cancelled) setTasks(executions); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [workspace.path]);

  const originUrl = overview?.remotes.find(remote => remote.name === workspace.originRemote)?.url;
  const originSlug = originUrl ? githubSlug(originUrl) : null;
  const upstreamUrl = overview?.remotes.find(remote => remote.name === workspace.upstreamRemote)?.url;
  const upstreamSlug = upstreamUrl ? githubSlug(upstreamUrl) : null;

  useEffect(() => {
    if (!originSlug) return;
    let cancelled = false;
    window.bridge.fetchPrs()
      .then(prs => {
        if (cancelled) return;
        setOpenPrs(prs.filter(pr =>
          pr.repo.toLowerCase() === originSlug.toLowerCase()
          && pr.state?.toLowerCase() !== 'closed').length);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [originSlug]);

  const workspaceTasks = originSlug === null
    ? tasks
    : tasks.filter(task => task.repository.toLowerCase() === originSlug.toLowerCase());
  const activeTasks = workspaceTasks.filter(isOpenTask);

  return (
    <div className="ws-tab-page">
      <h1>{workspace.name}</h1>
      <div className="ws-home-sub">
        {workspace.path} · {overview?.currentBranch ?? workspace.defaultBranch}
        {upstreamSlug ? ` · upstream ${upstreamSlug}` : ''}
      </div>
      <div className="ws-stat-row">
        {openPrs !== null && (
          <button type="button" className="ws-stat-card" onClick={() => onSelectTab('prs')}>
            <div className="ws-stat-value">{openPrs}</div>
            <div className="ws-stat-label">Open pull requests</div>
          </button>
        )}
        <button type="button" className="ws-stat-card" onClick={() => onSelectTab('tasks')}>
          <div className="ws-stat-value">{activeTasks.length}</div>
          <div className="ws-stat-label">Active tasks</div>
        </button>
        <button type="button" className="ws-stat-card" onClick={() => onSelectTab('tasks')}>
          <div className="ws-stat-value">{workspaceTasks.length - activeTasks.length}</div>
          <div className="ws-stat-label">Finished tasks</div>
        </button>
        <button type="button" className="ws-stat-card" onClick={() => onSelectTab('commits')}>
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

function WorkspaceSettings({ workspace, onRemoved }: {
  workspace: Workspace;
  onWorkspaceChanged: (workspace: Workspace) => void;
  onRemoved: () => void;
}) {
  const draft = workspace;
  const [remotes, setRemotes] = useState<GitOverviewDto['remotes']>([]);

  useEffect(() => {
    let cancelled = false;
    window.bridge.gitOverview(workspace.path)
      .then(overview => { if (!cancelled) setRemotes(overview.remotes); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [workspace.path]);

  const upstreamUrl = remotes.find(remote => remote.name === draft.upstreamRemote)?.url;

  return (
    <div className="ws-tab-page">
      <h1>Workspace settings</h1>
      <div className="ws-settings">
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
          <div className="ws-upstream-head">Linked upstream repository</div>
          <div className="ws-upstream-body">
            <div className="ws-upstream-main">
              <div className="ws-upstream-name">
                {draft.upstreamRemote
                  ? (upstreamUrl ? githubSlug(upstreamUrl) ?? draft.upstreamRemote : draft.upstreamRemote)
                  : 'None'}
                {draft.upstreamRemote && <span className="ws-card-upstream">read-only</span>}
              </div>
              <div className="ws-upstream-sub">
                Browse its commits from the Commits tab and cherry-pick ranges into this workspace.
              </div>
            </div>
            <select
              value={draft.upstreamRemote ?? ''}
              disabled
            >
              <option value="">None</option>
              {remotes.map(remote => (
                <option key={remote.name} value={remote.name}>
                  {remote.name} — {githubSlug(remote.url) ?? remote.url}
                </option>
              ))}
            </select>
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
            onClick={() => {
              if (!window.confirm(`Remove ${draft.name} from ByteQuay?`)) return;
              void window.bridge.removeWorkspace(draft.id).then(onRemoved);
            }}
          >
            Remove…
          </button>
        </div>
      </div>
    </div>
  );
}
