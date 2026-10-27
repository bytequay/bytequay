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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import '../../index.css';
import AppSidebar from '../../AppSidebar';
import AssistantPanel from '../AssistantPanel';
import { AgentTaskBody } from '../../agents/AgentTaskScreen';
import { initialAgentTaskState, type AgentTaskState } from '../../agents/model';
import { TASK_BODY_NOOPS, agentTurn, previewTask } from '../../agents/fixtures/previewBridge';
import type { AgentEventEnvelope } from '../../agents/events';
import type { DictationEvent } from '../../dictation/contracts';
import type { AgendaTargetDto, AssistantItemDto, AssistantViewDto, ModelOptionDto, WorkspaceDto } from '../../types';

// The website's dictation animation: the user talks to the Assistant to start a task, then asks it to open the running task.

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const WS = 'ws1';
const WORKSPACE = {
  id: WS, name: 'example/harbor', selectedOwner: 'example', selectedRepo: 'harbor', monitoredOwner: 'example', monitoredRepo: 'harbor',
  localRepositoryPath: '/managed/ws1/harbor', topology: 'DIRECT',
} as unknown as WorkspaceDto;
const MODELS: ModelOptionDto[] = [{ id: 'opus', name: 'Opus 5.5', description: 'Most capable', efforts: ['medium', 'high'], defaultEffort: 'high', inputModalities: ['text', 'image'] }];
const TITLE = 'Add retry backoff to the webhook client';
const START_WORDS = 'Start a task to add retry backoff to the webhook client.';
const OPEN_WORDS = 'Take me to that task.';
const TASK: AgendaTargetDto = { kind: 'TASK', workspaceId: WS, repo: null, number: null, id: 'new-1', connectionId: null, cloudId: null };

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
let sequence = 0;
const event = (taskId: string, turnId: string, type: string, payload: unknown, at = minutesAgo(0)): AgentEventEnvelope => ({
  schemaVersion: 2, eventId: `e${++sequence}`, sequence, taskId, turnId, occurredAt: at, type, payload,
  source: { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null, providerItemId: `i${sequence}`, providerEventId: null },
} as AgentEventEnvelope);
const command = (taskId: string, turnId: string, done: boolean, argv: string, action: object) => event(taskId, turnId, 'command', {
  state: done ? 'COMPLETED' : 'STARTED', argv: [argv], output: done ? '' : null, exitCode: done ? 0 : null, truncated: false, cwd: '.',
  durationMs: done ? 400 : null, actions: [{ name: null, path: null, query: null, ...action }], description: null,
});
const task = (id: string, title: string): AgentTaskState['task'] => ({
  ...previewTask(WS, id), title, titleSource: 'USER', laneId: 'claude', generatedBranch: `contributor/${id}`,
  defaults: { model: 'opus', effort: 'high', permissionPolicy: 'AUTO' }, createdAt: minutesAgo(30), updatedAt: minutesAgo(1),
});

