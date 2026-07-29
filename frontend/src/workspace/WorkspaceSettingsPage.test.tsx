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
import type { Ds4StatusDto, WorkspaceApiRequest, WorkspaceCardDto, WorkModelOptionsDto } from '../types';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';
import type { WorkspaceAutomationStatusDto } from './workspaceApi';

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

function automationStatus(overrides: {
  qualityScan?: Partial<WorkspaceAutomationStatusDto['qualityScan']>;
  remoteIssueIntake?: Partial<WorkspaceAutomationStatusDto['remoteIssueIntake']>;
} = {}): WorkspaceAutomationStatusDto {
  return {
    qualityScan: {
      enabled: false,
      eligible: true,
      reason: null,
      running: false,
      lastRunAt: null,
      expectedNextRunAt: null,
      lastOutcome: null,
      findingsProposed: 0,
      lastError: null,
      ...overrides.qualityScan,
    },
    remoteIssueIntake: {
      enabled: false,
      eligible: true,
      reason: null,
      running: false,
      lastRunAt: null,
      expectedNextRunAt: null,
      lastOutcome: null,
      issuesExamined: 0,
      tasksQueued: 0,
      implementationsStarted: 0,
      lastError: null,
      ...overrides.remoteIssueIntake,
    },
  };
}

function installBridge(
  localAiState: 'DISABLED' | 'RUNNING' = 'DISABLED',
  automation = automationStatus(),
  automationFailures = 0,
) {
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
        sessionCapUsd: 100,
        dailyCapUsd: 10,
        pauseAtCap: true,
        maxRunningTasks: 2,
        syncSeconds: 60,
        brainBudgetChars: 8000,
        distillMinutes: 30,
        kbAudiences: ['plan', 'dev', 'review', 'ci-fix'],
        providers: { plan: 'claude-sonnet-4.5', dev: 'cli:codex' },
        notifyCi: true,
        notifyCompletions: false,
        qualityScanEnabled: automation.qualityScan.enabled,
        remoteIssueIntakeEnabled: automation.remoteIssueIntake.enabled,
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
    if (request.path === '/api/workspaces/w1/automation') {
      if (automationFailures > 0) {
        automationFailures -= 1;
        throw new Error('Automation status unavailable');
      }
      return automation;
    }
    throw new Error(`Unexpected request: ${request.path}`);
  });
  (window as unknown as { bridge: unknown }).bridge = {
    workspaceApi,
    getWorkModelOptions: vi.fn(async () => options),
    refreshWorkModelOptions: vi.fn(async () => options),
    getDs4Status: vi.fn(async (): Promise<Ds4StatusDto> => ({
      state: localAiState,
      endpoint: 'http://127.0.0.1:11435',
      pid: localAiState === 'RUNNING' ? 123 : -1,
      startedAt: localAiState === 'RUNNING' ? '2026-07-20T00:00:00Z' : null,
      spawnedByUs: localAiState === 'RUNNING',
      restartAttempts: 0,
      uptimeSec: localAiState === 'RUNNING' ? 30 : 0,
      lastError: null,
    })),
    // Account-level defaults the Agents rows inherit from. Matching the
    // persisted workspace values keeps the "override" chip off unless a
    // test deliberately diverges.
    getAiDefaults: vi.fn(async () => ({
      plan: 'cli:claude-code',
      dev: 'cli:claude-code',
      review: 'cli:claude-code',
      globalReview: 'cli:claude-code',
      ciFix: 'cli:codex',
      triage: 'cli:claude-code',
      perf: 'cli:claude-code',
    })),
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

    // Five engine pickers: the workspace default plus the four session kinds.
    expect(await screen.findAllByRole('option', { name: /Codex CLI · available/ })).toHaveLength(5);
    const pickers = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(pickers).toHaveLength(5);
    // No providers.default stored, so the workspace default seeds from the
    // account-level dev engine — which is signed out here, so the row is
    // repaired to the first engine that is actually available.
    expect(pickers[0].value).toBe('cli:codex');
    expect(pickers[1].value).toBe('cli:codex');
    expect(pickers[2].value).toBe('cli:codex');
    expect(pickers[3].value).toBe('');
    expect(pickers[4].value).toBe('');
    expect(document.body.textContent).not.toContain('sonnet');
    expect((screen.getAllByRole('option', { name: /Claude CLI/ })[0] as HTMLOptionElement).disabled).toBe(true);
    expect(screen.getAllByRole('option', { name: /API · OpenAI · work-key/ })[0]).toBeTruthy();
    expect((screen.getAllByRole('option', { name: 'Local · not enabled' })[0] as HTMLOptionElement).disabled).toBe(true);
  });

  it('enables the local model only while the local runtime is running', async () => {
    installBridge('RUNNING');
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);

    const local = await screen.findAllByRole('option', { name: 'Local · available' });
    expect(local).toHaveLength(5);
    expect((local[0] as HTMLOptionElement).disabled).toBe(false);
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
    expect(numbers[0].value).toBe('100.00');
    fireEvent.change(numbers[0], { target: { value: '2.50' } });
    fireEvent.change(numbers[1], { target: { value: '15.00' } });
    fireEvent.click(screen.getByRole('switch', { name: 'Pause at cap' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
      body: expect.objectContaining({
        sessionCapUsd: 2.5,
        dailyCapUsd: 15,
        pauseAtCap: false,
        providers: expect.objectContaining({
          // The edited row is the workspace default; the session kinds keep
          // their stored pick, or stay empty to inherit it.
          default: 'api:openai:work-key',
          plan: 'cli:codex',
          dev: 'cli:codex',
          review: '',
          'ci-fix': '',
        }),
      }),
    }));
  });

  it('saves a workspace task limit and can restore inherited concurrency', async () => {
    const workspaceApi = installBridge();
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);

    const taskLimit = await screen.findByRole('textbox', { name: 'Max running tasks' });
    await waitFor(() => expect((taskLimit as HTMLInputElement).value).toBe('2'));

    fireEvent.change(taskLimit, { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
      body: expect.objectContaining({ maxRunningTasks: 3 }),
    }));

    workspaceApi.mockClear();
    fireEvent.change(taskLimit, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
      body: expect.objectContaining({ maxRunningTasks: null }),
    }));
  });

  it('ignores a stale save response after switching between inheriting workspaces', async () => {
    const workspaceApi = installBridge();
    const baseImplementation = workspaceApi.getMockImplementation();
    if (baseImplementation === undefined) throw new Error('workspace API mock has no implementation');

    let resolveSave!: (value: unknown) => void;
    let savedBody: unknown;
    const pendingSave = new Promise<unknown>(resolve => { resolveSave = resolve; });
    workspaceApi.mockImplementation(async request => {
      if (request.path === '/api/workspaces/w1/settings' && request.method === 'PUT') {
        savedBody = request.body;
        return pendingSave;
      }
      const workspaceTwo = request.path.startsWith('/api/workspaces/w2');
      const mappedRequest = workspaceTwo
        ? { ...request, path: request.path.replace('/api/workspaces/w2', '/api/workspaces/w1') }
        : request;
      const response = await baseImplementation(mappedRequest);
      if (request.path.endsWith('/settings') && request.method === undefined) {
        return {
          ...(response as Record<string, unknown>),
          dailyCapUsd: workspaceTwo ? 20 : 10,
          maxRunningTasks: null,
        };
      }
      return response;
    });

    const { rerender } = render(
      <WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />,
    );
    await waitFor(() => {
      const numbers = screen.getAllByRole('textbox') as HTMLInputElement[];
      expect(numbers[1].value).toBe('10.00');
      expect(numbers[2].value).toBe('');
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith(expect.objectContaining({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
    })));

    const workspaceTwo = { ...workspace, id: 'w2', name: 'second-workspace' };
    rerender(<WorkspaceSettingsPage workspace={workspaceTwo} workspaceId="w2" section="agents" />);
    await waitFor(() => {
      const numbers = screen.getAllByRole('textbox') as HTMLInputElement[];
      expect(numbers[1].value).toBe('20.00');
      expect(numbers[2].value).toBe('');
    });

    await act(async () => {
      resolveSave(savedBody);
      await pendingSave;
    });
    const currentNumbers = screen.getAllByRole('textbox') as HTMLInputElement[];
    expect(currentNumbers[1].value).toBe('20.00');
    expect(currentNumbers[2].value).toBe('');
    expect(screen.queryByText('Saved')).toBeNull();
  });

  it('ignores a stale save response after a newer same-workspace edit', async () => {
    const workspaceApi = installBridge();
    const baseImplementation = workspaceApi.getMockImplementation();
    if (baseImplementation === undefined) throw new Error('workspace API mock has no implementation');

    let resolveSave!: (value: unknown) => void;
    let savedBody: unknown;
    const pendingSave = new Promise<unknown>(resolve => { resolveSave = resolve; });
    workspaceApi.mockImplementation(async request => {
      if (request.path === '/api/workspaces/w1/settings' && request.method === 'PUT') {
        savedBody = request.body;
        return pendingSave;
      }
      return baseImplementation(request);
    });

    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);
    await waitFor(() => {
      const numbers = screen.getAllByRole('textbox') as HTMLInputElement[];
      expect(numbers[1].value).toBe('10.00');
      expect(numbers[2].value).toBe('2');
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith(expect.objectContaining({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
    })));

    const dailyCap = (screen.getAllByRole('textbox') as HTMLInputElement[])[1];
    fireEvent.change(dailyCap, { target: { value: '20.00' } });
    await act(async () => {
      resolveSave(savedBody);
      await pendingSave;
    });

    expect(dailyCap.value).toBe('20.00');
    expect(screen.queryByText('Saved')).toBeNull();
  });

  it.each(['0', '1.5', 'not-a-number'])(
    'blocks saving an invalid workspace task limit (%s)',
    async invalidValue => {
      const workspaceApi = installBridge();
      render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="agents" />);

      const taskLimit = await screen.findByRole('textbox', { name: 'Max running tasks' });
      await waitFor(() => expect((taskLimit as HTMLInputElement).value).toBe('2'));
      workspaceApi.mockClear();
      fireEvent.change(taskLimit, { target: { value: invalidValue } });

      expect(screen.getByText('Enter a whole number greater than zero, or leave empty.')).toBeTruthy();
      expect(screen.getByText('Max running tasks must be empty or a whole number greater than zero.')).toBeTruthy();
      const saveButton = screen.getByRole('button', { name: 'Save changes' }) as HTMLButtonElement;
      expect(saveButton.disabled).toBe(true);
      fireEvent.click(saveButton);
      expect(workspaceApi).not.toHaveBeenCalled();
      expect(screen.queryByText('Saved')).toBeNull();
    },
  );

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

  it('saves workspace automation opt-ins through the existing settings endpoint', async () => {
    const workspaceApi = installBridge();
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="automation" />);

    const qualityScan = await screen.findByRole('switch', { name: 'Scan this workspace' });
    const issueIntake = screen.getByRole('switch', { name: 'Watch new GitHub issues' });
    await waitFor(() => {
      expect((qualityScan as HTMLButtonElement).disabled).toBe(false);
      expect((issueIntake as HTMLButtonElement).disabled).toBe(false);
    });

    fireEvent.click(qualityScan);
    fireEvent.click(issueIntake);
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/w1/settings',
      method: 'PUT',
      body: expect.objectContaining({
        qualityScanEnabled: true,
        remoteIssueIntakeEnabled: true,
      }),
    }));
    expect(screen.getByText(/approval-gated GitHub issue proposals/i)).toBeTruthy();
    expect(screen.getByText(/every push and pull request remains approval-gated/i)).toBeTruthy();
  });

  it('shows automation health, disables ineligible opt-ins, and polls enabled jobs', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');
    installBridge('DISABLED', automationStatus({
      qualityScan: {
        enabled: true,
        lastRunAt: '2026-07-20T01:00:00Z',
        expectedNextRunAt: '2026-07-21T01:00:00Z',
        lastOutcome: 'SUCCESS',
        findingsProposed: 4,
      },
      remoteIssueIntake: {
        eligible: false,
        reason: 'GitHub write access is required.',
        lastError: 'GitHub timed out',
      },
    }));
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="automation" />);

    expect(await screen.findByText('GitHub write access is required.')).toBeTruthy();
    expect((screen.getByRole('switch', { name: 'Watch new GitHub issues' }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect(screen.getByText('Findings proposed').closest('.wu-setting-row')?.textContent).toContain('4');
    expect(screen.getByRole('alert').textContent).toContain('GitHub timed out');
    await waitFor(() => expect(setIntervalSpy)
      .toHaveBeenCalledWith(expect.any(Function), 30_000));
    setIntervalSpy.mockRestore();
  });

  it('keeps an enabled job switch-off-able and retries after status failure', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');
    const workspaceApi = installBridge('DISABLED', automationStatus({
      qualityScan: { enabled: true },
    }), 1);
    render(<WorkspaceSettingsPage workspace={workspace} workspaceId="w1" section="automation" />);

    const qualityScan = await screen.findByRole('switch', { name: 'Scan this workspace' });
    await waitFor(() => {
      expect(qualityScan.getAttribute('aria-checked')).toBe('true');
      expect((qualityScan as HTMLButtonElement).disabled).toBe(false);
    });
    fireEvent.click(qualityScan);
    expect(qualityScan.getAttribute('aria-checked')).toBe('false');

    await waitFor(() => expect(setIntervalSpy).toHaveBeenCalled());
    const refresh = setIntervalSpy.mock.calls[0][0] as () => void;
    await act(async () => { refresh(); });
    await waitFor(() => expect(workspaceApi.mock.calls.filter(([request]) =>
      request.path === '/api/workspaces/w1/automation')).toHaveLength(2));
    setIntervalSpy.mockRestore();
  });
});
