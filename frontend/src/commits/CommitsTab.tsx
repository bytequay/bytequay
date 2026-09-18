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
import { ChevronDown, GitBranch, GitCompareArrows, ListChecks, RefreshCw, Search } from 'lucide-react';
import { relativeTime } from '../relativeTime';
import type {
  CherryPickPreview,
  CommitHistoryRefDto,
  CommitHistorySourceDto,
  CommitRefreshStatusDto,
  GitCommitDto,
  GitOverviewDto,
  WorkspaceCommitsTargetDto,
  WorkspaceDto,
  WorkspaceSettingsDto,
} from '../types';
import { githubSlug } from '../workspaces/model';
import ChangesView from './ChangesView';
import FlowView from './FlowView';
import HistoryView from './HistoryView';
import ProgressView from './ProgressView';
import TasksView from './TasksView';
import { remoteBranchCorrection, selectedSpan, shortSha, spanFromShas } from './model';
import '../css/commits.css';

type Screen = 'commits' | 'flow' | 'tasks' | 'progress';
type CommitsView = 'history' | 'changes';
const COMMIT_REFRESH_POLL_INTERVAL_MS = 5_000;

export type CommitSourceSelection = {
  source: string | null;
};

export function commitRefreshRefs(
  target: WorkspaceCommitsTargetDto,
  source?: CommitHistoryRefDto,
): CommitHistoryRefDto[] {
  const targetRef = commitTargetRef(target);
  return source && (source.source !== targetRef.source || source.branch !== targetRef.branch)
    ? [targetRef, source]
    : [targetRef];
}

