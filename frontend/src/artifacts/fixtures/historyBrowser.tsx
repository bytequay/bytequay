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
import { assert, changeInput, element, settle, until } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { AgentEventEnvelope } from '../../agents/events';
import type { AgentPendingApprovalDto, Bridge } from '../../types';
import ArtifactsPage, { openArtifact } from '../ArtifactsPage';
import type { ArtifactViewDto } from '../contracts';
import '../../css/base.css';
import '../../css/agents.css';

const source: AgentEventEnvelope['source'] = { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null, providerItemId: null, providerEventId: null };
const occurredAt = '2026-10-20T00:00:00Z';
function turn(number: number, text = `Request ${number}`): AgentEventEnvelope[] {
  const first = (number - 1) * 4 + 1;
  const base = { schemaVersion: 2 as const, taskId: 'artifact', turnId: `turn-${number}`, occurredAt, source };
  return [
    { ...base, eventId: `event-${first}`, sequence: first, type: 'user_message', payload: { text, attachments: [], deliveryKind: 'TURN' } },
    { ...base, eventId: `event-${first + 1}`, sequence: first + 1, type: 'lifecycle', payload: { phase: 'TURN_STARTED', message: 'Started' } },
    { ...base, eventId: `event-${first + 2}`, sequence: first + 2, type: 'assistant_message', payload: { messageKind: 'ANSWER', phase: 'FINAL', text: `Answer ${number}` } },
    { ...base, eventId: `event-${first + 3}`, sequence: first + 3, type: 'lifecycle', payload: { phase: 'TURN_COMPLETED', message: 'Completed' } },
  ];
}

function controlPolls() {
  const originalSet = window.setInterval;
  const originalClear = window.clearInterval;
  const intervals = new Map<number, () => void>();
  let key = 1_000_000;
  window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
    if (delay !== 1000 || typeof handler !== 'function') return originalSet(handler, delay, ...args);
    const id = ++key;
    intervals.set(id, () => handler(...args));
    return id;
  }) as typeof window.setInterval;
  window.clearInterval = id => { if (typeof id === 'number' && intervals.delete(id)) return; originalClear(id); };
  return {
    tick: () => [...intervals.values()].forEach(callback => callback()),
    restore: () => { window.setInterval = originalSet; window.clearInterval = originalClear; intervals.clear(); },
  };
}

