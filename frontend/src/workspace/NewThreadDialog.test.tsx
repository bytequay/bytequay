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
import { afterEach, expect, it, vi } from 'vitest';
import type {
  Ds4StatusDto, NewTaskRequestDto, WorkspaceApiRequest, WorkModelOptionsDto,
} from '../types';
import NewThreadDialog from './NewThreadDialog';

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, 'bridge');
});

function cliAgent(id: string, displayName: string, installed: boolean) {
  return {
    id,
    displayName,
    installed,
    authed: installed,
    defaultModel: 'm',
    models: [{ id: 'm', displayName: 'M', isDefault: true }],
  };
}

function options(claudeInstalled: boolean, codexInstalled: boolean): WorkModelOptionsDto {
  return {
    cliAgents: [
      cliAgent('claude-code', 'Claude Code', claudeInstalled),
      cliAgent('codex', 'Codex', codexInstalled),
    ],
    apiProviders: [],
  };
}

function installBridge(available: WorkModelOptionsDto) {
  const createTask = vi.fn(async (_request: NewTaskRequestDto) => ({ id: 't-new' }));
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi: vi.fn(async (request: WorkspaceApiRequest): Promise<unknown> => {
      if (request.path === '/api/workspaces/w1/repository') {
        return { owner: 'chenjian2664', repo: 'ByteQuay', fullName: 'chenjian2664/ByteQuay' };
      }
      if (request.path === '/api/workspaces/w1/settings') {
        return { providers: { default: 'cli:claude-code', 'ci-fix': 'cli:codex' } };
      }
      throw new Error(`Unexpected request: ${request.path}`);
    }),
    getWorkModelOptions: vi.fn(async () => available),
    refreshWorkModelOptions: vi.fn(async () => available),
    getDs4Status: vi.fn(async (): Promise<Ds4StatusDto> => ({
      state: 'DISABLED',
      endpoint: 'http://127.0.0.1:11435',
      pid: -1,
      startedAt: null,
      spawnedByUs: false,
      restartAttempts: 0,
      uptimeSec: 0,
      lastError: null,
    })),
    createTask,
  };
  return createTask;
}

function renderDialog() {
  render(
    <NewThreadDialog
      workspaceId="w1"
      workspaceName="bytequay-v3-test"
      onClose={() => {}}
      onCreated={() => {}}
    />);
}

it('inherits the workspace agents and pins only the kind the user swaps', async () => {
  const createTask = installBridge(options(true, true));
  renderDialog();

  await screen.findByText('chenjian2664/ByteQuay');
  await waitFor(() => expect(screen.getByText('inheriting bytequay-v3-test defaults')).toBeTruthy());
  // ci-fix has its own workspace pick; the other three take the default.
  expect(screen.getAllByText('Claude CLI')).toHaveLength(3);
  expect(screen.getAllByText('Codex CLI')).toHaveLength(1);

  // Swap the plan row onto Codex — only that kind becomes an override.
  fireEvent.click(screen.getAllByTitle('Override the workspace agent for this trunk')[0]);
  fireEvent.click(screen.getByText(/Codex CLI · available/));
  await waitFor(() => expect(screen.getByText('1 overridden for this trunk')).toBeTruthy());

  fireEvent.click(screen.getByText(/Let's ride/));
  await waitFor(() => expect(createTask).toHaveBeenCalled());
  expect(createTask.mock.calls[0][0]).toMatchObject({ engines: { plan: 'cli:codex' } });
});

it('blocks creation when the workspace has no usable agent', async () => {
  const createTask = installBridge(options(false, false));
  renderDialog();

  await screen.findByText(/No agents in bytequay-v3-test/);
  expect(screen.getAllByText('none available')).toHaveLength(4);
  expect(screen.getByText(/Let's ride/).closest('button')?.disabled).toBe(true);
  expect(createTask).not.toHaveBeenCalled();
});
