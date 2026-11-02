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
import type { ComposerReference } from '../composerReference';
import type { BackportChainViewDto, BackportChainRouteDto, ComposerSuggestionDto, CreateAgentTaskRequest, LinkedWorkspaceWorkDto, ModelProviderDto } from '../../types';
import { assert, changeInput, controlTimers, settle, element, until } from '../../fixtures/harness';
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
      return { ...base, chain: { ...base.chain, version: 2, reason: 'Selected 2 landed commits. Resume to continue the saved route.',
        hops: [{ ...base.chain.hops[0], selection: { commits: ['b'.repeat(40), 'a'.repeat(40)], tipSha: 'a'.repeat(40) } }, base.chain.hops[1]] } };
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
    const nodes = () => [...view.host.querySelectorAll('.backport-node')];
    assert(!view.host.textContent?.includes('Requested in'), 'Compact conversation card');
    assert(nodes().map(node => node.querySelector('.backport-node__repo')?.textContent).join(' → ') === 'owner/repository → acme/internal → acme/private',
      'Route order preserved');
    assert(view.host.querySelector('.backport-chain__head .pill')?.textContent === 'Queued' && nodes()[1].textContent?.includes('Opened backport PR') === true
      && nodes()[2].textContent?.includes('Starts after acme/internal merges') === true, 'Each repository carries its own state');
    view.parallel(); await settle();
    assert(nodes()[2].querySelector('.backport-node__note')?.textContent?.startsWith('From owner/repository · ') === true, 'Sibling uses original source');
    assert(nodes()[0].querySelector('a.backport-node__repo') === null, 'Existing PR source has no invented source conversation');
    return [{ name: 'backport chain groups, navigation, selection, Stop and Resume' }];
  } finally { view.close(); }
}

