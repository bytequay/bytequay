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
import { useEffect, useRef, useState } from 'react';
import type { ReactionsDto } from '../types';
import { REACTION_EMOJI, REACTION_PICKER, type ReactionContent } from './utils';

/** Reverse map of {@link REACTION_FIELD} — given a ReactionsDto field
 *  name (e.g. {@code plusOne}), what's the API content string the
 *  reactions endpoint expects ({@code +1})? Hardcoded rather than
 *  derived since the inverse function is small and lets the chip
 *  click handler do its lookup without a search. */
const REACTION_CONTENT_BY_FIELD: Record<keyof ReactionsDto, ReactionContent> = {
  plusOne: '+1',
  minusOne: '-1',
  laugh: 'laugh',
  hooray: 'hooray',
  confused: 'confused',
  heart: 'heart',
  rocket: 'rocket',
  eyes: 'eyes',
};

/** The "+ reaction" affordance: a small smiley button that opens an
 *  inline emoji picker. Click an emoji → the parent's onPick callback
 *  fires. The popover closes on emoji click or click-outside. */
export function ReactionAddButton({ onPick, disabled }: {
  onPick: (content: ReactionContent) => void;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDocClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);
  return (
    <div className="reaction-add" ref={ref}>
      <button
        type="button"
        className="reaction-add__trigger"
        onClick={() => setOpen(v => !v)}
        title="Add a reaction"
        disabled={disabled}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <span aria-hidden="true">😀</span>
        <span className="reaction-add__plus" aria-hidden="true">+</span>
      </button>
      {open && (
        <div className="reaction-add__picker" role="menu">
          {REACTION_PICKER.map(r => (
            <button
              key={r.content}
              type="button"
              className="reaction-add__option"
              onClick={() => { setOpen(false); onPick(r.content); }}
              title={r.label}
            >
              {r.emoji}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function ReactionChips({
  reactions,
  onAddReaction,
}: {
  reactions: ReactionsDto | null | undefined;
  /** When provided, renders a smiley-add button next to the chips
   *  (always visible — even with zero existing reactions). */
  onAddReaction?: (content: ReactionContent) => void;
}) {
  const entries = reactions
    ? (Object.keys(REACTION_EMOJI) as (keyof ReactionsDto)[])
        .map(k => [k, reactions[k]] as const)
        .filter(([, n]) => n > 0)
    : [];
  if (entries.length === 0 && !onAddReaction) return null;
  return (
    <div className="reaction-chips">
      {entries.map(([k, n]) => (
        // Clicking an existing chip adds the same reaction from the
        // viewer — same affordance github.com uses. When onAddReaction
        // isn't provided (read-only context) the chip stays a span so
        // it's not focusable / clickable.
        onAddReaction ? (
          <button
            key={k}
            type="button"
            className="reaction-chip reaction-chip--clickable"
            onClick={() => onAddReaction(REACTION_CONTENT_BY_FIELD[k])}
            title={`Add a ${REACTION_EMOJI[k]} reaction`}
            aria-label={`Add reaction (${n} so far)`}
          >
            <span className="reaction-chip__emoji" aria-hidden="true">{REACTION_EMOJI[k]}</span>
            <span className="reaction-chip__count">{n}</span>
          </button>
        ) : (
          <span key={k} className="reaction-chip">
            <span className="reaction-chip__emoji" aria-hidden="true">{REACTION_EMOJI[k]}</span>
            <span className="reaction-chip__count">{n}</span>
          </span>
        )
      ))}
      {onAddReaction && <ReactionAddButton onPick={onAddReaction} />}
    </div>
  );
}
