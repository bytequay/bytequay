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
import { flushSync } from 'react-dom';
import AppSidebar from '../../AppSidebar';
import { assert, element, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { AgentTaskActivityDto, Bridge, WorkspaceDto } from '../../types';
import { workspaceFixture } from '../../workspaces/fixtures/workspace';
import { useTaskAttention } from '../attention';
import { createPreviewBridge, previewTask } from './previewBridge';

type Activity = Record<string, AgentTaskActivityDto>;
const noop = () => {};

function activity(workspaceId: string, text: string, waiting = false): Activity {
  return { [workspaceId]: {
    lastMessage: { author: 'AGENT', text, at: '2026-09-27T10:00:00Z', turnId: 'turn-1' },
    latestTurn: null,
    pendingApproval: waiting ? {
      approvalId: 'approval-1', occurredAt: '2026-09-27T10:00:00Z', title: 'Run tests', detail: '', approvalKind: 'COMMAND', scope: ['Bash'],
    } : null,
  } };
}

function Surface({ workspace }: { workspace: WorkspaceDto }) {
  const attention = useTaskAttention([{ id: workspace.id, name: '', repository: `${workspace.monitoredOwner}/${workspace.monitoredRepo}` }]);
  const waiting = Object.values(attention.activity).filter(facts => facts.pendingApproval !== null).length;
  return <>
    <AppSidebar page="workspaces" workspace={workspace} workspaceTab="home" tasksWaiting={waiting}
      onWorkspaceTab={noop} onNavigate={noop} canGoBack={false} canGoForward={false} onBack={noop} onForward={noop}
      browserRailVisible={false} onToggleBrowserRail={noop} />
    <output data-page>{attention.items.map(item => `${item.kind}:${item.task?.activity?.lastMessage?.text ?? ''}`).join(',')}</output>
    <button type="button" data-refresh onClick={attention.refresh}>Refresh task activity</button>
  </>;
}

export async function run(): Promise<boolean> {
  window.localStorage.clear();
  const first = workspaceFixture({ id: 'first' });
  const second = workspaceFixture({ id: 'second' });
  let snapshot = activity(first.id, 'Initial message');
  let pending: PromiseWithResolvers<Activity> | null = null;
  let failure: 'tasks' | 'activity' | null = null;
  let calls = 0;
  const bridge = {
    ...createPreviewBridge(first.id),
    listAgentTasks: async workspaceId => {
      if (failure === 'tasks') throw new Error('Task list unavailable');
      return [{ ...previewTask(workspaceId, workspaceId), mutationClaim: null }];
    },
    listAgentTaskActivity: async workspaceId => {
      calls++;
      if (failure === 'activity') throw new Error('Activity unavailable');
      return workspaceId === first.id ? pending?.promise ?? snapshot : activity(second.id, 'Second workspace');
    },
    listRunningTasks: async () => [],
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;

  const originalSet = window.setInterval;
  const originalClear = window.clearInterval;
  const visibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
  const timers = new Map<number, () => void>();
  let nextTimer = -1;
  window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay !== 5000 || typeof handler !== 'function') return originalSet(handler, delay, ...args);
    const id = nextTimer--;
    timers.set(id, () => handler(...args));
    return id;
  }) as typeof window.setInterval;
  window.clearInterval = id => { if (typeof id === 'number' && timers.delete(id)) return; originalClear(id); };
  const tick = () => [...timers.values()].forEach(run => run());
  const visible = (value: 'visible' | 'hidden') => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value });
    document.dispatchEvent(new Event('visibilitychange'));
  };
  visible('visible');
  const { host, root, dispose } = createFixtureRoot();
  const show = (workspace: WorkspaceDto) => flushSync(() => root.render(<Surface workspace={workspace} />));
  const page = () => element('[data-page]', host).textContent;
  const badge = () => host.querySelector('[aria-label="Tasks, 1 need you"]') !== null;
  try {
    show(first);
    await until(() => page()?.includes('Initial message') === true, 'The shared initial read reaches the page');
    await settle();
    assert(calls === 1, 'The page and sidebar share one initial read, including after the model catalog loads');
    assert(!badge(), 'No pending approval shows no sidebar badge');
    let before = calls;
    snapshot = activity(first.id, 'Please approve', true);
    tick();
    await until(() => page() === 'approval:Please approve' && badge(), 'One refresh updates both the page and sidebar');
    assert(calls === before + 1, 'Each five-second tick makes one activity request for both consumers');

    for (const source of ['activity', 'tasks'] as const) {
      snapshot = activity(first.id, `Approval before ${source} failure`, true);
      tick();
      await until(() => page() === `approval:Approval before ${source} failure` && badge(), 'An approval is visible before the failed refresh');
      before = calls;
      failure = source;
      snapshot = activity(first.id, 'Unconfirmed update');
      tick();
      await settle();
      assert(calls === before + 1 && page() === `approval:Approval before ${source} failure` && badge(),
        `A failed ${source} read preserves both the page and sidebar approval`);
      failure = null;
      snapshot = activity(first.id, `Recovered ${source}`);
      tick();
      await until(() => page() === `updated:Recovered ${source}` && !badge(), 'The next successful tick updates the page and clears the badge');
      assert(calls === before + 2, 'A failed shared read retries once on the next five-second tick');
    }

    pending = Promise.withResolvers<Activity>();
    before = calls;
    tick(); tick(); tick();
    assert(calls === before + 1, 'Slow activity reads never overlap');
    snapshot = activity(first.id, 'Approval resolved');
    pending.resolve(snapshot); pending = null;
    await until(() => page() === 'updated:Approval resolved' && !badge(), 'Both consumers show the completed slow refresh');

    visible('hidden');
    before = calls;
    snapshot = activity(first.id, 'Changed while hidden', true);
    tick(); tick();
    assert(calls === before, 'Hidden pages skip activity polling');
    visible('visible');
    await until(() => page() === 'approval:Changed while hidden' && badge(), 'Returning to the app immediately refreshes both consumers');
    assert(calls === before + 1, 'Returning starts one shared activity read');

    before = calls;
    snapshot = activity(first.id, 'Manual update');
    element<HTMLButtonElement>('[data-refresh]', host).click();
    await until(() => page() === 'updated:Manual update' && !badge(), 'An explicit page refresh also updates the sidebar');
    assert(calls === before + 1, 'An explicit refresh reads activity once');

    snapshot = activity(first.id, 'First workspace approval', true);
    tick();
    await until(() => badge(), 'The first workspace has a badge before switching');
    pending = Promise.withResolvers<Activity>();
    tick();
    show(second);
    assert(page() === '' && !badge(), 'Switching workspaces immediately clears the previous activity and badge');
    await until(() => page() === 'updated:Second workspace', 'The new workspace reads its own activity');
    pending.resolve(activity(first.id, 'Late first workspace', true)); pending = null;
    await settle();
    assert(page() === 'updated:Second workspace' && !badge(), 'A late previous-workspace response cannot replace the current snapshot');

    before = calls;
    flushSync(() => root.render(null));
    tick(); visible('visible');
    await settle();
    assert(calls === before && timers.size === 0, 'Unmounting stops polling and visibility refreshes');
    return true;
  }
  finally {
    dispose();
    window.setInterval = originalSet;
    window.clearInterval = originalClear;
    if (visibility === undefined) Reflect.deleteProperty(document, 'visibilityState');
    else Object.defineProperty(document, 'visibilityState', visibility);
  }
}
