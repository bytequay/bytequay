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
import { useAgentAvailability } from '../agents/availability';
import { SETUP_CHANGED } from '../onboarding/setup';
import type { SetupPreferencesDto } from '../types';
import AssistantPermissionsCard from './AssistantPermissionsCard';
import { SettingsCard, SettingsRow, Toggle } from './primitives';
import { settingsRoute } from './routes';

export default function AssistantFeatureCard() {
  const [setup, setSetup] = useState<SetupPreferencesDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const availability = useAgentAvailability();
  useEffect(() => {
    let current = true;
    void window.bridge.getSetupPreferences?.().then(value => { if (current) setSetup(value); })
      .catch((failure: unknown) => { if (current) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { current = false; };
  }, []);
  return <>
    <div className="setw-section"><span className="setw-section-title">Assistant</span></div>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
    <SettingsCard>
      <SettingsRow title="Enable Assistant" subtitle="Ask about your day and the work on screen. Task notices remain available when chat is off.">
        <Toggle label="Enable Assistant" on={setup?.assistantEnabled === true} disabled={busy || setup === null || !setup.assistantEnabled && !availability.development}
          onChange={assistantEnabled => {
            if (setup === null) return;
            setBusy(true); setError(null);
            window.bridge.saveSetupPreferences({ ...setup, assistantEnabled }).then(value => {
              setSetup(value); window.dispatchEvent(new Event(SETUP_CHANGED));
            }).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : String(failure))).finally(() => setBusy(false));
          }} />
      </SettingsRow>
      {!availability.development && <div className="setw-foot-row">Choose an available global Development model in <a href={settingsRoute('models')}>Models</a>.</div>}
    </SettingsCard>
    {setup?.assistantEnabled && <AssistantPermissionsCard />}
  </>;
}
