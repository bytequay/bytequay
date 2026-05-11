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
import SettingCard from '../shared/SettingCard';

type Account = { email: string; authMode: 'OAUTH' | 'IMAP' };

/**
 * Settings → Email. Currently a single concern: per-account mute
 * list management. The mute list itself is built up via the Mute
 * button on the email-thread detail pane; this page exists so the
 * user can review and un-mute senders without finding one of their
 * threads in the inbox first.
 *
 * <p>Local-only — the list never propagates to gmail.com.
 */
function EmailSettingsPage() {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [mutedByAccount, setMutedByAccount] = useState<Record<string, string[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Per-row pending state so the Unmute button can show "Removing…"
  // without freezing the whole page.
  const [unmuting, setUnmuting] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listGmailAccounts();
      setAccounts(list);
      const byAccount: Record<string, string[]> = {};
      await Promise.all(list.map(async acc => {
        try {
          byAccount[acc.email] = await window.bridge.listMutedEmailSenders(acc.email);
        }
        catch {
          byAccount[acc.email] = [];
        }
      }));
      setMutedByAccount(byAccount);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const handleUnmute = async (account: string, sender: string) => {
    const key = `${account}|${sender}`;
    setUnmuting(key);
    // Optimistic — drop the row immediately and roll back on failure.
    const prev = mutedByAccount[account] ?? [];
    setMutedByAccount(curr => ({ ...curr, [account]: prev.filter(s => s !== sender) }));
    try {
      await window.bridge.unmuteEmailSender(account, sender);
    }
    catch (e) {
      setMutedByAccount(curr => ({ ...curr, [account]: prev }));
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setUnmuting(null);
    }
  };

  if (loading && accounts == null) {
    return <div className="repo-loading">Loading…</div>;
  }
  if (error) {
    return <div className="repo-error">{error}</div>;
  }
  if (!accounts || accounts.length === 0) {
    return (
      <SettingCard title="Muted senders">
        <p className="settings-help">
          No email accounts connected. Connect one under Integrations
          to start using the inbox.
        </p>
      </SettingCard>
    );
  }

  return (
    <>
      <SettingCard
        title="Muted senders"
        hint="Threads from these senders are hidden from your inbox in ByteQuay. The list is local to this app — gmail.com still shows them."
      >
        {accounts.map(acc => {
          const muted = mutedByAccount[acc.email] ?? [];
          return (
            <div key={acc.email} className="email-mute-account">
              <div className="email-mute-account__header">
                {acc.email}
                <span className="email-mute-account__count">
                  {muted.length === 0 ? 'none' : `${muted.length} muted`}
                </span>
              </div>
              {muted.length === 0 ? (
                <p className="email-mute-account__empty">
                  Use the 🔕 Mute sender button on a thread to add an address here.
                </p>
              ) : (
                <ul className="email-mute-list">
                  {muted.map(sender => (
                    <li key={sender} className="email-mute-list__row">
                      <code className="email-mute-list__addr">{sender}</code>
                      <button
                        type="button"
                        className="button button--secondary email-mute-list__btn"
                        onClick={() => void handleUnmute(acc.email, sender)}
                        disabled={unmuting === `${acc.email}|${sender}`}
                      >
                        {unmuting === `${acc.email}|${sender}` ? 'Removing…' : 'Unmute'}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          );
        })}
      </SettingCard>
    </>
  );
}

export default EmailSettingsPage;
