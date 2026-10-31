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
import TaskOverview from '../TaskOverview';
import { createPreviewBridge } from './previewBridge';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, runScenarios, settle, until } from '../../fixtures/harness';
import type { AgentTaskDto, WorkspaceDto } from '../../types';
import '../../css/base.css';
import '../../css/agents.css';

declare global {
  interface Window { overviewKey: (key: string) => Promise<void>; }
}

async function keyboardSwitching(): Promise<void> {
  const bridge = createPreviewBridge('workspace-1');
  const base = await bridge.getAgentTask(bridge.events[0].taskId, 'workspace-1');
  const tasks: AgentTaskDto[] = ['First task', 'Second task'].map((title, index) => ({ ...base, id: `task-${index}`, title, mutationClaim: null }));
  const loading = Promise.withResolvers<AgentTaskDto[]>();
  bridge.listAgentTasks = () => loading.promise;
  bridge.getAgentTask = async id => tasks.find(task => task.id === id) ?? tasks[0];
  bridge.readAgentHistory = async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'position:relative;height:700px;width:1000px';
  const trigger = document.createElement('button');
  trigger.textContent = 'Show tasks';
  document.body.prepend(trigger);
  trigger.focus();
  let opened = '';
  let newTask = false;
  let escaped = 0;
  const outsideEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') escaped++; };
  window.addEventListener('keydown', outsideEscape);
  const show = (activeTaskId: string | null) => flushSync(() => root.render(<TaskOverview workspaceId="workspace-1" bridge={bridge}
    activeTaskId={activeTaskId} onOpenTask={id => { opened = id; }} onNewTask={() => { newTask = true; }}
    onDismiss={() => { root.render(null); }} />));
  const active = () => document.activeElement?.getAttribute('aria-label');
  try {
    show('task-1');
    assert(active() === 'New task', 'Loading keeps the overview keyboard reachable');
    loading.resolve(tasks);
    await until(() => active() === 'Open task: Second task', 'Loaded tasks focus the current task');
    await window.overviewKey('Left');
    assert(active() === 'Open task: First task', 'Left selects the previous task');
    await window.overviewKey('Enter');
    assert(opened === 'task-0', 'Enter opens the focused task through its button');
    await window.overviewKey('Right');
    assert(active() === 'Open task: Second task', 'Right selects the next task');
    await window.overviewKey('Down');
    assert(active() === 'New task', 'The new-task card is keyboard reachable');
    await window.overviewKey('Enter');
    assert(newTask, 'Enter on New task only requests the composer');
    await window.overviewKey('Up');
    assert(active() === 'Open task: Second task', 'Up selects the previous card');

    host.querySelector<HTMLButtonElement>('[aria-label="Close task: Second task"]')?.click();
    await until(() => host.querySelector('dialog[open]') !== null, 'Closing a task asks for confirmation');
    await window.overviewKey('Escape');
    await until(() => host.querySelector('dialog[open]') === null, 'Escape cancels only the confirmation');
    assert(host.querySelector('.agt-overview') !== null, 'Nested confirmation Escape must preserve the overview');

    const overview = host.querySelector<HTMLElement>('.agt-overview');
    assert(overview !== null, 'The overview remains open');
    const input = document.createElement('input');
    overview.append(input);
    input.focus();
    const arrow = new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true });
    input.dispatchEvent(arrow);
    assert(!arrow.defaultPrevented && document.activeElement === input, 'Editing a field must retain its arrow keys');
    input.remove();

    const covered = document.createElement('section');
    covered.setAttribute('role', 'dialog');
    const button = document.createElement('button');
    covered.append(button); document.body.append(covered); button.focus();
    await window.overviewKey('Escape');
    assert(host.querySelector('.agt-overview') !== null, 'Escape in another layer must not dismiss the overview');
    covered.remove();
    escaped = 0;
    host.querySelector<HTMLButtonElement>('.agt-card__open')?.focus();
    await window.overviewKey('Escape');
    await until(() => host.querySelector('.agt-overview') === null, 'Escape dismisses the overview');
    assert(document.activeElement === trigger, 'Dismissal restores the trigger focus');
    assert(escaped === 0, 'Overview Escape must not dismiss a surrounding layer');

    show(null);
    await until(() => active() === 'Open task: First task', 'Without an active task, the first task receives focus');
    host.querySelector<HTMLButtonElement>('[aria-label="Close task switcher"]')?.click();
    await settle();
    assert(host.querySelector('.agt-overview') === null && document.activeElement === trigger, 'The visible Close button dismisses and restores focus');
  }
  finally {
    window.removeEventListener('keydown', outsideEscape);
    dispose(); trigger.remove();
  }
}