/** The page the user is on when the movie starts: a finished task. */
function finished(): AgentTaskState {
  const [id, turn] = ['done-1', 'turn-done'];
  return {
    ...initialAgentTaskState(), connection: 'open', task: task(id, 'Keep sessions signed in'),
    turn: agentTurn('COMPLETED', { id: turn, taskId: id, startedAt: minutesAgo(24), endedAt: minutesAgo(21),
      metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT3M' } }),
    events: [
      event(id, turn, 'user_message', { text: 'Fix the request retry after session refresh. Preserve the original request options and add regression tests.',
        attachments: [], deliveryKind: 'TURN' }, minutesAgo(24)),
      event(id, turn, 'lifecycle', { phase: 'TURN_STARTED', message: 'Turn started' }, minutesAgo(24)),
      event(id, turn, 'assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'Implemented the session refresh fix. Retried requests now use the new '
        + 'token, so an expired session can recover without sending the user back to sign-in.\n\n**Verified:** all 12 focused tests passed.' }, minutesAgo(21)),
      event(id, turn, 'lifecycle', { phase: 'TURN_COMPLETED', message: 'Turn completed' }, minutesAgo(21)),
    ],
  };
}

/** The task the Assistant started, a step further into its first turn each time. */
function running(step: number): AgentTaskState {
  const [id, turn] = ['new-1', 'turn-new'];
  const events = [
    event(id, turn, 'user_message', { text: 'Add retry backoff to the webhook client.', attachments: [], deliveryKind: 'TURN' }, minutesAgo(0.4)),
    event(id, turn, 'lifecycle', { phase: 'TURN_STARTED', message: 'Turn started' }, minutesAgo(0.4)),
    event(id, turn, 'plan_updated', { items: [
      { text: 'Read the webhook client', state: step > 1 ? 'COMPLETED' : 'IN_PROGRESS' },
      { text: 'Add exponential backoff with jitter', state: step > 1 ? 'IN_PROGRESS' : 'PENDING' },
      { text: 'Add tests for retries', state: 'PENDING' },
    ] }, minutesAgo(0.3)),
    command(id, turn, step > 0, 'sed -n 1,120p src/webhooks/client.ts', { kind: 'READ', name: 'client.ts' }),
  ];
  if (step > 1) events.push(command(id, turn, false, 'rg -n "retry" src/webhooks', { kind: 'SEARCH', query: 'retry' }));
  return {
    ...initialAgentTaskState(), connection: 'open', task: task(id, TITLE), turn: agentTurn('RUNNING', { id: turn, taskId: id, startedAt: minutesAgo(0.4) }),
    events, latestTurnId: turn, latestTurnSettled: false,
  };
}

const item = (id: string, kind: AssistantItemDto['kind'], text: string, receipt: AssistantItemDto['receipt'] = null): AssistantItemDto =>
  ({ id, conversationId: 'c1', at: new Date().toISOString(), kind, text, targets: {}, notReady: false, receipt, list: null, draft: null });
let conversation: AssistantViewDto = { id: 'c1', model: 'Claude Sonnet', running: false, items: [], rows: {} };
const reply = (...items: AssistantItemDto[]) => { conversation = { ...conversation, running: false, items: [...conversation.items, ...items] }; };

let emit: (event: DictationEvent) => void = () => {};
let dictation = 0;
window.bridge = new Proxy({
  getVoiceSettings: async () => ({ enabled: true, spoken: null, written: null }),
  onVoiceChanged: () => () => {},
  startDictation: async () => ({ id: `d${++dictation}` }),
  stopDictation: async () => {},
  cancelDictation: async () => {},
  onDictationEvent: (listener: typeof emit) => { emit = listener; return () => {}; },
  listAssistantConversations: async () => conversation.items.length === 0 ? []
    : [{ id: conversation.id, title: 'Webhook retries', updatedAt: new Date().toISOString(), running: conversation.running }],
  getAssistantConversation: async (id: string) => ({ ...conversation, id }),
  sendAssistantMessage: async (id: string, text: string) => {
    conversation = { ...conversation, id, running: true, items: [...conversation.items, item(`u${conversation.items.length}`, 'USER', text)] };
    return { itemId: 'u' };
  },
  readAgentHistory: async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 }),
  getLinkedWorkspaceWork: async () => [],
  getBackportChains: async () => [],
  listRunningTasks: async () => [],
  readAgentPendingApprovals: async () => [],
}, {
  // The sidebar and task page read more than the movie needs; the rest answers empty.
  get: (target, name: string) => (target as Record<string, unknown>)[name] ?? (name.startsWith('on') ? () => () => {} : async () => null),
}) as unknown as typeof window.bridge;

const { host, root } = createFixtureRoot();
let page: 'finished' | 'running' = 'finished';
let step = 0;
let open = false;
let pointer: { x: number; y: number } | null = null;

function render() {
  flushSync(() => root.render(
    <div className="browser-workspace is-web-rail-hidden">
      <div className="app-surfaces">
        <div className="app-shell">
          <AppSidebar page="workspaces" onNavigate={() => {}} canGoBack canGoForward={false} onBack={() => {}} onForward={() => {}} workspace={WORKSPACE}
            workspaceTab="tasks" onWorkspaceTab={() => {}} browserRailVisible={false} onToggleBrowserRail={() => {}}
            assistant={{ open, waiting: 0, unread: false, onToggle: () => {} }} />
          <main className="app-content">
            <AgentTaskBody key={page} state={page === 'finished' ? finished() : running(step)} models={MODELS} {...TASK_BODY_NOOPS} provider="claude" />
          </main>
        </div>
      </div>
      <AssistantPanel open={open} notices={[]} openCard={null} onScreen={null} onClose={() => { open = false; render(); }} />
      {pointer && <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true"
        style={{ position: 'fixed', left: pointer.x, top: pointer.y, zIndex: 99999, pointerEvents: 'none', filter: 'drop-shadow(0 1px 2px rgba(0,0,0,.5))' }}>
        <path d="M3 2l14 9-6.5 1.3L7 19z" fill="white" stroke="black" strokeWidth="1.2" strokeLinejoin="round" />
      </svg>}
    </div>,
  ));
}

