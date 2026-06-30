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
export type TaskStatus = 'foreground' | 'shipped' | 'pending' | 'paused' | 'errored' | 'closed';

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
  linkedTaskLabel?: string;
  onOpenLinked?: () => void;
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
    if (onClick !== undefined && (e.key === 'Enter' || e.key === ' ')) {
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
      {body !== undefined && <div className="body">{body}</div>}
      <div className="meta-row">
        {kind === 'task' ? <TaskMeta {...props} /> : <BacklogMeta {...props} />}
      </div>
    </div>
  );
}

function TaskMeta({ branch, createdLabel, status, statusText, mergeReady }: TaskProps) {
  return (
    <>
      {branch !== undefined && (
        <span className="branch-tag"><span className="ic" aria-hidden>⎇</span>{branch}</span>
      )}
      {createdLabel !== undefined && <span className="created">{createdLabel}</span>}
      {mergeReady === true && <span className="merge-ready-pill">Ready to merge</span>}
      {status !== undefined && (
        <span className={`status-pill ${status}`}>
          {statusText ?? status.toUpperCase()}
          {status === 'foreground' && <span className="arrow" aria-hidden>→</span>}
        </span>
      )}
    </>
  );
}

function BacklogMeta({ tags, createdLabel, onStartDevelopment, started, linkedTaskLabel, onOpenLinked }: BacklogProps) {
  const stop = (fn?: () => void) => (e: { stopPropagation: () => void }) => { e.stopPropagation(); fn?.(); };
  return (
    <>
      {tags?.map((t, i) => <Tag key={`${t.label}-${i}`} color={t.color}>{t.label}</Tag>)}
      {createdLabel !== undefined && <span className="created">{createdLabel}</span>}
      {started === true && linkedTaskLabel !== undefined && (
        <span className="linked" role="link" tabIndex={0} onClick={stop(onOpenLinked)}>{linkedTaskLabel}</span>
      )}
      {started === true
        ? <span className="start-dev-btn started">Started <span className="arrow" aria-hidden>→</span></span>
        : (
          <button type="button" className="start-dev-btn" onClick={stop(onStartDevelopment)}>
            Start development <span className="arrow" aria-hidden>→</span>
          </button>
        )}
    </>
  );
}
