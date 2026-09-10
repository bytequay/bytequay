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

import { useReducer, useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { useConversationHistory } from '../AgentConversation';
import { AgentTaskBody } from '../AgentTaskScreen';
import type { AgentHistoryPageDto, AgentTurnDto } from '../../types';
import type { AgentEventEnvelope } from '../events';
import { agentTaskReducer, historyAfter, historyBefore, initialAgentTaskState } from '../model';
import { previewTask } from './previewBridge';

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
    providerEventId: null, parentProviderThreadId: null, logicalAgentPath: '/', providerOccurredAt: null,
  },
  type: 'assistant_message',
  payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: `Message ${first + offset}` },
}));

const settle = async (): Promise<void> => {
  await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
};

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function fixture(initial: Partial<Frame> = {}) {
  let frame: Frame = { events: page(31, 60), before: 'older', after: null, loading: false, endedAt: null, ...initial };
  let following = true;
  const requests: string[] = [];
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
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
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

async function longCommandConversation(): Promise<void> {
  const events = page(1, 7010).map((event): AgentEventEnvelope => event.sequence <= 7000 ? {
    ...event, source: { ...event.source, providerItemId: 'long-command' }, type: 'command',
    payload: {
      state: 'PROGRESS', argv: ['mvn', 'verify'], output: `Building module ${event.sequence}: compilation complete.\n`,
      exitCode: null, truncated: false, cwd: '.', durationMs: null, actions: [],
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
  const host = document.createElement('div');
  host.style.cssText = 'height: 560px; width: 760px; font: 14px -apple-system, sans-serif';
  document.body.append(host);
  const root = createRoot(host);
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
      }}
      onSelectModel={() => {}} onBack={() => {}} onSend={() => {}} onCancel={() => {}} onComplete={() => {}}
      onDecide={() => {}} onCompose={() => {}} onStopServer={() => {}} onMarkFailed={() => {}}
    />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    const container = host.querySelector<HTMLElement>('.agt-transcript');
    assert(container !== null, 'The production task transcript is missing');
    assert(container.scrollHeight > container.clientHeight + 400, 'The production CSS must constrain and scroll the transcript');
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
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
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
  return results;
}
