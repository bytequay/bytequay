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
import { openLab, saveLabLink, useLabLaunch, useLabLink } from '../labLink';
import { SettingsCard, SettingsRow, Toggle } from './primitives';

export default function LabPage() {
  const link = useLabLink();
  const { opening, error } = useLabLaunch();

  return (
    <div className="setw-page">
      <h1>Lab</h1>
      <p className="setw-footnote">Explore how ByteQuay and its agents work. Opening the Lab starts its local dashboard and refreshes its data.</p>
      {error !== null && <div className="setw-error" role="alert">Could not open the Lab: {error}</div>}
      <SettingsCard>
        <SettingsRow title="Show the Lab" subtitle="Adds a Lab shortcut to the sidebar.">
          <Toggle on={link.enabled} label="Show the Lab" onChange={enabled => saveLabLink({ enabled })} />
        </SettingsRow>
        <SettingsRow
          title="Open Lab"
          subtitle={opening ? 'Starting the dashboard and refreshing its data…' : 'Refreshes activity, context, tuning, and memory statistics on this Mac.'}
        >
          <button
            type="button"
            className="setw-btn-primary"
            disabled={opening}
            aria-busy={opening}
            onClick={() => { void openLab(); }}
          >
            {opening ? 'Opening Lab…' : error !== null ? 'Retry' : 'Open Lab'}
          </button>
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}
