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
import Avatar from '../../Avatar';
import SettingsPage from '../shared/SettingsPage';
import { PencilIcon, RefreshIcon, WarnIcon } from '../shared/icons';

type EditField = 'name' | 'bio' | 'location';

const FIELDS: { key: EditField; label: string; multiline: boolean; empty: string }[] = [
  { key: 'name', label: 'Display name', multiline: false, empty: 'No display name set.' },
  { key: 'bio', label: 'Bio', multiline: true, empty: 'No bio set.' },
  { key: 'location', label: 'Location', multiline: false, empty: 'No location set.' },
];

function AccountPage() {
  const [profile, setProfile] = useState<UserProfileDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncedAt, setSyncedAt] = useState<number | null>(null);
  const [editing, setEditing] = useState<EditField | null>(null);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [resetAvailable, setResetAvailable] = useState(false);
  // 'idle' → 'asking' (inline confirmation strip) → 'resetting'. The
  // strip replaces a window.confirm so the warning stays on the page
  // the user is looking at.
  const [reset, setReset] = useState<'idle' | 'asking' | 'resetting'>('idle');

  const load = async (manual = false) => {
    if (manual) setSyncing(true);
    else setLoading(true);
    setError(null);
    try {
      setProfile(await window.bridge.getUserProfile());
      setSyncedAt(Date.now());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setSyncing(false);
    }
  };

  useEffect(() => {
    void load(false);
    void window.bridge.isDevLocalDataResetAvailable()
      .then(setResetAvailable)
      .catch(() => setResetAvailable(false));
  }, []);

  const resetLocalData = async () => {
    setError(null);
    setReset('resetting');
    try {
      await window.bridge.requestDevLocalDataReset();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setReset('idle');
    }
  };

  const saveEdit = async () => {
    if (profile === null || editing === null) return;
    setSaving(true);
    try {
      const next = {
        name: profile.name ?? '',
        bio: profile.bio ?? '',
        location: profile.location ?? '',
        [editing]: draft,
      };
      setProfile(await window.bridge.updateProfile(next.name, next.bio, next.location));
      setEditing(null);
      setDraft('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <SettingsPage
      title="Account"
      subtitle="How you appear across ByteQuay. Your profile mirrors GitHub."
      width={820}
    >
      {loading && <div className="sv2-loading">Loading profile…</div>}
      {error !== null && <div className="sv2-error" role="alert">{error}</div>}

      {profile !== null && (
        <div className="sv2-card">
          <div className="sv2-account__id">
            <Avatar login={profile.login} avatarUrl={profile.avatarUrl} size={46} />
            <span className="sv2-account__who">
              <span className="sv2-account__name">{profile.name ?? profile.login}</span>
              <span className="sv2-account__handle">
                @{profile.login}
                {profile.location !== null && profile.location !== '' && ` · ${profile.location}`}
              </span>
            </span>
            <span className="sv2-account__sync">
              <button className="sv2-btn" type="button" disabled={syncing} onClick={() => { void load(true); }}>
                <span className={syncing ? 'sv2-spin' : ''} style={{ display: 'inline-flex' }}>
                  <RefreshIcon size={13} />
                </span>
                {syncing ? 'Syncing…' : 'Re-sync'}
              </button>
              <span className="sv2-account__synced">Synced {ago(syncedAt)}</span>
            </span>
          </div>
          <div className="sv2-account__note">Edits below are written back to your GitHub profile.</div>

          {FIELDS.map(field => {
            const value = profile[field.key];
            const shown = value === null || value === '' ? field.empty : value;
            if (editing !== field.key) {
              return (
                <div className="sv2-row" key={field.key}>
                  <span className="sv2-row__label">{field.label}</span>
                  <span className="sv2-row__value">{shown}</span>
                  <button
                    className="sv2-btn sv2-btn--sm"
                    type="button"
                    onClick={() => { setEditing(field.key); setDraft(value ?? ''); }}
                  >
                    <PencilIcon size={12} />Edit
                  </button>
                </div>
              );
            }
            return (
              <div className="sv2-row" key={field.key}>
                <span className="sv2-row__label">{field.label}</span>
                <span className="sv2-row__edit">
                  {field.multiline ? (
                    <textarea
                      className="sv2-textarea"
                      rows={2}
                      value={draft}
                      aria-label={field.label}
                      disabled={saving}
                      onChange={e => setDraft(e.target.value)}
                    />
                  ) : (
                    <input
                      className="sv2-input"
                      value={draft}
                      aria-label={field.label}
                      disabled={saving}
                      autoFocus
                      onChange={e => setDraft(e.target.value)}
                    />
                  )}
                  <span className="sv2-row__actions">
                    <button className="sv2-btn sv2-btn--primary" type="button" disabled={saving} onClick={() => { void saveEdit(); }}>
                      {saving ? 'Saving…' : 'Save'}
                    </button>
                    <button className="sv2-btn sv2-btn--sm" type="button" disabled={saving} onClick={() => { setEditing(null); setDraft(''); }}>
                      Cancel
                    </button>
                    Pushes to GitHub on save
                  </span>
                </span>
                <span />
              </div>
            );
          })}
        </div>
      )}

      {resetAvailable && (
        <div className="sv2-danger">
          <div className="sv2-danger__head">
            <span style={{ color: '#cf222e', display: 'inline-flex' }}><WarnIcon size={15} /></span>
            Danger zone
          </div>
          <div className="sv2-danger__body">
            <span className="sv2-danger__text">
              <span className="sv2-danger__title">Reset local test data</span>
              <span className="sv2-danger__desc">
                Development only. Restarts ByteQuay at first-run setup while keeping
                bundled system prompts and required managed tools and skills.
              </span>
            </span>
            {reset !== 'asking' && (
              <button
                className="sv2-btn sv2-btn--danger"
                type="button"
                disabled={reset === 'resetting'}
                onClick={() => setReset('asking')}
              >
                {reset === 'resetting' ? 'Resetting…' : 'Reset and restart'}
              </button>
            )}
          </div>
          {reset === 'asking' && (
            <div className="sv2-danger__confirm">
              <span>This clears credentials, workspaces and cached PRs on this Mac. It can’t be undone.</span>
              <button className="sv2-btn sv2-btn--sm" type="button" onClick={() => setReset('idle')}>Cancel</button>
              <button className="sv2-btn sv2-btn--danger-solid" type="button" onClick={() => { void resetLocalData(); }}>
                Yes, reset
              </button>
            </div>
          )}
        </div>
      )}
    </SettingsPage>
  );
}

/** "4m ago" / "just now" for the re-sync stamp. */
function ago(at: number | null): string {
  if (at === null) return 'never';
  const mins = Math.round((Date.now() - at) / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  return `${Math.round(mins / 60)}h ago`;
}

export default AccountPage;
