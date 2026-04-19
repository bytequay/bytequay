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
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [editing, setEditing] = useState<EditField | null>(null);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);

  const load = async (manual = false) => {
    if (manual) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const fresh = await window.bridge.getUserProfile();
      setProfile(fresh);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => { void load(false); }, []);

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
                description="Clear the stored GitHub PAT. You'll be sent back to the first-run setup."
                control={<button className="button button--danger" type="button" onClick={onClearPat}>Disconnect</button>}
              />
            )}
          </SettingCard>
        </>
      )}

      <SettingCard title="Danger zone">
        <SettingRow
          title="Delete all local data"
          description="Clears your cached PRs, drafts, and stats. Doesn't affect anything on GitHub. Coming soon."
          control={<button className="button button--danger" type="button" disabled title="Not implemented yet.">Clear local data</button>}
        />
      </SettingCard>
    </>
  );
}

export default AccountPage;
