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
import { SelectPill, SettingsCard, SettingsRow, Toggle } from '../settings/primitives';
import { useFocusBreaks } from './FocusBreakProvider';
import { BREAK_MINUTES, formatFocusTime, WORK_MINUTES } from './model';

export function FocusBreakSettings() {
  const { preferences, phase, remainingSeconds, paused, error, updatePreferences, togglePause, startBreak } = useFocusBreaks();
  return (
    <>
      <div className="setw-section"><span className="setw-section-title">Focus breaks</span></div>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}
      <SettingsCard>
        <SettingsRow title="Break reminders" subtitle="Let your companion remind you when it’s time to rest.">
          <Toggle on={preferences.enabled} label="Break reminders" onChange={enabled => updatePreferences({ ...preferences, enabled })} />
        </SettingsRow>
        <SettingsRow title="Break companion">
          <SelectPill quiet ariaLabel="Break companion" value={preferences.companion}
            label={preferences.companion === 'chubby-cat' ? 'Chubby orange cat' : 'Dancing husky'}
            options={[{ value: 'chubby-cat', label: 'Chubby orange cat' }, { value: 'dancing-husky', label: 'Dancing husky' }]}
            onSelect={value => updatePreferences({ ...preferences, companion: value === 'dancing-husky' ? value : 'chubby-cat' })} />
        </SettingsRow>
        <SettingsRow title="Focus time">
          <SelectPill quiet ariaLabel="Focus time" value={String(preferences.workMinutes)} label={`${preferences.workMinutes} min`}
            options={WORK_MINUTES.map(minutes => ({ value: String(minutes), label: `${minutes} min` }))}
            onSelect={value => updatePreferences({ ...preferences, workMinutes: Number(value) })} />
        </SettingsRow>
        <SettingsRow title="Break time">
          <SelectPill quiet ariaLabel="Break time" value={String(preferences.breakMinutes)} label={`${preferences.breakMinutes} min`}
            options={BREAK_MINUTES.map(minutes => ({ value: String(minutes), label: `${minutes} min` }))}
            onSelect={value => updatePreferences({ ...preferences, breakMinutes: Number(value) })} />
        </SettingsRow>
        {preferences.enabled && <SettingsRow title={paused ? 'Timer paused' : phase === 'entering' ? 'Companion is arriving' : phase === 'break' ? 'Taking a break' : 'Next break'} subtitle={`${formatFocusTime(remainingSeconds)} remaining`}>
          <button type="button" className="setw-btn" onClick={togglePause}>{paused ? 'Resume timer' : 'Pause timer'}</button>
          <button type="button" className="setw-btn" onClick={startBreak}>Take a break now</button>
        </SettingsRow>}
        <div className="setw-foot-row"><span>Enabling reminders or changing times starts a fresh focus session. Focus time pauses while ByteQuay is hidden. Agents keep running during breaks.</span></div>
      </SettingsCard>
    </>
  );
}