export function commitTargetRef(target: WorkspaceCommitsTargetDto): CommitHistoryRefDto {
  return { source: target.remote === 'upstream' ? 'UPSTREAM' : 'WORKSPACE', branch: target.branch };
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

export function commitRefreshChanged(
  knownAttemptAt: string | null | undefined,
  status: CommitRefreshStatusDto,
): boolean {
  return (status.lastAttemptAt ?? null) !== (knownAttemptAt ?? null);
}

export function selectableCommitSources(
  remotes: GitOverviewDto['remotes'],
  originRemote: string,
): GitOverviewDto['remotes'] {
  return remotes.filter(remote => remote.name !== originRemote);
}

export function availableCommitSource(
  source: string | null,
  remoteNames: readonly string[],
  hasUpstream: boolean,
): string | null {
  if (source === null) return null;
  if (source === 'upstream') {
    return hasUpstream ? source : remoteNames.find(name => name !== 'upstream') ?? null;
  }
  return remoteNames.includes(source) ? source : null;
}

type CommitsTabProps = {
  workspace: WorkspaceDto;
  /** Jump straight to a task's progress page (from the shell's Tasks tab). */
  initialExecutionId?: string | null;
  /** Open on the upstream commit browser (from the Home cherry-pick card). */
  initialRepo?: 'workspace' | 'upstream';
  initialSourceSelection?: CommitSourceSelection;
  onSourceSelectionChange: (selection: CommitSourceSelection) => void;
  onOpenPullRequest: (number: number) => void;
  onOpenSettings: () => void;
};

export default function CommitsTab(props: CommitsTabProps) {
  const [settings, setSettings] = useState<WorkspaceSettingsDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [screen, setScreen] = useState<Screen>(props.initialExecutionId ? 'progress' : 'commits');
  const [initialRepo, setInitialRepo] = useState(props.initialRepo);
  useEffect(() => {
    let cancelled = false;
    setError(null);
    window.bridge.getWorkspaceSettings(props.workspace.id)
      .then(value => { if (!cancelled) setSettings(value); })
      .catch(failure => { if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { cancelled = true; };
  }, [props.workspace.id, retry]);

  if (screen === 'progress' && props.initialExecutionId) {
    const { workspace } = props;
    return <div className="cmt-root"><ProgressView
      executionId={props.initialExecutionId}
      workspaceId={workspace.id}
      upstreamSlug={workspace.upstreamRepositoryId === null ? null : `${workspace.upstreamOwner}/${workspace.upstreamRepo}`}
      onBackToTasks={() => setScreen('tasks')}
      onStartAnotherRange={() => { setInitialRepo('upstream'); setScreen('commits'); }}
    /></div>;
  }

  // Do not read history or prepare a cherry-pick against a guessed target
  // while the saved workspace override is still unknown.
  if (settings === null) {
    return <div className="cmt-root">
      {error === null ? <div className="cmt-loading">Loading commits target…</div> : <div className="cmt-error" role="alert">
        Could not load the commits target: {error}
        {' '}<button type="button" onClick={() => setRetry(value => value + 1)}>Retry</button>
        {' '}<button type="button" onClick={props.onOpenSettings}>Workspace settings</button>
      </div>}
    </div>;
  }
  return <CommitsBrowser {...props} settings={settings} initialExecutionId={null} initialRepo={initialRepo} initialScreen={screen} />;
}

export function CommitsBrowser({ workspace, settings, initialExecutionId, initialScreen = 'commits', initialRepo, initialSourceSelection, onSourceSelectionChange, onOpenPullRequest, onOpenSettings }:
  CommitsTabProps & { settings: Pick<WorkspaceSettingsDto, 'commitsTarget' | 'remotes'>; initialScreen?: Screen }) {
  const hasUpstream = workspace.upstreamRepositoryId !== null;
  const target = settings.commitsTarget;
  const targetUpstream = target.remote === 'upstream';
  const initialSelection = initialSourceSelection ?? {
    source: hasUpstream ? 'upstream' : null,
  };
  const initialSource = initialRepo === undefined
    ? initialSelection.source
    : initialRepo === 'upstream' ? 'upstream' : null;
  const initialViewingSource = initialRepo === 'upstream';
  const [overview, setOverview] = useState<GitOverviewDto | null>(null);
  const [screen, setScreen] = useState<Screen>(initialExecutionId ? 'progress' : initialScreen);
  const [commitsView, setCommitsView] = useState<CommitsView>('history');
  const [source, setSource] = useState<string | null>(initialSource);
  const [viewingSource, setViewingSource] = useState(initialViewingSource);
  const [branch, setBranch] = useState(target.branch);
  const [targetBranches, setTargetBranches] = useState<string[]>([]);
  const [targetBranchQuery, setTargetBranchQuery] = useState('');
  const [upBranch, setUpBranch] = useState(workspace.upstreamDefaultBranch ?? workspace.defaultBranch);
  const [menu, setMenu] = useState<'branch' | 'source' | 'sourceBranch' | null>(null);
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

  const observedRemoteSources = selectableCommitSources(overview?.remotes ?? [], 'origin');
  const persistedRemoteSources = settings.remotes.filter(remote => remote.role !== 'ORIGIN');
  const remoteSources = persistedRemoteSources
    .map(remote => ({
      name: remote.name,
      url: remote.url,
      headBranch: observedRemoteSources.find(observed => observed.name === remote.name)?.headBranch ?? null,
    }));
  const configuredRemoteNames = persistedRemoteSources.map(remote => remote.name);
  const availableSource = availableCommitSource(source, configuredRemoteNames, hasUpstream);
  /** A source being browsed, or null when viewing the cherry-pick target. */
  const sourceReady = (source === 'upstream' && hasUpstream)
    || availableSource === source;
  const activeRemote = viewingSource && sourceReady ? source : null;
  const isRemote = activeRemote !== null;
  const isUpstream = activeRemote === 'upstream' && hasUpstream;
  const canEditTarget = !isRemote && !targetUpstream && overview?.currentBranch === branch;
  const upstreamSlug = hasUpstream && workspace.upstreamOwner && workspace.upstreamRepo
    ? `${workspace.upstreamOwner}/${workspace.upstreamRepo}`
    : null;
  const targetSlug = targetUpstream ? upstreamSlug : `${workspace.selectedOwner}/${workspace.selectedRepo}`;
  const targetLabel = targetUpstream ? `upstream/${branch}` : branch;
  const firstExtraRemote = remoteSources.find(remote => remote.name !== 'upstream')?.name ?? null;
  const activeRemoteUrl = activeRemote === null
    ? null
    : remoteSources.find(remote => remote.name === activeRemote)?.url ?? null;
  const activeRemoteHead = activeRemote === null
    ? null
    : remoteSources.find(remote => remote.name === activeRemote)?.headBranch ?? null;
  const sourceSlug = activeRemoteUrl ? githubSlug(activeRemoteUrl) : null;
  const logRef = isRemote ? `${activeRemote}/${upBranch}` : targetLabel;
  const targetRef = commitTargetRef({ ...target, branch });
  const historySource: CommitHistorySourceDto = isUpstream ? 'UPSTREAM' : isRemote ? 'REMOTE' : targetRef.source;
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
    if (!canEditTarget) setCommitsView('history');
  }, [canEditTarget]);

  useEffect(() => {
    let cancelled = false;
    window.bridge.gitRemoteBranches(workspace.id, target.remote)
      .then(branches => { if (!cancelled) setTargetBranches(branches); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [workspace.id, target.remote, logVersion]);

  useEffect(() => {
    setUpBranch(source === 'upstream'
      ? workspace.upstreamDefaultBranch ?? workspace.defaultBranch
      : workspace.defaultBranch);
    setUpBranches([]);
    setUpBranchPicked(false);
    setCheckedUp(new Set());
  }, [source, workspace.defaultBranch, workspace.upstreamDefaultBranch]);

  useEffect(() => {
    if (availableSource === source) return;
    setSource(availableSource);
    if (availableSource === null) setViewingSource(false);
  }, [availableSource, source]);

  useEffect(() => {
    onSourceSelectionChange({ source });
  }, [onSourceSelectionChange, source]);

  // Menus close on any outside press; the chip wrappers stop propagation.
  useEffect(() => {
    if (menu === null) return;
    const close = () => setMenu(null);
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [menu]);

  useEffect(() => {
    if (isRemote || targetUpstream) return;
    let cancelled = false;
    window.bridge.gitStatus(workspace.id)
      .then(status => { if (!cancelled) setChangesCount(status.files.length); })
      .catch(() => { if (!cancelled) setChangesCount(null); });
    return () => { cancelled = true; };
  }, [workspace.id, isRemote, targetUpstream, logVersion, commitsView]);

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

  // Upstream starts on the configured PR target, even when cached refs or HEAD
  // are stale. Extra sources use their detected default until the user picks.
  useEffect(() => {
    if (activeRemote === null || (isUpstream && !upBranchPicked)) return;
    const correction = remoteBranchCorrection(upBranches, upBranch, activeRemoteHead, upBranchPicked);
    if (correction === null) return;
    setUpBranch(correction);
    setCheckedUp(new Set());
  }, [activeRemote, isUpstream, upBranches, upBranch, activeRemoteHead, upBranchPicked]);

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
      // The extra-remote ref is only validated when its branch is known to
      // exist; the fetch itself covers every configured remote regardless, so
      // a fresh remote (branches unknown) syncs green instead of failing on a
      // guessed branch.
      const status = await window.bridge.refreshCommitHistory(
        workspace.id,
        commitRefreshRefs(
          { ...target, branch },
          isUpstream ? { source: 'UPSTREAM', branch: upBranch }
            : activeRemote !== null && upBranches.includes(upBranch)
              ? { source: 'REMOTE', branch: upBranch, remote: activeRemote }
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
      setCommitsView('history');
      setMenu(null);
      return;
    }
    setMenu(open => (open === 'branch' ? null : 'branch'));
  };

  const activateSource = () => {
    if (source === null) {
      setMenu('source');
      return;
    }
    setScreen('commits');
    setCommitsView('history');
    setViewingSource(true);
    setMenu(open => (open === 'source' ? open : null));
  };

  const sourceChipClick = () => {
    setMenu(open => (open === 'source' ? null : 'source'));
  };

  const sourceBranchChipClick = () => {
    setScreen('commits');
    setCommitsView('history');
    setViewingSource(true);
    setMenu(open => (open === 'sourceBranch' ? null : 'sourceBranch'));
  };

  const pickSource = (name: string) => {
    setScreen('commits');
    setMenu(null);
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

  // Scheduled enrichment is written out-of-band. Poll only its SQLite-backed
  // status, then reload Git + enrichment once when a completed attempt changes.
  useEffect(() => {
    if (!historyVisible || syncing) return;
    let cancelled = false;
    let timer: number | undefined;
    const knownAttemptAt = refreshStatus?.lastAttemptAt ?? null;
    const poll = async () => {
      try {
        const status = await window.bridge.getCommitRefreshStatus(workspace.id);
        if (cancelled) return;
        if (commitRefreshChanged(knownAttemptAt, status)) {
          setRefreshStatus(status);
          setLogVersion(version => version + 1);
          return;
        }
      }
      catch {
        // A transient local status read must not replace the visible history.
      }
      if (!cancelled) {
        timer = window.setTimeout(() => { void poll(); }, COMMIT_REFRESH_POLL_INTERVAL_MS);
      }
    };
    timer = window.setTimeout(() => { void poll(); }, COMMIT_REFRESH_POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [historyVisible, refreshStatus?.lastAttemptAt, syncing, workspace.id]);

  return (
    <div className="cmt-root">
      {showTabBar && (
        <div className="cmt-tab-bar">
          <div className="cmt-chip-wrap" onMouseDown={event => event.stopPropagation()}>
            <button
              type="button"
              className={`cmt-repo-chip${!isRemote ? ' is-active' : ''}`}
              title={`Cherry-pick target: ${targetSlug} · ${targetLabel}`}
              aria-pressed={!isRemote}
              onClick={wsChipClick}
            >
              <GitBranch className="cmt-toolbar-icon" size={16} strokeWidth={1.7} aria-hidden="true" />
              <span className="cmt-repo-chip__label cmt-mono">{targetLabel}</span>
              <ChevronDown className="cmt-caret" size={14} strokeWidth={1.7} aria-hidden="true" />
            </button>
            {menu === 'branch' && (
              <div className="cmt-menu">
                <div className="cmt-menu-label">CHERRY-PICK TARGET · {target.remote}</div>
                <div className="cmt-menu-search">
                  <input value={targetBranchQuery} placeholder="Find a target branch…"
                    aria-label="Find a target branch" onChange={event => setTargetBranchQuery(event.target.value)} />
                </div>
                {targetBranches.filter(name => name.toLowerCase().includes(targetBranchQuery.trim().toLowerCase())).slice(0, 30).map(name => (
                  <button
                    type="button"
                    key={name}
                    title={name}
                    className={`cmt-menu-item${name === branch ? ' is-current' : ''}`}
                    onClick={() => {
                      setBranch(name);
                      setMenu(null);
                      setTargetBranchQuery('');
                      setCheckedWs(new Set());
                      setCheckedUp(new Set());
                    }}
                  >
                    {name === branch ? '✓' : ' '} {targetUpstream ? `upstream/${name}` : name}
                  </button>
                ))}
                {targetBranches.length === 0 && <div className="cmt-menu-label">No fetched target branches. Sync to refresh.</div>}
                <div className="cmt-menu-divider" />
                <button type="button" className="cmt-menu-item" onClick={onOpenSettings}>Set the default in Workspace settings…</button>
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
                <GitCompareArrows className="cmt-toolbar-icon" size={16} strokeWidth={1.7} aria-hidden="true" />
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
                <ChevronDown className="cmt-caret" size={14} strokeWidth={1.7} aria-hidden="true" />
              </button>
              {source !== null && (
                <button
                  type="button"
                  className="cmt-split-chip__seg"
                  title="Branch of the selected source"
                  onClick={sourceBranchChipClick}
                >
                  <GitBranch className="cmt-toolbar-icon" size={16} strokeWidth={1.7} aria-hidden="true" />
                  <span className="cmt-repo-chip__label cmt-mono">{upBranch}</span>
                  <ChevronDown className="cmt-caret" size={14} strokeWidth={1.7} aria-hidden="true" />
                </button>
              )}
            </div>
            {menu === 'source' && (
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
                {remoteSources.length === 0 && (
                  <div className="cmt-menu-label">No remote sources configured</div>
                )}
                <div className="cmt-menu-divider" />
                <button
                  type="button"
                  className="cmt-menu-item cmt-source-item"
                  onClick={() => {
                    setMenu(null);
                    onOpenSettings();
                  }}
                >
                  <span className="cmt-source-name">⚙ Manage remote sources…</span>
                  <span className="cmt-source-desc">in Workspace settings</span>
                </button>
              </div>
            )}
            {menu === 'sourceBranch' && (
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
                      setMenu(null);
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
                <summary title="Select a commit range by SHA">
                  <ListChecks size={16} strokeWidth={1.7} aria-hidden="true" />
                  <span>select range</span>
                </summary>
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
              title={syncing
                ? 'Syncing commit history'
                : `Sync commit history · ${commitSyncLabel(refreshStatus)}`}
              aria-label={syncing ? 'Syncing commit history' : 'Sync commit history'}
              disabled={syncing}
              onClick={() => { void sync(); }}
            >
              <RefreshCw
                className={syncing ? 'is-spinning' : undefined}
                size={18}
                strokeWidth={1.8}
                aria-hidden="true"
              />
            </button>
          </div>

          <div className="cmt-tab-divider" />

          <button
            type="button"
            className={`cmt-tab${screen === 'commits' && commitsView === 'changes' ? ' is-active' : ''}${canEditTarget ? '' : ' is-disabled'}`}
            disabled={!canEditTarget}
            onClick={() => {
              if (!canEditTarget) return;
              setScreen('commits');
              setCommitsView('changes');
              setMenu(null);
            }}
          >
            Changes
            {!isRemote && !targetUpstream && changesCount !== null && changesCount > 0 && (
              <span className="cmt-tab-badge">{changesCount}</span>
            )}
          </button>
          <button
            type="button"
            className={`cmt-tab${screen === 'commits' && commitsView === 'history' ? ' is-active' : ''}`}
            onClick={() => {
              setScreen('commits');
              setCommitsView('history');
              setMenu(null);
            }}
          >
            History
          </button>
          <button
            type="button"
            className={`cmt-tab${screen === 'tasks' || screen === 'flow' ? ' is-active' : ''}`}
            onClick={() => { setScreen('tasks'); setMenu(null); setMenu(null); setMenu(null); }}
          >
            Cherry pick
          </button>
        </div>
      )}

      {showTabBar && historyVisible && (
        <div className="cmt-search">
          <Search size={18} strokeWidth={1.8} aria-hidden="true" />
          <input
            value={searchQuery}
            aria-label="Search commits by pull request title, number, or SHA"
            onChange={event => setSearchQuery(event.target.value)}
          />
        </div>
      )}

      {error && screen === 'commits' && <div className="cmt-error" role="alert">{error}</div>}

      {screen === 'commits' && commitsView === 'changes' && canEditTarget && (
        <ChangesView
          workspaceId={workspace.id}
          onCommitted={() => setLogVersion(version => version + 1)}
        />
      )}
      {screen === 'commits' && (commitsView === 'history' || isRemote) && (
        <HistoryView
          workspaceId={workspace.id}
          branch={branch}
          readOnlyTarget={!isRemote && targetUpstream}
          logRef={logRef}
          isUpstream={isRemote}
          canRewrite={canEditTarget}
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
          <span className="cmt-action-target">→ {targetSlug ?? workspace.name} · {targetLabel}</span>
          <button
            type="button"
            disabled={previewBusy}
            onClick={() => { void openFlow(); }}
          >
            {previewBusy ? 'Preparing preview…' : `Cherry-pick to ${targetLabel} →`}
          </button>
        </div>
      )}
    </div>
  );
}
