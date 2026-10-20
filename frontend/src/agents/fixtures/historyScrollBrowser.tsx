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

import { createFixtureRoot } from '../../fixtures/reactFixture';
import { useReducer, useState } from 'react';
import { flushSync } from 'react-dom';
import { AgentConversation, useConversationHistory } from '../AgentConversation';
import AgentTaskScreen, { AgentTaskBody } from '../AgentTaskScreen';
import type { AgentHistoryPageDto, AgentTaskDto, AgentTurnDto } from '../../types';
import type { AgentEventEnvelope } from '../events';
import { agentTaskReducer, historyAfter, historyBefore, initialAgentTaskState } from '../model';
import { projectTranscript } from '../transcript';
import { createPreviewBridge, previewTask, TASK_BODY_NOOPS } from './previewBridge';
import { assert, changeInput, settle, until } from '../../fixtures/harness';

declare global {
  interface Window {
    historyTestWheel: (event: { x: number; y: number; deltaY: number }) => Promise<void>;
  }
}

type Frame = {
  events: AgentEventEnvelope[];
  before: string | null;
  after: string | null;
  loading: boolean;
  endedAt: string | null;
  changedAnchor?: number;
};

const page = (first: number, last: number): AgentEventEnvelope[] => Array.from({ length: last - first + 1 }, (_, offset): AgentEventEnvelope => ({
  schemaVersion: 2,
  eventId: `message-${first + offset}`,
  sequence: first + offset,
  taskId: 'scroll-fixture',
  turnId: 'turn',
  occurredAt: '2026-09-08T00:00:00Z',
  source: {
    providerThreadId: 'thread', providerTurnId: 'turn', providerItemId: `item-${first + offset}`,
    providerEventId: null, parentProviderThreadId: null, logicalAgentPath: '/',
  },
  type: 'assistant_message',
  payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: `Message ${first + offset}` },
}));


function fixture(initial: Partial<Frame> = {}) {
  let frame: Frame = { events: page(31, 60), before: 'older', after: null, loading: false, endedAt: null, ...initial };
  let following = true;
  const requests: string[] = [];
  const { host, root, dispose } = createFixtureRoot();
  function Transcript() {
    const [, rerender] = useReducer(value => value + 1, 0);
    const scroll = useConversationHistory(frame.events, frame.endedAt, {
      ...frame,
      onLoad: direction => { requests.push(direction); frame = { ...frame, loading: true }; rerender(); },
      onFollow: value => { following = value; },
    });
    return <div {...scroll.transcriptProps} style={{ height: 400, width: 640, overflowY: 'auto', overflowAnchor: 'none', font: '16px / 20px monospace' }}>
      {frame.events.map(event => <div
        key={event.eventId}
        data-history-anchor={event.sequence === frame.changedAnchor ? `reprojected-${event.eventId}` : event.eventId}
        style={{ height: 80 }}
      >
        {Array.from({ length: 4 }, (_, index) => <span key={index} data-line={`${event.sequence}-${index}`} style={{ display: 'block' }}>
          Message {event.sequence}, readable line {index}. A stable passage in the task history.
        </span>)}
      </div>)}
    </div>;
  }
  const render = (changes: Partial<Frame> = {}): void => {
    frame = { ...frame, ...changes };
    flushSync(() => root.render(<Transcript />));
  };
  render();
  const container = host.firstElementChild as HTMLDivElement;
  return {
    container,
    requests,
    following: () => following,
    async update(changes: Partial<Frame>) { render(changes); await settle(); },
    async moveTo(top: number) {
      const deltaY = top - container.scrollTop;
      container.dispatchEvent(new WheelEvent('wheel', { deltaY, bubbles: true }));
      // Synthetic wheel events expose intent, but Chromium only scrolls for trusted input.
      container.scrollTop = top;
      container.dispatchEvent(new Event('scroll', { bubbles: true }));
      await settle();
    },
    async wheel(deltaY: number) {
      container.dispatchEvent(new WheelEvent('wheel', { deltaY, bubbles: true }));
      await settle();
    },
    passage(line?: string) {
      const viewport = container.getBoundingClientRect();
      const element = line === undefined
        ? [...container.querySelectorAll<HTMLElement>('[data-line]')].find(item => item.getBoundingClientRect().top >= viewport.top + 24)
        : container.querySelector<HTMLElement>(`[data-line="${line}"]`);
      assert(element != null, `Reading passage ${line ?? 'at viewport top'} is missing`);
      return { line: element.dataset.line, top: element.getBoundingClientRect().top };
    },
    dispose() { dispose(); },
  };
}

