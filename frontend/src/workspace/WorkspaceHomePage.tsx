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
import type { ReviewThreadPrSummaryDto, ThreadDto, WorkUnitTaskDto } from '../types';
import type { WorkspaceSection } from './WorkspaceShell';

type Props = {
  /** Active workspace id. Routes the thread-list + memory fetches so
   *  a workspace switch shows the right slice. */
  workspaceId: string;
  /** Active workspace's display name — rendered as the page title.
   *  Pass through from the shell so the title tracks renames + switches. */
  workspaceName: string;
  /** Routes the "View all →" / "Open →" affordances directly into
   *  the matching workspace section. */
  onSelectSection: (section: WorkspaceSection) => void;
  /** Open the new-thread modal. The shell owns the modal state so
   *  the dialog can also be triggered from other surfaces later. */
  onNewThread?: () => void;
  /** Open the assign-review-task modal — picks a PR awaiting review
   *  + the panel + caps, then spins up a review-flow thread. */
  onAssignReview?: () => void;
  /** Navigate to a thread's detail page. Wired so each Active threads
   *  / Tasks-in-flight row is a clickable target. */
  onOpenThread?: (threadId: string) => void;
};

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
function WorkspaceHomePage({ workspaceId, workspaceName, onSelectSection, onNewThread, onAssignReview, onOpenThread }: Props) {
  const [threads, setThreads] = useState<ThreadDto[]>([]);
  const [memoryMd, setMemoryMd] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // PR title + author per review thread, for labelling review rows.
  const [prSummaries, setPrSummaries] = useState<Map<string, ReviewThreadPrSummaryDto>>(new Map());

  const refresh = useCallback(async () => {
    try {
      const [threadList, memory] = await Promise.all([
        window.bridge.listTasks({ workspaceId }),
        window.bridge.getWorkspaceMemory(workspaceId),
      ]);
      setThreads(threadList.filter(thread => !isReviewThread(thread)));
      setMemoryMd(memory.memoryMd);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => { void refresh(); }, [refresh]);

  // Best-effort PR labels for the review threads on the page.
  useEffect(() => {
    const reviewIds = threads.filter(isReviewThread).map(t => t.id);
    if (reviewIds.length === 0) {
      setPrSummaries(new Map());
      return;
    }
    let cancelled = false;
    void window.bridge.getReviewThreadPrSummaries(reviewIds)
      .then(list => {
        if (!cancelled) setPrSummaries(new Map(list.map(s => [s.threadId, s])));
      })
      .catch(() => { /* labelling is best-effort */ });
    return () => { cancelled = true; };
  }, [threads]);

  const activeThreads = threads.filter(isActiveThread);
  // Per-thread active-task data is no longer carried on ThreadDto; without a
  // task list in scope here we render the card empty rather than refetch.
  // ponytail: empty until a task list is wired into this surface.
  const tasksInFlight: WorkUnitTaskDto[] = [];
  const spentTodayMilli = threads
      .filter(t => isUpdatedToday(t.updatedAt))
      .reduce((sum, t) => sum + (t.costUsdMilli || 0), 0);
  // The first row of Active threads is the default click target on
  // workspace entry — keyed off thread id so the highlight survives
  // re-renders even if the list shuffles slightly.
  const defaultThreadId = activeThreads[0]?.id ?? null;

  return (
    <>
      <header className="workspace-pageheader">
        <div className="workspace-pageheader__heading">
          <h1 className="workspace-pageheader__title">{workspaceName}</h1>
          {loading ? (
            <span className="workspace-pageheader__meta">loading…</span>
          ) : (
            <span className="workspace-pageheader__meta">
              <span className="workspace-pageheader__meta-num">{activeThreads.length}</span>
              {' active thread' + (activeThreads.length === 1 ? '' : 's')}
              <span className="workspace-pageheader__meta-sep" aria-hidden> · </span>
              <span className="workspace-pageheader__meta-num">{tasksInFlight.length}</span>
              {' task' + (tasksInFlight.length === 1 ? '' : 's') + ' in flight'}
              <span className="workspace-pageheader__meta-sep" aria-hidden> · </span>
              <span className="workspace-pageheader__meta-num">{formatMilliUsd(spentTodayMilli)}</span>
              {' today'}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {onAssignReview && (
            <button
              type="button"
              className="workspace-pageheader__action"
              onClick={onAssignReview}
              style={assignReviewBtnStyle}
              title="Spin up a multi-agent review panel on a PR awaiting your review"
            >
              ⎈ Assign review
            </button>
          )}
          <button
            type="button"
            className="workspace-pageheader__action"
            onClick={onNewThread}
            disabled={!onNewThread}
          >
            + New thread
            <span className="workspace-pageheader__action-kbd" aria-hidden>⌘N</span>
          </button>
        </div>
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
            {activeThreads.slice(0, ACTIVE_THREADS_PREVIEW).map(t => {
              const isDefault = t.id === defaultThreadId;
              const className = 'workspace-row'
                  + (isDefault ? ' workspace-row--selected' : '');
              return (
                <li key={t.id}>
                  <button
                    type="button"
                    className={className}
                    onClick={() => onOpenThread?.(t.id)}
                    disabled={!onOpenThread}
                  >
                    <span style={statusDotStyle(t.status)} aria-hidden />
                    <div style={threadBodyStyle}>
                      <div style={threadTitleRowStyle}>
                        {isReviewThread(t) && (() => {
                          const owner = (prSummaries.get(t.id)?.repoFullName
                              ?? reviewRepoFromTitle(t.title))?.split('/')[0];
                          return owner != null && owner !== '' ? (
                            <img
                              src={`https://github.com/${owner}.png?size=40`}
                              alt=""
                              style={repoLogoStyle}
                              onError={(e) => { e.currentTarget.style.display = 'none'; }}
                            />
                          ) : null;
                        })()}
                        <div style={threadTitleStyle}>
                          {isReviewThread(t)
                            ? (prSummaries.get(t.id)?.prTitle ?? t.title)
                            : t.title}
                        </div>
                        {needsAttention(t) && (
                          <span
                            style={attentionDotStyle}
                            aria-label="needs attention"
                          />
                        )}
                      </div>
                      {(() => {
                        // Review threads get a repo#PR · reviewers line in
                        // place of the generic "discussion · no task yet" —
                        // keyed off the flow, not the (best-effort) summary,
                        // so the discussion line never leaks through when the
                        // summary lookup is empty.
                        if (isReviewThread(t)) {
                          const summary = prSummaries.get(t.id);
                          const repoRef = summary != null
                            ? `${summary.repoFullName}#${summary.prNumber}`
                            : reviewRepoRefFromTitle(t.title);
                          const reviewers = summary != null && summary.reviewers.length > 0
                            ? summary.reviewers.join(', ')
                            : '';
                          return (
                            <div style={reviewersLineStyle} title={reviewers || undefined}>
                              {[repoRef, reviewers].filter(s => s !== '' && s != null).join(' · ')
                                || 'review panel'}
                            </div>
                          );
                        }
                        return <ThreadMetaLine task={null} />;
                      })()}
                    </div>
                    <div style={threadRightStyle}>
                      <div>{relativeTime(t.updatedAt)}</div>
                    </div>
                  </button>
                </li>
              );
            })}
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
                <TaskRow
                  key={t.id}
                  task={t}
                  onClick={onOpenThread ? () => onOpenThread(t.threadId) : undefined}
                />
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
                  <li key={i} style={bulletStyleFor(section.heading)}>{b}</li>
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
        {formatTokensCompact(approxTokens)} / {formatTokensCompact(MEMORY_TOKEN_CAP)}
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
function ThreadMetaLine(
  { task, author }: { task: WorkUnitTaskDto | null; author?: string | null },
) {
  if (task === null) {
    return (
      <div style={threadMetaStyle}>
        {author != null && author !== '' ? `by ${author} · ` : ''}discussion · no task yet
      </div>
    );
  }

  const segs: { key: string; node: React.ReactNode }[] = [];

  if (task.branchName !== null) {
    segs.push({
      key: 'branch',
      node: <span style={branchChipStyle}>↗ {task.branchName}</span>,
    });
  }
  else {
    // Task materialised but worktree not cut yet — surface the gap
    // so the row doesn't read as a blank space.
    segs.push({ key: 'no-branch', node: 'no branch yet' });
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

/** Compact token-count formatter for the Memory budget bar — "1.6k"
 *  instead of "1,600". Below 1k we keep the literal number; the bar
 *  loses meaning at counts that small so the form barely matters. */
function formatTokensCompact(n: number): string {
  if (n < 1000) return n.toString();
  const thousands = n / 1000;
  return thousands >= 10
    ? `${Math.round(thousands)}k`
    : `${thousands.toFixed(1)}k`;
}

function formatPrState(prState: string | null): string | null {
  if (prState === null || prState.length === 0) return null;
  const lower = prState.toLowerCase();
  // "open" is the default — surface only the qualifier states.
  if (lower === 'open') return null;
  return lower;
}

/** A row in the Tasks-in-flight card. Three columns:
 *  - status box (colored swatch keyed off the task's run status)
 *  - body: branch:seq title + a one-line status/CI hint
 *  - right-aligned PR # or "no PR"
 *
 *  Clicking the row navigates to the owning thread's detail page —
 *  one work-unit task lives in one thread, so the task is also the
 *  thread's entry point in practice.
 *
 *  Diff stats (linesAdded / linesRemoved) aren't surfaced yet — the
 *  Task DTO doesn't aggregate them. Follow-up: either a per-task
 *  rollup on the backend or a join through {@code thread_files}. */
function TaskRow({ task, onClick }: { task: WorkUnitTaskDto; onClick?: () => void }) {
  const meta = describeTaskMeta(task);
  // No branch yet → the worktree hasn't been cut. Render "task N" as
  // the title instead of "no branch:1" so the row reads as a real
  // pending work unit rather than a string concatenation accident.
  const titleText = task.branchName ?? `task ${task.seq}`;
  const showSeqSuffix = task.branchName !== null;
  return (
    <li>
      <button
        type="button"
        className="workspace-row"
        onClick={onClick}
        disabled={onClick === undefined}
      >
        <span style={taskStatusBoxStyle(task.status)} aria-hidden />
        <div style={taskBodyStyle}>
          <div style={taskTitleStyle}>
            {titleText}
            {showSeqSuffix && <span style={taskSeqStyle}>:{task.seq}</span>}
          </div>
          {meta !== null && <div style={taskMetaStyle}>{meta}</div>}
        </div>
        <div style={taskPrStyle}>
          {task.linkedPrNumber !== null ? `#${task.linkedPrNumber}` : 'no PR'}
        </div>
      </button>
    </li>
  );
}

/** Status text under a task row. AWAITING speaks for itself (publish
 *  gate); a populated CI state takes precedence over the generic
 *  status word for everything else; truly-resting states (idle /
 *  pending) fall through to the bare status word. A RUNNING task with
 *  no CI signal yet returns null — the title alone reads fine, and
 *  empty meta keeps the row visually quiet. */
function describeTaskMeta(t: WorkUnitTaskDto): string | null {
  if (t.status === 'AWAITING') return 'awaiting approval';
  if (t.status === 'IN_REVIEW') return 'in review';
  if (t.ciState !== null && t.ciState.length > 0) {
    return t.ciState.toLowerCase();
  }
  if (t.status === 'IDLE' || t.status === 'PENDING') {
    return t.status.toLowerCase();
  }
  return null;
}

function taskStatusBoxStyle(status: string): React.CSSProperties {
  const color = status === 'RUNNING' || status === 'COMPLETED' ? '#16a34a'
      : status === 'AWAITING' || status === 'AWAITING_REVIEW' ? '#d97706'
      : status === 'IN_REVIEW' ? '#7c3aed'
      : status === 'ERRORED' ? '#cf1322'
      : '#a8a3b5';
  return {
    width: 18,
    height: 18,
    borderRadius: 5,
    background: color,
    flexShrink: 0,
  };
}

/* ── helpers ─────────────────────────────────────────────────── */

/** A thread "needs attention" when something is parked at the publish
 *  gate or otherwise waiting on the human — the small purple dot next
 *  to the title is the at-a-glance signal. AWAITING on either the
 *  thread or its active task counts; the rail's unread dot uses the
 *  same predicate. */
function needsAttention(t: ThreadDto): boolean {
  return t.status === 'AWAITING';
}

function isActiveThread(t: ThreadDto): boolean {
  // Non-terminal status set lines up with the backend's
  // findActiveTaskForThread filter (PENDING/RUNNING/AWAITING/IDLE).
  return t.status === 'PENDING' || t.status === 'RUNNING'
      || t.status === 'AWAITING' || t.status === 'IDLE';
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
      : status === 'IN_REVIEW' ? '#7c3aed'
      : '#7a7388';
  return {
    width: 7,
    height: 7,
    borderRadius: 4,
    background: color,
    flexShrink: 0,
  };
}

const twoColStyle: React.CSSProperties = {
  display: 'grid',
  // Cards reflow into one column below ~664px (2 × 320px + gap). Matches
  // the design doc's repeat(auto-fit, minmax(320px, 1fr)) primitive so
  // the workspace surfaces don't hard-break at a fixed width.
  gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
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

// Row layout (gap, padding, hover, selected-state) lives on the
// .workspace-row CSS class — the row is a <button> so it can be a
// real click target. These inline styles only cover the body column.
const threadBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 13,
};

// Title sits next to an optional attention dot — flex row so the dot
// stays put while the title clips with an ellipsis. min-width: 0 on
// both is what unlocks the ellipsis inside a flex container.
const threadTitleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
};

const threadTitleStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const attentionDotStyle: React.CSSProperties = {
  width: 7,
  height: 7,
  borderRadius: 4,
  background: 'var(--ws-accent)',
  flexShrink: 0,
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
const threadPrTitleStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-2, var(--text-2))',
  marginTop: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

/** Review threads carry flow='review'. Tolerant of casing in case the
 *  wire value ever arrives as the enum name. */
function isReviewThread(t: ThreadDto): boolean {
  return String(t.flow).toLowerCase() === 'review';
}

/** "Review owner/repo#123" → "owner/repo" (legacy titles, before threads
 *  were named by the PR title). Null for a PR-title-named thread. */
function reviewRepoFromTitle(title: string): string | null {
  const m = /^Review (\S+?\/\S+?)#\d+/.exec(title);
  return m !== null ? m[1] : null;
}

/** "Review owner/repo#123" → "owner/repo#123". Empty for a PR-title thread. */
function reviewRepoRefFromTitle(title: string): string {
  const m = /^Review (\S+?\/\S+?#\d+)/.exec(title);
  return m !== null ? m[1] : '';
}

const repoLogoStyle: React.CSSProperties = {
  width: 16,
  height: 16,
  borderRadius: 4,
  flexShrink: 0,
  objectFit: 'cover',
};

// The review-thread roster line: single line, clipped with an ellipsis
// when the seat labels overflow the row.
const reviewersLineStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
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

// Tasks-in-flight meta is now prose (status word or CI state). Stats
// like "+47/-12" would be mono, but they aren't surfaced yet — see the
// TaskRow comment for the data-model follow-up.
const taskMetaStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  marginTop: 2,
};

const threadRightStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  textAlign: 'right',
  flexShrink: 0,
};


const taskBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
};

const taskTitleStyle: React.CSSProperties = {
  fontSize: 12,
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  color: 'var(--ws-text-1)',
};

const taskSeqStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
};

const taskPrStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--ws-text-3)',
  fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  flexShrink: 0,
  textAlign: 'right',
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

const memoryBulletBlockerStyle: React.CSSProperties = {
  ...memoryBulletStyle,
  // Coral/warm-red so Blockers visibly read as friction the user
  // should notice, while Decisions stay in neutral prose.
  color: '#dc2626',
};

function bulletStyleFor(heading: string): React.CSSProperties {
  return heading === 'Blockers' ? memoryBulletBlockerStyle : memoryBulletStyle;
}

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

const assignReviewBtnStyle: React.CSSProperties = {
  background: '#fff',
  color: 'var(--ws-accent-deep, #5b21b6)',
  border: '1px solid var(--ws-accent-soft, rgba(124,58,237,0.32))',
};

export default WorkspaceHomePage;
