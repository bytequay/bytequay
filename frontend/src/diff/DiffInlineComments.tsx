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
import Avatar from '../Avatar';
import { getCached } from '../dataCache';
import MarkdownComposer from '../MarkdownComposer';
import { MarkdownProse } from '../threads/MarkdownProse';
import { relativeTime } from '../notificationDisplay';
import type { ReviewCommentDto, UserProfileDto } from '../types';
import type { LocalPRComment } from '../types/localPr';
import { AgentFindingContent, presentFinding, type AgentFindingPresentation } from '../review/AgentEvidence';
import type { AgentReviewData } from '../review/agentReviewTypes';

export function initials(author: string): string {
  const cleaned = author.replace(/^@/, '');
  const parts = cleaned.split(/[.\s_-]+/).filter(Boolean);
  const letters = parts.length >= 2 ? parts[0][0] + parts[1][0] : cleaned.slice(0, 2);
  return letters.toUpperCase();
}

function isAgentAuthor(author: string): boolean {
  const normalized = author.trim().replace(/^@/, '').toLowerCase();
  return normalized === 'agent'
    || normalized === 'ai reviewer'
    || normalized === 'ai-reviewer'
    || normalized === 'agent-reviewer'
    || normalized === 'verifier'
    || normalized === 'brain'
    || normalized === 'claude'
    || normalized === 'claude-code'
    || normalized === 'codex';
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

export function commentLineLabel(c: Pick<DiffInlineComment, 'side' | 'lineNumber' | 'startLine' | 'startSide'>): string | null {
  if (c.lineNumber === null) return null;
  return rangeLabel(c.side, c.lineNumber, c.startLine, c.startSide);
}

export function commentLocationLabel(c: Pick<DiffInlineComment, 'filePath' | 'side' | 'lineNumber' | 'startLine' | 'startSide'>): string {
  const path = c.filePath ?? 'Pull request';
  const slash = path.lastIndexOf('/');
  const file = slash >= 0 ? path.slice(slash + 1) : path;
  const line = commentLineLabel(c);
  return line === null ? file : `${file} · ${line}`;
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
  /** Epoch ms — drives the relative-time chip. Omit to hide it. */
  createdAtMs?: number;
  finding?: AgentFindingPresentation;
};

/** Avatar tint: the dev agent's own findings (bot) vs. a real reviewer's
 *  comment synced from GitHub (ext) vs. the current user's own draft (you).
 *  Exported so the Review tab's pending cards (ReviewTabPendingList) use the
 *  same avatar coloring as the inline thread cards. */
export function avatarKind(c: Pick<DiffInlineComment, 'sourceLabel' | 'author' | 'origin'>): 'bot' | 'ext' | 'you' {
  if (c.sourceLabel === 'AGENT' || isAgentAuthor(c.author)) return 'bot';
  if (c.origin === 'remote') return 'ext';
  return 'you';
}

export function githubAvatarLogin(c: Pick<DiffInlineComment, 'sourceLabel' | 'author' | 'origin'>): string | null {
  if (avatarKind(c) === 'bot') return null;
  const author = c.author.trim().replace(/^@/, '');
  if (author.length === 0 || author.toLowerCase() === 'reviewer') return null;
  if (author.toLowerCase() === 'you') {
    return getCached<UserProfileDto>('home:profile')?.login ?? null;
  }
  return author;
}

/** Still-open local drafts that would be swept into the next publish — the
 *  set the Submit-review drawer's pending list and toolbar count show. */
export function isPendingLocalComment(c: LocalPRComment): boolean {
  return c.origin === 'local' && c.publishedAt === null && c.resolvedAt === null && c.dismissedAt === null;
}

export function diffInlineCommentFromLocalPr(c: LocalPRComment, reviewOrIndex?: AgentReviewData | number): DiffInlineComment {
  const review = typeof reviewOrIndex === 'number' ? undefined : reviewOrIndex;
  const agent = c.origin === 'local' && isAgentAuthor(c.author);
  return {
    id: c.id,
    filePath: c.filePath,
    lineNumber: c.lineNumber,
    side: c.side,
    startLine: c.startLine,
    startSide: c.startSide,
    author: agent ? 'AI Reviewer' : c.author,
    body: c.body,
    origin: c.origin,
    parentCommentId: c.parentCommentId,
    resolved: c.resolvedAt !== null,
    dismissed: c.dismissedAt !== null,
    pending: isPendingLocalComment(c),
    sourceLabel: agent ? 'AGENT' : undefined,
    createdAtMs: c.createdAt,
    finding: c.findingId == null || review === undefined ? undefined : presentFinding(review, c.findingId),
  };
}

export function diffInlineCommentFromReviewDto(c: ReviewCommentDto): DiffInlineComment {
  const author = commentAuthor(c.author, sourceAuthor(c.source));
  return {
    id: c.id,
    filePath: c.file,
    lineNumber: c.line,
    side: c.side,
    startLine: c.startLine,
    startSide: c.startSide,
    author,
    body: c.body,
    origin: c.source === 'REMOTE_REVIEWER' ? 'remote' : 'local',
    parentCommentId: null,
    resolved: c.resolved,
    dismissed: false,
    // A remote reviewer's comment already lives on GitHub — never "pending
    // submission" the way an unresolved local/agent finding is.
    pending: c.source !== 'REMOTE_REVIEWER' && !c.resolved,
    sourceLabel: sourceLabel(c.source),
    createdAtMs: c.createdAt,
  };
}

function commentAuthor(author: string | null | undefined, fallback: string): string {
  const trimmed = author?.trim();
  if (trimmed === undefined || trimmed.length === 0 || trimmed.toLowerCase() === 'unknown') {
    return fallback;
  }
  return trimmed.replace(/^@/, '');
}

function sourceAuthor(source: string): string {
  if (source === 'LOCAL_AGENT') return 'AI Reviewer';
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
  submitLabel = '＋ Add to review', showSingleAction = false, autoFocus = true, actions, error,
  singleActionLabel = 'Add single comment',
  className = 'cd-inline-comment cd-inline-comment--composer',
  headerClassName, actionsClassName = 'ic-actions', textareaClassName = 'ic-composer',
  disabled = false,
}: {
  value: string;
  onChange: (next: string) => void;
  onSubmit: () => void;
  onCancel?: () => void;
  range?: string;
  placeholder?: string;
  submitLabel?: string;
  /** Renders a secondary "Add single comment" action next to the primary
   *  submit button, matching the mockup's layout. Same handler as the
   *  primary action — local PR comments have no distinct "post immediately"
   *  path yet vs. queuing a draft for review, just this second way in. */
  showSingleAction?: boolean;
  singleActionLabel?: string;
  autoFocus?: boolean;
  actions?: ReactNode;
  error?: ReactNode;
  className?: string;
  /** Legacy path: renders "Commenting on {range}" as its own div above the
   *  composer instead of merging it into the Write/Preview tab row. Only
   *  RemotePrDiffReviewScreen still passes this. Omit for the current
   *  merged-header layout (docs/mockups/design/claude_design_v1). */
  headerClassName?: string;
  actionsClassName?: string;
  textareaClassName?: string;
  disabled?: boolean;
}) {
  const trimmed = value.trim();
  const rangeHeader = range !== undefined
    ? <>Commenting on <b>{range}</b></>
    : undefined;
  return (
    <div className={className}>
      {headerClassName !== undefined && rangeHeader !== undefined && (
        <div className={headerClassName}>{rangeHeader}</div>
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
        headerLeft={headerClassName === undefined && rangeHeader !== undefined
          ? <span className="ic-composer-range">{rangeHeader}</span>
          : undefined}
      />
      {actions ?? (
        <div className={actionsClassName}>
          <button type="button" className="resolve" onClick={onSubmit} disabled={disabled || trimmed.length === 0}>
            {submitLabel}
          </button>
          {showSingleAction && (
            <button type="button" className="secondary" onClick={onSubmit} disabled={disabled || trimmed.length === 0}>
              {singleActionLabel}
            </button>
          )}
          <span className="ic-actions__spacer" />
          {onCancel !== undefined && (
            <button type="button" className="cancel" onClick={onCancel}>
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
 * The inline comment thread on a single diff line — one bordered card per
 * anchor (matching docs/mockups/design/claude_design_v1/PR Review.dc.html),
 * amber-headed while any comment in it is still a draft/unresolved, neutral
 * once everything in it is resolved/posted. When `allowLocalComments` is set,
 * an empty line shows a composer and each open root comment offers
 * Reply / Resolve / Discard as small text links next to its author.
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
  singleActionLabel,
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
   *  composer so a multi-line range is visible while typing. Also used as the
   *  thread header's line label when `comments` is empty. */
  composingOn?: string;
  placeholder?: string;
  singleActionLabel?: string;
}) {
  const [draft, setDraft] = useState('');
  const [replyingTo, setReplyingTo] = useState<string | null>(null);
  const [replyDraft, setReplyDraft] = useState('');
  const [folded, setFolded] = useState(false);
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
  const hasPending = comments.some(c => c.pending === true);
  const first = comments[0];
  const firstOpenRoot = comments.find(c => c.parentCommentId === null && isOpen(c));
  const threadLabel = first !== undefined ? commentLineLabel(first) ?? composingOn : composingOn;
  return (
    <>
      {comments.length > 0 && (
        <div className={`ic-thread${hasPending ? ' ic-thread--pending' : ''}`}>
          <div className="ic-thread__head">
            <button
              type="button"
              className="ic-thread__fold"
              onClick={() => setFolded(v => !v)}
              aria-expanded={!folded}
              aria-label={folded ? 'Expand thread' : 'Collapse thread'}
              title={folded ? 'Expand thread' : 'Collapse thread'}
            >
              {folded ? '▸' : '▾'}
            </button>
            {hasPending && <span className="ic-thread__pending-badge">Pending review</span>}
            {threadLabel !== undefined && <span className="ic-thread__label">Line {threadLabel}</span>}
          </div>
          {!folded && (
            <div className="ic-thread__body">
              {comments.map(c => {
                const avatarLogin = githubAvatarLogin(c);
                return (
                  <div key={c.id}>
                    <div className="ic-comment" data-finding-id={c.finding?.finding.id}>
                      {avatarLogin !== null ? (
                        <Avatar
                          login={avatarLogin}
                          size={26}
                          className={`ic-comment__avatar-img ic-comment__avatar--${avatarKind(c)}`}
                        />
                      ) : (
                        <span className={`ic-comment__avatar ic-comment__avatar--${avatarKind(c)}`}>
                          {initials(c.author)}
                        </span>
                      )}
                      <div className="ic-comment__col">
                        <div className="ic-comment__meta">
                          <span className="ic-comment__author">{c.author}</span>
                          {c.sourceLabel !== undefined && <span className="ic-comment__tag">{c.sourceLabel}</span>}
                          {c.resolved && <span className="ic-comment__tag ic-comment__tag--resolved">resolved</span>}
                          {c.dismissed && <span className="ic-comment__tag ic-comment__tag--dismissed">dismissed</span>}
                          {c.createdAtMs !== undefined && (
                            <span className="ic-comment__time">{relativeTime(new Date(c.createdAtMs).toISOString())}</span>
                          )}
                          {allowLocalComments && c.parentCommentId === null && (
                            <span className="ic-comment__links">
                              {isOpen(c) && onReply !== undefined && (
                                <button type="button" onClick={() => { setReplyingTo(c.id); setReplyDraft(''); }}>
                                  Reply
                                </button>
                              )}
                              {isOpen(c) && onResolve !== undefined && (
                                <button type="button" onClick={() => onResolve(c.id)}>Resolve</button>
                              )}
                              {isOpen(c) && onDismiss !== undefined && (
                                <button type="button" onClick={() => onDismiss(c.id)}>Discard</button>
                              )}
                              {!isOpen(c) && onReopen !== undefined && (
                                <button type="button" onClick={() => onReopen(c.id)}>Reopen</button>
                              )}
                            </span>
                          )}
                        </div>
                        <div className="ic-comment__text">
                          {c.finding !== undefined
                            ? <AgentFindingContent view={c.finding} body={c.body} pending={c.pending} />
                            : <MarkdownProse text={c.body} />}
                        </div>
                      </div>
                    </div>
                    {replyingTo === c.id && onReply !== undefined && (
                      <DiffInlineCommentComposer
                        value={replyDraft}
                        onChange={setReplyDraft}
                        onSubmit={() => submitReply(c)}
                        onCancel={() => { setReplyingTo(null); setReplyDraft(''); }}
                        placeholder="Write a reply — markdown supported."
                        submitLabel="Reply"
                        className="ic-reply-composer"
                      />
                    )}
                  </div>
                );
              })}
              {allowLocalComments && onReply !== undefined && firstOpenRoot !== undefined && replyingTo === null && (
                <button
                  type="button"
                  className="ic-thread__reply-stub"
                  onClick={() => { setReplyingTo(firstOpenRoot.id); setReplyDraft(''); }}
                >
                  Write a reply
                </button>
              )}
            </div>
          )}
        </div>
      )}
      {allowLocalComments && onAdd !== undefined && (
        <DiffInlineCommentComposer
          value={draft}
          onChange={setDraft}
          onSubmit={submit}
          onCancel={onCancel !== undefined ? () => { setDraft(''); onCancel(); } : undefined}
          range={composingOn}
          placeholder={placeholder}
          showSingleAction
          singleActionLabel={singleActionLabel}
        />
      )}
    </>
  );
}
