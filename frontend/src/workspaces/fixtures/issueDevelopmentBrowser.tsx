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
import AgentTasksView, { type TaskLaunch } from '../../agents/AgentTasksView';
import { AgentTaskBody } from '../../agents/AgentTaskScreen';
import { agentTurn, createPreviewBridge, previewTask, TASK_BODY_NOOPS } from '../../agents/fixtures/previewBridge';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { parseAgentEventSequence } from '../../agents/events';
import eventFixture from '../../agents/fixtures/agent-events-v2.json';
import { initialAgentTaskState } from '../../agents/model';
import { getCached, setCached } from '../../dataCache';
import { ZOOM_REFERENCE, type ZoomedReference } from '../../githubReference';
import { runAttentionActions } from '../../home/fixtures/attentionBrowser';
import type { AgentTaskDto, AgentTurnDto, Bridge, CreateAgentTaskRequest, RepositoryIssueSnapshotDto, WorkspaceDto, WorkspaceIssueDetailDto } from '../../types';
import type { UnifiedWorkItem } from '../../workitems/contracts';
import { LocalWorkItems } from '../../workitems/WorkItemsScreen';
import { startEpicWork } from '../epics';
import WorkspaceIssuesScreen from '../WorkspaceIssuesScreen';
import WorkspaceShell from '../WorkspaceShell';
import type { DevelopmentIssue } from '../issueDevelopment';
import { workspaceAgentTaskRoute, workspaceIssueTaskRoute, workspaceLocationFromRoute, workspaceRoute, type WorkspaceTab } from '../model';
import '../../css/base.css';
import '../../css/agents.css';
import '../../css/issues.css';
import '../../css/home.css';
import '../../css/pulls.css';
import '../../css/reference-card.css';
import { assert, changeInput, settle } from '../../fixtures/harness';

function button(host: HTMLElement, label: string): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')]
    .find(candidate => candidate.textContent?.trim() === label || candidate.getAttribute('aria-label') === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}
