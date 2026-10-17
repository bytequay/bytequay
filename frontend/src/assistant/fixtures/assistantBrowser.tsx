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
import type { CSSProperties } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import '../../index.css';
import AssistantPanel from '../AssistantPanel';
import type { AppNotice } from '../../notices/contracts';
import type { AgendaDto, AgendaTargetDto, AgentPendingApprovalDto, AssistantViewDto, Bridge } from '../../types';
import { workspaceAgentTaskRoute } from '../../workspaces/model';

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

type Calls = {
  sent: unknown[][]; resolved: unknown[][]; opened: string[]; removed: string[]; stopped: number; closed: number; shown: number; cancelled: string[]; answered: string[];
};

type Mounted = { host: HTMLElement; calls: Calls; render: (open: boolean, openCard?: { id: string }) => void; dispose: () => void };

function mount(view: AssistantViewDto = VIEW, failSends = 0, notices = NOTICES): Mounted {
  const calls: Calls = { sent: [], resolved: [], opened: [], removed: [], stopped: 0, closed: 0, shown: 0, cancelled: [], answered: [] };
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
  const render = (open: boolean, openCard?: { id: string }) => flushSync(() => root.render(
    <div className="browser-workspace" style={{ '--browser-rail-width': '0px', '--assistant-panel-width': '380px' } as CSSProperties}>
      <div className="app-surfaces"><main style={{ height: '100vh', padding: 24, color: 'var(--fg-muted)' }}>The page beside the panel</main></div>
      <AssistantPanel open={open} notices={notices} openCard={openCard ?? null} onScreen="pr:trinodb/trino#31377" onClose={() => { calls.closed++; }}
        onShowApp={() => { calls.shown++; }} onResizeStart={() => {}} />
    </div>,
  ));
  render(true);
  return { host, calls, render, dispose: () => { flushSync(() => root.unmount()); host.remove(); } };
}

function type(host: HTMLElement, text: string): HTMLTextAreaElement {
  const input = host.querySelector<HTMLTextAreaElement>('.assistant-panel textarea');
  assert(input !== null, 'The composer must show');
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, text);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  return input;
}

const enter = (input: HTMLElement) => input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));

async function showsTheDayInOrder(): Promise<void> {
  const view = mount();
  try {
    await settle();
    const panel = view.host.querySelector<HTMLElement>('.assistant-panel');
    assert(panel !== null && document.activeElement === panel.querySelector('textarea'), 'Opening must focus the composer');
    const box = panel.getBoundingClientRect();
    assert(box.width === 380 && Math.round(box.right) === window.innerWidth, `The panel docks 380px at the right edge: ${JSON.stringify(box)}`);
    const head = panel.querySelector<HTMLElement>('.assistant-head');
    const region = (node: Element) => getComputedStyle(node).getPropertyValue('-webkit-app-region');
    assert(head !== null && region(head) === 'drag', 'The header moves the window, as page headers do');
    assert([...head.querySelectorAll('button, [title]')].every(node => region(node) === 'no-drag'), 'Header buttons and the model tooltip stay reachable');
    const page = view.host.querySelector<HTMLElement>('.app-surfaces')?.getBoundingClientRect();
    assert(page !== undefined && Math.round(page.right) === window.innerWidth - 380, `The page must make room: ${JSON.stringify(page)}`);
    const order = [...panel.querySelectorAll('.today, .agt-user, .assistant-reply, .assistant-receipts, .assistant-refusal, .assistant-waiting')]
      .map(node => node.className.split(' ')[0]);
    assert(JSON.stringify(order) === JSON.stringify(['today', 'agt-user', 'assistant-reply', 'assistant-receipts', 'assistant-refusal', 'assistant-waiting']),
      `Agenda, conversation and receipts, then waiting cards: ${JSON.stringify(order)}`);
    assert(panel.querySelector('.assistant-refusal')?.textContent === 'Use the buttons: this one runs outside the sandbox “allow it” ↓',
      `A refusal shows the words it refused and points down at the card: ${panel.querySelector('.assistant-refusal')?.textContent}`);
    assert(/^\d{1,2}:\d{2}$/.test(panel.querySelector('.assistant-time')?.textContent ?? ''), 'A message starts with its time');
    const opened: unknown[] = [];
    const listener = (event: Event) => { opened.push((event as CustomEvent).detail); };
    window.addEventListener('bytequay:zoom-reference', listener);
    try { panel.querySelector<HTMLAnchorElement>('.assistant-reply a')?.click(); }
    finally { window.removeEventListener('bytequay:zoom-reference', listener); }
    assert(JSON.stringify(opened) === JSON.stringify([{ kind: 'pull', repo: 'trinodb/trino', number: 31377, workspaceId: 'ws1' }]), `A reply ref opens its pull request: ${JSON.stringify(opened)}`);
    assert(view.calls.shown === 1, 'Opening from the panel brings the app back first');
    const links = [...panel.querySelectorAll<HTMLAnchorElement>('.assistant-reply a')].map(link => link.textContent);
    assert(JSON.stringify(links) === JSON.stringify(['#31377', 'task']), `Both refs link, bare or not: ${JSON.stringify(links)}`);
    panel.querySelectorAll<HTMLAnchorElement>('.assistant-reply a')[1].click();
    assert(window.location.hash === workspaceAgentTaskRoute('ws1', 't1'), `A task ref opens the task: ${window.location.hash}`);
    const receipt = panel.querySelector<HTMLElement>('.assistant-receipt');
    assert(receipt?.querySelector('.assistant-receipt__detail')?.textContent === 'trinodb/trino · Claude Code Opus 5.5 · Auto draft PR', 'A start says where it runs');
    assert(receipt?.querySelector('.assistant-receipt__words')?.textContent === '“start the snapshot id one”', 'A receipt shows the exact words it acted on');
    const action = (name: string) => [...(receipt?.querySelectorAll<HTMLButtonElement>('button') ?? [])].find(button => button.textContent === name);
    action('Open')?.click();
    assert(window.location.hash === workspaceAgentTaskRoute('ws1', 't9'), `Open goes to the started task: ${window.location.hash}`);
    action('Stop')?.click();
    await settle();
    assert(JSON.stringify(view.calls.cancelled) === '["turn-9"]' && receipt?.textContent?.includes('Stopped') === true, 'Stop cancels the first turn');
  }
  finally { view.dispose(); }
}

