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
import { Logo } from '../ui/primitives';
import { logoColorFor, monogram, threadRepo } from '../pages/useWorkspaceNav';
import { taskStatusBadge } from '../threads/taskStatusBadge';
import type { ThreadDto, WorkUnitTaskDto } from '../types';

/**
 * The workspace Threads tab body — the design's `.surface` with a header
 * and a list of full-width thread cards (repo logo · title · meta · time ·
 * task pill). Presentational: the shell owns the data + click routing.
 */
export function WorkspaceThreadsSurface({ threads, loading, onOpenThread }: {
  threads: ThreadDto[];
  loading: boolean;
  onOpenThread?: (id: string) => void;
}) {
  const open = threads.filter(isOpenThread);
  return (
    <div className="surface">
      <div className="surface-h">
        <span className="t">Open threads</span>
        <span className="grow" />
        <span className="filter">
          <span className="ic" aria-hidden>⛚</span>
          All repos
          <span style={{ color: 'var(--text-4)', fontSize: 9 }} aria-hidden>▾</span>
        </span>
      </div>
      {open.length === 0 ? (
        <div className="ghost">{loading ? 'Loading…' : 'No open threads — this workspace is at rest.'}</div>
      ) : (
        open.map(t => <ThreadCard key={t.id} thread={t} onOpen={onOpenThread} />)
      )}
    </div>
  );
}

function ThreadCard({ thread, onOpen }: { thread: ThreadDto; onOpen?: (id: string) => void }) {
  const repo = threadRepo(thread);
  return (
    <button
      type="button"
      className="thread-card"
      onClick={() => onOpen?.(thread.id)}
      disabled={onOpen === undefined}
    >
      <Logo initials={monogram(repo)} color={logoColorFor(repo)} size="md" />
      <div className="col">
        <div className="title-row"><span className="title">{thread.title}</span></div>
        <div className="sub">{subText(repo, thread.activeTask)}</div>
      </div>
      <div className="right">
        <span className="ts">{relativeTime(thread.updatedAt)}</span>
        {thread.activeTask !== null && (
          <TaskPill task={thread.activeTask} />
        )}
      </div>
    </button>
  );
}

/** The right-hand task pill: a colour-coded status dot + "N tasks · status",
 *  so running vs finished reads at a glance across the list. */
function TaskPill({ task }: { task: WorkUnitTaskDto }) {
  const { label, tone } = taskStatusBadge(task.status);
  const count = `${task.seq} task${task.seq === 1 ? '' : 's'}`;
  return (
    <span className={`tasks-pill tasks-pill--${tone}`}>
      <span className="dot" aria-hidden />
      {`${count} · ${label.toLowerCase()}`}
    </span>
  );
}

/** The card's second line: repo · what's happening on the active task. */
function subText(repo: string, task: WorkUnitTaskDto | null): string {
  if (task === null) return `${repo} · discussion · no task yet`;
  if (task.branchName !== null && task.branchName.length > 0) {
    return `${repo} · ${task.branchName}`;
  }
  return `${repo} · no branch yet`;
}

/** Open = non-terminal: everything except the resting states
 *  (COMPLETED / ARCHIVED / ERRORED). */
function isOpenThread(t: ThreadDto): boolean {
  return t.status !== 'COMPLETED' && t.status !== 'ARCHIVED' && t.status !== 'ERRORED';
}

function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const deltaSec = Math.round((Date.now() - then) / 1000);
  if (deltaSec < 60) return 'now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86_400) return `${Math.round(deltaSec / 3600)}h ago`;
  return `${Math.round(deltaSec / 86_400)}d ago`;
}

export default WorkspaceThreadsSurface;
