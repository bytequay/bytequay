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
import { useState, type ReactNode } from 'react';
import MarkdownComposer from '../MarkdownComposer';
import { MarkdownProse } from '../threads/MarkdownProse';
import type { ReviewCommentDto } from '../types';
import type { LocalPRComment } from '../types/localPr';

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

export type DiffInlineComment = {
  id: string;
  filePath: string | null;
  lineNumber: number | null;
  side: 'LEFT' | 'RIGHT';
  startLine: number | null;
  startSide: 'LEFT' | 'RIGHT' | null;
  author: string;
  body: string;
  origin: 'local' | 'remote';
  parentCommentId: string | null;
  resolved: boolean;
  dismissed: boolean;
  pending?: boolean;
  sourceLabel?: string;
};

export function diffInlineCommentFromLocalPr(c: LocalPRComment): DiffInlineComment {
  return {
    id: c.id,
    filePath: c.filePath,
    lineNumber: c.lineNumber,
    side: c.side,
    startLine: c.startLine,
    startSide: c.startSide,
    author: c.author,
    body: c.body,
    origin: c.origin,
    parentCommentId: c.parentCommentId,
    resolved: c.resolvedAt !== null,
    dismissed: c.dismissedAt !== null,
    pending: c.origin === 'local' && c.publishedAt === null,
  };
}

export function diffInlineCommentFromReviewDto(c: ReviewCommentDto): DiffInlineComment {
  return {
    id: c.id,
    filePath: c.file,
    lineNumber: c.line,
    side: c.side,
    startLine: c.startLine,
    startSide: c.startSide,
    author: sourceAuthor(c.source),
    body: c.body,
    origin: c.source === 'REMOTE_REVIEWER' ? 'remote' : 'local',
    parentCommentId: null,
    resolved: c.resolved,
    dismissed: false,
    sourceLabel: sourceLabel(c.source),
  };
}

function sourceAuthor(source: string): string {
  if (source === 'LOCAL_AGENT') return 'agent';
  if (source === 'REMOTE_REVIEWER') return 'reviewer';
  return 'you';
}

function sourceLabel(source: string): string | undefined {
  if (source === 'LOCAL_AGENT') return 'AGENT';
  return undefined;
}

function isOpen(c: DiffInlineComment): boolean {
  return !c.resolved && !c.dismissed;
}

export function DiffInlineCommentComposer({
  value, onChange, onSubmit, onCancel, range, placeholder = 'Leave a comment — markdown supported.',
  submitLabel = 'Comment', autoFocus = true, actions, error, className = 'cd-inline-comment cd-inline-comment--composer',
  headerClassName = 'ic-composer-range', actionsClassName = 'ic-actions', textareaClassName = 'ic-composer',
  disabled = false,
}: {
  value: string;
  onChange: (next: string) => void;
  onSubmit: () => void;
  onCancel?: () => void;
  range?: string;
  placeholder?: string;
  submitLabel?: string;
  autoFocus?: boolean;
  actions?: ReactNode;
  error?: ReactNode;
  className?: string;
  headerClassName?: string;
  actionsClassName?: string;
  textareaClassName?: string;
  disabled?: boolean;
}) {
  const trimmed = value.trim();
  return (
    <div className={className}>
      {range !== undefined && (
        <div className={headerClassName}>Commenting on {range}</div>
      )}
      <MarkdownComposer
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        rows={4}
        autoFocus={autoFocus}
        disabled={disabled}
        textareaClassName={textareaClassName}
        onSubmitShortcut={onSubmit}
        onCancelShortcut={onCancel}
      />
      {actions ?? (
        <div className={actionsClassName}>
          <button type="button" className="resolve" onClick={onSubmit} disabled={disabled || trimmed.length === 0}>
            {submitLabel}
          </button>
          {onCancel !== undefined && (
            <button type="button" onClick={onCancel}>
              Cancel
            </button>
          )}
        </div>
      )}
      {error}
    </div>
  );
}

/**
 * The inline comment thread(s) on a single diff line (mockup Frame 15). Each
 * comment carries an origin badge — 🔒 LOCAL (purple, never migrates) or
 * REMOTE — so it's always clear which comments stay private. When
 * `allowLocalComments` is set, an empty line shows a composer and each open
 * root thread offers Reply / Resolve / Discard.
 */
