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
import { MarkdownProse } from '../../threads/MarkdownProse';

/** Who produced the event — drives the icon colour + name colour. */
export type EventKind = 'agent' | 'user' | 'system' | 'brain' | 'followup';

const DEFAULT_GLYPH: Record<EventKind, string> = {
  agent: 'C',
  user: 'Y',
  system: '●',
  brain: 'B',
  followup: '⚠',
};

/** The colour-coded square icon at the start of a row. */
export function EventIcon({ kind, glyph }: { kind: EventKind; glyph?: ReactNode }) {
  return <span className={`ic ${kind}`} aria-hidden>{glyph ?? DEFAULT_GLYPH[kind]}</span>;
}

/** The metadata row: name + optional task ref + timestamp + collapse chev. */
export function WhoRow({ kind, who, taskRef, timestamp, collapsible = false, collapsed = false, onToggle }: {
  kind: EventKind;
  who: ReactNode;
  taskRef?: ReactNode;
  timestamp?: ReactNode;
  collapsible?: boolean;
  collapsed?: boolean;
  onToggle?: () => void;
  glyph?: ReactNode;
}) {
  const whoClass = kind === 'agent' || kind === 'followup' ? 'who' : `who ${kind}`;
  return (
    <div className="who-row">
      <EventIcon kind={kind} />
      <span className={whoClass}>{who}</span>
      {taskRef !== undefined && <span className="ref">{taskRef}</span>}
      {timestamp !== undefined && <span className="ref">{timestamp}</span>}
      {collapsible && (
        <button type="button" className="chev" aria-label={collapsed ? 'Expand' : 'Collapse'} onClick={onToggle}>
          {collapsed ? '▸' : '▾'}
        </button>
      )}
    </div>
  );
}

/** Markdown (or arbitrary) body of a row. Pass `markdown` to render
 *  GitHub-flavored prose through the shared {@link MarkdownProse}
 *  renderer; otherwise children are rendered as-is. */
export function Tx({ markdown, children }: { markdown?: string; children?: ReactNode }) {
  return (
    <div className="tx">
      {markdown !== undefined ? <MarkdownProse text={markdown} /> : children}
    </div>
  );
}

type EventRowProps = {
  kind: EventKind;
  who: ReactNode;
  taskRef?: ReactNode;
  timestamp?: ReactNode;
  collapsible?: boolean;
  collapsed?: boolean;
  onToggle?: () => void;
  /** Markdown body shorthand — rendered through {@link Tx}. */
  markdown?: string;
  /** Custom body content (tool blocks, callouts, …). Hidden when collapsed. */
  children?: ReactNode;
};

/**
 * One conversation event in the Copilot-style feed: a colour-coded icon
 * + a who-row + a body. The body collapses when `collapsed`, leaving the
 * who-row as the boundary marker. Composes {@link EventIcon},
 * {@link WhoRow}, and {@link Tx}.
 */
export function EventRow({
  kind, who, taskRef, timestamp, collapsible = false, collapsed = false, onToggle, markdown, children,
}: EventRowProps) {
  return (
    <div className="ev">
      <WhoRow
        kind={kind}
        who={who}
        taskRef={taskRef}
        timestamp={timestamp}
        collapsible={collapsible}
        collapsed={collapsed}
        onToggle={onToggle}
      />
      {!collapsed && markdown !== undefined && <Tx markdown={markdown} />}
      {!collapsed && children}
    </div>
  );
}

EventRow.Icon = EventIcon;
EventRow.WhoRow = WhoRow;
EventRow.Tx = Tx;