async function longCommandConversation(): Promise<void> {
  const events = page(1, 7010).map((event): AgentEventEnvelope => event.sequence <= 7000 ? {
    ...event, source: { ...event.source, providerItemId: 'long-command' }, type: 'command',
    payload: {
      state: 'PROGRESS', argv: ['mvn', 'verify'], output: `Building module ${event.sequence}: compilation complete.\n`,
      exitCode: null, truncated: false, cwd: '.', durationMs: null, actions: [], description: null,
    },
  } : {
    ...event, type: 'assistant_message',
    payload: {
      messageKind: event.sequence === 7010 ? 'ANSWER' : 'COMMENTARY', phase: 'FINAL',
      text: `Reading checkpoint ${event.sequence}. The build finished and this message explains the next step.\n\nThis paragraph should remain readable when earlier command output arrives.`,
    },
  });
  const read = (end: number): AgentHistoryPageDto => {
    const start = Math.max(0, end - 1000);
    return { events: events.slice(start, end), beforeCursor: start === 0 ? null : String(start), afterCursor: end === events.length ? null : String(end), latestSequence: events.length };
  };
  const turn: AgentTurnDto = {
    id: 'turn', taskId: 'scroll-fixture', state: 'COMPLETED', version: 1, createdAt: '2026-09-08T00:00:00Z',
    startedAt: '2026-09-08T00:00:00Z', endedAt: '2026-09-08T00:10:00Z', cancellationRequestedAt: null,
    delivered: true, knownUnsent: false, settings: null, terminalReason: null,
    metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT10M' },
  };
  const initial = agentTaskReducer({
    ...initialAgentTaskState(), task: previewTask('workspace', 'scroll-fixture'), turn, turns: { turn }, connection: 'open',
  }, { type: 'history/loaded', page: read(7010), earlier: [read(5010), read(6010)], direction: 'latest' });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  const requests: string[] = [];
  let respond = (): void => { throw new Error('No pending history read'); };
  function Conversation() {
    const [state, dispatch] = useReducer(agentTaskReducer, initial);
    const [loading, setLoading] = useState(false);
    return <AgentTaskBody
      state={state} models={[]} historyLoading={loading} turnChoice={{ turn: true }}
      onLoadHistory={direction => {
        requests.push(direction);
        setLoading(true);
        const end = direction === 'older' ? Number(historyBefore(state))
          : direction === 'newer' ? Math.min(events.length, Number(historyAfter(state)) + 1000) : events.length;
        respond = () => { dispatch({ type: 'history/loaded', page: read(end), direction }); setLoading(false); };
      }} {...TASK_BODY_NOOPS}
    />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    assert(container !== null, 'The production task transcript is missing');
    assert(container.scrollHeight > container.clientHeight + 400, 'The production CSS must constrain and scroll the transcript');
    const composer = host.querySelector<HTMLElement>('.agt-compose-dock');
    assert(composer !== null, 'The production task composer is missing');
    const composerTop = composer.getBoundingClientRect().top;
    assert(composerTop >= host.getBoundingClientRect().top && composer.getBoundingClientRect().bottom <= host.getBoundingClientRect().bottom,
      'A long transcript must leave the composer inside the task viewport');
    const paragraphs = [...container.querySelectorAll<HTMLElement>('.agt-text p, .agt-answer p')].map(element => element.textContent ?? '');
    assert(paragraphs.length === 20, `Expected ten readable messages, found ${paragraphs.length / 2}`);
    const wheel = async (deltaY: number): Promise<void> => {
      const bounds = container.getBoundingClientRect();
      await window.historyTestWheel({ x: Math.round(bounds.left + bounds.width / 2), y: Math.round(bounds.top + bounds.height / 2), deltaY });
      await settle();
    };
    for (let step = 0; requests.length === 0 && step < 30; step++) await wheel(-120);
    assert(requests.join() === 'older', 'Native mouse scrolling should request exactly one older page');
    const visible = [...container.querySelectorAll<HTMLElement>('.agt-text p')].find(element => {
      const bounds = element.getBoundingClientRect();
      const viewport = container.getBoundingClientRect();
      return element.textContent?.startsWith('Reading checkpoint') && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom;
    });
    assert(visible !== undefined, 'A commentary passage should still be visible while earlier output loads');
    const readingTop = visible.getBoundingClientRect().top;
    const readingText = visible.textContent;
    flushSync(() => respond());
    await settle();
    const remaining = [...container.querySelectorAll<HTMLElement>('.agt-text p, .agt-answer p')].map(element => element.textContent ?? '');
    assert(paragraphs.every(text => remaining.includes(text)), `Loading 1,000 earlier command deltas removed ${paragraphs.length - remaining.length} readable paragraphs`);
    const retainedPassage = [...container.querySelectorAll<HTMLElement>('.agt-text p')].find(element => element.textContent === readingText);
    assert(retainedPassage !== undefined && Math.abs(retainedPassage.getBoundingClientRect().top - readingTop) <= 1, 'Loading earlier command output must keep the current commentary passage in place');
    container.scrollTop = 0;
    await settle();
    const firstParagraph = container.querySelector<HTMLElement>('.agt-text p');
    assert(firstParagraph !== null, 'The first commentary should remain in the completed task');
    const topBeforeLoading = firstParagraph.getBoundingClientRect().top;
    await wheel(-120);
    assert(requests.join() === 'older,older', 'An upward mouse wheel at the top should request the next older page');
    assert(Math.abs(firstParagraph.getBoundingClientRect().top - topBeforeLoading) <= 1, 'The loading indicator must not push messages down at the top');
    flushSync(() => respond());
    await settle();
    assert(Math.abs(firstParagraph.getBoundingClientRect().top - topBeforeLoading) <= 1, 'Removing the loading indicator must not pull messages up at the top');
    for (let step = 0; container.scrollHeight - container.scrollTop - container.clientHeight > 1 && step < 30; step++) await wheel(120);
    const answer = container.querySelector<HTMLElement>('.agt-answer');
    assert(answer !== null && answer.getBoundingClientRect().bottom > container.getBoundingClientRect().top
      && answer.getBoundingClientRect().bottom <= container.getBoundingClientRect().bottom, 'Scrolling back down should reach the existing final answer');
    assert(Math.abs(composer.getBoundingClientRect().top - composerTop) <= 1,
      'Paging and scrolling the transcript must leave the composer in place');
  }
  finally { dispose(); }
}

