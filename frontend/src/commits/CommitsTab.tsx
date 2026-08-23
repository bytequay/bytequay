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
import type {
  CherryPickPreview,
  CommitHistoryRefDto,
  CommitHistorySourceDto,
  CommitRefreshStatusDto,
  GitCommitDto,
  GitOverviewDto,
} from '../types';
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

export function commitRefreshRefs(
  branch: string,
  upBranch: string,
  hasUpstream: boolean,
  extraRemote?: { name: string; branch: string },
): CommitHistoryRefDto[] {
  return [
    { source: 'WORKSPACE', branch },
    ...(hasUpstream ? [{ source: 'UPSTREAM' as const, branch: upBranch }] : []),
    ...(extraRemote
      ? [{ source: 'REMOTE' as const, branch: extraRemote.branch, remote: extraRemote.name }]
      : []),
  ];
}

export function isCommitRefreshActive(status: CommitRefreshStatusDto | null): boolean {
  return status?.state === 'QUEUED' || status?.state === 'RUNNING';
}

export function commitSyncLabel(status: CommitRefreshStatusDto | null): string {
  if (isCommitRefreshActive(status)) return 'syncing…';
  if (status?.state === 'FAILED') {
    return status.lastSucceededAt
      ? `sync failed · last synced ${relativeTime(status.lastSucceededAt)}`
      : 'sync failed';
  }
  return status?.lastSucceededAt
    ? `synced ${relativeTime(status.lastSucceededAt)}`
    : 'not synced yet';
}

export function selectableCommitSources(
  remotes: GitOverviewDto['remotes'],
  originRemote: string,
): GitOverviewDto['remotes'] {
  return remotes.filter(remote => remote.name !== originRemote);
}

