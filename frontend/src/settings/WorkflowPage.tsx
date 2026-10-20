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
import { AUTOMATION_STEPS, includesLevel, levelIndex } from '../agents/developmentAutomation';
import { AutomationSlider, CiProviderSelect } from '../agents/AutomationControl';
import AssistantFeatureCard from './AssistantFeatureCard';
import { SettingsCard, SettingsRow } from './primitives';

export default function WorkflowPage({ value, providers, error, onChange }: {
  value: DevelopmentAutomationDto | null;
  providers: ModelProviderDto[];
  error: string | null;
  onChange: (value: DevelopmentAutomationDto) => void;
}) {
  return <div className="setw-page">
    <h1>Workflow</h1>
    {error !== null && <div className="setw-error" role="alert">{error}</div>}
    {value === null ? <div className="setw-row-empty">Loading automation settings…</div> : <>
      <SettingsCard>
        <SettingsRow title={AUTOMATION_STEPS[levelIndex(value.level)].title} subtitle={AUTOMATION_STEPS[levelIndex(value.level)].description}>
          <div className="agt-auto-inline">
            <AutomationSlider value={value.level} onChange={level => onChange({ ...value, level })} />
          </div>
        </SettingsRow>
        {includesLevel(value.level, 'CI_FIX') && <SettingsRow title="CI repair agent" subtitle="Use the development agent or choose another agent to repair failing CI checks.">
          <CiProviderSelect value={value.ciLaneId} providers={providers} onChange={ciLaneId => onChange({ ...value, ciLaneId })} />
        </SettingsRow>}
      </SettingsCard>
      <p className="setw-footnote">Each step includes the ones before it. Defaults apply to new tasks, which can override them until they start. CI monitoring has no time limit, with up to 3 repairs per run and 3 reruns per revision.</p>
      <AssistantFeatureCard />
    </>}
  </div>;
}
