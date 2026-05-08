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
import { useEffect, useMemo, useRef, useState } from 'react';
import { renderMarkdown } from './markdown';
import type { AiReviewCommentDto, AiReviewDraftDto, DiffFileDto, PullRequestCommitDto, PullRequestDetailDto, PullRequestDto, ReviewMessageDto, ReviewThreadDto, UserProfileDto } from './types';
import { getCached } from './dataCache';
import { putCache } from './detailCache';
import Avatar from './Avatar';
import { parseUnifiedDiff } from './diffParse';
import {
  computeGap,
  computeFetchRange,
  canExpandUp,
  canExpandDown,
  isGapFullyLoaded,
  EXPAND_INCREMENT,
  type Gap,
  type LoadedGap,
} from './diffExpand';
import { buildFileTree, flattenFileTree, treeOrderedFiles } from './fileTree';
import ResizeHandle from './ResizeHandle';
import AiReviewSidebar, { type AiReviewSidebarHandle } from './AiReviewSidebar';
import PolishButtons from './ai/PolishButtons';
import MarkdownComposer from './MarkdownComposer';

type FilesMode = 'tree' | 'flat';
type ReviewVerdict = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';

const FILES_WIDTH_KEY = 'settings:diff-files-width';
const FILES_MODE_KEY = 'settings:diff-files-mode';
// Bumped to "-v2" so the default flip (now collapsed-by-default) takes
// effect for everyone — old "expanded" preferences saved against the
// previous key are quietly disregarded so the new default applies.
const FILES_COLLAPSED_KEY = 'settings:diff-files-collapsed-v2';
const AI_COLLAPSED_KEY = 'settings:diff-ai-collapsed';
const AI_WIDTH_KEY = 'settings:diff-ai-width';
const FILES_WIDTH_MIN = 180;
const FILES_WIDTH_MAX = 600;
const FILES_WIDTH_DEFAULT = 280;
const FILES_RAIL_WIDTH = 36;
const AI_WIDTH_MIN = 280;
const AI_WIDTH_MAX = 900;
const AI_WIDTH_DEFAULT = 380;
const AI_RAIL_WIDTH = 36;

function loadWidth(): number {
  const raw = localStorage.getItem(FILES_WIDTH_KEY);
  const n = raw ? parseInt(raw, 10) : NaN;
  if (!Number.isFinite(n)) return FILES_WIDTH_DEFAULT;
  return Math.max(FILES_WIDTH_MIN, Math.min(FILES_WIDTH_MAX, n));
}

function loadMode(): FilesMode {
  return localStorage.getItem(FILES_MODE_KEY) === 'flat' ? 'flat' : 'tree';
}

function loadAiWidth(): number {
  const raw = localStorage.getItem(AI_WIDTH_KEY);
  const n = raw ? parseInt(raw, 10) : NaN;
  if (!Number.isFinite(n)) return AI_WIDTH_DEFAULT;
  return Math.max(AI_WIDTH_MIN, Math.min(AI_WIDTH_MAX, n));
}

type Props = {
  pr: PullRequestDto;
  onBack: () => void;
  /** Optional approve handler. A green "Approve" button appears in the toolbar
   *  when provided. GitHub rejects self-approval server-side, so we don't
   *  pre-filter here — the error is surfaced inline. */
  onApprove?: (prId: number, repo: string, number: number) => Promise<void>;
  /** When set, the viewer opens on this single commit's diff instead of
   *  the cumulative PR diff — used by the timeline's clickable SHA chips. */
  initialCommitSha?: string | null;
};

function formatShortSha(sha: unknown): string {
  // Defensive: a non-string slipped in once via an onClick handler that
  // forwarded its MouseEvent into the optional `initialCommitSha`
  // argument. Coerce to string and accept anything; render an empty
  // span when we can't make sense of it.
  if (typeof sha !== 'string') return '';
  return sha.slice(0, 7);
}

// Flat view shows full repo paths, which can run 80+ characters in deep
// monorepos. Replace the middle directory segments with "…" so the file's
// basename always stays fully visible. The full path remains in the row's
// `title` for hover-to-inspect.
function truncatePathMiddle(path: string, headSegments = 1, tailSegments = 2): string {
  const segments = path.split('/');
  if (segments.length <= headSegments + tailSegments) return path;
  const head = segments.slice(0, headSegments).join('/');
  const tail = segments.slice(-tailSegments).join('/');
  return `${head}/…/${tail}`;
}

/** Minimal CSS attribute-selector escaper. Browsers ship CSS.escape but
 *  Safari/old Electron sometimes don't expose it on `window.CSS`, and
 *  jsdom (in tests) doesn't either. Covers the only chars that show up in
 *  filenames or anchor values: backslash, dot, slash, colon, brackets. */