const message = (sequence: number, turnId: string, text: string, messageKind: 'COMMENTARY' | 'ANSWER' = 'COMMENTARY'): AgentEventEnvelope => ({
  ...page(sequence, sequence)[0], turnId, source: { ...page(sequence, sequence)[0].source, providerTurnId: turnId },
  type: 'assistant_message', payload: { messageKind, phase: 'FINAL', text },
});

const request = (sequence: number, turnId: string, text: string): AgentEventEnvelope => ({
  ...page(sequence, sequence)[0], turnId, source: { ...page(sequence, sequence)[0].source, providerTurnId: turnId },
  type: 'user_message', payload: { text, attachments: [], deliveryKind: 'TURN' },
});

const durable = (id: string, state: AgentTurnDto['state']): AgentTurnDto => ({
  id, taskId: 'scroll-fixture', state, version: 1, createdAt: '2026-09-08T00:00:00Z', startedAt: '2026-09-08T00:00:00Z',
  endedAt: state === 'RUNNING' ? null : '2026-09-08T00:10:00Z', cancellationRequestedAt: null, delivered: true, knownUnsent: false,
  settings: null, terminalReason: null, metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT10M' },
});

const latest = (events: AgentEventEnvelope[]): AgentHistoryPageDto => ({ events, beforeCursor: null, afterCursor: null, latestSequence: events.at(-1)?.sequence ?? 0 });

