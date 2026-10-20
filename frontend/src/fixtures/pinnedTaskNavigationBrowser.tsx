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
import { createRoot } from 'react-dom/client';
import App from '../App';
import { createPreviewBridge } from '../agents/fixtures/previewBridge';
import { setPinned } from '../home/pinned';
import type { Bridge, WorkspaceDto } from '../types';
import { workspaceFixture } from '../workspaces/fixtures/workspace';
import { assert, until } from './harness';
import '../css/base.css';

const taskLayer = () => document.querySelector<HTMLElement>('[role="dialog"][aria-label="Task"]');
const recent = () => document.querySelector('[aria-label="Recent"][aria-current="page"]');

export async function run(): Promise<{ name: string }[]> {
  window.localStorage.clear();
  const workspace = workspaceFixture();
  const preview = createPreviewBridge(workspace.id);
  const taskId = preview.events[0].taskId;
  const title = 'Use MemoryContext in FixedPageSource';
  const missingTitle = 'Unavailable saved task';
  const pendingWorkspace = Promise.withResolvers<WorkspaceDto>();
  const workspaceReads: string[] = [];
  const bridge = {
    ...preview,
    hasPat: async () => true,
    getInAppBrowserState: async () => ({ tabs: [], activeTabId: null }),
    onInAppBrowserState: () => () => {},
    hideInAppBrowser: async () => {},
    getFullScreen: async () => false,
    onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {},
    onPrAttentionUpdated: () => () => {},
    onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {},
    stopFindInPage: async () => {},
    listJiraConnections: async () => [],
    listJiraAttention: async () => [],
    listWorkspaces: async () => [],
    fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this navigation fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    getWorkspace: async (id: string) => {
      workspaceReads.push(id);
      if (id === 'missing-workspace') throw new Error('The saved task workspace is unavailable');
      return pendingWorkspace.promise;
    },
    getAgentTask: async (id: string, workspaceId: string) => ({ ...await preview.getAgentTask(id, workspaceId), title }),
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  setPinned({ key: `task:${taskId}`, title, target: { kind: 'task', taskId, workspaceId: workspace.id } }, true);
  setPinned({ key: 'task:missing-task', title: missingTitle, target: { kind: 'task', taskId: 'missing-task', workspaceId: 'missing-workspace' } }, true);
  window.location.hash = '#/recent';
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  const openPin = (label: string): void => {
    const button = document.querySelector<HTMLButtonElement>(`[aria-label="Pinned navigation"] button[aria-label="${label}"]`);
    assert(button !== null, `Saved pin is visible: ${label}`);
    button.click();
  };
  const closeTask = (): void => {
    const close = taskLayer()?.querySelector<HTMLButtonElement>('[aria-label="Close task"]');
    assert(close !== null && close !== undefined, 'The task dialog can close');
    close.click();
  };
  const assertOrigin = (): void => {
    assert(window.location.hash === '#/recent' && recent() !== null, 'Opening a pinned task retains Recent instead of navigating to Workspaces');
    assert(document.querySelector('.rc-page h1')?.textContent === 'Recent', 'The original page remains mounted');
  };
  try {
    await until(() => recent() !== null, 'Recent navigation did not settle');
    openPin(title);
    await until(() => taskLayer()?.querySelector('[role="status"]')?.textContent === 'Loading…', 'The saved task opens a loading dialog');
    assertOrigin();
    assert(workspaceReads.join() === workspace.id, 'The pin resolves its saved workspace');
    assert(!preview.commands.some(command => command.name === 'getAgentTask'), 'The task waits for its workspace');

    pendingWorkspace.resolve(workspace);
    await until(() => taskLayer()?.querySelector('.agt-title')?.textContent === title, 'The saved task title appears in its dialog');
    const taskReads = preview.commands.filter(command => command.name === 'getAgentTask');
    assert(taskReads.length > 0 && taskReads.every(command => JSON.stringify(command.args) === JSON.stringify([taskId, workspace.id])),
      'The task screen loads exactly the saved task and workspace IDs');
    assertOrigin();
    closeTask();
    await until(() => taskLayer() === null, 'Closing the task returns to the original page');
    assertOrigin();

    openPin(missingTitle);
    await until(() => taskLayer()?.querySelector('[role="alert"]')?.textContent === 'The saved task workspace is unavailable',
      'An unavailable workspace displays its error in the task dialog');
    const error = taskLayer()?.querySelector<HTMLElement>('[role="alert"]');
    assert(error !== null && error !== undefined && error.getBoundingClientRect().height > 0, 'The workspace error is visible');
    assert(workspaceReads.join() === `${workspace.id},missing-workspace`, 'The second saved pin resolves its own workspace');
    assert(!preview.commands.some(command => command.name === 'getAgentTask' && command.args[0] === 'missing-task'), 'A workspace error does not load another task');
    assertOrigin();
    closeTask();
    await until(() => taskLayer() === null, 'The failed task dialog can close');
    assertOrigin();
    return [{ name: 'saved task pins load and report errors over the original page' }];
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