function cssEscape(s: string): string {
  return s.replace(/(["\\\n])/g, '\\$1');
}

function formatRelative(iso: string | null): string {
  if (!iso) return '';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
}

function commitSubject(message: string | null): string {
  if (!message) return '';
  const first = message.split('\n')[0];
  return first.length > 120 ? first.slice(0, 117) + '…' : first;
}

function statusBadge(status: string): { letter: string; cls: string } {
  switch (status) {
    case 'added': return { letter: 'A', cls: 'added' };
    case 'removed': return { letter: 'D', cls: 'removed' };
    case 'renamed': return { letter: 'R', cls: 'renamed' };
    case 'copied': return { letter: 'C', cls: 'copied' };
    default: return { letter: 'M', cls: 'modified' };
  }
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      className={`tree-chevron${open ? ' tree-chevron--open' : ''}`}
      width="10"
      height="10"
      viewBox="0 0 10 10"
      aria-hidden="true"
    >
      <path
        d="M3.5 2L7 5L3.5 8"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
    </svg>
  );
}

function FolderIcon({ open }: { open: boolean }) {
  return (
    <svg
      className="tree-folder"
      width="14"
      height="14"
      viewBox="0 0 14 14"
      aria-hidden="true"
    >
      {open ? (
        <path
          d="M1.5 3.5a1 1 0 0 1 1-1h3l1.2 1.2h4.8a1 1 0 0 1 1 1v.8H3.1l-1.6 5.6a.5.5 0 0 1-.5.4H1V3.5Zm1.2 7.5 1.5-5.2h9.3l-1.5 5.2H2.7Z"
          fill="currentColor"
        />
      ) : (
        <path
          d="M2.5 2.5a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1H7.2L6 2.5H2.5Z"
          fill="currentColor"
        />
      )}
    </svg>
  );
}

function severityClass(s: string): string {
  const k = s.toLowerCase();
  if (k === 'blocker') return 'inline-finding__sev--high';
  if (k === 'warning') return 'inline-finding__sev--med';
  if (k === 'info') return 'inline-finding__sev--low';
  return 'inline-finding__sev--tip';
}

function severityGlyph(s: string): string {
  const k = s.toLowerCase();
  if (k === 'blocker' || k === 'warning') return '!';
  if (k === 'info') return 'i';
  return '·';
}

type InlineFindingProps = {
  comment: AiReviewCommentDto;
  draftId: number | null;
  draftPublished: boolean;
  onDraftUpdated: (draft: AiReviewDraftDto) => void;
};

function InlineFinding({ comment, draftId, draftPublished, onDraftUpdated }: InlineFindingProps) {
  // editedBody (when set) wins over the AI's original. Editing flips into
  // a textarea seeded with whichever is current; Save persists, Cancel
  // discards. Reverting clears editedBody and the AI text comes back.
  const displayed = comment.editedBody ?? comment.body;
  const isHuman = comment.source === 'HUMAN';
  const [editing, setEditing] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [draftBody, setDraftBody] = useState(displayed);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Anchor label — "path:42" for single-line, "path:L40-L42" for ranges.
  // Keep AI comments on the legacy single-line shape so visual diff vs the
  // existing UI is zero unless a HUMAN-staged range is actually present.
  const lineLabel = isHuman && comment.startLine != null && comment.startLine !== comment.lineNumber
    ? `L${comment.startLine}-L${comment.lineNumber}`
    : String(comment.lineNumber);

  const startEdit = () => {
    setDraftBody(displayed);
    setError(null);
    setEditing(true);
  };
  const cancelEdit = () => {
    setEditing(false);
    setError(null);
  };
  const save = async () => {
    if (draftId == null) return;
    const next = draftBody.trim();
    // Empty input means "revert to original" — sending null clears editedBody.
    const payload: string | null = next === comment.body.trim() || next.length === 0 ? null : next;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.updateAiReviewComment(draftId, comment.id, payload);
      onDraftUpdated(updated);
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };
  const revert = async () => {
    if (draftId == null) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.updateAiReviewComment(draftId, comment.id, null);
      onDraftUpdated(updated);
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };
  const setDismissed = async (dismissed: boolean) => {
    if (draftId == null) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await window.bridge.setAiReviewCommentDismissed(draftId, comment.id, dismissed);
      onDraftUpdated(updated);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const dismissed = comment.dismissed;

  // Folded: render a tiny one-line strip with just the severity dot and a
  // small expand chevron — no source label, no location, no action buttons.
  // Steals as little vertical space from the diff as possible. The user
  // clicks the dot or the chevron to bring the full card back.
  if (collapsed) {
    return (
      <div className={`diff-row diff-row--inline-finding diff-row--inline-finding--folded${dismissed ? ' diff-row--inline-finding--dismissed' : ''}${isHuman ? ' diff-row--inline-finding--human' : ''}`}>
        <button
          type="button"
          className="inline-finding-folded"
          onClick={() => setCollapsed(false)}
          title={isHuman
            ? 'Expand pending review comment'
            : `Expand AI comment · ${comment.severity.toLowerCase()}`}
          aria-expanded="false"
        >
          <span className={`inline-finding__sev ${isHuman ? 'inline-finding__sev--human' : severityClass(comment.severity)}`}>
            {isHuman ? '✎' : severityGlyph(comment.severity)}
          </span>
          <span className="inline-finding-folded__hint">▸</span>
        </button>
      </div>
    );
  }

  return (
    <div className={`diff-row diff-row--inline-finding${dismissed ? ' diff-row--inline-finding--dismissed' : ''}${isHuman ? ' diff-row--inline-finding--human' : ''}`}>
      <div className="inline-finding">
        <span className={`inline-finding__sev ${isHuman ? 'inline-finding__sev--human' : severityClass(comment.severity)}`}>
          {isHuman ? '✎' : severityGlyph(comment.severity)}
        </span>
        <div className="inline-finding__body">
          <div className="inline-finding__head">
            <button
              type="button"
              className="inline-finding__fold-btn"
              onClick={() => setCollapsed(true)}
              title="Collapse"
              aria-expanded="true"
            >
              ▾
            </button>
            <span className="inline-finding__source">
              {isHuman ? '⏱ Pending review' : `✨ AI · ${comment.severity.toLowerCase()}`}
            </span>
            <span className="inline-finding__loc">{comment.filePath}:{lineLabel}</span>
            {!isHuman && comment.editedBody !== null && (
              <span className="inline-finding__edited" title="You've edited this comment from the AI's draft.">✎ edited</span>
            )}
            {dismissed && (
              <span className="inline-finding__dismissed-badge" title="Dismissed — won't be sent on publish.">⊘ dismissed</span>
            )}
            {!editing && !draftPublished && draftId != null && !dismissed && (
              <>
                <button
                  type="button"
                  className="inline-finding__edit-btn"
                  onClick={startEdit}
                  title="Edit this comment before publishing"
                >
                  ✎
                </button>
                <button
                  type="button"
                  className="inline-finding__edit-btn inline-finding__edit-btn--danger"
                  onClick={() => void setDismissed(true)}
                  disabled={saving}
                  title="Dismiss — keep the comment but don't send it on publish."
                >
                  ⊘
                </button>
              </>
            )}
            {!editing && !draftPublished && draftId != null && dismissed && (
              <button
                type="button"
                className="inline-finding__edit-btn"
                onClick={() => void setDismissed(false)}
                disabled={saving}
                title="Restore — bring this comment back into the publish payload."
              >
                ↺ restore
              </button>
            )}
          </div>
          {collapsed ? null : editing ? (
            <>
              {comment.editedBody !== null && (
                <div className="inline-finding__original">
                  <span className="inline-finding__original-label">Original AI suggestion</span>
                  <span className="inline-finding__original-text">{comment.body}</span>
                </div>
              )}
              <textarea
                className="inline-finding__textarea"
                value={draftBody}
                onChange={(e) => setDraftBody(e.target.value)}
                rows={3}
                autoFocus
              />
              {error && <div className="inline-finding__error">{error}</div>}
              <div className="inline-finding__actions">
                <button
                  type="button"
                  className="inline-finding__action inline-finding__action--primary"
                  onClick={() => void save()}
                  disabled={saving}
                >
                  {saving ? 'Saving…' : 'Save edit'}
                </button>
                <button
                  type="button"
                  className="inline-finding__action"
                  onClick={cancelEdit}
                  disabled={saving}
                >
                  Cancel
                </button>
                {comment.editedBody !== null && (
                  <button
                    type="button"
                    className="inline-finding__action inline-finding__action--ghost"
                    onClick={() => void revert()}
                    disabled={saving}
                    title="Discard your edit and bring back the AI's original text"
                  >
                    Revert to AI
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="inline-finding__text">{displayed}</div>
          )}
        </div>
      </div>
    </div>
  );
}

type FinishReviewPanelProps = {
  body: string;
  onBodyChange: (next: string) => void;
  verdict: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
  onVerdictChange: (next: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES') => void;
  pendingComments: AiReviewCommentDto[];
  pendingExpanded: boolean;
  onTogglePending: () => void;
  onClose: () => void;
  onSubmit: () => void;
  onDiscard: () => void;
  /** Hide the "Discard review" footer button when there's no draft yet
   *  — discard would be a no-op. */
  canDiscard: boolean;
  discardConfirm: boolean;
  onConfirmDiscard: () => void;
  onCancelDiscard: () => void;
  discardRunning: boolean;
  publishState: 'idle' | 'running' | 'error';
  publishError: string | null;
};

const VERDICT_OPTIONS: Array<{
  value: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';
  label: string;
  desc: string;
}> = [
  { value: 'COMMENT', label: 'Comment', desc: 'Submit general feedback without explicit approval.' },
  { value: 'APPROVE', label: 'Approve', desc: 'Submit feedback and approve merging these changes.' },
  { value: 'REQUEST_CHANGES', label: 'Request changes', desc: 'Submit feedback suggesting changes.' },
];

/**
 * "Finish your review" panel — mirrors github.com's submission modal:
 * a body composer at the top, three verdict radios, an expandable
 * "Pending comments" list, and a footer with Discard / Cancel / Submit.
 * See docs/mockups/v2/codereview/submit-button.png.
 */
function FinishReviewPanel({
  body,
  onBodyChange,
  verdict,
  onVerdictChange,
  pendingComments,
  pendingExpanded,
  onTogglePending,
  onClose,
  onSubmit,
  onDiscard,
  canDiscard,
  discardConfirm,
  onConfirmDiscard,
  onCancelDiscard,
  discardRunning,
  publishState,
  publishError,
}: FinishReviewPanelProps) {
  const submitting = publishState === 'running';
  return (
    <div
      className="finish-review-panel"
      onClick={(e) => e.stopPropagation()}
      role="dialog"
      aria-label="Finish your review"
    >
      <header className="finish-review-panel__head">
        <span className="finish-review-panel__title">Finish your review</span>
        <button
          type="button"
          className="finish-review-panel__close"
          onClick={onClose}
          aria-label="Close"
          title="Close"
        >
          ✕
        </button>
      </header>

      <div className="finish-review-panel__body">
        <MarkdownComposer
          value={body}
          onChange={onBodyChange}
          placeholder="Leave a comment"
          rows={4}
          disabled={submitting}
          textareaClassName="finish-review-panel__textarea"
        />
        {/* "Better words" polish — same affordance the inline-composer
            and PR-comment surfaces use, so the top-level review body
            can be softened without a separate workflow. */}
        <div className="finish-review-panel__polish">
          <PolishButtons
            value={body}
            onChange={onBodyChange}
            disabled={submitting}
          />
        </div>

        <div className="finish-review-panel__verdicts" role="radiogroup" aria-label="Review verdict">
          {VERDICT_OPTIONS.map((opt) => (
            <label
              key={opt.value}
              className={`finish-review-verdict${verdict === opt.value ? ' finish-review-verdict--active' : ''}`}
            >
              <input
                type="radio"
                name="finish-review-verdict"
                value={opt.value}
                checked={verdict === opt.value}
                onChange={() => onVerdictChange(opt.value)}
                disabled={submitting}
              />
              <span className="finish-review-verdict__text">
                <b>{opt.label}</b>
                <span className="finish-review-verdict__desc">{opt.desc}</span>
              </span>
            </label>
          ))}
        </div>

        <button
          type="button"
          className="finish-review-pending__toggle"
          onClick={onTogglePending}
          aria-expanded={pendingExpanded}
        >
          <span className={`finish-review-pending__chevron${pendingExpanded ? ' finish-review-pending__chevron--open' : ''}`} aria-hidden="true">▾</span>
          <span>Pending comments</span>
          <span className="finish-review-pending__count">{pendingComments.length}</span>
        </button>
        {pendingExpanded && (
          <div className="finish-review-pending__list">
            {pendingComments.length === 0 ? (
              <div className="finish-review-pending__empty">No pending comments.</div>
            ) : (
              pendingComments.map((c) => {
                const isHuman = c.source === 'HUMAN';
                const lineLabel = c.startLine != null && c.startLine !== c.lineNumber
                  ? `Lines ${c.startLine}–${c.lineNumber}`
                  : `Line ${c.lineNumber}`;
                return (
                  <div key={c.id} className="finish-review-pending__item">
                    <div className="finish-review-pending__item-head">
                      <span className="finish-review-pending__item-loc">{lineLabel}</span>
                      <span className="finish-review-pending__item-path" title={c.filePath}>{c.filePath}</span>
                      <span className={`finish-review-pending__item-source finish-review-pending__item-source--${isHuman ? 'human' : 'ai'}`}>
                        {isHuman ? '✎ You' : '✨ AI'}
                      </span>
                    </div>
                    <div className="finish-review-pending__item-body">
                      {c.editedBody ?? c.body}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        )}

        {publishState === 'error' && publishError && (
          <div className="finish-review-panel__error">{publishError}</div>
        )}
      </div>

      <footer className="finish-review-panel__foot">
        {discardConfirm ? (
          <div className="finish-review-panel__discard-confirm">
            <span className="finish-review-panel__discard-warn">Discard the staged review and all comments?</span>
            <button
              type="button"
              className="button button--secondary"
              onClick={onCancelDiscard}
              disabled={discardRunning}
            >
              Keep
            </button>
            <button
              type="button"
              className="button button--danger"
              onClick={onConfirmDiscard}
              disabled={discardRunning}
            >
              {discardRunning ? 'Discarding…' : 'Yes, discard'}
            </button>
          </div>
        ) : (
          <>
            {canDiscard ? (
              <button
                type="button"
                className="button button--danger-link"
                onClick={onDiscard}
                disabled={submitting}
              >
                Discard review
              </button>
            ) : (
              <span />
            )}
            <div className="finish-review-panel__foot-right">
              <button
                type="button"
                className="button button--secondary"
                onClick={onClose}
                disabled={submitting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="button button--submit"
                onClick={onSubmit}
                disabled={submitting}
              >
                {submitting ? 'Submitting…' : 'Submit review'}
              </button>
            </div>
          </>
        )}
      </footer>
    </div>
  );
}

// Per-message reaction emoji row. Inline copy of the bigger ReactionChips
// component in PullRequestPreview to avoid a circular import — the inline
// thread uses the same emoji map but renders a slightly tighter row to
// match the mockup at docs/mockups/v2/codereview/comment-layout.png.
const THREAD_REACTION_EMOJI: Record<string, string> = {
  plusOne: '👍', minusOne: '👎', laugh: '😄', hooray: '🎉',
  confused: '😕', heart: '❤️', rocket: '🚀', eyes: '👀',
};

function ThreadReactions({ reactions }: { reactions: ReviewThreadDto['messages'][number]['reactions'] }) {
  if (!reactions) return null;
  const items = Object.entries(reactions)
    .filter(([, n]) => typeof n === 'number' && n > 0)
    .filter(([k]) => THREAD_REACTION_EMOJI[k] !== undefined);
  if (items.length === 0) return null;
  return (
    <div className="diff-thread__reactions">
      {items.map(([k, n]) => (
        <span key={k} className="diff-thread__reaction" title={`${n}`}>
          <span aria-hidden="true">{THREAD_REACTION_EMOJI[k]}</span>
          <span className="diff-thread__reaction-count">{n}</span>
        </span>
      ))}
    </div>
  );
}

/**
 * Renders one existing per-line review thread directly under the diff row
 * it anchors to. Layout follows
 * docs/mockups/v2/codereview/comment-layout.png:
 *   1. Top header with the line range, fold chevron, and Resolved /
 *      Outdated pill on the right.
 *   2. Each message: avatar + author/role/time header row + body +
 *      reactions chips below.
 *   3. Bottom: collapsed "Write a reply" stub that expands to the full
 *      reply composer (with Polish button) on click.
 *
 * Resolved threads default to folded — same behaviour as github.com.
 * Toggle via the chevron at the top.
 */
function InlineExistingThread({
  thread,
  prAuthor,
  repo,
  prId,
  prNumber,
  onReplied,
}: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  repo: string;
  /** PR primary key — required for the resolve / unresolve path. */
  prId: number;
  prNumber: number;
  /** Called after a successful reply with a synthesised message so the
   *  parent can patch local state immediately. */
  onReplied: (optimisticReply?: ReviewMessageDto) => void;
}) {
  const [replying, setReplying] = useState(false);
  const [body, setBody] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Collapse resolved threads up-front; the user can toggle them open.
  const [folded, setFolded] = useState<boolean>(thread.resolved === true);
  // Local optimistic mirror so the pill + button text flip immediately
  // on click. Falls back to the prop when GraphQL hasn't given us a
  // value yet. Sync with thread.resolved on every render so a fresh
  // detail fetch overrides our local state.
  const [resolvedLocal, setResolvedLocal] = useState<boolean | null>(thread.resolved ?? null);
  const [resolving, setResolving] = useState(false);
  useEffect(() => {
    setResolvedLocal(thread.resolved ?? null);
  }, [thread.resolved]);

  const submit = async () => {
    const trimmed = body.trim();
    if (!trimmed) return;
    setPending(true); setError(null);
    try {
      await window.bridge.replyToReviewThread(repo, prNumber, thread.rootGithubId, trimmed);
      setBody('');
      setReplying(false);
      // Hand a synthesised optimistic message back so the parent can
      // append it to local state right away. The full-detail refetch
      // (also kicked off by onReplied) reconciles the temp id with
      // GitHub's real one in the background.
      const profile = getCached<UserProfileDto>('home:profile') ?? null;
      const optimistic: ReviewMessageDto = {
        githubId: -Date.now(),
        author: profile?.login ?? null,
        body: trimmed,
        createdAt: new Date().toISOString(),
        reactions: null,
        reviewId: null,
        authorAssociation: null,
      };
      onReplied(optimistic);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending(false);
    }
  };

  // Single-line vs. multi-line label. GitHub returns start_line +
  // start_side on multi-line threads (V27 surfaces them); when both are
  // set and the start differs from the end, we render the range. Falls
  // through to "Comment" when line is missing (rare, only on stale
  // threads).
  const lineLabel = thread.line == null
    ? 'Comment'
    : (thread.startLine != null && thread.startLine !== thread.line
      ? `Comment on lines ${(thread.startSide ?? thread.side) === 'LEFT' ? 'L' : 'R'}${thread.startLine} to ${thread.side === 'LEFT' ? 'L' : 'R'}${thread.line}`
      : `Comment on line ${(thread.side === 'LEFT' ? 'L' : 'R')}${thread.line}`);

  return (
    <div
      className={`diff-thread${thread.resolved === true ? ' diff-thread--resolved' : ''}${thread.outdated ? ' diff-thread--outdated' : ''}`}
    >
      <header className="diff-thread__header">
        <button
          type="button"
          className="diff-thread__fold"
          onClick={() => setFolded(f => !f)}
          aria-expanded={!folded}
          title={folded ? 'Expand thread' : 'Collapse thread'}
        >
          <span aria-hidden="true">{folded ? '▸' : '▾'}</span>
        </button>
        <span className="diff-thread__loc">{lineLabel}</span>
        {folded && (
          <span className="diff-thread__head-summary">
            · {thread.messages.length} comment{thread.messages.length === 1 ? '' : 's'}
          </span>
        )}
        {thread.outdated && (
          <span className="diff-thread__pill diff-thread__pill--outdated" title="The line this thread anchors to no longer exists in the current diff.">Outdated</span>
        )}
        {resolvedLocal === true && (
          <span className="diff-thread__pill diff-thread__pill--resolved">Resolved</span>
        )}
        <div className="diff-thread__head-pills">
          {/* Resolve / Unresolve toggle. Hidden when GraphQL hasn't
              populated the resolved flag yet (resolved == null), since
              the mutation needs the GraphQL node id which arrives in
              the same fetch. */}
          {resolvedLocal != null && (
            <button
              type="button"
              className={`diff-thread__resolve-btn${resolvedLocal ? ' diff-thread__resolve-btn--unresolve' : ''}`}
              onClick={async () => {
                if (resolving) return;
                const next = !resolvedLocal;
                setResolving(true);
                setResolvedLocal(next);
                try {
                  await window.bridge.setReviewThreadResolved(repo, prId, thread.rootGithubId, next);
                } catch (e) {
                  setResolvedLocal(!next);
                  setError(e instanceof Error ? e.message : String(e));
                } finally {
                  setResolving(false);
                }
              }}
              disabled={resolving}
              title={resolvedLocal ? 'Mark this conversation unresolved' : 'Mark this conversation resolved'}
            >
              {resolving ? '…' : resolvedLocal ? 'Unresolve' : 'Resolve'}
            </button>
          )}
        </div>
      </header>

      {!folded && (
        <>
          {thread.messages.map(msg => (
            <article key={msg.githubId} className="diff-thread__msg">
              <Avatar login={msg.author ?? ''} size={28} className="diff-thread__msg-avatar" />
              <div className="diff-thread__msg-body">
                <header className="diff-thread__msg-head">
                  {msg.author && <span className="diff-thread__msg-author">{msg.author}</span>}
                  {prAuthor === msg.author && (
                    <span className="diff-thread__msg-role">Author</span>
                  )}
                  {msg.createdAt && (
                    <span className="diff-thread__msg-time">{formatRelative(msg.createdAt)}</span>
                  )}
                </header>
                {msg.body && (
                  <div
                    className="diff-thread__msg-text"
                    dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.body) }}
                  />
                )}
                <ThreadReactions reactions={msg.reactions} />
              </div>
            </article>
          ))}

          {replying ? (
            <div className="diff-thread__reply">
              <MarkdownComposer
                value={body}
                onChange={setBody}
                placeholder="Reply to this thread — markdown supported."
                rows={2}
                disabled={pending}
                autoFocus
                textareaClassName="diff-thread__reply-input"
              />
              <div className="diff-thread__reply-actions">
                <button
                  type="button"
                  className="button button--primary"
                  onClick={submit}
                  disabled={pending || !body.trim()}
                >
                  {pending ? 'Sending…' : 'Reply'}
                </button>
                <PolishButtons
                  value={body}
                  onChange={setBody}
                  onError={setError}
                  disabled={pending}
                />
                <button
                  type="button"
                  className="pr-comment-box__cancel"
                  onClick={() => { setReplying(false); setBody(''); setError(null); }}
                  disabled={pending}
                >
                  Cancel
                </button>
              </div>
              {error && <div className="diff-thread__reply-error">{error}</div>}
            </div>
          ) : (
            <button
              type="button"
              className="diff-thread__reply-stub"
              onClick={() => setReplying(true)}
            >
              Write a reply…
            </button>
          )}
        </>
      )}
    </div>
  );
}

/**
 * Renders every changed file in the PR concatenated into one scroll
 * container — same UX as GitHub's "Files changed" tab. The active file in
 * the file-list rail is kept in sync with whatever is roughly under the
 * scroll-area's top edge, and clicking a file in the rail scrolls smoothly
 * to its header.
 */
function ContinuousFilesPane({
  files,
  selectedPath,
  onActiveFileChange,
  aiComments,
  draftId,
  draftPublished,
  onDraftUpdated,
  prId,
  repo,
  prNumber,
  headSha,
  threads,
  onThreadReplied,
  prAuthor,
}: {
  files: DiffFileDto[];
  selectedPath: string | null;
  onActiveFileChange: (path: string) => void;
  aiComments: AiReviewCommentDto[];
  draftId: number | null;
  draftPublished: boolean;
  onDraftUpdated: (draft: AiReviewDraftDto) => void;
  prId: number;
  repo: string;
  prNumber: number;
  headSha: string | null;
  threads: ReviewThreadDto[];
  /** Callback invoked after a successful reply / new-comment.
   *  - `rootGithubId` + `optimisticReply` set ⇒ append the message to
   *    that thread immediately.
   *  - both unset (new top-level inline comment) ⇒ force-refresh detail. */
  onThreadReplied: (rootGithubId?: number, optimisticReply?: ReviewMessageDto) => void;
  prAuthor: string | null;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const sectionsRef = useRef<Map<string, HTMLElement>>(new Map());
  const lastSyncedFromClick = useRef<string | null>(null);
  // Window during which handleScroll suppresses its active-file sync.
  // Set when we kick off a programmatic scroll so intermediate files
  // passing under the top band don't bounce setSelectedPath through
  // every file en route — those re-renders interrupted the smooth
  // animation and the scroll would land short of the clicked file
  // (the "moves only ~8 files at a time" bug).
  const suppressActiveSyncUntil = useRef(0);

  // Smoothly scroll to the section when the user picks a file in the rail.
  // We track `lastSyncedFromClick` so the scroll handler doesn't fight the
  // animation by re-setting the selection every frame.
  useEffect(() => {
    if (!selectedPath) return;
    if (lastSyncedFromClick.current === selectedPath) return;
    const el = sectionsRef.current.get(selectedPath);
    if (!el) return;
    lastSyncedFromClick.current = selectedPath;
    // Cover any reasonable smooth-scroll duration. 1500ms is generous
    // enough for cross-document jumps; once it elapses the active-
    // file detector resumes for genuine user scrolling.
    suppressActiveSyncUntil.current = Date.now() + 1500;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [selectedPath]);

  // Pick the file whose header is closest to the top of the scroll area.
  const handleScroll = () => {
    if (Date.now() < suppressActiveSyncUntil.current) return;
    const scroller = scrollRef.current;
    if (!scroller) return;
    const scrollerTop = scroller.getBoundingClientRect().top;
    let activePath: string | null = null;
    let bestOffset = Number.NEGATIVE_INFINITY;
    sectionsRef.current.forEach((el, path) => {
      const offset = el.getBoundingClientRect().top - scrollerTop;
      // Treat any header within 60px of the top as "the active one" — that
      // band lets the active selection flip slightly before the previous
      // file scrolls fully out of view, which feels more responsive.
      if (offset <= 60 && offset > bestOffset) {
        bestOffset = offset;
        activePath = path;
      }
    });
    if (activePath && activePath !== selectedPath) {
      lastSyncedFromClick.current = activePath;
      onActiveFileChange(activePath);
    }
  };

  return (
    <div
      ref={scrollRef}
      className="diff-viewer__pane-scroll diff-viewer__pane-scroll--continuous"
      onScroll={handleScroll}
    >
      {files.map((file) => (
        <section
          key={file.filename}
          ref={(el) => {
            if (el) sectionsRef.current.set(file.filename, el);
            else sectionsRef.current.delete(file.filename);
          }}
          className="diff-file-section"
          data-path={file.filename}
          // Anchor used by the AI sidebar's jump fallback when a
          // finding's line doesn't match any rendered diff row (e.g.
          // a multi-commit diff where the finding lives outside the
          // current hunks). Lets the fallback at least scroll the user
          // to the right file instead of the click looking inert.
          data-file-anchor={file.filename}
        >
          <header className="diff-file-section__header">
            <span className="diff-viewer__pane-filename">{file.filename}</span>
            <span className={`diff-viewer__pane-status diff-viewer__pane-status--${file.status}`}>{file.status}</span>
            <span className="diff-file-section__stats">
              <span className="diff-file-row__add">+{file.additions}</span>
              <span className="diff-file-row__del">−{file.deletions}</span>
            </span>
          </header>
          <FileDiff
            file={file}
            comments={aiComments}
            draftId={draftId}
            draftPublished={draftPublished}
            onDraftUpdated={onDraftUpdated}
            prId={prId}
            repo={repo}
            prNumber={prNumber}
            headSha={headSha}
            threads={threads}
            onThreadReplied={onThreadReplied}
            prAuthor={prAuthor}
          />
        </section>
      ))}
    </div>
  );
}

type FileDiffProps = {
  file: DiffFileDto;
  comments: AiReviewCommentDto[];
  draftId: number | null;
  draftPublished: boolean;
  onDraftUpdated: (draft: AiReviewDraftDto) => void;
  /** PR id — required by the staged-review path to find-or-create the
   *  active draft. */
  prId: number;
  /** PR repo + number + head SHA — required to post inline review comments
   *  via the GitHub pulls/{n}/comments endpoint. When headSha is null
   *  (commits not yet loaded) the inline composer hides. */
  repo: string;
  prNumber: number;
  headSha: string | null;
  /** Existing per-line review threads on this PR. Rendered inline below
   *  the matching diff row so reviewers see prior conversation in context. */
  threads: ReviewThreadDto[];
  /** Refresh-callback invoked after a successful reply or new inline comment.
   *  - `rootGithubId` + `optimisticReply` set ⇒ append the message to
   *    that thread immediately.
   *  - both unset (new top-level comment) ⇒ force-refresh detail. */
  onThreadReplied: (rootGithubId?: number, optimisticReply?: ReviewMessageDto) => void;
  prAuthor: string | null;
};

/** A single-line composer slot is `{ line, side }` (legacy shape).
 *  A multi-line range is `{ line, side, startLine, startSide }` where
 *  startLine ≤ line. The composer always pops below the END row
 *  (line/side), matching github.com's UX. */
type ComposerSlot =
  | null
  | {
    line: number;
    side: 'LEFT' | 'RIGHT';
    startLine?: number;
    startSide?: 'LEFT' | 'RIGHT';
  };

const ArrowUpIcon = () => (
  <svg className="diff-expand-btn__svg" viewBox="0 0 12 12" aria-hidden="true">
    <path d="M6 2 L10 7 L7.5 7 L7.5 10 L4.5 10 L4.5 7 L2 7 Z" />
  </svg>
);
const ArrowDownIcon = () => (
  <svg className="diff-expand-btn__svg" viewBox="0 0 12 12" aria-hidden="true">
    <path d="M6 10 L2 5 L4.5 5 L4.5 2 L7.5 2 L7.5 5 L10 5 Z" />
  </svg>
);
const ArrowUpDownIcon = () => (
  <svg className="diff-expand-btn__svg" viewBox="0 0 12 12" aria-hidden="true">
    <path d="M6 0.5 L10 4.5 L7.5 4.5 L7.5 7.5 L10 7.5 L6 11.5 L2 7.5 L4.5 7.5 L4.5 4.5 L2 4.5 Z" />
  </svg>
);

/** Expand-collapsed-code controls that sit in the gutter of a hunk
 *  header (or its own row for the after-last-hunk gap). The two button
 *  variants mirror docs/mockups/v2/codereview/expand.png:
 *
 *  - Top-of-file gap (no hunk above): single ↕ button — only "up"
 *    direction makes sense (loading lines toward the bottom of the gap,
 *    just above this hunk header).
 *  - Bottom-of-file gap (no hunk below): single ↕ button — only "down"
 *    makes sense (loading lines after the last hunk's content).
 *  - Middle gap: stacked control with an up chevron, a non-interactive
 *    "collapsed content" decoration in the middle, and a down chevron at
 *    the bottom. Either chevron loads the next 20 lines in that
 *    direction.
 */
function ExpandControls({
  gap,
  loaded,
  onClick,
  upBusy,
  downBusy,
}: {
  gap: Gap;
  loaded: LoadedGap;
  onClick: (gap: Gap, dir: 'up' | 'down') => void;
  upBusy: boolean;
  downBusy: boolean;
}) {
  const showUp = canExpandUp(gap, loaded);
  const showDown = canExpandDown(gap, loaded);
  // Single-button style for top/bottom gaps: only one direction is
  // meaningful, so we render one big affordance instead of the split.
  if (gap.isTop || gap.isBottom) {
    const dir: 'up' | 'down' = gap.isTop ? 'up' : 'down';
    const busy = dir === 'up' ? upBusy : downBusy;
    const enabled = dir === 'up' ? showUp : showDown;
    return (
      <button
        type="button"
        className="diff-expand-btn diff-expand-btn--single"
        onClick={() => onClick(gap, dir)}
        disabled={!enabled || busy}
        title={`Expand ${EXPAND_INCREMENT} more lines`}
        aria-label={`Expand ${EXPAND_INCREMENT} more lines`}
      >
        <ArrowUpDownIcon />
      </button>
    );
  }
  // Middle gap: github.com-style split. Dotted strip on top hints at
  // hidden lines; the two chevrons sit side-by-side below — up on the
  // left, down on the right. See docs/mockups/issue/code-diff/g-expand-button.png.
  return (
    <div className="diff-expand-split">
      <span className="diff-expand-split__divider" aria-hidden="true">
        <span /><span /><span /><span />
      </span>
      <div className="diff-expand-split__row">
        <button
          type="button"
          className="diff-expand-btn diff-expand-btn--up"
          onClick={() => onClick(gap, 'up')}
          disabled={!showUp || upBusy}
          title={`Expand ${EXPAND_INCREMENT} more lines up`}
          aria-label={`Expand ${EXPAND_INCREMENT} more lines up`}
        >
          <ArrowUpIcon />
        </button>
        <button
          type="button"
          className="diff-expand-btn diff-expand-btn--down"
          onClick={() => onClick(gap, 'down')}
          disabled={!showDown || downBusy}
          title={`Expand ${EXPAND_INCREMENT} more lines down`}
          aria-label={`Expand ${EXPAND_INCREMENT} more lines down`}
        >
          <ArrowDownIcon />
        </button>
      </div>
    </div>
  );
}

function FileDiff({ file, comments, draftId, draftPublished, onDraftUpdated, prId, repo, prNumber, headSha, threads, onThreadReplied, prAuthor }: FileDiffProps) {
  const hunks = useMemo(() => parseUnifiedDiff(file.patch), [file.patch]);
  // Expanded gap state — Map<gapIndex, Map<newLine, content>>. Gap g is
  // the region BEFORE hunks[g]; hunks.length is the after-last gap.
  // Cleared whenever the underlying patch changes (file or commit
  // selection swap) — re-expansion is cheap and the in-flight state
  // would be confusing to keep around.
  const [expanded, setExpanded] = useState<Map<number, LoadedGap>>(new Map());
  const [expandLoading, setExpandLoading] = useState<Set<string>>(new Set());
  const [expandError, setExpandError] = useState<string | null>(null);
  useEffect(() => {
    setExpanded(new Map());
    setExpandError(null);
  }, [file.patch, file.filename]);

  const onExpandClick = async (gap: Gap, direction: 'up' | 'down') => {
    if (!headSha) return;
    const loaded = expanded.get(gap.index) ?? new Map<number, string>();
    const range = computeFetchRange(gap, loaded, direction);
    if (!range) return;
    const key = `${gap.index}:${direction}`;
    if (expandLoading.has(key)) return;
    setExpandLoading(s => new Set(s).add(key));
    setExpandError(null);
    try {
      const blob = await window.bridge.fetchFileBlob(repo, file.filename, headSha);
      // Slice the requested 1-based [from..to] window. Lines past EOF
      // are skipped (bottom gap "finishes" naturally by returning fewer
      // than requested).
      const next = new Map(loaded);
      for (let n = range.from; n <= range.to; n++) {
        if (n - 1 < blob.lines.length) next.set(n, blob.lines[n - 1]);
      }
      setExpanded(prev => {
        const out = new Map(prev);
        out.set(gap.index, next);
        return out;
      });
    }
    catch (e) {
      setExpandError(e instanceof Error ? e.message : 'Expand failed.');
    }
    finally {
      setExpandLoading(s => {
        const out = new Set(s);
        out.delete(key);
        return out;
      });
    }
  };
  // Index existing threads by side+line so we can render them under the
  // matching diff row. Threads on lines that aren't visible in the current
  // diff (e.g. comment on a line that hasn't been touched here, or a stale
  // thread from a force-pushed commit) silently fall through.
  const threadsByAnchor = useMemo(() => {
    const map = new Map<string, ReviewThreadDto[]>();
    for (const t of threads) {
      if (t.filePath !== file.filename || t.line == null) continue;
      const side: 'LEFT' | 'RIGHT' = t.side === 'LEFT' ? 'LEFT' : 'RIGHT';
      const key = `${side}:${t.line}`;
      const list = map.get(key) ?? [];
      list.push(t);
      map.set(key, list);
    }
    return map;
  }, [threads, file.filename]);
  /** Threads on this file whose anchor line no longer exists in the
   *  current diff (GitHub returns position=null after a force-push or
   *  rebase). They have nowhere to slot inline, so we surface them in
   *  a collapsible section under the file header — see docs/mockups/
   *  v2/detail style for the user's "show outdated comments" ask. */
  const outdatedThreads = useMemo(
    () => threads.filter(t => t.filePath === file.filename && (t.outdated || t.line == null)),
    [threads, file.filename],
  );
  const [showOutdated, setShowOutdated] = useState(false);
  const [composerSlot, setComposerSlot] = useState<ComposerSlot>(null);
  const [composerBody, setComposerBody] = useState('');
  const [composerPending, setComposerPending] = useState(false);
  const [composerError, setComposerError] = useState<string | null>(null);
  const [postedKeys, setPostedKeys] = useState<Set<string>>(new Set());
  // Live drag-select range. Distinct from composerSlot so the range
  // preview can highlight rows during the drag without prematurely
  // mounting the composer below them. Mirror state in a ref so the
  // window-level pointerup listener (registered once) sees the latest
  // value without re-binding on every drag.
  const [dragRange, setDragRange] = useState<{ side: 'LEFT' | 'RIGHT'; start: number; end: number } | null>(null);
  const dragRangeRef = useRef<{ side: 'LEFT' | 'RIGHT'; start: number; end: number } | null>(null);
  // Set on pointerup when a real (multi-row) drag committed. The
  // synthetic click that browsers fire after the pointer sequence
  // would otherwise re-fire handleRowClick and reset to a single-line
  // composer; this flag swallows that one click.
  const suppressNextClickRef = useRef(false);

  const closeComposer = () => {
    setComposerSlot(null);
    setComposerBody('');
    setComposerError(null);
  };

  const submitComposer = async () => {
    if (!composerSlot || !headSha) return;
    const trimmed = composerBody.trim();
    if (!trimmed) return;
    setComposerPending(true);
    setComposerError(null);
    try {
      await window.bridge.createInlineReviewComment(
        repo,
        prNumber,
        trimmed,
        file.filename,
        composerSlot.line,
        composerSlot.side,
        headSha,
        // Multi-line range: pass start_line + start_side when present.
        // The bridge / backend strip them when startLine === line, so
        // single-line comments produce identical payloads to before.
        composerSlot.startLine ?? null,
        composerSlot.startSide ?? null,
      );
      const key = `${composerSlot.side}:${composerSlot.line}`;
      setPostedKeys(prev => {
        const next = new Set(prev);
        next.add(key);
        return next;
      });
      closeComposer();
      onThreadReplied();
    } catch (e) {
      setComposerError(e instanceof Error ? e.message : String(e));
    } finally {
      setComposerPending(false);
    }
  };

  /** Stages the composer body into the active review draft instead of
   *  posting a single comment. The refreshed draft flows back through
   *  onDraftUpdated so the floating review tray + inline rail repaint. */
  const stageComposer = async () => {
    if (!composerSlot || !headSha) return;
    const trimmed = composerBody.trim();
    if (!trimmed) return;
    setComposerPending(true);
    setComposerError(null);
    try {
      const updated = await window.bridge.stageReviewComment({
        prId,
        repo,
        number: prNumber,
        headSha,
        filePath: file.filename,
        line: composerSlot.line,
        side: composerSlot.side,
        startLine: composerSlot.startLine ?? null,
        startSide: composerSlot.startSide ?? null,
        body: trimmed,
      });
      onDraftUpdated(updated);
      closeComposer();
    } catch (e) {
      setComposerError(e instanceof Error ? e.message : String(e));
    } finally {
      setComposerPending(false);
    }
  };

  /** Plain click on a row → single-line composer. Shift-click on a
   *  second row while a composer is already open on the same side →
   *  extends the range. The drag-select path (see onRowPointerDown
   *  below) covers the click-and-drag case; this stays as a keyboard-
   *  friendly fallback and to support quick "click row" usage. */
  const handleRowClick = (
    e: React.MouseEvent,
    side: 'LEFT' | 'RIGHT',
    line: number,
  ) => {
    // Swallow the synthetic click that follows a real (multi-row)
    // drag-select — pointerup already opened the composer; this
    // click would otherwise reset to a single-line composer here.
    if (suppressNextClickRef.current) {
      suppressNextClickRef.current = false;
      return;
    }
    if (e.shiftKey && composerSlot && composerSlot.side === side) {
      const anchor = composerSlot.line;
      const start = Math.min(anchor, line);
      const end = Math.max(anchor, line);
      setComposerSlot(start === end
        ? { side, line: end }
        : { side, line: end, startLine: start, startSide: side });
      return;
    }
    setComposerSlot({ side, line });
  };

  /** Begin a drag-select. Records the starting (side, line) and lets
   *  pointerenter on subsequent rows extend it. */
  const onRowPointerDown = (
    _e: React.PointerEvent,
    side: 'LEFT' | 'RIGHT',
    line: number,
  ) => {
    const range = { side, start: line, end: line };
    dragRangeRef.current = range;
    setDragRange(range);
  };

  /** Mouse moved over a different row while still dragging — extend
   *  the range. Same-side only; crossing into the other side is a
   *  no-op (matches github.com — the API doesn't allow cross-side
   *  ranges either). */
  const onRowPointerEnter = (
    side: 'LEFT' | 'RIGHT',
    line: number,
  ) => {
    const cur = dragRangeRef.current;
    if (!cur || cur.side !== side) return;
    if (cur.end === line) return;
    const next = { ...cur, end: line };
    dragRangeRef.current = next;
    setDragRange(next);
  };

  // Window-level pointerup commits the drag. Registered once; reads
  // dragRangeRef so it always sees the latest value.
  useEffect(() => {
    const onUp = () => {
      const drag = dragRangeRef.current;
      if (!drag) return;
      dragRangeRef.current = null;
      setDragRange(null);
      const start = Math.min(drag.start, drag.end);
      const end = Math.max(drag.start, drag.end);
      if (end !== start) {
        // Real drag — swallow the click that follows on the
        // pointerdown row so it doesn't reset to single-line.
        suppressNextClickRef.current = true;
        setComposerSlot({
          side: drag.side,
          line: end,
          startLine: start,
          startSide: drag.side,
        });
      } else {
        // Single-row "click" — let handleRowClick (via the synthetic
        // click) handle it normally so shift-click + plain-click stay
        // consistent. No suppression flag needed.
        // (Composer will open via the click handler.)
      }
    };
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, []);

  /** True iff (side, line) sits within either the live drag range or
   *  the committed composer's range. Drives the amber highlight on
   *  rows during selection AND on the saved composer's range. */
  const isInRange = (side: 'LEFT' | 'RIGHT', line: number): boolean => {
    if (dragRange && dragRange.side === side) {
      const lo = Math.min(dragRange.start, dragRange.end);
      const hi = Math.max(dragRange.start, dragRange.end);
      return line >= lo && line <= hi;
    }
    if (!composerSlot || composerSlot.side !== side) return false;
    if (composerSlot.startLine == null) return composerSlot.line === line;
    return line >= composerSlot.startLine && line <= composerSlot.line;
  };
  // Group AI findings by the new-file line they anchor to. The prompt
  // instructs the model to emit "line number in the NEW file after the
  // patch is applied", so matching against row.newLine is the contract.
  // Keyed by "side:lineNumber" — AI findings usually anchor RIGHT,
  // but human-staged comments may be on LEFT (a deletion). The lookup
  // site below uses the same key shape so the LEFT-anchored comments
  // slot under deletion rows.
  const findingsByLine = useMemo(() => {
    const map = new Map<string, AiReviewCommentDto[]>();
    for (const c of comments) {
      if (c.filePath !== file.filename) continue;
      const key = `${c.side ?? 'RIGHT'}:${c.lineNumber}`;
      const list = map.get(key) ?? [];
      list.push(c);
      map.set(key, list);
    }
    return map;
  }, [comments, file.filename]);

  // Findings for this file whose (side, line) doesn't match any
  // rendered diff row — e.g. a stale AI run that referenced a line
  // outside the current hunks, or a single-commit view where the
  // finding's line lives in a different commit. Used to be silently
  // dropped (only visible in the sidebar); now surfaced as an
  // "orphan" block above the hunks so the user can read the comment
  // at all. The visible-line set is built from the parsed diff.
  const visibleLineKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const hunk of hunks) {
      for (const row of hunk.rows) {
        if (row.kind === 'del' && row.oldLine != null) {
          keys.add(`LEFT:${row.oldLine}`);
        }
        else if ((row.kind === 'add' || row.kind === 'context') && row.newLine != null) {
          keys.add(`RIGHT:${row.newLine}`);
        }
      }
    }
    // Promote any AI finding whose line is in an expanded gap. Expanded
    // lines are always unchanged context, so they live on the RIGHT
    // (new) side; their old-side equivalent is also valid as context,
    // so register both keys.
    for (const [gapIndex, loaded] of expanded) {
      const gap = computeGap(hunks, gapIndex);
      if (!gap) continue;
      for (const newLine of loaded.keys()) {
        keys.add(`RIGHT:${newLine}`);
        keys.add(`LEFT:${newLine + gap.oldOffset}`);
      }
    }
    return keys;
  }, [hunks, expanded]);
  const orphanFindings = useMemo(
    () => comments.filter(c =>
      c.filePath === file.filename
      && !visibleLineKeys.has(`${c.side ?? 'RIGHT'}:${c.lineNumber}`)),
    [comments, file.filename, visibleLineKeys],
  );

  if (file.patch === null || file.patch === undefined) {
    return (
      <div className="diff-file-empty">
        <span className="diff-file-empty__label">
          {file.status === 'renamed' ? 'File renamed without content changes.' : 'No diff available (binary file or large diff).'}
        </span>
      </div>
    );
  }
  if (hunks.length === 0) {
    return <div className="diff-file-empty">Empty diff.</div>;
  }
  return (
    <div className="diff-file">
      {/* Orphan AI findings — comments whose (side, line) doesn't match
          any rendered diff row. Used to silently disappear from the
          diff page and only show in the sidebar; surface them here so
          a click on the sidebar card has somewhere to land and the
          comment is at least readable. */}
      {orphanFindings.length > 0 && (
        <div className="diff-orphan-findings">
          <div className="diff-orphan-findings__label">
            {orphanFindings.length} comment{orphanFindings.length === 1 ? '' : 's'} on lines outside the visible diff
          </div>
          {orphanFindings.map(c => (
            <InlineFinding
              key={c.id}
              comment={c}
              draftId={draftId}
              draftPublished={draftPublished}
              onDraftUpdated={onDraftUpdated}
            />
          ))}
        </div>
      )}
      {/* Outdated comments — threads anchored to a line that no longer
          exists in the current diff. Surfaced as a collapsible bar so
          they don't get silently dropped (which they were until now).
          See the user's "outdated comments" ask. */}
      {outdatedThreads.length > 0 && (
        <div className={`diff-outdated${showOutdated ? ' diff-outdated--open' : ''}`}>
          <button
            type="button"
            className="diff-outdated__toggle"
            onClick={() => setShowOutdated(v => !v)}
            aria-expanded={showOutdated}
          >
            <span className="diff-outdated__chevron" aria-hidden="true">{showOutdated ? '▾' : '›'}</span>
            <span className="diff-outdated__label">
              {outdatedThreads.length} outdated comment{outdatedThreads.length === 1 ? '' : 's'}
            </span>
            <span className="diff-outdated__hint">
              anchored to lines that no longer exist in the current diff
            </span>
          </button>
          {showOutdated && (
            <div className="diff-outdated__list">
              {outdatedThreads.map(thread => (
                <InlineExistingThread
                  key={thread.rootGithubId}
                  thread={thread}
                  prAuthor={prAuthor}
                  repo={repo}
                  prId={prId}
                  prNumber={prNumber}
                  onReplied={(msg) => onThreadReplied(thread.rootGithubId, msg)}
                />
              ))}
            </div>
          )}
        </div>
      )}
      {expandError && (
        <div className="diff-expand-error" role="alert">{expandError}</div>
      )}
      {hunks.map((hunk, hi) => {
        const gapAbove = computeGap(hunks, hi);
        const loadedAbove = gapAbove ? (expanded.get(hi) ?? new Map<number, string>()) : new Map<number, string>();
        // Expanded rows render in newLine-ascending order between the
        // previous hunk's last row and this hunk's header. They behave
        // as plain context rows for finding-anchoring purposes.
        const expandedRows = gapAbove
          ? [...loadedAbove.entries()].sort((a, b) => a[0] - b[0])
          : [];
        return (
          <div key={hi} className="diff-hunk">
            {expandedRows.map(([newLine, content]) => {
              const oldLine = newLine + gapAbove!.oldOffset;
              const inline = findingsByLine.get(`RIGHT:${newLine}`);
              return (
                <div key={`exp-${newLine}`}>
                  <div className="diff-row diff-row--context diff-row--expanded">
                    <span className="diff-row__gutter">{oldLine}</span>
                    <span className="diff-row__gutter">{newLine}</span>
                    <span className="diff-row__content">
                      <span className="diff-row__sigil"> </span>
                      {content}
                    </span>
                  </div>
                  {inline?.map(c => (
                    <InlineFinding
                      key={c.id}
                      comment={c}
                      draftId={draftId}
                      draftPublished={draftPublished}
                      onDraftUpdated={onDraftUpdated}
                    />
                  ))}
                  {threadsByAnchor.get(`RIGHT:${newLine}`)?.map(thread => (
                    <InlineExistingThread
                      key={thread.rootGithubId}
                      thread={thread}
                      prAuthor={prAuthor}
                      repo={repo}
                      prId={prId}
                      prNumber={prNumber}
                      onReplied={(msg) => onThreadReplied(thread.rootGithubId, msg)}
                    />
                  ))}
                </div>
              );
            })}
            {hunk.rows.map((row, ri) => {
            if (row.kind === 'hunk-header') {
              const showExpand = gapAbove != null && !isGapFullyLoaded(gapAbove, loadedAbove);
              const upBusy = expandLoading.has(`${hi}:up`);
              const downBusy = expandLoading.has(`${hi}:down`);
              return (
                <div key={ri} className="diff-row diff-row--hunk-header">
                  {showExpand ? (
                    <span className="diff-row__expand-cell">
                      <ExpandControls
                        gap={gapAbove!}
                        loaded={loadedAbove}
                        onClick={onExpandClick}
                        upBusy={upBusy}
                        downBusy={downBusy}
                      />
                    </span>
                  ) : (
                    <>
                      <span className="diff-row__gutter" />
                      <span className="diff-row__gutter" />
                    </>
                  )}
                  <span className="diff-row__content">{hunk.header}</span>
                </div>
              );
            }
            // Side key matches anchorSide below — RIGHT for additions /
            // context, LEFT for deletions. AI findings still slot under
            // the right side (they always reference the new file).
            const lookupSide: 'LEFT' | 'RIGHT' = row.kind === 'del' ? 'LEFT' : 'RIGHT';
            const lookupLine = row.kind === 'del' ? row.oldLine : row.newLine;
            const inline = lookupLine != null
              ? findingsByLine.get(`${lookupSide}:${lookupLine}`)
              : undefined;
            const hasFinding = !!inline && inline.length > 0;
            // The line + side this row anchors to. Deletions exist only on
            // the LEFT side; additions and context default to RIGHT (the
            // new file). Hunk headers are filtered above.
            const anchorSide: 'LEFT' | 'RIGHT' = row.kind === 'del' ? 'LEFT' : 'RIGHT';
            const anchorLine = row.kind === 'del' ? row.oldLine : row.newLine;
            // The composer sits under the END row of a (possibly
            // multi-line) range. We never render a second composer for
            // the start row of the same range — there's only one
            // composer in flight per file at a time.
            const composerHere = !!composerSlot
                && composerSlot.side === anchorSide
                && composerSlot.line === anchorLine;
            const inRange = anchorLine != null && isInRange(anchorSide, anchorLine);
            const justPostedHere = anchorLine != null && postedKeys.has(`${anchorSide}:${anchorLine}`);
            const canComment = headSha != null && anchorLine != null;
            return (
              <div key={ri}>
                <div
                  className={
                    `diff-row diff-row--${row.kind}`
                    + (hasFinding ? ' diff-row--has-finding' : '')
                    + (canComment ? ' diff-row--commentable' : '')
                    + (inRange ? ' diff-row--in-range' : '')
                  }
                  // Stable anchor used by the AI sidebar's "jump to line"
                  // button: file.filename + the new-side line number, since
                  // AI findings always reference the new file.
                  data-anchor={anchorLine != null ? `${file.filename}:${anchorSide}:${anchorLine}` : undefined}
                  onClick={canComment
                    ? (e) => handleRowClick(e, anchorSide, anchorLine!)
                    : undefined}
                  onPointerDown={canComment
                    ? (e) => onRowPointerDown(e, anchorSide, anchorLine!)
                    : undefined}
                  onPointerEnter={canComment
                    ? () => onRowPointerEnter(anchorSide, anchorLine!)
                    : undefined}
                  role={canComment ? 'button' : undefined}
                  tabIndex={canComment ? 0 : undefined}
                  title={canComment
                    ? 'Click to comment, or click-and-drag across rows to comment on a range. Shift-click also extends the range.'
                    : undefined}
                >
                  <span className="diff-row__gutter">{row.oldLine ?? ''}</span>
                  <span className="diff-row__gutter">
                    {row.newLine ?? ''}
                    {canComment && <span className="diff-row__add-comment" aria-hidden="true">+</span>}
                  </span>
                  <span className="diff-row__content">
                    <span className="diff-row__sigil">
                      {row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}
                    </span>
                    {row.content}
                  </span>
                </div>
                {inline?.map(c => (
                  <InlineFinding
                    key={c.id}
                    comment={c}
                    draftId={draftId}
                    draftPublished={draftPublished}
                    onDraftUpdated={onDraftUpdated}
                  />
                ))}
                {anchorLine != null && threadsByAnchor.get(`${anchorSide}:${anchorLine}`)?.map(thread => (
                  <InlineExistingThread
                    key={thread.rootGithubId}
                    thread={thread}
                    prAuthor={prAuthor}
                    repo={repo}
                    prId={prId}
                    prNumber={prNumber}
                    onReplied={(msg) => onThreadReplied(thread.rootGithubId, msg)}
                  />
                ))}
                {composerHere && (
                  <div className="diff-inline-composer">
                    {/* Header — single line vs. multi-line range. Mirrors
                        github.com's "Comment on lines L455 to R467". */}
                    <div className="diff-inline-composer__header">
                      {composerSlot!.startLine != null && composerSlot!.startLine !== composerSlot!.line
                        ? `Adding a comment on lines ${(composerSlot!.startSide ?? composerSlot!.side) === 'LEFT' ? 'L' : 'R'}${composerSlot!.startLine} to ${composerSlot!.side === 'LEFT' ? 'L' : 'R'}${composerSlot!.line}`
                        : `Adding a comment on line ${composerSlot!.side === 'LEFT' ? 'L' : 'R'}${composerSlot!.line}`}
                      <span className="diff-inline-composer__hint">
                        Shift-click another line to extend the range.
                      </span>
                    </div>
                    <MarkdownComposer
                      value={composerBody}
                      onChange={setComposerBody}
                      placeholder="Leave a comment on this line — markdown supported."
                      rows={3}
                      disabled={composerPending}
                      autoFocus
                      textareaClassName="diff-inline-composer__input"
                    />
                    <div className="diff-inline-composer__actions">
                      <button
                        type="button"
                        className="button button--primary"
                        onClick={stageComposer}
                        disabled={composerPending || !composerBody.trim()}
                        title="Stage this comment into your review — submit them all together when you're done."
                      >
                        {composerPending ? 'Staging…' : 'Start a review'}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        onClick={submitComposer}
                        disabled={composerPending || !composerBody.trim()}
                        title="Post this comment now without starting a batched review."
                      >
                        {composerPending ? 'Posting…' : 'Add single comment'}
                      </button>
                      <PolishButtons
                        value={composerBody}
                        onChange={setComposerBody}
                        onError={setComposerError}
                        disabled={composerPending}
                      />
                      <button
                        type="button"
                        className="pr-comment-box__cancel"
                        onClick={closeComposer}
                        disabled={composerPending}
                      >
                        Cancel
                      </button>
                    </div>
                    {composerError && (
                      <div className="diff-inline-composer__error">{composerError}</div>
                    )}
                  </div>
                )}
                {justPostedHere && !composerHere && (
                  <div className="diff-inline-composer__posted">
                    ✓ Comment posted — open the PR detail to see it threaded.
                  </div>
                )}
              </div>
            );
          })}
        </div>
        );
      })}
      {/* After-last-hunk gap. Bottom expand controls live in their own
          row since there's no hunk header below to attach to. */}
      {(() => {
        const bottomGap = computeGap(hunks, hunks.length);
        if (!bottomGap) return null;
        const loadedBottom = expanded.get(hunks.length) ?? new Map<number, string>();
        const expandedRows = [...loadedBottom.entries()].sort((a, b) => a[0] - b[0]);
        const downBusy = expandLoading.has(`${hunks.length}:down`);
        const upBusy = expandLoading.has(`${hunks.length}:up`);
        const showExpand = canExpandUp(bottomGap, loadedBottom) || canExpandDown(bottomGap, loadedBottom);
        return (
          <div className="diff-hunk">
            {expandedRows.map(([newLine, content]) => {
              const oldLine = newLine + bottomGap.oldOffset;
              const inline = findingsByLine.get(`RIGHT:${newLine}`);
              return (
                <div key={`exp-bot-${newLine}`}>
                  <div className="diff-row diff-row--context diff-row--expanded">
                    <span className="diff-row__gutter">{oldLine}</span>
                    <span className="diff-row__gutter">{newLine}</span>
                    <span className="diff-row__content">
                      <span className="diff-row__sigil"> </span>
                      {content}
                    </span>
                  </div>
                  {inline?.map(c => (
                    <InlineFinding
                      key={c.id}
                      comment={c}
                      draftId={draftId}
                      draftPublished={draftPublished}
                      onDraftUpdated={onDraftUpdated}
                    />
                  ))}
                  {threadsByAnchor.get(`RIGHT:${newLine}`)?.map(thread => (
                    <InlineExistingThread
                      key={thread.rootGithubId}
                      thread={thread}
                      prAuthor={prAuthor}
                      repo={repo}
                      prId={prId}
                      prNumber={prNumber}
                      onReplied={(msg) => onThreadReplied(thread.rootGithubId, msg)}
                    />
                  ))}
                </div>
              );
            })}
            {showExpand && (
              <div className="diff-row diff-row--hunk-header">
                <span className="diff-row__expand-cell">
                  <ExpandControls
                    gap={bottomGap}
                    loaded={loadedBottom}
                    onClick={onExpandClick}
                    upBusy={upBusy}
                    downBusy={downBusy}
                  />
                </span>
                <span className="diff-row__content">Expand to end of file</span>
              </div>
            )}
          </div>
        );
      })()}
    </div>
  );
}

function DiffViewerScreen({ pr, onBack, onApprove, initialCommitSha }: Props) {
  const [files, setFiles] = useState<DiffFileDto[] | null>(null);
  const [commits, setCommits] = useState<PullRequestCommitDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  // Empty set === showing the cumulative PR diff. One sha === single
  // commit's changes. Multiple shas === union of those commits' diffs
  // (each filename is summed for +/- and shows the latest selected
  // commit's patch). Pre-seeded from `initialCommitSha` when the user
  // opened the viewer by clicking a SHA chip in the timeline.
  const [selectedCommits, setSelectedCommits] = useState<Set<string>>(
    () => new Set(initialCommitSha ? [initialCommitSha] : []),
  );
  const [commitDiffLoading, setCommitDiffLoading] = useState(false);
  const [commitsOpen, setCommitsOpen] = useState(false);
  const [collapsedDirs, setCollapsedDirs] = useState<Set<string>>(new Set());
  const [filesWidth, setFilesWidth] = useState<number>(loadWidth);
  const [mode, setMode] = useState<FilesMode>(loadMode);
  const [approveState, setApproveState] = useState<'idle' | 'running' | 'error'>('idle');
  const [approveError, setApproveError] = useState<string | null>(null);
  // Files pane is collapsed by default — most reviewers focus on the
  // diff content first and only pop the file list open to navigate.
  // Once the user explicitly expands it, that choice sticks (we write
  // '0' on expand and '1' on collapse). No prior choice ⇒ collapsed.
  const [filesCollapsed, setFilesCollapsed] = useState<boolean>(() => localStorage.getItem(FILES_COLLAPSED_KEY) !== '0');
  const [aiCollapsed, setAiCollapsed] = useState<boolean>(() => localStorage.getItem(AI_COLLAPSED_KEY) === '1');
  const [aiWidth, setAiWidth] = useState<number>(loadAiWidth);
  const [aiDraft, setAiDraft] = useState<AiReviewDraftDto | null>(null);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [publishState, setPublishState] = useState<'idle' | 'running' | 'error'>('idle');
  const [publishError, setPublishError] = useState<string | null>(null);
  // "Finish your review" panel state. Body is the top-level review body
  // (maps to GitHub's review.body); verdict is which radio is selected;
  // pendingExpanded toggles the per-comment list at the bottom of the
  // panel. Reset whenever the panel opens.
  const [submitBody, setSubmitBody] = useState('');
  const [submitVerdict, setSubmitVerdict] = useState<'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES'>('COMMENT');
  const [pendingExpanded, setPendingExpanded] = useState(true);
  const [discardConfirm, setDiscardConfirm] = useState(false);
  const [discardState, setDiscardState] = useState<'idle' | 'running'>('idle');
  // Existing per-line review threads on the PR — rendered inline under
  // their anchor row so reviewers see prior conversation while reading
  // the diff. Refreshed when the user posts a new inline comment.
  const [reviewThreads, setReviewThreads] = useState<ReviewThreadDto[]>([]);
  const bodyRef = useRef<HTMLDivElement>(null);
  const aiSidebarRef = useRef<AiReviewSidebarHandle>(null);

  // Opening the diff viewer counts as viewing the PR, even if the user closes
  // it again without commenting.
  useEffect(() => {
    void window.bridge.markPrViewed(pr.id).catch(() => { /* best-effort */ });
  }, [pr.id]);

  // Pull existing review threads for inline rendering. Keep this separate
  // from the diff/commits load so a slow detail call doesn't block the
  // main view.
  const refreshDetailFromGitHub = async (): Promise<PullRequestDetailDto> => {
    const detail = await window.bridge.refreshPullRequestDetail(pr.repo, pr.number);
    putCache(pr.id, detail);
    return detail;
  };

  const refreshReviewThreads = async (force = false) => {
    try {
      const detail = force
        ? await refreshDetailFromGitHub()
        : await window.bridge.fetchPullRequestDetail(pr.repo, pr.number);
      putCache(pr.id, detail);
      setReviewThreads(detail.reviewThreads ?? []);
    } catch {
      // Best-effort: an empty list just means no inline-comment markers,
      // which is the same fallback the user has when there are none.
    }
  };
  useEffect(() => { void refreshReviewThreads(); }, [pr.id]);

  const handleApprove = async () => {
    if (!onApprove || approveState === 'running') return;
    setApproveState('running');
    setApproveError(null);
    try {
      await onApprove(pr.id, pr.repo, pr.number);
      try {
        await refreshDetailFromGitHub();
      } catch {
        // Best-effort. The approval landed remotely; the next natural
        // detail refresh will reconcile if this fresh read fails.
      }
      onBack();
    } catch (e) {
      setApproveError(e instanceof Error ? e.message : String(e));
      setApproveState('error');
    }
  };

  const handleRunAi = () => {
    aiSidebarRef.current?.run();
    // Make sure the sidebar is visible so the user sees the running state.
    if (aiCollapsed) {
      setAiCollapsed(false);
      localStorage.setItem(AI_COLLAPSED_KEY, '0');
    }
  };

  const openSubmitPanel = () => {
    // Pre-seed the body with the AI summary so reviewers can start from
    // it (matching what publish() falls back to when body is empty).
    setSubmitBody(aiDraft?.summary ?? '');
    setSubmitVerdict('COMMENT');
    setPendingExpanded(true);
    setDiscardConfirm(false);
    setPublishError(null);
    setSubmitOpen(true);
  };

  const closeSubmitPanel = () => {
    setSubmitOpen(false);
    setDiscardConfirm(false);
  };

  const handlePublish = async (event: ReviewVerdict, body: string | null) => {
    if (publishState === 'running') return;
    if (aiDraft?.status === 'PUBLISHED') return;
    setPublishState('running');
    setPublishError(null);
    try {
      // Always go through the PR-keyed publish path so an Approve /
      // Comment with no staged inline comments still works (backend
      // finds-or-creates the active draft).
      const headSha = commits && commits.length > 0
        ? commits[commits.length - 1].sha
        : (aiDraft?.headSha ?? null);
      const updated = await window.bridge.publishReviewForPr({
        prId: pr.id,
        repo: pr.repo,
        number: pr.number,
        headSha,
        event,
        body: body ?? null,
      });
      setAiDraft(updated);
      setSubmitOpen(false);
      setPublishState('idle');
    } catch (e) {
      setPublishError(e instanceof Error ? e.message : String(e));
      setPublishState('error');
    }
  };

  /** Drops the active draft (and its staged comments) entirely. Same
   *  effect as deleting from the AI sidebar — the user starts fresh. */
  const handleDiscardReview = async () => {
    if (!aiDraft || discardState === 'running') return;
    setDiscardState('running');
    try {
      await window.bridge.deleteAiReview(aiDraft.id);
      setAiDraft(null);
      setSubmitOpen(false);
      setDiscardConfirm(false);
    } catch (e) {
      setPublishError(e instanceof Error ? e.message : String(e));
    } finally {
      setDiscardState('idle');
    }
  };

  const tree = useMemo(() => (files ? buildFileTree(files) : []), [files]);
  const treeRows = useMemo(() => flattenFileTree(tree, collapsedDirs), [tree, collapsedDirs]);
  // Order the flat file list AND the continuous-scroll sections by tree
  // DFS so the user sees the same sequence regardless of view mode.
  const orderedFiles = useMemo(() => (files ? treeOrderedFiles(files) : []), [files]);

  const toggleDir = (path: string) =>
    setCollapsedDirs((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });

  const handleFilesResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(FILES_WIDTH_MIN, Math.min(FILES_WIDTH_MAX, clientX - rect.left));
    setFilesWidth(next);
    localStorage.setItem(FILES_WIDTH_KEY, String(next));
  };

  // Right-edge sidebar: dragging the handle leftward widens the AI panel,
  // so the new width is bodyRect.right - clientX rather than clientX - left.
  const handleAiResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(AI_WIDTH_MIN, Math.min(AI_WIDTH_MAX, rect.right - clientX));
    setAiWidth(next);
    localStorage.setItem(AI_WIDTH_KEY, String(next));
  };

  const switchMode = (next: FilesMode) => {
    setMode(next);
    localStorage.setItem(FILES_MODE_KEY, next);
  };

  useEffect(() => {
    setFiles(null);
    setCommits(null);
    setError(null);
    setSelectedPath(null);
    setSelectedCommits(new Set(initialCommitSha ? [initialCommitSha] : []));
    // If the user landed on a specific commit, fetch its diff up-front
    // instead of the cumulative PR diff, so the first paint shows the
    // commit they clicked rather than briefly flashing the full PR diff.
    const filesPromise = initialCommitSha
      ? window.bridge.fetchPrCommitDiff(pr.repo, pr.number, initialCommitSha)
      : window.bridge.fetchPrDiffFiles(pr.repo, pr.number);
    Promise.allSettled([
      filesPromise,
      window.bridge.fetchPrCommits(pr.repo, pr.number),
    ]).then(([filesRes, commitsRes]) => {
      if (filesRes.status === 'fulfilled') {
        setFiles(filesRes.value);
        if (filesRes.value.length > 0) setSelectedPath(filesRes.value[0].filename);
      } else {
        setError(filesRes.reason instanceof Error ? filesRes.reason.message : String(filesRes.reason));
      }
      if (commitsRes.status === 'fulfilled') {
        setCommits(commitsRes.value);
      }
    });
  }, [pr.repo, pr.number, initialCommitSha]);

  /** Apply the new commit-selection set: fetch the matching diff and
   *  reset the file selection. Empty → cumulative PR diff. One → single
   *  commit. Multiple → union, summing +/- per filename and taking the
   *  latest selected commit's patch for files touched by more than one. */
  const applyCommitSelection = async (next: Set<string>) => {
    setSelectedCommits(next);
    setCommitDiffLoading(true);
    setError(null);
    try {
      let merged: DiffFileDto[];
      if (next.size === 0) {
        merged = await window.bridge.fetchPrDiffFiles(pr.repo, pr.number);
      } else if (next.size === 1) {
        const sha = [...next][0];
        merged = await window.bridge.fetchPrCommitDiff(pr.repo, pr.number, sha);
      } else {
        // Walk commits in chronological order so the latest occurrence
        // of each filename wins for the displayed patch.
        const ordered = (commits ?? []).map(c => c.sha).filter(s => next.has(s));
        const diffs = await Promise.all(
          ordered.map(s => window.bridge.fetchPrCommitDiff(pr.repo, pr.number, s)),
        );
        const byPath = new Map<string, DiffFileDto>();
        for (const diff of diffs) {
          for (const f of diff) {
            const prev = byPath.get(f.filename);
            byPath.set(f.filename, prev
              ? { ...f, additions: prev.additions + f.additions, deletions: prev.deletions + f.deletions }
              : { ...f });
          }
        }
        merged = [...byPath.values()];
      }
      setFiles(merged);
      setSelectedPath(merged.length > 0 ? merged[0].filename : null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setCommitDiffLoading(false);
    }
  };

  const toggleCommit = (sha: string) => {
    const next = new Set(selectedCommits);
    if (next.has(sha)) next.delete(sha);
    else next.add(sha);
    void applyCommitSelection(next);
  };

  const clearCommitSelection = () => {
    if (selectedCommits.size === 0) return;
    void applyCommitSelection(new Set());
  };

  const selectedFile = files?.find((f) => f.filename === selectedPath) ?? null;

  return (
    <div className="diff-viewer">
      <div className="diff-viewer__toolbar">
        <button className="button button--secondary" onClick={onBack} type="button">
          ← Back to details
        </button>
        <div className="diff-viewer__title">
          <span className="diff-viewer__repo">{pr.repo}</span>
          <span className="diff-viewer__num">#{pr.number}</span>
          <span className="diff-viewer__pr-title">{pr.title}</span>
        </div>
        {onApprove && (
          <button
            className="button button--approve"
            type="button"
            onClick={handleApprove}
            disabled={approveState === 'running'}
            title="Submit an Approved review on GitHub and return to the details page."
          >
            {approveState === 'running' ? 'Approving…' : 'Approve'}
          </button>
        )}
        <button
          className="button button--ai"
          type="button"
          onClick={handleRunAi}
          title="Ask Claude to draft a review — summary plus line-anchored comments. Stored locally until you publish."
        >
          ✨ Run AI review
        </button>
        <div className="diff-viewer__submit-wrap">
          {(() => {
            // Dismissed comments stay on the row but the publish payload skips
            // them, so the staged-count badge has to skip them too — otherwise
            // the user sees "Submit review [4]" right after dismissing two of
            // four findings, which would be a lie about what's about to ship.
            const stagedCount = aiDraft
              ? aiDraft.comments.filter(c => !c.dismissed).length
              : 0;
            const noStaged = stagedCount === 0;
            return (
              <>
                <button
                  className="button button--submit"
                  type="button"
                  disabled={aiDraft?.status === 'PUBLISHED'}
                  onClick={() => (submitOpen ? closeSubmitPanel() : openSubmitPanel())}
                  title={aiDraft?.status === 'PUBLISHED'
                    ? 'This review has already been submitted.'
                    : noStaged
                      ? 'Submit a verdict-only review (Approve / Comment) — or stage comments first.'
                      : 'Submit all staged comments to GitHub as a single review.'}
                >
                  Submit review
                  {aiDraft && aiDraft.status !== 'PUBLISHED' && stagedCount > 0 && (
                    <span className="button--submit__count">{stagedCount}</span>
                  )} ▾
                </button>
                {submitOpen && aiDraft?.status !== 'PUBLISHED' && (
                  <FinishReviewPanel
                    body={submitBody}
                    onBodyChange={setSubmitBody}
                    verdict={submitVerdict}
                    onVerdictChange={setSubmitVerdict}
                    pendingComments={aiDraft ? aiDraft.comments.filter(c => !c.dismissed) : []}
                    pendingExpanded={pendingExpanded}
                    onTogglePending={() => setPendingExpanded(v => !v)}
                    onClose={closeSubmitPanel}
                    onSubmit={() => void handlePublish(submitVerdict, submitBody)}
                    onDiscard={() => setDiscardConfirm(true)}
                    canDiscard={!!aiDraft}
                    discardConfirm={discardConfirm}
                    onConfirmDiscard={() => void handleDiscardReview()}
                    onCancelDiscard={() => setDiscardConfirm(false)}
                    discardRunning={discardState === 'running'}
                    publishState={publishState}
                    publishError={publishError}
                  />
                )}
              </>
            );
          })()}
        </div>
        {approveState === 'error' && approveError && (
          <span className="action-badge action-badge--error" title={approveError}>
            {approveError.length > 60 ? approveError.slice(0, 57) + '…' : approveError}
          </span>
        )}
      </div>

      {commits && commits.length > 0 && (
        <div className="diff-viewer__sub">
          <div className="diff-viewer__sub-left">
            <span className="diff-viewer__sub-label">Showing:</span>
            <button
              type="button"
              className="commits-pill"
              onClick={() => setCommitsOpen(o => !o)}
              title={selectedCommits.size === 0
                ? 'All commits in this PR (cumulative diff). Click to filter by commit.'
                : selectedCommits.size === 1
                  ? "Showing only this commit's changes — click to change selection."
                  : `Showing the union of ${selectedCommits.size} selected commits.`}
            >
              <span className="commits-pill__icon" aria-hidden="true">⊞</span>
              {selectedCommits.size === 0 ? (
                <><b>All {commits.length} commit{commits.length === 1 ? '' : 's'}</b> (cumulative)</>
              ) : selectedCommits.size === 1 ? (
                <><b>{formatShortSha([...selectedCommits][0])}</b> · single commit</>
              ) : (
                <><b>{selectedCommits.size} of {commits.length} commits</b> selected</>
              )}
              <span className="commits-pill__caret" aria-hidden="true">▾</span>
            </button>
            {commitDiffLoading && (
              <span className="diff-viewer__sub-status">Loading commit diff…</span>
            )}
          </div>
          <div className="diff-viewer__sub-right">
            <span className="diff-viewer__sub-stat">{files?.length ?? 0} files</span>
          </div>
          {commitsOpen && (
            <div className="commits-popover" onClick={(e) => e.stopPropagation()}>
              <button
                type="button"
                className={'commits-popover__row commits-popover__row--all' + (selectedCommits.size === 0 ? ' commits-popover__row--active' : '')}
                onClick={() => { clearCommitSelection(); setCommitsOpen(false); }}
              >
                <code className="commits-popover__sha">All</code>
                <span className="commits-popover__subject">All commits (cumulative)</span>
              </button>
              {commits.map((c) => {
                const checked = selectedCommits.has(c.sha);
                return (
                  <label
                    key={c.sha}
                    className={'commits-popover__row commits-popover__row--checkable' + (checked ? ' commits-popover__row--active' : '')}
                    title={c.message ?? ''}
                  >
                    <input
                      type="checkbox"
                      className="commits-popover__check"
                      checked={checked}
                      onChange={() => toggleCommit(c.sha)}
                    />
                    <code className="commits-popover__sha">{formatShortSha(c.sha)}</code>
                    <span className="commits-popover__subject">{commitSubject(c.message)}</span>
                    {c.authoredAt && (
                      <span className="commits-popover__time">{formatRelative(c.authoredAt)}</span>
                    )}
                  </label>
                );
              })}
            </div>
          )}
        </div>
      )}

      <div
        className="diff-viewer__body"
        ref={bodyRef}
        style={{
          // When the file list is collapsed, the resize-handle column is
          // also dropped — there's nothing to resize when the panel is a
          // 36px rail. Mirrors the AI sidebar's collapse behaviour.
          // The AI panel gets its own 5px resize handle on its left edge
          // when expanded, so the user can drag it wider for long comments.
          gridTemplateColumns: [
            filesCollapsed ? `${FILES_RAIL_WIDTH}px` : `${filesWidth}px 5px`,
            'minmax(0, 1fr)',
            aiCollapsed ? `${AI_RAIL_WIDTH}px` : `5px ${aiWidth}px`,
          ].join(' '),
        }}
      >
        {filesCollapsed ? (
          <aside className="diff-viewer__files diff-viewer__files--collapsed">
            <button
              type="button"
              className="diff-viewer__files-rail-toggle"
              onClick={() => {
                setFilesCollapsed(false);
                localStorage.setItem(FILES_COLLAPSED_KEY, '0');
              }}
              title="Expand changed-files panel"
            >
              ▶
            </button>
            <div className="diff-viewer__files-rail-label" aria-hidden="true">
              Files{files !== null && <span className="diff-viewer__files-rail-count"> · {files.length}</span>}
            </div>
          </aside>
        ) : (
        <aside className="diff-viewer__files">
          <div className="diff-viewer__files-header">
            <span>Changed files</span>
            {files !== null && <span className="diff-viewer__files-count">{files.length}</span>}
            <div className="diff-viewer__mode-toggle" role="tablist" aria-label="File list layout">
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${mode === 'tree' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchMode('tree')}
                aria-selected={mode === 'tree'}
                title="Tree — group by directory, compact single-child chains"
              >
                Tree
              </button>
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${mode === 'flat' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchMode('flat')}
                aria-selected={mode === 'flat'}
                title="Flat — one row per file, full path on each row"
              >
                Flat
              </button>
            </div>
            <button
              type="button"
              className="diff-viewer__files-collapse-btn"
              onClick={() => {
                setFilesCollapsed(true);
                localStorage.setItem(FILES_COLLAPSED_KEY, '1');
              }}
              title="Collapse changed-files panel"
            >
              ◀
            </button>
          </div>
          <div className={`diff-viewer__files-list diff-viewer__files-list--${mode}`}>
            {files === null && !error && <div className="diff-viewer__loading">Loading files…</div>}
            {error && <div className="diff-viewer__error">{error}</div>}
            {files !== null && files.length === 0 && (
              <div className="diff-viewer__empty">No files changed.</div>
            )}
            {files !== null && mode === 'tree' && treeRows.map((row) => {
              // Tighter indent than GitHub Desktop — the diff viewer's
              // file pane is narrow, every horizontal pixel back to the
              // filename helps readability.
              const indent = 4 + row.depth * 10;
              if (row.kind === 'dir') {
                return (
                  <button
                    key={`dir:${row.path}`}
                    type="button"
                    className="diff-file-row diff-file-row--dir"
                    style={{ paddingLeft: indent }}
                    onClick={() => toggleDir(row.path)}
                    title={row.path}
                  >
                    <Chevron open={!row.collapsed} />
                    <FolderIcon open={!row.collapsed} />
                    <span className="diff-tree-dir-name">{row.name}</span>
                  </button>
                );
              }
              const badge = statusBadge(row.file.status);
              return (
                <button
                  key={`file:${row.path}`}
                  type="button"
                  className={`diff-file-row diff-file-row--file${selectedPath === row.path ? ' diff-file-row--selected' : ''}`}
                  style={{ paddingLeft: indent }}
                  onClick={() => setSelectedPath(row.path)}
                  title={row.path}
                >
                  <span className="tree-chevron tree-chevron--placeholder" aria-hidden="true" />
                  <span className={`diff-file-row__badge diff-file-row__badge--${badge.cls}`} title={row.file.status}>
                    {badge.letter}
                  </span>
                  <span className="diff-file-row__name">{row.name}</span>
                </button>
              );
            })}
            {files !== null && mode === 'flat' && orderedFiles.map((f) => {
              const badge = statusBadge(f.status);
              return (
                <button
                  key={`flat:${f.filename}`}
                  type="button"
                  className={`diff-file-row diff-file-row--flat${selectedPath === f.filename ? ' diff-file-row--selected' : ''}`}
                  onClick={() => setSelectedPath(f.filename)}
                  title={f.filename}
                >
                  <span className={`diff-file-row__badge diff-file-row__badge--${badge.cls}`} title={f.status}>
                    {badge.letter}
                  </span>
                  <span className="diff-file-row__name diff-file-row__name--path">{truncatePathMiddle(f.filename)}</span>
                </button>
              );
            })}
          </div>
        </aside>
        )}

        {!filesCollapsed && (
          <ResizeHandle onResize={handleFilesResize} ariaLabel="Resize changed-files panel" />
        )}

        <main className="diff-viewer__pane">
          {files !== null && files.length > 0 ? (
            <ContinuousFilesPane
              files={orderedFiles}
              selectedPath={selectedPath}
              onActiveFileChange={(path) => setSelectedPath(path)}
              aiComments={aiDraft?.comments ?? []}
              draftId={aiDraft?.id ?? null}
              draftPublished={aiDraft?.status === 'PUBLISHED'}
              onDraftUpdated={setAiDraft}
              prId={pr.id}
              repo={pr.repo}
              prNumber={pr.number}
              headSha={commits && commits.length > 0 ? commits[commits.length - 1].sha : null}
              threads={reviewThreads}
              onThreadReplied={(rootGithubId, optimisticReply) => {
                // Patch local state right away so the user sees their
                // reply land without pulling a stale SQLite snapshot over it.
                if (rootGithubId != null && optimisticReply) {
                  setReviewThreads(prev => prev.map(t =>
                    t.rootGithubId === rootGithubId
                      ? { ...t, messages: [...t.messages, optimisticReply] }
                      : t,
                  ));
                  return;
                }
                void refreshReviewThreads(true);
              }}
              prAuthor={pr.author}
            />
          ) : files === null ? null : (
            <div className="diff-viewer__empty">No files in this diff.</div>
          )}
        </main>

        {!aiCollapsed && (
          <ResizeHandle onResize={handleAiResize} ariaLabel="Resize AI review panel" />
        )}

        <AiReviewSidebar
          ref={aiSidebarRef}
          pr={pr}
          collapsed={aiCollapsed}
          onToggleCollapsed={() => {
            setAiCollapsed(prev => {
              const next = !prev;
              localStorage.setItem(AI_COLLAPSED_KEY, next ? '1' : '0');
              return next;
            });
          }}
          onJumpToFile={(filePath, lineNumber, side) => {
            // Only jump if the file is in the current diff source — when
            // a single commit is selected, a finding from the cumulative
            // diff may not be present; fall through silently in that case.
            if (!files?.some(f => f.filename === filePath)) return;
            setSelectedPath(filePath);
            if (lineNumber != null) {
              // Two animation frames let the file-section auto-scroll
              // (set off by setSelectedPath above) settle before we
              // reach for the row — without this, the row's bounding
              // rect can still be in its pre-scroll position.
              requestAnimationFrame(() => requestAnimationFrame(() => {
                // Use the comment's actual side. AI findings are
                // usually RIGHT (new file) but human-staged inlines
                // can be LEFT for deletions. Fall back to RIGHT if
                // unspecified.
                const targetSide = side ?? 'RIGHT';
                const escapedPath = cssEscape(filePath);
                let row = document.querySelector<HTMLElement>(
                  `[data-anchor="${escapedPath}:${targetSide}:${lineNumber}"]`,
                );
                // Fallback 1: try the other side. AI sometimes mis-
                // tags a deletion as RIGHT or an addition as LEFT.
                if (!row) {
                  const otherSide = targetSide === 'RIGHT' ? 'LEFT' : 'RIGHT';
                  row = document.querySelector<HTMLElement>(
                    `[data-anchor="${escapedPath}:${otherSide}:${lineNumber}"]`,
                  );
                }
                // Fallback 2: scroll to the file header if the line
                // doesn't exist in the current diff (e.g. an AI
                // finding whose line is outside the rendered hunks).
                // Prevents the page from looking inert on click.
                if (!row) {
                  const fileSection = document.querySelector<HTMLElement>(
                    `[data-file-anchor="${escapedPath}"]`,
                  );
                  if (fileSection) {
                    fileSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    fileSection.classList.add('diff-row--flash');
                    window.setTimeout(() => fileSection.classList.remove('diff-row--flash'), 1600);
                  }
                  return;
                }
                row.scrollIntoView({ behavior: 'smooth', block: 'center' });
                row.classList.add('diff-row--flash');
                window.setTimeout(() => row.classList.remove('diff-row--flash'), 1600);
              }));
            }
          }}
          onDraftChange={setAiDraft}
          draftSnapshot={aiDraft}
        />
      </div>
    </div>
  );
}

export default DiffViewerScreen;
