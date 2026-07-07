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
};

const PINNED: ResolvedWorkModelDto = {
  override: {
    kind: 'API',
    agentOrProvider: 'anthropic',
    model: 'claude-opus-4-7',
    account: 'team',
  },
  effective: {
    kind: 'API',
    agentOrProvider: 'anthropic',
    model: 'claude-opus-4-7',
    account: 'team',
  },
  provenance: {
    source: 'THREAD',
    scopeId: 'thread-1',
    scopeLabel: 'thread thread-1',
  },
};

describe('WorkModelPill', () => {
  it('reads the resolved model for a thread on mount and renders a compact label', async () => {
    const getThreadWorkModel = vi.fn(async () => INHERITED);
    installBridge({ getThreadWorkModel });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);

    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('claude-code');
    });
    expect(getThreadWorkModel).toHaveBeenCalledWith('thread-1');
    // Label includes the effective model id + the kind so the user
    // sees both at a glance.
    expect(screen.getByRole('button').textContent).toContain('claude-sonnet-4-6');
    expect(screen.getByRole('button').textContent).toContain('CLI');
  });

  it('opens the picker popover on click and shows the inheritance hint', async () => {
    installBridge({ getThreadWorkModel: vi.fn(async () => INHERITED) });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();

    await act(async () => { fireEvent.click(screen.getByRole('button')); });

    // The popover renders with an inheritance hint that names the
    // winning scope so the user knows whether they're about to
    // override a thread-, workspace-, or global-level value.
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

  it('shows a Clear override button when an override is pinned and calls the bridge with null', async () => {
    const setThreadWorkModel = vi.fn(async () => INHERITED);
    installBridge({
      getThreadWorkModel: vi.fn(async () => PINNED),
      setThreadWorkModel,
    });

    render(<WorkModelPill scope={{ kind: 'thread', threadId: 'thread-1' }} />);
    await waitForLoadedPill();
    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));

    const clearBtn = screen.getByRole('button', { name: /Clear override/i });
    await act(async () => { fireEvent.click(clearBtn); });

    expect(setThreadWorkModel).toHaveBeenCalledWith('thread-1', null);
  });

  it('reads the resolved model for a task and writes through the task bridge', async () => {
    const getTaskWorkModel = vi.fn(async () => PINNED);
    const setTaskWorkModel = vi.fn(async () => INHERITED);
    installBridge({ getTaskWorkModel, setTaskWorkModel });

    render(<WorkModelPill scope={{ kind: 'task', threadId: 'thread-1', taskId: 'task-1' }} />);

    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('anthropic');
    });
    expect(getTaskWorkModel).toHaveBeenCalledWith('thread-1', 'task-1');

    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));
    const clearBtn = screen.getByRole('button', { name: /Clear override/i });
    await act(async () => { fireEvent.click(clearBtn); });

    expect(setTaskWorkModel).toHaveBeenCalledWith('thread-1', 'task-1', null);
  });

  it('reads the resolved model for a stage, writes through the stage bridge, and shows the mid-stage hint', async () => {
    const getStageWorkModel = vi.fn(async () => PINNED);
    const setStageWorkModel = vi.fn(async () => INHERITED);
    installBridge({ getStageWorkModel, setStageWorkModel });

    render(<WorkModelPill scope={{ kind: 'stage', stageId: 'stage-1' }} />);

    await waitFor(() => {
      expect(screen.getByRole('button').textContent).toContain('anthropic');
    });
    expect(getStageWorkModel).toHaveBeenCalledWith('stage-1');

    await act(async () => { fireEvent.click(screen.getByRole('button')); });
    await waitFor(() => screen.getByRole('dialog'));

    // A stage's session runs for the stage's whole lifetime, so the
    // popover must warn that a change doesn't retroactively affect it.
    expect(screen.getByRole('dialog').textContent).toContain('applies next time this stage starts a new one');

    const clearBtn = screen.getByRole('button', { name: /Clear override/i });
    await act(async () => { fireEvent.click(clearBtn); });

    expect(setStageWorkModel).toHaveBeenCalledWith('stage-1', null);
  });
});

/** Wait until the pill has finished its async load. The first render is a
 *  disabled "Loading…" button; clicking that no-ops, so opening the
 *  popover before the resolved model lands would race (and flakes under
 *  CI load). Block on the button becoming enabled. */
async function waitForLoadedPill() {
  await waitFor(() => {
    const button = screen.getByRole('button') as HTMLButtonElement;
    if (button.disabled) {
      throw new Error('pill is still loading');
    }
  });
}

function emptyOptions(): WorkModelOptionsDto {
  return { cliAgents: [], apiProviders: [] };
}

function installBridge(overrides: Partial<Bridge>) {
  (window as unknown as { bridge: Partial<Bridge> }).bridge = {
    getThreadWorkModel: vi.fn(async () => INHERITED),
    setThreadWorkModel: vi.fn(async () => INHERITED),
    getTaskWorkModel: vi.fn(async () => INHERITED),
    setTaskWorkModel: vi.fn(async () => INHERITED),
    getStageWorkModel: vi.fn(async () => INHERITED),
    setStageWorkModel: vi.fn(async () => INHERITED),
    // The picker hits these on mount; return a minimal options
    // payload so the rendered popover doesn't crash on empty refs.
    getWorkModelOptions: vi.fn(async () => emptyOptions()),
    refreshWorkModelOptions: vi.fn(async () => emptyOptions()),
    ...overrides,
  };
}
