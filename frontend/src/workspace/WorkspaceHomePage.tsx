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
import { Fragment, useCallback, useEffect, useState } from 'react';
import type { ThreadDto, WorkUnitTaskDto } from '../types';
import type { WorkspaceSection } from './WorkspaceShell';

type Props = {
  /** Routes the "View all →" / "Open →" affordances directly into
   *  the matching workspace section. */
  onSelectSection: (section: WorkspaceSection) => void;
  /** Open the new-thread modal. The shell owns the modal state so
   *  the dialog can also be triggered from other surfaces later. */
  onNewThread?: () => void;
};

const WORKSPACE_ID = 'ws-default';
const ACTIVE_THREADS_PREVIEW = 3;
const TASKS_PREVIEW = 4;
const MEMORY_TOKEN_CAP = 4_000;
const CHARS_PER_TOKEN = 4;

/** Workspace Home overview — a calm landing page summarising what's
 *  in flight inside the workspace. Three cards (active threads,
 *  tasks in flight, memory excerpt with budget bar) and a topline
 *  with the rolled-up counts plus today's spend.
 *
 *  <p>Data pulls from existing bridge endpoints: listTasks() gives
 *  the thread list (the {@code listTasks} naming is a holdover from
 *  the Task→Thread rename), and getWorkspaceMemory() gives the
 *  markdown body for excerpts and the budget bar. Spend today is a
 *  rough estimate summed from threads updated today — proper
 *  aggregation lands with Insights (commit 3). */
