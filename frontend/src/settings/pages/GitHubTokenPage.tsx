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

const TYPE = 'ACCOUNT' as const;
const NAME = 'github';

/**
 * Manages the singleton account-type GitHub credential. There is at most one
 * row in the credentials table with (type=ACCOUNT, name="github") — we use
 * it for every GitHub call that isn't repo-scoped (profile, search, user
 * repos, org info). Repo-scoped fetches will prefer a per-repo PAT once M7
 * lands; this token is the fallback.
 */
function GitHubTokenPage() {
  const [credential, setCredential] = useState<CredentialDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');
  const [label, setLabel] = useState('');
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await window.bridge.listCredentials(TYPE);
      const existing = list.find(c => c.name === NAME) ?? null;
      setCredential(existing);
      setLabel(existing?.label ?? '');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const startEdit = () => {
    setEditing(true);
    setValue('');
  };

  const cancelEdit = () => {
    setEditing(false);
    setValue('');
    setLabel(credential?.label ?? '');
  };

  const save = async () => {
    if (!value.trim()) {
      setError('Token must not be blank.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await window.bridge.upsertCredential({
        type: TYPE,
        name: NAME,
        value: value.trim(),
        label: label.trim() || null,
        notes: null,
      });
      setEditing(false);
      setValue('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!confirm('Delete the stored GitHub token? You\'ll be returned to the first-run setup.')) return;
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
          <h2 className="settings-shell-page__title">GitHub token</h2>
          <div className="settings-shell-page__subtitle">
            One personal access token authorises every account-level GitHub call.
          </div>
        </div>
      </div>

      <SettingCard
        title="What this token is for"
        hint={
          <>
            ByteQuay uses this token for everything that isn't tied to a specific repo:
            your <b>profile</b>, the list of <b>repos</b> you own / collaborate on,
            <b> org</b> memberships, search, and any cross-repo activity feed.
            Per-repo fetches (PR sync, comments, reviews on a watched repo) prefer the
            repo-specific token from <em>Watched repos → Tokens</em> when one exists,
            falling back to this token otherwise.
            <br />
            <br />
            The token is stored encrypted on this machine and never leaves it.
            Recommended scopes for a classic PAT: <code>repo</code>, <code>read:user</code>, <code>read:org</code>.
          </>
        }
      />

      {loading && <div className="repo-loading">Loading…</div>}
      {error && <div className="repo-error">{error}</div>}

      {!loading && !editing && credential && (
        <SettingCard
          title="Current token"
          action={
            <a
              className="button button--secondary"
              href="https://github.com/settings/tokens"
              target="_blank"
              rel="noreferrer"
            >
              Manage on GitHub ↗
            </a>
          }
        >
          <SettingRow
            title={<code>{credential.preview}</code>}
            description={
              <>
                {credential.label && <>{credential.label} · </>}
                Added {new Date(credential.createdAt).toLocaleDateString()}
                {credential.lastUsedAt && <> · Last used {new Date(credential.lastUsedAt).toLocaleDateString()}</>}
              </>
            }
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
        <SettingCard title="Add a token">
          <SettingRow
            title="No GitHub token saved"
            description="Without a token, ByteQuay can't fetch your profile, repos, or any cross-repo data."
            control={
              <button className="button button--primary" type="button" onClick={startEdit}>
                + Add token
              </button>
            }
          />
        </SettingCard>
      )}

      {!loading && editing && (
        <SettingCard title={credential ? 'Replace token' : 'Add token'}>
          <SettingRow
            title="Personal access token"
            description="Paste a classic or fine-grained PAT. Only the masked preview is kept after save."
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="password"
                value={value}
                onChange={e => setValue(e.target.value)}
                placeholder="ghp_…"
                autoFocus
              />
            }
          />
          <SettingRow
            title="Label (optional)"
            description="A short note, e.g. 'work account' or 'personal'."
            control={
              <input
                className="settings-input-number"
                style={{ width: 280 }}
                type="text"
                value={label}
                onChange={e => setLabel(e.target.value)}
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

export default GitHubTokenPage;
