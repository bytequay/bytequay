import { useState } from 'react';
import type { ReactNode } from 'react';
import Avatar from '../Avatar';
import { authorAssociationLabel, formatRelativeTime } from './utils';

/** Timeline entry for a `reviewed` event. Same outer shape as a comment
 *  card (40-px avatar at the same position as the timeline's other
 *  avatars, body to the right) so the avatar lines up with comments
 *  immediately above and below. The body itself is *flat* — no speech-
 *  bubble border or tail — and the inline review threads sit directly
 *  on the page beneath the header, matching docs/mockups/v2/codereview/
 *  display-reviewed.png.
 *
 *  Replaces the older "render a review as a full bordered comment card"
 *  treatment, which made approve / request-changes events look
 *  identical to plain issue comments. */
export function ReviewActivityRow({
  actor,
  verb,
  state,
  timestamp,
  isAuthor,
  authorAssociation,
  marker,
  hasContent,
  children,
}: {
  actor: string;
  verb: string;
  state: string | null;
  timestamp: string | null;
  isAuthor: boolean;
  authorAssociation: string | null;
  marker: string;
  /** False when the review has no body and no inline threads — we hide
   *  the expand button entirely so the user doesn't click a no-op. */
  hasContent: boolean;
  /** Body + threads content shown when the row is expanded. */
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(true);
  // Suppress the "COMMENTED" pill: the verb "left a review" already
  // tells the user this is a comment-only review, and the blue pill
  // alongside it adds no information. Pills only render for APPROVED
  // and CHANGES_REQUESTED, which carry an outcome the verb doesn't.
  const showStatePill = state === 'APPROVED' || state === 'CHANGES_REQUESTED';
  const stateClass = showStatePill ? `prc-verdict-pill prc-verdict-pill--${state!.toLowerCase()}` : '';
  const stateLabel = showStatePill ? state!.replace(/_/g, ' ').toLowerCase() : null;
  return (
    <article className="prc-comment-card prc-review-card">
      <Avatar login={actor} size={40} className="prc-comment-avatar" />
      <span className="prc-review-card__marker" aria-hidden>{marker}</span>
      <div className="prc-comment-card-body prc-review-card-body">
        <header className="prc-comment-head">
          <a
            href={`https://github.com/${actor}`}
            target="_blank"
            rel="noreferrer"
            className="prc-comment-author"
          >
            {actor}
          </a>
          <span className="prc-comment-verb">{verb}</span>
          {timestamp && (
            <span className="prc-comment-time">{formatRelativeTime(timestamp)}</span>
          )}
          {isAuthor
            ? <span className="prc-comment-role">AUTHOR</span>
            : authorAssociationLabel(authorAssociation) && (
              <span className="prc-comment-role prc-comment-role--association">
                {authorAssociationLabel(authorAssociation)}
              </span>
            )}
          {stateLabel && <span className={stateClass}>{stateLabel}</span>}
          {hasContent && (
            <button
              type="button"
              className="prc-review-card__toggle"
              onClick={() => setOpen(v => !v)}
              aria-expanded={open}
              title={open ? 'Hide review details' : 'Show review details'}
            >
              {open ? '▾' : '›'}
            </button>
          )}
        </header>
        {open && hasContent && (
          <div className="prc-review-card__content">{children}</div>
        )}
      </div>
    </article>
  );
}