async function boundedHistory(): Promise<void> {
  const all = Array.from({ length: 1250 }, (_, index) => turn(index + 1)).flat();
  const requests: { before?: string; after?: string; size: number }[] = [];
  const sends: string[] = [];
  const initialRead = Promise.withResolvers<void>();
  let olderRead: PromiseWithResolvers<void> | null = null;
  const view: ArtifactViewDto = { artifact: { id: 'artifact', title: 'Long conversation', updatedAt: occurredAt, working: false },
    offers: [], pages: [], questions: [], pendingApprovals: [] };
  const bridge = {
    listArtifacts: async () => [{ artifact: view.artifact, pages: 0 }],
    viewArtifact: async () => view,
    artifactEvents: async (_workspace, _artifact, before, after) => {
      const events = before !== undefined ? all.filter(event => event.sequence < Number(before)).slice(-500)
        : after !== undefined ? all.filter(event => event.sequence > Number(after)).slice(0, 500) : all.slice(-500);
      const first = events[0]?.sequence ?? 1;
      const last = events.at(-1)?.sequence ?? 0;
      requests.push({ before, after, size: events.length });
      if (requests.length === 1) await initialRead.promise;
      if (before !== undefined && olderRead !== null) await olderRead.promise;
      return { events, beforeCursor: first > 1 ? String(first) : null, afterCursor: last < all.length ? String(last) : null, latestSequence: all.length };
    },
    sendArtifact: async (_workspace, _artifact, text) => {
      sends.push(text); all.push(...turn(1251, text)); return view;
    },
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  host.style.cssText = 'height:650px;width:950px';
  const turnCount = () => host.querySelectorAll('.agt-turn').length;
  const shown = (number: number) => host.querySelector(`[data-history-anchor="turn-${number}"]`) !== null;
  try {
    flushSync(() => root.render(<ArtifactsPage workspaceId="workspace" />));
    await until(() => host.querySelector('.artifacts-row') !== null, 'The conversation is listed');
    element<HTMLButtonElement>('.artifacts-row', host).click();
    await until(() => requests.length === 1, 'The initial history request stays in flight');
    await settle();
    assert(host.querySelector('.agt-history-edge') !== null, 'Opening a conversation shows history loading');
    initialRead.resolve();
    await until(() => shown(1250), 'The newest turn renders without draining the history');
    await settle();
    assert(Number(requests.length) === 1 && requests[0].size === 500 && requests[0].before === undefined && requests[0].after === undefined,
      'Opening reads the latest 500 of 5000 events once');
    assert(turnCount() === 125 && !shown(1125), 'Only the latest event page is retained initially');
    assert(element('[data-history-anchor="turn-1250"]', host).getAttribute('aria-label') === 'Turn',
      'A partial history does not invent turn numbers from its truncated first page');
    const transcript = element<HTMLDivElement>('.agt-transcript', host);
    const older = () => {
      transcript.scrollTop = 0;
      transcript.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
    };
    older();
    await until(() => shown(1001), 'Scrolling upward loads the adjacent older page');
    assert(Number(requests.length) === 2 && requests[1].before === '4501' && turnCount() === 250, 'An older read retains at most 1000 events');
    older();
    await until(() => shown(876), 'Scrolling up again reads one more page');
    assert(Number(requests.length) === 3 && requests[2].before === '4001' && turnCount() === 250 && !shown(1250),
      'Further paging evicts the newer edge instead of growing the retained history');
    await settle();
    assert(Number(requests.length) === 3, 'Reading older history does not automatically drain newer pages');

    olderRead = Promise.withResolvers<void>();
    older();
    await until(() => requests.length === 4, 'Another older-page request stays in flight');
    await settle();
    assert(host.querySelector('.agt-history-edge') !== null, 'An explicit older-page request shows history loading');
    const input = element<HTMLTextAreaElement>('[aria-label="Message the agent"]', host);
    changeInput(input, 'Continue from the latest answer');
    await until(() => !element<HTMLButtonElement>('[aria-label="Send message"]', host).disabled, 'The composer works while reading older history');
    element<HTMLButtonElement>('[aria-label="Send message"]', host).click();
    await until(() => host.querySelector('[data-history-anchor="pending-message"]')?.textContent?.includes('Continue from the latest answer') === true,
      'Sending shows its pending message while older history is loading');
    olderRead.resolve(); olderRead = null;
    await until(() => shown(1251) && host.textContent?.includes('Answer 1251') === true, 'Sending returns to the latest bounded history window');
    await settle();
    assert(sends.length === 1 && sends[0] === 'Continue from the latest answer', 'The message is sent once');
    assert(Number(requests.length) === 6 && requests[4].before === undefined && requests[4].after === undefined && requests[5].after === '5004',
      'Sending and resuming share one latest read, followed by a read after the completed send');
    assert(turnCount() <= 250 && requests.every(request => request.size <= 500), 'History and individual reads stay bounded after sending');
  }
  finally { initialRead.resolve(); olderRead?.resolve(); dispose(); }
}

async function approvalAuthority(): Promise<void> {
  const question = (approvalId: string, title: string): AgentPendingApprovalDto => ({
    approvalId, taskId: 'artifact', turnId: approvalId === 'old' ? 'turn-1000' : 'turn-1250', source, occurredAt,
    title, approvalKind: 'OTHER', scope: ['AskUserQuestion'], sandboxed: false, actionable: true,
    availableDecisions: ['ALLOW_ONCE', 'DENY'], detail: JSON.stringify({ questions: [{ question: title, header: 'Path', multiSelect: false,
      options: [{ label: 'Read path', description: 'Follow reads' }, { label: 'Write path', description: 'Follow writes' }] }] }),
  });
  const old = question('old', 'Already answered question');
  const current = question('current', 'Current question');
  const all = Array.from({ length: 1250 }, (_, index) => turn(index + 1)).flat();
  const requested = (sequence: number, approval: AgentPendingApprovalDto): AgentEventEnvelope => ({
    ...all[sequence - 1], turnId: approval.turnId, type: 'approval_requested', payload: {
      approvalId: approval.approvalId, title: approval.title, detail: approval.detail, approvalKind: 'OTHER',
      scope: approval.scope, sandboxed: false, actionable: true, availableDecisions: ['ALLOW_ONCE', 'DENY'],
    },
  });
  all[3998] = requested(3999, old);
  all[4997] = { ...all[4997], turnId: old.turnId, type: 'approval_resolved', payload: { approvalId: old.approvalId, outcome: 'ALLOWED', forTask: false } };
  all[4998] = requested(4999, current);
  // The latest turn is waiting for its answer, so it has not completed yet.
  all[4999] = { ...all[4999], type: 'lifecycle', payload: { phase: 'TURN_STARTED', message: 'Waiting for the answer' } };
  let view: ArtifactViewDto = { artifact: { id: 'artifact', title: 'Paged approvals', updatedAt: occurredAt, working: true },
    offers: [], pages: [], questions: [current.approvalId], pendingApprovals: [current] };
  const reads: { first: number; last: number }[] = [];
  const answers: { approvalId: string; allow: boolean; answers: Record<string, string> }[] = [];
  window.bridge = {
    listArtifacts: async () => [{ artifact: view.artifact, pages: 0 }],
    viewArtifact: async () => view,
    artifactEvents: async (_workspace, _artifact, before, after) => {
      const events = before !== undefined ? all.filter(event => event.sequence < Number(before)).slice(-500)
        : after !== undefined ? all.filter(event => event.sequence > Number(after)).slice(0, 500) : all.slice(-500);
      const first = events[0]?.sequence ?? 1;
      const last = events.at(-1)?.sequence ?? 0;
      reads.push({ first, last });
      return { events, beforeCursor: first > 1 ? String(first) : null, afterCursor: last < all.length ? String(last) : null, latestSequence: all.length };
    },
    answerArtifactQuestion: async (_workspace, _artifact, approvalId, allow, selected) => {
      answers.push({ approvalId, allow, answers: selected });
      view = { ...view, artifact: { ...view.artifact, working: false }, questions: [], pendingApprovals: [] };
      return view;
    },
  } satisfies Partial<Bridge> as unknown as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  const timers = controlPolls();
  host.style.cssText = 'height:650px;width:950px';
  try {
    flushSync(() => root.render(<ArtifactsPage workspaceId="workspace" />));
    await until(() => host.querySelector('.artifacts-row') !== null, 'The approval conversation is listed');
    element<HTMLButtonElement>('.artifacts-row', host).click();
    await until(() => host.querySelector('[aria-label="Question: Current question"]') !== null, 'The latest pending question renders');
    const transcript = element<HTMLDivElement>('.agt-transcript', host);
    const older = () => {
      transcript.scrollTop = 0;
      transcript.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
    };
    older();
    await until(() => host.querySelector('[data-history-anchor="turn-1001"]') !== null, 'The adjacent older page loads');
    older();
    await until(() => host.querySelector('[data-history-anchor="turn-876"]') !== null, 'The question request is loaded without its newer resolution');
    const historical = element('[aria-label="Approval: Already answered question"]', host);
    assert(historical.textContent?.includes('Resolution outside this history window') === true && historical.querySelector('button') === null,
      'A resolved request stays non-actionable when its resolution is outside the retained history');
    assert(host.querySelector('[data-history-anchor="turn-1250"]') === null, 'The current question event is outside the retained history');
    const pending = element('[aria-label="Question: Current question"]', host);
    element<HTMLButtonElement>('[role="radio"]', pending).click();
    await until(() => !element<HTMLButtonElement>('[aria-label="Send answer: Current question"]', pending).disabled, 'The independent pending question accepts a choice');
    element<HTMLButtonElement>('[aria-label="Send answer: Current question"]', pending).click();
    await until(() => answers.length === 1 && host.querySelector('[aria-label="Question: Current question"]') === null, 'The answer resolves the current pending question');
    assert(answers[0].approvalId === 'current' && answers[0].allow && answers[0].answers['Current question'] === 'Read path',
      'Answering outside the history window sends the selected answer for the current approval only');
    assert(reads.length === 3 && reads[2].last === 4000, 'Approval authority does not require loading the rest of the event log');
    assert(element('[aria-label="Approval: Already answered question"]', host).querySelector('button') === null, 'The historical approval remains non-actionable after answering');
  }
  finally { dispose(); timers.restore(); }
}

async function openFromNotice(): Promise<void> {
  const view = (id: string, title: string): ArtifactViewDto => ({ artifact: { id, title, updatedAt: occurredAt, working: false },
    offers: [], pages: [], questions: [], pendingApprovals: [] });
  const views = [view('first', 'First conversation'), view('missed', 'Missed question')];
  window.bridge = {
    listArtifacts: async () => views.map(each => ({ artifact: each.artifact, pages: 0 })),
    viewArtifact: async (_workspace, artifact) => views.find(each => each.artifact.id === artifact) ?? views[0],
    artifactEvents: async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 }),
  } satisfies Partial<Bridge> as unknown as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  host.style.cssText = 'height:650px;width:950px';
  try {
    flushSync(() => root.render(<ArtifactsPage workspaceId="workspace" />));
    await until(() => host.querySelector('.artifacts-row') !== null, 'The conversations are listed');
    openArtifact('other', 'missed');
    await settle();
    assert(host.querySelector('[aria-label="Missed question"]') === null, 'A notice for another workspace leaves this one alone');
    // The list is already showing, so the route does not change; the notice still opens its conversation.
    openArtifact('workspace', 'missed');
    await until(() => host.querySelector('[aria-label="Missed question"]') !== null, 'A notice opens its conversation over the showing list');
  }
  finally { dispose(); }
}

