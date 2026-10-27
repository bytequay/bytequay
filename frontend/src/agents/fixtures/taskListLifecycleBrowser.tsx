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
import { assert, button, changeInput, element, runScenarios, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { AgentTaskDto, Bridge } from '../../types';
import { workspaceAgentTaskRoute } from '../../workspaces/model';
import type { WorkspaceAttention } from '../../workspaces/workspaceAttention';
import AgentHome from '../AgentHome';
import AgentTasksView, { useAgentTaskCreation } from '../AgentTasksView';
import { useTaskAttention } from '../attention';
import { UNCONFIRMED_REQUEST } from '../contracts';
import { createPreviewBridge, previewTask } from './previewBridge';

const noop = () => {};
const attention: WorkspaceAttention = { candidates: [], handled: {}, applyHandled: noop, error: null, login: null, jiraConnected: false };

function makeBridge(workspaceId: string) {
  return { ...createPreviewBridge(workspaceId), getLinkedWorkspaceWork: async () => [], getRelatedWorkspaceRequest: async () => null,
    getBackportChains: async () => [], listAgentTaskActivity: async () => ({}) };
}

async function creationAfterDeparture(departure: 'unmount' | 'switch', replay: boolean) {
  const workspaceId = `departure-${departure}-${replay}`;
  const bridge = makeBridge(workspaceId);
  const live = () => bridge;
  const pending = Promise.withResolvers<AgentTaskDto>();
  const requests: Parameters<Bridge['createAgentTask']>[0][] = [];
  bridge.listAgentTasks = async () => [];
  bridge.createAgentTask = async request => {
    requests.push(request);
    if (replay && requests.length === 1) throw new Error(UNCONFIRMED_REQUEST);
    return pending.promise;
  };
  window.bridge = bridge as unknown as Bridge;
  let activeWorkspace = workspaceId;
  const opened: string[] = [];
  const onOpenTask = (id: string) => { opened.push(workspaceAgentTaskRoute(activeWorkspace, id)); };
  // The Tasks page can supply the full task callback; Home uses the task-id callback.
  const onOpenCreatedTask = replay ? (task: AgentTaskDto) => { opened.push(workspaceAgentTaskRoute(task.workspaceId, task.id)); } : undefined;
  let creation: ReturnType<typeof useAgentTaskCreation> | null = null;
  const currentCreation = () => { assert(creation !== null, 'The creation hook is mounted'); return creation; };
  function Composer({ id }: { id: string }) {
    creation = useAgentTaskCreation({ workspaceId: id, bridge: live, onOpenTask, onOpenCreatedTask });
    return <output>{creation.composer.draft}</output>;
  }
  const { host, root, dispose } = createFixtureRoot();
  try {
    flushSync(() => root.render(<Composer id={workspaceId} />));
    await until(() => creation?.composer.blocker === null && creation.composer.selection !== null, 'Creation settings load');
    flushSync(() => creation?.composer.onDraftChange('Start in the original workspace'));
    currentCreation().composer.onCreate();
    await until(() => requests.length === (replay ? 2 : 1), 'The creation request is pending');
    activeWorkspace = `${workspaceId}-next`;
    flushSync(() => root.render(departure === 'unmount' ? null : <Composer id={activeWorkspace} />));
    if (departure === 'switch') flushSync(() => creation?.composer.onDraftChange('Keep the next workspace draft'));
    pending.resolve(previewTask(workspaceId, requests[0].taskId));
    await settle();
    await settle();
    assert(opened.length === 0, 'A departed composer must not navigate when its task finishes');
    if (departure === 'switch') assert(host.textContent === 'Keep the next workspace draft', 'The old completion must not clear the new workspace draft');
    if (replay) assert(JSON.stringify(requests[0]) === JSON.stringify(requests[1]), 'Reconciliation still replays the accepted identity');
  }
  finally { dispose(); }
}

async function taskMembership(surface: 'home' | 'tasks') {
  const workspaceId = `membership-${surface}`;
  const first = { ...previewTask(workspaceId, 'first'), title: 'First existing task', mutationClaim: null };
  const added = { ...previewTask(workspaceId, 'added'), title: 'Task created elsewhere', mutationClaim: null };
  let tasks = [first];
  let fail = false;
  let calls = 0;
  const bridge = makeBridge(workspaceId);
  bridge.listAgentTasks = async () => { calls++; if (fail) throw new Error('Read failed'); return tasks; };
  window.bridge = bridge as unknown as Bridge;
  let refresh = noop;
  function Surface() {
    const taskAttention = useTaskAttention([{ id: workspaceId, name: '', repository: 'octo/app' }]);
    refresh = taskAttention.refresh;
    const props = { workspaceId, bridge, taskAttention, onOpenTask: noop, onOpenReview: noop };
    return surface === 'home' ? <AgentHome {...props} repository="octo/app" attention={attention} onShowAll={noop} /> : <AgentTasksView {...props} />;
  }
  const { host, root, dispose } = createFixtureRoot();
  const has = (title: string) => host.textContent?.includes(title) === true;
  try {
    flushSync(() => root.render(<Surface />));
    await until(() => has(first.title), `${surface}: initial tasks load`);
    tasks = [first, added];
    refresh();
    await until(() => has(added.title), `${surface}: polling adds tasks created elsewhere`);
    fail = true;
    tasks = [];
    const before = calls;
    refresh();
    await until(() => calls > before, 'The failed refresh was attempted');
    await settle();
    assert(has(first.title) && has(added.title), `${surface}: a failed refresh preserves the loaded tasks`);
    fail = false;
    tasks = [added];
    refresh();
    await until(() => !has(first.title) && has(added.title), `${surface}: polling removes deleted tasks`);
    tasks = [];
    refresh();
    await until(() => !has(first.title) && !has(added.title), `${surface}: a successfully loaded empty list clears the last task`);
  }
  finally { dispose(); }
}

async function retainedComposer() {
  const workspaceId = 'retained-composer';
  const bridge = makeBridge(workspaceId);
  const pending = Promise.withResolvers<AgentTaskDto>();
  const requests: Parameters<Bridge['createAgentTask']>[0][] = [];
  const opened: string[] = [];
  bridge.listAgentTasks = async () => [];
  bridge.createAgentTask = async request => {
    requests.push(request);
    return requests.length === 1 ? pending.promise : previewTask(workspaceId, request.taskId);
  };
  window.bridge = bridge as unknown as Bridge;
  const { host, root, dispose } = createFixtureRoot();
  const show = (active: boolean) => flushSync(() => root.render(<div style={{ display: active ? 'contents' : 'none' }}>
    <AgentTasksView workspaceId={workspaceId} bridge={bridge} active={active} onOpenTask={noop} onOpenReview={noop}
      onOpenCreatedTask={task => { opened.push(task.id); }} />
  </div>));
  const send = async (message: string) => {
    const input = element<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]', host);
    changeInput(input, message);
    await until(() => !button('Send — creates the task', host).disabled, 'The retained composer is ready to send');
    button('Send — creates the task', host).click();
  };
  try {
    show(true);
    await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'The retained composer loads');
    await send('Finish after opening an existing task');
    await until(() => requests.length === 1, 'Creation is pending before the user opens another task');
    show(false);
    pending.resolve(previewTask(workspaceId, requests[0].taskId));
    await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'The hidden composer settles its completed request');
    assert(opened.length === 0, 'A hidden composer cannot replace the existing task the user opened');
    show(true);
    assert(element<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]', host).value === '', 'The settled composer clears its accepted draft');
    await send('Create while this composer stays visible');
    await until(() => opened.length === 1, 'A visible composer opens its accepted task');
    assert(opened[0] === requests[1].taskId, 'Only the visible creation takes over navigation');
  }
  finally { dispose(); }
}