const earlierTurn = (): AgentEventEnvelope[] => [request(1, 'earlier', 'Explain the build'),
  ...Array.from({ length: 30 }, (_, index) => message(index + 2, 'earlier', `Earlier commentary ${index}. The build output is being read.`)),
  message(32, 'earlier', 'The build is explained.', 'ANSWER')];

async function latestRequestAtTop(): Promise<void> {
  const earlier = earlierTurn();
  const running = durable('latest', 'RUNNING');
  const initial = agentTaskReducer({
    ...initialAgentTaskState(), task: previewTask('workspace', 'scroll-fixture'), turn: running,
    turns: { earlier: durable('earlier', 'COMPLETED'), latest: running }, connection: 'open',
  }, { type: 'history/loaded', page: latest([...earlier, request(33, 'latest', 'commit')]), direction: 'latest' });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  let append = (_events: AgentEventEnvelope[]): void => { throw new Error('The conversation is not mounted'); };
  function Conversation() {
    const [state, dispatch] = useReducer(agentTaskReducer, initial);
    append = events => dispatch({ type: 'history/loaded', page: latest(events), direction: 'latest' });
    return <AgentTaskBody
      state={state} models={[]} historyLoading={false} turnChoice={{ earlier: true, latest: true }} onLoadHistory={() => {}} {...TASK_BODY_NOOPS}
    />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    const turn = host.querySelector<HTMLElement>('.agt-turn.is-latest');
    assert(container !== null && turn !== null, 'The production transcript should mark its latest turn');
    assert(host.querySelectorAll('.agt-turn.is-latest').length === 1, 'Only the latest turn reserves the viewport');
    const viewport = container.getBoundingClientRect();
    const padding = Number.parseFloat(getComputedStyle(container).paddingTop);
    const offset = turn.getBoundingClientRect().top - viewport.top;
    assert(container.scrollHeight - container.scrollTop - container.clientHeight <= 1, 'A new request should still follow the bottom');
    assert(Math.abs(offset - padding) <= 1, `A new request should sit at the top of the transcript, not ${offset}px down`);
    const live = turn.querySelector<HTMLElement>('.agt-live');
    assert(live !== null && live.getBoundingClientRect().top < viewport.top + 200, 'The live activity should sit under the request, not at the bottom');

    flushSync(() => append([...earlier, request(33, 'latest', 'commit'), message(34, 'latest', 'Streaming progress. The commit is being prepared.')]));
    await settle();
    const streamed = turn.querySelector<HTMLElement>('.agt-live');
    const trailing = streamed === null ? Number.NaN : viewport.bottom - streamed.getBoundingClientRect().bottom;
    assert(trailing <= Number.parseFloat(getComputedStyle(container).paddingBottom) + 1,
      `A short reply should end the reserve and leave its output against the composer, not ${Math.round(trailing)}px above it`);

    flushSync(() => append([...earlier, request(33, 'latest', 'commit'),
      ...Array.from({ length: 40 }, (_, index) => message(index + 34, 'latest', `Streaming progress ${index}. The commit is being prepared.`))]));
    await settle();
    assert(container.scrollHeight - container.scrollTop - container.clientHeight <= 1, 'A reply taller than the viewport should keep following its output');
    assert(turn.getBoundingClientRect().top < viewport.top, 'A long reply should scroll its request out of view like before');
    const streaming = turn.querySelector<HTMLElement>('.agt-live');
    assert(streaming !== null && streaming.getBoundingClientRect().bottom <= turn.getBoundingClientRect().bottom + 1, 'A long latest turn must grow with its reply instead of overflowing its box');
  }
  finally { dispose(); }
}

