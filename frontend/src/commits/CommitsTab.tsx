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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import { remoteBranchCorrection, selectedSpan, shortSha, spanFromShas } from './model';
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

export function commitSyncLabel(status: CommitRefreshStatusDto | null): string {
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
  /** The user chose this source's branch themselves; stop steering to its default. */
  const [upBranchPicked, setUpBranchPicked] = useState(false);
  const [commits, setCommits] = useState<GitCommitDto[]>([]);
  const [logLoading, setLogLoading] = useState(true);
  const [checkedWs, setCheckedWs] = useState<Set<string>>(new Set());
  const [checkedUp, setCheckedUp] = useState<Set<string>>(new Set());
  const [changesCount, setChangesCount] = useState<number | null>(null);
  const [refreshStatus, setRefreshStatus] = useState<CommitRefreshStatusDto | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [preview, setPreview] = useState<CherryPickPreview | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [executionId, setExecutionId] = useState<string | null>(initialExecutionId ?? null);
  const [error, setError] = useState<string | null>(null);
  const [logVersion, setLogVersion] = useState(0);
  const [rangeFrom, setRangeFrom] = useState('');
  const [rangeTo, setRangeTo] = useState('');
  const [rangeError, setRangeError] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  /** The one ref an unreadable remote log already auto-synced for. */
  const autoSyncAttempt = useRef<string | null>(null);

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
  const activeRemoteHead = activeRemote === null
    ? null
    : overview?.remotes.find(remote => remote.name === activeRemote)?.headBranch ?? null;
  const sourceSlug = activeRemoteUrl ? githubSlug(activeRemoteUrl) : null;
  const logRef = isRemote ? `${activeRemote}/${upBranch}` : branch;
  const historySource: CommitHistorySourceDto = isUpstream ? 'UPSTREAM' : isRemote ? 'REMOTE' : 'WORKSPACE';
  const historyBranch = isRemote ? upBranch : branch;
  const historyRemote = historySource === 'REMOTE' ? activeRemote ?? undefined : undefined;
  const checked = isRemote ? checkedUp : checkedWs;
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
    setUpBranchPicked(false);
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
    if (isRemote) return;
    let cancelled = false;
    window.bridge.gitStatus(workspace.id)
      .then(status => { if (!cancelled) setChangesCount(status.files.length); })
      .catch(() => { if (!cancelled) setChangesCount(null); });
    return () => { cancelled = true; };
  }, [workspace.id, isRemote, logVersion, commitsView]);

  // A browsed remote's branches load as soon as it becomes active (not only
  // when its menu opens), and reload after every sync in case a fetch just
  // brought the remote-tracking refs in.
  useEffect(() => {
    if (activeRemote === null) return;
    let cancelled = false;
    window.bridge.gitRemoteBranches(workspace.id, activeRemote)
      .then(value => { if (!cancelled) setUpBranches(value); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [activeRemote, workspace.id, logVersion]);

  // The branch defaults to the workspace's default, which may not even exist
  // on the source; steer to the source's detected default branch (its HEAD,
  // resolved during sync) until the user picks a branch themselves.
  useEffect(() => {
    if (activeRemote === null) return;
    const correction = remoteBranchCorrection(upBranches, upBranch, activeRemoteHead, upBranchPicked);
    if (correction === null) return;
    setUpBranch(correction);
    setCheckedUp(new Set());
  }, [activeRemote, upBranches, upBranch, activeRemoteHead, upBranchPicked]);

  const span = useMemo(() => selectedSpan(commits, checked), [commits, checked]);

  const canApplyRange = rangeFrom.trim() !== '' && rangeTo.trim() !== '';
  const applyRange = () => {
    if (!canApplyRange) return;
    const result = spanFromShas(commits, rangeFrom, rangeTo);
    if ('error' in result) {
      setRangeError(true);
      return;
    }
    setRangeError(false);
    (isRemote ? setCheckedUp : setCheckedWs)(new Set(result.anchors));
  };

  const sync = async () => {
    if (syncing) return;
    setSyncing(true);
    setError(null);
    try {
      const upstreamBranch = isUpstream
        ? upBranch
        : workspace.upstreamDefaultBranch ?? workspace.defaultBranch;
      // The extra-remote ref is only validated when its branch is known to
      // exist; the fetch itself covers every configured remote regardless, so
      // a fresh remote (branches unknown) syncs green instead of failing on a
      // guessed branch.
      const status = await window.bridge.refreshCommitHistory(
        workspace.id,
        commitRefreshRefs(
          branch,
          upstreamBranch,
          hasUpstream,
          activeRemote !== null && activeRemote !== 'upstream' && upBranches.includes(upBranch)
            ? { name: activeRemote, branch: upBranch }
            : undefined,
        ),
      );
      setRefreshStatus(status);
      setLogVersion(version => version + 1);
      if (status.state === 'FAILED') {
        setError(status.error ?? 'Commit history refresh failed');
      }
      else {
        refreshOverview();
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSyncing(false);
    }
  };

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
        // Never leave the previous ref's commits on screen: a reader would
        // take them for the selected source's history.
        setCommits([]);
        setError(failure instanceof Error ? failure.message : String(failure));
        // A browsed remote ref usually fails because it was never fetched;
        // sync once for it (a fetch), which bumps logVersion and retries.
        const attempt = `${historySource}:${historyRemote ?? ''}:${historyBranch}`;
        if (historySource !== 'WORKSPACE' && autoSyncAttempt.current !== attempt) {
          autoSyncAttempt.current = attempt;
          void sync();
        }
      })
      .finally(() => {
        if (!cancelled) setLogLoading(false);
    });
    return () => { cancelled = true; };
    // `sync` is deliberately omitted: it is remade every render and only the
    // error path calls it; depending on it would refetch on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace.id, historySource, historyBranch, historyRemote, logVersion]);

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
  const historyVisible = screen === 'commits' && (commitsView === 'history' || isRemote);
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
              <span className="cmt-repo-chip__label cmt-mono">{branch}</span>
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
                  <span className="cmt-repo-chip__label cmt-mono">{upBranch}</span>
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
                      setUpBranchPicked(true);
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

          {historyVisible && (
            <div className="cmt-chip-wrap">
              <details className="cmt-range">
                <summary title="Select a commit range by SHA">⇲ select range</summary>
                <div className="cmt-range-panel">
                  <input
                    value={rangeFrom}
                    placeholder="from SHA"
                    onChange={event => { setRangeFrom(event.target.value); setRangeError(false); }}
                  />
                  <span className="cmt-range-sep">…</span>
                  <input
                    value={rangeTo}
                    placeholder="to SHA"
                    onChange={event => { setRangeTo(event.target.value); setRangeError(false); }}
                  />
                  <button type="button" disabled={!canApplyRange} onClick={applyRange}>Select range</button>
                  {rangeError && <span className="cmt-range-error">SHA not on this branch</span>}
                </div>
              </details>
            </div>
          )}
          <div className="cmt-sync">
            <button
              type="button"
              title="Fetch latest for this branch and the selected remote source"
              disabled={syncing}
              onClick={() => { void sync(); }}
            >
              <span className={syncing ? 'is-spinning' : ''}>⟳</span>
            </button>
            <span title={refreshStatus?.error ?? undefined}>
              {syncing ? 'syncing…' : commitSyncLabel(refreshStatus)}
            </span>
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

      {showTabBar && historyVisible && (
        <div className="cmt-search">
          <span>⌕</span>
          <input
            value={searchQuery}
            placeholder="Search — PR title, #number, or SHA"
            onChange={event => setSearchQuery(event.target.value)}
          />
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
          filter={searchQuery}
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