async function localTaskChanges() {
  const workspaceId = 'local-task-changes';
  const first = { ...previewTask(workspaceId, 'first'), version: 1, title: 'Locally changed task', mutationClaim: null };
  let tasks: AgentTaskDto[] = [first];
  let pending: PromiseWithResolvers<AgentTaskDto[]> | null = null;
  let calls = 0;
  let archived: AgentTaskDto | null = null;
  let created: AgentTaskDto | null = null;
  const bridge = makeBridge(workspaceId);
  bridge.listAgentTasks = async () => { calls++; return pending?.promise ?? tasks; };
  bridge.archiveAgentTask = async (_id, _workspace, version, value) => {
    assert(version === first.version, 'Archive sends the currently displayed task version');
    archived = { ...first, version: 2, archivedAt: value ? new Date().toISOString() : null };
    tasks = [archived];
    return archived;
  };
  bridge.deleteAgentTask = async () => { tasks = []; };
  bridge.createAgentTask = async request => {
    created = { ...previewTask(workspaceId, request.taskId), title: request.message, mutationClaim: null };
    tasks = [created];
    return created;
  };
  window.bridge = bridge as unknown as Bridge;
  let refresh = noop;
  function Surface() {
    const taskAttention = useTaskAttention([{ id: workspaceId, name: '', repository: 'octo/app' }]);
    refresh = taskAttention.refresh;
    return <AgentTasksView workspaceId={workspaceId} bridge={bridge} taskAttention={taskAttention} onOpenTask={noop} onOpenReview={noop} />;
  }
  const { host, root, dispose } = createFixtureRoot();
  const hasTask = (title: string) => host.querySelector(`[aria-label="Select ${title}"]`) !== null;
  const releaseRead = () => pending?.resolve(tasks);
  const holdRead = async () => {
    pending = Promise.withResolvers<AgentTaskDto[]>();
    const before = calls;
    refresh();
    await until(() => calls > before, 'The older list read has started');
    return pending;
  };
  const tab = (label: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find(candidate => candidate.textContent?.startsWith(label));
    assert(found !== undefined, `Missing ${label} tab`);
    found.click();
  };
  try {
    flushSync(() => root.render(<Surface />));
    await until(() => hasTask(first.title), 'The initial task loads');
    let held = await holdRead();
    button(`Archive task: ${first.title}`, host).click();
    await until(() => archived !== null && !hasTask(first.title), 'Archiving immediately removes the active task');
    held.resolve([first]); pending = null;
    await settle();
    assert(!hasTask(first.title), 'A read started before archive cannot restore the old active task');
    // Even a later stale task payload must not replace a newer local version.
    tasks = [first]; refresh();
    await settle();
    assert(!hasTask(first.title), 'A lower server version cannot undo the local archive');
    tab('Archived');
    await until(() => hasTask(first.title), 'The locally archived task remains in Archived');
    held = await holdRead();
    button(`Delete task: ${first.title}`, host).click();
    await settle();
    button('Delete task', host).click();
    await until(() => !hasTask(first.title), 'Deleting removes the archived task');
    held.resolve(archived === null ? [] : [archived]); pending = null;
    await settle();
    assert(!hasTask(first.title), 'A read started before delete cannot resurrect the task');
    tab('Active');
    held = await holdRead();
    const input = element<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]', host);
    changeInput(input, 'New locally created task');
    await until(() => !button('Send — creates the task', host).disabled, 'Creation is ready');
    button('Send — creates the task', host).click();
    await until(() => created !== null && hasTask('New locally created task'), 'Creation immediately adds the new task');
    held.resolve([]); pending = null;
    await settle();
    assert(hasTask('New locally created task'), 'An older empty read cannot remove the newly created task');
    tasks = []; refresh();
    await until(() => !hasTask('New locally created task'), 'A later authoritative empty read can remove a task deleted elsewhere');
  }
  finally { releaseRead(); dispose(); }
}

export async function run() {
  window.localStorage.clear();
  return runScenarios([
    ['creation finishes after unmount', () => creationAfterDeparture('unmount', false)],
    ['creation finishes after workspace switch', () => creationAfterDeparture('switch', false)],
    ['creation replay finishes after unmount', () => creationAfterDeparture('unmount', true)],
    ['creation replay finishes after workspace switch', () => creationAfterDeparture('switch', true)],
    ['hidden retained composer settles without navigating', retainedComposer],
    ['Tasks adopts additions, deletions and empty polls', () => taskMembership('tasks')],
    ['Home adopts additions, deletions and empty polls', () => taskMembership('home')],
    ['local create, archive and delete survive older polls', localTaskChanges],
  ]);
}
