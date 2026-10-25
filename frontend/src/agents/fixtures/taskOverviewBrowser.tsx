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
import type { AgentTaskDto } from '../../types';
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

export async function run(): Promise<{ name: string; error?: string }[]> {
  return runScenarios([['switches tasks with the keyboard and isolates Escape', keyboardSwitching]]);
}
