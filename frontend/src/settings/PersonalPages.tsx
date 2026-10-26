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
import { OPEN_SETUP } from '../onboarding/setup';
import { readBrowserDisplayMode, saveBrowserDisplayMode, type BrowserDisplayMode } from '../browserPreferences';
import type { AppPreferencesDto, AppThemeDto, QuietHoursDto } from '../types';
import { FocusBreakSettings } from '../focus/FocusBreakSettings';
import { Segmented, SelectPill, SettingsCard, SettingsRow, TimePill, Toggle } from './primitives';

const RETENTION_DAYS = [3, 7, 14, 30, 90];
const RECENT_DAYS = [7, 14, 30, 90];

function dayLabel(days: number): string {
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

export function GeneralPage({ preferences, quietHours, quietHoursBusy, version, clearing, error, onChange, onQuietHours, onClearRecent }: {
  preferences: AppPreferencesDto;
  quietHours: QuietHoursDto | null;
  quietHoursBusy: boolean;
  version: string;
  clearing: boolean;
  error: string | null;
  onChange: (next: AppPreferencesDto) => void;
  onQuietHours: (next: QuietHoursDto) => void;
  onClearRecent: () => void;
}) {
  const [confirming, setConfirming] = useState(false);

  return (
    <div className="setw-page">
      <h1>General</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <SettingsCard>
        <SettingsRow title="Setup guide" subtitle="Revisit appearance, agents, automation, and feature previews.">
          <button type="button" className="setw-btn" onClick={() => window.dispatchEvent(new Event(OPEN_SETUP))}>Open setup</button>
        </SettingsRow>
      </SettingsCard>

      <FocusBreakSettings />

      <div className="setw-section"><span className="setw-section-title">Background refresh</span></div>
      <SettingsCard>
        {quietHours === null ? <div className="setw-row-empty">Loading quiet hours…</div> : <div className="setw-quiet-hours">
          <SettingsRow title="Quiet hours" subtitle="GitHub and Jira · this Mac’s local time">
            <TimePill ariaLabel="Quiet hours start" minute={quietHours.startMinute} disabled={!quietHours.enabled || quietHoursBusy}
              onSelect={startMinute => onQuietHours({ ...quietHours, startMinute })} />
            <span className="setw-row-sub">to</span>
            <TimePill ariaLabel="Quiet hours end" minute={quietHours.endMinute} disabled={!quietHours.enabled || quietHoursBusy}
              onSelect={endMinute => onQuietHours({ ...quietHours, endMinute })} />
            <Toggle on={quietHours.enabled} label="Pause background refresh during quiet hours" disabled={quietHoursBusy} onChange={enabled => onQuietHours({ ...quietHours, enabled })} />
          </SettingsRow>
        </div>}
        <div className="setw-foot-row"><span>Manual refresh and desktop notifications remain available during quiet hours.</span></div>
      </SettingsCard>

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
              { value: 'DARK', label: 'Dark' },
            ]}
            onChange={theme => onChange({ ...preferences, theme })}
          />
        </SettingsRow>
      </SettingsCard>
      <div className="setw-footnote">System follows your Mac’s appearance, including automatic changes.</div>
    </div>
  );
}

export function BrowserPage() {
  const [mode, setMode] = useState(readBrowserDisplayMode);

  return (
    <div className="setw-page">
      <h1>Browser</h1>
      <SettingsCard>
        <SettingsRow title="Full screen behavior"
          subtitle="Zoom opens a centered view at about 80% of the window size. Full screen fills the window.">
          <Segmented<BrowserDisplayMode>
            label="Full screen behavior"
            value={mode}
            options={[
              { value: 'zoom', label: 'Zoom' },
              { value: 'fullscreen', label: 'Full screen' },
            ]}
            onChange={next => { saveBrowserDisplayMode(next); setMode(next); }}
          />
        </SettingsRow>
      </SettingsCard>
      <div className="setw-footnote">Saved on this Mac. Applies when you open the browser.</div>
    </div>
  );
}
