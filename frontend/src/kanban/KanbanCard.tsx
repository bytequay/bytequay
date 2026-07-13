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
import type { PrLikeWithId } from '../prBuckets';
import KanbanPrCard from './KanbanPrCard';
import type { KanbanColumnKind } from './KanbanColumn';

type Props<T extends PrLikeWithId> = {
  pr: T;
  column: KanbanColumnKind;
  /** "team" surfaces repo avatar + author chip; "inbox" (default) hides
   *  them since the user is implicitly the author of every visible card. */
  mode?: 'inbox' | 'team';
  selected: boolean;
  onSelect: () => void;
  // Action callbacks kept on the prop signature for parity with callers
  // (PullRequestList, TeamDetailPage) — currently unused since the rich
  // card-action buttons (Ping reviewers / Merge / Address feedback) need
  // separate backend wiring still to come.
  onHandle: () => void;
  onReopen: () => void;
  onSnooze?: (untilIso: string) => void;
  onAgentReview?: () => void;
  draggable?: boolean;
};

/**
 * Thin wrapper around KanbanPrCard. Kept as its own file so callers that
 * import './KanbanCard' don't have to change, and so we have a place to
 * eventually layer back in column-specific actions (Mark handled, Reopen)
 * without bloating the rich-card component.
 */
function KanbanCard<T extends PrLikeWithId>({ pr, column, mode, selected, onSelect, onHandle, onReopen, onSnooze, onAgentReview, draggable }: Props<T>) {
  return (
    <KanbanPrCard
      pr={pr}
      column={column}
      mode={mode}
      selected={selected}
      onSelect={onSelect}
      onHandle={onHandle}
      onReopen={onReopen}
      onSnooze={onSnooze}
      onAgentReview={onAgentReview}
      reviewState={pr.reviewState}
      draggable={draggable}
    />
  );
}

export default KanbanCard;