// The Assistant follows an open by changing the route; the app would then show that task.
window.addEventListener('hashchange', () => {
  if (window.location.hash.includes(TASK.id ?? '')) { page = 'running'; render(); }
});

const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
const panel = () => host.querySelector<HTMLElement>('.assistant-float');
const button = (pattern: RegExp) => {
  const found = [...panel()?.querySelectorAll<HTMLButtonElement>('button') ?? []].find(each => pattern.test(each.getAttribute('aria-label') ?? ''));
  assert(found !== undefined, `The Assistant has a button matching ${pattern}`);
  return found;
};
const mic = () => button(/^(Dictate|Stop dictation)/);
const pointAt = (node: Element) => {
  const box = node.getBoundingClientRect();
  pointer = { x: box.left + box.width / 2 - 4, y: box.top + box.height / 2 - 3 };
  render();
};
// The Assistant reads its conversation again on focus, as it does when the window comes back.
const reread = async () => { window.dispatchEvent(new Event('focus')); await wait(80); };

/** Each yield is a frame on screen, with how long it holds in milliseconds. */
async function* talk(words: string): AsyncGenerator<number> {
  pointAt(mic());
  yield 500;
  mic().click();
  await wait(80);
  const id = `d${dictation}`;
  flushSync(() => emit({ id, type: 'listening' }));
  pointAt(mic());
  yield 450;
  const parts = words.split(' ');
  const steps = Math.min(5, parts.length);
  for (let index = 1; index <= steps; index++) {
    flushSync(() => emit({ id, type: 'partial', text: parts.slice(0, Math.ceil(parts.length * index / steps)).join(' ') }));
    await wait(30);
    yield index === steps ? 650 : 380;
  }
  assert(host.querySelector('.agt-dictation')?.textContent?.includes(words) === true, `The strip shows what was heard: ${words}`);
  mic().click();
  await wait(50);
  flushSync(() => emit({ id, type: 'finishing' }));
  flushSync(() => emit({ id, type: 'done', text: words }));
  await wait(80);
  assert(panel()?.querySelector('textarea')?.value === words, 'The words land in the draft');
  pointAt(button(/send/i));
  yield 800;
}

async function* send(): AsyncGenerator<number> {
  button(/send/i).click();
  await wait(150);
  pointer = null;
  render();
  yield 800;
}

async function* frames(): AsyncGenerator<number> {
  document.body.style.margin = '0';
  render();
  await wait(400);
  yield 1000;
  open = true;
  render();
  await wait(400);
  yield 700;

  yield* talk(START_WORDS);
  yield* send();
  reply(
    item('r1', 'RECEIPT', `Started “${TITLE}”`, { operation: 'START_TASK', outcome: 'DONE', detail: 'example/harbor · Claude Code Opus 5.5 · Auto-Draft PR',
      target: TASK, page: null, turnId: 'turn-new', fix: null, words: 'start a task to add retry backoff' }),
    item('a1', 'REPLY', 'Started it in example/harbor. It is reading the webhook client now.'),
  );
  await reread();
  assert(panel()?.querySelector('.assistant-receipt')?.textContent?.startsWith(`Started “${TITLE}”`) === true, 'The start leaves a receipt');
  yield 2000;

  yield* talk(OPEN_WORDS);
  yield* send();
  reply(item('r2', 'RECEIPT', `Opened “${TITLE}”`, { operation: 'OPEN', outcome: 'DONE', detail: null, target: TASK, page: null, turnId: null, fix: null,
    words: 'take me to that task' }));
  await reread();
  await wait(300);
  assert(panel() === null && page === 'running', 'An open that arrives while the panel is open closes it and goes to the task');
  assert(host.querySelector('.app-content')?.textContent?.includes(TITLE) === true, 'The running task is on screen');
  yield 1200;
  for (const [further, hold] of [[1, 1200], [2, 2600]]) {
    step = further;
    render();
    await wait(100);
    yield hold;
  }
}

let movie: AsyncGenerator<number> | null = null;

/** Draws the next frame and returns how long it holds, or null after the last one. */
export async function next(): Promise<number | null> {
  movie ??= frames();
  const frame = await movie.next();
  return frame.done === true ? null : frame.value;
}
