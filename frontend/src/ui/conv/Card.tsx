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
import type { KeyboardEvent } from 'react';
import { PrStateIcon, Tag } from '../primitives';
import type { PrGlyphState, TagColor } from '../primitives';

/** Task queue state — drives the uppercase status pill colour. */
export type TaskStatus = 'foreground' | 'shipped' | 'pending' | 'review' | 'paused' | 'errored' | 'closed';

/** A backlog tag chip. */
export type CardTag = { label: string; color?: TagColor };

type CommonProps = {
  title: string;
  /** Short prose description (clamped to 2 lines). */
  body?: string;
  onClick?: () => void;
};

type TaskProps = CommonProps & {
  kind: 'task';
  status?: TaskStatus;
  /** Pill label override; defaults to the uppercased status. */
  statusText?: string;
  /** Monospace branch chip. */
  branch?: string;
  createdLabel?: string;
  /** The task's PR number, shown as a `#123` chip when the task has a PR. */
  prNumber?: number;
  /** The task's PR is ready to merge (CI green, no unresolved comments,
   *  mergeable) — tints the card + adds a "Ready to merge" badge. */
  mergeReady?: boolean;
  /** PR-state glyph before the title (merged / open / draft); when set it
   *  replaces the generic ◆ diamond. Omitted while the task has no PR. */
  pr?: PrGlyphState;
};

type BacklogProps = CommonProps & {
  kind: 'backlog';
  tags?: CardTag[];
  createdLabel?: string;
  /** Cuts a task seeded from this item — the bright-orange CTA. */
  onStartDevelopment?: () => void;
  /** Started items are demoted with a faded "Started →" + a link badge. */
  started?: boolean;
  /** Label for a progressed item, e.g. "In progress" or "Task cut". */
  progressLabel?: string;
  linkedTaskLabel?: string;
  onOpenLinked?: () => void;
  /** Marks the item not-to-proceed ("handled, don't work on it"). Shown as a
   *  quiet Drop button on unstarted items. */
  onDrop?: () => void;
  /** Item is not-to-proceed (Dropped). Replaces the Start/Drop buttons with a
   *  muted "Dropped" label + a Reopen action instead of a dead Start button. */
  dropped?: boolean;
  /** Restores a dropped item to created. */
  onReopen?: () => void;
};

export type CardProps = TaskProps | BacklogProps;

/**
 * The unified card used for both inline task cards and backlog items —
 * one component, two content variants (`kind`). Same chrome (white,
 * rounded, header + clamped body + meta-row). The task variant adds the
 * purple spine + diamond + status pill; the backlog variant adds tags +
 * the Start-development CTA. There is no separate card component.
 */
export function Card(props: CardProps) {
  const { kind, title, body, onClick } = props;
  const classes = ['task-card'];
  if (kind === 'backlog') classes.push('backlog');
  if (kind === 'backlog' && props.started) classes.push('started');
  if (kind === 'task' && props.mergeReady === true) classes.push('merge-ready');

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget && onClick !== undefined && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      onClick();
    }
  };

  return (
    <div
      className={classes.join(' ')}
      onClick={onClick}
      role={onClick !== undefined ? 'button' : undefined}
      tabIndex={onClick !== undefined ? 0 : undefined}
      onKeyDown={onClick !== undefined ? onKeyDown : undefined}
    >
      <div className="header">
        {kind === 'task' && (props.pr !== undefined
          ? <PrStateIcon state={props.pr} />
          : <span className="diamond" aria-hidden>◆</span>)}
        <span className="title">{title}</span>
      </div>
      {body !== undefined && body.trim() !== title.trim() && <div className="body">{body}</div>}
      <div className="meta-row">
        {kind === 'task' ? <TaskMeta {...props} /> : <BacklogMeta {...props} />}
      </div>
      {kind === 'task' && props.status !== undefined && <TaskStatusRow {...props} />}
    </div>
  );
}

