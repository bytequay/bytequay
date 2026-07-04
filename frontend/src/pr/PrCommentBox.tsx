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
import {
  forwardRef, useImperativeHandle, useRef, useState,
  type ChangeEvent, type CSSProperties,
} from 'react';
import { marked } from 'marked';
import type { PullRequestDto } from '../types';
import { buildQuotedReply } from './utils';
import { useMentions } from '../useMentions';
import PolishButtons from '../ai/PolishButtons';

/**
 * Imperative handle exposed by PrCommentBox so parents (e.g. timeline cards
 * with a "Quote reply" affordance) can inject text into the composer and
 * pop it open without owning its state.
 */
export type PrCommentBoxHandle = {
  insertQuote: (body: string) => void;
};

export const PrCommentBox = forwardRef<PrCommentBoxHandle, {
  pr: PullRequestDto;
  /** Logins offered as @mention autocomplete (PR author, reviewers,
   *  commenters). Empty/undefined disables the picker. */
  mentionCandidates?: string[];
  /** Revalidate-before-submit guard. Run just before the comment / close
   *  is sent to GitHub; return a warning string to abort (the composer
   *  keeps the user's draft and shows the warning) or null to proceed.
   *  Used to catch a PR that moved on GitHub since the user opened it so
   *  they don't post into stale context. Undefined skips the check. */
  beforeSubmit?: () => Promise<string | null>;
  onCommented?: () => void;
  onClosed?: () => void;
}>(function PrCommentBox({ pr, mentionCandidates, beforeSubmit, onCommented, onClosed }, ref) {
  const [body, setBody] = useState('');
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const [pending, setPending] = useState<'idle' | 'comment' | 'close'>('idle');
  const [error, setError] = useState<string | null>(null);
  // Amber "this PR changed, review first" notice from the beforeSubmit
  // guard. Separate from `error` (red, a real failure) — a notice means
  // the post was held back on purpose, not that anything broke.
  const [notice, setNotice] = useState<string | null>(null);
  // Expanded by default so the user can start typing immediately. The
  // collapse-after-send behaviour stays — once a comment is sent the box
  // returns to its closed state, and the user can re-open with one click.
  const [expanded, setExpanded] = useState(true);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // @mention autocomplete — shared with every other markdown composer.
  const mentions = useMentions({
    value: body, onChange: setBody, candidates: mentionCandidates, textareaRef,
  });

  const onBodyChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    // Editing the draft after a stale-PR notice clears it — the user is
    // reacting to the change they were just shown.
    if (notice) setNotice(null);
    mentions.onChange(e);
  };

  useImperativeHandle(ref, () => ({
    insertQuote: (quoted: string) => {
      // Prepend "> " to each line of the quoted body, separator + blank line,
      // then any text the user has already typed. Match GitHub's "Quote reply"
      // wording exactly.
      setBody(prev => buildQuotedReply(quoted, prev));
      setExpanded(true);
      setTab('write');
      // Defer focus until after the textarea renders.
      setTimeout(() => textareaRef.current?.focus(), 0);
    },
  }), []);

  const bodyTrim = body.trim();
  const busy = pending !== 'idle';

  // Collapsed by default so CI + the action bar take minimal space and the
  // description above gets the lion's share of the viewport. One click to
  // expand into the full Write/Preview composer.
  if (!expanded) {
    return (
      <button
        type="button"
        className="pr-comment-box pr-comment-box--collapsed"
        onClick={() => setExpanded(true)}
        title="Click to write a comment."
      >
        <span className="pr-comment-box__placeholder">Add a comment…</span>
      </button>
    );
  }

  const submit = async (close: boolean) => {
    if (!bodyTrim && !close) return;
    setPending(close ? 'close' : 'comment');
    setError(null);
    setNotice(null);
    try {
      // Revalidate against GitHub before writing: if the PR moved since
      // the user loaded it, hold the post and surface what changed so
      // they don't comment into stale context.
      if (beforeSubmit) {
        const warning = await beforeSubmit();
        if (warning) {
          setNotice(warning);
          setPending('idle');
          return;
        }
      }
      await window.bridge.commentPr(pr.id, pr.repo, pr.number, bodyTrim, close);
      setBody('');
      setTab('write');
      setExpanded(false);
      if (close) onClosed?.(); else onCommented?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending('idle');
    }
  };

  const previewHtml = tab === 'preview'
    ? (marked(bodyTrim || '_Nothing to preview._', { gfm: true, breaks: true }) as string)
    : '';

  return (
    <section className="preview__section pr-comment-box">
      <h4 className="preview__section-title">Add a comment</h4>
      <div className="pr-comment-box__tabs" role="tablist">
        <button
          type="button"
          role="tab"
          className={`pr-comment-box__tab${tab === 'write' ? ' pr-comment-box__tab--active' : ''}`}
          onClick={() => setTab('write')}
          aria-selected={tab === 'write'}
        >
          Write
        </button>
        <button
          type="button"
          role="tab"
          className={`pr-comment-box__tab${tab === 'preview' ? ' pr-comment-box__tab--active' : ''}`}
          onClick={() => setTab('preview')}
          aria-selected={tab === 'preview'}
        >
          Preview
        </button>
      </div>
      {tab === 'write' ? (
        <div style={{ position: 'relative' }}>
          <textarea
            ref={textareaRef}
            className="pr-comment-box__input"
            value={body}
            onChange={onBodyChange}
            onKeyDown={mentions.onKeyDown}
            onClick={mentions.onClick}
            placeholder="Leave a comment — markdown is supported."
            /* 5 rows is a comfortable starting height — enough to see
               the start of a quote-reply, not so tall the action bar
               gets pushed below the fold. The textarea is user-resizable
               via the bottom-right handle when more room is needed. */
            rows={5}
            disabled={busy}
          />
          {mentions.dropdown}
        </div>
      ) : (
        <div
          className="md-body pr-comment-box__preview"
          // The only HTML here is what `marked` renders from our own textarea.
          // contextIsolation prevents renderer escapes even if a PR contained
          // an injected script via the preview-tab copy path.
          dangerouslySetInnerHTML={{ __html: previewHtml }}
        />
      )}
      <div className="pr-comment-box__actions">
        <button
          type="button"
          className="button button--primary"
          onClick={() => submit(false)}
          disabled={busy || !bodyTrim}
          title="Post this text as a comment on the PR."
        >
          {pending === 'comment' ? 'Commenting…' : 'Comment'}
        </button>
        <button
          type="button"
          className="button button--secondary"
          onClick={() => submit(true)}
          disabled={busy}
          title={bodyTrim
            ? 'Post the comment, then close the pull request on GitHub.'
            : 'Close the pull request on GitHub without commenting.'}
        >
          {pending === 'close'
            ? 'Closing…'
            : bodyTrim ? 'Close with comment' : 'Close pull request'}
        </button>
        <PolishButtons
          value={body}
          onChange={setBody}
          onError={setError}
          disabled={busy}
        />
        <button
          type="button"
          className="pr-comment-box__cancel"
          onClick={() => { setBody(''); setError(null); setExpanded(false); }}
          disabled={busy}
          title="Collapse the comment box."
        >
          Cancel
        </button>
      </div>
      {notice && <div style={noticeStyle} role="status">{notice}</div>}
      {error && <div className="pr-comment-box__error">{error}</div>}
    </section>
  );
});

const noticeStyle: CSSProperties = {
  marginTop: 8,
  padding: '8px 10px',
  fontSize: 13,
  lineHeight: 1.4,
  borderRadius: 6,
  border: '1px solid #E0B24D',
  background: '#FEF6E6',
  color: '#7A571A',
};