async function answersWithTheTasksButtons(): Promise<void> {
  const view = mount();
  try {
    await settle();
    const allow = view.host.querySelector<HTMLButtonElement>('button[aria-label="Allow once: Run the focused Maven test"]');
    assert(allow !== null, 'The approval shows the task’s own buttons');
    allow.click();
    await settle();
    assert(JSON.stringify(view.calls.resolved) === JSON.stringify([['a1', 'ALLOW', null, null]]), `Allow once reaches the task: ${JSON.stringify(view.calls.resolved)}`);
    assert(JSON.stringify(view.calls.answered) === '["a1"]', 'The answer leaves a receipt in the conversation');
    assert(view.host.querySelector('.agt-appr') === null, 'An answered card leaves');
    const rows = [...view.host.querySelectorAll('.assistant-row')].map(row => row.textContent);
    assert(rows.length === 1 && rows[0]?.includes('Retry commit on conflict') === true, `Other notices stay: ${JSON.stringify(rows)}`);
    view.host.querySelector<HTMLButtonElement>('.assistant-row__open')?.click();
    view.host.querySelector<HTMLButtonElement>('.assistant-row button[aria-label="Clear “Retry commit on conflict”"]')?.click();
    await settle();
    assert(JSON.stringify(view.calls.opened) === '["finished:turn-2"]' && JSON.stringify(view.calls.removed) === '["finished:turn-2"]', 'Rows open and clear their notice');
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
      `A message carries the visible cards and what is on screen: ${JSON.stringify(first)}`);
    assert(second[3] === first[3], 'Sending the same words again reuses the key');
    assert(input.value === '' && view.host.querySelector('.assistant-error') === null, 'A sent message clears the draft');
    view.host.querySelector<HTMLButtonElement>('.assistant-chip button')?.click();
    await settle();
    assert(view.host.querySelector('.assistant-chip') === null, 'The chip can be left out');
    enter(type(view.host, 'and the next one?'));
    await settle();
    assert(view.calls.sent[2][2] === null && view.calls.sent[2][3] !== first[3], `A removed chip sends no screen, with a new key: ${JSON.stringify(view.calls.sent[2])}`);
    assert(view.host.querySelector('.assistant-chip') !== null, 'The chip comes back for the next message');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    assert(view.calls.closed === 1, 'Escape closes the panel');
  }
  finally { view.dispose(); }
}