export async function runSelectionOrder() {
  for (const mode of ['oldest-first', 'newest-first', 'paste-oldest-first', 'paste-newest-first', 'single'] as const) {
    const view = mount();
    try {
      const select = [...view.host.querySelectorAll<HTMLButtonElement>('.backport-chain__action')].find(button => button.textContent === 'Select commits');
      assert(select !== undefined, 'Select commits exists');
      select.click();
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

export function mountComposer(surface: 'Home' | 'Tasks' | 'Other' = 'Home', workspaceId?: string, backports = true) {
  const sends: string[] = [];
  const asks: string[] = [];
  let currentDraft = '';
  let childVersion = 0;
  function Composer({ version }: { version: number }) {
    const [draft, setDraft] = useState('');
    const [reference, setReference] = useState<ComposerReference | null>(null);
    currentDraft = draft;
    const props: Omit<ComponentProps<typeof AgentTaskList>, 'inGoal'> & { repository: string } = { draft, onDraftChange: setDraft, onCreate: () => sends.push(draft), tasks: [], loading: false, error: null,
      workspaceId, backports, reference, onReferenceChange: setReference, laneId: 'codex', providers: [], models: [], selection: { model: 'test', effort: null }, blocker: null,
      repository: 'example/repository', onSelectProvider: () => {}, onSelectModel: () => {}, onOpenTask: () => {} };
    if (surface === 'Home') return <AgentHomeView key={version} {...props} onAskArtifacts={async question => { asks.push(question); }} onOpenReview={() => {}} onShowAll={() => {}} />;
    if (surface === 'Tasks') return <AgentTaskList key={version} {...props} />;
    return <TaskComposer key={version} {...props} />;
  }
  const host = document.createElement('main');
  host.style.cssText = 'height:100vh;max-width:1080px;margin:auto';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<Composer version={childVersion} />));
  return { host, sends, asks, draft: () => currentDraft,
    remount: () => { flushSync(() => root.render(<Composer version={++childVersion} />)); },
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
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
      assert(view.host.querySelector('.agt-compose__keyword')?.textContent === '@backport' && view.host.querySelector('.agt-backport-hint') === null,
        'Completed backport stays colored inline without the description row');
      assert(input.selectionStart === 10 && document.activeElement === input && option() === null, 'Selection keeps caret after command');
      await type('@co');
      assert(view.host.querySelectorAll('[role="option"]').length === 1 && option()?.querySelector('svg') !== null
        && option()?.textContent === '@contributeFind and fix one small, worthwhile problem', `${surface}: prefix suggests contribute with its icon and description`);
      await key('Enter');
      assert(value() === '@contribute ' && option() === null && view.host.querySelector('.agt-backport-hint') === null && view.sends.length === 0,
        'Choosing contribute leaves only the command, without its description');
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
      assert(view.host.querySelectorAll('[role="option"]').length === 5, 'Bare @ lists backport, fix, review, contribute and artifacts on Home');
      for (let index = 0; index < 4; index++) await key('ArrowDown');
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
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await settle();
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

export async function runComposerSuggestions() {
  type Command = 'BACKPORT' | 'FIX' | 'REVIEW';
  const physical = (window as unknown as { referenceInput: { key: (key: string) => Promise<void>; click: (x: number, y: number) => Promise<void> } }).referenceInput;
  const item = (command: Command, number: number, title = number === 19 ? 'Improve query planning for nested filters and preserve projected column names' : `Change ${number}`): ComposerSuggestionDto => ({
    number, title, author: 'contributor', updatedAt: '2026-01-01T00:00:00Z',
    url: `https://github.com/example/repository/${command === 'FIX' ? 'issues' : 'pull'}/${number}`,
  });
  const calls: { workspaceId: string; command: Command; search: string }[] = [];
  const opened: string[] = [];
  const pending = new Map<string, (items: ComposerSuggestionDto[]) => void>();
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    openInAppBrowser: async (url: string) => { opened.push(url); },
    getComposerSuggestions: (workspaceId: string, command: Command, search: string): Promise<ComposerSuggestionDto[]> => {
      calls.push({ workspaceId, command, search });
      if (['old', 'new', 'waiting', 'firstword'].includes(search)) return new Promise(resolve => pending.set(search, resolve));
      if (search === 'failed') return Promise.reject(new Error('Could not load suggestions'));
      if (search === 'missing') return Promise.resolve([]);
      if (search !== '') return Promise.resolve([item(command, 81, 'Improve dynamic FILTER behavior')]);
      return Promise.resolve(Array.from({ length: 10 }, (_, index) => item(command, 20 - index)));
    },
  } });
  for (const surface of ['Home', 'Tasks'] as const) {
    const view = mountComposer(surface, 'workspace-picker');
    try {
      await settle();
      let input = element<HTMLTextAreaElement>('textarea', view.host);
      const value = () => input.value;
      const options = () => [...view.host.querySelectorAll<HTMLButtonElement>('[role="option"]')];
      const type = async (text: string) => {
        input.focus(); changeInput(input, text); await settle();
      };
      const key = async (key: string, extra: KeyboardEventInit = {}) => {
        input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...extra })); await settle();
      };
      const list = () => view.host.querySelector('[role="listbox"]');
      const sendCount = () => view.sends.length;
      for (const [command, label] of [['BACKPORT', 'Merged pull requests'], ['FIX', 'Open issues'], ['REVIEW', 'Open pull requests from others']] as const) {
        await type('');
        element<HTMLButtonElement>(`[title="Add @${command.toLowerCase()} to the composer"]`, view.host).click();
        await until(() => options().length === 10, `${surface}/${command}: shortcut shows ten recommended items`);
        assert(list()?.getAttribute('aria-label') === label && calls.at(-1)?.command === command && calls.at(-1)?.search === '', 'Shortcut reads its own domain');
        assert(calls.at(-1)?.workspaceId === 'workspace-picker', 'Suggestion reads retain the selected workspace');
        assert(options()[0].textContent?.includes('#20') && options()[0].textContent?.includes('Change 20'), 'Rows show number and title');
        assert(input.getAttribute('aria-activedescendant') === options()[0].id, 'First recommendation is associated with input');
        await key('Escape');
        element<HTMLButtonElement>(`[title="Add @${command.toLowerCase()} to the composer"]`, view.host).click();
        await until(() => options().length === 10, 'Clicking the same shortcut reopens a dismissed picker');
        await key('ArrowDown');
        const secondId = options()[1].id;
        assert(input.getAttribute('aria-activedescendant') === secondId, 'Arrows change active recommendation');
        await key(command === 'FIX' ? 'Tab' : 'Enter');
        assert(view.draft() === `@${command.toLowerCase()} ${item(command, 19).url} `, 'Selection retains the canonical URL in the submitted draft');
        const selected = element<HTMLAnchorElement>('.agt-compose__reference', view.host);
        const inlineLabel = selected.textContent ?? '';
        const title = inlineLabel.replace(/ #19$/, '');
        assert(inlineLabel.endsWith(' #19') && title.startsWith('Improve query planning') && Array.from(title).length <= 40 && title.endsWith('…'),
          'The inline reference shows a title limited to forty characters and its number');
        assert(value() === `@${command.toLowerCase()} ${inlineLabel} ` && !value().includes('example/repository') && !value().includes('https://'),
          'The title and number follow the command in the text field without the repository or URL');
        assert(selected.closest('.agt-compose__highlights') !== null && view.host.querySelector('.agt-compose-reference') === null,
          'The reference is inline text in the composer, without a separate card');
        assert(sendCount() === 0 && list() === null && document.activeElement === input, 'Selecting only edits the focused draft');
        assert(selected.title === item(command, 19).title && selected.href === item(command, 19).url,
          'Hover reveals the complete title and the link names the selected target');
        const field = element<HTMLElement>('.agt-compose__field', view.host);
        if (command === 'REVIEW') field.style.maxWidth = '240px';
        await settle();
        const boxes = [...selected.getClientRects()].filter(box => box.width > 0 && box.height > 0);
        if (command === 'REVIEW') assert(boxes.length > 1, 'The long title wraps in a narrow composer field');
        for (const box of boxes) {
          const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
          assert(hit === selected || selected.contains(hit), 'The visible reference receives pointer events above the textarea on every line');
        }
        const beforeOpen = opened.length;
        const unchanged = view.draft();
        await physical.click(Math.round(boxes[0].x + boxes[0].width / 2), Math.round(boxes[0].y + boxes[0].height / 2));
        await settle();
        assert(opened.length === beforeOpen + 1 && opened.at(-1) === item(command, 19).url, 'Pointer activation opens the selected issue or PR');
        const preceding = [...view.host.querySelectorAll<HTMLButtonElement>('.agt-dock__chips .agt-start')].at(-1);
        assert(preceding !== undefined, 'A shortcut precedes the composer in keyboard order');
        preceding.focus();
        await physical.key('Tab'); await settle();
        assert(document.activeElement === selected, 'Tab reaches the inline reference');
        await physical.key('Enter'); await settle();
        assert(opened.length === beforeOpen + 2 && opened.at(-1) === item(command, 19).url && view.draft() === unchanged && sendCount() === 0,
          'Keyboard activation opens the target without sending or changing the draft');
        field.style.maxWidth = '';
        input.focus(); input.setSelectionRange(value().length, value().length);
        await settle();
        await physical.key('Left');
        assert(input.selectionStart === value().length - 1, 'Native textarea caret movement remains available after opening a reference');
        view.remount(); await settle();
        input = element<HTMLTextAreaElement>('textarea', view.host);
        assert(view.host.querySelector('.agt-compose__reference')?.textContent === selected.textContent && !value().includes('https://'),
          'Parent-owned selection survives a composer remount');
        element<HTMLButtonElement>(`[title="Add @${command.toLowerCase()} to the composer"]`, view.host).click();
        await until(() => options().length === 10, 'Clicking the selected shortcut reopens recommendations');
        assert(view.host.querySelector('.agt-compose__reference') === null && view.draft() === `@${command.toLowerCase()} `,
          'Starting another selection removes the old hidden target');
      }
      await type('keep all this context');
      element<HTMLButtonElement>('[title="Add @fix to the composer"]', view.host).click();
      await until(() => options().length === 10, 'A shortcut leaves existing context after the empty query');
      assert(calls.at(-1)?.search === '', 'Existing context is not searched before the caret');
      options()[0].click(); await settle();
      assert(value() === '@fix Change 20 #20 keep all this context' && view.draft() === `@fix ${item('FIX', 20).url} keep all this context`,
        'Selecting preserves visible context and the submitted target');
      await type('');
      await type('@FiX FILTER');
      await until(() => options().length === 1 && options()[0].textContent?.includes('#81') === true, 'Typing searches beyond the first recommendations');
      assert(calls.at(-1)?.command === 'FIX' && calls.at(-1)?.search === 'FILTER', 'Commands ignore case and query words reach the search');
      options()[0].click(); await settle();
      assert(value() === '@FiX Improve dynamic FILTER behavior #81 ' && sendCount() === 0, 'Click selects inline issue text without exposing its URL or sending');
      await type('@FiX Improve dynamic FILTER behavior #81 include a regression test');
      assert(list() === null && view.draft() === `@FiX ${item('FIX', 81).url} include a regression test`, 'Editing instructions retains the selected target without searching');
      input.select();
      const clipboard = new DataTransfer();
      input.dispatchEvent(new ClipboardEvent('copy', { bubbles: true, cancelable: true, clipboardData: clipboard }));
      assert(clipboard.getData('text/plain') === view.draft(), 'Copying the whole draft includes the selected reference');
      await type(clipboard.getData('text/plain'));
      assert(view.draft() === clipboard.getData('text/plain') && !value().includes('https://') && view.host.querySelector('.agt-compose__reference') !== null,
        'Pasting the copied request over a selected reference keeps exactly one target');
      input.setSelectionRange(value().length, value().length);
      await key('Enter');
      assert(sendCount() === 1 && view.sends[0] === `@FiX ${item('FIX', 81).url} include a regression test`, 'Enter sends the selected URL and edited instructions once');
      input.select();
      const cut = new DataTransfer();
      input.dispatchEvent(new ClipboardEvent('cut', { bubbles: true, cancelable: true, clipboardData: cut }));
      await settle();
      assert(cut.getData('text/plain') === view.sends[0] && value() === '' && view.draft() === '' && view.host.querySelector('.agt-compose__reference') === null,
        'Cutting the whole draft copies the URL and clears both the text and selected reference');

      await type('@review filter and check tests');
      input.setSelectionRange('@review filter'.length, '@review filter'.length);
      input.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowLeft', bubbles: true }));
      await until(() => calls.at(-1)?.search === 'filter' && options().length === 1, 'Caret bounds the query before following instructions');
      options()[0].click(); await settle();
      assert(value() === '@review Improve dynamic FILTER behavior #81 and check tests' && view.draft() === `@review ${item('REVIEW', 81).url} and check tests`,
        'Selecting preserves the remaining draft');
      await type('@review and check tests');
      input.setSelectionRange('@review '.length, '@review '.length);
      input.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowLeft', bubbles: true }));
      await until(() => options().length === 10, 'Deleting the inline reference allows selecting another');
      assert(view.host.querySelector('.agt-compose__reference') === null && value() === '@review and check tests' && view.draft() === value(),
        'Deleting inline reference text removes its canonical URL and keeps the command and instructions');
      options()[0].click(); await settle();
      await key('Backspace', { isComposing: true });
      assert(view.host.querySelector('.agt-compose__reference') !== null, 'IME Backspace keeps the selected reference');
      await key('Backspace');
      await until(() => options().length === 10, 'Backspace at the reference boundary reopens recommendations');
      assert(view.host.querySelector('.agt-compose__reference') === null && value() === '@review and check tests' && view.draft() === value(),
        'Backspace removes the reference without deleting the command or instructions');
      options()[0].click(); await settle();
      await type('@fix different');
      assert(view.host.querySelector('.agt-compose__reference') === null && view.draft() === '@fix different', 'Changing command removes the previous hidden target');

      for (const command of ['backport', 'fix', 'review'] as const) {
        await type(`@${command} firstword`);
        await until(() => pending.has('firstword'), `${command}: the first word starts a lookup`);
        assert(calls.at(-1)?.command === command.toUpperCase(), 'The first word searches the command’s domain');
        const before = calls.length;
        const debounce = controlTimers(180);
        try {
          for (const separator of [' ', '\t', '\n']) {
            await type(`@${command} firstword${separator}`);
            debounce.tick(); await settle();
            assert(list() === null && calls.length === before, `${command}: whitespace after the first word closes the popup without another lookup`);
          }
          await type(`@${command} firstword more instructions`);
          debounce.tick(); await settle();
          assert(list() === null && calls.length === before, `${command}: later prose neither opens nor searches the picker`);
          pending.get('firstword')?.([item(command.toUpperCase() as Command, 91)]); pending.delete('firstword');
          await settle();
          assert(list() === null, `${command}: a late first-word response cannot reopen the popup`);
          await type(`@${command} pasted multiple words`);
          debounce.tick(); await settle();
          assert(list() === null && calls.length === before, `${command}: pasting several words does not start a lookup`);
          await type(`@${command} queued`);
          await type(`@${command} queued more`);
          debounce.tick(); await settle();
          assert(list() === null && calls.length === before, `${command}: continuing to prose cancels a pending debounce`);
        } finally { debounce.restore(); }
        await type(`@${command} pasted`);
        await until(() => calls.at(-1)?.command === command.toUpperCase() && calls.at(-1)?.search === 'pasted' && options().length === 1,
          `${command}: deleting back to the first word resumes lookup`);
      }

      await type('@fix old');
      await until(() => pending.has('old'), 'Old request begins');
      await type('@fix new');
      await until(() => pending.has('new'), 'New request begins');
      pending.get('new')?.([item('FIX', 90, 'New result')]); pending.delete('new');
      await until(() => options()[0]?.textContent?.includes('New result') === true, 'New response appears');
      pending.get('old')?.([item('FIX', 89, 'Old result')]); pending.delete('old');
      await settle();
      assert(options().length === 1 && options()[0].textContent?.includes('New result'), 'Late responses cannot replace current matches');
      await key('Escape');
      document.dispatchEvent(new Event('selectionchange')); await settle();
      assert(list() === null, 'Escape remains closed at the current caret');

      for (const query of ['missing', 'failed', 'waiting']) {
        await type(`@fix ${query}`);
        await until(() => calls.at(-1)?.search === query, `${query}: request starts`);
        await settle();
        assert(options().length === 0 && view.host.querySelector('[role="status"]') !== null, `${query}: state is visible without a selectable stale row`);
        const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
        input.dispatchEvent(tab);
        assert(!tab.defaultPrevented, `${query}: Tab can leave a picker without results`);
        await key('Enter');
        assert(sendCount() === 1, `${query}: Enter cannot accidentally send an unresolved picker`);
        await key('Escape');
        assert(list() === null, `${query}: Escape dismisses picker`);
      }
      pending.get('waiting')?.([item('FIX', 99)]); pending.delete('waiting');
      await settle();
      assert(list() === null, 'A late response cannot reopen a dismissed picker');
      await type('@review ');
      await until(() => options().length === 10, 'Editing reopens recommendations');
      input.blur(); input.dispatchEvent(new FocusEvent('focusout', { bubbles: true })); await settle();
      assert(list() === null, 'Blur closes the item picker');
      await type('@fix ');
      input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })); await settle();
      await key('Enter', { isComposing: true });
      assert(list() === null && sendCount() === 1 && value() === '@fix ', 'IME Enter neither selects an item nor sends');
      input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      await until(() => options().length === 10, 'Composition completion restores recommendations');
      options()[0].click(); await settle();
      const replacement = '@fix \nhttps://github.com/example/repository/issues/88';
      await type(replacement);
      assert(view.host.querySelector('.agt-compose__reference') === null && view.draft() === replacement, 'A multiline pasted target replaces the hidden reference');
      await key('Enter');
      assert(sendCount() === 2 && view.sends[1] === replacement, 'A multiline pasted request submits only its replacement target');
    } finally { view.close(); }
  }
  const view = mountComposer('Home', 'workspace-without-backports', false);
  try {
    await settle();
    assert(view.host.querySelector('[title="Add @backport to the composer"]') === null, 'Unsupported backports remain hidden');
    for (const command of ['fix', 'review']) {
      element<HTMLButtonElement>(`[title="Add @${command} to the composer"]`, view.host).click();
      await until(() => view.host.querySelectorAll('[role="option"]').length === 10 && calls.at(-1)?.command === command.toUpperCase(),
        `Disabling backports keeps ${command} recommendations available`);
    }
  } finally { view.close(); }
  return [{ name: 'Composer recommendations, search, selection and stale response handling' }];
}

