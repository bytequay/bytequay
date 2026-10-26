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
import { useAgentAvailability } from '../agents/availability';
import { ArrowRight, ArrowUpRight, Check, ChevronDown, GitBranch, GitFork, ListChecks, RefreshCw, Search, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import '../css/commits.css';
import { relativeTime } from '@bytequay/ui/relativeTime';
import type {
  CherryPickExecution,
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
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import { ConfirmDialog } from '../ConfirmDialog';
import Toast from '../Toast';
import { workspaceCherryPickRoute, workspaceRoute } from '../workspaces/model';
import { useRelationships } from '../workspaces/RelatedWorkspaces';
import ChangesView from './ChangesView';
import FlowView from './FlowView';
import HistoryView from './HistoryView';
import PullRequestsView from './PullRequestsView';
import SelectRangeDialog from './SelectRangeDialog';
import TasksView from './TasksView';
import { backportRoute, chosenRoute, lineRepos, remoteBranchCorrection, selectedSpan, shortSha, toggledGroup, type BackportHop, type LineRepo } from './model';

type Screen = 'commits' | 'flow' | 'tasks';
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

/** The remotes Commits can read from: the repo line's upstream repositories, then another branch of this one. */
export function commitSources(repos: LineRepo[], hasUpstream: boolean): string[] {
  return [...repos.flatMap(repo => repo.remote === null ? [] : [repo.remote]), ...(hasUpstream ? ['upstream'] : [])];
}

/** Picking starts in the repository just upstream, then any other source, then another branch of this one. */
export function firstCommitSource(repos: LineRepo[], hasUpstream: boolean): string | null {
  const here = repos.findIndex(repo => repo.place === 'here');
  return (here > 0 ? repos[here - 1].remote : null) ?? commitSources(repos, hasUpstream)[0] ?? null;
}

function lineRepoTitle(repo: LineRepo, repos: LineRepo[]): string {
  if (repo.place === 'here') return `${repo.slug} · this workspace`;
  if (repo.place === 'upstream') return `Pick commits from ${repo.slug}`;
  const parent = repos.find(item => item.workspaceId === repo.parentWorkspaceId)?.name ?? 'this workspace';
  return `${repo.name} takes changes from ${parent} · open its Commits`;
}

type CommitsTabProps = {
  workspace: WorkspaceDto;
  /** `upstream` opens the nearest repository to pick commits from. */
  initialRepo?: 'workspace' | 'upstream';
  initialSourceSelection?: CommitSourceSelection;
  onSourceSelectionChange: (selection: CommitSourceSelection) => void;
  onOpenPullRequest: (number: number, repo?: string) => void;
  onOpenSettings: () => void;
};

export default function CommitsTab(props: CommitsTabProps) {
  const [settings, setSettings] = useState<WorkspaceSettingsDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let cancelled = false;
    setError(null);
    window.bridge.getWorkspaceSettings(props.workspace.id)
      .then(value => { if (!cancelled) setSettings(value); })
      .catch(failure => { if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { cancelled = true; };
  }, [props.workspace.id, retry]);

  const relationships = useRelationships(props.workspace.id);

  // Do not read history or prepare a cherry-pick against a guessed target
  // while the saved workspace override is still unknown.
  if (settings === null || (relationships.data === null && relationships.error === null)) {
    return <div className="cmt-root">
      {error === null ? <div className="cmt-loading">Loading commits target…</div> : <div className="cmt-error" role="alert">
        Could not load the commits target: {error}
        {' '}<button type="button" onClick={() => setRetry(value => value + 1)}>Retry</button>
        {' '}<button type="button" onClick={props.onOpenSettings}>Workspace settings</button>
      </div>}
    </div>;
  }
  const repos = lineRepos(props.workspace, relationships.data ?? { links: [], related: [], line: [] }, relationships.data?.workspaces ?? []);
  return <CommitsBrowser {...props} settings={settings} repos={repos} />;
}

export function CommitsBrowser({ workspace, settings, repos, initialRepo, initialSourceSelection, onSourceSelectionChange, onOpenPullRequest, onOpenSettings }:
  CommitsTabProps & { settings: Pick<WorkspaceSettingsDto, 'commitsTarget'>; repos: LineRepo[] }) {
  const availability = useAgentAvailability(workspace.id);
  const hasUpstream = workspace.upstreamRepositoryId !== null;
  const target = settings.commitsTarget;
  const targetUpstream = target.remote === 'upstream';
  const sources = commitSources(repos, hasUpstream);
  const firstSource = firstCommitSource(repos, hasUpstream);
  const initialSource = initialRepo === 'upstream' ? firstSource
    : initialRepo === 'workspace' ? null : initialSourceSelection?.source ?? null;
  const [overview, setOverview] = useState<GitOverviewDto | null>(null);
  const [screen, setScreen] = useState<Screen>('commits');
  const [started, setStarted] = useState<CherryPickExecution | null>(null);
  const [commitsView, setCommitsView] = useState<CommitsView>('history');
  /** The remote being browsed, or null while viewing this workspace's cherry-pick target. */
  const [source, setSource] = useState<string | null>(initialSource);
  const [branch, setBranch] = useState(target.branch);
  const [targetBranches, setTargetBranches] = useState<string[]>([]);
  const [targetBranchQuery, setTargetBranchQuery] = useState('');
  const [upBranch, setUpBranch] = useState(repos.find(repo => repo.remote !== null && repo.remote === initialSource)?.defaultBranch
    ?? workspace.upstreamDefaultBranch ?? workspace.defaultBranch);
  const [menu, setMenu] = useState<'branch' | 'into' | null>(null);
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
  const [error, setError] = useState<string | null>(null);
  const [logVersion, setLogVersion] = useState(0);
  const [rangeOpen, setRangeOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [listMode, setListMode] = useState<'commits' | 'pulls'>('commits');
  const [selectedPr, setSelectedPr] = useState<number | null>(null);
  const [backport, setBackport] = useState<{ number: number; source: LineRepo; route: BackportHop[]; chosen: Set<string> } | null>(null);
  const [backportBusy, setBackportBusy] = useState(false);
  const [backportError, setBackportError] = useState<string | null>(null);
  /** The one ref an unreadable remote log already auto-synced for. */
  const autoSyncAttempt = useRef<string | null>(null);
  const rangeButtonRef = useRef<HTMLButtonElement>(null);

  const activeRemote = source !== null && sources.includes(source) ? source : null;
  const browsed = repos.find(repo => repo.remote !== null && repo.remote === activeRemote) ?? null;
  const isRemote = activeRemote !== null;
  const isUpstream = activeRemote === 'upstream';
  const canEditTarget = !isRemote && !targetUpstream && overview?.currentBranch === branch;
  const upstreamSlug = hasUpstream && workspace.upstreamOwner && workspace.upstreamRepo
    ? `${workspace.upstreamOwner}/${workspace.upstreamRepo}`
    : null;
  const targetSlug = targetUpstream ? upstreamSlug : `${workspace.selectedOwner}/${workspace.selectedRepo}`;
  const targetLabel = targetUpstream ? `upstream/${branch}` : branch;
  const activeRemoteHead = activeRemote === null
    ? null
    : overview?.remotes.find(remote => remote.name === activeRemote)?.headBranch ?? null;
  const sourceSlug = browsed?.slug ?? (isUpstream ? upstreamSlug : null);
  const sourceDefaultBranch = browsed?.defaultBranch ?? null;
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
      : sourceDefaultBranch ?? workspace.defaultBranch);
    setUpBranches([]);
    setUpBranchPicked(false);
    setCheckedUp(new Set());
    setSelectedPr(null);
  }, [source, sourceDefaultBranch, workspace.defaultBranch, workspace.upstreamDefaultBranch]);

  // A remembered source the repo line no longer names falls back to this workspace.
  useEffect(() => {
    if (source !== null && activeRemote === null) setSource(null);
  }, [source, activeRemote]);

  useEffect(() => {
    onSourceSelectionChange({ source });
  }, [onSourceSelectionChange, source]);

  // Menus close on any outside press; the chip wrappers stop propagation.
  const menuRef = usePopoverDismiss(() => setMenu(null), false, menu !== null);

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

  // Closing returns the reader to the control that opened the dialog.
  const closeRange = () => {
    setRangeOpen(false);
    rangeButtonRef.current?.focus();
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

  const hereRepo = repos.find(repo => repo.place === 'here') ?? null;
  /** A backport starts from the browsed upstream repository, or from this one while viewing it. */
  const backportSource = isUpstream ? null : isRemote ? browsed : hereRepo;
  const route = backportSource === null ? [] : backportRoute(repos, backportSource.workspaceId);
  /** Pull requests live in the browsed repository's own workspace; another branch of this one is this workspace. */
  const showPulls = listMode === 'pulls' && (isRemote || route.length > 0);
  const spanPullRequests = new Set(span === null ? [] : commits.slice(span.lo, span.hi + 1).map(commit => commit.pullRequestNumber ?? null));
  const spanPullRequest = spanPullRequests.size === 1 ? [...spanPullRequests][0] : null;
  const backportNumber = showPulls ? selectedPr : spanPullRequest;
  const canBackport = availability.any && route.length > 0 && backportNumber !== null && backportSource !== null;

  const openBackport = () => {
    if (!canBackport || backportSource === null || backportNumber === null) return;
    setBackportError(null);
    setBackport({ number: backportNumber, source: backportSource, route, chosen: new Set(route.map(hop => hop.repo.workspaceId)) });
  };

  const startBackport = async () => {
    if (backport === null) return;
    const hops = chosenRoute(backport.route, backport.chosen);
    setBackportBusy(true);
    setBackportError(null);
    try {
      await window.bridge.startBackportChain(workspace.id, {
        sourceWorkspaceId: backport.source.workspaceId,
        number: backport.number,
        destinations: hops.map(hop => hop.repo.workspaceId),
        sourceHops: hops.map(hop => hop.sourceHop),
      });
      setBackport(null);
      setScreen('tasks');
    } catch (failure) {
      setBackportError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBackportBusy(false);
    }
  };

  const backportButton = canBackport && (
    <button type="button" className="cmt-strip-btn" title={`Backport #${backportNumber} to ${route.map(hop => hop.repo.name).join(', ')}`}
      onClick={openBackport}>
      Backport
      <ArrowRight size={12} strokeWidth={2} aria-hidden="true" />
    </button>
  );

  const openPullRequestFlow = async (number: number) => {
    if (activeRemote === null || sourceSlug === null) return;
    const [owner, repo] = sourceSlug.split('/');
    setPreviewBusy(true);
    setError(null);
    try {
      setPreview(await window.bridge.previewCherryPickPullRequest({
        workspaceId: workspace.id, sourceRemote: activeRemote, owner, repo, number, targetBranch: branch,
      }));
      setScreen('flow');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setPreviewBusy(false);
    }
  };

  const browse = (remote: string | null) => {
    setScreen('commits');
    setCommitsView('history');
    setMenu(null);
    setSource(remote);
  };

  // With nothing upstream to read from, the next step is linking a related repository.
  const startCherryPick = () => {
    if (firstSource === null) {
      onOpenSettings();
      return;
    }
    browse(firstSource);
  };

  const sourceName = (remote: string) =>
    (remote === 'upstream' ? 'another branch' : repos.find(repo => repo.remote === remote)?.name ?? remote);

  const historyVisible = screen === 'commits' && (commitsView === 'history' || isRemote) && !showPulls;
  const filteredUpBranches = upBranches.filter(name =>
    name.toLowerCase().includes(upBranchQuery.trim().toLowerCase()));
  const filteredTargetBranches = targetBranches.filter(name =>
    name.toLowerCase().includes(targetBranchQuery.trim().toLowerCase()));
  const targetDefaultBranch = targetUpstream ? workspace.upstreamDefaultBranch : workspace.defaultBranch;
  const targetRepoName = (targetUpstream ? workspace.upstreamRepo : workspace.selectedRepo) ?? workspace.name;
  const targetPillLabel = `${targetRepoName} · ${branch}`;
  const pullRequestRepo = isRemote ? sourceSlug : targetSlug;
  const openPullRequest = (number: number) => onOpenPullRequest(number, pullRequestRepo ?? undefined);
  const selectedCount = commits.filter(commit => checked.has(commit.sha)).length;
  /** Commits the span covers that the reader never checked themselves. */
  const pulledIn = span === null ? 0 : span.hi - span.lo + 1 - selectedCount;

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

  const listHeader = (
    <>
      {/* The mock keeps search and selection inside the commit column. */}
      <div className="cmt-search-row">
        {(isRemote || route.length > 0) && (
          <div className="cmt-seg" role="group" aria-label="List">
            {([['commits', 'Commits'], ['pulls', 'Pull requests']] as const).map(([key, label]) => (
              <button type="button" key={key} className={listMode === key ? 'is-active' : ''} aria-pressed={listMode === key}
                onClick={() => setListMode(key)}>
                {label}
              </button>
            ))}
          </div>
        )}
        <div className="cmt-search">
          <Search size={13} strokeWidth={2} aria-hidden="true" />
          <input
            value={searchQuery}
            placeholder={showPulls ? 'Search merged pull requests, #number or a link' : 'Search title, SHA, PR title or #number'}
            aria-label={showPulls ? 'Search merged pull requests by title, number, or link' : 'Search commits by pull request title, number, or SHA'}
            onChange={event => setSearchQuery(event.target.value)}
          />
        </div>
      </div>

      {!isRemote && !showPulls && (
        <div className="cmt-strip">
          <GitBranch className="cmt-strip-icon" size={13} strokeWidth={2} aria-hidden="true" />
          <span className="cmt-strip-note">
            {firstSource === null ? 'No upstream repository to pick commits from' : 'Ranges are picked on the source repo'}
          </span>
          <div className="cmt-strip-gap" />
          <button type="button" className="cmt-strip-btn" onClick={startCherryPick}>
            {firstSource === null ? 'Link one in Related workspaces' : `Pick commits from ${sourceName(firstSource)}`}
            <ArrowRight size={12} strokeWidth={2} aria-hidden="true" />
          </button>
          {backportButton}
        </div>
      )}

      {showPulls && (
        <div className={`cmt-strip${selectedPr === null ? '' : ' has-selection'}`}>
          <span className="cmt-strip-count">{selectedPr === null ? 'No pull request selected' : `#${selectedPr} selected`}</span>
          <div className="cmt-strip-gap" />
          {selectedPr !== null && isRemote && availability.any && (
            <button type="button" className="cmt-strip-btn is-cherry" disabled={previewBusy} onClick={() => { void openPullRequestFlow(selectedPr); }}>
              {previewBusy ? 'Preparing preview…' : 'Cherry-pick'}
              <ArrowRight size={12} strokeWidth={2} aria-hidden="true" />
            </button>
          )}
          {backportButton}
        </div>
      )}

      {isRemote && !showPulls && (
        <div className={`cmt-strip${span === null ? '' : ' has-selection'}`}>
          <span className="cmt-strip-count">
            {selectedCount === 0
              ? 'No commits selected'
              : `${selectedCount} commit${selectedCount === 1 ? '' : 's'} selected`}
          </span>
          {span !== null && (
            <span className="cmt-strip-range cmt-mono">
              {shortSha(commits[span.hi].sha)}…{shortSha(commits[span.lo].sha)}
            </span>
          )}
          {pulledIn > 0 && <span className="cmt-strip-pulled">{pulledIn} pulled in by range</span>}
          <div className="cmt-strip-gap" />
          <button
            type="button"
            className="cmt-strip-btn"
            ref={rangeButtonRef}
            aria-haspopup="dialog"
            onClick={() => setRangeOpen(true)}
          >
            <ListChecks size={12} strokeWidth={2} aria-hidden="true" />
            Select range
          </button>
          {span !== null && (
            <>
              <button
                type="button"
                className="cmt-strip-btn is-clear"
                onClick={() => setCheckedUp(new Set())}
              >
                <X size={12} strokeWidth={2} aria-hidden="true" />
                Clear
              </button>
              {availability.any && <button
                type="button"
                className="cmt-strip-btn is-cherry"
                disabled={previewBusy}
                onClick={() => { void openFlow(); }}
              >
                {previewBusy ? 'Preparing preview…' : 'Cherry-pick'}
                <ArrowRight size={12} strokeWidth={2} aria-hidden="true" />
              </button>}
              {backportButton}
            </>
          )}
        </div>
      )}
      {syncing && !showPulls && (
        <div className="cmt-sync-notice" role="status">
          <span className="bq-spinner is-spinning">
            <RefreshCw size={16} strokeWidth={2} aria-hidden="true" />
          </span>
          <div>
            <strong>Syncing commit details…</strong>
            <span>Avatars and pull-request details will update when sync finishes.</span>
          </div>
        </div>
      )}
    </>
  );

  return (
    <div className="cmt-root">
      <div className="cmt-tab-bar">
        <div ref={menuRef} className="cmt-line" onMouseDown={event => event.stopPropagation()}>
          <ol className="cmt-line-repos" aria-label="Repo line">
            {repos.map((repo, index) => {
              const active = repo.place === 'here' ? !isRemote || isUpstream : repo.remote !== null && repo.remote === activeRemote;
              return (
                <li key={repo.workspaceId} className={!repo.joined && index > 0 ? 'is-apart' : undefined}>
                  {repo.joined && <ArrowRight className="cmt-line-arrow" size={12} strokeWidth={2} aria-hidden="true" />}
                  <button
                    type="button"
                    className={`cmt-line-repo${active ? ' is-active' : ''}`}
                    aria-pressed={active}
                    title={lineRepoTitle(repo, repos)}
                    onClick={() => {
                      if (repo.place === 'downstream') window.location.hash = workspaceRoute(repo.workspaceId, 'commits');
                      else browse(repo.place === 'here' ? null : repo.remote);
                    }}
                  >
                    {repo.place === 'here' ? <GitBranch size={12} strokeWidth={2} aria-hidden="true" />
                      : repo.place === 'upstream' ? <GitFork size={12} strokeWidth={2} aria-hidden="true" />
                        : <ArrowUpRight size={12} strokeWidth={2} aria-hidden="true" />}
                    {repo.name}
                  </button>
                </li>
              );
            })}
          </ol>
          <button
            type="button"
            className="cmt-pill"
            aria-haspopup="menu"
            aria-expanded={menu === 'branch'}
            title={isRemote ? `Read history from ${sourceSlug ?? activeRemote} · ${upBranch}` : `Cherry-pick target: ${targetSlug ?? workspace.name} · ${targetLabel}`}
            onClick={() => setMenu(open => (open === 'branch' ? null : 'branch'))}
          >
            <GitBranch size={13} strokeWidth={2} aria-hidden="true" />
            <span className="cmt-pill__label cmt-mono">{isRemote ? upBranch : targetPillLabel}</span>
            <ChevronDown className="cmt-pill__caret" size={11} strokeWidth={2} aria-hidden="true" />
          </button>
          {isRemote && (
            <button
              type="button"
              className="cmt-pill"
              aria-haspopup="menu"
              aria-expanded={menu === 'into'}
              title={`Cherry-pick target: ${targetSlug ?? workspace.name} · ${targetLabel}`}
              onClick={() => setMenu(open => (open === 'into' ? null : 'into'))}
            >
              <span className="cmt-pill__caption">into</span>
              <span className="cmt-pill__label cmt-mono">{targetPillLabel}</span>
              <ChevronDown className="cmt-pill__caret" size={11} strokeWidth={2} aria-hidden="true" />
            </button>
          )}

          {(menu === 'into' || (menu === 'branch' && !isRemote)) && (
            <div className="cmt-menu">
              <div className="cmt-menu-head">
                <div className="cmt-menu-label">CHERRY-PICK DESTINATION</div>
                <div className="cmt-menu-sub cmt-mono">{targetSlug ?? workspace.name}</div>
              </div>
              <div className="cmt-menu-search">
                <input
                  value={targetBranchQuery}
                  placeholder="Find a target branch…"
                  aria-label="Find a target branch"
                  onChange={event => setTargetBranchQuery(event.target.value)}
                />
              </div>
              <div className="cmt-menu-list">
                {filteredTargetBranches.slice(0, 30).map(name => (
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
                    <span className="cmt-menu-check">
                      {name === branch && <Check size={11} strokeWidth={2} aria-hidden="true" />}
                    </span>
                    <span className="cmt-menu-branch cmt-mono">{name}</span>
                    {name === targetDefaultBranch && <span className="cmt-menu-tag">default</span>}
                  </button>
                ))}
                {targetBranches.length === 0 && (
                  <div className="cmt-menu-label">No fetched target branches. Sync to refresh.</div>
                )}
              </div>
              <button type="button" className="cmt-menu-foot" onClick={onOpenSettings}>
                Default target lives in Workspace settings
              </button>
            </div>
          )}

          {menu === 'branch' && isRemote && (
            <div className="cmt-menu">
              <div className="cmt-menu-head">
                <div className="cmt-menu-label">HISTORY AND CHERRY-PICK READ FROM HERE</div>
                <div className="cmt-menu-sub cmt-mono">{sourceSlug ?? activeRemote}</div>
              </div>
              <div className="cmt-menu-search">
                <input
                  value={upBranchQuery}
                  placeholder="Find a branch…"
                  aria-label="Find a source branch"
                  onChange={event => setUpBranchQuery(event.target.value)}
                />
              </div>
              <div className="cmt-menu-list">
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
                    <span className="cmt-menu-check">
                      {name === upBranch && <Check size={11} strokeWidth={2} aria-hidden="true" />}
                    </span>
                    <span className="cmt-menu-branch cmt-mono">{name}</span>
                  </button>
                ))}
                {upBranches.length === 0 && <div className="cmt-menu-label">Loading branches…</div>}
              </div>
            </div>
          )}
        </div>

        <button
          type="button"
          className="cmt-refresh"
          title={syncing
            ? 'Syncing commit history'
            : `Sync commit history · ${commitSyncLabel(refreshStatus)}`}
          aria-label={syncing ? 'Syncing commit history' : 'Sync commit history'}
          disabled={syncing}
          onClick={() => { void sync(); }}
        >
          <span className={`bq-spinner${syncing ? ' is-spinning' : ''}`}>
            <RefreshCw size={15} strokeWidth={2} aria-hidden="true" />
          </span>
        </button>
        <span className="cmt-sync-text">{syncing ? 'syncing…' : commitSyncLabel(refreshStatus)}</span>

        <div className="cmt-tab-spacer" />

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
          onClick={() => { setScreen('tasks'); setMenu(null); }}
        >
          Cherry pick
        </button>
      </div>
      {rangeOpen && isRemote && (
        <SelectRangeDialog
          commits={commits}
          scope={`${sourceSlug ?? activeRemote} · ${upBranch}`}
          pullRequestRepo={pullRequestRepo}
          onOpenPullRequest={openPullRequest}
          onCancel={closeRange}
          onApply={anchors => {
            setCheckedUp(new Set(anchors));
            closeRange();
          }}
        />
      )}

      {backport !== null && (() => {
        const kept = chosenRoute(backport.route, backport.chosen);
        const toggle = (id: string) => setBackport(current => current === null ? current : { ...current, chosen: toggledGroup(current.chosen, [id]) });
        return (
          <ConfirmDialog
            title={`Backport ${backport.source.slug}#${backport.number}`}
            confirmLabel="Start backport"
            busy={backportBusy}
            confirmDisabled={kept.length === 0}
            onConfirm={() => { void startBackport(); }}
            onCancel={() => setBackport(null)}
          >
            <p>
              ByteQuay cherry-picks it into each repository you keep, opens a pull request there, and repairs CI as usual.
              Each step starts after the pull request it copies from merges; nothing is merged for you.
            </p>
            <ul className="cmt-backport-route">
              {backport.route.map(hop => {
                const from = hop.sourceHop < 0 ? backport.source : backport.route[hop.sourceHop].repo;
                const sourceKept = hop.sourceHop < 0 || kept.some(item => item.repo.workspaceId === from.workspaceId);
                return (
                  <li key={hop.repo.workspaceId}>
                    <label>
                      <input type="checkbox" checked={backport.chosen.has(hop.repo.workspaceId) && sourceKept} disabled={backportBusy || !sourceKept}
                        onChange={() => toggle(hop.repo.workspaceId)} />
                      {hop.repo.name}
                      <span className="cmt-backport-from">from {from.name}</span>
                    </label>
                  </li>
                );
              })}
            </ul>
            {backportError && <div className="cmt-error" role="alert">{backportError}</div>}
          </ConfirmDialog>
        );
      })()}

      {error && screen === 'commits' && <div className="cmt-error" role="alert">{error}</div>}

      {screen === 'commits' && commitsView === 'changes' && canEditTarget && (
        <ChangesView
          workspaceId={workspace.id}
          onCommitted={() => setLogVersion(version => version + 1)}
        />
      )}
      {screen === 'commits' && showPulls && (
        <PullRequestsView
          key={browsed?.workspaceId ?? workspace.id}
          workspaceId={browsed?.workspaceId ?? workspace.id}
          slug={isRemote ? sourceSlug : hereRepo?.slug ?? null}
          query={searchQuery}
          selected={selectedPr}
          listHeader={listHeader}
          onSelect={setSelectedPr}
          onOpenPullRequest={openPullRequest}
        />
      )}
      {screen === 'commits' && (commitsView === 'history' || isRemote) && !showPulls && (
        <HistoryView
          listHeader={listHeader}
          pullRequestRepo={pullRequestRepo}
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
          onOpenPullRequest={openPullRequest}
        />
      )}
      {screen === 'tasks' && (
        <TasksView
          workspaceId={workspace.id}
          onOpenTask={execution => { window.location.hash = workspaceCherryPickRoute(workspace.id, execution.executionId); }}
          onNewCherryPick={startCherryPick}
        />
      )}
      {screen === 'flow' && preview && (
        <FlowView
          preview={preview}
          workspaceId={workspace.id}
          upstreamLabel={sourceSlug ?? activeRemote ?? upstreamSlug ?? 'upstream'}
          onCancel={() => { setPreview(null); setScreen('commits'); }}
          onStarted={execution => {
            setPreview(null);
            setCheckedUp(new Set());
            setScreen('commits');
            setStarted(execution);
          }}
        />
      )}
      {started !== null && <Toast key={started.executionId} text="Cherry-pick started" actionLabel="Open task" durationMs={8_000}
        onUndo={() => { window.location.hash = workspaceCherryPickRoute(workspace.id, started.executionId); }} onDismiss={() => setStarted(null)} />}
    </div>
  );
}
