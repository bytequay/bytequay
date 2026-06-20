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
import { Fragment, useCallback, useRef, useState } from 'react';
import type { BrainFeedRow, TaskBrainViewData } from '../../types/brainView';
import { EventRow } from './EventRow';
import { ConversationScrubber } from './ConversationScrubber';
import { Composer } from './Composer';
import { relativeLong } from './format';

type Props = {
  feed: BrainFeedRow[];
  scrubbers: TaskBrainViewData['scrubbers'];
  /** stageId → display label, resolved across main + sub stages. */
  stageLabels: Map<string, string>;
  /** Stage ids currently ACTIVE — used to flag a row as live. */
  activeStageIds: Set<string>;
  nowMs: number;
  onOpenStage: (stageId: string) => void;
  onSubmitMessage: (text: string) => void;
};

/** Gap (ms) between consecutive rows above which a time divider is drawn. */
const DIVIDER_GAP_MS = 120_000;

/** Last feed-row id per stage — the row that should carry the live
 *  indicator when its stage is still running. */
function lastRowIdByStage(feed: BrainFeedRow[]): Map<string, string> {
  const last = new Map<string, string>();
  for (const row of feed) {
    if (row.stageId !== null) last.set(row.stageId, row.id);
  }
  return last;
}

export function BrainFeedColumn({
  feed, scrubbers, stageLabels, activeStageIds, nowMs, onOpenStage, onSubmitMessage,
}: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [pulsingId, setPulsingId] = useState<string | null>(null);
  const pulseTimer = useRef<number | undefined>(undefined);

  const onJumpTo = useCallback((rowId: string) => {
    const el = scrollRef.current?.querySelector<HTMLElement>(`[data-row-id="${rowId}"]`);
    // Guard: jsdom (test env) doesn't implement scrollIntoView.
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    setPulsingId(rowId);
    window.clearTimeout(pulseTimer.current);
    pulseTimer.current = window.setTimeout(() => setPulsingId(null), 1500);
  }, []);

  const lastByStage = lastRowIdByStage(feed);

  return (
    <main className="brain">
      <ConversationScrubber position="left" dashes={scrubbers.stageEvents} onJumpTo={onJumpTo} />
      <ConversationScrubber position="right" dashes={scrubbers.userMessages} onJumpTo={onJumpTo} />

      <div className="scroll" ref={scrollRef}>
        {feed.map((row, i) => {
          const prev = feed[i - 1];
          const showDivider = i === 0
            || new Date(row.ts).getTime() - new Date(prev.ts).getTime() >= DIVIDER_GAP_MS;
          const live = row.type === 'ITERATION_SUMMARY'
            && row.stageId !== null
            && activeStageIds.has(row.stageId)
            && lastByStage.get(row.stageId) === row.id;
          return (
            <Fragment key={row.id}>
              {showDivider && (
                <div className="tdiv">
                  <span className="ln" />
                  <span>{relativeLong(row.ts, nowMs)}</span>
                  <span className="ln" />
                </div>
              )}
              <EventRow
                row={row}
                stageLabel={row.stageId !== null ? stageLabels.get(row.stageId) ?? null : null}
                nowMs={nowMs}
                live={live}
                pulsing={pulsingId === row.id}
                onOpenStage={onOpenStage}
              />
            </Fragment>
          );
        })}
      </div>

      <Composer onSubmit={onSubmitMessage} />
    </main>
  );
}
