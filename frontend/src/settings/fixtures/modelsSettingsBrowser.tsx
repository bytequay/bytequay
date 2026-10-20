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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge, ModelSettingsDto, WorkspaceDto, WorkspaceSettingsDto } from '../../types';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { assert, changeInput, settle } from '../../fixtures/harness';
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
const claude = {
  ...saved.providers[0], id: 'claude', label: 'Claude Code',
  models: [{ id: 'opus', name: 'Opus', description: '', efforts: ['High'], defaultEffort: 'High' }],
  selectedModel: 'opus', selectedEffort: 'High', diagnostic: 'Signed in as default@example.com',
};
const account = { ...claude, id: 'claude-account-123', label: 'Claude · Work', workspaceId: 'repo-a', diagnostic: '', readiness: 'AUTHENTICATION_REQUIRED' as const };
const withAccount: ModelSettingsDto = { ...saved, providers: [...saved.providers, claude, account] };
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
    async 'global providers hide repository accounts and their models'(view: View) {
      await settle(); view.finish({ ...withAccount, providers: withAccount.providers.map(provider => provider.id === 'claude'
        ? { ...provider, readiness: 'AUTHENTICATION_REQUIRED', diagnostic: '' } : provider) }); await settle();
      assert(view.host.textContent?.includes('Claude Code CLI · Default account') === true, 'Default Claude stays in the global providers card');
      assert(!view.host.textContent?.includes('Claude · Work'), 'Named accounts stay out of global provider rows');
      assert(view.host.textContent?.includes('Add Claude account'), 'Global account setup is also available');
      const signIn = [...view.host.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Sign in');
      assert(signIn !== undefined, 'The default account can authenticate'); signIn.click(); await settle();
      assert(view.signIns[0] === 'claude', 'Global sign-in uses only the default profile');
      view.host.querySelector<HTMLButtonElement>('.bq-signin-dialog__button')?.click(); await settle();
      view.host.querySelector<HTMLButtonElement>('[aria-label="Development model"]')?.click(); await settle();
      const options = [...view.host.querySelectorAll<HTMLButtonElement>('[role="option"]')];
      assert(!options.some(button => button.textContent?.includes('Claude · Work')), 'Repository accounts cannot be picked as global defaults');
      const choice = options.find(button => button.textContent?.includes('Opus') && button.textContent?.includes('Claude Code'));
      assert(choice !== undefined, 'Default Claude models remain available'); choice.click(); await settle();
      assert(view.chosen[0] === 'claude' && view.added.length === 0, 'Choosing the default never creates another account');
    },
    async 'repository setup uses a display name and login email and keeps repo choices separate'(view: View) {
      await settle(); view.finish({ ...saved, providers: [...saved.providers, claude] }); await settle();
      view.render('ws-agents', 'repo-a'); await settle();
      view.finish({ ...saved, providers: [...saved.providers, claude] }); await settle();
      assert(view.host.textContent?.includes('default@example.com') === true, 'The signed-in email comes from the CLI, separately from the name');
      const add = [...view.host.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Add Claude account');
      assert(add !== undefined, 'Account setup is available inside the repository'); add.click(); await settle();
      const input = view.host.querySelector<HTMLInputElement>('[aria-label="Claude account name"]');
      assert(input !== null, 'Repository setup asks for a name');
      changeInput(input, 'Work'); await settle();
      const signIn = [...view.host.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Add and sign in');
      assert(signIn?.disabled === true, 'A name alone cannot start sign-in');
      assert(!view.host.querySelector('[role="dialog"]')?.textContent?.includes('This name is a label'), 'The explanatory paragraph is removed');
      const email = view.host.querySelector<HTMLInputElement>('[aria-label="Claude account email"]');
      assert(email !== null && email.type === 'email', 'Repository setup asks for a login email');
      changeInput(email, 'invalid'); await settle();
      signIn.click(); await settle();
      assert(view.added.length === 0 && view.signIns.length === 0, 'An invalid email cannot create an account');
      changeInput(email, 'work@example.com'); await settle();
      signIn.click(); await settle();
      assert(view.added[0]?.join('/') === 'Work/work@example.com/repo-a', 'The display name and login email are sent separately');
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('Claude · Work'), 'Adding selects Development automatically');
      assert(view.host.querySelector('[aria-label="Review model"]')?.textContent?.includes('Claude · Work'), 'Adding selects Review automatically');
      assert(view.signIns[0] === account.id, 'Repository login opens the new profile, never the default profile');
      view.completeSignIn(); await settle();
      view.host.querySelector<HTMLButtonElement>('.bq-signin-dialog__button')?.click(); await settle();
      view.finish({ ...withAccount, providers: withAccount.providers.map(provider => provider.id === account.id
        ? { ...provider, readiness: 'READY', diagnostic: 'Signed in as work@example.com' } : provider) }); await settle();
      assert(view.host.textContent?.includes('work@example.com') === true, 'After sign-in the repository displays the actual email');
      view.host.querySelector<HTMLButtonElement>('[aria-label="Development model"]')?.click(); await settle();
      const choice = [...view.host.querySelectorAll<HTMLButtonElement>('[role="option"]')]
        .find(button => button.textContent?.includes('Opus') && button.textContent?.includes('Claude · Work'));
      assert(choice !== undefined, 'The account can be selected for this repository'); choice.click(); await settle();
      assert(view.workspaceChoices[0]?.join('/') === `repo-a/${account.id}`, 'Only the current repository’s model is changed');
      view.render('ws-agents', 'repo-b'); await settle();
      assert(!view.host.textContent?.includes('Claude · Work'), 'Another repository never displays the scoped account');
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('GPT-6 Sol') === true, 'The second repository keeps its previous model');
      view.render('ws-agents', 'repo-a'); await settle();
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('Claude · Work') === true, 'The first repository keeps its account when revisited');
      assert(view.chosen.length === 0, 'Repository account setup never changes global defaults');
      view.render('models'); await settle(); view.finish(withAccount); await settle();
      assert(!view.host.textContent?.includes('Claude · Work'), 'Repository setup does not add global provider rows');
    },
    async 'leaving models ignores a late refresh error'(view: View) {
      await settle(); view.render('browser'); await settle();
      view.fail(new Error('Old page refresh failed')); await settle();
      assert(view.host.querySelector('h1')?.textContent === 'Browser', 'Navigation remains responsive');
      assert(!view.host.textContent?.includes('Old page refresh failed'), 'Old request does not affect the new page');
    },
    async 'an existing global account can be restricted to the current repository'(view: View) {
      const globalAccount: ModelSettingsDto = { ...withAccount, providers: withAccount.providers.map((provider): ModelSettingsDto['providers'][number] => ({ ...provider, workspaceId: null })) };
      await settle(); view.finish(globalAccount); await settle();
      assert(view.host.textContent?.includes('Claude · Work'), 'A global named account is visible in global settings');
      view.render('ws-agents', 'repo-b'); await settle(); view.finish(globalAccount); await settle();
      const button = [...view.host.querySelectorAll('button')].find(value => value.textContent?.trim() === 'Use only here');
      assert(button !== undefined, 'A shared account can be made repository-only'); button.click(); await settle();
      const confirm = [...view.host.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(value => value.textContent?.trim() === 'Use only here');
      assert(confirm !== undefined, 'Restriction has a concrete confirmation'); confirm.click(); await settle();
      assert(view.host.querySelector('[aria-label="Development model"]')?.textContent?.includes('Claude · Work'), 'Restriction selects Development');
      assert(view.host.querySelector('[aria-label="Review model"]')?.textContent?.includes('Claude · Work'), 'Restriction selects Review');
      view.render('ws-agents', 'repo-a'); await settle();
      assert(!view.host.textContent?.includes('Claude · Work'), 'The restricted account disappears from other repositories');
      view.render('models'); await settle();
      assert(!view.host.textContent?.includes('Claude · Work'), 'The account is no longer globally selectable');
      assert(view.signIns.length === 0, 'Restriction preserves the existing login');
    },
    async 'removing a named account explains its scope and handles failure without losing the row'(view: View) {
      await settle(); view.finish(withAccount); await settle();
      view.render('ws-agents', 'repo-a'); await settle(); view.finish(withAccount); await settle();
      const click = (label: string) => {
        const button = [...view.host.querySelectorAll('button')].find(value => value.textContent?.trim() === label);
        assert(button !== undefined, `${label} is available`); button.click();
      };
      const defaultRow = [...view.host.querySelectorAll('.setw-row')].find(row => row.textContent?.includes('Default account'));
      assert(defaultRow !== undefined && !defaultRow.textContent?.includes('Remove'), 'The built-in provider is not removable');
      click('Remove'); await settle();
      assert(view.removed.length === 0, 'Removal requires confirmation');
      assert(view.host.querySelector('[role="dialog"]')?.textContent?.includes('this repository') === true, 'Repository scope is explained');
      click('Cancel'); await settle();
      assert(view.removed.length === 0, 'Cancel keeps the account');
      click('Remove'); await settle();
      assert(view.host.querySelector('[role="dialog"]')?.textContent?.includes('does not sign out') === true, 'Credential retention is explicit');
      click('Remove account'); await settle();
      assert(view.host.querySelector('[role="alert"]')?.textContent?.includes('Removal failed') === true, 'Failure stays in the dialog');
      assert(view.host.textContent?.includes('Claude · Work') === true, 'Failure keeps the account visible');
      click('Remove account'); await settle();
      assert(view.removed.join(',') === `${account.id},${account.id}`, 'Only the selected account is removed, including retry');
      assert(view.host.querySelector('[role="dialog"]') === null && !view.host.textContent?.includes('Claude · Work'), 'Success closes the dialog and removes the row');
      assert(view.host.textContent?.includes('Default account') === true, 'The other account remains');
    },
  })) {
    const { host, root, dispose: disposeRoot } = createFixtureRoot();
    const forces: boolean[] = [];
    const added: string[][] = [];
    const removed: string[] = [];
    const chosen: string[] = [];
    const signIns: string[] = [];
    const workspaceChoices: string[][] = [];
    let terminalEvent: Parameters<Bridge['onReviewTerminalEvent']>[0] = () => {};
    const workspaceSettings = new Map<string, WorkspaceSettingsDto>();
    let catalog = saved;
    const scoped = (id?: string): ModelSettingsDto => ({ ...catalog,
      agents: id === undefined ? catalog.agents : workspaceSettings.get(id)?.agents ?? catalog.agents,
      providers: catalog.providers.filter(provider => provider.workspaceId == null || provider.workspaceId === id),
    });
    let finish: (value: ModelSettingsDto) => void = () => { throw new Error('No pending refresh'); };
    let fail: (error: Error) => void = () => { throw new Error('No pending refresh'); };
    const bridge: Partial<Bridge> = {
      getAppVersion: async () => ({ version: '0.4.0' }),
      getAppPreferences: async () => ({ theme: 'SYSTEM' as const, taskRetentionDays: 7, recentRetentionDays: 14, prWarmCount: 30 }),
      getModelSettings: async id => scoped(id),
      getWorkspaceSettings: async id => workspaceSettings.get(id) ?? { agents: saved.agents, remotes: [], commitsTarget: null },
      setWorkspaceAgentModel: async (id, type, configId, model, effort) => {
        workspaceChoices.push([id, configId]);
        const value: WorkspaceSettingsDto = { agents: [{ type, explicit: true, configId, model, effort, label: account.label, modelLabel: 'Opus' }], remotes: [], commitsTarget: null };
        workspaceSettings.set(id, value);
        return value.agents[0];
      },
      openProviderSignIn: async laneId => { signIns.push(laneId); return { terminalId: laneId, scrollback: 'Choose an email in your browser' }; },
      onReviewTerminalEvent: listener => { terminalEvent = listener; return () => {}; },
      writeReviewTerminal: () => {},
      resizeReviewTerminal: () => {},
      addClaudeAccount: async (label, email, workspaceId) => {
        added.push([label, email, workspaceId ?? 'global']);
        catalog = { ...withAccount, providers: withAccount.providers.map(provider => provider.id === account.id ? { ...provider, workspaceId } : provider) };
        if (workspaceId !== undefined) workspaceSettings.set(workspaceId, { agents: ['DEVELOPMENT', 'REVIEW'].map(type => ({
          type: type as 'DEVELOPMENT' | 'REVIEW', explicit: true, configId: account.id, model: 'opus', effort: 'High', label: account.label, modelLabel: 'Opus',
        })), remotes: [], commitsTarget: null });
        return scoped(workspaceId);
      },
      restrictClaudeAccount: async (laneId, workspaceId) => {
        catalog = { ...catalog, providers: catalog.providers.map(provider => provider.id === laneId ? { ...provider, workspaceId } : provider) };
        workspaceSettings.set(workspaceId, { agents: ['DEVELOPMENT', 'REVIEW'].map(type => ({
          type: type as 'DEVELOPMENT' | 'REVIEW', explicit: true, configId: laneId, model: 'opus', effort: 'High', label: account.label, modelLabel: 'Opus',
        })), remotes: [], commitsTarget: null });
        return scoped(workspaceId);
      },
      removeClaudeAccount: async laneId => {
        removed.push(laneId);
        if (removed.length === 1) throw new Error('Removal failed; try again');
        catalog = { ...saved, providers: [...saved.providers, claude] };
        return scoped();
      },
      setAgentModel: async (type, configId, model, effort) => {
        chosen.push(configId);
        return { ...withAccount, agents: [{ type, explicit: true, configId, model, effort, label: withAccount.providers.find(provider => provider.id === configId)?.label ?? configId, modelLabel: 'Opus' }] };
      },
      refreshModelSettings: (force = false, id) => { forces.push(force); return new Promise<ModelSettingsDto>((resolve, reject) => { finish = value => { catalog = value; resolve(scoped(id)); }; fail = reject; }); },
    };
    window.bridge = bridge as Bridge;
    const render = (section: SettingsSection, id?: string) => {
      const workspace = id === undefined ? null : { id, name: id, selectedOwner: 'owner', selectedRepo: id, localRepositoryPath: `/managed/${id}` } as WorkspaceDto;
      flushSync(() => root.render(<SettingsWindow section={section} workspace={workspace} onWorkspacesChanged={() => {}} />));
    };
    try {
      render('models');
      await test({ host, forces, added, removed, chosen, signIns, workspaceChoices, render,
        completeSignIn: () => terminalEvent({ terminalId: signIns.at(-1) ?? '', exitCode: 0 }), finish: value => finish(value), fail: error => fail(error) });
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { disposeRoot(); }
  }
  return results;
}

type View = {
  host: HTMLElement;
  forces: boolean[];
  added: string[][];
  removed: string[];
  chosen: string[];
  signIns: string[];
  workspaceChoices: string[][];
  completeSignIn: () => void;
  render: (section: SettingsSection, workspaceId?: string) => void;
  finish: (value: ModelSettingsDto) => void;
  fail: (error: Error) => void;
};
