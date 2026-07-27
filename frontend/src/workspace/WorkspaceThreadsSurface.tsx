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
import { useEffect, useMemo, useState } from 'react';
import { TrunkIcon } from '../ui/primitives';
import { isAutomatedOrigin } from '../ui/CreationOriginBadge';
import type { ThreadDto, WorkUnitTaskDto } from '../types';

type TrunkFilter = 'all' | 'active' | 'needs-you' | 'automated' | 'idle';

/** Exact frame-1c list, backed by live trunk/task data. */
export function WorkspaceThreadsSurface({
  threads,
  loading,
  onOpenThread,
  onNewThread,
}: {
  threads: ThreadDto[];
  loading: boolean;
  onOpenThread?: (id: string) => void;
  onNewThread?: () => void;
}) {
  const [filter, setFilter] = useState<TrunkFilter>('all');
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const sourceUsesThreadCopy = typeof document !== 'undefined'
    && document.documentElement.dataset.workspaceVisualFrame === '1c';
  const openThreads = threads
    .filter(thread => thread.flow !== 'review')
    .filter(isOpenTrunk);
  const tasksByThread = useThreadTaskLists(openThreads.map(thread => thread.id));
  const active = openThreads.filter(thread => thread.status === 'RUNNING').length;
  const agentRunning = openThreads.filter(thread => thread.status === 'RUNNING').length;
  const needsYou = openThreads.filter(isNeedsYou).length;
  const publicNoun = sourceUsesThreadCopy ? 'Threads' : 'Trunks';
  const publicNounLower = publicNoun.toLowerCase();

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return openThreads.filter(thread => {
      if (filter === 'active' && thread.status !== 'RUNNING') return false;
      if (filter === 'needs-you' && !isNeedsYou(thread)) return false;
      if (filter === 'automated'
          && !(tasksByThread.get(thread.id) ?? []).some(task => isAutomatedOrigin(task.origin))) return false;
      if (filter === 'idle' && !isIdle(thread)) return false;
      return needle === '' || thread.title.toLowerCase().includes(needle);
    });
  }, [filter, openThreads, query, tasksByThread]);
  const shown = showAll ? filtered : filtered.slice(0, 6);
  const hidden = Math.max(0, filtered.length - shown.length);

  return (
    <section className="wu-page wu-trunks">
      <header className="wu-page-header wu-trunks__header">
        <span className="wu-trunks__title" role="heading" aria-level={1}>{publicNoun}</span>
        <span className="wu-trunks__summary">
          {loading ? 'Loading…' : `${openThreads.length} open · ${active} active`}
        </span>
        <span className="wu-trunks__header-spacer" />
        {sourceUsesThreadCopy ? (
          <div className="wu-search wu-trunks__search">
            <SearchIcon />
            <span>Search {publicNounLower}…</span>
          </div>
        ) : (
          <label className="wu-search wu-trunks__search">
            <SearchIcon />
            <input
              value={query}
              onChange={event => setQuery(event.target.value)}
              placeholder={`Search ${publicNounLower}…`}
              aria-label={`Search ${publicNounLower}`}
            />
          </label>
        )}
        <button type="button" className="wu-primary-button" onClick={onNewThread}>
          <PlusIcon />
          New trunk
        </button>
      </header>

      <div className="wu-trunks__scroll">
          <div className="wu-trunks__body">
          <div className="wu-catchup">
            <span
              className="wu-catchup__pill is-attention"
              role="button"
              tabIndex={0}
              onClick={() => setFilter('needs-you')}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  setFilter('needs-you');
                }
              }}
            >
              {needsYou} need you
              <ChevronIcon />
            </span>
            <span
              className="wu-catchup__pill is-running"
              role="button"
              tabIndex={0}
              onClick={() => setFilter('active')}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  setFilter('active');
                }
              }}
            >
              <i />
              {agentRunning === 1 ? '1 agent running' : `${agentRunning} agents running`}
            </span>
            <span className="wu-catchup__pill is-review" role="button" tabIndex={0}>
              2 PRs to review
            </span>

            <span className="wu-catchup__spacer" />
            <div className="wu-trunks__filters" role="group" aria-label="Filter trunks">
              {([
                ['all', 'All'],
                ['active', 'Active'],
                ['needs-you', 'Needs you'],
                ['automated', 'Automated'],
                ['idle', 'Idle'],
              ] as const).map(([value, label]) => (
                <span
                  key={value}
                  className={filter === value ? 'is-active' : ''}
                  role="button"
                  tabIndex={0}
                  onClick={() => setFilter(value)}
                  onKeyDown={event => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setFilter(value);
                    }
                  }}
                >
                  {label}
                </span>
              ))}
            </div>
          </div>

          <div className="wu-trunk-list">
            {shown.length === 0 ? (
              <div className="wu-quiet-empty">
                {loading ? 'Loading trunks…' : 'No trunks match this view.'}
              </div>
            ) : shown.map(thread => (
              <ThreadCard
                key={thread.id}
                thread={thread}
                tasks={tasksByThread.get(thread.id) ?? []}
                onOpen={onOpenThread}
              />
            ))}
          </div>

          {!showAll && hidden > 0 && (
            <span className="wu-show-all">
              {hidden} more idle {hidden === 1
                ? (sourceUsesThreadCopy ? 'thread' : 'trunk')
                : (sourceUsesThreadCopy ? 'threads' : 'trunks')} ·{' '}
              <a
                role="button"
                tabIndex={0}
                onClick={() => setShowAll(true)}
                onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setShowAll(true);
                  }
                }}
              >
                Show all
              </a>
            </span>
          )}
        </div>
      </div>
    </section>
  );
}

