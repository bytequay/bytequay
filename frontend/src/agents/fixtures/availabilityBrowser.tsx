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
import { assert, runScenarios, settle } from '../../fixtures/harness';
import type { Bridge, ModelSettingsDto } from '../../types';
import { PullRequestWorkspaceProvider } from '../../pulls/PullRequestWorkspace';
import { settingsRoute } from '../../settings/routes';
import { AgentHomeView } from '../AgentHome';
import { AgentTaskList, type TaskComposerProps } from '../AgentTasksView';
import { NO_AGENT_CAPABILITIES } from '../capabilities';
import { AgentAvailabilityProvider, useAgentAvailability } from '../availability';

async function readinessAndScope() {
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
  }
  finally { dispose(); }
}

async function workspaceComposers() {
  const { host, root, dispose } = createFixtureRoot();
  let settings: ModelSettingsDto = { agents: [], providers: [], apiKeys: [], keyProviders: [] };
  let fail = false;
  window.bridge = {
    getModelSettings: async () => { if (fail) throw new Error('Unavailable'); return settings; },
    refreshModelSettings: async () => settings,
    getLinkedWorkspaceWork: async () => [],
  } as Pick<Bridge, 'getModelSettings' | 'refreshModelSettings' | 'getLinkedWorkspaceWork'> as Bridge;
  const composer: TaskComposerProps = {
    workspaceId: 'work', laneId: 'codex', providers: [], models: [], selection: null, draft: '', blocker: null,
    onDraftChange: () => {}, onSelectProvider: () => {}, onSelectModel: () => {}, onCreate: () => {},
  };
  const repository = 'group/subgroup/repository';
  const server = { provider: 'GITLAB' as const, webBaseUrl: 'https://gitlab.example.com', apiBaseUrl: 'https://gitlab.example.com/api/v4' };
  const notices = () => [...host.querySelectorAll<HTMLAnchorElement>('.agt-dock a')].filter(link => link.getAttribute('href') === settingsRoute('ws-agents', 'work'));
  try {
    flushSync(() => root.render(<AgentAvailabilityProvider><PullRequestWorkspaceProvider workspaceId="work" server={server} repository={repository}>
      <AgentHomeView {...composer} repository={repository} tasks={[]} onOpenTask={() => {}} onOpenReview={() => {}} onShowAll={() => {}} />
      <AgentTaskList {...composer} repository={repository} tasks={[]} loading={false} error={null} onOpenTask={() => {}} />
    </PullRequestWorkspaceProvider></AgentAvailabilityProvider>));
    await settle();
    assert(notices().length === 2 && host.querySelector('textarea') === null, 'Home and Tasks explain agent setup and link to this workspace settings');
    assert(host.querySelector('.agt-start') === null, 'Unavailable agents do not offer task suggestions');

    settings = { ...settings, providers: [{ id: 'codex', label: 'Codex', readiness: 'READY', diagnostic: '', selectedModel: 'sol', selectedEffort: '',
      capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: ['AUTO'] }, models: [{ id: 'sol', name: 'Sol', description: '', efforts: [] }] }] };
    window.dispatchEvent(new Event('bytequay:models-changed'));
    await settle();
    assert(host.querySelectorAll('textarea[aria-label="Describe a task"]').length === 2 && notices().length === 0,
      'Both composers appear once the workspace has a usable agent');

    fail = true;
    window.dispatchEvent(new Event('focus'));
    await settle();
    assert(notices().length === 2 && host.querySelector('textarea') === null, 'A catalog failure leaves an actionable setup link instead of an empty dock');
  }
  finally { dispose(); }
}

export async function run() {
  return runScenarios([
    ['Readiness changes and scope preserve history and gate new work', readinessAndScope],
    ['GitLab workspace Home and Tasks explain unavailable composers', workspaceComposers],
  ]);
}
