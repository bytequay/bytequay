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
import { useMemo, useState } from 'react';
import type { ThreadDto } from '../types';
import type { StatusFilter } from './ThreadsLeftRail';

type Props = {
  /** Threads narrowed to whatever the active filter is. */
  threads: ThreadDto[];
  /** Active filter chip. The card title and the per-row status chip
   *  are both filter-derived; the banner + the {@code auto*} chip
   *  next to the title appear only for {@code AUTO}. */
  filter: StatusFilter;
  /** Open this thread's detail. The auto-row jump-in action also
   *  fires this after the lease transfer completes. */
  onOpenThread: (threadId: string) => void;
  /** Optional — opens the Settings → Automation page from the
   *  auto-queue banner link. Disabled when the page doesn't exist
   *  yet, which is the current state. */
  onOpenAutomationSettings?: () => void;
};

type RowStatus = 'RUNNING' | 'AWAITING' | 'AWAITING_REVIEW' | 'NEEDS_ATTENTION' | 'IDLE' | 'COMPLETED' | 'ERRORED';

/**
 * Universal thread-list card per the workspace's threads-auto-filter
 * design — one purple-tinted banner (auto* only), a white card with
 * the section title + count, and rows shaped as dot + title + chips
 * + description + big right-side status pill + action with timestamp.
 *
 * <p>Same layout for every filter chip; the differences are surface
 * level: title ("All threads" / "Mine" / "Automation threads" / …),
 * the inline {@code auto*} chip next to the title (auto* only), and
 * the row's action + classification logic. AUTO is the most active
 * surface — parked rows expose Jump-in / Review-diff — while the
 * other filters render a simple "Open" affordance per row.
 */
