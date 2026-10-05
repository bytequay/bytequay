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
import { createPreviewBridge, previewTask, TASK_BODY_NOOPS } from '../../agents/fixtures/previewBridge';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { parseAgentEventSequence } from '../../agents/events';
import eventFixture from '../../agents/fixtures/agent-events-v2.json';
import { initialAgentTaskState } from '../../agents/model';
import { getCached, setCached } from '../../dataCache';
import { runAttentionActions } from '../../home/fixtures/attentionBrowser';
import type { AgentTaskDto, AgentTurnDto, Bridge, CreateAgentTaskRequest, RepositoryIssueSnapshotDto, WorkspaceIssueDetailDto } from '../../types';
import WorkspaceIssuesScreen from '../WorkspaceIssuesScreen';
import type { DevelopmentIssue } from '../issueDevelopment';
import { workspaceAgentTaskRoute, workspaceIssueTaskRoute, workspaceLocationFromRoute } from '../model';
import '../../css/base.css';
import '../../css/agents.css';
import '../../css/issues.css';
import '../../css/home.css';
import '../../css/pulls.css';
import '../../css/reference-card.css';
import { assert, settle } from '../../fixtures/harness';

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
function edit(input: HTMLTextAreaElement, value: string): void {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
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
    edit(input, 'Keep the public API stable'); await settle();
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
    edit(input, 'Keep my additional instructions'); await settle();
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

export async function run(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Another attempt opens the composer over an ended task and names it', anotherAttempt],
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
