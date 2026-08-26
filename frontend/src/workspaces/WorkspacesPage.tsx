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
import { useEffect, useRef, useState } from 'react';
import AddRepoModal from '../AddRepoModal';
import { getCachedFresh, setCached } from '../dataCache';
import { pullRequestNeedsReview } from '../pulls/model';
import { relativeTime } from '../relativeTime';
import RepoAvatar from '../RepoAvatar';
import Toast from '../Toast';
import type { CherryPickExecution, PullRequestDto, WorkspaceDto } from '../types';
import {
  commitsPerDay,
  metadataAgeDays,
  syncStateColor,
  workspaceCodeState,
  workspaceIsUpdating,
  workspaceMetadataState,
  workspaceRoute,
  type SyncState,
} from './model';
import '../css/workspaces.css';

const SPARK_DAYS = 12;
const SPARK_MAX_AGE_MS = 300_000;
/** How long a clone may sit unfinished before the card offers a retry. */
const SLOW_CLONE_MS = 60_000;

export type WorkspaceActivity = {
  openPrs: number;
  needsYou: number;
  tasks: number;
  /** Commits per day, oldest first; null until the first sync finishes. */
  spark: number[] | null;
  /** Work branch of a cherry-pick still in flight. */
  runningTask: string | null;
};

export type WorkspaceFailure = { at: number; message: string };