function AutoQueueList({ threads, filter, onOpenThread, onOpenAutomationSettings }: Props) {
  const isAuto = filter === 'AUTO';

  const rows = useMemo(() => {
    const decorated = threads.map(t => ({ thread: t, status: classify(t, isAuto) }));
    decorated.sort((a, b) => statusRank(a.status) - statusRank(b.status));
    return decorated;
  }, [threads, isAuto]);

  const tally = useMemo(() => tallyByStatus(rows), [rows]);

  return (
    <section className="auto-queue" aria-label={titleFor(filter)}>
      {isAuto && (
        <header className="auto-queue__banner">
          <span className="auto-queue__banner-icon" aria-hidden>📋</span>
          <p className="auto-queue__banner-text">
            <strong>Automation queue</strong> — system-initiated work on your PRs.{' '}
            {bannerTally(tally)}. Click any to jump in.
          </p>
          <button
            type="button"
            className="auto-queue__banner-link"
            onClick={onOpenAutomationSettings}
            disabled={!onOpenAutomationSettings}
          >
            Automation settings →
          </button>
        </header>
      )}

      <div className="auto-queue__card">
        <header className="auto-queue__card-head">
          <span className="auto-queue__card-title">{titleFor(filter)}</span>
          {isAuto && <span className="auto-queue__card-chip">auto*</span>}
          <span className="auto-queue__card-sep">·</span>
          <span className="auto-queue__card-count">{rows.length}</span>
        </header>

        {rows.length === 0 ? (
          <p className="auto-queue__empty">{emptyCopyFor(filter)}</p>
        ) : (
          <ul className="auto-queue__list">
            {rows.map(({ thread, status }) => (
              <ThreadRow
                key={thread.id}
                thread={thread}
                status={status}
                isAuto={isAuto}
                onOpenThread={onOpenThread}
              />
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function ThreadRow({
  thread, status, isAuto, onOpenThread,
}: {
  thread: ThreadDto;
  status: RowStatus;
  isAuto: boolean;
  onOpenThread: (threadId: string) => void;
}) {
  const [jumping, setJumping] = useState(false);
  const isParked = status === 'AWAITING_REVIEW' || status === 'NEEDS_ATTENTION';
  // Auto-queue rows that need the human's attention transfer the
  // worktree lease on click; everything else just opens the thread.
  const handleAction = async () => {
    if (isAuto && isParked) {
      setJumping(true);
      try { await window.bridge.jumpInThread(thread.id); }
      catch { /* non-fatal — lease can be re-acquired from inside */ }
      finally {
        setJumping(false);
        onOpenThread(thread.id);
      }
      return;
    }
    onOpenThread(thread.id);
  };

  const branch = thread.activeTask?.branchName ?? null;
  const prNumber = thread.activeTask?.prNumber ?? null;
  const description = descriptionFor(thread, status);
  const action = isAuto ? autoActionFor(status) : 'Open';
  const age = relativeTime(thread.updatedAt);
  const timestampPrefix = timestampPrefixFor(status, isAuto);
  const dotMod = chipModifier(status);

  return (
    <li className={`auto-queue__row auto-queue__row--${dotMod}`}>
      <span className={`auto-queue__dot auto-queue__dot--${dotMod}`} />
      <div className="auto-queue__body">
        <div className="auto-queue__title-row">
          <button
            type="button"
            className="auto-queue__title"
            onClick={() => onOpenThread(thread.id)}
          >
            {thread.title}
          </button>
          {isAuto && <span className="auto-queue__inline-chip">auto*</span>}
        </div>
        <div className="auto-queue__meta">
          {branch && (
            <span className="auto-queue__pill">
              <span className="auto-queue__pill-icon" aria-hidden>↗</span>
              {branch}
            </span>
          )}
          {prNumber != null && (
            <span className="auto-queue__pill auto-queue__pill--pr">
              #{prNumber}
            </span>
          )}
          {description && (
            <span className="auto-queue__desc">{description}</span>
          )}
        </div>
      </div>
      <span className={`auto-queue__status auto-queue__status--${dotMod}`}>
        {chipLabel(status, isAuto)}
      </span>
      <div className="auto-queue__action-stack">
        <button
          type="button"
          className={`auto-queue__action auto-queue__action--${dotMod}`}
          onClick={() => { void handleAction(); }}
          disabled={jumping}
        >
          {jumping ? '…' : action}
        </button>
        <span className="auto-queue__age">
          {timestampPrefix}{age}
        </span>
      </div>
    </li>
  );
}

function titleFor(filter: StatusFilter): string {
  switch (filter) {
    case 'ALL':         return 'All threads';
    case 'MINE':        return 'Mine';
    case 'REVIEW':      return 'Review';
    case 'AUTO':        return 'Automation threads';
    case 'AWAITING_ME': return 'Awaiting me';
    case 'RUNNING':     return 'Running';
    case 'AWAITING':    return 'Awaiting input';
    case 'PENDING':     return 'Pending';
    case 'IDLE':        return 'Alive';
    case 'COMPLETED':   return 'Completed';
    case 'ERRORED':     return 'Errored';
  }
}

function emptyCopyFor(filter: StatusFilter): string {
  if (filter === 'AUTO') {
    return 'The queue is empty.';
  }
  return 'No threads in this view.';
}

/** Classify a thread for the row's status pill + action. AUTO routes
 *  parked task states to the queue's NEEDS_ATTENTION / AWAITING_REVIEW
 *  surface; non-AUTO filters use the thread's actual run state so the
 *  same row template works for every chip. */
function classify(thread: ThreadDto, isAuto: boolean): RowStatus {
  const taskStatus = thread.activeTask?.status;
  if (isAuto) {
    if (taskStatus === 'NEEDS_ATTENTION') return 'NEEDS_ATTENTION';
    if (taskStatus === 'AWAITING_REVIEW') return 'AWAITING_REVIEW';
    if (thread.status === 'RUNNING') return 'RUNNING';
    return 'COMPLETED';
  }
  // Non-AUTO surfaces use the thread's own status; parked states still
  // bubble up so "Awaiting me" lists those rows with the right chip.
  if (taskStatus === 'NEEDS_ATTENTION') return 'NEEDS_ATTENTION';
  if (taskStatus === 'AWAITING_REVIEW') return 'AWAITING_REVIEW';
  switch (thread.status) {
    case 'RUNNING':   return 'RUNNING';
    case 'AWAITING':  return 'AWAITING';
    case 'IDLE':      return 'IDLE';
    case 'COMPLETED': return 'COMPLETED';
    case 'ERRORED':   return 'ERRORED';
    case 'PENDING':   return 'RUNNING';
    case 'AWAITING_REVIEW':  return 'AWAITING_REVIEW';
    case 'NEEDS_ATTENTION':  return 'NEEDS_ATTENTION';
  }
}

function statusRank(s: RowStatus): number {
  switch (s) {
    case 'NEEDS_ATTENTION':  return 0;
    case 'AWAITING_REVIEW':  return 1;
    case 'AWAITING':         return 2;
    case 'RUNNING':          return 3;
    case 'IDLE':             return 4;
    case 'ERRORED':          return 5;
    case 'COMPLETED':        return 6;
  }
}

function chipLabel(s: RowStatus, isAuto: boolean): string {
  switch (s) {
    case 'RUNNING':          return 'RUNNING';
    case 'AWAITING':         return 'AWAITING';
    case 'AWAITING_REVIEW':  return 'AWAITING REVIEW';
    case 'NEEDS_ATTENTION':  return 'NEEDS ATTENTION';
    case 'IDLE':             return 'ALIVE';
    case 'ERRORED':          return 'ERRORED';
    case 'COMPLETED':        return isAuto ? 'DONE · ARCHIVED' : 'COMPLETED';
  }
}

function chipModifier(s: RowStatus): string {
  switch (s) {
    case 'RUNNING':          return 'running';
    case 'AWAITING':         return 'await';
    case 'AWAITING_REVIEW':  return 'await';
    case 'NEEDS_ATTENTION':  return 'attention';
    case 'IDLE':             return 'idle';
    case 'ERRORED':          return 'attention';
    case 'COMPLETED':        return 'done';
  }
}

function autoActionFor(s: RowStatus): string {
  switch (s) {
    case 'NEEDS_ATTENTION':  return 'Jump in →';
    case 'AWAITING_REVIEW':  return 'Review diff →';
    case 'RUNNING':          return 'Open';
    case 'AWAITING':         return 'Open';
    case 'IDLE':             return 'Open';
    case 'ERRORED':          return 'View log';
    case 'COMPLETED':        return 'View log';
  }
}

/** Optional one-line context the row surfaces between the chips. No
 *  per-task summary field on the DTO yet, so we lean on the error
 *  message for stuck rows and leave it blank otherwise. */
function descriptionFor(thread: ThreadDto, status: RowStatus): string {
  if ((status === 'NEEDS_ATTENTION' || status === 'ERRORED' || status === 'COMPLETED')
      && thread.errorMessage) {
    return truncate(thread.errorMessage, 80);
  }
  return '';
}

function truncate(s: string, max: number): string {
  return s.length <= max ? s : `${s.slice(0, max - 1).trim()}…`;
}

function timestampPrefixFor(s: RowStatus, isAuto: boolean): string {
  if (!isAuto) {
    return '';
  }
  switch (s) {
    case 'NEEDS_ATTENTION':  return 'escalated ';
    case 'AWAITING_REVIEW':  return 'parked ';
    case 'RUNNING':          return 'headless · ';
    default:                 return '';
  }
}

function tallyByStatus(rows: Array<{ status: RowStatus }>): Record<RowStatus, number> {
  const out: Record<RowStatus, number> = {
    RUNNING: 0, AWAITING: 0, AWAITING_REVIEW: 0,
    NEEDS_ATTENTION: 0, IDLE: 0, ERRORED: 0, COMPLETED: 0,
  };
  for (const r of rows) {
    out[r.status]++;
  }
  return out;
}

function bannerTally(tally: Record<RowStatus, number>): string {
  const needs = tally.NEEDS_ATTENTION + tally.AWAITING_REVIEW;
  const parts: string[] = [];
  if (needs > 0) {
    parts.push(`${needs} need${needs === 1 ? 's' : ''} you`);
  }
  if (tally.RUNNING > 0) {
    parts.push(`${tally.RUNNING} running`);
  }
  if (tally.COMPLETED > 0) {
    parts.push(`${tally.COMPLETED} done`);
  }
  if (parts.length === 0) {
    return 'Nothing parked right now';
  }
  return parts.join(', ');
}

/** Short relative-time renderer for queue rows. Caps at days; older
 *  rows fall back to a locale date so the row stays readable. */
function relativeTime(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    return '';
  }
  const diff = Date.now() - ms;
  if (diff < 60_000) {
    return 'now';
  }
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  const days = Math.floor(hours / 24);
  if (days < 30) {
    return `${days}d ago`;
  }
  return new Date(ms).toLocaleDateString();
}

export default AutoQueueList;