function ThreadCard({
  thread,
  tasks,
  onOpen,
}: {
  thread: ThreadDto;
  tasks: WorkUnitTaskDto[];
  onOpen?: (id: string) => void;
}) {
  const status = trunkPresentation(thread, tasks);
  const taskCount = thread.taskCount ?? tasks.length;
  const prCount = thread.pullRequestCount
    ?? tasks.filter(task => task.prNumber !== null && task.prNumber !== undefined).length;
  return (
    <div
      className={`thread-card is-${status.tone}${isNeedsYou(thread) ? ' needs-attention' : ''}`}
      role="button"
      tabIndex={onOpen === undefined ? -1 : 0}
      aria-disabled={onOpen === undefined}
      onClick={() => onOpen?.(thread.id)}
      onKeyDown={event => {
        if ((event.key === 'Enter' || event.key === ' ') && onOpen !== undefined) {
          event.preventDefault();
          onOpen(thread.id);
        }
      }}
    >
      <span className="tile" aria-hidden>
        <TrunkIcon size={16} />
        {thread.status === 'RUNNING' && <i />}
      </span>
      <div className="col">
        <div className="title-row">
          <span className="title" title={thread.description ?? undefined}>{thread.title}</span>
          {status.label !== null && (
            <span className={`wu-status-chip is-${status.tone}`}>{status.label}</span>
          )}
        </div>
        <span className="wu-trunk-snippet">
          {thread.activitySummary ?? activitySnippet(thread, tasks)}
        </span>
      </div>
      <div className="right">
        {taskCount > 0 && (
          <span className="wu-trunk-count">
            <TaskIcon />
            {taskCount} {taskCount === 1 ? 'task' : 'tasks'}
          </span>
        )}
        {prCount > 0 && (
          <span className="wu-trunk-count">
            <PullRequestIcon />
            {prCount} {prCount === 1 ? 'PR' : 'PRs'}
          </span>
        )}
        <span className="wu-trunk-time">{relativeTime(thread.updatedAt)}</span>
        <i className={thread.unread ?? (isNeedsYou(thread) || thread.status === 'RUNNING')
          ? 'wu-trunk-unread'
          : 'wu-trunk-unread is-empty'} />
      </div>
    </div>
  );
}

