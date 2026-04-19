import { forwardRef, useImperativeHandle, useRef, useState } from 'react';
import { marked } from 'marked';
import type { PullRequestDto } from '../types';
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
  onCommented?: () => void;
  onClosed?: () => void;
}>(function PrCommentBox({ pr, onCommented, onClosed }, ref) {
  const [body, setBody] = useState('');
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const [pending, setPending] = useState<'idle' | 'comment' | 'close'>('idle');
  const [error, setError] = useState<string | null>(null);
  // Expanded by default so the user can start typing immediately. The
  // collapse-after-send behaviour stays — once a comment is sent the box
  // returns to its closed state, and the user can re-open with one click.
  const [expanded, setExpanded] = useState(true);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useImperativeHandle(ref, () => ({
    insertQuote: (quoted: string) => {
      // Prepend "> " to each line of the quoted body, separator + blank line,
      // then any text the user has already typed. Match GitHub's "Quote reply"
      // wording exactly.
      const quote = quoted.split('\n').map(l => `> ${l}`).join('\n');
      setBody(prev => {
        const sep = prev.trim().length > 0 ? '\n\n' : '';
        return `${quote}\n\n${sep}${prev}`;
      });
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
        <textarea
          ref={textareaRef}
          className="pr-comment-box__input"
          value={body}
          onChange={(e) => setBody(e.target.value)}
          placeholder="Leave a comment — markdown is supported."
          /* 5 rows is a comfortable starting height — enough to see
             the start of a quote-reply, not so tall the action bar
             gets pushed below the fold. The textarea is user-resizable
             via the bottom-right handle when more room is needed. */
          rows={5}
          disabled={busy}
        />
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
