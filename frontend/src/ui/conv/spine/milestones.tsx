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
import { Card } from '../Card';
import type { TaskStatus } from '../Card';
import type { PrGlyphState } from '../../primitives';
import type { SpineColor } from './Spine';

/**
 * Layer-4 milestone base (trunk feed): a big spine node with a square,
 * gradient mark + a coloured kicker + a card body. The trunk's structure is
 * the set of outputs it produces (task cuts, backlog saves, …), so these are
 * the spine's peaks. Architecture / risk milestones compose this too once a
 * later milestone emits them structured.
 */
export function MilestoneNode({ mark, color, kicker, children, id, flash }: {
  mark: ReactNode;
  color: SpineColor;
  kicker: ReactNode;
  children: ReactNode;
  id?: string;
  flash?: boolean;
}) {
  return (
    <div className={`sp-ms sp-ms--${color}${flash === true ? ' sp-flash' : ''}`} id={id}>
      <span className="sp-ms__mark" aria-hidden>{mark}</span>
      <div className="sp-ms__kicker">{kicker}</div>
      {children}
    </div>
  );
}

/**
 * Layer-4 milestone: a task cut — the trunk's visual peak. Embeds the
 * existing `<Card kind="task">` unchanged (the same card used in the Tasks
 * tab), so there is exactly one task card.
 */
export function TaskCutNode({
  title, status, statusText, branch, createdLabel, prNumber, mergeReady, pr, body, onOpen, id, flash,
}: {
  title: string;
  status?: TaskStatus;
  statusText?: string;
  branch?: string;
  createdLabel?: string;
  prNumber?: number;
  mergeReady?: boolean;
  pr?: PrGlyphState;
  body?: string;
  onOpen?: () => void;
  id?: string;
  flash?: boolean;
}) {
  return (
    <MilestoneNode color="purple" mark="◆" kicker="Task cut" id={id} flash={flash}>
      <Card
        kind="task"
        title={title}
        status={status}
        statusText={statusText}
        branch={branch}
        createdLabel={createdLabel}
        prNumber={prNumber}
        mergeReady={mergeReady}
        pr={pr}
        body={body}
        onClick={onOpen}
      />
    </MilestoneNode>
  );
}

/** One chip in the outline strip. */
export type OutlineChip = {
  id: string;
  icon?: ReactNode;
  label: ReactNode;
  /** A status pill on a task chip ("Foreground" / "Pending"). */
  status?: string;
  /** Status pill tone. */
  statusTone?: 'fg' | 'pend';
  tone?: 'task' | 'backlog' | 'plain';
  onJump: () => void;
};

/**
 * Layer-4 control: the outline strip — one clickable chip per trunk output,
 * pinned under the top bar. Lets the user read the thread's *result* before
 * scrolling a message and jump to any milestone (the jump flashes it).
 */
export function OutlineStrip({ chips }: { chips: OutlineChip[] }) {
  if (chips.length === 0) return null;
  return (
    <div className="sp-outline">
      <span className="sp-outline__lbl">Outputs</span>
      {chips.map(c => (
        <button type="button" key={c.id} className={`sp-ochip sp-ochip--${c.tone ?? 'plain'}`} onClick={c.onJump}>
          {c.icon !== undefined && <span className="sp-ochip__di" aria-hidden>{c.icon}</span>}
          <span>{c.label}</span>
          {c.status !== undefined && <span className={`sp-ochip__st sp-ochip__st--${c.statusTone ?? 'pend'}`}>{c.status}</span>}
        </button>
      ))}
    </div>
  );
}
