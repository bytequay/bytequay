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
import '../../index.css';
import AssistantPanel from '../AssistantPanel';
import type { RunningTask } from '../../agents/runningTaskModel';
import type { AppNotice } from '../../notices/contracts';
import type { AgendaDto, AgendaTargetDto, AgentPendingApprovalDto, AssistantViewDto, Bridge } from '../../types';
import { workspaceAgentTaskRoute, workspaceReviewTaskRoute } from '../../workspaces/model';

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
    { id: 'u1', day: '2026-10-16', at: '2026-10-16T07:06:00Z', kind: 'USER', text: 'What should I look at first?', targets: {}, notReady: false, receipt: null },
    { id: 'r1', day: '2026-10-16', at: '2026-10-16T07:06:20Z', kind: 'REPLY', targets: { 'pr:trinodb/trino#31377': PULL, 'task:t1': target('TASK', { id: 't1' }) },
      text: 'Start with [#31377](pr:trinodb/trino#31377): it has waited 3 days and CI is green. Then answer elric-t on task:t1.', notReady: false, receipt: null },
    { id: 'c1', day: '2026-10-16', at: '2026-10-16T07:08:00Z', kind: 'RECEIPT', text: 'Started “Expose snapshot id in metadata”', targets: {}, notReady: false,
      receipt: { operation: 'START_TASK', outcome: 'DONE', detail: 'trinodb/trino · Claude Code Opus 5.5 · Auto draft PR', target: target('TASK', { id: 't9' }),
        turnId: 'turn-9', fix: null, words: 'start the snapshot id one' } },
    { id: 'c2', day: '2026-10-16', at: '2026-10-16T07:08:01Z', kind: 'RECEIPT', text: 'Use the buttons: this one runs outside the sandbox', targets: {}, notReady: false,
      receipt: { operation: 'ANSWER_APPROVAL', outcome: 'REFUSED', detail: null, target: null, turnId: null, fix: 'BUTTONS', words: 'allow it' } },
  ],
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
};

type Mounted = { host: HTMLElement; calls: Calls; render: (open: boolean, openCard?: { id: string }) => void; dispose: () => void };

function mount(view: AssistantViewDto = VIEW, failSends = 0, notices = NOTICES, running: RunningTask[] = []): Mounted {
  const calls: Calls = { sent: [], resolved: [], opened: [], removed: [], stopped: 0, closed: 0, cancelled: [], answered: [] };
  let failures = failSends;
  window.bridge = {
    getAssistantToday: async () => view,
    sendAssistantMessage: async (...args: unknown[]) => {
      calls.sent.push(args);
      if (failures-- > 0) throw new Error("Error invoking remote method 'assistant:send': Error: The backend is busy");
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
    openAppNotice: async (id: string) => { calls.opened.push(id); },
    removeAppNotice: async (id: string) => { calls.removed.push(id); },
  } as unknown as Bridge;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let shown = true;
  const render = (open: boolean, openCard?: { id: string }) => {
    shown = open;
    flushSync(() => root.render(
      <div className="browser-workspace">
        <div className="app-surfaces"><main style={{ height: '100vh', padding: 24, color: 'var(--fg-muted)' }}>The page under the Assistant</main></div>
        <AssistantPanel open={open} notices={notices} openCard={openCard ?? null} onScreen="pr:trinodb/trino#31377"
          onClose={() => { calls.closed++; if (shown) render(false); }} />
      </div>,
    ));
  };
  render(true);
  return { host, calls, render, dispose: () => { flushSync(() => root.unmount()); host.remove(); } };
}

function type(host: HTMLElement, text: string): HTMLTextAreaElement {
  const input = host.querySelector<HTMLTextAreaElement>('.assistant-float textarea');
  assert(input !== null, 'The composer must show');
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, text);
  input.dispatchEvent(new Event('input', { bubbles: true }));
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
    const order = [...talk.querySelectorAll('.agt-user, .assistant-reply, .assistant-receipts, .assistant-refusal')].map(node => node.className.split(' ')[0]);
    assert(JSON.stringify(order) === JSON.stringify(['agt-user', 'assistant-reply', 'assistant-receipts', 'assistant-refusal']), `The day in order: ${JSON.stringify(order)}`);
    assert(talk.querySelector('.assistant-refusal')?.textContent === 'Use the buttons: this one runs outside the sandbox “allow it” ↓',
      `A refusal shows the words it refused and points down at the card: ${talk.querySelector('.assistant-refusal')?.textContent}`);
    assert(/^\d{1,2}:\d{2}$/.test(talk.querySelector('.assistant-time')?.textContent ?? ''), 'A message starts with its time');
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
    [...view.host.querySelectorAll<HTMLButtonElement>('.assistant-receipt__action')].find(button => button.textContent === 'Open')?.click();
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

async function stopsARunningReply(): Promise<void> {
  const view = mount({ ...VIEW, running: true });
  try {
    await settle();
    assert(view.host.querySelector('.assistant-talk .assistant-thinking') !== null, 'A running reply shows the conversation with Thinking');
    assert(view.host.querySelector('.assistant-float.is-replying') !== null, 'The light speeds up while it replies');
    assert(view.host.querySelector('button[aria-label="Send to the Assistant"]') === null, 'Stop replaces send');
    view.host.querySelector<HTMLButtonElement>('button[aria-label="Stop the reply"]')?.click();
    await settle();
    assert(view.calls.stopped === 1, 'Stop reaches the backend');
  }
  finally { view.dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['floats over the page', floatsOverThePage], ['shows the day once the user talks', showsTheDayOnceTheUserTalks],
    ['answers with the task’s buttons', answersWithTheTasksButtons], ['sends with what is shown', sendsWithWhatIsShown],
    ['moves with the keyboard', movesWithTheKeyboard], ['opens at a card from its alert', opensAtACardFromItsAlert],
    ['stops a running reply', stopsARunningReply]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

let preview: { dispose: () => void } | null = null;

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