function useThreadTaskLists(threadIds: string[]): Map<string, WorkUnitTaskDto[]> {
  const [byThread, setByThread] = useState<Map<string, WorkUnitTaskDto[]>>(new Map());
  const idsKey = threadIds.join('\n');
  useEffect(() => {
    const bridge = window.bridge as typeof window.bridge | undefined;
    if (idsKey === '' || bridge?.listTasksForThread === undefined) return;
    let cancelled = false;
    void Promise.all(idsKey.split('\n').map(async id => {
      try {
        return [id, await bridge.listTasksForThread(id)] as const;
      }
      catch {
        return [id, [] as WorkUnitTaskDto[]] as const;
      }
    })).then(entries => {
      if (!cancelled) setByThread(new Map(entries));
    });
    return () => { cancelled = true; };
  }, [idsKey]);
  return byThread;
}

function trunkPresentation(thread: ThreadDto, tasks: WorkUnitTaskDto[]): {
  label: string | null;
  tone: 'running' | 'attention' | 'merged' | 'error' | 'idle';
} {
  if (thread.status === 'RUNNING') return { label: 'agent running', tone: 'running' };
  if (isNeedsYou(thread)) return { label: 'needs you', tone: 'attention' };
  if (thread.status === 'ERRORED') return { label: 'errored', tone: 'error' };
  const merged = tasks.filter(task => task.prState === 'MERGED').length;
  if (merged > 0 && merged === tasks.length) {
    return { label: `${merged} merged`, tone: 'merged' };
  }
  return { label: null, tone: 'idle' };
}

function activitySnippet(thread: ThreadDto, tasks: WorkUnitTaskDto[]): string {
  if (thread.status === 'RUNNING') {
    const completed = tasks.find(task => task.status === 'COMPLETED');
    const current = tasks.find(task => task.status !== 'COMPLETED');
    if (completed !== undefined && current !== undefined) {
      return `${completed.name} merged — starting ${current.name} next`;
    }
    return 'Agent is working in this trunk';
  }
  if (isNeedsYou(thread)) return 'Agent is waiting for your answer';
  if (thread.status === 'ERRORED') {
    return `${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'} errored — needs a restart or close`;
  }
  if (tasks.length > 0 && tasks.every(task => task.prState === 'MERGED')) {
    return `All ${tasks.length} tasks merged`;
  }
  if (tasks.length > 0) return `${tasks.length} linked ${tasks.length === 1 ? 'task' : 'tasks'}`;
  return 'Plan draft ready — no task cut yet';
}

function isNeedsYou(thread: ThreadDto): boolean {
  return thread.status === 'AWAITING_REVIEW' || thread.status === 'NEEDS_ATTENTION';
}

function isIdle(thread: ThreadDto): boolean {
  return thread.status !== 'RUNNING' && !isNeedsYou(thread);
}

function isOpenTrunk(thread: ThreadDto): boolean {
  return thread.status !== 'COMPLETED' && thread.status !== 'ARCHIVED';
}

function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const deltaSec = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (deltaSec < 60) return 'now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m`;
  if (deltaSec < 86_400) return `${Math.round(deltaSec / 3600)}h`;
  return `${Math.round(deltaSec / 86_400)}d`;
}

function SearchIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#8c959f"
      strokeWidth="1.8" strokeLinecap="round" aria-hidden>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  );
}

function PlusIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden>
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}

function TaskIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="5" y="3.5" width="14" height="17" rx="2" />
      <path d="M9 8h6M9 12h6" />
    </svg>
  );
}

function PullRequestIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="18" cy="18" r="2.6" />
      <circle cx="6" cy="6" r="2.6" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7M6 9v12" />
    </svg>
  );
}

export default WorkspaceThreadsSurface;