/** Someone reading earlier in the live page still gets streamed output, and keeps their place. */
async function streamsWhileReadingEarlier(): Promise<void> {
  const earlier = earlierTurn();
  const running = durable('latest', 'RUNNING');
  const opening = [...earlier, request(33, 'latest', 'commit'), message(34, 'latest', 'Reading the changed files.')];
  const initial = agentTaskReducer({
    ...initialAgentTaskState(), task: previewTask('workspace', 'scroll-fixture'), turn: running,
    turns: { earlier: durable('earlier', 'COMPLETED'), latest: running }, connection: 'open',
  }, { type: 'history/loaded', page: latest(opening), direction: 'latest' });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  let append = (_events: AgentEventEnvelope[]): void => { throw new Error('The conversation is not mounted'); };
  function Conversation() {
    const [state, dispatch] = useReducer(agentTaskReducer, initial);
    append = events => dispatch({ type: 'history/loaded', page: latest(events), direction: 'latest' });
    return <AgentTaskBody
      state={state} models={[]} historyLoading={false} turnChoice={{ earlier: true, latest: true }} onLoadHistory={() => {}} {...TASK_BODY_NOOPS}
    />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    assert(container !== null && container.querySelector('.agt-live') !== null, 'A running task should show its live activity');
    container.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
    container.scrollTop -= 120;
    container.dispatchEvent(new Event('scroll', { bubbles: true }));
    await settle();
    assert(container.querySelector('.agt-live') !== null, 'Reading earlier in the live page must not hide the live activity');
    const passage = [...container.querySelectorAll<HTMLElement>('.agt-text p')]
      .find(element => element.getBoundingClientRect().top >= container.getBoundingClientRect().top + 24);
    assert(passage !== undefined, 'A readable passage should be in view after scrolling up');
    const text = passage.textContent ?? '';
    const top = passage.getBoundingClientRect().top;

    // The live window slides as output streams, so the page loses its oldest events while it gains new ones.
    flushSync(() => append([...opening.slice(18), message(35, 'latest', 'Rewriting the first file.'),
      message(36, 'latest', 'The commit is prepared.')]));
    await settle();
    const streamed = [...container.querySelectorAll<HTMLElement>('.agt-text p')].map(element => element.textContent ?? '');
    assert(streamed.some(line => line.startsWith('The commit is prepared')),
      'Output that arrives while reading earlier should render instead of waiting for a scroll');
    assert(container.querySelector('.agt-live') !== null, 'Streamed output must not drop the live activity');
    const held = [...container.querySelectorAll<HTMLElement>('.agt-text p')].find(element => element.textContent === text);
    const movement = held === undefined ? Number.NaN : held.getBoundingClientRect().top - top;
    assert(Math.abs(movement) <= 1, `Streamed output moved the passage being read ${Math.round(movement)}px`);
  }
  finally { dispose(); }
}

async function sentMessageGlidesToTop(): Promise<void> {
  const earlier = earlierTurn();
  const completed = durable('earlier', 'COMPLETED');
  const initial = agentTaskReducer({
    ...initialAgentTaskState(), task: { ...previewTask('workspace', 'scroll-fixture'), mutationClaim: null, developmentContinuationUnfinished: false },
    turn: completed, turns: { earlier: completed }, connection: 'open', composer: 'Check the next step',
  }, { type: 'history/loaded', page: latest(earlier), direction: 'latest' });
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  let deliver = (_events: AgentEventEnvelope[]): void => { throw new Error('The conversation is not mounted'); };
  function Conversation() {
    const [state, dispatch] = useReducer(agentTaskReducer, initial);
    deliver = events => dispatch({ type: 'history/loaded', page: latest(events), direction: 'latest' });
    return <AgentTaskBody
      state={state} models={[]} historyLoading={false} turnChoice={{ earlier: true }} onLoadHistory={() => {}}
      onSend={() => { dispatch({ type: 'composer/changed', text: '' }); dispatch({ type: 'message/sent', text: state.composer }); }}
      onSelectModel={() => {}} onBack={() => {}} onCancel={() => {}} onComplete={() => {}}
      onDecide={() => {}} onCompose={() => {}} onStopServer={() => {}} onMarkFailed={() => {}}
    />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    const send = host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
    assert(container !== null && send !== null && !send.disabled, 'An idle task should offer Send');
    const viewport = container.getBoundingClientRect();
    const padding = Number.parseFloat(getComputedStyle(container).paddingTop);
    const before = container.scrollTop;
    flushSync(() => send.click());
    const turn = host.querySelector<HTMLElement>('.agt-turn.is-latest');
    assert(turn?.querySelector('.agt-user__bubble')?.textContent === 'Check the next step', 'Send should show the message before its durable event arrives');
    assert(turn.querySelector('.agt-live')?.textContent === 'Sending…', 'The sent message should say it is still being sent');
    assert(container.scrollTop === before && turn.getBoundingClientRect().top - viewport.top > viewport.height / 3,
      `The sent message should start at the bottom, not jump to the top: it sits ${turn.getBoundingClientRect().top - viewport.top}px down`);
    await settle();
    const offset = (): number => turn.getBoundingClientRect().top - viewport.top;
    for (let step = 0; (container.scrollHeight - container.scrollTop - container.clientHeight > 1 || Math.abs(offset() - padding) > 1) && step < 60; step++) await settle();
    assert(container.scrollHeight - container.scrollTop - container.clientHeight <= 1 && Math.abs(offset() - padding) <= 1,
      `The sent message should glide to the top of the transcript, not stop ${offset()}px down`);

    flushSync(() => deliver([...earlier, request(33, 'latest', 'Check the next step')]));
    await settle();
    const bubbles = [...host.querySelectorAll('.agt-user__bubble')].filter(bubble => bubble.textContent === 'Check the next step');
    const delivered = host.querySelector<HTMLElement>('.agt-turn.is-latest');
    assert(bubbles.length === 1 && delivered !== null && Math.abs(delivered.getBoundingClientRect().top - viewport.top - padding) <= 1,
      'The durable message should replace the sent one in place, without a duplicate or a jump');
  }
  finally { dispose(); }
}