async function resumeFollowing(): Promise<void> {
  const all = [...Array.from({ length: 50 }, (_, index) => turn(index + 1)).flat(), ...turn(51).slice(0, 2)];
  let view: ArtifactViewDto = { artifact: { id: 'artifact', title: 'Answer while reading', updatedAt: occurredAt, working: true },
    offers: [], pages: [], questions: [], pendingApprovals: [] };
  let viewReads = 0;
  const reads: { before?: string; after?: string }[] = [];
  window.bridge = {
    listArtifacts: async () => [{ artifact: view.artifact, pages: 0 }],
    viewArtifact: async () => { viewReads++; return view; },
    artifactEvents: async (_workspace, _artifact, before, after) => {
      reads.push({ before, after });
      const events = after === undefined ? [...all] : all.filter(event => event.sequence > Number(after));
      return { events, beforeCursor: null, afterCursor: null, latestSequence: all.length };
    },
  } satisfies Partial<Bridge> as unknown as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  const timers = controlPolls();
  host.style.cssText = 'height:650px;width:950px';
  try {
    flushSync(() => root.render(<ArtifactsPage workspaceId="workspace" />));
    await until(() => host.querySelector('.artifacts-row') !== null, 'The active conversation is listed');
    element<HTMLButtonElement>('.artifacts-row', host).click();
    await until(() => host.querySelector('[data-history-anchor="turn-51"]') !== null, 'The running latest turn renders');
    const transcript = element<HTMLDivElement>('.agt-transcript', host);
    transcript.scrollTop = transcript.scrollHeight - transcript.clientHeight - 300;
    transcript.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
    await settle();
    assert(reads.length === 1, 'Reading within the latest window does not fetch an older page');
    all.push(...turn(51).slice(2));
    view = { ...view, artifact: { ...view.artifact, working: false } };
    timers.tick();
    await until(() => viewReads === 2 && host.querySelector('[aria-label="Stop"]') === null, 'Polling learns that the agent finished while the user reads above');
    assert(Number(reads.length) === 1 && !host.textContent?.includes('Answer 51'), 'The paused transcript does not fetch terminal events during the view poll');
    transcript.scrollTop = transcript.scrollHeight;
    transcript.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, bubbles: true }));
    await until(() => host.textContent?.includes('Answer 51') === true, 'Returning to the bottom catches up the completed answer');
    assert(Number(reads.length) === 2 && reads[1].before === undefined && reads[1].after === undefined,
      'Resuming following reads the latest history even when the last known page had no newer cursor');
  }
  finally { dispose(); timers.restore(); }
}

