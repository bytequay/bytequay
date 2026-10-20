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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { flushSync } from 'react-dom';
import { assert, settle } from '../../fixtures/harness';
import type { Bridge, ModelSettingsDto } from '../../types';
import { NO_AGENT_CAPABILITIES } from '../capabilities';
import { AgentAvailabilityProvider, useAgentAvailability } from '../availability';

export async function run() {
  const { host, root, dispose } = createFixtureRoot();
  let reads = 0;
  let probes = 0;
  let settings: ModelSettingsDto = {
    agents: ['DEVELOPMENT', 'REVIEW'].map(type => ({ type: type as 'DEVELOPMENT' | 'REVIEW', explicit: true, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol' })),
    providers: [{ id: 'codex', label: 'Codex', readiness: 'CLI_MISSING', diagnostic: '', selectedModel: 'sol', selectedEffort: '',
      capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: ['AUTO'] }, models: [{ id: 'sol', name: 'Sol', description: '', efforts: [] }] }],
    apiKeys: [], keyProviders: [],
  };
  window.bridge = {
    getModelSettings: async () => { reads += 1; return settings; },
    refreshModelSettings: async () => { probes += 1; return settings; },
  } as Pick<Bridge, 'getModelSettings' | 'refreshModelSettings'> as Bridge;
  function Actions({ workspaceId }: { workspaceId?: string }) {
    const availability = useAgentAvailability(workspaceId);
    return <section data-scope={workspaceId ?? 'global'}>{availability.development && <button>Start development</button>}<span>Saved task history</span></section>;
  }
  try {
    flushSync(() => root.render(<AgentAvailabilityProvider><Actions /><Actions workspaceId="work" /></AgentAvailabilityProvider>));
    assert(host.querySelector('button') === null, 'New actions must stay hidden before readiness loads');
    await settle();
    assert(probes === 1 && host.querySelector('button') === null, 'Missing CLI must hide new actions after one startup check');
    settings = { ...settings, providers: [{ ...settings.providers[0], readiness: 'READY' }] };
    window.dispatchEvent(new Event('bytequay:models-changed'));
    await settle();
    assert(host.querySelectorAll('button').length === 2, 'Saving a usable agent must unlock global and workspace actions');
    settings = { ...settings, providers: [{ ...settings.providers[0], workspaceId: 'work' }] };
    window.dispatchEvent(new Event('bytequay:models-changed'));
    await settle();
    assert(host.querySelector('[data-scope="global"] button') === null && host.querySelector('[data-scope="work"] button') !== null,
      'Repository-only accounts must not unlock global features');
    settings = { ...settings, providers: [{ ...settings.providers[0], readiness: 'AUTHENTICATION_REQUIRED' }] };
    const beforeFocus = reads;
    window.dispatchEvent(new Event('focus'));
    await settle();
    assert(reads > beforeFocus && probes === 1, 'Focus must reread readiness without repeating expensive CLI checks');
    assert(host.querySelector('button') === null && host.textContent?.includes('Saved task history'), 'Lost authentication hides starts and preserves history');
    return [{ name: 'Readiness changes and scope preserve history and gate new work' }];
  }
  catch (error) { return [{ name: 'Agent availability updates', error: error instanceof Error ? error.message : String(error) }]; }
  finally { dispose(); }
}
