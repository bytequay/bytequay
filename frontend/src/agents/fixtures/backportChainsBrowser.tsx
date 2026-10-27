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
import { createRoot } from 'react-dom/client';
import { useState, type ComponentProps } from 'react';
import { flushSync } from 'react-dom';
import { AgentTaskList, TaskComposer, useAgentTaskCreation, type TaskLaunch } from '../AgentTasksView';
import { createPreviewBridge } from './previewBridge';
import { AgentHomeView } from '../AgentHome';
import { BackportChainCard, BackportChainList } from '../BackportChains';
import type { BackportChainViewDto, BackportChainRouteDto, CreateAgentTaskRequest, LinkedWorkspaceWorkDto, ModelProviderDto } from '../../types';
import { assert, changeInput, settle, element } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/home.css';
import '../../css/settings-window.css';
import '../../css/commits.css';

const route = (workspaceId: string, owner: string, repo: string): BackportChainRouteDto => ({
  workspaceId, repository: { owner, repo }, remote: 'origin', branch: 'main',
});
const p4 = route('p4', 'owner', 'repository');
const p3 = route('p3', 'acme', 'internal');
const p2 = route('p2', 'acme', 'private');
const base: BackportChainViewDto = {
  title: 'Fix dynamic filter behavior', requestingRepository: 'acme/private', archived: false,
  chain: {
    id: 'blocked', workspaceId: 'p2', requestingTaskId: 'request', sourceTaskId: 'upstream-fix', source: p4,
    sourceMerged: true, sourcePullRequest: { owner: 'owner', repo: 'repository', number: 24060 },
    acceptances: [{ operationKey: 'route', turnId: 'user-turn', destinations: ['p3', 'p2'] }],
    hops: [
      { sourceHop: -1, route: p3, executionId: null, pullRequest: null, landedSource: null, selection: null, completed: false, noOp: false },
      { sourceHop: 0, route: p2, executionId: null, pullRequest: null, landedSource: null, selection: null, completed: false, noOp: false },
    ],
    state: 'BLOCKED', reason: 'Landed range unclear. Select commits to continue.', createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z', version: 1,
  },
  progress: [{ taskId: null, ciRunId: null, status: 'WAITING', summary: null }, { taskId: null, ciRunId: null, status: 'WAITING', summary: null }],
};
export function mount() {
  const calls: unknown[][] = [];
  const running: BackportChainViewDto = { ...base, chain: { ...base.chain, id: 'running', state: 'ACTIVE', reason: null,
    hops: [{ ...base.chain.hops[0], executionId: 'execution-1', landedSource: { commits: ['a'.repeat(40)], mergeSha: 'a'.repeat(40) },
      pullRequest: { owner: 'acme', repo: 'internal', number: 812 } }, base.chain.hops[1]] } };
  const stopped: BackportChainViewDto = { ...running, chain: { ...running.chain, id: 'stopped', state: 'STOPPED' } };
  const completed: BackportChainViewDto = { ...running, chain: { ...running.chain, id: 'complete', state: 'COMPLETED',
    hops: running.chain.hops.map(hop => ({ ...hop, completed: true })) } };
  const empty: BackportChainViewDto = { ...base, chain: { ...base.chain, id: 'empty', state: 'COMPLETED', reason: null, requestingTaskId: null,
    acceptances: [{ operationKey: 'commits', turnId: null, destinations: ['p2'] }],
    hops: [{ ...base.chain.hops[1], sourceHop: -1, completed: true, noOp: true }] }, title: 'Backport owner/repository#24060' };
  const chains = [base, running, stopped, completed, empty];
  const change = async (workspace: string, id: string, state: 'STOPPING' | 'ACTIVE'): Promise<BackportChainViewDto> => {
    calls.push([workspace, id, state]);
    const value = chains.find(row => row.chain.id === id);
    if (!value) throw new Error("Chain not found");
    return { ...value, chain: { ...value.chain, state, reason: null, version: value.chain.version + 1 } };
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getBackportChains: async () => chains, getLinkedWorkspaceWork: async (): Promise<LinkedWorkspaceWorkDto[]> => [],
    stopBackportChain: (workspace: string, id: string) => change(workspace, id, 'STOPPING'),
    resumeBackportChain: (workspace: string, id: string) => change(workspace, id, 'ACTIVE'),
    gitLog: async (...args: unknown[]) => {
      calls.push(args);
      return { commits: ['a', 'b', 'c'].map((letter, i) => ({ sha: letter.repeat(40), title: 'Landed fix ' + i, body: '',
        author: 'Developer', committer: 'Developer', authoredAt: '2026-01-01T00:00:00Z', committedAt: '2026-01-01T00:00:00Z' })),
      refresh: { state: 'IDLE', workspaceId: 'p4' } };
    },
    selectBackportSource: async (...args: unknown[]) => {
      calls.push(args);
      return { ...base, chain: { ...base.chain, version: 2, reason: 'Selected 2 landed commits. Resume to continue the saved route.' } };
    },
  } });
  const host = document.createElement('main');
  host.style.cssText = 'max-width:1080px;margin:20px auto';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<BackportChainList chains={chains} error={null} />));
  return { host, calls, parallel: () => flushSync(() => root.render(<BackportChainCard view={{ ...running, chain: { ...running.chain, sourceTaskId: null,
      hops: running.chain.hops.map(hop => ({ ...hop, sourceHop: -1 })) } }} />)),
    inline: () => flushSync(() => root.render(<BackportChainCard view={running} />)),
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function run() {
  const view = mount();
  try {
    await settle();
    assert(view.host.querySelector('.backport-intro')?.textContent?.includes('choose Backport') === true, 'The list says where backports start');
    assert(view.host.querySelectorAll('.backport-chain').length === 5, 'Every saved chain is shown');
    assert(view.host.textContent?.includes('Started from Commits in acme/private') === true, 'A chain started in Commits names no conversation');
    assert([...view.host.querySelectorAll('.linked-work-list h3')].map(node => node.textContent?.trim()).join(',') ===
      'Needs you 1,Active 1,Stopped 1,Completed 2', 'Approved groups and counts');
    assert(![...view.host.querySelectorAll('button')].some(button => button.textContent?.includes('New task')), 'No Create task button');
    assert(view.host.querySelector('a[href="#/workspaces/p3/tasks/cherry/execution-1"]') !== null, 'Existing cherry-pick view linked');
    assert(view.host.querySelector('a[href="#/workspaces/p3/prs/812"]') !== null, 'Destination PR linked');
    assert(view.host.querySelector('a[href="#/workspaces/p2/tasks/agent/request"]') !== null, 'Requesting conversation linked');
    assert(view.host.textContent?.includes('Already present — no PR needed'), 'No empty PR state');
    const button = (text: string) => {
      const value = [...view.host.querySelectorAll('button')].find(item => item.textContent?.trim() === text);
      if (!value) throw new Error('Missing button: ' + text);
      return value;
    };
    button('Select commits').click();
    await settle();
    assert(view.host.querySelector('[role="dialog"]')?.textContent?.includes('Select a commit range'), 'Existing commit picker reused');
    const results = [...view.host.querySelectorAll<HTMLElement>('.cmt-rd-results .cmt-commit-row')];
    assert(results.length === 3, 'Source history supplied');
    results[1].click(); await settle(); results[0].click(); await settle();
    button('Select range').click(); await settle();
    assert(view.host.textContent?.includes('Selected 2 landed commits'), 'Explicit selected range returned');
    assert(view.calls.some(call => JSON.stringify(call) === JSON.stringify(['p2', 'blocked', {
      version: 1, hopIndex: 0, oldest: 'b'.repeat(40), newest: 'a'.repeat(40),
    }])), 'Selection bound to chain, version and ordered endpoints');
    button('Resume').click(); await settle();
    assert(view.calls.some(call => JSON.stringify(call) === JSON.stringify(['p2', 'blocked', 'ACTIVE'])), 'Resume uses owning workspace');
    button('Stop').click(); await settle();
    assert(view.calls.some(call => JSON.stringify(call) === JSON.stringify(['p2', 'blocked', 'STOPPING'])), 'Stop uses owning chain');
    view.inline(); await settle();
    assert(!view.host.textContent?.includes('Requested in'), 'Compact conversation card');
    assert(view.host.querySelector('.backport-route')?.textContent?.includes('owner/repository → acme/internal → acme/private'), 'Route order preserved');
    view.parallel(); await settle();
    assert(view.host.querySelector('.backport-route')?.textContent?.includes('owner/repository → acme/private'), 'Sibling uses original source');
    assert(!view.host.textContent?.includes('Conversation ›'), 'Existing PR source has no invented source conversation');
    return [{ name: 'backport chain groups, navigation, selection, Stop and Resume' }];
  } finally { view.close(); }
}

export async function runSelectionOrder() {
  for (const mode of ['oldest-first', 'newest-first', 'paste-oldest-first', 'paste-newest-first', 'single'] as const) {
    const view = mount();
    try {
      element<HTMLButtonElement>('.backport-block button', view.host, 'Select commits exists').click();
      await settle();
      const dialog = element<HTMLDialogElement>('.cmt-rd', view.host, 'Range picker opens');
      if (mode.startsWith('paste-')) {
        const input = element<HTMLInputElement>('.cmt-rd-search', dialog, 'Range input exists');
        changeInput(input, mode === 'paste-oldest-first' ? 'ccccccc..aaaaaaa' : 'aaaaaaa..ccccccc');
        await settle();
      } else {
        const results = [...dialog.querySelectorAll<HTMLElement>('.cmt-commit-row')];
        const indices = mode === 'single' ? [1, 1] : mode === 'oldest-first' ? [2, 0] : [0, 2];
        for (const index of indices) { results[index].click(); await settle(); }
      }
      assert(dialog.textContent?.includes(mode === 'single' ? '1 commit in this range' : '3 commits in this range'), `${mode}: inclusive range count`);
      element<HTMLButtonElement>('.cmt-rd-btn.is-primary', dialog, 'Select range exists').click();
      await settle();
      const selections = view.calls.filter(call => call[0] === 'p2' && call[1] === 'blocked');
      assert(JSON.stringify(selections) === JSON.stringify([['p2', 'blocked', {
        version: 1, hopIndex: 0, oldest: (mode === 'single' ? 'b' : 'c').repeat(40), newest: (mode === 'single' ? 'b' : 'a').repeat(40),
      }]]), `${mode}: backport receives oldest then newest exactly once`);
    } finally { view.close(); }
  }
  return [{ name: 'Backport ranges preserve chronological order for clicks and pasted endpoints' }];
}

export function mountComposer(surface: 'Home' | 'Tasks' | 'Other' = 'Home') {
  const sends: string[] = [];
  const asks: string[] = [];
  function Composer() {
    const [draft, setDraft] = useState('');
    const props: Omit<ComponentProps<typeof AgentTaskList>, 'inGoal'> & { repository: string } = { draft, onDraftChange: setDraft, onCreate: () => sends.push(draft), tasks: [], loading: false, error: null,
      laneId: 'codex', providers: [], models: [], selection: { model: 'test', effort: null }, blocker: null,
      repository: 'acme/private', onSelectProvider: () => {}, onSelectModel: () => {}, onOpenTask: () => {} };
    if (surface === 'Home') return <AgentHomeView {...props} onAskArtifacts={async question => { asks.push(question); }} onOpenReview={() => {}} onShowAll={() => {}} />;
    if (surface === 'Tasks') return <AgentTaskList {...props} />;
    return <TaskComposer {...props} />;
  }
  const host = document.createElement('main');
  host.style.cssText = 'height:100vh;max-width:1080px;margin:auto';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<Composer />));
  return { host, sends, asks, close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function runComposer() {
  for (const surface of ['Home', 'Tasks', 'Other'] as const) {
    const view = mountComposer(surface);
    try {
      await settle();
      const input = element<HTMLTextAreaElement>('textarea', view.host, `${surface}: composer exists`);
      const value = () => input.value;
      const caret = () => input.selectionStart;
      const type = async (text: string) => {
        input.focus(); input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
        changeInput(input, text); await settle();
      };
      const key = async (key: string, options: KeyboardEventInit = {}) => {
        input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options })); await settle();
      };
      const option = () => view.host.querySelector<HTMLButtonElement>('[role="option"]');
      const pill = view.host.querySelector<HTMLButtonElement>('.agt-dock__chips .agt-start');
      await type('@backp');
      if (surface === 'Other') {
        assert(option() === null && pill === null, 'Other composers have no backport shortcut or suggestion');
        continue;
      }
      assert(option()?.textContent?.includes('@backport') === true, `${surface}: prefix suggests backport`);
      assert(input.getAttribute('aria-activedescendant') === option()?.id, 'Suggestion is associated with input');
      await key('Enter');
      assert(value() === '@backport ' && view.sends.length === 0, 'Enter completes without sending');
      assert(input.selectionStart === 10 && document.activeElement === input && option() === null, 'Selection keeps caret after command');
      await type('@'); await key('Tab');
      assert(value() === '@backport ' && view.sends.length === 0, 'Tab completes bare @ without sending');
      await type('  @BACKP #1234');
      input.setSelectionRange(6, 6); input.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowLeft', bubbles: true }));
      await settle();
      assert(option() !== null, 'Caret inside prefix matches the whole token');
      option()?.click(); await settle();
      assert(value() === '@backport #1234' && view.sends.length === 0, 'Click replaces partial token and preserves PR');
      await type('@bac'); await key('Escape');
      document.dispatchEvent(new Event('selectionchange')); await settle();
      assert(option() === null, 'Escape stays dismissed at the same caret');
      await type('@back');
      assert(option() !== null, 'Editing after Escape restores suggestion');
      input.blur(); input.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); await settle();
      assert(option() === null, 'Blur closes suggestion');
      await type('@backp');
      input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })); await settle();
      await key('Enter', { isComposing: true });
      assert(value() === '@backp' && option() === null && view.sends.length === 0, 'IME Enter neither selects nor sends');
      input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })); await settle();
      assert(option() !== null, 'Composition end restores matching suggestion');
      for (const text of ['Explain @backp', '@backend', '@backport #1234', 'user@example.com']) {
        await type(text); assert(option() === null, 'Unrelated or completed text has no suggestion');
      }
      assert(pill?.textContent === 'Backport', `${surface}: Backport pill sits next to repository`);
      await type('oss/repo #1234'); pill?.click(); await settle();
      assert(value() === '@backport oss/repo #1234' && document.activeElement === input, 'Pill preserves draft and focuses input');
      pill?.click(); await settle();
      assert(value() === '@backport oss/repo #1234', 'Repeated clicks do not duplicate command');
      await type('@backp #1234'); pill?.click(); await settle();
      assert(value() === '@backport #1234' && view.sends.length === 0, 'Pill completes existing prefix without sending');
      await key('Enter');
      assert(view.sends.join() === '@backport #1234', 'Normal Enter sends the final request once');
      if (surface !== 'Home') continue;
      await type('@');
      assert(view.host.querySelectorAll('[role="option"]').length === 2, 'Bare @ lists backport and artifacts on Home');
      await key('ArrowDown');
      assert(input.getAttribute('aria-activedescendant')?.endsWith('-artifacts') === true, 'ArrowDown moves to artifacts');
      await key('Enter');
      assert(value() === '@artifacts ' && caret() === 11, 'Enter completes the chosen suggestion');
      await type('@Arti');
      assert(option()?.textContent?.includes('@artifacts') === true, 'Prefix suggests artifacts');
      option()?.click(); await settle();
      assert(value() === '@artifacts ', 'Click completes artifacts');
      await type('@artifacts What is a checkpoint?'); await key('Enter');
      assert(view.asks.join() === 'What is a checkpoint?' && view.sends.join() === '@backport #1234' && value() === '', 'Enter asks Artifacts instead of creating a task');
    } finally { view.close(); }
  }
  return [{ name: 'Backport discovery, completion and shortcuts on Home and Tasks only' }];
}

