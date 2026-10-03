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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge, ModelSettingsDto } from '../../types';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { assert, settle } from '../../fixtures/harness';
import SettingsWindow from '../SettingsWindow';
import { entryFor, type SettingsSection } from '../routes';
import '../../css/base.css';

const saved: ModelSettingsDto = {
  checkedAt: '2026-10-03T01:00:00Z',
  agents: [{ type: 'DEVELOPMENT', explicit: true, configId: 'codex', label: 'Codex', model: 'gpt-6-sol', modelLabel: 'GPT-6 Sol', effort: 'high' }],
  providers: [{
    id: 'codex', label: 'Codex', readiness: 'READY', version: '0.156.1', diagnostic: '', capabilities: NO_AGENT_CAPABILITIES,
    models: [{ id: 'gpt-6-sol', name: 'GPT-6 Sol', description: '', efforts: ['high'], defaultEffort: 'high' }],
    selectedModel: 'gpt-6-sol', selectedEffort: 'high',
  }],
  apiKeys: [], keyProviders: [],
};
const latest: ModelSettingsDto = {
  ...saved,
  providers: [{ ...saved.providers[0], models: [...saved.providers[0].models,
    { id: 'gpt-6.1-sol', name: 'GPT-6.1 Sol', description: '', efforts: ['high'], defaultEffort: 'high' }] }],
};

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, test] of Object.entries({
    async 'saved controls paint before the provider refresh finishes'(view: View) {
      await settle();
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('GPT-6 Sol') === true, 'Saved model renders while refresh is pending');
      assert(view.forces.length === 1 && view.forces[0] === false, 'Opening uses an automatic refresh');
      assert(entryFor('ws-agents').label === 'Model overrides', 'Workspace navigation describes overrides');
      assert(view.host.textContent?.includes('Default models') === true, 'Global defaults have a distinct heading');
      view.finish(latest); await settle();
      view.host.querySelector<HTMLButtonElement>('[aria-label="Development model"]')?.click(); await settle();
      assert(document.body.textContent?.includes('GPT-6.1 Sol') === true, 'Newly advertised model becomes selectable');
    },
    async 'manual checks force discovery and preserve choices on failure'(view: View) {
      await settle(); view.finish(saved); await settle();
      const check = [...view.host.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Check');
      assert(check !== undefined, 'Check is available'); check.click(); await settle();
      assert(view.forces.at(-1) === true, 'Check bypasses the freshness interval');
      view.fail(new Error('Provider temporarily unavailable')); await settle();
      assert(view.host.textContent?.includes('Provider temporarily unavailable') === true, 'Refresh errors stay visible');
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('GPT-6 Sol') === true, 'Failure retains the saved choice');
    },
    async 'leaving models ignores a late refresh error'(view: View) {
      await settle(); view.render('browser'); await settle();
      view.fail(new Error('Old page refresh failed')); await settle();
      assert(view.host.querySelector('h1')?.textContent === 'Browser', 'Navigation remains responsive');
      assert(!view.host.textContent?.includes('Old page refresh failed'), 'Old request does not affect the new page');
    },
  })) {
    const host = document.createElement('div'); document.body.append(host);
    const root = createRoot(host);
    const forces: boolean[] = [];
    let finish: (value: ModelSettingsDto) => void = () => { throw new Error('No pending refresh'); };
    let fail: (error: Error) => void = () => { throw new Error('No pending refresh'); };
    const bridge = {
      getAppVersion: async () => ({ version: '0.4.0' }),
      getAppPreferences: async () => ({ theme: 'SYSTEM' as const, taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 }),
      getModelSettings: async () => saved,
      refreshModelSettings: (force = false) => { forces.push(force); return new Promise<ModelSettingsDto>((resolve, reject) => { finish = resolve; fail = reject; }); },
    } satisfies Partial<Bridge>;
    window.bridge = bridge as Bridge;
    const render = (section: SettingsSection) => flushSync(() => root.render(<SettingsWindow section={section} workspace={null} onWorkspacesChanged={() => {}} />));
    try {
      render('models');
      await test({ host, forces, render, finish: value => finish(value), fail: error => fail(error) });
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
  return results;
}

type View = {
  host: HTMLElement;
  forces: boolean[];
  render: (section: SettingsSection) => void;
  finish: (value: ModelSettingsDto) => void;
  fail: (error: Error) => void;
};
