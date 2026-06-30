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
import { useState } from 'react';
import { Card } from '../../conv';
import type { TaskStatus } from '../../conv';
import type { PrGlyphState } from '../../primitives';

/** Task data for a card in the Tasks tab. */
export type TaskCardData = {
  id: string;
  title: string;
  body?: string;
  status: TaskStatus;
  branch?: string;
  createdLabel?: string;
  /** PR is ready to merge — tints the card + drives the "Ready to merge" tab. */
  mergeReady?: boolean;
  /** PR-state glyph before the title (merged / open / draft), or omitted
   *  while the task has no PR. */
  pr?: PrGlyphState;
};

/**
 * The Tasks tab (trunk only). Active + paused tasks render as cards at
 * the top with no folder; PENDING tasks sit in a collapsible "Queued"
 * folder below (the same folder pattern as the sidebar's Closed folder).
 * SHIPPED tasks are not shown here — they stay in the conversation
 * history. Renders the same {@link Card} used inline in the conversation.
 */
export function TasksTabContent({ active, queued, closed = [], queuedExpanded, onToggleQueued, onOpenTask }: {
  active: TaskCardData[];
  queued: TaskCardData[];
  /** Terminal tasks (merged / canceled), shown in a collapsed "Closed"
   *  folder below the queue. Omit to hide the folder. */
  closed?: TaskCardData[];
  /** Controlled queued-folder state; self-managed (open) when omitted. */
  queuedExpanded?: boolean;
  onToggleQueued?: () => void;
  onOpenTask?: (id: string) => void;
}) {
  const [selfOpen, setSelfOpen] = useState(true);
  const [closedOpen, setClosedOpen] = useState(false);
  const isControlled = queuedExpanded !== undefined;
  const open = isControlled ? queuedExpanded : selfOpen;
  const toggle = () => { if (isControlled) onToggleQueued?.(); else setSelfOpen(o => !o); };

  return (
    <>
      {active.map(t => (
        <Card
          key={t.id}
          kind="task"
          title={t.title}
          body={t.body}
          status={t.status}
          branch={t.branch}
          createdLabel={t.createdLabel}
          mergeReady={t.mergeReady}
          pr={t.pr}
          onClick={onOpenTask !== undefined ? () => onOpenTask(t.id) : undefined}
        />
      ))}

      {queued.length > 0 && (
        <div className="closed-folder">
          <button type="button" className="folder-row" onClick={toggle} aria-expanded={open}>
            <span className="chev" aria-hidden>{open ? '▾' : '▸'}</span>
            <span className="ic" aria-hidden>📥</span>
            <span>Queued</span>
            <span className="count">{queued.length}</span>
          </button>
          {open && queued.map(t => (
            <Card
              key={t.id}
              kind="task"
              title={t.title}
              body={t.body}
              status={t.status}
              branch={t.branch}
              createdLabel={t.createdLabel}
              pr={t.pr}
              onClick={onOpenTask !== undefined ? () => onOpenTask(t.id) : undefined}
            />
          ))}
        </div>
      )}

      {closed.length > 0 && (
        <div className="closed-folder">
          <button type="button" className="folder-row" onClick={() => setClosedOpen(o => !o)} aria-expanded={closedOpen}>
            <span className="chev" aria-hidden>{closedOpen ? '▾' : '▸'}</span>
            <span className="ic" aria-hidden>✓</span>
            <span>Closed</span>
            <span className="count">{closed.length}</span>
          </button>
          {closedOpen && closed.map(t => (
            <Card
              key={t.id}
              kind="task"
              title={t.title}
              body={t.body}
              status={t.status}
              branch={t.branch}
              createdLabel={t.createdLabel}
              pr={t.pr}
              onClick={onOpenTask !== undefined ? () => onOpenTask(t.id) : undefined}
            />
          ))}
        </div>
      )}
    </>
  );
}
