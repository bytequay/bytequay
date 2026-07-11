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
import { TrunkIcon } from '../ui/primitives';
import { threadRepo } from '../pages/useWorkspaceNav';
import type { ThreadDto } from '../types';

/**
 * The workspace Trunks tab body — the redesign's `.surface` with an
 * "Open threads · N active · All repos" header and a list of full-width
 * thread cards (trunk tile · title · repo chip + kind + task hint ·
 * time · chevron). Presentational: the shell owns the data + routing.
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
        <span className="n">{loading ? '' : `${open.length} active`}</span>
        <span className="grow" />
        <span className="filter">
          <TrunkIcon size={13} />
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
      <span className="tile" aria-hidden><TrunkIcon size={18} /></span>
      <div className="col">
        <div className="title-row"><span className="title">{thread.title}</span></div>
        <div className="meta-row">
          <span className="repo-chip">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M3 12h4l3 8 4-16 3 8h4" />
            </svg>
            {repo}
          </span>
          <span className="kind">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            discussion
          </span>
          <span className="task-hint">no task yet</span>
        </div>
      </div>
      <div className="right">
        <span className="ts">{relativeTime(thread.updatedAt)}</span>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--text-4)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="m9 18 6-6-6-6" />
        </svg>
      </div>
    </button>
  );
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
