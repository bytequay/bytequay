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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { EmailMessageDetailDto, EmailThreadDetailDto, EmailThreadMetaDto, LinkedRefDto } from '../types';

type Account = { email: string; authMode: 'OAUTH' | 'IMAP' };

type Props = {
  /** Click handler for "no Gmail account connected" empty state — jumps the
   *  user to Settings → Integrations to add one. */
  onOpenIntegrationsSettings: () => void;
  /** Routes a detected PR/issue link to the Repository PR/issues tab.
   *  Wired by App.tsx so the email surface can hand off triage. */
  onOpenLinkedRef: (ref: LinkedRefDto) => void;
};

/** Window during which an auto-archive can be one-clicked back to
 *  the inbox via the toast at the bottom of the page. Long enough
 *  to register a misclick, short enough not to clutter the screen.
 *  Mirrors Gmail's own "Undo" timing. */
const UNDO_GRACE_MS = 5_000;

/**
 * Master-detail inbox, thread-based. One row per Gmail conversation
 * (matches Gmail's web UI), with a (N) badge for multi-message
 * threads. Click a thread to load the full conversation in the right
 * pane — every message stacked oldest-first.
 *
 * <p>Archive / mark-read operate on the entire thread, like Gmail
 * itself does.
 */
export default function EmailPage({ onOpenIntegrationsSettings, onOpenLinkedRef }: Props) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [accountsError, setAccountsError] = useState<string | null>(null);
  const [selectedAccount, setSelectedAccount] = useState<string | null>(null);

  const [threads, setThreads] = useState<EmailThreadMetaDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  // Threads the user opened in this session that auto-archived. They're
  // still in `threads` so the detail pane can render the open one, but
  // filtered out of the visible inbox list. "Keep in inbox" removes the
  // ID and the row pops back into the list.
  const [autoArchivedIds, setAutoArchivedIds] = useState<Set<string>>(() => new Set());
  // Tracks thread IDs we've already auto-acted on (mark-read + archive)
  // so re-selecting after "Keep in inbox" doesn't immediately re-archive.
  const autoActedRef = useRef<Set<string>>(new Set());
  // Most-recent auto-archive that's still inside the undo grace window.
  // Set right after readAndArchive resolves; cleared by the timer (see
  // UNDO_GRACE_MS) or by a manual undo click. Replaced — not stacked —
  // when the user opens another thread before the previous toast expires.
  const [undoTarget, setUndoTarget] = useState<{ id: string; subject: string } | null>(null);
  const undoTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Load accounts on mount; auto-select the first OAuth account.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listGmailAccounts();
        if (cancelled) return;
        setAccounts(list);
        const firstOauth = list.find(a => a.authMode === 'OAUTH');
        setSelectedAccount(firstOauth?.email ?? list[0]?.email ?? null);
      }
      catch (e) {
        if (cancelled) return;
        setAccountsError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const loadInbox = useCallback(async (account: string, force = false) => {
    setLoading(true);
    setError(null);
    try {
      const list = force
        ? await window.bridge.refreshEmailThreads(account)
        : await window.bridge.listEmailThreads(account);
      setThreads(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setThreads(null);
    }
    finally {
      setLoading(false);
    }
  }, []);

  // Background poll of the inbox list — focus-gated so it pauses
  // when the user tabs away. Cheap because listEmailThreads reads
  // from the local SQLite mirror; the heavy GmailPollingJob refresh
  // runs server-side every 60s. Effect: new arrivals appear in the
  // list within ~30s without a manual refresh, and the detail-pane
  // poll below picks up new messages in the open thread.
  useEffect(() => {
    if (!selectedAccount) return;
    const acc = accounts?.find(a => a.email === selectedAccount);
    if (acc?.authMode !== 'OAUTH') return;
    let interval: ReturnType<typeof setInterval> | null = null;
    const isVisible = () => document.visibilityState === 'visible' && document.hasFocus();
    const tick = async () => {
      try {
        const list = await window.bridge.listEmailThreads(selectedAccount);
        setThreads(list);
      }
      catch { /* best-effort: skip this tick */ }
    };
    const start = () => {
      if (interval != null) return;
      interval = setInterval(() => { void tick(); }, 30_000);
    };
    const stop = () => {
      if (interval != null) { clearInterval(interval); interval = null; }
    };
    const onVisibility = () => { isVisible() ? start() : stop(); };
    if (isVisible()) start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onVisibility);
    window.addEventListener('blur', onVisibility);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onVisibility);
      window.removeEventListener('blur', onVisibility);
    };
  }, [selectedAccount, accounts]);

  // Load threads whenever the selected account changes.
  useEffect(() => {
    if (selectedAccount == null) {
      setThreads(null);
      return;
    }
    const account = accounts?.find(a => a.email === selectedAccount);
    if (account?.authMode !== 'OAUTH') {
      setThreads(null);
      return;
    }
    void loadInbox(selectedAccount);
    setSelectedThreadId(null);
  }, [selectedAccount, accounts, loadInbox]);

  // Auto-fired when the user opens an unread thread: flip it to read
  // and remove from the visible inbox in one Gmail call. The thread
  // stays in `threads` so the detail pane keeps rendering it; the
  // visible-list filter (autoArchivedIds) hides the row. "Keep in
  // inbox" undoes both.
  /** Replace any in-flight undo toast with a new one (or clear). The
   *  ref-based timer survives renders so we can cancel cleanly. */
  const queueUndoToast = (target: { id: string; subject: string } | null) => {
    if (undoTimerRef.current) {
      clearTimeout(undoTimerRef.current);
      undoTimerRef.current = null;
    }
    setUndoTarget(target);
    if (target) {
      undoTimerRef.current = setTimeout(() => {
        setUndoTarget(curr => (curr && curr.id === target.id ? null : curr));
        undoTimerRef.current = null;
      }, UNDO_GRACE_MS);
    }
  };

  const readAndArchive = async (id: string) => {
    if (!selectedAccount) return;
    const prev = threads;
    if (!prev) return;
    const target = prev.find(t => t.id === id);
    setThreads(prev.map(t => t.id === id ? { ...t, unread: false } : t));
    setAutoArchivedIds(s => {
      const next = new Set(s);
      next.add(id);
      return next;
    });
    try {
      await window.bridge.readAndArchiveEmailThread(selectedAccount, id);
      queueUndoToast({ id, subject: target?.subject ?? '(no subject)' });
    }
    catch (e) {
      setThreads(prev);
      setAutoArchivedIds(s => {
        const next = new Set(s);
        next.delete(id);
        return next;
      });
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const keepInInbox = async (id: string) => {
    if (!selectedAccount) return;
    const prevArchived = autoArchivedIds;
    const prev = threads;
    if (!prev) return;
    setAutoArchivedIds(s => {
      const next = new Set(s);
      next.delete(id);
      return next;
    });
    setThreads(prev.map(t => t.id === id ? { ...t, unread: false } : t));
    // Manual unarchive — toast is irrelevant now.
    if (undoTarget && undoTarget.id === id) queueUndoToast(null);
    try {
      await window.bridge.keepEmailThreadInInbox(selectedAccount, id);
    }
    catch (e) {
      setAutoArchivedIds(prevArchived);
      setThreads(prev);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  // Cancel any pending undo timer when the page unmounts.
  useEffect(() => {
    return () => {
      if (undoTimerRef.current) clearTimeout(undoTimerRef.current);
    };
  }, []);

  // Auto-action on opening any inbox thread (read or unread): mark-
  // read + archive in a single round trip. Per-thread dedup via
  // autoActedRef so re-selecting after "Keep in inbox" doesn't bounce
  // the user's choice within the same session. After a page refresh
  // the dedup is empty, so a kept-in-inbox thread will re-archive on
  // next click — that's the intentional "in inbox = needs action;
  // opening = dealt with" model.
  useEffect(() => {
    if (!selectedAccount || !selectedThreadId || !threads) return;
    const t = threads.find(th => th.id === selectedThreadId);
    if (!t) return;
    if (autoActedRef.current.has(selectedThreadId)) return;
    autoActedRef.current.add(selectedThreadId);
    void readAndArchive(selectedThreadId);
    // readAndArchive is captured as a closure; we only want to re-run
    // when the selection changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedThreadId, selectedAccount]);

  // Reset the auto-act dedup when the account changes — per-account
  // threads are a fresh universe.
  useEffect(() => {
    autoActedRef.current = new Set();
    setAutoArchivedIds(new Set());
  }, [selectedAccount]);

  // Keyboard shortcuts: j/k or arrow keys to walk the visible list.
  // Archive/mark-read are no longer manual — opening already does both
  // — so e/u are gone. Walking still uses the unfiltered threads array
  // so j/k after auto-archiving the current row jumps to the next
  // visible row rather than getting stuck on the now-hidden one.
  // Suppressed when focus is in an input or contenteditable so it
  // doesn't fight typing.
  useEffect(() => {
    const acc = accounts?.find(a => a.email === selectedAccount);
    if (acc?.authMode !== 'OAUTH' || !threads || threads.length === 0) return;
    const visible = threads.filter(t => !autoArchivedIds.has(t.id) || t.id === selectedThreadId);
    if (visible.length === 0) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return;
      }
      const idx = selectedThreadId
        ? visible.findIndex(t => t.id === selectedThreadId)
        : -1;
      if (e.key === 'j' || e.key === 'ArrowDown') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.min(idx + 1, visible.length - 1);
        setSelectedThreadId(visible[next].id);
      }
      else if (e.key === 'k' || e.key === 'ArrowUp') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.max(idx - 1, 0);
        setSelectedThreadId(visible[next].id);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [threads, selectedThreadId, selectedAccount, accounts, autoArchivedIds]);

  if (accountsError) {
    return (
      <div className="email-page">
        <div className="repo-error">{accountsError}</div>
      </div>
    );
  }

  if (accounts == null) {
    return <div className="email-page"><div className="repo-loading">Loading accounts…</div></div>;
  }

  if (accounts.length === 0) {
    return (
      <div className="email-page email-page--empty">
        <h2>No Gmail account connected</h2>
        <p>Connect a Gmail account in Settings → Integrations to start triaging email here.</p>
        <button className="button button--primary" type="button" onClick={onOpenIntegrationsSettings}>
          Open Integrations settings
        </button>
      </div>
    );
  }

  const account = accounts.find(a => a.email === selectedAccount);
  const selectedThread = threads?.find(t => t.id === selectedThreadId) ?? null;

  return (
    <div className="email-page">
      <header className="email-page__header">
        <div className="email-page__accounts">
          {accounts.map(acc => (
            <button
              key={acc.email}
              type="button"
              className={`email-account-chip${acc.email === selectedAccount ? ' email-account-chip--active' : ''}`}
              onClick={() => setSelectedAccount(acc.email)}
              title={`${acc.email} · ${acc.authMode}`}
            >
              <span className="email-account-chip__email">{acc.email}</span>
              <span className={`auth-method-pill auth-method-pill--${acc.authMode === 'OAUTH' ? 'oauth' : 'pat'}`}>
                {acc.authMode === 'OAUTH' ? 'OAuth' : 'IMAP'}
              </span>
            </button>
          ))}
        </div>
        <div className="email-page__actions">
          <button
            className="button button--secondary"
            type="button"
            onClick={() => selectedAccount && void loadInbox(selectedAccount, true)}
            disabled={loading || account?.authMode !== 'OAUTH'}
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {account?.authMode === 'IMAP' && (
        <div className="email-page__hint">
          IMAP inbox loading isn't wired up yet — only OAuth-connected accounts can
          be browsed. Switch to an OAuth account or check back in the next slice.
        </div>
      )}

      {error && <div className="repo-error">{error}</div>}

      {account?.authMode === 'OAUTH' && threads != null && (() => {
        // Visible list excludes auto-archived threads but keeps the
        // currently-selected one even if it was just auto-archived,
        // so the highlight in the inbox doesn't vanish out from under
        // the user while they're reading.
        const visibleThreads = threads.filter(t =>
          !autoArchivedIds.has(t.id) || t.id === selectedThreadId);
        return (
          <div className="email-pane">
            <div className="email-pane__list">
              {visibleThreads.length === 0 && <div className="email-page__hint">Inbox is empty.</div>}
              <ul className="email-list email-list--inset">
                {visibleThreads.map(t => (
                  <li
                    key={t.id}
                    className={`email-row${t.unread ? ' email-row--unread' : ''}${t.id === selectedThreadId ? ' email-row--selected' : ''}${autoArchivedIds.has(t.id) ? ' email-row--archived' : ''}`}
                    onClick={() => setSelectedThreadId(t.id)}
                  >
                    <div className="email-row__rail" aria-hidden="true" />
                    <div className="email-row__body">
                      <div className="email-row__line1">
                        <span className="email-row__from">
                          {shortenFrom(t.from)}
                          {t.messageCount > 1 && (
                            <span className="email-row__count" title={`${t.messageCount} messages in this thread`}>
                              {' '}({t.messageCount})
                            </span>
                          )}
                        </span>
                        <span className="email-row__time">{formatRelative(t.receivedAt)}</span>
                      </div>
                      <div className="email-row__subject">{t.subject || '(no subject)'}</div>
                      <div className="email-row__snippet">{t.snippet}</div>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
            <div className="email-pane__detail">
              {selectedThread ? (
                <ThreadDetailPane
                  key={selectedThread.id}
                  account={selectedAccount!}
                  meta={selectedThread}
                  archived={autoArchivedIds.has(selectedThread.id)}
                  onKeepInInbox={() => void keepInInbox(selectedThread.id)}
                  onOpenLinkedRef={onOpenLinkedRef}
                />
              ) : (
                <div className="email-page__hint">Pick a thread on the left.</div>
              )}
            </div>
          </div>
        );
      })()}

      {loading && threads == null && <div className="repo-loading">Loading inbox…</div>}

      {undoTarget && (
        <div className="email-undo-toast" role="status" aria-live="polite">
          <span className="email-undo-toast__msg">
            Archived <span className="email-undo-toast__subject">{undoTarget.subject || '(no subject)'}</span>
          </span>
          <button
            type="button"
            className="email-undo-toast__btn"
            onClick={() => { void keepInInbox(undoTarget.id); }}
          >
            Undo
          </button>
        </div>
      )}
    </div>
  );
}

type DetailProps = {
  account: string;
  meta: EmailThreadMetaDto;
  /** True when the thread was auto-archived in this session — drives
   *  the "Keep in inbox" button's enabled state. Once cleared, the
   *  button disables (the thread is already in the inbox). */
  archived: boolean;
  onKeepInInbox: () => void;
  onOpenLinkedRef: (ref: LinkedRefDto) => void;
};

function ThreadDetailPane({ account, meta, archived, onKeepInInbox, onOpenLinkedRef }: DetailProps) {
  const [detail, setDetail] = useState<EmailThreadDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Inline reply composer — collapsed by default, opens below the
  // last message. v1 is plain-text body only; To/Subject are derived
  // server-side from the latest message in the thread.
  const [composerOpen, setComposerOpen] = useState(false);
  const [replyBody, setReplyBody] = useState('');
  const [sendState, setSendState] = useState<'idle' | 'sending'>('idle');
  const [sendError, setSendError] = useState<string | null>(null);

  // Reset the composer when the user picks a different thread —
  // half-typed text in one thread shouldn't bleed into another.
  useEffect(() => {
    setComposerOpen(false);
    setReplyBody('');
    setSendState('idle');
    setSendError(null);
  }, [meta.id]);

  const handleSend = async () => {
    if (sendState === 'sending') return;
    const trimmed = replyBody.trim();
    if (!trimmed) return;
    setSendState('sending');
    setSendError(null);
    try {
      await window.bridge.replyToEmailThread(account, meta.id, trimmed);
      setReplyBody('');
      setComposerOpen(false);
      setSendState('idle');
      // Pull the sent message into view. The 30s inbox poll would
      // catch it eventually; this just tightens the loop so the user
      // sees their reply land at the bottom of the thread immediately.
      try {
        const fresh = await window.bridge.getEmailThread(account, meta.id);
        setDetail(fresh);
      }
      catch { /* best-effort — inbox poll will catch up */ }
    }
    catch (e) {
      setSendError(e instanceof Error ? e.message : String(e));
      setSendState('idle');
    }
  };

  // Re-fetch when:
  //  - the user picks a different thread (meta.id changes), OR
  //  - the inbox poll above shows the open thread now has more messages
  //    than we have on screen (meta.messageCount changes). Loading state
  //    only flips for the first case so the silent-refresh path doesn't
  //    flash a spinner over content the user is already reading.
  useEffect(() => {
    let cancelled = false;
    const isInitialLoad = detail === null || detail.id !== meta.id;
    if (isInitialLoad) {
      setLoading(true);
      setError(null);
      setDetail(null);
    }
    void (async () => {
      try {
        const d = await window.bridge.getEmailThread(account, meta.id);
        if (cancelled) return;
        setDetail(d);
      }
      catch (e) {
        if (cancelled) return;
        if (isInitialLoad) setError(e instanceof Error ? e.message : String(e));
        // Silent-refresh errors are swallowed — keep the existing detail.
      }
      finally {
        if (!cancelled && isInitialLoad) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account, meta.id, meta.messageCount]);

  return (
    <div className="email-detail">
      <div className="email-detail__actions">
        {/* Opening an unread thread already marks-read + archives it
            in one Gmail call. The only manual action left is undoing
            that — putting the thread back in the inbox while keeping
            it read. Disabled when the thread is already in the inbox
            (i.e. user opened a previously-read thread, or already
            clicked "Keep in inbox"). */}
        <button
          className="button button--primary"
          type="button"
          onClick={onKeepInInbox}
          disabled={!archived}
          title={archived
            ? 'Re-add this thread to your inbox (stays marked as read)'
            : 'Already in inbox'}
        >
          📥 Keep in inbox
        </button>
        {detail && detail.messages.length > 1 && (
          <span className="email-detail__count">
            {detail.messages.length} messages
          </span>
        )}
      </div>
      <div className="email-detail__head">
        <div className="email-detail__subject">{detail?.subject ?? meta.subject ?? '(no subject)'}</div>
      </div>
      {loading && <div className="repo-loading">Loading thread…</div>}
      {error && <div className="repo-error">{error}</div>}
      {detail && detail.linkedRefs.length > 0 && (
        <div className="email-linked-refs">
          <div className="email-linked-refs__label">Detected links · open in ByteQuay</div>
          <div className="email-linked-refs__items">
            {detail.linkedRefs.map(ref => (
              <button
                key={ref.url}
                type="button"
                className="email-linked-refs__item"
                onClick={() => onOpenLinkedRef(ref)}
                title={ref.url}
              >
                <span className="email-linked-refs__kind">{ref.kind}</span>
                <span className="email-linked-refs__num">
                  {ref.kind === 'COMMIT' ? ref.slug : `#${ref.slug}`}
                </span>
                <span className="email-linked-refs__repo">{ref.owner}/{ref.repo}</span>
                <span className="email-linked-refs__arrow">→</span>
              </button>
            ))}
          </div>
        </div>
      )}
      {detail && (
        <div className="email-detail__messages">
          {detail.messages.map((m, i) => (
            <ThreadMessage key={m.id} message={m} isLast={i === detail.messages.length - 1} />
          ))}
        </div>
      )}
      {detail && (
        <div className="email-reply">
          {composerOpen ? (
            <>
              <textarea
                className="email-reply__textarea"
                placeholder={`Reply to ${shortenFrom(detail.messages[detail.messages.length - 1]?.from ?? '')}…`}
                value={replyBody}
                onChange={e => setReplyBody(e.target.value)}
                rows={6}
                autoFocus
                disabled={sendState === 'sending'}
              />
              {sendError && <div className="repo-error">{sendError}</div>}
              <div className="email-reply__buttons">
                <button
                  type="button"
                  className="button button--primary"
                  onClick={() => void handleSend()}
                  disabled={sendState === 'sending' || !replyBody.trim()}
                >
                  {sendState === 'sending' ? 'Sending…' : 'Send'}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => { setComposerOpen(false); setSendError(null); }}
                  disabled={sendState === 'sending'}
                >
                  Cancel
                </button>
              </div>
            </>
          ) : (
            <button
              type="button"
              className="button button--secondary email-reply__open"
              onClick={() => setComposerOpen(true)}
            >
              ↩ Reply
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ThreadMessage({ message, isLast }: { message: EmailMessageDetailDto; isLast: boolean }) {
  return (
    <div className={`thread-message${isLast ? ' thread-message--latest' : ''}`}>
      <div className="thread-message__head">
        <div className="thread-message__from">{message.from}</div>
        <div className="thread-message__time">{new Date(message.receivedAt).toLocaleString()}</div>
      </div>
      {message.to && <div className="thread-message__to">to {message.to}</div>}
      <div className="thread-message__body">
        {message.bodyHtml
          ? <SanitizedHtml html={message.bodyHtml} />
          : <pre className="thread-message__plain">{message.bodyText ?? '(empty body)'}</pre>}
      </div>
    </div>
  );
}

/** Renders Gmail HTML inside an iframe sandbox.
 *
 *  <p>Sandbox tokens:
 *  <ul>
 *    <li>{@code allow-top-navigation-by-user-activation} — link clicks
 *        navigate the top frame so {@code main.ts}'s will-navigate
 *        handler routes them into the in-app overlay (with ←/→/×
 *        chrome).</li>
 *    <li>{@code allow-same-origin} — lets the parent measure
 *        {@code contentDocument.body.scrollHeight} on load so we can
 *        size the iframe to its content. Safe without allow-scripts:
 *        the email can't execute JS, can't access localStorage, can't
 *        submit forms — it's a read-only document tree.</li>
 *  </ul>
 *  Without auto-sizing the iframe held a fixed slab regardless of
 *  content, so the "boundary" between the email and the empty space
 *  below sat in the middle of the pane. */
function SanitizedHtml({ html }: { html: string }) {
  const safeHtml = html.replace(/<script[\s\S]*?<\/script>/gi, '');
  const wrapped = '<base target="_top">' + safeHtml;
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  // Tracks the ResizeObserver attached to the iframe's body. Disconnected
  // on each new load (srcDoc change) and on unmount.
  const observerRef = useRef<ResizeObserver | null>(null);
  // Resize the iframe to fit its content. Runs on each load (srcDoc
  // change triggers a load), once more after a small delay for inline
  // images to settle, and again whenever the ResizeObserver fires —
  // covers window resize, dragging the inbox/detail split, and any
  // late content reflow inside the email itself.
  const fit = () => {
    const iframe = iframeRef.current;
    if (!iframe) return;
    let doc: Document | null = null;
    try {
      doc = iframe.contentDocument;
    }
    catch {
      return;
    }
    if (!doc?.body || !doc.documentElement) return;
    const height = Math.max(doc.body.scrollHeight, doc.documentElement.scrollHeight);
    // +4 buys a tiny margin so the last line never sits flush against
    // the iframe's bottom edge (which causes a phantom scrollbar).
    iframe.style.height = `${height + 4}px`;
  };

  // Disconnect the observer on unmount. The per-load disconnect lives
  // inside onLoad below — that path swaps observers as the document is
  // replaced, this one cleans up the final outstanding observer.
  useEffect(() => {
    return () => {
      if (observerRef.current) {
        observerRef.current.disconnect();
        observerRef.current = null;
      }
    };
  }, []);

  return (
    <iframe
      ref={iframeRef}
      title="Email body"
      className="email-detail__iframe"
      sandbox="allow-top-navigation-by-user-activation allow-same-origin"
      srcDoc={wrapped}
      onLoad={() => {
        fit();
        // Re-measure after images settle. 200ms covers most inline
        // images cached by Gmail's CDN; for slow loads the ResizeObserver
        // below will catch later reflows.
        setTimeout(fit, 200);
        // Watch the iframe's body for size changes — fires when the
        // user resizes the window, drags the pane split, or when an
        // image loads later than our 200ms timer. Each load swaps the
        // observer (the previous document is being torn down).
        if (observerRef.current) observerRef.current.disconnect();
        const doc = (() => { try { return iframeRef.current?.contentDocument; } catch { return null; } })();
        if (doc?.body && typeof ResizeObserver !== 'undefined') {
          const ro = new ResizeObserver(() => fit());
          ro.observe(doc.body);
          observerRef.current = ro;
        }
      }}
    />
  );
}

/** Strips the address from a "Display Name <addr@host>" header. */
function shortenFrom(raw: string): string {
  if (!raw) return '';
  const angleStart = raw.indexOf('<');
  if (angleStart > 0) {
    const name = raw.slice(0, angleStart).trim().replace(/^"|"$/g, '');
    return name || raw;
  }
  return raw;
}

/** Light-weight relative-time formatter. */
function formatRelative(iso: string): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '';
  const deltaSec = Math.floor((Date.now() - t) / 1000);
  if (deltaSec < 60) return 'just now';
  if (deltaSec < 3600) return Math.floor(deltaSec / 60) + 'm ago';
  if (deltaSec < 86400) return Math.floor(deltaSec / 3600) + 'h ago';
  if (deltaSec < 86400 * 7) return Math.floor(deltaSec / 86400) + 'd ago';
  return new Date(t).toLocaleDateString();
}