function dialogSelect(host: HTMLElement, label: string): HTMLSelectElement {
  const found = [...host.querySelectorAll<HTMLLabelElement>('.bq-confirm-dialog__choice')]
    .find(candidate => candidate.textContent?.startsWith(label))?.querySelector('select');
  assert(found != null, `Missing dialog choice: ${label}`);
  return found;
}
function choose(select: HTMLSelectElement, value: string): void {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(select, value);
  select.dispatchEvent(new Event('change', { bubbles: true }));
}
function fixture() {
  let issue: DevelopmentIssue = {
    id: 7001, number: 7, title: 'Fix upload timeout', body: 'Preserve the existing upload API.', author: 'alice', authorAvatarUrl: '',
    htmlUrl: 'https://github.com/upstream/app/issues/7', labels: [], assignees: ['me'], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z', isEpic: false, developmentTask: null,
  };
  const workspaceId = 'workspace-fork';
  const created: CreateAgentTaskRequest[] = [];
  const opened: string[] = [];
  let epicWrites = 0;
  const preview = createPreviewBridge(workspaceId);
  const snapshot = (): RepositoryIssueSnapshotDto => ({
    repositoryId: 40, repository: { owner: 'upstream', repo: 'app' }, issues: [{ ...issue }],
    page: 0, pageSize: 50, totalIssues: 1, hasMore: false, syncedAt: issue.updatedAt,
  });
  const detail = (): WorkspaceIssueDetailDto => ({ repositoryId: 40, issueNumber: 7, timeline: [], currentType: null, availableTypes: [], refreshedAt: issue.updatedAt });
  const bridge = {
    ...preview,
    // No Epic here holds work, so unmarking needs no confirmation.
    listWorkItems: async () => [],
    listAgentTasks: async () => [],
    listAgentReviews: async () => [],
    getModelSettings: async (workspace?: string) => {
      const settings = await preview.getModelSettings(workspace);
      return { ...settings, providers: [...settings.providers, { ...settings.providers[0], id: 'claude', label: 'Claude' }] };
    },
    fetchWorkspaceIssue: async (workspace: string, number: number) => {
      assert(workspace === workspaceId && number === issue.number, 'Source lookup must retain the chosen fork workspace and upstream number');
      return { ...issue };
    },
    fetchWorkspaceIssues: async () => snapshot(),
    refreshWorkspaceIssues: async () => snapshot(),
    fetchWorkspaceIssueDetail: async () => detail(),
    refreshWorkspaceIssueDetail: async () => detail(),
    getWorkspaceMetadata: async () => ({ repositoryId: 40, repository: snapshot().repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null }),
    setWorkspaceIssueEpic: async (workspace: string, number: number, isEpic: boolean) => {
      assert(workspace === workspaceId && number === 7, 'Epic marker targets selected issue');
      epicWrites++;
      issue = { ...issue, isEpic };
      return { isEpic, developmentTask: issue.developmentTask ?? null };
    },
    openInAppBrowser: async (url: string) => { opened.push(url); },
    recordRecentTouch: async () => {},
    createAgentTask: async (request: CreateAgentTaskRequest) => { created.push(request); return previewTask(workspaceId, request.taskId); },
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  return { bridge, workspaceId, created, opened, get issue() { return issue; }, set issue(value: DevelopmentIssue) { issue = value; }, get epicWrites() { return epicWrites; } };
}

async function issueToTask(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  let started: TaskLaunch | undefined;
  const showIssue = () => flushSync(() => root.render(<WorkspaceIssuesScreen key="issue" workspaceId={f.workspaceId} repository="upstream/app"
    initialIssueNumber={7} onSelectIssue={() => {}}
    onStartDevelopment={(number, launch) => { started = launch; window.location.hash = workspaceIssueTaskRoute(f.workspaceId, number); }}
    onViewTask={link => { window.location.hash = workspaceAgentTaskRoute(link.workspaceId, link.taskId); }} />));
  try {
    showIssue(); await settle();
    setCached('home:issues', [{ title: 'stale suggestion' }]);
    button(host, 'Mark as Epic').click(); await settle();
    assert(f.epicWrites === 1 && host.querySelector('.issues-epic-badge') !== null, 'Mark must persist and display Epic');
    assert(!host.querySelector('[aria-label="Start development"]') && f.created.length === 0, 'Epic must remove launch without creating a task');
    assert((getCached<unknown[]>('home:issues') ?? []).length === 0, 'Epic invalidation must clear Home seeds');
    button(host, 'Unmark as Epic').click(); await settle();
    assert(Number(f.epicWrites) === 2, 'Unmark must use the same durable classification');
    button(host, 'Start development').click(); await settle();
    assert(dialogSelect(host, 'Agent provider').value === 'codex' && dialogSelect(host, 'Model').value === 'gpt-5.6-codex' && dialogSelect(host, 'Effort').value === 'high',
      'Launch must ask which provider, model and effort, offered on the workspace development agent');
    assert(window.location.hash !== workspaceIssueTaskRoute(f.workspaceId, 7), 'Asking must not route before it is confirmed');
    button(host, 'Cancel').click(); await settle();
    assert(host.querySelector('.bq-confirm-dialog') === null && window.location.hash !== workspaceIssueTaskRoute(f.workspaceId, 7), 'Cancel must leave the issue as it was');
    button(host, 'Start development').click(); await settle();
    choose(dialogSelect(host, 'Agent provider'), 'claude'); await settle();
    choose(dialogSelect(host, 'Model'), 'gpt-5.6-codex-mini'); await settle();
    assert(dialogSelect(host, 'Effort').value === 'medium', 'A model that drops the chosen effort must fall back to its own');
    button(host, 'Start').click(); await settle();
    const location = workspaceLocationFromRoute(window.location.hash);
    assert(location?.sourceIssueNumber === 7 && location.workspaceId === f.workspaceId, 'Card must route to its workspace issue composer');
    assert(started?.laneId === 'claude' && started.model === 'gpt-5.6-codex-mini' && started.effort === 'medium',
      'The confirmed provider, model and effort must reach the composer');
    f.bridge.createAgentTask = async request => {
      f.created.push(request);
      if (f.created.length === 1) throw new Error('Response lost');
      const task = { ...previewTask('linked-workspace', request.taskId), issueSource: {
        initialTurnId: 'initial-turn', instructions: request.message,
        host: 'github.com', repo: 'upstream/app', issueId: f.issue.id, number: 7, title: f.issue.title, body: f.issue.body,
        url: f.issue.htmlUrl, updatedAt: f.issue.updatedAt,
      } };
      f.issue = { ...f.issue, developmentTask: { taskId: task.id, workspaceId: task.workspaceId } };
      return task;
    };
    flushSync(() => root.render(<AgentTasksView key="composer" workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge}
      launch={started}
      onOpenTask={() => { throw new Error('Creation must use returned workspace'); }} onOpenReview={() => {}}
      onOpenCreatedTask={task => { window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); }} />));
    await settle();
    button(host, 'Preview issue: upstream/app#7').focus(); await settle();
    assert(f.created.length === 0 && document.querySelector('.ref-card')?.textContent?.includes('Preserve the existing upload API.') === true, 'Opening composer must preview context without starting');
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null && input.value === 'Implement Fix upload timeout', 'Existing composer must be prefilled');
    changeInput(input, 'Keep the public API stable'); await settle();
    const send = button(host, 'Send — creates the task'); send.click(); send.click(); await settle();
    assert(Number(f.created.length) === 2 && f.created[0] === f.created[1], 'Lost response and double click must replay the same accepted request');
    const request = f.created[0];
    assert(request.issueSource?.issueId === 7001 && request.workspaceId === f.workspaceId, 'Creation must retain upstream issue and fork workspace');
    assert(request.message === 'Keep the public API stable', 'User instructions must survive');
    assert(request.laneId === 'claude' && request.defaults.model === 'gpt-5.6-codex-mini' && request.defaults.effort === 'medium',
      'The task must launch on the confirmed provider, model and effort');
    assert(request.developmentAutomation?.autoDraftPr === true && request.developmentAutomation.autoCiFix === true, 'Automation defaults must survive');
    assert(window.location.hash === workspaceAgentTaskRoute('linked-workspace', request.taskId), 'Returned task workspace controls navigation');
    showIssue(); await settle();
    button(host, 'Mark as Epic').click(); await settle();
    button(host, 'View task').click();
    assert(window.location.hash === workspaceAgentTaskRoute('linked-workspace', request.taskId), 'Epic marking must retain access to existing task');
    assert(!host.querySelector('[aria-label="Start development"]'), 'Linked task must not offer a duplicate launch');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function preparedInstruction(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<AgentTasksView workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge}
      initialDraft="Retry the upload once on a timeout and add a test that times out today." onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null && input.value === 'Retry the upload once on a timeout and add a test that times out today.',
      'A Goal’s prepared instruction survives the issue loading');
    assert(f.created.length === 0, 'Opening the prepared composer never creates a task');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function refreshAndCancel(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<AgentTasksView workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge}
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null, 'Composer input is available');
    changeInput(input, 'Keep my additional instructions'); await settle();
    f.issue = { ...f.issue, title: 'Updated issue title', body: 'Updated issue description', updatedAt: '2026-09-03T00:00:00Z' };
    button(host, 'Refresh issue').click(); await settle();
    button(host, 'Preview issue: upstream/app#7').focus(); await settle();
    assert(input.value === 'Keep my additional instructions' && document.querySelector('.ref-card')?.textContent?.includes('Updated issue description') === true,
      'Source refresh must update the attachment without losing user instructions');
    f.issue = { ...f.issue, isEpic: true };
    button(host, 'Refresh issue').click(); await settle();
    assert(button(host, 'Send — creates the task').disabled && host.textContent?.includes('Epics organize work') === true, 'Fresh Epic state blocks launch');
    assert(f.created.length === 0, 'Refresh and leaving the composer must never create a task');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function retainedSource(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  const task: AgentTaskDto = { ...previewTask(f.workspaceId, 'completed-task'), state: 'COMPLETED', issueSource: {
    initialTurnId: 'initial-turn', instructions: '',
    host: 'github.com', repo: 'upstream/app', issueId: f.issue.id, number: 7, title: f.issue.title, body: f.issue.body,
    url: f.issue.htmlUrl, updatedAt: f.issue.updatedAt,
  } };
  f.bridge.fetchWorkspaceIssue = async () => { throw new Error('Open issue cache row removed'); };
  try {
    flushSync(() => root.render(<AgentTaskBody state={{ ...initialAgentTaskState(), task, connection: 'open' }}
      models={[]} {...TASK_BODY_NOOPS} />));
    button(host, 'View issue: upstream/app#7').click();
    assert(f.opened[0] === f.issue.htmlUrl, 'Completed task must open retained source URL without a cache lookup');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

let conversationHost: HTMLDivElement | null = null;
let conversationRoot: ReturnType<typeof createRoot> | null = null;

/** Kept mounted while the Chromium harness exercises actual pointer and keyboard focus. */
export async function mountIssueConversation(running = false): Promise<{ x: number; y: number }> {
  const f = fixture();
  conversationHost = document.createElement('div');
  conversationHost.style.height = '100vh';
  document.body.append(conversationHost);
  conversationRoot = createRoot(conversationHost);
  const opening = parseAgentEventSequence(eventFixture.events)[0];
  assert(opening.type === 'user_message', 'Fixture opening event is a message');
  const currentTurn: AgentTurnDto | null = running ? {
    ...await f.bridge.getAgentTurn(opening.turnId ?? 'initial-turn'), state: 'RUNNING', endedAt: null,
    plan: [{ text: 'Inspect planner', state: 'COMPLETED' }, { text: 'Verify fix', state: 'IN_PROGRESS' }, { text: 'Commit', state: 'PENDING' }],
  } : null;
  const conversationEvents = [
    { ...opening, payload: { ...opening.payload, text: 'INTERNAL GENERATED PROVIDER PROMPT' } },
    ...(running ? [{ ...opening, eventId: 'recent-prose', sequence: opening.sequence + 1, type: 'assistant_message' as const,
      payload: { messageKind: 'COMMENTARY' as const, phase: 'FINAL' as const, text: 'The fix is ready for validation.\n\n- [x] Inspect planner\n- [ ] Verify fix' } }] : []),
  ];
  const task: AgentTaskDto = { ...previewTask(f.workspaceId, 'issue-conversation'), title: 'Fix upload timeout', issueSource: {
    host: 'github.com', repo: 'upstream/app', issueId: f.issue.id, number: 7, title: f.issue.title,
    body: '## Acceptance criteria\n\nPreserve the **existing upload API**.\n\n- Keep existing retries.\n- Add a regression test.\n\n```ts\nawait upload(file)\n```',
    url: f.issue.htmlUrl, updatedAt: f.issue.updatedAt, initialTurnId: null,
    instructions: 'Keep the **public API** stable.\n\nCover cancellation.',
  } };
  flushSync(() => conversationRoot?.render(<AgentTaskBody state={{ ...initialAgentTaskState(), task, connection: 'open', events: conversationEvents,
    turn: currentTurn, turns: currentTurn === null ? {} : { [currentTurn.id]: currentTurn },
    capabilities: { ...NO_AGENT_CAPABILITIES, structuredPlans: true },
  }} models={[]} {...TASK_BODY_NOOPS} />));
  await settle();
  const source = conversationHost.querySelector<HTMLElement>('.agt-issue-attachment');
  const composer = conversationHost.querySelector<HTMLElement>('.agt-compose');
  assert(source !== null && composer !== null, 'Source and shared composer must exist');
  const sourceBox = source.getBoundingClientRect();
  const composerBox = composer.getBoundingClientRect();
  assert(sourceBox.height <= 44 && composerBox.top - sourceBox.bottom >= 0 && composerBox.top - sourceBox.bottom <= 12,
    'Source must remain compact immediately above input');
  assert(sourceBox.right <= innerWidth && sourceBox.left >= 0 && composerBox.bottom <= innerHeight, 'Dock must fit the viewport');
  assert(!conversationHost.textContent?.includes('INTERNAL GENERATED'), 'Provider prompt must never appear in the request card');
  const request = conversationHost.querySelector<HTMLElement>('.agt-issue-request .agt-user__request');
  assert(request !== null && request.textContent === 'Start developmentupstream/app#7' && conversationHost.textContent?.includes('flow.png') === true,
    'Compact first request must retain the source and image attachments, including on old backend responses');
  assert(request.getBoundingClientRect().height <= 44 && request.getBoundingClientRect().right <= innerWidth,
    'First request must stay a compact row at normal and narrow widths');
  const reference = request.querySelector<HTMLElement>('.agt-user__request-head');
  assert(reference !== null && reference.clientWidth >= reference.scrollWidth,
    'The issue reference must remain readable at normal and narrow widths');
  assert(conversationHost.querySelector('.agt-review-request') === null, 'Issue request must not offer prompt disclosure');
  if (running) {
    const plan = conversationHost.querySelector<HTMLElement>('.agt-todos');
    const transcript = conversationHost.querySelector<HTMLElement>('.agt-transcript');
    assert(plan !== null && transcript !== null && plan.querySelectorAll('.agt-plan__step').length === 3,
      'Running turn snapshot must show the plan even without a plan event in loaded history');
    assert(plan.textContent?.includes('1 completed · 1 in progress · 1 pending') === true,
      'Plan summary must show the snapshot states');
    assert(transcript.getBoundingClientRect().bottom <= plan.getBoundingClientRect().top && plan.getBoundingClientRect().bottom <= sourceBox.top,
      'Plan dock must not overlap the transcript or attached issue');
    const checks = [...transcript.querySelectorAll<HTMLInputElement>('.agt-md input[type="checkbox"]')];
    assert(checks.length === 2 && checks.every(check => check.disabled) && checks[0].checked && !checks[1].checked,
      'Commentary checkboxes must render checked state and remain read-only');
  }
  const buttonBox = button(conversationHost, 'Preview issue: upstream/app#7').getBoundingClientRect();
  return { x: buttonBox.left + buttonBox.width / 2, y: buttonBox.top + buttonBox.height / 2 };
}

export async function verifyIssuePreview(): Promise<void> {
  await settle();
  const preview = document.querySelector<HTMLElement>('.ref-card');
  assert(preview !== null, 'Hover must open source preview');
  assert(preview.querySelector('h2')?.textContent === 'Acceptance criteria' && preview.querySelector('.ref-card__title')?.textContent === 'Fix upload timeout',
    'Preview must show title and render Markdown headings');
  assert(preview.querySelector('ul li') !== null && preview.querySelector('pre code') !== null && preview.textContent?.includes('existing upload API') === true,
    'Preview must render lists, emphasis, and fenced code');
  const box = preview.getBoundingClientRect();
  assert(box.left >= 0 && box.right <= innerWidth && box.top >= 0 && box.bottom <= innerHeight,
    `Source preview must fit normal and narrow panes (${box.left},${box.top},${box.right},${box.bottom} in ${innerWidth}x${innerHeight})`);
}

export async function verifyIssueKeyboard(): Promise<void> {
  assert(conversationHost !== null, 'Conversation is mounted');
  const source = button(conversationHost, 'Preview issue: upstream/app#7');
  source.focus(); await settle();
  await verifyIssuePreview();
  source.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  await settle();
  assert(document.querySelector('.ref-card')?.contains(document.activeElement) === true, 'Down arrow must enter the rich preview');
  await new Promise(resolve => setTimeout(resolve, 150));
  assert(document.querySelector('.ref-card')?.contains(document.activeElement) === true, 'Moving focus within preview must keep it open');
  document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  await settle();
  assert(document.querySelector('.ref-card') === null && document.activeElement === source, 'Escape must close preview and return focus');
  assert(!conversationHost.textContent?.includes('INTERNAL GENERATED') && conversationHost.querySelector('.agt-review-request') === null,
    'Source interaction never exposes provider prompt or adds request disclosure');
}

export function unmountIssueConversation(): void {
  flushSync(() => conversationRoot?.unmount());
  conversationHost?.remove();
  conversationRoot = null;
  conversationHost = null;
}

async function anotherAttempt(): Promise<void> {
  const f = fixture();
  f.issue = { ...f.issue, developmentTask: { taskId: 'ended-task', workspaceId: f.workspaceId } };
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  window.location.hash = '';
  try {
    flushSync(() => root.render(<AgentTasksView workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge} replaces="ended-task"
      initialDraft="Retry the upload with a longer timeout." onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    assert(window.location.hash === '', 'Another attempt keeps the composer open over the ended task');
    button(host, 'Send — creates the task').click(); await settle();
    assert(f.created.length === 1 && f.created[0].issueSource?.replaces === 'ended-task' && f.created[0].message === 'Retry the upload with a longer timeout.',
      'The new attempt names the ended task it replaces');
    flushSync(() => root.render(<AgentTasksView key="plain" workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge}
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    assert(window.location.hash === workspaceAgentTaskRoute(f.workspaceId, 'ended-task'), 'Without Start another attempt the issue opens its task');
  }
  finally { flushSync(() => root.unmount()); host.remove(); window.location.hash = ''; }
}

async function workAttempt(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  window.location.hash = '';
  try {
    flushSync(() => root.render(<AgentTasksView workspaceId={f.workspaceId} bridge={f.bridge} workItemId="work-1" initialDraft="Profile the reader"
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    button(host, 'Send — creates the task').click(); await settle();
    assert(f.created.length === 1 && f.created[0].workItemId === 'work-1' && f.created[0].message === 'Profile the reader' && f.created[0].issueSource === undefined,
      'The task for work without a source names the work and carries no source');
  }
  finally { flushSync(() => root.unmount()); host.remove(); window.location.hash = ''; }
}

async function decidedStart(): Promise<void> {
  const f = fixture();
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  window.location.hash = '';
  let taken = 0;
  const composer = (): HTMLTextAreaElement | null => host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
  try {
    flushSync(() => root.render(<AgentTasksView key="work" workspaceId={f.workspaceId} bridge={f.bridge} workItemId="work-1" initialDraft="Profile the reader" autoStart
      onAutoStart={() => { taken += 1; }} onOpenTask={() => {}} onOpenReview={() => {}}
      onOpenCreatedTask={task => { window.location.hash = workspaceAgentTaskRoute(task.workspaceId, task.id); }} />));
    assert(host.textContent?.includes('Starting task…') === true && host.textContent.includes('Profile the reader') && composer() === null,
      'A decided start shows its message and the task starting from the first frame, with no composer to send from');
    await settle(); await settle();
    assert(taken === 1 && f.created.length === 1 && f.created[0].workItemId === 'work-1' && f.created[0].message === 'Profile the reader',
      `A decided start is taken once and sends the work's message itself: ${taken} ${JSON.stringify(f.created)}`);
    assert(window.location.hash === workspaceAgentTaskRoute(f.workspaceId, f.created[0].taskId), 'The started task opens');

    flushSync(() => root.render(<AgentTasksView key="issue" workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge} initialDraft="Retry the upload once." autoStart
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    assert(Number(f.created.length) === 2 && f.created[1].issueSource?.issueId === 7001 && f.created[1].message === 'Retry the upload once.',
      'An issue’s decided start waits for the issue and starts as its task');

    f.issue = { ...f.issue, developmentTask: { taskId: 'under-way', workspaceId: f.workspaceId } };
    flushSync(() => root.render(<AgentTasksView key="under-way" workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge} initialDraft="Retry the upload once." autoStart
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    assert(Number(f.created.length) === 2 && window.location.hash === workspaceAgentTaskRoute(f.workspaceId, 'under-way'),
      'An issue that already has a task opens it, and nothing starts without its source');

    f.issue = { ...f.issue, developmentTask: null, isEpic: true };
    flushSync(() => root.render(<AgentTasksView key="blocked" workspaceId={f.workspaceId} sourceIssueNumber={7} bridge={f.bridge} initialDraft="Retry the upload once." autoStart
      onOpenTask={() => {}} onOpenReview={() => {}} />));
    await settle(); await settle();
    assert(Number(f.created.length) === 2 && composer()?.value === 'Retry the upload once.' && host.textContent?.includes('Epics organize work') === true,
      'A decided start that cannot run leaves its message in the composer with the reason');
    f.issue = { ...f.issue, isEpic: false };
    button(host, 'Refresh issue').click(); await settle(); await settle();
    assert(Number(f.created.length) === 2 && !button(host, 'Send — creates the task').disabled, 'Once it was left to the user, clearing what blocked it sends nothing');
  }
  finally { flushSync(() => root.unmount()); host.remove(); window.location.hash = ''; }
}

const WORKSPACE: WorkspaceDto = {
  id: 'workspace-fork', name: 'me/app', localRepositoryPath: '/managed/workspace-fork/repository', defaultBranch: 'main', upstreamDefaultBranch: 'main',
  selectedRepositoryId: 41, selectedOwner: 'me', selectedRepo: 'app', upstreamRepositoryId: 40, upstreamOwner: 'upstream', upstreamRepo: 'app',
  monitoredRepositoryId: 40, monitoredOwner: 'upstream', monitoredRepo: 'app', topology: 'FORK', lastCodeSyncAt: null, lastMetadataSyncAt: null, displayOrder: 0,
};

async function localDevelopmentThroughWorkspace(): Promise<void> {
  const f = fixture();
  const item: UnifiedWorkItem = {
    id: 'work-1', source: 'LOCAL', title: 'Profile the reader', description: 'Keep equivalent memory accounting', status: 'PLANNED', closed: false,
    updatedAt: f.issue.updatedAt, reference: null,
    work: { id: 'work-1', workspaceId: f.workspaceId, isEpic: false, contextLinks: [], title: 'Profile the reader', acceptanceCriteria: 'Keep equivalent memory accounting',
      kind: 'DEVELOP', completionRule: 'MERGED_DELIVERY', state: 'PLANNED', revision: 0, links: [], history: [], createdAt: f.issue.createdAt,
      updatedAt: f.issue.updatedAt, priority: 'NORMAL', blockedBy: [], epicId: 'ISSUE:github.com:7001', epicRank: 0 },
  };
  const epic: UnifiedWorkItem = { id: 'ISSUE:github.com:7001', source: 'GITHUB', title: 'Master reader memory accounting', description: '', status: 'Open',
    closed: false, updatedAt: f.issue.updatedAt, work: null,
    reference: { kind: 'ISSUE', key: 'upstream/app#7', label: '#7', title: 'Master reader memory accounting', addedAt: f.issue.createdAt } };
  let finishCreation = (): void => {};
  const creating = new Promise<void>(resolve => { finishCreation = resolve; });
  // The workspace's pages read more than a composer does; a read this scenario does not name fails, as an unreachable source would.
  window.bridge = new Proxy({ ...f.bridge, listUnifiedWorkItems: async () => [item, epic], createAgentTask: async (request: CreateAgentTaskRequest) => {
    const task = await f.bridge.createAgentTask(request);
    await creating;
    item.work?.links.push({ kind: 'TASK', key: task.id, label: 'Task', title: task.title, state: 'WORKING', status: 'Running', blocked: false,
      url: null, activity: null, addedAt: task.createdAt });
    return task;
  } } as unknown as Bridge, {
    get: (target, name: string) => Reflect.get(target, name) ?? (name.startsWith('on') ? () => () => {} : async (): Promise<never> => { throw new Error(`${name} is not in this fixture`); }),
  });
  const host = document.createElement('div'); document.body.append(host);
  const failures: string[] = [];
  const root = createRoot(host, { onUncaughtError: error => { failures.push(String(error)); } });
  window.location.hash = '';
  // The router follows the hash; here each page is rendered as it would.
  const show = (tab: WorkspaceTab): void => flushSync(() => root.render(<><WorkspaceShell workspace={WORKSPACE} tab={tab} onSelectTab={() => {}} onWorkspaceChanged={() => {}}
    initialPullRequestNumber={null} initialIssueNumber={null} initialAgentTaskId={null} onSelectAgentTask={() => {}} onSelectPullRequest={() => {}}
    onSelectIssue={() => {}} />{tab === 'home' && <LocalWorkItems workspace={WORKSPACE} zoomedItemId={item.id} />}</>));
  const composer = (): HTMLTextAreaElement | null => host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
  const opened: ZoomedReference[] = [];
  const onZoom = (event: Event): void => { opened.push((event as CustomEvent<ZoomedReference>).detail); };
  window.addEventListener(ZOOM_REFERENCE, onZoom);
  try {
    show('home'); await settle();
    button(host, 'Start development').click();
    assert(window.location.hash === workspaceRoute(f.workspaceId, 'tasks'), `Local development opens its workspace's tasks: ${window.location.hash}`);
    show('tasks');
    assert(host.textContent?.includes('Starting task…') === true && composer() === null, `The tasks page opens on the task starting: ${failures.join(' | ')}`);
    await settle(); await settle();
    assert(f.created.length === 1 && f.created[0].workItemId === item.id && f.created[0].message.includes(item.title)
      && f.created[0].message.includes('read_issue_context') && f.created[0].message.includes('parent Epic'),
      `Start development binds the work and requests its parent context without a composer step: ${JSON.stringify(f.created)} ${failures.join(' | ')}`);
    const card = host.querySelector<HTMLElement>('.agt-work-request');
    assert(card !== null && card.innerText.includes(item.title) && card.innerText.includes(epic.title) && !card.innerText.includes('read_issue_context'),
      'Startup shows the work title and parent Epic while keeping the generated instructions folded');
    const startup = host.querySelector<HTMLDetailsElement>('.agt-starting');
    assert(startup !== null && !startup.open && startup.querySelectorAll('.agt-starting-step').length === 5, 'Initialization starts folded with all steps retained');
    startup.querySelector('summary')?.click(); await settle();
    assert(startup.open && startup.querySelector<HTMLElement>('.agt-starting-step')?.checkVisibility(), 'Expanding initialization reveals its steps');
    startup.querySelector('summary')?.click();
    card.querySelector<HTMLAnchorElement>('.agt-work-request__epic a')?.click();
    const openedEpic = opened.at(-1);
    assert(openedEpic?.kind === 'issue' && openedEpic.number === 7 && openedEpic.repo === 'upstream/app', 'The parent link opens its Epic');
    finishCreation(); await settle(); await settle();
    assert(window.location.hash === workspaceAgentTaskRoute(f.workspaceId, f.created[0].taskId), `Start development lands on the task's conversation: ${window.location.hash}`);

    // A start that asks for review, as a Goal's prepared request does, waits in the composer.
    startEpicWork({ workspaceId: f.workspaceId, itemId: 'work-2', draft: 'A prepared request' });
    await settle(); await settle();
    assert(Number(f.created.length) === 1 && composer()?.value === 'A prepared request', `A start without send waits for the user: ${JSON.stringify(f.created)}`);

    const task = previewTask(f.workspaceId, f.created[0].taskId);
    const opening = parseAgentEventSequence(eventFixture.events)[0];
    assert(opening.type === 'user_message' && opening.turnId !== null, 'Fixture starts with a user turn');
    const turn = agentTurn('COMPLETED', { id: opening.turnId, taskId: task.id, endedAt: task.updatedAt });
    const firstMessage = { ...opening, taskId: task.id, payload: { ...opening.payload, text: f.created[0].message } };
    const followup = { ...firstMessage, eventId: 'followup', sequence: opening.sequence + 1, turnId: 'followup-turn', payload: { ...opening.payload, text: 'Explain the result' } };
    flushSync(() => root.render(<AgentTaskBody state={{ ...initialAgentTaskState(), task, connection: 'open', events: [firstMessage, followup],
      turns: { [turn.id]: turn, 'followup-turn': { ...turn, id: 'followup-turn' } } }} models={[]} {...TASK_BODY_NOOPS} />));
    await settle(); await settle();
    assert(host.querySelectorAll('.agt-work-request').length === 1 && host.querySelector('.agt-work-request')?.textContent?.includes(epic.title),
      'Replayed first turn retains its work card and parent link');
    assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Explain the result'), 'Follow-up messages remain ordinary messages');
    const request = host.querySelector<HTMLDetailsElement>('.agt-work-request details');
    request?.querySelector('summary')?.click();
    assert(request?.open && request.innerText.includes('read_issue_context') && host.querySelector('.agt-work-request')?.textContent?.includes('flow.png'),
      'The exact submitted prompt can be expanded and image attachments remain available');
    host.querySelector<HTMLAnchorElement>('.agt-work-request__title')?.click();
    const openedWork = opened.at(-1);
    assert(openedWork?.kind === 'work' && openedWork.itemId === item.id, 'The title opens its local work item');

    const jiraEpic: UnifiedWorkItem = { ...epic, source: 'JIRA', reference: { kind: 'JIRA', key: 'connection:cloud:1007', label: 'ENG-7',
      title: epic.title, addedAt: f.issue.createdAt } };
    window.bridge.listUnifiedWorkItems = async () => [item, jiraEpic];
    const replay = (key: string) => flushSync(() => root.render(<AgentTaskBody key={key} state={{ ...initialAgentTaskState(), task, connection: 'open',
      events: [firstMessage], turns: { [turn.id]: turn } }} models={[]} {...TASK_BODY_NOOPS} />));
    replay('jira'); await settle(); await settle();
    host.querySelector<HTMLAnchorElement>('.agt-work-request__epic a')?.click();
    const openedJira = opened.at(-1);
    assert(openedJira?.kind === 'jira' && openedJira.connectionId === 'connection' && openedJira.cloudId === 'cloud' && openedJira.issueId === '1007',
      'A Jira parent opens its own ticket');
    window.bridge.listUnifiedWorkItems = async () => [{ ...item, work: item.work === null ? null : { ...item.work, epicId: null } }];
    replay('standalone'); await settle(); await settle();
    assert(host.querySelector('.agt-work-request') !== null && host.querySelector('.agt-work-request__epic') === null,
      'Standalone work keeps its card without an invented parent');
    window.bridge.listUnifiedWorkItems = async () => { throw new Error('Work items unavailable'); };
    replay('unavailable'); await settle(); await settle();
    assert(host.querySelector('.agt-work-request') === null && host.querySelector('.agt-user__bubble')?.textContent?.includes('read_issue_context'),
      'An unavailable work relationship leaves the original message readable');
    assert(failures.length === 0, `The workspace rendered without errors: ${failures.join(' | ')}`);
  }
  finally { finishCreation(); window.removeEventListener(ZOOM_REFERENCE, onZoom); flushSync(() => root.unmount()); host.remove(); window.location.hash = ''; }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Another attempt opens the composer over an ended task and names it', anotherAttempt],
    ['Work without a source opens the composer with its title and names the work', workAttempt],
    ['A decided start sends itself once, with its source, and is left to the user when blocked', decidedStart],
    ['Local Start development opens its task directly with work-item and parent context', localDevelopmentThroughWorkspace],
    ['Issue Epic actions, composer, replay and linked workspace navigation', issueToTask],
    ['Source refresh preserves instructions and blocks Epics without launching', refreshAndCancel],
    ['A Goal’s prepared instruction opens the issue composer without launching', preparedInstruction],
    ['Completed task retains source link after cache removal', retainedSource],
    ['Attention actions keep source navigation and launch separate', runAttentionActions],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of cases) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
