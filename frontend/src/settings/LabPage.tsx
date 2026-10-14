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
import { labAddress, saveLabLink, useLabLink } from '../labLink';
import { SettingsCard, SettingsRow, Toggle } from './primitives';

export default function LabPage() {
  const link = useLabLink();
  /** What is being typed; null while the saved address is shown. */
  const [typed, setTyped] = useState<string | null>(null);
  const shown = typed ?? link.address;
  const address = labAddress(shown);

  return (
    <div className="setw-page">
      <h1>Lab</h1>
      <p className="setw-footnote">The Lab appears in the sidebar while it is turned on.</p>
      <SettingsCard>
        <SettingsRow title="Show the Lab" subtitle="Adds a Lab item that opens the lab's dashboard as a web page.">
          <Toggle on={link.enabled} label="Show the Lab" onChange={enabled => saveLabLink({ enabled })} />
        </SettingsRow>
        <SettingsRow
          title="Dashboard address"
          subtitle={address === null ? 'Use an address on this Mac, such as http://127.0.0.1:8517' : 'Where the lab serves its dashboard on this Mac.'}
        >
          <input
            className="setw-input setw-mono"
            aria-label="Dashboard address"
            aria-invalid={address === null}
            spellCheck={false}
            size={26}
            value={shown}
            onChange={event => setTyped(event.target.value)}
          />
          <button
            type="button"
            className="setw-btn-primary"
            disabled={address === null || address === link.address}
            onClick={() => { saveLabLink({ address }); setTyped(null); }}
          >
            Save
          </button>
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}