async function answerDuringRead(): Promise<void> {
  const approval: AgentPendingApprovalDto = {
    approvalId: 'current', taskId: 'artifact', turnId: 'turn-1', source, occurredAt, title: 'Which path?', approvalKind: 'OTHER',
    scope: ['AskUserQuestion'], sandboxed: false, actionable: true, availableDecisions: ['ALLOW_ONCE', 'DENY'],
    detail: JSON.stringify({ questions: [{ question: 'Which path?', header: 'Path', multiSelect: false,
      options: [{ label: 'Read path', description: 'Follow reads' }] }] }),
  };
  const base = { schemaVersion: 2 as const, taskId: 'artifact', turnId: 'turn-1', occurredAt, source };
  const all: AgentEventEnvelope[] = [...turn(1).slice(0, 2), {
    ...base, eventId: 'question', sequence: 3, type: 'approval_requested', payload: {
      approvalId: approval.approvalId, title: approval.title, detail: approval.detail, approvalKind: 'OTHER', scope: approval.scope,
      sandboxed: false, actionable: true, availableDecisions: ['ALLOW_ONCE', 'DENY'],
    },
  }];
  let view: ArtifactViewDto = { artifact: { id: 'artifact', title: 'Answer during a read', updatedAt: occurredAt, working: true },
    offers: [], pages: [], questions: [approval.approvalId], pendingApprovals: [approval] };
  const reads: (string | undefined)[] = [];
  let pendingRead: PromiseWithResolvers<void> | null = null;
  window.bridge = {
    listArtifacts: async () => [{ artifact: view.artifact, pages: 0 }],
    viewArtifact: async () => view,
    artifactEvents: async (_workspace, _artifact, _before, after) => {
      const events = after === undefined ? [...all] : all.filter(event => event.sequence > Number(after));
      const latestSequence = all.length;
      reads.push(after);
      if (pendingRead !== null) await pendingRead.promise;
      return { events, beforeCursor: null, afterCursor: null, latestSequence };
    },
    answerArtifactQuestion: async () => {
      all.push({ ...base, eventId: 'resolved', sequence: 4, type: 'approval_resolved',
        payload: { approvalId: approval.approvalId, outcome: 'ALLOWED', forTask: false } });
      all.push(...turn(1).slice(2).map(event => ({ ...event, sequence: event.sequence + 2 })));
      view = { ...view, artifact: { ...view.artifact, working: false }, questions: [], pendingApprovals: [] };
      return view;
    },
  } satisfies Partial<Bridge> as unknown as Bridge;
  const { root, host, dispose } = createFixtureRoot();
  const timers = controlPolls();
  host.style.cssText = 'height:650px;width:950px';
  try {
    flushSync(() => root.render(<ArtifactsPage workspaceId="workspace" />));
    await until(() => host.querySelector('.artifacts-row') !== null, 'The question conversation is listed');
    element<HTMLButtonElement>('.artifacts-row', host).click();
    await until(() => host.querySelector('[aria-label="Question: Which path?"]') !== null, 'The pending question renders');
    pendingRead = Promise.withResolvers<void>();
    timers.tick();
    await until(() => reads.length === 2, 'A poll holds an event response from before the answer');
    await settle();
    assert(host.querySelector('.agt-history-edge') === null, 'A background poll does not show history loading');
    const question = element('[aria-label="Question: Which path?"]', host);
    element<HTMLButtonElement>('[role="radio"]', question).click();
    await until(() => !element<HTMLButtonElement>('[aria-label="Send answer: Which path?"]', question).disabled, 'The question accepts a choice');
    element<HTMLButtonElement>('[aria-label="Send answer: Which path?"]', question).click();
    await until(() => host.querySelector('[aria-label="Stop"]') === null, 'The answer completes the turn while its old read remains in flight');
    assert(!host.textContent?.includes('Answer 1'), 'The held response does not contain the final answer');
    pendingRead.resolve(); pendingRead = null;
    await until(() => host.textContent?.includes('Answer 1') === true, 'The action reads again after the older request and loads the final answer');
    await settle();
    assert(Number(reads.length) === 3 && reads[1] === '3' && reads[2] === '3', 'An idle completed action gets exactly one subsequent event refresh');
    assert(host.querySelector('[aria-label="Question: Which path?"]') === null, 'The completed question cannot be answered again');
  }
  finally { pendingRead?.resolve(); dispose(); timers.restore(); }
}

export async function run(): Promise<boolean> {
  await boundedHistory();
  await approvalAuthority();
  await resumeFollowing();
  await answerDuringRead();
  await openFromNotice();
  return true;
}
