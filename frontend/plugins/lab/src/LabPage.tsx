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
import { saveLabLink, useLabLaunch, useLabLink } from './labLink';
import { SettingsCard, SettingsRow, Toggle } from '@bytequay/ui/settings';

export default function LabPage({ onOpen }: { onOpen: () => Promise<void> }) {
  const link = useLabLink();
  const { opening, error } = useLabLaunch();

  return (
    <div className="setw-page">
      <h1>Lab</h1>
      <p className="setw-footnote">Check ByteQuay's health and find ways to improve it. Use Analyze now in the Lab whenever you want to run the analysis again.</p>
      {error !== null && <div className="setw-error" role="alert">Could not open the Lab: {error}</div>}
      <SettingsCard>
        <SettingsRow title="Show the Lab" subtitle="Adds a Lab shortcut to the sidebar.">
          <Toggle on={link.enabled} label="Show the Lab" onChange={enabled => saveLabLink({ enabled })} />
        </SettingsRow>
        <SettingsRow
          title="Open Lab"
          subtitle={opening
            ? <span role="status">Analyzing local logs and agent activity, then starting the dashboard. This may take a few minutes. You can keep using ByteQuay.</span>
            : 'Analyzes existing logs, resource measurements, and agent activity on this Mac. Starting Lab may take a few minutes.'}
        >
          <button
            type="button"
            className="setw-btn-primary setw-lab-open"
            disabled={opening}
            aria-busy={opening}
            onClick={() => { void onOpen(); }}
          >
            {opening && <span className="bq-spinner is-spinning setw-lab-spinner" aria-hidden="true" />}
            {opening ? 'Opening Lab…' : error !== null ? 'Retry' : 'Open Lab'}
          </button>
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}
