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
import type { UserProfileDto } from '../../types';
import SettingCard from '../shared/SettingCard';
import SettingRow from '../shared/SettingRow';

type Props = {
  /** Hook to clear the GitHub PAT — wired by App.tsx so the user lands back on first-run setup. */
  onClearPat?: () => void;
};

type EditField = 'name' | 'bio' | 'location';

function AccountPage({ onClearPat }: Props) {
  const [profile, setProfile] = useState<UserProfileDto | null>(null);
  // The credential row's `label` carries the GitHub login when the token
  // came from OAuth; PATs save with label=null. We use that distinction
  // to label the row "OAuth" vs "Personal access token".
  const [oauthLogin, setOauthLogin] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [editing, setEditing] = useState<EditField | null>(null);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [resetAvailable, setResetAvailable] = useState(false);
  const [resetting, setResetting] = useState(false);

  const load = async (manual = false) => {
    if (manual) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const [fresh, conn] = await Promise.all([
        window.bridge.getUserProfile(),
        window.bridge.getGitHubOAuthConnection().catch(() => ({ connected: false, login: undefined } as { connected: boolean; login?: string })),
      ]);
      setProfile(fresh);
      setOauthLogin(conn.connected && conn.login ? conn.login : null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    void load(false);
    void window.bridge.isDevLocalDataResetAvailable()
      .then(setResetAvailable)
      .catch(() => setResetAvailable(false));
  }, []);

  const resetLocalData = async () => {
    const confirmed = window.confirm(
      'Reset ByteQuay to first-run state?\n\n'
      + 'This permanently deletes credentials, workspaces, tasks, conversations, drafts, settings, browser sign-ins, and ByteQuay-managed repository copies. Repositories outside ByteQuay and data on GitHub are not changed.\n\n'
      + 'Bundled system prompts and required managed tools and skills are kept. ByteQuay will quit and restart.',
    );
    if (!confirmed) return;

    setError(null);
    setResetting(true);
    try {
      await window.bridge.requestDevLocalDataReset();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setResetting(false);
    }
  };

  const startEdit = (field: EditField) => {
    if (!profile) return;
    setEditing(field);
    setDraft(profile[field] ?? '');
  };

  const cancelEdit = () => {
    setEditing(null);
    setDraft('');
  };

  const saveEdit = async () => {
    if (!profile || !editing) return;
    setSaving(true);
    try {
      const next: Record<EditField, string> = {
        name: profile.name ?? '',
        bio: profile.bio ?? '',
        location: profile.location ?? '',
      };
      next[editing] = draft;
      const updated = await window.bridge.updateProfile(next.name, next.bio, next.location);
      setProfile(updated);
      setEditing(null);
      setDraft('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const renderEditableRow = (field: EditField, label: string, description: (p: UserProfileDto) => string) => {
    if (!profile) return null;
    if (editing === field) {
      return (
        <SettingRow
          title={label}
          description={`Saving updates ${field} on GitHub.`}
          control={
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <input
                className="settings-input-number"
                style={{ width: 220 }}
                value={draft}
                onChange={e => setDraft(e.target.value)}
                disabled={saving}
                autoFocus
              />
              <button className="button button--primary" type="button" onClick={() => void saveEdit()} disabled={saving}>
                {saving ? 'Saving…' : 'Save'}
              </button>
              <button className="button button--secondary" type="button" onClick={cancelEdit} disabled={saving}>
                Cancel
              </button>
            </div>
          }
        />
      );
    }
    return (
      <SettingRow
        title={label}
        description={description(profile)}
        control={<button className="button button--secondary" type="button" onClick={() => startEdit(field)}>Edit</button>}
      />
    );
  };

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Account</h2>
          <div className="settings-shell-page__subtitle">How you appear across ByteQuay.</div>
        </div>
      </div>

      {loading && <div className="repo-loading">Loading profile…</div>}
      {error && <div className="repo-error">{error}</div>}

      {profile && (
        <>
          <SettingCard
            title="Profile"
            hint="Synced from your GitHub account. Edits are written back to GitHub."
            action={
              <button className="button button--secondary" type="button" onClick={() => void load(true)} disabled={refreshing}>
                {refreshing ? 'Refreshing…' : 'Re-sync from GitHub'}
              </button>
            }
          >
            {renderEditableRow('name', 'Display name', p => `${p.name ?? '—'} · @${p.login}`)}
            {renderEditableRow('bio', 'Bio', p => p.bio ?? 'No bio set.')}
            {renderEditableRow('location', 'Location', p => p.location ?? 'No location set.')}
          </SettingCard>

          <SettingCard
            title="GitHub connection"
            hint={<>Connected as <b>@{profile.login}</b>. Followers {profile.followers} · Following {profile.following} · Public repos {profile.publicRepos}.</>}
          >
            <SettingRow
              title="Sign-in method"
              description={oauthLogin
                ? `OAuth · token issued by GitHub for @${oauthLogin}.`
                : 'Personal access token · pasted manually during onboarding.'}
              control={
                <span className={`auth-method-pill ${oauthLogin ? 'auth-method-pill--oauth' : 'auth-method-pill--pat'}`}>
                  {oauthLogin ? 'OAuth' : 'PAT'}
                </span>
              }
            />
            <SettingRow
              title="Open profile on GitHub"
              control={
                <a className="button button--secondary" href={profile.htmlUrl} target="_blank" rel="noreferrer">
                  Open ↗
                </a>
              }
            />
            {onClearPat && (
              <SettingRow
                title="Disconnect"
                description={oauthLogin
                  ? "Revokes the stored OAuth token. You'll be sent back to the first-run setup."
                  : "Clears the stored GitHub PAT. You'll be sent back to the first-run setup."}
                control={<button className="button button--danger" type="button" onClick={onClearPat}>Disconnect</button>}
              />
            )}
          </SettingCard>
        </>
      )}

      {resetAvailable && (
        <SettingCard title="Danger zone">
          <SettingRow
            title="Reset local test data"
            description="Development only. Restarts ByteQuay at first-run setup while keeping bundled system prompts and required managed tools and skills."
            control={
              <button
                className="button button--danger"
                type="button"
                disabled={resetting}
                onClick={() => void resetLocalData()}
              >
                {resetting ? 'Resetting…' : 'Reset and restart'}
              </button>
            }
          />
        </SettingCard>
      )}
    </>
  );
}

export default AccountPage;