async function sendsWhileOlderHistoryLoads(): Promise<void> {
  const bridge = createPreviewBridge('workspace');
  const task: AgentTaskDto = { ...previewTask('workspace', 'scroll-fixture'), mutationClaim: null, developmentContinuationUnfinished: false };
  const events = page(1, 5000).map(event => event.sequence % 1000 > 970 || event.sequence % 1000 === 0 ? event : {
    ...event, source: { ...event.source, providerThreadId: 'child-thread', parentProviderThreadId: 'thread', logicalAgentPath: '/child' },
  });
  const read = (end: number): AgentHistoryPageDto => ({
    events: events.slice(Math.max(0, end - 1000), end), beforeCursor: end > 1000 ? String(end - 1000) : null,
    afterCursor: end < events.length ? String(end) : null, latestSequence: events.length,
  });
  let reads = 0;
  let olderReply: ((result: AgentHistoryPageDto) => void) | null = null;
  let latestReply: ((result: AgentHistoryPageDto) => void) | null = null;
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => durable('turn', 'COMPLETED');
  bridge.readAgentPendingApprovals = async () => [];
  bridge.readAgentHistory = async (_taskId, before) => {
    if (before === undefined) {
      if (++reads === 1) return read(5000);
      return new Promise(resolve => { latestReply = resolve; });
    }
    if (before === '1000') return new Promise(resolve => { olderReply = resolve; });
    return read(Number(before));
  };
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    listener({ subscriptionId: 'history-send', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.startAgentTurn = () => new Promise(() => {});
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  try {
    flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace" taskId={task.id} bridge={bridge} onBack={() => {}} />));
    await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]') !== null
      && host.textContent?.includes('Message 5000') === true, 'The task should load its latest history');
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    assert(container !== null, 'The transcript must exist');
    const scrollOlder = (): void => {
      container.scrollTop = 0;
      container.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
    };
    scrollOlder();
    await until(() => host.textContent?.includes('Message 1971') === true && !host.textContent?.includes('Message 5000'),
      'Paging earlier should leave the latest history window');
    scrollOlder();
    await until(() => olderReply !== null, 'Another older history request should remain in flight');
    const input = host.querySelector<HTMLTextAreaElement>('[aria-label="Message the agent"]');
    assert(input !== null, 'The composer must exist while reading history');
    changeInput(input, 'Continue from the latest result');
    await settle();
    const send = host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'An idle task must allow sending while history loads');
    flushSync(() => send.click());
    assert(latestReply !== null && reads === 2, 'Sending must start one latest history request without waiting for the older read');
    const pending = host.querySelector('[data-history-anchor="pending-message"]');
    assert(pending?.textContent?.includes('Continue from the latest result') === true,
      'The sent message must appear before either history response or turn startup returns');
    assert(host.querySelector('.agt-live')?.textContent === 'Sending…', 'The pending message must show sending progress on an older page');
    const delivered = [request(5001, 'next-turn', 'Continue from the latest result'), message(5002, 'next-turn', 'Reading the latest result.')];
    (latestReply as (result: AgentHistoryPageDto) => void)(latest(delivered));
    await until(() => host.textContent?.includes('Reading the latest result.') === true, 'The latest reply should show the new log');
    (olderReply as (result: AgentHistoryPageDto) => void)(read(1000));
    await settle();
    assert(host.textContent?.includes('Reading the latest result.') === true && !host.textContent?.includes('Message 1'),
      'A late older-history response must not replace the message and log just sent');
    assert(host.querySelectorAll('.agt-user__bubble').length === 1, 'The durable message must replace its pending bubble exactly once');
  }
  finally { dispose(); }
}

