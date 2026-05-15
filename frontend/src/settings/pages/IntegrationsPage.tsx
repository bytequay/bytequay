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
import SettingRow from '../shared/SettingRow';

/**
 * Connect-account hub. Gmail connects with an IMAP app password —
 * no Cloud Console setup, no OAuth verification dance. Anything
 * saved here stays encrypted on this machine.
 */
function IntegrationsPage() {
  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Integrations</h2>
          <div className="settings-shell-page__subtitle">
            Gmail connects with an app password by default — OAuth is
            available under Advanced for anyone who prefers it.
            Anything saved here stays encrypted on this machine.
          </div>
        </div>
      </div>

      <GmailSection />
    </>
  );
}

/* ─── Gmail ────────────────────────────────────────────────────── */

type GmailConnectStatus = 'idle' | 'awaiting' | 'error';

function GmailSection() {
  const [accounts, setAccounts] = useState<Array<{ email: string; authMode: 'IMAP' }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [connectStatus, setConnectStatus] = useState<GmailConnectStatus>('idle');
  const [connectError, setConnectError] = useState<string | null>(null);

  const [imapEmail, setImapEmail] = useState('');
  const [imapAppPassword, setImapAppPassword] = useState('');

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const accs = await window.bridge.listGmailAccounts()
        .catch((): Array<{ email: string; authMode: 'IMAP' }> => []);
      setAccounts(accs);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const connectImap = async () => {
    if (!imapEmail.trim() || !imapAppPassword.trim()) {
      setConnectStatus('error');
      setConnectError('Email and app password must both be set.');
      return;
    }
    setConnectStatus('awaiting');
    setConnectError(null);
    try {
      await window.bridge.connectGmailImap(imapEmail.trim(), imapAppPassword);
      setConnectStatus('idle');
      setImapEmail('');
      setImapAppPassword('');
      await load();
    } catch (e) {
      setConnectStatus('error');
      setConnectError(e instanceof Error ? e.message : String(e));
    }
  };

  const disconnectAccount = async (email: string) => {
    if (!confirm(`Disconnect ${email}? You'll need to re-enter the app password to reconnect.`)) return;
    try {
      await window.bridge.disconnectGmailAccount(email);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <>
      <SettingCard
        title="Gmail"
        hint={
          <>
            Connect one or more Gmail accounts so the <b>Email</b> tab shows
            their inbox alongside your PR review queue. ByteQuay uses IMAP
            with a Google app password — works on every Google account that
            has 2FA enabled, no Cloud Console setup needed.
          </>
        }
      />

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {!loading && (
        <SettingCard
          title="Connected Gmail accounts"
          hint={accounts.length === 0
            ? 'No accounts connected yet. Use the form below to add one.'
            : `${accounts.length} account${accounts.length === 1 ? '' : 's'} connected.`}
        >
          <SettingRow
            title="Email"
            description="The Gmail address to connect."
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="email"
                value={imapEmail}
                onChange={e => setImapEmail(e.target.value)}
                placeholder="you@gmail.com"
                // "off" alone isn't enough on Chromium — autocomplete="new-password"
                // is the documented escape hatch that actually suppresses the
                // saved-credential dropdown for non-signup forms.
                autoComplete="off"
              />
            }
          />
          <SettingRow
            title="App password"
            description={<>16-char string from <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noreferrer">myaccount.google.com/apppasswords</a> (requires 2FA enabled). Whitespace is stripped — paste the four 4-char groups as shown.</>}
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="password"
                value={imapAppPassword}
                onChange={e => setImapAppPassword(e.target.value)}
                placeholder="xxxx xxxx xxxx xxxx"
                // Block the browser from auto-filling a saved Google
                // password here — that's the wrong credential and would
                // 401 against Gmail IMAP.
                autoComplete="new-password"
              />
            }
          />
          <SettingRow
            title=""
            control={
              <button
                className="button button--primary"
                type="button"
                onClick={() => void connectImap()}
                disabled={connectStatus === 'awaiting'}
              >
                {connectStatus === 'awaiting' ? 'Verifying…' : '+ Connect Gmail account'}
              </button>
            }
          />

          {connectStatus === 'error' && connectError && (
            <div className="repo-error">{connectError}</div>
          )}

          {accounts.map(acc => (
            <SettingRow
              key={acc.email}
              title={acc.email}
              description="IMAP app password stored locally."
              control={
                <button
                  className="button button--danger"
                  type="button"
                  onClick={() => void disconnectAccount(acc.email)}
                >
                  Disconnect
                </button>
              }
            />
          ))}
        </SettingCard>
      )}
    </>
  );
}

export default IntegrationsPage;
