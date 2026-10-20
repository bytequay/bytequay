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
import { changeInput } from '../../fixtures/harness';
import type { WorkItemDto } from '../../workitems/contracts';
import type { ReviewDraftDto } from '../../pulls/agent-review/contracts';
import { pr, prDetail } from '../../pulls/fixtures/pullRequest';
import AssistantPanel from '../AssistantPanel';
import type { RunningTask } from '../../agents/runningTaskModel';
import type { AppNotice } from '../../notices/contracts';
import type {
  AgendaDto, AgendaTargetDto, AgentPendingApprovalDto, AssistantItemDto, AssistantListStyle, AssistantReceiptDto, AssistantRowDto, AssistantViewDto, Bridge,
} from '../../types';
import { settingsRoute } from '../../settings/routes';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../../workspaces/model';
import { dayLabel, localDay } from '../model';

function assert(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

const settle = () => new Promise(resolve => setTimeout(resolve, 30));
const target = (kind: AgendaTargetDto['kind'], extra: Partial<AgendaTargetDto>): AgendaTargetDto =>
  ({ kind, workspaceId: 'ws1', repo: null, number: null, id: null, connectionId: null, cloudId: null, ...extra });
const PULL = target('PULL', { repo: 'trinodb/trino', number: 31377 });

const AGENDA: AgendaDto = {
  day: '2026-10-16', state: 'READY', enabled: true, at: 540, generatedAt: '2026-10-16T07:02:00Z', model: 'Sonnet', summary: null,
  items: [
    { ref: 'work:w22', action: 'start', title: 'Start “Expose snapshot id in metadata”', label: 'Expose snapshot id', at: '2026-10-16T00:14:00Z', workspaceId: 'ws1',
      why: 'This is next and unblocks 2 items.', target: target('WORK', { id: 'w22' }), where: 'Iceberg write path · 3/6', status: 'open', byYou: false,
      doneText: null, doneAt: null },
    { ref: 'pr:trinodb/trino#31377', action: 'review', title: 'Review #31377 Add Iceberg metadata cache', label: 'Review #31377', at: '2026-10-13T08:00:00Z',
      workspaceId: 'ws1', why: 'Asked 3 days ago. CI green.', target: PULL, where: null, status: 'open', byYou: false, doneText: null, doneAt: null },
  ],
  recap: null, calendar: null, failure: null,
};

const VIEW: AssistantViewDto = {
  day: '2026-10-16', model: 'Claude Sonnet', running: false,
  items: [
    { id: 'u1', day: '2026-10-16', at: '2026-10-16T07:06:00Z', kind: 'USER', text: 'What should I look at first?', targets: {}, notReady: false, receipt: null, list: null, draft: null },
    { id: 'r1', day: '2026-10-16', at: '2026-10-16T07:06:20Z', kind: 'REPLY', targets: { 'pr:trinodb/trino#31377': PULL, 'task:t1': target('TASK', { id: 't1' }) },
      text: 'Start with [#31377](pr:trinodb/trino#31377): it has waited 3 days and CI is green. Then answer elric-t on task:t1.', notReady: false, receipt: null, list: null, draft: null },
    { id: 'c1', day: '2026-10-16', at: '2026-10-16T07:08:00Z', kind: 'RECEIPT', text: 'Started “Expose snapshot id in metadata”', targets: {}, notReady: false,
      receipt: { operation: 'START_TASK', outcome: 'DONE', detail: 'trinodb/trino · Claude Code Opus 5.5 · Auto draft PR', target: target('TASK', { id: 't9' }),
        page: null, turnId: 'turn-9', fix: null, words: 'start the snapshot id one' }, list: null, draft: null },
    { id: 'c2', day: '2026-10-16', at: '2026-10-16T07:08:01Z', kind: 'RECEIPT', text: 'Use the buttons: this one runs outside the sandbox', targets: {}, notReady: false,
      receipt: { operation: 'ANSWER_APPROVAL', outcome: 'REFUSED', detail: null, target: null, page: null, turnId: null, fix: 'BUTTONS', words: 'allow it' }, list: null, draft: null },
  ],
  rows: {},
};

// Relative to now, so a timeline's Today and Yesterday hold whenever the fixture runs.
const ago = (minutes: number): string => new Date(Date.now() - minutes * 60_000).toISOString();
const row = (fields: Pick<AssistantRowDto, 'ref' | 'kind' | 'title' | 'target'> & Partial<AssistantRowDto>): AssistantRowDto => ({
  key: null, state: null, ci: null, review: null, approvals: null, mine: false, mergeBlocked: false, youApproved: false, agent: null, epic: null, done: null, total: null, at: null, latest: null, ...fields,
});
const ROWS: Record<string, AssistantRowDto> = Object.fromEntries([
  row({ ref: 'pr:trinodb/trino#31377', kind: 'PULL', title: 'Add Iceberg metadata cache', target: PULL, key: '#31377', state: 'open', ci: 'passing',
    review: 'approved', approvals: 2, at: ago(20), latest: { by: 'marlo-t', text: 'approved these changes', at: ago(20) } }),
  row({ ref: 'pr:trinodb/trino#31402', kind: 'PULL', title: 'Retry Iceberg commit on conflict', target: target('PULL', { repo: 'trinodb/trino', number: 31402 }),
    key: '#31402', state: 'open', ci: 'failing', review: 'changes_requested', mine: true, at: ago(60 * 26) }),
  row({ ref: 'task:t1', kind: 'TASK', title: 'Expose snapshot id in metadata', target: target('TASK', { id: 't1' }), agent: 'Claude Code', state: 'waiting',
    at: ago(45), latest: { by: 'Claude Code', text: 'Which table should hold the **snapshot id**?', at: ago(45) } }),
  row({ ref: 'jira:cloud:10042', kind: 'JIRA', title: 'Expire snapshots by branch', target: target('JIRA', { id: '10042', connectionId: 'c1', cloudId: 'cloud' }),
    key: 'ICE-7', state: 'In progress', at: ago(60 * 24 * 3) }),
  row({ ref: 'work:w22', kind: 'WORK', title: 'Add a snapshot id column', target: target('WORK', { id: 'w22' }), epic: 'Iceberg write path', state: 'planned',
    at: ago(60 * 24 * 3 + 30) }),
  row({ ref: 'epic:LOCAL:w1', kind: 'EPIC', title: 'Iceberg write path', target: target('WORK', { id: 'w1' }), done: 3, total: 6 }),
].map(each => [each.ref, each]));
const REFS = ['pr:trinodb/trino#31377', 'pr:trinodb/trino#31402', 'task:t1', 'jira:cloud:10042', 'work:w22', 'epic:LOCAL:w1', 'pr:trinodb/trino#1'];
const listView = (style: AssistantListStyle, extra: AssistantRowDto[] = []): AssistantViewDto => {
  const list: AssistantItemDto = { id: 'l1', day: '2026-10-16', at: '2026-10-16T07:06:10Z', kind: 'LIST', text: 'Waiting on you', targets: {}, notReady: false,
    receipt: null, list: { style, refs: [...REFS, ...extra.map(each => each.ref)] }, draft: null };
  return { ...VIEW, items: [VIEW.items[0], list], rows: { ...ROWS, ...Object.fromEntries(extra.map(each => [each.ref, each])) } };
};

const notice = (id: string, kind: AppNotice['kind'], taskId: string, title: string): AppNotice => ({
  id, kind, taskId, workspaceId: 'ws1', turnId: `turn-${taskId}`, reviewId: null, title, pullRequest: null, pullRequestTitle: null, changes: null,
  message: 'Permission needed', repository: 'trinodb/trino', laneId: 'claude', occurredAt: '2026-10-16T07:10:00Z',
});
const NOTICES = [notice('approval:a1', 'APPROVAL', 'task-1', 'Metadata cache'), notice('finished:turn-2', 'TURN_FINISHED', 'task-2', 'Retry commit on conflict')];

const APPROVAL: AgentPendingApprovalDto = {
  approvalId: 'a1', taskId: 'task-1', turnId: 'turn-task-1', occurredAt: '2026-10-16T07:10:00Z', title: 'Run the focused Maven test',
  detail: './mvnw -pl :trino-iceberg test -Dtest=TestIcebergSnapshotId', approvalKind: 'COMMAND', scope: [], sandboxed: true,
  availableDecisions: ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'], actionable: true,
  source: { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null, providerItemId: null, providerEventId: null },
};

const RUNNING: RunningTask = {
  id: 'run-1', workspaceId: 'ws1', title: 'Expose snapshot id in metadata', repository: 'trinodb/trino', kind: 'REVIEW', targetId: 'r7', status: 'RUNNING',
  startedAt: '2026-10-16T07:20:00Z', expiresAt: null,
};

type Calls = {
  sent: unknown[][]; resolved: unknown[][]; opened: string[]; removed: string[]; stopped: number; closed: number; cancelled: string[]; answered: string[];
  completed: unknown[][];
};

type Mounted = { host: HTMLElement; calls: Calls; render: (open: boolean, openCard?: { id: string }) => void; dispose: () => void };

function mount(view: AssistantViewDto = VIEW, failSends = 0, notices = NOTICES, running: RunningTask[] = [], replied: () => AssistantViewDto = () => view,
  completed: () => AssistantViewDto = () => view, scripted: Partial<Bridge> = {}, chatEnabled = true): Mounted {
  const calls: Calls = { sent: [], resolved: [], opened: [], removed: [], stopped: 0, closed: 0, cancelled: [], answered: [], completed: [] };
  let failures = failSends;
  let today = view;
  window.bridge = {
    getAssistantToday: async () => today,
    sendAssistantMessage: async (...args: unknown[]) => {
      calls.sent.push(args);
      if (failures-- > 0) throw new Error("Error invoking remote method 'assistant:send': Error: The backend is busy");
      today = replied();
      return { itemId: 'u2' };
    },
    stopAssistant: async () => { calls.stopped++; },
    retryAssistant: async () => ({ itemId: 'u1' }),
    getAgendaToday: async () => AGENDA,
    makeAgendaAgain: async () => AGENDA,
    markAgendaItem: async () => AGENDA,
    listRunningTasks: async () => running,
    readAgentPendingApprovals: async (taskId: string, turnId: string) => taskId === APPROVAL.taskId && turnId === APPROVAL.turnId ? [APPROVAL] : [],
    resolveAgentApproval: async (...args: unknown[]) => { calls.resolved.push(args); return {}; },
    recordAssistantAnswer: async (id: string) => { calls.answered.push(id); },
    cancelAgentTurn: async (turnId: string) => { calls.cancelled.push(turnId); return {}; },
    completeAgentTask: async (...args: unknown[]) => { calls.completed.push(args); today = completed(); return {}; },
    openAppNotice: async (id: string) => { calls.opened.push(id); },
    removeAppNotice: async (id: string) => { calls.removed.push(id); },
    findStoredPullRequest: async (): Promise<null> => null,
    ...scripted,
  } as unknown as Bridge;
  const { host, root, dispose: disposeRoot } = createFixtureRoot();
  let shown = true;
  const render = (open: boolean, openCard?: { id: string }) => {
    shown = open;
    flushSync(() => root.render(
      <div className="browser-workspace">
        <div className="app-surfaces"><main style={{ height: '100vh', padding: 24, color: 'var(--fg-muted)' }}>The page under the Assistant</main></div>
        <AssistantPanel open={open} chatEnabled={chatEnabled} notices={notices} openCard={openCard ?? null} onScreen="pr:trinodb/trino#31377"
          onClose={() => { calls.closed++; if (shown) render(false); }} />
      </div>,
    ));
  };
  render(true);
  return { host, calls, render, dispose: disposeRoot };
}

function type(host: HTMLElement, text: string): HTMLTextAreaElement {
  const input = host.querySelector<HTMLTextAreaElement>('.assistant-float textarea');
  assert(input !== null, 'The composer must show');
  changeInput(input, text);
  return input;
}

const press = (node: Element, key: string, shiftKey = false) =>
  node.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }));
