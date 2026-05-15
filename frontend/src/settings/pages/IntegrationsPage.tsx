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
import type { CredentialDto } from '../../types';
import SettingCard from '../shared/SettingCard';
import SettingRow from '../shared/SettingRow';

const SLACK_NAME = 'slack-oauth-app';
const SLACK_REDIRECT = 'bytequay://slack-oauth-callback';

/**
 * Connect-account hub. Slack uses one-click PKCE; Gmail uses IMAP +
 * app password — no Cloud Console setup, no OAuth verification dance.
 * Anything saved here stays encrypted on this machine.
 */
function IntegrationsPage() {
  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Integrations</h2>
          <div className="settings-shell-page__subtitle">
            Slack connects with one click via PKCE. Gmail connects with an
            app password by default — OAuth is available under Advanced for
            anyone who prefers it. Anything saved here stays encrypted on
            this machine.
          </div>
        </div>
      </div>

      <SlackSection />
      <GmailSection />
    </>
  );
}

/* ─── Slack ────────────────────────────────────────────────────── */

function SlackSection() {
  const [credential, setCredential] = useState<CredentialDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listCredentials('INTEGRATION');
      const existing = list.find(c => c.name === SLACK_NAME) ?? null;
      setCredential(existing);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const startEdit = () => {
    setEditing(true);
    setClientId(credential?.label ?? '');
    setClientSecret('');
  };

  const cancelEdit = () => {
    setEditing(false);
    setClientId('');
    setClientSecret('');
  };

  const save = async () => {
    if (!clientId.trim()) {
      setError('Client ID must not be blank.');
      return;
    }
    if (!clientSecret.trim()) {
      setError('Client Secret must not be blank.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await window.bridge.upsertCredential({
        type: 'INTEGRATION',
        name: SLACK_NAME,
        value: clientSecret.trim(),
        label: clientId.trim(),
        notes: null,
      });
      setEditing(false);
      setClientSecret('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!confirm('Delete the saved Slack app credentials? You\'ll need to paste them again to connect a workspace.')) return;
    setSaving(true);
    setError(null);
    try {
      await window.bridge.deleteCredential('INTEGRATION', SLACK_NAME);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  // The BYO sections used to be the primary connect path; PKCE made them
  // unnecessary for end users. We keep them around as an "Advanced"
  // disclosure for anyone running their own Slack app (forks, custom
  // distributions, builds shipped without an embedded client_id).
  // Open-by-default when a credential already exists so the existing
  // power-user flow stays one click away.
  const detailsOpen = credential != null || editing;

  return (
    <>
      <SettingCard
        title="Slack"
        hint={
          <>
            ByteQuay's <b>Slack</b> tab connects to your workspace with one
            click via Slack's PKCE flow — no app registration on your side,
            no credentials to manage. Head over there to connect or
            disconnect.
          </>
        }
      />

      <details className="settings-advanced-details" open={detailsOpen}>
        <summary>Advanced — use your own Slack app</summary>
        <SettingCard
          title="Slack — bring your own app"
          hint={
            <>
              Power users can register their own Slack app and have ByteQuay
              authenticate against it instead of the embedded one. Useful
              for forks, custom builds, or workspaces with strict app
              policies. Stored values stay encrypted on this machine and
              never leave it.
            </>
          }
        />

        <SlackSetupGuide />

        {loading && <div className="repo-loading">Loading…</div>}
        {error && <div className="repo-error">{error}</div>}

        {!loading && !editing && credential && (
          <SettingCard
            title="Saved Slack app"
            action={
              <a
                className="button button--secondary"
                href="https://api.slack.com/apps"
                target="_blank"
                rel="noreferrer"
              >
                Open Slack apps ↗
              </a>
            }
          >
            <SettingRow
              title="Client ID"
              description={<code>{credential.label ?? '—'}</code>}
              control={<></>}
            />
            <SettingRow
              title="Client Secret"
              description={<code>{credential.preview}</code>}
              control={
                <>
                  <button className="button button--secondary" type="button" onClick={startEdit}>
                    Replace
                  </button>
                  <button className="button button--danger" type="button" onClick={() => void remove()} disabled={saving}>
                    Delete
                  </button>
                </>
              }
            />
          </SettingCard>
        )}

        {!loading && !editing && !credential && (
          <SettingCard title="Add Slack app credentials">
            <SettingRow
              title="No Slack app saved"
              description="Optional — only needed if you're not using the embedded one-click connect."
              control={
                <button className="button button--primary" type="button" onClick={startEdit}>
                  + Add Slack app
                </button>
              }
            />
          </SettingCard>
        )}

        {!loading && editing && (
        <SettingCard title={credential ? 'Replace Slack app' : 'Add Slack app'}>
          <SettingRow
            title="Client ID"
            description="From your app's Basic Information page on api.slack.com. Public — fine to paste."
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="text"
                value={clientId}
                onChange={e => setClientId(e.target.value)}
                placeholder="1234567890.1234567890123"
                autoFocus
              />
            }
          />
          <SettingRow
            title="Client Secret"
            description="Right below Client ID. Stored encrypted on this machine."
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="password"
                value={clientSecret}
                onChange={e => setClientSecret(e.target.value)}
                placeholder="••••••••••••••••••••••••••••••••"
              />
            }
          />
          <SettingRow
            title=""
            control={
              <>
                <button className="button button--primary" type="button" onClick={() => void save()} disabled={saving}>
                  {saving ? 'Saving…' : credential ? 'Replace' : 'Save'}
                </button>
                <button className="button button--secondary" type="button" onClick={cancelEdit} disabled={saving}>
                  Cancel
                </button>
              </>
            }
          />
        </SettingCard>
        )}
      </details>
    </>
  );
}

function SlackSetupGuide() {
  return (
    <SettingCard title="How to register a Slack app">
      <ol className="settings-setup-steps">
        <li>
          Open <a href="https://api.slack.com/apps" target="_blank" rel="noreferrer">api.slack.com/apps</a>{' '}
          and click <b>Create New App → From scratch</b>. Name it{' '}
          <code>ByteQuay</code> (or anything you like) and pick the workspace you want
          to connect.
        </li>
        <li>
          Open <b>OAuth &amp; Permissions</b>. Under <b>Redirect URLs</b>, add{' '}
          <code>{SLACK_REDIRECT}</code> and click <b>Save URLs</b>.
        </li>
        <li>
          On the same page, scroll to <b>Scopes → User Token Scopes</b> and add all
          ten of the following:
          <code className="settings-setup-scopes">
            users:read, channels:history, groups:history, im:history, mpim:history,
            channels:read, groups:read, im:read, mpim:read, chat:write
          </code>
          (Bot scopes are <i>not</i> needed — ByteQuay acts on your behalf as a user
          token.)
        </li>
        <li>
          Click <b>Install to Workspace</b> at the top of <b>OAuth &amp; Permissions</b>.
          Slack will show an authorization screen — approve it. Don't worry about the
          token Slack displays after install; ByteQuay re-runs OAuth from inside the
          app to get its own copy.
        </li>
        <li>
          Open <b>Basic Information</b>. Copy <b>Client ID</b> and <b>Client Secret</b>{' '}
          (you'll need to click <i>Show</i> on the secret) and paste them into the form
          below. Save.
        </li>
        <li>
          Switch to the <b>Slack</b> tab in the sidebar and click <b>Connect Slack workspace</b>.
          Your browser will open Slack's authorize page, then redirect back to ByteQuay.
        </li>
      </ol>
    </SettingCard>
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
