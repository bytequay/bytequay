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
import { useEffect, useState } from 'react';
import type { DiffInlineComment } from '../diff/DiffInlineComments';
import { PendingCommentsList } from '../diff/PendingCommentsList';
import MarkdownComposer from '../MarkdownComposer';

export type ReviewVerdict = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';

const VERDICT_OPTIONS: Array<{ value: ReviewVerdict; label: string; desc: string; tone: string }> = [
  { value: 'COMMENT', label: 'Comment', desc: 'Submit general feedback without explicit approval.', tone: 'comment' },
  { value: 'APPROVE', label: 'Approve', desc: 'Submit feedback and approve merging these changes.', tone: 'approve' },
  { value: 'REQUEST_CHANGES', label: 'Request changes', desc: 'Submit feedback that must be addressed before merging.', tone: 'request' },
];

function submitErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : 'Could not submit the review.';
  return message.replace(/^Error invoking remote method '[^']+': Error:\s*/, '');
}

/** The backend refuses to publish to a repository the app doesn't watch: an
 *  external PR's GitHub effects are owned by a review trunk in the workspace
 *  bound to its repository, and an unwatched repository has neither. Its
 *  message names the repository, which is all this drawer needs to offer the
 *  fix — keep the pattern in step with SqliteExternalPrActionStore's
 *  UnwatchedRepositoryException. */
const UNWATCHED_REPOSITORY = /must watch (\S+) before publishing/;

function unwatchedRepository(submitError: string | null): string | null {
  return submitError === null ? null : submitError.match(UNWATCHED_REPOSITORY)?.[1] ?? null;
}

/**
 * Centered modal for submitting a review — an overall comment plus a verdict,
 * mirroring github.com's "Finish your review" panel. It sends the selected
 * verdict, overall body, and unresolved local line comments as one review.
 * The caller owns the boundary: a task-local review dispatches Development,
 * while an external PR publishes through GitHub. All three verdicts are
 * always offered — GitHub itself rejects Approve/Request-changes on a PR you
 * authored, and that rejection surfaces through the normal submitError path
 * below.
 */
