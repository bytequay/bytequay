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
import { Fragment, useCallback, useMemo, useRef, useState } from 'react';
import type { BrainFeedRow, StageType, TaskBrainViewData } from '../../types/brainView';
import { EventRow } from './EventRow';
import { ConversationScrubber } from './ConversationScrubber';
import { Composer } from './Composer';
import { relativeLong } from './format';

type TimeRange = 'all' | 'hour' | 'day';

const STAGE_TYPE_OPTIONS: { value: StageType | 'all'; label: string }[] = [
  { value: 'all', label: 'All stages' },
  { value: 'DEVELOPMENT_STAGE', label: 'Development' },
  { value: 'CI_FIXING_STAGE', label: 'CI fixing' },
  { value: 'REVIEW_MONITOR_STAGE', label: 'Review monitor' },
  { value: 'CLEANUP_STAGE', label: 'Cleanup' },
  { value: 'REVIEW_STAGE', label: 'Review panel' },
];

const EVENT_TYPE_OPTIONS: { value: BrainFeedRow['type'] | 'all'; label: string }[] = [
  { value: 'all', label: 'All events' },
  { value: 'USER_MESSAGE', label: 'You' },
  { value: 'BRAIN_AGENT_RESPONSE', label: 'Brain agent' },
  { value: 'ITERATION_SUMMARY', label: 'Iteration summary' },
  { value: 'STAGE_OPENED', label: 'Stage opened' },
  { value: 'STAGE_CLOSED', label: 'Stage closed' },
  { value: 'PANEL_REVIEW_COMPLETED', label: 'Panel review' },
  { value: 'NEEDS_ATTENTION', label: 'Needs attention' },
];

/** Frontend-only filter over the already-fetched feed: free text + stage
 *  type + event type + time range. Server-side filtering can come later if
 *  very long-lived Tasks make this slow. */
function filterFeed(
  feed: BrainFeedRow[],
  query: string,
  stageType: StageType | 'all',
  eventType: BrainFeedRow['type'] | 'all',
  range: TimeRange,
  nowMs: number,
): BrainFeedRow[] {
  const q = query.trim().toLowerCase();
  const cutoff = range === 'hour' ? nowMs - 3_600_000
    : range === 'day' ? nowMs - 86_400_000 : null;
  return feed.filter(row => {
    if (q.length > 0 && !row.body.toLowerCase().includes(q)) return false;
    if (stageType !== 'all' && row.stageType !== stageType) return false;
    if (eventType !== 'all' && row.type !== eventType) return false;
    if (cutoff !== null && new Date(row.ts).getTime() < cutoff) return false;
    return true;
  });
}

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

  const [query, setQuery] = useState('');
  const [stageType, setStageType] = useState<StageType | 'all'>('all');
  const [eventType, setEventType] = useState<BrainFeedRow['type'] | 'all'>('all');
  const [range, setRange] = useState<TimeRange>('all');
  const filtered = useMemo(
    () => filterFeed(feed, query, stageType, eventType, range, nowMs),
    [feed, query, stageType, eventType, range, nowMs]);
  const filterActive = query.trim().length > 0 || stageType !== 'all'
    || eventType !== 'all' || range !== 'all';

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

  const lastByStage = lastRowIdByStage(filtered);

  return (
    <main className="brain">
      <ConversationScrubber position="left" dashes={scrubbers.stageEvents} onJumpTo={onJumpTo} />
      <ConversationScrubber position="right" dashes={scrubbers.userMessages} onJumpTo={onJumpTo} />

      <div className="brain-filter" role="search">
        <input
          className="brain-filter__q"
          type="search"
          placeholder="Search the feed…"
          aria-label="Search the brain feed"
          value={query}
          onChange={e => setQuery(e.target.value)}
        />
        <select aria-label="Filter by stage" value={stageType} onChange={e => setStageType(e.target.value as StageType | 'all')}>
          {STAGE_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select aria-label="Filter by event type" value={eventType} onChange={e => setEventType(e.target.value as BrainFeedRow['type'] | 'all')}>
          {EVENT_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select aria-label="Filter by time" value={range} onChange={e => setRange(e.target.value as TimeRange)}>
          <option value="all">Any time</option>
          <option value="hour">Last hour</option>
          <option value="day">Last day</option>
        </select>
      </div>

      <div className="scroll" ref={scrollRef}>
        {filterActive && filtered.length === 0 && (
          <div className="brain-filter__empty">No feed entries match the filter.</div>
        )}
        {filtered.map((row, i) => {
          const prev = filtered[i - 1];
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