function TaskMeta({ branch, createdLabel, prNumber }: TaskProps) {
  return (
    <>
      {branch !== undefined && (
        <span className="branch-tag"><span className="ic" aria-hidden>⎇</span>{branch}</span>
      )}
      {prNumber !== undefined && (
        <span className="pr-num"><span className="ic" aria-hidden>⌗</span>#{prNumber}</span>
      )}
      {createdLabel !== undefined && <span className="created">{createdLabel}</span>}
    </>
  );
}

/** Sentence-case status wording for the card's footer row. */
const STATUS_LABEL: Record<TaskStatus, string> = {
  foreground: 'In progress', shipped: 'Shipped', pending: 'Queued', review: 'Awaiting review',
  paused: 'Paused', errored: 'Errored', closed: 'Closed',
};

/** The card footer: a status dot + sentence-case status label, plus a
 *  "Foreground →" affordance on the running task (the whole card is
 *  clickable — this only labels what the click does). A merge-ready card
 *  leads its label with "Ready to merge · …". */
function TaskStatusRow({ status, statusText, mergeReady }: TaskProps) {
  if (status === undefined) return null;
  const base = statusText ?? STATUS_LABEL[status];
  const label = mergeReady === true ? `Ready to merge · ${base.toLowerCase()}` : base;
  return (
    <div className="status-row">
      <span className={`dot ${status}${mergeReady === true ? ' merge-ready' : ''}`} aria-hidden />
      <span className={`lbl ${status}${mergeReady === true ? ' merge-ready' : ''}`}>{label}</span>
      {status === 'foreground' && (
        <span className="fg-action">Foreground <span className="arrow" aria-hidden>→</span></span>
      )}
    </div>
  );
}

function BacklogMeta(
  { tags, createdLabel, onStartDevelopment, started, dropped, onReopen,
    progressLabel, linkedTaskLabel, onOpenLinked, onDrop }: BacklogProps) {
  const stop = (fn?: () => void) => (e: { stopPropagation: () => void }) => { e.stopPropagation(); fn?.(); };
  // A dropped (not-to-proceed) item can't be started — offer Reopen, not a
  // dead Start button.
  if (dropped === true) {
    return (
      <>
        {tags?.map((t, i) => <Tag key={`${t.label}-${i}`} color={t.color}>{t.label}</Tag>)}
        {createdLabel !== undefined && <span className="created">{createdLabel}</span>}
        <span className="backlog-dropped-tag">Dropped</span>
        {onReopen !== undefined && (
          <button type="button" className="backlog-drop-btn" onClick={stop(onReopen)}>Reopen</button>
        )}
      </>
    );
  }
  return (
    <>
      {tags?.map((t, i) => <Tag key={`${t.label}-${i}`} color={t.color}>{t.label}</Tag>)}
      {createdLabel !== undefined && <span className="created">{createdLabel}</span>}
      {started === true && linkedTaskLabel !== undefined && (
        <span
          className="linked"
          role={onOpenLinked === undefined ? undefined : 'link'}
          tabIndex={onOpenLinked === undefined ? undefined : 0}
          onClick={onOpenLinked === undefined ? undefined : stop(onOpenLinked)}
          onKeyDown={event => {
            if (onOpenLinked !== undefined && (event.key === 'Enter' || event.key === ' ')) {
              event.preventDefault();
              event.stopPropagation();
              onOpenLinked();
            }
          }}
        >
          {linkedTaskLabel}
        </span>
      )}
      {started !== true && onDrop !== undefined && (
        <button type="button" className="backlog-drop-btn" onClick={stop(onDrop)}>Drop</button>
      )}
      {started === true
        ? <span className="start-dev-btn started">{progressLabel ?? 'Started'} <span className="arrow" aria-hidden>→</span></span>
        : (
          <button type="button" className="start-dev-btn" onClick={stop(onStartDevelopment)}>
            Start development <span className="arrow" aria-hidden>→</span>
          </button>
        )}
    </>
  );
}