const enter = (input: HTMLElement) => press(input, 'Enter');
const pills = (host: HTMLElement) => [...host.querySelectorAll<HTMLElement>('.assistant-pill')].map(pill => pill.textContent);
const selected = (host: HTMLElement) => host.querySelector('.assistant-pill.is-selected')?.textContent;
const top = (host: HTMLElement) => host.querySelector<HTMLElement>('.assistant-card');

async function floatsOverThePage(): Promise<void> {
  const view = mount();
  try {
    await settle();
    const float = view.host.querySelector<HTMLElement>('.assistant-float');
    assert(float !== null && document.activeElement === float.querySelector('textarea'), 'Opening focuses the composer');
    const box = float.getBoundingClientRect();
    assert(box.width === 640 && box.top === 72 && Math.round(box.left + box.width / 2) === window.innerWidth / 2, `It floats 640px wide, 72px down, centred: ${JSON.stringify(box)}`);
    const page = view.host.querySelector<HTMLElement>('.app-surfaces')?.getBoundingClientRect();
    assert(page !== undefined && page.width === window.innerWidth, `The page keeps its width: ${JSON.stringify(page)}`);
    assert(float.getAttribute('role') === 'dialog', 'It is a dialog, so the editor’s native view hides under it');
    const light = getComputedStyle(float.querySelector('.agt-compose__streamer') as Element);
    assert(light.animationName === 'assistant-light' && light.animationDuration === '10s', `The composer's light turns once every 10 s: ${light.animationDuration}`);
    assert(view.host.querySelector('.assistant-talk') === null, 'The earlier conversation stays folded until the user talks');
    assert(JSON.stringify(pills(view.host)) === JSON.stringify(['All2', 'Decision1', 'Finished1', 'Today0/2']), `Empty groups hide: ${JSON.stringify(pills(view.host))}`);
    assert(selected(view.host) === 'All2', 'All is selected first');
    assert(top(view.host)?.dataset.card === 'approval:a1', 'The approval is the top card');
    const peeks = [...view.host.querySelectorAll('.assistant-peek')].map(peek => peek.textContent);
    assert(JSON.stringify(peeks) === '["Retry commit on conflictTurn finished"]', `The next card peeks by its title: ${JSON.stringify(peeks)}`);
    assert(view.host.querySelector('.assistant-more') === null, 'Two cards need no View all');
    view.host.querySelector<HTMLButtonElement>('.assistant-pill[data-group="today"]')?.click();
    await settle();
    assert(view.host.querySelector('.assistant-today .today-row') !== null && top(view.host) === null, 'Today swaps the stack for the agenda');
    view.host.querySelector<HTMLButtonElement>('.assistant-backdrop')?.click();
    assert(view.calls.closed === 1 && view.host.querySelector('.assistant-float') === null, 'A click beside it closes it');
  }
  finally { view.dispose(); }
}

