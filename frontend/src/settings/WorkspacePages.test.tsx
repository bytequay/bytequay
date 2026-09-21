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
import type { ModelSettingsDto, WorkspaceDto, WorkspaceSettingsDto } from '../types';
import { WorkspaceAgentsPage } from './WorkspacePages';
import { NO_AGENT_CAPABILITIES } from '../agents/capabilities';

const WORKSPACE = {
  id: 'ws-1',
  name: 'trino',
  selectedOwner: 'chenjian2664',
  selectedRepo: 'trino',
  localRepositoryPath: '/repos/trino',
} as WorkspaceDto;

const MODELS: ModelSettingsDto = {
  agents: [],
  providers: [
    {
      id: 'codex',
      label: 'Codex',
      readiness: 'READY',
      version: '0.153.4',
      diagnostic: '',
      capabilities: NO_AGENT_CAPABILITIES,
      models: [{ id: 'sol', name: 'Sol', description: '', efforts: ['low', 'high'], defaultEffort: 'low' }],
      selectedModel: 'sol',
      selectedEffort: 'high',
    },
    {
      id: 'claude',
      label: 'Claude',
      readiness: 'AUTHENTICATION_REQUIRED',
      version: '2.1.278',
      diagnostic: '',
      signInCommand: 'CLAUDE_CONFIG_DIR="/homes/ByteQuay/claude-home" claude auth login',
      capabilities: NO_AGENT_CAPABILITIES,
      models: [{ id: 'opus', name: 'Opus', description: '', efforts: ['High'], defaultEffort: 'High' }],
      selectedModel: 'opus',
      selectedEffort: 'High',
    },
  ],
  apiKeys: [],
  keyProviders: [],
};

const SETTINGS: WorkspaceSettingsDto = {
  remotes: [],
  commitsTarget: { remote: 'origin', branch: 'main', overridden: false },
  agents: [
    { type: 'DEVELOPMENT', explicit: false, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol', effort: 'high' },
    { type: 'REVIEW', explicit: false, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol', effort: 'high' },
  ],
};

function page(onSignIn?: (providerId: string) => void): string {
  return renderToStaticMarkup(
    <WorkspaceAgentsPage
      workspace={WORKSPACE}
      settings={SETTINGS}
      models={MODELS}
      checking={false}
      checkedAt="06:31"
      error={null}
      onChoose={vi.fn()}
      onUseGlobal={vi.fn()}
      onCheck={vi.fn()}
      onSignIn={onSignIn}
    />,
  );
}

describe('workspace agents page', () => {
  it('offers sign-in on the lane that needs it, because a blocked composer sends the user here', () => {
    const markup = page(vi.fn());

    expect(markup).toContain('Sign in required');
    expect(markup.match(/>Sign in</g)).toHaveLength(1);
    // The command belongs to the sign-in terminal, not to a row the user has to read and retype.
    expect(markup).not.toContain('CLAUDE_CONFIG_DIR');
  });

  it('shows no button when no terminal can be opened', () => {
    expect(page()).not.toContain('>Sign in<');
  });
});
