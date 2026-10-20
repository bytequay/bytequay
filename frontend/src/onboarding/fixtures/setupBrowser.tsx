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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import App from '../../App';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { LOCAL_COMMITS_ONLY } from '../../agents/developmentAutomation';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { assert, settle, until } from '../../fixtures/harness';
import type { AgendaScheduleDto, AppPreferencesDto, Bridge, ModelSettingsDto, SetupPreferencesDto, SetupStep } from '../../types';
import SetupFlow from '../SetupFlow';
import { OPEN_SETUP } from '../setup';
import '../../index.css';

const CATALOG: ModelSettingsDto = {
  agents: ['DEVELOPMENT', 'REVIEW'].map(type => ({ type: type as 'DEVELOPMENT' | 'REVIEW', explicit: false,
    configId: 'codex', model: 'sol', modelLabel: 'Sol', label: 'Codex', effort: 'high' })),
  providers: [{ id: 'codex', label: 'Codex', readiness: 'READY', diagnostic: '', selectedModel: 'sol', selectedEffort: 'high',
    capabilities: { ...NO_AGENT_CAPABILITIES, permissionPolicies: ['AUTO', 'CI_AUTOFIX'] },
    models: [{ id: 'sol', name: 'Sol', description: '', efforts: ['high'] }] }],
  apiKeys: [], keyProviders: [{ name: 'DeepSeek', models: ['deepseek-chat'] }],
};

function harness(step: SetupStep = 'APPEARANCE', available = true, connected = false, revisit = false) {
  let setup: SetupPreferencesDto = { step, assistantEnabled: false };
  let preferences: AppPreferencesDto = { theme: 'SYSTEM', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 };
  let models: ModelSettingsDto = { ...CATALOG, providers: CATALOG.providers.map(provider => ({ ...provider, readiness: available ? 'READY' : 'CLI_MISSING' })) };
  let automation = { ...LOCAL_COMMITS_ONLY };
  let agenda: AgendaScheduleDto = { enabled: false, minute: 540, days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'], calendarAccountId: null };
  let permissions = { startTasks: false, answerTasks: false, changeWork: false, markAttention: false };
  let finished = false;
  let failSave = false;
  let selected = false;
  const writes: SetupPreferencesDto[] = [];
  const bridge: Partial<Bridge> = {
    ...createPreviewBridge('workspace'),
    getSetupPreferences: async () => setup,
    saveSetupPreferences: async value => { if (failSave) throw new Error('Unable to save setup'); setup = value; writes.push(value); return value; },
    getAppPreferences: async () => preferences,
    saveAppPreferences: async value => { preferences = value; document.documentElement.style.colorScheme = value.theme.toLowerCase(); return value; },
    getModelSettings: async () => models,
    refreshModelSettings: async () => models,
    setAgentModel: async (type, configId, model, effort) => {
      selected = true;
      models = { ...models, agents: models.agents.map(agent => agent.type === type ? { ...agent, configId, model, effort, explicit: true } : agent) };
      return models;
    },
    getDevelopmentAutomation: async () => automation,
    saveDevelopmentAutomation: async value => { automation = value; return value; },
    getAgendaSchedule: async () => ({ ...agenda, days: [...agenda.days] }),
    saveAgendaSchedule: async value => { agenda = { ...agenda, enabled: value.enabled, minute: value.minute }; return value; },
    getAssistantPermissions: async () => permissions,
    saveAssistantPermissions: async value => { permissions = value; return value; },
    getGitHubCliAvailable: async () => ({ available: true }),
    importGitHubCliToken: async () => { connected = true; return { login: 'example' }; },
    hasPat: async () => connected,
    controlWindow: async () => {},
    getInAppBrowserState: async () => ({ tabs: [], activeTabId: null }),
    onInAppBrowserState: () => () => {}, getFullScreen: async () => false, onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {}, onPrAttentionUpdated: () => () => {}, onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {}, stopFindInPage: async () => {}, listJiraConnections: async () => [], listJiraAttention: async () => [],
    listWorkspaces: async () => [], fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [], onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
  };
  window.bridge = bridge as Bridge;
  const host = document.createElement('div'); document.body.append(host);
  let root = createRoot(host);
  function Wizard() {
    const [value, setValue] = useState(setup);
    const [signedIn, setSignedIn] = useState(connected);
    return <SetupFlow initial={value} connected={signedIn} revisit={revisit} onSaved={setValue}
      onConnected={() => setSignedIn(true)} onFinish={() => { finished = true; }} />;
  }
  const render = (app = false) => { flushSync(() => root.render(app ? <App /> : <Wizard />)); };
  const reloadApp = () => { flushSync(() => root.unmount()); root = createRoot(host); render(true); };
  const dispose = () => { flushSync(() => root.unmount()); host.remove(); document.documentElement.style.colorScheme = ''; };
  const state = () => ({ setup, preferences, automation, agenda, permissions, finished, selected });
  return { host, render, reloadApp, dispose, state, writes, setFailure: (value: boolean) => { failSave = value; },
    setAutomation: (value: typeof automation) => { automation = value; },
    ready: () => { models = CATALOG; }, disconnect: () => { connected = false; } };
}

function button(host: HTMLElement, label: string) {
  const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label || button.getAttribute('aria-label') === label);
  assert(value !== undefined, `Missing button: ${label}`); return value;
}
const title = (host: HTMLElement, text: string) => until(() => host.querySelector('h1')?.textContent === text, `Missing step: ${text}`);
const click = async (host: HTMLElement, label: string) => { button(host, label).click(); await settle(); };

