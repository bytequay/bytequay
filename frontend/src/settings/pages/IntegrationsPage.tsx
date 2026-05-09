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
const GMAIL_NAME = 'gmail-oauth-app';
const GMAIL_REDIRECT = 'bytequay://gmail-oauth-callback';

/**
 * BYO OAuth app configuration for the third-party providers ByteQuay
 * talks to. Each provider gets its own card: the user pastes their
 * own {@code client_id} / {@code client_secret} from the provider's
 * developer console, then connects accounts through the resulting
 * OAuth dance. Both pieces of state live in the local credentials
 * vault and never leave this machine.
 */
function IntegrationsPage() {
  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Integrations</h2>
          <div className="settings-shell-page__subtitle">
            Bring-your-own OAuth app credentials for Slack and Gmail.
            Both stay encrypted on this machine.
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

  return (
    <>
      <SettingCard
        title="Slack — bring your own app"
        hint={
          <>
            ByteQuay doesn't ship a hosted Slack app yet, so each user registers a small
            personal Slack app and pastes its <code>client_id</code> and <code>client_secret</code> here.
            Both values are stored encrypted on this machine and never leave it.
            Follow the steps below — it takes about two minutes.
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
            description="Without a Slack app, the Connect button on the Slack tab can't kick off OAuth."
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

type GmailConnectStatus = 'idle' | 'launching' | 'awaiting' | 'error';

function GmailSection() {
  const [credential, setCredential] = useState<CredentialDto | null>(null);
  const [accounts, setAccounts] = useState<Array<{ email: string }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [saving, setSaving] = useState(false);

  const [connectStatus, setConnectStatus] = useState<GmailConnectStatus>('idle');
  const [connectError, setConnectError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, accs] = await Promise.all([
        window.bridge.listCredentials('INTEGRATION'),
        window.bridge.listGmailAccounts().catch((): Array<{ email: string }> => []),
      ]);
      setCredential(list.find(c => c.name === GMAIL_NAME) ?? null);
      setAccounts(accs);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    const teardown = window.bridge.onGmailOauthComplete((payload) => {
      if (payload.success) {
        setConnectStatus('idle');
        setConnectError(null);
        void load();
      }
      else {
        setConnectStatus('error');
        setConnectError(payload.error ?? 'Gmail sign-in failed');
      }
    });
    return teardown;
  }, []);

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
    if (!clientId.trim()) { setError('Client ID must not be blank.'); return; }
    if (!clientSecret.trim()) { setError('Client Secret must not be blank.'); return; }
    setSaving(true);
    setError(null);
    try {
      await window.bridge.upsertCredential({
        type: 'INTEGRATION',
        name: GMAIL_NAME,
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
    if (!confirm('Delete the saved Gmail app credentials? Connected Gmail accounts stay until disconnected individually.')) return;
    setSaving(true);
    setError(null);
    try {
      await window.bridge.deleteCredential('INTEGRATION', GMAIL_NAME);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const connect = async () => {
    setConnectStatus('launching');
    setConnectError(null);
    try {
      const res = await window.bridge.getGmailOAuthAuthorizeUrl();
      if (!res.configured || !res.url) {
        setConnectStatus('error');
        setConnectError('Gmail OAuth client not configured. Save client_id and client_secret first.');
        return;
      }
      await window.bridge.openExternal(res.url);
      setConnectStatus('awaiting');
    } catch (e) {
      setConnectStatus('error');
      setConnectError(e instanceof Error ? e.message : String(e));
    }
  };

  const disconnectAccount = async (email: string) => {
    if (!confirm(`Disconnect ${email}? You'll need to re-authorize to reconnect.`)) return;
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
        title="Gmail — bring your own OAuth client"
        hint={
          <>
            Same model as Slack: register a personal OAuth client on Google Cloud Console
            and paste its <code>client_id</code> + <code>client_secret</code> here. Once
            saved, you can connect one or more Gmail accounts; each lands a separate
            refresh token in your local keychain.
          </>
        }
      />

      <GmailSetupGuide />

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {!loading && !editing && credential && (
        <SettingCard
          title="Saved Gmail OAuth client"
          action={
            <a
              className="button button--secondary"
              href="https://console.cloud.google.com/apis/credentials"
              target="_blank"
              rel="noreferrer"
            >
              Open Cloud Console ↗
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
                <button className="button button--secondary" type="button" onClick={startEdit}>Replace</button>
                <button className="button button--danger" type="button" onClick={() => void remove()} disabled={saving}>Delete</button>
              </>
            }
          />
        </SettingCard>
      )}

      {!loading && !editing && !credential && (
        <SettingCard title="Add Gmail OAuth client">
          <SettingRow
            title="No Gmail OAuth client saved"
            description="Without it, you can't connect Gmail accounts."
            control={
              <button className="button button--primary" type="button" onClick={startEdit}>
                + Add Gmail OAuth client
              </button>
            }
          />
        </SettingCard>
      )}

      {!loading && editing && (
        <SettingCard title={credential ? 'Replace Gmail OAuth client' : 'Add Gmail OAuth client'}>
          <SettingRow
            title="Client ID"
            description={<>From the Cloud Console under <b>APIs &amp; Services → Credentials → OAuth 2.0 Client IDs</b>. Public — fine to paste.</>}
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="text"
                value={clientId}
                onChange={e => setClientId(e.target.value)}
                placeholder="1234567890-xxxxxxxxxx.apps.googleusercontent.com"
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
                <button className="button button--secondary" type="button" onClick={cancelEdit} disabled={saving}>Cancel</button>
              </>
            }
          />
        </SettingCard>
      )}

      {!loading && credential && (
        <SettingCard
          title="Connected Gmail accounts"
          hint={accounts.length === 0
            ? 'No accounts connected yet. Click below to add one — repeat to add more.'
            : `${accounts.length} account${accounts.length === 1 ? '' : 's'} connected.`}
          action={
            <button
              className="button button--primary"
              type="button"
              onClick={() => void connect()}
              disabled={connectStatus === 'launching' || connectStatus === 'awaiting'}
            >
              {connectStatus === 'launching' && 'Opening browser…'}
              {connectStatus === 'awaiting' && 'Waiting for Google…'}
              {(connectStatus === 'idle' || connectStatus === 'error') && '+ Connect Gmail account'}
            </button>
          }
        >
          {connectStatus === 'awaiting' && (
            <div className="repo-loading">A new tab opened in your browser — finish the consent flow there.</div>
          )}
          {connectStatus === 'error' && connectError && (
            <div className="repo-error">{connectError}</div>
          )}
          {accounts.map(acc => (
            <SettingRow
              key={acc.email}
              title={acc.email}
              description="OAuth refresh token stored locally."
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

function GmailSetupGuide() {
  return (
    <SettingCard title="How to register a Gmail OAuth client">
      <ol className="settings-setup-steps">
        <li>
          Open <a href="https://console.cloud.google.com/" target="_blank" rel="noreferrer">console.cloud.google.com</a>{' '}
          and create a new project (or pick an existing one).
        </li>
        <li>
          Under <b>APIs &amp; Services → Library</b>, enable <b>Gmail API</b>.
        </li>
        <li>
          Go to <b>APIs &amp; Services → OAuth consent screen</b>. Pick <b>External</b>,
          fill in app name (<code>ByteQuay</code>), support email, and a developer
          contact email. Add yourself under <b>Test users</b> — until the app is
          verified, only listed test users can sign in (capped at 100). For a
          personal tool that's fine.
        </li>
        <li>
          Add the scope <code>https://www.googleapis.com/auth/gmail.modify</code>{' '}
          on the consent-screen scopes step.
        </li>
        <li>
          Go to <b>APIs &amp; Services → Credentials</b>. Click{' '}
          <b>+ Create Credentials → OAuth client ID</b>. Pick <b>Desktop app</b>{' '}
          as the application type and name it <code>ByteQuay</code>.
        </li>
        <li>
          After creation, edit the client and add{' '}
          <code>{GMAIL_REDIRECT}</code> under <b>Authorized redirect URIs</b>{' '}
          (custom URI scheme is supported on Desktop app type).
        </li>
        <li>
          Copy the <b>Client ID</b> and <b>Client Secret</b> from the credential
          row and paste them into the form below.
        </li>
      </ol>
    </SettingCard>
  );
}

export default IntegrationsPage;
