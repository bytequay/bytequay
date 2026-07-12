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

/** Spine hues — one per lifecycle stage / node tier. Drives the `--n`
 *  custom property each node colours its mark + label from. */
export type SpineColor = 'purple' | 'blue' | 'amber' | 'teal' | 'orange' | 'gray' | 'green';

/**
 * Layer-1 spine primitive: the vertical timeline rail. A line runs down the
 * left and every {@link SpineNode} / conversation unit hangs its mark on it.
 * Shared by the brain feed, the trunk feed, and any surface that wants a
 * "where is this conversation right now?" spine — there is exactly one spine
 * component (no per-surface copies). The `trunk` variant applies the Trunk
 * Thread mockup's quieter styling (neutral rail, unlabelled nodes, quiet
 * work rows) without touching the brain feed.
 */
export function Spine({ children, variant }: { children: ReactNode; variant?: 'trunk' }) {
  return <div className={variant === undefined ? 'spine' : `spine spine--${variant}`}>{children}</div>;
}

/**
 * Layer-1 spine primitive: a break in the trunk — a dashed rule marking
 * "what follows is not part of the fold above it". Used between the last
 * folded task and the trailing conversation that hasn't been cut into a
 * task yet, so the live conversation doesn't read as nested under the
 * task card sitting right above it.
 */
export function SpineBreak() {
  return (
    <div className="sp-break" aria-hidden>
      <span className="sp-break__line" />
      <span className="sp-break__lbl">new since last task</span>
      <span className="sp-break__line" />
    </div>
  );
}

/**
 * Layer-1 spine primitive: a labelled boundary node — the largest tier.
 * A colour-coded mark sits on the rail; the label pill carries a name, an
 * optional status, and optional meta (duration / cost / outcome). Used for
 * stage boundaries (brain feed) and milestone kickers (trunk feed). When
 * `onToggle` is given the whole node is a button (closed-stage folding).
 */
export function SpineNode({
  mark, color = 'purple', name, state, meta, right, collapsed, onToggle, onOpen, flash, id,
}: {
  mark: ReactNode;
  color?: SpineColor;
  name: ReactNode;
  state?: ReactNode;
  meta?: ReactNode;
  /** Trailing slot (e.g. a "plan amended" tick or a chevron). */
  right?: ReactNode;
  /** Folding affordance: when defined, the label (or a split-out chevron when
   *  `onOpen` also applies) toggles the node's collapsed chatter. */
  collapsed?: boolean;
  onToggle?: () => void;
  /** Navigation affordance: when defined, the label becomes a link that opens
   *  this node's stage, carrying a trailing "open" chevron. */
  onOpen?: () => void;
  /** Adds a one-shot highlight class (outline-strip jump target). */
  flash?: boolean;
  id?: string;
}) {
  // The label prioritises navigation: when the node can be opened, clicking it
  // jumps to the stage and folding (if any) splits out into its own control.
  const navigable = onOpen !== undefined;
  const labelInner = (
    <>
      <span className="sp-node__nm">{name}</span>
      {state !== undefined && <span className="sp-node__st">{state}</span>}
      {meta !== undefined && <span className="sp-node__meta">{meta}</span>}
      {navigable
        ? <span className="sp-node__chev" aria-hidden>›</span>
        : onToggle !== undefined && (
          <span className="sp-node__chev" aria-hidden>{collapsed === true ? '▸' : '▾'}</span>
        )}
      {right}
    </>
  );
  return (
    <div className={`sp-node sp-node--${color}${flash === true ? ' sp-flash' : ''}`} id={id}>
      <span className="sp-node__mark" aria-hidden>{mark}</span>
      {navigable
        ? <button type="button" className="sp-node__label" onClick={onOpen} title="Open this stage">{labelInner}</button>
        : onToggle !== undefined
          ? (
            <button type="button" className="sp-node__label" onClick={onToggle} aria-expanded={collapsed !== true}>
              {labelInner}
            </button>
          )
          : <span className="sp-node__label">{labelInner}</span>}
    </div>
  );
}

/**
 * Layer-1 spine primitive: a card hung on the spine via a coloured mark,
 * the base for gate cards (approval / ask-question) and milestone cards.
 * Renders the mark + an arbitrary card body; the mark tier is `medium` by
 * default (between a boundary node and a chatter dot).
 */
export function NodeCard({ mark, color = 'gray', children, id, flash }: {
  mark: ReactNode;
  color?: SpineColor;
  children: ReactNode;
  id?: string;
  flash?: boolean;
}) {
  return (
    <div className={`sp-nodecard sp-node--${color}${flash === true ? ' sp-flash' : ''}`} id={id}>
      <span className="sp-nodecard__mark" aria-hidden>{mark}</span>
      <div className="sp-nodecard__body">{children}</div>
    </div>
  );
}