export default function CommitsTab({ workspace, initialExecutionId, initialRepo, onOpenPullRequest, onOpenSettings }: {
  workspace: Workspace;
  /** Jump straight to a task's progress page (from the shell's Tasks tab). */
  initialExecutionId?: string | null;
  /** Open on the upstream commit browser (from the Home cherry-pick card). */
  initialRepo?: 'workspace' | 'upstream';
  onOpenPullRequest: (number: number) => void;
  onOpenSettings: () => void;
}) {
  const [overview, setOverview] = useState<GitOverviewDto | null>(null);
  const [screen, setScreen] = useState<Screen>(initialExecutionId ? 'progress' : 'commits');
  const [commitsView, setCommitsView] = useState<CommitsView>('history');
  const [source, setSource] = useState<string | null>(initialRepo === 'upstream' ? 'upstream' : null);
  const [viewingSource, setViewingSource] = useState(initialRepo === 'upstream');
  const [branch, setBranch] = useState(workspace.defaultBranch);
  const [upBranch, setUpBranch] = useState(workspace.upstreamDefaultBranch ?? workspace.defaultBranch);
  const [branchMenu, setBranchMenu] = useState(false);
  const [sourceMenu, setSourceMenu] = useState(false);
  const [sourceBranchMenu, setSourceBranchMenu] = useState(false);
  const [upBranchQuery, setUpBranchQuery] = useState('');
  const [upBranches, setUpBranches] = useState<string[]>([]);
  const [commits, setCommits] = useState<GitCommitDto[]>([]);
  const [logLoading, setLogLoading] = useState(true);
  const [checkedWs, setCheckedWs] = useState<Set<string>>(new Set());
  const [checkedUp, setCheckedUp] = useState<Set<string>>(new Set());
  const [changesCount, setChangesCount] = useState<number | null>(null);
  const [refreshStatus, setRefreshStatus] = useState<CommitRefreshStatusDto | null>(null);
  const [manualPolling, setManualPolling] = useState(false);
  const [preview, setPreview] = useState<CherryPickPreview | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [executionId, setExecutionId] = useState<string | null>(initialExecutionId ?? null);
  const [error, setError] = useState<string | null>(null);
  const [logVersion, setLogVersion] = useState(0);

  const hasUpstream = workspace.upstreamRepositoryId !== null;
  const remoteSources = selectableCommitSources(overview?.remotes ?? [], workspace.originRemote);
  /** A non-origin remote being browsed read-only, or null for the local checkout. */
  const activeRemote = viewingSource ? source : null;
  const isRemote = activeRemote !== null;
  const isUpstream = activeRemote === 'upstream' && hasUpstream;
  const upstreamSlug = hasUpstream && workspace.upstreamOwner && workspace.upstreamRepo
    ? `${workspace.upstreamOwner}/${workspace.upstreamRepo}`
    : null;
  const originUrl = overview?.remotes.find(remote => remote.name === workspace.originRemote)?.url ?? null;
  const originSlug = originUrl ? githubSlug(originUrl) : null;
  const firstExtraRemote = remoteSources.find(remote => remote.name !== 'upstream')?.name ?? null;
  const activeRemoteUrl = activeRemote === null
    ? null
    : overview?.remotes.find(remote => remote.name === activeRemote)?.url ?? null;
  const sourceSlug = activeRemoteUrl ? githubSlug(activeRemoteUrl) : null;
  const logRef = isRemote ? `${activeRemote}/${upBranch}` : branch;
  const historySource: CommitHistorySourceDto = isUpstream ? 'UPSTREAM' : isRemote ? 'REMOTE' : 'WORKSPACE';
  const historyBranch = isRemote ? upBranch : branch;
  const historyRemote = historySource === 'REMOTE' ? activeRemote ?? undefined : undefined;
  const checked = isRemote ? checkedUp : checkedWs;
  const syncing = isCommitRefreshActive(refreshStatus);

  const refreshOverview = useCallback(() => {
    window.bridge.gitOverview(workspace.id)
      .then(setOverview)
      .catch(failure => setError(failure instanceof Error ? failure.message : String(failure)));
  }, [workspace.id]);

  useEffect(() => { refreshOverview(); }, [refreshOverview]);

  useEffect(() => {
    if (!hasUpstream && source === 'upstream') {
      setSource(firstExtraRemote);
      setViewingSource(firstExtraRemote !== null);
      return;
    }
    setUpBranch(source === 'upstream'
      ? workspace.upstreamDefaultBranch ?? workspace.defaultBranch
      : workspace.defaultBranch);
    setUpBranches([]);
    setCheckedUp(new Set());
  }, [hasUpstream, source, firstExtraRemote, workspace.defaultBranch, workspace.upstreamDefaultBranch]);

  // Menus close on any outside press; the chip wrappers stop propagation.
  useEffect(() => {
    if (!branchMenu && !sourceMenu && !sourceBranchMenu) return;
    const close = () => {
      setBranchMenu(false);
      setSourceMenu(false);
      setSourceBranchMenu(false);
    };
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [branchMenu, sourceMenu, sourceBranchMenu]);

  useEffect(() => {
    let cancelled = false;
    setLogLoading(true);
    window.bridge.gitLog(workspace.id, historySource, historyBranch, 300, historyRemote)
      .then(snapshot => {
        if (cancelled) return;
        setCommits(snapshot.commits);
        setRefreshStatus(snapshot.refresh);
        setError(null);
      })
      .catch(failure => {
        if (cancelled) return;
        setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => {
        if (!cancelled) setLogLoading(false);
    });
    return () => { cancelled = true; };
  }, [workspace.id, historySource, historyBranch, historyRemote, logVersion]);

  useEffect(() => {
    setManualPolling(false);
  }, [workspace.id, historySource, historyBranch, historyRemote]);

  useEffect(() => {
    if (!manualPolling || !isCommitRefreshActive(refreshStatus)) return;
    let cancelled = false;
    const timeout = window.setTimeout(() => {
      window.bridge.gitLog(workspace.id, historySource, historyBranch, 300, historyRemote)
        .then(snapshot => {
          if (cancelled) return;
          setCommits(snapshot.commits);
          setRefreshStatus(snapshot.refresh);
          if (!isCommitRefreshActive(snapshot.refresh)) {
            setManualPolling(false);
            if (snapshot.refresh.state === 'FAILED') {
              setError(snapshot.refresh.error ?? 'Commit history refresh failed');
            }
            else {
              setError(null);
              refreshOverview();
            }
          }
        })
        .catch(failure => {
          if (cancelled) return;
          setManualPolling(false);
          setError(failure instanceof Error ? failure.message : String(failure));
        });
    }, 1_000);
    return () => {
      cancelled = true;
      window.clearTimeout(timeout);
    };
  }, [
    manualPolling,
    refreshStatus,
    workspace.id,
    historySource,
    historyBranch,
    historyRemote,
    refreshOverview,
  ]);

  useEffect(() => {
    if (isRemote) return;
    let cancelled = false;
    window.bridge.gitStatus(workspace.id)
      .then(status => { if (!cancelled) setChangesCount(status.files.length); })
      .catch(() => { if (!cancelled) setChangesCount(null); });
    return () => { cancelled = true; };
  }, [workspace.id, isRemote, logVersion, commitsView]);

  useEffect(() => {
    if (activeRemote === null || !sourceBranchMenu || upBranches.length > 0) return;
    let cancelled = false;
    window.bridge.gitRemoteBranches(workspace.id, activeRemote)
      .then(value => { if (!cancelled) setUpBranches(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [activeRemote, sourceBranchMenu, upBranches.length, workspace.id]);

  const span = useMemo(() => selectedSpan(commits, checked), [commits, checked]);

  const sync = async () => {
    if (syncing) return;
    setError(null);
    try {
      const upstreamBranch = isUpstream
        ? upBranch
        : workspace.upstreamDefaultBranch ?? workspace.defaultBranch;
      const status = await window.bridge.refreshCommitHistory(
        workspace.id,
        commitRefreshRefs(
          branch,
          upstreamBranch,
          hasUpstream,
          activeRemote !== null && activeRemote !== 'upstream'
            ? { name: activeRemote, branch: upBranch }
            : undefined,
        ),
      );
      setRefreshStatus(status);
      if (isCommitRefreshActive(status)) {
        setManualPolling(true);
      }
      else {
        setLogVersion(version => version + 1);
        if (status.state === 'FAILED') {
          setError(status.error ?? 'Commit history refresh failed');
        }
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  };

  const openFlow = async () => {
    if (activeRemote === null || !span) return;
    setPreviewBusy(true);
    setError(null);
    try {
      const oldest = commits[span.hi].sha;
      const newest = commits[span.lo].sha;
      const next = await window.bridge.previewCherryPickRange({
        workspaceId: workspace.id,
        sourceRemote: activeRemote,
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
    if (screen !== 'commits' || isRemote) {
      setScreen('commits');
      setViewingSource(false);
      setBranchMenu(false);
      setSourceMenu(false);
      setSourceBranchMenu(false);
      return;
    }
    setBranchMenu(open => !open);
    setSourceMenu(false);
    setSourceBranchMenu(false);
  };

  const activateSource = () => {
    if (source === null) {
      setSourceMenu(true);
    }
    else {
      setScreen('commits');
      setCommitsView('history');
      setViewingSource(true);
    }
    setBranchMenu(false);
    setSourceBranchMenu(false);
  };

  const sourceChipClick = () => {
    setSourceMenu(open => !open);
    setBranchMenu(false);
    setSourceBranchMenu(false);
  };

  const sourceBranchChipClick = () => {
    setScreen('commits');
    setCommitsView('history');
    setViewingSource(true);
    setSourceBranchMenu(open => !open);
    setBranchMenu(false);
    setSourceMenu(false);
  };

  // Choosing a source flows straight into choosing its branch.
  const pickSource = (name: string) => {
    setScreen('commits');
    setSourceMenu(false);
    setSourceBranchMenu(true);
    setSource(name);
    setViewingSource(true);
    setCommitsView('history');
  };

  // Cherry-pick reads from the browsed remote source; with none selected it
  // defaults to the linked upstream, then the first extra remote.
  const startCherryPick = () => {
    const cherryPickSource = hasUpstream ? 'upstream' : firstExtraRemote;
    if (cherryPickSource === null) {
      onOpenSettings();
      return;
    }
    setScreen('commits');
    setSource(cherryPickSource);
    setViewingSource(true);
    setCommitsView('history');
  };

  const remoteDescription = (name: string, url: string) => {
    const slug = githubSlug(url);
    if (name === 'upstream' && hasUpstream) return `${slug ?? url} · linked upstream`;
    return slug ?? url;
  };

  const sourceLabel = source ?? 'Select source';
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
              className={`cmt-repo-chip${!isRemote ? ' is-active' : ''}`}
              title="This workspace's branch — click again to switch branch"
              aria-pressed={!isRemote}
              onClick={wsChipClick}
            >
              <span className="cmt-mono">⑂</span>
              <span className="cmt-repo-chip__label">{branch}</span>
              <span className="cmt-caret">▾</span>
            </button>
            {branchMenu && (
              <div className="cmt-menu">
                <div className="cmt-menu-label">SWITCH BRANCH</div>
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

          <div className="cmt-chip-wrap" onMouseDown={event => event.stopPropagation()}>
            <div className={`cmt-split-chip${isRemote ? ' is-active' : ''}`}>
              <button
                type="button"
                className="cmt-split-chip__seg"
                title={source === null
                  ? 'Choose a remote source for history and cherry-pick'
                  : 'View the selected remote source'}
                aria-pressed={isRemote}
                onClick={activateSource}
              >
                <span className="cmt-mono">⇅</span>
                <span className="cmt-repo-chip__label">Source: {sourceLabel}</span>
                {isRemote && <span className="cmt-readonly">read-only</span>}
              </button>
              <button
                type="button"
                className="cmt-split-chip__seg cmt-split-chip__caret"
                title="Choose remote source"
                aria-label="Choose remote source"
                onClick={sourceChipClick}
              >
                <span className="cmt-caret">▾</span>
              </button>
              {source !== null && (
                <button
                  type="button"
                  className="cmt-split-chip__seg"
                  title="Branch of the selected source"
                  onClick={sourceBranchChipClick}
                >
                  <span className="cmt-mono">⑂</span>
                  <span className="cmt-repo-chip__label">{upBranch}</span>
                  <span className="cmt-caret">▾</span>
                </button>
              )}
            </div>
            {sourceMenu && (
              <div className="cmt-menu is-wide">
                <div className="cmt-menu-label">
                  REMOTE SOURCE — HISTORY &amp; CHERRY-PICK READ FROM HERE
                </div>
                {remoteSources.map(remote => (
                  <button
                    type="button"
                    key={remote.name}
                    title={remote.url}
                    className={`cmt-menu-item cmt-source-item${source === remote.name ? ' is-current' : ''}`}
                    onClick={() => pickSource(remote.name)}
                  >
                    <span className="cmt-source-name cmt-mono">
                      {source === remote.name ? '✓ ' : ''}{remote.name}
                    </span>
                    <span className="cmt-source-desc">{remoteDescription(remote.name, remote.url)}</span>
                  </button>
                ))}
                {overview === null && <div className="cmt-menu-label">Loading remotes…</div>}
                {overview !== null && remoteSources.length === 0 && (
                  <div className="cmt-menu-label">No remote sources configured</div>
                )}
                <div className="cmt-menu-divider" />
                <button
                  type="button"
                  className="cmt-menu-item cmt-source-item"
                  onClick={() => {
                    setSourceMenu(false);
                    onOpenSettings();
                  }}
                >
                  <span className="cmt-source-name">⚙ Manage remote sources…</span>
                  <span className="cmt-source-desc">in Workspace settings</span>
                </button>
              </div>
            )}
            {sourceBranchMenu && (
              <div className="cmt-menu is-wide">
                <div className="cmt-menu-label">SWITCH BRANCH — {sourceLabel}</div>
                <div className="cmt-menu-search">
                  <input
                    value={upBranchQuery}
                    placeholder="⌕ Find a branch…"
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
                      setSourceBranchMenu(false);
                      setUpBranchQuery('');
                      setCheckedUp(new Set());
                    }}
                  >
                    {name === upBranch ? '✓' : ' '} {name}
                  </button>
                ))}
                {upBranches.length === 0 && <div className="cmt-menu-label">Loading branches…</div>}
              </div>
            )}
          </div>

          <div className="cmt-sync">
            <button
              type="button"
              title="Fetch latest for this branch and the selected remote source"
              disabled={syncing}
              onClick={() => { void sync(); }}
            >
              <span className={syncing ? 'is-spinning' : ''}>⟳</span>
            </button>
            <span title={refreshStatus?.error ?? undefined}>{commitSyncLabel(refreshStatus)}</span>
          </div>

          <div className="cmt-tab-divider" />

          <button
            type="button"
            className={`cmt-tab${screen === 'commits' && commitsView === 'changes' ? ' is-active' : ''}${isRemote ? ' is-disabled' : ''}`}
            onClick={() => {
              if (isRemote) return;
              setScreen('commits');
              setCommitsView('changes');
              setBranchMenu(false);
            }}
          >
            Changes
            {!isRemote && changesCount !== null && changesCount > 0 && (
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
            onClick={() => { setScreen('tasks'); setBranchMenu(false); setSourceMenu(false); setSourceBranchMenu(false); }}
          >
            Cherry pick
          </button>
        </div>
      )}

      {error && screen === 'commits' && <div className="cmt-error" role="alert">{error}</div>}

      {screen === 'commits' && commitsView === 'changes' && !isRemote && (
        <ChangesView
          workspaceId={workspace.id}
          onCommitted={() => setLogVersion(version => version + 1)}
        />
      )}
      {screen === 'commits' && (commitsView === 'history' || isRemote) && (
        <HistoryView
          workspaceId={workspace.id}
          branch={branch}
          logRef={logRef}
          isUpstream={isRemote}
          canRewrite={!isRemote && overview?.currentBranch === branch}
          commits={commits}
          loading={logLoading}
          checked={checked}
          onChangeChecked={next => (isRemote ? setCheckedUp(next) : setCheckedWs(next))}
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
          onNewCherryPick={startCherryPick}
        />
      )}
      {screen === 'flow' && preview && (
        <FlowView
          preview={preview}
          workspaceId={workspace.id}
          upstreamLabel={sourceSlug ?? activeRemote ?? upstreamSlug ?? 'upstream'}
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
          onStartAnotherRange={startCherryPick}
        />
      )}

      {isRemote && screen === 'commits' && span !== null && (
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
