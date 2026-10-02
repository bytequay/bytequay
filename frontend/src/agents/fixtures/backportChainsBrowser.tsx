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
import { flushSync } from 'react-dom';
import { AgentTaskList } from '../AgentTasksView';
import { BackportChainCard } from '../BackportChains';
import type { BackportChainViewDto, BackportChainRouteDto, LinkedWorkspaceWorkDto } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/home.css';
import '../../css/settings-window.css';
import '../../css/commits.css';

const route = (workspaceId: string, owner: string, repo: string): BackportChainRouteDto => ({
  workspaceId, repository: { owner, repo }, remote: 'origin', branch: 'main',
});
const p4 = route('p4', 'trinodb', 'trino');
const p3 = route('p3', 'acme', 'trino-internal');
const p2 = route('p2', 'acme', 'trino-private');
const base: BackportChainViewDto = {
  title: 'Fix dynamic filter behavior', requestingRepository: 'acme/trino-private', archived: false,
  chain: {
    id: 'blocked', workspaceId: 'p2', requestingTaskId: 'request', sourceTaskId: 'upstream-fix', source: p4,
    sourceMerged: true, sourcePullRequest: { owner: 'trinodb', repo: 'trino', number: 24060 },
    acceptances: [{ operationKey: 'route', turnId: 'user-turn', destinations: ['p3', 'p2'] }],
    hops: [
      { route: p3, executionId: null, pullRequest: null, landedSource: null, selection: null, completed: false, noOp: false },
      { route: p2, executionId: null, pullRequest: null, landedSource: null, selection: null, completed: false, noOp: false },
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
      pullRequest: { owner: 'acme', repo: 'trino-internal', number: 812 } }, base.chain.hops[1]] } };
  const stopped: BackportChainViewDto = { ...running, chain: { ...running.chain, id: 'stopped', state: 'STOPPED' } };
  const completed: BackportChainViewDto = { ...running, chain: { ...running.chain, id: 'complete', state: 'COMPLETED',
    hops: running.chain.hops.map(hop => ({ ...hop, completed: true })) } };
  const empty: BackportChainViewDto = { ...base, chain: { ...base.chain, id: 'empty', state: 'COMPLETED', reason: null,
    hops: [{ ...base.chain.hops[1], completed: true, noOp: true }] } };
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
      return { commits: ['a', 'b'].map((letter, i) => ({ sha: letter.repeat(40), title: 'Landed fix ' + i, body: '',
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
  flushSync(() => root.render(<AgentTaskList workspaceId="p2" repository="acme/trino-private" tasks={[]} loading={false} error={null}
    laneId="codex" providers={[]} models={[]} selection={null} draft="" blocker={null} initialType="backports"
    onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}} onCreate={() => {}} onOpenTask={() => {}} />));
  return { host, calls, inline: () => flushSync(() => root.render(<BackportChainCard view={running} />)),
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function run() {
  const view = mount();
  try {
    await settle();
    assert(view.host.querySelectorAll('.backport-chain').length === 5, 'Every saved chain is shown');
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
    assert(results.length === 2, 'Source history supplied');
    results[1].click(); await settle(); results[0].click(); await settle();
    button('Select range').click(); await settle();
    assert(view.host.textContent?.includes('Selected 2 landed commits'), 'Explicit selected range returned');
    assert(view.calls.some(call => call[0] === 'p2' && call[1] === 'blocked' && typeof call[2] === 'object'), 'Selection bound to chain and version');
    button('Resume').click(); await settle();
    assert(view.calls.some(call => JSON.stringify(call) === JSON.stringify(['p2', 'blocked', 'ACTIVE'])), 'Resume uses owning workspace');
    button('Stop').click(); await settle();
    assert(view.calls.some(call => JSON.stringify(call) === JSON.stringify(['p2', 'blocked', 'STOPPING'])), 'Stop uses owning chain');
    view.inline(); await settle();
    assert(!view.host.textContent?.includes('Requested in'), 'Compact conversation card');
    assert(view.host.querySelector('.backport-route')?.textContent?.includes('trinodb/trino → acme/trino-internal → acme/trino-private'), 'Route order preserved');
    return [{ name: 'backport chain groups, navigation, selection, Stop and Resume' }];
  } finally { view.close(); }
}