async function opensAtACardFromItsAlert(): Promise<void> {
  // Enough rows after the approval that the end of the scroll leaves it out of view.
  const rows = Array.from({ length: 12 }, (_, index) => notice(`finished:turn-${index + 3}`, 'TURN_FINISHED', `task-${index + 3}`, `Finished task ${index + 3}`));
  const view = mount(VIEW, 0, [NOTICES[0], ...rows]);
  try {
    await settle();
    view.render(false);
    // The conversation grows after the panel opens, and that read scrolls it to the end.
    window.bridge.getAssistantToday = async () => ({ ...VIEW, items: [...VIEW.items, { ...VIEW.items[0], id: 'u2', text: 'And the next one?' }] });
    view.render(true, { id: 'approval:a1' });
    const card = view.host.querySelector<HTMLElement>('[data-notice="approval:a1"]');
    assert(card !== null && document.activeElement === card, `Focus goes to the card itself, not to one of its buttons: ${document.activeElement?.outerHTML.slice(0, 80)}`);
    const accent = document.createElement('span');
    accent.style.color = 'var(--accent)';
    view.host.append(accent);
    const style = getComputedStyle(card);
    assert(card.classList.contains('is-opened') && style.outlineStyle === 'solid' && style.outlineWidth === '2px' && style.outlineColor === getComputedStyle(accent).color,
      `The card gets a 2px accent outline: ${style.outlineStyle} ${style.outlineWidth} ${style.outlineColor}`);
    assert(style.animationName === 'assistant-opened' && style.animationDelay === '2s' && style.animationDuration === '0.4s', 'The outline holds for 2 s, then fades');
    await settle();
    await settle();
    assert(view.host.querySelectorAll('.agt-user').length === 2 && view.host.querySelector('.today') !== null, 'The conversation and the agenda arrived after the panel opened');
    const scroll = view.host.querySelector<HTMLElement>('.assistant-scroll');
    assert(scroll !== null && scroll.scrollTop > 0 && scroll.scrollTop + scroll.clientHeight < scroll.scrollHeight - 1,
      `The panel stops at the card, not at the end of the scroll: ${scroll?.scrollTop} + ${scroll?.clientHeight} of ${scroll?.scrollHeight}`);
    const box = card.getBoundingClientRect();
    const frame = scroll.getBoundingClientRect();
    assert(box.top - 6 >= frame.top && box.bottom + 6 <= frame.bottom, `The card and its outline stay in view: ${JSON.stringify({ box, frame })}`);
    assert(card.querySelector('button[aria-label="Allow once: Run the focused Maven test"]') !== null, 'The card shows the task’s own buttons');
    await new Promise(resolve => setTimeout(resolve, 2500));
    await settle();
    assert(!card.classList.contains('is-opened') && document.activeElement === card, 'The outline is gone after it fades, and focus stays on the card');
  }
  finally { view.dispose(); }
}

async function stopsARunningReply(): Promise<void> {
  const view = mount({ ...VIEW, running: true });
  try {
    await settle();
    assert(view.host.querySelector('.assistant-thinking') !== null, 'A running reply shows Thinking');
    assert(view.host.querySelector('button[aria-label="Send to the Assistant"]') === null, 'Stop replaces send');
    view.host.querySelector<HTMLButtonElement>('button[aria-label="Stop the reply"]')?.click();
    await settle();
    assert(view.calls.stopped === 1, 'Stop reaches the backend');
  }
  finally { view.dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['shows the day in order', showsTheDayInOrder], ['answers with the task’s buttons', answersWithTheTasksButtons],
    ['sends with what is shown', sendsWithWhatIsShown], ['opens at a card from its alert', opensAtACardFromItsAlert],
    ['stops a running reply', stopsARunningReply]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

let preview: { dispose: () => void } | null = null;

/** Draws the docked panel beside a page for a screenshot. */
export async function showPreview(): Promise<void> {
  preview?.dispose();
  document.body.style.margin = '0';
  document.body.style.background = 'var(--bg)';
  preview = mount();
  // A hidden window paints no frames, so a timer lets the reads and layout settle instead.
  await new Promise(resolve => setTimeout(resolve, 150));
}
