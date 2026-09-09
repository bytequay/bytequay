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
import { parseAgentSkillInput } from '../../agents/contracts';
import type { AgentSkillDto, AgentSkillInput, AgentSkillScope } from '../../types';
import AgentSkillsCard from '../AgentSkillsCard';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function fixture(initial: AgentSkillDto[] = []) {
  let saved = initial;
  const writes: AgentSkillInput[] = [];
  const bridge = {
    getAgentSkills: async () => saved,
    addAgentSkill: async (input: AgentSkillInput) => {
      const skill = parseAgentSkillInput(input);
      writes.push(skill);
      saved = [...saved, { ...skill, id: `skill-${saved.length}` }];
      return saved;
    },
    updateAgentSkill: async (id: string, input: AgentSkillInput) => {
      const skill = parseAgentSkillInput(input);
      writes.push(skill);
      saved = saved.map(existing => existing.id === id ? { ...skill, id } : existing);
      return saved;
    },
  } satisfies Partial<Window['bridge']>;
  Object.defineProperty(window, 'bridge', { value: bridge, configurable: true });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentSkillsCard />));
  return {
    host,
    writes,
    async click(label: string) {
      const button = [...host.querySelectorAll('button')].find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
      assert(button !== undefined && !button.disabled, `Button ${label} must be available`);
      button.click();
      await settle();
    },
    async fill(selector: string, value: string) {
      const input = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector);
      assert(input !== null, `Input ${selector} is missing`);
      const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await settle();
    },
    scope() {
      const select = host.querySelector('select');
      assert(select !== null && select.labels?.[0]?.textContent?.trim().startsWith('Scope') === true, 'Scope must have an accessible label');
      return select;
    },
    async select(scope: AgentSkillScope) {
      const select = this.scope();
      select.value = scope;
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
    },
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (name: string, test: (view: ReturnType<typeof fixture>) => Promise<void>, initial?: AgentSkillDto[]) => {
    const view = fixture(initial);
    try { await settle(); await test(view); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { view.dispose(); }
  };
  await check('creates each user scope and shows only the saved custom skills', async view => {
    assert(view.host.querySelectorAll('.sets-skill-row').length === 0, 'An empty collection must not contain system skill rows');
    for (const [scope, label] of [['GLOBAL', 'Global'], ['DEVELOPMENT', 'Development'], ['REVIEW', 'Review']] as const) {
      await view.click('+ Add skill');
      assert(view.scope().value === 'GLOBAL', 'A new skill must default to Global');
      await view.fill('input[maxlength="100"]', `  ${label} preference  `);
      await view.fill('textarea', `Instructions for ${label}.`);
      if (scope !== 'GLOBAL') await view.select(scope);
      await view.click('Save skill');
      assert(JSON.stringify(view.writes.at(-1)) === JSON.stringify({
        name: `${label} preference`, instructions: `Instructions for ${label}.`, enabled: true, scope,
      }), `${label} must reach the bridge in the complete save payload`);
      assert([...view.host.querySelectorAll('.sets-skill-row')].some(row => row.textContent?.includes(`${label} · Enabled`)), `${label} must be displayed on the saved row`);
    }
    assert(view.host.querySelectorAll('.sets-skill-row').length === 3, 'Only the three user-created skills should be listed');
  });
  await check('preserves scope through content edits and toggles and allows changing it', async view => {
    await view.click('Edit Review preference');
    assert(view.scope().value === 'REVIEW', 'Edit must start with the saved Review scope');
    await view.fill('textarea', 'Updated review instructions.');
    await view.click('Save skill');
    assert(view.writes.at(-1)?.scope === 'REVIEW' && view.writes.at(-1)?.instructions === 'Updated review instructions.', 'Editing content must preserve the saved scope');
    await view.click('Enable Review preference');
    assert(view.writes.at(-1)?.scope === 'REVIEW' && view.writes.at(-1)?.enabled === false, 'Disabling must preserve the saved scope');
    assert(view.host.textContent?.includes('Review · Disabled') === true, 'The row must keep its scope while disabled');
    await view.click('Edit Review preference');
    await view.select('DEVELOPMENT');
    await view.click('Save skill');
    assert(view.writes.at(-1)?.scope === 'DEVELOPMENT' && view.writes.at(-1)?.enabled === false, 'Changing scope must preserve the disabled state');
    assert(view.host.textContent?.includes('Development · Disabled') === true, 'The row must reflect its new scope');
    await view.click('Enable Review preference');
    assert(view.writes.at(-1)?.scope === 'DEVELOPMENT' && view.writes.at(-1)?.enabled === true, 'Enabling must preserve the changed scope');
  }, [{ id: 'review', name: 'Review preference', instructions: 'Original instructions.', enabled: true, scope: 'REVIEW' }]);
  return results;
}
