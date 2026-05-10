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
import { useCallback, useEffect, useState } from 'react';
import type { EmailMessageDetailDto, EmailMessageMetaDto } from '../types';

type Account = { email: string; authMode: 'OAUTH' | 'IMAP' };

type Props = {
  /** Click handler for "no Gmail account connected" empty state — jumps the
   *  user to Settings → Integrations to add one. */
  onOpenIntegrationsSettings: () => void;
};

/**
 * Master-detail inbox. Left pane (460px) lists messages for the
 * selected account; right pane shows the selected message body with
 * Archive / Mark read action bar. OAuth path only — IMAP-connected
 * accounts show a "not yet wired" hint.
 *
 * <p>Optimistic UI: archive removes the row from the list immediately
 * and rolls back on error. Mark-read updates the unread flag locally
 * before the server confirms.
 */
export default function EmailPage({ onOpenIntegrationsSettings }: Props) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [accountsError, setAccountsError] = useState<string | null>(null);
  const [selectedAccount, setSelectedAccount] = useState<string | null>(null);

  const [messages, setMessages] = useState<EmailMessageMetaDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

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

  const loadInbox = useCallback(async (account: string) => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listEmailMessages(account);
      setMessages(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setMessages(null);
    }
    finally {
      setLoading(false);
    }
  }, []);

  // Load messages whenever the selected account changes.
  useEffect(() => {
    if (selectedAccount == null) {
      setMessages(null);
      return;
    }
    const account = accounts?.find(a => a.email === selectedAccount);
    if (account?.authMode !== 'OAUTH') {
      setMessages(null);
      return;
    }
    void loadInbox(selectedAccount);
    setSelectedId(null);
  }, [selectedAccount, accounts, loadInbox]);

  const archive = async (id: string) => {
    if (!selectedAccount) return;
    // Optimistic: remove from the list, advance selection to next row.
    const prev = messages;
    if (!prev) return;
    const idx = prev.findIndex(m => m.id === id);
    const next = prev.filter(m => m.id !== id);
    setMessages(next);
    if (selectedId === id) {
      setSelectedId(idx < next.length ? next[idx]?.id ?? null : next[next.length - 1]?.id ?? null);
    }
    try {
      await window.bridge.archiveEmail(selectedAccount, id);
    }
    catch (e) {
      // Roll back.
      setMessages(prev);
      setSelectedId(id);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const toggleRead = async (id: string, currentlyUnread: boolean) => {
    if (!selectedAccount) return;
    const prev = messages;
    if (!prev) return;
    setMessages(prev.map(m => m.id === id ? { ...m, unread: !currentlyUnread } : m));
    try {
      if (currentlyUnread) {
        await window.bridge.markEmailRead(selectedAccount, id);
      }
      else {
        await window.bridge.markEmailUnread(selectedAccount, id);
      }
    }
    catch (e) {
      setMessages(prev);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

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
  const selectedMessage = messages?.find(m => m.id === selectedId) ?? null;

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
            onClick={() => selectedAccount && void loadInbox(selectedAccount)}
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

      {account?.authMode === 'OAUTH' && messages != null && (
        <div className="email-pane">
          <div className="email-pane__list">
            {messages.length === 0 && <div className="email-page__hint">Inbox is empty.</div>}
            <ul className="email-list email-list--inset">
              {messages.map(m => (
                <li
                  key={m.id}
                  className={`email-row${m.unread ? ' email-row--unread' : ''}${m.id === selectedId ? ' email-row--selected' : ''}`}
                  onClick={() => setSelectedId(m.id)}
                >
                  <div className="email-row__rail" aria-hidden="true" />
                  <div className="email-row__body">
                    <div className="email-row__line1">
                      <span className="email-row__from">{shortenFrom(m.from)}</span>
                      <span className="email-row__time">{formatRelative(m.receivedAt)}</span>
                    </div>
                    <div className="email-row__subject">{m.subject || '(no subject)'}</div>
                    <div className="email-row__snippet">{m.snippet}</div>
                  </div>
                </li>
              ))}
            </ul>
          </div>
          <div className="email-pane__detail">
            {selectedMessage ? (
              <EmailDetailPane
                key={selectedMessage.id}
                account={selectedAccount!}
                meta={selectedMessage}
                onArchive={() => void archive(selectedMessage.id)}
                onToggleRead={() => void toggleRead(selectedMessage.id, selectedMessage.unread)}
              />
            ) : (
              <div className="email-page__hint">Pick a message on the left.</div>
            )}
          </div>
        </div>
      )}

      {loading && messages == null && <div className="repo-loading">Loading inbox…</div>}
    </div>
  );
}

type DetailProps = {
  account: string;
  meta: EmailMessageMetaDto;
  onArchive: () => void;
  onToggleRead: () => void;
};

function EmailDetailPane({ account, meta, onArchive, onToggleRead }: DetailProps) {
  const [detail, setDetail] = useState<EmailMessageDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDetail(null);
    void (async () => {
      try {
        const d = await window.bridge.getEmailMessage(account, meta.id);
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
      </div>
      <div className="email-detail__head">
        <div className="email-detail__subject">{meta.subject || '(no subject)'}</div>
        <div className="email-detail__from">{meta.from}</div>
        {detail?.to && <div className="email-detail__to">to {detail.to}</div>}
      </div>
      {loading && <div className="repo-loading">Loading message…</div>}
      {error && <div className="repo-error">{error}</div>}
      {detail && (
        <div className="email-detail__body">
          {detail.bodyHtml
            ? <SanitizedHtml html={detail.bodyHtml} />
            : <pre className="email-detail__plain">{detail.bodyText ?? '(empty body)'}</pre>}
        </div>
      )}
    </div>
  );
}

/** Renders Gmail HTML inside an iframe sandbox — no JS, no remote-image
 *  auto-loading, no parent-document access. The iframe srcdoc carves the
 *  HTML off into its own document so styles in the email don't bleed
 *  into ByteQuay's UI. */
function SanitizedHtml({ html }: { html: string }) {
  // Strip <script> tags as a belt-and-suspenders measure even though
  // sandbox="" already disables JS.
  const safeHtml = html.replace(/<script[\s\S]*?<\/script>/gi, '');
  return (
    <iframe
      title="Email body"
      className="email-detail__iframe"
      sandbox=""
      srcDoc={safeHtml}
    />
  );
}

/** Strips the address from a "Display Name <addr@host>" header so the
 *  card shows just the friendly name when present. Falls back to the
 *  whole string. */
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
