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
  type ChangeEvent, type CSSProperties, type KeyboardEvent,
} from 'react';
import { marked } from 'marked';
import type { PullRequestDto } from '../types';
import { buildQuotedReply } from './utils';
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
  onCommented?: () => void;
  onClosed?: () => void;
}>(function PrCommentBox({ pr, mentionCandidates, onCommented, onClosed }, ref) {
  const [body, setBody] = useState('');
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const [pending, setPending] = useState<'idle' | 'comment' | 'close'>('idle');
  const [error, setError] = useState<string | null>(null);
  // Expanded by default so the user can start typing immediately. The
  // collapse-after-send behaviour stays — once a comment is sent the box
  // returns to its closed state, and the user can re-open with one click.
  const [expanded, setExpanded] = useState(true);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // @mention autocomplete. `mention` is non-null when the caret sits in
  // an "@token" the user is typing; `mentionIdx` is the highlighted row.
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [mentionIdx, setMentionIdx] = useState(0);
  const mentionMatches = mention === null ? [] : (mentionCandidates ?? [])
      .filter(c => c.toLowerCase().startsWith(mention.query.toLowerCase()))
      .slice(0, 6);
  const showMentions = mention !== null && mentionMatches.length > 0;

  // Recompute the active @token from the text + caret. A mention starts
  // at an "@" that follows the start of the line or a separator, so an
  // email-style "a@b" never triggers it.
  const syncMention = (text: string, caret: number) => {
    const before = text.slice(0, caret);
    const m = before.match(/(?:^|[\s([{])@([A-Za-z0-9-]*)$/);
    setMention(m === null ? null : { start: caret - m[1].length - 1, query: m[1] });
    setMentionIdx(0);
  };

  const onBodyChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    setBody(e.target.value);
    syncMention(e.target.value, e.target.selectionStart ?? e.target.value.length);
  };

  const insertMention = (login: string) => {
    if (mention === null) return;
    const before = body.slice(0, mention.start);
    const after = body.slice(mention.start + 1 + mention.query.length);
    const next = `${before}@${login} ${after}`;
    const caret = before.length + login.length + 2; // past "@login "
    setBody(next);
    setMention(null);
    requestAnimationFrame(() => {
      const ta = textareaRef.current;
      if (ta) { ta.focus(); ta.setSelectionRange(caret, caret); }
    });
  };

  const onTextareaKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!showMentions) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setMentionIdx(i => (i + 1) % mentionMatches.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setMentionIdx(i => (i - 1 + mentionMatches.length) % mentionMatches.length);
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      insertMention(mentionMatches[mentionIdx]);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setMention(null);
    }
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
    try {
      await window.bridge.commentPr(pr.id, pr.repo, pr.number, bodyTrim, close);
      setBody('');
      setMention(null);
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
            onKeyDown={onTextareaKeyDown}
            onClick={(e) => syncMention(e.currentTarget.value, e.currentTarget.selectionStart ?? 0)}
            placeholder="Leave a comment — markdown is supported."
            /* 5 rows is a comfortable starting height — enough to see
               the start of a quote-reply, not so tall the action bar
               gets pushed below the fold. The textarea is user-resizable
               via the bottom-right handle when more room is needed. */
            rows={5}
            disabled={busy}
          />
          {showMentions && (
            <ul style={mentionListStyle} role="listbox" aria-label="Mention a user">
              {mentionMatches.map((c, i) => (
                <li key={c} role="option" aria-selected={i === mentionIdx}>
                  <button
                    type="button"
                    style={mentionItemStyle(i === mentionIdx)}
                    // mousedown (not click) so the textarea doesn't blur
                    // and lose its caret before we insert.
                    onMouseDown={(e) => { e.preventDefault(); insertMention(c); }}
                    onMouseEnter={() => setMentionIdx(i)}
                  >
                    @{c}
                  </button>
                </li>
              ))}
            </ul>
          )}
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
      {error && <div className="pr-comment-box__error">{error}</div>}
    </section>
  );
});

const mentionListStyle: CSSProperties = {
  position: 'absolute',
  top: '100%',
  left: 0,
  marginTop: 4,
  minWidth: 220,
  maxHeight: 220,
  overflowY: 'auto',
  zIndex: 30,
  listStyle: 'none',
  padding: 4,
  background: 'var(--bg-1, #fff)',
  border: '1px solid var(--border, #d0d7de)',
  borderRadius: 8,
  boxShadow: '0 6px 22px rgba(0,0,0,0.14)',
};

function mentionItemStyle(active: boolean): CSSProperties {
  return {
    display: 'block',
    width: '100%',
    textAlign: 'left',
    padding: '5px 10px',
    fontSize: 13,
    borderRadius: 6,
    border: 'none',
    cursor: 'pointer',
    background: active ? 'var(--accent, #2563eb)' : 'transparent',
    color: active ? '#fff' : 'var(--text-1, inherit)',
  };
}
