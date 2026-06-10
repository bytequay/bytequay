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

/**
 * The "⋯" overflow menu github.com puts on the top-right of every
 * comment. Holds the three per-comment actions:
 *
 * <ul>
 *   <li><b>Copy link</b> — copies {@code linkHref} (a github.com anchor
 *       the caller builds) to the clipboard, with brief "Copied"
 *       feedback.</li>
 *   <li><b>Quote reply</b> — fires {@code onQuote}; hidden when the
 *       comment has no body to quote.</li>
 *   <li><b>Delete</b> — fires {@code onDelete} behind an inline confirm
 *       step (the menu swaps to a "Delete this comment?" prompt rather
 *       than a separate dialog). Hidden unless {@code onDelete} is
 *       supplied, which the caller gates on author / write access.</li>
 * </ul>
 *
 * <p>Closes on outside-click or Esc. The delete is a real, outward
 * github.com mutation, hence the confirm gate before {@code onDelete}
 * is ever called.
 */
export function CommentActionsMenu({
  linkHref, onQuote, onDelete,
}: {
  linkHref: string;
  onQuote?: () => void;
  onDelete?: () => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const close = () => { setOpen(false); setConfirming(false); setCopied(false); };

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(linkHref);
      setCopied(true);
      window.setTimeout(close, 900);
    }
    catch {
      // Clipboard blocked (rare in the desktop shell) — just close.
      close();
    }
  };

  const runDelete = async () => {
    if (!onDelete) return;
    setBusy(true);
    try {
      await onDelete();
      close();
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <div className="prc-comment-menu" ref={rootRef}>
      <button
        type="button"
        className="prc-comment-menu__trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        title="Comment actions"
        onClick={() => (open ? close() : setOpen(true))}
      >
        ⋯
      </button>
      {open && (
        <div className="prc-comment-menu__pop" role="menu">
          {confirming ? (
            <div className="prc-comment-menu__confirm">
              <span className="prc-comment-menu__confirm-q">Delete this comment?</span>
              <div className="prc-comment-menu__confirm-row">
                <button
                  type="button"
                  className="prc-comment-menu__confirm-del"
                  onClick={() => { void runDelete(); }}
                  disabled={busy}
                >
                  {busy ? 'Deleting…' : 'Delete'}
                </button>
                <button
                  type="button"
                  className="prc-comment-menu__confirm-cancel"
                  onClick={() => setConfirming(false)}
                  disabled={busy}
                >
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <>
              <button
                type="button"
                role="menuitem"
                className="prc-comment-menu__item"
                onClick={() => { void copyLink(); }}
              >
                {copied ? '✓ Copied' : 'Copy link'}
              </button>
              {onQuote && (
                <button
                  type="button"
                  role="menuitem"
                  className="prc-comment-menu__item"
                  onClick={() => { onQuote(); close(); }}
                >
                  Quote reply
                </button>
              )}
              {onDelete && (
                <button
                  type="button"
                  role="menuitem"
                  className="prc-comment-menu__item prc-comment-menu__item--danger"
                  onClick={() => setConfirming(true)}
                >
                  Delete
                </button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** Builds the github.com anchor for a comment so "Copy link" lands on
 *  the exact comment on the web. Issue / conversation comments use the
 *  {@code #issuecomment-<id>} fragment; per-line review comments use
 *  {@code #discussion_r<id>} — the same fragments github.com itself
 *  links to. */
export function issueCommentLink(prHtmlUrl: string, commentId: number): string {
  return `${prHtmlUrl}#issuecomment-${commentId}`;
}

export function reviewCommentLink(prHtmlUrl: string, commentId: number): string {
  return `${prHtmlUrl}#discussion_r${commentId}`;
}
