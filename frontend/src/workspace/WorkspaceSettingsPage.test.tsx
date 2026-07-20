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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceApiRequest, WorkspaceCardDto, WorkModelOptionsDto } from '../types';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

const workspace: WorkspaceCardDto = {
  id: 'w1',
  name: 'bytequay-v3-test',
  color: '#24292f',
  isScratch: false,
  repos: ['ByteQuay'],
  activeThreadCount: 1,
  tasksInFlight: 0,
  spendTodayMilliUsd: 0,
  needsAttentionCount: 0,
  memory: { decisionCount: 0, blockerCount: 0, tokensUsed: 0, tokensCap: 8000 },
  lastActivityMs: 1,
};

const options: WorkModelOptionsDto = {
  cliAgents: [
    {
      id: 'claude-code',
      displayName: 'Claude Code',
      installed: false,
      authed: false,
      defaultModel: 'claude-opus-4-8',
      models: [{ id: 'claude-opus-4-8', displayName: 'Claude Opus 4.8', isDefault: true }],
    },
    {
      id: 'codex',
      displayName: 'Codex',
      installed: true,
      authed: true,
      defaultModel: 'gpt-5',
      models: [{ id: 'gpt-5', displayName: 'GPT-5', isDefault: true }],
    },
  ],
  apiProviders: [{
    id: 'openai',
    displayName: 'OpenAI',
    defaultModel: 'gpt-5',
    models: [{ id: 'gpt-5', displayName: 'GPT-5', isDefault: true }],
    accounts: [{ name: 'work-key', isDefault: true, valid: true }],
  }],
};

function installBridge() {
  const workspaceApi = vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
    if (request.path === '/api/workspaces/w1/repository') {
      return {
        owner: 'chenjian2664',
        repo: 'ByteQuay',
        fullName: 'chenjian2664/ByteQuay',
        defaultBaseBranch: 'main',
        local: {},
      };
    }
    if (request.path === '/api/workspaces/w1/settings' && request.method === undefined) {
      return {
        sessionCapUsd: 1,
        dailyCapUsd: 10,
        pauseAtCap: true,
        syncSeconds: 60,
        brainBudgetChars: 8000,
        distillMinutes: 30,
        kbAudiences: ['plan', 'dev', 'review', 'ci-fix'],
        providers: { plan: 'claude-sonnet-4.5', dev: 'cli:codex' },
        notifyCi: true,
        notifyCompletions: false,
      };
    }
    if (request.path === '/api/workspaces/w1/settings' && request.method === 'PUT') {
      return request.body;
    }
    if (request.path === '/api/workspaces/w1' && request.method === 'PATCH') {
      return {
        id: 'w1',
        name: (request.body as { name: string }).name,
        memoryMd: '',
        isScratch: false,
        workModel: null,
        createdAt: '',
        updatedAt: '',
      };
    }
    if (request.path === '/api/workspaces/w1/memory/aggregate') {
      return {
        markdown: '## Decisions\nUse trunks.',
        characters: 24,
        characterBudget: 8000,
        blocks: [{ id: 1, category: 'Decisions', body: 'Use trunks.', provenance: 'user', tags: [], createdAt: 1 }],
        knowledge: [],
        distillRuns: [],
      };
    }
    throw new Error(`Unexpected request: ${request.path}`);
  });
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi,
    getWorkModelOptions: vi.fn(async () => options),
    refreshWorkModelOptions: vi.fn(async () => options),
  };
  return workspaceApi;
}

describe('WorkspaceSettingsPage', () => {
  it('renames from General and renders runtime provider choices', async () => {
    const workspaceApi = installBridge();
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="general" />);

    const name = await screen.findByDisplayValue('bytequay-v3-test');
    fireEvent.change(name, { target: { value: 'ByteQuay renamed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1',
      method: 'PATCH',
      body: { name: 'ByteQuay renamed' },
    }));

    cleanup();
    installBridge();
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);

    expect(await screen.findAllByRole('option', { name: /Codex CLI · available/ })).toHaveLength(4);
    const pickers = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(pickers[0].value).toBe('cli:codex');
    expect(document.body.textContent).not.toContain('sonnet');
    expect((screen.getAllByRole('option', { name: /Claude CLI/ })[0] as HTMLOptionElement).disabled).toBe(true);
    expect(screen.getAllByRole('option', { name: /API · OpenAI · work-key/ })[0]).toBeTruthy();
  });

  it('saves agent defaults and budget caps from the Agents section', async () => {
    const workspaceApi = installBridge();
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);

    // Wait for the async settings + model-options load to land before editing;
    // otherwise it resolves mid-interaction and setSettings clobbers the edits.
    await screen.findAllByRole('option', { name: /Codex CLI · available/ });
    const pickers = screen.getAllByRole('combobox') as HTMLSelectElement[];
    fireEvent.change(pickers[0], { target: { value: 'api:openai:work-key' } });

    const numbers = screen.getAllByRole('textbox') as HTMLInputElement[];
    fireEvent.change(numbers[0], { target: { value: '2.50' } });
    fireEvent.change(numbers[1], { target: { value: '15.00' } });
    fireEvent.click(screen.getByRole('switch', { name: '' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
      body: expect.objectContaining({
        sessionCapUsd: 2.5,
        dailyCapUsd: 15,
        pauseAtCap: false,
        providers: expect.objectContaining({
          plan: 'api:openai:work-key',
          dev: 'cli:codex',
          review: 'cli:codex',
          'ci-fix': 'cli:codex',
        }),
      }),
    }));
  });

  it('shows memory status and opens the full Memory page', async () => {
    installBridge();
    const onOpenMemory = vi.fn();
    render(
      <WorkspaceSettingsPage
        workspace={workspace}
        workspaceId="w1"
        section="memory"
        onOpenMemory={onOpenMemory}
      />,
    );

    expect(await screen.findByText(/24 \/ 8,000 characters/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'View memory' }));
    expect(onOpenMemory).toHaveBeenCalled();
  });
});
