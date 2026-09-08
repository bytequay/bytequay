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

import { useReducer } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { useConversationHistory } from '../AgentConversation';
import type { AgentEventEnvelope } from '../events';

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
  return results;
}