export default function WorkspacesPage({ onOpen }: { onOpen: (workspaceId: string) => void }) {
  const [workspaces, setWorkspaces] = useState<WorkspaceDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [executions, setExecutions] = useState<CherryPickExecution[]>([]);
  const [sparks, setSparks] = useState<Record<string, number[]>>({});
  const [failures, setFailures] = useState<Record<string, WorkspaceFailure>>({});
  const [retrying, setRetrying] = useState<string[]>([]);
  const [showModal, setShowModal] = useState(false);
  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const firstSeen = useRef<Record<string, number>>({});
  const toastId = useRef(0);

  useEffect(() => {
    let cancelled = false;
    window.bridge.listWorkspaces()
      .then(rows => { if (!cancelled) setWorkspaces(rows); })
      .catch(failure => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    window.bridge.fetchPrs(undefined, 0, 100)
      .then(page => { if (!cancelled) setPrs(page.pullRequests); })
      .catch(() => { /* Cards fall back to zeroed counts. */ });
    window.bridge.listCherryPickExecutions(30)
      .then(rows => { if (!cancelled) setExecutions(rows); })
      .catch(() => { /* Cards fall back to no running task. */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!workspaces.some(workspaceIsUpdating)) return;
    const timer = window.setTimeout(() => {
      void window.bridge.listWorkspaces()
        .then(setWorkspaces)
        .catch(() => {});
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [workspaces]);

  // Commit history is a local read, so it only runs once the clone exists.
  useEffect(() => {
    let cancelled = false;
    for (const workspace of workspaces) {
      if (firstSeen.current[workspace.id] === undefined) {
        firstSeen.current[workspace.id] = Date.now();
      }
      if (workspace.lastCodeSyncAt === null || sparks[workspace.id] !== undefined) continue;
      const key = `workspace-spark:${workspace.id}`;
      const cached = getCachedFresh<number[]>(key, SPARK_MAX_AGE_MS);
      if (cached) {
        setSparks(previous => ({ ...previous, [workspace.id]: cached }));
        continue;
      }
      window.bridge.gitLog(workspace.id, 'WORKSPACE', workspace.defaultBranch, 200)
        .then(snapshot => {
          const bars = commitsPerDay(
            snapshot.commits.map(commit => commit.committedAt),
            SPARK_DAYS,
          );
          setCached(key, bars);
          if (!cancelled) setSparks(previous => ({ ...previous, [workspace.id]: bars }));
        })
        .catch(() => { /* Card keeps the flat "activity after first sync" bars. */ });
    }
    return () => { cancelled = true; };
    // sparks is deliberately omitted: the `!== undefined` guard already stops
    // the loop from refiring, and depending on it would restart it per arrival.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaces]);

  function flash(text: string) {
    toastId.current += 1;
    setToast({ id: toastId.current, text });
  }

  async function retrySync(workspace: WorkspaceDto) {
    setRetrying(previous => [...previous, workspace.id]);
    try {
      const updated = await window.bridge.syncWorkspace(workspace.id);
      setWorkspaces(previous => previous.map(row => row.id === workspace.id ? updated : row));
      setFailures(previous => {
        const next = { ...previous };
        delete next[workspace.id];
        return next;
      });
    }
    catch (failure) {
      setFailures(previous => ({
        ...previous,
        [workspace.id]: {
          at: Date.now(),
          message: failure instanceof Error ? failure.message : String(failure),
        },
      }));
    }
    finally {
      setRetrying(previous => previous.filter(id => id !== workspace.id));
    }
  }

  const activity: Record<string, WorkspaceActivity> = {};
  for (const workspace of workspaces) {
    const monitored = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
    const running = executions.find(execution =>
      execution.workspaceId === workspace.id && execution.status !== 'FINISHED');
    activity[workspace.id] = {
      openPrs: prs.filter(pr => pr.repo === monitored && pr.state === 'open').length,
      needsYou: prs.filter(pr => pr.repo === monitored && pr.state === 'open' && pullRequestNeedsReview(pr)).length,
      tasks: executions.filter(execution =>
        execution.workspaceId === workspace.id && execution.status !== 'FINISHED').length,
      spark: sparks[workspace.id] ?? null,
      runningTask: running?.workBranch ?? null,
    };
  }

  const slow: Record<string, boolean> = {};
  for (const workspace of workspaces) {
    slow[workspace.id] = workspace.lastCodeSyncAt === null
      && Date.now() - (firstSeen.current[workspace.id] ?? Date.now()) > SLOW_CLONE_MS;
  }

  return (
    <div className="ws-page">
      <div className="ws-head">
        <h1>Workspaces</h1>
        <span className="ws-count">
          {workspaces.length} watched repositor{workspaces.length === 1 ? 'y' : 'ies'}
        </span>
        <div className="ws-spacer" />
        <button className="ws-new" type="button" onClick={() => setShowModal(true)}>
          <span aria-hidden="true">＋</span> New workspace
        </button>
      </div>
      <p className="ws-lede">
        A workspace is a repository you watch. ByteQuay clones it and keeps code, pull requests
        and people in sync.
      </p>

      {error && <div className="ws-error" role="alert">{error}</div>}
      {loading && <div className="ws-empty">Loading workspaces…</div>}

      <WorkspaceCards
        workspaces={workspaces}
        onOpen={onOpen}
        onWatch={() => setShowModal(true)}
        activity={activity}
        failures={failures}
        retrying={retrying}
        slow={slow}
        onRetry={workspace => { void retrySync(workspace); }}
      />

      {showModal && (
        <AddRepoModal
          onAdded={dto => {
            setWorkspaces(previous => [dto, ...previous.filter(row => row.id !== dto.id)]
              .sort((left, right) => left.displayOrder - right.displayOrder));
            flash(`Watching ${dto.selectedOwner}/${dto.selectedRepo} — cloning in the background`);
          }}
          onOpenWorkspace={onOpen}
          onClose={() => setShowModal(false)}
        />
      )}

      {toast && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
    </div>
  );
}

function chipFor(state: SyncState, kind: 'code' | 'meta', days: number) {
  const name = kind === 'code' ? 'code' : 'labels & people';
  switch (state) {
    case 'queued': return { text: 'queued', tone: 'queued' as const, spin: false };
    case 'syncing': return {
      text: kind === 'code' ? 'cloning…' : 'syncing',
      tone: 'syncing' as const,
      spin: true,
    };
    case 'stale': return {
      text: `contributors ${days} day${days === 1 ? '' : 's'} old`,
      tone: 'stale' as const,
      spin: false,
    };
    case 'failed': return { text: `✕ ${name} failed`, tone: 'failed' as const, spin: false };
    case 'ready': return {
      text: kind === 'code' ? '✓ code ready' : '✓ labels & people',
      tone: 'ready' as const,
      spin: false,
    };
  }
}

function Sparkline({ bars, live }: { bars: number[]; live: boolean }) {
  const max = Math.max(1, ...bars);
  return (
    <div className="ws-spark" aria-hidden="true">
      {bars.map((value, index) => (
        <span
          key={index}
          className={live && index >= bars.length - 3 ? 'is-recent' : ''}
          style={{ height: Math.max(2, Math.round((value / max) * 20)) }}
        />
      ))}
    </div>
  );
}

type CardsProps = {
  workspaces: WorkspaceDto[];
  onOpen: (workspaceId: string) => void;
  onWatch?: () => void;
  activity?: Record<string, WorkspaceActivity>;
  failures?: Record<string, WorkspaceFailure>;
  retrying?: string[];
  slow?: Record<string, boolean>;
  onRetry?: (workspace: WorkspaceDto) => void;
};

export function WorkspaceCards({
  workspaces, onOpen, onWatch, activity = {}, failures = {}, retrying = [], slow = {}, onRetry,
}: CardsProps) {
  const now = Date.now();
  return (
    <div className="ws-grid">
      {workspaces.map(workspace => {
        const name = `${workspace.selectedOwner}/${workspace.selectedRepo}`;
        const upstream = `${workspace.monitoredOwner}/${workspace.monitoredRepo}`;
        const fork = workspace.topology === 'FORK';
        const failure = failures[workspace.id];
        const busy = retrying.includes(workspace.id);
        const stats = activity[workspace.id];
        const codeState: SyncState = failure ? 'failed'
          : busy ? 'syncing' : workspaceCodeState(workspace);
        const metaState: SyncState = workspaceMetadataState(workspace, now);
        const days = metadataAgeDays(workspace, now);
        const chips = [
          {
            id: 'code',
            ...(busy && workspace.lastCodeSyncAt !== null
              ? { text: 'fetching latest', tone: 'syncing' as const, spin: true }
              : chipFor(codeState, 'code', days)),
          },
          { id: 'meta', ...chipFor(metaState, 'meta', days) },
        ];
        const bars = stats?.spark ?? new Array<number>(SPARK_DAYS).fill(0);
        const weekly = (stats?.spark ?? []).slice(-7).reduce((total, value) => total + value, 0);
        const foot = footLine(workspace, codeState, metaState, stats, failure);
        const retryable = onRetry !== undefined && (failure !== undefined || slow[workspace.id] === true);
        return (
          <article
            key={workspace.id}
            data-workspace-id={workspace.id}
            className={`ws-card${failure ? ' is-failed' : ''}`}
          >
            <a
              className="ws-card__hit"
              href={workspaceRoute(workspace.id)}
              aria-label={`Open ${name}`}
              onClick={event => { event.preventDefault(); onOpen(workspace.id); }}
            />
            <div className="ws-card-title">
              <RepoAvatar
                owner={workspace.selectedOwner}
                repo={workspace.selectedRepo}
                size={20}
              />
              <span className="ws-card-name">{workspace.name}</span>
              <span className={`ws-topo${failure ? ' is-failed' : fork ? ' is-fork' : ''}`}>
                {failure ? 'needs attention' : fork ? `⑂ ${upstream}` : 'direct'}
              </span>
            </div>
            <div className="ws-card-path">
              {fork ? `${workspace.defaultBranch} · follows ${upstream}` : workspace.defaultBranch}
            </div>

            <div className="ws-stats">
              <div className="ws-stat">
                <div className={`ws-stat__n${stats?.openPrs ? '' : ' is-zero'}`}>{stats?.openPrs ?? 0}</div>
                <div className="ws-stat__label">open PRs</div>
              </div>
              <div className="ws-stat">
                <div className={`ws-stat__n is-review${stats?.needsYou ? '' : ' is-zero'}`}>{stats?.needsYou ?? 0}</div>
                <div className="ws-stat__label">need your review</div>
              </div>
              <div className="ws-stat">
                <div className={`ws-stat__n is-task${stats?.tasks ? '' : ' is-zero'}`}>{stats?.tasks ?? 0}</div>
                <div className="ws-stat__label">
                  {stats?.tasks === 1 ? 'task running' : 'tasks running'}
                </div>
              </div>
            </div>

            <div className="ws-card-spark">
              <Sparkline bars={bars} live={stats?.spark != null} />
              <span className="ws-card-spark__label">
                {stats?.spark == null
                  ? 'activity after first sync'
                  : `${weekly} commit${weekly === 1 ? '' : 's'} this week`}
              </span>
            </div>

            <div className="ws-chips">
              {chips.map(chip => (
                <span className={`ws-chip is-${chip.tone}`} key={chip.id}>
                  {chip.spin && <span className="ws-chip__spinner" aria-hidden="true" />}
                  {chip.text}
                </span>
              ))}
            </div>

            <div className="ws-card-foot">
              <span
                className="ws-card-foot__dot"
                style={{ background: syncStateColor(failure ? 'failed' : codeState === 'ready' ? metaState : codeState) }}
                aria-hidden="true"
              />
              <span className="ws-card-foot__text">{foot}</span>
              <div className="ws-spacer" />
              {retryable && (
                <button
                  className="ws-card__retry"
                  type="button"
                  disabled={busy}
                  onClick={() => onRetry?.(workspace)}
                >
                  {busy ? 'Retrying…' : workspace.lastCodeSyncAt === null ? 'Retry clone' : 'Retry sync'}
                </button>
              )}
            </div>
          </article>
        );
      })}

      {onWatch && (
        <button className="ws-new-tile" type="button" onClick={onWatch}>
          <span className="ws-new-tile__plus" aria-hidden="true">＋</span>
          <span className="ws-new-tile__title">Watch a repository</span>
          <span className="ws-new-tile__hint">
            Search GitHub and watch it — the repo becomes a workspace.
          </span>
        </button>
      )}
    </div>
  );
}

function footLine(
  workspace: WorkspaceDto,
  codeState: SyncState,
  metaState: SyncState,
  stats: WorkspaceActivity | undefined,
  failure: WorkspaceFailure | undefined,
): string {
  if (failure) {
    const when = relativeTime(failure.at);
    return `sync failed ${when === 'now' ? 'just now' : when}`;
  }
  if (stats?.runningTask) return `cherry-pick ${stats.runningTask} running`;
  if (codeState === 'queued') return 'saved just now — open it any time';
  if (codeState === 'syncing') return `cloning ${workspace.selectedOwner}/${workspace.selectedRepo}…`;
  if (metaState === 'syncing') return 'labels & people syncing';
  return `synced ${relativeTime(workspace.lastCodeSyncAt)}`;
}