function WorkspaceHomePage({ onSelectSection, onNewThread }: Props) {
  const [threads, setThreads] = useState<ThreadDto[]>([]);
  const [memoryMd, setMemoryMd] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [threadList, memory] = await Promise.all([
        window.bridge.listTasks(),
        window.bridge.getWorkspaceMemory(WORKSPACE_ID),
      ]);
      setThreads(threadList);
      setMemoryMd(memory.memoryMd);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const activeThreads = threads.filter(isActiveThread);
  const tasksInFlight = threads
      .map(t => t.activeTask)
      .filter((t): t is NonNullable<typeof t> => t !== null && isInFlightStatus(t.status));
  const spentTodayMilli = threads
      .filter(t => isUpdatedToday(t.updatedAt))
      .reduce((sum, t) => sum + (t.costUsdMilli || 0), 0);

  return (
    <>
      <header className="workspace-pageheader">
        <div>
          <h1 className="workspace-pageheader__title">ByteQuay</h1>
          <div className="workspace-pageheader__meta">
            {loading
              ? 'loading…'
              : `${activeThreads.length} active thread${activeThreads.length === 1 ? '' : 's'}`
                + ` · ${tasksInFlight.length} task${tasksInFlight.length === 1 ? '' : 's'} in flight`
                + ` · ${formatMilliUsd(spentTodayMilli)} today`}
          </div>
        </div>
        <button
          type="button"
          className="workspace-pageheader__action"
          onClick={onNewThread}
          disabled={!onNewThread}
        >
          + New thread
        </button>
      </header>

      {error !== null && <div style={errorStyle} role="alert">{error}</div>}

      <section className="workspace-card" style={{ marginBottom: 14 }} aria-label="Active threads">
        <div className="workspace-card__head">
          <div className="workspace-card__title">
            Active threads
            <span className="workspace-card__title-count">{activeThreads.length}</span>
          </div>
          <button
            type="button"
            className="workspace-card__link"
            onClick={() => onSelectSection('threads')}
          >
            View all →
          </button>
        </div>
        {activeThreads.length === 0 ? (
          <div style={emptyRowStyle}>
            {loading ? 'Loading…' : 'No active threads — workspace is at rest.'}
          </div>
        ) : (
          <ul style={listStyle}>
            {activeThreads.slice(0, ACTIVE_THREADS_PREVIEW).map(t => (
              <li key={t.id} style={threadRowStyle}>
                <span style={statusDotStyle(t.status)} aria-hidden />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={threadTitleStyle}>{t.title}</div>
                  <ThreadMetaLine task={t.activeTask} />
                </div>
                <div style={threadRightStyle}>
                  <div>{relativeTime(t.updatedAt)}</div>
                  <div style={threadCostStyle}>{formatMilliUsd(t.costUsdMilli)}</div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div style={twoColStyle}>
        <section className="workspace-card" aria-label="Tasks in flight">
          <div className="workspace-card__head">
            <div className="workspace-card__title">
              Tasks in flight
              <span className="workspace-card__title-count">{tasksInFlight.length}</span>
            </div>
            <button
              type="button"
              className="workspace-card__link"
              onClick={() => onSelectSection('threads')}
            >
              All →
            </button>
          </div>
          {tasksInFlight.length === 0 ? (
            <div style={emptyRowStyle}>
              {loading ? 'Loading…' : 'No tasks in flight.'}
            </div>
          ) : (
            <ul style={listStyle}>
              {tasksInFlight.slice(0, TASKS_PREVIEW).map(t => (
                <li key={t.id} style={taskRowStyle}>
                  <div style={taskTitleStyle}>
                    {t.branchName ?? 'no branch'}
                    <span style={taskSeqStyle}>:{t.seq}</span>
                  </div>
                  <div style={taskMetaStyle}>
                    {t.linkedPrNumber !== null
                      ? `PR #${t.linkedPrNumber}`
                      : 'no PR yet'}
                    {' · '}{(t.status || 'idle').toLowerCase()}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="workspace-card" aria-label="Memory">
          <div className="workspace-card__head">
            <div className="workspace-card__title">Memory</div>
            <button
              type="button"
              className="workspace-card__link"
              onClick={() => onSelectSection('memory')}
            >
              Open →
            </button>
          </div>
          {memoryExcerpt(memoryMd).map(section => (
            <div key={section.heading} style={memorySectionStyle}>
              <div style={memorySectionHeadStyle}>{section.heading}</div>
              <ul style={memoryBulletsStyle}>
                {section.bullets.map((b, i) => (
                  <li key={i} style={memoryBulletStyle}>{b}</li>
                ))}
              </ul>
            </div>
          ))}
          <MemoryBudgetBar charLength={memoryMd.length} />
        </section>
      </div>
    </>
  );
}

function MemoryBudgetBar({ charLength }: { charLength: number }) {
  const approxTokens = Math.round(charLength / CHARS_PER_TOKEN);
  const pct = Math.min(100, Math.round((approxTokens / MEMORY_TOKEN_CAP) * 100));
  const healthy = approxTokens < MEMORY_TOKEN_CAP * 0.95;
  const color = healthy ? '#16a34a' : '#cf1322';
  return (
    <div style={budgetWrapStyle}>
      <div style={{ ...budgetTrackStyle }}>
        <div style={{ ...budgetFillStyle, width: `${pct}%`, background: color }} />
      </div>
      <div style={budgetTextStyle}>
        {approxTokens.toLocaleString()} / {MEMORY_TOKEN_CAP.toLocaleString()} tokens
        {' · '}
        <span style={{ color }}>{healthy ? 'healthy' : 'over budget'}</span>
      </div>
    </div>
  );
}

/** The Active threads row's metadata line. Three shapes:
 *  - 0-Task discussion thread → "discussion · no task yet".
 *  - Task-bearing thread without a PR yet → branch chip + "awaiting
 *    approval" when the task is parked at the publish gate.
 *  - Task-bearing thread with a PR → branch chip + PR # + state qualifier
 *    (draft/merged/closed; "open" is omitted as the default state).
 *
 *  Task-progression hint ("task N of N") shows once the thread has rolled
 *  through ship-&-continue at least once (seq ≥ 2); a single-task thread
 *  doesn't surface the count since it adds no information. */
function ThreadMetaLine({ task }: { task: WorkUnitTaskDto | null }) {
  if (task === null) {
    return <div style={threadMetaStyle}>discussion · no task yet</div>;
  }

  const segs: { key: string; node: React.ReactNode }[] = [];

  if (task.branchName !== null) {
    segs.push({
      key: 'branch',
      node: <span style={branchChipStyle}>↗ {task.branchName}</span>,
    });
  }

  if (task.linkedPrNumber !== null) {
    const stateLabel = formatPrState(task.prState);
    segs.push({
      key: 'pr',
      node: stateLabel === null
        ? <span style={monoStyle}>PR #{task.linkedPrNumber}</span>
        : <><span style={monoStyle}>PR #{task.linkedPrNumber}</span> {stateLabel}</>,
    });
  }
  else if (task.status === 'AWAITING') {
    segs.push({ key: 'await', node: 'awaiting approval' });
  }

  if (task.seq >= 2) {
    segs.push({ key: 'seq', node: `task ${task.seq} of ${task.seq}` });
  }

  if (segs.length === 0) {
    return <div style={threadMetaStyle}>no task yet</div>;
  }

  return (
    <div style={threadMetaStyle}>
      {segs.map((s, i) => (
        <Fragment key={s.key}>
          {i > 0 && ' · '}
          {s.node}
        </Fragment>
      ))}
    </div>
  );
}

function formatPrState(prState: string | null): string | null {
  if (prState === null || prState.length === 0) return null;
  const lower = prState.toLowerCase();
  // "open" is the default — surface only the qualifier states.
  if (lower === 'open') return null;
  return lower;
}

/* ── helpers ─────────────────────────────────────────────────── */

function isActiveThread(t: ThreadDto): boolean {
  // Non-terminal status set lines up with the backend's
  // findActiveTaskForThread filter (PENDING/RUNNING/AWAITING/IDLE).
  return t.status === 'PENDING' || t.status === 'RUNNING'
      || t.status === 'AWAITING' || t.status === 'IDLE';
}

function isInFlightStatus(status: string | null | undefined): boolean {
  if (status === null || status === undefined) return false;
  return status === 'PENDING' || status === 'RUNNING'
      || status === 'AWAITING' || status === 'IDLE';
}

function isUpdatedToday(iso: string): boolean {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return false;
  const now = new Date();
  return then.getUTCFullYear() === now.getUTCFullYear()
      && then.getUTCMonth() === now.getUTCMonth()
      && then.getUTCDate() === now.getUTCDate();
}

function formatMilliUsd(milli: number): string {
  const dollars = milli / 1000;
  return `$${dollars.toFixed(2)}`;
}

function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const deltaSec = Math.round((Date.now() - then) / 1000);
  if (deltaSec < 60) return 'now';
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`;
  return `${Math.round(deltaSec / 86400)}d ago`;
}

type MemorySection = { heading: string; bullets: string[] };

/** Pull a couple of bullets from the Decisions + Blockers sections —
 *  matches what the workspace-home mockup surfaces. Light parser, not
 *  a full markdown renderer; whatever isn't a recognised section
 *  silently drops. */
function memoryExcerpt(md: string): MemorySection[] {
  if (md.length === 0) return [];
  const wanted = new Set(['Decisions', 'Blockers']);
  const out: MemorySection[] = [];
  const lines = md.split('\n');
  let cur: MemorySection | null = null;
  for (const line of lines) {
    const match = /^##\s+(.*)$/.exec(line.trim());
    if (match) {
      const heading = match[1].trim();
      if (wanted.has(heading)) {
        cur = { heading, bullets: [] };
        out.push(cur);
      }
      else {
        cur = null;
      }
      continue;
    }
    if (cur === null) continue;
    const bullet = /^\s*[-*]\s+(.+)$/.exec(line);
    if (bullet && cur.bullets.length < 2) {
      // Strip back-link markers from the excerpt — they'd add noise
      // here; users see the full chips in the Memory page.
      cur.bullets.push(bullet[1].replace(/\s*\[thread:[A-Za-z0-9_-]+\]/g, '').trim());
    }
  }
  return out;
}

/* ── styles ──────────────────────────────────────────────────── */

function statusDotStyle(status: string): React.CSSProperties {
  const color = status === 'RUNNING' ? '#16a34a'
      : status === 'AWAITING' || status === 'AWAITING_REVIEW' ? '#d97706'
      : '#7a7388';
  return {
    width: 7,
    height: 7,
    borderRadius: 4,
    background: color,
    flexShrink: 0,
    marginTop: 6,
  };
}

const twoColStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 14,
};

const listStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const threadRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  fontSize: 13,
  padding: '4px 0',
};

const threadTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  color: 'var(--ws-text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const threadMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 0,
};

const branchChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 3,
  padding: '1px 6px',
  borderRadius: 4,
  background: 'rgba(124, 58, 237, 0.08)',
  color: 'var(--ws-accent-deep)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const monoStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

// Tasks-in-flight card uses mono throughout (branch label, PR number,
// status word). Group B will rewrite the row layout; keeping the old
// mono treatment here in the meantime avoids a visual regression.
const taskMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const threadRightStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  textAlign: 'right',
  flexShrink: 0,
  marginLeft: 8,
};

const threadCostStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  marginTop: 2,
};

const taskRowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  padding: '4px 0',
};

const taskTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  color: 'var(--ws-text-1)',
};

const taskSeqStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
};

const memorySectionStyle: React.CSSProperties = {
  marginBottom: 10,
};

const memorySectionHeadStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  color: 'var(--ws-text-3)',
  marginBottom: 4,
};

const memoryBulletsStyle: React.CSSProperties = {
  margin: 0,
  paddingLeft: 16,
};

const memoryBulletStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-2)',
  lineHeight: 1.5,
};

const budgetWrapStyle: React.CSSProperties = {
  marginTop: 10,
};

const budgetTrackStyle: React.CSSProperties = {
  height: 4,
  background: 'rgba(124, 58, 237, 0.12)',
  borderRadius: 999,
  overflow: 'hidden',
};

const budgetFillStyle: React.CSSProperties = {
  height: '100%',
  transition: 'width 140ms ease',
};

const budgetTextStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 4,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
};

const emptyRowStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-3)',
  padding: '6px 0',
};

const errorStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: 10,
  background: 'rgba(207, 19, 34, 0.06)',
  border: '1px solid rgba(207, 19, 34, 0.4)',
  borderRadius: 8,
  color: '#cf1322',
  fontSize: 12,
};

export default WorkspaceHomePage;