export function SubmitReviewDrawer({
  open, submitting = false, onClose, onSubmit, pendingComments = [], onRemovePending,
  onWatchRepo, subject, onJumpToComment, onDiscard,
}: {
  open: boolean;
  submitting?: boolean;
  onClose: () => void;
  onSubmit: (body: string, verdict: ReviewVerdict) => void | Promise<void>;
  /** Draft comments not yet submitted — shown below the overall body so the
   *  reviewer sees exactly what this submission will send, and can drop one
   *  before submitting. Omit where no draft-comment source is wired up. */
  pendingComments?: DiffInlineComment[];
  onRemovePending?: (commentId: string) => void;
  /** Starts watching the PR's repository. Offered only when the submission
   *  failed for want of a watched repository. Omit on surfaces whose PR
   *  always has one (a task's own PR), where the failure can't arise. */
  onWatchRepo?: () => void;
  /** "repo #37" chip beside the title. Omit where the host surface already
   *  names the PR unambiguously. */
  subject?: string;
  /** Selects a draft's file/line in the diff. Omit where the host has no
   *  diff to jump to. */
  onJumpToComment?: (comment: DiffInlineComment) => void;
  /** Drops every pending draft in one go. Omit where drafts can't be
   *  deleted (the caller confirms — this fires straight away). */
  onDiscard?: () => void;
}) {
  const [body, setBody] = useState('');
  const [verdict, setVerdict] = useState<ReviewVerdict>('COMMENT');
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [pendingOpen, setPendingOpen] = useState(true);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Fresh draft each time the drawer opens, so a prior submission's text
  // doesn't linger the next time it's reopened.
  useEffect(() => {
    if (open) { setBody(''); setVerdict('COMMENT'); setSubmitError(null); }
  }, [open]);

  const unwatched = unwatchedRepository(submitError);

  const submit = async () => {
    setSubmitError(null);
    try {
      await onSubmit(body, verdict);
    } catch (error) {
      setSubmitError(submitErrorMessage(error));
    }
  };

  if (!open) return null;
  return (
    <div className="submit-review-drawer-backdrop" role="presentation" onClick={onClose}>
      <div
        className="submit-review-drawer"
        role="dialog"
        aria-label="Submit review"
        onClick={e => e.stopPropagation()}
      >
        <div className="submit-review-drawer__head">
          <strong>Submit review</strong>
          {subject !== undefined && <span className="submit-review-drawer__subject">{subject}</span>}
          <button type="button" className="submit-review-drawer__close" aria-label="Close" onClick={onClose}>×</button>
        </div>
        <div className="submit-review-drawer__scroll">
          <MarkdownComposer
            value={body}
            onChange={setBody}
            placeholder="Leave an overall comment on this pull request…"
            rows={5}
            autoGrow={false}
            disabled={submitting}
            onSubmitShortcut={() => { void submit(); }}
          />
          <div className="submit-review-drawer__section">
            <span className="submit-review-drawer__section-label">Review decision</span>
            <div className="submit-review-drawer__verdicts" role="radiogroup" aria-label="Review verdict">
              {VERDICT_OPTIONS.map(opt => (
                <label
                  key={opt.value}
                  className={`submit-review-verdict submit-review-verdict--${opt.tone}${
                    verdict === opt.value ? ' submit-review-verdict--active' : ''}`}
                >
                  <input
                    type="radio"
                    name="submit-review-verdict"
                    value={opt.value}
                    checked={verdict === opt.value}
                    onChange={() => setVerdict(opt.value)}
                    disabled={submitting}
                  />
                  <span className="submit-review-verdict__text">
                    <b>{opt.label}</b>
                    <span className="submit-review-verdict__desc">{opt.desc}</span>
                  </span>
                </label>
              ))}
            </div>
          </div>
          <div className="submit-review-drawer__section">
            <button
              type="button"
              className="submit-review-drawer__pending-toggle"
              aria-expanded={pendingOpen}
              onClick={() => setPendingOpen(open => !open)}
            >
              <span className={`submit-review-drawer__chevron${pendingOpen ? '' : ' submit-review-drawer__chevron--closed'}`} aria-hidden="true">
                <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3.5 6l4.5 4.5L12.5 6" /></svg>
              </span>
              Pending comments
              <span className="submit-review-drawer__pending-badge">{pendingComments.length}</span>
            </button>
            {pendingOpen && (
              <PendingCommentsList
                comments={pendingComments}
                onRemove={submitting ? undefined : onRemovePending}
                onJump={onJumpToComment}
                emptyHint="No pending inline comments. You can still leave overall feedback above."
              />
            )}
          </div>
          {submitError !== null && (
            <p className="submit-review-drawer__error" role="alert">
              {unwatched === null ? submitError : (
                <>
                  Can't submit review — ByteQuay isn't watching <code>{unwatched}</code> yet.
                  {onWatchRepo !== undefined && (
                    <button
                      type="button"
                      className="button button--primary button--sm submit-review-drawer__watch"
                      onClick={onWatchRepo}
                    >
                      Watch {unwatched}
                    </button>
                  )}
                </>
              )}
            </p>
          )}
        </div>
        <div className="submit-review-drawer__foot">
          {onDiscard !== undefined && (
            <button
              type="button"
              className="submit-review-drawer__discard"
              onClick={onDiscard}
              disabled={submitting}
            >
              Discard review
            </button>
          )}
          <span style={{ flex: 1 }} />
          <button type="button" className="submit-review-drawer__cancel" onClick={onClose} disabled={submitting}>
            Cancel
          </button>
          <button
            type="button"
            className="submit-review-drawer__submit"
            onClick={() => { void submit(); }}
            disabled={submitting}
          >
            {submitting ? 'Submitting…' : 'Submit review'}
            {!submitting && <kbd className="submit-review-drawer__kbd" aria-hidden="true">⌘↵</kbd>}
          </button>
        </div>
      </div>
    </div>
  );
}
