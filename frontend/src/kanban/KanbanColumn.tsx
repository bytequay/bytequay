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
import { useRef, useState, type DragEvent } from 'react';
import type { PullRequestDto } from '../types';
import KanbanCard from './KanbanCard';
import { PR_DRAG_MIME, type PrDragPayload } from './KanbanPrCard';

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
  onSnooze?: (prId: number, untilIso: string) => void;
  /** When provided, the column's cards are HTML5-draggable. Only set
   *  by the My-PRs board. */
  draggable?: boolean;
  /** Validates whether a card from {@code fromColumn} can be dropped
   *  on this one. Drives the green/red drop-target outline. Without
   *  this prop the column doesn't accept drops at all. */
  acceptDropFrom?: (fromColumn: KanbanColumnKind) => boolean;
  /** Fires after a successful drop with the resolved payload. */
  onCardDrop?: (payload: PrDragPayload, toColumn: KanbanColumnKind) => void;
  /** "team" tells the card to show repo avatar + author chip. */
  cardMode?: 'inbox' | 'team';
  /** When provided, the column is in server-paginated mode: prs is just
   *  the loaded slice, totalCount is the column's full size on the
   *  backend, and "+ N more" calls onLoadMore to fetch the next page.
   *  When absent, the column falls back to the legacy slice-from-loaded
   *  behaviour used by the inbox kanban. */
  totalCount?: number;
  onLoadMore?: () => Promise<void> | void;
  /** Optional CTA card pinned to the bottom of the column (used by
   *  the Recently Merged column to surface the deferred merge-history
   *  page). Disabled until the destination ships. */
  footerCta?: {
    label: string;
    subtitle?: string;
    onClick?: () => void;
    disabled?: boolean;
  };
};

function KanbanColumn({
  kind, label, prs, selectedId, collapsed, yourMove,
  onToggle, onSelect, onHandle, onReopen, onSnooze,
  draggable, acceptDropFrom, onCardDrop,
  cardMode,
  totalCount, onLoadMore, footerCta,
}: Props) {
  const [shownCount, setShownCount] = useState(INITIAL_SHOWN);
  const [loadingMore, setLoadingMore] = useState(false);
  // Drop-target visual: 'accept' = green outline (drop will translate
  // to a real action), 'reject' = red outline (drop will be no-op).
  // Cleared on dragleave-from-column or after drop fires.
  const [dropState, setDropState] = useState<'accept' | 'reject' | null>(null);
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

  // ── Drop-target wiring ───────────────────────────────────────────────────
  //
  // Only attached when both onCardDrop and acceptDropFrom are provided
  // (i.e. when the parent board opted into drag/drop). dragenter/leave
  // fire spuriously when the cursor crosses child nodes, so we use a
  // depth counter to keep the visual stable while the user hovers.
  const dropDepthRef = useRef(0);
  const dropEnabled = !!onCardDrop && !!acceptDropFrom;

  const readPayload = (e: DragEvent<HTMLElement>): PrDragPayload | null => {
    const raw = e.dataTransfer.getData(PR_DRAG_MIME);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as PrDragPayload;
    } catch {
      return null;
    }
  };

  const dropHandlers = dropEnabled ? {
    onDragEnter: (e: DragEvent<HTMLElement>) => {
      // Skip non-PR drags (e.g. files dragged from Finder).
      if (!Array.from(e.dataTransfer.types).includes(PR_DRAG_MIME)) return;
      dropDepthRef.current += 1;
      if (dropDepthRef.current === 1) {
        // We can't peek at the payload during dragenter (security: only
        // available on drop), so we only know fromColumn at drop time.
        // Show "accept" optimistically and resolve on drop.
        setDropState('accept');
      }
    },
    onDragOver: (e: DragEvent<HTMLElement>) => {
      if (!Array.from(e.dataTransfer.types).includes(PR_DRAG_MIME)) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
    },
    onDragLeave: (e: DragEvent<HTMLElement>) => {
      if (!Array.from(e.dataTransfer.types).includes(PR_DRAG_MIME)) return;
      dropDepthRef.current = Math.max(0, dropDepthRef.current - 1);
      if (dropDepthRef.current === 0) setDropState(null);
    },
    onDrop: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      dropDepthRef.current = 0;
      setDropState(null);
      const payload = readPayload(e);
      if (!payload) return;
      if (payload.fromColumn === kind) return; // dropped on self — no-op
      if (!acceptDropFrom!(payload.fromColumn)) return;
      onCardDrop!(payload, kind);
    },
  } : {};

  const dropClass = dropState ? ` kanban-col--drop-${dropState}` : '';

  return (
    <section
      className={`kanban-col kanban-col--${slug}${yourMove ? ` kanban-col--move-${yourMove}` : ''}${dropClass}`}
      {...dropHandlers}
    >
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
                onSnooze={onSnooze ? (untilIso) => onSnooze(pr.id, untilIso) : undefined}
                draggable={draggable}
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
        {footerCta && (
          <button
            type="button"
            className="kanban-col__footer-cta"
            onClick={footerCta.onClick}
            disabled={footerCta.disabled}
            title={footerCta.disabled
              ? 'Coming with the merge history page'
              : footerCta.label}
          >
            <span className="kanban-col__footer-cta-label">{footerCta.label}</span>
            {footerCta.subtitle && (
              <span className="kanban-col__footer-cta-subtitle">{footerCta.subtitle}</span>
            )}
          </button>
        )}
      </div>
    </section>
  );
}

export default KanbanColumn;
