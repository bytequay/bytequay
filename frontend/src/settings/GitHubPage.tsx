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
import { useState } from 'react';
import type { AppPreferencesDto, GitHubAccountDto } from '../types';
import UserAvatar from '../UserAvatar';
import { SelectPill, SettingsCard, SettingsRow, StatusDot } from './primitives';
import { settingsRoute } from './routes';

const REFRESH_MINUTES = [1, 3, 5, 6, 10, 15, 30, 60];

export function refreshLabel(minutes: number): string {
  if (minutes === 60) return '1 hour';
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

export default function GitHubPage({
  account, login, preferences, copied, error,
  onSaveToken, onDisconnect, onCopy, onPreferences,
}: {
  account: GitHubAccountDto | null;
  login: string | null;
  preferences: AppPreferencesDto;
  copied: boolean;
  error: string | null;
  onSaveToken: (token: string) => void;
  onDisconnect: () => void;
  onCopy: () => void;
  onPreferences: (next: AppPreferencesDto) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [token, setToken] = useState('');
  const configured = account?.configured ?? false;
  const showForm = !configured || editing;

  return (
    <div className="setw-page">
      <h1>GitHub</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section"><span className="setw-section-title">Account</span></div>
      <SettingsCard>
        <SettingsRow
          title={
            <>
              {login === null ? null : <UserAvatar login={login} size={30} />}
              <span>{configured ? (login ?? 'Connected') : 'Not connected'}</span>
              <StatusDot tone={configured ? 'ok' : 'idle'} />
            </>
          }
          subtitle={configured
            ? (account?.source === 'GITHUB_CLI' ? 'GitHub CLI login' : 'Personal access token')
            : 'Paste a personal access token to connect'}
        >
          {configured && !editing && (
            <>
              <button type="button" className="setw-btn" onClick={() => setEditing(true)}>Update…</button>
              <button type="button" className="setw-btn is-danger" onClick={onDisconnect}>Disconnect</button>
            </>
          )}
        </SettingsRow>
        {configured && !editing && account?.maskedToken && (
          <div className="setw-row">
            <div className="setw-token setw-mono">{account.maskedToken}</div>
            <button type="button" className="setw-btn" onClick={onCopy}>{copied ? 'Copied' : 'Copy'}</button>
          </div>
        )}
        {showForm && (
          <div className="setw-row">
            <input
              type="password"
              value={token}
              aria-label="GitHub personal access token"
              placeholder="github_pat_…"
              className="setw-input setw-mono"
              style={{ flex: 1, minWidth: 0 }}
              onChange={event => setToken(event.target.value)}
            />
            <button
              type="button"
              className="setw-btn-primary"
              disabled={token.trim() === ''}
              onClick={() => { onSaveToken(token.trim()); setToken(''); setEditing(false); }}
            >
              Save
            </button>
            {editing && <button type="button" className="setw-btn" onClick={() => { setEditing(false); setToken(''); }}>Cancel</button>}
          </div>
        )}
      </SettingsCard>

      <div className="setw-section"><span className="setw-section-title">Refresh</span></div>
      <SettingsCard>
        <SettingsRow title="Detail refresh interval">
          <SelectPill
            quiet
            ariaLabel="Detail refresh interval"
            value={String(preferences.refreshIntervalMinutes)}
            label={refreshLabel(preferences.refreshIntervalMinutes)}
            options={REFRESH_MINUTES.map(minutes => ({ value: String(minutes), label: refreshLabel(minutes) }))}
            onSelect={minutes => onPreferences({ ...preferences, refreshIntervalMinutes: Number(minutes) })}
          />
        </SettingsRow>
      </SettingsCard>
      <div className="setw-footnote">This interval applies to PR details and repository sync. PR summaries are checked every 15 seconds. <a href={settingsRoute('general')}>Manage quiet hours in General</a>.</div>
    </div>
  );
}
