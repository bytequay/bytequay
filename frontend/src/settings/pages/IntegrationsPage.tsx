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

const TYPE = 'INTEGRATION' as const;
const NAME = 'slack-oauth-app';
const REDIRECT_URI = 'bytequay://slack-oauth-callback';

/**
 * BYO Slack OAuth app configuration. Users register their own app on
 * api.slack.com and paste the public {@code client_id} (label) plus the
 * encrypted {@code client_secret} (value) into a single credential row.
 * The Slack tab picks the values up at the next call — no backend
 * restart needed.
 *
 * The setup-guide card mirrors the steps in
 * docs/mockups/design/slack/scopes.md so users have everything they
 * need without leaving the app.
 */
function IntegrationsPage() {
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
      const list = await window.bridge.listCredentials(TYPE);
      const existing = list.find(c => c.name === NAME) ?? null;
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
        type: TYPE,
        name: NAME,
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
      await window.bridge.deleteCredential(TYPE, NAME);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Integrations</h2>
          <div className="settings-shell-page__subtitle">
            Connect ByteQuay to Slack so the cockpit can show your mentions and DMs.
          </div>
        </div>
      </div>

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

      <SetupGuide />

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

function SetupGuide() {
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
          <code>{REDIRECT_URI}</code> and click <b>Save URLs</b>.
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

export default IntegrationsPage;
