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
  // Tracks thread IDs we've already auto-marked-read so re-rendering or
  // toggling back to unread + reselecting doesn't loop.
  const autoMarkedRef = useRef<Set<string>>(new Set());

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

  const archive = async (id: string) => {
    if (!selectedAccount) return;
    // Optimistic: remove the thread, advance selection to next row.
    const prev = threads;
    if (!prev) return;
    const idx = prev.findIndex(t => t.id === id);
    const next = prev.filter(t => t.id !== id);
    setThreads(next);
    if (selectedThreadId === id) {
      setSelectedThreadId(idx < next.length ? next[idx]?.id ?? null : next[next.length - 1]?.id ?? null);
    }
    try {
      await window.bridge.archiveEmailThread(selectedAccount, id);
    }
    catch (e) {
      setThreads(prev);
      setSelectedThreadId(id);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const toggleRead = async (id: string, currentlyUnread: boolean) => {
    if (!selectedAccount) return;
    const prev = threads;
    if (!prev) return;
    setThreads(prev.map(t => t.id === id ? { ...t, unread: !currentlyUnread } : t));
    try {
      if (currentlyUnread) {
        await window.bridge.markEmailThreadRead(selectedAccount, id);
      }
      else {
        await window.bridge.markEmailThreadUnread(selectedAccount, id);
      }
    }
    catch (e) {
      setThreads(prev);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  // Auto-mark-as-read on selection, matching Gmail's web UI: opening
  // an unread thread immediately flips it to read locally and fires a
  // background mark-read call to Google. Per-thread dedup via
  // autoMarkedRef so toggling back to unread + reselecting doesn't
  // loop the API call.
  useEffect(() => {
    if (!selectedAccount || !selectedThreadId || !threads) return;
    const t = threads.find(th => th.id === selectedThreadId);
    if (!t || !t.unread) return;
    if (autoMarkedRef.current.has(selectedThreadId)) return;
    autoMarkedRef.current.add(selectedThreadId);
    void toggleRead(selectedThreadId, true);
    // toggleRead is intentionally not in deps — capturing it as a closure
    // is fine, we only want to re-run when selectedThreadId changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedThreadId, selectedAccount]);

  // Reset the auto-mark dedup when the account changes — per-account
  // threads are a fresh universe.
  useEffect(() => {
    autoMarkedRef.current = new Set();
  }, [selectedAccount]);

  // Keyboard shortcuts: j/k or arrow keys to walk the list, e to
  // archive, u to toggle read. Suppressed when focus is in an input
  // or contenteditable so it doesn't fight typing.
  useEffect(() => {
    const acc = accounts?.find(a => a.email === selectedAccount);
    if (acc?.authMode !== 'OAUTH' || !threads || threads.length === 0) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return;
      }
      const idx = selectedThreadId
        ? threads.findIndex(t => t.id === selectedThreadId)
        : -1;
      if (e.key === 'j' || e.key === 'ArrowDown') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.min(idx + 1, threads.length - 1);
        setSelectedThreadId(threads[next].id);
      }
      else if (e.key === 'k' || e.key === 'ArrowUp') {
        e.preventDefault();
        const next = idx < 0 ? 0 : Math.max(idx - 1, 0);
        setSelectedThreadId(threads[next].id);
      }
      else if (e.key === 'e' && selectedThreadId) {
        e.preventDefault();
        void archive(selectedThreadId);
      }
      else if ((e.key === 'u' || e.key === 'U') && selectedThreadId) {
        e.preventDefault();
        const t = threads.find(th => th.id === selectedThreadId);
        if (t) void toggleRead(selectedThreadId, t.unread);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // archive / toggleRead are stable enough; we re-bind when threads
    // or selection change so the closure sees the latest state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threads, selectedThreadId, selectedAccount, accounts]);

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

      {account?.authMode === 'OAUTH' && threads != null && (
        <div className="email-pane">
          <div className="email-pane__list">
            {threads.length === 0 && <div className="email-page__hint">Inbox is empty.</div>}
            <ul className="email-list email-list--inset">
              {threads.map(t => (
                <li
                  key={t.id}
                  className={`email-row${t.unread ? ' email-row--unread' : ''}${t.id === selectedThreadId ? ' email-row--selected' : ''}`}
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
                onArchive={() => void archive(selectedThread.id)}
                onToggleRead={() => void toggleRead(selectedThread.id, selectedThread.unread)}
                onOpenLinkedRef={onOpenLinkedRef}
              />
            ) : (
              <div className="email-page__hint">Pick a thread on the left.</div>
            )}
          </div>
        </div>
      )}

      {loading && threads == null && <div className="repo-loading">Loading inbox…</div>}
    </div>
  );
}

type DetailProps = {
  account: string;
  meta: EmailThreadMetaDto;
  onArchive: () => void;
  onToggleRead: () => void;
  onOpenLinkedRef: (ref: LinkedRefDto) => void;
};

function ThreadDetailPane({ account, meta, onArchive, onToggleRead, onOpenLinkedRef }: DetailProps) {
  const [detail, setDetail] = useState<EmailThreadDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDetail(null);
    void (async () => {
      try {
        const d = await window.bridge.getEmailThread(account, meta.id);
        if (cancelled) return;
        setDetail(d);
      }
      catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
      }
      finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [account, meta.id]);

  return (
    <div className="email-detail">
      <div className="email-detail__actions">
        <button className="button button--primary" type="button" onClick={onArchive}>
          📥 Archive
        </button>
        <button className="button button--secondary" type="button" onClick={onToggleRead}>
          {meta.unread ? '✓ Mark as read' : '◌ Mark as unread'}
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

/** Renders Gmail HTML inside an iframe sandbox — no JS, no
 *  parent-document access. {@code allow-popups} lets clicks on
 *  links inside the email open via the main-process
 *  setWindowOpenHandler, which routes them into the in-app browser
 *  overlay. {@code <base target="_blank">} forces every link to
 *  use the popup path so we don't lose the iframe's content to
 *  in-frame navigation. */
function SanitizedHtml({ html }: { html: string }) {
  const safeHtml = html.replace(/<script[\s\S]*?<\/script>/gi, '');
  const wrapped = '<base target="_blank">' + safeHtml;
  return (
    <iframe
      title="Email body"
      className="email-detail__iframe"
      sandbox="allow-popups"
      srcDoc={wrapped}
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
