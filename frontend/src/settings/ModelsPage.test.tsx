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
import { describe, expect, it, vi } from 'vitest';
import type { ModelSettingsDto, WorkspaceDto } from '../types';
import { WorkspaceAgentsPage } from './WorkspacePages';
import ModelsPage, { API_KEY_REASON, effortAfterModelChange, modelOptions } from './ModelsPage';
import { NO_AGENT_CAPABILITIES } from '../agents/capabilities';

const SETTINGS: ModelSettingsDto = {
  agents: [
    { type: 'DEVELOPMENT', explicit: true, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol', effort: 'high' },
    { type: 'REVIEW', explicit: false, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol', effort: 'high' },
  ],
  providers: [{
    id: 'codex',
    label: 'Codex',
    readiness: 'READY',
    version: '0.153.4',
    diagnostic: '',
    capabilities: NO_AGENT_CAPABILITIES,
    models: [
      { id: 'sol', name: 'Sol', description: '', efforts: ['low', 'medium', 'high'], defaultEffort: 'low' },
      { id: 'mini', name: 'Sol mini', description: '', efforts: ['low', 'medium'], defaultEffort: 'low' },
      { id: 'flat', name: 'Flat', description: '', efforts: [], defaultEffort: null },
    ],
    selectedModel: 'sol',
    selectedEffort: 'high',
  }, {
    id: 'deepseek-key-123',
    label: 'DeepSeek · personal',
    readiness: 'READY',
    version: null,
    diagnostic: '',
    capabilities: NO_AGENT_CAPABILITIES,
    models: [{ id: 'deepseek-flash', name: 'deepseek-flash', description: '', efforts: [], defaultEffort: null }],
    selectedModel: 'deepseek-flash',
    selectedEffort: '',
  }],
  apiKeys: [{
    id: 'key:personal',
    alias: 'personal',
    provider: 'DeepSeek',
    maskedKey: 'sk-4f••••2a1',
    createdAt: '2026-09-01T00:00:00Z',
    models: ['deepseek-flash'],
    selectedModel: 'deepseek-flash',
  }],
  keyProviders: [{ name: 'DeepSeek', models: ['deepseek-flash'] }],
};

describe('model choice', () => {
  it('offers executable API-key aliases as defaults without a duplicate disabled choice', () => {
    const options = modelOptions(SETTINGS);

    expect(options.map(option => option.value))
      .toEqual(['codex::sol', 'codex::mini', 'codex::flat', 'deepseek-key-123::deepseek-flash']);
    expect(options.some(option => option.disabled)).toBe(false);
    expect(modelOptions({ ...SETTINGS, apiKeys: [{ ...SETTINGS.apiKeys[0], id: 'key:other', provider: 'OpenAI', alias: 'other' }] }).at(-1)?.disabledReason)
      .toBe(API_KEY_REASON);
  });

  it('keeps the effort when the new model still offers it and otherwise takes that model’s highest', () => {
    expect(effortAfterModelChange(SETTINGS, 'codex', 'mini', 'medium')).toBe('medium');
    // 'high' is gone on mini, so the fallback is mini's own highest rather than nothing.
    expect(effortAfterModelChange(SETTINGS, 'codex', 'mini', 'high')).toBe('medium');
    expect(effortAfterModelChange(SETTINGS, 'codex', 'sol', null)).toBe('high');
    // A model that advertises no effort takes none.
    expect(effortAfterModelChange(SETTINGS, 'codex', 'flat', 'high')).toBeNull();
  });
});

describe('models page', () => {
  const markup = renderToStaticMarkup(
    <ModelsPage
      settings={SETTINGS}
      checkedAt="09:15"
      checking={false}
      error={null}
      onCheck={vi.fn()}
      onChoose={vi.fn()}
      onInherit={vi.fn()}
      onAddKey={vi.fn()}
      onRemoveKey={vi.fn()}
    />,
  );

  it.each(['global', 'workspace'])('offers sign-in only for an unauthenticated lane on the %s page', surface => {
    const command = 'CLAUDE_CONFIG_DIR="/homes/ByteQuay/claude-home" claude auth login';
    const blocked: ModelSettingsDto = {
      ...SETTINGS,
      providers: [
        SETTINGS.providers[0],
        { ...SETTINGS.providers[0], id: 'claude', label: 'Claude', readiness: 'AUTHENTICATION_REQUIRED', version: '2.1.259', signInCommand: command },
      ],
    };
    const render = (onSignIn?: (providerId: string) => void) => {
      const props = { checkedAt: '09:15', checking: false, error: null as string | null, onCheck: vi.fn(), onSignIn,
        onChoose: vi.fn(), onInherit: vi.fn(), onAddKey: vi.fn(), onRemoveKey: vi.fn() };
      return renderToStaticMarkup(surface === 'global' ? <ModelsPage {...props} settings={blocked} /> : <WorkspaceAgentsPage {...props}
        workspace={{ id: 'ws-1', name: 'repository', selectedOwner: 'alice', selectedRepo: 'repository', localRepositoryPath: '/repos/repository' } as WorkspaceDto}
        settings={{ commitsTarget: { remote: 'origin', branch: 'main', overridden: false }, agents: blocked.agents }}
        models={blocked} onUseGlobal={vi.fn()} />);
    };
    const blockedMarkup = render(vi.fn());
    expect(blockedMarkup).toContain('Sign in required');
    expect(blockedMarkup.match(/>Sign in</g)).toHaveLength(1);
    expect(blockedMarkup).not.toContain('CLAUDE_CONFIG_DIR');
    expect(blockedMarkup).not.toContain('auth login');
    expect(render()).not.toContain('>Sign in<');
  });

  it('shows the upgrade command for a CLI below the minimum, since ByteQuay never upgrades it', () => {
    const diagnostic = 'Codex CLI 0.150.0 is below the minimum version 0.159.3. Run npm install --global @openai/codex@0.159.3';
    const outdated = { ...SETTINGS, providers: [{ ...SETTINGS.providers[0], readiness: 'UNSUPPORTED' as const, version: '0.150.0', diagnostic }] };
    const html = renderToStaticMarkup(<ModelsPage settings={outdated} checkedAt="09:15" checking={false} error={null}
      onCheck={vi.fn()} onChoose={vi.fn()} onInherit={vi.fn()} onAddKey={vi.fn()} onRemoveKey={vi.fn()} />);
    expect(html).toContain('npm install --global @openai/codex@0.159.3');
  });

  it('names both agent types and no longer offers a main model', () => {
    expect(markup).toContain('Development');
    expect(markup).toContain('Review');
    expect(markup).not.toContain('Set as main');
    expect(markup).not.toContain('MAIN');
  });

  it('shows an inheriting Review as following Development, with no effort of its own', () => {
    expect(markup).toContain('Same as Development');
    expect(markup).toContain('aria-label="Development effort"');
    expect(markup).not.toContain('aria-label="Review effort"');
  });

  it('keeps provider rows to status and credentials', () => {
    expect(markup).toContain('Codex CLI');
    expect(markup).not.toContain('DeepSeek · personal CLI');
    expect(markup.match(/DeepSeek · personal/g)).toHaveLength(1);
    expect(markup).toContain('v0.153.4');
    expect(markup).toContain('sk-4f••••2a1');
    expect(markup).toContain('Add API key');
    expect(markup).toContain('Models and providers refresh automatically · checked 09:15');
  });
});
