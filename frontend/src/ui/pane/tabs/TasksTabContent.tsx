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
// ponytail: queue removed; Queued folder + its controlled-open machinery gone.
import type { TaskStatus } from '../../conv';
import type { PrGlyphState } from '../../primitives';

/** Task data for a card in the Tasks tab. */
export type TaskCardData = {
  id: string;
  title: string;
  body?: string;
  status: TaskStatus;
  statusText?: string;
  branch?: string;
  createdLabel?: string;
  prNumber?: number;
  /** PR is ready to merge — tints the card + drives the "Ready to merge" tab. */
  mergeReady?: boolean;
  /** PR-state glyph before the title (merged / open / draft), or omitted
   *  while the task has no PR. */
  pr?: PrGlyphState;
};

/**
 * The Tasks tab (trunk only). Active + paused tasks render as cards at
 * the top with no folder; terminal tasks sit in a collapsible "Closed"
 * folder below. SHIPPED tasks are not shown here — they stay in the
 * conversation history. Renders the same {@link Card} used inline in the
 * conversation.
 */
export function TasksTabContent({ active, closed = [], onOpenTask }: {
  active: TaskCardData[];
  /** Terminal tasks (merged / canceled), shown in a collapsed "Closed"
   *  folder below. Omit to hide the folder. */
  closed?: TaskCardData[];
  onOpenTask?: (id: string) => void;
}) {
  const [closedOpen, setClosedOpen] = useState(false);

  return (
    <>
      {active.map(t => (
        <Card
          key={t.id}
          kind="task"
          title={t.title}
          body={t.body}
          status={t.status}
          statusText={t.statusText}
          branch={t.branch}
          createdLabel={t.createdLabel}
          prNumber={t.prNumber}
          mergeReady={t.mergeReady}
          pr={t.pr}
          onClick={onOpenTask !== undefined ? () => onOpenTask(t.id) : undefined}
        />
      ))}

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
              statusText={t.statusText}
              branch={t.branch}
              createdLabel={t.createdLabel}
              prNumber={t.prNumber}
              pr={t.pr}
              onClick={onOpenTask !== undefined ? () => onOpenTask(t.id) : undefined}
            />
          ))}
        </div>
      )}
    </>
  );
}
