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
import type { AppPreferencesDto, AppThemeDto } from '../types';
import { Segmented, SelectPill, SettingsCard, SettingsRow } from './primitives';

const RETENTION_DAYS = [3, 7, 14, 30, 90];
const RECENT_DAYS = [7, 14, 30, 90];

export function dayLabel(days: number): string {
  return days === 1 ? '1 day' : `${days} days`;
}

function daysPill(value: number, choices: number[], ariaLabel: string, onSelect: (days: number) => void) {
  return (
    <SelectPill
      quiet
      ariaLabel={ariaLabel}
      value={String(value)}
      label={dayLabel(value)}
      options={choices.map(days => ({ value: String(days), label: dayLabel(days) }))}
      onSelect={next => onSelect(Number(next))}
    />
  );
}

export function GeneralPage({ preferences, version, clearing, error, onChange, onClearRecent }: {
  preferences: AppPreferencesDto;
  version: string;
  clearing: boolean;
  error: string | null;
  onChange: (next: AppPreferencesDto) => void;
  onClearRecent: () => void;
}) {
  const [confirming, setConfirming] = useState(false);

  return (
    <div className="setw-page">
      <h1>General</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <div className="setw-section"><span className="setw-section-title">Tasks</span></div>
      <SettingsCard>
        <SettingsRow title="Keep detailed task runtime">
          {daysPill(
            preferences.taskRetentionDays,
            RETENTION_DAYS,
            'Keep detailed task runtime',
            days => onChange({ ...preferences, taskRetentionDays: days }),
          )}
        </SettingsRow>
      </SettingsCard>

      <div className="setw-section">
        <span className="setw-section-title">Recent</span>
        <span className="setw-section-note">this Mac only — never synced</span>
      </div>
      <SettingsCard>
        <SettingsRow title="Keep what you touched">
          {daysPill(
            preferences.recentRetentionDays,
            RECENT_DAYS,
            'Keep what you touched',
            days => onChange({ ...preferences, recentRetentionDays: days }),
          )}
        </SettingsRow>
        <SettingsRow title="Clear history">
          {confirming ? (
            <>
              <button type="button" className="setw-btn is-danger" disabled={clearing} onClick={onClearRecent}>
                {clearing ? 'Clearing…' : 'Clear everything'}
              </button>
              <button type="button" className="setw-btn" onClick={() => setConfirming(false)}>Cancel</button>
            </>
          ) : (
            <button type="button" className="setw-btn is-danger" onClick={() => setConfirming(true)}>Clear…</button>
          )}
        </SettingsRow>
      </SettingsCard>
      <div className="setw-version">ByteQuay {version}</div>
    </div>
  );
}

export function AppearancePage({ preferences, error, onChange }: {
  preferences: AppPreferencesDto;
  error: string | null;
  onChange: (next: AppPreferencesDto) => void;
}) {
  return (
    <div className="setw-page">
      <h1>Appearance</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <SettingsCard>
        <SettingsRow title="Theme">
          <Segmented<AppThemeDto>
            label="Theme"
            value={preferences.theme}
            options={[
              { value: 'SYSTEM', label: 'System' },
              { value: 'LIGHT', label: 'Light' },
              // No dark palette exists yet; offering the switch would change nothing.
              { value: 'DARK', label: 'Dark', disabled: true },
            ]}
            onChange={theme => onChange({ ...preferences, theme })}
          />
        </SettingsRow>
      </SettingsCard>
      <div className="setw-footnote">A dark palette is still being built; System follows the light one for now.</div>
    </div>
  );
}
