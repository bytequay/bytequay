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
import type { CodeGraphBaselineDto, CodeGraphMode } from '../types';
import { relativeTime } from '../relativeTime';
import { Segmented, SettingsCard, SettingsRow } from './primitives';

const MODE_HINTS: Record<CodeGraphMode, string> = {
  OFF: 'Tool hidden and indexing stopped',
  ON: 'Every task gets callers, callees, and cross-file structure in one call',
};

export default function CodeGraphPage({ mode, baseline, error, onMode }: {
  mode: CodeGraphMode;
  baseline: CodeGraphBaselineDto | null;
  error: string | null;
  onMode: (mode: CodeGraphMode) => void;
}) {
  return (
    <div className="setw-page">
      <h1>Code graph</h1>
      {error !== null && <div className="setw-error" role="alert">{error}</div>}

      <SettingsCard>
        <SettingsRow title="Give tasks a code graph of their worktree" subtitle={MODE_HINTS[mode]}>
          <Segmented<CodeGraphMode>
            label="Code graph mode"
            value={mode}
            options={[
              { value: 'OFF', label: 'Off' },
              { value: 'ON', label: 'On' },
            ]}
            onChange={onMode}
          />
        </SettingsRow>
        <SettingsRow title="Baseline index">
          <span className="setw-row-sub setw-mono">
            {baseline === null
              ? 'not read yet'
              : `${baseline.repositories} ${baseline.repositories === 1 ? 'repo' : 'repos'}`
                + (baseline.updatedAt ? ` · updated ${relativeTime(baseline.updatedAt)}` : '')}
          </span>
        </SettingsRow>
      </SettingsCard>
    </div>
  );
}