async function showsTheDayOnceTheUserTalks(): Promise<void> {
  const view = mount();
  try {
    await settle();
    enter(type(view.host, 'What should I look at first?'));
    await settle();
    const talk = view.host.querySelector<HTMLElement>('.assistant-talk');
    assert(talk !== null, 'Sending shows the conversation');
    const order = [...talk.querySelectorAll('.agt-user, .assistant-reply, .assistant-receipt')].map(node => node.className);
    assert(JSON.stringify(order) === JSON.stringify(['agt-user', 'assistant-reply', 'assistant-receipt', 'assistant-receipt is-refused']), `The day in order: ${JSON.stringify(order)}`);
    const refusal = talk.querySelector<HTMLElement>('.assistant-receipt.is-refused');
    assert(refusal?.querySelector('.assistant-receipt__line')?.textContent === 'Use the buttons: this one runs outside the sandbox ↓'
      && refusal.querySelector('.assistant-receipt__words')?.textContent === '“allow it”' && refusal.querySelector('button') === null,
      `A refusal shows the words it refused and points down at the card: ${refusal?.textContent}`);
    assert(/^Sent at \d{1,2}:\d{2}$/.test(talk.querySelector('.agt-user__bubble')?.getAttribute('title') ?? ''), 'A message keeps its time on hover');
    const links = [...talk.querySelectorAll<HTMLAnchorElement>('.assistant-reply a')].map(link => link.textContent);
    assert(JSON.stringify(links) === JSON.stringify(['#31377', 'task']), `Both refs link, bare or not: ${JSON.stringify(links)}`);
    const receipt = talk.querySelector<HTMLElement>('.assistant-receipt');
    assert(receipt?.querySelector('.assistant-receipt__detail')?.textContent === 'trinodb/trino · Claude Code Opus 5.5 · Auto draft PR', 'A start says where it runs');
    assert(receipt?.querySelector('.assistant-receipt__words')?.textContent === '“start the snapshot id one”', 'A receipt shows the exact words it acted on');
    [...receipt.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Stop')?.click();
    await settle();
    assert(JSON.stringify(view.calls.cancelled) === '["turn-9"]' && receipt.textContent?.includes('Stopped') === true, 'Stop cancels the first turn');
    const opened: unknown[] = [];
    const listener = (event: Event) => { opened.push((event as CustomEvent).detail); };
    window.addEventListener('bytequay:zoom-reference', listener);
    try { talk.querySelector<HTMLAnchorElement>('.assistant-reply a')?.click(); }
    finally { window.removeEventListener('bytequay:zoom-reference', listener); }
    assert(JSON.stringify(opened) === JSON.stringify([{ kind: 'pull', repo: 'trinodb/trino', number: 31377, workspaceId: 'ws1' }]), `A reply ref opens its pull request: ${JSON.stringify(opened)}`);
    assert(view.calls.closed === 1, 'Opening something closes the Assistant over it');
    view.render(true);
    await settle();
    assert(view.host.querySelector('.assistant-talk') === null, 'Opened again, the conversation folds away');
    enter(type(view.host, 'and the task?'));
    await settle();
    [...view.host.querySelectorAll<HTMLButtonElement>('.assistant-receipt .assistant-action')].find(button => button.textContent === 'Open')?.click();
    assert(window.location.hash === workspaceAgentTaskRoute('ws1', 't9'), `Open goes to the started task: ${window.location.hash}`);
  }
  finally { view.dispose(); }
}

async function answersWithTheTasksButtons(): Promise<void> {
  const view = mount();
  try {
    await settle();
    const allow = view.host.querySelector<HTMLButtonElement>('.assistant-card button[aria-label="Allow once: Run the focused Maven test"]');
    assert(allow !== null, 'The approval shows the task’s own buttons');
    allow.click();
    await settle();
    assert(JSON.stringify(view.calls.resolved) === JSON.stringify([['a1', 'ALLOW', null, null]]), `Allow once reaches the task: ${JSON.stringify(view.calls.resolved)}`);
    assert(JSON.stringify(view.calls.answered) === '["a1"]', 'The answer leaves a receipt in the conversation');
    assert(view.host.querySelector('.agt-appr') === null, 'An answered card leaves');
    assert(JSON.stringify(pills(view.host)) === JSON.stringify(['All1', 'Finished1', 'Today0/2']), `Decision goes with its last card: ${JSON.stringify(pills(view.host))}`);
    const card = top(view.host);
    assert(card?.textContent?.includes('Retry commit on conflict') === true, 'The next card slides up');
    view.host.querySelector<HTMLButtonElement>('.assistant-card button[aria-label="Clear “Retry commit on conflict”"]')?.click();
    [...(card?.querySelectorAll<HTMLButtonElement>('.assistant-button') ?? [])].find(button => button.textContent === 'View response')?.click();
    await settle();
    assert(JSON.stringify(view.calls.opened) === '["finished:turn-2"]' && JSON.stringify(view.calls.removed) === '["finished:turn-2"]', 'Cards open and clear their notice');
    assert(view.calls.closed === 1, 'Opening a notice closes the Assistant');
  }
  finally { view.dispose(); }
}

async function sendsWithWhatIsShown(): Promise<void> {
  const view = mount(VIEW, 1);
  try {
    await settle();
    const input = type(view.host, 'allow it');
    enter(input);
    await settle();
    assert(view.host.querySelector('.assistant-error')?.textContent === 'The backend is busy', 'A failed send says why');
    assert(input.value === 'allow it', 'A failed send keeps the draft');
    enter(input);
    await settle();
    const [first, second] = view.calls.sent;
    assert(JSON.stringify(first.slice(0, 3)) === JSON.stringify(['allow it', ['approval:a1', 'finished:turn-2'], 'pr:trinodb/trino#31377']),
      `A message carries the cards on screen and what is on screen: ${JSON.stringify(first)}`);
    assert(second[3] === first[3], 'Sending the same words again reuses the key');
    assert(input.value === '' && view.host.querySelector('.assistant-error') === null, 'A sent message clears the draft');
    view.host.querySelector<HTMLButtonElement>('.assistant-chip button')?.click();
    await settle();
    assert(view.host.querySelector('.assistant-chip') === null, 'The chip can be left out');
    view.host.querySelector<HTMLButtonElement>('.assistant-pill[data-group="today"]')?.click();
    enter(type(view.host, 'and the next one?'));
    await settle();
    assert(view.calls.sent[2][2] === null && view.calls.sent[2][3] !== first[3], `A removed chip sends no screen, with a new key: ${JSON.stringify(view.calls.sent[2])}`);
    assert(JSON.stringify(view.calls.sent[2][1]) === '[]', 'With the agenda showing, no card is on screen');
    assert(view.host.querySelector('.assistant-chip') !== null, 'The chip comes back for the next message');
    press(input, 'Escape');
    assert(view.calls.closed === 1, 'Escape closes the Assistant');
  }
  finally { view.dispose(); }
}

async function movesWithTheKeyboard(): Promise<void> {
  const rows = Array.from({ length: 3 }, (_, index) => ({ ...notice(`finished:turn-${index + 3}`, 'TURN_FINISHED', `task-${index + 3}`, `Finished task ${index + 3}`),
    occurredAt: `2026-10-16T07:0${index}:00Z` }));
  const view = mount(VIEW, 0, [...NOTICES, ...rows], [RUNNING]);
  // React renders a dispatched key's update after the event, so each press settles before the next.
  const key = async (node: Element | null, name: string, shiftKey = false) => { if (node !== null) press(node, name, shiftKey); await settle(); };
  try {
    await settle();
    assert(JSON.stringify(pills(view.host)) === JSON.stringify(['All6', 'Decision1', 'Running1', 'Finished4', 'Today0/2']), `Running tasks get a pill: ${JSON.stringify(pills(view.host))}`);
    assert(top(view.host)?.textContent?.includes('Expose snapshot id in metadata') === true, 'The newest card leads All');
    assert(view.host.querySelectorAll('.assistant-peek').length === 2 && view.host.querySelector('.assistant-more')?.textContent === '3 more· View all',
      `At most three cards show: ${view.host.querySelector('.assistant-more')?.textContent}`);
    const input = view.host.querySelector<HTMLTextAreaElement>('.assistant-float textarea');
    assert(input !== null, 'The composer shows');
    await key(input, 'Tab');
    assert(document.activeElement?.textContent === 'All6', 'Tab from the composer lands on the selected pill');
    await key(document.activeElement, 'Tab');
    assert(document.activeElement?.textContent === 'Decision1' && selected(view.host) === 'Decision1', 'Tab moves to the next pill and selects it');
    await key(document.activeElement, 'Tab', true);
    await key(document.activeElement, 'Tab', true);
    assert(selected(view.host) === 'Today0/2', `Shift+Tab goes back and wraps: ${selected(view.host)}`);
    await key(document.activeElement, 'Tab');
    await key(document.activeElement, 'Tab');
    await key(document.activeElement, 'Tab');
    await key(document.activeElement, 'Tab');
    assert(selected(view.host) === 'Finished4' && top(view.host)?.textContent?.includes('Retry commit on conflict') === true, `Finished shows its own cards: ${selected(view.host)}`);
    await key(document.activeElement, 'ArrowRight');
    assert(top(view.host)?.querySelector('.assistant-pager')?.textContent === '2/4', `→ pages the cards: ${top(view.host)?.querySelector('.assistant-pager')?.textContent}`);
    await key(document.activeElement, 'ArrowLeft');
    await key(document.activeElement, 'ArrowLeft');
    assert(top(view.host)?.querySelector('.assistant-pager')?.textContent === '4/4', '← wraps to the last card');
    view.host.querySelector<HTMLButtonElement>('.assistant-more')?.click();
    await settle();
    const lines = [...view.host.querySelectorAll<HTMLButtonElement>('.assistant-line')];
    assert(lines.length === 4 && top(view.host) === null, 'View all lists every card in the group');
    lines[1].click();
    await settle();
    assert(top(view.host)?.textContent?.includes('Finished task 5') === true, `A row comes back as the top card: ${top(view.host)?.textContent}`);
    const pill = view.host.querySelector<HTMLElement>('.assistant-pill.is-selected');
    pill?.focus();
    await key(pill ?? input, 'a');
    assert(document.activeElement === input, 'Typing on a pill goes back to the composer');
    view.host.querySelector<HTMLButtonElement>('.assistant-pill[data-group="running"]')?.click();
    await settle();
    [...(top(view.host)?.querySelectorAll<HTMLButtonElement>('.assistant-button') ?? [])].find(button => button.textContent === 'Open task')?.click();
    assert(window.location.hash === workspaceReviewTaskRoute('ws1', 'r7') && view.calls.closed === 1, `Open task goes to the running task: ${window.location.hash}`);
  }
  finally { view.dispose(); }
}

async function opensAtACardFromItsAlert(): Promise<void> {
  const rows = Array.from({ length: 4 }, (_, index) => ({ ...notice(`finished:turn-${index + 3}`, 'TURN_FINISHED', `task-${index + 3}`, `Finished task ${index + 3}`),
    occurredAt: '2026-10-16T07:30:00Z' }));
  const view = mount(VIEW, 0, [NOTICES[0], ...rows]);
  try {
    await settle();
    view.render(false);
    view.render(true, { id: 'approval:a1' });
    await settle();
    const card = top(view.host);
    assert(card?.dataset.card === 'approval:a1' && document.activeElement === card, `The asked-for card is on top and focused, not one of its buttons: ${document.activeElement?.outerHTML.slice(0, 80)}`);
    const accent = document.createElement('span');
    accent.style.color = 'var(--accent)';
    view.host.append(accent);
    const style = getComputedStyle(card);
    assert(card.classList.contains('is-opened') && style.outlineStyle === 'solid' && style.outlineWidth === '2px' && style.outlineColor === getComputedStyle(accent).color,
      `The card gets a 2px accent outline: ${style.outlineStyle} ${style.outlineWidth} ${style.outlineColor}`);
    assert(style.animationName === 'assistant-opened' && style.animationDelay === '2s' && style.animationDuration === '0.4s', 'The outline holds for 2 s, then fades');
    assert(card.querySelector('button[aria-label="Allow once: Run the focused Maven test"]') !== null, 'The card shows the task’s own buttons');
    await new Promise(resolve => setTimeout(resolve, 2500));
    await settle();
    assert(!card.classList.contains('is-opened') && document.activeElement === card, 'The outline is gone after it fades, and focus stays on the card');
    view.render(false);
    view.render(true);
    await settle();
    assert(top(view.host)?.dataset.card !== 'approval:a1', 'Opening again with ⌘J does not go back to that card');
  }
  finally { view.dispose(); }
}

async function showsAListAsRowsCardsOrATimeline(): Promise<void> {
  const view = mount(listView('ROWS'), 0, []);
  try {
    await settle();
    enter(type(view.host, 'What is waiting on me?'));
    await settle();
    const list = view.host.querySelector<HTMLElement>('.assistant-talk .assistant-items');
    assert(list !== null, 'The list shows in the conversation');
    assert(list.querySelector('.assistant-items__title')?.textContent === 'Waiting on you' && list.querySelector('.assistant-items__count')?.textContent === '6',
      'Its header names it and counts what ByteQuay still holds');
    const switches = [...list.querySelectorAll<HTMLButtonElement>('.assistant-items__head .card-iconbtn')];
    const pressed = () => switches.map(button => button.getAttribute('aria-pressed'));
    assert(JSON.stringify(switches.map(button => button.getAttribute('aria-label'))) === '["Show as rows","Show as cards","Show as timeline"]', 'Three style switches');
    assert(JSON.stringify(pressed()) === '["true","false","false"]', 'The assistant picked rows');
    assert(switches.every(button => getComputedStyle(button).borderStyle === 'none'), 'The switches are borderless icon buttons');

    const lines = [...list.querySelectorAll<HTMLElement>('.assistant-row')];
    assert(lines.length === 7 && lines.every(line => line.getBoundingClientRect().height === 32), `Rows are one line each: ${lines.map(line => line.getBoundingClientRect().height)}`);
    assert(lines[0].getAttribute('title') === 'trinodb/trino #31377 · Add Iceberg metadata cache', `A row's tooltip says where it lives: ${lines[0].getAttribute('title')}`);
    const [mine, red, task, ticket, work, epic, gone] = lines;
    assert(mine.textContent?.includes('#31377') === true && mine.querySelector('[title="Checks passing"]') !== null
      && mine.querySelector('[title="2 approvals"]')?.textContent === '2', `A pull request shows its checks and approvals: ${mine.textContent}`);
    assert(mine.querySelector('[title="Review decision: approved"]') !== null, 'And its review decision');
    assert(red.querySelector('[title="Checks failing"]') !== null && red.querySelector('[title="Review decision: changes requested"]') !== null, 'Red checks and changes requested');
    assert(task.querySelector('[title="Agent: Claude Code"]') !== null && task.querySelector('[title="Waiting for your answer"] .assistant-dot') !== null, 'A task shows its agent and phase');
    assert(ticket.querySelector('.card-key')?.textContent === 'ICE-7' && ticket.querySelector('[title="Status: In progress"]')?.textContent === 'In progress',
      'A ticket shows its key and status');
    assert(work.querySelector('[title="Epic: Iceberg write path"]') !== null && work.querySelector('.pill') === null, 'A work item shows its Epic');
    assert(epic.querySelector('[title="3 of 6 done"]')?.textContent === '3/6', 'An Epic shows done of total');
    assert(gone.classList.contains('is-gone') && gone.textContent === '#1Gone' && gone.getAttribute('role') === null, `A ref that is gone draws as gone: ${gone.textContent}`);
    const bold = () => [...list.querySelectorAll('*')].filter(node => Number(getComputedStyle(node).fontWeight) > 400).map(node => node.className);
    assert(bold().length === 0, `Nothing in a list is bold: ${bold().join(', ')}`);

    switches[1].click();
    await settle();
    assert(JSON.stringify(pressed()) === '["false","true","false"]', 'The switch moves to cards');
    const cards = [...list.querySelectorAll<HTMLElement>('.assistant-item-card')];
    assert(cards.length === 6 && cards.every(card => card.classList.contains('card') && card.getAttribute('role') === 'button'), 'Each item is a card of the shared base');
    assert(list.querySelectorAll('.assistant-row.is-gone').length === 1, 'The gone ref stays a line among the cards');
    const strips = cards.map(card => card.querySelector('.assistant-item-card__strip .pill')?.textContent ?? null);
    assert(JSON.stringify(strips) === '["Approved","Changes requested","Waiting","In progress","Planned",null]', `Where each stands is in the strip: ${JSON.stringify(strips)}`);
    const types = cards.map(card => card.querySelector('.assistant-item-card__strip')?.textContent ?? '').filter(text => /Task|Jira|Work item|Epic/.test(text));
    assert(types.length === 0, `A strip names no type: ${JSON.stringify(types)}`);
    assert(cards[2].querySelector('.assistant-item-card__lane')?.textContent === 'Claude Code'
      && cards[2].querySelector('.assistant-item-card__latest')?.textContent === 'Which table should hold the snapshot id?',
      `A task's strip names its agent, so its message needs no name, and its Markdown reads as plain text: ${cards[2].querySelector('.assistant-item-card__latest')?.textContent}`);
    assert(cards[3].querySelector('.assistant-item-card__body .pill') === null && cards[3].querySelector('.assistant-item-card__foot')?.textContent?.includes('In progress') === false,
      'Below the strip, a ticket does not repeat its status');
    assert(cards[5].textContent?.split('3/6').length === 2 && cards[5].querySelector('.assistant-item-card__foot [title="3 of 6 done"]') !== null, 'An Epic says done of total once');
    const shown = [...cards[0].querySelectorAll<HTMLElement>('.assistant-item-card__foot button')];
    assert(shown.length === 3 && shown.every(button => getComputedStyle(button).display !== 'none' && getComputedStyle(button).borderTopColor === 'rgba(0, 0, 0, 0)')
      && cards[0].querySelector('.assistant-item-card__strip .card-age') !== null, 'A card\'s buttons always show, borderless, and its age moves to the strip');
    assert(bold().length === 0, `Nothing in a card is bold: ${bold().join(', ')}`);

    switches[2].click();
    await settle();
    const dates = [...list.querySelectorAll('.assistant-items__date')].map(date => date.textContent);
    const expected = [...new Set(['pr:trinodb/trino#31377', 'task:t1', 'pr:trinodb/trino#31402', 'jira:cloud:10042', 'work:w22'].map(ref => dayLabel(localDay(ROWS[ref].at))))];
    assert(JSON.stringify(dates) === JSON.stringify(expected), `Days head the timeline, newest first: ${JSON.stringify(dates)} != ${JSON.stringify(expected)}`);
    const timed = [...list.querySelectorAll<HTMLElement>('.assistant-row')];
    assert(timed[0].textContent?.includes('Add Iceberg metadata cache') === true && timed[1].textContent?.includes('Expose snapshot id') === true, 'The newest comes first');
    assert(/^\d{1,2}:\d{2}$/.test(timed[0].firstElementChild?.textContent ?? '') && timed[0].firstElementChild?.className === 'assistant-row__time', 'Its time is on the left');
    const last = [...list.querySelectorAll<HTMLElement>('.assistant-items__day')].at(-1);
    assert(last?.querySelector('.assistant-items__date') === null && last.querySelectorAll('.assistant-row').length === 2, 'What has no time comes last, without a day');

    const opened: unknown[] = [];
    const listener = (event: Event) => { opened.push((event as CustomEvent).detail); };
    window.addEventListener('bytequay:zoom-reference', listener);
    try { timed[0].click(); }
    finally { window.removeEventListener('bytequay:zoom-reference', listener); }
    assert(JSON.stringify(opened) === JSON.stringify([{ kind: 'pull', repo: 'trinodb/trino', number: 31377, workspaceId: 'ws1' }]), `A row opens its item: ${JSON.stringify(opened)}`);
    assert(view.calls.closed === 1 && view.host.querySelector('.assistant-float') === null, 'Opening an item closes the Assistant');
  }
  finally { view.dispose(); }
}

const opened = (id: string, at: string, page: string | null, item: AgendaTargetDto | null, words: string): AssistantItemDto => ({
  id, day: '2026-10-16', at, kind: 'RECEIPT', text: page === null ? 'Opened “Add Iceberg metadata cache”' : 'Opened Settings · Models', targets: {}, notReady: false, list: null, draft: null,
  receipt: { operation: 'OPEN', outcome: 'DONE', detail: null, target: item, page, turnId: null, fix: null, words },
});

async function followsAnOpenOnceWhenItArrives(): Promise<void> {
  // An open from an earlier conversation, read when the panel opens, only offers Open.
  const earlier = { ...VIEW, items: [...VIEW.items, opened('o1', '2026-10-16T07:09:00Z', null, PULL, 'open it')] };
  const view = mount(earlier, 0, [], [], () => ({ ...earlier, items: [...earlier.items, opened('o2', new Date(Date.now() + 1000).toISOString(), 'settings/models', null,
    'take me to model settings')] }));
  try {
    window.location.hash = '#/home';
    await settle();
    assert(view.calls.closed === 0 && window.location.hash === '#/home', 'An open read when the panel opens is not followed');
    enter(type(view.host, 'take me to model settings'));
    await settle();
    assert(window.location.hash === settingsRoute('models') && view.calls.closed === 1, `An open that arrives while the panel is open goes there: ${window.location.hash}`);

    window.location.hash = '#/home';
    view.render(true);
    await settle();
    enter(type(view.host, 'and now?'));
    await settle();
    assert(window.location.hash === '#/home' && view.calls.closed === 1, 'Read again later, the same open only offers Open');
    const actions = [...view.host.querySelectorAll<HTMLElement>('.assistant-receipt')].map(receipt => receipt.textContent);
    assert(actions.filter(text => text?.startsWith('Opened') === true && text.endsWith('Open')).length === 2, `Each open keeps its Open: ${JSON.stringify(actions)}`);
    [...view.host.querySelectorAll<HTMLElement>('.assistant-receipt')].find(receipt => receipt.textContent?.startsWith('Opened Settings') === true)
      ?.querySelector<HTMLButtonElement>('.assistant-action')?.click();
    assert(window.location.hash === settingsRoute('models') && view.calls.closed === 2, 'Open goes to the page and closes the Assistant');
  }
  finally {
    window.location.hash = '';
    view.dispose();
  }
}

const taskRow = (id: string, title: string, state: string): AssistantRowDto => row({ ref: `task:${id}`, kind: 'TASK', title, target: target('TASK', { id }),
  agent: 'Claude Code', state, at: ago(5), latest: { by: 'Claude Code', text: 'The snapshot id is in $snapshots now.', at: ago(5) } });
const taskView = (first: string): AssistantViewDto => {
  const rows = [taskRow('t1', 'Expose snapshot id in metadata', first), taskRow('t2', 'Retry Iceberg commit on conflict', 'running'),
    taskRow('t3', 'Drop the old cache key', 'completed')];
  const list: AssistantItemDto = { id: 'l2', day: '2026-10-16', at: '2026-10-16T07:06:10Z', kind: 'LIST', text: 'Your tasks', targets: {}, notReady: false, receipt: null,
    list: { style: 'CARDS', refs: rows.map(each => each.ref) }, draft: null };
  return { ...VIEW, items: [VIEW.items[0], list], rows: Object.fromEntries(rows.map(each => [each.ref, each])) };
};

async function completesATaskFromItsCard(): Promise<void> {
  const view = mount(taskView('idle'), 0, [], [], () => taskView('idle'), () => taskView('completed'));
  try {
    await settle();
    enter(type(view.host, 'close the snapshot task'));
    await settle();
    const cards = () => [...view.host.querySelectorAll<HTMLElement>('.assistant-item-card')];
    const complete = (card: HTMLElement) => card.querySelector<HTMLButtonElement>('.assistant-item-card__foot button');
    const [idle, running, ended] = cards();
    const button = complete(idle);
    assert(button !== null && !button.disabled && button.textContent === 'Complete task' && button.getAttribute('aria-label') === 'Complete task “Expose snapshot id in metadata”',
      `An idle task's card has its own Complete task: ${button?.outerHTML.slice(0, 120)}`);
    assert(complete(running)?.disabled === true && complete(running)?.title === 'Available when the task is idle', 'A working task cannot be completed yet');
    assert(complete(ended) === null && ended.querySelector('.assistant-item-card__foot .card-age') !== null, 'An ended task has nothing to complete, only its age');
    assert(getComputedStyle(button).display !== 'none', 'Its button always shows');

    const hash = window.location.hash;
    button.click();
    await settle();
    assert(JSON.stringify(view.calls.completed) === '[["t1","ws1"]]', `The click completes that task: ${JSON.stringify(view.calls.completed)}`);
    assert(view.calls.closed === 0 && window.location.hash === hash, 'Its button never opens the card it sits on');
    const strip = cards()[0].querySelector('.assistant-item-card__strip .pill')?.textContent;
    assert(strip === 'Completed' && complete(cards()[0]) === null, `The list reads the task again and shows it completed: ${strip}`);
  }
  finally { view.dispose(); }

  const refused = mount(taskView('idle'), 0, [], [], () => taskView('idle'), () => {
    throw new Error("Error invoking remote method 'agent:tasks:complete': Error: Run or remove every waiting queued message first");
  });
  try {
    await settle();
    enter(type(refused.host, 'close it'));
    await settle();
    refused.host.querySelector<HTMLButtonElement>('.assistant-item-card .assistant-item-card__foot button')?.click();
    await settle();
    const refusal = refused.host.querySelector<HTMLElement>('.assistant-item-card__refusal');
    assert(refusal?.querySelector('span')?.textContent === 'Run or remove every waiting queued message first' && refused.host.querySelector('.assistant-error') === null,
      `A refused completion says why on its card: ${refusal?.textContent}`);
    assert(refusal.querySelector('button')?.textContent === 'Open' && refused.host.querySelector('.assistant-item-card')?.querySelector('.assistant-item-card__foot') === null,
      'In place of its button, with the way to the task');
  }
  finally { refused.dispose(); }
}

const draftView = (created: boolean): AssistantViewDto => {
  const draft: AssistantItemDto = { id: 'd1', day: '2026-10-16', at: '2026-10-16T07:06:10Z', kind: 'DRAFT', text: 'Iceberg snapshot metadata', targets: {}, notReady: false,
    receipt: null, list: null, draft: { workspaceId: 'ws1', repository: 'trinodb/trino', body: 'Track the work that exposes snapshot ids.', workItemId: 'w-new' } };
  const receipt: AssistantItemDto = { id: 'c9', day: '2026-10-16', at: '2026-10-16T07:07:00Z', kind: 'RECEIPT', text: 'Created “Iceberg snapshot ids” as an Epic',
    targets: {}, notReady: false, list: null, draft: null,
    receipt: { operation: 'CREATE_EPIC', outcome: 'DONE', detail: 'trinodb/trino#77', target: target('WORK', { id: 'w-new' }), page: null, turnId: null, fix: null, words: null } };
  return { ...VIEW, items: created ? [VIEW.items[0], draft, receipt] : [VIEW.items[0], draft] };
};

async function createsADraftedIssueAsAnEpic(): Promise<void> {
  const steps: unknown[][] = [];
  let created = false;
  let made: WorkItemDto | null = null;
  let publishFails = 1;
  const view = mount(draftView(false), 0, [], [], () => draftView(created), () => draftView(created), {
    getAssistantToday: async () => draftView(created),
    listWorkItems: async () => { steps.push(['list']); return made === null ? [] : [made]; },
    createWorkItem: async (...args: unknown[]) => {
      steps.push(['create', ...args]);
      made = { id: 'w-new', revision: 1, links: [] } as unknown as WorkItemDto;
      return made;
    },
    setLocalWorkItemEpic: async (...args: unknown[]) => { steps.push(['epic', ...args]); },
    publishWorkItem: async (...args: unknown[]) => {
      steps.push(['publish', ...args]);
      if (publishFails-- > 0) throw new Error("Error invoking remote method 'work-items:publish': Error: GitHub's rate limit was reached; try again in a minute");
      return { id: 'w-new', revision: 2, links: [{ kind: 'ISSUE', key: 'trinodb/trino#77' }] } as unknown as WorkItemDto;
    },
    recordAssistantDraft: async (id: string) => { steps.push(['drafted', id]); created = true; },
  });
  try {
    await settle();
    enter(type(view.host, 'make a new issue for the snapshot work, as an epic'));
    await settle();
    const form = view.host.querySelector<HTMLFormElement>('.assistant-talk form.assistant-draft');
    const title = form?.querySelector<HTMLInputElement>('.assistant-draft__title');
    const body = form?.querySelector<HTMLTextAreaElement>('.assistant-draft__body');
    assert(form !== null && title?.value === 'Iceberg snapshot metadata' && body?.value === 'Track the work that exposes snapshot ids.'
      && form.querySelector('.assistant-draft__repo')?.textContent === 'trinodb/trino', 'The draft shows what the assistant wrote, for the repository it goes to');
    assert(steps.length === 0, 'Nothing is created until the user clicks');
    changeInput(title, 'Iceberg snapshot ids');
    await settle();
    const button = () => view.host.querySelector<HTMLButtonElement>('.assistant-draft button[type="submit"]');
    assert(button()?.textContent === 'Create issue', 'Its one button creates the issue');
    button()?.click();
    await settle();
    assert(view.host.querySelector('.assistant-draft__error')?.textContent === "GitHub's rate limit was reached; try again in a minute",
      'A failed step says why, and the draft stays');
    assert(JSON.stringify(steps.map(step => step[0])) === '["list","create","epic","publish"]', `The click made the work and its Epic before the push failed: ${JSON.stringify(steps)}`);
    const [, workspace, request] = steps[1] as [string, string, Record<string, unknown>];
    assert(workspace === 'ws1' && request.id === 'w-new' && request.title === 'Iceberg snapshot ids' && request.acceptanceCriteria === 'Track the work that exposes snapshot ids.',
      `The work takes the user's edits and the draft's id: ${JSON.stringify(request)}`);
    assert(JSON.stringify(steps[3]) === '["publish","ws1","w-new",{"source":"GITHUB","expectedRevision":1}]', `It is pushed to GitHub as an issue: ${JSON.stringify(steps[3])}`);
    assert(view.host.querySelector<HTMLInputElement>('.assistant-draft__title')?.disabled === true
      && view.host.querySelector('.assistant-draft__note')?.textContent === 'Saved as a local Epic' && view.host.querySelectorAll('.assistant-draft__lock').length === 2,
      'Once made, the work keeps the words it was made with, and its fields say so');

    const opened: unknown[] = [];
    const listener = (event: Event) => { opened.push((event as CustomEvent).detail); };
    window.addEventListener('bytequay:zoom-reference', listener);
    try { [...view.host.querySelectorAll<HTMLButtonElement>('.assistant-draft button')].find(each => each.textContent === 'Open work item')?.click(); }
    finally { window.removeEventListener('bytequay:zoom-reference', listener); }
    assert(JSON.stringify(opened) === '[{"kind":"work","workspaceId":"ws1","itemId":"w-new"}]' && view.calls.closed === 1,
      `A failed push can be finished from the work item's page: ${JSON.stringify(opened)}`);

    // Opened again, the card has forgotten its click, and trying again still finds the work it made.
    view.render(true);
    await settle();
    enter(type(view.host, 'try it again'));
    await settle();
    button()?.click();
    await settle();
    assert(JSON.stringify(steps.slice(4).map(step => step[0])) === '["list","epic","publish","drafted"]' && JSON.stringify(steps.at(-1)) === '["drafted","d1"]',
      `Trying again finishes the push and keeps a receipt: ${JSON.stringify(steps.slice(4))}`);
    assert(view.host.querySelector('.assistant-draft') === null, 'Once created, the draft gives way to its receipt');
    assert(view.host.querySelector('.assistant-receipt')?.textContent?.startsWith('Created “Iceberg snapshot ids” as an Epic') === true, 'The receipt names the Epic it became');
  }
  finally { view.dispose(); }
}

async function readsTheMarksTheAssistantMade(): Promise<void> {
  let reads = 0;
  const acknowledged: AssistantItemDto = { id: 'c8', day: '2026-10-16', at: '2026-10-16T07:05:00Z', kind: 'RECEIPT', text: 'Acknowledged “Add Iceberg metadata cache”',
    targets: {}, notReady: false, list: null, draft: null,
    receipt: { operation: 'ACKNOWLEDGE', outcome: 'DONE', detail: 'Back when something new happens', target: PULL, page: null, turnId: null, fix: null, words: 'clear it' } };
  const view = mount({ ...VIEW, items: [VIEW.items[0], acknowledged] }, 0, [], [], undefined, undefined, {
    getAttentionMarks: async () => { reads++; return []; },
    setPrAttentionHandled: async () => {},
  });
  try {
    await settle();
    assert(reads === 1, `A mark the assistant made reaches the lists when its receipt arrives: ${reads} reads`);
  }
  finally { view.dispose(); }
}

async function actsOnAPullRequestFromItsCard(): Promise<void> {
  const steps: unknown[][] = [];
  let ready = false;
  let unsettled = true;
  let release = () => {};
  const draft: ReviewDraftDto = { repo: 'trinodb/trino', number: 31377, body: '', event: 'COMMENT', reviewPublication: null, items: [], postedSummaryFindingIds: [],
    version: 3, updatedAt: '2026-10-16T07:00:00Z' };
  // Someone else's pull request the user already approved.
  const approved = row({ ref: 'pr:example/catalogs#4127', kind: 'PULL', title: 'Retry catalog loads after a timeout', key: '#4127', state: 'open',
    target: target('PULL', { repo: 'example/catalogs', number: 4127 }), review: 'approved', approvals: 1, youApproved: true, at: ago(60) });
  const view = mount(listView('CARDS', [approved]), 0, [], [], undefined, undefined, {
    findStoredPullRequest: async (repo: string, number: number) => pr({ id: number, repo, number }),
    getReviewDraft: async () => unsettled
      ? { ...draft, reviewPublication: { state: 'UNCERTAIN', remoteReviewId: null, remoteCommentId: null, url: null, at: '2026-10-16T06:00:00Z' } } : draft,
    saveReviewDraft: async (...args: unknown[]) => { steps.push(['save', ...args]); return { ...draft, version: 4, event: 'APPROVE' }; },
    submitReviewDraft: async (...args: unknown[]) => { steps.push(['submit', ...args]); },
    refreshPullRequestDetailByRef: async () => { steps.push(['refresh']); return { full: true, detail: prDetail({}), status: null }; },
    fetchPullRequestDetailByRef: async (repo: string, number: number) => prDetail({ repo, number, viewerCanWrite: ready, headSha: 'abc123' }),
    fetchMergeMode: async () => ({ mergeQueue: false }),
    mergePr: async (...args: unknown[]) => {
      steps.push(['merge', ...args]);
      await new Promise<void>(resolve => { release = () => resolve(); });
    },
    closePr: async (...args: unknown[]) => { steps.push(['close', ...args]); },
  });
  try {
    await settle();
    enter(type(view.host, 'show my pull requests as cards'));
    await settle();
    const card = (key: string) => [...view.host.querySelectorAll<HTMLElement>('.assistant-item-card')].find(each => each.querySelector('.card-key')?.textContent === key);
    const buttons = (key: string) => [...(card(key)?.querySelectorAll<HTMLButtonElement>('.assistant-item-card__foot button') ?? [])].map(button => button.getAttribute('aria-label'));
    assert(JSON.stringify(buttons('#31377')) === '["Approve #31377","Merge #31377","Comment on #31377"]', `Someone else's pull request: ${JSON.stringify(buttons('#31377'))}`);
    assert(JSON.stringify(buttons('#31402')) === '["Merge #31402","Comment on #31402","Close #31402"]', `The user's own: ${JSON.stringify(buttons('#31402'))}`);
    assert(JSON.stringify(buttons('#4127')) === '["Merge #4127","Comment on #4127"]', `One the user approved: ${JSON.stringify(buttons('#4127'))}`);
    const press = (label: string) => view.host.querySelector<HTMLButtonElement>(`.assistant-item-card__foot button[aria-label="${label}"]`)?.click();
    const refusal = (key: string) => card(key)?.querySelector('.assistant-item-card__refusal span')?.textContent;
    // Opened again, a card has forgotten what refused it.
    const reopen = async () => {
      view.render(false);
      view.render(true);
      await settle();
      enter(type(view.host, 'and again'));
      await settle();
    };

    press('Approve #31377');
    await settle();
    assert(refusal('#31377') === 'A review you sent has no answer yet. Open the pull request to settle it first.' && steps.length === 0,
      'A review whose outcome is unknown is settled on its page before another goes out');
    await reopen();
    unsettled = false;
    press('Approve #31377');
    await settle();
    assert(JSON.stringify(steps) === '[["save","trinodb/trino",31377,3,"","APPROVE"],["submit","trinodb/trino",31377,4,"","APPROVE","abc123"]]',
      `Approve goes through the review draft, as the review sheet does: ${JSON.stringify(steps)}`);
    assert(view.calls.closed === 0, 'A card’s button never opens the card it sits on');

    steps.length = 0;
    press('Merge #31377');
    await settle();
    assert(refusal('#31377') === 'Not ready to merge. Open the pull request to see why.' && JSON.stringify(steps) === '[["refresh"]]',
      `The merge sheet's own check refuses, after reading GitHub again: ${JSON.stringify(steps)}`);
    await reopen();
    ready = true;
    press('Merge #31377');
    await settle();
    const merging = [...(card('#31377')?.querySelectorAll<HTMLButtonElement>('.assistant-item-card__foot button') ?? [])];
    assert(merging.some(button => button.textContent === 'Merging…') && merging.every(button => button.disabled), 'While it merges, the button says so and none can be pressed');
    release();
    await settle();
    assert(JSON.stringify(steps.at(-1)) === '["merge",31377,"trinodb/trino",31377,"rebase","abc123"]' && refusal('#31377') === undefined,
      `Once ready, it merges only the head commit the check saw: ${JSON.stringify(steps.at(-1))}`);

    press('Close #31402');
    await settle();
    assert(JSON.stringify(steps.at(-1)) === '["close","trinodb/trino",31402]', `Close closes the user's own pull request: ${JSON.stringify(steps.at(-1))}`);

    const opened: unknown[] = [];
    const listener = (event: Event) => { opened.push((event as CustomEvent).detail); };
    window.addEventListener('bytequay:zoom-reference', listener);
    try { press('Comment on #31377'); }
    finally { window.removeEventListener('bytequay:zoom-reference', listener); }
    assert(JSON.stringify(opened) === '[{"kind":"pull","repo":"trinodb/trino","number":31377,"workspaceId":"ws1"}]' && view.calls.closed === 1,
      `Comment opens the pull request over the page: ${JSON.stringify(opened)}`);
  }
  finally { view.dispose(); }
}

const signIn: AssistantItemDto = { id: 'f1', day: '2026-10-16', at: '2026-10-16T07:06:30Z', kind: 'FAILURE', text: 'Claude sign-in required', targets: {},
  notReady: true, receipt: null, list: null, draft: null };

async function asksToSignInWhenTheModelIsNotReady(): Promise<void> {
  const view = mount({ ...VIEW, items: [VIEW.items[0], signIn] }, 0, []);
  try {
    await settle();
    enter(type(view.host, 'What should I look at first?'));
    await settle();
    const line = view.host.querySelector<HTMLElement>('.assistant-failure');
    const buttons = [...(line?.querySelectorAll<HTMLButtonElement>('.assistant-action') ?? [])].map(button => button.textContent);
    assert(line?.textContent?.startsWith('Claude sign-in required') === true && JSON.stringify(buttons) === '["Open Settings","Retry"]',
      `The reply says the model needs a sign-in, and offers Settings and Retry: ${line?.textContent}`);
    assert(line.title === 'Claude Sonnet answers through your Claude account. Sign in from Settings → Models.', `It says where to sign in: ${line.title}`);
    line.querySelector<HTMLButtonElement>('.assistant-action')?.click();
    assert(window.location.hash === settingsRoute('models') && view.calls.closed === 1, `Open Settings goes to Models: ${window.location.hash}`);
  }
  finally {
    window.location.hash = '';
    view.dispose();
  }
}

async function stopsARunningReply(): Promise<void> {
  const view = mount({ ...VIEW, running: true });
  try {
    await settle();
    assert(view.host.querySelector('.assistant-talk .assistant-thinking') !== null, 'A running reply shows the conversation with Thinking');
    const light = view.host.querySelector('.assistant-float.is-replying .agt-compose__streamer');
    assert(light !== null && getComputedStyle(light).animationDuration === '4s', 'The light speeds up while it replies');
    assert(view.host.querySelector('button[aria-label="Send to the Assistant"]') === null, 'Stop replaces send');
    view.host.querySelector<HTMLButtonElement>('button[aria-label="Stop the reply"]')?.click();
    await settle();
    assert(view.calls.stopped === 1, 'Stop reaches the backend');
  }
  finally { view.dispose(); }
}

async function keepsTheSetUpBoxWholeWithChatOff(): Promise<void> {
  // Newer than the panel's last close, so the conversation unfolds at once and fills the window.
  const soon = new Date(Date.now() + 60_000).toISOString();
  const long = { ...VIEW, items: Array.from({ length: 24 }, (_, index) => ({ ...VIEW.items[0], id: `u${index}`, at: soon })) };
  const view = mount(long, 0, [], [], undefined, undefined, {}, false);
  try {
    await settle();
    const box = view.host.querySelector<HTMLElement>('.assistant-off');
    const link = box?.querySelector<HTMLElement>('.assistant-link');
    assert(box !== null && link?.textContent === 'Set up Assistant in Settings' && view.host.querySelector('.assistant-talk') !== null,
      'With chat off, the set-up box takes the composer’s place above the conversation');
    const outer = box.getBoundingClientRect();
    assert(box.scrollHeight <= box.clientHeight && link.getBoundingClientRect().bottom <= outer.bottom - 12,
      `A long conversation leaves the set-up box whole: ${JSON.stringify({ box: outer.height, content: box.scrollHeight, link: link.getBoundingClientRect().bottom })}`);
  }
  finally { view.dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['floats over the page', floatsOverThePage], ['shows the day once the user talks', showsTheDayOnceTheUserTalks],
    ['answers with the task’s buttons', answersWithTheTasksButtons], ['sends with what is shown', sendsWithWhatIsShown],
    ['moves with the keyboard', movesWithTheKeyboard], ['opens at a card from its alert', opensAtACardFromItsAlert],
    ['stops a running reply', stopsARunningReply], ['shows a list as rows, cards or a timeline', showsAListAsRowsCardsOrATimeline],
    ['follows an open once when it arrives', followsAnOpenOnceWhenItArrives], ['completes a task from its card', completesATaskFromItsCard],
    ['creates a drafted issue as an Epic', createsADraftedIssueAsAnEpic], ['reads the marks the assistant made', readsTheMarksTheAssistantMade],
    ['acts on a pull request from its card', actsOnAPullRequestFromItsCard], ['asks to sign in when the model is not ready', asksToSignInWhenTheModelIsNotReady],
    ['keeps the set-up box whole with chat off', keepsTheSetUpBoxWholeWithChatOff]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

const receiptsView = (): AssistantViewDto => {
  const receipt = (id: string, text: string, fields: Partial<AssistantReceiptDto> & Pick<AssistantReceiptDto, 'operation'>): AssistantItemDto => ({
    id, day: '2026-10-16', at: '2026-10-16T07:07:00Z', kind: 'RECEIPT', text, targets: {}, notReady: false, list: null, draft: null,
    receipt: { outcome: 'DONE', detail: null, target: PULL, page: null, turnId: null, fix: null, words: null, ...fields },
  });
  return { ...VIEW, items: [VIEW.items[0],
    receipt('e1', 'Acknowledged “Use remote splits generation for large manifests”', { operation: 'ACKNOWLEDGE', detail: 'Back when something new happens',
      words: 'Clear the cork PR from Needs attention' }),
    receipt('e2', 'Queued your words for “Retry Iceberg commit on conflict”', { operation: 'REPLY_TO_TASK', detail: 'After its current turn',
      words: 'Tell the retry task to keep the old key too' }),
    receipt('e3', 'Changing Epics and work items is off', { operation: 'MARK_EPIC', outcome: 'REFUSED', target: null, fix: 'SETTINGS', words: 'make ICE-7 an Epic' }),
    receipt('e4', 'That isn’t in Needs attention', { operation: 'ACKNOWLEDGE', outcome: 'FAILED', target: null, words: 'clear ICE-7 from Needs attention' }),
    signIn] };
};

let preview: { dispose: () => void } | null = null;

/**
 * Draws a list the assistant showed in one style, for a screenshot; `tasks` is a list of task cards with an idle one focused, `draft` a
 * drafted issue, and `receipts` what actions did and refused, ending with a reply that needs a sign-in.
 */
export async function showListPreview(style: AssistantListStyle | 'tasks' | 'draft' | 'receipts'): Promise<void> {
  preview?.dispose();
  document.body.style.margin = '0';
  document.body.style.background = 'var(--bg)';
  const view = style === 'tasks' ? taskView('idle') : style === 'draft' ? draftView(false) : style === 'receipts' ? receiptsView() : listView(style);
  const mounted = mount(view, 0, []);
  preview = mounted;
  await new Promise(resolve => setTimeout(resolve, 150));
  enter(type(mounted.host, 'What is waiting on me?'));
  await new Promise(resolve => setTimeout(resolve, 150));
  if (style === 'tasks') mounted.host.querySelector<HTMLElement>('.assistant-item-card')?.focus();
}

/** Draws the Assistant over a page for a screenshot: the day's conversation once the user talks, and an approval on top of a few cards. */
export async function showPreview(): Promise<void> {
  preview?.dispose();
  document.body.style.margin = '0';
  document.body.style.background = 'var(--bg)';
  const rows = Array.from({ length: 2 }, (_, index) => ({ ...notice(`finished:turn-${index + 3}`, 'TURN_FINISHED', `task-${index + 3}`, `Finished task ${index + 3}`),
    occurredAt: `2026-10-16T07:0${index}:00Z` }));
  const mounted = mount(VIEW, 0, [...NOTICES, ...rows], [{ ...RUNNING, startedAt: '2026-10-16T07:05:00Z' }]);
  preview = mounted;
  // A hidden window paints no frames, so a timer lets the reads and layout settle instead.
  await new Promise(resolve => setTimeout(resolve, 150));
  enter(type(mounted.host, 'What should I look at first?'));
  await new Promise(resolve => setTimeout(resolve, 150));
}
