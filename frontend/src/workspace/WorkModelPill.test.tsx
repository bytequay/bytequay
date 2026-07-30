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

const CODEX: ResolvedWorkModelDto = resolvedModel('CLI', 'codex', 'gpt-5.6-sol', 'low');
const CLAUDE: ResolvedWorkModelDto = resolvedModel(
  'CLI', 'claude-code', 'claude-sonnet-4-6', 'medium', 'TASK',
);
const ANTHROPIC: ResolvedWorkModelDto = resolvedModel(
  'API', 'anthropic', 'claude-opus-4-8', 'high', 'STAGE',
);

const OPTIONS: WorkModelOptionsDto = {
  cliAgents: [
    {
      id: 'codex', displayName: 'Codex', installed: true, authed: true,
      defaultModel: 'gpt-5.6-sol',
      models: [{
        id: 'gpt-5.6-sol', displayName: 'GPT-5.6 Sol', isDefault: true,
        defaultReasoningEffort: 'low',
        supportedReasoningEfforts: [{ id: 'low' }, { id: 'max' }],
      }],
    },
    {
      id: 'claude-code', displayName: 'Claude Code', installed: true, authed: true,
      defaultModel: 'claude-sonnet-4-6',
      models: [{
        id: 'claude-sonnet-4-6', displayName: 'Claude Sonnet 4.6', isDefault: true,
        defaultReasoningEffort: 'high',
        supportedReasoningEfforts: [{ id: 'medium' }, { id: 'high' }],
      }],
    },
    {
      id: 'plain-agent', displayName: 'Plain Agent', installed: true, authed: true,
      defaultModel: 'plain-model',
      models: [{ id: 'plain-model', displayName: 'Plain Model', isDefault: true }],
    },
  ],
  apiProviders: [{
    id: 'anthropic', displayName: 'Anthropic', defaultModel: 'claude-opus-4-8',
    models: [{
      id: 'claude-opus-4-8', displayName: 'Claude Opus 4.8', isDefault: true,
      defaultReasoningEffort: 'high',
      supportedReasoningEfforts: [{ id: 'low' }, { id: 'high' }, { id: 'max' }],
    }],
    accounts: [{ name: 'team', isDefault: true, valid: true }],
  }],
};

describe('WorkModelPill', () => {
  it('updates a trunk effort and refreshes from the setter response', async () => {
    const updated = withEffort(CODEX, 'max');
    const setThreadWorkModel = vi.fn(async () => updated);
    const onChange = vi.fn();
    installBridge({ getThreadWorkModel: vi.fn(async () => CODEX), setThreadWorkModel });

    render(
      <WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} onChange={onChange} />,
    );
    await openPill();

    expect(screen.getByRole('dialog').textContent).toContain('not-yet-admitted turns');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Max' })); });

    expect(setThreadWorkModel).toHaveBeenCalledWith('thread-1', {
      ...CODEX.effective,
      reasoningEffort: 'max',
    });
    expect(onChange).toHaveBeenCalledWith(updated);
    await waitFor(() => expect(screen.getByRole('button').textContent).toContain('Max'));
  });

  it('updates Claude Code effort through the task bridge', async () => {
    const setTaskWorkModel = vi.fn(async () => withEffort(CLAUDE, 'high'));
    installBridge({ getTaskWorkModel: vi.fn(async () => CLAUDE), setTaskWorkModel });

    render(<WorkModelPill scope={{ kind: 'task', threadId: 'thread-1', taskId: 'task-1' }} />);
    await openPill();
    expect(screen.getByRole('dialog').textContent).toContain('engine is fixed for this task');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'High' })); });

    expect(setTaskWorkModel).toHaveBeenCalledWith('thread-1', 'task-1', {
      ...CLAUDE.effective,
      reasoningEffort: 'high',
    });
  });

  it('updates API effort through the stage bridge', async () => {
    const setStageWorkModel = vi.fn(async () => withEffort(ANTHROPIC, 'max'));
    installBridge({ getStageWorkModel: vi.fn(async () => ANTHROPIC), setStageWorkModel });

    render(<WorkModelPill scope={{ kind: 'stage', stageId: 'stage-1' }} />);
    await openPill();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Max' })); });

    expect(setStageWorkModel).toHaveBeenCalledWith('stage-1', {
      ...ANTHROPIC.effective,
      reasoningEffort: 'max',
    });
  });

  it('clears the scope effort to use the inherited or model default', async () => {
    const inherited: ResolvedWorkModelDto = {
      ...CODEX,
      override: null,
      effective: { ...CODEX.effective, reasoningEffort: 'low' },
    };
    const setThreadWorkModel = vi.fn(async () => inherited);
    installBridge({
      getThreadWorkModel: vi.fn(async () => withEffort(CODEX, 'max')),
      setThreadWorkModel,
    });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await openPill();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Use inherited\/default/ }));
    });

    expect(setThreadWorkModel).toHaveBeenCalledWith('thread-1', null);
    await waitFor(() => expect(screen.getByRole('button').textContent).toContain('Low'));
  });

  it('keeps unsupported models read-only', async () => {
    const unsupported = resolvedModel('CLI', 'plain-agent', 'plain-model', null);
    const setThreadWorkModel = vi.fn(async () => unsupported);
    installBridge({ getThreadWorkModel: vi.fn(async () => unsupported), setThreadWorkModel });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await openPill();

    expect(screen.getByRole('dialog').textContent).toContain('does not expose reasoning-effort controls');
    expect(screen.queryByRole('group', { name: 'Reasoning effort' })).toBeNull();
    expect(setThreadWorkModel).not.toHaveBeenCalled();
  });
});

async function openPill() {
  await waitFor(() => {
    const button = screen.getByRole('button') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
  });
  await act(async () => { fireEvent.click(screen.getByRole('button')); });
  await waitFor(() => screen.getByRole('dialog'));
}

function resolvedModel(
  kind: 'CLI' | 'API',
  agentOrProvider: string,
  model: string,
  reasoningEffort: string | null,
  source: 'THREAD' | 'TASK' | 'STAGE' = 'THREAD',
): ResolvedWorkModelDto {
  const workModel = { kind, agentOrProvider, model, account: kind === 'API' ? 'team' : null, reasoningEffort };
  return {
    override: workModel,
    effective: workModel,
    provenance: { source, scopeId: 'scope-1', scopeLabel: 'scope scope-1' },
    agentLocked: false,
  };
}

function withEffort(model: ResolvedWorkModelDto, reasoningEffort: string): ResolvedWorkModelDto {
  return {
    ...model,
    override: { ...model.effective, reasoningEffort },
    effective: { ...model.effective, reasoningEffort },
  };
}

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getThreadWorkModel: vi.fn(async () => CODEX),
    setThreadWorkModel: vi.fn(async () => CODEX),
    getTaskWorkModel: vi.fn(async () => CODEX),
    setTaskWorkModel: vi.fn(async () => CODEX),
    getStageWorkModel: vi.fn(async () => CODEX),
    setStageWorkModel: vi.fn(async () => CODEX),
    getWorkModelOptions: vi.fn(async () => OPTIONS),
    refreshWorkModelOptions: vi.fn(async () => OPTIONS),
    ...overrides,
  };
}
