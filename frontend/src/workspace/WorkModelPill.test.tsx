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
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, ResolvedWorkModelDto, WorkModelOptionsDto } from '../types';
import { WorkModelPill } from './WorkModelPill';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

const INHERITED: ResolvedWorkModelDto = {
  override: null,
  effective: {
    kind: 'CLI',
    agentOrProvider: 'claude-code',
    model: 'claude-sonnet-4-6',
    account: null,
  },
  provenance: {
    source: 'WORKSPACE',
    scopeId: 'ws-default',
    scopeLabel: 'workspace ByteQuay',
  },
  agentLocked: false,
};

const CODEX: ResolvedWorkModelDto = {
  override: {
    kind: 'CLI',
    agentOrProvider: 'codex',
    model: 'gpt-5.6-sol',
    account: null,
    reasoningEffort: null,
  },
  effective: {
    kind: 'CLI',
    agentOrProvider: 'codex',
    model: 'gpt-5.6-sol',
    account: null,
    reasoningEffort: null,
  },
  provenance: {
    source: 'THREAD',
    scopeId: 'thread-1',
    scopeLabel: 'thread thread-1',
  },
  agentLocked: false,
};

const OPTIONS: WorkModelOptionsDto = {
  cliAgents: [
    {
      id: 'claude-code', displayName: 'Claude Code', installed: true, authed: true,
      defaultModel: 'claude-opus-4-8',
      models: [
        { id: 'claude-opus-4-8', displayName: 'Claude Opus 4.8', isDefault: true },
        { id: 'claude-sonnet-4-6', displayName: 'Claude Sonnet 4.6', isDefault: false },
      ],
    },
    {
      id: 'codex', displayName: 'Codex', installed: true, authed: true,
      defaultModel: 'gpt-5.6-sol',
      models: [{
        id: 'gpt-5.6-sol', displayName: 'GPT-5.6 Sol', isDefault: true,
        description: 'Frontier coding model',
        defaultReasoningEffort: 'low',
        supportedReasoningEfforts: [
          { id: 'low', description: 'Fast answers' },
          { id: 'max', description: 'Maximum reasoning depth' },
        ],
      }],
    },
  ],
  apiProviders: [{
    id: 'anthropic', displayName: 'Anthropic', defaultModel: 'claude-opus-4-8',
    models: [
      { id: 'claude-opus-4-8', displayName: 'Claude Opus 4.8', isDefault: true },
      { id: 'claude-opus-4-7', displayName: 'Claude Opus 4.7', isDefault: false },
    ],
    accounts: [{ name: 'team', isDefault: true, valid: true }],
  }],
};

describe('WorkModelPill', () => {
  it('resolves the effective model id to its catalog display name for the pill label', async () => {
    const getThreadWorkModel = vi.fn(async () => INHERITED);
    installBridge({ getThreadWorkModel });

    const { container } = render(
      <WorkModelPill variant="workspace-v2" scope={{ kind: 'thread', threadId: 'thread-1' }} />,
    );

    // The pill shows the human model name alone — no agent id, no "· CLI".
    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('Claude Sonnet 4.6');
    });
    expect(getThreadWorkModel).toHaveBeenCalledWith('thread-1');
    expect(screen.getByRole('button').textContent).not.toContain('claude-code');
    expect(container.querySelector('.workspace-work-model-pill svg')).toBeNull();
  });

  it('opens the picker popover on click and shows the inheritance hint', async () => {
    installBridge({ getThreadWorkModel: vi.fn(async () => INHERITED) });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();

    await act(async () => { fireEvent.click(screen.getByRole('button')); });

    await waitFor(() => {
      expect(screen.getByRole('dialog').textContent).toContain('workspace ByteQuay');
    });
  });

  it('closes the popover when Esc is pressed', async () => {
    installBridge({ getThreadWorkModel: vi.fn(async () => INHERITED) });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));

    await act(async () => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('shows the frozen engine and effort without exposing mutation controls', async () => {
    installBridge({ getThreadWorkModel: vi.fn(async () => INHERITED) });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();
    await act(async () => { fireEvent.click(screen.getByRole('button')); });

    const dialog = await waitFor(() => screen.getByRole('dialog'));
    expect(dialog.textContent).toContain('Workspace settings');
    expect(dialog.textContent).toContain('Reasoning effort');
    expect(dialog.textContent).toContain('fixed when this thread was created');
    expect(dialog.querySelector('button')).toBeNull();
    expect('setThreadWorkModel' in window.bridge).toBe(false);
  });

  it('reads a frozen Task snapshot', async () => {
    const getTaskWorkModel = vi.fn(async () => CODEX);
    installBridge({ getTaskWorkModel });

    render(<WorkModelPill scope={{ kind: 'task', threadId: 'thread-1', taskId: 'task-1' }} />);

    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('GPT-5.6 Sol');
    });
    expect(getTaskWorkModel).toHaveBeenCalledWith('thread-1', 'task-1');

    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    const dialog = await waitFor(() => screen.getByRole('dialog'));
    expect(dialog.textContent).toContain('Reasoning effort');
    expect(dialog.textContent).toContain('Low');
  });

  it('reads a frozen Stage snapshot', async () => {
    const getStageWorkModel = vi.fn(async () => CODEX);
    installBridge({ getStageWorkModel });

    render(<WorkModelPill scope={{ kind: 'stage', stageId: 'stage-1' }} />);

    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('GPT-5.6 Sol');
    });
    expect(getStageWorkModel).toHaveBeenCalledWith('stage-1');

    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    const dialog = await waitFor(() => screen.getByRole('dialog'));

    expect(dialog.textContent).toContain('fixed when this stage was created');
  });

  it('shows the effort the scope currently runs at', async () => {
    installBridge({
      getThreadWorkModel: vi.fn(async () => ({
        ...CODEX,
        effective: { ...CODEX.effective, reasoningEffort: 'max' },
      })),
    });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();
    await act(async () => { fireEvent.click(screen.getByRole('button')); });

    const dialog = await waitFor(() => screen.getByRole('dialog'));
    expect(dialog.textContent).toContain('Max');
    expect(dialog.querySelector('button')).toBeNull();
  });
});

/** Wait until the pill has finished its async load. The first render is a
 *  disabled "Model…" button; clicking that no-ops, so opening the popover
 *  before the resolved model lands would race. Block on the button
 *  becoming enabled. */
async function waitForLoadedPill() {
  await waitFor(() => {
    const button = screen.getByRole('button') as HTMLButtonElement;
    if (button.disabled) {
      throw new Error('pill is still loading');
    }
  });
}

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getThreadWorkModel: vi.fn(async () => INHERITED),
    getTaskWorkModel: vi.fn(async () => INHERITED),
    getStageWorkModel: vi.fn(async () => INHERITED),
    // The pill reads options on mount for the label and the picker list.
    getWorkModelOptions: vi.fn(async () => OPTIONS),
    refreshWorkModelOptions: vi.fn(async () => OPTIONS),
    ...overrides,
  };
}
