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
import SettingsWindow from '../SettingsWindow';
import type { AgentSkillInput, AppPreferencesDto } from '../../types';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, button, changeInput, element, runScenarios, settle, until } from '../../fixtures/harness';

const preferences: AppPreferencesDto = { theme: 'SYSTEM', taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30, autoMergeDelayMinutes: 60 };

export async function run() {
  return runScenarios([
    ['Retain failed skill edits and retry the same text', async () => {
      const pending = Promise.withResolvers<never>();
      const writes: AgentSkillInput[] = [];
      Object.defineProperty(window, 'bridge', { configurable: true, value: {
        getAppVersion: async () => ({ version: 'test' }), getAppPreferences: async () => preferences, getAgentSkills: async () => [],
        addAgentSkill: async (input: AgentSkillInput) => {
          writes.push(input);
          if (writes.length === 1) return pending.promise;
          return [{ ...input, id: 'saved' }];
        },
      } });
      const view = createFixtureRoot();
      try {
        flushSync(() => view.root.render(<SettingsWindow section="skills" workspace={null} onWorkspacesChanged={() => {}} />));
        await settle();
        button('Add skill', view.host).click(); await settle();
        const dialog = element<HTMLDialogElement>('dialog', view.host);
        changeInput(element<HTMLInputElement>('input', dialog), 'My rule');
        changeInput(element<HTMLTextAreaElement>('textarea', dialog), 'Keep my carefully written instructions.');
        await settle(); button('Add skill', dialog).click(); await settle();
        assert(element<HTMLTextAreaElement>('textarea', dialog).disabled, 'Pending save prevents new edits from being cleared');
        pending.reject(new Error('Save unavailable'));
        await until(() => dialog.textContent?.includes('Save unavailable') === true, 'Save failure must remain visible in the dialog');
        assert(element<HTMLTextAreaElement>('textarea', dialog).value === 'Keep my carefully written instructions.', 'Failed save keeps instructions');
        assert(element<HTMLInputElement>('input', dialog).value === 'My rule', 'Failed save keeps name');
        button('Add skill', dialog).click();
        await until(() => view.host.querySelector('dialog') === null, 'Successful retry closes the editor');
        assert(writes.length === 2 && JSON.stringify(writes[0]) === JSON.stringify(writes[1]), 'Retry preserves the full draft');
      }
      finally { view.dispose(); }
    }],
    ['Save quick preference changes in order and recover from failure', async () => {
      const first = Promise.withResolvers<AppPreferencesDto>();
      const writes: AppPreferencesDto[] = [];
      let saved = preferences;
      let fail = false;
      Object.defineProperty(window, 'bridge', { configurable: true, value: {
        getAppVersion: async () => ({ version: 'test' }), getAppPreferences: async () => saved,
        saveAppPreferences: async (value: AppPreferencesDto) => {
          writes.push(value);
          if (writes.length === 1) return first.promise;
          if (fail) throw new Error('Preferences unavailable');
          saved = value; return saved;
        },
      } });
      const view = createFixtureRoot();
      try {
        flushSync(() => view.root.render(<SettingsWindow section="appearance" workspace={null} onWorkspacesChanged={() => {}} />));
        await settle(); button('Light', view.host).click(); await settle();
        button('Dark', view.host).click(); await settle();
        assert(writes.length === 1, 'The second save waits for the first');
        assert(button('Dark', view.host).getAttribute('aria-checked') === 'true', 'The latest choice stays visible while saving');
        first.resolve({ ...preferences, theme: 'LIGHT' });
        await until(() => writes.length === 2, 'Both choices must reach persistence');
        assert(writes[0].theme === 'LIGHT' && writes[1].theme === 'DARK', 'The final choice is saved last');
        await settle(); fail = true; button('System', view.host).click();
        await until(() => view.host.textContent?.includes('Preferences unavailable') === true, 'Failed save is disclosed');
        await settle();
        assert(button('Dark', view.host).getAttribute('aria-checked') === 'true', 'Failed save restores the stored choice');
        fail = false; button('Light', view.host).click(); await settle();
        assert(saved.theme === 'LIGHT', 'A failed save does not poison subsequent saves');
      }
      finally { view.dispose(); }
    }],
  ]);
}