export async function run() {
  const results: { name: string; error?: string }[] = [];
  const scenario = async (name: string, work: () => Promise<void>) => {
    try { await work(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  };
  await scenario('Setup saves choices, requires GitHub and a usable agent, then completes', async () => {
    const view = harness('APPEARANCE', false);
    try {
      view.render(); await title(view.host, 'Make yourself at home'); await until(() => !button(view.host, 'Continue').disabled, 'Preferences did not load');
      await click(view.host, 'Dark'); assert(view.state().preferences.theme === 'DARK', 'Theme is applied immediately');
      await click(view.host, 'Continue'); await title(view.host, 'Bring your GitHub work together');
      assert(button(view.host, 'Continue').disabled, 'GitHub is required before proceeding');
      await click(view.host, 'Use my GitHub CLI login'); await click(view.host, 'Continue'); await title(view.host, 'Choose who you work with');
      await until(() => !button(view.host, 'Check agents again').disabled, 'Probe did not finish');
      assert(button(view.host, 'Continue').disabled, 'Missing agents cannot unlock setup');
      view.ready(); await click(view.host, 'Check agents again'); await click(view.host, 'Continue'); await title(view.host, 'Your everyday agent');
      await click(view.host, 'Continue'); await title(view.host, 'How should your agent work?');
      assert(view.state().selected, 'The selected default is explicitly saved');
      for (const toggle of view.host.querySelectorAll('[role="switch"]')) assert(toggle.getAttribute('aria-checked') === 'false', 'Automation must start off');
      await click(view.host, 'Auto draft PR'); await click(view.host, 'Auto draft review replies'); await click(view.host, 'Continue');
      await title(view.host, 'A little help with your day');
      assert(view.state().automation.autoDraftPr && view.state().automation.reviewReply && !view.state().automation.autoCiFix, 'Only chosen automation is saved');
      await click(view.host, 'Enable daily agenda'); await click(view.host, 'Enable Assistant');
      assert(button(view.host, 'Assistant can start tasks').getAttribute('aria-checked') === 'false', 'Enabling chat cannot authorize task actions');
      await click(view.host, 'Open ByteQuay');
      assert(view.state().finished && view.state().setup.step === 'COMPLETE' && view.state().setup.assistantEnabled && view.state().agenda.enabled, 'Completion and opt-ins persist');
      assert(!view.state().permissions.startTasks && !view.state().permissions.answerTasks, 'Assistant permissions remain separate');
    } finally { view.dispose(); }
  });
  await scenario('A resumed agent step keeps fresh readiness when initial preferences finish later', async () => {
    const view = harness('AGENT', false, true);
    const staleCatalog = await window.bridge.getModelSettings();
    const preferences = Promise.withResolvers<AppPreferencesDto>();
    const catalog = Promise.withResolvers<ModelSettingsDto>();
    window.bridge.getAppPreferences = () => preferences.promise;
    window.bridge.getModelSettings = () => catalog.promise;
    const refresh = window.bridge.refreshModelSettings;
    let refreshed = false;
    window.bridge.refreshModelSettings = async (...args) => {
      const value = await refresh(...args); refreshed = true; return value;
    };
    view.ready();
    try {
      view.render(); await title(view.host, 'Choose who you work with');
      await until(() => refreshed, 'The resumed step did not probe its agents'); await settle();
      assert(button(view.host, 'Continue').disabled, 'Pending initial preferences must keep Continue disabled');
      catalog.resolve(staleCatalog); preferences.resolve(view.state().preferences);
      await until(() => view.host.querySelector('.setup-body') !== null, 'Initial preferences did not finish');
      assert(!button(view.host, 'Continue').disabled, 'Delayed cached readiness must not overwrite the usable probe result');
      await click(view.host, 'Continue'); await title(view.host, 'Your everyday agent');
    } finally {
      catalog.resolve(staleCatalog); preferences.resolve(view.state().preferences); view.dispose();
    }
  });
  await scenario('Skipping agents completes setup and stays completed on reopening the app', async () => {
    const view = harness('AGENT', false, true);
    try {
      view.render(); await until(() => !button(view.host, 'Continue without an agent').disabled, 'Setup did not load');
      await click(view.host, 'Continue without an agent');
      assert(view.state().setup.step === 'COMPLETE' && !view.state().setup.assistantEnabled, 'Skipping completes without enabling features');
      window.location.hash = '#/recent'; view.render(true);
      await until(() => view.host.querySelector('.app-sidebar') !== null, 'App did not open');
      assert(view.host.querySelector('.setup-shell') === null && view.host.querySelector('[aria-label="Assistant"]') === null, 'Completed setup and unavailable Assistant remain hidden');
      view.reloadApp(); await until(() => view.host.querySelector('.app-sidebar') !== null, 'App did not reopen');
      assert(view.host.querySelector('.setup-shell') === null, 'Completed setup never returns on launch');
      view.disconnect(); view.reloadApp(); await until(() => view.host.querySelector('.onboarding') !== null, 'GitHub reconnect did not appear');
      assert(view.host.querySelector('.setup-shell') === null, 'Disconnected GitHub cannot restart completed setup');
      window.dispatchEvent(new Event(OPEN_SETUP)); await title(view.host, 'Make yourself at home');
      await click(view.host, 'Continue'); assert(view.state().setup.step === 'COMPLETE', 'Explicit revisits must not erase completion');
      await click(view.host, 'Close setup');
      assert(view.host.querySelector('.setup-shell') === null, 'Explicitly opened setup closes');
    } finally { view.dispose(); }
  });
  await scenario('Resumed CI automation can disable an unavailable lane or select the ready default', async () => {
    const view = harness('AUTOMATION', true, true);
    view.setAutomation({ ...LOCAL_COMMITS_ONLY, autoCiFix: true, ciLaneId: 'removed-lane' });
    try {
      view.render(); await title(view.host, 'How should your agent work?');
      await until(() => view.host.querySelector('.setup-body') !== null, 'Preferences did not load');
      assert(button(view.host, 'Continue').disabled, 'An unavailable CI lane must prevent enabling its automation');
      assert(!button(view.host, 'Auto CI fix').disabled, 'Enabled CI automation can always be turned off');
      const selector = view.host.querySelector<HTMLSelectElement>('.agt-automation-ci select');
      assert(selector !== null && selector.value === 'removed-lane', 'The saved unavailable CI lane remains selectable');
      await click(view.host, 'Auto CI fix');
      assert(!button(view.host, 'Continue').disabled, 'Disabled CI automation does not block setup');
      selector.value = ''; selector.dispatchEvent(new Event('change', { bubbles: true })); await settle();
      await click(view.host, 'Auto CI fix'); await click(view.host, 'Continue');
      await title(view.host, 'A little help with your day');
      assert(view.state().automation.autoCiFix && view.state().automation.ciLaneId === null, 'The ready Development default replaces the unavailable CI lane');
    } finally { view.dispose(); }
  });
  await scenario('Resuming keeps progress and save failures leave the step retryable', async () => {
    const view = harness('AUTOMATION', true, true);
    try {
      view.render(); await title(view.host, 'How should your agent work?'); await until(() => !button(view.host, 'Continue').disabled, 'Setup did not load');
      view.setFailure(true); await click(view.host, 'Continue');
      assert(view.host.querySelector('[role="alert"]')?.textContent?.includes('Unable to save setup') === true, 'Saving errors are visible');
      assert(view.host.querySelector('h1')?.textContent === 'How should your agent work?', 'Failed persistence cannot advance');
      view.setFailure(false); await click(view.host, 'Continue'); await title(view.host, 'A little help with your day');
    } finally { view.dispose(); }
  });
  return results;
}

let preview: ReturnType<typeof harness> | null = null;
export async function show(step: SetupStep) {
  preview?.dispose(); preview = harness(step, true, true); preview.render(); await settle(); await settle();
}
