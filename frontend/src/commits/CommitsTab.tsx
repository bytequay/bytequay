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
import { relativeTime } from '../relativeTime';
import type { CherryPickPreview, GitCommitDto, GitOverviewDto, PullRequestDto } from '../types';
import { githubSlug, type Workspace } from '../workspaces/model';
import ChangesView from './ChangesView';
import FlowView from './FlowView';
import HistoryView from './HistoryView';
import ProgressView from './ProgressView';
import TasksView from './TasksView';
import { selectedSpan, shortSha } from './model';
import '../css/commits.css';

type Screen = 'commits' | 'flow' | 'tasks' | 'progress';
type CommitsView = 'history' | 'changes';

export default function CommitsTab({ workspace, initialExecutionId, onOpenPullRequest, onOpenSettings }: {
  workspace: Workspace;
  /** Jump straight to a task's progress page (from the shell's Tasks tab). */
  initialExecutionId?: string | null;
  onOpenPullRequest: (number: number) => void;
  onOpenSettings: () => void;
}) {
  const [overview, setOverview] = useState<GitOverviewDto | null>(null);
  const [screen, setScreen] = useState<Screen>(initialExecutionId ? 'progress' : 'commits');
  const [commitsView, setCommitsView] = useState<CommitsView>('history');
  const [repo, setRepo] = useState<'workspace' | 'upstream'>('workspace');
  const [branch, setBranch] = useState(workspace.defaultBranch);
  const [upBranch, setUpBranch] = useState(workspace.upstreamDefaultBranch ?? workspace.defaultBranch);
  const [branchMenu, setBranchMenu] = useState(false);
  const [upMenu, setUpMenu] = useState(false);
  const [upBranchQuery, setUpBranchQuery] = useState('');
  const [upBranches, setUpBranches] = useState<string[]>([]);
  const [commits, setCommits] = useState<GitCommitDto[]>([]);
  const [pullRequests, setPullRequests] = useState<PullRequestDto[]>([]);
  const [logLoading, setLogLoading] = useState(true);
  const [checkedWs, setCheckedWs] = useState<Set<string>>(new Set());
  const [checkedUp, setCheckedUp] = useState<Set<string>>(new Set());
  const [changesCount, setChangesCount] = useState<number | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncedAt, setSyncedAt] = useState<number | null>(() => {
    const value = localStorage.getItem(`bytequay.lastSync.${workspace.id}`);
    return value ? Number(value) : null;
  });
  const [preview, setPreview] = useState<CherryPickPreview | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [executionId, setExecutionId] = useState<string | null>(initialExecutionId ?? null);
  const [error, setError] = useState<string | null>(null);
  const [logVersion, setLogVersion] = useState(0);

  const hasUpstream = workspace.upstreamWorkspaceId !== null && workspace.upstreamRemote !== null;
  const isUpstream = repo === 'upstream' && hasUpstream;
  const upstreamRemote = workspace.upstreamRemote;
  const sourceRemote = upstreamRemote ?? workspace.originRemote;
  const upstreamSlug = hasUpstream && workspace.upstreamOwner && workspace.upstreamRepo
    ? `${workspace.upstreamOwner}/${workspace.upstreamRepo}`
    : null;
  const originUrl = overview?.remotes.find(remote => remote.name === workspace.originRemote)?.url ?? null;
  const originSlug = originUrl ? githubSlug(originUrl) : null;
  const logRef = isUpstream ? `${sourceRemote}/${upBranch}` : branch;
  const checked = isUpstream ? checkedUp : checkedWs;

  const refreshOverview = useCallback(() => {
    window.bridge.gitOverview(workspace.id)
      .then(setOverview)
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  }, [workspace.id]);

  useEffect(() => { refreshOverview(); }, [refreshOverview]);

  useEffect(() => {
    if (!hasUpstream) setRepo('workspace');
    setUpBranch(workspace.upstreamDefaultBranch ?? workspace.defaultBranch);
    setUpBranches([]);
    setCheckedUp(new Set());
  }, [hasUpstream, workspace.defaultBranch, workspace.upstreamDefaultBranch]);

  useEffect(() => {
    let cancelled = false;
    window.bridge.fetchPrs(workspace.id)
      .then(value => { if (!cancelled) setPullRequests(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [workspace.id]);

  // Menus close on any outside press; the chip wrappers stop propagation.
  useEffect(() => {
    if (!branchMenu && !upMenu) return;
    const close = () => {
      setBranchMenu(false);
      setUpMenu(false);
    };
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [branchMenu, upMenu]);

  useEffect(() => {
    let cancelled = false;
    setLogLoading(true);
    window.bridge.gitLog(workspace.id, logRef, 300)
      .then(value => {
        if (cancelled) return;
        setCommits(value);
        setError(null);
      })
      .catch(failure => {
        if (cancelled) return;
        setCommits([]);
        setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => {
        if (!cancelled) setLogLoading(false);
      });
    return () => { cancelled = true; };
  }, [workspace.id, logRef, logVersion]);

  useEffect(() => {
    if (isUpstream) return;
    let cancelled = false;
    window.bridge.gitStatus(workspace.id)
      .then(status => { if (!cancelled) setChangesCount(status.files.length); })
      .catch(() => { if (!cancelled) setChangesCount(null); });
    return () => { cancelled = true; };
  }, [workspace.id, isUpstream, logVersion, commitsView]);

  useEffect(() => {
    if (!hasUpstream || !upMenu || upBranches.length > 0) return;
    let cancelled = false;
    window.bridge.gitRemoteBranches(workspace.id, sourceRemote)
      .then(value => { if (!cancelled) setUpBranches(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [hasUpstream, upMenu, sourceRemote, upBranches.length, workspace.id]);

  const span = useMemo(() => selectedSpan(commits, checked), [commits, checked]);

  const sync = async () => {
    if (syncing) return;
    setSyncing(true);
    try {
      const remotes = [workspace.originRemote, ...(upstreamRemote ? [upstreamRemote] : [])];
      await window.bridge.gitFetch(workspace.id, remotes);
      const now = Date.now();
      setSyncedAt(now);
      localStorage.setItem(`bytequay.lastSync.${workspace.id}`, String(now));
      setLogVersion(version => version + 1);
      refreshOverview();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSyncing(false);
    }
  };

  const openFlow = async () => {
    if (!hasUpstream) {
      onOpenSettings();
      return;
    }
    if (!span) return;
    setPreviewBusy(true);
    setError(null);
    try {
      const oldest = commits[span.hi].sha;
      const newest = commits[span.lo].sha;
      const next = await window.bridge.previewCherryPickRange({
        workspaceId: workspace.id,
        sourceBaseRef: `${oldest}^`,
        sourceTipRef: newest,
        targetBranch: branch,
      });
      setPreview(next);
      setScreen('flow');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setPreviewBusy(false);
    }
  };

  const wsChipClick = () => {
    if (screen !== 'commits' || isUpstream) {
      setScreen('commits');
      setRepo('workspace');
      setBranchMenu(false);
      setUpMenu(false);
      return;
    }
    setBranchMenu(open => !open);
    setUpMenu(false);
  };

  const upChipClick = () => {
    if (!hasUpstream) {
      onOpenSettings();
      return;
    }
    if (screen !== 'commits' || !isUpstream) {
      setScreen('commits');
      setRepo('upstream');
      setCommitsView('history');
      setBranchMenu(false);
      setUpMenu(false);
      return;
    }
    setUpMenu(open => !open);
    setBranchMenu(false);
  };

  const showTabBar = screen !== 'progress';
  const filteredUpBranches = upBranches.filter(name =>
    name.toLowerCase().includes(upBranchQuery.trim().toLowerCase()));

  return (
    <div className="cmt-root">
      {showTabBar && (
        <div className="cmt-tab-bar">
          <div className="cmt-chip-wrap" onMouseDown={event => event.stopPropagation()}>
            <button
              type="button"
              className={`cmt-repo-chip${!isUpstream ? ' is-active' : ''}`}
              title="This workspace's branch — click again to switch branch"
              onClick={wsChipClick}
            >
              <span className="cmt-mono">⑂</span>
              <span className="cmt-repo-chip__label">{branch}</span>
              <span className="cmt-caret">▾</span>
            </button>
            {branchMenu && (
              <div className="cmt-menu">
                <div className="cmt-menu-label">
                  {isUpstream ? `TARGET BRANCH — ${workspace.name}` : 'SWITCH BRANCH'}
                </div>
                {(overview?.branches ?? []).map(name => (
                  <button
                    type="button"
                    key={name}
                    title={name}
                    className={`cmt-menu-item${name === branch ? ' is-current' : ''}`}
                    onClick={() => {
                      setBranch(name);
                      setBranchMenu(false);
                      setCheckedWs(new Set());
                    }}
                  >
                    {name === branch ? '✓' : ' '} {name}
                  </button>
                ))}
              </div>
            )}
          </div>

          {hasUpstream ? (
            <div className="cmt-chip-wrap" onMouseDown={event => event.stopPropagation()}>
              <button
                type="button"
                className={`cmt-repo-chip${isUpstream ? ' is-active' : ''}`}
                title="Remote source — read-only, cherry-pick source — click again to switch branch"
                onClick={upChipClick}
              >
                <span className="cmt-mono">↞</span>
                <span className="cmt-repo-chip__label">
                  {upstreamSlug ?? sourceRemote} · {upBranch}
                </span>
                <span className="cmt-readonly">read-only</span>
                <span className="cmt-caret">▾</span>
              </button>
              {upMenu && (
                <div className="cmt-menu is-wide">
                  <div className="cmt-menu-search">
                    <input
                      value={upBranchQuery}
                      placeholder="⌕ Find an upstream branch…"
                      onChange={event => setUpBranchQuery(event.target.value)}
                    />
                  </div>
                  {filteredUpBranches.slice(0, 30).map(name => (
                    <button
                      type="button"
                      key={name}
                      title={name}
                      className={`cmt-menu-item${name === upBranch ? ' is-current' : ''}`}
                      onClick={() => {
                        setUpBranch(name);
                        setUpMenu(false);
                        setUpBranchQuery('');
                        setCheckedUp(new Set());
                      }}
                    >
                      {name === upBranch ? '✓' : ' '} {name}
                    </button>
                  ))}
                  {upBranches.length === 0 && <div className="cmt-menu-label">Loading branches…</div>}
                </div>
              )}
            </div>
          ) : (
            <div className="cmt-chip-wrap">
              <button
                type="button"
                className="cmt-repo-chip"
                title="Link another watched workspace"
                onClick={onOpenSettings}
              >
                <span className="cmt-mono">↞</span>
                <span className="cmt-repo-chip__label">No upstream linked</span>
                <span className="cmt-readonly">Settings</span>
              </button>
            </div>
          )}

          <div className="cmt-sync">
            <button
              type="button"
              title="Fetch latest for this branch and the read-only upstream branch"
              onClick={() => { void sync(); }}
            >
              <span className={syncing ? 'is-spinning' : ''}>⟳</span>
            </button>
            <span>
              {syncing ? 'syncing…' : syncedAt ? `synced ${relativeTime(syncedAt)}` : 'not synced yet'}
            </span>
          </div>

          <div className="cmt-tab-divider" />

          <button
            type="button"
            className={`cmt-tab${screen === 'commits' && commitsView === 'changes' ? ' is-active' : ''}${isUpstream ? ' is-disabled' : ''}`}
            onClick={() => {
              if (isUpstream) return;
              setScreen('commits');
              setCommitsView('changes');
              setBranchMenu(false);
            }}
          >
            Changes
            {!isUpstream && changesCount !== null && changesCount > 0 && (
              <span className="cmt-tab-badge">{changesCount}</span>
            )}
          </button>
          <button
            type="button"
            className={`cmt-tab${screen === 'commits' && commitsView === 'history' ? ' is-active' : ''}`}
            onClick={() => {
              setScreen('commits');
              setCommitsView('history');
              setBranchMenu(false);
            }}
          >
            History
          </button>
          <button
            type="button"
            className={`cmt-tab${screen === 'tasks' || screen === 'flow' ? ' is-active' : ''}`}
            onClick={() => { setScreen('tasks'); setBranchMenu(false); setUpMenu(false); }}
          >
            Cherry pick
          </button>
        </div>
      )}

      {error && screen === 'commits' && <div className="cmt-error" role="alert">{error}</div>}

      {screen === 'commits' && commitsView === 'changes' && !isUpstream && (
        <ChangesView
          workspaceId={workspace.id}
          onCommitted={() => setLogVersion(version => version + 1)}
        />
      )}
      {screen === 'commits' && (commitsView === 'history' || isUpstream) && (
        <HistoryView
          workspaceId={workspace.id}
          branch={branch}
          logRef={logRef}
          isUpstream={isUpstream}
          canRewrite={!isUpstream && overview?.currentBranch === branch}
          commits={commits}
          loading={logLoading}
          checked={checked}
          pullRequests={pullRequests}
          onChangeChecked={next => (isUpstream ? setCheckedUp(next) : setCheckedWs(next))}
          onHistoryRewritten={() => setLogVersion(version => version + 1)}
          onOpenPullRequest={onOpenPullRequest}
        />
      )}
      {screen === 'tasks' && (
        <TasksView
          workspaceId={workspace.id}
          onOpenTask={execution => {
            setExecutionId(execution.executionId);
            setScreen('progress');
          }}
          onNewCherryPick={() => {
            if (!hasUpstream) {
              onOpenSettings();
              return;
            }
            setScreen('commits');
            setRepo('upstream');
            setCommitsView('history');
          }}
        />
      )}
      {screen === 'flow' && preview && (
        <FlowView
          preview={preview}
          workspaceId={workspace.id}
          upstreamLabel={upstreamSlug ?? sourceRemote}
          onStarted={execution => {
            setExecutionId(execution.executionId);
            setPreview(null);
            setCheckedUp(new Set());
            setScreen('progress');
          }}
        />
      )}
      {screen === 'progress' && executionId !== null && (
        <ProgressView
          executionId={executionId}
          workspaceId={workspace.id}
          upstreamSlug={upstreamSlug}
          onBackToTasks={() => setScreen('tasks')}
          onStartAnotherRange={() => {
            if (!hasUpstream) {
              onOpenSettings();
              return;
            }
            setScreen('commits');
            setRepo('upstream');
            setCommitsView('history');
          }}
        />
      )}

      {isUpstream && screen === 'commits' && span !== null && (
        <div className="cmt-action-bar">
          <span className="cmt-action-count">{span.hi - span.lo + 1} commits selected</span>
          <span className="cmt-action-range">
            {shortSha(commits[span.hi].sha)}…{shortSha(commits[span.lo].sha)}
          </span>
          <span className="cmt-action-target">→ {originSlug ?? workspace.name} · {branch}</span>
          <button
            type="button"
            disabled={previewBusy}
            onClick={() => { void openFlow(); }}
          >
            {previewBusy ? 'Preparing preview…' : 'Cherry-pick to workspace →'}
          </button>
        </div>
      )}
    </div>
  );
}
