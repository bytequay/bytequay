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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { AgentTurnDto, Bridge } from '../../../types';
import { parseAgentEventSequence } from '../../../agents/events';
import contracts from '../../../agents/fixtures/agent-contracts-v1.json';
import eventFixture from '../../../agents/fixtures/agent-events-v2.json';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto } from '../contracts';
import { reviewFixture, runningReviewFixture } from './review';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const previous = window.bridge;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const results: { name: string; error?: string }[] = [];
  const running = runningReviewFixture();
  const completed = reviewFixture({ messages: [], byteQuayTurnIds: ['turn-1'] });
  const inputEvents = parseAgentEventSequence(eventFixture.events).slice(0, 2).map(event => ({ ...event, turnId: 'turn-1' }));
  const endEvent = { ...inputEvents[1], eventId: 'completed', sequence: 3, type: 'lifecycle' as const,
    payload: { phase: 'TURN_COMPLETED' as const, message: 'Completed' } };
  const button = (label: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === label);
  const input = () => {
    const node = host.querySelector<HTMLTextAreaElement>('.agt-compose__input');
    assert(node !== null, 'The draft must stay editable');
    return node;
  };
  const scenario = async (name: string, test: () => Promise<void>) => {
    try { await test(); results.push({ name }); }
    catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { flushSync(() => root.render(null)); }
  };
  const mount = async (initial: AgentReviewDto, saved: () => AgentReviewDto, message: Bridge['messageAgentReview']) => {
    const bridge = {
      getModelSettings: async () => ({ providers: [], agents: [], apiKeys: [], keyProviders: [] }),
      getAgentReview: async () => saved(),
      getAgentTurn: async () => ({ ...contracts.turn, id: 'turn-1', taskId: running.task.id,
        state: saved().activeTurn === null ? 'COMPLETED' : 'RUNNING',
        endedAt: saved().activeTurn === null ? '2026-09-06T16:20:00Z' : null } as AgentTurnDto),
      readAgentReviewHistory: async () => ({ events: saved().activeTurn === null ? [...inputEvents, endEvent] : inputEvents,
        beforeCursor: null, afterCursor: null, latestSequence: saved().activeTurn === null ? 3 : 2 }),
      readAgentReviewApprovals: async () => [],
      // The connection stays open but never sends the terminal notification, reproducing the stale screen.
      subscribeAgentReview: async (id, _after, listener) => {
        listener({ kind: 'state', state: 'open', subscriptionId: 'review-stream', taskId: id });
        return () => {};
      },
      messageAgentReview: message,
    } satisfies Pick<Bridge, 'getModelSettings' | 'getAgentReview' | 'getAgentTurn' | 'readAgentReviewHistory'
      | 'readAgentReviewApprovals' | 'subscribeAgentReview' | 'messageAgentReview'>;
    Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
    function Surface() {
      const [review, setReview] = useState(initial);
      return <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={setReview}
        mode="conversation" focusFindingId={null} onNavigateToChanges={() => {}} />;
    }
    flushSync(() => root.render(<Surface />));
    await settle(); await settle();
    changeInput(input(), 'what are you doing?'); await settle();
  };
  try {
    for (const delivery of ['STEER', 'QUEUE'] as const) {
      await scenario(`A rejected ${delivery} refreshes the turn and preserves the unsent draft`, async () => {
        let saved = running;
        const sent: string[] = [];
        const error = delivery === 'STEER' ? 'The review agent is not running a turn to steer' : 'Task has no active turn to queue behind';
        await mount(running, () => saved, async (_id, _text, kind) => {
          sent.push(kind);
          if (kind !== 'TURN') throw new Error(error);
          return completed;
        });
        saved = completed;
        const control = button(delivery === 'STEER' ? 'Steer' : 'Run next');
        assert(control !== undefined && !control.disabled, 'The stale view must reproduce the enabled control');
        control.click(); await settle(); await settle();
        assert(host.textContent?.includes(error) === true, 'Keep the original delivery failure visible');
        assert(input().value === 'what are you doing?', 'Do not discard the rejected draft');
        assert(button('Steer') === undefined && button('Run next') === undefined && host.querySelector('.agt-live') === null,
          'The completed turn must stop thinking and restore normal Send');
        assert(sent.length === 1, 'Recovery must not send the draft automatically');
        const send = host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
        assert(send !== null && !send.disabled, 'The preserved draft must be sendable as a new turn');
        send.click(); await settle();
        assert(sent.join(',') === `${delivery},TURN`, 'Only the next deliberate Send may start another turn');
      });
    }
    await scenario('A missed completion update is recovered without a failed user action', async () => {
      let saved = running;
      let sends = 0;
      await mount(running, () => saved, async () => { sends++; return saved; });
      saved = completed;
      const deadline = Date.now() + 7000;
      while (button('Steer') !== undefined && Date.now() < deadline) await settle();
      assert(button('Steer') === undefined && host.querySelector('.agt-live') === null, 'The status check must retire the stale running turn');
      assert(input().value === 'what are you doing?' && sends === 0, 'Background reconciliation must preserve the draft without sending it');
    });
    await scenario('Run next waits for an active assignment turn', async () => {
      const between: AgentReviewDto = { ...running, activeTurn: null, task: { ...running.task, mutationClaim: null } };
      await mount(between, () => between, async () => { throw new Error('No message expected'); });
      assert(button('Steer')?.disabled === true && button('Run next')?.disabled === true,
        'An overall review can be running between turns, but neither turn-bound action can be used then');
    });
  }
  finally {
    flushSync(() => root.unmount()); host.remove();
    Object.defineProperty(window, 'bridge', { configurable: true, value: previous });
  }
  return results;
}
