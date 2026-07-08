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
import type { LocalPRComment } from '../types/localPr';
import { useAutoGrow } from '../useAutoGrow';

function initials(author: string): string {
  const cleaned = author.replace(/^@/, '');
  const parts = cleaned.split(/[.\s_-]+/).filter(Boolean);
  const letters = parts.length >= 2 ? parts[0][0] + parts[1][0] : cleaned.slice(0, 2);
  return letters.toUpperCase();
}

/** "R42" for a single line, or "L40 to R42" for a multi-line range — shared
 *  by every diff-comment composer so the copy reads identically everywhere. */
export function rangeLabel(
  side: 'LEFT' | 'RIGHT', line: number, startLine?: number | null, startSide?: 'LEFT' | 'RIGHT' | null,
): string {
  const prefix = (s: 'LEFT' | 'RIGHT') => (s === 'LEFT' ? 'L' : 'R');
  if (startLine == null || startLine === line) return `${prefix(side)}${line}`;
  return `${prefix(startSide ?? side)}${startLine} to ${prefix(side)}${line}`;
}

/**
 * The inline comment thread(s) on a single diff line (mockup Frame 15). Each
 * comment carries an origin badge — 🔒 LOCAL (purple, never migrates) or
 * REMOTE — so it's always clear which comments stay private. When
 * `allowLocalComments` is set, an empty line shows a composer and each thread
 * offers Reply / Mark resolved.
 */
export function DiffInlineComments({
  comments, allowLocalComments, onAdd, onResolve, onDismiss, onCancel, composingOn,
}: {
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  onAdd?: (body: string) => void;
  onResolve?: (commentId: string) => void;
  /** Close the thread without the agent addressing it — the other terminal
   *  state alongside `onResolve`. */
  onDismiss?: (commentId: string) => void;
  /** Discard the open composer (Esc or the Cancel button). */
  onCancel?: () => void;
  /** {@link rangeLabel} of the line/range the open composer is anchored to
   *  (e.g. "R42" or "L40 to R42") — shown as a small header above the
   *  composer so a multi-line range is visible while typing. Omit for a
   *  plain single-line composer with no range to call out. */
  composingOn?: string;
}) {
  const [draft, setDraft] = useState('');
  const draftRef = useAutoGrow(draft);
  const submit = () => {
    const body = draft.trim();
    if (body.length > 0 && onAdd !== undefined) { onAdd(body); setDraft(''); }
  };
  return (
    <>
      {comments.map(c => (
        <div className="cd-inline-comment" key={c.id}>
          <div className="ic-head">
            <span className="avatar">{initials(c.author)}</span>
            <span className="author">{c.author}</span>
            <span className={c.origin === 'local' ? 'local-badge' : 'remote-badge'}>
              {c.origin === 'local' ? '🔒 LOCAL' : 'REMOTE'}
            </span>
            {c.resolvedAt !== null && <span className="resolved-badge">resolved</span>}
            {c.dismissedAt !== null && <span className="dismissed-badge">dismissed</span>}
            {c.startLine !== null && c.startLine !== c.lineNumber && c.lineNumber !== null && (
              <span className="ic-range">{rangeLabel(c.side, c.lineNumber, c.startLine, c.startSide)}</span>
            )}
          </div>
          <div className="ic-body">{c.body}</div>
          {allowLocalComments && c.resolvedAt === null && c.dismissedAt === null && (
            <div className="ic-actions">
              <button type="button">Reply</button>
              {onResolve !== undefined && (
                <button type="button" className="resolve" onClick={() => onResolve(c.id)}>
                  Mark resolved
                </button>
              )}
              {onDismiss !== undefined && (
                <button type="button" className="dismiss" onClick={() => onDismiss(c.id)}>
                  Dismiss
                </button>
              )}
            </div>
          )}
        </div>
      ))}
      {allowLocalComments && onAdd !== undefined && (
        <div className="cd-inline-comment">
          {composingOn !== undefined && (
            <div className="ic-composer-range">Commenting on {composingOn}</div>
          )}
          <textarea
            ref={draftRef}
            className="ic-composer"
            placeholder="Leave a local comment… (⌘↵ to save, Esc to discard)"
            value={draft}
            autoFocus
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => {
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
              else if (e.key === 'Escape') { e.preventDefault(); setDraft(''); onCancel?.(); }
            }}
          />
          <div className="ic-actions">
            <button type="button" className="resolve" onClick={submit} disabled={draft.trim().length === 0}>
              Save
            </button>
            {onCancel !== undefined && (
              <button type="button" onClick={() => { setDraft(''); onCancel(); }}>
                Cancel
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
}
