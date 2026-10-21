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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { parseAgentSkillInput } from '../../agents/contracts';
import type { AgentSkillDto, AgentSkillInput, AgentSkillScope } from '../../types';
import SkillsPage from '../SkillsPage';
import { assert, changeInput, settle, element } from '../../fixtures/harness';



const SCOPE_LABELS: Record<AgentSkillScope, string> = {
  GLOBAL: 'Global',
  DEVELOPMENT: 'Development',
  REVIEW: 'Review',
};

/** The shell's own save wiring, so the fixture drives the real controls rather than a stand-in. */
function Harness({ initial, writes }: { initial: AgentSkillDto[]; writes: AgentSkillInput[] }) {
  const [skills, setSkills] = useState(initial);
  const save = (input: AgentSkillInput, id: string | null) => {
    const skill = parseAgentSkillInput(input);
    writes.push(skill);
    setSkills(current => (id === null
      ? [...current, { ...skill, id: `skill-${current.length}` }]
      : current.map(existing => (existing.id === id ? { ...skill, id } : existing))));
  };
  return (
    <SkillsPage
      skills={skills}
      busy={false}
      error={null}
      onSave={save}
      onToggle={skill => save({ ...skill, enabled: !skill.enabled }, skill.id)}
      onRemove={skill => setSkills(current => current.filter(existing => existing.id !== skill.id))}
    />
  );
}

function fixture(initial: AgentSkillDto[] = []) {
  const writes: AgentSkillInput[] = [];
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  flushSync(() => root.render(<Harness initial={initial} writes={writes} />));

  /** While a dialog is open it owns the controls, so "Add skill" means confirm, not reopen. */
  const scope_ = (): ParentNode => host.querySelector('.setw-dialog') ?? host;

  const scopeRadios = () => {
    const group = [...scope_().querySelectorAll('[role="radiogroup"]')]
      .find(candidate => candidate.getAttribute('aria-label') === 'Skill scope');
    assert(group !== undefined, 'The dialog must expose a labelled scope control');
    return [...group.querySelectorAll('button')];
  };

  return {
    host,
    writes,
    rows: () => [...host.querySelectorAll('.setw-row')],
    async click(label: string) {
      const button = [...scope_().querySelectorAll('button')]
        .find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label);
      assert(button !== undefined && !button.disabled, `Button ${label} must be available`);
      button.click();
      await settle();
    },
    async fill(selector: string, value: string) {
      const input = element<HTMLInputElement | HTMLTextAreaElement>(selector, scope_(), `Input ${selector} is missing`);
      changeInput(input, value);
      await settle();
    },
    scope(): AgentSkillScope {
      const chosen = scopeRadios().find(button => button.getAttribute('aria-checked') === 'true');
      assert(chosen !== undefined, 'A scope must always be chosen');
      const entry = Object.entries(SCOPE_LABELS).find(([, label]) => label === chosen.textContent?.trim());
      assert(entry !== undefined, `Unknown scope label ${chosen.textContent}`);
      return entry[0] as AgentSkillScope;
    },
    async select(next: AgentSkillScope) {
      const button = scopeRadios().find(candidate => candidate.textContent?.trim() === SCOPE_LABELS[next]);
      assert(button !== undefined, `Scope ${next} must be offered`);
      button.click();
      await settle();
    },
    enabled(name: string): boolean {
      const toggle = [...host.querySelectorAll('button')]
        .find(item => item.getAttribute('aria-label') === `Enable ${name}`);
      assert(toggle !== undefined, `${name} must have an enable switch`);
      return toggle.getAttribute('aria-checked') === 'true';
    },
    dispose() { disposeRoot(); },
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (
    name: string,
    test: (view: ReturnType<typeof fixture>) => Promise<void>,
    initial?: AgentSkillDto[],
  ) => {
    const view = fixture(initial);
    try { await settle(); await test(view); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { view.dispose(); }
  };

  await check('creates each user scope and shows only the saved custom skills', async view => {
    assert(view.rows().length === 0, 'An empty collection must not contain rows');
    for (const [scope, label] of [['GLOBAL', 'Global'], ['DEVELOPMENT', 'Development'], ['REVIEW', 'Review']] as const) {
      await view.click('Add skill');
      assert(view.scope() === 'GLOBAL', 'A new skill must default to Global');
      await view.fill('input[aria-label="Skill name"]', `  ${label} preference  `);
      await view.fill('textarea', `Instructions for ${label}.`);
      if (scope !== 'GLOBAL') await view.select(scope);
      await view.click('Add skill');
      assert(JSON.stringify(view.writes.at(-1)) === JSON.stringify({
        name: `${label} preference`, instructions: `Instructions for ${label}.`, enabled: true, scope,
      }), `${label} must reach the save payload complete`);
      assert(view.rows().some(row => row.textContent?.includes(label)), `${label} must be shown on the saved row`);
    }
    assert(view.rows().length === 3, 'Only the three user-created skills should be listed');
  });

  await check('preserves scope through content edits and toggles and allows changing it', async view => {
    await view.click('Edit Review preference');
    assert(view.scope() === 'REVIEW', 'Edit must start with the saved Review scope');
    await view.fill('textarea', 'Updated review instructions.');
    await view.click('Save skill');
    assert(view.writes.at(-1)?.scope === 'REVIEW' && view.writes.at(-1)?.instructions === 'Updated review instructions.',
      'Editing content must preserve the saved scope');

    await view.click('Enable Review preference');
    assert(view.writes.at(-1)?.scope === 'REVIEW' && view.writes.at(-1)?.enabled === false, 'Disabling must preserve the scope');
    assert(!view.enabled('Review preference'), 'The switch must read as off');

    await view.click('Edit Review preference');
    await view.select('DEVELOPMENT');
    await view.click('Save skill');
    assert(view.writes.at(-1)?.scope === 'DEVELOPMENT' && view.writes.at(-1)?.enabled === false,
      'Changing scope must preserve the disabled state');
    assert(view.rows().some(row => row.textContent?.includes('Development')), 'The row must reflect its new scope');

    await view.click('Enable Review preference');
    assert(view.writes.at(-1)?.scope === 'DEVELOPMENT' && view.writes.at(-1)?.enabled === true,
      'Enabling must preserve the changed scope');

    // Removal lives in the dialog rather than on the row, but it must still be reachable.
    await view.click('Edit Review preference');
    await view.click('Remove skill');
    assert(view.rows().length === 0, 'Removing must drop the skill from the list');
  }, [{ id: 'review', name: 'Review preference', instructions: 'Original instructions.', enabled: true, scope: 'REVIEW' }]);

  return results;
}
