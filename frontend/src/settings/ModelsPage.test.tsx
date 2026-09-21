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
import type { ModelSettingsDto } from '../types';
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
  }],
  apiKeys: [{
    id: 'key:personal',
    alias: 'personal',
    provider: 'DeepSeek',
    maskedKey: 'sk-4f••••2a1',
    createdAt: '2026-09-01T00:00:00Z',
    models: ['deepseek-chat'],
    selectedModel: 'deepseek-chat',
  }],
  keyProviders: [{ name: 'DeepSeek', models: ['deepseek-chat'] }],
};

describe('model choice', () => {
  it('offers every configured model but refuses to let an API key back an agent turn', () => {
    const options = modelOptions(SETTINGS);

    expect(options.map(option => option.value))
      .toEqual(['codex::sol', 'codex::mini', 'codex::flat', 'key:personal::deepseek-chat']);
    expect(options.filter(option => option.disabled === true).map(option => option.value))
      .toEqual(['key:personal::deepseek-chat']);
    expect(options.at(-1)?.disabledReason).toBe(API_KEY_REASON);
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

  it('offers sign-in as a button per unauthenticated lane, and never as row metadata', () => {
    const command = 'CLAUDE_CONFIG_DIR="/homes/ByteQuay/claude-home" claude auth login';
    const blocked: ModelSettingsDto = {
      ...SETTINGS,
      providers: [
        SETTINGS.providers[0],
        { ...SETTINGS.providers[0], id: 'claude', label: 'Claude', readiness: 'AUTHENTICATION_REQUIRED', version: '2.1.259', signInCommand: command },
      ],
    };
    const onSignIn = vi.fn();
    const blockedMarkup = renderToStaticMarkup(
      <ModelsPage settings={blocked} checkedAt="09:15" checking={false} error={null}
        onCheck={vi.fn()} onSignIn={onSignIn} onChoose={vi.fn()} onInherit={vi.fn()} onAddKey={vi.fn()} onRemoveKey={vi.fn()} />);

    // A shell command is not row metadata: the button carries it instead of the subtitle.
    expect(blockedMarkup).toContain('Sign in');
    expect(blockedMarkup).not.toContain('CLAUDE_CONFIG_DIR');
    expect(blockedMarkup).not.toContain('auth login');
    // The ready lane offers only Check, so one blocked lane cannot invite a sign-in for another.
    // The label text is matched, not the 'Sign in required' status beside it.
    expect(blockedMarkup.match(/>Sign in</g)).toHaveLength(1);
    // Without a way to open a terminal there is no button at all.
    expect(renderToStaticMarkup(
      <ModelsPage settings={blocked} checkedAt="09:15" checking={false} error={null}
        onCheck={vi.fn()} onChoose={vi.fn()} onInherit={vi.fn()} onAddKey={vi.fn()} onRemoveKey={vi.fn()} />)).not.toContain('>Sign in<');
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
    expect(markup).toContain('v0.153.4');
    expect(markup).toContain('sk-4f••••2a1');
    expect(markup).toContain('Add API key');
    expect(markup).toContain('CLIs are detected automatically · checked 09:15');
  });
});
