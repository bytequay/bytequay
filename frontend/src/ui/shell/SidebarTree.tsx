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
import type { ReactNode } from 'react';
import { StatusDot } from '../primitives';
import type { StatusDotVariant } from '../primitives';

/* The threads tree: thread → tasks → stages. Three nesting levels, each
 * a row that can expand to reveal its children. These are presentational
 * — the host owns expand/selection state and passes it down. */

/**
 * A thread row at the top of the tree. When `expandable`, a chevron
 * toggles; the expanded thread reveals its task children. A non-expandable
 * thread shows the branch glyph and just opens on click.
 */
export function ThreadItem({ label, active = false, expandable = false, expanded = false, onToggle, onOpen, children }: {
  label: string;
  active?: boolean;
  expandable?: boolean;
  expanded?: boolean;
  onToggle?: () => void;
  onOpen?: () => void;
  children?: ReactNode;
}) {
  const icon = expandable ? (expanded ? '▾' : '▸') : '⎇';
  return (
    <>
      <button
        type="button"
        className={active ? 'session-item active' : 'session-item'}
        onClick={() => { (expandable ? onToggle : onOpen)?.(); }}
      >
        <span className="ic" aria-hidden>{icon}</span>
        <span className="label">{label}</span>
      </button>
      {expandable && expanded && children !== undefined && (
        <div className="session-children">{children}</div>
      )}
    </>
  );
}

/**
 * A task row nested under a thread. Expands to reveal its stage children;
 * when collapsed it shows a status dot for the task's current state.
 */
export function TaskItem({ label, expanded = false, status, onToggle, onOpen, children }: {
  label: string;
  expanded?: boolean;
  status?: StatusDotVariant;
  onToggle?: () => void;
  onOpen?: () => void;
  children?: ReactNode;
}) {
  return (
    <>
      <button
        type="button"
        className={expanded ? 'session-item nested current' : 'session-item nested'}
        onClick={() => { (children !== undefined ? onToggle : onOpen)?.(); }}
      >
        <span className="ic" aria-hidden>{expanded ? '▾' : '▸'}</span>
        <span className="label">{label}</span>
        {!expanded && status !== undefined && <StatusDot variant={status} />}
      </button>
      {expanded && children !== undefined && (
        <div className="session-children" style={{ paddingLeft: 22 }}>{children}</div>
      )}
    </>
  );
}

/**
 * A stage row nested under a task. Always one of the six lifecycle
 * stages; the trailing status dot reflects its state, and `future`
 * dims a stage that hasn't been instantiated yet.
 */
export function StageItem({ label, icon, status, future = false, current = false, onOpen }: {
  label: string;
  icon: ReactNode;
  status: StatusDotVariant;
  future?: boolean;
  current?: boolean;
  onOpen?: () => void;
}) {
  const classes = ['session-item', 'nested'];
  if (current) classes.push('current');
  if (future) classes.push('future');
  return (
    <button type="button" className={classes.join(' ')} onClick={onOpen}>
      <span className="ic" aria-hidden>{icon}</span>
      <span className="label">{label}</span>
      <StatusDot variant={status} />
    </button>
  );
}