export async function runReferenceRetry() {
  const workspaceId = 'workspace-reference-retry';
  const selected: ComposerSuggestionDto = { number: 52, title: 'Keep the selected issue while retrying',
    url: 'https://github.com/example/repository/issues/52', author: 'contributor', updatedAt: '2026-01-01T00:00:00Z' };
  const bridge = createPreviewBridge(workspaceId);
  const create = bridge.createAgentTask;
  const attempts: CreateAgentTaskRequest[] = [];
  const pending: ((failure: Error) => void)[] = [];
  const opened: string[] = [];
  let failing = true;
  bridge.createAgentTask = request => {
    attempts.push(request);
    return failing ? new Promise((_resolve, reject) => pending.push(reject)) : create(request);
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...bridge, getComposerSuggestions: async () => [selected] } });
  const live = () => bridge;
  function Composer() {
    const creation = useAgentTaskCreation({ workspaceId, bridge: live, onOpenTask: id => opened.push(id) });
    return <AgentHomeView {...creation.composer} repository="example/repository" tasks={creation.tasks} error={creation.error}
      onOpenTask={() => {}} onOpenReview={() => {}} onShowAll={() => {}} />;
  }
  const host = document.createElement('main');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<Composer />));
  try {
    await settle();
    element<HTMLButtonElement>('[title="Add @fix to the composer"]', host).click();
    await until(() => host.querySelector('[role="option"]') !== null, 'Real task composer loads issue recommendations');
    element<HTMLButtonElement>('[role="option"]', host).click(); await settle();
    const input = element<HTMLTextAreaElement>('textarea', host);
    changeInput(input, `@fix ${selected.title} #52 retain this context`); await settle();
    const canonical = `@fix ${selected.url} retain this context`;
    assert(host.querySelector('.agt-compose__reference')?.textContent?.includes(selected.title), 'Real creation state retains the selected issue');
    element<HTMLButtonElement>('.agt-send', host).click();
    await until(() => pending.length === 1 && host.querySelector('textarea[aria-label="Describe a task"]') === null, 'Sending unmounts the composer during startup');
    assert(attempts[0].message === canonical, 'Task creation receives the canonical URL and instructions');
    pending[0](new Error('Creation unavailable'));
    await until(() => pending.length === 2, 'Creation retries its uncertain request before returning the draft');
    pending[1](new Error('Creation unavailable'));
    await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'Failed creation restores the composer');
    assert(element<HTMLTextAreaElement>('textarea', host).value === `@fix ${selected.title} #52 retain this context`
      && host.querySelector('.agt-compose__reference')?.textContent?.includes(selected.title), 'Failed creation restores the inline title without exposing its URL');
    failing = false;
    element<HTMLButtonElement>('.agt-send', host).click();
    await until(() => opened.length === 1 && host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'Successful retry finishes creation and returns the empty draft');
    assert(attempts.length === 3 && attempts.every(attempt => attempt.message === canonical && attempt.taskId === attempts[0].taskId),
      'Retry preserves the exact request identity and selected target');
    assert(element<HTMLTextAreaElement>('textarea', host).value === '' && host.querySelector('.agt-compose__reference') === null,
      'Successful creation clears both the text and selected reference');
  } finally { flushSync(() => root.unmount()); host.remove(); }
  return [{ name: 'Selected composer reference survives failed task creation and clears after retry' }];
}
