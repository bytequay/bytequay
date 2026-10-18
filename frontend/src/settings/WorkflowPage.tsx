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
import type { DevelopmentAutomationDto, ModelProviderDto } from '../types';
import { AUTOMATION_OPTIONS } from '../agents/developmentAutomation';
import { CiProviderSelect } from '../agents/AutomationControl';
import AssistantFeatureCard from './AssistantFeatureCard';
import { SettingsCard, SettingsRow, Toggle } from './primitives';

export default function WorkflowPage({ value, providers, busy, error, onChange }: {
  value: DevelopmentAutomationDto | null;
  providers: ModelProviderDto[];
  busy: boolean;
  error: string | null;
  onChange: (value: DevelopmentAutomationDto) => void;
}) {
  return <div className="setw-page">
    <h1>Workflow</h1>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
    {value === null ? <div className="setw-row-empty">Loading automation settings…</div> : <>
      <p className="setw-footnote">Finished development work is committed automatically. Review experiments stay uncommitted.</p>
      <SettingsCard>
        {AUTOMATION_OPTIONS.map(option => <SettingsRow key={option.key} title={option.title} subtitle={option.description}>
          <Toggle label={option.title} on={value[option.key]} disabled={busy} onChange={enabled => onChange({ ...value, [option.key]: enabled })} />
        </SettingsRow>)}
        <SettingsRow title="CI repair agent" subtitle="Use the development agent or choose another agent to repair failing CI checks.">
          <CiProviderSelect value={value.ciLaneId} providers={providers} disabled={busy} onChange={ciLaneId => onChange({ ...value, ciLaneId })} />
        </SettingsRow>
      </SettingsCard>
      <p className="setw-footnote">Defaults apply to new tasks, which can override them until they start. CI allows up to 3 repairs, 3 reruns per revision, and 8 hours.</p>
      <AssistantFeatureCard />
    </>}
  </div>;
}
