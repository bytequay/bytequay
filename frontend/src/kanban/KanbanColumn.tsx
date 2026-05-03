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
import type { PullRequestDto } from '../types';
import KanbanCard from './KanbanCard';

/** Cards visible before the user clicks "+ N more". One screen's worth. */
const INITIAL_SHOWN = 5;
/** Each "+ N more" click loads roughly another screen's worth. */
const LOAD_STEP = 5;

export type KanbanColumnKind =
  // Active columns — render as inbox-style cards.
  | 'drafting'
  | 'waiting_on_review'
  | 'needs_changes'
  | 'ready_to_merge'
  | 'needs_attention'
  | 'in_progress'
  | 'awaiting_author'
  // Closed/done columns — render as handled-style cards. The "handled"
  // bucket on the team kanban collects PRs the user explicitly
  // dismissed via mark-handled.
  | 'recently_merged'
  | 'cleared_today'
  | 'handled';

const DONE_KINDS = new Set<KanbanColumnKind>(['recently_merged', 'cleared_today', 'handled']);

type Props = {
  kind: KanbanColumnKind;
  label: string;
  prs: PullRequestDto[];
  selectedId: number | null;
  /** Whether this column is shown as a 38px vertical strip. */
  collapsed: boolean;
  /** Optional "your move" hint shown next to the column title — mockup
   *  uses this to draw the eye to action-required columns. */
  yourMove?: 'go' | 'caution';
  onToggle: () => void;
  onSelect: (pr: PullRequestDto) => void;
  onHandle: (prId: number) => void;
  onReopen: (prId: number) => void;
  /** "team" tells the card to show repo avatar + author chip. */
  cardMode?: 'inbox' | 'team';
  /** When provided, the column is in server-paginated mode: prs is just
   *  the loaded slice, totalCount is the column's full size on the
   *  backend, and "+ N more" calls onLoadMore to fetch the next page.
   *  When absent, the column falls back to the legacy slice-from-loaded
   *  behaviour used by the inbox kanban. */
  totalCount?: number;
  onLoadMore?: () => Promise<void> | void;
};

function KanbanColumn({
  kind, label, prs, selectedId, collapsed, yourMove,
  onToggle, onSelect, onHandle, onReopen, cardMode,
  totalCount, onLoadMore,
}: Props) {
  const [shownCount, setShownCount] = useState(INITIAL_SHOWN);
  const [loadingMore, setLoadingMore] = useState(false);
  const slug = kind.replace(/_/g, '-');
  // Done flag was the old delegator's "render as HandledCard" switch — the
  // rich KanbanPrCard handles all column kinds uniformly now.
  void DONE_KINDS;
  const serverPaginated = onLoadMore !== undefined;

  // Total = column's true size. For inbox-mode (slice-from-loaded), prs
  // is everything we have; total === prs.length. For team-mode (server-
  // paginated), the backend tells us how many exist; we may have loaded
  // fewer.
  const total = totalCount ?? prs.length;

  if (collapsed) {
    return (
      <button
        type="button"
        className={`kanban-col kanban-col--${slug} kanban-col--collapsed`}
        onClick={onToggle}
        title={`Expand ${label}`}
      >
        <span className="kanban-col__count-vertical">{total}</span>
        <span className="kanban-col__name-vertical">{label}</span>
        <span className="kanban-col__dot" aria-hidden="true" />
      </button>
    );
  }

  // Server-paginated mode: render whatever the backend sent — that IS
  // the loaded slice — and "+ N more" calls onLoadMore to fetch the
  // next page. Inbox-mode: slice the in-memory array; "+ N more"
  // reveals more from already-loaded data.
  const visiblePrs = serverPaginated ? prs : prs.slice(0, shownCount);
  const hiddenCount = serverPaginated ? Math.max(0, total - prs.length) : prs.length - visiblePrs.length;
  const nextStep = Math.min(LOAD_STEP, hiddenCount);

  const handleLoadMore = async () => {
    if (serverPaginated) {
      setLoadingMore(true);
      try {
        await onLoadMore!();
      } finally {
        setLoadingMore(false);
      }
    } else {
      setShownCount(c => c + LOAD_STEP);
    }
  };

  return (
    <section className={`kanban-col kanban-col--${slug}${yourMove ? ` kanban-col--move-${yourMove}` : ''}`}>
      <header className="kanban-col__header">
        <span className="kanban-col__dot" aria-hidden="true" />
        <h3 className="kanban-col__title">{label}</h3>
        <span className="kanban-col__count">{total}</span>
        {yourMove && (
          <span className={`kanban-col__hint kanban-col__hint--${yourMove}`}>
            {yourMove === 'go' ? 'your move' : 'urgent'}
          </span>
        )}
        <button
          type="button"
          className="kanban-col__collapse-btn"
          onClick={onToggle}
          title="Collapse column"
          aria-label="Collapse column"
        >
          ◀
        </button>
      </header>
      <div className="kanban-col__body">
        {prs.length === 0 ? (
          <div className="kanban-col__empty">—</div>
        ) : (
          <>
            {visiblePrs.map(pr => (
              <KanbanCard
                key={pr.id}
                pr={pr}
                column={kind}
                mode={cardMode}
                selected={selectedId === pr.id}
                onSelect={() => onSelect(pr)}
                onHandle={() => onHandle(pr.id)}
                onReopen={() => onReopen(pr.id)}
              />
            ))}
            {hiddenCount > 0 && (
              <button
                type="button"
                className="kanban-col__more"
                onClick={() => void handleLoadMore()}
                disabled={loadingMore}
                title={`${hiddenCount} more in this column`}
              >
                {loadingMore ? 'Loading…' : `+ ${nextStep} more`}
                {hiddenCount > nextStep && !loadingMore && (
                  <span className="kanban-col__more-rest"> ({hiddenCount} left)</span>
                )}
              </button>
            )}
          </>
        )}
      </div>
    </section>
  );
}

export default KanbanColumn;