async function trailingNoticeKeepsRequestAtTop(): Promise<void> {
  const events = [request(1, 'earlier', 'Explain the build'),
    ...Array.from({ length: 30 }, (_, index) => message(index + 2, 'earlier', `Earlier commentary ${index}. The build output is being read.`)),
    request(32, 'latest', 'commit')];
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height: 560px; width: 760px; display: flex; flex-direction: column; font: 14px -apple-system, sans-serif';
  let notice = (_working: boolean): void => { throw new Error('The conversation is not mounted'); };
  function Conversation() {
    const [working, setWorking] = useState(true);
    notice = setWorking;
    const scroll = useConversationHistory(events, null, { before: null, after: null, loading: false, onLoad: () => {}, onFollow: () => {} });
    return <AgentConversation
      turns={projectTranscript(events)} durableTurns={{}} models={[]} now={Date.parse('2026-09-08T00:10:00Z')} scroll={scroll}
      connection="open" running activeTurn={null} waiting={false} activityChoice={{}} turnChoice={{ earlier: true, latest: true }}
      onToggleTurn={() => {}} onToggleActivity={() => {}} onDecide={() => {}}
      transcriptFooter={working && <div className="agt-barrier">2 subagents are still working — Send, queued messages, and Complete unlock when every agent and command finishes.</div>}>
      <div />
    </AgentConversation>;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    const turn = host.querySelector<HTMLElement>('.agt-turn.is-latest');
    assert(container !== null && turn !== null && host.querySelector('.agt-barrier') !== null, 'The conversation should render its latest turn and notice');
    const padding = Number.parseFloat(getComputedStyle(container).paddingTop);
    const offset = (): number => turn.getBoundingClientRect().top - container.getBoundingClientRect().top;
    assert(Math.abs(offset() - padding) <= 1, `A trailing notice must not push the request off the top: it sits ${offset()}px down`);
    flushSync(() => notice(false));
    await settle();
    assert(Math.abs(offset() - padding) <= 1, `A notice that disappears must not drop the request: it sits ${offset()}px down`);
  }
  finally { dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const check = async (name: string, test: () => Promise<void>): Promise<void> => {
    try { await test(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  };
  await check('keeps the current passage when scrolling continues during an older-page request', async () => {
    const view = fixture();
    try {
      await view.moveTo(300);
      assert(view.requests.join() === 'older', 'Scrolling near the top should request one older page');
      await view.update({ loading: true });
      await view.moveTo(180);
      const passage = view.passage();
      await view.update({ events: page(21, 50), loading: false, after: 'newer' });
      const movement = view.passage(passage.line).top - passage.top;
      assert(Math.abs(movement) <= 1, `The passage moved ${movement}px after prepend and eviction`);
    }
    finally { view.dispose(); }
  });
  await check('uses a surviving visible passage when a newer page replaces the top anchor', async () => {
    const view = fixture({ events: page(1, 30), before: null, after: 'newer' });
    try {
      await settle();
      const passage = view.passage('28-0');
      await view.wheel(1);
      assert(view.requests.join() === 'newer', 'Scrolling at the bottom should request a newer page');
      await view.update({ loading: true });
      // Reprojecting a split activity group can change its anchor while later messages survive.
      await view.update({ events: page(11, 40), loading: false, before: 'older', changedAnchor: 26 });
      const movement = view.passage(passage.line).top - passage.top;
      assert(Math.abs(movement) <= 1, `The surviving passage moved ${movement}px after newer-page eviction`);
    }
    finally { view.dispose(); }
  });
  await check('preserves completed history through repeated adjacent page changes', async () => {
    const view = fixture({ endedAt: '2026-09-08T01:00:00Z' });
    try {
      for (const first of [21, 11]) {
        await view.moveTo(250);
        await view.update({ loading: true });
        const passage = view.passage();
        await view.update({ events: page(first, first + 29), loading: false, after: 'newer' });
        const movement = view.passage(passage.line).top - passage.top;
        assert(Math.abs(movement) <= 1, `Completed history moved ${movement}px at page ${first}`);
      }
    }
    finally { view.dispose(); }
  });
  await check('stops following on a small upward scroll and only resumes at the bottom', async () => {
    const view = fixture({ before: null });
    try {
      await settle();
      const bottom = view.container.scrollTop;
      await view.moveTo(bottom - 20);
      assert(!view.following(), 'An upward wheel gesture should stop following');
      await view.update({ events: page(31, 60), endedAt: '2026-09-08T01:00:00Z' });
      assert(!view.following(), 'A layout update within 32px of the bottom must not resume following');
      assert(view.container.scrollTop === bottom - 20, 'Completion should retain the user scroll position');
      await view.moveTo(bottom - 10);
      assert(!view.following(), 'Scrolling down within 32px must not snap the remaining distance');
      await view.moveTo(bottom);
      assert(view.following(), 'Reaching the bottom should resume following');
    }
    finally { view.dispose(); }
  });
  await check('keeps the pending page anchor when the turn completes before the response', async () => {
    const view = fixture();
    try {
      await view.moveTo(300);
      await view.update({ endedAt: '2026-09-08T01:00:00Z' });
      await view.moveTo(180);
      const passage = view.passage();
      await view.update({ events: page(21, 50), loading: false, after: 'newer' });
      const movement = view.passage(passage.line).top - passage.top;
      assert(Math.abs(movement) <= 1, `Completion lost the pending anchor and moved the passage ${movement}px`);
    }
    finally { view.dispose(); }
  });
  await check('allows another page request after a failed read', async () => {
    const view = fixture();
    try {
      await view.moveTo(300);
      await view.update({ loading: false });
      await view.moveTo(180);
      assert(view.requests.join() === 'older,older', 'A failed page read must not block a subsequent scroll request');
      const passage = view.passage();
      await view.update({ events: page(21, 50), loading: false, after: 'newer' });
      const movement = view.passage(passage.line).top - passage.top;
      assert(Math.abs(movement) <= 1, `The retry used an old anchor and moved the passage ${movement}px`);
    }
    finally { view.dispose(); }
  });
  await check('does not resume following when reversing to the bottom during a page request', async () => {
    const view = fixture();
    try {
      const bottom = view.container.scrollTop;
      await view.moveTo(300);
      await view.moveTo(bottom);
      assert(!view.following(), 'Reversing direction must not resume following while an older page is pending');
      assert(view.requests.join() === 'older', 'Reversing direction must not start a competing page request');
    }
    finally { view.dispose(); }
  });
  await check('keeps production task messages when paging through thousands of command deltas', longCommandConversation);
  await check('puts a new request at the top with room for its reply', latestRequestAtTop);
  await check('keeps a new request at the top while a trailing notice comes and goes', trailingNoticeKeepsRequestAtTop);
  await check('shows a sent message at once and glides it to the top', sentMessageGlidesToTop);
  await check('shows Send immediately while an older history read is in flight', sendsWhileOlderHistoryLoads);
  await check('streams output to someone reading earlier without moving them', streamsWhileReadingEarlier);
  return results;
}