export function DiffInlineComments({
  comments,
  allowLocalComments,
  onAdd,
  onReply,
  onResolve,
  onDismiss,
  onReopen,
  onCancel,
  composingOn,
  placeholder,
}: {
  comments: DiffInlineComment[];
  allowLocalComments: boolean;
  onAdd?: (body: string) => void;
  onReply?: (comment: DiffInlineComment, body: string) => void;
  onResolve?: (commentId: string) => void;
  /** Close the thread without the agent addressing it — the other terminal
   *  state alongside `onResolve`. */
  onDismiss?: (commentId: string) => void;
  /** Reopen a resolved review comment when that backend path is available. */
  onReopen?: (commentId: string) => void;
  /** Discard the open composer (Esc or the Cancel button). */
  onCancel?: () => void;
  /** {@link rangeLabel} of the line/range the open composer is anchored to
   *  (e.g. "R42" or "L40 to R42") — shown as a small header above the
   *  composer so a multi-line range is visible while typing. Omit for a
   *  plain single-line composer with no range to call out. */
  composingOn?: string;
  placeholder?: string;
}) {
  const [draft, setDraft] = useState('');
  const [replyingTo, setReplyingTo] = useState<string | null>(null);
  const [replyDraft, setReplyDraft] = useState('');
  const submit = () => {
    const body = draft.trim();
    if (body.length > 0 && onAdd !== undefined) { onAdd(body); setDraft(''); }
  };
  const submitReply = (comment: DiffInlineComment) => {
    const body = replyDraft.trim();
    if (body.length === 0 || onReply === undefined) return;
    onReply(comment, body);
    setReplyDraft('');
    setReplyingTo(null);
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
            {c.sourceLabel !== undefined && <span className="remote-badge">{c.sourceLabel}</span>}
            {c.pending === true && <span className="pending-badge">Pending</span>}
            {c.resolved && <span className="resolved-badge">resolved</span>}
            {c.dismissed && <span className="dismissed-badge">dismissed</span>}
            {c.startLine !== null && c.startLine !== c.lineNumber && c.lineNumber !== null && (
              <span className="ic-range">{rangeLabel(c.side, c.lineNumber, c.startLine, c.startSide)}</span>
            )}
          </div>
          <div className="ic-body"><MarkdownProse text={c.body} /></div>
          {allowLocalComments && isOpen(c) && c.parentCommentId === null && (
            <div className="ic-actions">
              {onReply !== undefined && (
                <button
                  type="button"
                  onClick={() => {
                    setReplyingTo(c.id);
                    setReplyDraft('');
                  }}
                >
                  Reply
                </button>
              )}
              {onResolve !== undefined && (
                <button type="button" className="resolve" onClick={() => onResolve(c.id)}>
                  Resolve conversation
                </button>
              )}
              {onDismiss !== undefined && (
                <button type="button" className="dismiss" onClick={() => onDismiss(c.id)}>
                  Discard draft
                </button>
              )}
            </div>
          )}
          {allowLocalComments && c.parentCommentId === null && !isOpen(c) && onReopen !== undefined && (
            <div className="ic-actions">
              <button type="button" onClick={() => onReopen(c.id)}>
                Reopen conversation
              </button>
            </div>
          )}
          {replyingTo === c.id && onReply !== undefined && (
            <DiffInlineCommentComposer
              value={replyDraft}
              onChange={setReplyDraft}
              onSubmit={() => submitReply(c)}
              onCancel={() => { setReplyingTo(null); setReplyDraft(''); }}
              placeholder="Write a reply — markdown supported."
              submitLabel="Reply"
            />
          )}
        </div>
      ))}
      {allowLocalComments && onAdd !== undefined && (
        <DiffInlineCommentComposer
          value={draft}
          onChange={setDraft}
          onSubmit={submit}
          onCancel={onCancel !== undefined ? () => { setDraft(''); onCancel(); } : undefined}
          range={composingOn}
          placeholder={placeholder}
        />
      )}
    </>
  );
}
