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
import type { EmailMessageMetaDto } from '../types';

type Account = { email: string; authMode: 'OAUTH' | 'IMAP' };

type Props = {
  /** Click handler for "no Gmail account connected" empty state — jumps the
   *  user to Settings → Integrations to add one. */
  onOpenIntegrationsSettings: () => void;
};

/**
 * First slice of the Email surface — read-only inbox. Accounts list
 * lives at the top, picker for which account to view (auto-picks the
 * first one). Below it, a flat list of message cards. No preview
 * pane, no archive/mark-read yet — those land in the next slice once
 * we've validated the OAuth path against a real Gmail inbox.
 *
 * <p>OAuth-only for now. IMAP-connected accounts are visible in the
 * picker but selecting one shows a "not yet implemented" hint until
 * the IMAP adapter ships.
 */
export default function EmailPage({ onOpenIntegrationsSettings }: Props) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [accountsError, setAccountsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const [messages, setMessages] = useState<EmailMessageMetaDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load accounts on mount; auto-select the first OAuth account.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listGmailAccounts();
        if (cancelled) return;
        setAccounts(list);
        const firstOauth = list.find(a => a.authMode === 'OAUTH');
        setSelected(firstOauth?.email ?? list[0]?.email ?? null);
      }
      catch (e) {
        if (cancelled) return;
        setAccountsError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Load messages whenever the selected account changes.
  useEffect(() => {
    if (selected == null) {
      setMessages(null);
      return;
    }
    const account = accounts?.find(a => a.email === selected);
    if (account?.authMode !== 'OAUTH') {
      setMessages(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        const list = await window.bridge.listEmailMessages(selected);
        if (cancelled) return;
        setMessages(list);
      }
      catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
        setMessages(null);
      }
      finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [selected, accounts]);

  const refresh = async () => {
    if (!selected) return;
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listEmailMessages(selected);
      setMessages(list);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
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

  const selectedAccount = accounts.find(a => a.email === selected);

  return (
    <div className="email-page">
      <header className="email-page__header">
        <div className="email-page__accounts">
          {accounts.map(acc => (
            <button
              key={acc.email}
              type="button"
              className={`email-account-chip${acc.email === selected ? ' email-account-chip--active' : ''}`}
              onClick={() => setSelected(acc.email)}
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
            onClick={() => void refresh()}
            disabled={loading || selectedAccount?.authMode !== 'OAUTH'}
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {selectedAccount?.authMode === 'IMAP' && (
        <div className="email-page__hint">
          IMAP inbox loading isn't wired up yet — only OAuth-connected accounts can
          be browsed in this slice. Switch to an OAuth account or check back in
          the next iteration.
        </div>
      )}

      {error && <div className="repo-error">{error}</div>}

      {selectedAccount?.authMode === 'OAUTH' && messages != null && messages.length === 0 && (
        <div className="email-page__hint">Inbox is empty.</div>
      )}

      {selectedAccount?.authMode === 'OAUTH' && messages != null && messages.length > 0 && (
        <ul className="email-list">
          {messages.map(m => <EmailRow key={m.id} message={m} />)}
        </ul>
      )}

      {loading && messages == null && <div className="repo-loading">Loading inbox…</div>}
    </div>
  );
}

function EmailRow({ message }: { message: EmailMessageMetaDto }) {
  return (
    <li className={`email-row${message.unread ? ' email-row--unread' : ''}`}>
      <div className="email-row__rail" aria-hidden="true" />
      <div className="email-row__body">
        <div className="email-row__line1">
          <span className="email-row__from">{shortenFrom(message.from)}</span>
          <span className="email-row__time">{formatRelative(message.receivedAt)}</span>
        </div>
        <div className="email-row__subject">{message.subject || '(no subject)'}</div>
        <div className="email-row__snippet">{message.snippet}</div>
      </div>
    </li>
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

/** Light-weight relative-time formatter — minutes/hours/days from now,
 *  no external dep. */
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
