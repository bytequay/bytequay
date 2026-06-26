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
import type { ReactNode } from 'react';
import { Avatar } from '../../primitives';

/** Pull-request lifecycle state. */
export type PRStatus = 'open' | 'merged' | 'draft';
/** A clickable metadata chip (reviewers, labels, …). */
export type PRMetaChip = { icon?: ReactNode; label: ReactNode; count?: number };
/** CI check-run tallies for the summary card. */
export type PRChecks = { passed: number; failed: number; pending: number; total: number };

/** The "All checks passed / N failing" summary card (frames 6/7). */
function PRCheckCard({ checks }: { checks: PRChecks }) {
  const allGreen = checks.failed === 0 && checks.pending === 0 && checks.total > 0;
  const headIcon = checks.failed > 0 ? 'fail' : checks.pending > 0 ? 'pending' : 'ok';
  const headText = checks.total === 0
    ? 'No checks reported'
    : checks.failed > 0
      ? `${checks.failed} check${checks.failed === 1 ? '' : 's'} failing`
      : checks.pending > 0
        ? `${checks.pending} check${checks.pending === 1 ? '' : 's'} running`
        : 'All checks have passed';
  return (
    <div className="pr-check-card">
      <div className="hd">
        <span className={`ic ${headIcon}`} aria-hidden>{allGreen ? '✓' : checks.failed > 0 ? '✕' : '●'}</span>
        {headText}
      </div>
      <div className="sub">
        {checks.passed} passed
        {checks.failed > 0 ? ` · ${checks.failed} failed` : ''}
        {checks.pending > 0 ? ` · ${checks.pending} running` : ''}
      </div>
    </div>
  );
}

/** One review comment thread in the PR tab. */
export type CommentThreadData = {
  id: string;
  author: string;
  authorInitials?: string;
  file?: string;
  status: 'open' | 'resolved';
  body: ReactNode;
  reply?: { src: string; text: ReactNode };
};

/** A single comment thread card. */
export function CommentThread({ thread }: { thread: CommentThreadData }) {
  return (
    <div className="pr-comment-thread">
      <div className="head">
        <Avatar initials={thread.authorInitials ?? thread.author.slice(0, 2).toUpperCase()} size={18} hue="teal" />
        <span className="author">{thread.author}</span>
        {thread.file !== undefined && <span className="file">{thread.file}</span>}
        <span className={`status ${thread.status}`}>{thread.status === 'open' ? 'Open' : 'Resolved'}</span>
      </div>
      <div className="body">{thread.body}</div>
      {thread.reply !== undefined && (
        <div className="reply"><span className="src">{thread.reply.src}</span>{thread.reply.text}</div>
      )}
    </div>
  );
}

/**
 * The PR tab — pull-request metadata (status badge, branch flow, meta
 * chips, author) plus the review comment threads and an add-comment box.
 * Used on the Comments stage and the standalone review thread.
 * Presentational; the host wires the comment box to the review API.
 */
export function PRTabContent({
  title, prNumber, status, statusLabel, headBranch, baseBranch, metaChips, checks,
  author, authoredLabel, threads, threadsHeader, commentValue = '', onCommentChange,
  onAddComment, addLabel = 'Comment',
}: {
  /** PR title + number, rendered as the section header (frame 7). */
  title?: ReactNode;
  prNumber?: number;
  status: PRStatus;
  statusLabel: ReactNode;
  headBranch?: string;
  baseBranch?: string;
  metaChips?: PRMetaChip[];
  /** CI check summary card, shown above the comment threads. */
  checks?: PRChecks;
  author?: { initials: string; name: ReactNode };
  authoredLabel?: ReactNode;
  threads?: CommentThreadData[];
  /** Header above the threads — defaults to "Comments". */
  threadsHeader?: ReactNode;
  commentValue?: string;
  onCommentChange?: (next: string) => void;
  onAddComment?: () => void;
  addLabel?: string;
}) {
  return (
    <>
      {title !== undefined && (
        <div className="pr-section-h">
          {title}
          {prNumber !== undefined && <span className="num"> #{prNumber}</span>}
        </div>
      )}
      <div className="pr-status-row">
        <span className={`pr-status-badge ${status}`}>{statusLabel}</span>
        {headBranch !== undefined && baseBranch !== undefined && (
          <span className="pr-branch-flow">
            <span className="br">{headBranch}</span>
            <span className="arrow" aria-hidden>→</span>
            <span className="br">{baseBranch}</span>
          </span>
        )}
      </div>

      {metaChips !== undefined && metaChips.length > 0 && (
        <div className="pr-meta-chips">
          {metaChips.map((c, i) => (
            <button type="button" className="pr-meta-chip" key={i}>
              {c.icon !== undefined && <span className="ic" aria-hidden>{c.icon}</span>}
              {c.label}
              {c.count !== undefined && <span className="count">{c.count}</span>}
            </button>
          ))}
        </div>
      )}

      {author !== undefined && (
        <div className="pr-author-row">
          <Avatar initials={author.initials} size={22} hue="purple" />
          <span className="text"><span className="b">{author.name}</span></span>
          {authoredLabel !== undefined && <span className="ts">{authoredLabel}</span>}
        </div>
      )}

      {checks !== undefined && <PRCheckCard checks={checks} />}

      {threads !== undefined && threads.length > 0 && (
        <>
          <div className="pr-comment-section-h">{threadsHeader ?? 'Comments'}</div>
          {threads.map(t => <CommentThread key={t.id} thread={t} />)}
        </>
      )}

      {onAddComment !== undefined && (
        <div className="pr-comment-box">
          <textarea
            className="input"
            placeholder="Add a comment…"
            value={commentValue}
            onChange={e => onCommentChange?.(e.target.value)}
            style={{ border: 0, outline: 'none', resize: 'vertical', background: 'transparent', fontFamily: 'inherit' }}
          />
          <div className="footer">
            <span className="grow" />
            <button
              type="button"
              className="comment-btn"
              disabled={commentValue.trim().length === 0}
              onClick={onAddComment}
            >
              {addLabel}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