async function overlappingRefreshes(): Promise<void> {
  const bridge = createPreviewBridge('workspace-1');
  const base = await bridge.getAgentTask(bridge.events[0].taskId, 'workspace-1');
  const tasks: AgentTaskDto[] = ['First task', 'Second task'].map((title, index) => ({ ...base, id: `refresh-task-${index}`, title, mutationClaim: null }));
  const older = Promise.withResolvers<AgentTaskDto[]>();
  let reads = 0;
  bridge.listAgentTasks = async () => ++reads === 1 ? tasks : reads === 2 ? older.promise : [];
  bridge.getAgentTask = async id => tasks.find(task => task.id === id) ?? tasks[0];
  bridge.readAgentHistory = async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 });
  const { host, root, dispose } = createFixtureRoot();
  const close = async (title: string) => {
    host.querySelector<HTMLButtonElement>(`[aria-label="Close task: ${title}"]`)?.click();
    await until(() => host.querySelector('dialog[open]') !== null, 'Closing asks for confirmation');
    host.querySelector<HTMLButtonElement>('dialog .bq-button--primary')?.click();
    await until(() => host.querySelector('dialog[open]') === null, 'Confirmation closes after accepting');
  };
  try {
    flushSync(() => root.render(<TaskOverview workspaceId="workspace-1" bridge={bridge} activeTaskId={null}
      onOpenTask={() => {}} onNewTask={() => {}} onDismiss={() => {}} />));
    await until(() => host.querySelectorAll('.agt-card').length === 2, 'Initial tasks load');
    await close('First task');
    await until(() => reads === 2, 'The first close starts a delayed refresh');
    await close('Second task');
    await until(() => reads === 3 && host.querySelectorAll('.agt-card').length === 0, 'The second close refreshes the empty list');
    older.resolve([tasks[1]]);
    await settle();
    assert(host.querySelectorAll('.agt-card').length === 0, 'A superseded refresh must not restore a closed task');
  }
  finally { older.resolve([]); dispose(); }
}

async function everyWorkspace(): Promise<void> {
  const bridge = createPreviewBridge('workspace-1');
  const base = await bridge.getAgentTask(bridge.events[0].taskId, 'workspace-1');
  const turn = { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-08-20T08:00:00Z' };
  const tasks: AgentTaskDto[] = [
    { ...base, id: 'idle-new', workspaceId: 'workspace-1', title: 'Idle and newest', updatedAt: '2026-08-20T12:00:00Z', mutationClaim: null },
    { ...base, id: 'idle-old', workspaceId: 'workspace-1', title: 'Idle and older', updatedAt: '2026-08-20T09:00:00Z', mutationClaim: null },
    { ...base, id: 'running', workspaceId: 'workspace-2', title: 'Running elsewhere', updatedAt: '2026-08-20T08:00:00Z', mutationClaim: turn },
  ];
  bridge.listWorkspaces = async () => ([['workspace-1', 'app'], ['workspace-2', 'docs']] as const)
    .map(([id, repo]) => ({ id, monitoredOwner: 'example', monitoredRepo: repo, hostingServer: { provider: 'GITHUB', webBaseUrl: 'https://github.com', apiBaseUrl: 'https://api.github.com' } }) as unknown as WorkspaceDto);
  bridge.listAgentTasks = async id => tasks.filter(task => task.workspaceId === id);
  bridge.getAgentTask = async id => tasks.find(task => task.id === id) ?? tasks[0];
  bridge.readAgentHistory = async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 });
  const { host, root, dispose } = createFixtureRoot();
  let opened: string[] = [];
  try {
    flushSync(() => root.render(<TaskOverview workspaceId={null} bridge={bridge} activeTaskId={null}
      onOpenTask={(taskId, workspaceId) => { opened = [taskId, workspaceId]; }} onNewTask={() => {}} onDismiss={() => {}} />));
    await until(() => host.querySelectorAll('.agt-card').length === 3, 'Every workspace\'s tasks load');
    const names = [...host.querySelectorAll('.agt-card__name')].map(name => name.textContent).join(', ');
    assert(names === 'Running elsewhere, Idle and newest, Idle and older', `Running tasks lead, then the most recent: ${names}`);
    const avatars = [...host.querySelectorAll('.agt-card__foot .repo-avatar')].map(avatar => avatar.getAttribute('aria-label')).join(', ');
    assert(avatars === 'example/docs, example/app, example/app', `Each window names its repository: ${avatars}`);
    host.querySelector<HTMLButtonElement>('.agt-card__open')?.click();
    assert(opened.join(' ') === 'running workspace-2', 'Opening a window names its own workspace');
  }
  finally { dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  return runScenarios([
    ['switches tasks with the keyboard and isolates Escape', keyboardSwitching],
    ['ignores superseded task list refreshes', overlappingRefreshes],
    ['lists every workspace\'s tasks, running first, each with its repository', everyWorkspace],
  ]);
}