export async function runProviderChoice() {
  for (const surface of ['Home', 'Tasks'] as const) {
    for (const mode of ['codex', 'api-key', 'no-account', 'claude-ready', 'claude-unavailable', 'explicit-claude'] as const) {
      const bridge = createPreviewBridge('workspace-1');
      const settings = await bridge.getModelSettings();
      const codex: ModelProviderDto = { ...settings.providers[0], readiness: mode === 'api-key' || mode === 'no-account' ? 'AUTHENTICATION_REQUIRED' : 'READY' };
      const claude: ModelProviderDto = { ...codex, id: 'claude', label: 'Claude',
        readiness: mode === 'claude-ready' ? 'READY' : 'AUTHENTICATION_REQUIRED', diagnostic: 'Claude sign-in required',
        models: [{ id: 'sonnet', name: 'Sonnet', description: '', efforts: ['high'], defaultEffort: 'high' },
          { id: 'opus', name: 'Opus', description: '', efforts: ['high', 'max'], defaultEffort: 'high' }],
        selectedModel: 'sonnet', selectedEffort: 'high',
        signInCommand: 'CLAUDE_CONFIG_DIR=/private/claude-home claude auth login' };
      const key: ModelProviderDto = { ...codex, id: 'deepseek-key-123', label: 'DeepSeek · personal', readiness: 'READY' };
      bridge.getModelSettings = async () => ({ ...settings,
        agents: settings.agents.map(agent => agent.type !== 'DEVELOPMENT' || mode === 'codex' || mode === 'explicit-claude' ? agent
          : { ...agent, explicit: mode === 'claude-ready', configId: 'claude', label: 'Claude', model: 'opus', modelLabel: 'Opus', effort: 'max' }),
        providers: [claude, codex, ...(mode === 'api-key' ? [key] : [])],
      });
      const live = () => bridge;
      const initialLaunch: TaskLaunch | undefined = mode === 'explicit-claude' ? { laneId: 'claude', model: claude.selectedModel, effort: null } : undefined;
      const host = document.createElement('main');
      document.body.append(host);
      const root = createRoot(host);
      const Composer = () => {
        const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', bridge: live, onOpenTask: () => {}, initialLaunch });
        const props = { ...creation.composer, repository: 'acme/private', tasks: creation.tasks,
          error: creation.error, onOpenTask: () => {} };
        return surface === 'Home' ? <AgentHomeView {...props} onOpenReview={() => {}} onShowAll={() => {}} />
          : <AgentTaskList {...props} loading={creation.loading} />;
      };
      flushSync(() => root.render(<Composer />));
      try {
        await settle();
        const input = host.querySelector<HTMLTextAreaElement>('textarea');
        const pill = host.querySelector<HTMLButtonElement>('.agt-dock__chips .agt-start');
        assert(input !== null && !input.disabled && pill !== null && !pill.disabled, `${surface}/${mode}: drafting and Backport stay enabled`);
        pill.click(); await settle();
        assert(input.value === '@backport ' && document.activeElement === input, 'Backport fills and focuses before provider setup');
        assert(!host.textContent?.includes('sign-in required') && !host.textContent?.includes('CLAUDE_CONFIG_DIR'), 'No unsolicited Claude authentication error');
        changeInput(input, '@backport #1234'); await settle();
        const send = element<HTMLButtonElement>('.agt-send', host, 'Send control exists');
        let blocked = mode !== 'codex' && mode !== 'claude-ready';
        assert(send.disabled === blocked, 'Only a usable selected provider enables Send');
        let expectedProvider = mode === 'codex' ? codex : claude;
        assert(host.querySelector(`[aria-label="Provider: ${expectedProvider.label}"]`) !== null, 'Provider selection follows settings or explicit launch regardless of readiness');
        if (mode !== 'codex' && mode !== 'explicit-claude') {
          assert(host.querySelector('[aria-label="Model and effort for the new task"]')?.textContent === 'OpusMax', 'Composer inherits configured model and effort instead of lane defaults');
        }
        if (mode === 'api-key') {
          host.querySelector<HTMLButtonElement>('[aria-label="Provider: Claude"]')?.click(); await settle();
          const chooseKey = Array.from(host.querySelectorAll<HTMLButtonElement>('.agt-provider-picker__row button'))
            .find(button => button.textContent?.startsWith(key.label));
          assert(chooseKey !== undefined, 'Available API key can be selected explicitly');
          chooseKey.click(); await settle();
          expectedProvider = key;
          blocked = false;
          assert(!send.disabled, 'Explicit provider choice enables Send');
        }
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })); await settle();
        const creations = bridge.commands.filter(command => command.name === 'createAgentTask');
        assert(creations.length === (blocked ? 0 : 1), 'Enter respects readiness while draft remains editable');
        if (!blocked) {
          const request = creations[0].args[0] as CreateAgentTaskRequest;
          assert(request.laneId === expectedProvider.id, 'Submission uses the visible provider');
          if (mode === 'claude-ready') assert(request.defaults.model === 'opus' && request.defaults.effort === 'max', 'Submission preserves the configured model and effort');
        }
      } finally { flushSync(() => root.unmount()); host.remove(); }
    }
  }
  return [{ name: 'Optional provider accounts and editable Backport drafts on Home and Tasks' }];
}
