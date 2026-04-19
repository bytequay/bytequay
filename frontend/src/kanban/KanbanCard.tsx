import type { PullRequestDto } from '../types';
import KanbanPrCard from './KanbanPrCard';
import type { KanbanColumnKind } from './KanbanColumn';

type Props = {
  pr: PullRequestDto;
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
};

/**
 * Thin wrapper around KanbanPrCard. Kept as its own file so callers that
 * import './KanbanCard' don't have to change, and so we have a place to
 * eventually layer back in column-specific actions (Mark handled, Reopen)
 * without bloating the rich-card component.
 */
function KanbanCard({ pr, column, mode, selected, onSelect, onHandle, onReopen }: Props) {
  return (
    <KanbanPrCard
      pr={pr}
      column={column}
      mode={mode}
      selected={selected}
      onSelect={onSelect}
      onHandle={onHandle}
      onReopen={onReopen}
    />
  );
}

export default KanbanCard;
