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
import type { ModelSettingsDto } from '../types';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { AgentAvailabilityProvider, agentAvailability, canUseAgent, useAgentAvailability } from './availability';

const settings: ModelSettingsDto = {
  agents: ['DEVELOPMENT', 'REVIEW'].map(type => ({ type: type as 'DEVELOPMENT' | 'REVIEW', explicit: true, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol' })),
  providers: [{ id: 'codex', label: 'Codex', readiness: 'READY', diagnostic: '', selectedModel: 'sol', selectedEffort: '',
    capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: ['AUTO'] }, models: [{ id: 'sol', name: 'Sol', description: '', efforts: [] }] }],
  apiKeys: [], keyProviders: [],
};

describe('agent feature availability', () => {
  it('requires a ready lane, an executable model, and the action permission policy', () => {
    expect(agentAvailability(settings)).toEqual({ any: true, development: true, review: true });
    expect(canUseAgent(settings, 'DEVELOPMENT', 'CI_AUTOFIX')).toBe(false);
    for (const readiness of ['CLI_MISSING', 'AUTHENTICATION_REQUIRED', 'UNSUPPORTED', 'TEMPORARILY_UNAVAILABLE'] as const) {
      expect(agentAvailability({ ...settings, providers: [{ ...settings.providers[0], readiness }] })).toEqual({ any: false, development: false, review: false });
    }
    expect(agentAvailability({ ...settings, providers: [{ ...settings.providers[0], readiness: 'DEGRADED' }] }).development).toBe(true);
    expect(agentAvailability({ ...settings, providers: [{ ...settings.providers[0], models: [] }] }).any).toBe(false);
    expect(agentAvailability({ ...settings, providers: [{ ...settings.providers[0], capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: [] } }] }).development).toBe(false);
  });

  it('does not replace an unavailable configured agent with another ready lane', () => {
    const withOther = { ...settings, providers: [{ ...settings.providers[0], id: 'claude' }] };
    expect(agentAvailability(withOther)).toEqual({ any: true, development: false, review: false });
    expect(agentAvailability({ ...settings, agents: settings.agents.map(agent => ({ ...agent, model: 'removed' })) }).development).toBe(false);
  });

  it('does not unlock global or other repository features with a repository-owned account', () => {
    const scoped = { ...settings, providers: [{ ...settings.providers[0], workspaceId: 'work' }] };
    expect(agentAvailability(scoped)).toEqual({ any: false, development: false, review: false });
    expect(agentAvailability(scoped, 'other').development).toBe(false);
    expect(agentAvailability(scoped, 'work').development).toBe(true);
  });

  it('fails closed before the app catalog has loaded', () => {
    function Actions() { const availability = useAgentAvailability(); return <>{availability.any ? 'New task' : 'Connect an agent'}<span>Saved task history</span></>; }
    const html = renderToStaticMarkup(<AgentAvailabilityProvider><Actions /></AgentAvailabilityProvider>);
    expect(html).not.toContain('New task');
    expect(html).toContain('Connect an agent');
    expect(html).toContain('Saved task history');
  });
});
