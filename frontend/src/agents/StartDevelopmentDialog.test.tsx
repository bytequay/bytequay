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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { StartDevelopmentChoice, defaultDevelopmentLane } from './StartDevelopmentDialog';
import { laneDefaults, modelEffort } from '../settings/model';
import type { ModelProviderDto } from '../types';

function provider(id: string, readiness: ModelProviderDto['readiness'], efforts: string[] = [], selectedEffort = ''): ModelProviderDto {
  return {
    id,
    label: id === 'claude' ? 'Claude' : 'Codex',
    readiness,
    version: null,
    diagnostic: '',
    capabilities: NO_AGENT_CAPABILITIES,
    models: [
      { id: `${id}-model`, name: 'GPT-5.6-Sol', description: '', efforts, defaultEffort: efforts[0] ?? null },
      { id: `${id}-mini`, name: 'GPT-5.6-Mini', description: '', efforts: efforts.slice(0, 1), defaultEffort: efforts[0] ?? null },
    ],
    selectedModel: `${id}-model`,
    selectedEffort,
  };
}

describe('start development dialog', () => {
  it('offers the Development agent first and never a lane that cannot run', () => {
    const both = [provider('claude', 'READY'), provider('codex', 'READY')];
    expect(defaultDevelopmentLane(both, 'codex')).toBe('codex');
    expect(defaultDevelopmentLane(both, undefined)).toBe('claude');
    expect(defaultDevelopmentLane([provider('claude', 'AUTHENTICATION_REQUIRED'), provider('codex', 'READY')], 'claude')).toBe('codex');
    expect(defaultDevelopmentLane([provider('claude', 'CLI_MISSING')], 'claude')).toBe('');
    expect(defaultDevelopmentLane([provider('deepseek-key-123', 'READY'), provider('codex', 'READY')], undefined)).toBe('codex');
  });

  it('seeds the lane\u2019s own model and effort, and keeps an effort only a model still offers', () => {
    expect(laneDefaults([provider('codex', 'READY', ['medium', 'high'], 'high')], 'codex')).toEqual({ model: 'codex-model', effort: 'high' });
    expect(laneDefaults([provider('codex', 'READY', ['medium', 'high'], 'ultra')], 'codex').effort).toBe('medium');
    expect(laneDefaults([provider('codex', 'READY')], 'codex')).toEqual({ model: 'codex-model', effort: '' });
    expect(laneDefaults([], 'codex')).toEqual({ model: '', effort: '' });

    const mini = provider('codex', 'READY', ['medium', 'high']).models[1];
    expect(modelEffort(mini, 'high')).toBe('medium');
    expect(modelEffort(mini, 'medium')).toBe('medium');
    expect(modelEffort(undefined, 'high')).toBe('');
  });

  it('confirms only once a runnable provider is chosen and says why the others are not', () => {
    const choice = (providers: ModelProviderDto[] | null, laneId: string, model = '', effort = '') => renderToStaticMarkup(
      <StartDevelopmentChoice issueNumber={7} providers={providers} laneId={laneId} model={model} effort={effort}
        onSelectLane={() => {}} onSelectModel={() => {}} onSelectEffort={() => {}} onConfirm={() => {}} onCancel={() => {}} />);

    const ready = choice([provider('claude', 'CLI_MISSING'), provider('codex', 'READY', ['medium', 'high'])], 'codex', 'codex-model', 'high');
    expect(ready).toContain('Start development on #7?');
    expect(ready).toContain('<option value="claude" disabled="">Claude — Not installed</option>');
    expect(ready).toContain('<option value="codex" selected="">Codex</option>');
    expect(ready).toContain('<option value="codex-model" selected="">5.6 Sol</option>');
    expect(ready).toContain('<option value="high" selected="">High</option>');
    expect(ready).not.toContain('disabled="">Start');

    expect(choice([provider('codex', 'READY')], 'codex', 'codex-model')).not.toContain('<span>Effort</span>');
    expect(choice(null, '')).toContain('disabled="">Start');
    expect(choice([], '')).toContain('No agent provider is available');
  });
});
